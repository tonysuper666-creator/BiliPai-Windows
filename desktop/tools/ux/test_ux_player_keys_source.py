"""Slice V6 source checks: the window-level player shortcuts of the ordinary-video page, and step 0, the vendored key map. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_player_keys_source.py

What slice V6 is (spec 6.5 and 6.6, release R1): ui/DesktopWindowsVideoShortcutInput.kt, the router, is composed with the existing
DesktopWindowsVideoKeyInput into the one leaf slot of the window key input (existing input first, router second); the pure part, ui/DesktopPlayerShortcutEffects.kt,
holds the key names, one effect per action kind, the chapter and speed steps and the focus rule; the leaf gets nine small anchored hunks (V6_HUNKS in
test_ux_leaf_layout_binding.py, recorded as hunk RH4 part V6). It works in the LEGACY layout too, which is the point: the tooltips of the restyled bar name
these keys. Frame step stays omitted until an MpvPlayer API exists.

  VendoredExtractorTest   step 0. ux_vendored_player_keys.py parses the REAL resolvePlayerKeyAction, resolves each of its 46 bindings through a transliteration of
                          the router resolver and sorts it into kept, changed, not-taken or dropped. A binding that is none of "kept" and not listed fails; a listed
                          difference that no binding has any more fails; so does a status that does not match what the router does. The table is emitted into the
                          contract (player_keys.vendored), so DesktopPlayerShortcutEffectsTest runs the real vendored resolver and the router side by side
  KeyCodesTest            the AWT virtual key codes of the Kotlin block equal the contract and an independent table typed from java.awt.event.KeyEvent; every key of
                          the shortcut tables can be pressed; the zoom keys are the ones the zoom decoder reads
  PureFunctionsTest       chapter jump, speed step and focus rule: the mirror equals an oracle written from the prose over sweeps; the Kotlin lines are pinned
  EffectsTest             one effect per action kind (the `when` is exhaustive), mirror against oracle on a fake player over every combination, the leaf call
  RouterTest              the held-press state machine of the router: scenarios, the Kotlin lines, the mirror against the scenarios
  LeafWiringTest          the nine V6 hunks of the leaf: anchored, reversible to the V2 state byte for byte, small; the one slot, the shared gate, the page-entry focus,
                          the SideEffect; the files that must not change (the existing input, the key fallback, the vendored map) are byte-pinned
  ReleaseCouplingTest     every key that a tooltip of the restyled bar names is bound to an effect the leaf wires
  JvmTestsTest            the two JVM test classes exist, run with no window, GPU or network and read the byte-identical contract copy
  HygieneTest             purity of the pure file, the program rules for both new files, names declared once, plain LF
  MutationTest            each check fails on an edited copy of the real text

What is not proved. That the Kotlin compiles or runs: DesktopWindowsVideoShortcutInputTest and DesktopPlayerShortcutEffectsTest are the proof of the real code,
and what the real window does with the keys (focus of the mpv child window, key delivery after a click on the video) is a V0 question that nothing here answers.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2 and 9.2 say.
"""
import hashlib
import itertools
import json
import re
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_leaf_model as leaf_model  # noqa: E402
import ux_player_keys_model as km  # noqa: E402
import ux_player_model as pm  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402
import ux_vendored_player_keys as vk  # noqa: E402
from test_ux_leaf_anchor_guard import call_arguments, check_legacy_branch, check_must_keep  # noqa: E402
from test_ux_leaf_layout_binding import BASE_SHA256, HUNKS, V6_HUNKS, reverse_apply, reverse_v6  # noqa: E402

CONTRACT = model.load()
KEYS = CONTRACT["player_keys"]
EFFECTS_TEXT = km.read(km.EFFECTS_KT)
INPUT_TEXT = km.read(km.INPUT_KT)
LEAF_TEXT = km.read(km.LEAF_KT)
SHORTCUT_TEXT = pm.read(pm.SHORTCUT_KT)
SHELL_TEXT = pm.read(shell.SHORTCUTS_KT)
CONSTANTS = pm.constants(EFFECTS_TEXT)
RANGES, NAMED = km.key_code_tables(EFFECTS_TEXT)
PLAYER_TABLE = pm.player_table(pm.player_rows(SHORTCUT_TEXT))
RESERVED = set(shell.string_set(SHELL_TEXT, "DESKTOP_SHELL_RESERVED_CHORDS"))
ZOOM = set(shell.string_set(SHELL_TEXT, "DESKTOP_SHELL_ZOOM_KEYS"))
NAVIGATION = set(km.set_of_strings(EFFECTS_TEXT, "DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS"))
REPEAT_KINDS = tuple(KEYS["repeat_actions"])
KINDS = pm.enum_entries(SHORTCUT_TEXT, "DesktopPlayerActionKind")

# The sha256 of files that slice V6 must leave exactly as they were (their text at the commit before the slice).
UNTOUCHED = {
    "ui/DesktopWindowsVideoKeyInput.kt": "a3df87d9a4341340cd082910872b797097f812c70b7fc8292ff9171b563cec04",
    "ui/DesktopWindowKeyFallback.kt": "f6dc3fe8fd83ac58483ed7c742511c73e621964ee8714397bc2bddec10870bb0",
    "ui/DesktopPlayerShortcutPolicy.kt": "1dd2091fea307534f8dd54c924303f7bd27137634621cf4a63706558118ef024",
    "ui/DesktopPlayerControlsBinding.kt": "bbdf8e411c0ed0aa7f54b9ee30792f9d1c783db9449557d7f696128d0373fb71",
}
UNTOUCHED_TEST = {"ui/DesktopWindowsVideoKeyInputTest.kt": "98d7e4923a34e96df90b47c6b2e6f5373f81da003c09048549c95fec2e5206d7"}
VENDORED_SHA256 = "d9315792c605c4acc4f7c3de98c888b335de729fba741c6e7f0eb966a3101441"
V2_STATE_LEAF_SHA256 = "91b26ab1dff277a62c71bb6e684c7b5551cbbfaa042040fdc2d62014618ddf22"  # the leaf at the commit before slice V6
V6_ADDED, V6_REMOVED = 68, 3


def sha256_of(path):
    return hashlib.sha256(source.read(path).encode("utf-8")).hexdigest()


class NeedleCase(unittest.TestCase):
    def assertHas(self, text, *needles):
        flat = km.squash(text)
        for needle in needles:
            self.assertIn(km.squash(needle), flat, needle)


def missing(text, needles):
    flat = km.squash(text)
    return [needle for needle in needles if km.squash(needle) not in flat]


# ---------------------------------------------------------------------------------------------------------------------------------------
# The Kotlin lines that the mirror transliterates, as needles. Each check returns the needles that the text no longer holds.
# ---------------------------------------------------------------------------------------------------------------------------------------

EFFECT_NEEDLES = (
    "internal fun desktopPlayerKeyName(keyCode: Int): String? = when (keyCode) {",
    "in DESKTOP_PLAYER_KEY_LETTERS, in DESKTOP_PLAYER_KEY_DIGITS -> Char(keyCode).toString()",
    'in DESKTOP_PLAYER_KEY_NUMPAD_DIGITS -> "Numpad" + (keyCode - DESKTOP_PLAYER_KEY_NUMPAD_DIGITS.first)',
    'in DESKTOP_PLAYER_KEY_FUNCTION_KEYS -> "F" + (keyCode - DESKTOP_PLAYER_KEY_FUNCTION_KEYS.first + 1)',
    "else -> DESKTOP_PLAYER_NAMED_KEY_CODES[keyCode]",
    'internal const val DESKTOP_PLAYER_SPACE_KEY_NAME = "Space"',
    "kind == DesktopPlayerActionKind.SEEK || kind == DesktopPlayerActionKind.VOLUME",
    "if (starts.isEmpty()) return null",
    "if (direction > 0) return starts.firstOrNull { it > positionMs }",
    "val earlier = starts.lastOrNull { it < positionMs - DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS }",
    "return earlier ?: if (positionMs > DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS) 0L else null",
    "if (direction > 0) options.filter { it > current + DESKTOP_PLAYER_SPEED_EPSILON }.minOrNull()",
    "else options.filter { it < current - DESKTOP_PLAYER_SPEED_EPSILON }.maxOrNull()",
    "DesktopWindowsUxPlayerLayout.LEGACY -> legacyGate",
    "DesktopWindowsUxPlayerLayout.DESKTOP -> !textEditorFocused && !flyoutOpen",
    "DesktopPlayerActionKind.PLAY_PAUSE -> { controls.playPause(); true }",
    "DesktopPlayerActionKind.SEEK -> { seekBy(action.arg.toDouble()); true }",
    "DesktopPlayerActionKind.CHAPTER -> seekChapter(action.arg)",
    "DesktopPlayerActionKind.VOLUME -> { controls.volume((snapshot().volume + action.arg).coerceIn(0.0, DESKTOP_PLAYER_VOLUME_MAX)) true }",
    "DesktopPlayerActionKind.MUTE -> { controls.mute(); true }",
    "DesktopPlayerActionKind.FULLSCREEN_TOGGLE -> { controls.fullscreen(); true }",
    "DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA -> { togglePanel(); true }",
    "DesktopPlayerActionKind.MINI_PLAYER -> miniPlayer()",
    "DesktopPlayerActionKind.SUBTITLE_TOGGLE -> toggleSubtitles()",
    "DesktopPlayerActionKind.DANMAKU_TOGGLE -> { toggleDanmaku(); true }",
    "DesktopPlayerActionKind.SCREENSHOT -> takeScreenshot()",
    "DesktopPlayerActionKind.PART -> navigatePart(action.arg > 0)",
    "DesktopPlayerActionKind.SEEK_PERCENT -> seekWithin { duration -> duration * action.arg / 100.0 }",
    "DesktopPlayerActionKind.SEEK_START -> { controls.seek(0.0); true }",
    "DesktopPlayerActionKind.SEEK_END -> seekWithin { duration -> duration }",
    "DesktopPlayerActionKind.SPEED_STEP -> stepSpeed(action.arg)",
    "DesktopPlayerActionKind.SPEED_SET -> { controls.speed(action.arg.toDouble()); true }",
    "val target = desktopPlayerChapterTarget(starts, (snapshot().positionSeconds * 1000.0).toLong(), direction) ?: return false",
    "if (target in starts) seekToChapter(target) else controls.seek(target / 1000.0)",
    "if (!duration.isFinite() || duration <= 0.0) return false",
    "controls.seek(target(duration))",
    "if (!canMiniPlayer()) return false",
    "controls.pictureInPicture()",
    "if (!hasPart(forward)) return false",
    "if (forward) controls.next() else controls.previous()",
    "val next = desktopPlayerSpeedStep(speedChoices(), currentSpeed(), direction) ?: return false",
    "controls.speed(next)",
    "var legacyLayout: Boolean = true",
    "var flyoutOpen: Boolean = false",
    "var effects: DesktopPlayerShortcutEffects? = null",
    "get() = if (legacyLayout) DesktopWindowsUxPlayerLayout.LEGACY else DesktopWindowsUxPlayerLayout.DESKTOP",
    "desktopPlayerShortcutOwnsFocus(layout, legacyGate, textEditorFocused, flyoutOpen)",
    "fun perform(action: DesktopPlayerAction): Boolean = effects?.perform(action) ?: false",
)

