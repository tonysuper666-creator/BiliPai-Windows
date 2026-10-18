"""Gate-preservation source checks for the NVIDIA auto-tier work (design section 13 and 16).

Every presentation gate stays strict. The auto-tier work only adds vetoes, so these checks pin:
the presentation tracker, the native frame token and bridge sources (frozen hashes), the single
`active = proved` assignment and every original conjunct of `proved`, the position and shape of the
additive LUID vetoes, the exact adapter option call, the preserved Python-pinned anchors, and the
lexical rules for the new package (no proof writer, no forbidden API, no shader-list option).

The hash fixture below was computed from the tree at slice S2 (the Native261 head plus the slices
committed so far). Only Root updates it, together with a deliberate change of one of these files. It
deliberately does NOT cover presentation-edits.json or DesktopVeyraPrivateComponent.kt, which the
Native261 line legitimately changes."""
import hashlib
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

REPO = source.REPO
PLAYER = source.K / "player"
MPV_PLAYER = PLAYER / "MpvPlayer.kt"
PLAYER_NVIDIA = PLAYER / "PlayerNvidiaVideo.kt"
RUNTIME_PROBE = PLAYER / "MpvRuntimeProbe.kt"
MAIN = source.K / "Main.kt"
JAVA_DXGI = REPO / "desktop/src/main/java/com/bilipai/desktop/player/DesktopWindowsDxgiAdapters.java"
VEYRA_COMPONENT = PLAYER / "DesktopVeyraPrivateComponent.kt"

# LF-normalised sha256 of the files that define the presentation proof and the native frame token.
GATE_FILE_SHA256 = {
    "desktop/src/main/kotlin/com/bilipai/desktop/player/MpvVeyraPresentation.kt":
        "ad63d19121d0e3951e5d47e371d9d1ffb0c682cf2ae5f2ca748f3f5f1b1e7e85",
    "desktop/native/mpv-rtx-bridge/vf_bilipai_rtx.c":
        "9e6e9bb5910d227eccde9f55f2d4c7b590588c5d160d26dc994a73f93b30bb28",
    "desktop/native/mpv-rtx-bridge/bilipai_rtx_mpv_bridge.c":
        "51b988ab9ce5a6ac5da1708e118675bf499a86c697417a55a61edd1493067014",
    "desktop/native/mpv-rtx-bridge/bilipai_rtx_mpv_bridge.h":
        "6c82888d4b1ab5175dd4a01d5ffbccfd0310e1fbee35b206bebdcd5ed1fe43bf",
    "desktop/native/mpv-rtx-bridge/bilipai_rtx_frame_provenance.h":
        "c8e33dbb31feb14b034579b3432ded44fe7d05995f15ff473e57d1d4cf763184",
}

# Members of DesktopWindowsDxgiAdapters.java that predate this work, hashed with trailing whitespace removed.
DXGI_EXISTING_MEMBER_SHA256 = {
    "public record Adapter(": "84d6152789fe592d95634e03ea1d112fcb78dac93823959803953d546a159a10",
    "public record Snapshot(": "6675bcfec315da9b5bae53629b4f1c0b339c75f893cb773257984b97bc22054e",
    "public static Snapshot probe()": "f75317c1aedbd69c4d1bb30a47a620ee3d2327021a436913d9e5a8073a70ebd8",
    "private static Snapshot unknown(String error)": "e18cc47095e06d7b4e44e63b2a0a5ac16e45222809ebb28506567b7d160e7819",
    "private interface Dxgi extends StdCallLibrary": "7d1f91276f300ea8f7db142d1a88a7f3017f766b2610d9ae310d71572cedc015",
    "private static Snapshot probeWindows()": "1f955e27330e8cfb502c5b8e94cbce5aabccfcef2cb5758c86a224056b8e2506",
    "private static String boundedDescription(Pointer description)":
        "380e4479cf21545488da60d3c5e3c358f52af2399f9734f14680ef8f6b54bfe5",
    "private static Function method(Pointer instance, int slot)":
        "07ec015bb7d6f84c06091ce14c8bcd2107dd75ed2fca3695ca9e26e01f90ae4e",
    "private static void release(Pointer instance)": "75e241e4efc5e2c896dcac329ba047f359c0b0b1fea79de76a21dcf9bbc81528",
}

