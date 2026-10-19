"""Step 0 of slice V6 (spec 6.5): the vendored key map against the key table of the desktop router. No JVM, no network.

The vendored player keeps its keyboard map in resolvePlayerKeyAction (app/.../feature/video/player/PlayerKeyboardPolicy.kt). The desktop
router (ui/DesktopWindowsVideoShortcutInput.kt, resolving through the X1b table in ui/DesktopPlayerShortcutPolicy.kt) is NOT a superset of
that map, and spec 6.5 says so. This module parses the real vendored function, resolves every binding it finds through a transliteration
of the real router resolver, and sorts each one into exactly one of

  kept       the router resolves the same chord to the same action
  changed    the router resolves the chord to a different action (L: the phone screen lock became +10 s)
  not-taken  the router does not take the chord at all, though the vendored map has an action for it (Enter, decision D16)
  dropped    the vendored action has no counterpart on the desktop (the engagement keys, the screen lock)

A binding that is none of "kept" and has no entry in INTENTIONAL is an error, and so is an entry that no vendored binding uses any more, or
whose status does not match what the router does. The table is emitted into the shared contract (section player_keys, key vendored),
so the Kotlin test can run the real vendored resolver and the router side by side on the same rows:

    python desktop/tools/ux/ux_vendored_player_keys.py          print the table
    python desktop/tools/ux/ux_vendored_player_keys.py --check  exit 1 when the contract holds a stale table
    python desktop/tools/ux/ux_vendored_player_keys.py --write  rewrite the contract and its Kotlin copy

Besides the bindings the table lists the state rules of the vendored function (key press only, picture-in-picture, text input, screen lock,
the Ctrl/Alt/Meta guard) and the additions: rows of the router table that the vendored map has no binding for.
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_player_model as pm  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

VENDORED_KEY_POLICY = pm.VENDORED_KEY_POLICY
FUNCTION = "resolvePlayerKeyAction"

# The intentional differences, keyed by the chord of the vendored binding: (status, reason). Nothing else may differ.
INTENTIONAL = {
    "L": ("changed", "L was the phone screen lock; spec 6.5 makes J and L mirror each other, minus and plus ten seconds"),
    "Enter": ("not-taken", "decision D16: Enter is not taken by the router; it falls to Root, whose vendored map still toggles fullscreen"),
    "NumpadEnter": ("not-taken", "the router names both Enter keys Enter (one AWT key code) and never takes it, decision D16"),
    "Q": ("dropped", "the engagement keys are a non-goal (spec 1.5); the desktop Root handler never acted on this action"),
    "W": ("dropped", "the engagement keys are a non-goal (spec 1.5); the desktop Root handler never acted on this action"),
    "E": ("dropped", "the engagement keys are a non-goal (spec 1.5); the desktop Root handler never acted on this action"),
    "R": ("dropped", "the engagement keys are a non-goal (spec 1.5); the desktop Root handler never acted on this action"),
}

# The state rules of the vendored function: (the rule as the vendored code states it, what the router does, status, needle in the code).
STATE_RULES = (
    ("only a key press is read", "the resolver reads key presses only; the release of a claimed press is consumed by the input",
     "kept", "if (event.type != KeyEventType.KeyDown) return null"),
    ("no key in picture-in-picture", "the leaf claims no key while the page is not the current source, which includes picture-in-picture",
     "kept", "if (isInPipMode || isTextInputActive) return null"),
    ("no key while a text input is active", "the resolver gets textEditorFocused and the leaf passes the focus owner test of a Swing text component",
     "kept", "if (isInPipMode || isTextInputActive) return null"),
    ("screen locked: only L, which unlocks", "there is no screen lock on the desktop",
     "dropped", "if (isScreenLocked) {"),
    ("Ctrl, Alt or Meta held: no action", "the Ctrl and Alt chords of the table are claimed (Ctrl+Left, Ctrl+Right, Ctrl+B, Alt+Enter); Meta and Ctrl+Alt never",
     "changed", "if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) { return null }"),
)

_NAMED = {"Spacebar": "Space", "DirectionLeft": "Left", "DirectionRight": "Right", "DirectionUp": "Up", "DirectionDown": "Down",
          "Enter": "Enter", "NumPadEnter": "NumpadEnter", "LeftBracket": "LeftBracket", "RightBracket": "RightBracket"}
_DIGITS = {"Zero": "0", "One": "1", "Two": "2", "Three": "3", "Four": "4", "Five": "5", "Six": "6", "Seven": "7", "Eight": "8", "Nine": "9"}


def key_name(compose_name):
    """The chord spelling of a Compose Key constant. A key the table does not know is an error, so a new vendored key is reviewed."""
    if compose_name in _NAMED:
        return _NAMED[compose_name]
    if compose_name in _DIGITS:
        return _DIGITS[compose_name]
    pad = re.fullmatch(r"NumPad(\d)", compose_name)
    if pad:
        return "Numpad" + pad.group(1)
    if re.fullmatch(r"[A-Z]", compose_name):
        return compose_name
    raise AssertionError("an unknown key in the vendored map: Key." + compose_name)


def _matching(code, open_index):
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] == "{":
            depth += 1
        elif code[index] == "}":
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError("an unbalanced brace in the vendored function")


def _constants(text):
    found = {}
    for name, value in re.findall(r"const val (\w+) = (-?[\d_]+)L\b", text):
        found[name] = int(value.replace("_", ""))
    return found


def _number(expression, constants):
    expression = expression.strip()
    sign = 1
    if expression.startswith("-"):
        sign, expression = -1, expression[1:].strip()
    if expression in constants:
        return sign * constants[expression]
    return sign * float(expression.rstrip("fF"))


def _arguments(name, argument, constants):
    """(action name, argument in the unit of the router table or None) of one vendored action expression."""
    if name == "SeekRelative":
        millis = _number(argument, constants)
        assert millis == int(millis) and int(millis) % 1000 == 0, millis
        return name, int(millis) // 1000
    if name == "SeekPercent":
        return name, int(round(_number(argument, constants) * 100))
    if name == "SetSpeed":
        speed = _number(argument, constants)
        assert speed == int(speed), speed
        return name, int(speed)
    assert argument is None, (name, argument)
    return name, None


def function_body(text):
    code = source.mask_kotlin(text)
    start = code.index("fun %s(" % FUNCTION)
    begin = code.index("{", code.index("PlayerKeyAction?", start))
    end = _matching(code, begin)
    return text[begin + 1:end], code[begin + 1:end]


def _branches(code, constants):
    """[(compose key names, action name, argument)] of the branches of one when block (code is the masked block)."""
    found = []
    for line in code.splitlines():
        match = re.match(r"\s*((?:Key\.\w+\s*,\s*)*Key\.\w+)\s*->\s*PlayerKeyAction\.(\w+)(?:\((.*)\))?\s*$", line)
        if match:
            keys = re.findall(r"Key\.(\w+)", match.group(1))
            name, argument = _arguments(match.group(2), match.group(3), constants)
            found.append((keys, name, argument))
        elif not re.match(r"\s*(else\s*->\s*null)?\s*$", line):
            raise AssertionError("a branch the extractor does not understand: " + line.strip())
    return found


def extract(text=None):
    """{'bindings': [(chord, action, argument)], 'rules': {...}} of the real vendored function. Raises AssertionError when it changed shape."""
    text = source.read(VENDORED_KEY_POLICY) if text is None else text
    constants = _constants(text)
    body, code = function_body(text)
    squashed = re.sub(r"\s+", " ", code)
    for _rule, _router, _status, needle in STATE_RULES:
        if re.sub(r"\s+", " ", needle) not in squashed:
            raise AssertionError("the vendored function no longer holds: " + needle)
    locked = re.search(r"if \(isScreenLocked\) \{ return if \(event\.key == Key\.(\w+) && !event\.isCtrlPressed && !event\.isAltPressed && "
                       r"!event\.isMetaPressed\) \{ PlayerKeyAction\.(\w+) \} else \{ null \} \}", squashed)
    if locked is None:
        raise AssertionError("the screen-locked block of the vendored function has another shape")
    shift_open = code.index("if (shift) {")
    shift_close = _matching(code, shift_open + len("if (shift) "))
    shift_block = code[shift_open:shift_close]
    after = code[shift_close:]
    assert code.count("when (event.key) {") == 2, "the vendored function holds other than two key maps"
    shift_when = shift_block.index("when (event.key) {") + len("when (event.key) ")
    shift_text = shift_block[shift_when:_matching(shift_block, shift_when) + 1]
    final_when = after.index("when (event.key) {") + len("when (event.key) ")
    final_text = after[final_when:_matching(after, final_when) + 1]
    bindings = []
    for prefix, block in (("Shift+", shift_text), ("", final_text)):
        for keys, name, argument in _branches(block[1:-1], constants):
            for compose_name in keys:
                bindings.append((prefix + key_name(compose_name), name, argument))
    chords = [chord for chord, _name, _argument in bindings]
    assert len(chords) == len(set(chords)), "a chord is bound twice in the vendored function"
    return {"bindings": bindings, "locked": (key_name(locked.group(1)), locked.group(2)), "constants": constants}


def equivalent(name, argument):
    """The router action (kind, argument) that a vendored action stands for, or None when the desktop has no counterpart."""
    step = model.load()["pointer"]["volume_step"]
    table = {
        "PlayPause": ("PLAY_PAUSE", 0), "ToggleMute": ("MUTE", 0), "ToggleFullscreen": ("FULLSCREEN_TOGGLE", 0),
        "ToggleDanmaku": ("DANMAKU_TOGGLE", 0), "TakeScreenshot": ("SCREENSHOT", 0), "PreviousPart": ("PART", -1),
        "NextPart": ("PART", 1), "VolumeUp": ("VOLUME", step), "VolumeDown": ("VOLUME", -step),
    }
    if name in table:
        return table[name]
    if name == "SeekRelative":
        return ("SEEK", argument)
    if name == "SeekPercent":
        return ("SEEK_PERCENT", argument)
    if name == "SetSpeed":
        return ("SPEED_SET", argument)
    return None


class Router:
    """The router's resolver, transliterated from DesktopPlayerShortcutPolicy.kt by ux_player_model, fed with the Kotlin table itself."""

    def __init__(self):
        text = pm.read(pm.SHORTCUT_KT)
        self.table = pm.player_table(pm.player_rows(text))
        shell_text = pm.read(shell.SHORTCUTS_KT)
        self.reserved = set(shell.string_set(shell_text, "DESKTOP_SHELL_RESERVED_CHORDS"))
        self.zoom = set(shell.string_set(shell_text, "DESKTOP_SHELL_ZOOM_KEYS"))

    def resolve(self, chord):
        shift = chord.startswith("Shift+")
        key = chord[len("Shift+"):] if shift else chord
        key = "Enter" if key == "NumpadEnter" else key  # both Enter keys are one AWT key code
        return pm.resolve_player_key_kt(self.table, self.reserved, self.zoom, key, shift=shift)

    def chord_of(self, chord):
        """The chord the router sees for a vendored chord: a keypad digit is the digit, both Enter keys are Enter."""
        shift = "Shift+" if chord.startswith("Shift+") else ""
        key = chord[len("Shift+"):] if shift else chord
        pad = re.fullmatch(r"Numpad(\d)", key)
        return shift + (pad.group(1) if pad else "Enter" if key == "NumpadEnter" else key)


