package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import com.bilipai.desktop.player.DesktopNvidiaVideoHdrMode
import com.bilipai.desktop.player.DesktopNvidiaVideoContent
import com.bilipai.desktop.player.DesktopNvidiaVideoPreferences
import com.bilipai.desktop.player.DesktopNvidiaVideoQuality
import com.bilipai.desktop.player.DesktopNvidiaVideoIntensity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Root switch: a store created by this build is seeded with HDR automatic. */
internal const val SEED_FRESH_STORE_HDR_AUTO: Boolean = true

/**
 * Root switch: the exact store the previous build seeded and nobody touched since carries HDR
 * automatic in memory only. Nothing is rewritten on load; the first save stamps the profile
 * version and from then on the stored value is authoritative.
 */
internal const val EFFECTIVE_HDR_AUTO_FOR_UNTOUCHED_SEED: Boolean = true

/** One Windows preference authority. Accepted writes drain before Store retirement. */
class DesktopVideoEnhancementConfiguration(
    private val store: DesktopPluginStore,
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val acceptChanges: () -> Boolean = { true },
) {
    private data class Request(val change: (DesktopNvidiaVideoPreferences) -> DesktopNvidiaVideoPreferences,
        val completion: CompletableDeferred<Unit>)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val closed = AtomicBoolean()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableAutomaticEnabled = MutableStateFlow(false)
    val automaticEnabled: StateFlow<Boolean> = mutableAutomaticEnabled.asStateFlow()
    private val mutablePreferences = MutableStateFlow(DesktopNvidiaVideoPreferences())
    val preferences: StateFlow<DesktopNvidiaVideoPreferences> = mutablePreferences.asStateFlow()
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
                        savedValues(DesktopNvidiaVideoPreferences(enabled = true,
                            hdrMode = if (SEED_FRESH_STORE_HDR_AUTO) DesktopNvidiaVideoHdrMode.AUTO else DesktopNvidiaVideoHdrMode.OFF)) +
                            (MIGRATION_KEY to JsonPrimitive(1))
                    } else {
                        validatedPreferences(fresh)
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
        val saved = validatedPreferences(snapshot)
        mutablePreferences.value = saved
        mutableAutomaticEnabled.value = saved.enabled
        compatibilityConfig.value = Anime4KConfig(rememberAcrossVideos = true, rememberedEnabled = saved.enabled)
    }
    private fun validatedPreferences(snapshot: DesktopPreferenceSnapshot): DesktopNvidiaVideoPreferences {
        val enabled = validatedEnabled(snapshot)
        val qualityValue = snapshot[qualityKey]
        val quality = if (qualityValue == null) DesktopNvidiaVideoQuality.HIGHEST else {
            val level = (qualityValue as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            checkNotNull(DesktopNvidiaVideoQuality.entries.singleOrNull { it.nativeLevel == level }) {
                "NVIDIA 处理质量设置无效"
            }
        }
        val hdrValue = snapshot[hdrModeKey]
        val hdrMode = if (hdrValue == null) DesktopNvidiaVideoHdrMode.OFF else {
            val name = (hdrValue as? JsonPrimitive)?.takeIf { it.isString }?.content
            checkNotNull(DesktopNvidiaVideoHdrMode.entries.singleOrNull { it.storedValue == name }) {
                "NVIDIA HDR 设置无效"
            }
        }
        // A missing SR key is the old two-option schema, including HDR AUTO.
        val srValue = snapshot[srEnabledKey]
        val srEnabled = if (srValue == null) true else checkNotNull(
            (srValue as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) {
            "NVIDIA 增强内容设置无效"
        }
        check(srEnabled || hdrMode == DesktopNvidiaVideoHdrMode.AUTO) { "NVIDIA HDR 独立模式设置无效" }
        val intensityValue = snapshot[intensityKey]
        val intensity = if (intensityValue == null) DesktopNvidiaVideoIntensity.FULL else {
            val percent = (intensityValue as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            checkNotNull(DesktopNvidiaVideoIntensity.entries.singleOrNull { it.nativePercent == percent }) {
                "NVIDIA 效果强度设置无效"
            }
        }
        val stored = DesktopNvidiaVideoPreferences(enabled, quality, hdrMode, srEnabled, intensity)
        return if (EFFECTIVE_HDR_AUTO_FOR_UNTOUCHED_SEED && untouchedOldSeed(snapshot, stored)) {
            stored.copy(hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        } else stored
    }
    /** Lenient on purpose: anything that is not a whole number of at least 1 counts as the version before any stamp. */
    private fun profileVersion(snapshot: DesktopPreferenceSnapshot): Int {
        val value = snapshot[profileVersionKey] as? JsonPrimitive ?: return 1
        if (value.isString) return 1
        val version = value.intOrNull ?: return 1
        return if (version >= 1) version else 1
    }
    /** The exact signature of the store the previous build seeded and nobody changed since: every stored value is
     * the old default (a missing key is that default) and no build has stamped the current profile version.
     * `enabled` is deliberately ignored, so the switch alone never makes the store look touched. */
    private fun untouchedOldSeed(snapshot: DesktopPreferenceSnapshot, stored: DesktopNvidiaVideoPreferences): Boolean =
        profileVersion(snapshot) < PROFILE_VERSION_CURRENT &&
            stored.quality == DesktopNvidiaVideoQuality.HIGHEST && stored.hdrMode == DesktopNvidiaVideoHdrMode.OFF &&
            stored.srEnabled && stored.intensity == DesktopNvidiaVideoIntensity.FULL
    private fun savedValues(preferences: DesktopNvidiaVideoPreferences) = mapOf(
        ENABLED_KEY to JsonPrimitive(preferences.enabled),
        QUALITY_KEY to JsonPrimitive(preferences.quality.nativeLevel),
        HDR_MODE_KEY to JsonPrimitive(preferences.hdrMode.storedValue),
        SR_ENABLED_KEY to JsonPrimitive(preferences.srEnabled),
        INTENSITY_KEY to JsonPrimitive(preferences.intensity.nativePercent),
        PROFILE_VERSION_KEY to JsonPrimitive(PROFILE_VERSION_CURRENT),
    )
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
                            savedValues(request.change(validatedPreferences(fresh)))
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

    fun setAutomaticEnabled(enabled: Boolean): Deferred<Unit> = setPreference { it.copy(enabled = enabled) }
    fun setQuality(quality: DesktopNvidiaVideoQuality): Deferred<Unit> = setPreference { it.copy(quality = quality) }
    fun setIntensity(intensity: DesktopNvidiaVideoIntensity): Deferred<Unit> = setPreference { it.copy(intensity = intensity) }
    fun setHdrMode(hdrMode: DesktopNvidiaVideoHdrMode): Deferred<Unit> = setPreference {
        it.copy(hdrMode = hdrMode, srEnabled = it.srEnabled || hdrMode == DesktopNvidiaVideoHdrMode.OFF)
    }
    fun setContent(content: DesktopNvidiaVideoContent): Deferred<Unit> = setPreference {
        it.copy(srEnabled = content.srEnabled, hdrMode = content.hdrMode)
    }
    private fun setPreference(change: (DesktopNvidiaVideoPreferences) -> DesktopNvidiaVideoPreferences): Deferred<Unit> {
        check(!closed.get() && acceptChanges()) { "NVIDIA 增强设置已关闭" }
        val completion = CompletableDeferred<Unit>()
        check(requests.trySend(Request(change, completion)).isSuccess) { "NVIDIA 增强设置已关闭" }
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
        const val QUALITY_KEY = "quality_level"
        const val HDR_MODE_KEY = "hdr_mode"
        const val SR_ENABLED_KEY = "sr_enabled"
        const val INTENSITY_KEY = "effect_intensity_percent"
        /** Lenient, never validated: only written, and read to tell an untouched old seed from a saved store. */
        const val PROFILE_VERSION_KEY = "profile_version"
        const val PROFILE_VERSION_CURRENT = 2
        private val profileVersionKey = DesktopPreferenceKey(PROFILE_VERSION_KEY) { it }
        private val enabledKey = DesktopPreferenceKey(ENABLED_KEY) { it }
        private val migrationKey = DesktopPreferenceKey(MIGRATION_KEY) { it }
        private val qualityKey = DesktopPreferenceKey(QUALITY_KEY) { it }
        private val hdrModeKey = DesktopPreferenceKey(HDR_MODE_KEY) { it }
        private val srEnabledKey = DesktopPreferenceKey(SR_ENABLED_KEY) { it }
        private val intensityKey = DesktopPreferenceKey(INTENSITY_KEY) { it }
    }
}
