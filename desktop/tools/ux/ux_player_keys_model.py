"""Reference and mirror model of the window-level player shortcuts (desktop UX program, slice V6). No JVM, no network.

The Kotlin files of the slice are ui/DesktopPlayerShortcutEffects.kt (pure: key names, effects, chapter and speed steps, the focus rule, the
binding) and ui/DesktopWindowsVideoShortcutInput.kt (the router: held presses, claims, Esc). Like ux_player_model.py this module holds

  * parsers for the marked key-code block and the constants of the Kotlin text, so a test compares the Kotlin numbers with the contract
    (section player_keys) instead of repeating them;
  * mirrors: line-by-line Python transliterations of the Kotlin functions and of the router's state machine, fed with the constants
    parsed from the Kotlin text. The suite pins the transliterated Kotlin lines as source needles, so editing either side without the
    other fails;
  * oracles: the same rules written from the spec prose or from the AWT documentation, in a different shape from the Kotlin (the AWT
    key-code table is typed from java.awt.event.KeyEvent, in hexadecimal, and compared with the numbers of the Kotlin block).

The suite compares mirror and oracle over sweeps and scenarios. That proves the Kotlin algorithm, as far as it is mirrored faithfully, and says
nothing about Kotlin compilation: the JVM test classes are the proof of the real code.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_player_model as pm  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

UI = source.K / "ui"
EFFECTS_KT = UI / "DesktopPlayerShortcutEffects.kt"
INPUT_KT = UI / "DesktopWindowsVideoShortcutInput.kt"
KEY_INPUT_KT = UI / "DesktopWindowsVideoKeyInput.kt"
FALLBACK_KT = UI / "DesktopWindowKeyFallback.kt"
LEAF_KT = UI / "DesktopWindowsVideoPhysicalLeaf.kt"
ZOOM_INPUT_KT = source.K / "appearance/DesktopWindowsDisplayScaleInput.kt"
JVM_INPUT_TEST = source.KT / "ui" / "DesktopWindowsVideoShortcutInputTest.kt"
JVM_EFFECTS_TEST = source.KT / "ui" / "DesktopPlayerShortcutEffectsTest.kt"
JVM_TEST_CLASSES = ("com.bilipai.desktop.ui.DesktopWindowsVideoShortcutInputTest", "com.bilipai.desktop.ui.DesktopPlayerShortcutEffectsTest")

read = source.read
squash = shell.squash
marked_block = shell.marked_block


def player_keys(contract=None):
    return (model.load() if contract is None else contract)["player_keys"]


# --------------------------------------------------------------------------------------
# Kotlin parsers
# --------------------------------------------------------------------------------------

def key_code_tables(text):
    """({LETTERS, DIGITS, NUMPAD_DIGITS, FUNCTION_KEYS: (first, last)}, {code: name}) of the marked block of the effects file."""
    block = squash(marked_block(text, "PLAYER-KEY-CODES"))
    ranges = {name: (int(first), int(last)) for name, first, last in re.findall(r"private val DESKTOP_PLAYER_KEY_(\w+) = (\d+)\.\.(\d+)", block)}
    named = {int(code): name for code, name in re.findall(r'(\d+) to "(\w+)"', block)}
    return ranges, named


def set_of_strings(text, name):
    """The strings of `val NAME: Set<String> = setOf("a", "b")`."""
    match = re.search(r"val %s: Set<String> = setOf\(([^)]*)\)" % re.escape(name), text)
    if match is None:
        raise AssertionError("no set " + name)
    return re.findall(r'"([^"]*)"', match.group(1))


def action_kinds_of_perform(text):
    """[kind names] in the order the `when` of DesktopPlayerShortcutEffects.perform lists them (every branch label, once each)."""
    start = text.index("fun perform(action: DesktopPlayerAction): Boolean = when (action.kind) {")
    end = text.index("\n    private fun seekChapter", start)
    return re.findall(r"DesktopPlayerActionKind\.([A-Z_]+)\b(?=\s*(?:,|->))", text[start:end])


# --------------------------------------------------------------------------------------
# Key names
# --------------------------------------------------------------------------------------

def key_name_kt(code, ranges, named):
    """desktopPlayerKeyName."""
    if ranges["LETTERS"][0] <= code <= ranges["LETTERS"][1] or ranges["DIGITS"][0] <= code <= ranges["DIGITS"][1]:
        return chr(code)
    if ranges["NUMPAD_DIGITS"][0] <= code <= ranges["NUMPAD_DIGITS"][1]:
        return "Numpad%d" % (code - ranges["NUMPAD_DIGITS"][0])
    if ranges["FUNCTION_KEYS"][0] <= code <= ranges["FUNCTION_KEYS"][1]:
        return "F%d" % (code - ranges["FUNCTION_KEYS"][0] + 1)
    return named.get(code)


# The oracle: java.awt.event.KeyEvent virtual key codes in hexadecimal, as the JDK documents them, and the chord name each one has.
AWT_KEYS = {
    "VK_BACK_SPACE": (0x08, "Backspace"), "VK_ENTER": (0x0A, "Enter"), "VK_ESCAPE": (0x1B, "Esc"), "VK_SPACE": (0x20, "Space"),
    "VK_END": (0x23, "End"), "VK_HOME": (0x24, "Home"), "VK_LEFT": (0x25, "Left"), "VK_UP": (0x26, "Up"), "VK_RIGHT": (0x27, "Right"),
    "VK_DOWN": (0x28, "Down"), "VK_COMMA": (0x2C, "Comma"), "VK_MINUS": (0x2D, "Minus"), "VK_PERIOD": (0x2E, "Period"),
    "VK_SLASH": (0x2F, "Slash"), "VK_EQUALS": (0x3D, "Equals"), "VK_OPEN_BRACKET": (0x5B, "LeftBracket"),
    "VK_CLOSE_BRACKET": (0x5D, "RightBracket"), "VK_ADD": (0x6B, "Add"), "VK_SUBTRACT": (0x6D, "Subtract"),
    "VK_PLUS": (0x209, "Plus"), "VK_CONTEXT_MENU": (0x20D, "Menu"),
}
for _digit in range(10):
    AWT_KEYS["VK_%d" % _digit] = (0x30 + _digit, str(_digit))
    AWT_KEYS["VK_NUMPAD%d" % _digit] = (0x60 + _digit, "Numpad%d" % _digit)
for _index in range(26):
    AWT_KEYS["VK_" + chr(ord("A") + _index)] = (0x41 + _index, chr(ord("A") + _index))
for _index in range(12):
    AWT_KEYS["VK_F%d" % (_index + 1)] = (0x70 + _index, "F%d" % (_index + 1))


def oracle_key_name(code):
    names = [name for _vk, (vk_code, name) in AWT_KEYS.items() if vk_code == code]
    assert len(names) <= 1, (code, names)
    return names[0] if names else None


# --------------------------------------------------------------------------------------
# Pure functions
# --------------------------------------------------------------------------------------

def repeats_kt(kind, repeat_kinds=("SEEK", "VOLUME")):
    """desktopPlayerShortcutRepeats."""
    return kind in repeat_kinds


def chapter_target_kt(starts, position_ms, direction, margin):
    """desktopPlayerChapterTarget."""
    if not starts:
        return None
    if direction > 0:
        return next((start for start in starts if start > position_ms), None)
    earlier = [start for start in starts if start < position_ms - margin]
    if earlier:
        return earlier[-1]
    return 0 if position_ms > margin else None


def oracle_chapter_target(starts, position_ms, direction, margin):
    """The jump from the spec prose: next = the closest start ahead; previous = the closest start that is more than the margin behind,
    else the video start when the position is past the margin; nothing without chapters or at the very start."""
    if not starts:
        return None
    if direction > 0:
        ahead = sorted(start for start in starts if start - position_ms > 0)
        return ahead[0] if ahead else None
    behind = sorted((position_ms - start for start in starts if position_ms - start > margin))
    if behind:
        return position_ms - behind[0]
    return 0 if position_ms - margin > 0 else None


def speed_step_kt(options, current, direction, epsilon):
    """desktopPlayerSpeedStep."""
    if direction > 0:
        higher = [value for value in options if value > current + epsilon]
        return min(higher) if higher else None
    lower = [value for value in options if value < current - epsilon]
    return max(lower) if lower else None


def oracle_speed_step(options, current, direction, epsilon):
    """The neighbour of the current speed in the sorted list of distinct speeds, where speeds within epsilon are the same speed."""
    ordered = []
    for value in sorted(options):
        if not ordered or value - ordered[-1] > epsilon / 4:
            ordered.append(value)
    if direction > 0:
        for value in ordered:
            if value - current > epsilon:
                return value
        return None
    for value in reversed(ordered):
        if current - value > epsilon:
            return value
    return None


def owns_focus_kt(layout, legacy_gate, text_editor_focused, flyout_open):
    """desktopPlayerShortcutOwnsFocus."""
    if layout == "LEGACY":
        return legacy_gate
    return not text_editor_focused and not flyout_open


def oracle_owns_focus(layout, legacy_gate, text_editor_focused, flyout_open):
    """Spec 6.6 as a table: LEGACY is the old gate whatever else is true; DESKTOP is free unless an editor or a flyout has the keys."""
    if layout == "LEGACY":
        return {True: True, False: False}[legacy_gate]
    return not (text_editor_focused or flyout_open)


# --------------------------------------------------------------------------------------
# Effects
# --------------------------------------------------------------------------------------

class Host:
    """The leaf's side of the effects: a fake player and a recorder of every admitted action that an effect calls."""

    def __init__(self, position=100.0, duration=600.0, volume=50.0, speed=1.0, speeds=(0.5, 1.0, 1.5, 2.0), starts=(), has_part=(True, True),
                 can_mini_player=True, subtitles=True, screenshot=True):
        self.events = []
        self.position, self.duration, self.volume, self.speed = position, duration, volume, speed
        self.speeds, self.starts, self.has_part = list(speeds), list(starts), has_part
        self.can_mini_player, self.subtitles, self.screenshot = can_mini_player, subtitles, screenshot

    def record(self, name, *args):
        self.events.append((name,) + args)


