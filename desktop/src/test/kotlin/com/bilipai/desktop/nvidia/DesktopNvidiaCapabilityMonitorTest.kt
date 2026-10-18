package com.bilipai.desktop.nvidia

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixture probe over one shared golden scenario. No native call, no GPU, no JNA. */
private class ScenarioProbe(
    private val scenario: JsonObject,
    private val failWith: Throwable? = null,
    private val gate: CountDownLatch? = null,
) : DesktopNvidiaGpuProbe {
    val probeCalls = AtomicInteger()
    val verdictCalls = AtomicInteger()
    @Volatile var probeThreadName: String? = null
    @Volatile var probeThreadDaemon: Boolean? = null

    override fun probe(): NvidiaProbeInput {
        probeCalls.incrementAndGet()
        probeThreadName = Thread.currentThread().name
        probeThreadDaemon = Thread.currentThread().isDaemon
        gate?.await()
        failWith?.let { throw it }
        return NvidiaGoldenVectors.scenarioInput(scenario)
    }

    override fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict {
        verdictCalls.incrementAndGet()
        val adapter = NvidiaGoldenVectors.objectsOf(scenario.getValue("adapters")).firstOrNull {
            NvidiaGoldenVectors.int(it, "luidHigh") == luidHigh &&
                NvidiaGoldenVectors.hex(NvidiaGoldenVectors.string(it, "luidLow")) == luidLow
        } ?: return NvidiaPhysicalVerdict.UNKNOWN
        return classifyNvidiaAdapterPath(NvidiaGoldenVectors.stringOrNull(adapter, "pnpPath"), dxgiDeviceId)
    }
}

private fun scenario(id: String): JsonObject =
    NvidiaGoldenVectors.objects("scenarios").first { NvidiaGoldenVectors.string(it, "id") == id }

private fun awaitCapability(monitor: DesktopNvidiaCapabilityMonitor, accept: (NvidiaCapability) -> Boolean): NvidiaCapability =
    runBlocking { withTimeout(5_000L) { monitor.capability.first { accept(it) } } }

private fun eventually(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for: $description")
        Thread.sleep(10L)
    }
}

