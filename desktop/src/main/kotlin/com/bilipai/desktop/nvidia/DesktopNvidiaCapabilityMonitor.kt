package com.bilipai.desktop.nvidia

import com.bilipai.desktop.player.DesktopWindowsDxgiAdapters
import com.sun.jna.Native
import com.sun.jna.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Process-level NVIDIA capability monitor. It owns the read-only probe, the cached verdict, the
// adapter pin the player should set, and the LUID expectation the player checks its own log against.
// Nothing here writes the presentation active flag or touches any presentation proof: it only adds
// vetoes. A failed probe is "unknown" and fails closed (design 5.7): it is never "no GPU", and a
// request is only allowed after the player's own logged LUID passes the physical check.

/** What the player's own log must show before an enhancement request may run. */
internal sealed interface DesktopNvidiaLuidExpectation {
    /** One physical adapter was verified by the probe: the player must be using exactly this LUID. */
    data class Exact(val luidHex: String, val deviceId: Int) : DesktopNvidiaLuidExpectation

    /** The probe failed or has not finished: the player's logged LUID must itself pass the physical check. */
    data object VerifyObserved : DesktopNvidiaLuidExpectation
}

/** The player has this long after its Device ID line to log a LUID. */
internal const val NVIDIA_LUID_WAIT_NANOS: Long = 2_000_000_000L

/** Verdict of the actor-side LUID check. Pending keeps the request queued; the others are final. */
internal sealed interface NvidiaLuidGateResult {
    data object Pass : NvidiaLuidGateResult

    data object Pending : NvidiaLuidGateResult

    /** The player never logged a LUID within the wait. */
    data object Missing : NvidiaLuidGateResult

    data object Mismatch : NvidiaLuidGateResult
}

/**
 * Pure actor-side check. An absent LUID stays pending until waitedNanos (time since the player's
 * Device ID line) reaches the wait, then becomes Missing. With an exact expectation the LUIDs are
 * compared with either 32-bit half order, because the order the player prints is unverified; with
 * VerifyObserved the logged LUID must pass the physical check.
 */
internal fun resolveNvidiaLuidGate(
    expectation: DesktopNvidiaLuidExpectation,
    observedLuidHex: String?,
    waitedNanos: Long,
    verifyObservedPhysical: (String) -> Boolean,
): NvidiaLuidGateResult {
    if (observedLuidHex == null) {
        return if (waitedNanos >= NVIDIA_LUID_WAIT_NANOS) NvidiaLuidGateResult.Missing else NvidiaLuidGateResult.Pending
    }
    return when (expectation) {
        is DesktopNvidiaLuidExpectation.Exact ->
            if (luidMatches(expectation.luidHex, observedLuidHex)) NvidiaLuidGateResult.Pass else NvidiaLuidGateResult.Mismatch
        is DesktopNvidiaLuidExpectation.VerifyObserved ->
            if (verifyObservedPhysical(observedLuidHex)) NvidiaLuidGateResult.Pass else NvidiaLuidGateResult.Mismatch
    }
}

/**
 * Proof-time conjunct. It only tightens: no expectation allows everything, an exact expectation needs
 * the displayed frame's LUID text to equal the expected high-first text, and a verified-observed
 * expectation needs the displayed LUID to match the one the player logged and that was verified.
 */
internal fun nvidiaProofLuidAllowed(
    expectation: DesktopNvidiaLuidExpectation?,
    observedLuidHex: String?,
    shownLuidHex: String,
): Boolean = when (expectation) {
    null -> true
    is DesktopNvidiaLuidExpectation.Exact -> shownLuidHex == expectation.luidHex
    is DesktopNvidiaLuidExpectation.VerifyObserved ->
        observedLuidHex != null && luidMatches(observedLuidHex, shownLuidHex)
}

/** Public driver number in hundredths from the player-logged "a.b.c.d" text, or null when it is not four bounded numbers. */
internal fun nvidiaDriverPublicX100FromText(text: String?): Int? {
    if (text == null) return null
    val parts = text.trim().split('.')
    if (parts.size != 4) return null
    val numbers = IntArray(4)
    for (index in 0 until 4) {
        val part = parts[index]
        if (part.isEmpty() || part.length > 5 || !part.all { it in '0'..'9' }) return null
        numbers[index] = part.toInt()
    }
    return nvidiaDriverPublicX100(numbers[0], numbers[1], numbers[2], numbers[3])
}

