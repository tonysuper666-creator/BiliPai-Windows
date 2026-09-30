package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Every original setter finishes its actual IO children before the next setter runs. */
class DesktopVideoEnhancementConfiguration(
    private val plugin: Anime4KPlugin,
    private val ready: suspend () -> Unit,
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val acceptChanges: () -> Boolean = { true },
) {
    private data class Request(val change: () -> Unit, val completion: CompletableDeferred<Unit>)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val closed = AtomicBoolean()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val configState get() = plugin.configState
    private var loaded = false
    private suspend fun ensureLoaded() {
        if (loaded) return
        ready()
        plugin.loadConfig()
        loaded = true
    }
    private val worker = scope.launch {
        // Off-state settings must reflect saved original config without enabling the plugin.
        try { serialize { ensureLoaded() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "读取画质增强配置失败，请重试" }
        for (request in requests) {
            try {
                serialize {
                    ensureLoaded()
                    request.change()
                    plugin.awaitDesktopConfigurationWrites()
                }
                mutableError.value = null
                request.completion.complete(Unit)
            } catch (cancelled: CancellationException) {
                request.completion.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = "保存画质增强配置失败，请重试"
                request.completion.completeExceptionally(failure)
            }
        }
    }

    fun setPreset(preset: Anime4KPreset) = enqueue { plugin.setPreset(preset) }
    fun setAlgorithm(algorithm: VideoEnhancementAlgorithm) = enqueue { plugin.setAlgorithm(algorithm) }
    fun setFsrSharpness(strength: Float) = enqueue { plugin.setFsrSharpness(strength) }
    fun setRememberAcrossVideos(enabled: Boolean, currentVideoEnabled: Boolean) = enqueue {
        plugin.setRememberAcrossVideos(enabled, currentVideoEnabled)
    }
    fun rememberCurrentVideoEnabled(enabled: Boolean) = enqueue { plugin.rememberCurrentVideoEnabled(enabled) }
    private fun enqueue(change: () -> Unit): Deferred<Unit> {
        check(!closed.get() && acceptChanges()) { "画质增强配置已关闭" }
        val completion = CompletableDeferred<Unit>()
        check(requests.trySend(Request(change, completion)).isSuccess) { "画质增强配置已关闭" }
        return completion
    }

    suspend fun flushAndClose(): Unit = withContext(NonCancellable) {
        if (closed.compareAndSet(false, true)) requests.close()
        worker.join()
        scope.cancel()
    }
}
