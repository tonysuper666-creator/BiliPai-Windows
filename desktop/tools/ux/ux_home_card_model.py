"""Reference and mirror model of the Home card interactions (desktop UX program, slice H2, spec 3.6 and 6.7). No JVM, no network.

The pure logic of the slice is in desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCardInteractionPolicy.kt: the three token
constants (between the UX-CARD-TOKENS markers) and the small functions that the composables read: which text a menu entry shows, how
big the overflow button is, whether the right-click handler is mounted, which press the card takes as a right click, and what the
handler does with each later event of that pointer. The composables (DesktopCardContextMenu.kt and DesktopCardPointer.kt) are not
mirrored: they are judged by test_ux_home_card_adaptations.py (source rules) and, after a compile, by the JVM test
DesktopCardInteractionPolicyTest for the policy.

This module holds:

  * parsers for the token block, and the labels (the contract holds each label's id and the code point of its vendored emoji; the
    plain Chinese text is the generator's constant, because the contract is ASCII);
  * mirrors: line-by-line Python transliterations of the Kotlin functions, pinned in the suites as source needles, so editing the
    Kotlin without the mirror, or the mirror without the Kotlin, fails;
  * replay, the whole right-click gesture as a sequence of events run through the mirrors (the oracle of the suites is the tables of
    the contract, written by hand, not derived from the mirrors).

A mirror is only as faithful as the needles that bind it, and it proves nothing about Kotlin compilation.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_source as source  # noqa: E402

sys.path.insert(0, str(source.REPO / "desktop/tools"))
import ux_home_card as layer  # noqa: E402

# The plain labels live in the generator (and so in the generated card), not in the contract: the contract is ASCII and holds no
# Chinese text (test_ux_control_bar.py and test_ux_settings_model.py pin that). The contract names each label by id and gives the
# code point of its vendored emoji.
PLAIN_LABELS = {"watch_later": layer.WATCH_LATER_PLAIN, "unfavorite": layer.UNFAVORITE_PLAIN, "dismiss": layer.DISMISS_PLAIN}

POLICY_KT = source.K / "ui/DesktopCardInteractionPolicy.kt"
MENU_KT = source.K / "ui/DesktopCardContextMenu.kt"
POINTER_KT = source.K / "ui/DesktopCardPointer.kt"
CARD_FILES = (POLICY_KT, MENU_KT, POINTER_KT)
KOTLIN_TEST = source.KT / "ui/DesktopCardInteractionPolicyTest.kt"


def section():
    """The home_card section of the contract."""
    return model.load()["home_card"]


def policy_tokens(text):
    """{name: int} of the const vals between the UX-CARD-TOKENS markers of the policy file."""
    block = text.split("// UX-CARD-TOKENS-BEGIN\n", 1)[1].split("// UX-CARD-TOKENS-END", 1)[0]
    return {name: int(value) for name, value in re.findall(r"internal const val (\w+) = (-?\d+)\n", block)}


def token_map():
    """Where each Kotlin token constant lives in the contract section."""
    return {
        "DESKTOP_CARD_MENU_ROW_DP": ("menu", "row_height_dp"),
        "DESKTOP_CARD_MENU_WIDTH_DP": ("menu", "width_dp"),
        "DESKTOP_CARD_OVERFLOW_TARGET_DP": ("overflow_target_dp",),
    }


def contract_token(name, home_card=None):
    value = home_card if home_card is not None else section()
    for key in token_map()[name]:
        value = value[key]
    return value


def plain_label(entry):
    """The plain label of a contract entry (the generator's constant of its id)."""
    return PLAIN_LABELS[entry["id"]]


def original_label(entry):
    """The vendored label of a contract entry: its emoji, a space and the plain label."""
    return chr(int(entry["emoji_code_point"], 16)) + " " + plain_label(entry)


# --------------------------------------------------------------------------------------
# Mirrors of DesktopCardInteractionPolicy.kt
# --------------------------------------------------------------------------------------

def menu_label(interactions_on, original, plain):
    """desktopCardMenuLabelFor."""
    return plain if interactions_on else original


def overflow_target_dp(interactions_on, original_dp, target_dp):
    """desktopCardOverflowTargetDpFor (target_dp stands for DESKTOP_CARD_OVERFLOW_TARGET_DP)."""
    return float(target_dp) if interactions_on else original_dp


def secondary_click_active(has_menu, interactions_on):
    """desktopCardSecondaryClickActive."""
    return has_menu and interactions_on


def owns_secondary_press(primary_pressed, secondary_pressed):
    """desktopCardOwnsSecondaryPress."""
    return secondary_pressed and not primary_pressed


def secondary_follow(pointer_present, still_pressed):
    """desktopCardSecondaryFollow: the name of the DesktopCardSecondaryFollow entry."""
    if not pointer_present:
        return "END"
    if still_pressed:
        return "WAIT"
    return "CONSUME_AND_END"


def replay(primary, secondary, followers):
    """The whole gesture of DesktopCardPointer.kt for one first press and the later events of the same pointer.

    followers: [(pointer present, still pressed)] in event order. Returns a dict: taken (the press was the card's), opened (the
    menu callback ran, once, at the press), consumed (what the handler consumed, in order) and finished (the watch ended).
    A press that is not the card's consumes nothing and opens nothing."""
    if not owns_secondary_press(primary, secondary):
        return dict(taken=False, opened=False, consumed=[], finished=True)
    consumed = ["PRESS"]
    for present, pressed in followers:
        step = secondary_follow(present, pressed)
        if step == "WAIT":
            continue
        if step == "CONSUME_AND_END":
            consumed.append("RELEASE")
        return dict(taken=True, opened=True, consumed=consumed, finished=True)
    return dict(taken=True, opened=True, consumed=consumed, finished=False)
