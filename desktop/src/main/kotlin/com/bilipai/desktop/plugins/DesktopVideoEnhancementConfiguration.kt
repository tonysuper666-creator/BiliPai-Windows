package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** One Windows preference authority. Accepted writes drain before Store retirement. */
class DesktopVideoEnhancementConfiguration(
    private val store: DesktopPluginStore,
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val acceptChanges: () -> Boolean = { true },
) {
    private data class Request(val enabled: Boolean, val completion: CompletableDeferred<Unit>)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val closed = AtomicBoolean()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableAutomaticEnabled = MutableStateFlow(false)
    val automaticEnabled: StateFlow<Boolean> = mutableAutomaticEnabled.asStateFlow()
    // A read-only view for compiled upstream platform signatures. This never
    // selects an algorithm or writes the old plugin's configuration.
    private val compatibilityConfig = MutableStateFlow(Anime4KConfig())
    val configState: StateFlow<Anime4KConfig> = compatibilityConfig.asStateFlow()
    private var loaded = false
    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            store.requireObjectNamespace(NAMESPACE)
            if (store.snapshot(NAMESPACE).value[migrationKey] == null) {
                store.updateFromSnapshot(NAMESPACE) { fresh ->
                    if (fresh[migrationKey] == null) {
                        // One user-authorized migration to the Windows replacement;
                        // legacy plugin keys and JSON remain untouched.
                        mapOf(ENABLED_KEY to JsonPrimitive(true), MIGRATION_KEY to JsonPrimitive(1))
                    } else {
                        validatedEnabled(fresh)
                        emptyMap()
                    }
                }
            }
            publishSaved(store.snapshot(NAMESPACE).value)
        }
        loaded = true
    }
    private fun validatedEnabled(snapshot: DesktopPreferenceSnapshot): Boolean {
        val version = snapshot[migrationKey] as? JsonPrimitive
        check(version != null && !version.isString && version.intOrNull == 1) { "NVIDIA 设置版本无效" }
        val enabled = snapshot[enabledKey] as? JsonPrimitive
        return checkNotNull(enabled?.takeUnless { it.isString }?.booleanOrNull) { "NVIDIA 自动增强设置无效" }
    }
    private fun publishSaved(snapshot: DesktopPreferenceSnapshot) {
        val enabled = validatedEnabled(snapshot)
        mutableAutomaticEnabled.value = enabled
        compatibilityConfig.value = Anime4KConfig(rememberAcrossVideos = true, rememberedEnabled = enabled)
    }
    private val worker = scope.launch {
        try { serialize { ensureLoaded() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableError.value = "读取 NVIDIA 增强设置失败，请重试" }
        for (request in requests) {
            try {
                serialize {
                    ensureLoaded()
                    withContext(Dispatchers.IO) {
                        store.updateFromSnapshot(NAMESPACE) { fresh ->
                            validatedEnabled(fresh)
                            mapOf(ENABLED_KEY to JsonPrimitive(request.enabled))
                        }
                        publishSaved(store.snapshot(NAMESPACE).value)
                    }
                }
                mutableError.value = null
                request.completion.complete(Unit)
            } catch (cancelled: CancellationException) {
                request.completion.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = "保存 NVIDIA 增强设置失败，请重试"
                request.completion.completeExceptionally(failure)
            }
        }
    }

    fun setAutomaticEnabled(enabled: Boolean): Deferred<Unit> {
        check(!closed.get() && acceptChanges()) { "NVIDIA 增强设置已关闭" }
        val completion = CompletableDeferred<Unit>()
        check(requests.trySend(Request(enabled, completion)).isSuccess) { "NVIDIA 增强设置已关闭" }
        return completion
    }
    // Old signatures compile while their Windows widget leaf is replaced. They
    // cannot install old shaders or mutate legacy algorithm/preset/sharpness keys.
    fun setPreset(@Suppress("UNUSED_PARAMETER") preset: Anime4KPreset): Deferred<Unit> = unsupportedLegacyChoice()
    fun setAlgorithm(@Suppress("UNUSED_PARAMETER") algorithm: VideoEnhancementAlgorithm): Deferred<Unit> = unsupportedLegacyChoice()
    fun setFsrSharpness(@Suppress("UNUSED_PARAMETER") strength: Float): Deferred<Unit> = unsupportedLegacyChoice()
    fun setRememberAcrossVideos(@Suppress("UNUSED_PARAMETER") enabled: Boolean, currentVideoEnabled: Boolean): Deferred<Unit> = setAutomaticEnabled(currentVideoEnabled)
    fun rememberCurrentVideoEnabled(enabled: Boolean): Deferred<Unit> = setAutomaticEnabled(enabled)
    private fun unsupportedLegacyChoice(): Deferred<Unit> {
        val message = "Windows 已统一使用 NVIDIA 自动增强"
        mutableError.value = message
        return CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException(message)) }
    }

    suspend fun flushAndClose(): Unit = withContext(NonCancellable) {
        if (closed.compareAndSet(false, true)) requests.close()
        worker.join()
        scope.cancel()
    }
    internal companion object {
        const val NAMESPACE = "windows_video_enhancement"
        const val ENABLED_KEY = "enabled"
        const val MIGRATION_KEY = "migration_version"
        private val enabledKey = DesktopPreferenceKey(ENABLED_KEY) { it }
        private val migrationKey = DesktopPreferenceKey(MIGRATION_KEY) { it }
    }
}