ROUTER_NEEDLES = (
    "internal class DesktopWindowsVideoShortcutInput<Source : Any>(",
    "private class Held<Owner : Any>(val source: Owner, val action: DesktopPlayerAction)",
    "private val held = HashMap<Int, Held<Source>>()",
    "if (closed) return false",
    "val code = event.key.nativeKeyCode",
    "val name = desktopPlayerKeyName(code) ?: return false",
    "if (name == DESKTOP_PLAYER_SPACE_KEY_NAME) return false",
    "if (event.type == KeyEventType.KeyUp) {",
    "val press = held.remove(code) ?: return false",
    "return currentSource() === press.source && ownsFocus()",
    "if (event.type != KeyEventType.KeyDown || !ownsFocus()) return false",
    "if (name in DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS && controlHoldsFocus()) return false",
    "val source = currentSource() ?: return false",
    "key = name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed,",
    "meta = event.isMetaPressed, keyDown = true,",
    "DesktopPlayerKeyContext(textEditorFocused = textEditorFocused(), paused = isPaused())",
    "val press = held[code]",
    "if (press != null) {",
    "if (desktopPlayerShortcutRepeats(press.action.kind) && press.source === source && action == press.action) perform(press.action)",
    "return true",
    "if (action == null || currentSource() !== source) return false",
    "val handled = if (action.kind == DesktopPlayerActionKind.ESC_CHAIN) escapeSwallowed() else perform(action)",
    "if (!handled) return false",
    "held[code] = Held(source, action)",
    "resolveDesktopPlayerEscStep(popupOpen = false, fullscreen = isFullscreen(), layout = layout()) == DesktopPlayerEscStep.NONE",
    "closed = true",
    "KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent",
    "if (textEditorFocused) return false",
    "request()",
    "} catch (_: IllegalStateException) {",
)

ROUTER_PARAMETERS = ("currentSource", "ownsFocus", "controlHoldsFocus", "textEditorFocused", "isFullscreen", "isPaused", "layout", "perform")
EFFECT_PARAMETERS = ("controls", "snapshot", "seekBy", "hasPart", "canMiniPlayer", "togglePanel", "toggleSubtitles", "toggleDanmaku",
                     "takeScreenshot", "chapterStartsMs", "seekToChapter", "speedChoices", "currentSpeed")

# What the leaf passes for each parameter of the router and of the effects, squashed.
ROUTER_ARGUMENTS = {
    "currentSource": "{ latestChromeSource?.takeIf { chromeCurrent() } }",
    "ownsFocus": "keyboardOwned",
    "controlHoldsFocus": "{ topFocused || barInteraction.focused }",
    "textEditorFocused": "::desktopWindowsTextEditorFocused",
    "isFullscreen": "{ latestFullscreen }",
    "isPaused": "{ native.state.value.paused }",
    "layout": "{ shortcutBinding.layout }",
    "perform": "{ action -> shortcutBinding.perform(action).also { handled -> if (handled) chromeActivity(DesktopWindowsFullscreenChromeActivity.NATIVE_KEY) } }",
}
EFFECT_ARGUMENTS = {
    "controls": "controls",
    "snapshot": "{ native.state.value.let { DesktopPlayerShortcutSnapshot(it.positionSeconds, it.durationSeconds, it.volume) } }",
    "seekBy": "{ seconds -> if (current()) shell.playback.seekBy(seconds) }",
    "hasPart": "{ forward -> if (forward) shell.playback.hasNext else shell.playback.hasPrevious }",
    "canMiniPlayer": "{ !pipActive && success != null && state.videoCodec != null && !state.audioOnly }",
    "togglePanel": "{ if (current()) detailsOpen = !detailsOpen }",
    "toggleSubtitles": ("{ val target = if (subtitleMode != SubtitleDisplayMode.OFF) SubtitleDisplayMode.OFF else subtitleDisplayOptions.firstOrNull "
                        "{ it.enabled && it.mode != SubtitleDisplayMode.OFF }?.mode if (current() && target != null) { subtitleOverride = target; true } else false }"),
    "toggleDanmaku": "{ if (current()) actions.toggleDanmaku() }",
    "takeScreenshot": "::takeScreenshot",
    "chapterStartsMs": ("{ val result = chapters if (result == null) emptyList() else normalizeViewPointSegments(result.points, "
                        "(native.state.value.durationSeconds * 1000.0).toLong()).map { it.fromMs } }"),
    "seekToChapter": "{ positionMs -> val result = chapters val accepted = chaptersSource if (result != null && accepted != null) seekChapter(result, accepted, positionMs) }",
    "speedChoices": "{ resolveDesktopWindowsVideoSpeedOptions(speedOptions, state.speed.toFloat()).map { it.toDouble() } }",
    "currentSpeed": "{ state.speed }",
}


def check_effects(text):
    return missing(text, EFFECT_NEEDLES)


def check_router(text):
    return missing(text, ROUTER_NEEDLES)


def constructor_parameters(text, class_name):
    """[parameter names] of the primary constructor of `internal class NAME(...)`."""
    start = text.index("class %s" % class_name)
    code = source.mask_kotlin(text)
    begin = code.index("(", start)
    depth, end = 0, begin
    while True:
        depth += (code[end] == "(") - (code[end] == ")")
        if depth == 0:
            break
        end += 1
    return re.findall(r"(?:private )?val (\w+):", " ".join(text[begin + 1:end].split()))


def same_events(left, right):
    """The recorded events are equal: names and integers exactly, floats to a relative 1e-9 (the oracle may order a product differently)."""
    if len(left) != len(right):
        return False
    for first, second in zip(left, right):
        if len(first) != len(second) or first[0] != second[0]:
            return False
        for x, y in zip(first[1:], second[1:]):
            if isinstance(x, float) or isinstance(y, float):
                if not abs(x - y) <= 1e-9 * max(1.0, abs(x)):
                    return False
            elif x != y:
                return False
    return True


def make_router(host=None):
    host = km.RouterHost() if host is None else host
    return host, km.RouterModel(host, RANGES, NAMED, PLAYER_TABLE, RESERVED, ZOOM, NAVIGATION, REPEAT_KINDS)


def vk_code(name):
    """The AWT key code of a chord key name, from the independent oracle table."""
    found = [code for _vk, (code, key_name) in km.AWT_KEYS.items() if key_name == name]
    assert len(found) == 1, name
    return found[0]


# ---------------------------------------------------------------------------------------------------------------------------------------
# Step 0: the vendored key map
# ---------------------------------------------------------------------------------------------------------------------------------------

