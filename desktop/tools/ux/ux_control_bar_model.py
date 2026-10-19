"""Reference and mirror model of the LEGACY player control bar (desktop UX program, slice V2b). No JVM, no network.

Three Kotlin files of the slice are read here:

  ui/DesktopWindowsVideoControlBarModel.kt    the pure rules: width breakpoints, the shortcut hint of a button, the height of a
                                              menu window and of the more menu, the quality pill label (no Compose, AWT or Windows)
  ui/DesktopWindowsVideoControlBarStrings.kt  every text of the bar (hard-coded Chinese lives only here)
  ui/DesktopWindowsVideoControlBar.kt         the composable, restyled with the kit

This module holds:

  * parsers for the marked blocks, the hint table, the string constants and the parameter list of the composable, so the suites
    compare the Kotlin text with the contract instead of repeating numbers;
  * mirrors: line-by-line Python transliterations of the pure Kotlin functions (Kotlin integer semantics), pinned in the suites as
    source needles, so editing the Kotlin without the mirror, or the mirror without the Kotlin, fails;
  * oracles: the same rules written differently from the contract and the kit tokens, used by the sweeps.

The composable is not mirrored: it is judged by test_ux_control_bar.py (source rules, the frozen signature and the frozen seek
function) and, after a compile, by DesktopWindowsVideoControlBarUiTest and the Windows fixtures. Python proves nothing about
Kotlin compilation.
"""
import hashlib
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

BAR_KT = source.K / "ui/DesktopWindowsVideoControlBar.kt"
MODEL_KT = source.K / "ui/DesktopWindowsVideoControlBarModel.kt"
STRINGS_KT = source.K / "ui/DesktopWindowsVideoControlBarStrings.kt"
PLAYER_STRINGS_KT = source.K / "ui/DesktopPlayerStrings.kt"
PLAYER_SHORTCUTS_KT = source.K / "ui/DesktopPlayerShortcutPolicy.kt"
PLAYER_MENU_KT = source.K / "ui/DesktopWindowsPlayerMenu.kt"
KIT_TOKENS_KT = source.K / "ui/kit/DesktopKitTokens.kt"
LEAF_KT = source.K / "ui/DesktopWindowsVideoPhysicalLeaf.kt"
FIXTURE_KT = source.KT / "ui/WindowsVideoActualRootUiFixture.kt"
MODEL_TEST_KT = source.KT / "ui/DesktopWindowsVideoControlBarModelTest.kt"
UI_TEST_KT = source.KT / "ui/DesktopWindowsVideoControlBarUiTest.kt"

read = shell.read
squash = shell.squash
marked_block = shell.marked_block


def section(contract=None):
    return (contract or model.load())["control_bar"]


# --------------------------------------------------------------------------------------
# Kotlin parsers
# --------------------------------------------------------------------------------------

def constants(text):
    """{name: int} of the const vals between the UX-CONTROL-BAR markers of the model file."""
    return shell.int_constants(marked_block(text, "CONTROL-BAR"))


def kit_constants():
    """{name: int} of the kit token block (the control height and the list row height are the ones the menus use)."""
    return shell.int_constants(marked_block(read(KIT_TOKENS_KT), "KIT-TOKENS"))


