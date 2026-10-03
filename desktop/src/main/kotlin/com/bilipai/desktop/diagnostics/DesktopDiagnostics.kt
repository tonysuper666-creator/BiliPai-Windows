package com.bilipai.desktop.diagnostics

import com.android.purebilibili.core.util.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.update.UpdateStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** Windows-only privacy additions; the original Android sanitizer runs afterwards. */
internal fun sanitizeDesktopDiagnosticText(value:String):String = sanitizeLogMessage(
    value.replace(Regex("(?i)\\b(?:https?|rtmps?|rtsp)://[^\\s<>\\\"]+"), "[network-address]")
        .replace(Regex("(?i)\\b[A-Z]:[\\\\/](?:Users|Documents and Settings)[\\\\/][^\\\\/\\r\\n]+"), "[user-home]")
        .replace(Regex("(?i)(auth_key|w_rid|x-bili-ticket|x-api-key)[=:]\\s*[^&\\s,}]+"), "$1=***")
        .replace(Regex("(?i)\"(password|access_token|refresh_token|authorization|cookie)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"***\"")
)

/** One process-owned serial consumer. No account, network client, or upload dependency. */
internal class DesktopDiagnostics(
    private val store:DesktopPluginStore,
    private val version:String,
    private val clock:()->Long = System::currentTimeMillis,
    private val writer:ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task,"BiliPai-diagnostic-writer").apply {isDaemon=true} },
) : AutoCloseable {
    private val gate=Any()
    private var closing=false
    private val root=store.root.toAbsolutePath().normalize()
    private val settings=DesktopDiagnosticSettings(store)
    private val mutableEnhanced=MutableStateFlow(settings.getEnhancedDiagnosticLoggingEnabledSync())
    val enhancedEnabled:StateFlow<Boolean> = mutableEnhanced.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error:StateFlow<String?> = mutableError.asStateFlow()
    private val collector=DesktopDiagnosticCollector(clock) {entry,basic ->
        val isBasic=basic || entry.level=="W" || entry.level=="E"
        val file=if(isBasic)resolveBasicLogFile(root.toFile()) else resolveRuntimeLogFile(root.toFile())
        ensurePrivateFile(file.toPath())
        appendRollingDiagnosticLog(file,sanitizeDesktopDiagnosticText(entry.format())+"\n",if(isBasic)64*1024 else 256*1024)
    }
    private val coreApiErrorPolicy = DesktopOriginalCoreApiErrorPolicy(
        enabled = { mutableEnhanced.value },
        emit = { message -> add("E", "ApiError", message) },
        clock = clock,
    )
    fun reportApiError(endpoint:String, httpCode:Int, errorMessage:String):Boolean = synchronized(gate) {
        if(closing)return false
        val safeEndpoint = sanitizeDesktopDiagnosticText(normalizeApiErrorEndpoint(endpoint))
        val safeMessage = sanitizeDesktopDiagnosticText(errorMessage)
        submit { coreApiErrorPolicy.reportApiError(safeEndpoint, httpCode, safeMessage) }
        true
    }
    init {
        // Synchronous read of the same atomic settings key precedes the first accepted log.
        recordStartupStage("diagnostics_initialized")
        if(mutableEnhanced.value) submit {recordSessionStart()}
    }
    private fun <T> submit(block:()->T):CompletableFuture<T> = synchronized(gate) {
        check(!closing){"诊断记录器已经停止"}
        val result=CompletableFuture<T>()
        writer.execute {
            try {result.complete(block())}
            catch(failure:Exception) {
                // Error state never contains raw exception/paths; no recursive log on writer failure.
                mutableError.value="诊断文件操作失败（${failure.javaClass.simpleName}）"
                result.completeExceptionally(failure)
            }
        }
        result
    }
    private suspend fun <T> await(future:CompletableFuture<T>):T = withContext(Dispatchers.IO) {
        try {future.get()} catch(failure:ExecutionException){throw failure.cause ?: failure}
    }
    private fun add(level:String,tag:String,message:String,basic:Boolean=false) {
        collector.add(level,sanitizeDesktopDiagnosticText(tag),sanitizeDesktopDiagnosticText(message),true,basic)
    }
    private fun recordSessionStart() {
        add("I","Diagnostics","增强诊断已开启；仅保存在应用私有目录，导出前会再次脱敏，滚动上限=256KB")
        add("I","Diagnostics","app=$version, system=${System.getProperty("os.name")}/${System.getProperty("os.version")}, arch=${System.getProperty("os.arch")}, java=${System.getProperty("java.version")}")
    }
    fun record(level:String,tag:String,message:String,cause:Throwable?=null):Boolean = synchronized(gate) {
        if(closing)return false
        // Make raw messages task-local, never publish to JUL/console or the in-memory buffer.
        val safe=sanitizeDesktopDiagnosticText(if(cause==null)message else "$message\n${cause.stackTraceToString()}")
        val safeTag=sanitizeDesktopDiagnosticText(tag)
        submit {
            if(shouldCaptureRuntimeLogEntry(level,mutableEnhanced.value))add(level,safeTag,safe)
        }
        true
    }
    fun recordStartupStage(stage:String):Boolean = synchronized(gate) {
        if(closing)return false
        require(stage.matches(Regex("[a-z0-9_]{1,64}"))) {"启动诊断仅接受固定阶段名"}
        submit {add("I","StartupDiagnostics","stage=$stage, app=$version",true)}
        true
    }
    /** Explicit original consent on the same serial writer. All three original
     * keys publish atomically; capture the caller Job before entering the actor. */
    suspend fun setOriginalCrashConsent(enabled:Boolean,stillOwned:()->Boolean,commit:((()->Unit)->Boolean)) {
        val caller=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        await(submit {
            caller?.ensureActive()
            if(!stillOwned())throw kotlinx.coroutines.CancellationException("诊断授权页面已退役")
            if(!commit {
                caller?.ensureActive()
                if(!stillOwned())throw kotlinx.coroutines.CancellationException("诊断授权页面已退役")
                store.update("settings",mapOf(
                    "crash_tracking_enabled" to kotlinx.serialization.json.JsonPrimitive(enabled),
                    "crash_tracking_consent_shown" to kotlinx.serialization.json.JsonPrimitive(true),
                    "enhanced_diagnostic_logging_enabled" to kotlinx.serialization.json.JsonPrimitive(enabled)))
                mutableEnhanced.value=enabled
                if(enabled)recordSessionStart()
                else {collector.clearRuntimeDiagnostics();deletePrivate(resolveRuntimeLogFile(root.toFile()).toPath())}
                mutableError.value=null
            })throw kotlinx.coroutines.CancellationException("诊断授权写入已退役")
            Unit
        })
    }

    suspend fun setEnhancedEnabled(enabled:Boolean) = await(submit {
        settings.setEnhancedDiagnosticLoggingEnabled(enabled)
        mutableEnhanced.value=enabled
        if(enabled)recordSessionStart()
        else {
            collector.clearRuntimeDiagnostics()
            deletePrivate(resolveRuntimeLogFile(root.toFile()).toPath())
        }
        mutableError.value=null
    })
    suspend fun flush():Unit = await(submit {Unit})
    suspend fun entries():List<DesktopDiagnosticCollector.LogEntry> = await(submit {collector.getEntries()})
    suspend fun artifactSize():Long = await(submit {privatePaths().sumOf {path ->
        ensurePrivateFile(path);if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.size(path) else 0L
    }})
    suspend fun viewLocal():String = await(submit {buildExport()})
    private val nativeShareCache=DesktopCrashShareCache(root)
    private var nativeShareClearHook:(suspend (suspend ()->Unit)->Unit)?=null
    internal fun installNativeShareClearHook(hook:suspend (suspend ()->Unit)->Unit)=synchronized(gate) {
        check(!closing && nativeShareClearHook==null){"诊断分享已经绑定"};nativeShareClearHook=hook
    }
    internal suspend fun prepareNativeCrashShareLease():DesktopCrashShareLease? = await(submit {
        val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath()
        val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath()
        ensurePrivateFile(snapshot);ensurePrivateFile(marker)
        if(!hasPendingCrashSnapshot(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS),
                Files.isRegularFile(snapshot,LinkOption.NOFOLLOW_LINKS)))null
        else nativeShareCache.create(readBounded(snapshot,256*1024))
    })
    internal suspend fun markNativeCrashShareMayExpose(lease:DesktopCrashShareLease):Unit =
        await(submit {nativeShareCache.mayExpose(lease)})
    internal suspend fun retireNativeCrashShareLease(lease:DesktopCrashShareLease,safeToDelete:Boolean):Unit =
        await(submit {nativeShareCache.retire(lease,safeToDelete)})
    /** The original explicit player-report export, on THIS sole serial writer.
     * Temporary IO stays outside Root admission. Only no-clobber publication is
     * admitted by the existing Store -> entry gate; retirement cannot publish.
     * Original synchronous UI API returns only an actually published path. */
    internal fun exportOriginalPlayerReport(content:String, stillOwned:()->Boolean,
        commitIfCurrent:((()->Unit)->Boolean)):String? {
        if(!stillOwned())throw kotlinx.coroutines.CancellationException("Player diagnostic owner retired")
        val open=java.util.concurrent.atomic.AtomicBoolean(true)
        val future=submit {
            fun checkOwned() {
                if(!open.get() || !stillOwned())throw kotlinx.coroutines.CancellationException("Player diagnostic owner retired")
            }
            checkOwned()
            val directory=root.resolve("logs")
            val target=directory.resolve(resolvePlayerDiagnosticExportFileName(clock()))
            val temporary=directory.resolve(".player-report-${java.util.UUID.randomUUID()}.tmp")
            ensurePrivateFile(target);ensurePrivateFile(temporary)
            try {
                val safe=utf8Tail(sanitizeDesktopDiagnosticText(content).toByteArray(Charsets.UTF_8),512*1024)
                FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { channel ->
                    val buffer=ByteBuffer.wrap(safe)
                    while(buffer.hasRemaining()){checkOwned();channel.write(buffer)}
                    channel.force(true)
                }
                checkOwned()
                var published=false
                if(!commitIfCurrent {
                    checkOwned()
                    // A hard-link publication is atomic and has CREATE_NEW semantics.
                    Files.createLink(target,temporary);published=true
                } || !published)throw kotlinx.coroutines.CancellationException("Player diagnostic publication retired")
                target.toString()
            } finally {Files.deleteIfExists(temporary)}
        }
        return try {future.get(5,TimeUnit.SECONDS)}
        catch(cancelled:java.util.concurrent.CancellationException){throw cancelled}
        catch(failure:ExecutionException) {
            if(failure.cause is kotlinx.coroutines.CancellationException)throw failure.cause!!
            null
        } catch(_:Exception){open.set(false);null}
    }

    /** Explicit caller-selected local file only. CREATE_NEW never overwrites another artifact. */
    suspend fun exportTo(selectedPath:Path):Path = await(submit {
        val target=selectedPath.toAbsolutePath().normalize()
        require(!target.startsWith(root.resolve("logs"))) {"导出目标不能覆盖应用日志"}
        Files.write(target,buildExport().toByteArray(Charsets.UTF_8),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
        target
    })
    internal suspend fun cacheBytes():Long = await(submit {
        privatePaths().sumOf { path -> ensurePrivateFile(path);if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.size(path) else 0L }
    })
    suspend fun clearAll():Unit {
        val action:suspend ()->Unit={ await(submit {
            nativeShareCache.clearExplicit()
            collector.clear();privatePaths().forEach(::deletePrivate);mutableError.value=null
        }) }
        val hook=synchronized(gate){check(!closing){"诊断记录器已经停止"};nativeShareClearHook}
        if(hook==null)action() else hook(action)
    }
    suspend fun clearCrashPrompt():Unit = await(submit {deletePrivate(resolveCrashSnapshotMarkerFile(root.toFile()).toPath())})
    suspend fun hasPendingCrash():Boolean = await(submit {
        val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath();val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath()
        ensurePrivateFile(snapshot);ensurePrivateFile(marker)
        hasPendingCrashSnapshot(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS),Files.isRegularFile(snapshot,LinkOption.NOFOLLOW_LINKS))
    })
    private fun privatePaths():List<Path> = listOf(resolveBasicLogFile(root.toFile()),resolveRuntimeLogFile(root.toFile()),
        resolveCrashSnapshotFile(root.toFile()),resolveCrashSnapshotMarkerFile(root.toFile())).map {it.toPath()}
    private fun ensurePrivateFile(path:Path) {
        val dir=root.resolve("logs")
        require(path.toAbsolutePath().normalize().parent==dir) {"诊断文件越界"}
        // Reuse the actual Windows reparse/ancestor guard before creating a missing directory.
        var existing=dir
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=requireNotNull(existing.parent)
        UpdateStorage.existingPathWithoutLinks(existing)
        Files.createDirectories(dir)
        val verifiedDir=UpdateStorage.existingPathWithoutLinks(dir)
        require(Files.isDirectory(verifiedDir,LinkOption.NOFOLLOW_LINKS)) {"诊断目录必须是普通目录"}
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            val verifiedFile=UpdateStorage.existingPathWithoutLinks(path)
            require(Files.isRegularFile(verifiedFile,LinkOption.NOFOLLOW_LINKS)) {"诊断文件必须是普通文件"}
        }
    }
    private fun deletePrivate(path:Path) {ensurePrivateFile(path);Files.deleteIfExists(path)}
    private fun readBounded(path:Path,maxBytes:Int):String {
        ensurePrivateFile(path)
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return ""
        return FileChannel.open(path,StandardOpenOption.READ).use {channel ->
            val count=min(channel.size(),maxBytes.toLong()).toInt();val buffer=ByteBuffer.allocate(count)
            channel.position((channel.size()-count).coerceAtLeast(0));while(buffer.hasRemaining()&&channel.read(buffer)>=0){}
            utf8Tail(buffer.array().copyOf(buffer.position()),count).toString(Charsets.UTF_8)
        }
    }
    private fun buildExport():String {
        val lines=(readBounded(resolveBasicLogFile(root.toFile()).toPath(),64*1024).lines()+
            readBounded(resolveRuntimeLogFile(root.toFile()).toPath(),256*1024).lines()+collector.getEntries().map {it.format()})
            .filter {it.isNotBlank()}.distinct()
        val crash=readBounded(resolveCrashSnapshotFile(root.toFile()).toPath(),256*1024)
        if(!hasExportableDiagnostics(lines.size,crash.isNotBlank(),0,0))return "暂无日志记录"
        val header="BiliPai 应用日志导出\n应用版本: $version\n系统: ${System.getProperty("os.name")} ${System.getProperty("os.version")}\n基础诊断: 64KB；增强诊断: ${if(mutableEnhanced.value)"已开启" else "未开启"}（256KB）\n隐私说明: 本地脱敏日志，仅在用户主动选择文件时导出；不自动上传。\n"
        val body=sanitizeDesktopDiagnosticText(lines.joinToString("\n")+if(crash.isBlank())"" else "\n----- 最近一次崩溃快照 -----\n$crash")
        val safeHeader=sanitizeDesktopDiagnosticText(header).toByteArray(Charsets.UTF_8)
        return (safeHeader+utf8Tail(body.toByteArray(Charsets.UTF_8),512*1024-safeHeader.size)).toString(Charsets.UTF_8)
    }
    /** Synchronous only for an actual uncaught handler. Queues behind writes and retains evidence. */
    fun persistLocalCrash(throwable:Throwable):Boolean = synchronized(gate) {
        if(closing)return false
        // Original local-snapshot policy is true independently of Firebase consent.
        if(!shouldPersistLocalCrashSnapshot(false))return false
        submit {
            val content=buildCrashSnapshotContent(throwable,collector.getEntries(),clock(),version,0,
                "Windows","JVM",System.getProperty("os.version"),Runtime.version().feature(),"desktop","unknown")
            val snapshot=resolveCrashSnapshotFile(root.toFile()).toPath();ensurePrivateFile(snapshot)
            Files.write(snapshot,utf8Tail(sanitizeDesktopDiagnosticText(content).toByteArray(Charsets.UTF_8),256*1024))
            val marker=resolveCrashSnapshotMarkerFile(root.toFile()).toPath();ensurePrivateFile(marker)
            Files.writeString(marker,clock().toString())
        }.get(5,TimeUnit.SECONDS)
        true
    }
    /** Call before Store.freezeWrites / restore. Accepted queue drains; old facade rejects new work. */
    suspend fun shutdownForRestore() = withContext(Dispatchers.IO) {close()}
    override fun close() {
        synchronized(gate){if(!closing){closing=true;writer.shutdown()}}
        DesktopDiagnosticsBridge.retire(this)
        check(writer.awaitTermination(10,TimeUnit.SECONDS)) {"诊断写入尚未排空"}
    }
    companion object {
        internal fun utf8Tail(bytes:ByteArray,maxBytes:Int):ByteArray {
            require(maxBytes>0);var start=(bytes.size-maxBytes).coerceAtLeast(0)
            while(start<bytes.size&&(bytes[start].toInt() and 0xC0)==0x80)start++
            return bytes.copyOfRange(start,bytes.size)
        }
    }
}

/** Replaces the two existing logging bridges; it never installs a global JUL handler. */
internal object DesktopDiagnosticsBridge {
    private val active=AtomicReference<DesktopDiagnostics?>()
    fun install(consumer:DesktopDiagnostics) {check(active.compareAndSet(null,consumer)){"诊断记录器已安装"}}
    fun retire(consumer:DesktopDiagnostics) {active.compareAndSet(consumer,null)}
    fun reportApiError(endpoint:String,httpCode:Int,errorMessage:String) {
        active.get()?.reportApiError(endpoint,httpCode,errorMessage)
    }
    fun record(level:String,tag:String,message:String,cause:Throwable?=null):Int {
        active.get()?.record(level,tag,message,cause)
        return 0
    }
}