class VendoredExtractorTest(unittest.TestCase):
    TEXT = source.read(vk.VENDORED_KEY_POLICY)

    def test_the_real_vendored_function_is_parsed_into_its_46_bindings(self):
        extracted = vk.extract()
        chords = [chord for chord, _name, _argument in extracted["bindings"]]
        self.assertEqual(46, len(chords))
        self.assertEqual(46, len(set(chords)))
        self.assertEqual({"KEYBOARD_SEEK_SHORT_STEP_MS": 5000, "KEYBOARD_SEEK_LONG_STEP_MS": 10000}, extracted["constants"])
        self.assertEqual(("L", "ToggleScreenLock"), extracted["locked"])
        self.assertEqual(sha256_of(vk.VENDORED_KEY_POLICY), VENDORED_SHA256, "the vendored key map changed: review the table and re-emit it")

    def test_every_binding_is_kept_or_listed_and_the_counts_are_pinned(self):
        rows = vk.rows()
        counts = {}
        for row in rows:
            counts[row["status"]] = counts.get(row["status"], 0) + 1
        self.assertEqual({"kept": 39, "changed": 1, "not-taken": 2, "dropped": 4}, counts)
        differing = sorted(row["chord"] for row in rows if row["status"] != "kept")
        self.assertEqual(sorted(vk.INTENTIONAL), differing)
        for row in rows:
            if row["status"] != "kept":
                self.assertTrue(row["reason"].strip(), row["chord"])
            else:
                self.assertNotIn("reason", row)

    def test_the_kept_rows_resolve_to_the_same_action_through_the_router_table(self):
        router = vk.Router()
        for row in vk.rows():
            if row["status"] != "kept":
                continue
            resolved = router.resolve(row["chord"])
            self.assertEqual(vk.equivalent(row["vendored"]["action"], row["vendored"].get("arg")), resolved, row["chord"])
            self.assertEqual({"action": resolved[0], "arg": resolved[1]}, row["router"])

    def test_the_four_kinds_of_difference_are_what_they_say(self):
        by_chord = {row["chord"]: row for row in vk.rows()}
        self.assertEqual("changed", by_chord["L"]["status"])
        self.assertEqual({"action": "SEEK", "arg": 10}, by_chord["L"]["router"])
        for chord in ("Enter", "NumpadEnter"):
            self.assertEqual("not-taken", by_chord[chord]["status"])
            self.assertIsNone(by_chord[chord]["router"])
            self.assertEqual("ToggleFullscreen", by_chord[chord]["vendored"]["action"])
        for chord, action in (("Q", "ToggleLike"), ("W", "Coin"), ("E", "ToggleFavorite"), ("R", "TripleAction")):
            self.assertEqual("dropped", by_chord[chord]["status"])
            self.assertEqual(action, by_chord[chord]["vendored"]["action"])
            self.assertIsNone(by_chord[chord]["router"])

    def test_the_contract_holds_exactly_what_the_extractor_emits(self):
        self.assertEqual(vk.section(), KEYS["vendored"])
        self.assertEqual([], vk.problems())
        self.assertEqual(0, vk.main(["--check"]))
        self.assertEqual(model.canonical_text(vk.merged(model.load())), model.CONTRACT_PATH.read_bytes().decode("utf-8"))
        self.assertEqual(model.CONTRACT_PATH.read_bytes(), model.KOTLIN_COPY_PATH.read_bytes())

    def test_a_new_vendored_binding_that_is_neither_kept_nor_listed_fails(self):
        text = self.TEXT.replace("        Key.M -> PlayerKeyAction.ToggleMute\n", "        Key.M -> PlayerKeyAction.ToggleMute\n        Key.X -> PlayerKeyAction.PlayPause\n", 1)
        self.assertNotEqual(self.TEXT, text)
        with self.assertRaises(AssertionError) as caught:
            vk.rows(vk.extract(text))
        self.assertIn("neither kept nor listed", str(caught.exception))
        self.assertIn("X", str(caught.exception))

    def test_a_vendored_binding_that_now_means_something_else_fails(self):
        text = self.TEXT.replace("Key.M -> PlayerKeyAction.ToggleMute", "Key.M -> PlayerKeyAction.TakeScreenshot", 1)
        with self.assertRaises(AssertionError) as caught:
            vk.rows(vk.extract(text))
        self.assertIn("neither kept nor listed", str(caught.exception))

    def test_a_new_vendored_binding_that_the_router_also_has_is_kept(self):
        # Shift+P is the router's previous part; a vendored Shift+P with the same action is a kept binding.
        text = self.TEXT.replace("            Key.Two, Key.NumPad2 -> PlayerKeyAction.SetSpeed(2.0f)\n",
                                 "            Key.Two, Key.NumPad2 -> PlayerKeyAction.SetSpeed(2.0f)\n            Key.P -> PlayerKeyAction.PreviousPart\n", 1)
        self.assertNotEqual(self.TEXT, text)
        by_chord = {row["chord"]: row for row in vk.rows(vk.extract(text))}
        self.assertEqual("kept", by_chord["Shift+P"]["status"])
        self.assertEqual({"action": "PART", "arg": -1}, by_chord["Shift+P"]["router"])

    def test_a_listed_difference_that_no_binding_has_any_more_fails(self):
        text = self.TEXT.replace("        Key.Q -> PlayerKeyAction.ToggleLike\n", "", 1)
        self.assertNotEqual(self.TEXT, text)
        with self.assertRaises(AssertionError) as caught:
            vk.rows(vk.extract(text))
        self.assertIn("no vendored binding has any more", str(caught.exception))
        self.assertIn("'Q'", str(caught.exception))

    def test_a_listed_difference_with_the_wrong_status_fails(self):
        for chord, wrong in (("Q", "changed"), ("L", "dropped"), ("Enter", "dropped"), ("NumpadEnter", "changed")):
            with mock.patch.dict(vk.INTENTIONAL, {chord: (wrong, "wrong on purpose")}):
                with self.assertRaises(AssertionError, msg=chord):
                    vk.rows()

    def test_a_kept_binding_cannot_also_be_listed(self):
        with mock.patch.dict(vk.INTENTIONAL, {"Space": ("changed", "wrong on purpose")}):
            with self.assertRaises(AssertionError):
                vk.rows()

    def test_a_vendored_function_of_another_shape_is_reported(self):
        for old in ("    if (isInPipMode || isTextInputActive) return null\n", "    if (event.type != KeyEventType.KeyDown) return null\n",
                    "    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {\n        return null\n    }\n"):
            self.assertEqual(1, self.TEXT.count(old), old)
            with self.assertRaises(AssertionError, msg=old):
                vk.extract(self.TEXT.replace(old, "", 1))
        extra = self.TEXT.replace("    return when (event.key) {\n        Key.Spacebar", "    val unused = when (event.key) { else -> null }\n    return when (event.key) {\n        Key.Spacebar", 1)
        self.assertNotEqual(self.TEXT, extra)
        with self.assertRaises(AssertionError):
            vk.extract(extra)

    def test_an_unknown_vendored_key_name_is_reported(self):
        text = self.TEXT.replace("Key.LeftBracket ->", "Key.Backslash ->", 1)
        with self.assertRaises(AssertionError) as caught:
            vk.extract(text)
        self.assertIn("Backslash", str(caught.exception))

    def test_the_state_rules_of_the_vendored_function_are_listed_with_their_status(self):
        rules = {rule["vendored"]: rule["status"] for rule in KEYS["vendored"]["state_rules"]}
        self.assertEqual({"only a key press is read": "kept", "no key in picture-in-picture": "kept", "no key while a text input is active": "kept",
                          "screen locked: only L, which unlocks": "dropped", "Ctrl, Alt or Meta held: no action": "changed"}, rules)

    def test_the_additions_are_the_router_rows_that_the_vendored_map_does_not_bind(self):
        additions = KEYS["vendored"]["additions"]
        self.assertEqual(vk.additions(), additions)
        self.assertEqual({"Ctrl+Left", "Ctrl+Right", "Alt+Enter", "F11", "Esc", "T", "Ctrl+B", "P", "I", "C", "Shift+C", "Shift+P", "Shift+N", "Home",
                          "End", "Comma", "Period", "Shift+Comma", "Shift+Period", "Menu", "Shift+F10", "Shift+Slash"}, {item["chord"] for item in additions})
        bound = {vk.Router().chord_of(row["chord"]) for row in KEYS["vendored"]["bindings"]}
        player_chords = [row["chord"] for row in CONTRACT["shortcuts"]["rows"] if row["scope"] == "player"]
        self.assertEqual(sorted(set(player_chords) - bound), sorted(item["chord"] for item in additions))

    def test_the_reasons_of_the_dropped_rows_are_true_of_the_desktop_root_handler(self):
        shell_text = source.read(source.K / "DesktopShell.kt")
        body = shell_text[shell_text.index("fun performPlayerKey(action: PlayerKeyAction?): Boolean {"):]
        body = body[:body.index("\n    }\n")]
        for name in ("ToggleLike", "Coin", "ToggleFavorite", "TripleAction", "ToggleScreenLock"):
            self.assertNotIn(name, body, name + " is not run by the desktop Root handler")
        self.assertIn("else -> false", body)
        # Enter: the vendored map still runs at Root, so the key keeps toggling fullscreen there.
        self.assertIn("performPlayerKey(resolvePlayerKeyAction(event, isTextInputActive = searchFocused))", shell_text)

    def test_the_table_prints(self):
        text = vk.markdown()
        self.assertIn("| L | ToggleScreenLock | changed | SEEK 10 |", text)
        self.assertIn("Rows of the router table without a vendored binding:", text)


# ---------------------------------------------------------------------------------------------------------------------------------------
# Key codes
# ---------------------------------------------------------------------------------------------------------------------------------------

class KeyCodesTest(unittest.TestCase):
    def test_the_kotlin_block_equals_the_contract(self):
        codes = KEYS["key_codes"]
        self.assertEqual({"LETTERS": tuple(codes["letters"]), "DIGITS": tuple(codes["digits"]), "NUMPAD_DIGITS": tuple(codes["numpad_digits"]),
                          "FUNCTION_KEYS": tuple(codes["function_keys"])}, RANGES)
        self.assertEqual({code: name for name, code in codes["named"].items()}, NAMED)
        self.assertEqual(len(NAMED), len(codes["named"]), "two names share a code")

    def test_the_mirror_equals_the_independent_awt_table_for_every_code(self):
        for code in range(0, 1100):
            self.assertEqual(km.oracle_key_name(code), km.key_name_kt(code, RANGES, NAMED), code)

    def test_the_awt_oracle_has_no_two_names_for_one_code_and_no_two_codes_for_one_name(self):
        pairs = list(km.AWT_KEYS.values())
        self.assertEqual(len(pairs), len({code for code, _name in pairs}))
        self.assertEqual(len(pairs), len({name for _code, name in pairs}))

    def test_the_names_cover_the_keys_of_the_shortcut_tables_and_the_zoom_decoder(self):
        produced = {km.key_name_kt(code, RANGES, NAMED) for code in range(0, 1100)} - {None}
        for row in CONTRACT["shortcuts"]["rows"]:
            self.assertIn(row["chord"].split("+")[-1], produced, row["chord"])
        for name in CONTRACT["shortcuts"]["zoom_decoder_keys"]:
            self.assertIn(name, produced, name)

    def test_the_zoom_keys_are_the_keys_the_zoom_decoder_reads(self):
        decoder = source.read(km.ZOOM_INPUT_KT)
        decoded = set(re.findall(r"KeyEvent\.(VK_\w+)", decoder[:decoder.index("fun ", decoder.index("when (event.keyCode)"))]))
        self.assertTrue({"VK_PLUS", "VK_EQUALS", "VK_ADD", "VK_MINUS", "VK_SUBTRACT", "VK_0", "VK_NUMPAD0"} <= decoded, decoded)
        names = {km.AWT_KEYS[vk_name][1] for vk_name in decoded if vk_name in km.AWT_KEYS}
        self.assertEqual(set(CONTRACT["shortcuts"]["zoom_decoder_keys"]), names)
        for vk_name in decoded:
            self.assertEqual(km.AWT_KEYS[vk_name][1], km.key_name_kt(km.AWT_KEYS[vk_name][0], RANGES, NAMED), vk_name)

    def test_menu_is_the_awt_context_menu_key_and_not_a_compose_constant(self):
        # Compose's Key.Menu carries a synthetic code that no AWT event has (read from the pinned jar), so the router names keys by AWT code.
        self.assertEqual("Menu", km.key_name_kt(0x20D, RANGES, NAMED))
        self.assertEqual("F10", km.key_name_kt(0x79, RANGES, NAMED))
        self.assertEqual(0x20D, KEYS["key_codes"]["named"]["Menu"])

    def test_the_kotlin_function_and_its_block_are_pinned(self):
        self.assertEqual([], check_effects(EFFECTS_TEXT))
        block = shell.squash(shell.marked_block(EFFECTS_TEXT, "PLAYER-KEY-CODES"))
        self.assertIn("private val DESKTOP_PLAYER_KEY_LETTERS = 65..90", block)
        self.assertIn("private val DESKTOP_PLAYER_NAMED_KEY_CODES: Map<Int, String> = mapOf(", block)


# ---------------------------------------------------------------------------------------------------------------------------------------
# Pure functions
# ---------------------------------------------------------------------------------------------------------------------------------------

