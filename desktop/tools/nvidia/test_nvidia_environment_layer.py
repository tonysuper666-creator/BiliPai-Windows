"""Source checks for the NVIDIA environment layer (design 8.3 and 16; slice S5): game yield, battery, remote or
virtual output, and the display adapter.

The native layouts that the Kotlin code reads are compared with ctypes structures that the platform ABI lays out
independently of the Kotlin text. The seam checks pin how the session feeds the monitor and how the monitor polls:
only while a plan exists and the host is visible, through the pause hysteresis, with a failed read never a pause.
The checks read source only: nothing here loads user32, kernel32, shell32, a driver or a GPU."""
import ctypes
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

PROBE = source.KN / "DesktopWindowsGpuProbe.kt"
MONITOR = source.KN / "DesktopNvidiaCapabilityMonitor.kt"
RULES = source.KN / "DesktopNvidiaEnvironmentRules.kt"
ACTIVATION = source.KN / "DesktopNvidiaActivation.kt"
HDR_DISPLAY = source.K / "player/DesktopWindowsHdrDisplay.kt"
SESSION = source.K / "player/DesktopVideoEnhancementSession.kt"


class Luid(ctypes.Structure):
    _fields_ = [("LowPart", ctypes.c_uint32), ("HighPart", ctypes.c_int32)]


class SystemPowerStatus(ctypes.Structure):
    """WinBase.h SYSTEM_POWER_STATUS: four BYTE then two DWORD."""
    _fields_ = [("ACLineStatus", ctypes.c_uint8), ("BatteryFlag", ctypes.c_uint8), ("BatteryLifePercent", ctypes.c_uint8),
                ("SystemStatusFlag", ctypes.c_uint8), ("BatteryLifeTime", ctypes.c_uint32),
                ("BatteryFullLifeTime", ctypes.c_uint32)]


class Rect(ctypes.Structure):
    _fields_ = [("left", ctypes.c_int32), ("top", ctypes.c_int32), ("right", ctypes.c_int32), ("bottom", ctypes.c_int32)]


class MonitorInfo(ctypes.Structure):
    """MONITORINFO: cbSize, rcMonitor, rcWork, dwFlags."""
    _fields_ = [("cbSize", ctypes.c_uint32), ("rcMonitor", Rect), ("rcWork", Rect), ("dwFlags", ctypes.c_uint32)]


class PathSourceInfo(ctypes.Structure):
    """DISPLAYCONFIG_PATH_SOURCE_INFO: the mode index and the clone group share one union member."""
    _fields_ = [("adapterId", Luid), ("id", ctypes.c_uint32), ("modeInfoIdx", ctypes.c_uint32), ("statusFlags", ctypes.c_uint32)]


class Rational(ctypes.Structure):
    _fields_ = [("Numerator", ctypes.c_uint32), ("Denominator", ctypes.c_uint32)]


class PathTargetInfo(ctypes.Structure):
    """DISPLAYCONFIG_PATH_TARGET_INFO."""
    _fields_ = [("adapterId", Luid), ("id", ctypes.c_uint32), ("modeInfoIdx", ctypes.c_uint32),
                ("outputTechnology", ctypes.c_uint32), ("rotation", ctypes.c_uint32), ("scaling", ctypes.c_uint32),
                ("refreshRate", Rational), ("scanLineOrdering", ctypes.c_uint32), ("targetAvailable", ctypes.c_int32),
                ("statusFlags", ctypes.c_uint32)]


class PathInfo(ctypes.Structure):
    """DISPLAYCONFIG_PATH_INFO."""
    _fields_ = [("sourceInfo", PathSourceInfo), ("targetInfo", PathTargetInfo), ("flags", ctypes.c_uint32)]


def kotlin_constants(text):
    values = {}
    for name, value in re.findall(r"const val ([A-Z_]+): (?:Int|Long) = (\d+)L?\n", text):
        values[name] = int(value)
    return values


def collapse(text):
    return " ".join(text.split())