# The expression `proved` had before the auto-tier work, with whitespace collapsed.
ORIGINAL_PROVED = (
    "shown != null && shown.width == outputWidth && shown.height == outputHeight && "
    "previous.error == null && previous.unavailableReason == null && filterPresent && "
    "state.value.firstVideoFrameReady && !state.value.ended && state.value.error == null && "
    "(!activeNvidiaOptions.hdr || (input.hdrDisplay.hdrEnabled && "
    "nvidiaHdrTarget(targetTransfer, targetPrimaries)))"
)

# NvidiaVideoOptions is the request identity (Request.matches and the install equality). Its fields never change.
OPTIONS_HEADER = (
    "data class NvidiaVideoOptions(val scale: Double = 1.0, val hdr: Boolean = false, "
    "val nativeResolutionProcessing: Boolean = false, val backend: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER, "
    "val qualityLevel: Int = 4, val srEnabled: Boolean = true, val intensityPercent: Int = 100) {"
)

# Literals and words that must never appear in a new package file (design sections 13 and 16 (c)).
NEW_FILE_FORBIDDEN = (
    "VeyraPresentationTracker", "proved =", "veyraReceipt", "glsl-shaders", "interpolation", "video-sync",
    "d3d11-flip", "target-colorspace-hint", "NvidiaVideoState", "mutableNvidiaVideo", "hdrConversionActive",
)

# Forbidden APIs for every new and changed source (design section 16 (d)).
FORBIDDEN_API = (
    "nvmlDeviceSet", "nvmlUnit", "NvAPI_", "RegSetValue", "RegCreateKey", "ChangeDisplaySettings",
    "SetPriorityClass", "SetThreadPriority", "powercfg", "D3D11CreateDevice", "D3D12CreateDevice", "DLSS",
)


def lf_sha256(path):
    data = Path(path).read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def collapse(text):
    return " ".join(text.split())


def trailing_clean_sha256(text):
    return hashlib.sha256("\n".join(line.rstrip() for line in text.split("\n")).encode("utf-8")).hexdigest()


def java_member(text, marker):
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    depth = 0
    index = text.index("{", start)
    while True:
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        index += 1


def kotlin_member(text, marker):
    """Source text from the marker through the closing brace of its body.

    Braces inside comments, string and character literals are ignored."""
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    index = text.index("{", start)
    depth = 0
    length = len(text)
    while index < length:
        pair = text[index:index + 2]
        char = text[index]
        if pair == "//":
            while index < length and text[index] != "\n":
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


def new_package_files():
    return sorted(source.KN.glob("*.kt"))


def changed_or_new_main_sources():
    return new_package_files() + [JAVA_DXGI, MPV_PLAYER, PLAYER_NVIDIA, RUNTIME_PROBE, MAIN]


class FrozenGateFilesTest(unittest.TestCase):
    def test_every_gate_file_is_byte_identical_after_line_ending_normalisation(self):
        for relative, expected in GATE_FILE_SHA256.items():
            self.assertEqual(expected, lf_sha256(REPO / relative), relative)

    def test_the_fixture_names_exactly_the_five_gate_files_and_not_the_native261_files(self):
        names = sorted(Path(relative).name for relative in GATE_FILE_SHA256)
        self.assertEqual(sorted([
            "MpvVeyraPresentation.kt", "vf_bilipai_rtx.c", "bilipai_rtx_mpv_bridge.c",
            "bilipai_rtx_mpv_bridge.h", "bilipai_rtx_frame_provenance.h"]), names)
        for relative in GATE_FILE_SHA256:
            self.assertNotIn("presentation-edits.json", relative)
            self.assertNotIn("DesktopVeyraPrivateComponent.kt", relative)

    def test_the_native_gate_files_carry_no_carriage_return(self):
        for relative in GATE_FILE_SHA256:
            self.assertNotIn(b"\r", (REPO / relative).read_bytes(), relative)

    def test_the_wire_constants_that_the_tracker_requires_are_unchanged(self):
        # peak=1000 and timeout=1000: the tracker requires 1000, and tier budgets never change it.
        component = source.read(VEYRA_COMPONENT)
        self.assertEqual(1, component.count(":peak=1000:timeout=1000\""))
        self.assertIn("peak=1000", component)
        self.assertIn("timeout=1000", component)

    def test_the_existing_dxgi_inventory_members_are_unchanged(self):
        text = source.read(JAVA_DXGI)
        for marker, expected in DXGI_EXISTING_MEMBER_SHA256.items():
            self.assertEqual(expected, trailing_clean_sha256(java_member(text, marker)), marker)


class ProofGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MPV_PLAYER)

    def test_active_is_assigned_from_proved_exactly_once_in_one_statement(self):
        self.assertEqual(1, self.text.count("active = proved"))
        self.assertIn(
            "observed.copy(active = proved, hdrConversionActive = proved && activeNvidiaOptions.hdr,\n"
            "                            nativeResolutionAttemptAccepted = false)", self.text)
        self.assertEqual(1, self.text.count("val proved = "))

    def test_proved_keeps_every_original_conjunct_and_adds_only_the_luid_conjunct_at_the_end(self):
        match = re.search(r"val proved = (.*?)\n\s+observed\.copy\(active = proved", self.text, re.S)
        self.assertIsNotNone(match)
        self.assertEqual(ORIGINAL_PROVED + " && luidAllowed", collapse(match.group(1)))

    def test_the_proof_luid_conjunct_can_only_tighten(self):
        text = self.text
        self.assertEqual(3, text.count("luidAllowed"), "definition, diagnostic and the one conjunct")
        self.assertIn(
            "val luidAllowed = shown == null ||\n"
            "                            nvidiaProofLuidAllowed(proofLuidExpectation, previous.gpuLuidHex, shown.adapterLuidHex)",
            text)
        self.assertIn("if (!luidAllowed && MpvRuntimeProbe.enabled) "
                      "MpvRuntimeProbe.recordChanged(MpvRuntimeProbeStage.NVIDIA_LUID_GATE, 2L)", text)
        self.assertIn("val proofLuidExpectation = gpuCapability.expectation()", text)
        probe = source.read(RUNTIME_PROBE)
        self.assertIn("NVIDIA_LUID_GATE,\n}", probe, "the new stage is appended last, so no ordinal moves")

    def test_the_veyra_observation_still_never_reports_active(self):
        text = source.read(PLAYER_NVIDIA)
        self.assertEqual(1, text.count("active = false, hdrConversionActive = false, nativeResolutionAttemptAccepted = false,"))
        observe = kotlin_member(text, "internal fun observeNvidiaVideo(")
        veyra = observe[:observe.index("// Pinned vf_d3d11vpp truncates")]
        self.assertIn("if (options.backend == NvidiaVideoBackend.VEYRA_CORE) {", veyra)
        self.assertIn("active = false, hdrConversionActive = false, nativeResolutionAttemptAccepted = false,", veyra)
        self.assertNotIn("active = true", observe)

    def test_the_request_identity_fields_are_unchanged(self):
        text = source.read(PLAYER_NVIDIA)
        start = text.index("data class NvidiaVideoOptions(")
        header = collapse(text[start:text.index(") {", start) + 3])
        self.assertEqual(OPTIONS_HEADER, header)

    def test_new_code_never_writes_the_active_flag(self):
        for path in new_package_files():
            code, _ = source.lex_kotlin(source.read(path))
            self.assertIsNone(re.search(r"(?<![A-Za-z0-9_])active\s*=(?!=)", code), path.name)
            self.assertNotIn("hdrConversionActive", code, path.name)


class LuidVetoShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MPV_PLAYER)
        cls.apply = kotlin_member(cls.text, "private fun applyNvidiaVideo(native: MpvNative, handle: Pointer, action: Action.NvidiaVideo)")

    def test_the_luid_veto_follows_the_vendor_check_and_precedes_the_texture_check(self):
        apply = self.apply
        vendor = apply.index('observed.gpuVendorId != 0x10de || observed.currentGpuContext != "d3d11"')
        expectation = apply.index("val luidExpectation = gpuCapability.expectation()")
        gate = apply.index("resolveNvidiaLuidGate(luidExpectation, observed.gpuLuidHex,")
        texture = apply.index("val maximum = input.maximumTextureDimension")
        self.assertLess(vendor, expectation)
        self.assertLess(expectation, gate)
        self.assertLess(gate, texture)
        self.assertEqual(1, apply.count("gpuCapability.expectation()"))

    def test_every_outcome_is_handled_and_none_can_proceed_unverified(self):
        apply = self.apply
        gate = apply[apply.index("when (luidGate) {"):apply.index("val maximum = input.maximumTextureDimension")]
        self.assertIn("is NvidiaLuidGateResult.Pass -> Unit", gate)
        self.assertIn("is NvidiaLuidGateResult.Pending -> { pendingNvidiaAction = action; return }", gate)
        self.assertIn("DesktopNvidiaStatusText.LUID_UNKNOWN, unavailable = true); return", gate)
        self.assertIn("DesktopNvidiaStatusText.LUID_MISMATCH, unavailable = true)", gate)
        self.assertEqual(1, gate.count("gpuCapability.invalidate()"), "only a mismatch invalidates the probe")
        self.assertLess(gate.index("LUID_MISMATCH"), gate.index("gpuCapability.invalidate()"))
        self.assertEqual(3, gate.count("return"))

    def test_the_veto_uses_only_the_players_own_logged_identity(self):
        apply = self.apply
        for expected in ("observed.gpuLuidHex", "observed.gpuDeviceId", "observed.gpuName",
                         "nvidiaDriverPublicX100FromText(observed.gpuDriverVersion)",
                         "gpuCapability.validateObservedLuid(luid,"):
            self.assertIn(expected, apply)

    def test_the_logged_block_updates_only_observation_fields_under_the_session_checks(self):
        receive = kotlin_member(self.text, "private fun receiveNvidiaMessage(native: MpvNative, handle: Pointer, prefix: String, text: String)")
        for branch in ("is NvidiaNativeMessage.DeviceLuid -> synchronized(lock) {",
                       "is NvidiaNativeMessage.DeviceDriver -> synchronized(lock) {"):
            start = receive.index(branch)
            body = receive[start:start + 260]
            self.assertIn("if (session === this && !closing.get()) mutableNvidiaVideo.update", body)
        self.assertIn("it.copy(gpuLuidHex = message.luidHex)", receive)
        self.assertIn("it.copy(gpuDriverVersion = message.version)", receive)
        self.assertIn("gpuDeviceId = message.deviceId", receive)

    def test_the_observed_identity_is_carried_by_the_install_and_cleared_with_the_session(self):
        text = self.text
        install = kotlin_member(text, "private fun installNvidiaVideo(options: NvidiaVideoOptions, owner: Long?, retainTargetMetadata: Boolean = true): Long")
        for expected in ("gpuDeviceId = previous.gpuDeviceId", "gpuLuidHex = previous.gpuLuidHex",
                         "gpuDriverVersion = previous.gpuDriverVersion", "adapterPinResult = previous.adapterPinResult"):
            self.assertIn(expected, install)
        self.assertIn("gpuDeviceId = null, gpuLuidHex = null, gpuDriverVersion = null, adapterPinResult = null) }", text)


class AdapterPinShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MPV_PLAYER)

    def test_the_adapter_option_value_is_computed_and_its_return_code_is_captured(self):
        text = self.text
        self.assertEqual(1, text.count('"d3d11-adapter"'))
        self.assertNotIn('"d3d11-adapter", "NVIDIA"', text, "the bare literal exists only in the legacy branch of the monitor")
        self.assertIn('if (adapterPin != null) adapterPinResult = native.mpv_set_option_string(handle, "d3d11-adapter", adapterPin)', text)
        self.assertIn("val adapterPin = gpuCapability.pinFor()", text)
        self.assertIn("gpuCapability.refreshForSession()", text)
        self.assertIn("it.copy(adapterPinResult = adapterPinRequestResult)", text)

    def test_the_pin_precedes_the_option_loop_and_the_core_initialisation_in_run(self):
        text = self.text
        run = kotlin_member(text, "private fun run()")
        pin = run.index('"d3d11-adapter"')
        self.assertLess(run.index("gpuCapability.refreshForSession()"), run.index("gpuCapability.pinFor()"))
        self.assertLess(run.index("gpuCapability.pinFor()"), pin)
        self.assertLess(pin, run.index("options.forEach { (name, value) ->"))
        self.assertLess(pin, run.index("native.mpv_initialize(handle)"))
        guarded = run[run.index("var adapterPinResult: Int? = null"):pin]
        self.assertIn("softwareTarget == null && com.sun.jna.Platform.isWindows()", guarded)

    def test_the_python_pinned_anchors_are_still_present(self):
        text = self.text
        run = kotlin_member(text, "private fun run()")
        self.assertIn("native.mpv_initialize(handle)", run)
        self.assertIn("perform(native, handle, Action.Load(", run)
        self.assertIn("applyNvidiaVideo(native, handle, action)", text)

    def test_the_shader_list_option_literal_is_absent_everywhere_it_is_pinned(self):
        self.assertNotIn("glsl-shaders", self.text)
        for path in changed_or_new_main_sources():
            self.assertNotIn("glsl-shaders", path.read_text(encoding="utf-8"), path.name)

    def test_the_player_takes_its_monitor_as_a_neutral_default_internal_property(self):
        self.assertIn("    internal val gpuCapability: DesktopNvidiaCapabilityMonitor = DesktopNvidiaCapabilityMonitor.neutral(),\n"
                      ") : AutoCloseable {", self.text)

    def test_the_application_passes_the_process_monitor_after_warming_it_up(self):
        main = source.read(MAIN)
        warm = main.index("DesktopNvidiaCapabilityMonitor.process().also { it.warmUp() }")
        build = main.index("MpvPlayer(veyraComponent = veyraComponent, gpuCapability = gpuCapability)")
        self.assertLess(warm, build)
        self.assertEqual(1, main.count("DesktopNvidiaCapabilityMonitor.process()"))


class ObservedLogLinesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(PLAYER_NVIDIA)

    def test_the_new_messages_exist_with_bounded_digit_only_patterns(self):
        text = self.text
        self.assertIn("data class DeviceVendor(val vendorId: Int, val deviceId: Int? = null) : NvidiaNativeMessage", text)
        self.assertIn("data class DeviceLuid(val luidHex: String) : NvidiaNativeMessage", text)
        self.assertIn("data class DeviceDriver(val version: String) : NvidiaNativeMessage", text)
        self.assertIn('Regex("LUID: ([0-9a-fA-F]{16})")', text)
        self.assertIn('Regex("Driver version: ([0-9]{1,5}\\\\.[0-9]{1,5}\\\\.[0-9]{1,5}\\\\.[0-9]{1,5})")', text)
        self.assertIn('Regex("Device ID: ([0-9a-fA-F]{4}):([0-9a-fA-F]{4}) \\\\(rev [0-9a-fA-F]{2}\\\\)")', text)

    def test_the_new_lines_live_inside_the_same_prefix_whitelist_and_length_bound(self):
        parse = kotlin_member(self.text, "internal fun parseNvidiaNativeMessage(prefix: String, text: String): NvidiaNativeMessage?")
        self.assertIn("if (text.length > 1024) return null", parse)
        block = parse[parse.index('if (prefix == "vo/gpu/d3d11" || prefix == "vo/gpu-next/d3d11") {'):]
        for expected in ("Device Name: ", "Device ID: ", "LUID: ", "Driver version: "):
            self.assertIn(expected, block)
        before = parse[:parse.index('if (prefix == "vo/gpu/d3d11" || prefix == "vo/gpu-next/d3d11") {')]
        for expected in ("LUID: ", "Driver version: ", "Device ID: "):
            self.assertNotIn(expected, before)

    def test_the_new_state_fields_default_to_unknown(self):
        text = self.text
        for field in ("gpuDeviceId: Int? = null", "gpuLuidHex: String? = null",
                      "gpuDriverVersion: String? = null", "adapterPinResult: Int? = null"):
            self.assertIn("val " + field, text)


