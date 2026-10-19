"""Python reference model of slice V2: the player layout selection and the admitted actions of the player controls. No JVM.

The Kotlin files are ui/DesktopPlayerLayoutSelection.kt (the pure layout selection) and ui/DesktopPlayerControlsBinding.kt (the
admitted actions). Both are line-by-line transliterated here. The model reads the player_layout flag from the shared contract
(desktop-ux-contract.json, flags section), so a value that drifts between the contract, the enum and the selection fails in
test_ux_leaf_layout_binding.py or in DesktopPlayerLayoutSelectionTest, which reads the byte-identical copy of the contract.

The oracle (ORACLE_RULES, expected_events) is written from the description of the actions, in a different shape from the model: a
table of when an action runs and what it does, not a transliteration. The suite compares the model with the oracle on every
combination of current page, command gate and muted flag, and compares the model with the Kotlin text line by line.
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_source as source  # noqa: E402

CONTRACT_PATH = HERE / "desktop-ux-contract.json"
UI = source.K / "ui"
LEAF_KT = UI / "DesktopWindowsVideoPhysicalLeaf.kt"
BINDING_KT = UI / "DesktopPlayerControlsBinding.kt"
SELECTION_KT = UI / "DesktopPlayerLayoutSelection.kt"
SETTINGS_KT = UI / "DesktopWindowsUxSettings.kt"
JVM_BINDING_TEST = source.KT / "ui" / "DesktopPlayerControlsBindingTest.kt"
JVM_SELECTION_TEST = source.KT / "ui" / "DesktopPlayerLayoutSelectionTest.kt"
JVM_TEST_CLASSES = ("com.bilipai.desktop.ui.DesktopPlayerControlsBindingTest", "com.bilipai.desktop.ui.DesktopPlayerLayoutSelectionTest")


def read(path):
    return source.read(path)


def squash(text):
    return re.sub(r"\s+", " ", text).strip()


def load_contract():
    return json.loads(read(CONTRACT_PATH))


def player_layout_flag(contract):
    """The flags.fields entry of player_layout: key, kotlin, enum, values, default."""
    found = [field for field in contract["flags"]["fields"] if field["key"] == "player_layout"]
    assert len(found) == 1, found
    return found[0]


# ---------------------------------------------------------------------------------------------------------------------
# Layout selection (DesktopPlayerLayoutSelection.kt)
# ---------------------------------------------------------------------------------------------------------------------

def effective_layout(requested, overlay_available):
    """desktopPlayerEffectiveLayout: DESKTOP only when it was requested and an overlay exists, LEGACY in every other case."""
    return "DESKTOP" if requested == "DESKTOP" and overlay_available else "LEGACY"


def selection_oracle(requested, overlay_available):
    """The same rule as a truth table over the contract's values, written without the Kotlin shape."""
    table = {("LEGACY", False): "LEGACY", ("LEGACY", True): "LEGACY", ("DESKTOP", False): "LEGACY", ("DESKTOP", True): "DESKTOP"}
    return table[(requested, overlay_available)]


# ---------------------------------------------------------------------------------------------------------------------
# Admitted actions (DesktopPlayerControlsBinding.kt)
# ---------------------------------------------------------------------------------------------------------------------

# Constructor parameters in order: (name, kotlin type, public). The leaf call names every one of them once, in this order.
CONSTRUCTOR = (
    ("current", "() -> Boolean", False),
    ("command", "(() -> Unit) -> Boolean", False),
    ("togglePause", "() -> Unit", False),
    ("setMuted", "(Boolean) -> Unit", False),
    ("setVolume", "(Double) -> Unit", False),
    ("isMuted", "() -> Boolean", False),
    ("preferences", "PlayerPreferences", False),
    ("preferencesChanged", "(PlayerPreferences) -> Unit", False),
    ("navigate", "(Boolean) -> Unit", False),
    ("switchQuality", "(Int) -> Unit", False),
    ("seekToSeconds", "(Double) -> Unit", False),
    ("goBack", "() -> Unit", False),
    ("enterPictureInPicture", "() -> Unit", False),
    ("toggleFullscreen", "() -> Unit", False),
    ("speed", "(Double) -> Unit", True),
)

# The members of the binding, as the Kotlin file spells them: name -> (type, right-hand side).
MEMBERS = {
    "back": ("() -> Unit", "{ if (current()) goBack() }"),
    "playPause": ("() -> Unit", "{ command { togglePause() } }"),
    "previous": ("() -> Unit", "{ navigate(false) }"),
    "next": ("() -> Unit", "{ navigate(true) }"),
    "mute": ("() -> Unit", "{ if (command { setMuted(!isMuted()) }) preferencesChanged(preferences.copy(muted = !isMuted())) }"),
    "volume": ("(Double) -> Unit", "{ value -> if (command { setVolume(value) }) preferencesChanged(preferences.copy(volume = value)) }"),
    "quality": ("(Int) -> Unit", "{ id -> if (current()) switchQuality(id) }"),
    "seek": ("(Double) -> Unit", "{ seconds -> if (current()) seekToSeconds(seconds) }"),
    "pictureInPicture": ("() -> Unit", "{ if (current()) enterPictureInPicture() }"),
    "fullscreen": ("() -> Unit", "{ if (current()) toggleFullscreen() }"),
}
# The members that the leaf hands to the LEGACY control bar (bar argument -> binding member). The back button of the strip takes
# "back" (IconButton onClick); it is pinned in test_ux_leaf_layout_binding.py.
BAR_ROUTES = {
    "onPlayPause": "playPause", "onPrevious": "previous", "onNext": "next", "onMute": "mute", "onVolume": "volume",
    "onSpeed": "speed", "onQuality": "quality", "onSeek": "seek", "onPictureInPicture": "pictureInPicture", "onFullscreen": "fullscreen",
}
PUBLIC_MEMBERS = tuple(MEMBERS) + ("speed",)