def balanced_body(text, marker):
    """Source from the marker through the closing brace of its first body. Comments and literals are skipped."""
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    index = text.index("{", start)
    depth = 0
    while index < len(text):
        pair = text[index:index + 2]
        char = text[index]
        if pair == "//":
            while index < len(text) and text[index] != "\n":
                index += 1
            continue
        if pair == "/*":
            index = text.index("*/", index + 2) + 2
            continue
        if char in "\"'":
            quote = char
            index += 1
            while text[index] != quote:
                index += 2 if text[index] == "\\" else 1
            index += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        index += 1
    raise AssertionError("unbalanced body for " + marker)


class NativeLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.probe = source.read(PROBE)
        cls.constants = kotlin_constants(cls.probe)
        cls.hdr = source.read(HDR_DISPLAY)
        cls.hdr_constants = {name: int(value) for name, value in
                             re.findall(r"private const val ([A-Z_]+) = (\d+)L\n", cls.hdr)}

    def test_the_power_status_is_twelve_bytes_with_the_two_bytes_the_probe_reads_at_zero_and_one(self):
        self.assertEqual(12, ctypes.sizeof(SystemPowerStatus))
        self.assertEqual(0, SystemPowerStatus.ACLineStatus.offset)
        self.assertEqual(1, SystemPowerStatus.BatteryFlag.offset)
        self.assertEqual(SystemPowerStatus.ACLineStatus.offset, self.constants["AC_LINE_STATUS_OFFSET"])
        self.assertEqual(SystemPowerStatus.BatteryFlag.offset, self.constants["BATTERY_FLAG_OFFSET"])
        self.assertEqual(ctypes.sizeof(SystemPowerStatus), self.constants["STATUS_BYTES"])

    def test_the_power_probe_reads_only_those_two_bytes_and_hands_them_to_the_pure_rule(self):
        body = balanced_body(self.probe, "    fun onBattery(): Boolean {")
        compact = collapse(body)
        self.assertIn("desktopNvidiaOnBattery( status.getByte(AC_LINE_STATUS_OFFSET).toInt(), "
                      "status.getByte(BATTERY_FLAG_OFFSET).toInt(), )", compact)
        self.assertIn("if (api.GetSystemPowerStatus(status) == 0) { false }", compact)
        self.assertEqual(2, body.count(".getByte("))
        self.assertIn('Native.load("kernel32", PowerApi::class.java)', self.probe)

    def test_the_monitor_info_and_rect_sizes_and_offsets_match(self):
        self.assertEqual(40, ctypes.sizeof(MonitorInfo))
        self.assertEqual(16, ctypes.sizeof(Rect))
        self.assertEqual(MonitorInfo.rcMonitor.offset, self.constants["MONITOR_INFO_RECT_OFFSET"])
        self.assertEqual(ctypes.sizeof(MonitorInfo), self.constants["MONITOR_INFO_BYTES"])
        self.assertEqual(ctypes.sizeof(Rect), self.constants["RECT_BYTES"])
        self.assertEqual(2, self.constants["MONITOR_DEFAULT_TO_NEAREST"])
        self.assertEqual(64, self.constants["CLASS_NAME_CHARS"])

    def test_the_rectangles_are_read_in_left_top_right_bottom_order(self):
        text = self.probe
        window = collapse(text[text.index("    private fun windowRect(window: Pointer)"):][:600])
        for offset, name in ((0, "left"), (4, "top"), (8, "right"), (12, "bottom")):
            self.assertEqual(offset, getattr(Rect, name).offset)
        self.assertIn("DesktopNvidiaScreenRect(rect.getInt(0L), rect.getInt(4L), rect.getInt(8L), rect.getInt(12L))", window)
        monitor = collapse(text[text.index("    private fun monitorRect(window: Pointer)"):][:1200])
        self.assertIn("info.setInt(0L, MONITOR_INFO_BYTES)", monitor)
        for step in ("info.getInt(MONITOR_INFO_RECT_OFFSET),", "info.getInt(MONITOR_INFO_RECT_OFFSET + 4L),",
                     "info.getInt(MONITOR_INFO_RECT_OFFSET + 8L),", "info.getInt(MONITOR_INFO_RECT_OFFSET + 12L),"):
            self.assertIn(step, monitor)

    def test_the_display_path_layout_puts_the_source_adapter_at_zero_and_the_output_technology_at_36(self):
        self.assertEqual(72, ctypes.sizeof(PathInfo))
        self.assertEqual(20, ctypes.sizeof(PathSourceInfo))
        self.assertEqual(48, ctypes.sizeof(PathTargetInfo))
        source_luid = PathInfo.sourceInfo.offset + PathSourceInfo.adapterId.offset
        self.assertEqual(0, source_luid)
        technology = PathInfo.targetInfo.offset + PathTargetInfo.outputTechnology.offset
        self.assertEqual(36, technology)
        self.assertEqual(source_luid + Luid.LowPart.offset, self.hdr_constants["SOURCE_ADAPTER_LUID_LOW_OFFSET"])
        self.assertEqual(source_luid + Luid.HighPart.offset, self.hdr_constants["SOURCE_ADAPTER_LUID_HIGH_OFFSET"])
        self.assertEqual(technology, self.hdr_constants["OUTPUT_TECHNOLOGY_OFFSET"])
        # The display reader still walks the paths with the same 72 byte stride, and reads the LUID high part first.
        self.assertEqual(2, self.hdr.count("72L"))
        self.assertIn("nvidiaLuidHex(path.getInt(SOURCE_ADAPTER_LUID_HIGH_OFFSET), path.getInt(SOURCE_ADAPTER_LUID_LOW_OFFSET))", self.hdr)

    def test_the_output_technology_values_are_the_remote_and_indirect_ones(self):
        rules = source.read(RULES)
        values = dict(re.findall(r"const val (MIRACAST|INDIRECT_WIRED|INDIRECT_VIRTUAL): Int = (\d+)", rules))
        self.assertEqual({"MIRACAST": "15", "INDIRECT_WIRED": "16", "INDIRECT_VIRTUAL": "17"}, values)

    def test_the_battery_flag_bits_are_the_documented_ones(self):
        rules = source.read(RULES)
        body = balanced_body(rules, "internal fun desktopNvidiaOnBattery(acLineStatus: Int, batteryFlag: Int): Boolean {")
        compact = collapse(body)
        self.assertIn("if ((acLineStatus and 0xFF) != 0) return false", compact)
        self.assertIn("return (batteryFlag and 0x80) == 0", compact)

    def test_the_adapter_path_prefix_is_the_pci_enumerator(self):
        rules = source.read(RULES)
        self.assertIn('private const val PCI_ENUMERATED_ADAPTER_PATH_PREFIX = "\\\\\\\\?\\\\PCI#"', rules)


class MonitorSeamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MONITOR)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_the_poll_runs_every_two_seconds_on_a_named_daemon_thread(self):
        self.assertIn("const val ENVIRONMENT_POLL_MILLIS: Long = 2_000L", self.text)
        self.assertIn("private val environmentIntervalMillis: Long = ENVIRONMENT_POLL_MILLIS,", self.text)
        self.assertIn('Thread({ pollEnvironmentLoop(generation) }, "BiliPai-gpu-environment")', self.text)
        self.assertIn("thread.isDaemon = true", self.text)
        loop = collapse(balanced_body(self.text, "    private fun pollEnvironmentLoop(generation: Long) {"))
        self.assertIn("while (pollGeneration.get() == generation)", loop)
        self.assertIn("Thread.sleep(environmentIntervalMillis)", loop)
        self.assertIn("catch (_: Throwable)", loop)

    def test_the_poll_is_idempotent_stops_clear_every_pause_and_needs_an_environment_probe(self):
        setter = collapse(balanced_body(self.text, "    fun setEnvironmentPolling(wanted: Boolean) {"))
        self.assertIn("if (environmentProbe == null) return", setter)
        self.assertIn("synchronized(pollLock) { if (wanted) startEnvironmentPollLocked() else stopEnvironmentPollLocked() }", setter)
        start = collapse(balanced_body(self.text, "    private fun startEnvironmentPollLocked() {"))
        self.assertIn("if (running != null && running.isAlive) return", start)
        stop = collapse(balanced_body(self.text, "    private fun stopEnvironmentPollLocked() {"))
        self.assertIn("pollGeneration.incrementAndGet()", stop)
        self.assertIn("if (hadThread) resetEnvironment()", stop)
        reset = collapse(balanced_body(self.text, "    private fun resetEnvironment() {"))
        self.assertIn("environmentTracker = DesktopNvidiaEnvironmentTracker()", reset)
        self.assertIn("mutableEnvironment.value = DesktopNvidiaEnvironment()", reset)

    def test_a_poll_that_finished_after_a_stop_never_publishes_and_the_hysteresis_comes_from_the_activation_file(self):
        body = collapse(balanced_body(self.text, "    private fun pollEnvironmentFor(generation: Long?) {"))
        self.assertIn("val source = environmentProbe ?: return", body)
        gate = body.index("if (generation != null && pollGeneration.get() != generation) return")
        advance = body.index("environmentTracker = environmentTracker.advance(raw, nowMillis())")
        publish = body.index("mutableEnvironment.value = environmentTracker.environment")
        self.assertLess(gate, advance)
        self.assertLess(advance, publish)
        # The raw read happens before the lock, so no system call runs while a lock is held.
        self.assertLess(body.index("val raw = readRawEnvironment(source)"), body.index("synchronized(environmentLock)"))
        activation = source.read(ACTIVATION)
        self.assertIn("internal data class DesktopNvidiaEnvironmentTracker(", activation)
        self.assertIn("fun advance(raw: DesktopNvidiaEnvironment, nowMillis: Long): DesktopNvidiaEnvironmentTracker", activation)

    def test_every_raw_read_is_guarded_so_a_failed_read_is_unknown_and_never_a_pause(self):
        body = collapse(balanced_body(self.text, "    private fun readRawEnvironment(source: DesktopNvidiaEnvironmentProbe): DesktopNvidiaEnvironment {"))
        self.assertEqual(3, body.count("catch (_: Throwable) { false }"))
        self.assertIn("snapshot != null && desktopNvidiaGameLike(snapshot)", body)
        self.assertIn("desktopNvidiaRemoteOutputTechnology(report.second) ||", body)
        self.assertIn("desktopNvidiaAdapterPathIsNonPci(source.adapterDevicePath(luid))", body)
        self.assertIn("return DesktopNvidiaEnvironment(gameLike, onBattery, remoteOrVirtual)", body)

    def test_the_session_reports_the_display_as_raw_facts_in_one_immutable_pair(self):
        self.assertIn("@Volatile private var displayReport: Pair<String?, Int?> = Pair(null, null)", self.text)
        report = collapse(balanced_body(self.text, "    fun reportDisplayOutput(adapterLuidHex: String?, outputTechnology: Int?) {"))
        self.assertEqual("fun reportDisplayOutput(adapterLuidHex: String?, outputTechnology: Int?) { displayReport = Pair(adapterLuidHex, outputTechnology) }", report)

    def test_only_the_process_monitor_gets_an_environment_probe_and_the_kill_switch_keeps_none(self):
        self.assertEqual(1, self.text.count("environmentProbe = DesktopNvidiaSystemEnvironmentProbe()"))
        process = self.text[self.text.index("private val processMonitor: DesktopNvidiaCapabilityMonitor by lazy {"):]
        enabled, disabled = process.split("} else {", 1)
        self.assertIn("DesktopNvidiaSystemEnvironmentProbe()", enabled)
        self.assertNotIn("DesktopNvidiaSystemEnvironmentProbe", disabled)
        self.assertIn("DesktopNvidiaCapabilityMonitor(probe = null)", disabled)
        neutral = self.text[self.text.index("fun neutral(): DesktopNvidiaCapabilityMonitor"):][:200]
        self.assertIn("DesktopNvidiaCapabilityMonitor(probe = null)", neutral)

    def test_the_real_environment_probe_only_delegates_to_the_read_only_probes(self):
        body = collapse(balanced_body(self.text, "internal class DesktopNvidiaSystemEnvironmentProbe : DesktopNvidiaEnvironmentProbe {"))
        for expected in ("ProcessHandle.current().pid().toInt()", "DesktopWindowsForegroundBusy.snapshot(ownProcessId)",
                         "DesktopWindowsPowerState.onBattery()",
                         "DesktopWindowsAdapterIdentity.adapterDevicePath(halves.first, halves.second)"):
            self.assertIn(expected, body)
        interface = collapse(balanced_body(self.text, "internal interface DesktopNvidiaEnvironmentProbe {"))
        self.assertEqual({"foreground", "onBattery", "adapterDevicePath"}, set(re.findall(r"fun (\w+)\(", interface)))


class SessionSeamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(SESSION)
        cls.apply_locked = balanced_body(cls.text, "    private fun applyLocked(input: Input) {")

    def test_the_display_is_reported_before_the_gate_and_the_gate_gets_its_adapter(self):
        body = self.apply_locked
        report = body.index("player.gpuCapability.reportDisplayOutput(display.adapterLuidHex, display.outputTechnology)")
        gate = body.index("val frameGate = resolveDesktopNvidiaGate(")
        self.assertLess(body.index("val display = input.output.hdrDisplay"), report)
        self.assertLess(report, gate)
        call = collapse(body[gate:body.index("gate = frameGate")])
        self.assertTrue(call.endswith("native.veyraAvailable, display.adapterLuidHex, memory)"), call)
        self.assertEqual(1, body.count("reportDisplayOutput("))

    def test_the_poll_is_wanted_only_with_a_plan_and_a_visible_host_and_stops_with_the_session(self):
        body = self.apply_locked
        wanted = "player.gpuCapability.setEnvironmentPolling(frameGate.plan != null && (input.settings.started || input.settings.pip))"
        self.assertEqual(1, body.count("setEnvironmentPolling("))
        self.assertIn(wanted, body)
        self.assertLess(body.index("gate = frameGate"), body.index(wanted))
        self.assertLess(body.index(wanted), body.index("if (!input.settings.enabled) {"))
        close = balanced_body(self.text, "    override fun close() {")
        self.assertIn("player.gpuCapability.setEnvironmentPolling(false)", close)
        self.assertLess(close.index("scope.cancel()"), close.index("setEnvironmentPolling(false)"))
        self.assertEqual(2, self.text.count("setEnvironmentPolling("))

    def test_the_session_never_reads_the_system_itself(self):
        code, _ = source.lex_kotlin(self.text)
        for forbidden in ("GetForegroundWindow", "SHQueryUserNotificationState", "GetSystemPowerStatus", "Thread(",
                          "ProcessHandle", "Native.load", "adapterDevicePath"):
            self.assertNotIn(forbidden, code, forbidden)
        init = self.text[self.text.index("    init {"):self.text.index("    /** Missing BV/PGC/live/story IDs")]
        self.assertIn("combine(monitor.capability, monitor.environment, monitor.observed)", init)

    def test_entering_a_pause_clears_the_owned_filter_through_the_one_bypass(self):
        body = self.apply_locked
        bypass = balanced_body(body, "    fun bypass(reason: Anime4KBypassReason, text: String, statusKind: DesktopNvidiaStatusKind? = null, line: String? = null) {")
        first = bypass[bypass.index("{") + 1:].lstrip()
        self.assertTrue(first.startswith("clearOwnedLocked()"), first[:80])
        unavailable = collapse(balanced_body(body, "    fun bypassUnavailable(reason: Anime4KBypassReason, line: String) {"))
        self.assertIn("bypass(reason, line, line = line)", unavailable)
        # The pause states are exactly the ones that bypass before any source check, the three environment ones among them.
        getter = collapse(self.text[self.text.index("val bypassesBeforeSourceChecks: Boolean get() = when (state) {"):][:900])
        for state in ("PausedGame", "PausedBattery", "PausedRemote", "FailedRemembered", "FailedThisVideo", "FailedExtendedRange"):
            self.assertIn("DesktopNvidiaActivationState." + state, getter, state)
        gate = body.index("if (frameGate.bypassesBeforeSourceChecks) {")
        self.assertIn("bypassUnavailable(Anime4KBypassReason.NONE,", body[gate:gate + 200])
        # The pause carries no request: it returns before the decision, the options and the native call.
        self.assertLess(gate, body.index("player.setNvidiaVideoEnhancementIfSourceSnapshot("))

    def test_the_gate_takes_the_display_adapter_as_a_defaulted_last_argument(self):
        gate = collapse(self.text[self.text.index("internal fun resolveDesktopNvidiaGate("):][:1400])
        self.assertIn("componentAvailable: Boolean, displayAdapterLuidHex: String? = null, "
                      "memory: DesktopNvidiaGateMemory = DesktopNvidiaGateMemory.None, ): DesktopNvidiaGate {", gate)
        self.assertIn("displayAdapterLuidHex = displayAdapterLuidHex, )", gate)


class ActivationSeamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(ACTIVATION)

    def test_the_display_adapter_is_a_defaulted_last_parameter_applied_to_both_plan_builds(self):
        resolver = collapse(self.text[self.text.index("internal fun resolveDesktopNvidiaActivation("):][:700])
        self.assertIn("guardGaveUpForSource: Boolean = false, displayAdapterLuidHex: String? = null, ): DesktopNvidiaActivationState {", resolver)
        body = collapse(balanced_body(self.text, "internal fun resolveDesktopNvidiaActivation("))
        self.assertEqual(2, body.count("nvidiaCapabilityForDisplayAdapter("))
        self.assertIn("buildNvidiaPlan(nvidiaCapabilityForDisplayAdapter(capability, displayAdapterLuidHex))", body)
        self.assertIn("buildNvidiaPlan(nvidiaCapabilityForDisplayAdapter(observedCapability, displayAdapterLuidHex))", body)

    def test_the_precedence_order_is_unchanged(self):
        body = collapse(balanced_body(self.text, "internal fun resolveDesktopNvidiaActivation("))
        order = ["if (mode == DesktopNvidiaMode.OFF)", "if (capability is NvidiaCapability.NotEvaluated)",
                 "if (capability is NvidiaCapability.Probing)", "if (capability is NvidiaCapability.Absent)",
                 "if (capability is NvidiaCapability.Unsupported)", "if (environment.gameLike)",
                 "if (plan.mobile && environment.onBattery)", "if (environment.remoteOrVirtualOutput)",
                 "if (failureRemembered)", "if (extendedRangeFailedForSource)", "if (guardGaveUpForSource)"]
        positions = [body.index(item) for item in order]
        self.assertEqual(sorted(positions), positions)

    def test_the_cross_adapter_step_is_applied_once_and_only_when_both_luids_are_readable(self):
        rules = source.read(RULES)
        body = collapse(balanced_body(rules, "internal fun nvidiaCapabilityForDisplayAdapter("))
        self.assertIn("if (NvidiaCapabilityNote.CROSS_ADAPTER in capable.notes) return capable", body)
        self.assertIn("if (parseNvidiaLuidHalves(displayAdapterLuidHex) == null) return capable", body)
        self.assertIn("if (parseNvidiaLuidHalves(capable.gpu.luidHex) == null) return capable", body)
        self.assertIn("if (luidMatches(capable.gpu.luidHex, displayAdapterLuidHex)) return capable", body)
        self.assertEqual(1, body.count(".stepDown()"))
        self.assertIn("notes = capable.notes + NvidiaCapabilityNote.CROSS_ADAPTER", body)


class FilesAreLfOnlyTest(unittest.TestCase):
    def test_every_file_of_this_slice_is_lf_only(self):
        tests = source.ST / "kotlin/com/bilipai/desktop"
        paths = [PROBE, MONITOR, RULES, ACTIVATION, HDR_DISPLAY, SESSION, Path(__file__),
                 tests / "nvidia/DesktopNvidiaActivationTest.kt", tests / "nvidia/DesktopNvidiaCapabilityMonitorTest.kt",
                 tests / "player/DesktopVideoEnhancementSessionTest.kt"]
        for path in paths:
            self.assertNotIn(b"\r", path.read_bytes(), path.name)


if __name__ == "__main__":
    unittest.main()