class PureFunctionsTest(NeedleCase):
    def test_the_constants_are_the_contract_numbers(self):
        self.assertEqual(KEYS["chapter_back_margin_ms"], CONSTANTS["DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS"])
        self.assertEqual(KEYS["speed_epsilon"], CONSTANTS["DESKTOP_PLAYER_SPEED_EPSILON"])
        self.assertEqual(float(KEYS["volume"]["max"]), CONSTANTS["DESKTOP_PLAYER_VOLUME_MAX"])
        self.assertEqual(0, KEYS["volume"]["min"])
        self.assertEqual(sorted(KEYS["control_navigation_keys"]), sorted(NAVIGATION))
        self.assertEqual(["SEEK", "VOLUME"], KEYS["repeat_actions"])
        self.assertEqual({"Space": "DesktopWindowsVideoKeyInput claims Space (and Esc in fullscreen); the router never claims Space"}, KEYS["owned_elsewhere"])

    def test_the_repeat_rule_covers_exactly_seek_and_volume(self):
        for kind in KINDS:
            self.assertEqual(kind in ("SEEK", "VOLUME"), km.repeats_kt(kind, REPEAT_KINDS), kind)

    def test_the_chapter_jump_mirror_equals_the_oracle_over_a_sweep(self):
        margin = CONSTANTS["DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS"]
        layouts = ([], [0], [0, 60000, 120000], [10000, 60000], [5000], [0, 1000, 2000, 3000], [30000, 90000, 150000])
        cases = 0
        for starts in layouts:
            for position in list(range(-500, 160000, 997)) + [0, 1, margin - 1, margin, margin + 1, 59999, 60000, 60001, 62000, 62001, 120000]:
                for direction in (1, -1):
                    self.assertEqual(km.oracle_chapter_target(starts, position, direction, margin), km.chapter_target_kt(starts, position, direction, margin),
                                     (starts, position, direction))
                    cases += 1
        self.assertGreater(cases, 2000)

    def test_the_chapter_jump_examples_of_the_spec(self):
        margin = CONSTANTS["DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS"]
        starts = [0, 60000, 120000]
        self.assertEqual(60000, km.chapter_target_kt(starts, 30000, 1, margin))
        self.assertIsNone(km.chapter_target_kt(starts, 120000, 1, margin))
        self.assertEqual(60000, km.chapter_target_kt(starts, 121999, -1, margin))
        self.assertEqual(120000, km.chapter_target_kt(starts, 123000, -1, margin))
        self.assertEqual(0, km.chapter_target_kt(starts, 2001, -1, margin))
        self.assertIsNone(km.chapter_target_kt(starts, 2000, -1, margin))
        self.assertEqual(0, km.chapter_target_kt([10000, 60000], 5000, -1, margin))
        self.assertIsNone(km.chapter_target_kt([], 50000, 1, margin))

    def test_the_speed_step_mirror_equals_the_oracle_over_a_sweep(self):
        epsilon = CONSTANTS["DESKTOP_PLAYER_SPEED_EPSILON"]
        lists = ([], [1.0], [0.5, 1.0, 1.5, 2.0], [2.0, 0.5, 1.5], [0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0])
        cases = 0
        for options in lists:
            currents = [round(0.2 + index * 0.0137, 6) for index in range(300)] + [0.9995, 1.0, 1.0005, 1.0011, 0.999, 2.0, 4.0, 0.25]
            for current in currents:
                # A speed that sits exactly on the epsilon edge of an option is a floating point coin toss for any two ways of writing the
                # comparison, so the sweep leaves those few out and the examples above and in the JVM test decide them.
                if any(abs(abs(option - current) - epsilon) < 1e-9 for option in options):
                    continue
                for direction in (1, -1):
                    self.assertEqual(km.oracle_speed_step(options, current, direction, epsilon),
                                     km.speed_step_kt(options, current, direction, epsilon), (options, current, direction))
                    cases += 1
        self.assertGreater(cases, 3000)

    def test_a_speed_step_never_returns_the_current_speed_and_always_moves_the_right_way(self):
        epsilon = CONSTANTS["DESKTOP_PLAYER_SPEED_EPSILON"]
        options = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0]
        for current in (0.5, 0.6, 0.75, 1.0, 1.1, 1.5, 1.9, 2.0, 3.0):
            up = km.speed_step_kt(options, current, 1, epsilon)
            down = km.speed_step_kt(options, current, -1, epsilon)
            if up is not None:
                self.assertGreater(up, current + epsilon)
            if down is not None:
                self.assertLess(down, current - epsilon)

    def test_the_focus_rule_mirror_equals_the_oracle(self):
        for layout, gate, editor, flyout in itertools.product(("LEGACY", "DESKTOP"), (True, False), (True, False), (True, False)):
            self.assertEqual(km.oracle_owns_focus(layout, gate, editor, flyout), km.owns_focus_kt(layout, gate, editor, flyout), (layout, gate, editor, flyout))
        self.assertTrue(km.owns_focus_kt("DESKTOP", False, False, False))
        self.assertFalse(km.owns_focus_kt("LEGACY", False, False, False))

    def test_the_kotlin_lines_of_the_pure_functions_are_the_ones_the_mirror_transliterates(self):
        self.assertEqual([], check_effects(EFFECTS_TEXT))
        self.assertHas(EFFECTS_TEXT,
                       "internal fun desktopPlayerChapterTarget(starts: List<Long>, positionMs: Long, direction: Int): Long? {",
                       "internal fun desktopPlayerSpeedStep(options: List<Double>, current: Double, direction: Int): Double? =",
                       "internal fun desktopPlayerShortcutOwnsFocus( layout: DesktopWindowsUxPlayerLayout, legacyGate: Boolean, textEditorFocused: Boolean, flyoutOpen: Boolean, ): Boolean = when (layout) {",
                       "internal fun desktopPlayerShortcutRepeats(kind: DesktopPlayerActionKind): Boolean =")

    def test_the_legacy_gate_is_the_old_gate_of_the_leaf_and_nothing_else(self):
        # The leaf's gate text is unchanged: the only edit is that the router and the existing input share it through the binding.
        self.assertIn("topFocused || barInteraction.focused || viewportKeyboardFocused || nativeKeyboardOnVideo)", LEAF_TEXT)
        self.assertEqual(1, LEAF_TEXT.count("val latestKeyInputFocus by rememberUpdatedState("))


# ---------------------------------------------------------------------------------------------------------------------------------------
# Effects
# ---------------------------------------------------------------------------------------------------------------------------------------

class EffectsTest(NeedleCase):
    def test_the_perform_when_names_every_action_kind_exactly_once(self):
        kinds = km.action_kinds_of_perform(EFFECTS_TEXT)
        self.assertEqual(sorted(KINDS), sorted(kinds))
        self.assertEqual(23, len(kinds))
        self.assertNotIn("else ->", EFFECTS_TEXT[EFFECTS_TEXT.index("fun perform("):EFFECTS_TEXT.index("private fun seekChapter")])

    def test_the_kinds_without_an_effect_are_the_contract_list(self):
        start = EFFECTS_TEXT.index("DesktopPlayerActionKind.ESC_CHAIN, ")
        tail = EFFECTS_TEXT[start:EFFECTS_TEXT.index("\n    }", start)]
        self.assertEqual(sorted(KEYS["not_performed"]), sorted(re.findall(r"DesktopPlayerActionKind\.([A-Z_]+)", tail)))
        self.assertTrue(tail.rstrip().endswith("-> false"))
        for kind, reason in KEYS["not_performed"].items():
            self.assertTrue(reason.strip(), kind)
        # Frame step is in the table and omitted; the other four wait for the slice that builds their target.
        self.assertEqual("omitted until an MpvPlayer API exists", KEYS["not_performed"]["FRAME_STEP"])

    def test_every_kind_the_effects_act_on_is_an_active_row_of_the_table_or_a_digit_preset(self):
        active = {kind for chord, (kind, _arg, status, _paused) in PLAYER_TABLE.items() if status == "ACTIVE"}
        acted = set(KINDS) - set(KEYS["not_performed"])
        self.assertEqual(acted | {"ESC_CHAIN", "CONTEXT_MENU", "VIDEO_INFO", "SUBTITLE_FLYOUT", "SHORTCUT_HELP"}, active)

    def test_the_effects_use_only_public_members_of_the_controls_binding(self):
        used = set(re.findall(r"\bcontrols\.(\w+)", source.mask_kotlin(EFFECTS_TEXT)))
        self.assertEqual({"playPause", "volume", "mute", "fullscreen", "seek", "speed", "pictureInPicture", "next", "previous"}, used)
        self.assertTrue(used <= set(leaf_model.PUBLIC_MEMBERS), used)

    def test_the_constructor_names_the_capabilities_in_the_order_the_leaf_passes_them(self):
        self.assertEqual(list(EFFECT_PARAMETERS), constructor_parameters(EFFECTS_TEXT, "DesktopPlayerShortcutEffects"))
        arguments = call_arguments(LEAF_TEXT, "DesktopPlayerShortcutEffects(")
        self.assertEqual(list(EFFECT_PARAMETERS), [name for name, _ in arguments])
        for name, value in arguments:
            self.assertEqual(km.squash(EFFECT_ARGUMENTS[name]), km.squash(value), name)

    def test_the_mirror_equals_the_oracle_on_every_kind_and_every_state_of_the_player(self):
        import random
        rng = random.Random(2026)
        positions, durations = (0.0, 1.5, 100.0, 590.0), (0.0, 600.0, float("nan"), float("inf"), -1.0)
        volumes, speeds = (0.0, 2.0, 50.0, 98.0, 100.0), (1.0, 2.0, 0.5, 1.25)
        starts_choices, part_choices = ([], [0, 60000, 120000], [10000], [5000, 95000, 100500]), ((True, True), (False, False), (True, False), (False, True))
        configs = [dict(position=rng.choice(positions), duration=rng.choice(durations), volume=rng.choice(volumes), speed=rng.choice(speeds),
                        starts=rng.choice(starts_choices), has_part=rng.choice(part_choices), can_mini_player=rng.random() < .5,
                        subtitles=rng.random() < .5, screenshot=rng.random() < .5) for _ in range(400)]
        # The corners that a sample could miss: every boolean capability on and off, and every duration.
        for mini, subtitles, screenshot, duration in itertools.product((True, False), (True, False), (True, False), durations):
            configs.append(dict(can_mini_player=mini, subtitles=subtitles, screenshot=screenshot, duration=duration, starts=[0, 60000, 120000]))
        arguments = {kind: sorted({arg for _chord, (k, arg, _s, _p) in PLAYER_TABLE.items() if k == kind}) or [0] for kind in KINDS}
        cases = 0
        for kind in KINDS:
            for config in configs:
                for arg in arguments[kind]:
                    first, second = km.Host(**config), km.Host(**config)
                    handled = km.perform_kt(kind, arg, first, CONSTANTS)
                    want_handled, want_events = km.oracle_effect(kind, arg, second, CONTRACT)
                    self.assertEqual(want_handled, handled, (kind, arg, config))
                    self.assertTrue(same_events(want_events, first.events), (kind, arg, config, want_events, first.events))
                    cases += 1
        self.assertGreater(cases, 15000)

    def test_a_press_is_claimed_only_when_something_was_done(self):
        for kind in KINDS:
            host = km.Host()
            handled = km.perform_kt(kind, 1, host, CONSTANTS)
            self.assertEqual(handled, bool(host.events), kind)

    def test_the_volume_effect_stays_inside_the_range_and_seeking_is_relative(self):
        host = km.Host(volume=98.0)
        km.perform_kt("VOLUME", 5, host, CONSTANTS)
        km.perform_kt("VOLUME", -5, km.Host(volume=2.0), CONSTANTS)
        self.assertEqual([("volume", 100.0)], host.events)
        low = km.Host(volume=2.0)
        km.perform_kt("VOLUME", -5, low, CONSTANTS)
        self.assertEqual([("volume", 0.0)], low.events)
        seek = km.Host()
        km.perform_kt("SEEK", -10, seek, CONSTANTS)
        self.assertEqual([("seekBy", -10.0)], seek.events)

    def test_the_kotlin_lines_of_the_effects_are_the_ones_the_mirror_transliterates(self):
        self.assertEqual([], check_effects(EFFECTS_TEXT))

    def test_the_leaf_binds_every_effect_with_the_pinned_lambda(self):
        arguments = dict(call_arguments(LEAF_TEXT, "DesktopPlayerShortcutEffects("))
        self.assertEqual(set(EFFECT_PARAMETERS), set(arguments))
        self.assertEqual(1, LEAF_TEXT.count("DesktopPlayerShortcutEffects("))


