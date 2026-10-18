package com.bilipai.desktop.player

import com.bilipai.desktop.nvidia.DesktopNvidiaActivationState
import com.bilipai.desktop.nvidia.DesktopNvidiaCapabilityMonitor
import com.bilipai.desktop.nvidia.DesktopNvidiaEnvironment
import com.bilipai.desktop.nvidia.DesktopNvidiaGpuProbe
import com.bilipai.desktop.nvidia.DesktopNvidiaHealthStore
import com.bilipai.desktop.nvidia.DesktopNvidiaObservedGpu
import com.bilipai.desktop.nvidia.DesktopNvidiaStatusKind
import com.bilipai.desktop.nvidia.DesktopNvidiaStatusText
import com.bilipai.desktop.nvidia.NvidiaCapability
import com.bilipai.desktop.nvidia.NvidiaGoldenVectors
import com.bilipai.desktop.nvidia.NvidiaHealthView
import com.bilipai.desktop.nvidia.NvidiaPhysicalVerdict
import com.bilipai.desktop.nvidia.NvidiaPlaybackHealthSample
import com.bilipai.desktop.nvidia.NvidiaProbeInput
import com.bilipai.desktop.nvidia.NvidiaTier
import com.bilipai.desktop.nvidia.NvidiaUnknownReason
import com.bilipai.desktop.nvidia.NvidiaUnsupportedReason
import com.bilipai.desktop.nvidia.buildNvidiaPlan
import com.bilipai.desktop.nvidia.classifyNvidiaCapability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** One golden scenario as the monitor's probe. No native call, no GPU, no JNA. */
private class SessionFixtureGpuProbe(private val scenario: JsonObject) : DesktopNvidiaGpuProbe {
    val calls = AtomicInteger()
    override fun probe(): NvidiaProbeInput {
        calls.incrementAndGet()
        return NvidiaGoldenVectors.scenarioInput(scenario)
    }
    override fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict =
        NvidiaPhysicalVerdict.UNKNOWN
}

class DesktopVideoEnhancementSessionTest {
    @Test fun automaticPreferenceCoversUnlabelledSourcesAndRemainsGlobalAcrossVideos() = runBlocking<Unit> {
        val enabled = MutableStateFlow(true)
        val writes = mutableListOf<Boolean>()
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, enabled, MutableStateFlow(true), MutableStateFlow(false), {
                writes += it; enabled.value = it; CompletableDeferred(Unit)
            }).use { enhancement ->
                val live = player.loadVersioned(PlaybackSource("file:///C:/unlabelled-video-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == live && enhancement.state.value.identity == "video:$live" }
                assertTrue(enhancement.state.value.requested)
                assertFalse(enhancement.state.value.active)
                assertNotNull(enhancement.setCurrentVideoEnabled(false)).join()
                awaitState { !enhancement.state.value.requested }
                val episode = player.loadVersioned(PlaybackSource("file:///C:/pgc-video-fixture.avi"))
                enhancement.bindVideoIdentity(null, episode)
                awaitState { enhancement.state.value.sourceVersion == episode }
                assertFalse(enhancement.state.value.requested)
                assertEquals(listOf(false), writes)
            }
        }
    }

    @Test fun failedOldSettingCompletionCannotPublishErrorIntoAReplacementSource() = runBlocking<Unit> {
        val submitted = CompletableDeferred<Unit>()
        val persistence = CompletableDeferred<Unit>()
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(false), {
                submitted.complete(Unit); persistence
            }).use { enhancement ->
                val old = player.loadVersioned(PlaybackSource("file:///C:/old-video-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == old }
                val job = assertNotNull(enhancement.setCurrentVideoEnabled(false))
                withTimeout(3_000) { submitted.await() }
                val replacement = player.loadVersioned(PlaybackSource("file:///C:/replacement-video-fixture.avi"))
                enhancement.bindVideoIdentity(null, replacement)
                awaitState { enhancement.state.value.sourceVersion == replacement }
                persistence.completeExceptionally(IllegalStateException("private fixture persistence failure"))
                withTimeout(3_000) { job.join() }
                assertNull(enhancement.state.value.error)
                assertEquals(replacement, enhancement.state.value.sourceVersion)
            }
        }
    }

