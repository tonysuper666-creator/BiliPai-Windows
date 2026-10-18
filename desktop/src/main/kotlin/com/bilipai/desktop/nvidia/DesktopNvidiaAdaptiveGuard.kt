package com.bilipai.desktop.nvidia

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Pure adaptive guard (design section 9). No I/O and no side effect: the clock is injected, the guard keeps
// its state per source, and every decision is returned as data for the session to act on.
//
// What shapes it: the native filter reports no timing and no step-down. A failure is terminal for its stream
// and the application removes the filter, so every step down is a new configuration, and each rebuild costs a
// full NGX shutdown and init and can hit a busy resource. The guard therefore only ever lowers, holds at least
// five seconds between steps, and takes at most three steps per source.
//
// Nothing here is a presentation proof. A stalled proof, a covered window, a hidden host, a seek, a pause, a
// cache stall and decoder drops are never overload signals, and a submitted frame never counts as healthy.
// The guard never names the presentation tracker and never writes the player active flag.
//
// Every string template in this package uses braces around the variable.

// GUARD-CONSTANTS-BEGIN
/** A step needs at least this long since the previous step. */
internal const val GUARD_HOLD_MILLIS: Long = 5_000L

/** Steps (transitions) per source. After the cap the guard is frozen: a terminal failure gives up instead. */
internal const val GUARD_MAX_TRANSITIONS: Int = 3

/** Same-configuration retries for a busy native resource, uncounted and outside the hold. */
internal const val GUARD_BUSY_MAX_RETRIES: Int = 3
internal val GUARD_BUSY_RETRY_DELAYS_MILLIS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

/** Overload windows: unpaused 1x playback, ignored this long after a configuration change. */
internal const val GUARD_OVERLOAD_WINDOW_MILLIS: Long = 10_000L
internal const val GUARD_OVERLOAD_SETTLE_MILLIS: Long = 10_000L

/** A window is bad when its drops reach max(6, 6 percent of the frames of the window) or the sync offset holds. */
internal const val GUARD_OVERLOAD_MIN_DROPS: Int = 6
internal const val GUARD_OVERLOAD_DROP_FRACTION: Double = 0.06
internal const val GUARD_OVERLOAD_AVSYNC_SECONDS: Double = 0.25
internal const val GUARD_OVERLOAD_BAD_WINDOWS: Int = 2
internal const val GUARD_OVERLOAD_MAX_STEPS: Int = 2

/** After an overload step the next window must show at least this much less load, else the source is bypassed. */
internal const val GUARD_OVERLOAD_MIN_IMPROVEMENT: Double = 0.30

/** Samples further apart than this do not belong to one window. */
internal const val GUARD_SAMPLE_GAP_MILLIS: Long = 3_000L

/** A shown stream held this long clears the failure streak. */
internal const val GUARD_HEALTHY_CLEAR_MILLIS: Long = 30_000L

/** Failure memory in wall-clock milliseconds: a remembered failure lasts 24 hours and is never kept longer than 7 days. */
internal const val GUARD_REMEMBER_MILLIS: Long = 86_400_000L
internal const val GUARD_REMEMBER_MAX_MILLIS: Long = 604_800_000L

/** Learned limits (quality ceiling, extended-range denial) last 7 days. A whole entry expires after 14 days. */
internal const val GUARD_LEARNED_TTL_MILLIS: Long = 604_800_000L
internal const val GUARD_ENTRY_TTL_MILLIS: Long = 1_209_600_000L

/** Bounds of the persisted counters. */
internal const val GUARD_MAX_STREAK: Int = 100
internal const val GUARD_MAX_SOURCE_COUNT: Int = 1_000
// GUARD-CONSTANTS-END

// ---- Rungs ---------------------------------------------------------------------------------

/** One configuration a source can be held at: the NGX quality level and whether SDR to HDR conversion stays on. */
internal data class NvidiaRung(val qualityLevel: Int, val hdr: Boolean)

/**
 * The rungs of the shared-core route with super resolution, best first:
 * (Qc, hdr) down to (2, hdr), then (2, no hdr) and (1, no hdr), then bypass. Without a requested HDR
 * conversion the HDR rungs are omitted: (Qc, no hdr) down to (1, no hdr). Without quality rungs (the driver
 * route, HDR on its own) there is one rung and the next step is bypass. Level 1 is accepted natively but has
 * never been executed on hardware.
 */
