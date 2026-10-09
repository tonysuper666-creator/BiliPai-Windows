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
    val unavailableReason: String? = null,
    val nativeResolutionAttemptAccepted: Boolean = false,
    val veyraAvailable: Boolean = false,
    val veyraSubmitted: Boolean = false,
)

/** Shared projection; the session admits the current source/configuration before calling it. */
internal fun DesktopVideoEnhancementState.withNvidiaObservation(native: NvidiaVideoState): DesktopVideoEnhancementState {
    val hdrPresented = native.hdrConversionActive && hdrDisplayEnabled && native.active &&
        nvidiaHdrTarget(native.targetTransfer, native.targetPrimaries)
    if (native.backend == NvidiaVideoBackend.VEYRA_CORE) {
        val status = when {
            native.error != null -> "画质增强暂不可用，继续播放原画"
            native.unavailableReason != null || !native.veyraAvailable -> "画质增强暂不可用，继续播放原画"
            native.active -> "画质增强画面已显示"
            native.veyraSubmitted -> "已提交画面增强，等待画面反馈"
            else -> "正在准备画质增强"
        }
        return copy(available = native.veyraAvailable, active = native.active, pending = native.pending,
            error = native.error, unavailableReason = native.unavailableReason, statusText = status,
            driverVsrAccepted = false, driverHdrAccepted = false, hdrConversionActive = native.hdrConversionActive,
            nativeResolutionAttemptAccepted = false, veyraAvailable = native.veyraAvailable, veyraSubmitted = native.veyraSubmitted)
    }
    val status = when {
        native.error != null -> "NVIDIA 增强异常：${native.error}"
        native.unavailableReason != null -> "NVIDIA 增强不可用：${native.unavailableReason}"
        native.pending -> "正在请求 NVIDIA 硬件增强"
        native.active -> buildList {
            if (native.nativeResolutionAttemptAccepted) add("同分辨率处理请求已接受，画质效果尚未验证")
            else if (native.driverVsrAccepted) add("驱动已接受 VSR")
            if (hdrPresented) add("HDR 转换帧与 HDR 显示目标均已就绪")
            else if (native.hdrConversionActive) add("已产生 HDR 转换帧，HDR 显示目标尚未就绪")
            else if (native.driverHdrAccepted) add("驱动已接受 HDR，等待转换帧与 HDR 显示目标")
            if (isEmpty()) add("硬件输出已就绪")
            if (native.inputWidth > 0 && native.outputWidth > 0)
                add("${native.inputWidth}×${native.inputHeight} → ${native.outputWidth}×${native.outputHeight}")
        }.joinToString("；")
        native.nativeResolutionAttemptAccepted -> "同分辨率处理请求已接受，画质效果尚未验证"
        native.hdrConversionActive -> "已产生 HDR 转换帧，HDR 显示目标尚未就绪"
        else -> "等待 NVIDIA 硬件输出，视频保持原有画面"
    }
    return copy(available = native.unavailableReason == null &&
        (native.gpuVendorId == 0x10de || native.driverVsrAccepted || native.driverHdrAccepted),
        active = native.active, pending = native.pending, error = native.error, unavailableReason = native.unavailableReason,
        statusText = native.gpuName?.let { name -> "$name；$status" } ?: status, gpuName = native.gpuName,
        targetTransfer = native.targetTransfer, targetPrimaries = native.targetPrimaries,
        driverVsrAccepted = native.driverVsrAccepted, driverHdrAccepted = native.driverHdrAccepted,
        hdrConversionActive = native.hdrConversionActive,
        nativeResolutionAttemptAccepted = native.nativeResolutionAttemptAccepted)
}

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
    private data class Frame(val ready: Boolean, val hasVideo: Boolean, val audioOnly: Boolean, val ended: Boolean, val failed: Boolean, val nativeIdentity: PlayerNativeTrackIdentity?)
    private data class Settings(val enabled: Boolean, val started: Boolean, val pip: Boolean)
    private data class Target(val sourceVersion: Long, val transfer: String?, val primaries: String?,
        val nativeResolutionPatchAvailable: Boolean, val veyraAvailable: Boolean)
    private data class Input(val settings: Settings, val label: Label, val frame: Frame, val output: PlayerVideoOutputState, val target: Target)
    private class Request(val source: OwnedPlaybackSourceSnapshot, val epoch: Long, val options: NvidiaVideoOptions,
        val nativeIdentity: PlayerNativeTrackIdentity) {
        fun matches(other: Request) = epoch == other.epoch && options == other.options &&
            source.sourceVersion == other.source.sourceVersion && source.source == other.source.source &&
            nativeIdentity == other.nativeIdentity
    }
    private class Owned(val request: Request, val token: Long)
    private class Clearing(val source: OwnedPlaybackSourceSnapshot, val epoch: Long, val token: Long) {
        var reportedError: String? = null
    }
    private val label = MutableStateFlow(Label(epoch = sessionEpoch()))
    private val mutableState = MutableStateFlow(DesktopVideoEnhancementState(requested = automaticEnabled.value))
    val state: StateFlow<DesktopVideoEnhancementState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean()
    private val ownershipLock = Any()
    private var owned: Owned? = null
    private var clearing: Clearing? = null

    init {
        scope.launch {
            val settings = combine(automaticEnabled, hostStarted, pip) { enabled, started, inPip -> Settings(enabled, started, inPip) }
            // A completed native load keeps a distinct receipt even if transient loading/zero sizes are conflated.
            val frame = player.state.map { Frame(it.ready, it.videoCodec != null, it.audioOnly, it.ended, it.error != null, it.nativeTrackIdentity) }.distinctUntilChanged()
            // Output format/driver ACK cannot reconfigure its own processing.
            // Only the actual display target and loaded binary patch identity are decision inputs.
            val target = player.nvidiaVideoState.map { Target(it.sourceVersion, it.targetTransfer, it.targetPrimaries, it.nativeResolutionPatchAvailable, it.veyraAvailable) }.distinctUntilChanged()
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
            observeClearingLocked(player.nvidiaVideoState.value)
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
        val currentSource = checkNotNull(source)
        val nativeIdentity = input.frame.nativeIdentity
        if (nativeIdentity == null || nativeIdentity.sourceVersion != currentSource.sourceVersion ||
            nativeIdentity.source != currentSource.source) {
            bypass(Anime4KBypassReason.NONE, "等待当前原生视频载入"); return@synchronized
        }
        val decision = resolveDesktopNvidiaVideoDecision(input.output.inputWidth, input.output.inputHeight,
            input.output.displayWidth, input.output.displayHeight, input.output.maximumTextureDimension,
            input.output.gamma, input.output.dolbyVisionProfile, input.output.hdrDisplay.hdrEnabled,
            input.target.transfer.takeIf { input.target.sourceVersion == source!!.sourceVersion },
            input.target.primaries.takeIf { input.target.sourceVersion == source!!.sourceVersion },
            (input.target.nativeResolutionPatchAvailable || (input.target.veyraAvailable && input.output.gamma in setOf("bt.1886", "srgb") && input.output.inputPrimaries == "bt.709" && !nvidiaHdrTransfer(input.output.gamma) && (input.output.dolbyVisionProfile ?: 0) <= 0)) && input.target.sourceVersion == currentSource.sourceVersion)
        if (!decision.needsProcessing) {
            val text = when (decision.kind) {
                DesktopNvidiaVideoDecisionKind.WAITING_VIDEO -> "等待实际视频尺寸，原画输出"
                DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT -> "等待 GPU 输出能力，原画输出"
                else -> if (decision.sourceIsHdr) "原生 HDR / Dolby Vision 内容，保留原生输出" else "当前尺寸无需放大，原画直出"
            }
            bypass(Anime4KBypassReason.NONE, text); return@synchronized
        }
        val backend = if (input.target.veyraAvailable && input.target.sourceVersion == currentSource.sourceVersion &&
            input.output.gamma in setOf("bt.1886", "srgb") && input.output.inputPrimaries == "bt.709" && !decision.sourceIsHdr) NvidiaVideoBackend.VEYRA_CORE else NvidiaVideoBackend.DRIVER
        val request = Request(currentSource, epoch, NvidiaVideoOptions(decision.scale, decision.hdr, decision.nativeResolutionProcessing, backend), nativeIdentity)
        val previous = owned
        if (previous != null && previous.request.matches(request)) {
            mutableState.update { it.copy(hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled) }
            observeNativeLocked(native, previous)
            return@synchronized
        }
        // Native actor performs source publication admission outside its MPV lock.
        clearOwnedLocked()
        if (!owns(request.source, request.epoch)) return@synchronized
        val token = player.setNvidiaVideoEnhancementIfSourceSnapshot(request.source, request.options, request.nativeIdentity)
        if (token == null) {
            mutableState.value = DesktopVideoEnhancementState(identity, source!!.sourceVersion, true, available,
                statusText = "当前视频源已切换，等待新画面", gpuName = native.gpuName,
                hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled)
            return@synchronized
        }
        owned = Owned(request, token)
        clearing = null
        mutableState.value = DesktopVideoEnhancementState(identity, source!!.sourceVersion, true, available,
            pending = true, bypassReason = Anime4KBypassReason.NONE, statusText = "正在请求 NVIDIA 硬件增强",
            gpuName = native.gpuName, hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled)
        observeNativeLocked(player.nvidiaVideoState.value, checkNotNull(owned))
    }

    private fun observeNative(native: NvidiaVideoState): Unit = synchronized(ownershipLock) {
        if (closed.get()) return@synchronized
        val current = owned
        if (current == null) observeClearingLocked(native) else observeNativeLocked(native, current)
    }

    private fun retireClearingLocked(current: Clearing) {
        if (clearing !== current) return
        clearing = null
        if (!closed.get() && current.reportedError != null) mutableState.update {
            if (it.sourceVersion == current.source.sourceVersion && it.error == current.reportedError)
                it.copy(error = null, unavailableReason = null, statusText = "当前硬件增强配置已更换，等待当前输出")
            else it
        }
    }

    private fun observeClearingLocked(native: NvidiaVideoState) {
        val current = clearing ?: return
        if (closed.get()) return
        var stillCurrent = false
        // Only short UI publication under the existing source lock. No new native
        // command, worker, source owner, or admission inferred from version + 1.
        player.admitSourceSnapshot(current.source) {
            if (!owns(current.source, current.epoch) ||
                player.nvidiaVideoState.value.configurationVersion != current.token) return@admitSourceSnapshot
            stillCurrent = true
            // A queued old StateFlow sample must not retire a still-current clear.
            if (native.configurationVersion != current.token || native.sourceVersion != current.source.sourceVersion) return@admitSourceSnapshot
            if (native.error != null && mutableState.value.sourceVersion == current.source.sourceVersion) {
                current.reportedError = native.error
                mutableState.update { it.withNvidiaObservation(native) }
            }
        }
        if (!stillCurrent) retireClearingLocked(current)
    }

    private fun observeNativeLocked(native: NvidiaVideoState, current: Owned) {
        if (owned !== current || !owns(current.request.source, current.request.epoch)) return
        if (native.configurationVersion != current.token || native.sourceVersion != current.request.source.sourceVersion) {
            mutableState.update { it.copy(active = false, pending = false, error = null, unavailableReason = null,
                driverVsrAccepted = false, driverHdrAccepted = false, hdrConversionActive = false, nativeResolutionAttemptAccepted = false,
                statusText = "当前硬件增强配置已更换，等待当前输出") }
            return
        }
        mutableState.update { it.withNvidiaObservation(native) }
    }

    private fun clearOwnedLocked() {
        val previous = owned ?: return
        owned = null
        clearing = null
        val receipt = player.clearNvidiaVideoEnhancementWithReceipt(previous.token) ?: return
        val source = receipt.source ?: return
        if (source.sourceVersion == previous.request.source.sourceVersion && source.source == previous.request.source.source &&
            owns(source, previous.request.epoch)) clearing = Clearing(source, previous.request.epoch, receipt.configurationVersion)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            scope.cancel()
            synchronized(ownershipLock) {
                clearOwnedLocked()
                clearing = null
                mutableState.value = DesktopVideoEnhancementState(requested = automaticEnabled.value,
                    statusText = "视频增强会话已关闭")
            }
        }
    }
}
