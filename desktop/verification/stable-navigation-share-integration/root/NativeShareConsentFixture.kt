package com.bilipai.desktop.ui

import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

private var assertions=0
private fun expect(value:Boolean) { check(value);assertions++ }

fun main(args:Array<String>)=runBlocking {
    val product=Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    for(type in listOf(DesktopNativeTextShare::class.java,DesktopDiagnostics::class.java,
        DesktopOriginalCrashConsentBinding::class.java,DesktopNativeDiagnosticShareAssetHash::class.java,
        DesktopPluginStore::class.java)) {
        check(type.protectionDomain.codeSource.location==product)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }
    val root=Path.of(args[0]);Files.createDirectories(root)
    val image=Path.of(args[2])
    val native=DesktopNativeTextShare({Path.of(args[1])},DesktopNativeDiagnosticShareAssetHash.sha256,{null})
    var retired=0
    try {
        expect(native.probeMediaAvailable())
        expect(!native.shareMedia(image,"synthetic local card","https://www.bilibili.com/video/BV-FIXTURE",{true}) {safe->expect(safe);retired++})
        expect(retired==1)
        var cleared=false
        native.clearVideoShareFiles { cleared=true }
        expect(cleared && native.probeMediaAvailable())
        expect(!native.shareMedia(image,"after clear","fixture",{true}) {safe->expect(safe);retired++})
        expect(retired==2)
        expect(!native.share("original text","fixture",{true}))
        expect(!native.shareMedia(image,"retired caller","fixture",{false}) {safe->expect(safe);retired++})
        expect(retired==3)
        val failure=runCatching {native.shareMedia(root.resolve("missing.jpg"),"missing","fixture",{true}) {safe->expect(safe);retired++}}.exceptionOrNull()
        expect(failure!=null && retired==4)
    } finally {native.shutdown()}
    expect(!native.probeMediaAvailable())
    val bad=DesktopNativeTextShare({Path.of(args[1])},"0".repeat(64),{null})
    expect(!bad.probeMediaAvailable());bad.shutdown()
    val nativeAssertions=assertions
    println("GROUP actual fresh DLL/installed actor prepares then rejects unavailable HWND; real retirement, explicit clear preserves reuse, stale caller and digest rejection")

    val store=DesktopPluginStore(root.resolve("consent-store"))
    store.update("settings",mapOf("sentinel" to JsonPrimitive("preserve")))
    val diagnostics=DesktopDiagnostics(store,"fixture")
    val owned=AtomicBoolean(true);val gate=Any()
    val binding=DesktopOriginalCrashConsentBinding(diagnostics,owned::get,
        {action->synchronized(gate){if(owned.get()){action();true}else false}},diagnostics::setOriginalCrashConsent)
    fun value(key:String)=store.preferences("settings")[key]?.jsonPrimitive?.booleanOrNull
    try {
        expect(!binding.enhancedEnabled)
        binding.saveChoice(true)
        expect(binding.enhancedEnabled && value("crash_tracking_enabled")==true && value("crash_tracking_consent_shown")==true && value("enhanced_diagnostic_logging_enabled")==true)
        expect(store.preferences("settings")["sentinel"]?.jsonPrimitive?.content=="preserve")
        expect(Files.readString(store.root.resolve("plugin-settings.json")).contains("crash_tracking_consent_shown"))
        binding.saveChoice(false)
        expect(!binding.enhancedEnabled && value("crash_tracking_enabled")==false && value("crash_tracking_consent_shown")==true && value("enhanced_diagnostic_logging_enabled")==false)
        diagnostics.record("E","Fixture","synthetic basic error retained")
        diagnostics.flush()
        expect(diagnostics.viewLocal().contains("synthetic basic error retained"))
        owned.set(false)
        expect(runCatching {binding.saveChoice(true)}.exceptionOrNull() is CancellationException)
        expect(!binding.enhancedEnabled && value("crash_tracking_enabled")==false)
    } finally {diagnostics.close()}
    println("GROUP same real serial diagnostics actor atomically writes original global keys; disable retains basic local error logs; retired choice cannot write")

    val writer=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,LinkedBlockingQueue<Runnable>())
    val blocked=CountDownLatch(1);val release=CountDownLatch(1)
    writer.execute {blocked.countDown();check(release.await(5,TimeUnit.SECONDS))}
    check(blocked.await(1,TimeUnit.SECONDS))
    val queuedStore=DesktopPluginStore(root.resolve("cancelled-consent-store"))
    val queued=DesktopDiagnostics(queuedStore,"fixture",writer=writer)
    val call=launch(Dispatchers.Default) {queued.setOriginalCrashConsent(true,{true},{action->action();true})}
    try {
        withTimeout(2_000){while(writer.queue.size<2)yield()}
        call.cancel();release.countDown();withTimeout(3_000){call.join()}
        queued.flush()
        expect(!queued.enhancedEnabled.value && queuedStore.preferences("settings")["crash_tracking_enabled"]==null && queuedStore.preferences("settings")["crash_tracking_consent_shown"]==null)
    } finally {release.countDown();call.cancel();queued.close()}
    println("GROUP captured caller Job rejects queued consent before any persisted choice after cancellation")
    println("RESULT actors assertions=$assertions native=$nativeAssertions consent=${assertions-nativeAssertions} groups=3; actual42 no HWND/ShareUI/receiver/network/account")
}