internal fun buildNvidiaRungLadder(qualityCeiling: Int, hdrRequested: Boolean, qualityRungs: Boolean = true): List<NvidiaRung> {
    val top = qualityCeiling.coerceIn(1, 4)
    if (!qualityRungs) return listOf(NvidiaRung(top, hdrRequested))
    val rungs = ArrayList<NvidiaRung>()
    if (hdrRequested) {
        val lowestHdrQuality = min(top, 2)
        var quality = top
        while (quality >= lowestHdrQuality) {
            rungs.add(NvidiaRung(quality, true))
            quality--
        }
        quality = lowestHdrQuality
        while (quality >= 1) {
            rungs.add(NvidiaRung(quality, false))
            quality--
        }
    } else {
        var quality = top
        while (quality >= 1) {
            rungs.add(NvidiaRung(quality, false))
            quality--
        }
    }
    return rungs
}

/** The next rung in ladder order after the current one, or null when the next step is bypass. */
internal fun nextNvidiaRung(ladder: List<NvidiaRung>, current: NvidiaRung): NvidiaRung? {
    for (rung in ladder) {
        val lowerQuality = rung.qualityLevel < current.qualityLevel
        val sameQualityWithoutHdr = rung.qualityLevel == current.qualityLevel && current.hdr && !rung.hdr
        if (lowerQuality || sameQualityWithoutHdr) return rung
    }
    return null
}

/** A device or feature failure drops HDR first at the same quality, otherwise one quality level. Null means bypass. */
internal fun nvidiaDeviceStepRung(current: NvidiaRung, qualityRungs: Boolean): NvidiaRung? = when {
    !qualityRungs -> null
    current.hdr -> NvidiaRung(current.qualityLevel, false)
    current.qualityLevel > 1 -> NvidiaRung(current.qualityLevel - 1, false)
    else -> null
}

/** What the request builder reads: the quality ceiling and whether HDR may stay on. Four and true change nothing. */
internal data class NvidiaGuardCaps(val qualityCap: Int = 4, val hdrAllowed: Boolean = true)

// ---- Failure taxonomy ----------------------------------------------------------------------

internal enum class NvidiaFailureClass {
    /** Codes 6, 7, 8, and 11 at bridge-unavailable. */
    DEVICE_OR_FEATURE,

    /** Code 9, and 11 at process-bypass. */
    TIMEOUT,

    /** Code 3: the resource is held by someone else. */
    BUSY,

    /** Code 5: the colour format. Per source, nothing else changes. */
    COLOR,

    /** Code 1. */
    CONTRACT,

    /** Code 2. */
    ABI_MISMATCH,

    /** Codes 4 and 10, and 11 at seek-reset-retired. */
    STALE_OR_RESET,

    /** Zero, out of range, or a code the table does not name. */
    UNKNOWN,
}

internal data class NvidiaFailureRule(val code: Int, val stage: String?, val failureClass: NvidiaFailureClass)

// GUARD-TAXONOMY-BEGIN
internal val NVIDIA_FAILURE_RULES: List<NvidiaFailureRule> = listOf(
    NvidiaFailureRule(code = 1, stage = null, failureClass = NvidiaFailureClass.CONTRACT),
    NvidiaFailureRule(code = 2, stage = null, failureClass = NvidiaFailureClass.ABI_MISMATCH),
    NvidiaFailureRule(code = 3, stage = null, failureClass = NvidiaFailureClass.BUSY),
    NvidiaFailureRule(code = 4, stage = null, failureClass = NvidiaFailureClass.STALE_OR_RESET),
    NvidiaFailureRule(code = 5, stage = null, failureClass = NvidiaFailureClass.COLOR),
    NvidiaFailureRule(code = 6, stage = null, failureClass = NvidiaFailureClass.DEVICE_OR_FEATURE),
    NvidiaFailureRule(code = 7, stage = null, failureClass = NvidiaFailureClass.DEVICE_OR_FEATURE),
    NvidiaFailureRule(code = 8, stage = null, failureClass = NvidiaFailureClass.DEVICE_OR_FEATURE),
    NvidiaFailureRule(code = 9, stage = null, failureClass = NvidiaFailureClass.TIMEOUT),
    NvidiaFailureRule(code = 10, stage = null, failureClass = NvidiaFailureClass.STALE_OR_RESET),
    NvidiaFailureRule(code = 11, stage = "bridge-unavailable", failureClass = NvidiaFailureClass.DEVICE_OR_FEATURE),
    NvidiaFailureRule(code = 11, stage = "process-bypass", failureClass = NvidiaFailureClass.TIMEOUT),
    NvidiaFailureRule(code = 11, stage = "seek-reset-retired", failureClass = NvidiaFailureClass.STALE_OR_RESET),
)
// GUARD-TAXONOMY-END

/** The class of one native failure. A rule without a stage matches every stage; the first matching rule wins. */
internal fun classifyNvidiaFailure(code: Int, stage: String): NvidiaFailureClass {
    for (rule in NVIDIA_FAILURE_RULES) {
        val stageMatches = rule.stage == null || rule.stage == stage
        if (rule.code == code && stageMatches) return rule.failureClass
    }
    return NvidiaFailureClass.UNKNOWN
}