/** The two 32-bit halves (first high, then low) of a 16 digit LUID text, or null. A 0x prefix and case are ignored. */
internal fun parseNvidiaLuidHalves(text: String?): Pair<Int, Int>? {
    if (text == null) return null
    val value = text.trim().lowercase().removePrefix("0x")
    if (value.length != 16) return null
    for (ch in value) {
        if (!((ch in '0'..'9') || (ch in 'a'..'f'))) return null
    }
    return Pair(value.substring(0, 8).toLong(16).toInt(), value.substring(8).toLong(16).toInt())
}

/** One read-only inventory plus the physical check of one LUID. Real in production, fake in tests. */
internal interface DesktopNvidiaGpuProbe {
    /** The full inventory. A failure is reported as an incomplete input, never as an empty complete one. */
    fun probe(): NvidiaProbeInput

    /** PnP check of one adapter LUID against a DXGI device id. */
    fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict
}

/**
 * The real probe: the DXGI detailed inventory plus the DisplayConfig adapter path of each NVIDIA
 * hardware candidate. The user-mode driver version is read for the one validated primary only,
 * after the selection, never in the enumeration loop. No D3D device is created.
 */
internal class DesktopNvidiaSystemGpuProbe : DesktopNvidiaGpuProbe {
    override fun probe(): NvidiaProbeInput {
        val snapshot = DesktopWindowsDxgiAdapters.probeDetailed()
        val details = snapshot.adapters()
        val evidence = details.map { detail -> evidenceFor(detail) }
        val input = NvidiaProbeInput(
            dxgiComplete = snapshot.complete() && snapshot.error() == null,
            adapters = evidence,
        )
        if (!input.dxgiComplete) return input
        val selection = selectNvidiaPrimary(input)
        if (selection !is NvidiaPrimarySelection.Selected) return input
        val primary = selection.primary
        val umd = DesktopWindowsDxgiAdapters.umdVersionFor(primary.luidLow, primary.luidHigh)
        if (umd <= 0L) return input
        return input.copy(
            adapters = input.adapters.map { adapter ->
                if (adapter.luidLow == primary.luidLow && adapter.luidHigh == primary.luidHigh) {
                    adapter.copy(umdVersion = umd)
                } else {
                    adapter
                }
            },
        )
    }

    override fun physicalVerdict(luidHigh: Int, luidLow: Int, dxgiDeviceId: Int): NvidiaPhysicalVerdict =
        DesktopWindowsAdapterIdentity.physicalVerdict(luidHigh, luidLow, dxgiDeviceId)

    private fun evidenceFor(detail: DesktopWindowsDxgiAdapters.AdapterDetail): NvidiaAdapterEvidence {
        val candidate = detail.vendorId() == NVIDIA_VENDOR_ID && !detail.isSoftware()
        val verdict = if (candidate) {
            physicalVerdict(detail.luidHigh(), detail.luidLow(), detail.deviceId())
        } else {
            NvidiaPhysicalVerdict.UNKNOWN
        }
        return NvidiaAdapterEvidence(
            vendorId = detail.vendorId(),
            deviceId = detail.deviceId(),
            subSysId = detail.subSysId(),
            revision = detail.revision(),
            description = detail.description(),
            dedicatedVideoMemoryBytes = detail.dedicatedVideoMemory(),
            luidLow = detail.luidLow(),
            luidHigh = detail.luidHigh(),
            flags = detail.flags(),
            outputCount = detail.outputCount(),
            physical = verdict,
        )
    }
}

/**
 * The raw, read-only environment facts one poll needs. Real in production, fake in tests. Every method
 * answers "unknown" instead of throwing, and unknown never counts as a pause reason.
 */
internal interface DesktopNvidiaEnvironmentProbe {
    /** The foreground window facts, or null when they could not be read. */
    fun foreground(): DesktopNvidiaForegroundSnapshot?

    /** True only for AC offline with a battery present. */
    fun onBattery(): Boolean

    /** PnP device path of the display adapter with this 16 digit LUID text, or null when unknown. */
    fun adapterDevicePath(luidHex: String): String?
}