def vendored_object(name, argument):
    return {"action": name} if argument is None else {"action": name, "arg": argument}


def router_object(resolved):
    return None if resolved is None else {"action": resolved[0], "arg": resolved[1]}


def rows(extracted=None, router=None):
    """The classified bindings, in the order of the vendored source. Raises AssertionError for an unlisted or a wrongly listed difference."""
    extracted = extract() if extracted is None else extracted
    router = Router() if router is None else router
    out, used = [], set()
    for chord, name, argument in extracted["bindings"]:
        want = equivalent(name, argument)
        got = router.resolve(chord)
        row = {"chord": chord, "vendored": vendored_object(name, argument)}
        if want is not None and got == want:
            assert chord not in INTENTIONAL, "%s is kept, so it must not be listed as a difference" % chord
            row.update(status="kept", router=router_object(got))
        else:
            if chord not in INTENTIONAL:
                raise AssertionError("the vendored binding %s (%s) is neither kept nor listed as an intentional difference; the router does %r"
                                     % (chord, name, got))
            status, reason = INTENTIONAL[chord]
            used.add(chord)
            if status == "changed":
                assert got is not None and got != want, (chord, got, want)
            elif status == "not-taken":
                assert got is None and want is not None, (chord, got, want)
            elif status == "dropped":
                assert got is None and want is None, (chord, got, want)
            else:
                raise AssertionError("unknown status " + status)
            row.update(status=status, router=router_object(got), reason=reason)
        out.append(row)
    stale = sorted(set(INTENTIONAL) - used)
    if stale:
        raise AssertionError("intentional differences that no vendored binding has any more: %s" % stale)
    return out