# ---------------------------------------------------------------------------------------------------------------------------------------
# The router
# ---------------------------------------------------------------------------------------------------------------------------------------

class RouterTest(NeedleCase):
    def test_the_constructor_names_the_capabilities_in_the_order_the_leaf_passes_them(self):
        self.assertEqual(list(ROUTER_PARAMETERS), constructor_parameters(INPUT_TEXT, "DesktopWindowsVideoShortcutInput<Source : Any>"))
        arguments = call_arguments(LEAF_TEXT, "DesktopWindowsVideoShortcutInput(")
        self.assertEqual(list(ROUTER_PARAMETERS), [name for name, _ in arguments])
        for name, value in arguments:
            self.assertEqual(km.squash(ROUTER_ARGUMENTS[name]), km.squash(value), name)

    def test_the_kotlin_lines_of_the_router_are_the_ones_the_mirror_transliterates(self):
        self.assertEqual([], check_router(INPUT_TEXT))
        self.assertEqual(3, INPUT_TEXT.count("check(EventQueue.isDispatchThread())"))

    def test_a_claimed_press_owns_its_repeats_and_its_release_and_runs_only_once(self):
        host, router = make_router()
        k = vk_code("K")
        self.assertTrue(router.on_key(k))
        for _ in range(3):
            self.assertTrue(router.on_key(k))
        self.assertEqual([("PLAY_PAUSE", 0)], host.performed)
        self.assertTrue(router.on_key(k, "up"))
        self.assertTrue(router.on_key(k))
        self.assertEqual(2, len(host.performed))
        self.assertTrue(router.on_key(k, "up"))
        self.assertFalse(router.on_key(k, "up"))

    def test_seeking_and_the_volume_run_again_for_every_repeat_and_nothing_else_does(self):
        host, router = make_router()
        for _ in range(3):
            self.assertTrue(router.on_key(vk_code("Right")))
        self.assertEqual([("SEEK", 5)] * 3, host.performed)
        self.assertTrue(router.on_key(vk_code("Right"), "up"))
        host.performed.clear()
        for name in ("Up", "Up", "Down", "Down"):
            self.assertTrue(router.on_key(vk_code(name)))
        self.assertEqual([("VOLUME", 5), ("VOLUME", 5), ("VOLUME", -5), ("VOLUME", -5)], host.performed)
        host.performed.clear()
        for _ in range(4):
            self.assertTrue(router.on_key(vk_code("M")))
        self.assertEqual([("MUTE", 0)], host.performed)

    def test_a_held_key_whose_modifier_changed_does_not_run_another_action(self):
        host, router = make_router()
        self.assertTrue(router.on_key(vk_code("Right")))
        self.assertTrue(router.on_key(vk_code("Right"), "down", {"Shift"}))
        self.assertEqual([("SEEK", 5)], host.performed)

    def test_modifiers_select_their_own_rows(self):
        host, router = make_router()
        cases = (("Right", {"Shift"}, ("SEEK", 10)), ("Right", {"Ctrl"}, ("CHAPTER", 1)), ("Enter", {"Alt"}, ("FULLSCREEN_TOGGLE", 0)),
                 ("Comma", {"Shift"}, ("SPEED_STEP", -1)), ("1", {"Shift"}, ("SPEED_SET", 1)), ("5", set(), ("SEEK_PERCENT", 50)),
                 ("Numpad5", set(), ("SEEK_PERCENT", 50)), ("Menu", set(), ("CONTEXT_MENU", 0)), ("F10", {"Shift"}, ("CONTEXT_MENU", 0)),
                 ("Home", set(), ("SEEK_START", 0)), ("End", set(), ("SEEK_END", 0)))
        for name, modifiers, expected in cases:
            host.performed.clear()
            self.assertTrue(router.on_key(vk_code(name), "down", modifiers), name)
            self.assertEqual([expected], host.performed)
            self.assertTrue(router.on_key(vk_code(name), "up"))

    def test_meta_ctrl_alt_the_zoom_keys_tab_and_keys_without_a_row_are_not_claimed(self):
        host, router = make_router()
        for name, modifiers in (("K", {"Meta"}), ("K", {"Ctrl", "Alt"}), ("Plus", {"Ctrl"}), ("5", {"Ctrl"}), ("X", set()), ("Enter", set()), ("K", {"Ctrl"})):
            self.assertFalse(router.on_key(vk_code(name), "down", modifiers), (name, modifiers))
        self.assertFalse(router.on_key(9))
        self.assertEqual([], host.performed)

    def test_space_is_never_claimed_and_the_frame_step_stays_unclaimed_while_paused(self):
        host, router = make_router()
        host.paused = True
        self.assertFalse(router.on_key(vk_code("Space")))
        self.assertFalse(router.on_key(vk_code("Space"), "up"))
        self.assertFalse(router.on_key(vk_code("Comma")))
        self.assertEqual([], host.performed)

    def test_no_key_is_claimed_without_the_keyboard_a_source_or_while_an_editor_has_it(self):
        host, router = make_router()
        host.owns = False
        self.assertFalse(router.on_key(vk_code("K")))
        host.owns, host.source = True, None
        self.assertFalse(router.on_key(vk_code("K")))
        host.source, host.text_editor = object(), True
        self.assertFalse(router.on_key(vk_code("K")))
        self.assertFalse(router.on_key(vk_code("5")))
        host.text_editor = False
        self.assertEqual([], host.performed)
        self.assertTrue(router.on_key(vk_code("K")))

    def test_the_arrow_keys_home_and_end_stay_with_a_control_that_holds_the_focus(self):
        host, router = make_router()
        host.control_holds_focus = True
        for name in ("Left", "Right", "Up", "Down", "Home", "End"):
            self.assertFalse(router.on_key(vk_code(name)), name)
            self.assertFalse(router.on_key(vk_code(name), "down", {"Shift"}), name)
        self.assertEqual([], host.performed)
        self.assertTrue(router.on_key(vk_code("K")))
        self.assertTrue(router.on_key(vk_code("M")))
        host.control_holds_focus = False
        self.assertTrue(router.on_key(vk_code("Right")))

    def test_a_press_that_nobody_acted_on_is_not_claimed_and_its_release_is_not_consumed(self):
        host, router = make_router()
        host.accept = False
        self.assertFalse(router.on_key(vk_code("K")))
        self.assertFalse(router.on_key(vk_code("K"), "up"))
        host.accept = True
        self.assertTrue(router.on_key(vk_code("K")))
        self.assertEqual(2, len(host.performed))

    def test_a_held_press_cannot_act_on_a_replacement_source(self):
        host, router = make_router()
        right = vk_code("Right")
        self.assertTrue(router.on_key(right))
        host.source = object()
        self.assertTrue(router.on_key(right))
        self.assertEqual(1, len(host.performed))
        self.assertFalse(router.on_key(right, "up"))
        self.assertTrue(router.on_key(right))
        self.assertEqual(2, len(host.performed))

    def test_a_release_after_the_keyboard_was_lost_still_clears_the_held_press(self):
        host, router = make_router()
        k = vk_code("K")
        self.assertTrue(router.on_key(k))
        host.owns = False
        self.assertFalse(router.on_key(k, "up"))
        host.owns = True
        self.assertTrue(router.on_key(k))
        self.assertEqual(2, len(host.performed))

    def test_focus_loss_forgets_every_held_press_and_closing_retires_the_router(self):
        host, router = make_router()
        k = vk_code("K")
        self.assertTrue(router.on_key(k))
        router.release_held_input()
        self.assertTrue(router.on_key(k))
        self.assertEqual(2, len(host.performed))
        router.close()
        self.assertFalse(router.on_key(k, "up"))
        self.assertFalse(router.on_key(k))
        self.assertEqual(2, len(host.performed))

    def test_escape_is_swallowed_only_by_the_desktop_layout_outside_fullscreen(self):
        host, router = make_router()
        esc = vk_code("Esc")
        self.assertFalse(router.on_key(esc))
        host.fullscreen = True
        self.assertFalse(router.on_key(esc))
        host.layout = "DESKTOP"
        self.assertFalse(router.on_key(esc))
        host.fullscreen = False
        self.assertTrue(router.on_key(esc))
        self.assertTrue(router.on_key(esc))
        self.assertTrue(router.on_key(esc, "up"))
        self.assertFalse(router.on_key(esc, "up"))
        self.assertEqual([], host.performed)

    def test_every_row_of_the_table_is_claimed_or_has_a_named_reason_not_to_be(self):
        # The table, the key codes, the resolver, the router and the effects together, for each of the 50 player rows pressed as the chord says.
        not_performed = set(KEYS["not_performed"])
        claimed_rows = 0
        for row in CONTRACT["shortcuts"]["rows"]:
            if row["scope"] != "player":
                continue
            chord, kind = row["chord"], row["action"]
            parts = chord.split("+")
            key, modifiers = parts[-1], set(parts[:-1])
            host, router = make_router()
            performed = []

            def perform(action_kind, arg, player=km.Host(starts=[0, 60000, 120000])):
                performed.append((action_kind, arg))
                return km.perform_kt(action_kind, arg, player, CONSTANTS)

            host.perform = perform
            claimed = router.on_key(vk_code(key), "down", modifiers)
            if key == "Space":
                expected = False  # owned by the existing input
            elif row.get("status") in ("existing-at-root", "omitted-until-mpv-api"):
                expected = False  # F11 stays with the shell root; the frame step waits for an mpv API
            elif kind == "ESC_CHAIN":
                expected = False  # a windowed Esc of the LEGACY layout stays with Root
            else:
                expected = kind not in not_performed
            self.assertEqual(expected, claimed, chord)
            if claimed:
                self.assertEqual([(kind, row.get("arg", 0))], performed, chord)
                claimed_rows += 1
        self.assertEqual(40, claimed_rows)  # 50 rows less Space, F11, Esc, the two frame steps and the five rows without a target

    def test_the_decision_d4_chain_of_the_leaf_layouts(self):
        for layout, fullscreen, want in (("LEGACY", False, "LEAVE_PAGE"), ("LEGACY", True, "LEAVE_FULLSCREEN"), ("DESKTOP", False, "NONE"),
                                         ("DESKTOP", True, "LEAVE_FULLSCREEN")):
            self.assertEqual(want, pm.esc_step_kt(False, fullscreen, layout), (layout, fullscreen))

    def test_the_existing_input_is_not_touched_and_is_asked_first(self):
        for relative, digest in UNTOUCHED.items():
            self.assertEqual(digest, sha256_of(source.K / relative), relative)
        for relative, digest in UNTOUCHED_TEST.items():
            self.assertEqual(digest, sha256_of(source.KT / relative), relative)
        self.assertEqual(1, LEAF_TEXT.count("windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) }"))
        self.assertEqual(1, len(re.findall(r"registerLeaf\b", source.mask_kotlin(LEAF_TEXT))))
        self.assertNotIn("registerLeaf(input::onKey)", LEAF_TEXT)


