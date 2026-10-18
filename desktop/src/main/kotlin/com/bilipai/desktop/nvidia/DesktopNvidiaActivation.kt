package com.bilipai.desktop.nvidia

// Pure activation precedence and pause hysteresis. No I/O and no clock: callers pass timestamps
// in, so every rule is testable with plain numbers. There is no manual mode: the persisted intent
// is only on or off, and everything else is derived.

/** Wider game protection: also pause for a busy or presentation state and a monitor-covering foreground window. */
internal const val GAME_YIELD_BROAD: Boolean = true

/** Enter a pause after this many consecutive positive polls. */
internal const val NVIDIA_PAUSE_ENTER_POLLS: Int = 2

/** Leave a pause only after the signal has stayed clear this long. */
internal const val NVIDIA_PAUSE_LEAVE_CLEAR_MILLIS: Long = 5_000L

/** Minimum spacing between two filter reconfigurations. */
internal const val NVIDIA_MIN_RECONFIGURE_INTERVAL_MILLIS: Long = 3_000L

/** Derived from the stored on or off intent. */
internal enum class DesktopNvidiaMode { OFF, AUTO }

/** SHQueryUserNotificationState values that matter for game yielding. */
internal object DesktopNvidiaNotificationState {
    const val BUSY: Int = 2
    const val RUNNING_D3D_FULL_SCREEN: Int = 3
    const val PRESENTATION_MODE: Int = 4
}

/** Smoothed (or raw) environment flags. Pauses clear by themselves when the condition clears. */
internal data class DesktopNvidiaEnvironment(
    val gameLike: Boolean = false,
    val onBattery: Boolean = false,
    val remoteOrVirtualOutput: Boolean = false,
)

/** What the foreground window says. Null or unknown inputs never count as a game. */
internal data class DesktopNvidiaForegroundSnapshot(
    val foregroundProcessId: Int?,
    val ownProcessId: Int,
    /** SHQueryUserNotificationState result, or null when unavailable. */
    val notificationState: Int?,
    val isShellOrDesktopWindow: Boolean,
    val coversWholeMonitor: Boolean,
)

/**
 * Game-like means another process owns the foreground and either the notification state is busy,
 * full-screen D3D or presentation mode, or its window is not the shell and covers a whole monitor.
 * The application's own full screen is never a yield. With broad false only full-screen D3D counts.
 * This is a courtesy, not a guarantee.
 */
internal fun desktopNvidiaGameLike(
    snapshot: DesktopNvidiaForegroundSnapshot,
    broad: Boolean = GAME_YIELD_BROAD,
): Boolean {
    val foreground = snapshot.foregroundProcessId ?: return false
    if (foreground == snapshot.ownProcessId) return false
    val state = snapshot.notificationState
    if (!broad) return state == DesktopNvidiaNotificationState.RUNNING_D3D_FULL_SCREEN
    if (state == DesktopNvidiaNotificationState.BUSY ||
        state == DesktopNvidiaNotificationState.RUNNING_D3D_FULL_SCREEN ||
        state == DesktopNvidiaNotificationState.PRESENTATION_MODE
    ) {
        return true
    }
    return !snapshot.isShellOrDesktopWindow && snapshot.coversWholeMonitor
}

/** Enter after two consecutive positive polls, leave after five seconds of clear signal. */
internal data class DesktopNvidiaHysteresis(
    val active: Boolean = false,
    val consecutiveHits: Int = 0,
    val clearSinceMillis: Long? = null,
) {
    fun advance(signal: Boolean, nowMillis: Long): DesktopNvidiaHysteresis {
        if (signal) {
            if (active) return DesktopNvidiaHysteresis(true, consecutiveHits, null)
            val hits = consecutiveHits + 1
            return DesktopNvidiaHysteresis(hits >= NVIDIA_PAUSE_ENTER_POLLS, hits, null)
        }
        if (!active) return DesktopNvidiaHysteresis()
        val since = clearSinceMillis
        val clearStart = if (since == null || nowMillis < since) nowMillis else since
        if (nowMillis - clearStart >= NVIDIA_PAUSE_LEAVE_CLEAR_MILLIS) return DesktopNvidiaHysteresis()
        return DesktopNvidiaHysteresis(true, 0, clearStart)
    }
}

