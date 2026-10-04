package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.Anime4KBypassReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopVideoEnhancementState(
    val identity: String? = null,
    val sourceVersion: Long = 0,
    val requested: Boolean = false,
    val available: Boolean = false,
    val active: Boolean = false,
    val pending: Boolean = false,
    // Compatibility for generated original declarations, never an Android algorithm authority.
    val bypassReason: Anime4KBypassReason = Anime4KBypassReason.DISABLED,
    val error: String? = null,
    val statusText: String = "等待视频播放",
    val driverVsrAccepted: Boolean = false,
    val driverHdrAccepted: Boolean = false,
    val hdrConversionActive: Boolean = false,
    val gpuName: String? = null,
    val targetTransfer: String? = null,
    val targetPrimaries: String? = null,
    val hdrDisplayEnabled: Boolean = false,
)

/** One Windows NVIDIA session on the main native video actor. Every video kind
 * derives ownership from that actor's complete source snapshot; BV labels only
 * describe UI identity. No plugin, GLSL shader or second decoding surface is used. */
class DesktopVideoEnhancementSession(
    private val player: MpvPlayer,
    private val automaticEnabled: StateFlow<Boolean>,
    private val hostStarted: StateFlow<Boolean>,
    private val pip: StateFlow<Boolean>,
    private val setAutomaticEnabled: (Boolean) -> Deferred<Unit>,
    private val sessionEpoch: () -> Long = { 0L },
) : AutoCloseable {
    private data class Label(val key: String? = null, val version: Long = 0, val epoch: Long = 0)
    private data class Frame(val ready: Boolean, val hasVideo: Boolean, val audioOnly: Boolean, val ended: Boolean, val failed: Boolean)
    private data class Settings(val enabled: Boolean, val started: Boolean, val pip: Boolean)
    private data class Target(val sourceVersion: Long, val transfer: String?, val primaries: String?)
    private data class Input(val settings: Settings, val label: Label, val frame: Frame, val output: PlayerVideoOutputState, val target: Target)
    private class Request(val source: OwnedPlaybackSourceSnapshot, val epoch: Long, val options: NvidiaVideoOptions) {
        fun matches(other: Request) = epoch == other.epoch && options == other.options &&
            source.sourceVersion == other.source.sourceVersion && source.source == other.source.source
    }
    private class Owned(val request: Request, val token: Long)
    private val label = MutableStateFlow(Label(epoch = sessionEpoch()))
    private val mutableState = MutableStateFlow(DesktopVideoEnhancementState(requested = automaticEnabled.value))
    val state: StateFlow<DesktopVideoEnhancementState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean()
    private val ownershipLock = Any()
    private var owned: Owned? = null

    init {
        scope.launch {
            val settings = combine(automaticEnabled, hostStarted, pip) { enabled, started, inPip -> Settings(enabled, started, inPip) }
            val frame = player.state.map { Frame(it.ready, it.videoCodec != null, it.audioOnly, it.ended, it.error != null) }.distinctUntilChanged()
            // Output format/driver ACK cannot reconfigure its own processing.
            // Only the actual display target is a distinct native decision input.
            val target = player.nvidiaVideoState.map { Target(it.sourceVersion, it.targetTransfer, it.targetPrimaries) }.distinctUntilChanged()
            combine(settings, label, frame, player.videoOutput, target) { preferences, identity, playback, output, destination ->
                Input(preferences, identity, playback, output, destination)
            }.distinctUntilChanged().collect(::apply)
        }
        scope.launch { player.nvidiaVideoState.collect(::observeNative) }
    }

    /** Missing BV/PGC/live/story IDs never deny the actual source enhancement. */
    fun bindVideoIdentity(key: String?, sourceVersion: Long) {
        if (closed.get()) return
        require(key == null || (key.isNotBlank() && key.length <= 128))
        val updated = Label(key, sourceVersion, sessionEpoch())
        if (label.value != updated) label.value = updated
    }

    /** Check the source/epoch before submitting to the existing config worker.
     * An accepted global setting is durable; completion cannot alter a new source's status. */
    fun setCurrentVideoEnabled(enabled: Boolean): Job? {
        if (closed.get()) return null
        val source = player.currentSourceSnapshot() ?: return null
        val epoch = sessionEpoch()
        if (!owns(source, epoch)) return null
        return scope.launch {
            if (!owns(source, epoch)) return@launch
            try {
                setAutomaticEnabled(enabled).await()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(ownershipLock) {
                    if (owns(source, epoch)) mutableState.update {
                        it.copy(error = "保存 NVIDIA 自动增强设置失败，请重试", statusText = "设置保存失败，视频继续原有输出")
                    }
                }
            }
        }
    }

    private fun owns(source: OwnedPlaybackSourceSnapshot, epoch: Long): Boolean = !closed.get() &&
        epoch == sessionEpoch() && player.ownsSourceSnapshot(source) &&
        (source.source.authorizationReceipt?.accountEpoch ?: source.source.primaryAccountEpoch ?: epoch) == epoch

    private fun apply(input: Input): Unit = synchronized(ownershipLock) {
        if (closed.get()) return@synchronized
        val source = player.currentSourceSnapshot()
        val epoch = input.label.epoch
        val current = source != null && owns(source, epoch) && source.sourceVersion == input.output.sourceVersion
        val identity = if (current) input.label.key.takeIf { input.label.version == source!!.sourceVersion }
            ?: "video:${source!!.sourceVersion}" else null
        val native = player.nvidiaVideoState.value
        val available = native.gpuVendorId == 0x10de && native.currentGpuContext?.startsWith("d3d11") == true
        fun bypass(reason: Anime4KBypassReason, text: String) {
            clearOwnedLocked()
            mutableState.value = DesktopVideoEnhancementState(identity, source?.sourceVersion ?: 0,
                input.settings.enabled, available, bypassReason = reason,
                statusText = native.gpuName?.let { "$it；$text" } ?: text, gpuName = native.gpuName,
                targetTransfer = native.targetTransfer, targetPrimaries = native.targetPrimaries,
                hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled)
        }
        if (!input.settings.enabled) { bypass(Anime4KBypassReason.DISABLED, "NVIDIA 自动增强已关闭，原画直出"); return@synchronized }
        if (!current) { bypass(Anime4KBypassReason.NONE, "等待当前视频源"); return@synchronized }
        if (!input.settings.started && !input.settings.pip) {
            bypass(Anime4KBypassReason.HOST_NOT_STARTED, "播放器当前不可见，原画输出"); return@synchronized
        }
        if (input.frame.audioOnly) { bypass(Anime4KBypassReason.AUDIO_ONLY, "仅音频播放，无需画面增强"); return@synchronized }
        if (!input.frame.ready || !input.frame.hasVideo || input.frame.ended || input.frame.failed) {
            bypass(Anime4KBypassReason.NONE, "等待可用视频画面"); return@synchronized
        }
        val decision = resolveDesktopNvidiaVideoDecision(input.output.inputWidth, input.output.inputHeight,
            input.output.displayWidth, input.output.displayHeight, input.output.maximumTextureDimension,
            input.output.gamma, input.output.dolbyVisionProfile, input.output.hdrDisplay.hdrEnabled,
            input.target.transfer.takeIf { input.target.sourceVersion == source!!.sourceVersion },
            input.target.primaries.takeIf { input.target.sourceVersion == source!!.sourceVersion })
        if (!decision.needsProcessing) {
            val text = when (decision.kind) {
                DesktopNvidiaVideoDecisionKind.WAITING_VIDEO -> "等待实际视频尺寸，原画输出"
                DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT -> "等待 GPU 输出能力，原画输出"
                else -> if (decision.sourceIsHdr) "原生 HDR / Dolby Vision 内容，保留原生输出" else "当前尺寸无需放大，原画直出"
            }
            bypass(Anime4KBypassReason.NONE, text); return@synchronized
        }
        val request = Request(checkNotNull(source), epoch, NvidiaVideoOptions(decision.scale, decision.hdr))
        val previous = owned
        if (previous != null && previous.request.matches(request)) {
            mutableState.update { it.copy(hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled) }
            observeNativeLocked(native, previous)
            return@synchronized
        }
        // Native actor performs source publication admission outside its MPV lock.
        clearOwnedLocked()
        if (!owns(request.source, request.epoch)) return@synchronized
        val token = player.setNvidiaVideoEnhancementIfSourceSnapshot(request.source, request.options)
        if (token == null) {
            mutableState.value = DesktopVideoEnhancementState(identity, source!!.sourceVersion, true, available,
                statusText = "当前视频源已切换，等待新画面", gpuName = native.gpuName,
                hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled)
            return@synchronized
        }
        owned = Owned(request, token)
        mutableState.value = DesktopVideoEnhancementState(identity, source!!.sourceVersion, true, available,
            pending = true, bypassReason = Anime4KBypassReason.NONE, statusText = "正在请求 NVIDIA 硬件增强",
            gpuName = native.gpuName, hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled)
        observeNativeLocked(player.nvidiaVideoState.value, checkNotNull(owned))
    }

    private fun observeNative(native: NvidiaVideoState): Unit = synchronized(ownershipLock) {
        val current = owned ?: return@synchronized
        observeNativeLocked(native, current)
    }

    private fun observeNativeLocked(native: NvidiaVideoState, current: Owned) {
        if (owned !== current || !owns(current.request.source, current.request.epoch)) return
        if (native.configurationVersion != current.token || native.sourceVersion != current.request.source.sourceVersion) {
            mutableState.update { it.copy(active = false, pending = false, error = null,
                driverVsrAccepted = false, driverHdrAccepted = false, hdrConversionActive = false,
                statusText = "当前硬件增强配置已更换，等待当前输出") }
            return
        }
        val hdrPresented = native.hdrConversionActive && mutableState.value.hdrDisplayEnabled && native.active &&
            nvidiaHdrTarget(native.targetTransfer, native.targetPrimaries)
        val status = when {
            native.error != null -> "NVIDIA 增强异常：${native.error}"
            native.pending -> "正在请求 NVIDIA 硬件增强"
            native.active -> buildList {
                if (native.driverVsrAccepted) add("驱动已接受 VSR")
                if (hdrPresented) add("HDR 转换帧与 HDR 显示目标均已就绪")
                else if (native.hdrConversionActive) add("已产生 HDR 转换帧，HDR 显示目标尚未就绪")
                else if (native.driverHdrAccepted) add("驱动已接受 HDR，等待转换帧与 HDR 显示目标")
                if (isEmpty()) add("硬件输出已就绪")
                if (native.inputWidth > 0 && native.outputWidth > 0)
                    add("${native.inputWidth}×${native.inputHeight} → ${native.outputWidth}×${native.outputHeight}")
            }.joinToString("；")
            native.hdrConversionActive -> "已产生 HDR 转换帧，HDR 显示目标尚未就绪"
            else -> "等待 NVIDIA 硬件输出，视频保持原有画面"
        }
        mutableState.update { it.copy(available = native.gpuVendorId == 0x10de || native.driverVsrAccepted || native.driverHdrAccepted,
            active = native.active, pending = native.pending, error = native.error,
            statusText = native.gpuName?.let { name -> "$name；$status" } ?: status, gpuName = native.gpuName,
            targetTransfer = native.targetTransfer, targetPrimaries = native.targetPrimaries,
            driverVsrAccepted = native.driverVsrAccepted, driverHdrAccepted = native.driverHdrAccepted,
            hdrConversionActive = native.hdrConversionActive) }
    }

    private fun clearOwnedLocked() {
        val previous = owned ?: return
        owned = null
        player.clearNvidiaVideoEnhancementIfConfigurationVersion(previous.token)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            synchronized(ownershipLock) {
                clearOwnedLocked()
                mutableState.value = DesktopVideoEnhancementState(requested = automaticEnabled.value,
                    statusText = "视频增强会话已关闭")
            }
        }
    }
}