// ---- Decisions -----------------------------------------------------------------------------

internal enum class NvidiaStepReason { DEVICE, TIMEOUT, OVERLOAD }

/** Why the guard stopped enhancing one source. The overload reasons are worded as a smoothness stop. */
internal enum class NvidiaGiveUpReason(val overload: Boolean = false) {
    BUSY_EXHAUSTED,
    CONTRACT,
    ABI_MISMATCH,
    STALE_OR_RESET,
    UNKNOWN_FAILURE,
    DEVICE_TWICE,
    NO_LOWER_RUNG,
    TRANSITION_CAP,
    EXTENDED_RANGE,
    OVERLOAD_NO_IMPROVEMENT(true),
    OVERLOAD_EXHAUSTED(true),
    OVERLOAD_NO_LOWER_RUNG(true),
}

/** What a give-up asks the failure memory to record. */
internal enum class NvidiaMemoryEffect { NONE, DEVICE_FAILURE, REMEMBER_NOW, EXTENDED_RANGE_DENIED }

internal sealed interface NvidiaGuardDecision {
    /** Nothing to do: not a failure the guard acts on, already handled, or no overload. */
    data object NoChange : NvidiaGuardDecision

    /** A busy resource: the same configuration again as a new attempt, after the delay. */
    data class Retry(val attempt: Int, val delayMillis: Long) : NvidiaGuardDecision

    /** A lower rung after the delay (the hold). The step takes effect when commitPending is called. */
    data class StepDown(val rung: NvidiaRung, val delayMillis: Long, val reason: NvidiaStepReason) : NvidiaGuardDecision

    /** Stop enhancing this source. qualityLevel and atLowestRung describe the rung that failed, for the memory. */
    data class GiveUp(
        val reason: NvidiaGiveUpReason,
        val memory: NvidiaMemoryEffect,
        val qualityLevel: Int,
        val atLowestRung: Boolean,
    ) : NvidiaGuardDecision
}

/**
 * One reading of the player's video output counters, taken about once a second. Observation only. The counters
 * are cumulative since the file started; the guard works with their differences. Null means unreadable.
 * atMillis is the monotonic clock of the poll, in the clock domain of the guard.
 */
internal data class NvidiaPlaybackHealthSample(
    /** The source the counters belong to; zero while nothing is loaded. */
    val sourceVersion: Long = 0L,
    val atMillis: Long = 0L,
    val frameDrops: Long? = null,
    val delayedFrames: Long? = null,
    /** Audio and video sync offset in seconds. */
    val avSyncSeconds: Double? = null,
    val paused: Boolean = false,
    val pausedForCache: Boolean = false,
    val seeking: Boolean = false,
    val speed: Double = 1.0,
)

// ---- The guard -----------------------------------------------------------------------------

/**
 * Per-source, reduce-only state machine. Not thread safe: the session calls it under its ownership lock.
 *
 * Flow: selectSource and configure on every request build; onFailure when the native filter reports a failure
 * for the current configuration; observePlayback with each health sample; commitPending when a returned delay
 * has elapsed; caps and attempt feed the next request. A source that gave up stays given up until the source
 * changes, the user switches the feature off and on, or the user asks for a new detection.
 */