def perform_kt(kind, arg, host, c):
    """DesktopPlayerShortcutEffects.perform. c holds the constants of the Kotlin file. Returns whether the press was acted on."""
    if kind == "PLAY_PAUSE":
        host.record("playPause")
        return True
    if kind == "SEEK":
        host.record("seekBy", float(arg))
        return True
    if kind == "CHAPTER":
        starts = list(host.starts)
        target = chapter_target_kt(starts, int(host.position * 1000.0), arg, c["DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS"])
        if target is None:
            return False
        if target in starts:
            host.record("seekToChapter", target)
        else:
            host.record("seek", target / 1000.0)
        return True
    if kind == "VOLUME":
        host.record("volume", min(max(host.volume + arg, 0.0), c["DESKTOP_PLAYER_VOLUME_MAX"]))
        return True
    if kind == "MUTE":
        host.record("mute")
        return True
    if kind == "FULLSCREEN_TOGGLE":
        host.record("fullscreen")
        return True
    if kind == "SIDE_PANEL_OR_CINEMA":
        host.record("togglePanel")
        return True
    if kind == "MINI_PLAYER":
        if not host.can_mini_player:
            return False
        host.record("pictureInPicture")
        return True
    if kind == "SUBTITLE_TOGGLE":
        if not host.subtitles:
            return False
        host.record("toggleSubtitles")
        return True
    if kind == "DANMAKU_TOGGLE":
        host.record("toggleDanmaku")
        return True
    if kind == "SCREENSHOT":
        if not host.screenshot:
            return False
        host.record("takeScreenshot")
        return True
    if kind == "PART":
        forward = arg > 0
        if not (host.has_part[1] if forward else host.has_part[0]):  # has_part is (has previous, has next)
            return False
        host.record("next" if forward else "previous")
        return True
    if kind in ("SEEK_PERCENT", "SEEK_END"):
        duration = host.duration
        if duration != duration or duration in (float("inf"), float("-inf")) or duration <= 0.0:
            return False
        host.record("seek", duration * arg / 100.0 if kind == "SEEK_PERCENT" else duration)
        return True
    if kind == "SEEK_START":
        host.record("seek", 0.0)
        return True
    if kind == "SPEED_STEP":
        target = speed_step_kt(host.speeds, host.speed, arg, c["DESKTOP_PLAYER_SPEED_EPSILON"])
        if target is None:
            return False
        host.record("speed", target)
        return True
    if kind == "SPEED_SET":
        host.record("speed", float(arg))
        return True
    return False  # ESC_CHAIN, VIDEO_INFO, SUBTITLE_FLYOUT, FRAME_STEP, CONTEXT_MENU, SHORTCUT_HELP


