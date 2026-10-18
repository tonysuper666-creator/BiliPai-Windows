package com.bilipai.desktop.nvidia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A hand-moved monotonic clock in milliseconds. The guard never reads another clock. */
private class AdaptiveGuardTestClock(var now: Long = 1_000_000L)

/**
 * Feeds one-second playback samples with cumulative counters, like the player's poll does. The first tick after
 * a configuration at time zero is one second later; samples become eligible ten seconds after the configuration.
 */
private class AdaptiveGuardSampleFeeder(private val guard: DesktopNvidiaAdaptiveGuard, var at: Long) {
    var drops = 0L
    var delayed = 0L

    fun tick(
        dropsPerTick: Long = 0L,
        delayedPerTick: Long = 0L,
        avSync: Double? = 0.0,
        paused: Boolean = false,
        pausedForCache: Boolean = false,
        seeking: Boolean = false,
        speed: Double = 1.0,
    ): NvidiaGuardDecision {
        at += 1_000L
        drops += dropsPerTick
        delayed += delayedPerTick
        return guard.observePlayback(
            NvidiaPlaybackHealthSample(1L, at, drops, delayed, avSync, paused, pausedForCache, seeking, speed),
        )
    }

    /** Every decision that is not NoChange, with its one-based tick number. */
    fun run(
        ticks: Int,
        dropsPerTick: Long = 0L,
        delayedPerTick: Long = 0L,
        avSync: Double? = 0.0,
        paused: Boolean = false,
        pausedForCache: Boolean = false,
        seeking: Boolean = false,
        speed: Double = 1.0,
    ): List<Pair<Int, NvidiaGuardDecision>> {
        val decisions = ArrayList<Pair<Int, NvidiaGuardDecision>>()
        for (index in 1..ticks) {
            val decision = tick(dropsPerTick, delayedPerTick, avSync, paused, pausedForCache, seeking, speed)
            if (decision !is NvidiaGuardDecision.NoChange) decisions.add(Pair(index, decision))
        }
        return decisions
    }

    /** Ten ticks with every drop in the last one: a window that lost exactly this many frames. */
    fun windowLosing(lost: Long): NvidiaGuardDecision {
        for (index in 1..9) tick()
        return tick(dropsPerTick = lost)
    }
}

class DesktopNvidiaAdaptiveGuardTest {
    private val start = 1_000_000L

    private fun newGuard(clock: AdaptiveGuardTestClock = AdaptiveGuardTestClock()): DesktopNvidiaAdaptiveGuard =
        DesktopNvidiaAdaptiveGuard { clock.now }

    private fun configured(
        guard: DesktopNvidiaAdaptiveGuard,
        quality: Int = 4,
        hdr: Boolean = false,
        qualityRungs: Boolean = true,
        extendedRange: Boolean = false,
        frameRate: Double? = 60.0,
    ): DesktopNvidiaAdaptiveGuard {
        guard.selectSource("1:1")
        guard.configure(quality, hdr, qualityRungs, extendedRange, frameRate)
        return guard
    }

    private fun rungOrder(rung: NvidiaRung): Int = rung.qualityLevel * 2 + (if (rung.hdr) 1 else 0)

    // ---- Rung ladder ----------------------------------------------------------------------

    @Test fun theLadderWithHdrDropsQualityAtFullConversionThenHdrAtTheLowestConversionRung() {
        assertEquals(
            listOf(NvidiaRung(4, true), NvidiaRung(3, true), NvidiaRung(2, true), NvidiaRung(2, false), NvidiaRung(1, false)),
            buildNvidiaRungLadder(4, true),
        )
        assertEquals(
            listOf(NvidiaRung(3, true), NvidiaRung(2, true), NvidiaRung(2, false), NvidiaRung(1, false)),
            buildNvidiaRungLadder(3, true),
        )
        assertEquals(listOf(NvidiaRung(2, true), NvidiaRung(2, false), NvidiaRung(1, false)), buildNvidiaRungLadder(2, true))
        assertEquals(listOf(NvidiaRung(1, true), NvidiaRung(1, false)), buildNvidiaRungLadder(1, true))
    }

    @Test fun theLadderWithoutHdrOmitsEveryHdrRung() {
        assertEquals(
            listOf(NvidiaRung(4, false), NvidiaRung(3, false), NvidiaRung(2, false), NvidiaRung(1, false)),
            buildNvidiaRungLadder(4, false),
        )
        assertEquals(listOf(NvidiaRung(2, false), NvidiaRung(1, false)), buildNvidiaRungLadder(2, false))
        assertEquals(listOf(NvidiaRung(1, false)), buildNvidiaRungLadder(1, false))
        // An out of range ceiling is clamped, never extended.
        assertEquals(buildNvidiaRungLadder(4, false), buildNvidiaRungLadder(9, false))
        assertEquals(buildNvidiaRungLadder(1, false), buildNvidiaRungLadder(-3, false))
    }

    @Test fun aRouteWithoutQualityRungsHasOneRungAndTheNextStepIsBypass() {
        for (hdr in listOf(true, false)) {
            val ladder = buildNvidiaRungLadder(4, hdr, qualityRungs = false)
            assertEquals(listOf(NvidiaRung(4, hdr)), ladder)
            assertNull(nextNvidiaRung(ladder, ladder.first()))
            assertNull(nvidiaDeviceStepRung(ladder.first(), qualityRungs = false))
        }
    }

    @Test fun everyLadderIsStrictlyDecreasingAndWalksToBypass() {
        for (quality in 1..4) for (hdr in listOf(true, false)) {
            val ladder = buildNvidiaRungLadder(quality, hdr)
            assertEquals(quality, ladder.first().qualityLevel, "the first rung is the ceiling")
            assertEquals(hdr, ladder.first().hdr)
            for (index in 1 until ladder.size) {
                // Each rung is cheaper than the one before: lower quality, or the same quality without HDR.
                assertTrue(rungOrder(ladder[index]) < rungOrder(ladder[index - 1]), "$quality $hdr $index")
            }
            var current = ladder.first()
            val walked = arrayListOf(current)
            while (true) {
                val next = nextNvidiaRung(ladder, current) ?: break
                walked.add(next)
                current = next
            }
            assertEquals(ladder, walked, "next walks the ladder in order and then ends")
            assertNull(nextNvidiaRung(ladder, ladder.last()), "the last rung is followed by bypass")
            assertFalse(ladder.last().hdr, "the last rung never keeps HDR when a conversion was requested with quality rungs")
        }
    }

    @Test fun aDeviceFailureDropsHdrFirstAtTheSameQualityThenOneLevelAndNeverBelowOne() {
        assertEquals(NvidiaRung(4, false), nvidiaDeviceStepRung(NvidiaRung(4, true), true))
        assertEquals(NvidiaRung(2, false), nvidiaDeviceStepRung(NvidiaRung(2, true), true))
        assertEquals(NvidiaRung(3, false), nvidiaDeviceStepRung(NvidiaRung(4, false), true))
        assertEquals(NvidiaRung(1, false), nvidiaDeviceStepRung(NvidiaRung(2, false), true))
        assertNull(nvidiaDeviceStepRung(NvidiaRung(1, false), true))
        assertEquals(NvidiaRung(1, false), nvidiaDeviceStepRung(NvidiaRung(1, true), true))
    }