# ---------------------------------------------------------------------------------------------------------------------------------------
# The leaf
# ---------------------------------------------------------------------------------------------------------------------------------------

def check_leaf_v6(leaf):
    """Problems in the V6 shape of the leaf (what the nine hunks together must leave behind)."""
    problems = []

    def need(condition, message):
        if not condition:
            problems.append(message)

    code = source.mask_kotlin(leaf)
    need(leaf.count("val shortcutBinding = remember(assembly) { DesktopWindowsVideoShortcutBinding() }") == 1, "the binding is remembered once per assembly")
    need(leaf.count("val keyboardOwned = { shortcutBinding.ownsFocus(latestKeyInputFocus, desktopWindowsTextEditorFocused()) &&") == 1, "the shared gate")
    need(leaf.count("ownsFocus = keyboardOwned,") == 2, "both keyboard inputs take the shared gate")
    need(leaf.count("val shortcuts = DesktopWindowsVideoShortcutInput(") == 1, "one router")
    need(leaf.count("val input = DesktopWindowsVideoKeyInput(") == 1, "the existing input stays")
    need(leaf.count("windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) }") == 1, "the one slot takes both")
    need(leaf.count("shortcuts.releaseHeldInput()") == 1 and leaf.count("shortcuts.close()") == 1, "focus loss and dispose reach the router")
    need(len(re.findall(r"\bshortcutBinding\b", code)) == 7, "shortcutBinding is used 7 times: %d" % len(re.findall(r"\bshortcutBinding\b", code)))
    need(leaf.count("SideEffect {\n        shortcutBinding.legacyLayout = legacyChrome") == 1, "the binding is filled by one SideEffect from legacyChrome")
    need(leaf.count("DesktopPlayerShortcutEffects(") == 1, "one set of effects")
    need(leaf.count("desktopWindowsRequestPageEntryFocus(") == 1, "one page-entry focus request")
    order = ["val shortcutBinding = remember(assembly)", "val keyboardOwned = {", "val input = DesktopWindowsVideoKeyInput(", "val shortcuts = DesktopWindowsVideoShortcutInput(",
             "windowKeyFallback?.registerLeaf {", "val controls = DesktopPlayerControlsBinding(", "}.focusable()) {", "LaunchedEffect(assembly, route, active, presentationAlive, pipActive) {",
             "if (active && !pipActive) {", "if (detailsOpen) detailsPanel()", "SideEffect {\n        shortcutBinding.legacyLayout",
             "audioLanguageMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }"]
    position = 0
    for item in order:
        found = leaf.find(item, position)
        if found < 0:
            problems.append("an order anchor is missing or out of order (binding, gate, input, router, registration, controls, viewport focus, page entry, "
                            "panel, SideEffect, dialogs): " + item)
            break
        position = found
    need("normalizeViewPointSegments" in leaf and leaf.count("import com.android.purebilibili.feature.video.ui.overlay.normalizeViewPointSegments\n") == 1,
         "the chapter normalizer is imported once")
    need("DesktopWindowsUxPlayerLayout.DESKTOP" not in code and "UxPlayerLayout.DESKTOP" not in code, "the leaf still has no DESKTOP branch of its own")
    need(source.hook_uses(leaf) == [], "no global hook")
    return problems


class LeafWiringTest(NeedleCase):
    def test_the_nine_hunks_are_anchored_and_each_block_occurs_once(self):
        self.assertEqual(9, len(V6_HUNKS))
        for label, before, after in V6_HUNKS:
            self.assertEqual(1, LEAF_TEXT.count("\n".join(after)), label)
            self.assertEqual(before[0], after[0], label + ": the first line is unchanged context")
            self.assertEqual(before[-1], after[-1], label + ": the last line is unchanged context")
        self.assertEqual(["chapter normalizer import", "shortcut binding holder", "shared keyboard gate", "window-level shortcut router", "composite registration",
                          "focus loss releases the router", "dispose closes the router", "page-entry focus", "effects bound by a SideEffect"],
                         [label for label, _, _ in V6_HUNKS])

    def test_the_hunks_do_not_overlap_the_ten_hunks_of_slice_v2(self):
        # V2's blocks are all still in the leaf as they were, so the two chains are independent and reverse in either order.
        for label, _before, after in HUNKS:
            self.assertEqual(1, LEAF_TEXT.count("\n".join(after)), label)

    def test_undoing_the_v6_hunks_gives_the_leaf_of_the_commit_before_the_slice_byte_for_byte(self):
        v2_state = reverse_v6(LEAF_TEXT)
        self.assertEqual(V2_STATE_LEAF_SHA256, hashlib.sha256(v2_state.encode("utf-8")).hexdigest(), "the LEGACY layout changed outside the recorded V6 hunks")
        self.assertEqual(BASE_SHA256, hashlib.sha256(reverse_apply(LEAF_TEXT).encode("utf-8")).hexdigest())

    def test_the_diff_is_small_and_exactly_the_recorded_size(self):
        import difflib
        base = reverse_v6(LEAF_TEXT).split("\n")
        now = LEAF_TEXT.split("\n")
        added = removed = 0
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, base, now, autojunk=False).get_opcodes():
            if tag != "equal":
                removed += i2 - i1
                added += j2 - j1
        self.assertEqual((V6_ADDED, V6_REMOVED), (added, removed))
        self.assertLessEqual(added + removed, 80)

    def test_the_v6_shape(self):
        self.assertEqual([], check_leaf_v6(LEAF_TEXT))

    def test_the_older_pins_still_hold(self):
        self.assertEqual([], check_legacy_branch(LEAF_TEXT))
        self.assertEqual([], check_must_keep(LEAF_TEXT))

    def test_the_legacy_bar_call_and_the_top_strip_are_untouched(self):
        self.assertEqual(1, LEAF_TEXT.count("DesktopWindowsVideoControlBar("))
        self.assertEqual(1, LEAF_TEXT.count("val legacyControlBar: @Composable () -> Unit = { DesktopWindowsVideoControlBar("))
        self.assertEqual(1, LEAF_TEXT.count("val legacyTopStrip: @Composable () -> Unit = { DesktopWindowsPlayerSurface(Modifier.fillMaxWidth()"))
        self.assertEqual("false", re.search(r"DESKTOP_PLAYER_OVERLAY_AVAILABLE = (true|false)", source.read(source.K / "ui/DesktopPlayerLayoutSelection.kt")).group(1))

    def test_the_effects_run_only_through_admitted_lambdas_and_never_reach_the_player(self):
        block = LEAF_TEXT[LEAF_TEXT.index("SideEffect {\n        shortcutBinding.legacyLayout"):LEAF_TEXT.index("audioLanguageMenu?.takeIf { shell.playback.isAudioSelectionCurrent(it) }")]
        code = source.mask_kotlin(block)
        for needle in ("native.togglePause", "native.setMuted", "native.setVolume", "native.seek", "assembly.", "MpvPlayer", "SwingPanel"):
            self.assertNotIn(needle, code, needle)
        # The only reads of the player are the position, duration and volume snapshot and the speed and subtitle state.
        self.assertEqual(2, len(re.findall(r"\bnative\.state\.value\b", code)))
        for needle in ("seekBy = { seconds -> if (current()) shell.playback.seekBy(seconds) }", "toggleDanmaku = { if (current()) actions.toggleDanmaku() }",
                       "togglePanel = { if (current()) detailsOpen = !detailsOpen }"):
            self.assertHas(block, needle)
        self.assertGreaterEqual(len(re.findall(r"\bcurrent\(\)", code)), 4)

    def test_the_flyout_flag_reads_the_dialogs_the_leaf_owns_and_nothing_else(self):
        self.assertHas(LEAF_TEXT, "shortcutBinding.flyoutOpen = showCollection || showPlaybackQueue || audioLanguageMenu != null || audioTrackMenu != null || interactionMode != null")
        chrome_held = "showCollection || showPlaybackQueue || audioLanguageMenu != null || audioTrackMenu != null || interactionMode != null ||"
        self.assertHas(LEAF_TEXT, chrome_held)

    def test_the_page_entry_request_is_inside_the_viewport_and_asks_the_editor_test_first(self):
        start = LEAF_TEXT.index("}.focusable()) {")
        end = LEAF_TEXT.index("if (active && !pipActive) {", start)
        block = LEAF_TEXT[start:end]
        self.assertHas(block, "LaunchedEffect(assembly, route, active, presentationAlive, pipActive) {",
                       "if (active && presentationAlive && !pipActive && current())",
                       "desktopWindowsRequestPageEntryFocus(desktopWindowsTextEditorFocused()) { viewportFocus.requestFocus() }")

    def test_the_text_editor_test_is_the_one_the_story_input_uses_and_no_compose_text_field_is_composed_by_the_page(self):
        story = source.read(source.K / "ui/DesktopStoryNativeInput.kt")
        self.assertIn("KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent", story)
        # Tripwire for the DESKTOP focus rule: today no Compose text editor lives in the ordinary-video page, every editor is an owned window of its own
        # (the active window then is not the main window). The slice that puts one into the page (the side panel, V7) must make the rule read its focus.
        for path in sorted((source.K / "ui").glob("DesktopWindowsVideo*.kt")) + [km.LEAF_KT]:
            code = source.mask_kotlin(source.read(path))
            self.assertIsNone(re.search(r"\b(?:Basic|Outlined)?TextField\(|\bCommentEmoteTextField\(", code), "%s composes a text field" % path.name)

    def test_the_decision_d4_and_the_dormant_desktop_branch_are_documented_in_the_router(self):
        self.assertIn("spec decision D4", INPUT_TEXT)
        self.assertEqual("LEGACY", model.load()["flags"]["fields"][1]["default"])

    def test_the_vendored_key_map_and_the_root_handler_are_untouched(self):
        self.assertEqual(VENDORED_SHA256, sha256_of(vk.VENDORED_KEY_POLICY))
        root = source.read(source.K / "DesktopShell.kt")
        self.assertEqual(1, root.count("performPlayerKey(resolvePlayerKeyAction(event, isTextInputActive = searchFocused))"))


