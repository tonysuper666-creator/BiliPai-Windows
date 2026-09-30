package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.*
import com.bilipai.desktop.plugins.DesktopVideoShaderResources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopVideoEnhancementState(
    val identity: String? = null,
    val sourceVersion: Long = 0,
    val requested: Boolean = false,
    val available: Boolean = false,
    val active: Boolean = false,
    val pending: Boolean = false,
    val bypassReason: Anime4KBypassReason = Anime4KBypassReason.DISABLED,
    val error: String? = null,
)

/** Original per-BV/remember/output/fallback policies, with native media and shader ownership guards. */
class DesktopVideoEnhancementSession(
    private val player: MpvPlayer,
    private val config: StateFlow<Anime4KConfig>,
    private val pluginEnabled: StateFlow<Boolean>,
    private val anime4kResources: DesktopVideoShaderResources,
    fsrCacheRoot: Path,
    private val pip: StateFlow<Boolean>,
    private val hostStarted: StateFlow<Boolean>,
    private val enablePlugin: suspend () -> Unit,
    private val rememberCurrentEnabled: (Boolean) -> Unit,
    private val sessionEpoch: () -> Long = { 0L },
    private val enablePluginGuarded: (suspend (() -> Boolean) -> Unit)? = null,
) : AutoCloseable {
    private data class Identity(val key: String? = null, val version: Long = 0, val override: Boolean? = null, val epoch: Long = 0L)
    private data class Settings(val config: Anime4KConfig, val enabled: Boolean, val pip: Boolean, val started: Boolean)
    private data class Frame(val ready: Boolean, val hasVideo: Boolean, val audioOnly: Boolean, val ended: Boolean, val failed: Boolean)
    private data class Output(val version: Long, val maximumTexture: Int?, val width: Int, val height: Int,
        val sourceWidth: Int, val sourceHeight: Int, val gamma: String?, val dolbyVisionProfile: Int?)
    private data class Input(val settings: Settings, val identity: Identity, val frame: Frame, val output: Output, val pipelineFailed: Boolean)
    private data class OwnedShaders(val version: Long, val sourceVersion: Long)
    private val identity = MutableStateFlow(Identity(epoch = sessionEpoch()))
    private val pipelineFailed = MutableStateFlow(false)
    private val mutableState = MutableStateFlow(DesktopVideoEnhancementState())
    val state: StateFlow<DesktopVideoEnhancementState> = mutableState.asStateFlow()
    private val fsrResources = DesktopFsrVideoShaderResources(fsrCacheRoot)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean()
    private val shaderOwnershipLock = Any()
    @Volatile private var ownedShaders: OwnedShaders? = null

    init {
        scope.launch {
            config.map { it.algorithm }.distinctUntilChanged().collect { pipelineFailed.value = false }
        }
        scope.launch {
            player.videoShaderState.collect { native ->
                val owned = ownedShaders ?: return@collect
                if (native.configurationVersion != owned.version || identity.value.version != owned.sourceVersion ||
                    !player.ownsSourceVersion(owned.sourceVersion)) return@collect
                mutableState.update { it.copy(active = native.active, pending = !native.active && native.error == null, error = native.error) }
                if (native.error != null) pipelineFailed.value = true
            }
        }
        scope.launch {
            val settings = combine(config, pluginEnabled, pip, hostStarted) { configuration, enabled, pictureInPicture, started ->
                Settings(configuration, enabled, pictureInPicture, started)
            }
            val frame = player.state.map { Frame(it.ready, it.videoCodec != null, it.audioOnly, it.ended, it.error != null) }.distinctUntilChanged()
            // Applied FBO format is observed by shaderState; it must not retrigger and reinstall its own configuration.
            val output = player.videoOutput.map { Output(it.sourceVersion, it.maximumTextureDimension, it.displayWidth, it.displayHeight,
                it.inputWidth, it.inputHeight, it.gamma, it.dolbyVisionProfile) }.distinctUntilChanged()
            combine(settings, identity, frame, output, pipelineFailed) { configuration, video, playback, native, failed ->
                Input(configuration, video, playback, native, failed)
            }.distinctUntilChanged().collectLatest(::apply)
        }
    }

    /** Ordinary parts share bvid, matching upstream remember(bvid, player); ownership is still each native source token. */
    fun bindVideoIdentity(key: String?, sourceVersion: Long) {
        if (closed.get()) return
        require(key == null || (key.isNotBlank() && key.length <= 128))
        val epoch = sessionEpoch()
        identity.update { previous ->
            if (previous.key != key || previous.epoch != epoch) pipelineFailed.value = false
            Identity(key, sourceVersion, previous.override.takeIf { key != null && previous.key == key && previous.epoch == epoch }, epoch)
        }
    }

    fun setCurrentVideoEnabled(enabled: Boolean): Job? {
        if (closed.get()) return null
        val snapshot = identity.value
        if (snapshot.key == null || snapshot.epoch != sessionEpoch() || !player.ownsSourceVersion(snapshot.version)) return null
        pipelineFailed.value = false
        identity.update { if (it.key == snapshot.key && it.version == snapshot.version) it.copy(override = enabled) else it }
        return scope.launch {
            try {
                if (!ownsToggle(snapshot, enabled)) return@launch
                if (enabled && !pluginEnabled.value) {
                    if (enablePluginGuarded == null) enablePlugin()
                    else enablePluginGuarded { ownsToggle(snapshot, enabled) }
                }
                ensureActive()
                if (ownsToggle(snapshot, enabled)) rememberCurrentEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ownsToggle(snapshot, enabled)) {
                    mutableState.update { it.copy(pending = false, error = "启用画质增强失败，请重试") }
                    pipelineFailed.value = true
                }
            }
        }
    }

    private fun ownsToggle(snapshot: Identity, enabled: Boolean): Boolean = !closed.get() &&
        snapshot.epoch == sessionEpoch() && identity.value.let {
            it.key == snapshot.key && it.version == snapshot.version && it.epoch == snapshot.epoch && it.override == enabled
        } && player.ownsSourceVersion(snapshot.version)

    private suspend fun apply(input: Input) {
        val video = input.identity
        val settings = input.settings
        val belongs = !closed.get() && video.epoch == sessionEpoch() && video.key != null &&
            video.version == input.output.version && player.ownsSourceVersion(video.version)
        val requested = video.override ?: resolveInitialVideoEnhancementEnabled(settings.enabled, settings.config)
        val available = input.output.maximumTexture != null && !input.pipelineFailed
        val transfer = when (input.output.gamma?.lowercase()) {
            // video-params/gamma uses pl_csp_trc_names from the pinned mpv csputils.c.
            "pq" -> com.bilipai.desktop.player.platform.DesktopMedia3ColorTransfers.COLOR_TRANSFER_ST2084
            "hlg" -> com.bilipai.desktop.player.platform.DesktopMedia3ColorTransfers.COLOR_TRANSFER_HLG
            else -> 0
        }
        val decision = resolveAnime4KOutputDecision(
            pluginEnabled = settings.enabled && requested && belongs,
            glAvailable = available,
            colorTransfer = transfer,
            sampleMimeType = if (input.output.dolbyVisionProfile != null) "video/dolby-vision" else null,
            isInPipMode = settings.pip,
            isAudioOnly = input.frame.audioOnly,
            hostLifecycleStarted = settings.started,
        )
        mutableState.value = DesktopVideoEnhancementState(video.key, video.version, settings.enabled && requested && belongs,
            available, bypassReason = decision.bypassReason, error = mutableState.value.error.takeIf { input.pipelineFailed })
        if (!decision.shouldUsePipeline || !input.frame.ready || !input.frame.hasVideo || input.frame.ended || input.frame.failed ||
            input.output.sourceWidth <= 0 || input.output.sourceHeight <= 0 || input.output.width <= 0 || input.output.height <= 0) {
            clearOwnedShaders()
            return
        }
        try {
            val (paths, options) = when (settings.config.algorithm) {
                VideoEnhancementAlgorithm.ANIME4K -> anime4kResources.resolveAnime4KPaths(settings.config.preset) to PlayerVideoShaderOptions("rgba16hf")
                VideoEnhancementAlgorithm.FSR_1_0 -> {
                    val program = DesktopFsrHookAdapter.prepare(input.output.sourceWidth, input.output.sourceHeight,
                        input.output.width, input.output.height, requireNotNull(input.output.maximumTexture), settings.config.fsrSharpness)
                    if (program == null) { clearOwnedShaders(); return }
                    fsrResources.resolve(program) to PlayerVideoShaderOptions(program.requiredIntermediateFormat, program.parameters, program.requiredPassDescriptions)
                }
            }
            currentCoroutineContext().ensureActive()
            if (closed.get() || identity.value != video || video.epoch != sessionEpoch()) return
            val token = synchronized(shaderOwnershipLock) {
                // close() must never race a final install after it has cleared its owned configuration.
                if (closed.get() || identity.value != video || video.epoch != sessionEpoch()) null else {
                    player.setVideoShadersIfSourceVersion(video.version, paths, options)?.also {
                        ownedShaders = OwnedShaders(it, video.version)
                    }
                }
            } ?: return
            mutableState.update { it.copy(pending = true, active = false, error = null) }
            val began = System.nanoTime()
            while (currentCoroutineContext().isActive && !closed.get() && identity.value == video && player.ownsSourceVersion(video.version)) {
                val actual = player.videoShaderState.value
                if (actual.configurationVersion != token) {
                    mutableState.update { it.copy(active = false, pending = false) }
                    return
                }
                if (actual.active) { mutableState.update { it.copy(active = true, pending = false) }; return }
                if (actual.error != null) { pipelineFailed.value = true; return }
                val playback = player.state.value
                if (shouldFallbackAnime4KBeforeFirstFrame(
                        pipelineRequested = true, inputSurfaceReady = playback.ready, displayedFirstFrame = actual.active,
                        playWhenReady = !playback.paused, mediaItemCount = if (player.ownsSourceVersion(video.version)) 1 else 0,
                        elapsedMs = (System.nanoTime() - began) / 1_000_000L)) {
                    mutableState.update { it.copy(error = "画质增强未显示首帧，已恢复原始视频输出。") }
                    pipelineFailed.value = true
                    return
                }
                delay(40)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (identity.value == video && player.ownsSourceVersion(video.version) && !closed.get()) {
                mutableState.update { it.copy(error = "画质增强无法初始化：${failure.javaClass.simpleName}") }
                pipelineFailed.value = true
            }
        }
    }

    private fun clearOwnedShaders(): Unit = synchronized(shaderOwnershipLock) {
        val owned = ownedShaders ?: return@synchronized
        player.clearVideoShadersIfConfigurationVersion(owned.version)
        if (ownedShaders === owned) ownedShaders = null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            clearOwnedShaders()
        }
    }
}