    // ---- Failure taxonomy -----------------------------------------------------------------

    @Test fun everyNativeCodeFromOneToElevenIsClassified() {
        val stages = listOf("bridge-unavailable", "process-bypass", "seek-reset-retired")
        val expected = mapOf(
            1 to NvidiaFailureClass.CONTRACT, 2 to NvidiaFailureClass.ABI_MISMATCH, 3 to NvidiaFailureClass.BUSY,
            4 to NvidiaFailureClass.STALE_OR_RESET, 5 to NvidiaFailureClass.COLOR,
            6 to NvidiaFailureClass.DEVICE_OR_FEATURE, 7 to NvidiaFailureClass.DEVICE_OR_FEATURE,
            8 to NvidiaFailureClass.DEVICE_OR_FEATURE, 9 to NvidiaFailureClass.TIMEOUT,
            10 to NvidiaFailureClass.STALE_OR_RESET,
        )
        for ((code, failureClass) in expected) {
            for (stage in stages) assertEquals(failureClass, classifyNvidiaFailure(code, stage), "$code $stage")
        }
        // Code 11 depends on the stage: only initialisation is a device failure, only frame processing a timeout.
        assertEquals(NvidiaFailureClass.DEVICE_OR_FEATURE, classifyNvidiaFailure(11, "bridge-unavailable"))
        assertEquals(NvidiaFailureClass.TIMEOUT, classifyNvidiaFailure(11, "process-bypass"))
        assertEquals(NvidiaFailureClass.STALE_OR_RESET, classifyNvidiaFailure(11, "seek-reset-retired"))
        assertEquals((1..11).toSet(), NVIDIA_FAILURE_RULES.map { it.code }.toSet())
    }

    @Test fun zeroOutOfRangeAndUnnamedCodesAreUnknownNeverSuccess() {
        for (code in listOf(0, 12, 99, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(NvidiaFailureClass.UNKNOWN, classifyNvidiaFailure(code, "process-bypass"), "$code")
        }
        // A rule without a stage matches every stage, so a plain code keeps its class; code 11 needs one of its stages.
        assertEquals(NvidiaFailureClass.DEVICE_OR_FEATURE, classifyNvidiaFailure(6, "an-unnamed-stage"))
        assertEquals(NvidiaFailureClass.UNKNOWN, classifyNvidiaFailure(11, "an-unnamed-stage"))
    }

    // ---- Device and feature failures ------------------------------------------------------

    @Test fun aDeviceFailureWithHdrDropsHdrFirstAtTheSameQualityAndTheSecondFailureGivesUp() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        val first = assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(10L, 6, "process-bypass", clock.now))
        assertEquals(NvidiaRung(4, false), first.rung)
        assertEquals(0L, first.delayMillis)
        assertEquals(NvidiaStepReason.DEVICE, first.reason)
        assertTrue(guard.holding)
        assertEquals(NvidiaGuardCaps(), guard.caps(), "a decided step changes nothing until it is committed")
        assertTrue(guard.commitPending(clock.now))
        assertEquals(NvidiaGuardCaps(4, false), guard.caps())
        assertEquals(1, guard.attempt)
        assertEquals(1, guard.transitionCount)
        assertTrue(guard.steppedDown)
        assertFalse(guard.holding)
        guard.noteConfiguration(clock.now)

