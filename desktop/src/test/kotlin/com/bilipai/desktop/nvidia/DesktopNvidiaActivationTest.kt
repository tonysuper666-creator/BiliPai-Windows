package com.bilipai.desktop.nvidia

import com.bilipai.desktop.player.NvidiaVideoBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNvidiaActivationTest {
    private val vectors = NvidiaGoldenVectors

    private fun scenarioCapability(id: String): NvidiaCapability {
        val scenario = vectors.objects("scenarios").first { vectors.string(it, "id") == id }
        return classifyNvidiaCapability(vectors.scenarioInput(scenario))
    }

    private fun desktopCapable(): NvidiaCapability.Capable = assertIs<NvidiaCapability.Capable>(scenarioCapability("this-pc"))

    private fun laptopCapable(): NvidiaCapability.Capable =
        assertIs<NvidiaCapability.Capable>(scenarioCapability("mobile-and-cross-adapter"))

    private fun resolve(
        capability: NvidiaCapability,
        mode: DesktopNvidiaMode = DesktopNvidiaMode.AUTO,
        environment: DesktopNvidiaEnvironment = DesktopNvidiaEnvironment(),
        observedGpu: DesktopNvidiaObservedGpu? = null,
        sourceLoaded: Boolean = true,
        failureRemembered: Boolean = false,
        extendedRangeFailedForSource: Boolean = false,
        guardGaveUpForSource: Boolean = false,
    ): DesktopNvidiaActivationState = resolveDesktopNvidiaActivation(
        mode, capability, environment, observedGpu, sourceLoaded, failureRemembered,
        extendedRangeFailedForSource, guardGaveUpForSource,
    )

    private val verifiedObserved = DesktopNvidiaObservedGpu("NVIDIA GeForce RTX 5080", 0x2C02, "000000000000e782", true)

    // ---- precedence -----------------------------------------------------------------------

    @Test fun offWinsOverEverythingAndKeepsAKnownGpuName() {
        val capable = desktopCapable()
        val off = assertIs<DesktopNvidiaActivationState.Off>(
            resolve(capable, mode = DesktopNvidiaMode.OFF, environment = DesktopNvidiaEnvironment(gameLike = true), failureRemembered = true),
        )
        assertEquals(capable.gpu.description, off.gpuDescription)
        assertNull(assertIs<DesktopNvidiaActivationState.Off>(resolve(NvidiaCapability.NotEvaluated, mode = DesktopNvidiaMode.OFF)).gpuDescription)
        assertNull(assertIs<DesktopNvidiaActivationState.Off>(resolve(NvidiaCapability.Absent, mode = DesktopNvidiaMode.OFF)).gpuDescription)
        assertFalse(off.allowsRequest)
    }

    @Test fun notEvaluatedIsExactlyLegacyBehaviour() {
        val state = resolve(NvidiaCapability.NotEvaluated, environment = DesktopNvidiaEnvironment(gameLike = true), failureRemembered = true)
        assertEquals(DesktopNvidiaActivationState.Legacy, state)
        assertTrue(state.allowsRequest)
        assertNull(state.planOrNull)
    }

    @Test fun probingAbsentAndUnsupportedNeverRequest() {
        assertEquals(DesktopNvidiaActivationState.Probing, resolve(NvidiaCapability.Probing))
        assertEquals(DesktopNvidiaActivationState.NoNvidia, resolve(NvidiaCapability.Absent))
        val unsupported = assertIs<DesktopNvidiaActivationState.Unsupported>(
            resolve(NvidiaCapability.Unsupported(NvidiaUnsupportedReason.NOT_PHYSICAL)),
        )
        assertEquals(NvidiaUnsupportedReason.NOT_PHYSICAL, unsupported.reason)
        assertNull(unsupported.gpu)
        for (state in listOf(
            resolve(NvidiaCapability.Probing), resolve(NvidiaCapability.Absent),
            resolve(NvidiaCapability.Unsupported(NvidiaUnsupportedReason.PIN_UNSAFE)),
        )) {
            assertFalse(state.allowsRequest)
        }
    }

    @Test fun probeFailureWithoutAVerifiedPhysicalLuidIsUnidentified() {
        val failed = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)
        assertEquals(DesktopNvidiaActivationState.Unidentified, resolve(failed))
        assertEquals(DesktopNvidiaActivationState.Unidentified, resolve(failed, sourceLoaded = false))
        val unverified = verifiedObserved.copy(verifiedPhysical = false)
        assertEquals(DesktopNvidiaActivationState.Unidentified, resolve(failed, observedGpu = unverified))
        assertFalse(resolve(failed, observedGpu = unverified).allowsRequest)
    }

    @Test fun probeFailureWithAVerifiedLuidAllowsAHighCappedProfile() {
        val failed = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)
        val allowed = assertIs<DesktopNvidiaActivationState.Allowed>(resolve(failed, observedGpu = verifiedObserved))
        assertEquals(NvidiaTier.HIGH, allowed.plan.tier)
        assertTrue(NvidiaCapabilityNote.OBSERVED_IDENTITY in allowed.plan.notes)
        assertTrue(allowed.allowsRequest)
        val standby = assertIs<DesktopNvidiaActivationState.Standby>(resolve(failed, observedGpu = verifiedObserved, sourceLoaded = false))
        assertEquals(NvidiaTier.HIGH, standby.plan.tier)
        assertFalse(standby.allowsRequest)
    }

    @Test fun probeFailureWithAVerifiedPreRtxOrOldBlackwellObservationIsDeclined() {
        val failed = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)
        val gtx = verifiedObserved.copy(description = "NVIDIA GeForce GTX 1080", deviceId = 0x1B80)
        val declined = assertIs<DesktopNvidiaActivationState.Unsupported>(resolve(failed, observedGpu = gtx))
        assertEquals(NvidiaUnsupportedReason.PRE_RTX, declined.reason)
        val old = verifiedObserved.copy(driverPublicX100 = 55_000)
        val oldDeclined = assertIs<DesktopNvidiaActivationState.Unsupported>(resolve(failed, observedGpu = old))
        assertEquals(NvidiaUnsupportedReason.DRIVER_OLD, oldDeclined.reason)
    }

    @Test fun allowedAfterLoadAndStandbyBefore() {
        val capable = desktopCapable()
        val allowed = assertIs<DesktopNvidiaActivationState.Allowed>(resolve(capable))
        assertEquals(NvidiaTier.ULTRA, allowed.plan.tier)
        assertTrue(allowed.allowsRequest)
        assertNotNull(allowed.planOrNull)
        val standby = assertIs<DesktopNvidiaActivationState.Standby>(resolve(capable, sourceLoaded = false))
        assertEquals(NvidiaTier.ULTRA, standby.plan.tier)
        assertFalse(standby.allowsRequest)
    }

    @Test fun environmentAndFailureStatesFollowTheDocumentedOrder() {
        val capable = desktopCapable()
        val everything = DesktopNvidiaEnvironment(gameLike = true, onBattery = true, remoteOrVirtualOutput = true)
        assertIs<DesktopNvidiaActivationState.PausedGame>(
            resolve(capable, environment = everything, failureRemembered = true, extendedRangeFailedForSource = true, guardGaveUpForSource = true),
        )
        assertIs<DesktopNvidiaActivationState.PausedRemote>(
            resolve(capable, environment = everything.copy(gameLike = false), failureRemembered = true, guardGaveUpForSource = true),
        )
        assertIs<DesktopNvidiaActivationState.FailedRemembered>(
            resolve(capable, failureRemembered = true, extendedRangeFailedForSource = true, guardGaveUpForSource = true),
        )
        assertIs<DesktopNvidiaActivationState.FailedExtendedRange>(
            resolve(capable, extendedRangeFailedForSource = true, guardGaveUpForSource = true),
        )
        assertIs<DesktopNvidiaActivationState.FailedThisVideo>(resolve(capable, guardGaveUpForSource = true))
        for (state in listOf(
            resolve(capable, environment = everything),
            resolve(capable, failureRemembered = true),
            resolve(capable, guardGaveUpForSource = true),
        )) {
            assertFalse(state.allowsRequest)
            assertNotNull(state.planOrNull)
        }
    }

    @Test fun batteryPausesOnlyAMobileGpu() {
        val onBattery = DesktopNvidiaEnvironment(onBattery = true)
        assertIs<DesktopNvidiaActivationState.Allowed>(resolve(desktopCapable(), environment = onBattery))
        assertIs<DesktopNvidiaActivationState.PausedBattery>(resolve(laptopCapable(), environment = onBattery))
        assertIs<DesktopNvidiaActivationState.Allowed>(resolve(laptopCapable()))
    }

    @Test fun pausesClearByThemselvesWhenTheConditionClears() {
        val capable = desktopCapable()
        assertIs<DesktopNvidiaActivationState.PausedGame>(resolve(capable, environment = DesktopNvidiaEnvironment(gameLike = true)))
        assertIs<DesktopNvidiaActivationState.Allowed>(resolve(capable, environment = DesktopNvidiaEnvironment()))
    }

    @Test fun planCarriersExposeThePlanAndPlanlessStatesDoNot() {
        val capable = desktopCapable()
        val plan = buildNvidiaPlan(capable)
        assertEquals(plan, DesktopNvidiaActivationState.Allowed(plan).planOrNull)
        assertEquals(plan, DesktopNvidiaActivationState.PausedRemote(plan).planOrNull)
        assertNull(DesktopNvidiaActivationState.Probing.planOrNull)
        assertNull(DesktopNvidiaActivationState.Unidentified.planOrNull)
        assertNull(DesktopNvidiaActivationState.NoNvidia.planOrNull)
        assertNull(DesktopNvidiaActivationState.Off().planOrNull)
    }

    // ---- game detection -------------------------------------------------------------------

    private fun foreground(
        pid: Int? = 4242,
        own: Int = 100,
        state: Int? = null,
        shell: Boolean = false,
        covers: Boolean = false,
    ) = DesktopNvidiaForegroundSnapshot(pid, own, state, shell, covers)

    @Test fun busyFullScreenD3dAndPresentationStatesCountAsGameLike() {
        assertTrue(desktopNvidiaGameLike(foreground(state = DesktopNvidiaNotificationState.BUSY)))
        assertTrue(desktopNvidiaGameLike(foreground(state = DesktopNvidiaNotificationState.RUNNING_D3D_FULL_SCREEN)))
        assertTrue(desktopNvidiaGameLike(foreground(state = DesktopNvidiaNotificationState.PRESENTATION_MODE)))
        assertEquals(2, DesktopNvidiaNotificationState.BUSY)
        assertEquals(3, DesktopNvidiaNotificationState.RUNNING_D3D_FULL_SCREEN)
        assertEquals(4, DesktopNvidiaNotificationState.PRESENTATION_MODE)
        assertFalse(desktopNvidiaGameLike(foreground(state = 5)))
        assertFalse(desktopNvidiaGameLike(foreground(state = null)))
    }

    @Test fun aMonitorCoveringWindowOfAnotherProcessIsGameLikeButTheShellIsNot() {
        assertTrue(desktopNvidiaGameLike(foreground(covers = true)))
        assertFalse(desktopNvidiaGameLike(foreground(covers = true, shell = true)))
        assertFalse(desktopNvidiaGameLike(foreground(covers = false)))
        assertTrue(desktopNvidiaGameLike(foreground(covers = true, state = 5)))
    }

    @Test fun theApplicationsOwnFullScreenAndUnknownForegroundNeverYield() {
        assertFalse(desktopNvidiaGameLike(foreground(pid = 100, own = 100, state = 3, covers = true)))
        assertFalse(desktopNvidiaGameLike(foreground(pid = null, state = 3, covers = true)))
    }

    @Test fun narrowModeOnlyCountsFullScreenD3d() {
        assertTrue(desktopNvidiaGameLike(foreground(state = 3), broad = false))
        assertFalse(desktopNvidiaGameLike(foreground(state = 2), broad = false))
        assertFalse(desktopNvidiaGameLike(foreground(state = 4), broad = false))
        assertFalse(desktopNvidiaGameLike(foreground(covers = true), broad = false))
        assertFalse(desktopNvidiaGameLike(foreground(pid = 100, own = 100, state = 3), broad = false))
        assertTrue(GAME_YIELD_BROAD)
    }

    // ---- hysteresis -----------------------------------------------------------------------

    @Test fun enteringNeedsTwoConsecutivePositivePolls() {
        var state = DesktopNvidiaHysteresis()
        state = state.advance(true, 0L)
        assertFalse(state.active)
        state = state.advance(true, 2_000L)
        assertTrue(state.active)
        var blip = DesktopNvidiaHysteresis().advance(true, 0L)
        blip = blip.advance(false, 2_000L)
        blip = blip.advance(true, 4_000L)
        assertFalse(blip.active, "a clear poll in between restarts the count")
        assertEquals(2, NVIDIA_PAUSE_ENTER_POLLS)
    }

    @Test fun leavingNeedsFiveSecondsOfContinuousClearSignal() {
        var state = DesktopNvidiaHysteresis().advance(true, 0L).advance(true, 2_000L)
        assertTrue(state.active)
        state = state.advance(false, 10_000L)
        assertTrue(state.active)
        state = state.advance(false, 14_999L)
        assertTrue(state.active)
        state = state.advance(false, 15_000L)
        assertFalse(state.active)
        assertEquals(DesktopNvidiaHysteresis(), state)
        assertEquals(5_000L, NVIDIA_PAUSE_LEAVE_CLEAR_MILLIS)
    }

    @Test fun aPositivePollWhileLeavingRestartsTheClearWindow() {
        var state = DesktopNvidiaHysteresis().advance(true, 0L).advance(true, 2_000L)
        state = state.advance(false, 10_000L)
        state = state.advance(true, 13_000L)
        assertTrue(state.active)
        assertNull(state.clearSinceMillis)
        state = state.advance(false, 14_000L)
        state = state.advance(false, 18_999L)
        assertTrue(state.active)
        state = state.advance(false, 19_000L)
        assertFalse(state.active)
    }

    @Test fun aClockThatGoesBackwardsNeverLeavesEarly() {
        var state = DesktopNvidiaHysteresis().advance(true, 100_000L).advance(true, 102_000L)
        state = state.advance(false, 110_000L)
        state = state.advance(false, 5_000L)
        assertTrue(state.active)
        assertEquals(5_000L, state.clearSinceMillis)
        state = state.advance(false, 10_000L)
        assertFalse(state.active)
    }

    @Test fun environmentTrackerSmoothsEachReasonIndependently() {
        var tracker = DesktopNvidiaEnvironmentTracker()
        val game = DesktopNvidiaEnvironment(gameLike = true)
        tracker = tracker.advance(game, 0L)
        assertEquals(DesktopNvidiaEnvironment(), tracker.environment)
        tracker = tracker.advance(game.copy(onBattery = true), 2_000L)
        assertEquals(DesktopNvidiaEnvironment(gameLike = true), tracker.environment)
        tracker = tracker.advance(game.copy(onBattery = true), 4_000L)
        assertEquals(DesktopNvidiaEnvironment(gameLike = true, onBattery = true), tracker.environment)
        tracker = tracker.advance(DesktopNvidiaEnvironment(onBattery = true), 6_000L)
        assertTrue(tracker.environment.gameLike, "the game flag lingers until five clear seconds passed")
        tracker = tracker.advance(DesktopNvidiaEnvironment(onBattery = true), 11_000L)
        assertFalse(tracker.environment.gameLike)
        assertTrue(tracker.environment.onBattery)
        assertFalse(tracker.environment.remoteOrVirtualOutput)
    }

    @Test fun reconfigurationsAreSpacedAtLeastThreeSeconds() {
        assertTrue(nvidiaReconfigurationAllowed(null, 0L))
        assertFalse(nvidiaReconfigurationAllowed(1_000L, 3_999L))
        assertTrue(nvidiaReconfigurationAllowed(1_000L, 4_000L))
        assertTrue(nvidiaReconfigurationAllowed(10_000L, 5_000L), "a clock that went backwards allows the change")
        assertEquals(3_000L, NVIDIA_MIN_RECONFIGURE_INTERVAL_MILLIS)
    }

    // ---- status text over activation states (the single string owner) ---------------------

    @Test fun statusLinesDoNotShowATierWordWhileTheSwitchIsOff() {
        assertFalse(TIER_WORD_ENABLED)
        val plan = buildNvidiaPlan(desktopCapable())
        val word = assertNotNull(desktopNvidiaTierWord(NvidiaTier.ULTRA, NvidiaVideoBackend.VEYRA_CORE, true))
        val line = assertNotNull(
            desktopNvidiaActivationStatusLine(
                DesktopNvidiaActivationState.Standby(plan), NvidiaVideoBackend.VEYRA_CORE,
            ),
        )
        assertFalse(line.contains(word))
        assertEquals("RTX 5080 · " + DesktopNvidiaStatusText.STANDBY_TAIL, line)
        val withWord = assertNotNull(
            desktopNvidiaActivationStatusLine(
                DesktopNvidiaActivationState.Standby(plan), NvidiaVideoBackend.VEYRA_CORE, tierWordEnabled = true,
            ),
        )
        assertTrue(withWord.contains(word))
    }

    @Test fun tierWordNeverAppearsOnTheDriverRoute() {
        assertNull(desktopNvidiaTierWord(NvidiaTier.ULTRA, NvidiaVideoBackend.DRIVER, true))
        assertNull(desktopNvidiaTierWord(null, NvidiaVideoBackend.VEYRA_CORE, true))
        assertNull(desktopNvidiaTierWord(NvidiaTier.ULTRA, NvidiaVideoBackend.VEYRA_CORE, false))
    }

    @Test fun activationStatesMapToTheirOwnStatusLine() {
        assertNull(desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.Legacy))
        val plan = buildNvidiaPlan(desktopCapable())
        assertNull(desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.Allowed(plan)))
        assertEquals(DesktopNvidiaStatusText.PROBING, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.Probing))
        assertEquals(DesktopNvidiaStatusText.NO_NVIDIA, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.NoNvidia))
        assertEquals(DesktopNvidiaStatusText.UNIDENTIFIED, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.Unidentified))
        assertEquals(DesktopNvidiaStatusText.PAUSED_GAME, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.PausedGame(plan)))
        assertEquals(DesktopNvidiaStatusText.PAUSED_BATTERY, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.PausedBattery(plan)))
        assertEquals(DesktopNvidiaStatusText.PAUSED_REMOTE, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.PausedRemote(plan)))
        assertEquals(DesktopNvidiaStatusText.NOT_PHYSICAL, desktopNvidiaActivationStatusLine(
            DesktopNvidiaActivationState.Unsupported(NvidiaUnsupportedReason.NOT_PHYSICAL),
        ))
        assertEquals(DesktopNvidiaStatusText.PIN_UNSAFE, desktopNvidiaActivationStatusLine(
            DesktopNvidiaActivationState.Unsupported(NvidiaUnsupportedReason.PHYSICAL_UNVERIFIED),
        ))
        assertEquals(DesktopNvidiaStatusText.FAILED_EXTENDED_RANGE, desktopNvidiaActivationStatusLine(
            DesktopNvidiaActivationState.FailedExtendedRange(plan),
        ))
        assertEquals("RTX 5080 · " + DesktopNvidiaStatusText.FAILED_REMEMBERED_TAIL, desktopNvidiaActivationStatusLine(
            DesktopNvidiaActivationState.FailedRemembered(plan),
        ))
        assertEquals("RTX 5080 · " + DesktopNvidiaStatusText.FAILED_THIS_VIDEO_TAIL, desktopNvidiaActivationStatusLine(
            DesktopNvidiaActivationState.FailedThisVideo(plan),
        ))
        assertEquals("RTX 5080 · " + DesktopNvidiaStatusText.OFF_GPU_KNOWN_TAIL, desktopNvidiaActivationStatusLine(
            DesktopNvidiaActivationState.Off("NVIDIA GeForce RTX 5080"),
        ))
        assertEquals(DesktopNvidiaStatusText.OFF_GPU_UNKNOWN, desktopNvidiaActivationStatusLine(DesktopNvidiaActivationState.Off()))
    }
}