class DesktopNvidiaCapabilityMonitorTest {
    private val failed = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)
    private val physicalLuid = "000000000000e782"
    private val twinLuid = "00000000000b95ad"
    private val rtx5080 = "NVIDIA GeForce RTX 5080"

    // ---- Neutral and kill-switch behaviour: exactly today's player ----

    @Test fun neutralMonitorNeverProbesAndKeepsTodaysBehaviour() {
        val monitor = DesktopNvidiaCapabilityMonitor.neutral()
        assertFalse(monitor.autodetectEnabled)
        assertEquals(NvidiaCapability.NotEvaluated, monitor.capability.value)
        assertEquals(NVIDIA_LEGACY_ADAPTER_PIN, monitor.pinFor())
        assertNull(monitor.expectation())
        monitor.warmUp(); monitor.refreshForSession(); monitor.invalidate(); monitor.redetect()
        assertEquals(NvidiaCapability.NotEvaluated, monitor.capability.value)
        assertFalse(monitor.validateObservedLuid(physicalLuid, 0x2c02, rtx5080))
        assertEquals(DesktopNvidiaEnvironment(), monitor.environment.value)
        assertNull(monitor.observed.value)
    }

    @Test fun everyNeutralMonitorIsIndependent() {
        val first = DesktopNvidiaCapabilityMonitor.neutral()
        val second = DesktopNvidiaCapabilityMonitor.neutral()
        assertTrue(first !== second)
    }

    @Test fun onlyTheExactValueOffTurnsDetectionOff() {
        assertTrue(DesktopNvidiaCapabilityMonitor.autodetectRequested(null))
        assertFalse(DesktopNvidiaCapabilityMonitor.autodetectRequested("off"))
        assertFalse(DesktopNvidiaCapabilityMonitor.autodetectRequested("OFF"))
        assertFalse(DesktopNvidiaCapabilityMonitor.autodetectRequested("  Off "))
        for (other in listOf("", "on", "true", "false", "0", "no", "offline", "of")) {
            assertTrue(DesktopNvidiaCapabilityMonitor.autodetectRequested(other), other)
        }
        assertEquals("bilipai.gpu.autodetect", DesktopNvidiaCapabilityMonitor.KILL_SWITCH_PROPERTY)
    }

    // ---- Probe, cache, expectation, pin ----

    @Test fun beforeTheFirstResultTheMonitorIsProbingWithTheLegacyPinAndAVerifyObservedExpectation() {
        val monitor = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("this-pc")))
        assertTrue(monitor.autodetectEnabled)
        assertEquals(NvidiaCapability.Probing, monitor.capability.value)
        assertEquals(NVIDIA_LEGACY_ADAPTER_PIN, monitor.pinFor())
        assertEquals(DesktopNvidiaLuidExpectation.VerifyObserved, monitor.expectation())
    }

    @Test fun thisMachineResolvesToTheExactPinAndTheExactPhysicalLuid() {
        val probe = ScenarioProbe(scenario("this-pc"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        monitor.refreshForSession()
        val capable = assertIs<NvidiaCapability.Capable>(monitor.capability.value)
        assertEquals(NvidiaTier.ULTRA, capable.tier)
        assertEquals(physicalLuid, capable.gpu.luidHex)
        assertEquals(rtx5080, monitor.pinFor())
        assertEquals(DesktopNvidiaLuidExpectation.Exact(physicalLuid, 0x2C02), monitor.expectation())
        assertEquals("BiliPai-gpu-probe", probe.probeThreadName)
        assertEquals(true, probe.probeThreadDaemon)
    }

    @Test fun aResultYoungerThanThirtySecondsIsReusedAndAnOlderOneIsProbedAgain() {
        var now = 1_000L
        val probe = ScenarioProbe(scenario("this-pc"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe, nowMillis = { now })
        monitor.refreshForSession()
        assertEquals(1, probe.probeCalls.get())
        now += 29_999L
        monitor.refreshForSession()
        assertEquals(1, probe.probeCalls.get())
        now += 2L
        monitor.refreshForSession()
        assertEquals(2, probe.probeCalls.get())
        monitor.refreshForSession()
        assertEquals(2, probe.probeCalls.get())
        now -= 10_000L // a clock that went backwards is never treated as fresh
        monitor.refreshForSession()
        assertEquals(3, probe.probeCalls.get())
    }

    @Test fun invalidateMakesTheNextSessionStartProbeWithoutStartingAThread() {
        val probe = ScenarioProbe(scenario("this-pc"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        monitor.refreshForSession()
        monitor.invalidate()
        assertEquals(1, probe.probeCalls.get())
        assertIs<NvidiaCapability.Capable>(monitor.capability.value)
        monitor.refreshForSession()
        assertEquals(2, probe.probeCalls.get())
        monitor.refreshForSession()
        assertEquals(2, probe.probeCalls.get())
    }

    @Test fun warmUpProbesOnceOffTheCallerThreadAndIsSafeToRepeat() {
        val probe = ScenarioProbe(scenario("this-pc"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        monitor.warmUp(); monitor.warmUp()
        val verdict = awaitCapability(monitor) { it !is NvidiaCapability.Probing }
        assertIs<NvidiaCapability.Capable>(verdict)
        assertEquals(1, probe.probeCalls.get())
        assertEquals("BiliPai-gpu-probe", probe.probeThreadName)
        monitor.warmUp()
        monitor.refreshForSession()
        assertEquals(1, probe.probeCalls.get())
    }

    @Test fun redetectProbesAgainInTheBackgroundAndKeepsTheOldVerdictUntilTheNewOneArrives() {
        val probe = ScenarioProbe(scenario("this-pc"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        monitor.refreshForSession()
        monitor.redetect()
        assertIs<NvidiaCapability.Capable>(monitor.capability.value, "no Probing flicker for a manual re-detection")
        eventually("the second probe") { probe.probeCalls.get() >= 2 }
        monitor.refreshForSession()
        assertEquals(2, probe.probeCalls.get())
        assertIs<NvidiaCapability.Capable>(monitor.capability.value)
    }

    @Test fun aThrowingProbeIsUnknownProbeFailedNeverNoGpu() {
        val monitor = DesktopNvidiaCapabilityMonitor(
            ScenarioProbe(scenario("this-pc"), failWith = IllegalStateException("fixture")))
        monitor.refreshForSession()
        assertEquals(failed, monitor.capability.value)
        assertEquals(NVIDIA_LEGACY_ADAPTER_PIN, monitor.pinFor())
        assertEquals(DesktopNvidiaLuidExpectation.VerifyObserved, monitor.expectation())
        val error = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("this-pc"), failWith = LinkageError("fixture")))
        error.refreshForSession()
        assertEquals(failed, error.capability.value)
    }

    @Test fun anIncompleteInventoryWithTheTwinPresentIsUnknownAndNeverAcceptsTheTwin() {
        val probe = ScenarioProbe(scenario("dxgi-failed-twin-present"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        monitor.refreshForSession()
        assertEquals(failed, monitor.capability.value)
        assertEquals(NVIDIA_LEGACY_ADAPTER_PIN, monitor.pinFor())
        assertEquals(DesktopNvidiaLuidExpectation.VerifyObserved, monitor.expectation())
        assertFalse(monitor.validateObservedLuid(twinLuid, 0x2c02, rtx5080), "the twin has identical vendor, device, name and driver")
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02, rtx5080))
    }

    @Test fun everyScenarioVerdictMapsToTheRightPinAndExpectation() {
        val noPin = listOf("twin-first-pin-unsafe", "only-virtual", "amd-only", "software-only", "empty-inventory")
        for (id in noPin) {
            val monitor = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario(id)))
            monitor.refreshForSession()
            assertNull(monitor.pinFor(), id)
            assertNull(monitor.expectation(), id)
        }
        val gtx = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("gtx-only")))
        gtx.refreshForSession()
        assertIs<NvidiaCapability.Unsupported>(gtx.capability.value)
        assertNotNull(gtx.pinFor(), "decode steering is kept for any physical NVIDIA GPU")
        assertNull(gtx.expectation())
        val legacy = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("non-ascii-description-legacy-pin")))
        legacy.refreshForSession()
        assertEquals(NVIDIA_LEGACY_ADAPTER_PIN, legacy.pinFor())
        assertIs<DesktopNvidiaLuidExpectation.Exact>(legacy.expectation())
        val noLuidDisplay = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("two-physical-no-output-class-wins")))
        noLuidDisplay.refreshForSession()
        val exact = assertIs<DesktopNvidiaLuidExpectation.Exact>(noLuidDisplay.expectation())
        assertEquals(exact.luidHex, assertIs<NvidiaCapability.Capable>(noLuidDisplay.capability.value).gpu.luidHex)
    }

    // ---- Observed LUID validation ----

    @Test fun anObservedLuidIsValidatedAgainstThePnpPathInEitherHalfOrder() {
        val probe = ScenarioProbe(scenario("dxgi-failed-twin-present"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02, rtx5080, 61742))
        assertEquals(DesktopNvidiaObservedGpu(rtx5080, 0x2c02, physicalLuid, true, 61742), monitor.observed.value)
        assertTrue(monitor.validateObservedLuid("0000e78200000000", 0x2c02), "low half printed first")
        assertTrue(monitor.validateObservedLuid("0x000000000000E782", 0x2c02), "prefix and case are ignored")
        assertFalse(monitor.validateObservedLuid(twinLuid, 0x2c02, rtx5080))
        assertFalse(monitor.validateObservedLuid("0000000000012345", 0x2c02), "no adapter owns this luid")
    }

    @Test fun anObservedLuidIsRefusedWhenAnyInputIsMissingOrWrong() {
        val monitor = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("this-pc")))
        assertFalse(monitor.validateObservedLuid(physicalLuid, null), "no device id to compare with the path")
        assertFalse(monitor.validateObservedLuid("not-a-luid", 0x2c02))
        assertFalse(monitor.validateObservedLuid("", 0x2c02))
        assertFalse(monitor.validateObservedLuid("00000000000e782", 0x2c02), "fifteen digits")
        assertFalse(monitor.validateObservedLuid(physicalLuid, 0x2c02, "Microsoft Basic Render Driver"))
        assertFalse(monitor.validateObservedLuid(physicalLuid, 0x2c02, "NVIDIA GeForce RTX 5080 Virtual Display"))
        assertFalse(monitor.validateObservedLuid(physicalLuid, 0x2c02, "GameViewer Indirect Display Adapter"))
        assertNull(monitor.observed.value, "refusals before the physical check publish nothing")
        assertFalse(monitor.validateObservedLuid(physicalLuid, 0x2c03), "device id differs from the path")
        assertEquals(false, monitor.observed.value?.verifiedPhysical, "a failed physical check is published as unverified")
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02, rtx5080))
        assertEquals(true, monitor.observed.value?.verifiedPhysical)
    }

    @Test fun observedVerdictsAreCachedUntilTheMonitorIsInvalidated() {
        val probe = ScenarioProbe(scenario("this-pc"))
        val monitor = DesktopNvidiaCapabilityMonitor(probe)
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02))
        val calls = probe.verdictCalls.get()
        assertTrue(calls >= 1)
        for (repeat in 0 until 5) assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02))
        assertEquals(calls, probe.verdictCalls.get(), "the actor polls every 200 ms; the path query runs once")
        monitor.invalidate()
        assertNull(monitor.observed.value)
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02))
        assertTrue(probe.verdictCalls.get() > calls)
    }

    @Test fun aCacheHitRepublishesTheLatestCheckedLuidAsTheObservedOne() {
        val monitor = DesktopNvidiaCapabilityMonitor(ScenarioProbe(scenario("dxgi-failed-twin-present")))
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02, rtx5080))
        assertFalse(monitor.validateObservedLuid(twinLuid, 0x2c02, rtx5080))
        assertEquals(DesktopNvidiaObservedGpu(rtx5080, 0x2c02, twinLuid, false, null), monitor.observed.value)
        assertTrue(monitor.validateObservedLuid(physicalLuid, 0x2c02, rtx5080), "cached")
        assertEquals(DesktopNvidiaObservedGpu(rtx5080, 0x2c02, physicalLuid, true, null), monitor.observed.value)
    }

    @Test fun aProbeThatFailsDuringAPathQueryNeverAcceptsTheLuid() {
        val throwing = object : DesktopNvidiaGpuProbe {
            override fun probe(): NvidiaProbeInput = NvidiaProbeInput(false, emptyList())
            override fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict =
                throw IllegalStateException("fixture")
        }
        val monitor = DesktopNvidiaCapabilityMonitor(throwing)
        assertFalse(monitor.validateObservedLuid(physicalLuid, 0x2c02))
        assertEquals(false, monitor.observed.value?.verifiedPhysical)
    }

    // ---- Watchdog and bounded waits ----

    @Test fun aHungProbeIsAbandonedAfterTheWatchdogAndItsLateResultIsDropped() {
        val release = CountDownLatch(1)
        val probe = ScenarioProbe(scenario("this-pc"), gate = release)
        val monitor = DesktopNvidiaCapabilityMonitor(probe, warmUpTimeoutMillis = 150L, sessionTimeoutMillis = 4_000L)
        monitor.warmUp()
        assertEquals(failed, awaitCapability(monitor) { it !is NvidiaCapability.Probing })
        release.countDown() // the abandoned thread now finishes late
        val deadline = System.nanoTime() + 300_000_000L
        while (System.nanoTime() < deadline) {
            assertEquals(failed, monitor.capability.value, "a late result must never replace the bounded verdict")
            Thread.sleep(10L)
        }
        // After the abandoned thread ended, an invalidated verdict can be probed again and recovers.
        monitor.invalidate()
        monitor.refreshForSession()
        assertIs<NvidiaCapability.Capable>(monitor.capability.value)
        assertEquals(2, probe.probeCalls.get())
    }

    @Test fun theSessionWaitIsBoundedAndNeverWaitsAgainOnAnAbandonedProbe() {
        val release = CountDownLatch(1)
        val probe = ScenarioProbe(scenario("this-pc"), gate = release)
        val monitor = DesktopNvidiaCapabilityMonitor(probe, warmUpTimeoutMillis = 100L, sessionTimeoutMillis = 5_000L)
        try {
            monitor.warmUp()
            assertEquals(failed, awaitCapability(monitor) { it !is NvidiaCapability.Probing })
            monitor.invalidate()
            val started = System.nanoTime()
            monitor.refreshForSession()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMillis < 2_000L, "waited $elapsedMillis ms on a probe that was already abandoned")
            assertEquals(1, probe.probeCalls.get(), "no second thread is started while the first call hangs")
            assertEquals(failed, monitor.capability.value)
        } finally {
            release.countDown()
        }
    }

    @Test fun aSessionStartThatStartedTheProbeAbandonsItAtItsOwnBound() {
        val release = CountDownLatch(1)
        val probe = ScenarioProbe(scenario("this-pc"), gate = release)
        val monitor = DesktopNvidiaCapabilityMonitor(probe, sessionTimeoutMillis = 120L)
        try {
            val started = System.nanoTime()
            monitor.refreshForSession()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMillis < 3_000L, "the native worker waited $elapsedMillis ms")
            assertEquals(failed, monitor.capability.value)
            assertEquals(NVIDIA_LEGACY_ADAPTER_PIN, monitor.pinFor())
        } finally {
            release.countDown()
        }
    }

    // ---- Pure helpers shared with the player ----

    @Test fun theActorGateWaitsForAnAbsentLuidThenGivesUp() {
        val exact = DesktopNvidiaLuidExpectation.Exact(physicalLuid, 0x2c02)
        val never: (String) -> Boolean = { throw AssertionError("an absent luid must not be verified") }
        assertEquals(NvidiaLuidGateResult.Pending, resolveNvidiaLuidGate(exact, null, 0L, never))
        assertEquals(NvidiaLuidGateResult.Pending, resolveNvidiaLuidGate(exact, null, NVIDIA_LUID_WAIT_NANOS - 1L, never))
        assertEquals(NvidiaLuidGateResult.Missing, resolveNvidiaLuidGate(exact, null, NVIDIA_LUID_WAIT_NANOS, never))
        assertEquals(NvidiaLuidGateResult.Missing,
            resolveNvidiaLuidGate(DesktopNvidiaLuidExpectation.VerifyObserved, null, Long.MAX_VALUE, never))
        assertEquals(2_000_000_000L, NVIDIA_LUID_WAIT_NANOS)
    }

    @Test fun theActorGateComparesAnExactExpectationInEitherHalfOrder() {
        val exact = DesktopNvidiaLuidExpectation.Exact(physicalLuid, 0x2c02)
        val never: (String) -> Boolean = { throw AssertionError("an exact expectation does not verify") }
        for (observed in listOf(physicalLuid, "000000000000E782", "0x000000000000e782", "0000e78200000000")) {
            assertEquals(NvidiaLuidGateResult.Pass, resolveNvidiaLuidGate(exact, observed, 0L, never), observed)
        }
        for (observed in listOf(twinLuid, "0000000000000000", "garbage", "", "000000000000e78", "000000000000e7822")) {
            assertEquals(NvidiaLuidGateResult.Mismatch, resolveNvidiaLuidGate(exact, observed, 0L, never), observed)
        }
    }

    @Test fun theActorGateAsksTheVerifierExactlyOnceForAVerifyObservedExpectation() {
        for (verdict in listOf(true, false)) {
            val seen = mutableListOf<String>()
            val result = resolveNvidiaLuidGate(DesktopNvidiaLuidExpectation.VerifyObserved, "000000000000e782", 0L) {
                seen += it; verdict
            }
            assertEquals(if (verdict) NvidiaLuidGateResult.Pass else NvidiaLuidGateResult.Mismatch, result)
            assertEquals(listOf("000000000000e782"), seen)
        }
    }

    @Test fun theProofConjunctOnlyTightens() {
        assertTrue(nvidiaProofLuidAllowed(null, null, physicalLuid))
        assertTrue(nvidiaProofLuidAllowed(null, twinLuid, twinLuid))
        val exact = DesktopNvidiaLuidExpectation.Exact(physicalLuid, 0x2c02)
        assertTrue(nvidiaProofLuidAllowed(exact, null, physicalLuid))
        assertFalse(nvidiaProofLuidAllowed(exact, physicalLuid, twinLuid))
        assertFalse(nvidiaProofLuidAllowed(exact, null, "0000e78200000000"), "the frame prints high first; no half swap here")
        assertFalse(nvidiaProofLuidAllowed(exact, null, ""))
        val verify = DesktopNvidiaLuidExpectation.VerifyObserved
        assertTrue(nvidiaProofLuidAllowed(verify, physicalLuid, physicalLuid))
        assertTrue(nvidiaProofLuidAllowed(verify, "0000e78200000000", physicalLuid))
        assertFalse(nvidiaProofLuidAllowed(verify, null, physicalLuid), "no verified observed luid, no proof")
        assertFalse(nvidiaProofLuidAllowed(verify, twinLuid, physicalLuid))
    }

    @Test fun loggedDriverTextConvertsLikeTheWindowsDriverFields() {
        assertEquals(61742, nvidiaDriverPublicX100FromText("32.0.16.1742"))
        assertEquals(54601, nvidiaDriverPublicX100FromText("31.0.15.4601"))
        assertEquals(57602, nvidiaDriverPublicX100FromText(" 32.0.15.7602 "))
        for (invalid in listOf(null, "", "32.0.16", "32.0.16.1742.1", "a.b.c.d", "123456.0.16.1742", "32.1.16.1742",
            "10.0.16.1742", "32.0.16.10000", "32.0.-1.1742", "32..16.1742", "32.0.16.17x2")) {
            assertNull(nvidiaDriverPublicX100FromText(invalid), invalid ?: "null")
        }
    }

    @Test fun luidTextSplitsIntoHighAndLowHalves() {
        assertEquals(0 to 0xe782, parseNvidiaLuidHalves("000000000000e782"))
        assertEquals(-2 to 0x80000001.toInt(), parseNvidiaLuidHalves("0xFFFFFFFE80000001"))
        assertEquals(1 to 0xb95ad, parseNvidiaLuidHalves(" 00000001000B95AD "))
        for (invalid in listOf(null, "", "e782", "000000000000e78", "000000000000e7822", "000000000000e78g", "zzzzzzzzzzzzzzzz")) {
            assertNull(parseNvidiaLuidHalves(invalid), invalid ?: "null")
        }
    }
}
