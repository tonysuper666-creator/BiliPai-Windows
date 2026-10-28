package com.bilipai.desktop.hardware

import com.bilipai.desktop.nvidia.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Machine-local cache only; never part of account settings, upload or backup data. */
internal class DesktopHardwareCache(private val file:Path?) {
    private val json=Json { ignoreUnknownKeys=true }
    @Synchronized fun read():List<HardwareLearning> = try {
        if(file==null || !safe(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>512*1024) emptyList()
        else json.decodeFromString<List<HardwareLearning>>(Files.readString(file)).takeLast(8)
    } catch(_:Exception){emptyList()}
    @Synchronized fun put(value:HardwareLearning) {
        if(file==null)return
        require(value.fingerprint.matches(Regex("[a-f0-9]{64}")))
        val entries=(read().filterNot {it.fingerprint==value.fingerprint}+value.copy(decodes=value.decodes.takeLast(24),enhancements=value.enhancements.takeLast(64))).takeLast(8)
        val encoded=json.encodeToString(entries);require(encoded.length<=512*1024)
        check(safe(file));Files.createDirectories(file.toAbsolutePath().parent);check(safe(file))
        val temporary=Files.createTempFile(file.toAbsolutePath().parent,"hardware-", ".tmp")
        try {
            java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE).use {channel ->
                val bytes=java.nio.ByteBuffer.wrap(encoded.toByteArray(Charsets.UTF_8))
                while(bytes.hasRemaining())channel.write(bytes)
                channel.force(true)
            }
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
            catch(_:AtomicMoveNotSupportedException){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING)}
        } finally {Files.deleteIfExists(temporary)}
    }
    private fun safe(path:Path):Boolean {
        var p:Path?=path.toAbsolutePath()
        while(p!=null) {
            if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)) {
                val attr=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                if(attr.isSymbolicLink || attr.isOther)return false
            }
            p=p.parent
        }
        return true
    }
}
internal data class HardwareOptimizationState(
    val snapshot:HardwareSnapshot=HardwareSnapshot(), val learning:HardwareLearning?=null,
    val policy:HardwarePlaybackPolicy=decideHardwarePolicy(snapshot,learning), val busy:Boolean=false,
)

