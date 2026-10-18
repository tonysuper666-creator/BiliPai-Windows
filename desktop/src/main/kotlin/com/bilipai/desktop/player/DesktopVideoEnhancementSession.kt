package com.bilipai.desktop.player

import com.android.purebilibili.feature.anime4k.Anime4KBypassReason
import com.bilipai.desktop.nvidia.DesktopNvidiaActivationState
import com.bilipai.desktop.nvidia.DesktopNvidiaAdaptiveGuard
import com.bilipai.desktop.nvidia.DesktopNvidiaEnvironment
import com.bilipai.desktop.nvidia.DesktopNvidiaMode
import com.bilipai.desktop.nvidia.DesktopNvidiaObservedGpu
import com.bilipai.desktop.nvidia.DesktopNvidiaStatusInput
import com.bilipai.desktop.nvidia.DesktopNvidiaStatusKind
import com.bilipai.desktop.nvidia.DesktopNvidiaStatusText
import com.bilipai.desktop.nvidia.DesktopNvidiaVideoBudget
import com.bilipai.desktop.nvidia.GUARD_HEALTHY_CLEAR_MILLIS
import com.bilipai.desktop.nvidia.NVIDIA_HEALTH_BACKEND_VEYRA
import com.bilipai.desktop.nvidia.NVIDIA_HEALTH_RUNTIME_UNKNOWN
import com.bilipai.desktop.nvidia.NvidiaCapability
import com.bilipai.desktop.nvidia.NvidiaCapabilityNote
import com.bilipai.desktop.nvidia.NvidiaGiveUpReason
import com.bilipai.desktop.nvidia.NvidiaGuardDecision
import com.bilipai.desktop.nvidia.NvidiaHealthView
import com.bilipai.desktop.nvidia.NvidiaMemoryEffect
import com.bilipai.desktop.nvidia.NvidiaPlan
import com.bilipai.desktop.nvidia.NvidiaPlaybackHealthSample
import com.bilipai.desktop.nvidia.NvidiaStickyFloor
import com.bilipai.desktop.nvidia.TIER_WORD_ENABLED
import com.bilipai.desktop.nvidia.desktopNvidiaActivationStatusLine
import com.bilipai.desktop.nvidia.desktopNvidiaGpuShortName
import com.bilipai.desktop.nvidia.desktopNvidiaStatusLine
import com.bilipai.desktop.nvidia.desktopNvidiaTierWord
import com.bilipai.desktop.nvidia.nvidiaAdvanceStickyFloor
import com.bilipai.desktop.nvidia.nvidiaDriverPublicX100FromText
import com.bilipai.desktop.nvidia.nvidiaHealthKeyFor
import com.bilipai.desktop.nvidia.nvidiaStickyFloorAfterGuardCommit
import com.bilipai.desktop.nvidia.nvidiaStickyFloorFor
import com.bilipai.desktop.nvidia.planOrNull
import com.bilipai.desktop.nvidia.resolveDesktopNvidiaActivation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the activation gate decided, as a public mirror of the internal activation state so the public
 * state class exposes no internal type. LEGACY means the gate was not evaluated (detection is off or the
 * host is not 64-bit Windows) and today's behaviour and texts apply unchanged.
 */
enum class DesktopVideoEnhancementActivation {
    LEGACY, OFF, PROBING, NO_NVIDIA, UNSUPPORTED, UNIDENTIFIED, STANDBY,
    PAUSED_GAME, PAUSED_BATTERY, PAUSED_REMOTE,
    FAILED_REMEMBERED, FAILED_THIS_VIDEO, FAILED_EXTENDED_RANGE, ALLOWED,
}

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
    /** Actual selected processing route; saved shared-core quality is unavailable on DRIVER. */
    val backend: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,
    val srEnabledRequested: Boolean = true,
    val requestedIntensityPercent: Int = 100,
    val failureDiagnostic: NvidiaVideoFailureDiagnostic? = null,
    /** The activation gate's verdict; LEGACY keeps every pre-detection behaviour. */
    val activation: DesktopVideoEnhancementActivation = DesktopVideoEnhancementActivation.LEGACY,
    /** Short GPU name for display ("RTX 5080"), or null while it is unknown. */
    val gpuSummary: String? = null,
    /** Quality level requested for the owned configuration; null when none is owned. Requested, never proven. */
    val requestedQualityLevel: Int? = null,
    /** True after the adaptive guard lowered the request for this video: a quality level, or only the HDR conversion. */
    val steppedDown: Boolean = false,
    /** True when the guard lowered the quality level itself. Dropping only the HDR conversion leaves it false. */
    val qualityLowered: Boolean = false,
    /** Submitted but no displayed frame for a long time. Changes the wording only, never counts as proof or failure. */
    val proofDelayed: Boolean = false,
    /** The route the controls should assume: the owned request's route, or the shared core when its component is loaded. */
    val route: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,
    /** The tier word, non-null only when the shipped switch is on and the route is the shared core. */
    val tierWord: String? = null,
    /** One short explanatory line (old driver, HDR dropped by the tier), or null. */
    val notice: String? = null,
)