        val second = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(11L, 7, "bridge-unavailable", clock.now))
        assertEquals(NvidiaGiveUpReason.DEVICE_TWICE, second.reason)
        assertEquals(NvidiaMemoryEffect.DEVICE_FAILURE, second.memory)
        assertEquals(4, second.qualityLevel)
        assertFalse(second.atLowestRung)
        assertEquals(NvidiaGiveUpReason.DEVICE_TWICE, guard.gaveUp)
        assertFalse(guard.holding)
    }

    @Test fun aDeviceFailureWithoutHdrLowersTheQualityByOneLevelOnce() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = false)
        val first = assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(1L, 8, "bridge-unavailable", clock.now))
        assertEquals(NvidiaRung(3, false), first.rung)
        assertTrue(guard.commitPending(clock.now))
        assertEquals(NvidiaGuardCaps(3, false), guard.caps())
        val second = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(2L, 6, "process-bypass", clock.now))
        assertEquals(NvidiaGiveUpReason.DEVICE_TWICE, second.reason)
        assertEquals(3, second.qualityLevel)
    }

    @Test fun aDeviceFailureAtTheLowestRungGivesUpAtOnceAndMarksTheLowestRungForTheMemory() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 1, hdr = false)
        val decision = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(1L, 7, "process-bypass", clock.now))
        assertEquals(NvidiaGiveUpReason.NO_LOWER_RUNG, decision.reason)
        assertEquals(NvidiaMemoryEffect.DEVICE_FAILURE, decision.memory)
        assertEquals(1, decision.qualityLevel)
        assertTrue(decision.atLowestRung)
    }

    @Test fun hdrOnPlusOneQualityLevelStillStepsThroughNoHdrFirst() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 1, hdr = true)
        val first = assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(1L, 6, "process-bypass", clock.now))
        assertEquals(NvidiaRung(1, false), first.rung)
    }

    @Test fun aRouteWithoutQualityRungsGivesUpOnAHardDeviceFailure() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true, qualityRungs = false)
        val decision = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(1L, 6, "process-bypass", clock.now))
        assertEquals(NvidiaGiveUpReason.NO_LOWER_RUNG, decision.reason)
        assertTrue(decision.atLowestRung)
    }

    // ---- Timeouts and the hold ------------------------------------------------------------

    @Test fun aTimeoutTakesTheNextRungInOrderAndTheHoldSpacesTheSteps() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        val first = assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(1L, 9, "process-bypass", clock.now))
        assertEquals(NvidiaRung(3, true), first.rung)
        assertEquals(0L, first.delayMillis, "the first step of a source has no earlier step to wait for")
        assertEquals(NvidiaStepReason.TIMEOUT, first.reason)
        assertTrue(guard.commitPending(clock.now))
        val committedAt = clock.now
        clock.now += 1_000L
        val second = assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(2L, 11, "process-bypass", clock.now))
        assertEquals(NvidiaRung(2, true), second.rung)
        assertEquals(GUARD_HOLD_MILLIS - 1_000L, second.delayMillis, "at least five seconds between two steps")
        assertEquals(committedAt + GUARD_HOLD_MILLIS, guard.pendingDueAtMillis)
        assertFalse(guard.commitPending(clock.now), "not due yet")
        assertFalse(guard.commitPending(committedAt + GUARD_HOLD_MILLIS - 1L))
        assertEquals(NvidiaGuardCaps(3, true), guard.caps())
        assertTrue(guard.commitPending(committedAt + GUARD_HOLD_MILLIS))
        assertEquals(NvidiaGuardCaps(2, true), guard.caps())
        assertEquals(2, guard.attempt)
    }

    @Test fun timeoutStepsWalkTheWholeLadderAndAtMostThreeStepsAreTaken() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        val expected = listOf(NvidiaRung(3, true), NvidiaRung(2, true), NvidiaRung(2, false))
        for ((index, rung) in expected.withIndex()) {
            val decision = assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(index + 1L, 9, "process-bypass", clock.now))
            assertEquals(rung, decision.rung)
            assertTrue(guard.commitPending(clock.now))
            clock.now += GUARD_HOLD_MILLIS
        }
        assertEquals(GUARD_MAX_TRANSITIONS, guard.transitionCount)
        // Frozen after the cap: another failure ends the source, with nothing remembered for a timeout.
        val capped = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(4L, 9, "process-bypass", clock.now))
        assertEquals(NvidiaGiveUpReason.TRANSITION_CAP, capped.reason)
        assertEquals(NvidiaMemoryEffect.NONE, capped.memory)
    }

    @Test fun aTimeoutAtTheLastRungGivesUpWithoutMemory() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 1, hdr = false)
        val decision = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(1L, 9, "process-bypass", clock.now))
        assertEquals(NvidiaGiveUpReason.NO_LOWER_RUNG, decision.reason)
        assertEquals(NvidiaMemoryEffect.NONE, decision.memory)
    }

    @Test fun theDeviceCapMemoryEffectIsKeptWhenTheCapIsReached() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        for (index in 1..3) {
            assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(index.toLong(), 9, "process-bypass", clock.now))
            assertTrue(guard.commitPending(clock.now))
            clock.now += GUARD_HOLD_MILLIS
        }
        val capped = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(9L, 6, "process-bypass", clock.now))
        assertEquals(NvidiaGiveUpReason.TRANSITION_CAP, capped.reason)
        assertEquals(NvidiaMemoryEffect.DEVICE_FAILURE, capped.memory)
    }

    // ---- Busy retries ---------------------------------------------------------------------

    @Test fun aBusyFailureRetriesTheSameConfigurationAfterOneTwoAndFourSecondsUncounted() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = false)
        val delays = listOf(1_000L, 2_000L, 4_000L)
        assertEquals(delays, GUARD_BUSY_RETRY_DELAYS_MILLIS)
        for ((index, delay) in delays.withIndex()) {
            val retry = assertIs<NvidiaGuardDecision.Retry>(guard.onFailure(100L + index, 3, "bridge-unavailable", clock.now))
            assertEquals(index + 1, retry.attempt)
            assertEquals(delay, retry.delayMillis)
            assertTrue(guard.holding)
            assertFalse(guard.commitPending(clock.now + delay - 1L))
            assertTrue(guard.commitPending(clock.now + delay))
            assertFalse(guard.holding)
            // The retry is a new attempt of the very same configuration: nothing was lowered and nothing counted.
            assertEquals(index + 1, guard.attempt)
            assertEquals(NvidiaGuardCaps(), guard.caps())
            assertEquals(0, guard.transitionCount)
            assertFalse(guard.steppedDown)
            clock.now += delay
        }
        val exhausted = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(103L, 3, "bridge-unavailable", clock.now))
        assertEquals(NvidiaGiveUpReason.BUSY_EXHAUSTED, exhausted.reason)
        assertEquals(NvidiaMemoryEffect.NONE, exhausted.memory, "a busy resource is never remembered")
    }

    @Test fun aBusyRetryIsNotHeldBackByTheStepHoldAndDoesNotUseTheTransitionBudget() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(1L, 9, "process-bypass", clock.now))
        assertTrue(guard.commitPending(clock.now))
        clock.now += 100L
        val retry = assertIs<NvidiaGuardDecision.Retry>(guard.onFailure(2L, 3, "process-bypass", clock.now))
        assertEquals(1_000L, retry.delayMillis)
        assertTrue(guard.commitPending(clock.now + 1_000L))
        assertEquals(1, guard.transitionCount)
    }

    @Test fun aFailureTokenIsHandledOnce() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock))
        assertIs<NvidiaGuardDecision.Retry>(guard.onFailure(5L, 3, "bridge-unavailable", clock.now))
        assertSame(NvidiaGuardDecision.NoChange, guard.onFailure(5L, 3, "bridge-unavailable", clock.now))
        assertTrue(guard.commitPending(clock.now + 1_000L))
        assertEquals(1, guard.attempt)
        // The next busy failure (a new token) is the second retry, so the count was not raised twice.
        val next = assertIs<NvidiaGuardDecision.Retry>(guard.onFailure(6L, 3, "bridge-unavailable", clock.now + 1_000L))
        assertEquals(2, next.attempt)
        assertEquals(2_000L, next.delayMillis)
    }

    // ---- Everything else --------------------------------------------------------------------

    @Test fun aColourFailureChangesNothingAndKeepsTheExistingMessage() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        assertSame(NvidiaGuardDecision.NoChange, guard.onFailure(1L, 5, "process-bypass", clock.now))
        assertNull(guard.gaveUp)
        assertFalse(guard.holding)
        assertEquals(NvidiaGuardCaps(), guard.caps())
        assertEquals(0, guard.attempt)
    }

    @Test fun aColourFailureMarksTheSourceSoItIsNotRequestedAgainUntilANewSourceOrAFreshStart() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        assertFalse(guard.colorFailed)
        assertSame(NvidiaGuardDecision.NoChange, guard.onFailure(1L, 5, "process-bypass", clock.now))
        assertTrue(guard.colorFailed)
        // It is a mark of its own and not a give-up: the line of the source is the colour line, not the failure line.
        assertNull(guard.gaveUp)
        assertEquals(0, guard.transitionCount)
        assertEquals(NvidiaGuardCaps(), guard.caps())
        // A later colour failure of the same source changes nothing more, and the same source keeps the mark.
        assertSame(NvidiaGuardDecision.NoChange, guard.onFailure(2L, 5, "bridge-unavailable", clock.now))
        guard.selectSource("1:1")
        assertTrue(guard.colorFailed)
        // A different source starts clean, and so does a fresh start for the same source.
        guard.selectSource("1:2")
        assertFalse(guard.colorFailed)
        assertSame(NvidiaGuardDecision.NoChange, guard.onFailure(3L, 5, "process-bypass", clock.now))
        assertTrue(guard.colorFailed)
        guard.resetForSwitch()
        assertFalse(guard.colorFailed)
    }

    @Test fun onlyACodeFiveFailureMarksTheSourceAsColourRejected() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = false)
        for (code in listOf(1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 0, 99)) {
            guard.resetForSwitch()
            guard.onFailure(code.toLong() + 100L, code, "process-bypass", clock.now)
            assertFalse(guard.colorFailed, "code $code")
        }
    }

    @Test fun contractStaleAndUnknownFailuresGiveTheSourceUpAndOnlyAnAbiMismatchIsRemembered() {
        val cases = listOf(
            Triple(1, NvidiaGiveUpReason.CONTRACT, NvidiaMemoryEffect.NONE),
            Triple(2, NvidiaGiveUpReason.ABI_MISMATCH, NvidiaMemoryEffect.REMEMBER_NOW),
            Triple(4, NvidiaGiveUpReason.STALE_OR_RESET, NvidiaMemoryEffect.NONE),
            Triple(10, NvidiaGiveUpReason.STALE_OR_RESET, NvidiaMemoryEffect.NONE),
            Triple(0, NvidiaGiveUpReason.UNKNOWN_FAILURE, NvidiaMemoryEffect.NONE),
            Triple(77, NvidiaGiveUpReason.UNKNOWN_FAILURE, NvidiaMemoryEffect.NONE),
        )
        for ((code, reason, memory) in cases) {
            val clock = AdaptiveGuardTestClock()
            val guard = configured(newGuard(clock))
            val decision = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(1L, code, "process-bypass", clock.now), "$code")
            assertEquals(reason, decision.reason, "$code")
            assertEquals(memory, decision.memory, "$code")
            assertEquals(reason, guard.gaveUp)
            // Once given up, nothing more happens for the source.
            assertSame(NvidiaGuardDecision.NoChange, guard.onFailure(2L, 6, "process-bypass", clock.now))
        }
        val clock = AdaptiveGuardTestClock()
        val staleReset = assertIs<NvidiaGuardDecision.GiveUp>(
            configured(newGuard(clock)).onFailure(1L, 11, "seek-reset-retired", clock.now),
        )
        assertEquals(NvidiaGiveUpReason.STALE_OR_RESET, staleReset.reason)
    }

    @Test fun aFailureBeyondTheSuperResolutionBoxIsContainedAndNeverRemembered() {
        for (code in listOf(6, 7, 8, 9)) {
            val clock = AdaptiveGuardTestClock()
            val guard = configured(newGuard(clock), quality = 4, hdr = true, extendedRange = true)
            val decision = assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(1L, code, "process-bypass", clock.now), "$code")
            assertEquals(NvidiaGiveUpReason.EXTENDED_RANGE, decision.reason, "$code")
            assertEquals(NvidiaMemoryEffect.EXTENDED_RANGE_DENIED, decision.memory, "$code")
            assertEquals(NvidiaGiveUpReason.EXTENDED_RANGE, guard.gaveUp)
        }
        // A busy resource and a component mismatch have nothing to do with the input size.
        val clock = AdaptiveGuardTestClock()
        val busy = configured(newGuard(clock), extendedRange = true)
        assertIs<NvidiaGuardDecision.Retry>(busy.onFailure(1L, 3, "process-bypass", clock.now))
        val abi = configured(newGuard(clock), extendedRange = true)
        assertEquals(NvidiaMemoryEffect.REMEMBER_NOW, assertIs<NvidiaGuardDecision.GiveUp>(abi.onFailure(1L, 2, "process-bypass", clock.now)).memory)
    }

    @Test fun theSameFailureInsideTheSuperResolutionBoxIsNotExtendedRange() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = false, extendedRange = false)
        assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(1L, 6, "process-bypass", clock.now))
    }

    // ---- Source and reset -------------------------------------------------------------------

    @Test fun aNewSourceStartsFromScratchAndANullKeyKeepsTheState() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(1L, 6, "process-bypass", clock.now))
        assertTrue(guard.commitPending(clock.now))
        assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(2L, 6, "process-bypass", clock.now))
        guard.selectSource(null)
        assertEquals("1:1", guard.sourceKey)
        assertNotNull(guard.gaveUp, "a moment without a current source does not make a failing source look new")
        guard.selectSource("1:1")
        assertNotNull(guard.gaveUp, "the same source keeps its state")
        guard.selectSource("1:2")
        assertNull(guard.gaveUp)
        assertEquals(NvidiaGuardCaps(), guard.caps())
        assertEquals(0, guard.attempt)
        assertEquals(0, guard.transitionCount)
        assertFalse(guard.steppedDown)
        assertFalse(guard.holding)
    }

    @Test fun aSwitchOrANewDetectionGivesOneFreshAttemptForTheSameSource() {
        val clock = AdaptiveGuardTestClock()
        val guard = configured(newGuard(clock), quality = 4, hdr = false)
        assertIs<NvidiaGuardDecision.Retry>(guard.onFailure(1L, 3, "process-bypass", clock.now))
        assertTrue(guard.holding)
        guard.resetForSwitch()
        assertFalse(guard.holding)
        assertEquals("1:1", guard.sourceKey)
        assertEquals(0, guard.attempt)
        // The failure counters are fresh too: a new token is a first busy failure again.
        val again = assertIs<NvidiaGuardDecision.Retry>(guard.onFailure(1L, 3, "process-bypass", clock.now))
        assertEquals(1, again.attempt)
        assertEquals(1_000L, again.delayMillis)
    }

    // ---- Overload -------------------------------------------------------------------------

    private fun overloadGuard(quality: Int = 4, hdr: Boolean = false, frameRate: Double? = 60.0):
        Pair<DesktopNvidiaAdaptiveGuard, AdaptiveGuardSampleFeeder> {
        val guard = configured(newGuard(), quality = quality, hdr = hdr, frameRate = frameRate)
        guard.noteConfiguration(start)
        return Pair(guard, AdaptiveGuardSampleFeeder(guard, start))
    }

    @Test fun nothingIsJudgedDuringTheTenSecondsAfterAConfigurationChange() {
        val (guard, feeder) = overloadGuard()
        // Nine seconds of terrible drops right after the configuration are the cold start of the NGX core.
        assertEquals(emptyList(), feeder.run(9, dropsPerTick = 50L))
        assertFalse(guard.holding)
        assertNull(guard.gaveUp)
    }

    @Test fun twoBadWindowsInARowStepDownOnceAndAGoodWindowBetweenThemResets() {
        val (guard, feeder) = overloadGuard()
        // Tick 10 opens the first window; the windows close at ticks 20 and 30. Four lost frames a second are
        // forty a window, over the 36 that sixty frames a second allow.
        val decisions = feeder.run(30, dropsPerTick = 4L)
        assertEquals(listOf(30), decisions.map { it.first })
        val step = assertIs<NvidiaGuardDecision.StepDown>(decisions.single().second)
        assertEquals(NvidiaRung(3, false), step.rung)
        assertEquals(NvidiaStepReason.OVERLOAD, step.reason)
        assertEquals(1, guard.overloadStepCount)

        val (resetGuard, resetFeeder) = overloadGuard()
        assertEquals(emptyList(), resetFeeder.run(10, dropsPerTick = 4L))
        assertEquals(emptyList(), resetFeeder.run(10, dropsPerTick = 4L), "first bad window")
        assertEquals(emptyList(), resetFeeder.run(10, dropsPerTick = 0L), "a good window resets the streak")
        assertEquals(emptyList(), resetFeeder.run(10, dropsPerTick = 4L), "a bad window after a good one is the first again")
        assertFalse(resetGuard.holding)
    }

    @Test fun theDropThresholdIsSixPercentOfTheFramesOfTheWindowWithAFloorOfSix() {
        // (frame rate, frames lost in a window, bad). Sixty frames a second: 36 is the threshold.
        val cases = listOf(
            Triple(60.0, 35L, false), Triple(60.0, 36L, true),
            Triple(24.0, 14L, false), Triple(24.0, 15L, true),
            Triple(5.0, 5L, false), Triple(5.0, 6L, true),
        )
        for ((frameRate, lost, bad) in cases) {
            val (guard, feeder) = overloadGuard(frameRate = frameRate)
            assertEquals(emptyList(), feeder.run(10))
            assertSame(NvidiaGuardDecision.NoChange, feeder.windowLosing(lost), "first window $frameRate $lost")
            val second = feeder.windowLosing(lost)
            if (bad) assertIs<NvidiaGuardDecision.StepDown>(second, "$frameRate $lost") else assertSame(NvidiaGuardDecision.NoChange, second, "$frameRate $lost")
            assertEquals(bad, guard.holding, "$frameRate $lost")
        }
    }

    @Test fun anUnknownFrameRateCountsAsSixty() {
        for ((lost, bad) in listOf(35L to false, 36L to true)) {
            val (guard, feeder) = overloadGuard(frameRate = null)
            assertEquals(emptyList(), feeder.run(10))
            feeder.windowLosing(lost)
            feeder.windowLosing(lost)
            assertEquals(bad, guard.holding, "$lost")
        }
    }

    @Test fun delayedFramesCountLikeDroppedFramesAndTheyAddUp() {
        val (guard, feeder) = overloadGuard()
        // Two a second of each is forty a window.
        assertEquals(listOf(30), feeder.run(30, dropsPerTick = 2L, delayedPerTick = 2L).map { it.first })
        assertTrue(guard.holding)
    }

    @Test fun aSyncOffsetOfAQuarterSecondForTheWholeWindowIsABadWindow() {
        val (guard, feeder) = overloadGuard()
        val decisions = feeder.run(30, avSync = 0.25)
        assertEquals(listOf(30), decisions.map { it.first })
        assertIs<NvidiaGuardDecision.StepDown>(decisions.single().second)
        assertTrue(guard.holding)

        // A single reading below a quarter second inside every window means the offset did not hold: not bad.
        val (steadyGuard, steadyFeeder) = overloadGuard()
        assertEquals(emptyList(), steadyFeeder.run(10))
        for (window in 1..4) {
            for (index in 1..10) steadyFeeder.tick(avSync = if (index == 5) 0.1 else 0.4)
        }
        assertFalse(steadyGuard.holding)
        // A negative offset counts by its size.
        val (negativeGuard, negativeFeeder) = overloadGuard()
        assertEquals(listOf(30), negativeFeeder.run(30, avSync = -0.5).map { it.first })
        assertTrue(negativeGuard.holding)
    }

    @Test fun aPauseASeekACacheStallAnotherSpeedOrUnreadableCountersNeverCountAsOverload() {
        val (guard, feeder) = overloadGuard()
        assertEquals(emptyList(), feeder.run(10))
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L, paused = true))
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L, seeking = true))
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L, pausedForCache = true))
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L, speed = 1.25))
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L, speed = 0.5))
        // Counters that cannot be read void the window as well.
        for (index in 1..60) {
            feeder.at += 1_000L
            val decision = guard.observePlayback(NvidiaPlaybackHealthSample(1L, feeder.at, null, null, 0.9, false, false, false, 1.0))
            assertSame(NvidiaGuardDecision.NoChange, decision)
        }
        assertFalse(guard.holding)
        assertNull(guard.gaveUp)
    }

    @Test fun anInterruptionVoidsTheWindowSoTheTwoBadWindowsMustFollowEachOtherAgain() {
        val (guard, feeder) = overloadGuard()
        assertEquals(emptyList(), feeder.run(20, dropsPerTick = 6L), "the first bad window closes at tick 20")
        assertEquals(emptyList(), feeder.run(1, dropsPerTick = 6L, paused = true))
        val resumed = feeder.run(30, dropsPerTick = 6L)
        // The streak restarted at the pause: the first bad window closes ten ticks after the resume (no decision)
        // and the second one twenty ticks after it.
        assertEquals(listOf(21), resumed.map { it.first })
        assertIs<NvidiaGuardDecision.StepDown>(resumed.single().second)
        assertTrue(guard.holding)
    }

    @Test fun aGapInTheSamplesOrCountersThatGoBackwardsStartTheWindowOver() {
        val (guard, feeder) = overloadGuard()
        assertEquals(emptyList(), feeder.run(25, dropsPerTick = 6L))
        // Four seconds without a sample: the window of the readings before it is void.
        feeder.at += 4_000L
        assertEquals(emptyList(), feeder.run(10, dropsPerTick = 6L))
        // A new file resets the counters to zero: also a fresh start, never a negative load.
        feeder.drops = 0L
        feeder.delayed = 0L
        assertEquals(emptyList(), feeder.run(15, dropsPerTick = 6L))
        assertFalse(guard.holding)
    }

    @Test fun afterAnOverloadStepTheNextWindowMustShowThirtyPercentLessLoadOrTheSourceIsBypassed() {
        val (guard, feeder) = overloadGuard(quality = 4, hdr = true)
        // Six a second is sixty a window: 1.67 times the threshold. The first step goes to quality 3 with HDR.
        val first = feeder.run(30, dropsPerTick = 6L)
        assertEquals(listOf(30), first.map { it.first })
        assertEquals(NvidiaRung(3, true), assertIs<NvidiaGuardDecision.StepDown>(first.single().second).rung)
        assertTrue(guard.commitPending(feeder.at))
        guard.noteConfiguration(feeder.at)

        // Four a second is forty: still bad (1.11) but 33 percent lower than before, so the guard keeps going. Two
        // bad windows in a row take the second overload step.
        val second = feeder.run(30, dropsPerTick = 4L)
        assertEquals(listOf(30), second.map { it.first })
        assertEquals(NvidiaRung(2, true), assertIs<NvidiaGuardDecision.StepDown>(second.single().second).rung)
        assertEquals(2, guard.overloadStepCount)
        assertTrue(guard.commitPending(feeder.at))
        guard.noteConfiguration(feeder.at)

        // The same forty a window after a step from 1.11 is not 30 percent less: bypass for the source.
        val third = feeder.run(25, dropsPerTick = 4L)
        assertEquals(listOf(20), third.map { it.first })
        val giveUp = assertIs<NvidiaGuardDecision.GiveUp>(third.single().second)
        assertEquals(NvidiaGiveUpReason.OVERLOAD_NO_IMPROVEMENT, giveUp.reason)
        assertEquals(NvidiaMemoryEffect.NONE, giveUp.memory, "smoothness is never remembered")
        assertTrue(assertNotNull(guard.gaveUp).overload)
        // Drops that persist in bypass are not the guard's to chase: nothing more is decided.
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L))
    }

    @Test fun anUnimprovedFirstStepIsBypassedAtOnce() {
        val (guard, feeder) = overloadGuard(quality = 4, hdr = false)
        val first = feeder.run(30, dropsPerTick = 4L)
        assertEquals(NvidiaRung(3, false), assertIs<NvidiaGuardDecision.StepDown>(first.single().second).rung)
        assertTrue(guard.commitPending(feeder.at))
        guard.noteConfiguration(feeder.at)
        val after = feeder.run(30, dropsPerTick = 4L)
        assertEquals(listOf(20), after.map { it.first })
        assertEquals(NvidiaGiveUpReason.OVERLOAD_NO_IMPROVEMENT, assertIs<NvidiaGuardDecision.GiveUp>(after.single().second).reason)
    }

    @Test fun aClearlyBetterWindowAfterAStepEndsTheWatchWithoutAnotherStep() {
        val (guard, feeder) = overloadGuard(quality = 4, hdr = false)
        assertIs<NvidiaGuardDecision.StepDown>(feeder.run(30, dropsPerTick = 6L).single().second)
        assertTrue(guard.commitPending(feeder.at))
        guard.noteConfiguration(feeder.at)
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 1L))
        assertEquals(1, guard.overloadStepCount)
        assertNull(guard.gaveUp)
        assertEquals(NvidiaGuardCaps(3, false), guard.caps())
    }

    @Test fun atMostTwoOverloadStepsAreTakenThenTheSourceIsBypassed() {
        val (guard, feeder) = overloadGuard(quality = 4, hdr = true)
        assertEquals(NvidiaRung(3, true), assertIs<NvidiaGuardDecision.StepDown>(feeder.run(30, dropsPerTick = 12L).single().second).rung)
        assertTrue(guard.commitPending(feeder.at))
        guard.noteConfiguration(feeder.at)
        // 8 a second: 2.2 times the threshold, 33 percent below the 3.3 before, so the second step follows.
        assertEquals(NvidiaRung(2, true), assertIs<NvidiaGuardDecision.StepDown>(feeder.run(30, dropsPerTick = 8L).single().second).rung)
        assertTrue(guard.commitPending(feeder.at))
        guard.noteConfiguration(feeder.at)
        // 5 a second is 1.39, improved against 2.2 and still bad: a third step is not allowed.
        val last = feeder.run(30, dropsPerTick = 5L)
        assertEquals(listOf(30), last.map { it.first })
        assertEquals(NvidiaGiveUpReason.OVERLOAD_EXHAUSTED, assertIs<NvidiaGuardDecision.GiveUp>(last.single().second).reason)
        assertEquals(2, guard.overloadStepCount)
        assertTrue(assertNotNull(guard.gaveUp).overload)
    }

    @Test fun overloadWithNoLowerRungBypassesTheSource() {
        val (guard, feeder) = overloadGuard(quality = 1, hdr = false)
        val decisions = feeder.run(30, dropsPerTick = 6L)
        assertEquals(listOf(30), decisions.map { it.first })
        assertEquals(NvidiaGiveUpReason.OVERLOAD_NO_LOWER_RUNG, assertIs<NvidiaGuardDecision.GiveUp>(decisions.single().second).reason)
        assertTrue(assertNotNull(guard.gaveUp).overload)
    }

    @Test fun overloadOnTheDriverRouteOrHdrAloneHasOnlyBypass() {
        val (guard, feeder) = overloadGuard(quality = 4, hdr = true)
        guard.configure(4, true, false, false, 60.0)
        val decisions = feeder.run(30, dropsPerTick = 6L)
        assertEquals(NvidiaGiveUpReason.OVERLOAD_NO_LOWER_RUNG, assertIs<NvidiaGuardDecision.GiveUp>(decisions.single().second).reason)
    }

    @Test fun overloadAfterTheTransitionCapGivesUpInsteadOfStepping() {
        val clock = AdaptiveGuardTestClock(start)
        val guard = configured(newGuard(clock), quality = 4, hdr = true)
        for (index in 1..3) {
            assertIs<NvidiaGuardDecision.StepDown>(guard.onFailure(index.toLong(), 9, "process-bypass", clock.now))
            assertTrue(guard.commitPending(clock.now))
            clock.now += GUARD_HOLD_MILLIS
        }
        guard.noteConfiguration(clock.now)
        val feeder = AdaptiveGuardSampleFeeder(guard, clock.now)
        val decisions = feeder.run(30, dropsPerTick = 6L)
        assertEquals(NvidiaGiveUpReason.OVERLOAD_EXHAUSTED, assertIs<NvidiaGuardDecision.GiveUp>(decisions.single().second).reason)
    }

    @Test fun aDecidedStepPausesOverloadJudgementUntilItIsCommitted() {
        val (guard, feeder) = overloadGuard()
        assertIs<NvidiaGuardDecision.StepDown>(feeder.run(30, dropsPerTick = 6L).single().second)
        assertTrue(guard.holding)
        // While the step waits nothing is judged, however bad the readings are.
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L))
    }

    @Test fun overloadSamplesAfterAFailureOrBeforeAnyConfigurationAreIgnored() {
        val guard = configured(newGuard(), quality = 4, hdr = false)
        val feeder = AdaptiveGuardSampleFeeder(guard, start)
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L), "no configuration was installed yet")
        guard.noteConfiguration(feeder.at)
        assertIs<NvidiaGuardDecision.GiveUp>(guard.onFailure(1L, 1, "process-bypass", feeder.at))
        assertEquals(emptyList(), feeder.run(60, dropsPerTick = 50L), "a given up source is never judged")
    }

    // ---- Healthy streak -------------------------------------------------------------------

    @Test fun aStreamHeldForThirtySecondsClearsTheStreakExactlyOnce() {
        val guard = newGuard()
        assertFalse(guard.observeProofHeld(1L, true, 0L))
        assertFalse(guard.observeProofHeld(1L, true, GUARD_HEALTHY_CLEAR_MILLIS - 1L))
        assertTrue(guard.observeProofHeld(1L, true, GUARD_HEALTHY_CLEAR_MILLIS))
        assertFalse(guard.observeProofHeld(1L, true, GUARD_HEALTHY_CLEAR_MILLIS * 2), "only once for one configuration")
        // A new configuration starts counting again.
        assertFalse(guard.observeProofHeld(2L, true, GUARD_HEALTHY_CLEAR_MILLIS * 2))
        assertTrue(guard.observeProofHeld(2L, true, GUARD_HEALTHY_CLEAR_MILLIS * 3))
    }

    @Test fun aGapInTheHeldStateRestartsTheThirtySeconds() {
        val guard = newGuard()
        assertFalse(guard.observeProofHeld(1L, true, 0L))
        assertFalse(guard.observeProofHeld(1L, true, 20_000L))
        assertFalse(guard.observeProofHeld(1L, false, 25_000L))
        assertFalse(guard.observeProofHeld(1L, true, 26_000L))
        assertFalse(guard.observeProofHeld(1L, true, 26_000L + GUARD_HEALTHY_CLEAR_MILLIS - 1L))
        assertTrue(guard.observeProofHeld(1L, true, 26_000L + GUARD_HEALTHY_CLEAR_MILLIS))
    }

    @Test fun aClockThatWentBackwardsNeverClearsTheStreak() {
        val guard = newGuard()
        assertFalse(guard.observeProofHeld(1L, true, 100_000L))
        assertFalse(guard.observeProofHeld(1L, true, 10_000L))
        assertFalse(guard.observeProofHeld(1L, true, 50_000L))
    }

    // ---- Failure memory model -------------------------------------------------------------

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val key = "0123456789abcdef"

    @Test fun twoDeviceFailuresOrOneAtTheLowestRungRememberTheFailureForTwentyFourHours() {
        val first = nvidiaHealthAfterDeviceFailure(null, key, now, atLowestRung = false, failedQuality = 4)
        assertEquals(1, first.streak)
        assertEquals(0L, first.rememberedUntilMillis)
        assertEquals(now, first.updatedAtMillis)
        assertFalse(nvidiaHealthViewOf(first, now).remembered)

        val second = nvidiaHealthAfterDeviceFailure(first, key, now + 1_000L, atLowestRung = false, failedQuality = 4)
        assertEquals(2, second.streak)
        assertEquals(now + 1_000L + GUARD_REMEMBER_MILLIS, second.rememberedUntilMillis)
        assertTrue(nvidiaHealthViewOf(second, now + 2_000L).remembered)

        val lowest = nvidiaHealthAfterDeviceFailure(null, key, now, atLowestRung = true, failedQuality = 1)
        assertEquals(1, lowest.streak)
        assertEquals(now + GUARD_REMEMBER_MILLIS, lowest.rememberedUntilMillis)
        assertEquals(day, GUARD_REMEMBER_MILLIS)
    }

    @Test fun aRememberedFailureEndsAtItsTimeAndIsNeverKeptLongerThanSevenDays() {
        val entry = nvidiaHealthAfterRemember(null, key, now)
        assertTrue(nvidiaHealthViewOf(entry, now + day - 1L).remembered)
        assertFalse(nvidiaHealthViewOf(entry, now + day).remembered, "the end of the cooldown is not remembered any more")
        // A file that claims a hundred days is cut to seven.
        val claimed = NvidiaHealthEntry(key, streak = 2, rememberedUntilMillis = now + 100 * day, updatedAtMillis = now)
        val normalized = assertNotNull(nvidiaHealthNormalize(claimed, now))
        assertEquals(now + 7 * day, normalized.rememberedUntilMillis)
        assertEquals(7 * day, GUARD_REMEMBER_MAX_MILLIS)
    }

    @Test fun twoSourcesFailingAtTheSameQualityLearnALowerCeilingThatOnlyGoesDown() {
        val one = nvidiaHealthAfterDeviceFailure(null, key, now, false, 4)
        assertEquals(0, one.learnedQualityCeiling)
        assertEquals(1, one.failedQualityCount)
        val two = nvidiaHealthAfterDeviceFailure(one, key, now, false, 4)
        assertEquals(3, two.learnedQualityCeiling)
        assertEquals(now + GUARD_LEARNED_TTL_MILLIS, two.learnedUntilMillis)
        assertEquals(3, assertNotNull(nvidiaHealthViewOf(two, now).learnedQualityCeiling))
        // Another level resets the count and keeps what was learned.
        val other = nvidiaHealthAfterDeviceFailure(two, key, now, false, 3)
        assertEquals(1, other.failedQualityCount)
        assertEquals(3, other.learnedQualityCeiling)
        // Two failures at level 3 lower it to 2; a later pair at level 4 never raises it again.
        val lowered = nvidiaHealthAfterDeviceFailure(other, key, now, false, 3)
        assertEquals(2, lowered.learnedQualityCeiling)
        val raisedAttempt = nvidiaHealthAfterDeviceFailure(
            nvidiaHealthAfterDeviceFailure(lowered, key, now, false, 4), key, now, false, 4,
        )
        assertEquals(2, raisedAttempt.learnedQualityCeiling)
        // Level 1 can only learn a ceiling of 1.
        val floor = nvidiaHealthAfterDeviceFailure(nvidiaHealthAfterDeviceFailure(null, key, now, false, 1), key, now, false, 1)
        assertEquals(1, floor.learnedQualityCeiling)
    }

    @Test fun aLearnedCeilingExpiresAfterSevenDays() {
        val learned = nvidiaHealthAfterDeviceFailure(nvidiaHealthAfterDeviceFailure(null, key, now, false, 4), key, now, false, 4)
        assertNotNull(nvidiaHealthViewOf(learned, now + 7 * day - 1L).learnedQualityCeiling)
        assertNull(nvidiaHealthViewOf(learned, now + 7 * day).learnedQualityCeiling)
    }

    @Test fun anExtendedRangeFailureDeniesTheRangeForSevenDaysAndIsNeverAFailureMemory() {
        val entry = nvidiaHealthAfterExtendedRange(null, key, now)
        assertEquals(0, entry.streak)
        assertEquals(0L, entry.rememberedUntilMillis)
        assertEquals(now + 7 * day, entry.extendedRangeDeniedUntilMillis)
        val view = nvidiaHealthViewOf(entry, now + day)
        assertTrue(view.extendedRangeDenied)
        assertFalse(view.remembered)
        assertFalse(nvidiaHealthViewOf(entry, now + 7 * day).extendedRangeDenied)
        // It never touches a remembered failure of the same key.
        val both = nvidiaHealthAfterExtendedRange(nvidiaHealthAfterRemember(null, key, now), key, now)
        assertTrue(nvidiaHealthViewOf(both, now).remembered)
        assertTrue(nvidiaHealthViewOf(both, now).extendedRangeDenied)
    }

    @Test fun aHealthyStreamClearsTheStreakOnly() {
        assertNull(nvidiaHealthAfterHealthy(null, now))
        val clean = NvidiaHealthEntry(key, extendedRangeDeniedUntilMillis = now + day, updatedAtMillis = now)
        assertSame(clean, nvidiaHealthAfterHealthy(clean, now + 1L), "nothing to clear")
        val failing = nvidiaHealthAfterDeviceFailure(null, key, now, false, 4)
        val cleared = assertNotNull(nvidiaHealthAfterHealthy(failing, now + 5L))
        assertEquals(0, cleared.streak)
        assertEquals(failing.failedQualityCount, cleared.failedQualityCount)
        val remembered = nvidiaHealthAfterRemember(failing, key, now)
        assertTrue(nvidiaHealthViewOf(assertNotNull(nvidiaHealthAfterHealthy(remembered, now + 5L)), now + 5L).remembered)
    }

    @Test fun anEntryFromTheFutureOrOlderThanFourteenDaysIsExpired() {
        val fresh = NvidiaHealthEntry(key, streak = 1, updatedAtMillis = now)
        assertNotNull(nvidiaHealthNormalize(fresh, now))
        assertNull(nvidiaHealthNormalize(fresh, now - 1L), "a clock that went backwards: the timestamp is in the future")
        assertNull(nvidiaHealthNormalize(fresh.copy(updatedAtMillis = now + day), now), "skewed clock")
        assertNotNull(nvidiaHealthNormalize(fresh, now + GUARD_ENTRY_TTL_MILLIS))
        assertNull(nvidiaHealthNormalize(fresh, now + GUARD_ENTRY_TTL_MILLIS + 1L))
        assertEquals(14 * day, GUARD_ENTRY_TTL_MILLIS)
    }

    @Test fun anEntryThatCarriesNoInformationAnyMoreDisappears() {
        assertNull(nvidiaHealthNormalize(NvidiaHealthEntry(key, updatedAtMillis = now), now))
        val expired = NvidiaHealthEntry(key, rememberedUntilMillis = now - 1L, learnedQualityCeiling = 2,
            learnedUntilMillis = now - 1L, extendedRangeDeniedUntilMillis = now - 1L, updatedAtMillis = now)
        assertNull(nvidiaHealthNormalize(expired, now))
        assertEquals(NvidiaHealthView.None, nvidiaHealthViewOf(null, now))
        assertEquals(NvidiaHealthView.None, nvidiaHealthViewOf(expired, now))
    }

    @Test fun counterBoundsAreClampedWhenAnEntryIsNormalized() {
        val wild = NvidiaHealthEntry(key, streak = 10_000, failedQualityLevel = 9, failedQualityCount = 99_999, updatedAtMillis = now)
        val normalized = assertNotNull(nvidiaHealthNormalize(wild, now))
        assertEquals(GUARD_MAX_STREAK, normalized.streak)
        assertEquals(4, normalized.failedQualityLevel)
        assertEquals(GUARD_MAX_SOURCE_COUNT, normalized.failedQualityCount)
    }

    // ---- Key ------------------------------------------------------------------------------

    @Test fun theKeyIsSixteenLowercaseHexDigitsAndMatchesTheDocumentedDigest() {
        val gpuKey = "10de:2c02:8a4a1043:a1"
        val value = nvidiaHealthKey(gpuKey, 61742, "profile", NvidiaTier.ULTRA, NVIDIA_HEALTH_BACKEND_VEYRA)
        assertEquals("663f25477c29cfc9", value, "sha256 of the identity text, first sixteen hex digits")
        assertTrue(nvidiaHealthKeyValid(value))
        assertEquals("d893a03dfaad4251", nvidiaHealthKey(gpuKey, null, "profile", NvidiaTier.ULTRA, NVIDIA_HEALTH_BACKEND_VEYRA))
        assertEquals(value, nvidiaHealthKey(gpuKey, 61742, "profile", NvidiaTier.ULTRA, NVIDIA_HEALTH_BACKEND_VEYRA))
    }

    @Test fun aChangeOfDriverRuntimeTierBackendOrGpuIsADifferentKey() {
        val gpuKey = "10de:2c02:8a4a1043:a1"
        val base = nvidiaHealthKey(gpuKey, 61742, "profile", NvidiaTier.ULTRA, "veyra")
        val others = listOf(
            nvidiaHealthKey(gpuKey, 54601, "profile", NvidiaTier.ULTRA, "veyra"),
            nvidiaHealthKey(gpuKey, 61742, "another-profile", NvidiaTier.ULTRA, "veyra"),
            nvidiaHealthKey(gpuKey, 61742, "profile", NvidiaTier.HIGH, "veyra"),
            nvidiaHealthKey(gpuKey, 61742, "profile", NvidiaTier.ULTRA, NVIDIA_HEALTH_BACKEND_DRIVER),
            nvidiaHealthKey("10de:2684:12345678:a1", 61742, "profile", NvidiaTier.ULTRA, "veyra"),
        )
        for (other in others) assertTrue(other != base)
        assertEquals(others.size, others.toSet().size)
    }

    @Test fun theKeyValidatorAcceptsOnlySixteenLowercaseHexDigits() {
        assertTrue(nvidiaHealthKeyValid("0123456789abcdef"))
        for (bad in listOf("", "0123456789abcde", "0123456789abcdef0", "0123456789ABCDEF", "0123456789abcdeg", "0123456789abcde ")) {
            assertFalse(nvidiaHealthKeyValid(bad), bad)
        }
    }

    private fun scenarioCapability(id: String): NvidiaCapability {
        val scenario = NvidiaGoldenVectors.objects("scenarios").first { NvidiaGoldenVectors.string(it, "id") == id }
        return classifyNvidiaCapability(NvidiaGoldenVectors.scenarioInput(scenario))
    }

    @Test fun theKeyFollowsTheCapabilityAndAFailedProbeNeedsAVerifiedPhysicalIdentity() {
        val capable = assertIs<NvidiaCapability.Capable>(scenarioCapability("this-pc"))
        val expected = nvidiaHealthKey(capable.gpu.gpuKey, capable.gpu.driverPublicX100, "profile", capable.tier, "veyra")
        assertEquals(expected, nvidiaHealthKeyFor(capable, null, "profile", "veyra"))
        // Capabilities that carry no usable GPU identity have no key, and so no memory.
        for (none in listOf(NvidiaCapability.NotEvaluated, NvidiaCapability.Probing, NvidiaCapability.Absent,
            scenarioCapability("amd-only"), scenarioCapability("only-virtual"))) {
            assertNull(nvidiaHealthKeyFor(none, null, "profile", "veyra"), "$none")
        }
        val failed = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)
        assertNull(nvidiaHealthKeyFor(failed, null, "profile", "veyra"))
        val unverified = DesktopNvidiaObservedGpu("NVIDIA GeForce RTX 5080", 0x2C02, "000000000000e782", false, 61742)
        assertNull(nvidiaHealthKeyFor(failed, unverified, "profile", "veyra"))
        val verified = unverified.copy(verifiedPhysical = true)
        val observedKey = assertNotNull(nvidiaHealthKeyFor(failed, verified, "profile", "veyra"))
        assertTrue(nvidiaHealthKeyValid(observedKey))
        // The observed identity has no subsystem or revision, so it is not the key of the probed adapter.
        assertTrue(observedKey != expected)
    }
}