internal class DesktopNvidiaAdaptiveGuard(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private class Pending(val retry: Boolean, val rung: NvidiaRung?, val dueAtMillis: Long)

    private class Window(
        val startAtMillis: Long,
        val startDrops: Long,
        val startDelayed: Long,
        var lastAtMillis: Long,
        var avSyncMinAbs: Double,
    )

    var sourceKey: String? = null
        private set

    private var rung: NvidiaRung? = null
    private var ceilingQuality: Int = 4
    private var hdrRequested: Boolean = false
    private var qualityRungs: Boolean = true
    private var inExtendedRange: Boolean = false
    private var frameRate: Double? = null
    private var transitions: Int = 0
    private var lastStepAtMillis: Long? = null
    private var pending: Pending? = null
    private var deviceFailures: Int = 0
    private var busyRetries: Int = 0
    private var handledToken: Long = -1L
    private var attemptCounter: Int = 0
    private var gaveUpReason: NvidiaGiveUpReason? = null
    private var colorRejected: Boolean = false
    private var overloadSteps: Int = 0
    private var configuredAtMillis: Long? = null
    private var window: Window? = null
    private var consecutiveBad: Int = 0
    private var baselineScore: Double? = null
    private var heldToken: Long = -1L
    private var heldSinceMillis: Long = 0L
    private var heldCleared: Boolean = false

    /** Distinguishes two otherwise identical requests: raised by every retry and every committed step. */
    val attempt: Int get() = attemptCounter

    /** Why this source was given up, or null while it is still being enhanced. */
    val gaveUp: NvidiaGiveUpReason? get() = gaveUpReason

    /**
     * The filter rejected the colour format of this source (code 5). Nothing is lowered and nothing is remembered,
     * but the source is not requested again: no other option helps a format the filter cannot take. It is its own
     * flag and not a give-up, because the line for it is the colour line and not the failure line.
     */
    val colorFailed: Boolean get() = colorRejected

    /** A retry or a step has been decided and waits for its delay: nothing new may be installed meanwhile. */
    val holding: Boolean get() = pending != null

    /** When the next retry or step is due, in the clock domain of the guard. */
    val pendingDueAtMillis: Long? get() = pending?.dueAtMillis

    /** True after at least one step down was committed for this source. */
    val steppedDown: Boolean get() = rung != null

    val transitionCount: Int get() = transitions

    val overloadStepCount: Int get() = overloadSteps

    /** The limits to build the next request with. */
    fun caps(): NvidiaGuardCaps {
        val current = rung ?: return NvidiaGuardCaps()
        return NvidiaGuardCaps(current.qualityLevel, current.hdr)
    }

    /**
     * Binds the guard to one source. A different source starts from scratch. A null key keeps the state, so a
     * moment without a current source cannot make a failing source look new.
     */
    fun selectSource(key: String?) {
        if (key == null || key == sourceKey) return
        sourceKey = key
        resetState()
    }

    /** An explicit switch off and on, or a new GPU detection: one fresh attempt for the current source. */
    fun resetForSwitch() {
        resetState()
    }

    private fun resetState() {
        rung = null
        transitions = 0
        lastStepAtMillis = null
        pending = null
        deviceFailures = 0
        busyRetries = 0
        handledToken = -1L
        attemptCounter = 0
        gaveUpReason = null
        colorRejected = false
        overloadSteps = 0
        configuredAtMillis = null
        window = null
        consecutiveBad = 0
        baselineScore = null
        heldToken = -1L
        heldSinceMillis = 0L
        heldCleared = false
    }

    /**
     * The facts of the request being built, before the guard limits are applied: the quality ceiling (the lowest
     * of the user ceiling, the tier and any learned ceiling), whether HDR conversion is requested, whether the
     * route has quality rungs (shared core with super resolution), whether the input is beyond the super
     * resolution box, and the source frame rate.
     */
    fun configure(
        qualityCeiling: Int,
        hdrRequestedNow: Boolean,
        qualityRungsNow: Boolean,
        extendedRange: Boolean,
        frameRateNow: Double?,
    ) {
        ceilingQuality = qualityCeiling.coerceIn(1, 4)
        hdrRequested = hdrRequestedNow
        qualityRungs = qualityRungsNow
        inExtendedRange = extendedRange
        frameRate = frameRateNow
    }

    /** A new configuration was installed for the source: the overload settling time starts. */
    fun noteConfiguration(nowMillis: Long = clock()) {
        configuredAtMillis = nowMillis
        window = null
        consecutiveBad = 0
    }

    /** The filter is not running (a pause, a failed state, another source): any partial window is void. */
    fun resetPlaybackWindow() {
        window = null
        consecutiveBad = 0
    }

    private fun currentRung(): NvidiaRung = rung ?: NvidiaRung(ceilingQuality, hdrRequested)

    private fun ladder(): List<NvidiaRung> =
        buildNvidiaRungLadder(ceilingQuality, hdrRequested && (rung?.hdr ?: true), qualityRungs)

    private fun giveUp(reason: NvidiaGiveUpReason, memory: NvidiaMemoryEffect): NvidiaGuardDecision.GiveUp {
        gaveUpReason = reason
        pending = null
        window = null
        consecutiveBad = 0
        val current = currentRung()
        val atLowest = nvidiaDeviceStepRung(current, qualityRungs) == null
        return NvidiaGuardDecision.GiveUp(reason, memory, current.qualityLevel, atLowest)
    }

    /**
     * Decides what a native failure of the configuration with this token means. A token is handled once.
     * Colour failures lower and remember nothing (the existing message stays) and mark the source, which is then not
     * requested again. Busy failures retry the same configuration after 1, 2 and 4 seconds. Device and feature
     * failures drop HDR first, then one quality level, and give up on the second failure. Timeouts take the next
     * rung. Everything else gives the source up.
     */
    fun onFailure(token: Long, code: Int, stage: String, nowMillis: Long = clock()): NvidiaGuardDecision {
        if (gaveUpReason != null || token == handledToken) return NvidiaGuardDecision.NoChange
        handledToken = token
        window = null
        consecutiveBad = 0
        baselineScore = null
        return when (classifyNvidiaFailure(code, stage)) {
            NvidiaFailureClass.COLOR -> {
                colorRejected = true
                NvidiaGuardDecision.NoChange
            }
            NvidiaFailureClass.BUSY -> decideBusy(nowMillis)
            NvidiaFailureClass.CONTRACT -> giveUp(NvidiaGiveUpReason.CONTRACT, NvidiaMemoryEffect.NONE)
            NvidiaFailureClass.ABI_MISMATCH -> giveUp(NvidiaGiveUpReason.ABI_MISMATCH, NvidiaMemoryEffect.REMEMBER_NOW)
            NvidiaFailureClass.STALE_OR_RESET -> giveUp(NvidiaGiveUpReason.STALE_OR_RESET, NvidiaMemoryEffect.NONE)
            NvidiaFailureClass.UNKNOWN -> giveUp(NvidiaGiveUpReason.UNKNOWN_FAILURE, NvidiaMemoryEffect.NONE)
            NvidiaFailureClass.DEVICE_OR_FEATURE -> decideDevice(nowMillis)
            NvidiaFailureClass.TIMEOUT -> decideTimeout(nowMillis)
        }
    }

    private fun decideBusy(nowMillis: Long): NvidiaGuardDecision {
        if (busyRetries >= GUARD_BUSY_MAX_RETRIES) {
            return giveUp(NvidiaGiveUpReason.BUSY_EXHAUSTED, NvidiaMemoryEffect.NONE)
        }
        val delay = GUARD_BUSY_RETRY_DELAYS_MILLIS.getOrElse(busyRetries) { GUARD_BUSY_RETRY_DELAYS_MILLIS.last() }
        busyRetries++
        pending = Pending(true, null, nowMillis + delay)
        return NvidiaGuardDecision.Retry(attemptCounter + 1, delay)
    }

    private fun decideDevice(nowMillis: Long): NvidiaGuardDecision {
        // A failure beyond the super resolution box is contained: this source only, and the range is learned.
        if (inExtendedRange) return giveUp(NvidiaGiveUpReason.EXTENDED_RANGE, NvidiaMemoryEffect.EXTENDED_RANGE_DENIED)
        deviceFailures++
        val target = nvidiaDeviceStepRung(currentRung(), qualityRungs)
        if (target == null) return giveUp(NvidiaGiveUpReason.NO_LOWER_RUNG, NvidiaMemoryEffect.DEVICE_FAILURE)
        if (deviceFailures >= 2) return giveUp(NvidiaGiveUpReason.DEVICE_TWICE, NvidiaMemoryEffect.DEVICE_FAILURE)
        return step(target, NvidiaStepReason.DEVICE, nowMillis, NvidiaMemoryEffect.DEVICE_FAILURE)
    }

    private fun decideTimeout(nowMillis: Long): NvidiaGuardDecision {
        if (inExtendedRange) return giveUp(NvidiaGiveUpReason.EXTENDED_RANGE, NvidiaMemoryEffect.EXTENDED_RANGE_DENIED)
        val target = nextNvidiaRung(ladder(), currentRung())
        if (target == null) return giveUp(NvidiaGiveUpReason.NO_LOWER_RUNG, NvidiaMemoryEffect.NONE)
        return step(target, NvidiaStepReason.TIMEOUT, nowMillis, NvidiaMemoryEffect.NONE)
    }

    private fun step(
        target: NvidiaRung,
        reason: NvidiaStepReason,
        nowMillis: Long,
        memoryOnCap: NvidiaMemoryEffect,
    ): NvidiaGuardDecision {
        if (transitions >= GUARD_MAX_TRANSITIONS) return giveUp(NvidiaGiveUpReason.TRANSITION_CAP, memoryOnCap)
        val previousStep = lastStepAtMillis
        val earliest = if (previousStep == null) nowMillis else previousStep + GUARD_HOLD_MILLIS
        val due = max(nowMillis, earliest)
        pending = Pending(false, target, due)
        return NvidiaGuardDecision.StepDown(target, due - nowMillis, reason)
    }

    /**
     * Applies the retry or the step once its time has come. True means the limits or the attempt changed, so
     * a new request must be built. A step that is not due yet changes nothing.
     */
    fun commitPending(nowMillis: Long = clock()): Boolean {
        val item = pending ?: return false
        if (nowMillis < item.dueAtMillis) return false
        pending = null
        val target = item.rung
        if (target != null) {
            rung = target
            transitions++
            lastStepAtMillis = nowMillis
        }
        attemptCounter++
        window = null
        consecutiveBad = 0
        return true
    }

    // ---- Overload ----

    /**
     * Feeds one health sample. Windows are ten seconds of unpaused 1x playback, ignored for ten seconds after a
     * configuration change. A window is bad when its drops (output drops plus delayed frames) reach
     * max(6, 6 percent of its frames) or the sync offset stayed at 0.25 s or more for all of it. Two bad windows
     * in a row step down, at most twice; the first window after a step must show 30 percent less load or the
     * source is bypassed. A pause, a seek, a cache stall, another speed or a gap in the samples voids the window.
     */
    fun observePlayback(sample: NvidiaPlaybackHealthSample): NvidiaGuardDecision {
        if (gaveUpReason != null || pending != null) {
            window = null
            return NvidiaGuardDecision.NoChange
        }
        val drops = sample.frameDrops
        val delayed = sample.delayedFrames
        val configured = configuredAtMillis
        val settled = configured != null && sample.atMillis - configured >= GUARD_OVERLOAD_SETTLE_MILLIS
        val steady = !sample.paused && !sample.pausedForCache && !sample.seeking && abs(sample.speed - 1.0) < 0.001
        if (drops == null || delayed == null || !settled || !steady) {
            window = null
            consecutiveBad = 0
            return NvidiaGuardDecision.NoChange
        }
        val syncOffset = abs(sample.avSyncSeconds ?: 0.0)
        val running = window
        if (running == null) {
            window = Window(sample.atMillis, drops, delayed, sample.atMillis, syncOffset)
            return NvidiaGuardDecision.NoChange
        }
        val gap = sample.atMillis - running.lastAtMillis
        val backwards = gap < 0L || drops < running.startDrops || delayed < running.startDelayed
        if (backwards || gap > GUARD_SAMPLE_GAP_MILLIS) {
            // A hole in the samples or counters that went backwards (a new file): start over from this sample.
            window = Window(sample.atMillis, drops, delayed, sample.atMillis, syncOffset)
            consecutiveBad = 0
            return NvidiaGuardDecision.NoChange
        }
        running.lastAtMillis = sample.atMillis
        running.avSyncMinAbs = min(running.avSyncMinAbs, syncOffset)
        val elapsed = sample.atMillis - running.startAtMillis
        if (elapsed < GUARD_OVERLOAD_WINDOW_MILLIS) return NvidiaGuardDecision.NoChange
        val seconds = elapsed / 1000.0
        val lost = (drops - running.startDrops) + (delayed - running.startDelayed)
        val threshold = max(
            GUARD_OVERLOAD_MIN_DROPS.toDouble(),
            GUARD_OVERLOAD_DROP_FRACTION * nvidiaEffectiveFrameRate(frameRate) * seconds,
        )
        val score = max(lost.toDouble() / threshold, running.avSyncMinAbs / GUARD_OVERLOAD_AVSYNC_SECONDS)
        // The next window starts where this one ended.
        window = Window(sample.atMillis, drops, delayed, sample.atMillis, syncOffset)
        return judgeWindow(score, sample.atMillis)
    }

    private fun judgeWindow(score: Double, nowMillis: Long): NvidiaGuardDecision {
        val bad = score >= 1.0
        val baseline = baselineScore
        if (baseline != null) {
            baselineScore = null
            if (!bad) {
                consecutiveBad = 0
                return NvidiaGuardDecision.NoChange
            }
            if (score > baseline * (1.0 - GUARD_OVERLOAD_MIN_IMPROVEMENT)) {
                return giveUp(NvidiaGiveUpReason.OVERLOAD_NO_IMPROVEMENT, NvidiaMemoryEffect.NONE)
            }
        }
        if (!bad) {
            consecutiveBad = 0
            return NvidiaGuardDecision.NoChange
        }
        consecutiveBad++
        if (consecutiveBad < GUARD_OVERLOAD_BAD_WINDOWS) return NvidiaGuardDecision.NoChange
        consecutiveBad = 0
        if (overloadSteps >= GUARD_OVERLOAD_MAX_STEPS || transitions >= GUARD_MAX_TRANSITIONS) {
            return giveUp(NvidiaGiveUpReason.OVERLOAD_EXHAUSTED, NvidiaMemoryEffect.NONE)
        }
        val target = nextNvidiaRung(ladder(), currentRung())
        if (target == null) return giveUp(NvidiaGiveUpReason.OVERLOAD_NO_LOWER_RUNG, NvidiaMemoryEffect.NONE)
        val decision = step(target, NvidiaStepReason.OVERLOAD, nowMillis, NvidiaMemoryEffect.NONE)
        if (decision is NvidiaGuardDecision.StepDown) {
            overloadSteps++
            baselineScore = score
        }
        return decision
    }

    // ---- Healthy streak ----

    /**
     * True exactly once per configuration when the stream was shown continuously for 30 seconds. The caller
     * passes the player's own flag; the guard only measures how long it held and never sets it.
     */
    fun observeProofHeld(token: Long, held: Boolean, nowMillis: Long = clock()): Boolean {
        if (!held) {
            heldToken = -1L
            heldCleared = false
            return false
        }
        if (heldToken != token) {
            heldToken = token
            heldSinceMillis = nowMillis
            heldCleared = false
            return false
        }
        if (heldCleared) return false
        if (nowMillis - heldSinceMillis < GUARD_HEALTHY_CLEAR_MILLIS) return false
        heldCleared = true
        return true
    }
}