/** One retained owner, one bounded worker. The settings page does not own its lifetime. */
internal class DesktopHardwareOptimizer(
    private val probe:()->HardwareSnapshot,
    private val selfTest:(HardwareSnapshot,()->Boolean)->List<DecodeMeasurement>,
    private val cache:DesktopHardwareCache,
    private val testAllowed:()->Boolean={true},
    private val windowReporter:(Long)->Unit={},
) : AutoCloseable {
    private val worker=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"BiliPai-hardware-optimizer").apply{isDaemon=true} }
    private val mutable=MutableStateFlow(HardwareOptimizationState())
    val state:StateFlow<HardwareOptimizationState> = mutable.asStateFlow()
    @Volatile private var closed=false
    @Volatile private var started=false
    @Volatile private var renderer:String?=null
    @Volatile private var playbackActive=false
    private val refreshQueued=AtomicBoolean()
    @Synchronized fun start() {if(!started && !closed){started=true;worker.scheduleWithFixedDelay({runCatching {refreshOnce()}},0,30,TimeUnit.SECONDS)}}
    /** The native owner can signal display/driver changes without blocking its UI thread. */
    fun invalidate() {
        if(!closed && refreshQueued.compareAndSet(false,true))runCatching {worker.execute {
            try {runCatching {refreshOnce()}} finally {refreshQueued.set(false)}
        }}.onFailure {refreshQueued.set(false)}
    }
    internal fun refreshOnce() {
        if(closed)return
        mutable.value=mutable.value.copy(busy=true)
        try {
            val snapshot=probe();val key=snapshot.fingerprint()
            var learning=mutable.value.learning?.takeIf {it.fingerprint==key} ?: cache.read().firstOrNull {it.fingerprint==key} ?: HardwareLearning(key)
            fun allowed()=!closed && !playbackActive && testAllowed()
            if(snapshot.complete && !learning.selfTestComplete && allowed()) {
                fun pending(s:HardwareSnapshot,previous:List<DecodeMeasurement>)=s.copy(adapters=s.adapters.map {a->a.copy(decoders=a.decoders.filter {cap->
                    cap.bitDepth==8 && cap.support==HardwareSupport.YES && previous.none {m->(m.adapterLuid==a.luid || a.stableId!=null && m.adapterIdentity==a.stableId) && m.codec==cap.codec &&
                        (m.passed || m.width==cap.width && m.height==cap.height && m.reason!="deferred")}
                })})
                val outcome=runCatching {selfTest(pending(snapshot,learning.decodes),::allowed)}
                val measured=outcome.getOrElse {emptyList()}
                val deferred=!allowed() || measured.any {it.reason=="deferred"}
                val combined=learning.decodes.filterNot {it.reason=="deferred"}+measured
                val stillPending=pending(snapshot,combined).adapters.any {a->a.physical && a.decoders.isNotEmpty()}
                val complete=!deferred && (!stillPending || outcome.isFailure || measured.isEmpty())
                learning=learning.copy(decodes=combined,selfTestComplete=complete,selfTestReason=when {
                    deferred->"deferred";outcome.isFailure || measured.isEmpty()->"unavailable";!complete->"budget_exhausted";else->"completed" })
                runCatching {cache.put(learning)}
            }
            synchronized(this) {
                // Overrides/playback evidence can arrive while native IO is in flight.
                val current=mutable.value.learning?.takeIf {it.fingerprint==key}
                if(current!=null)learning=learning.copy(overrides=current.overrides,enhancements=current.enhancements)
                if(!closed)mutable.value=HardwareOptimizationState(snapshot,learning,decideHardwarePolicy(snapshot,learning,renderer))
            }
        } finally {if(!closed)mutable.value=mutable.value.copy(busy=false)}
    }
    /** TON-1006 calls this before opening media, and clears it when its session retires. */
    fun setPlaybackActive(active:Boolean) {playbackActive=active}
    /** The real native owner should report its HWND, rather than relying on own-window discovery. */
    fun reportWindow(windowId:Long) {windowReporter(windowId);invalidate()}
    @Synchronized fun reportRenderer(luid:String?):Boolean {
        if(closed || luid!=null && mutable.value.snapshot.adapters.none {it.luid==luid})return false
        renderer=luid
        mutable.value=mutable.value.copy(policy=decideHardwarePolicy(mutable.value.snapshot,mutable.value.learning,renderer))
        return true
    }
    /** Called ONLY with real presentation/AdaptiveGuard measurements by TON-1006's owner.
     * Stale fingerprint or unknown renderer cannot poison another device's cache. */
    @Synchronized fun reportPlayback(fingerprint:String, measurement:EnhancementMeasurement):Boolean {
        if(closed || fingerprint!=mutable.value.snapshot.fingerprint() || measurement.quality !in 1..4 ||
            mutable.value.snapshot.adapters.none {it.luid==measurement.adapterLuid})return false
        renderer=measurement.adapterLuid
        val old=mutable.value.learning ?: HardwareLearning(fingerprint)
        fun overloaded(m:EnhancementMeasurement)=m.sustainedMillis>=10_000 && m.presentedFrames>0 &&
            m.droppedFrames.toDouble()/m.presentedFrames>=0.06
        val previous=old.enhancements.lastOrNull {it.adapterLuid==measurement.adapterLuid && it.enhancement==measurement.enhancement && it.quality==measurement.quality}
        val sample=measurement.copy(failed=measurement.failed || (overloaded(measurement) && previous?.let(::overloaded)==true),
            adapterIdentity=mutable.value.snapshot.adapters.first {it.luid==measurement.adapterLuid}.stableId)
        // Keep one failed result per rung indefinitely for this fingerprint; successful playback
        // cannot erase it. The last nonfailed sample also retains the two-window overload signal.
        val records=old.enhancements+sample
        val remembered=records.filter {it.failed}.distinctBy {listOf(it.adapterIdentity ?: it.adapterLuid,it.enhancement.name,it.quality.toString(),it.hdr.toString())}
        val recent=records.filterNot {it.failed}.takeLast((64-remembered.size).coerceAtLeast(0))
        val learning=old.copy(enhancements=(remembered+recent).take(64))
        runCatching {cache.put(learning)}
        mutable.value=mutable.value.copy(learning=learning,policy=decideHardwarePolicy(mutable.value.snapshot,learning,renderer))
        return true
    }
    @Synchronized fun setOverrides(overrides:HardwareOverrides) {
        if(closed)return
        val old=mutable.value.learning ?: HardwareLearning(mutable.value.snapshot.fingerprint())
        val learning=old.copy(overrides=overrides)
        runCatching {cache.put(learning)}
        mutable.value=mutable.value.copy(learning=learning,policy=decideHardwarePolicy(mutable.value.snapshot,learning,renderer))
    }
    fun restoreAutomatic()=setOverrides(HardwareOverrides()) // Keep remembered failures.
    fun diagnosticJson():String=hardwareDiagnosticJson(state.value)
    override fun close() {closed=true;worker.shutdownNow()}

    companion object {
        fun windows(mpvLibrary:()->Path):DesktopHardwareOptimizer {
          // Same machine-local GPU directory as DesktopNvidiaHealthStore, outside account backups.
          val file=System.getenv("LOCALAPPDATA")?.takeIf {it.isNotBlank()}?.let {base->
              runCatching {Path.of(base,HEALTH_DIRECTORY_NAME,HEALTH_SUBDIRECTORY_NAME,"hardware-optimization-v3.json")}.getOrNull()
          }
          val window=java.util.concurrent.atomic.AtomicLong()
          return DesktopHardwareOptimizer(
            DesktopHardwareProbe {window.get().takeIf {it!=0L} ?: WindowsHardwareFacts.ownWindow()}::probe,DesktopHardwareSelfTest(mpvLibrary)::run,
            DesktopHardwareCache(file),
            testAllowed={
                val paused=System.getProperty("bilipai.hardware.selftest.pause")?.let {Files.exists(Path.of(it))}==true
                val foreground=DesktopWindowsForegroundBusy.snapshot(ProcessHandle.current().pid().toInt())
                !paused && (foreground==null || !desktopNvidiaGameLike(foreground))
            },
            windowReporter=window::set,
          )
        }
    }
}