    @Test fun closingThePlayerRevokesToggleEvenIfTheNumericSourceVersionDidNotChange() = runBlocking<Unit> {
        val writes = mutableListOf<Boolean>()
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(false), {
                writes += it; CompletableDeferred(Unit)
            }).use { enhancement ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/closing-video-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == version }
                player.close()
                assertEquals(version, player.currentSourceVersion)
                assertNull(enhancement.setCurrentVideoEnabled(false))
                assertTrue(writes.isEmpty())
            }
        }
    }

    @Test fun anUnmountedSessionDoesNotClearAForeignNativeConfigurationWhenClosing() = runBlocking<Unit> {
        MpvPlayer().use { player ->
            val enhancement = DesktopVideoEnhancementSession(player, MutableStateFlow(false),
                MutableStateFlow(true), MutableStateFlow(false), { CompletableDeferred(Unit) })
            val version = player.loadVersioned(PlaybackSource("file:///C:/foreign-config-fixture.avi"))
            awaitState { enhancement.state.value.sourceVersion == version }
            val foreign = assertNotNull(player.setNvidiaVideoEnhancementIfSourceVersion(version, NvidiaVideoOptions(2.0)))
            enhancement.close()
            assertEquals(foreign, player.nvidiaVideoState.value.configurationVersion)
            assertNull(enhancement.setCurrentVideoEnabled(true))
            assertFalse(enhancement.state.value.active)
        }
    }

    // ---- Activation gate: pure rows (no player, no native call) ----

    private fun scenarioCapability(id: String): NvidiaCapability {
        val scenario = NvidiaGoldenVectors.objects("scenarios").first { NvidiaGoldenVectors.string(it, "id") == id }
        return classifyNvidiaCapability(NvidiaGoldenVectors.scenarioInput(scenario))
    }

    private fun gateFor(capability: NvidiaCapability, enabled: Boolean = true, sourceLoaded: Boolean = true,
        preferences: DesktopNvidiaVideoPreferences = DesktopNvidiaVideoPreferences(enabled = true),
        environment: DesktopNvidiaEnvironment = DesktopNvidiaEnvironment(), observed: DesktopNvidiaObservedGpu? = null,
        componentAvailable: Boolean = false): DesktopNvidiaGate =
        resolveDesktopNvidiaGate(capability, environment, observed, enabled, sourceLoaded, preferences, componentAvailable)

    @Test fun aNeverEvaluatedCapabilityKeepsEveryLegacyBehaviourWhateverTheSwitchSays() {
        for (enabled in listOf(true, false)) {
            val gate = gateFor(NvidiaCapability.NotEvaluated, enabled = enabled)
            assertTrue(gate.legacy)
            assertEquals(DesktopVideoEnhancementActivation.LEGACY, gate.kind)
            assertTrue(gate.capabilityUsable)
            assertFalse(gate.bypassesBeforeSourceChecks)
            assertNull(gate.gateLine())
            assertEquals("legacy text", gate.statusText("legacy text", DesktopNvidiaStatusKind.PENDING))
        }
    }

    @Test fun noNvidiaAndUnsupportedAdaptersBypassBeforeAnySourceCheckAndAreNotUsable() {
        val none = gateFor(scenarioCapability("amd-only"))
        assertEquals(DesktopVideoEnhancementActivation.NO_NVIDIA, none.kind)
        assertTrue(none.bypassesBeforeSourceChecks)
        assertFalse(none.capabilityUsable)
        assertEquals(DesktopNvidiaStatusText.NO_NVIDIA, none.gateLine())
        val virtualOnly = gateFor(scenarioCapability("only-virtual"))
        assertEquals(DesktopVideoEnhancementActivation.UNSUPPORTED, virtualOnly.kind)
        assertTrue(virtualOnly.bypassesBeforeSourceChecks)
        assertEquals(DesktopNvidiaStatusText.NOT_PHYSICAL, virtualOnly.gateLine())
        val twin = gateFor(scenarioCapability("twin-first-pin-unsafe"))
        assertEquals(DesktopNvidiaStatusText.PIN_UNSAFE, twin.gateLine())
        val preRtx = gateFor(scenarioCapability("gtx-only"))
        assertEquals(NvidiaUnsupportedReason.PRE_RTX, assertIs<DesktopNvidiaActivationState.Unsupported>(preRtx.state).reason)
        assertFalse(preRtx.capabilityUsable)
        // The switch is handled before the gate: a switched-off Absent machine is simply off, with no GPU named.
        val off = gateFor(scenarioCapability("amd-only"), enabled = false)
        assertEquals(DesktopVideoEnhancementActivation.OFF, off.kind)
        assertFalse(off.bypassesBeforeSourceChecks)
        assertEquals(DesktopNvidiaStatusText.OFF_GPU_UNKNOWN, off.lineFor("unused", DesktopNvidiaStatusKind.OFF))
    }

    @Test fun aProbeFailureNeverMeansNoGpuAndNeedsAVerifiedPhysicalLuid() {
        val failed = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)
        assertEquals(DesktopVideoEnhancementActivation.PROBING, gateFor(NvidiaCapability.Probing).kind)
        assertTrue(gateFor(NvidiaCapability.Probing).bypassesBeforeSourceChecks)
        assertEquals(DesktopVideoEnhancementActivation.UNIDENTIFIED, gateFor(failed).kind)
        val unverified = DesktopNvidiaObservedGpu("NVIDIA GeForce RTX 5080", 0x2C02, "000000000000e782", false)
        assertEquals(DesktopVideoEnhancementActivation.UNIDENTIFIED, gateFor(failed, observed = unverified).kind)
        assertTrue(gateFor(failed, observed = unverified).bypassesBeforeSourceChecks)
        val verified = unverified.copy(verifiedPhysical = true)
        val allowed = gateFor(failed, observed = verified)
        assertEquals(DesktopVideoEnhancementActivation.ALLOWED, allowed.kind)
        assertTrue(allowed.capabilityUsable)
        assertFalse(allowed.bypassesBeforeSourceChecks)
        assertTrue(assertNotNull(allowed.plan).tier.ordinal >= NvidiaTier.HIGH.ordinal)
        assertEquals("RTX 5080", allowed.gpuSummary)
        // Before a file loads the same verdict is standby: no request yet.
        assertEquals(DesktopVideoEnhancementActivation.STANDBY, gateFor(failed, observed = verified, sourceLoaded = false).kind)
    }

    @Test fun thisMachineIsStandbyBeforeALoadAllowedAfterAndOffWhenSwitchedOff() {
        val capable = scenarioCapability("this-pc")
        val standby = gateFor(capable, sourceLoaded = false)
        assertEquals(DesktopVideoEnhancementActivation.STANDBY, standby.kind)
        assertFalse(standby.bypassesBeforeSourceChecks)
        assertTrue(standby.capabilityUsable)
        assertEquals("RTX 5080", standby.gpuSummary)
        assertEquals(NvidiaTier.ULTRA, assertNotNull(standby.plan).tier)
        assertEquals(DesktopVideoEnhancementActivation.ALLOWED, gateFor(capable).kind)
        val off = gateFor(capable, enabled = false)
        assertEquals(DesktopVideoEnhancementActivation.OFF, off.kind)
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + DesktopNvidiaStatusText.OFF_GPU_KNOWN_TAIL,
            off.lineFor("unused", DesktopNvidiaStatusKind.OFF))
        // The assumed route follows the component; the tier word stays off with the shipped switch.
        assertEquals(NvidiaVideoBackend.DRIVER, gateFor(capable).route)
        val withComponent = gateFor(capable, componentAvailable = true)
        assertEquals(NvidiaVideoBackend.VEYRA_CORE, withComponent.route)
        assertNull(withComponent.tierWordFor(NvidiaVideoBackend.VEYRA_CORE))
    }

    @Test fun theLiveLinesNameTheGpuAndTheDriverRouteNeverClaimsAConfirmedResult() {
        val gate = gateFor(scenarioCapability("this-pc"), componentAvailable = true)
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + DesktopNvidiaStatusText.PENDING_TAIL,
            gate.lineFor("unused", DesktopNvidiaStatusKind.PENDING, NvidiaVideoBackend.VEYRA_CORE))
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + DesktopNvidiaStatusText.DRIVER_NOT_ACTIVE_TAIL,
            gate.lineFor("unused", DesktopNvidiaStatusKind.PENDING, NvidiaVideoBackend.DRIVER))
        val driverActive = gate.lineFor("unused", DesktopNvidiaStatusKind.ACTIVE, NvidiaVideoBackend.DRIVER)
        assertTrue(driverActive.contains("无法确认"))
        // A situation without a kind of its own keeps its reason text under the GPU name.
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + "reason text", gate.lineFor("reason text", null))
        // Legacy keeps its own text, so the gate adds nothing when detection is off.
        assertEquals("reason text", gateFor(NvidiaCapability.NotEvaluated).statusText("reason text", null))
    }

    @Test fun pausesFollowTheEnvironmentAndBypassBeforeAnySourceCheck() {
        val capable = scenarioCapability("this-pc")
        val game = gateFor(capable, environment = DesktopNvidiaEnvironment(gameLike = true))
        assertEquals(DesktopVideoEnhancementActivation.PAUSED_GAME, game.kind)
        assertTrue(game.bypassesBeforeSourceChecks)
        assertEquals(DesktopNvidiaStatusText.PAUSED_GAME, game.gateLine())
        val remote = gateFor(capable, environment = DesktopNvidiaEnvironment(remoteOrVirtualOutput = true))
        assertEquals(DesktopVideoEnhancementActivation.PAUSED_REMOTE, remote.kind)
        assertEquals(DesktopNvidiaStatusText.PAUSED_REMOTE, remote.gateLine())
        // Battery only matters for a mobile GPU: the desktop 5080 is never paused for it.
        assertEquals(DesktopVideoEnhancementActivation.ALLOWED,
            gateFor(capable, environment = DesktopNvidiaEnvironment(onBattery = true)).kind)
        // The public mirror names the internal states one to one (the other states are covered by the rows above).
        val allKinds = listOf<DesktopNvidiaActivationState>(DesktopNvidiaActivationState.Off(), DesktopNvidiaActivationState.Legacy,
            DesktopNvidiaActivationState.Probing, DesktopNvidiaActivationState.NoNvidia,
            DesktopNvidiaActivationState.Unidentified).map { it.publicKind().name }
        assertEquals(listOf("OFF", "LEGACY", "PROBING", "NO_NVIDIA", "UNIDENTIFIED"), allKinds)
    }

    @Test fun aDisplayOnAnotherAdapterLowersTheGateTierAndNamesItInTheLine() {
        val capable = scenarioCapability("this-pc")
        val preferences = DesktopNvidiaVideoPreferences(enabled = true)
        val none = DesktopNvidiaEnvironment()
        val sameAdapter = resolveDesktopNvidiaGate(capable, none, null, true, true, preferences, false, "000000000000e782")
        assertEquals(NvidiaTier.ULTRA, assertNotNull(sameAdapter.plan).tier)
        assertFalse(assertNotNull(sameAdapter.plan).crossAdapter)
        val unknownAdapter = resolveDesktopNvidiaGate(capable, none, null, true, true, preferences, false, null)
        assertEquals(NvidiaTier.ULTRA, assertNotNull(unknownAdapter.plan).tier)
        val otherAdapter = resolveDesktopNvidiaGate(capable, none, null, true, true, preferences, false, "00000000000109a0")
        assertEquals(NvidiaTier.HIGH, assertNotNull(otherAdapter.plan).tier)
        assertTrue(assertNotNull(otherAdapter.plan).crossAdapter)
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + DesktopNvidiaStatusText.STANDBY_TAIL +
            DesktopNvidiaStatusText.CROSS_ADAPTER_SUFFIX, otherAdapter.lineFor("unused", DesktopNvidiaStatusKind.STANDBY))
        // A pause keeps the lower plan, and the pause line itself needs no GPU name.
        val paused = resolveDesktopNvidiaGate(capable, DesktopNvidiaEnvironment(gameLike = true), null, true, true,
            preferences, false, "00000000000109a0")
        assertEquals(DesktopVideoEnhancementActivation.PAUSED_GAME, paused.kind)
        assertEquals(NvidiaTier.HIGH, assertNotNull(paused.plan).tier)
        assertEquals(DesktopNvidiaStatusText.PAUSED_GAME, paused.gateLine())
    }

    @Test fun anOldDriverOrALightTierDropsHdrFirstAndSaysSo() {
        val auto = DesktopNvidiaVideoPreferences(enabled = true, hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        val hdrOnly = auto.copy(srEnabled = false)
        val oldDriver = gateFor(scenarioCapability("this-pc-old-hdr-driver-ada"), preferences = auto)
        assertEquals(DesktopNvidiaStatusText.HDR_DRIVER_OLD, oldDriver.notice)
        assertEquals(DesktopNvidiaVideoHdrMode.OFF, nvidiaEffectiveHdrMode(auto, oldDriver.plan))
        assertEquals(DesktopNvidiaVideoHdrMode.OFF, nvidiaEffectiveHdrMode(hdrOnly, oldDriver.plan))
        assertNull(gateFor(scenarioCapability("this-pc-old-hdr-driver-ada"), preferences = hdrOnly).notice)
        assertNull(gateFor(scenarioCapability("this-pc-old-hdr-driver-ada"),
            preferences = DesktopNvidiaVideoPreferences(enabled = true)).notice)
        val light = gateFor(scenarioCapability("two-physical-output-owner"), preferences = auto)
        assertEquals(DesktopNvidiaStatusText.TIER_DENIES_HDR, light.notice)
        assertEquals(DesktopNvidiaVideoHdrMode.OFF, nvidiaEffectiveHdrMode(auto, light.plan))
        // HDR on its own is allowed on every tier, so the light tier keeps it.
        assertEquals(DesktopNvidiaVideoHdrMode.AUTO, nvidiaEffectiveHdrMode(hdrOnly, light.plan))
        val ultra = gateFor(scenarioCapability("this-pc"), preferences = auto)
        assertNull(ultra.notice)
        assertEquals(DesktopNvidiaVideoHdrMode.AUTO, nvidiaEffectiveHdrMode(auto, ultra.plan))
        assertEquals(DesktopNvidiaVideoHdrMode.AUTO, nvidiaEffectiveHdrMode(auto, null))
    }

    // ---- The available fix ----

    @Test fun capabilityIsAndedIntoAvailableOnBothRoutes() {
        val before = DesktopVideoEnhancementState(requested = true)
        val core = NvidiaVideoState(configurationVersion = 1, veyraAvailable = true,
            backend = NvidiaVideoBackend.VEYRA_CORE, gpuVendorId = 0x10de, currentGpuContext = "d3d11")
        assertTrue(before.withNvidiaObservation(core).available)
        assertFalse(before.withNvidiaObservation(core, capabilityAvailable = false).available)
        val driver = NvidiaVideoState(configurationVersion = 1, backend = NvidiaVideoBackend.DRIVER, gpuVendorId = 0x10de)
        assertTrue(before.withNvidiaObservation(driver).available)
        assertFalse(before.withNvidiaObservation(driver, capabilityAvailable = false).available)
        // A confirmed unavailable reason keeps meaning unavailable on the driver route, as before.
        assertFalse(before.withNvidiaObservation(driver.copy(unavailableReason = "reason")).available)
    }

    // ---- Request options ----

    private val upscaleAndHdr = DesktopNvidiaVideoDecision(DesktopNvidiaVideoDecisionKind.UPSCALE_AND_HDR, scale = 2.0, hdr = true)

    @Test fun optionsTakeTheLowestQualityAndOnlyEverRemoveHdr() {
        val ultra = buildNvidiaPlan(assertIs<NvidiaCapability.Capable>(scenarioCapability("this-pc")))
        val light = buildNvidiaPlan(assertIs<NvidiaCapability.Capable>(scenarioCapability("two-physical-output-owner")))
        val preferences = DesktopNvidiaVideoPreferences(enabled = true, hdrMode = DesktopNvidiaVideoHdrMode.AUTO)
        val core = NvidiaVideoBackend.VEYRA_CORE
        // No plan and the default guard: exactly the request the preferences alone describe.
        val plain = preferences.optionsFor(upscaleAndHdr, core)
        assertEquals(plain, preferences.optionsFor(upscaleAndHdr, core, null, NvidiaGuardLimits.None))
        assertEquals(4, plain.qualityLevel)
        assertTrue(plain.hdr)
        val onUltra = preferences.optionsFor(upscaleAndHdr, core, ultra, NvidiaGuardLimits.None)
        assertEquals(4, onUltra.qualityLevel)
        assertTrue(onUltra.hdr)
        assertEquals(3, preferences.optionsFor(upscaleAndHdr, core, ultra, NvidiaGuardLimits(qualityCap = 3)).qualityLevel)
        assertFalse(preferences.optionsFor(upscaleAndHdr, core, ultra, NvidiaGuardLimits(hdrAllowed = false)).hdr)
        val onLight = preferences.optionsFor(upscaleAndHdr, core, light, NvidiaGuardLimits.None)
        assertEquals(2, onLight.qualityLevel)
        assertFalse(onLight.hdr)
        // The user's ceiling still wins when it is lower than the tier.
        val standard = preferences.copy(quality = DesktopNvidiaVideoQuality.STANDARD)
        assertEquals(2, standard.optionsFor(upscaleAndHdr, core, ultra).qualityLevel)
        // The driver route cannot apply quality or strength: its request never changes.
        val driver = preferences.optionsFor(upscaleAndHdr, NvidiaVideoBackend.DRIVER, light, NvidiaGuardLimits(qualityCap = 1))
        assertEquals(4, driver.qualityLevel)
        assertEquals(100, driver.intensityPercent)
        // Every combination stays a valid request.
        for (plan in listOf(null, ultra, light)) for (cap in 1..4) {
            preferences.optionsFor(upscaleAndHdr, core, plan, NvidiaGuardLimits(qualityCap = cap)).requireValid()
        }
    }

    // ---- Real player rows ----

    @Test fun aMachineWithoutNvidiaMakesNoRequestAndIsNotAvailableEvenWithAVideoLoaded() = runBlocking<Unit> {
        val monitor = DesktopNvidiaCapabilityMonitor(SessionFixtureGpuProbe(amdOnlyScenario()))
        monitor.refreshForSession()
        assertEquals(NvidiaCapability.Absent, monitor.capability.value)
        MpvPlayer(gpuCapability = monitor).use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(false),
                { CompletableDeferred(Unit) }).use { enhancement ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/no-nvidia-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == version &&
                    enhancement.state.value.activation == DesktopVideoEnhancementActivation.NO_NVIDIA }
                val shown = enhancement.state.value
                assertFalse(shown.available)
                assertFalse(shown.pending)
                assertFalse(shown.active)
                assertTrue(shown.requested)
                assertEquals(DesktopNvidiaStatusText.NO_NVIDIA, shown.statusText)
                assertEquals(DesktopNvidiaStatusText.NO_NVIDIA, shown.unavailableReason)
            }
        }
    }

    @Test fun aSwitchedOffSessionOnAMachineWithoutNvidiaIsOffAndNotUnavailable() = runBlocking<Unit> {
        val monitor = DesktopNvidiaCapabilityMonitor(SessionFixtureGpuProbe(amdOnlyScenario()))
        monitor.refreshForSession()
        MpvPlayer(gpuCapability = monitor).use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(false), MutableStateFlow(true), MutableStateFlow(false),
                { CompletableDeferred(Unit) }).use { enhancement ->
                awaitState { enhancement.state.value.activation == DesktopVideoEnhancementActivation.OFF }
                val shown = enhancement.state.value
                assertFalse(shown.requested)
                assertEquals(DesktopNvidiaStatusText.OFF_GPU_UNKNOWN, shown.statusText)
                assertNull(shown.unavailableReason)
            }
        }
    }

    @Test fun aNeutralMonitorKeepsTheLegacyGateAndTheLegacyFields() = runBlocking<Unit> {
        MpvPlayer().use { player ->
            DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(false),
                { CompletableDeferred(Unit) }).use { enhancement ->
                val version = player.loadVersioned(PlaybackSource("file:///C:/legacy-gate-fixture.avi"))
                awaitState { enhancement.state.value.sourceVersion == version }
                val shown = enhancement.state.value
                assertEquals(DesktopVideoEnhancementActivation.LEGACY, shown.activation)
                assertNull(shown.gpuSummary)
                assertNull(shown.notice)
                assertNull(shown.tierWord)
                assertNull(shown.requestedQualityLevel)
                assertFalse(shown.steppedDown)
                assertFalse(shown.qualityLowered)
                assertFalse(shown.proofDelayed)
            }
        }
    }

    @Test fun redetectGpuAsksTheMonitorForAFreshProbeAndIsInertAfterClose() = runBlocking<Unit> {
        val probe = SessionFixtureGpuProbe(amdOnlyScenario())
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        monitor.refreshForSession()
        assertEquals(1, probe.calls.get())
        MpvPlayer(gpuCapability = monitor).use { player ->
            val enhancement = DesktopVideoEnhancementSession(player, MutableStateFlow(true), MutableStateFlow(true),
                MutableStateFlow(false), { CompletableDeferred(Unit) })
            enhancement.redetectGpu()
            awaitState { probe.calls.get() >= 2 }
            enhancement.close()
            val afterClose = probe.calls.get()
            enhancement.redetectGpu()
            delay(200)
            assertEquals(afterClose, probe.calls.get())
        }
    }


    // ---- Adaptive guard: the memory inputs of the gate and the wiring of the health store ----

    private fun gateWithMemory(capability: NvidiaCapability, memory: DesktopNvidiaGateMemory,
        environment: DesktopNvidiaEnvironment = DesktopNvidiaEnvironment(), sourceLoaded: Boolean = true): DesktopNvidiaGate =
        resolveDesktopNvidiaGate(capability, environment, null, true, sourceLoaded, DesktopNvidiaVideoPreferences(enabled = true),
            false, null, memory)

    @Test fun theMemoryInputsSelectTheFailedStatesBeforeAnySourceCheckAndNameTheGpu() {
        val capable = scenarioCapability("this-pc")
        val remembered = gateWithMemory(capable, DesktopNvidiaGateMemory(failureRemembered = true))
        assertEquals(DesktopVideoEnhancementActivation.FAILED_REMEMBERED, remembered.kind)
        assertTrue(remembered.bypassesBeforeSourceChecks)
        assertTrue(remembered.capabilityUsable, "the GPU is fine; the combination failed before")
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + DesktopNvidiaStatusText.FAILED_REMEMBERED_TAIL,
            remembered.gateLine())
        val extended = gateWithMemory(capable, DesktopNvidiaGateMemory(extendedRangeFailed = true))
        assertEquals(DesktopVideoEnhancementActivation.FAILED_EXTENDED_RANGE, extended.kind)
        assertTrue(extended.bypassesBeforeSourceChecks)
        assertEquals(DesktopNvidiaStatusText.FAILED_EXTENDED_RANGE, extended.gateLine())
        val gaveUp = gateWithMemory(capable, DesktopNvidiaGateMemory(guardGaveUp = true))
        assertEquals(DesktopVideoEnhancementActivation.FAILED_THIS_VIDEO, gaveUp.kind)
        assertTrue(gaveUp.bypassesBeforeSourceChecks)
        assertEquals("RTX 5080" + DesktopNvidiaStatusText.SEPARATOR + DesktopNvidiaStatusText.FAILED_THIS_VIDEO_TAIL,
            gaveUp.gateLine())
        // The plan stays known, so the controls and the compact label can still name the GPU.
        for (gate in listOf(remembered, extended, gaveUp)) assertEquals("RTX 5080", gate.gpuSummary)
    }

    @Test fun rememberedBeatsExtendedRangeBeatsAGivenUpVideoAndAPauseBeatsAllOfThem() {
        val capable = scenarioCapability("this-pc")
        val all = DesktopNvidiaGateMemory(failureRemembered = true, extendedRangeFailed = true, guardGaveUp = true)
        assertEquals(DesktopVideoEnhancementActivation.FAILED_REMEMBERED, gateWithMemory(capable, all).kind)
        assertEquals(DesktopVideoEnhancementActivation.FAILED_EXTENDED_RANGE,
            gateWithMemory(capable, all.copy(failureRemembered = false)).kind)
        assertEquals(DesktopVideoEnhancementActivation.FAILED_THIS_VIDEO,
            gateWithMemory(capable, all.copy(failureRemembered = false, extendedRangeFailed = false)).kind)
        // A pause outranks every failed state, and clears by itself when the game is gone.
        assertEquals(DesktopVideoEnhancementActivation.PAUSED_GAME,
            gateWithMemory(capable, all, environment = DesktopNvidiaEnvironment(gameLike = true)).kind)
        assertEquals(DesktopVideoEnhancementActivation.PAUSED_REMOTE,
            gateWithMemory(capable, all, environment = DesktopNvidiaEnvironment(remoteOrVirtualOutput = true)).kind)
        // A remembered failure applies before a file is loaded too: no standby, no request.
        assertEquals(DesktopVideoEnhancementActivation.FAILED_REMEMBERED,
            gateWithMemory(capable, DesktopNvidiaGateMemory(failureRemembered = true), sourceLoaded = false).kind)
    }

    @Test fun noMemoryChangesNothingAndEveryEarlierStateOutranksTheMemory() {
        val capable = scenarioCapability("this-pc")
        assertEquals(DesktopNvidiaGateMemory(), DesktopNvidiaGateMemory.None)
        assertEquals(DesktopVideoEnhancementActivation.ALLOWED, gateWithMemory(capable, DesktopNvidiaGateMemory.None).kind)
        assertEquals(DesktopVideoEnhancementActivation.STANDBY,
            gateWithMemory(capable, DesktopNvidiaGateMemory.None, sourceLoaded = false).kind)
        val all = DesktopNvidiaGateMemory(failureRemembered = true, extendedRangeFailed = true, guardGaveUp = true)
        // Detection off stays legacy, a missing GPU stays a missing GPU, and a failed probe stays unidentified.
        assertEquals(DesktopVideoEnhancementActivation.LEGACY, gateWithMemory(NvidiaCapability.NotEvaluated, all).kind)
        assertEquals(DesktopVideoEnhancementActivation.PROBING, gateWithMemory(NvidiaCapability.Probing, all).kind)
        assertEquals(DesktopVideoEnhancementActivation.NO_NVIDIA, gateWithMemory(scenarioCapability("amd-only"), all).kind)
        assertEquals(DesktopVideoEnhancementActivation.UNSUPPORTED, gateWithMemory(scenarioCapability("only-virtual"), all).kind)
        assertEquals(DesktopVideoEnhancementActivation.UNIDENTIFIED,
            gateWithMemory(NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED), all).kind)
    }

    @Test fun theOverloadStopHasItsOwnWordingAndNeverNamesAFailure() {
        val gate = gateWithMemory(scenarioCapability("this-pc"), DesktopNvidiaGateMemory(guardGaveUp = true))
        assertEquals(DesktopNvidiaStatusText.GUARD_BYPASS,
            gate.lineFor(DesktopNvidiaStatusText.GUARD_BYPASS, DesktopNvidiaStatusKind.GUARD_BYPASS))
        assertTrue(DesktopNvidiaStatusText.GUARD_BYPASS != gate.gateLine())
    }

    @Test fun everyMonitorCarriesAnInMemoryHealthStoreByDefaultSoATestNeverTouchesTheDisk() {
        val key = "00000000000000a1"
        val neutral = DesktopNvidiaCapabilityMonitor.neutral()
        neutral.healthStore.recordRemembered(key)
        assertTrue(neutral.healthStore.view(key).remembered)
        assertTrue(neutral.healthStore.flushNow(), "an in-memory store has nothing to write")
        assertEquals(NvidiaHealthView.None, DesktopNvidiaCapabilityMonitor.neutral().healthStore.view(key),
            "every monitor has its own memory")
        val shared = DesktopNvidiaHealthStore.inMemory()
        assertSame(shared, DesktopNvidiaCapabilityMonitor(probe = null, healthStore = shared).healthStore)
    }

    @Test fun aFreshPlayerReportsAnEmptyPlaybackHealthUntilAFileIsLoadedAndPolled() {
        MpvPlayer().use { player ->
            assertEquals(NvidiaPlaybackHealthSample(), player.playbackHealth.value)
            assertEquals(0L, player.playbackHealth.value.sourceVersion)
            assertNull(player.playbackHealth.value.frameDrops)
            assertNull(player.playbackHealth.value.delayedFrames)
        }
    }

    private fun amdOnlyScenario(): JsonObject =
        NvidiaGoldenVectors.objects("scenarios").first { NvidiaGoldenVectors.string(it, "id") == "amd-only" }

    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(10)
    }
}
