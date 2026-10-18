"""Source-shape checks for the activation gate in the enhancement session (design 7.3, 8, 11 and 16; slice S3).

The Session is the single seam between the GPU capability monitor and the native request. These checks pin
where the gate sits in the decision ladder, that the whole decision is guarded, that the new state fields are
all defaulted and carried by every construction, that `available` is combined with the capability, that the
presentation proof is never written from this file, and that the Python-pinned anchors of the controls stay.
Later slices extend this file with their own seams (the guard, the retry attempt, the environment poll)."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

SESSION = source.K / "player/DesktopVideoEnhancementSession.kt"
CONTROLS = source.K / "ui/DesktopVideoEnhancementControls.kt"
SHELL = source.K / "DesktopShell.kt"
CATALOG = source.K / "settings/DesktopWindowsSettingsCatalog.kt"

# Members of the session that the design keeps untouched, with whitespace collapsed.
CLEAR_OWNED = (
    "private fun clearOwnedLocked() { val previous = owned ?: return owned = null clearing = null "
    "val receipt = player.clearNvidiaVideoEnhancementWithReceipt(previous.token) ?: return "
    "val source = receipt.source ?: return "
    "if (source.sourceVersion == previous.request.source.sourceVersion && source.source == previous.request.source.source && "
    "owns(source, previous.request.epoch)) clearing = Clearing(source, previous.request.epoch, receipt.configurationVersion) }"
)
REQUEST_MATCHES = (
    "fun matches(other: Request) = epoch == other.epoch && options == other.options && "
    "source.sourceVersion == other.source.sourceVersion && source.source == other.source.source && "
    "nativeIdentity == other.nativeIdentity"
)
OWNS = (
    "private fun owns(source: OwnedPlaybackSourceSnapshot, epoch: Long): Boolean = !closed.get() && "
    "epoch == sessionEpoch() && player.ownsSourceSnapshot(source) && "
    "(source.source.authorizationReceipt?.accountEpoch ?: source.source.primaryAccountEpoch ?: epoch) == epoch"
)

NEW_STATE_FIELDS = {
    "activation": "DesktopVideoEnhancementActivation",
    "gpuSummary": "String?",
    "requestedQualityLevel": "Int?",
    "steppedDown": "Boolean",
    "proofDelayed": "Boolean",
    "route": "NvidiaVideoBackend",
    "tierWord": "String?",
    "notice": "String?",
}
INTERNAL_TYPE_NAMES = (
    "NvidiaPlan", "NvidiaTier", "NvidiaCapability", "DesktopNvidiaActivationState", "DesktopNvidiaGate",
    "DesktopNvidiaStatusKind", "DesktopNvidiaEnvironment", "NvidiaGuardLimits",
)


def collapse(text):
    return " ".join(text.split())


def skip_non_code(text, index):
    """Index after a comment, string or character literal starting at index, or index itself when none starts there."""
    pair = text[index:index + 2]
    char = text[index]
    if pair == "//":
        while index < len(text) and text[index] != "\n":
            index += 1
        return index
    if pair == "/*":
        return text.index("*/", index + 2) + 2
    if char in "\"'":
        index += 1
        while text[index] != char:
            index += 2 if text[index] == "\\" else 1
        return index + 1
    return index


def balanced(text, open_index):
    """Index of the bracket that closes the one at open_index; comments, strings and characters are skipped."""
    pairs = {"(": ")", "{": "}"}
    opening = text[open_index]
    closing = pairs[opening]
    depth = 0
    index = open_index
    while index < len(text):
        moved = skip_non_code(text, index)
        if moved != index:
            index = moved
            continue
        char = text[index]
        if char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise AssertionError("unbalanced bracket")


def member(text, marker):
    """Source from the marker through the closing brace of its body."""
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    return text[start:balanced(text, text.index("{", start)) + 1]


class SessionGateShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(SESSION)
        cls.code, cls.literals = source.lex_kotlin(cls.text)
        cls.apply_locked = member(cls.text, "private fun applyLocked(input: Input)")

    def test_the_gate_sits_after_the_switch_bypass_and_before_every_source_check(self):
        body = self.apply_locked
        switch = body.index("if (!input.settings.enabled) {")
        gate = body.index("if (frameGate.bypassesBeforeSourceChecks) {")
        current = body.index("if (!current) {")
        self.assertLess(switch, gate)
        self.assertLess(gate, current)
        # Every other ladder step comes after the gate.
        for later in ("if (!input.settings.started && !input.settings.pip) {", "if (input.frame.audioOnly) {",
                      "val sharedCoreAvailable = ", "val decision = resolveDesktopNvidiaVideoDecision("):
            self.assertLess(gate, body.index(later), later)
        block = body[gate:current]
        self.assertIn("bypassUnavailable(Anime4KBypassReason.NONE,", block)
        self.assertIn("return", block)
        self.assertEqual(1, body.count("bypassesBeforeSourceChecks"))

    def test_bypass_unavailable_exists_and_marks_the_feature_unavailable_with_the_one_reason_line(self):
        body = self.apply_locked
        bypass = body.index("fun bypass(reason: Anime4KBypassReason, text: String, statusKind: DesktopNvidiaStatusKind? = null, line: String? = null) {")
        helper = body.index("fun bypassUnavailable(reason: Anime4KBypassReason, line: String) {")
        self.assertLess(bypass, helper)
        self.assertLess(helper, body.index("if (!input.settings.enabled) {"))
        helper_body = member(body, "fun bypassUnavailable(reason: Anime4KBypassReason, line: String)")
        compact = collapse(helper_body)
        self.assertIn("bypass(reason, line, line = line)", compact)
        self.assertIn("it.copy(available = false, unavailableReason = line)", compact)

    def test_the_request_build_is_guarded_and_an_invalid_request_bypasses_the_video(self):
        body = self.apply_locked
        build = body.index("val request = try {")
        previous = body.index("val previous = owned")
        self.assertLess(build, previous)
        block = body[build:previous]
        self.assertIn("optionsFor(decision, backend, plan, NvidiaGuardLimits.None)", block)
        self.assertIn(".requireValid()", block)
        self.assertIn("catch (_: IllegalArgumentException) {", block)
        self.assertIn("bypassUnavailable(Anime4KBypassReason.NONE, failedThisVideoLine(frameGate)); return", block)
        # The request is compared with the owned one exactly as before.
        self.assertIn("previous.request.matches(request)", body)

    def test_the_whole_decision_is_guarded_and_can_never_end_the_collector(self):
        apply = member(self.text, "private fun apply(input: Input): Unit = synchronized(ownershipLock)")
        compact = collapse(apply)
        self.assertIn("try { applyLocked(input) }", compact)
        cancelled = compact.index("catch (cancelled: CancellationException) { throw cancelled }")
        runtime = compact.index("catch (failure: RuntimeException) { failApplyLocked(failure) }")
        self.assertLess(cancelled, runtime)
        fail = member(self.text, "private fun failApplyLocked(failure: RuntimeException)")
        self.assertIn("java.util.logging.Level.WARNING", fail)
        self.assertIn("try { clearOwnedLocked() } catch (_: RuntimeException) { owned = null; clearing = null }", collapse(fail))
        self.assertNotIn("throw ", fail)
        # The collector still runs the one function through the one reference.
        self.assertIn(".distinctUntilChanged().collect(::apply)", self.text)

    def test_the_resolver_receives_the_effective_hdr_mode_and_never_a_masked_decision(self):
        body = self.apply_locked
        call = body[body.index("val decision = resolveDesktopNvidiaVideoDecision("):body.index("if (!decision.needsProcessing) {")]
        self.assertIn("hdrMode = nvidiaEffectiveHdrMode(input.settings.preferences, plan)", call)
        self.assertNotIn("hdrMode = input.settings.preferences.hdrMode", call)

    def test_legacy_keeps_today_exactly_when_the_capability_was_never_evaluated(self):
        gate = member(self.text, "internal fun resolveDesktopNvidiaGate(")
        compact = collapse(gate)
        evaluated = compact.index("if (capability is NvidiaCapability.NotEvaluated) { DesktopNvidiaActivationState.Legacy }")
        resolved = compact.index("resolveDesktopNvidiaActivation(")
        self.assertLess(evaluated, resolved)
        bypass = collapse(member(self.apply_locked, "fun bypass(reason: Anime4KBypassReason, text: String"))
        self.assertIn('frameGate.legacy -> native.gpuName?.let { "$it；$text" } ?: text', bypass)
        observed = collapse(member(self.text, "private fun observedState(state: DesktopVideoEnhancementState, native: NvidiaVideoState)"))
        self.assertIn("if (gate.legacy) return shown", observed)
        status_text = self.text[self.text.index("fun statusText(legacyText: String"):]
        self.assertIn("if (legacy) legacyText else lineFor(legacyText, statusKind, routeInUse)", collapse(status_text[:400]))

    def test_available_is_anded_with_the_capability_on_both_routes(self):
        self.assertIn("native.gpuVendorId == 0x10de && native.currentGpuContext?.startsWith(\"d3d11\") == true &&\n"
                      "            frameGate.capabilityUsable", self.apply_locked)
        projection = member(self.text, "internal fun DesktopVideoEnhancementState.withNvidiaObservation(")
        self.assertIn("capabilityAvailable: Boolean = true", projection)
        compact = collapse(projection)
        self.assertIn("copy(available = native.veyraAvailable && capabilityAvailable,", compact)
        self.assertIn("copy(available = capabilityAvailable && native.unavailableReason == null &&", compact)
        self.assertEqual(2, self.text.count("withNvidiaObservation("), "the definition and the one call")
        self.assertIn("state.withNvidiaObservation(native, gate.capabilityUsable)", self.text)

    def test_the_failed_probe_feeds_the_monitor_with_the_players_own_identity_before_observation(self):
        init = self.text[self.text.index("    init {"):self.text.index("    /** Missing BV/PGC/live/story IDs")]
        collector = init[init.index("player.nvidiaVideoState.collect { native ->"):]
        self.assertLess(collector.index("confirmObservedGpu(native)"), collector.index("observeNative(native)"))
        body = collapse(member(self.text, "private fun confirmObservedGpu(native: NvidiaVideoState)"))
        self.assertIn("if (monitor.capability.value !is NvidiaCapability.Unknown) return", body)
        self.assertIn("val luid = native.gpuLuidHex ?: return", body)
        self.assertIn("monitor.validateObservedLuid(luid, native.gpuDeviceId, native.gpuName,", body)
        self.assertIn("catch (_: RuntimeException)", body)

    def test_the_policy_flow_carries_capability_environment_and_the_observed_gpu(self):
        self.assertIn("private data class Policy(val capability: NvidiaCapability, val environment: DesktopNvidiaEnvironment,", self.text)
        self.assertIn("val policy: Policy) {", self.text)
        init = self.text[self.text.index("    init {"):self.text.index("    /** Missing BV/PGC/live/story IDs")]
        self.assertIn("combine(monitor.capability, monitor.environment, monitor.observed)", init)
        self.assertIn("combine(preferenceSnapshots, hostStarted, pip, policy)", init)
        # The five-flow input combine is unchanged.
        self.assertIn("combine(settings, label, frame, player.videoOutput, target)", init)
        self.assertIn("val monitor = player.gpuCapability", init)

    def test_redetect_reaches_the_monitor_and_the_shell_passes_the_session_action(self):
        body = collapse(member(self.text, "fun redetectGpu()"))
        self.assertIn("if (closed.get()) return", body)
        self.assertIn("player.gpuCapability.redetect()", body)
        shell = source.read(SHELL)
        self.assertEqual(1, shell.count("enhancement?.let { session -> session::redetectGpu }"))
        controls = source.read(CONTROLS)
        self.assertIn("val redetectGpu: (() -> Unit)? = null,", controls)

    def test_the_untouched_members_are_untouched(self):
        self.assertIn(CLEAR_OWNED, collapse(self.text))
        self.assertIn(REQUEST_MATCHES, collapse(self.text))
        self.assertIn(OWNS, collapse(self.text))
        observe = collapse(member(self.text, "private fun observeNative(native: NvidiaVideoState): Unit = synchronized(ownershipLock)"))
        self.assertIn("val current = owned if (current == null) observeClearingLocked(native) else observeNativeLocked(native, current)", observe)
        locked = collapse(member(self.text, "private fun observeNativeLocked(native: NvidiaVideoState, current: Owned)"))
        self.assertIn("if (owned !== current || !owns(current.request.source, current.request.epoch)) return", locked)
        self.assertIn("if (native.configurationVersion != current.token || native.sourceVersion != current.request.source.sourceVersion) {", locked)


class SessionStateShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(SESSION)
        cls.code, _ = source.lex_kotlin(cls.text)
        start = cls.text.index("data class DesktopVideoEnhancementState(")
        cls.header = cls.text[start:balanced(cls.text, cls.text.index("(", start)) + 1]

    def test_every_new_field_is_defaulted_and_typed_with_a_public_type(self):
        fields = dict(re.findall(r"^\s+val (\w+): ([^=\n]+?) = ", self.header, re.M))
        for name, kind in NEW_STATE_FIELDS.items():
            self.assertEqual(kind, fields.get(name), name)
        for name in re.findall(r"^\s+val (\w+): ", self.header, re.M):
            self.assertIn(name, fields, "%s has no default" % name)
        for internal in INTERNAL_TYPE_NAMES:
            self.assertNotIn(internal, self.header, internal)
        self.assertIn("val activation: DesktopVideoEnhancementActivation = DesktopVideoEnhancementActivation.LEGACY,", self.header)
        for default in ("val steppedDown: Boolean = false,", "val proofDelayed: Boolean = false,",
                        "val gpuSummary: String? = null,", "val requestedQualityLevel: Int? = null,",
                        "val tierWord: String? = null,", "val notice: String? = null,",
                        "val route: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER,"):
            self.assertIn(default, self.header)

    def test_the_public_activation_enum_mirrors_every_internal_state(self):
        enum = re.search(r"\nenum class DesktopVideoEnhancementActivation \{(.*?)\n\}", self.text, re.S)
        self.assertIsNotNone(enum)
        names = set(re.findall(r"[A-Z_]{3,}", enum.group(1)))
        self.assertEqual({"LEGACY", "OFF", "PROBING", "NO_NVIDIA", "UNSUPPORTED", "UNIDENTIFIED", "STANDBY", "PAUSED_GAME",
                          "PAUSED_BATTERY", "PAUSED_REMOTE", "FAILED_REMEMBERED", "FAILED_THIS_VIDEO",
                          "FAILED_EXTENDED_RANGE", "ALLOWED"}, names)
        activation = (source.KN / "DesktopNvidiaActivation.kt").read_text(encoding="utf-8")
        mirrored = re.search(r"fun DesktopNvidiaActivationState\.publicKind\(\)(.*?)\n\}", self.text, re.S)
        self.assertIsNotNone(mirrored)
        interfaces = set(re.findall(r"is DesktopNvidiaActivationState\.(\w+) ->", mirrored.group(1)))
        declared = set(re.findall(r"^    (?:data class|data object) (\w+)", activation, re.M))
        self.assertEqual(declared, interfaces)
        self.assertEqual(14, len(interfaces))

    def test_the_five_constructions_exist_and_the_three_decorated_ones_carry_the_gate(self):
        constructions = [match.start() for match in re.finditer(r"DesktopVideoEnhancementState\(", self.code)]
        # One declaration and five constructions: the initial state, bypass, source switched, pending, close.
        self.assertEqual(6, len(constructions))
        apply_locked = member(self.text, "private fun applyLocked(input: Input)")
        decorated = 0
        for match in re.finditer(r"DesktopVideoEnhancementState\(", apply_locked):
            end = balanced(apply_locked, match.end() - 1)
            self.assertTrue(apply_locked[end + 1:].startswith(".withGate(frameGate, "), apply_locked[match.start():match.start() + 60])
            decorated += 1
        self.assertEqual(3, decorated)
        # The initial state and the closed state keep their defaults.
        self.assertIn("private val mutableState = MutableStateFlow(DesktopVideoEnhancementState(\n        requested = ", self.text)
        close = member(self.text, "override fun close()")
        self.assertIn('statusText = "视频增强会话已关闭")', close)
        self.assertNotIn("withGate", close)

    def test_the_gate_carries_the_facts_the_state_publishes(self):
        carried = self.text[self.text.index("internal fun DesktopVideoEnhancementState.withGate("):]
        compact = collapse(carried[:400])
        for expected in ("activation = gate.kind", "gpuSummary = gate.gpuSummary", "route = routeInUse",
                         "tierWord = gate.tierWordFor(routeInUse)", "notice = gate.notice"):
            self.assertIn(expected, compact)
        self.assertIn("desktopNvidiaTierWord(plan?.tier, routeInUse, TIER_WORD_ENABLED)", self.text)

    def test_the_tier_word_and_requested_text_stay_behind_their_switches(self):
        self.assertNotIn("TIER_WORD_ENABLED: Boolean", self.code)
        self.assertEqual(1, len(re.findall(r"TIER_WORD_ENABLED\)", self.code)))
        for literal in re.findall(r'"([^"\n]*)"', self.text):
            for tier_word in ("极致", "均衡", "轻量"):
                self.assertNotIn(tier_word, literal)
            self.assertNotIn("已请求", literal)


class SessionProofGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(SESSION)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_this_file_never_writes_the_presentation_proof_or_touches_the_tracker(self):
        for forbidden in ("VeyraPresentationTracker", "proved", "veyraReceipt", "MpvVeyraPresentation", "glsl-shaders",
                          "interpolation", "video-sync", "d3d11-flip", "target-colorspace-hint"):
            self.assertNotIn(forbidden, self.text, forbidden)
        # The only values ever assigned to an active flag here are a projection of the player's own state or false.
        for value in re.findall(r"(?<![A-Za-z0-9_])active\s*=(?!=)\s*([^,)\n]+)", self.code):
            self.assertIn(value.strip(), ("native.active", "false"))

    def test_the_proof_delay_only_reads_the_player_and_only_changes_wording(self):
        body = re.search(r"private fun proofDelayedFor\(native: NvidiaVideoState, current: Owned\): Boolean \{(.*?)\n    \}", self.text, re.S)
        self.assertIsNotNone(body)
        compact = collapse(body.group(1))
        self.assertIn("!gate.legacy && native.backend == NvidiaVideoBackend.VEYRA_CORE && native.veyraSubmitted && "
                      "!native.active && native.error == null && native.unavailableReason == null", compact)
        self.assertNotIn("mutableState", compact)
        self.assertNotIn("player.", compact)
        self.assertIn("const val PROOF_DELAY_MILLIS = 8_000L", self.text)
        # The delayed flag selects a wording kind and nothing else.
        live = self.text[self.text.index("private fun liveStatusLine("):]
        self.assertIn("if (shown.proofDelayed) DesktopNvidiaStatusKind.PROOF_DELAYED else DesktopNvidiaStatusKind.SUBMITTED", collapse(live))

    def test_the_session_never_counts_submitted_or_throughput_as_proof(self):
        for forbidden in ("headroom", "throughput", "frame-drop", "vo-delayed"):
            self.assertNotIn(forbidden, self.text)

    def test_no_forbidden_api_and_lf_only(self):
        for forbidden in ("nvmlDeviceSet", "NvAPI_", "RegSetValue", "RegCreateKey", "ChangeDisplaySettings",
                          "SetPriorityClass", "SetThreadPriority", "powercfg", "D3D11CreateDevice", "D3D12CreateDevice", "DLSS"):
            for path in (SESSION, CONTROLS, CATALOG, source.K / "player/DesktopNvidiaVideoPreferences.kt",
                         source.K / "plugins/DesktopVideoEnhancementConfiguration.kt"):
                self.assertNotIn(forbidden, path.read_text(encoding="utf-8"), "%s: %s" % (path.name, forbidden))
        for path in (SESSION, CONTROLS, SHELL, CATALOG, source.K / "player/DesktopNvidiaVideoPreferences.kt",
                     source.K / "plugins/DesktopVideoEnhancementConfiguration.kt",
                     source.ST / "kotlin/com/bilipai/desktop/player/DesktopVideoEnhancementSessionTest.kt",
                     source.ST / "kotlin/com/bilipai/desktop/plugins/DesktopVideoEnhancementConfigurationTest.kt"):
            self.assertNotIn(b"\r", path.read_bytes(), path.name)


class ControlsAnchorTest(unittest.TestCase):
    """The Python-pinned anchors of the controls and the rule that the player-bar entry is never hidden."""

    @classmethod
    def setUpClass(cls):
        cls.text = source.read(CONTROLS)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_the_pinned_player_slot_is_intact(self):
        start = self.text.index("internal fun DesktopWindowsNvidiaPlayerSlot(menuEnabled: Boolean, onMenuExpandedChanged: (Boolean) -> Unit) {")
        end = self.text.index("\n@Composable", start)
        slot = self.text[start:end]
        for expected in ("LocalDesktopWindowsVideoEnhancement.current", "binding.state.collectAsState()",
                         "binding.configuration::setAutomaticEnabled",
                         "DesktopVideoEnhancementCompactSlot(showStatus = false, menuEnabled = menuEnabled,"):
            self.assertIn(expected, slot)
        self.assertNotIn("DesktopVideoEnhancementSession(", slot)
        self.assertNotIn("DesktopVideoEnhancementConfiguration(", slot)

    def test_the_two_settings_content_overloads_and_the_compact_literal_are_intact(self):
        self.assertIn("internal fun DesktopWindowsVideoEnhancementSettingsContent(showProcessingQuality: Boolean = true) {", self.text)
        self.assertIn("internal fun DesktopWindowsVideoEnhancementSettingsContent(configuration: DesktopVideoEnhancementConfiguration,\n"
                      "    showProcessingQuality: Boolean = true) {", self.text)
        self.assertIn("DesktopWindowsVideoEnhancementSettingsContent(configuration, showProcessingQuality = false)", self.text)
        self.assertIn("fun DesktopVideoEnhancementControls(state: DesktopVideoEnhancementState,\n"
                      "    configuration: DesktopVideoEnhancementConfiguration, onToggle: (Boolean) -> Unit, onSettings: (() -> Unit)? = null) {",
                      self.text)
        self.assertIn("fun DesktopVideoEnhancementSettingsDialog(configuration: DesktopVideoEnhancementConfiguration, onDismiss: () -> Unit) {", self.text)
        self.assertEqual(1, self.text.count('contentDescription = "NVIDIA 增强详情"'))
        self.assertIn('title = "NVIDIA 自动增强"', self.text)

    def test_the_player_bar_entry_is_never_hidden_by_the_gate(self):
        controls = member(self.text, "fun DesktopVideoEnhancementControls(state: DesktopVideoEnhancementState,")
        compact = controls[:controls.index("        return\n")]
        box = compact.index("        Box {")
        button = compact.index("AppTextButton(onClick = {")
        self.assertEqual("", compact[box + len("        Box {"):button].strip())
        self.assertIn("val canExpand = menuEnabled && state.sourceVersion > 0 && owner?.isShowing == true", compact)
        self.assertIn("enabled = canExpand", compact)
        # The entry reads no activation, so no gate state can hide it; only the label changes.
        self.assertNotIn("activation", compact)
        self.assertEqual(1, self.code.count("desktopVideoEnhancementCapable(state)"), "only the switch subtitle asks")

    def test_the_compact_label_gains_gate_branches_after_the_error_and_off_branches(self):
        label = member(self.text, "internal fun desktopVideoEnhancementCompactLabel(state: DesktopVideoEnhancementState,")
        compact = collapse(label)
        error = compact.index('configurationError != null || state.error != null -> "异常"')
        off = compact.index('!enabled -> "关闭"')
        gate = compact.index("gateLabel != null -> gateLabel")
        unavailable = compact.index('state.unavailableReason != null -> "不可用"')
        self.assertLess(error, off)
        self.assertLess(off, gate)
        self.assertLess(gate, unavailable)
        gate_label = member(self.text, "private fun desktopVideoEnhancementGateLabel(state: DesktopVideoEnhancementState): String?")
        words = {
            "PROBING": "检测中", "NO_NVIDIA": "不可用", "UNSUPPORTED": "不可用", "UNIDENTIFIED": "不可用",
            "PAUSED_GAME": "已暂停", "PAUSED_BATTERY": "已暂停", "PAUSED_REMOTE": "已暂停",
            "FAILED_REMEMBERED": "已回退", "FAILED_THIS_VIDEO": "已回退", "FAILED_EXTENDED_RANGE": "已回退",
        }
        flat = collapse(gate_label)
        for state_name, word in words.items():
            self.assertIn("DesktopVideoEnhancementActivation." + state_name, flat)
            self.assertIn('"%s"' % word, flat)
        self.assertIn('else -> if (state.steppedDown) "已回退" else null', flat)

    def test_route_aware_controls_keep_saved_values_and_disable_only_what_the_route_cannot_apply(self):
        body = member(self.text, "private fun DesktopWindowsVideoEnhancementBody(")
        compact = collapse(body)
        self.assertIn("val detecting = state.activation != DesktopVideoEnhancementActivation.LEGACY", compact)
        self.assertIn("val routeNotCore = (if (detecting) state.route else state.backend) != NvidiaVideoBackend.VEYRA_CORE", compact)
        self.assertIn("val strengthEditable = enabled && (!detecting || (!componentMissing && !routeNotCore))", compact)
        self.assertIn("val qualityEditable = enabled && preferences.srEnabled && (!detecting || (!componentMissing && !routeNotCore))", compact)
        self.assertIn("enabled = strengthEditable)", compact)
        self.assertIn("enabled = qualityEditable)", compact)
        # The content choice is never gated by the route.
        content_start = compact.index('DesktopWindowsSettingsChoice("增强内容", preferences.content,')
        content_end = compact.index('DesktopWindowsSettingsChoice("效果强度"', content_start)
        self.assertIn("enabled = enabled)", compact[content_start:content_end])
        self.assertNotIn("Editable", compact[content_start:content_end])
        # The redetect button needs a session action and detection on; it is offered only where it helps.
        self.assertIn("redetectGpu != null && detecting && (showProcessingQuality ||", compact)
        self.assertIn("DesktopVideoEnhancementActivation.FAILED_REMEMBERED", compact)
        self.assertIn("DesktopVideoEnhancementActivation.UNIDENTIFIED", compact)


if __name__ == "__main__":
    unittest.main()