// ---- Failure memory model ------------------------------------------------------------------

/** Backend word of the failure-memory key for the shared core. The driver route has no codes and no memory. */
internal const val NVIDIA_HEALTH_BACKEND_VEYRA: String = "veyra"
internal const val NVIDIA_HEALTH_BACKEND_DRIVER: String = "driver"

/** Runtime word of the key while the installed component identity is not known. */
internal const val NVIDIA_HEALTH_RUNTIME_UNKNOWN: String = "unknown"

private const val NVIDIA_HEX_DIGITS: String = "0123456789abcdef"

/**
 * What is remembered about one GPU, driver, runtime, tier and backend. Times are wall-clock milliseconds,
 * zero means none. Machine-local: it never leaves the machine and is never part of a backup.
 */
internal data class NvidiaHealthEntry(
    val key: String,
    /** Hard device failures counted since the last healthy stream. */
    val streak: Int = 0,
    val rememberedUntilMillis: Long = 0L,
    /** Reduce-only quality ceiling learned from repeated failures at one level; zero means none. */
    val learnedQualityCeiling: Int = 0,
    val learnedUntilMillis: Long = 0L,
    val failedQualityLevel: Int = 0,
    val failedQualityCount: Int = 0,
    val extendedRangeDeniedUntilMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
)