class Calls:
    """Fake capabilities that record what ran. command() runs its block only when the gate accepts."""

    def __init__(self, is_current=True, accepts=True, muted=False, preferences=None):
        self.is_current, self.accepts, self.muted = is_current, accepts, muted
        self.preferences = dict(preferences or {"volume": 40.0, "speed": 1.5, "muted": False})
        self.events, self.saved, self.speed_calls = [], [], []

    def command(self, block):
        if self.accepts:
            block()
            return True
        return False

    def binding(self):
        return make_binding(
            current=lambda: self.is_current, command=self.command,
            toggle_pause=lambda: self.events.append("togglePause"),
            set_muted=lambda value: self.events.append("setMuted(%s)" % java_bool(value)),
            set_volume=lambda value: self.events.append("setVolume(%s)" % value),
            is_muted=lambda: self.muted, preferences=self.preferences, preferences_changed=lambda value: self.saved.append(value),
            navigate=lambda forward: self.events.append("navigate(%s)" % java_bool(forward)),
            switch_quality=lambda ident: self.events.append("switchQuality(%s)" % ident),
            seek_to_seconds=lambda seconds: self.events.append("seek(%s)" % seconds),
            go_back=lambda: self.events.append("back"), enter_picture_in_picture=lambda: self.events.append("pip"),
            toggle_fullscreen=lambda: self.events.append("fullscreen"), speed=lambda value: self.speed_calls.append(value))


def java_bool(value):
    return "true" if value else "false"


def make_binding(current, command, toggle_pause, set_muted, set_volume, is_muted, preferences, preferences_changed, navigate,
                 switch_quality, seek_to_seconds, go_back, enter_picture_in_picture, toggle_fullscreen, speed):
    """DesktopPlayerControlsBinding, transliterated member by member. preferences is a dict (copy -> a new dict)."""

    def back():
        if current():
            go_back()

    def play_pause():
        command(lambda: toggle_pause())

    def previous():
        navigate(False)

    def next_():
        navigate(True)

    def mute():
        if command(lambda: set_muted(not is_muted())):
            preferences_changed(dict(preferences, muted=not is_muted()))

    def volume(value):
        if command(lambda: set_volume(value)):
            preferences_changed(dict(preferences, volume=value))

    def quality(ident):
        if current():
            switch_quality(ident)

    def seek(seconds):
        if current():
            seek_to_seconds(seconds)

    def picture_in_picture():
        if current():
            enter_picture_in_picture()

    def fullscreen():
        if current():
            toggle_fullscreen()

    return {"back": back, "playPause": play_pause, "previous": previous, "next": next_, "mute": mute, "volume": volume,
            "quality": quality, "seek": seek, "pictureInPicture": picture_in_picture, "fullscreen": fullscreen, "speed": speed}


# What each action does, as a rule: (gate, effects). The gate is "current" (the page must be current), "command" (the command gate
# must accept) or "always". Effects name the recorder lines. The argument and the muted flag are filled in by expected_events.
ORACLE_RULES = {
    "back": ("current", ("back",)),
    "playPause": ("command", ("togglePause",)),
    "previous": ("always", ("navigate(false)",)),
    "next": ("always", ("navigate(true)",)),
    "mute": ("command", ("setMuted(!muted)",), "muted"),
    "volume": ("command", ("setVolume(value)",), "volume"),
    "quality": ("current", ("switchQuality(value)",)),
    "seek": ("current", ("seek(value)",)),
    "pictureInPicture": ("current", ("pip",)),
    "fullscreen": ("current", ("fullscreen",)),
}


def expected_events(action, is_current, accepts, muted, value=None):
    """(recorded events, saved preference changes as {field: value}) that the oracle expects for one call of one action."""
    rule = ORACLE_RULES[action]
    gate = rule[0]
    runs = {"current": is_current, "command": accepts, "always": True}[gate]
    if not runs:
        return [], []
    events = []
    for effect in rule[1]:
        if effect == "setMuted(!muted)":
            effect = "setMuted(%s)" % java_bool(not muted)
        elif "(value)" in effect:
            effect = effect.replace("value", str(value))
        events.append(effect)
    saved = []
    if len(rule) > 2:
        saved.append({"muted": not muted} if rule[2] == "muted" else {"volume": value})
    return events, saved


# ---------------------------------------------------------------------------------------------------------------------
# Kotlin text readers
# ---------------------------------------------------------------------------------------------------------------------

def binding_constructor(text):
    """[(name, type, public)] of the primary constructor of the binding, in order."""
    start = text.index("internal class DesktopPlayerControlsBinding(")
    end = text.index(") {", start)
    body = re.sub(r"/\*\*.*?\*/", "", text[start:end], flags=re.S)
    found = []
    for match in re.finditer(r"(?m)^\s*(private )?val (\w+): (.+?),\s*$", body):
        found.append((match.group(2), match.group(3), match.group(1) is None))
    return found


def binding_members(text):
    """{name: (type, right-hand side)} of the property initialisers in the class body."""
    body = text[text.index(") {", text.index("internal class DesktopPlayerControlsBinding(")):]
    return {match.group(1): (match.group(2), match.group(3).strip())
            for match in re.finditer(r"(?m)^    val (\w+): (.+?) = (\{.*\})\s*$", body)}