/** The real environment probe: the foreground window, the power state and the adapter path, all read-only. */
internal class DesktopNvidiaSystemEnvironmentProbe : DesktopNvidiaEnvironmentProbe {
    private val ownProcessId: Int = ProcessHandle.current().pid().toInt()

    override fun foreground(): DesktopNvidiaForegroundSnapshot? = DesktopWindowsForegroundBusy.snapshot(ownProcessId)

    override fun onBattery(): Boolean = DesktopWindowsPowerState.onBattery()

    override fun adapterDevicePath(luidHex: String): String? {
        val halves = parseNvidiaLuidHalves(luidHex) ?: return null
        return DesktopWindowsAdapterIdentity.adapterDevicePath(halves.first, halves.second)
    }
}

/**
 * Cached, bounded, fail-closed capability verdict.
 *
 * The first probe runs at startup on a daemon thread named BiliPai-gpu-probe with a five second
 * watchdog. A hung driver call cannot be interrupted: the thread is abandoned and the verdict becomes
 * Unknown(PROBE_FAILED). Each native session start reuses a result younger than thirty seconds or
 * re-probes, waiting at most one and a half seconds on the native worker. The kill switch
 * -Dbilipai.gpu.autodetect=off makes the monitor report NotEvaluated, which is today's behaviour.
 * The neutral monitor has no probe at all and is what a player gets unless the application passes
 * the process monitor.
 *
 * The environment (game, battery, remote or virtual output) is polled every two seconds on a daemon
 * thread named BiliPai-gpu-environment, and only while the session asks for it: a plan exists (standby,
 * running or paused) and the host is visible. The raw reads go through the pause hysteresis of the
 * activation file (enter after two consecutive polls, leave after five clear seconds). Stopping the poll
 * clears every pause, so a stale one cannot linger. Without an environment probe (the neutral monitor, the
 * kill switch, a test) nothing is ever polled and the environment stays empty.
 */