/** No raw endpoint/device paths, account data, window titles, URLs or user-directory fields. */
internal fun hardwareDiagnosticJson(state:HardwareOptimizationState):String = buildJsonObject {
    put("schema",3);put("complete",state.snapshot.complete);put("fingerprint",state.snapshot.fingerprint())
    put("cpu",state.snapshot.cpu);put("logicalProcessors",state.snapshot.logicalProcessors);put("memoryBytes",state.snapshot.memoryBytes)
    put("adapters",JsonArray(state.snapshot.adapters.map { a -> buildJsonObject {
        put("vendor",a.vendor.name);put("name",a.name);put("luid",a.luid);put("vendorId",a.vendorId);put("deviceId",a.deviceId)
        put("driver",a.driver);put("dedicatedBytes",a.dedicatedBytes);put("physical",a.physical)
        put("decoders",Json.encodeToJsonElement(a.decoders))
    } }))
    put("displays",JsonArray(state.snapshot.displays.map { d -> buildJsonObject {
        put("adapterLuid",d.adapterLuid);put("target",d.target);put("width",d.width);put("height",d.height)
        put("refreshHz",d.refreshHz);put("scalePercent",d.scalePercent);put("hdrSupported",d.hdrSupported.name);put("hdrEnabled",d.hdrEnabled.name)
        put("windowOnDisplay",d.windowOnDisplay)
    } }))
    put("audio",Json.encodeToJsonElement(state.snapshot.audio))
    put("decision",Json.encodeToJsonElement(state.policy))
    state.learning?.let {put("selfTest",Json.encodeToJsonElement(it))}
    put("errors",Json.encodeToJsonElement(state.snapshot.errors))
}.toString()