/** What the session reads: nothing, or the three facts that change a request. */
internal data class NvidiaHealthView(
    val remembered: Boolean = false,
    val extendedRangeDenied: Boolean = false,
    /** A learned quality ceiling (1..3), or null. */
    val learnedQualityCeiling: Int? = null,
) {
    companion object {
        val None: NvidiaHealthView = NvidiaHealthView()
    }
}

/** A key is exactly sixteen lowercase hex digits. */
internal fun nvidiaHealthKeyValid(key: String): Boolean {
    if (key.length != 16) return false
    for (ch in key) {
        if (!((ch in '0'..'9') || (ch in 'a'..'f'))) return false
    }
    return true
}

/**
 * The first sixteen hex digits of the SHA-256 of the identity text: the boot-independent GPU key, the public
 * driver number, the runtime identity (the verified component profile hash, or the driver literal), the tier
 * and the backend. A change in any of them is a different key, which starts clean.
 */
internal fun nvidiaHealthKey(
    gpuKey: String,
    driverPublicX100: Int?,
    runtimeIdentity: String,
    tier: NvidiaTier,
    backend: String,
): String {
    val driver = driverPublicX100?.toString() ?: "unknown"
    val text = "${gpuKey};${driver};${runtimeIdentity};${tier.name};${backend}"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val hex = CharArray(16)
    for (index in 0 until 8) {
        val value = digest[index].toInt() and 0xFF
        hex[index * 2] = NVIDIA_HEX_DIGITS[value shr 4]
        hex[index * 2 + 1] = NVIDIA_HEX_DIGITS[value and 0x0F]
    }
    return String(hex)
}