def oracle_effect(kind, arg, host, contract):
    """What an effect does, written from the description of each action: (acted on, recorded events)."""
    keys = player_keys(contract)
    if kind in keys["not_performed"]:
        return False, []
    if kind == "PLAY_PAUSE":
        return True, [("playPause",)]
    if kind == "SEEK":
        return True, [("seekBy", float(arg))]
    if kind == "MUTE":
        return True, [("mute",)]
    if kind == "FULLSCREEN_TOGGLE":
        return True, [("fullscreen",)]
    if kind == "SIDE_PANEL_OR_CINEMA":
        return True, [("togglePanel",)]
    if kind == "DANMAKU_TOGGLE":
        return True, [("toggleDanmaku",)]
    if kind == "SPEED_SET":
        return True, [("speed", float(arg))]
    if kind == "VOLUME":
        ceiling = keys["volume"]["max"]
        floor = keys["volume"]["min"]
        return True, [("volume", float(sorted((floor, host.volume + arg, ceiling))[1]))]
    if kind == "MINI_PLAYER":
        return (True, [("pictureInPicture",)]) if host.can_mini_player else (False, [])
    if kind == "SUBTITLE_TOGGLE":
        return (True, [("toggleSubtitles",)]) if host.subtitles else (False, [])
    if kind == "SCREENSHOT":
        return (True, [("takeScreenshot",)]) if host.screenshot else (False, [])
    if kind == "PART":
        available = host.has_part[1] if arg > 0 else host.has_part[0]
        return (True, [("next" if arg > 0 else "previous",)]) if available else (False, [])
    if kind == "SEEK_START":
        return True, [("seek", 0.0)]
    if kind in ("SEEK_PERCENT", "SEEK_END"):
        if not (host.duration > 0.0 and host.duration != float("inf")):
            return False, []
        return True, [("seek", host.duration * (arg / 100.0 if kind == "SEEK_PERCENT" else 1.0))]
    if kind == "SPEED_STEP":
        target = oracle_speed_step(host.speeds, host.speed, arg, keys["speed_epsilon"])
        return (True, [("speed", target)]) if target is not None else (False, [])
    if kind == "CHAPTER":
        position = int(host.position * 1000.0)
        target = oracle_chapter_target(list(host.starts), position, arg, keys["chapter_back_margin_ms"])
        if target is None:
            return False, []
        return True, [("seekToChapter", target)] if target in host.starts else [("seek", target / 1000.0)]
    raise AssertionError("no oracle for " + kind)


