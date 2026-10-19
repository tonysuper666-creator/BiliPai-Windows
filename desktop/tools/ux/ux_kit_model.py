"""Reference and mirror model of the component kit (desktop UX program, slice X2a). No JVM, no network.

The kit lives in desktop/src/main/kotlin/com/bilipai/desktop/ui/kit. Only part of it is logic that a Python model can
mirror: the token constants (DesktopKitTokens.kt, between the UX-KIT-TOKENS markers) and the pure functions and the
focus state of DesktopKitModel.kt. This module holds:

  * parsers for the token block and the function bodies of those files, so the suites compare the Kotlin text with the
    contract instead of repeating numbers;
  * token_map: where each Kotlin token constant lives in desktop-ux-contract.json ("tokens" and "rail.metrics");
  * mirrors: line-by-line Python transliterations of the Kotlin functions (Kotlin integer semantics, round half to
    even, and a float32 emulation for the hairline), pinned in the suites as source needles, so editing the Kotlin
    without the mirror, or the mirror without the Kotlin, fails;
  * run_focus, the replay of the contract's focus event sequences through the mirrored state.

The composables (DesktopKitFocus, DesktopKitTooltip, DesktopKitControls) are not mirrored: they are judged by
test_ux_kit_guard.py (source rules) and, after a compile, by DesktopKitUiTest. Python proves nothing about Kotlin
compilation.
"""
import re
import struct
import sys
from dataclasses import dataclass, replace
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

KIT = source.K / "ui/kit"
TOKENS_KT = KIT / "DesktopKitTokens.kt"
MODEL_KT = KIT / "DesktopKitModel.kt"
FOCUS_KT = KIT / "DesktopKitFocus.kt"
TOOLTIP_KT = KIT / "DesktopKitTooltip.kt"
CONTROLS_KT = KIT / "DesktopKitControls.kt"
STRINGS_KT = KIT / "DesktopKitStrings.kt"
KIT_FILES = (TOKENS_KT, MODEL_KT, FOCUS_KT, TOOLTIP_KT, CONTROLS_KT, STRINGS_KT)
KOTLIN_MODEL_TEST = source.KT / "ui/kit/DesktopKitModelTest.kt"
KOTLIN_UI_TEST = source.KT / "ui/kit/DesktopKitUiTest.kt"

read = shell.read
squash = shell.squash
marked_block = shell.marked_block


def token_map(contract):
    """{Kotlin constant: contract number} for every constant of the UX-KIT-TOKENS block."""
    tokens, metrics = contract["tokens"], contract["rail"]["metrics"]
    return {
        "DESKTOP_KIT_CONTROL_HEIGHT_DP": tokens["control_height"],
        "DESKTOP_KIT_ROW_LIST_DP": tokens["row_height"]["list"],
        "DESKTOP_KIT_ROW_NAV_DP": tokens["row_height"]["nav"],
        "DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP": tokens["row_height"]["settings"][0],
        "DESKTOP_KIT_ROW_SETTINGS_DP": tokens["row_height"]["settings"][1],
        "DESKTOP_KIT_ROW_SETTINGS_COMFORTABLE_DP": tokens["row_height"]["settings"][2],
        "DESKTOP_KIT_ICON_DP": tokens["icon"],
        "DESKTOP_KIT_ICON_HIT_DP": tokens["icon_hit"][0],
        "DESKTOP_KIT_ICON_HIT_LARGE_DP": tokens["icon_hit"][1],
        "DESKTOP_KIT_HIT_FLOOR_DP": tokens["hit_floor"],
        "DESKTOP_KIT_RADIUS_CONTROL_DP": tokens["radius"]["control"],
        "DESKTOP_KIT_RADIUS_ROW_DP": tokens["radius"]["row"],
        "DESKTOP_KIT_RADIUS_MENU_DP": tokens["radius"]["menu"],
        "DESKTOP_KIT_RADIUS_FLYOUT_DP": tokens["radius"]["flyout"],
        "DESKTOP_KIT_RADIUS_PANEL_DP": tokens["radius"]["panel"],
        "DESKTOP_KIT_FOCUS_RING_OUTER_DP": tokens["focus_ring"]["outer"],
        "DESKTOP_KIT_FOCUS_RING_INNER_DP": tokens["focus_ring"]["inner"],
        "DESKTOP_KIT_MOTION_FAST_MS": tokens["motion_ms"][0],
        "DESKTOP_KIT_MOTION_SLOW_MS": tokens["motion_ms"][1],
        "DESKTOP_KIT_TOOLTIP_DELAY_MS": tokens["tooltip_delay_ms"]["kit"],
        "DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS": tokens["tooltip_delay_ms"]["player"],
        "DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS": tokens["tooltip_delay_ms"]["rail_expanded"],
        "DESKTOP_KIT_HOVER_PERCENT": metrics["hover_percent"],
        "DESKTOP_KIT_PRESSED_PERCENT": metrics["pressed_percent"],
        "DESKTOP_KIT_SELECTED_FILL_PERCENT": metrics["selected_fill_percent"],
        "DESKTOP_KIT_NAV_ICON_GAP_DP": metrics["item_gap"],
        "DESKTOP_KIT_NAV_PILL_WIDTH_DP": metrics["selected_pill"][0],
        "DESKTOP_KIT_NAV_PILL_HEIGHT_DP": metrics["selected_pill"][1],
        "DESKTOP_KIT_BADGE_DP": metrics["badge"],
        "DESKTOP_KIT_BADGE_DOT_DP": metrics["badge_dot"],
    }