def constant_map(contract=None):
    """{Kotlin constant: contract number} for every constant of the UX-CONTROL-BAR block."""
    value = section(contract)
    layout, menu = value["layout"], value["menu"]
    return {
        "DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP": layout["expanded_min_width"],
        "DESKTOP_CONTROL_BAR_STATUS_MIN_DP": layout["status_min_width"],
        "DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP": layout["row_min_height"],
        "DESKTOP_CONTROL_BAR_SIDE_PADDING_DP": layout["side_padding"],
        "DESKTOP_CONTROL_BAR_BUTTON_GAP_DP": layout["button_gap"],
        "DESKTOP_CONTROL_BAR_PILL_PADDING_DP": layout["pill_padding"],
        "DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP": menu["host_padding"],
        "DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP": menu["rounding_slack"],
        "DESKTOP_CONTROL_BAR_MENU_START_INSET_DP": menu["start_inset"],
        "DESKTOP_CONTROL_BAR_MENU_DIVIDER_BLOCK_DP": menu["divider_block"],
        "DESKTOP_CONTROL_BAR_MENU_MIN_DP": menu["min_height"],
        "DESKTOP_CONTROL_BAR_MENU_MAX_DP": menu["max_height"],
        "DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP": menu["compact_more_max_height"],
        "DESKTOP_CONTROL_BAR_MORE_BASE_ROWS": menu["more_base_rows"],
        "DESKTOP_CONTROL_BAR_MORE_COMPACT_FIXED_ROWS": menu["more_compact_fixed_rows"],
        "DESKTOP_CONTROL_BAR_MORE_COMPACT_DIVIDERS": menu["more_compact_dividers"],
        "DESKTOP_CONTROL_BAR_MORE_COMPACT_SLIDER_ROWS": menu["more_compact_slider_rows"],
    }


def hint_table(text):
    """[(target, chord, action kind, arg)] of DESKTOP_CONTROL_BAR_HINTS in source order."""
    block = marked_block(text, "CONTROL-BAR-HINTS")
    found = re.findall(
        r'DesktopControlBarHint\(DesktopControlBarHintTarget\.(\w+), "([^"]+)", '
        r"DesktopPlayerAction\(DesktopPlayerActionKind\.(\w+)(?:, (-?\d+))?\)\)", block)
    return [(target, chord, kind, int(arg) if arg else 0) for target, chord, kind, arg in found]


def hint_targets(text):
    """The enum constants of DesktopControlBarHintTarget in source order."""
    body = re.search(r"internal enum class DesktopControlBarHintTarget \{([^}]*)\}", text).group(1)
    return [name.strip() for name in body.split(",") if name.strip()]


def string_constants(text):
    """{name: value} of the const vals of a strings object."""
    return dict(re.findall(r'const val ([A-Z_]+) = "([^"]*)"', text))


def contract_player_rows(contract=None):
    """{chord: (action, arg, active)} of the player scope of the shortcut table; a row with a status is not active."""
    rows = (contract or model.load())["shortcuts"]["rows"]
    return {row["chord"]: (row["action"], row.get("arg", 0), "status" not in row) for row in rows if row["scope"] == "player"}


def kotlin_player_rows(text):
    """{chord: (action, arg, active)} of DESKTOP_PLAYER_SHORTCUTS: the desktopPlayerKey rows and the digit rows."""
    block = marked_block(text, "PLAYER-SHORTCUTS")
    rows = {}
    for chord, kind, arg, status in re.findall(
            r'desktopPlayerKey\("([^"]+)", DesktopPlayerActionKind\.(\w+)(?:, (-?\d+))?'
            r'(?:, (?:status = )?DesktopPlayerShortcutStatus\.(\w+))?(?:, whenPaused = true)?\)', block):
        rows[chord] = (kind, int(arg) if arg else 0, status in ("", "ACTIVE"))
    return rows


def walk(masked, angles=True):
    """(index, token, depth) for every character of masked Kotlin text; an arrow is one token and never a bracket, and a bracket
    reports the depth outside it. Angle brackets count as brackets unless angles is False (a call argument holds comparisons)."""
    opening, closing = ("([{<", ")]}>") if angles else ("([{", ")]}")
    depth, index = 0, 0
    while index < len(masked):
        char = masked[index]
        if masked.startswith("->", index):
            yield index, "->", depth
            index += 2
            continue
        if char in opening:
            yield index, char, depth
            depth += 1
        elif char in closing:
            depth -= 1
            yield index, char, depth
        else:
            yield index, char, depth
        index += 1


def split_top_level(raw, masked, separator=",", angles=True):
    """[(raw piece, masked piece)] split at the separator outside every bracket pair; blank pieces are dropped."""
    pieces, start = [], 0
    for index, token, depth in walk(masked, angles):
        if token == separator and depth == 0:
            pieces.append((raw[start:index], masked[start:index]))
            start = index + 1
    pieces.append((raw[start:], masked[start:]))
    return [(r.strip(), m.strip()) for r, m in pieces if r.strip()]