/**
 * The key for the capability the session works with, or null when no GPU identity is known. A failed probe
 * counts only with a verified physical observed identity, exactly as the activation gate does.
 */
internal fun nvidiaHealthKeyFor(
    capability: NvidiaCapability,
    observed: DesktopNvidiaObservedGpu?,
    runtimeIdentity: String,
    backend: String,
): String? {
    val capable: NvidiaCapability.Capable = when (capability) {
        is NvidiaCapability.Capable -> capability
        is NvidiaCapability.Unknown -> {
            val seen = observed
            if (seen == null || !seen.verifiedPhysical) return null
            val classified = classifyObservedNvidiaGpu(seen.description, seen.deviceId, seen.luidHex, seen.driverPublicX100)
            classified as? NvidiaCapability.Capable ?: return null
        }
        else -> return null
    }
    return nvidiaHealthKey(capable.gpu.gpuKey, capable.gpu.driverPublicX100, runtimeIdentity, capable.tier, backend)
}

/**
 * Applies expiry and bounds. A timestamp in the future (a clock that went backwards, or a foreign file) means
 * expired; so does an entry older than 14 days. A cooldown is never kept longer than 7 days from now, and a
 * learned limit that has run out is dropped. An entry that carries no information any more is null.
 */
internal fun nvidiaHealthNormalize(entry: NvidiaHealthEntry, nowMillis: Long): NvidiaHealthEntry? {
    if (entry.updatedAtMillis > nowMillis) return null
    if (nowMillis - entry.updatedAtMillis > GUARD_ENTRY_TTL_MILLIS) return null
    val remembered = if (entry.rememberedUntilMillis > nowMillis) {
        min(entry.rememberedUntilMillis, nowMillis + GUARD_REMEMBER_MAX_MILLIS)
    } else {
        0L
    }
    val learnedActive = entry.learnedUntilMillis > nowMillis && entry.learnedQualityCeiling in 1..3
    val denied = if (entry.extendedRangeDeniedUntilMillis > nowMillis) {
        min(entry.extendedRangeDeniedUntilMillis, nowMillis + GUARD_LEARNED_TTL_MILLIS)
    } else {
        0L
    }
    val result = entry.copy(
        streak = entry.streak.coerceIn(0, GUARD_MAX_STREAK),
        rememberedUntilMillis = remembered,
        learnedQualityCeiling = if (learnedActive) entry.learnedQualityCeiling else 0,
        learnedUntilMillis = if (learnedActive) min(entry.learnedUntilMillis, nowMillis + GUARD_LEARNED_TTL_MILLIS) else 0L,
        failedQualityLevel = entry.failedQualityLevel.coerceIn(0, 4),
        failedQualityCount = entry.failedQualityCount.coerceIn(0, GUARD_MAX_SOURCE_COUNT),
        extendedRangeDeniedUntilMillis = denied,
    )
    val informative = result.streak > 0 || remembered > 0L || learnedActive || denied > 0L || result.failedQualityCount > 0
    return if (informative) result else null
}