def token_constants(text):
    """{name: int} of the const vals between the UX-KIT-TOKENS markers."""
    return shell.int_constants(marked_block(text, "KIT-TOKENS"))


def model_constants(text):
    """The numeric const vals of DesktopKitModel.kt: Int values, and Long values with the L suffix or the
    Long.MIN_VALUE / 2 sentinel."""
    found = {name: int(value) for name, value in re.findall(r"const val (\w+) = (-?\d+)\b(?!L)", text)}
    found.update({name: int(value) for name, value in re.findall(r"const val (\w+) = (-?\d+)L\b", text)})
    if re.search(r"const val DESKTOP_KIT_NEVER_PRESSED_MS = Long\.MIN_VALUE / 2\b", text):
        found["DESKTOP_KIT_NEVER_PRESSED_MS"] = -(2 ** 63) // 2
    return found


def f32(value):
    """Round a Python float to the nearest float32, like a Kotlin Float."""
    return struct.unpack("f", struct.pack("f", value))[0]


# --------------------------------------------------------------------------------------
# Mirrors of DesktopKitModel.kt
# --------------------------------------------------------------------------------------

def hairline_dp(density):
    """desktopKitHairlineDp, in float32."""
    density = f32(density)
    return f32(f32(1.0) / density) if density > 0.0 else 1.0


def layout_px(dp, density):
    """Dp.roundToPx: the float32 product rounded half to even (Kotlin roundToInt rounds half up, equal off the ties)."""
    return int(round(f32(f32(dp) * f32(density))))


def snap_stroke_px(px):
    """desktopKitSnapStrokePx: kotlin.math.round (half to even) and never below one pixel."""
    return max(float(round(f32(px))), 1.0)


def wash_percent(consts, enabled, hovered, pressed):
    if not enabled:
        return 0
    if pressed:
        return consts["DESKTOP_KIT_PRESSED_PERCENT"]
    if hovered:
        return consts["DESKTOP_KIT_HOVER_PERCENT"]
    return 0


def motion_ms(reduced, duration_ms):
    return 0 if reduced else duration_ms


def segment_step(enabled, current, delta):
    """desktopKitSegmentStep, statement for statement."""
    count = len(enabled)
    if count == 0 or delta == 0:
        return current
    index = current
    for _ in range(count):
        if index < 0:
            index = 0 if delta > 0 else count - 1
        else:
            index = ((index + delta) % count + count) % count
        if enabled[index]:
            return index
    return current


def segment_edge(enabled, last):
    indexes = [i for i, flag in enumerate(enabled) if flag]
    if not indexes:
        return -1
    return indexes[-1] if last else indexes[0]


@dataclass(frozen=True)
class FocusState:
    """DesktopKitFocusState. window and never come from the Kotlin constants."""
    window: int
    never: int
    focused: bool = False
    pointer_driven: bool = False
    last_press: int = None

    def __post_init__(self):
        if self.last_press is None:
            object.__setattr__(self, "last_press", self.never)

    @property
    def ring_visible(self):
        return self.focused and not self.pointer_driven

    def on_focus(self, now):
        return replace(self, focused=True, pointer_driven=0 <= now - self.last_press <= self.window)

    def on_unfocus(self):
        return replace(self, focused=False, pointer_driven=False)

    def on_pointer_press(self, now):
        return replace(self, pointer_driven=self.pointer_driven or self.focused, last_press=now)

    def on_navigation_key(self):
        return replace(self, pointer_driven=False)


def run_focus(consts, events):
    state = FocusState(consts["DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS"], consts["DESKTOP_KIT_NEVER_PRESSED_MS"])
    for name, at in events:
        if name == "focus":
            state = state.on_focus(at)
        elif name == "unfocus":
            state = state.on_unfocus()
        elif name == "press":
            state = state.on_pointer_press(at)
        elif name == "key":
            state = state.on_navigation_key()
        else:
            raise ValueError("unknown focus event " + name)
    return state


def step_oracle(enabled, current, delta):
    """The step rule as a walk over the cyclic visiting order (delta is 1 or -1)."""
    count = len(enabled)
    if count == 0 or delta == 0:
        return current
    order = []
    for k in range(1, count + 1):
        if delta > 0:
            order.append(k - 1 if current < 0 else (current + k) % count)
        else:
            order.append(count - k if current < 0 else (current - k) % count)
    return next((i for i in order if enabled[i]), current)