def matching(masked, open_index):
    """Index of the bracket that closes the one at open_index (in masked text)."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    opener = masked[open_index]
    closer, depth = pairs[opener], 0
    for index in range(open_index, len(masked)):
        if masked[index] == opener:
            depth += 1
        elif masked[index] == closer:
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError("unbalanced " + opener)


def declaration_start(text, name):
    """Index in text of the one declaration `fun name(` at the start of a line (after modifiers and annotations)."""
    masked = source.mask_kotlin(text)
    found = [m.start() for m in re.finditer(r"(?m)^(?:[ \t]*(?:private|internal|public)[ \t]+)?fun[ \t]+" + re.escape(name) + r"\(", masked)]
    if len(found) != 1:
        raise AssertionError("%s is declared %d times" % (name, len(found)))
    return found[0]


def parameters(text, name):
    """[(name, type, default or None)] of the one function `name`, in order. A trailing comma is allowed."""
    masked = source.mask_kotlin(text)
    start = declaration_start(text, name)
    open_index = masked.index("(", start)
    close_index = matching(masked, open_index)
    result = []
    for raw_entry, masked_entry in split_top_level(text[open_index + 1:close_index], masked[open_index + 1:close_index]):
        colon = masked_entry.index(":")
        rest_raw, rest_masked = raw_entry[colon + 1:], masked_entry[colon + 1:]
        equals = next((index for index, token, depth in walk(rest_masked) if token == "=" and depth == 0), None)
        kind = rest_raw if equals is None else rest_raw[:equals]
        default = None if equals is None else rest_raw[equals + 1:].strip()
        result.append((raw_entry[:colon].strip(), " ".join(kind.split()), default))
    return result


def function_block(text, name):
    """Text of the one function `name`, from its declaration to the brace that closes its body."""
    masked = source.mask_kotlin(text)
    start = declaration_start(text, name)
    close_parameters = matching(masked, masked.index("(", start))
    body_open = masked.index("{", close_parameters)
    return text[start:matching(masked, body_open) + 1]


def block_digest(text):
    """sha256 of the whitespace-insensitive form of a block."""
    return hashlib.sha256(squash(text).encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------------------
# Kotlin semantics
# --------------------------------------------------------------------------------------

def coerce_at_least(value, floor):
    return value if value >= floor else floor


def coerce_in(value, low, high):
    return low if value < low else high if value > high else value


# --------------------------------------------------------------------------------------
# Mirrors of DesktopWindowsVideoControlBarModel.kt
# --------------------------------------------------------------------------------------

def expanded_kt(c, width):
    """desktopControlBarExpanded."""
    return width >= c["DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP"]


def shows_status_kt(c, width):
    """desktopControlBarShowsStatus."""
    return width >= c["DESKTOP_CONTROL_BAR_STATUS_MIN_DP"]


def key_label_kt(chord, key_space):
    """desktopControlBarKeyLabel."""
    if chord == "Space":
        return key_space
    if chord == "LeftBracket":
        return "["
    if chord == "RightBracket":
        return "]"
    return chord


def shortcut_hint_kt(target, shortcuts, hints, key_space):
    """desktopControlBarShortcutHint. shortcuts: [(chord, (kind, arg), active)]; hints: [(target, chord, (kind, arg))]."""
    hint = next((row for row in hints if row[0] == target), None)
    if hint is None:
        return None
    bound = any(chord == hint[1] and action == hint[2] and active for chord, action, active in shortcuts)
    return key_label_kt(hint[1], key_space) if bound else None


def menu_content_dp_kt(c, kit, rows, dividers=0, slider_rows=0):
    """desktopControlBarMenuContentDp."""
    return (c["DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP"]
            + c["DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP"]
            + coerce_at_least(rows, 0) * kit["DESKTOP_KIT_CONTROL_HEIGHT_DP"]
            + coerce_at_least(slider_rows, 0) * kit["DESKTOP_KIT_ROW_LIST_DP"]
            + coerce_at_least(dividers, 0) * c["DESKTOP_CONTROL_BAR_MENU_DIVIDER_BLOCK_DP"])


def menu_height_dp_kt(c, kit, rows, dividers=0, slider_rows=0, max_dp=None):
    """desktopControlBarMenuHeightDp."""
    limit = c["DESKTOP_CONTROL_BAR_MENU_MAX_DP"] if max_dp is None else max_dp
    raised = coerce_at_least(menu_content_dp_kt(c, kit, rows, dividers, slider_rows), c["DESKTOP_CONTROL_BAR_MENU_MIN_DP"])
    return raised if raised <= limit else limit


def more_menu_height_dp_kt(c, kit, expanded, speed_rows, quality_rows, chapter_row, collection_row, queue_row):
    """desktopControlBarMoreMenuHeightDp."""
    optional = (1 if chapter_row else 0) + (1 if collection_row else 0) + (1 if queue_row else 0)
    tail = c["DESKTOP_CONTROL_BAR_MORE_BASE_ROWS"] + optional
    if expanded:
        return menu_height_dp_kt(c, kit, tail)
    return menu_height_dp_kt(
        c, kit, c["DESKTOP_CONTROL_BAR_MORE_COMPACT_FIXED_ROWS"] + coerce_at_least(speed_rows, 0) + coerce_at_least(quality_rows, 0) + tail,
        dividers=c["DESKTOP_CONTROL_BAR_MORE_COMPACT_DIVIDERS"], slider_rows=c["DESKTOP_CONTROL_BAR_MORE_COMPACT_SLIDER_ROWS"],
        max_dp=c["DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP"])


def quality_label_kt(qualities, selected, fallback):
    """desktopControlBarQualityLabel: the label of the first quality whose id is the selected one, else the fallback word."""
    for quality_id, label in qualities:
        if quality_id == selected:
            return label
    return fallback


# --------------------------------------------------------------------------------------
# Oracles (the same rules from the contract and the tokens, in a different shape)
# --------------------------------------------------------------------------------------

def oracle_menu_height(contract, rows, dividers=0, slider_rows=0, limit=None):
    """Rows of the kit control height, slider rows of the kit list row height, divider blocks, the host padding and the rounding
    slack; between the floor and the limit."""
    tokens, menu = contract["tokens"], contract["control_bar"]["menu"]
    total = menu["host_padding"] + menu["rounding_slack"] + sum((max(rows, 0) * tokens["control_height"],
                                                                   max(slider_rows, 0) * tokens["row_height"]["list"],
                                                                   max(dividers, 0) * menu["divider_block"]))
    return min(max(total, menu["min_height"]), menu["max_height"] if limit is None else limit)


def oracle_more_menu_height(contract, expanded, speed_rows, quality_rows, chapter_row, collection_row, queue_row):
    menu = contract["control_bar"]["menu"]
    shared = menu["more_base_rows"] + [chapter_row, collection_row, queue_row].count(True)
    if expanded:
        return oracle_menu_height(contract, shared)
    return oracle_menu_height(contract, menu["more_compact_fixed_rows"] + max(speed_rows, 0) + max(quality_rows, 0) + shared,
                              menu["more_compact_dividers"], menu["more_compact_slider_rows"], menu["compact_more_max_height"])


def oracle_hint(contract, target, key_space):
    """The label of the hint of a target from the contract hints and the player rows, or None when the chord is not an active row
    with the hinted action."""
    row = next((item for item in contract["control_bar"]["hints"] if item["target"] == target), None)
    if row is None:
        return None
    player = [item for item in contract["shortcuts"]["rows"] if item["scope"] == "player" and "status" not in item]
    if not any(item["chord"] == row["chord"] and item["action"] == row["action"] and item.get("arg", 0) == row.get("arg", 0)
               for item in player):
        return None
    return key_space if row.get("label_from") == "KEY_SPACE" else row["label"]