class NewPackageLexicalRulesTest(unittest.TestCase):
    def test_the_new_package_exists_and_has_the_expected_files(self):
        names = {path.name for path in new_package_files()}
        for expected in ("DesktopNvidiaGpuCapability.kt", "DesktopNvidiaTierProfile.kt", "DesktopNvidiaActivation.kt",
                         "DesktopNvidiaStatusText.kt", "DesktopWindowsGpuProbe.kt", "DesktopNvidiaCapabilityMonitor.kt"):
            self.assertIn(expected, names)

    def test_every_new_file_stays_in_its_package_and_directory(self):
        for path in new_package_files():
            self.assertTrue(source.read(path).startswith("package com.bilipai.desktop.nvidia\n"), path.name)

    def test_new_files_never_reference_the_proof_writers_or_the_forbidden_options(self):
        for path in new_package_files():
            text = source.read(path)
            for forbidden in NEW_FILE_FORBIDDEN:
                self.assertNotIn(forbidden, text, "%s: %s" % (path.name, forbidden))

    def test_no_forbidden_api_appears_in_any_new_or_changed_source(self):
        for path in changed_or_new_main_sources():
            text = path.read_text(encoding="utf-8")
            for forbidden in FORBIDDEN_API:
                self.assertNotIn(forbidden, text, "%s: %s" % (path.name, forbidden))

    def test_the_probe_stays_read_only_and_uses_only_the_jna_core(self):
        for name in ("DesktopWindowsGpuProbe.kt", "DesktopNvidiaCapabilityMonitor.kt"):
            code, _ = source.lex_kotlin(source.read(source.KN / name))
            self.assertNotIn("com.sun.jna.platform", code, name)
            self.assertNotIn("ProcessBuilder", code, name)
            self.assertNotIn("Runtime.getRuntime", code, name)
            self.assertNotIn("nvidia-smi", code, name)
            self.assertNotIn("nvml", code.lower(), name)

    def test_all_new_and_changed_files_use_lf_only(self):
        for path in changed_or_new_main_sources():
            self.assertNotIn(b"\r", path.read_bytes(), path.name)
        for path in sorted(Path(__file__).resolve().parent.glob("*.py")):
            self.assertNotIn(b"\r", path.read_bytes(), path.name)
        tests = source.ST / "kotlin/com/bilipai/desktop"
        for path in [tests / "nvidia/DesktopNvidiaCapabilityMonitorTest.kt", tests / "player/PlayerNvidiaVideoTest.kt",
                     tests / "player/PlayerNvidiaActorWithdrawalTest.kt", tests / "player/DesktopWindowsDxgiAdaptersTest.kt"]:
            self.assertNotIn(b"\r", path.read_bytes(), path.name)

    def test_the_monitor_never_references_the_player_package_state_it_could_write(self):
        code, _ = source.lex_kotlin(source.read(source.KN / "DesktopNvidiaCapabilityMonitor.kt"))
        player_imports = re.findall(r"^import com\.bilipai\.desktop\.player\.(\w+)", source.read(source.KN / "DesktopNvidiaCapabilityMonitor.kt"), re.M)
        self.assertEqual(["DesktopWindowsDxgiAdapters"], player_imports)
        self.assertNotIn("MpvPlayer", code)


if __name__ == "__main__":
    unittest.main()