/** What the session should know at this time. A missing or expired entry is the empty view. */
internal fun nvidiaHealthViewOf(entry: NvidiaHealthEntry?, nowMillis: Long): NvidiaHealthView {
    if (entry == null) return NvidiaHealthView.None
    val normalized = nvidiaHealthNormalize(entry, nowMillis) ?: return NvidiaHealthView.None
    val ceiling = normalized.learnedQualityCeiling
    return NvidiaHealthView(
        remembered = normalized.rememberedUntilMillis > nowMillis,
        extendedRangeDenied = normalized.extendedRangeDeniedUntilMillis > nowMillis,
        learnedQualityCeiling = if (ceiling in 1..3) ceiling else null,
    )
}

/**
 * A source was given up after a hard device failure. Two such sources, or one at the lowest rung, remember the
 * failure for 24 hours. Two distinct sources failing at the same quality level also learn a lower ceiling,
 * which only ever goes down and lasts 7 days.
 */
internal fun nvidiaHealthAfterDeviceFailure(
    previous: NvidiaHealthEntry?,
    key: String,
    nowMillis: Long,
    atLowestRung: Boolean,
    failedQuality: Int,
): NvidiaHealthEntry {
    val base = previous ?: NvidiaHealthEntry(key)
    val streak = min(base.streak + 1, GUARD_MAX_STREAK)
    val remembered = if (streak >= 2 || atLowestRung) nowMillis + GUARD_REMEMBER_MILLIS else base.rememberedUntilMillis
    val quality = failedQuality.coerceIn(1, 4)
    val count = if (base.failedQualityLevel == quality) min(base.failedQualityCount + 1, GUARD_MAX_SOURCE_COUNT) else 1
    val learnNow = count >= 2
    val proposed = max(1, quality - 1)
    val existing = base.learnedQualityCeiling
    val ceiling = when {
        !learnNow -> existing
        existing in 1..3 -> min(existing, proposed)
        else -> proposed
    }
    return base.copy(
        streak = streak,
        rememberedUntilMillis = remembered,
        learnedQualityCeiling = ceiling,
        learnedUntilMillis = if (learnNow) nowMillis + GUARD_LEARNED_TTL_MILLIS else base.learnedUntilMillis,
        failedQualityLevel = quality,
        failedQualityCount = count,
        updatedAtMillis = nowMillis,
    )
}

/** A component contract failure: remembered for 24 hours at once. */
internal fun nvidiaHealthAfterRemember(previous: NvidiaHealthEntry?, key: String, nowMillis: Long): NvidiaHealthEntry {
    val base = previous ?: NvidiaHealthEntry(key)
    return base.copy(rememberedUntilMillis = nowMillis + GUARD_REMEMBER_MILLIS, updatedAtMillis = nowMillis)
}

/** A failure beyond the super resolution box: the extended range is denied for 7 days. Never a remembered failure. */
internal fun nvidiaHealthAfterExtendedRange(previous: NvidiaHealthEntry?, key: String, nowMillis: Long): NvidiaHealthEntry {
    val base = previous ?: NvidiaHealthEntry(key)
    return base.copy(extendedRangeDeniedUntilMillis = nowMillis + GUARD_LEARNED_TTL_MILLIS, updatedAtMillis = nowMillis)
}

/** A stream shown for 30 seconds clears the failure streak and nothing else. Null when there is nothing to clear. */
internal fun nvidiaHealthAfterHealthy(previous: NvidiaHealthEntry?, nowMillis: Long): NvidiaHealthEntry? {
    if (previous == null || previous.streak == 0) return previous
    return previous.copy(streak = 0, updatedAtMillis = nowMillis)
}