# --------------------------------------------------------------------------------------
# The router (DesktopWindowsVideoShortcutInput.kt)
# --------------------------------------------------------------------------------------

class RouterHost:
    """The capabilities the router gets from the leaf, as plain attributes a scenario can change."""

    def __init__(self):
        self.source = object()
        self.owns = True
        self.control_holds_focus = False
        self.text_editor = False
        self.fullscreen = False
        self.paused = False
        self.layout = "LEGACY"
        self.accept = True
        self.performed = []

    def perform(self, kind, arg):
        self.performed.append((kind, arg))
        return self.accept


class RouterModel:
    """DesktopWindowsVideoShortcutInput.onKey, line by line. events are (code, 'down' | 'up', modifiers) with modifiers a set of Ctrl, Alt,
    Shift and Meta."""

    def __init__(self, host, ranges, named, table, reserved, zoom, navigation, repeat_kinds):
        self.host, self.ranges, self.named = host, ranges, named
        self.table, self.reserved, self.zoom = table, reserved, zoom
        self.navigation, self.repeat_kinds = navigation, repeat_kinds
        self.closed = False
        self.held = {}

    def resolve(self, name, modifiers):
        return pm.resolve_player_key_kt(self.table, self.reserved, self.zoom, name, "Ctrl" in modifiers, "Alt" in modifiers, "Shift" in modifiers,
                                        "Meta" in modifiers, True, self.host.text_editor, self.host.paused)

    def on_key(self, code, kind="down", modifiers=()):
        host = self.host
        if self.closed:
            return False
        name = key_name_kt(code, self.ranges, self.named)
        if name is None:
            return False
        if name == "Space":
            return False
        if kind == "up":
            press = self.held.pop(code, None)
            if press is None:
                return False
            return host.source is press[0] and host.owns
        if kind != "down" or not host.owns:
            return False
        if name in self.navigation and host.control_holds_focus:
            return False
        source = host.source
        if source is None:
            return False
        action = self.resolve(name, modifiers)
        press = self.held.get(code)
        if press is not None:
            if repeats_kt(press[1][0], self.repeat_kinds) and press[0] is source and action == press[1]:
                host.perform(*press[1])
            return True
        if action is None or host.source is not source:
            return False
        if action[0] == "ESC_CHAIN":
            handled = pm.esc_step_kt(False, host.fullscreen, host.layout) == "NONE"
        else:
            handled = host.perform(*action)
        if not handled:
            return False
        self.held[code] = (source, action)
        return True

    def release_held_input(self):
        self.held.clear()

    def close(self):
        self.closed = True
        self.release_held_input()
