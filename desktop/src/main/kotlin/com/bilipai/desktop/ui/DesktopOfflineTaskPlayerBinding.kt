package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.download.DownloadTaskClickTarget
import com.android.purebilibili.feature.download.resolveDownloadTaskClickTarget
import com.bilipai.desktop.danmaku.DanmakuDocument
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A navigation consumer of the existing offline memory, not a second player or task store. */
class DesktopOfflineTaskPlayerBinding(
    val manager: DesktopDownloadManager,
    val retained: DesktopRetainedMedia,
    val overlay: DanmakuOverlay?,
    private val entryScope: CoroutineScope,
    private val capturedEpoch: Long,
    private val currentEpoch: () -> Long,
    private val stillOwned: () -> Boolean,
    private val withOwnedAdmission: ((() -> Unit) -> Boolean),
    private val networkAvailable: () -> Boolean,
    private val playerError: () -> String?,
) : AutoCloseable {
    val memory get() = retained.offline
    val player: MpvPlayer? get() = retained.player
    var opening by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val generation = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var acceptedVersion: Long? = null
    @Volatile private var acceptedTaskId: String? = null
    private val nativePublication = AtomicReference<DesktopOfflineNativeSourcePublication?>()
    private var request: Job? = null
    private val completion = entryScope.coroutineContext[Job]?.invokeOnCompletion { close() }
    init { require(entryScope.coroutineContext[Job] != null) { "Offline task requires an owned entry Job" } }

    fun isOwned(): Boolean = !closed && entryScope.coroutineContext[Job]?.isActive == true &&
        capturedEpoch == currentEpoch() && stillOwned()

    private fun ownsRequest(version: Long): Boolean = isOwned() && generation.get() == version
    private fun admit(version: Long, effect: () -> Unit): Boolean {
        if (!ownsRequest(version)) return false
        return withOwnedAdmission { if (ownsRequest(version)) effect() }
    }

    private fun matches(current: DownloadTask?, captured: DownloadTask): Boolean = current != null &&
        current.id == captured.id && current.item.bvid == captured.item.bvid && current.item.cid == captured.item.cid &&
        current.item.quality == captured.item.quality && current.item.isAudioOnly == captured.item.isAudioOnly &&
        current.status == captured.status && current.directory == captured.directory && current.outputFile == captured.outputFile

    internal fun selectedTask(taskId: String): DownloadTask? = manager.tasks.value.firstOrNull { it.id == taskId }
    internal fun selectedTarget(task: DownloadTask): DownloadTaskClickTarget? =
        resolveDownloadTaskClickTarget(task.item, networkAvailable())

    /** Used by the taskId route and the existing original episode queue. */
    fun open(taskId: String, onOnlinePlay: (DownloadTask) -> Unit): Job? = openOwned(taskId,onOnlinePlay,null)

    /** The complete original screen owns its episode state, asset effect and two-second checkpoint effect. */
    internal fun openOriginal(taskId:String,onEpisode:(String)->Unit):Job? =
        openOwned(taskId,{throw IllegalStateException("视频文件已被删除")},onEpisode)

    private fun openOwned(taskId: String, onOnlinePlay: (DownloadTask) -> Unit, onEpisode:((String)->Unit)?): Job? {
        if (!isOwned()) return null
        val version = generation.incrementAndGet()
        nativePublication.getAndSet(null)?.close()
        request?.cancel()
        val pending = entryScope.launch(start = CoroutineStart.LAZY) {
            try {
                val task = selectedTask(taskId) ?: throw IllegalArgumentException("视频文件不存在")
                val target = withContext(Dispatchers.IO) { selectedTarget(task) }
                ensureActive()
                if (!ownsRequest(version)) return@launch
                when (target) {
                    DownloadTaskClickTarget.OnlinePlayer -> {
                        admit(version) {
                            check(matches(selectedTask(taskId), task)) { "缓存任务已变更，请重新打开" }
                            opening = false
                            onOnlinePlay(task)
                        }
                        return@launch
                    }
                    null -> throw IllegalStateException("缓存文件不可用，连接网络后可回退在线播放")
                    DownloadTaskClickTarget.OfflinePlayer -> Unit
                }
                // Existing manager performs the exact completed-task and managed-file checks outside admission.
                val source = withContext(Dispatchers.IO) { manager.offlinePlayback(taskId) }
                val queue = withContext(Dispatchers.IO) { manager.offlineEpisodeQueue(taskId) }
                ensureActive()
                admit(version) {
                    check(matches(selectedTask(taskId), task)) { "缓存任务已变更，请重新打开" }
                    val initialized = player ?: throw IllegalStateException(playerError() ?: "播放器未能初始化")
                    retained.acquire(memory)
                    memory.stopPlayback()
                    val sourcePublication = DesktopOfflineNativeSourcePublication(initialized,
                        { ownsRequest(version) && ownsAcceptedSource() && acceptedTaskId == taskId },
                        { action -> admit(version, action) })
                    val nativeVersion = try {
                        initialized.loadVersioned(source.copy(nativePublication = sourcePublication))
                    } catch (failure: Throwable) { sourcePublication.close(); throw failure }
                    acceptedVersion = nativeVersion; acceptedTaskId = taskId
                    memory.sourceVersion = nativeVersion; memory.current = taskId
                    memory.loaded = true; memory.opening = false; memory.error = null
                    try {
                        sourcePublication.bind(checkNotNull(initialized.currentSourceSnapshot())
                            .also { check(it.sourceVersion == nativeVersion) })
                    } catch (failure: Throwable) { sourcePublication.close(); throw failure }
                    nativePublication.set(sourcePublication)
                    opening = false; error = null
                    memory.checkpoint = ::checkpoint
                    memory.onBeforeStop = { if (ownsAcceptedSource()) overlay?.setDocument(DanmakuDocument()) }
                    memory.release = { memory.assetsJob?.cancel(); memory.checkpointJob?.cancel() }
                    val index = queue.indexOfFirst { it.id == taskId }
                    memory.previous = queue.getOrNull(index - 1)?.takeIf { index > 0 }?.let { previous ->
                        { if(onEpisode!=null)onEpisode(previous.id) else open(previous.id, onOnlinePlay); Unit }
                    }
                    memory.next = queue.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { next ->
                        { if(onEpisode!=null)onEpisode(next.id) else open(next.id, onOnlinePlay); Unit }
                    }
                    if(onEpisode==null) {
                        memory.assetsJob = entryScope.launch { loadDanmaku(taskId, nativeVersion, version) }
                        memory.checkpointJob = entryScope.launch {
                            while (isActive && ownsRequest(version) && ownsAcceptedSource()) {
                                delay(2_000L)
                                checkpoint()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                admit(version) { error = failure.message ?: "离线播放失败"; opening = false }
            }
        }
        request = pending
        admit(version) { opening = true; error = null }
        pending.start()
        return pending
    }

    fun ownsAcceptedSource(): Boolean = acceptedVersion != null && memory.current == acceptedTaskId &&
        memory.sourceVersion == acceptedVersion && acceptedVersion == player?.currentSourceVersion

    private fun checkpoint() {
        if (!isOwned() || !ownsAcceptedSource()) return
        val id = acceptedTaskId ?: return
        val native = player?.state?.value ?: return
        withOwnedAdmission {
            if (isOwned() && ownsAcceptedSource() && selectedTask(id) != null)
                manager.savePlaybackPosition(id, (native.positionSeconds * 1000).toLong(), (native.durationSeconds * 1000).toLong())
        }
    }

    private suspend fun loadDanmaku(taskId: String, nativeVersion: Long, version: Long) {
        try {
            val files = withContext(Dispatchers.IO) { manager.offlineDanmaku(taskId) }
            currentCoroutineContext().ensureActive()
            if (!ownsRequest(version) || !ownsAcceptedSource() || acceptedVersion != nativeVersion) return
            val task = selectedTask(taskId) ?: return
            overlay?.enabled = memory.danmakuEnabled
            if (files != null && !task.item.isAudioOnly)
                overlay?.loadOffline(files.standardSegments, files.specialSegments, task.item.duration.toDouble())
            else overlay?.setDocument(DanmakuDocument())
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            admit(version) { if (ownsAcceptedSource()) error = failure.message ?: "离线弹幕读取失败" }
        }
    }

    internal fun persistOriginal(taskId:String,positionMs:Long) {
        val version=generation.get()
        admit(version) {
            if(ownsAcceptedSource() && acceptedTaskId==taskId) {
                val durationMs=(player!!.state.value.durationSeconds*1000).toLong()
                manager.savePlaybackPosition(taskId,positionMs,durationMs)
            }
        }
    }

    internal suspend fun loadOriginalDanmaku(taskId:String,standard:List<java.nio.file.Path>,special:List<java.nio.file.Path>) {
        currentCoroutineContext().ensureActive()
        val nativeVersion=acceptedVersion ?: return
        val version=generation.get()
        if(!ownsRequest(version)||!ownsAcceptedSource()||acceptedTaskId!=taskId)return
        val task=selectedTask(taskId) ?: return
        overlay?.loadOffline(standard,special,task.item.duration.toDouble(),expectedSourceVersion=nativeVersion,
            stillOwned={ownsRequest(version)&&ownsAcceptedSource()&&acceptedTaskId==taskId&&acceptedVersion==nativeVersion})
        currentCoroutineContext().ensureActive()
    }

    internal fun releaseOriginal(taskId:String,nativeVersion:Long?) {
        if(nativeVersion==null)return
        val version=generation.get()
        admit(version) {
            if(ownsAcceptedSource() && acceptedTaskId==taskId && acceptedVersion==nativeVersion) {
                nativePublication.getAndSet(null)?.close()
                memory.stopPlayback();memory.current=null
                acceptedVersion=null;acceptedTaskId=null
            }
        }
    }

    fun setDanmakuEnabled(enabled: Boolean) {
        val version = generation.get()
        admit(version) { if (ownsAcceptedSource()) { memory.danmakuEnabled = enabled; overlay?.enabled = enabled } }
    }

    fun runPlayerAction(effect: (MpvPlayer) -> Unit) {
        val version = generation.get()
        admit(version) { if (ownsAcceptedSource()) player?.let(effect) }
    }

    /** A retired entry can cancel only its own native version and offline jobs. */
    override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet(); request?.cancel(); completion?.dispose()
        nativePublication.getAndSet(null)?.close()
        opening = false
        val retiringVersion = acceptedVersion
        val retiringTask = acceptedTaskId
        val retireNative = {
            if (retiringVersion != null && memory.current == retiringTask && memory.sourceVersion == retiringVersion &&
                retiringVersion == player?.currentSourceVersion) {
                overlay?.setDocument(DanmakuDocument())
                memory.stopPlayback()
                memory.current = null
            }
        }
        // Root publishes media on Swing; cancellation may arrive from a transport thread.
        // Join that existing serial UI actor before clearing the shared overlay or native memory.
        if (javax.swing.SwingUtilities.isEventDispatchThread()) retireNative()
        else javax.swing.SwingUtilities.invokeLater(retireNative)
        acceptedVersion = null; acceptedTaskId = null
    }
}