/** Shared projection; the session admits the current source/configuration before calling it. */
internal fun DesktopVideoEnhancementState.withNvidiaObservation(native: NvidiaVideoState,
    capabilityAvailable: Boolean = true): DesktopVideoEnhancementState {
    val hdrPresented = native.hdrConversionActive && hdrDisplayEnabled && native.active &&
        nvidiaHdrTarget(native.targetTransfer, native.targetPrimaries)
    if (native.backend == NvidiaVideoBackend.VEYRA_CORE) {
        val status = when {
            native.error != null -> native.error
            native.unavailableReason != null || !native.veyraAvailable -> "画质增强暂不可用，继续播放原画"
            native.active -> "画质增强画面已显示"
            native.veyraSubmitted -> "已提交画面增强，等待画面反馈"
            else -> "正在准备画质增强"
        }
        return copy(available = native.veyraAvailable && capabilityAvailable, active = native.active, pending = native.pending,
            error = native.error, unavailableReason = native.unavailableReason, statusText = status,
            driverVsrAccepted = false, driverHdrAccepted = false, hdrConversionActive = native.hdrConversionActive,
            nativeResolutionAttemptAccepted = false, veyraAvailable = native.veyraAvailable,
            veyraSubmitted = native.veyraSubmitted, backend = native.backend, srEnabledRequested = native.srEnabledRequested,
            requestedIntensityPercent = native.requestedIntensityPercent, failureDiagnostic = native.failureDiagnostic)
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
    return copy(available = capabilityAvailable && native.unavailableReason == null &&
        (native.gpuVendorId == 0x10de || native.driverVsrAccepted || native.driverHdrAccepted),
        active = native.active, pending = native.pending, error = native.error, unavailableReason = native.unavailableReason,
        statusText = native.gpuName?.let { name -> "$name；$status" } ?: status, gpuName = native.gpuName,
        targetTransfer = native.targetTransfer, targetPrimaries = native.targetPrimaries,
        driverVsrAccepted = native.driverVsrAccepted, driverHdrAccepted = native.driverHdrAccepted,
        hdrConversionActive = native.hdrConversionActive,
        nativeResolutionAttemptAccepted = native.nativeResolutionAttemptAccepted,
        veyraAvailable = native.veyraAvailable, backend = native.backend, srEnabledRequested = native.srEnabledRequested,
            requestedIntensityPercent = native.requestedIntensityPercent, failureDiagnostic = native.failureDiagnostic)
}

/** The public mirror of one internal activation state. Exhaustive over the sealed interface. */
internal fun DesktopNvidiaActivationState.publicKind(): DesktopVideoEnhancementActivation = when (this) {
    is DesktopNvidiaActivationState.Off -> DesktopVideoEnhancementActivation.OFF
    is DesktopNvidiaActivationState.Legacy -> DesktopVideoEnhancementActivation.LEGACY
    is DesktopNvidiaActivationState.Probing -> DesktopVideoEnhancementActivation.PROBING
    is DesktopNvidiaActivationState.NoNvidia -> DesktopVideoEnhancementActivation.NO_NVIDIA
    is DesktopNvidiaActivationState.Unsupported -> DesktopVideoEnhancementActivation.UNSUPPORTED
    is DesktopNvidiaActivationState.Unidentified -> DesktopVideoEnhancementActivation.UNIDENTIFIED
    is DesktopNvidiaActivationState.Standby -> DesktopVideoEnhancementActivation.STANDBY
    is DesktopNvidiaActivationState.PausedGame -> DesktopVideoEnhancementActivation.PAUSED_GAME
    is DesktopNvidiaActivationState.PausedBattery -> DesktopVideoEnhancementActivation.PAUSED_BATTERY
    is DesktopNvidiaActivationState.PausedRemote -> DesktopVideoEnhancementActivation.PAUSED_REMOTE
    is DesktopNvidiaActivationState.FailedRemembered -> DesktopVideoEnhancementActivation.FAILED_REMEMBERED
    is DesktopNvidiaActivationState.FailedThisVideo -> DesktopVideoEnhancementActivation.FAILED_THIS_VIDEO
    is DesktopNvidiaActivationState.FailedExtendedRange -> DesktopVideoEnhancementActivation.FAILED_EXTENDED_RANGE
    is DesktopNvidiaActivationState.Allowed -> DesktopVideoEnhancementActivation.ALLOWED
}

/**
 * Everything one pass of the activation gate learned. Pure data plus the text helpers that turn it into
 * the one status line; the session builds it and the state carries the public part.
 */
internal class DesktopNvidiaGate(
    val state: DesktopNvidiaActivationState,
    val plan: NvidiaPlan?,
    /** Short GPU name for display, or null while the GPU is unknown. */
    val gpuSummary: String?,
    /** The route the controls assume before a request exists: the shared core once its component is loaded. */
    val route: NvidiaVideoBackend,
    val notice: String?,
) {
    val kind: DesktopVideoEnhancementActivation get() = state.publicKind()

    /** True when detection is off: every pre-detection behaviour and text applies. */
    val legacy: Boolean get() = state is DesktopNvidiaActivationState.Legacy

    /** False while no usable GPU is known: probing, none, unsupported or unidentified. */
    val capabilityUsable: Boolean get() = when (state) {
        is DesktopNvidiaActivationState.Probing, is DesktopNvidiaActivationState.NoNvidia,
        is DesktopNvidiaActivationState.Unsupported, is DesktopNvidiaActivationState.Unidentified -> false
        else -> true
    }

    /** True for every state that makes no request and bypasses before any source check. The switch is handled before the gate. */
    val bypassesBeforeSourceChecks: Boolean get() = when (state) {
        is DesktopNvidiaActivationState.Probing, is DesktopNvidiaActivationState.NoNvidia,
        is DesktopNvidiaActivationState.Unsupported, is DesktopNvidiaActivationState.Unidentified,
        is DesktopNvidiaActivationState.PausedGame, is DesktopNvidiaActivationState.PausedBattery,
        is DesktopNvidiaActivationState.PausedRemote, is DesktopNvidiaActivationState.FailedRemembered,
        is DesktopNvidiaActivationState.FailedThisVideo, is DesktopNvidiaActivationState.FailedExtendedRange -> true
        else -> false
    }

    /** The complete line of a state decided before the pipeline runs, or null for legacy and allowed. */
    fun gateLine(): String? = desktopNvidiaActivationStatusLine(state, route)

    fun tierWordFor(routeInUse: NvidiaVideoBackend): String? = desktopNvidiaTierWord(plan?.tier, routeInUse, TIER_WORD_ENABLED)

    /** The line of a status kind, or the reason text under the GPU name when the situation has no kind of its own. */
    fun lineFor(text: String, statusKind: DesktopNvidiaStatusKind?, routeInUse: NvidiaVideoBackend = route): String {
        if (statusKind != null) {
            return desktopNvidiaStatusLine(DesktopNvidiaStatusInput(kind = statusKind, gpuDescription = gpuSummary,
                route = routeInUse, tier = plan?.tier, crossAdapter = plan?.crossAdapter ?: false))
        }
        val summary = gpuSummary ?: return text
        return "${summary}${DesktopNvidiaStatusText.SEPARATOR}${text}"
    }

    /** Legacy keeps its own text exactly; every other state gets the one line. */
    fun statusText(legacyText: String, statusKind: DesktopNvidiaStatusKind?, routeInUse: NvidiaVideoBackend = route): String =
        if (legacy) legacyText else lineFor(legacyText, statusKind, routeInUse)
}

/** Carries the gate's facts on a state. The route is the one in use (the owned request's) or the assumed one. */
internal fun DesktopVideoEnhancementState.withGate(gate: DesktopNvidiaGate, routeInUse: NvidiaVideoBackend): DesktopVideoEnhancementState =
    copy(activation = gate.kind, gpuSummary = gate.gpuSummary, route = routeInUse,
        tierWord = gate.tierWordFor(routeInUse), notice = gate.notice)

/**
 * What the adaptive guard and the failure memory say about the request, as the three inputs of the activation
 * precedence: a remembered failure of this GPU, a failure beyond the super resolution box for this source, and a
 * source the guard gave up. None changes nothing.
 */
internal data class DesktopNvidiaGateMemory(
    val failureRemembered: Boolean = false,
    val extendedRangeFailed: Boolean = false,
    val guardGaveUp: Boolean = false,
) {
    companion object {
        val None: DesktopNvidiaGateMemory = DesktopNvidiaGateMemory()
    }
}

/** HDR is dropped first when the tier or the known driver does not allow it; SR is unaffected. */
internal fun nvidiaEffectiveHdrMode(preferences: DesktopNvidiaVideoPreferences, plan: NvidiaPlan?): DesktopNvidiaVideoHdrMode =
    if (plan != null && preferences.hdrMode == DesktopNvidiaVideoHdrMode.AUTO && !plan.hdrAllowedFor(preferences.srEnabled)) {
        DesktopNvidiaVideoHdrMode.OFF
    } else preferences.hdrMode

/** The one explanatory line shown next to the status when the plan changed what the user asked for. */
internal fun nvidiaNoticeFor(plan: NvidiaPlan?, preferences: DesktopNvidiaVideoPreferences): String? {
    if (plan == null) return null
    val hdrWanted = preferences.hdrMode == DesktopNvidiaVideoHdrMode.AUTO && preferences.srEnabled
    return when {
        hdrWanted && !plan.driverHdrOk -> DesktopNvidiaStatusText.HDR_DRIVER_OLD
        hdrWanted && !plan.hdrWithSrAllowed -> DesktopNvidiaStatusText.TIER_DENIES_HDR
        NvidiaCapabilityNote.DRIVER_ADVISORY in plan.notes -> DesktopNvidiaStatusText.DRIVER_ADVISORY
        else -> null
    }
}

/**
 * The activation gate (design 8.1), pure. Detection off (the capability was never evaluated) is Legacy whatever the
 * switch says, so the existing behaviour and texts are kept exactly. Otherwise the shared precedence decides.
 * componentAvailable is whether the shared core is loaded; it only selects the route the controls assume.
 * displayAdapterLuidHex is the adapter of the video window's monitor, when known: another adapter than the
 * selected GPU lowers the plan's tier one step (cross-adapter). memory carries the guard and failure memory.
 */
internal fun resolveDesktopNvidiaGate(
    capability: NvidiaCapability,
    environment: DesktopNvidiaEnvironment,
    observed: DesktopNvidiaObservedGpu?,
    enabled: Boolean,
    sourceLoaded: Boolean,
    preferences: DesktopNvidiaVideoPreferences,
    componentAvailable: Boolean,
    displayAdapterLuidHex: String? = null,
    memory: DesktopNvidiaGateMemory = DesktopNvidiaGateMemory.None,
): DesktopNvidiaGate {
    val state: DesktopNvidiaActivationState = if (capability is NvidiaCapability.NotEvaluated) {
        DesktopNvidiaActivationState.Legacy
    } else {
        resolveDesktopNvidiaActivation(
            mode = if (enabled) DesktopNvidiaMode.AUTO else DesktopNvidiaMode.OFF,
            capability = capability,
            environment = environment,
            observedGpu = observed,
            sourceLoaded = sourceLoaded,
            failureRemembered = memory.failureRemembered,
            extendedRangeFailedForSource = memory.extendedRangeFailed,
            guardGaveUpForSource = memory.guardGaveUp,
            displayAdapterLuidHex = displayAdapterLuidHex,
        )
    }
    val plan = state.planOrNull
    val description: String? = when (state) {
        is DesktopNvidiaActivationState.Off -> state.gpuDescription
        is DesktopNvidiaActivationState.Unsupported -> state.gpu?.description
        else -> plan?.gpuDescription
    }
    val route = if (componentAvailable) NvidiaVideoBackend.VEYRA_CORE else NvidiaVideoBackend.DRIVER
    return DesktopNvidiaGate(state, plan, description?.let { desktopNvidiaGpuShortName(it) }, route,
        nvidiaNoticeFor(plan, preferences))
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
    private val enhancementPreferences: StateFlow<DesktopNvidiaVideoPreferences>? = null,
) : AutoCloseable {
    private data class Label(val key: String? = null, val version: Long = 0, val epoch: Long = 0)
    private data class Frame(val ready: Boolean, val hasVideo: Boolean, val audioOnly: Boolean, val ended: Boolean, val failed: Boolean, val nativeIdentity: PlayerNativeTrackIdentity?)
    /** What the GPU monitor knows, and a counter the guard raises whenever it decided something that needs a new request. */
    private data class Policy(val capability: NvidiaCapability, val environment: DesktopNvidiaEnvironment,
        val observed: DesktopNvidiaObservedGpu?, val guardEpoch: Long = 0L)
    private data class Settings(val preferences: DesktopNvidiaVideoPreferences, val started: Boolean, val pip: Boolean,
        val policy: Policy) {
        val enabled: Boolean get() = preferences.enabled
    }
    private data class Target(val sourceVersion: Long, val transfer: String?, val primaries: String?,
        val nativeResolutionPatchAvailable: Boolean, val veyraAvailable: Boolean)
    private data class Input(val settings: Settings, val label: Label, val frame: Frame, val output: PlayerVideoOutputState, val target: Target)
    private class Request(val source: OwnedPlaybackSourceSnapshot, val epoch: Long, val options: NvidiaVideoOptions,
        val nativeIdentity: PlayerNativeTrackIdentity, val attempt: Int = 0) {
        fun matches(other: Request) = epoch == other.epoch && options == other.options &&
            source.sourceVersion == other.source.sourceVersion && source.source == other.source.source &&
            nativeIdentity == other.nativeIdentity && attempt == other.attempt
    }
    private class Owned(val request: Request, val token: Long)
    private class Clearing(val source: OwnedPlaybackSourceSnapshot, val epoch: Long, val token: Long) {
        var reportedError: String? = null
    }
    private val label = MutableStateFlow(Label(epoch = sessionEpoch()))
    private val mutableState = MutableStateFlow(DesktopVideoEnhancementState(
        requested = enhancementPreferences?.value?.enabled ?: automaticEnabled.value))
    val state: StateFlow<DesktopVideoEnhancementState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean()
    private val ownershipLock = Any()
    private var owned: Owned? = null
    private var clearing: Clearing? = null
    // The latest gate and the proof-delay bookkeeping are read and written only under ownershipLock.
    // Declared before init, because the collectors start there and run on another thread.
    private var gate: DesktopNvidiaGate = DesktopNvidiaGate(DesktopNvidiaActivationState.Legacy, null, null,
        NvidiaVideoBackend.DRIVER, null)
    private var submittedToken: Long = -1L
    private var submittedSinceNanos: Long = 0L
    private var proofTimer: Job? = null
    // The highest upscale bucket already used for the current source (design 6.5), under the same lock.
    private var stickyFloor: NvidiaStickyFloor = NvidiaStickyFloor()
    // The adaptive guard keeps its per-source state under the same lock. guardEpoch is raised whenever the guard decided
    // something that needs a new request; it joins the policy flow, so the next pass of the decision sees it.
    private val guard = DesktopNvidiaAdaptiveGuard()
    private val guardEpoch = MutableStateFlow(0L)
    private var guardTimer: Job? = null
    private var healthyTimer: Job? = null
    private var healthyTimerToken: Long = -1L
    // The failure-memory key of the latest decision pass and of the owned request, under the same lock.
    private var lastMemoryKey: String? = null
    private var healthKey: String? = null
    private var wasEnabled = false

    init {
        scope.launch {
            val preferenceSnapshots = enhancementPreferences ?: automaticEnabled.map { DesktopNvidiaVideoPreferences(enabled = it) }
            val monitor = player.gpuCapability
            val monitorPolicy = combine(monitor.capability, monitor.environment, monitor.observed) { capability, environment, observed ->
                Policy(capability, environment, observed)
            }
            val policy = combine(monitorPolicy, guardEpoch) { snapshot, epoch -> snapshot.copy(guardEpoch = epoch) }
            val settings = combine(preferenceSnapshots, hostStarted, pip, policy) { preferences, started, inPip, activationPolicy ->
                Settings(preferences, started, inPip, activationPolicy)
            }
            // A completed native load keeps a distinct receipt even if transient loading/zero sizes are conflated.
            val frame = player.state.map { Frame(it.ready, it.videoCodec != null, it.audioOnly, it.ended, it.error != null, it.nativeTrackIdentity) }.distinctUntilChanged()
            // Output format/driver ACK cannot reconfigure its own processing.
            // Only the actual display target and loaded binary patch identity are decision inputs.
            val target = player.nvidiaVideoState.map { Target(it.sourceVersion, it.targetTransfer, it.targetPrimaries, it.nativeResolutionPatchAvailable, it.veyraAvailable) }.distinctUntilChanged()
            combine(settings, label, frame, player.videoOutput, target) { preferences, identity, playback, output, destination ->
                Input(preferences, identity, playback, output, destination)
            }.distinctUntilChanged().collect(::apply)
        }
        scope.launch {
            player.nvidiaVideoState.collect { native ->
                confirmObservedGpu(native)
                observeNative(native)
            }
        }
        scope.launch {
            player.playbackHealth.collect { sample -> observePlayback(sample) }
        }
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

    /** User-requested re-detection of the GPU: the monitor marks its verdict stale and probes again in the background.
     * The failure memory of the current GPU is cleared first. Nothing is requested or withdrawn here; the new verdict
     * and the cleared memory reach the gate through the policy flow. */
    fun redetectGpu() {
        if (closed.get()) return
        // A new detection is a fresh start: the remembered failure of this GPU and the per-source give-up are forgotten.
        // The memory file was read ahead on the probe thread of the monitor, so this call normally does no disk read.
        synchronized(ownershipLock) {
            forgetFailuresLocked()
            guardEpoch.value = guardEpoch.value + 1
        }
        player.gpuCapability.redetect()
    }

    /** A failed probe never means "no GPU". The player's own logged identity must pass the physical check before a
     * request may run, so the verified verdict is fed to the monitor here; the gate reads it from the policy flow. */
    private fun confirmObservedGpu(native: NvidiaVideoState) {
        val monitor = player.gpuCapability
        if (monitor.capability.value !is NvidiaCapability.Unknown) return
        val luid = native.gpuLuidHex ?: return
        try {
            monitor.validateObservedLuid(luid, native.gpuDeviceId, native.gpuName,
                nvidiaDriverPublicX100FromText(native.gpuDriverVersion))
        } catch (_: RuntimeException) {
            // A failed check leaves the verdict unverified, which keeps the request off.
        }
    }

    private fun owns(source: OwnedPlaybackSourceSnapshot, epoch: Long): Boolean = !closed.get() &&
        epoch == sessionEpoch() && player.ownsSourceSnapshot(source) &&
        (source.source.authorizationReceipt?.accountEpoch ?: source.source.primaryAccountEpoch ?: epoch) == epoch

    /** The whole decision is guarded: a thrown exception becomes a bypass with a logged diagnostic and can never end the collector. */
    private fun apply(input: Input): Unit = synchronized(ownershipLock) {
        if (closed.get()) return@synchronized
        try {
            applyLocked(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            failApplyLocked(failure)
        }
    }

    private fun failedThisVideoLine(forGate: DesktopNvidiaGate): String =
        forGate.lineFor(DesktopNvidiaStatusText.FAILED_THIS_VIDEO_TAIL, DesktopNvidiaStatusKind.FAILED_THIS_VIDEO)

    private fun failApplyLocked(failure: RuntimeException) {
        java.util.logging.Logger.getLogger("BiliPai.NvidiaEnhancement").log(java.util.logging.Level.WARNING,
            "The NVIDIA enhancement decision failed; the video keeps its original output.", failure)
        try { clearOwnedLocked() } catch (_: RuntimeException) { owned = null; clearing = null }
        val line = failedThisVideoLine(gate)
        mutableState.update {
            it.copy(active = false, pending = false, hdrConversionActive = false, nativeResolutionAttemptAccepted = false,
                driverVsrAccepted = false, driverHdrAccepted = false, bypassReason = Anime4KBypassReason.NONE,
                unavailableReason = line, statusText = line)
        }
    }

    private fun applyLocked(input: Input) {
        val source = player.currentSourceSnapshot()
        val epoch = input.label.epoch
        val current = source != null && owns(source, epoch) && source.sourceVersion == input.output.sourceVersion
        val identity = if (current) input.label.key.takeIf { input.label.version == source!!.sourceVersion }
            ?: "video:${source!!.sourceVersion}" else null
        val native = player.nvidiaVideoState.value
        // The monitor of the video window: its adapter and output technology feed the remote-output check of the
        // environment poll, and its adapter decides the cross-adapter step-down of the plan.
        val display = input.output.hdrDisplay
        player.gpuCapability.reportDisplayOutput(display.adapterLuidHex, display.outputTechnology)
        // The guard works per source and the failure memory per GPU, driver, runtime and tier. A new source starts the
        // guard from scratch at once, even before the output state of that source has caught up.
        if (source != null && owns(source, epoch)) guard.selectSource("${epoch}:${source.sourceVersion}")
        val memoryKey = memoryKeyFor(input.settings.policy, native)
        lastMemoryKey = memoryKey
        val healthView = if (memoryKey == null) NvidiaHealthView.None else player.gpuCapability.healthStore.view(memoryKey)
        val givenUp = guard.gaveUp
        val memory = DesktopNvidiaGateMemory(
            failureRemembered = healthView.remembered,
            extendedRangeFailed = givenUp == NvidiaGiveUpReason.EXTENDED_RANGE,
            guardGaveUp = givenUp != null && givenUp != NvidiaGiveUpReason.EXTENDED_RANGE)
        val frameGate = resolveDesktopNvidiaGate(input.settings.policy.capability, input.settings.policy.environment,
            input.settings.policy.observed, input.settings.enabled, current, input.settings.preferences, native.veyraAvailable,
            display.adapterLuidHex, memory)
        gate = frameGate
        // The environment is polled only while a plan exists (standby, running or paused) and the host is visible.
        // A pause is entered and left by the poll, and the bypass below clears the owned filter when one is entered.
        player.gpuCapability.setEnvironmentPolling(frameGate.plan != null && (input.settings.started || input.settings.pip))
        val available = native.gpuVendorId == 0x10de && native.currentGpuContext?.startsWith("d3d11") == true &&
            frameGate.capabilityUsable
        fun bypass(reason: Anime4KBypassReason, text: String, statusKind: DesktopNvidiaStatusKind? = null, line: String? = null) {
            clearOwnedLocked()
            val shownText = when {
                frameGate.legacy -> native.gpuName?.let { "$it；$text" } ?: text
                line != null -> line
                else -> frameGate.lineFor(text, statusKind)
            }
            mutableState.value = DesktopVideoEnhancementState(identity, source?.sourceVersion ?: 0,
                input.settings.enabled, available, bypassReason = reason,
                statusText = shownText, gpuName = native.gpuName,
                targetTransfer = native.targetTransfer, targetPrimaries = native.targetPrimaries,
                hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled, veyraAvailable = native.veyraAvailable).withGate(frameGate, frameGate.route)
            observeClearingLocked(player.nvidiaVideoState.value)
        }
        // A bypass that also says the feature cannot run here: the line is the one reason shown.
        fun bypassUnavailable(reason: Anime4KBypassReason, line: String) {
            bypass(reason, line, line = line)
            mutableState.update { it.copy(available = false, unavailableReason = line) }
        }
        if (!input.settings.enabled) {
            // Switching the feature off after it was on gives one fresh attempt when it is switched on again: the
            // per-source steps and the remembered failure are forgotten. The default before the settings are loaded
            // is off too, but it never followed an on, so it forgets nothing.
            if (wasEnabled) {
                wasEnabled = false
                forgetFailuresLocked()
            }
            bypass(Anime4KBypassReason.DISABLED, "NVIDIA 自动增强已关闭，原画直出", DesktopNvidiaStatusKind.OFF); return
        }
        wasEnabled = true
        // Activation gate (design 8.1): after the switch, before any source check. Legacy and the states that may
        // request fall through to the unchanged ladder; every other state makes no request and says why.
        if (frameGate.bypassesBeforeSourceChecks) {
            if (overloadBypassed(frameGate)) {
                bypassUnavailable(Anime4KBypassReason.NONE, frameGate.lineFor(DesktopNvidiaStatusText.GUARD_BYPASS, DesktopNvidiaStatusKind.GUARD_BYPASS)); return
            }
            bypassUnavailable(Anime4KBypassReason.NONE, frameGate.gateLine() ?: DesktopNvidiaStatusText.UNIDENTIFIED); return
        }
        if (!current) { bypass(Anime4KBypassReason.NONE, "等待当前视频源", DesktopNvidiaStatusKind.STANDBY); return }
        if (!input.settings.started && !input.settings.pip) {
            bypass(Anime4KBypassReason.HOST_NOT_STARTED, "播放器当前不可见，原画输出"); return
        }
        if (input.frame.audioOnly) { bypass(Anime4KBypassReason.AUDIO_ONLY, "仅音频播放，无需画面增强"); return }
        if (!input.frame.ready || !input.frame.hasVideo || input.frame.ended || input.frame.failed) {
            bypass(Anime4KBypassReason.NONE, "等待可用视频画面", DesktopNvidiaStatusKind.WAITING); return
        }
        val currentSource = checkNotNull(source)
        val nativeIdentity = input.frame.nativeIdentity
        if (nativeIdentity == null || nativeIdentity.sourceVersion != currentSource.sourceVersion ||
            nativeIdentity.source != currentSource.source) {
            bypass(Anime4KBypassReason.NONE, "等待当前原生视频载入", DesktopNvidiaStatusKind.WAITING); return
        }
        if (!input.settings.preferences.srEnabled &&
            (nvidiaHdrTransfer(input.output.gamma) || input.output.dolbyVisionProfile != null)) {
            bypass(Anime4KBypassReason.NONE, "原生 HDR / Dolby Vision 内容，保留原生输出")
            return
        }
        val sharedCoreAvailable = input.target.veyraAvailable &&
            input.target.sourceVersion == currentSource.sourceVersion &&
            input.output.gamma in setOf("bt.1886", "srgb") && input.output.inputPrimaries == "bt.709" &&
            !nvidiaHdrTransfer(input.output.gamma) && input.output.dolbyVisionProfile == null
        if (!input.settings.preferences.srEnabled && !sharedCoreAvailable) {
            val message = "当前无法独立使用 HDR 增强，继续原画播放"
            bypass(Anime4KBypassReason.NONE, message)
            mutableState.update { it.copy(available = false, unavailableReason = message) }
            return
        }
        val backend = if (sharedCoreAvailable) NvidiaVideoBackend.VEYRA_CORE else NvidiaVideoBackend.DRIVER
        val plan = frameGate.plan
        // The per-source limits of the GPU's plan. Detection off has no plan, hence no budget: today's decision exactly.
        // A failure beyond the super resolution box learned earlier lowers the native-size caps to the documented range.
        val budget = plan?.budget(input.output.frameRate, healthView.extendedRangeDenied)
        // The effective HDR mode goes into the resolver, so a decision never carries HDR that the request would drop.
        fun resolveWith(limits: DesktopNvidiaVideoBudget?): DesktopNvidiaVideoDecision =
            resolveDesktopNvidiaVideoDecision(input.output.inputWidth, input.output.inputHeight,
                input.output.displayWidth, input.output.displayHeight, input.output.maximumTextureDimension,
                input.output.gamma, input.output.dolbyVisionProfile, input.output.hdrDisplay.hdrEnabled,
                input.target.transfer.takeIf { input.target.sourceVersion == currentSource.sourceVersion },
                input.target.primaries.takeIf { input.target.sourceVersion == currentSource.sourceVersion },
                nativeResolutionPatchAvailable = input.target.nativeResolutionPatchAvailable &&
                    input.target.sourceVersion == currentSource.sourceVersion,
                hdrMode = nvidiaEffectiveHdrMode(input.settings.preferences, plan),
                sharedCoreNativeResolutionAvailable = sharedCoreAvailable,
                srEnabled = input.settings.preferences.srEnabled,
                budget = limits)
        // The unbudgeted decision first: a source that needs no processing keeps its outcome and its text. Only a
        // source that needed processing is judged against the budget, which can turn it into OVER_BUDGET or trim its scale.
        val unbudgeted = resolveWith(null)
        val decision = if (budget != null && unbudgeted.needsProcessing) resolveWith(budget) else unbudgeted
        if (!decision.needsProcessing) {
            // Any decision without processing ends the sticky floor of the source.
            stickyFloor = NvidiaStickyFloor()
            val text = when (decision.kind) {
                DesktopNvidiaVideoDecisionKind.WAITING_VIDEO -> "等待实际视频尺寸，原画输出"
                DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT -> "等待 GPU 输出能力，原画输出"
                DesktopNvidiaVideoDecisionKind.OVER_BUDGET -> DesktopNvidiaStatusText.OVER_BUDGET_TAIL
                else -> when {
                    decision.sourceIsHdr -> "原生 HDR / Dolby Vision 内容，保留原生输出"
                    !input.settings.preferences.srEnabled -> "HDR 显示目标或视频条件尚未就绪，原画播放"
                    else -> "当前尺寸无需放大，原画直出"
                }
            }
            val textKind = when (decision.kind) {
                DesktopNvidiaVideoDecisionKind.WAITING_VIDEO, DesktopNvidiaVideoDecisionKind.WAITING_GPU_LIMIT ->
                    DesktopNvidiaStatusKind.WAITING
                DesktopNvidiaVideoDecisionKind.OVER_BUDGET -> DesktopNvidiaStatusKind.OVER_BUDGET
                else -> if (decision.sourceIsHdr || !input.settings.preferences.srEnabled) null else DesktopNvidiaStatusKind.DIRECT
            }
            bypass(Anime4KBypassReason.NONE, text, textKind); return
        }
        // Request build: an invalid combination bypasses this video instead of ending the collector.
        val floorKey = "${epoch}:${currentSource.sourceVersion}"
        var stickyApplied = false
        var qualityLowered = false
        val request = try {
            // The guard starts from what the user, the tier and a learned ceiling allow, then lowers only. The ladder it
            // follows is built from that unlowered request.
            val learnedCap = healthView.learnedQualityCeiling ?: 4
            val unlowered = input.settings.preferences.optionsFor(decision, backend, plan, NvidiaGuardLimits(qualityCap = learnedCap))
            guard.configure(unlowered.qualityLevel, unlowered.hdr,
                backend == NvidiaVideoBackend.VEYRA_CORE && input.settings.preferences.srEnabled,
                budget?.inExtendedRange(input.output.inputWidth, input.output.inputHeight) == true, input.output.frameRate)
            val caps = guard.caps()
            val guardLimits = NvidiaGuardLimits(qualityCap = minOf(caps.qualityCap, learnedCap), hdrAllowed = caps.hdrAllowed)
            val options = input.settings.preferences.optionsFor(decision, backend, plan, guardLimits)
            // Only a lower quality level counts as the guard having lowered the request in words; HDR alone does not.
            qualityLowered = options.qualityLevel < unlowered.qualityLevel
            // The stable bucket is bounded by the budget; the sticky floor then applies to a real upscale only.
            val sized = nvidiaRequestScale(options, decision, budget, input.output.inputWidth, input.output.inputHeight,
                input.output.maximumTextureDimension, nvidiaStickyFloorFor(stickyFloor, floorKey))
            stickyApplied = sized.stickyApplied
            val stableOptions = options.copy(scale = sized.scale).requireValid()
            Request(currentSource, epoch, stableOptions, nativeIdentity, guard.attempt)
        } catch (_: IllegalArgumentException) {
            stickyFloor = NvidiaStickyFloor()
            bypassUnavailable(Anime4KBypassReason.NONE, failedThisVideoLine(frameGate)); return
        }
        if (!frameGate.legacy && !request.options.requiresFilter) {
            // The guard took away the last effect (HDR): nothing is left to process and the video plays unchanged.
            stickyFloor = NvidiaStickyFloor()
            bypass(Anime4KBypassReason.NONE, DesktopNvidiaStatusText.DIRECT_TAIL, DesktopNvidiaStatusKind.DIRECT); return
        }
        stickyFloor = if (stickyApplied) nvidiaAdvanceStickyFloor(stickyFloor, floorKey, true, request.options.scale)
            else NvidiaStickyFloor()
        val previous = owned
        // An unchanged request is kept. So is the owned one while the guard holds a retry or a step for its delay, and the
        // failed one of a source whose colour format the filter rejected: that source is not requested again.
        if (previous != null && (previous.request.matches(request) || guard.holding || guard.colorFailed)) {
            healthKey = memoryKey
            mutableState.update {
                it.copy(hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled).withGate(frameGate, backend)
                    .copy(steppedDown = guard.steppedDown, qualityLowered = qualityLowered)
            }
            observeNativeLocked(native, previous)
            return
        }
        if (guard.colorFailed) {
            // The failed request is gone (a pause or a different decision removed it): the source stays on its original picture.
            bypass(Anime4KBypassReason.NONE, DesktopNvidiaStatusText.COLOR_UNSUPPORTED, DesktopNvidiaStatusKind.COLOR_UNSUPPORTED); return
        }
        // Native actor performs source publication admission outside its MPV lock.
        clearOwnedLocked()
        if (!owns(request.source, request.epoch)) return
        val token = player.setNvidiaVideoEnhancementIfSourceSnapshot(request.source, request.options, request.nativeIdentity)
        if (token == null) {
            mutableState.value = DesktopVideoEnhancementState(identity, source!!.sourceVersion, true, available,
                statusText = frameGate.statusText("当前视频源已切换，等待新画面", DesktopNvidiaStatusKind.WAITING, backend),
                gpuName = native.gpuName,
                hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled).withGate(frameGate, backend)
            return
        }
        owned = Owned(request, token)
        clearing = null
        healthKey = memoryKey
        guard.noteConfiguration(guardNow())
        mutableState.value = DesktopVideoEnhancementState(identity, source!!.sourceVersion, true, available,
            pending = true, bypassReason = Anime4KBypassReason.NONE,
            statusText = frameGate.statusText("正在请求 NVIDIA 硬件增强", DesktopNvidiaStatusKind.PENDING, backend),
            gpuName = native.gpuName, hdrDisplayEnabled = input.output.hdrDisplay.hdrEnabled,
            requestedQualityLevel = request.options.qualityLevel).withGate(frameGate, backend)
            .copy(steppedDown = guard.steppedDown, qualityLowered = qualityLowered)
        observeNativeLocked(player.nvidiaVideoState.value, checkNotNull(owned))
    }

    private fun observeNative(native: NvidiaVideoState): Unit = synchronized(ownershipLock) {
        if (closed.get()) return@synchronized
        val current = owned
        if (current == null) observeClearingLocked(native) else observeNativeLocked(native, current)
    }

    /** The shared projection, then the one status line unless detection is off. */
    private fun observedState(state: DesktopVideoEnhancementState, native: NvidiaVideoState): DesktopVideoEnhancementState {
        val shown = state.withNvidiaObservation(native, gate.capabilityUsable)
        if (gate.legacy) return shown
        // A failure that the guard answers with a retry or a lower rung is silent (design 9.2): until the replacement is
        // installed the video is only being prepared again, so the state carries neither the error nor its diagnostic.
        // The guard itself reads the player's own state, never this one.
        val visible = if (failureAnsweredSilently(native)) shown.copy(error = null, failureDiagnostic = null) else shown
        return visible.copy(statusText = liveStatusLine(visible, native))
    }

    /** The player failed, and the guard holds a busy retry or a step for it. A colour rejection is never answered that way. */
    private fun failureAnsweredSilently(native: NvidiaVideoState): Boolean =
        native.error != null && guard.holding && !guard.colorFailed

    /** A confirmed reason is already a complete sentence. Everything else is one of the shared status kinds;
     * requested is never worded as applied and the driver route never claims a confirmed result. */
    private fun liveStatusLine(shown: DesktopVideoEnhancementState, native: NvidiaVideoState): String {
        native.unavailableReason?.let { return it }
        val current = gate
        val sharedCore = native.backend == NvidiaVideoBackend.VEYRA_CORE
        val hdrPresented = native.hdrConversionActive && shown.hdrDisplayEnabled && native.active &&
            nvidiaHdrTarget(native.targetTransfer, native.targetPrimaries)
        // Informational only: the filter needs Direct3D 11 surfaces, so another decoder explains a request that never
        // gets going. It replaces only the in-progress lines and never an error, a reason or a displayed result.
        val notD3d11Decode = player.videoOutput.value.d3d11Decode == false
        val kind = when {
            failureAnsweredSilently(native) -> DesktopNvidiaStatusKind.PENDING
            native.error != null ->
                if (native.failureDiagnostic?.code == 5) DesktopNvidiaStatusKind.COLOR_UNSUPPORTED else DesktopNvidiaStatusKind.FAILED_THIS_VIDEO
            native.active && shown.qualityLowered && shown.requestedQualityLevel != null -> DesktopNvidiaStatusKind.STEPPED_DOWN
            native.active -> DesktopNvidiaStatusKind.ACTIVE
            notD3d11Decode -> DesktopNvidiaStatusKind.NON_D3D11_DECODE
            sharedCore && native.veyraSubmitted ->
                if (shown.proofDelayed) DesktopNvidiaStatusKind.PROOF_DELAYED else DesktopNvidiaStatusKind.SUBMITTED
            else -> DesktopNvidiaStatusKind.PENDING
        }
        return desktopNvidiaStatusLine(DesktopNvidiaStatusInput(kind = kind, gpuDescription = current.gpuSummary,
            route = native.backend, tier = current.plan?.tier, crossAdapter = current.plan?.crossAdapter ?: false,
            inputWidth = native.inputWidth, inputHeight = native.inputHeight,
            outputWidth = native.outputWidth, outputHeight = native.outputHeight,
            hdrPresented = hdrPresented, qualityLevel = shown.requestedQualityLevel))
    }

    private fun retireClearingLocked(current: Clearing) {
        if (clearing !== current) return
        clearing = null
        if (!closed.get() && current.reportedError != null) mutableState.update {
            if (it.sourceVersion == current.source.sourceVersion && it.error == current.reportedError)
                it.copy(error = null, unavailableReason = null, failureDiagnostic = null,
                    statusText = gate.statusText("当前硬件增强配置已更换，等待当前输出", DesktopNvidiaStatusKind.WAITING))
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
                mutableState.update { observedState(it, native) }
            }
        }
        if (!stillCurrent) retireClearingLocked(current)
    }

    private fun observeNativeLocked(native: NvidiaVideoState, current: Owned) {
        if (owned !== current || !owns(current.request.source, current.request.epoch)) return
        if (native.configurationVersion != current.token || native.sourceVersion != current.request.source.sourceVersion) {
            mutableState.update { it.copy(active = false, pending = false, error = null, unavailableReason = null, failureDiagnostic = null,
                driverVsrAccepted = false, driverHdrAccepted = false, hdrConversionActive = false, nativeResolutionAttemptAccepted = false,
                proofDelayed = false,
                statusText = gate.statusText("当前硬件增强配置已更换，等待当前输出", DesktopNvidiaStatusKind.WAITING)) }
            return
        }
        val delayed = proofDelayedFor(native, current)
        // The guard answers a failure first: a retry or a step it starts is silent, so the line is built after its answer.
        observeGuardLocked(native, current)
        mutableState.update { observedState(it.copy(proofDelayed = delayed), native) }
    }

    // ---- Adaptive guard (design 9) ----

    /** Milliseconds on the monotonic clock, the clock domain of the guard and of the playback health samples. */
    private fun guardNow(): Long = System.nanoTime() / 1_000_000L

    /** The failure-memory key of the shared core on this GPU, or null when the route, the GPU or the runtime is not known. */
    private fun memoryKeyFor(policy: Policy, native: NvidiaVideoState): String? {
        if (!native.veyraAvailable) return null
        val runtime = player.veyraInstalledIdentity.value?.profileSha256 ?: NVIDIA_HEALTH_RUNTIME_UNKNOWN
        return nvidiaHealthKeyFor(policy.capability, policy.observed, runtime, NVIDIA_HEALTH_BACKEND_VEYRA)
    }

    /** True when the guard stopped this source because of smoothness, which has its own wording. */
    private fun overloadBypassed(forGate: DesktopNvidiaGate): Boolean {
        val reason = guard.gaveUp
        return forGate.state is DesktopNvidiaActivationState.FailedThisVideo && reason != null && reason.overload
    }

    /** One fresh attempt: the per-source state and the remembered failure of the current GPU are forgotten. */
    private fun forgetFailuresLocked() {
        guard.resetForSwitch()
        guardTimer?.cancel()
        guardTimer = null
        val key = lastMemoryKey
        if (key != null) player.gpuCapability.healthStore.clearKey(key)
    }

    /** Reads the player's own failure of the owned configuration. Detection off keeps every pre-detection behaviour. */
    private fun observeGuardLocked(native: NvidiaVideoState, current: Owned) {
        if (gate.legacy) return
        trackHealthyLocked(native, current)
        val diagnostic = native.failureDiagnostic ?: return
        if (native.error == null) return
        handleGuardDecisionLocked(guard.onFailure(current.token, diagnostic.code, diagnostic.stage, guardNow()))
    }

    private fun handleGuardDecisionLocked(decision: NvidiaGuardDecision) {
        when (decision) {
            is NvidiaGuardDecision.NoChange -> Unit
            is NvidiaGuardDecision.Retry -> scheduleGuardCommitLocked(decision.delayMillis)
            is NvidiaGuardDecision.StepDown -> scheduleGuardCommitLocked(decision.delayMillis)
            is NvidiaGuardDecision.GiveUp -> {
                recordGuardMemoryLocked(decision)
                guardEpoch.value = guardEpoch.value + 1
            }
        }
    }

    private fun recordGuardMemoryLocked(decision: NvidiaGuardDecision.GiveUp) {
        val key = healthKey ?: return
        val store = player.gpuCapability.healthStore
        when (decision.memory) {
            NvidiaMemoryEffect.NONE -> Unit
            NvidiaMemoryEffect.DEVICE_FAILURE -> store.recordDeviceFailure(key, decision.atLowestRung, decision.qualityLevel)
            NvidiaMemoryEffect.REMEMBER_NOW -> store.recordRemembered(key)
            NvidiaMemoryEffect.EXTENDED_RANGE_DENIED -> store.recordExtendedRangeDenied(key)
        }
    }

    /** Waits for the delay of a retry or a step, then commits it. A commit raises the epoch, which builds the next request. */
    private fun scheduleGuardCommitLocked(delayMillis: Long) {
        guardTimer?.cancel()
        guardTimer = scope.launch {
            delay(maxOf(0L, delayMillis) + GUARD_COMMIT_SLACK_MILLIS)
            synchronized(ownershipLock) {
                if (!closed.get()) {
                    val stepsBefore = guard.transitionCount
                    if (guard.commitPending(guardNow())) {
                        // A committed step ends the sticky floor of the old rung (design 6.5); a busy retry keeps it, so that the
                        // retry resends identical options.
                        stickyFloor = nvidiaStickyFloorAfterGuardCommit(stickyFloor, stepsBefore, guard.transitionCount)
                        guardEpoch.value = guardEpoch.value + 1
                    } else {
                        val due = guard.pendingDueAtMillis
                        if (due != null) scheduleGuardCommitLocked(due - guardNow())
                    }
                }
            }
        }
    }

    /** The streak of hard failures ends after the player's own flag held for thirty seconds. The flag is only read. */
    private fun trackHealthyLocked(native: NvidiaVideoState, current: Owned) {
        val key = healthKey ?: return
        val held = native.active && native.error == null
        if (guard.observeProofHeld(current.token, held, guardNow())) player.gpuCapability.healthStore.recordHealthy(key)
        if (held && healthyTimerToken != current.token) {
            healthyTimerToken = current.token
            healthyTimer?.cancel()
            healthyTimer = scope.launch {
                delay(GUARD_HEALTHY_CLEAR_MILLIS + GUARD_COMMIT_SLACK_MILLIS)
                synchronized(ownershipLock) {
                    if (!closed.get() && owned === current) observeNativeLocked(player.nvidiaVideoState.value, current)
                }
            }
        }
    }

    /** One reading of the video output counters. Only a running filter of the current source is ever judged. */
    private fun observePlayback(sample: NvidiaPlaybackHealthSample): Unit = synchronized(ownershipLock) {
        if (closed.get() || gate.legacy) return@synchronized
        val current = owned
        if (current == null || sample.sourceVersion != current.request.source.sourceVersion) {
            guard.resetPlaybackWindow()
            return@synchronized
        }
        val native = player.nvidiaVideoState.value
        val running = native.configurationVersion == current.token && native.error == null && native.unavailableReason == null &&
            (native.veyraSubmitted || native.driverVsrAccepted || native.driverHdrAccepted || native.active)
        if (!running) {
            guard.resetPlaybackWindow()
            return@synchronized
        }
        handleGuardDecisionLocked(guard.observePlayback(sample))
    }

    /** Submitted but not shown for PROOF_DELAY_MILLIS: wording only. Detection off never reports it, and a state the
     * player has already failed or withdrawn never counts. It does not feed any gate, proof or failure memory. */
    private fun proofDelayedFor(native: NvidiaVideoState, current: Owned): Boolean {
        val waiting = !gate.legacy && native.backend == NvidiaVideoBackend.VEYRA_CORE && native.veyraSubmitted &&
            !native.active && native.error == null && native.unavailableReason == null
        if (!waiting) {
            submittedToken = -1L
            return false
        }
        val now = System.nanoTime()
        if (submittedToken != current.token) {
            submittedToken = current.token
            submittedSinceNanos = now
            scheduleProofCheckLocked(current)
        }
        return now - submittedSinceNanos >= PROOF_DELAY_NANOS
    }

    /** Re-reads the player's state once the delay has passed, because a stuck submission produces no further samples. */
    private fun scheduleProofCheckLocked(current: Owned) {
        proofTimer?.cancel()
        proofTimer = scope.launch {
            delay(PROOF_DELAY_MILLIS + PROOF_RECHECK_SLACK_MILLIS)
            synchronized(ownershipLock) {
                if (!closed.get() && owned === current) observeNativeLocked(player.nvidiaVideoState.value, current)
            }
        }
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
            // A write that is still waiting for its interval is done now; the memory never throws. This is the one deliberate
            // synchronous write (a file of at most 16 KB, on the closing thread); every other write is on the writer thread.
            player.gpuCapability.healthStore.flushNow()
            synchronized(ownershipLock) {
                clearOwnedLocked()
                clearing = null
                mutableState.value = DesktopVideoEnhancementState(requested = enhancementPreferences?.value?.enabled ?: automaticEnabled.value,
                    statusText = "视频增强会话已关闭")
                // A pass that was already running has finished (it holds this lock) and every later one sees the closed session,
                // so nothing can start the environment poll again.
                player.gpuCapability.setEnvironmentPolling(false)
            }
        }
    }

    private companion object {
        const val PROOF_DELAY_MILLIS = 8_000L
        const val PROOF_DELAY_NANOS = 8_000_000_000L
        const val PROOF_RECHECK_SLACK_MILLIS = 50L
        const val GUARD_COMMIT_SLACK_MILLIS = 50L
    }
}