# ---------------------------------------------------------------------------------------------------------------------------------------
# Release coupling with the restyled bar
# ---------------------------------------------------------------------------------------------------------------------------------------

class ReleaseCouplingTest(unittest.TestCase):
    def test_every_key_a_tooltip_of_the_bar_names_runs_an_effect_the_leaf_wires(self):
        wired = set(KINDS) - set(KEYS["not_performed"])
        for hint in CONTRACT["control_bar"]["hints"]:
            chord = hint["chord"]
            if chord == "Space":
                self.assertEqual("PLAY_PAUSE", hint["action"])  # Space stays with the existing input, which already runs it
                continue
            kind, arg, status, _paused = PLAYER_TABLE[chord]
            self.assertEqual("ACTIVE", status, chord)
            self.assertEqual(hint["action"], kind, chord)
            self.assertIn(kind, wired, "the bar names %s but no effect runs it" % chord)
            self.assertEqual(hint.get("arg", 0), arg, chord)

    def test_the_hinted_keys_are_not_navigation_keys_a_focused_bar_control_keeps(self):
        for hint in CONTRACT["control_bar"]["hints"]:
            self.assertNotIn(hint["chord"].split("+")[-1], NAVIGATION, hint["chord"])


# ---------------------------------------------------------------------------------------------------------------------------------------
# JVM tests
# ---------------------------------------------------------------------------------------------------------------------------------------

class JvmTestsTest(unittest.TestCase):
    def test_the_two_test_classes_exist_with_their_test_counts(self):
        for path, count in ((km.JVM_INPUT_TEST, 16), (km.JVM_EFFECTS_TEST, 16)):
            text = km.read(path)
            self.assertEqual(count, len(re.findall(r"(?m)^    @Test fun ", text)), path.name)
            self.assertTrue(text.startswith("@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)\n\n"
                                            "package com.bilipai.desktop.ui\n"))
            self.assertEqual(1, len(re.findall(r"(?m)^class \w+Test \{", text)))
        self.assertEqual(("com.bilipai.desktop.ui.DesktopWindowsVideoShortcutInputTest", "com.bilipai.desktop.ui.DesktopPlayerShortcutEffectsTest"), km.JVM_TEST_CLASSES)

    def test_they_need_no_window_no_gpu_and_no_network(self):
        for path in (km.JVM_INPUT_TEST, km.JVM_EFFECTS_TEST):
            code = source.mask_kotlin(km.read(path))
            for banned in ("ImageComposeScene", "java.net", "Thread.sleep", "System.getenv", "SwingPanel", "ComposeWindow", "JFrame", "MpvPlayer("):
                self.assertNotIn(banned, code, "%s uses %s" % (path.name, banned))

    def test_the_effects_test_reads_the_byte_identical_contract_copy(self):
        text = km.read(km.JVM_EFFECTS_TEST)
        self.assertIn('DesktopUxContract.section("player_keys")', text)
        self.assertIn("resolvePlayerKeyAction(event)", text)
        self.assertEqual((source.REPO / "desktop/src/test/resources/desktop-ux-contract.json").read_bytes(), model.CONTRACT_PATH.read_bytes())

    def test_the_router_test_runs_on_the_event_dispatch_thread_like_the_router_requires(self):
        text = km.read(km.JVM_INPUT_TEST)
        self.assertIn("EventQueue.invokeAndWait(block)", text)
        self.assertEqual(15, len(re.findall(r"(?m)^    @Test fun \w+\(\) = edt \{", text)))

    def test_the_rig_names_the_constructors_the_main_files_declare(self):
        effects = km.squash(km.read(km.JVM_EFFECTS_TEST))
        start = effects.index("val effects = DesktopPlayerShortcutEffects(")
        positions = [effects.index(name + " = ", start) for name in EFFECT_PARAMETERS]
        self.assertEqual(sorted(positions), positions)
        router = km.squash(km.read(km.JVM_INPUT_TEST))
        start = router.index("val input = DesktopWindowsVideoShortcutInput(")
        positions = [router.index(name + " = ", start) for name in ROUTER_PARAMETERS]
        self.assertEqual(sorted(positions), positions)
        binding = km.squash(km.read(km.JVM_EFFECTS_TEST))
        start = binding.index("val controls = DesktopPlayerControlsBinding(")
        positions = [binding.index(name + " = ", start) for name, _kind, _public in leaf_model.CONSTRUCTOR]
        self.assertEqual(sorted(positions), positions)

    def test_the_vendored_rows_of_the_contract_are_the_ones_the_jvm_test_expects(self):
        self.assertEqual(46, len(KEYS["vendored"]["bindings"]))
        self.assertEqual(22, len(KEYS["vendored"]["additions"]))
        self.assertIn('mapOf("kept" to 39, "changed" to 1, "not-taken" to 2, "dropped" to 4)', km.read(km.JVM_EFFECTS_TEST))
        self.assertIn("assertEquals(46, bindings.size)", km.read(km.JVM_EFFECTS_TEST))
        self.assertIn("assertEquals(22, additions.size)", km.read(km.JVM_EFFECTS_TEST))


# ---------------------------------------------------------------------------------------------------------------------------------------
# Hygiene
# ---------------------------------------------------------------------------------------------------------------------------------------

