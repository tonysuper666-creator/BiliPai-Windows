"""Reference and mirror model of the shell policy layer (desktop UX program, slice X1a). No JVM, no network.

The three pure Kotlin files of the slice (ui/DesktopWindowsRailModel.kt, ui/DesktopWindowsLayoutMetrics.kt,
ui/DesktopShellShortcuts.kt) have no Compose, AWT or Windows dependency, so their logic can be mirrored exactly.
This module holds three kinds of code, all reading the shared contract through ux_model:

  * parsers for the marked blocks, enum tables and string sets of those Kotlin files, so a test compares the
    Kotlin numbers and tables with the contract instead of repeating them;
  * mirrors: line-by-line Python transliterations of the Kotlin functions with Kotlin integer semantics (division
    truncates toward zero, coerceIn is min(max())), fed with the constants parsed from the Kotlin text. The suites
    pin the transliterated Kotlin lines as source needles, so editing either side without the other fails;
  * oracles: the same rules written from the spec prose over the contract, in a different shape from the Kotlin.
    ux_model.feed_layout (exact rational arithmetic) is the oracle of the Home density rule.

The suites compare mirror and oracle over sweeps. That proves the Kotlin algorithm, as far as it is mirrored
faithfully, and says nothing about Kotlin compilation: the JVM tests are the proof of the real code.
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_source as source  # noqa: E402

RAIL_KT = source.K / "ui/DesktopWindowsRailModel.kt"
METRICS_KT = source.K / "ui/DesktopWindowsLayoutMetrics.kt"
SHORTCUTS_KT = source.K / "ui/DesktopShellShortcuts.kt"
STRINGS_KT = source.K / "ui/DesktopShellStrings.kt"
BOTTOM_BAR = source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/components/BottomBar.kt"
ROOT_CHROME = source.K / "ui/DesktopOriginalRootChrome.kt"
GRID_POLICY = source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/HomeFeedGridPolicy.kt"

PAGES = ("HOME", "DYNAMIC", "SEARCH", "OTHER", "PLAYER")
PLAYER_PAGE_KINDS = ("BACK", "FULLSCREEN_TOGGLE", "SHORTCUT_HELP", "CYCLE_PANES")


def squash(text):
    """Whitespace-insensitive form for needle matching."""
    text = re.sub(r"\s+", " ", text)
    text = re.sub(r"\( ", "(", text)
    return re.sub(r" \)", ")", text)


# --------------------------------------------------------------------------------------
# Kotlin parsers
# --------------------------------------------------------------------------------------

def marked_block(text, name):
    begin, end = "// UX-%s-BEGIN" % name, "// UX-%s-END" % name
    if text.count(begin) != 1 or text.count(end) != 1:
        raise AssertionError("%s must be marked exactly once" % name)
    return text[text.index(begin) + len(begin):text.index(end)]


def int_constants(text):
    return {name: int(value) for name, value in re.findall(r"const val (\w+) = (-?\d+)\b", text)}


def thresholds(text):
    """{'AUTO': (expandAt, collapseBelow, reexpandAt), 'EXPANDED': (...)}."""
    block = marked_block(text, "RAIL-THRESHOLDS")
    found = re.findall(r"internal val DESKTOP_RAIL_(AUTO|EXPANDED)_THRESHOLDS = DesktopRailThresholds\("
                       r"expandAt = (\d+), collapseBelow = (\d+), reexpandAt = (\d+)\)", block)
    return {mode: (int(a), int(b), int(c)) for mode, a, b, c in found}


def presentations(text):
    """{'EXPANDED': ('expanded', 'DESKTOP_RAIL_FOOTPRINT_EXPANDED_DP'), ...} in declaration order."""
    start = text.index("internal enum class DesktopRailPresentation(")
    body = text[start:text.index("\n}\n", start)]
    return {name: (contract_name, const) for name, contract_name, const
            in re.findall(r'(?m)^    ([A-Z]+)\("([a-z]+)", (DESKTOP_RAIL_FOOTPRINT_[A-Z]+_DP)\),$', body)}


def rail_items(text):
    """[(name, group, bottomNavName or None)] in declaration order."""
    start = text.index("internal enum class DesktopRailItem(")
    body = text[start:text.index("\n}\n", start)]
    rows = re.findall(r'(?m)^    ([A-Z_]+)\(DesktopRailGroup\.([A-Z]+), (null|"[A-Z_]+")\),$', body)
    return [(name, group, None if bottom == "null" else bottom.strip('"')) for name, group, bottom in rows]


def string_set(text, name):
    match = re.search(r"val %s: Set<String> = setOf\((.*?)\n?\)\n" % re.escape(name), text, re.S)
    if match is None:
        raise AssertionError("no string set " + name)
    return re.findall(r'"([^"]+)"', match.group(1))


def fixed_destinations(text):
    return re.findall(r"DesktopRailItem\.([A-Z_]+)", marked_block(text, "RAIL-FIXED").split("listOf(", 1)[1])


GENERATED_DESTINATIONS = (
    "DESKTOP_RAIL_FIXED_DESTINATIONS.mapIndexed { index, item -> "
    'DesktopShellShortcut("Ctrl+${index + 1}", DesktopShellAction(DesktopShellActionKind.GO_DESTINATION, destination = item)) }')
_ROW = re.compile(r'DesktopShellShortcut\("([^"]+)", DesktopShellAction\(DesktopShellActionKind\.([A-Z_]+)'
                  r'(?:, direction = (-?\d+))?\)(?:, existing = (true))?\)')


def shortcut_rows(text):
    """[(chord, kind, destination or None, direction, existing)] of the Kotlin shell table, generated rows expanded."""
    block = squash(marked_block(text, "SHELL-SHORTCUTS"))
    before, marker, after = block.partition(GENERATED_DESTINATIONS)
    if not marker:
        raise AssertionError("the generated Ctrl+number rows are not in the expected form")
    rows = []

    def plain(part):
        return [(chord, kind, None, int(direction or 0), existing == "true")
                for chord, kind, direction, existing in _ROW.findall(part)]

    rows += plain(before)
    for index, item in enumerate(fixed_destinations(read(RAIL_KT)), start=1):
        rows.append(("Ctrl+%d" % index, "GO_DESTINATION", item, 0, False))
    rows += plain(after)
    return rows


def action_label_kinds(text):
    start = text.index("fun actionLabel(action: DesktopShellAction)")
    return re.findall(r"DesktopShellActionKind\.([A-Z_]+) ->", text[start:])


def rail_labels(text):
    start = text.index("fun railLabel(item: DesktopRailItem)")
    body = text[start:text.index("\n    }\n", start)]
    return dict(re.findall(r'DesktopRailItem\.([A-Z_]+) -> "([^"]+)"', body))


def menu_actions(text):
    """{action: explicit mode} of the Kotlin enum DesktopRailMenuAction in declaration order."""
    start = text.index("internal enum class DesktopRailMenuAction(")
    body = text[start:text.index("\n}\n", start)]
    return dict(re.findall(r"(?m)^    ([A-Z_]+)\(DesktopWindowsUxRailMode\.([A-Z_]+)\),$", body))


def rail_menu_labels(text):
    """{action: label} read from the Kotlin function railMenuLabel through the const vals it names."""
    start = text.index("fun railMenuLabel(action: DesktopRailMenuAction)")
    body = text[start:text.index("\n    }\n", start)]
    consts = dict(re.findall(r'const val ([A-Z_]+) = "([^"]+)"', text))
    return {action: consts[name] for action, name in re.findall(r"(?m)DesktopRailMenuAction\.([A-Z_]+) -> ([A-Z_]+)$", body)}


def read(path):
    return source.read(path)


# --------------------------------------------------------------------------------------
# Vendored facts
# --------------------------------------------------------------------------------------

def bottom_nav_items():
    """[(name, label)] of the vendored BottomNavItem enum in declaration order."""
    text = read(BOTTOM_BAR)
    body = text[text.index("enum class BottomNavItem("):]
    body = body[:body.index("\n}\n")]
    return re.findall(r'(?m)^    ([A-Z][A-Z_]*)\(\n        "([^"]+)",', body)


# --------------------------------------------------------------------------------------
# Kotlin semantics
# --------------------------------------------------------------------------------------

def kdiv(numerator, denominator):
    """Kotlin Int division: truncation toward zero."""
    quotient = abs(numerator) // abs(denominator)
    return quotient if (numerator >= 0) == (denominator >= 0) else -quotient


def coerce_in(value, low, high):
    return low if value < low else high if value > high else value


# --------------------------------------------------------------------------------------
# Mirrors of DesktopWindowsRailModel.kt
# --------------------------------------------------------------------------------------

def rail_presentation_kt(limits, mode, width, previous=None):
    """desktopRailPresentation. limits: {'AUTO': (expandAt, collapseBelow, reexpandAt), 'EXPANDED': ...}.
    previous is 'expanded', 'collapsed', 'classic' or None. Returns 'expanded', 'collapsed' or 'classic'."""
    if mode == "COLLAPSED":
        return "collapsed"
    if mode == "CLASSIC_TEXT":
        return "classic"
    expand_at, collapse_below, reexpand_at = limits[mode]
    if previous == "expanded":
        expanded = width >= collapse_below
    elif previous == "collapsed":
        expanded = width >= reexpand_at
    else:
        expanded = width >= expand_at
    return "expanded" if expanded else "collapsed"


def rail_toggle_mode_kt(mode, presentation):
    """desktopRailToggleMode: the explicit mode a hamburger click or Ctrl+B writes, or None when there is nothing to write.
    mode is a rail mode name, presentation is 'expanded', 'collapsed' or 'classic'."""
    target = {"expanded": "COLLAPSED", "collapsed": "EXPANDED", "classic": None}[presentation]
    return None if target == mode else target


def rail_toggle_effective_kt(limits, mode, presentation, width):
    """desktopRailToggleEffective: whether the explicit mode the toggle would write shows another presentation than the one on
    screen at this width (the shown presentation is the memory of the rule). False when there is nothing to write."""
    target = rail_toggle_mode_kt(mode, presentation)
    if target is None:
        return False
    return rail_presentation_kt(limits, target, width, presentation) != presentation


def rail_menu_actions_kt(action_modes, mode, presentation):
    """desktopRailMenuActions. action_modes: {action: mode it writes} (menu_actions of the Kotlin text)."""
    if presentation == "classic":
        return []
    toggle = "COLLAPSE" if presentation == "expanded" else "EXPAND"
    return [action for action in (toggle, "RESTORE_AUTO") if action_modes[action] != mode]


def rail_entries_kt(items, excluded, visible_names, account_switcher):
    """desktopRailEntries: [(item or None, bottomNavName)] in display order."""
    visible = [name for name in visible_names if name not in excluded]
    known = []
    for name, group, bottom in items:
        if name == "ACCOUNT_SWITCH":
            include = account_switcher
        elif bottom is None:
            include = True
        else:
            include = bottom in visible
        if include:
            known.append((name, bottom, group))
    unknown = [(None, name, "BROWSE") for name in visible if all(bottom != name for _n, _g, bottom in items)]
    browse = [entry for entry in known if entry[2] == "BROWSE"]
    rest = [entry for entry in known if entry[2] != "BROWSE"]
    return [(item, bottom) for item, bottom, _group in browse + unknown + rest]


def rail_selected_kt(on_main_host, current_item_name, item_name, current_legacy_route, item_route):
    return (on_main_host and item_name == current_item_name) or current_legacy_route == item_route


def rail_click_kt(item, selected):
    if not selected:
        return "NAVIGATE"
    if item == "HOME":
        return "HOME_DOUBLE_TAP"
    if item == "DYNAMIC":
        return "DYNAMIC_DOUBLE_TAP"
    return "NAVIGATE"


def badge_text_kt(count, cap):
    if count <= 0:
        return None
    if count > cap:
        return "%d+" % cap
    return str(count)


def badge_kind_kt(count, presentation):
    if count <= 0:
        return "NONE"
    if presentation == "collapsed":
        return "DOT"
    return "PILL"


# --------------------------------------------------------------------------------------
# Mirrors of the slice S1 functions of DesktopWindowsRailModel.kt (roving focus, zoom toast, write failure)
# --------------------------------------------------------------------------------------

ROVING_KEYS = ("UP", "DOWN", "HOME", "END")
INT_MAX = 2 ** 31 - 1
INT_MIN = -(2 ** 31)


def rail_roving_target_kt(count, current, key):
    """desktopRailRovingTarget: the stop that gets the focus after a key; -1 when there is no stop."""
    if count <= 0:
        return -1
    last = count - 1
    if key == "UP":
        return coerce_in(current - 1, 0, last)
    if key == "DOWN":
        return coerce_in(current + 1, 0, last)
    if key == "HOME":
        return 0
    if key == "END":
        return last
    raise AssertionError("unknown roving key " + repr(key))


def rail_tab_stop_kt(count, roving, selected):
    """desktopRailTabStop: the one stop Tab can reach, -1 when there is no stop."""
    if count <= 0:
        return -1
    chosen = roving if roving is not None else (selected if selected is not None else 0)
    return coerce_in(chosen, 0, count - 1)


def f32(value):
    """The nearest Kotlin Float of a number."""
    import struct
    try:
        return struct.unpack("<f", struct.pack("<f", value))[0]
    except OverflowError:
        return float("inf") if value > 0 else float("-inf")


def zoom_percent_kt(density, system_density):
    """desktopZoomPercent: Float inputs, Double division, kotlin.math.round (ties to even) and Double.toInt (saturating)."""
    import math
    if system_density is None:
        return None
    d, s = f32(density), f32(system_density)
    if not (d > 0.0) or not (s > 0.0):
        return None
    if math.isinf(d) or math.isinf(s):
        return None
    value = round(d / s * 100.0)
    return max(INT_MIN, min(INT_MAX, int(value)))


def zoom_toast_percent_kt(previous, current):
    """desktopZoomToastPercent."""
    return current if previous is not None and current is not None and previous != current else None


def rail_write_failure_shown_kt(issued_at_ms, now_ms, error_published, window_ms):
    """desktopRailWriteFailureShown: the window is inclusive and an error before the write does not count."""
    if not error_published or issued_at_ms is None:
        return False
    return 0 <= now_ms - issued_at_ms <= window_ms


# --------------------------------------------------------------------------------------
# Mirrors of DesktopWindowsLayoutMetrics.kt
# --------------------------------------------------------------------------------------

def home_density_kt(c, content_width, min_card=None):
    """resolveDesktopHomeDensity with the constants c parsed from the Kotlin text.
    Returns (columns, card as a float, grid, capped); the card is a Kotlin Double."""
    usable = content_width - 2 * c["DESKTOP_HOME_SIDE_PADDING_DP"]
    wanted = max(c["DESKTOP_HOME_MIN_CARD_DP"] if min_card is None else min_card, c["DESKTOP_HOME_CARD_FLOOR_DP"])
    limit = c["DESKTOP_HOME_MAX_COLUMNS_WIDE"] if content_width >= c["DESKTOP_HOME_WIDE_FROM_CONTENT_DP"] \
        else c["DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE"]
    gutter = c["DESKTOP_HOME_GUTTER_DP"]
    columns = coerce_in(kdiv(usable + gutter, wanted + gutter), c["DESKTOP_HOME_MIN_COLUMNS"], limit)
    room = usable - (columns - 1) * gutter
    if room > columns * c["DESKTOP_HOME_MAX_CARD_DP"]:
        return (columns, float(c["DESKTOP_HOME_MAX_CARD_DP"]),
                columns * c["DESKTOP_HOME_MAX_CARD_DP"] + (columns - 1) * gutter, True)
    return columns, room / columns, usable, False


def home_content_width_kt(c, window_width, rail_footprint, home_layout):
    if home_layout == "DESKTOP":
        return window_width - rail_footprint
    return min(window_width, c["DESKTOP_HOME_LEGACY_CAP_DP"])


def home_delegation_kt(c, home_layout, display_mode, fixed_column_count):
    if home_layout == "CLASSIC":
        return "CLASSIC_LAYOUT"
    if display_mode == c["DESKTOP_HOME_SINGLE_COLUMN_DISPLAY_MODE"]:
        return "SINGLE_COLUMN_MODE"
    if fixed_column_count > 0:
        return "PINNED_COLUMN_COUNT"
    return None


def home_compact_metadata_kt(c, original_compact, card_width):
    return original_compact and card_width < c["DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP"]


def home_side_inset_kt(content_width, grid_width):
    return (content_width - grid_width) / 2.0


def window_home_layout_kt(constants, limits, mode, window_width, previous=None, min_card=None):
    """The whole chain for a window: rail presentation, footprint, content width, density. Dict of the facts."""
    footprints = {"expanded": constants["DESKTOP_RAIL_FOOTPRINT_EXPANDED_DP"],
                  "collapsed": constants["DESKTOP_RAIL_FOOTPRINT_COLLAPSED_DP"],
                  "classic": constants["DESKTOP_RAIL_FOOTPRINT_CLASSIC_DP"]}
    rail = rail_presentation_kt(limits, mode, window_width, previous)
    wc = home_content_width_kt(constants, window_width, footprints[rail], "DESKTOP")
    columns, card, grid, capped = home_density_kt(constants, wc, min_card)
    return {"rail": rail, "wc": wc, "cols": columns, "card": card, "grid": grid, "capped": capped}


# --------------------------------------------------------------------------------------
# Mirror of DesktopShellShortcuts.kt
# --------------------------------------------------------------------------------------

def chord_kt(key, ctrl=False, alt=False, shift=False, meta=False):
    return "".join(("Ctrl+" if ctrl else "", "Alt+" if alt else "", "Shift+" if shift else "", "Meta+" if meta else "", key))


def page_allows_kt(kind, page):
    if page == "PLAYER":
        return kind in PLAYER_PAGE_KINDS
    if page == "SEARCH":
        return kind != "OPEN_SEARCH" and kind != "REFRESH"
    if page in ("HOME", "DYNAMIC"):
        return True
    return kind != "REFRESH"


def resolve_shell_key_kt(table, reserved, zoom_keys, key, ctrl=False, alt=False, shift=False, meta=False,
                         key_down=True, page="HOME", text_editor=False):
    """resolveDesktopShellKey. table: {chord: (kind, destination, direction)}. Returns that triple or None."""
    if not key_down or text_editor:
        return None
    if meta or (ctrl and alt):
        return None
    if ctrl and key in zoom_keys:
        return None
    chord = chord_kt(key, ctrl, alt, shift, meta)
    if chord in reserved:
        return None
    action = table.get(chord)
    if action is None:
        return None
    return action if page_allows_kt(action[0], page) else None


def kotlin_shell_table(rows):
    """{chord: (kind, destination, direction)} from shortcut_rows(); a duplicate chord is a defect."""
    table = {}
    for chord, kind, destination, direction, _existing in rows:
        if chord in table:
            raise AssertionError("duplicate chord " + chord)
        table[chord] = (kind, destination, direction)
    return table


# --------------------------------------------------------------------------------------
# Oracles written from the spec prose over the contract
# --------------------------------------------------------------------------------------

def contract_shell_rows(contract):
    return [row for row in contract["shortcuts"]["rows"] if row["scope"] == "shell"]


def oracle_shell_resolve(contract, key, ctrl=False, alt=False, shift=False, meta=False, key_down=True, page="HOME",
                         text_editor=False):
    """Spec 6.1: what the shell claims. Same triple as resolve_shell_key_kt, derived from contract rows only."""
    table = contract["shortcuts"]
    if not key_down or text_editor:
        return None  # nothing while a text editor owns focus; only a key press is read
    if meta or (ctrl and alt):
        return None  # no Windows-key chords; Ctrl+Alt is AltGr
    mods = [name for name, held in (("Ctrl", ctrl), ("Alt", alt), ("Shift", shift)) if held]
    chord = "+".join(mods + [key])
    if chord in table["reserved"] or chord in table["not_taken"]:
        return None
    if ctrl and key in table["zoom_decoder_keys"]:
        return None
    rows = [row for row in contract_shell_rows(contract) if row["chord"] == chord]
    if not rows:
        return None
    row = rows[0]
    kind = row["action"]
    # The player owns the keys of a rail-less page, except leaving it (Back), fullscreen, the shortcut list (spec 6.1) and
    # F6 between the content and the side panel (spec 6.6, the keyboard story of the DESKTOP video page). Written out here
    # on purpose, not taken from PLAYER_PAGE_KINDS, so the mirror and the oracle cannot drift together.
    if page == "PLAYER" and kind not in ("BACK", "FULLSCREEN_TOGGLE", "SHORTCUT_HELP", "CYCLE_PANES"):
        return None
    if kind == "OPEN_SEARCH" and page == "SEARCH":
        return None  # spec: ignored on the Search route
    if kind == "REFRESH" and page not in ("HOME", "DYNAMIC"):
        return None  # spec: refresh Home or Dynamic
    if kind == "RAIL_TOGGLE" and page == "PLAYER":
        return None  # spec: rail collapse on shell pages, side panel on the video page
    destination = row["arg"] if kind == "GO_DESTINATION" else None
    direction = row["arg"] if kind == "CYCLE_PANES" else 0
    return kind, destination, direction


def oracle_rail_menu(mode, presentation):
    """Spec 3.2 and 3.3: the rail context menu shows the collapse or the expand entry (whichever the rail is not showing
    now) and the way back to automatic; an entry that would not change the mode in force is pointless and not shown, and
    the classic block has no menu. Returns the entry names in menu order."""
    if presentation == "classic":
        return []
    entries = []
    if presentation == "expanded" and mode != "COLLAPSED":
        entries.append("COLLAPSE")
    if presentation == "collapsed" and mode != "EXPANDED":
        entries.append("EXPAND")
    if mode != "AUTO":
        entries.append("RESTORE_AUTO")
    return entries


# --------------------------------------------------------------------------------------
# Shortcut collision parser (spec 10.1)
# --------------------------------------------------------------------------------------

PRINTABLE_NAMES = ("Comma", "Period", "Slash", "LeftBracket", "RightBracket", "Space")


def shortcut_violations(rows, zoom_keys, reserved=(), not_taken=(), dual_meaning=(), scopes=("shell", "player")):
    """Rule violations of a shortcut table as (code, row) pairs. rows: dicts with scope, chord and action.
    Codes: bad-chord, bad-scope, duplicate, bare-printable-shell, ctrl-alt, zoom-overlap, ctrl-digit-range,
    reserved, not-taken, scope-conflict."""
    found = []
    seen = set()
    by_chord = {}
    for row in rows:
        try:
            mods, key = model.parse_chord(row["chord"])
        except ValueError:
            found.append(("bad-chord", row))
            continue
        if row["scope"] not in scopes:
            found.append(("bad-scope", row))
        identity = (row["scope"], row["chord"])
        if identity in seen:
            found.append(("duplicate", row))
        seen.add(identity)
        if row["scope"] == "shell" and not mods and (len(key) == 1 or key in PRINTABLE_NAMES):
            found.append(("bare-printable-shell", row))
        if "Ctrl" in mods and "Alt" in mods:
            found.append(("ctrl-alt", row))
        if "Ctrl" in mods and "Alt" not in mods and key in zoom_keys:
            found.append(("zoom-overlap", row))
        if "Ctrl" in mods and key.isdigit() and not ("1" <= key <= "9"):
            found.append(("ctrl-digit-range", row))
        if row["chord"] in reserved:
            found.append(("reserved", row))
        if row["chord"] in not_taken:
            found.append(("not-taken", row))
        by_chord.setdefault(row["chord"], {})[row["scope"]] = row["action"]
    for chord, actions in by_chord.items():
        if len(actions) > 1 and len(set(actions.values())) > 1 and chord not in dual_meaning:
            found.append(("scope-conflict", {"chord": chord, "actions": actions}))
    return found


def zoom_decoder_keys_from_source():
    """The zoom decoder's key set, read from the real desktopWindowsScaleKey, in the chord vocabulary."""
    text = read(source.K / "appearance/DesktopWindowsDisplayScaleInput.kt")
    start = text.index("fun desktopWindowsScaleKey")
    decoded = set(re.findall(r"VK_(\w+)", text[start:text.index("class DesktopWindowsScaleWheelAccumulator")]))
    names = {"PLUS": "Plus", "EQUALS": "Equals", "ADD": "Add", "MINUS": "Minus", "SUBTRACT": "Subtract", "0": "0",
             "NUMPAD0": "Numpad0"}
    return {names[code] for code in decoded}