/** True when enough time passed since the last reconfiguration; a clock that went backwards allows it. */
internal fun nvidiaReconfigurationAllowed(lastReconfigurationMillis: Long?, nowMillis: Long): Boolean {
    if (lastReconfigurationMillis == null) return true
    val elapsed = nowMillis - lastReconfigurationMillis
    return elapsed < 0L || elapsed >= NVIDIA_MIN_RECONFIGURE_INTERVAL_MILLIS
}

/** One hysteresis per pause reason; environment is the smoothed result the resolver reads. */
internal data class DesktopNvidiaEnvironmentTracker(
    val game: DesktopNvidiaHysteresis = DesktopNvidiaHysteresis(),
    val battery: DesktopNvidiaHysteresis = DesktopNvidiaHysteresis(),
    val remote: DesktopNvidiaHysteresis = DesktopNvidiaHysteresis(),
) {
    val environment: DesktopNvidiaEnvironment
        get() = DesktopNvidiaEnvironment(game.active, battery.active, remote.active)

    fun advance(raw: DesktopNvidiaEnvironment, nowMillis: Long): DesktopNvidiaEnvironmentTracker =
        DesktopNvidiaEnvironmentTracker(
            game = game.advance(raw.gameLike, nowMillis),
            battery = battery.advance(raw.onBattery, nowMillis),
            remote = remote.advance(raw.remoteOrVirtualOutput, nowMillis),
        )
}

/** The player-logged GPU when the probe failed. verifiedPhysical comes from the PnP check of the logged LUID. */
internal data class DesktopNvidiaObservedGpu(
    val description: String?,
    val deviceId: Int?,
    val luidHex: String?,
    val verifiedPhysical: Boolean,
    val driverPublicX100: Int? = null,
)

internal sealed interface DesktopNvidiaActivationState {
    /** gpuDescription is set when the GPU is already known. */
    data class Off(val gpuDescription: String? = null) : DesktopNvidiaActivationState

    /** Detection is disabled: today's behaviour exactly. */
    data object Legacy : DesktopNvidiaActivationState

    data object Probing : DesktopNvidiaActivationState

    data object NoNvidia : DesktopNvidiaActivationState

    data class Unsupported(val reason: NvidiaUnsupportedReason, val gpu: NvidiaGpuInfo? = null) :
        DesktopNvidiaActivationState

    data object Unidentified : DesktopNvidiaActivationState