internal class DesktopNvidiaCapabilityMonitor internal constructor(
    private val probe: DesktopNvidiaGpuProbe?,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val warmUpTimeoutMillis: Long = WARM_UP_TIMEOUT_MILLIS,
    private val sessionTimeoutMillis: Long = SESSION_TIMEOUT_MILLIS,
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val environmentProbe: DesktopNvidiaEnvironmentProbe? = null,
    private val environmentIntervalMillis: Long = ENVIRONMENT_POLL_MILLIS,
) {
    private val mutableCapability = MutableStateFlow<NvidiaCapability>(
        if (probe == null) NvidiaCapability.NotEvaluated else NvidiaCapability.Probing,
    )

    /** NotEvaluated without a probe, Probing until the first result, then the verdict. */
    val capability: StateFlow<NvidiaCapability> = mutableCapability.asStateFlow()

    private val mutableEnvironment = MutableStateFlow(DesktopNvidiaEnvironment())

    /** The smoothed environment flags. Empty while nothing is polled. */
    val environment: StateFlow<DesktopNvidiaEnvironment> = mutableEnvironment.asStateFlow()

    private val mutableObserved = MutableStateFlow<DesktopNvidiaObservedGpu?>(null)

    /** The player-logged GPU with the result of its physical check, once validateObservedLuid ran. */
    val observed: StateFlow<DesktopNvidiaObservedGpu?> = mutableObserved.asStateFlow()

    /** False for the neutral monitor and under the kill switch. */
    val autodetectEnabled: Boolean get() = probe != null

    // Lock order is runLock then stateLock; nothing takes runLock while holding stateLock.
    private val stateLock = Any()
    private val runLock = Any()
    private val invalidations = AtomicLong()
    private var completedAtMillis: Long? = null
    private var stale = false
    private var warmUpStarted = false
    private var inFlight: ProbeRun? = null
    private val observedVerdicts = LinkedHashMap<String, Boolean>()

    // Environment poll state. Lock order is pollLock then environmentLock; the poll thread takes only
    // environmentLock and never holds either lock while it reads the system.
    private val pollLock = Any()
    private val environmentLock = Any()
    private val pollGeneration = AtomicLong()
    private var pollThread: Thread? = null
    private var environmentTracker = DesktopNvidiaEnvironmentTracker()

    // The latest monitor of the video window as the session reports it: the adapter LUID text and the
    // output technology. One immutable pair, so a poll never mixes two reports.
    @Volatile private var displayReport: Pair<String?, Int?> = Pair(null, null)

    private class ProbeRun(val startInvalidations: Long) {
        val result = CompletableFuture<NvidiaCapability>()
        val published = AtomicBoolean(false)
    }

    private class ProbeClaim(val run: ProbeRun, val created: Boolean)

    /** First probe, off the caller's thread. Safe to call more than once. */
    fun warmUp() {
        val source = probe ?: return
        synchronized(stateLock) {
            if (warmUpStarted) return
            warmUpStarted = true
        }
        startSupervisor(source, warmUpTimeoutMillis)
    }

    /**
     * Called on the native worker before the adapter option is set. Reuses a current result,
     * otherwise waits at most the session bound for a probe. A timeout leaves the bounded verdict
     * Unknown(PROBE_FAILED) when this call started the probe.
     */
    fun refreshForSession() {
        val source = probe ?: return
        if (isFresh()) return
        awaitProbe(source, sessionTimeoutMillis)
    }

    /** The adapter option text the player should set, or null when no option may be set. */
    fun pinFor(): String? = nvidiaPinFor(capability.value).optionValue()

    /** The LUID the player's own log must match, or null when no check applies. */
    fun expectation(): DesktopNvidiaLuidExpectation? = when (val current = capability.value) {
        is NvidiaCapability.Capable -> DesktopNvidiaLuidExpectation.Exact(current.gpu.luidHex, current.gpu.deviceId)
        is NvidiaCapability.Unknown -> DesktopNvidiaLuidExpectation.VerifyObserved
        is NvidiaCapability.Probing -> DesktopNvidiaLuidExpectation.VerifyObserved
        is NvidiaCapability.NotEvaluated -> null
        is NvidiaCapability.Absent -> null
        is NvidiaCapability.Unsupported -> null
    }

    /**
     * True only when the player-logged LUID is a physical NVIDIA adapter whose PnP path carries the
     * logged device id. Either 32-bit half order is tried because the logged order is unverified; a
     * virtual-sounding name only ever excludes. The result is cached per LUID and device id.
     */
    fun validateObservedLuid(
        luidHex: String,
        deviceId: Int?,
        description: String? = null,
        driverPublicX100: Int? = null,
    ): Boolean {
        val source = probe ?: return false
        val halves = parseNvidiaLuidHalves(luidHex) ?: return false
        val device = deviceId ?: return false
        if (description != null && nvidiaNameLooksVirtual(description)) return false
        val normalized = luidHex.trim().lowercase().removePrefix("0x")
        val key = normalized + ":" + device
        val cached = synchronized(stateLock) {
            val known = observedVerdicts[key]
            // The latest checked LUID is the one observed(): a cache hit republishes it, so an
            // interleaved check of another LUID cannot leave a stale verdict behind.
            if (known != null) mutableObserved.value = DesktopNvidiaObservedGpu(description, device, normalized, known, driverPublicX100)
            known
        }
        if (cached != null) return cached
        val verdict = try {
            source.physicalVerdict(halves.first, halves.second, device) == NvidiaPhysicalVerdict.PHYSICAL ||
                source.physicalVerdict(halves.second, halves.first, device) == NvidiaPhysicalVerdict.PHYSICAL
        } catch (_: Throwable) {
            false
        }
        synchronized(stateLock) {
            if (observedVerdicts.size >= OBSERVED_CACHE_LIMIT) observedVerdicts.clear()
            observedVerdicts[key] = verdict
            mutableObserved.value = DesktopNvidiaObservedGpu(description, device, normalized, verdict, driverPublicX100)
        }
        return verdict
    }

    /** Marks the cached verdict stale: the next session start probes again. Starts no thread. */
    fun invalidate() {
        if (probe == null) return
        markStale()
    }

    /** The user-requested re-detection: stale now and a fresh probe in the background. */
    fun redetect() {
        val source = probe ?: return
        markStale()
        startSupervisor(source, warmUpTimeoutMillis)
    }

    /**
     * The session reports the monitor of the video window: the adapter LUID text (high part first) and the
     * output technology of its display path, either may be unknown. Raw facts only: the next poll reads them,
     * so the remote flag goes through the same hysteresis as the others. Cheap and safe to call often.
     */
    fun reportDisplayOutput(adapterLuidHex: String?, outputTechnology: Int?) {
        displayReport = Pair(adapterLuidHex, outputTechnology)
    }

    /**
     * Starts or stops the environment poll. The session wants it only while a plan exists and the host is
     * visible. Idempotent. Starting polls at once on a new daemon thread and then every interval. Stopping
     * clears every pause. Does nothing without an environment probe.
     */
    fun setEnvironmentPolling(wanted: Boolean) {
        if (environmentProbe == null) return
        synchronized(pollLock) {
            if (wanted) startEnvironmentPollLocked() else stopEnvironmentPollLocked()
        }
    }

    private fun startEnvironmentPollLocked() {
        val running = pollThread
        if (running != null && running.isAlive) return
        val generation = pollGeneration.incrementAndGet()
        val thread = Thread({ pollEnvironmentLoop(generation) }, "BiliPai-gpu-environment")
        thread.isDaemon = true
        pollThread = thread
        try {
            thread.start()
        } catch (_: Throwable) {
            // No thread could be created: nothing is polled and the environment stays empty.
            pollThread = null
        }
    }

    private fun stopEnvironmentPollLocked() {
        pollGeneration.incrementAndGet()
        val hadThread = pollThread != null
        pollThread = null
        if (hadThread) resetEnvironment()
    }

    /**
     * One poll: reads the raw facts, advances the hysteresis with the monitor clock and publishes the smoothed
     * environment. Tests call it directly. Does nothing without an environment probe.
     */
    internal fun pollEnvironmentNow() {
        pollEnvironmentFor(null)
    }

    private fun pollEnvironmentLoop(generation: Long) {
        while (pollGeneration.get() == generation) {
            try {
                pollEnvironmentFor(generation)
            } catch (_: Throwable) {
                // A failed poll leaves the flags as they are. Unknown never pauses.
            }
            try {
                Thread.sleep(environmentIntervalMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun pollEnvironmentFor(generation: Long?) {
        val source = environmentProbe ?: return
        val raw = readRawEnvironment(source)
        synchronized(environmentLock) {
            // A poll that finished after the poll was stopped or restarted must not publish.
            if (generation != null && pollGeneration.get() != generation) return
            environmentTracker = environmentTracker.advance(raw, nowMillis())
            mutableEnvironment.value = environmentTracker.environment
        }
    }

    private fun readRawEnvironment(source: DesktopNvidiaEnvironmentProbe): DesktopNvidiaEnvironment {
        val gameLike = try {
            val snapshot = source.foreground()
            snapshot != null && desktopNvidiaGameLike(snapshot)
        } catch (_: Throwable) {
            false
        }
        val onBattery = try {
            source.onBattery()
        } catch (_: Throwable) {
            false
        }
        val remoteOrVirtual = try {
            val report = displayReport
            val luid = report.first
            desktopNvidiaRemoteOutputTechnology(report.second) ||
                (luid != null && desktopNvidiaAdapterPathIsNonPci(source.adapterDevicePath(luid)))
        } catch (_: Throwable) {
            false
        }
        return DesktopNvidiaEnvironment(gameLike, onBattery, remoteOrVirtual)
    }

    private fun resetEnvironment() {
        synchronized(environmentLock) {
            environmentTracker = DesktopNvidiaEnvironmentTracker()
            mutableEnvironment.value = DesktopNvidiaEnvironment()
        }
    }

    private fun markStale() {
        invalidations.incrementAndGet()
        synchronized(stateLock) {
            stale = true
            observedVerdicts.clear()
            mutableObserved.value = null
        }
    }

    private fun isFresh(): Boolean = synchronized(stateLock) {
        val completed = completedAtMillis
        if (completed == null || stale) {
            false
        } else {
            val age = nowMillis() - completed
            age >= 0L && age < maxAgeMillis
        }
    }

    private fun startSupervisor(source: DesktopNvidiaGpuProbe, timeoutMillis: Long) {
        val supervisor = Thread({ awaitProbe(source, timeoutMillis) }, "BiliPai-gpu-probe-watchdog")
        supervisor.isDaemon = true
        try {
            supervisor.start()
        } catch (_: Throwable) {
            // No thread could be created: the verdict stays as it is, which the player treats as unconfirmed.
        }
    }

    /**
     * Joins the probe in flight or starts one, and waits at most timeoutMillis. Only the caller that
     * started the probe may abandon it: a timeout then publishes Unknown(PROBE_FAILED) once, and the
     * late result of the abandoned thread is dropped. A run that was already abandoned is never
     * waited for again, so a hung driver call cannot block every later caller.
     */
    private fun awaitProbe(source: DesktopNvidiaGpuProbe, timeoutMillis: Long) {
        val claim = synchronized(runLock) {
            val existing = inFlight
            if (existing != null) {
                ProbeClaim(existing, false)
            } else {
                val fresh = ProbeRun(invalidations.get())
                inFlight = fresh
                ProbeClaim(fresh, true)
            }
        }
        val run = claim.run
        if (claim.created) startWorker(run, source)
        if (run.published.get()) return
        try {
            run.result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (claim.created) publish(run, FAILED)
        } catch (failure: Exception) {
            if (claim.created) publish(run, FAILED)
        }
    }

    private fun startWorker(run: ProbeRun, source: DesktopNvidiaGpuProbe) {
        val worker = Thread({
            val verdict: NvidiaCapability = try {
                classifyNvidiaCapability(source.probe())
            } catch (_: Throwable) {
                FAILED
            }
            // Publish and release the slot before completing: a waiter that wakes on the future must
            // already see the verdict and must be able to start the next probe.
            publish(run, verdict)
            synchronized(runLock) { if (inFlight === run) inFlight = null }
            run.result.complete(verdict)
        }, "BiliPai-gpu-probe")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: Throwable) {
            publish(run, FAILED)
            synchronized(runLock) { if (inFlight === run) inFlight = null }
        }
    }

    private fun publish(run: ProbeRun, value: NvidiaCapability) {
        if (!run.published.compareAndSet(false, true)) return
        synchronized(stateLock) {
            mutableCapability.value = value
            completedAtMillis = nowMillis()
            stale = invalidations.get() != run.startInvalidations
            observedVerdicts.clear()
        }
    }

    companion object {
        /** -Dbilipai.gpu.autodetect=off turns detection off: the capability stays NotEvaluated. */
        const val KILL_SWITCH_PROPERTY: String = "bilipai.gpu.autodetect"
        const val WARM_UP_TIMEOUT_MILLIS: Long = 5_000L
        const val SESSION_TIMEOUT_MILLIS: Long = 1_500L
        const val MAX_AGE_MILLIS: Long = 30_000L

        /** The environment (game, battery, remote output) is read this often while the session wants it. */
        const val ENVIRONMENT_POLL_MILLIS: Long = 2_000L
        private const val OBSERVED_CACHE_LIMIT: Int = 8
        private val FAILED: NvidiaCapability = NvidiaCapability.Unknown(NvidiaUnknownReason.PROBE_FAILED)

        /** The real monitor, created once per process. NotEvaluated under the kill switch or outside 64-bit Windows. */
        fun process(): DesktopNvidiaCapabilityMonitor = processMonitor

        /** Never probes: NotEvaluated, no pin change, no expectation. Players and tests get this by default. */
        fun neutral(): DesktopNvidiaCapabilityMonitor = DesktopNvidiaCapabilityMonitor(probe = null)

        /** Only the exact value off disables detection; case and surrounding spaces are ignored. */
        internal fun autodetectRequested(property: String?): Boolean =
            property == null || property.trim().lowercase() != "off"

        private fun windowsGateOpen(): Boolean =
            Platform.isWindows() && Native.POINTER_SIZE == 8 && Native.WCHAR_SIZE == 2

        private val processMonitor: DesktopNvidiaCapabilityMonitor by lazy {
            if (autodetectRequested(System.getProperty(KILL_SWITCH_PROPERTY)) && windowsGateOpen()) {
                DesktopNvidiaCapabilityMonitor(
                    DesktopNvidiaSystemGpuProbe(),
                    environmentProbe = DesktopNvidiaSystemEnvironmentProbe(),
                )
            } else {
                DesktopNvidiaCapabilityMonitor(probe = null)
            }
        }
    }
}