def additions(extracted=None, router=None):
    """Rows of the router table that the vendored map has no binding for, in the order of the contract."""
    extracted = extract() if extracted is None else extracted
    router = Router() if router is None else router
    seen = {router.chord_of(chord) for chord, _name, _argument in extracted["bindings"]}
    out = []
    for row in model.load()["shortcuts"]["rows"]:
        if row["scope"] != "player" or row["chord"] in seen:
            continue
        item = {"chord": row["chord"], "action": row["action"], "arg": row.get("arg", 0)}
        item["status"] = {"existing-at-root": "existing-at-root", "omitted-until-mpv-api": "omitted-until-mpv-api"}.get(row.get("status"), "active")
        out.append(item)
    return out


def state_rules():
    return [{"vendored": rule, "router": router_text, "status": status} for rule, router_text, status, _needle in STATE_RULES]


def section(extracted=None, router=None):
    """The value of player_keys.vendored in the contract."""
    extracted = extract() if extracted is None else extracted
    router = Router() if router is None else router
    return {
        "source": "app/src/main/java/com/android/purebilibili/feature/video/player/PlayerKeyboardPolicy.kt, resolvePlayerKeyAction",
        "bindings": rows(extracted, router),
        "state_rules": state_rules(),
        "additions": additions(extracted, router),
    }