    /** Allowed, but no file is loaded yet. */
    data class Standby(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    data class PausedGame(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    data class PausedBattery(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    data class PausedRemote(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    data class FailedRemembered(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    data class FailedThisVideo(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    data class FailedExtendedRange(val plan: NvidiaPlan) : DesktopNvidiaActivationState

    /** The existing decision ladder may run and a request may be made. */
    data class Allowed(val plan: NvidiaPlan) : DesktopNvidiaActivationState
}

/** Allowed asks the existing ladder to run; Legacy keeps today's behaviour. Every other state makes no request. */
internal val DesktopNvidiaActivationState.allowsRequest: Boolean
    get() = this is DesktopNvidiaActivationState.Allowed || this is DesktopNvidiaActivationState.Legacy

/** The plan carried by a state, or null for the states decided before a plan exists. */
internal val DesktopNvidiaActivationState.planOrNull: NvidiaPlan?
    get() = when (this) {
        is DesktopNvidiaActivationState.Standby -> this.plan
        is DesktopNvidiaActivationState.PausedGame -> this.plan
        is DesktopNvidiaActivationState.PausedBattery -> this.plan
        is DesktopNvidiaActivationState.PausedRemote -> this.plan
        is DesktopNvidiaActivationState.FailedRemembered -> this.plan
        is DesktopNvidiaActivationState.FailedThisVideo -> this.plan
        is DesktopNvidiaActivationState.FailedExtendedRange -> this.plan
        is DesktopNvidiaActivationState.Allowed -> this.plan
        is DesktopNvidiaActivationState.Off -> null
        is DesktopNvidiaActivationState.Legacy -> null
        is DesktopNvidiaActivationState.Probing -> null
        is DesktopNvidiaActivationState.NoNvidia -> null
        is DesktopNvidiaActivationState.Unsupported -> null
        is DesktopNvidiaActivationState.Unidentified -> null
    }

private fun nvidiaCapabilityGpuDescription(capability: NvidiaCapability): String? = when (capability) {
    is NvidiaCapability.Capable -> capability.gpu.description
    is NvidiaCapability.Unsupported -> capability.gpu?.description
    is NvidiaCapability.NotEvaluated -> null
    is NvidiaCapability.Probing -> null
    is NvidiaCapability.Absent -> null
    is NvidiaCapability.Unknown -> null
}

/**
 * Activation precedence, first match wins:
 * off, not evaluated (legacy), probing, absent, unsupported, probe failed without and with a
 * verified observed LUID, game, battery on a mobile GPU, remote or virtual output, remembered
 * failure, extended-range failure, guard give-up for the source, then Standby before a file loads
 * and Allowed after.
 *
 * displayAdapterLuidHex is the adapter that owns the video window's monitor, when known. A monitor on
 * another adapter lowers the tier one step (cross-adapter) in the plan of every state that carries one.
 */
internal fun resolveDesktopNvidiaActivation(
    mode: DesktopNvidiaMode,
    capability: NvidiaCapability,
    environment: DesktopNvidiaEnvironment = DesktopNvidiaEnvironment(),
    observedGpu: DesktopNvidiaObservedGpu? = null,
    sourceLoaded: Boolean = false,
    failureRemembered: Boolean = false,
    extendedRangeFailedForSource: Boolean = false,
    guardGaveUpForSource: Boolean = false,
    displayAdapterLuidHex: String? = null,
): DesktopNvidiaActivationState {
    if (mode == DesktopNvidiaMode.OFF) {
        return DesktopNvidiaActivationState.Off(nvidiaCapabilityGpuDescription(capability))
    }
    if (capability is NvidiaCapability.NotEvaluated) return DesktopNvidiaActivationState.Legacy
    if (capability is NvidiaCapability.Probing) return DesktopNvidiaActivationState.Probing
    if (capability is NvidiaCapability.Absent) return DesktopNvidiaActivationState.NoNvidia
    if (capability is NvidiaCapability.Unsupported) {
        return DesktopNvidiaActivationState.Unsupported(capability.reason, capability.gpu)
    }
    val plan: NvidiaPlan = if (capability is NvidiaCapability.Capable) {
        buildNvidiaPlan(nvidiaCapabilityForDisplayAdapter(capability, displayAdapterLuidHex))
    } else {
        // The probe failed. Only a verified physical observed LUID lets a capped profile through.
        val observed = observedGpu
        if (observed == null || !observed.verifiedPhysical) return DesktopNvidiaActivationState.Unidentified
        val observedCapability = classifyObservedNvidiaGpu(
            observed.description, observed.deviceId, observed.luidHex, observed.driverPublicX100,
        )
        if (observedCapability is NvidiaCapability.Unsupported) {
            return DesktopNvidiaActivationState.Unsupported(observedCapability.reason, observedCapability.gpu)
        }
        if (observedCapability !is NvidiaCapability.Capable) return DesktopNvidiaActivationState.Unidentified
        buildNvidiaPlan(nvidiaCapabilityForDisplayAdapter(observedCapability, displayAdapterLuidHex))
    }
    if (environment.gameLike) return DesktopNvidiaActivationState.PausedGame(plan)
    if (plan.mobile && environment.onBattery) return DesktopNvidiaActivationState.PausedBattery(plan)
    if (environment.remoteOrVirtualOutput) return DesktopNvidiaActivationState.PausedRemote(plan)
    if (failureRemembered) return DesktopNvidiaActivationState.FailedRemembered(plan)
    if (extendedRangeFailedForSource) return DesktopNvidiaActivationState.FailedExtendedRange(plan)
    if (guardGaveUpForSource) return DesktopNvidiaActivationState.FailedThisVideo(plan)
    return if (sourceLoaded) {
        DesktopNvidiaActivationState.Allowed(plan)
    } else {
        DesktopNvidiaActivationState.Standby(plan)
    }
}