class HygieneTest(unittest.TestCase):
    def test_the_pure_file_is_pure_and_declares_the_expected_names(self):
        self.assertTrue(EFFECTS_TEXT.startswith("package com.bilipai.desktop.ui\n"))
        self.assertEqual(["import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout"], re.findall(r"(?m)^import .*$", EFFECTS_TEXT))
        code = source.mask_kotlin(EFFECTS_TEXT)
        for banned in ("androidx", "java.awt", "javax.swing", "KeyEvent", "Composable", "remember(", "SideEffect", "KeyFallback", "MpvPlayer", "SwingPanel",
                       "System.currentTimeMillis", "native.", "assembly."):
            self.assertNotIn(banned, code, banned)
        for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"!!", r"\bTODO\(", r"\bassert\("):
            self.assertIsNone(re.search(pattern, code), pattern)
        declared = re.findall(r"(?m)^(?:internal |private |public )?(?:const val|val|var|fun|class|object|enum class|data class) (\w+)", code)
        self.assertEqual(["DESKTOP_PLAYER_KEY_LETTERS", "DESKTOP_PLAYER_KEY_DIGITS", "DESKTOP_PLAYER_KEY_NUMPAD_DIGITS", "DESKTOP_PLAYER_KEY_FUNCTION_KEYS",
                          "DESKTOP_PLAYER_NAMED_KEY_CODES", "desktopPlayerKeyName", "DESKTOP_PLAYER_SPACE_KEY_NAME", "DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS",
                          "DESKTOP_PLAYER_VOLUME_MAX", "DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS", "DESKTOP_PLAYER_SPEED_EPSILON", "desktopPlayerShortcutRepeats",
                          "desktopPlayerChapterTarget", "desktopPlayerSpeedStep", "desktopPlayerShortcutOwnsFocus", "DesktopPlayerShortcutSnapshot",
                          "DesktopPlayerShortcutEffects", "DesktopWindowsVideoShortcutBinding"], declared)

    def test_the_router_file_imports_exactly_what_it_uses(self):
        self.assertEqual(["import androidx.compose.ui.input.key.*", "import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout",
                          "import java.awt.EventQueue", "import java.awt.KeyboardFocusManager", "import javax.swing.text.JTextComponent"],
                         re.findall(r"(?m)^import .*$", INPUT_TEXT))
        code = source.mask_kotlin(INPUT_TEXT)
        for banned in ("MpvPlayer", "SwingPanel", "native.", "assembly.", "addAWTEventListener", "KeyFallback", "registerLeaf", "System.currentTimeMillis"):
            self.assertNotIn(banned, code, banned)
        declared = re.findall(r"(?m)^(?:internal |private |public )?(?:class|fun|val|object) (\w+)", code)
        self.assertEqual(["DesktopWindowsVideoShortcutInput", "desktopWindowsTextEditorFocused", "desktopWindowsRequestPageEntryFocus"], declared)

    def test_both_files_pass_the_program_rules_and_hold_no_chinese(self):
        for path in (km.EFFECTS_KT, km.INPUT_KT):
            text = km.read(path)
            self.assertEqual([], source.banned_imports(text), path.name)
            self.assertEqual([], source.hook_uses(text), path.name)
            self.assertEqual([], source.key_fallback_uses(text), path.name)
            self.assertEqual([], source.cjk_positions(text), path.name)
            self.assertTrue(text.isascii(), path.name)
        self.assertIn(km.EFFECTS_KT, source.kotlin_files(source.STRICT_TEXT_GLOBS))
        self.assertIn(km.INPUT_KT, source.kotlin_files(source.STRICT_TEXT_GLOBS))
        self.assertIn("ui/DesktopWindowsVideoShortcutInput.kt", source.STRICT_TEXT_GLOBS)
        self.assertIn("ui/DesktopPlayer*.kt", source.BANNED_IMPORT_GLOBS)

    def test_the_new_top_level_names_are_declared_once_in_desktop_src(self):
        names = ("desktopPlayerKeyName", "DESKTOP_PLAYER_SPACE_KEY_NAME", "DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS", "DESKTOP_PLAYER_VOLUME_MAX",
                 "DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS", "DESKTOP_PLAYER_SPEED_EPSILON", "desktopPlayerShortcutRepeats", "desktopPlayerChapterTarget",
                 "desktopPlayerSpeedStep", "desktopPlayerShortcutOwnsFocus", "DesktopPlayerShortcutSnapshot", "DesktopPlayerShortcutEffects",
                 "DesktopWindowsVideoShortcutBinding", "DesktopWindowsVideoShortcutInput", "desktopWindowsTextEditorFocused",
                 "desktopWindowsRequestPageEntryFocus")
        declaration = re.compile(r"(?m)^(?:internal |private |public )?(?:const )?(?:class|object|interface|fun|val|var|typealias)\s+(%s)\b" % "|".join(names))
        found = []
        for path in sorted((source.REPO / "desktop/src").rglob("*.kt")):
            for match in declaration.finditer(source.mask_kotlin(km.read(path))):
                found.append((match.group(1), path.name))
        adapter = ("DesktopWindowsVideoShortcutInput", "desktopWindowsTextEditorFocused", "desktopWindowsRequestPageEntryFocus")
        expected = [(name, "DesktopWindowsVideoShortcutInput.kt" if name in adapter else "DesktopPlayerShortcutEffects.kt") for name in names]
        self.assertEqual(sorted(expected), sorted(found))

    def test_the_leaf_adds_no_top_level_declaration(self):
        pattern = re.compile(r"(?m)^(?:internal |private |public )?(?:data |enum |sealed )?(?:class|object|interface|fun|val|var|typealias)\s+(\w+)")
        self.assertEqual(pattern.findall(source.mask_kotlin(reverse_v6(LEAF_TEXT))), pattern.findall(source.mask_kotlin(LEAF_TEXT)))

    def test_every_file_of_the_slice_is_plain_lf(self):
        here = Path(__file__).resolve().parent
        files = [km.EFFECTS_KT, km.INPUT_KT, km.LEAF_KT, km.JVM_INPUT_TEST, km.JVM_EFFECTS_TEST, model.CONTRACT_PATH, model.KOTLIN_COPY_PATH, Path(__file__).resolve(),
                 here / "ux_player_keys_model.py", here / "ux_vendored_player_keys.py", here / "test_ux_leaf_layout_binding.py", here / "test_ux_leaf_anchor_guard.py"]
        source.assert_lf_only(self, *files)

    def test_the_python_files_hold_no_tab_and_the_vendored_extractor_is_ascii(self):
        here = Path(__file__).resolve().parent
        for path in (Path(__file__).resolve(), here / "ux_player_keys_model.py", here / "ux_vendored_player_keys.py"):
            self.assertNotIn("\t", path.read_bytes().decode("utf-8"), path.name)
        self.assertTrue((here / "ux_vendored_player_keys.py").read_bytes().decode("utf-8").isascii())


# ---------------------------------------------------------------------------------------------------------------------------------------
# Mutations
# ---------------------------------------------------------------------------------------------------------------------------------------

class MutationTest(unittest.TestCase):
    def mutated(self, text, old, new, count=1):
        self.assertIn(old, text, "the mutation anchor is gone: " + old)
        edited = text.replace(old, new, count)
        self.assertNotEqual(text, edited)
        return edited

    def test_every_check_passes_on_the_real_text(self):
        self.assertEqual([], check_effects(EFFECTS_TEXT))
        self.assertEqual([], check_router(INPUT_TEXT))
        self.assertEqual([], check_leaf_v6(LEAF_TEXT))

    def test_an_effect_that_changed_is_found(self):
        for old, new in (("coerceIn(0.0, DESKTOP_PLAYER_VOLUME_MAX)", "coerceAtLeast(0.0)"),
                         ("DesktopPlayerActionKind.MUTE -> { controls.mute(); true }", "DesktopPlayerActionKind.MUTE -> { true }"),
                         ("seekBy(action.arg.toDouble())", "seekBy(action.arg.toDouble() * 2)"),
                         ("if (!canMiniPlayer()) return false", ""),
                         ("duration * action.arg / 100.0", "duration * action.arg / 10.0"),
                         ("it > current + DESKTOP_PLAYER_SPEED_EPSILON", "it > current"),
                         ("positionMs - DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS", "positionMs"),
                         ("DesktopWindowsUxPlayerLayout.DESKTOP -> !textEditorFocused && !flyoutOpen", "DesktopWindowsUxPlayerLayout.DESKTOP -> !textEditorFocused")):
            self.assertTrue(check_effects(self.mutated(EFFECTS_TEXT, old, new)), old)

    def test_a_key_code_that_changed_is_found(self):
        edited = self.mutated(EFFECTS_TEXT, '525 to "Menu"', '524 to "Menu"')
        ranges, named = km.key_code_tables(edited)
        self.assertNotEqual({code: name for name, code in KEYS["key_codes"]["named"].items()}, named)
        edited = self.mutated(EFFECTS_TEXT, "= 112..123", "= 112..122")
        ranges, _named = km.key_code_tables(edited)
        self.assertNotEqual(tuple(KEYS["key_codes"]["function_keys"]), ranges["FUNCTION_KEYS"])

    def test_a_router_line_that_changed_is_found(self):
        for old, new in (("if (name == DESKTOP_PLAYER_SPACE_KEY_NAME) return false", ""),
                         ("if (name in DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS && controlHoldsFocus()) return false", ""),
                         ("if (action == null || currentSource() !== source) return false", "if (action == null) return false"),
                         ("return currentSource() === press.source && ownsFocus()", "return true"),
                         ("&& press.source === source && action == press.action", "&& action == press.action"),
                         ("held[code] = Held(source, action)", ""),
                         ("popupOpen = false, fullscreen = isFullscreen(), layout = layout()) == DesktopPlayerEscStep.NONE",
                          "popupOpen = false, fullscreen = isFullscreen(), layout = layout()) != DesktopPlayerEscStep.LEAVE_PAGE")):
            self.assertTrue(check_router(self.mutated(INPUT_TEXT, old, new)), old)

    def test_a_leaf_that_loses_a_v6_piece_is_found(self):
        for old, new in (("ownsFocus = keyboardOwned,", "ownsFocus = { true },"),
                         ("windowKeyFallback?.registerLeaf { event -> input.onKey(event) || shortcuts.onKey(event) }", "windowKeyFallback?.registerLeaf(input::onKey)"),
                         ("            shortcuts.close()\n", ""),
                         ("                shortcuts.releaseHeldInput()\n", ""),
                         ("shortcutBinding.legacyLayout = legacyChrome", "shortcutBinding.legacyLayout = true"),
                         ("val shortcutBinding = remember(assembly) { DesktopWindowsVideoShortcutBinding() }", "val shortcutBinding = DesktopWindowsVideoShortcutBinding()"),
                         ("desktopWindowsRequestPageEntryFocus(desktopWindowsTextEditorFocused()) { viewportFocus.requestFocus() }", "viewportFocus.requestFocus()")):
            edited = self.mutated(LEAF_TEXT, old, new)
            self.assertTrue(check_leaf_v6(edited), old)

    def test_a_desktop_branch_in_the_leaf_is_found(self):
        edited = self.mutated(LEAF_TEXT, "shortcutBinding.legacyLayout = legacyChrome",
                              "shortcutBinding.legacyLayout = windowsUxSettings.playerLayout == DesktopWindowsUxPlayerLayout.LEGACY || DesktopWindowsUxPlayerLayout.DESKTOP == null")
        self.assertTrue(check_leaf_v6(edited))

    def test_a_change_outside_the_recorded_hunks_breaks_the_identity(self):
        edited = self.mutated(LEAF_TEXT, "surface.isFocusable = true", "surface.isFocusable = false")
        self.assertNotEqual(V2_STATE_LEAF_SHA256, hashlib.sha256(reverse_v6(edited).encode("utf-8")).hexdigest())

    def test_a_hunk_that_no_longer_matches_is_reported_by_name(self):
        edited = self.mutated(LEAF_TEXT, "            shortcuts.close()\n", "            shortcuts.close(1)\n")
        with self.assertRaises(AssertionError) as caught:
            reverse_v6(edited)
        self.assertIn("dispose closes the router", str(caught.exception))

    def test_the_router_and_the_effects_constructor_orders_are_found_when_they_drift(self):
        edited = self.mutated(LEAF_TEXT, "            ownsFocus = keyboardOwned,\n            controlHoldsFocus = { topFocused || barInteraction.focused },\n",
                              "            controlHoldsFocus = { topFocused || barInteraction.focused },\n            ownsFocus = keyboardOwned,\n")
        self.assertNotEqual(list(ROUTER_PARAMETERS), [name for name, _ in call_arguments(edited, "DesktopWindowsVideoShortcutInput(")])
        edited = self.mutated(LEAF_TEXT, "currentSpeed = { state.speed },", "currentSpeed = { 1.0 },")
        self.assertNotEqual(km.squash(EFFECT_ARGUMENTS["currentSpeed"]), km.squash(dict(call_arguments(edited, "DesktopPlayerShortcutEffects("))["currentSpeed"]))


if __name__ == "__main__":
    unittest.main()