def merged(contract):
    """A copy of the contract with player_keys.vendored regenerated from the real vendored source."""
    out = json.loads(json.dumps(contract))
    out["player_keys"]["vendored"] = section()
    return out


def problems():
    contract = model.load()
    if "player_keys" not in contract:
        return ["the contract has no player_keys section"]
    return [] if contract["player_keys"].get("vendored") == section() else ["player_keys.vendored is stale (run ux_vendored_player_keys.py --write)"]


def markdown():
    lines = ["| chord | vendored | status | router | reason |", "|---|---|---|---|---|"]
    for row in rows():
        vendored = row["vendored"]["action"] + ("" if "arg" not in row["vendored"] else " %s" % row["vendored"]["arg"])
        router = "-" if row["router"] is None else "%s %s" % (row["router"]["action"], row["router"]["arg"])
        lines.append("| %s | %s | %s | %s | %s |" % (row["chord"], vendored, row["status"], router, row.get("reason", "")))
    lines += ["", "State rules of the vendored function:"]
    lines += ["- %s: %s (%s)" % (rule["vendored"], rule["router"], rule["status"]) for rule in state_rules()]
    lines += ["", "Rows of the router table without a vendored binding:"]
    lines += ["- %s: %s %s (%s)" % (item["chord"], item["action"], item["arg"], item["status"]) for item in additions()]
    return "\n".join(lines)


def write():
    text = model.canonical_text(merged(model.load())).encode("utf-8")
    for path in (model.CONTRACT_PATH, model.KOTLIN_COPY_PATH):
        Path(path).write_bytes(text)


def main(argv):
    if "--write" in argv:
        write()
        print("contract and Kotlin copy rewritten")
        return 0
    if "--check" in argv:
        found = problems()
        for line in found:
            print(line)
        return 1 if found else 0
    print(markdown())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
