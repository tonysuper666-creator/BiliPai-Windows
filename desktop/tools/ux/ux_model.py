#!/usr/bin/env python3
"""Reference model of desktop-ux-contract.json (desktop UX program, slice X0).

The contract holds the parameters (tokens, rail, feed and player layout rules, timings, shortcuts, flyout
metrics, displays) and golden tables. This module reads the parameters from the contract and recomputes every
derived number from them with exact rational arithmetic: dp extents of a display, rail presentation, Home feed
columns, player panel and video rect. Only parameters are edited by hand. `--write` recomputes every derived
value (display dp, golden expectations, resolved widths) and rewrites the contract and its byte-identical Kotlin
test copy; `--check` exits 1 when anything is stale. The suites in this folder run the same functions.

Rounding. Every derived integer is rounded half to even from the exact value, which is what the spec tables did
(Python round). Kotlin consumers must use kotlin.math.round (ties to even), never roundToInt (ties up).

Status. A derived number is only as sure as its inputs. displays.<name>.derived_status says, per derived value,
whether it rests on measured facts or on an assumed one (the maximised height depends on the assumed
displays.common.maximised_chrome_dip, so it is "assumed" even on the measured primary panel), and every golden row
with a "derive" block carries derive_status computed the same way. Both are recomputed by --write, never edited.

This module uses no JVM and no network. Line endings are LF.
"""
import copy
import json
import sys
from fractions import Fraction
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CONTRACT_PATH = HERE / "desktop-ux-contract.json"
KOTLIN_COPY_PATH = REPO / "desktop/src/test/resources/desktop-ux-contract.json"

INLINE_WIDTH = 110


# --------------------------------------------------------------------------------------
# File layer
# --------------------------------------------------------------------------------------

def load(path=CONTRACT_PATH):
    return json.loads(Path(path).read_bytes().decode("utf-8"))


def dump(value, indent=0):
    """Canonical text: short containers on one line, long ones one member per line. Deterministic, LF only."""
    inline = json.dumps(value, ensure_ascii=False, separators=(", ", ": "))
    if not isinstance(value, (dict, list)) or not value or indent + len(inline) <= INLINE_WIDTH:
        return inline
    pad = " " * (indent + 2)
    if isinstance(value, dict):
        members = [pad + json.dumps(key, ensure_ascii=False) + ": " + dump(item, indent + 2) for key, item in value.items()]
        return "{\n" + ",\n".join(members) + "\n" + " " * indent + "}"
    return "[\n" + ",\n".join(pad + dump(item, indent + 2) for item in value) + "\n" + " " * indent + "]"


def canonical_text(contract):
    return dump(contract) + "\n"


# --------------------------------------------------------------------------------------
# Arithmetic
# --------------------------------------------------------------------------------------

def round_int(value):
    """Half to even, like Python round on a Fraction."""
    return int(round(Fraction(value)))


def round_1dp(value):
    tenths = round(Fraction(value) * 10)
    return tenths // 10 if tenths % 10 == 0 else tenths / 10


def density(display, app_zoom_percent=None):
    zoom = display["app_zoom_percent"] if app_zoom_percent is None else app_zoom_percent
    return Fraction(display["windows_scale_percent"], 100) * Fraction(zoom, 100)


def extent_dp(contract, display_name, app_zoom_percent=None, extent="maximised"):
    """(width, height) in dp of a display at an app zoom. screen = the whole panel; maximised = client area of a
    maximised window (the chrome is given in Windows DIP and scales with the Windows scale, not the app zoom)."""
    display = contract["displays"][display_name]
    scale = density(display, app_zoom_percent)
    px_width, px_height = display["px"]
    width = Fraction(px_width) / scale
    if extent == "screen":
        height = Fraction(px_height) / scale
    elif extent == "maximised":
        chrome_px = Fraction(contract["displays"]["common"]["maximised_chrome_dip"] * display["windows_scale_percent"], 100)
        height = (px_height - chrome_px) / scale
    else:
        raise ValueError("unknown extent " + str(extent))
    return round_int(width), round_int(height)


def resolve_derive(contract, derive):
    return extent_dp(contract, derive["display"], derive.get("app_zoom_percent"), derive.get("extent", "maximised"))


def value_status(contract, display_name, uses_maximised_height):
    """measured or assumed. A value rests on the display facts and, when it is the height of a maximised window, on the
    maximised chrome as well (displays.common.maximised_chrome_status). Anything not measured is assumed."""
    common = contract["displays"]["common"]
    status = contract["displays"][display_name]["status"]
    if uses_maximised_height and common["maximised_chrome_status"] != "measured":
        status = "assumed"
    return "measured" if status == "measured" else "assumed"


def derive_status(contract, derive, uses_height):
    """Status of the width (uses_height False) or of the width and height (True) that a derive block resolves to."""
    return value_status(contract, derive["display"], uses_height and derive.get("extent", "maximised") == "maximised")


def _after_derive(row, status):
    """The row with derive_status placed right after its derive block (a stable key order for the canonical text)."""
    rebuilt = {}
    for key, value in row.items():
        if key == "derive_status":
            continue
        rebuilt[key] = value
        if key == "derive":
            rebuilt["derive_status"] = status
    return rebuilt


# --------------------------------------------------------------------------------------
# Rail
# --------------------------------------------------------------------------------------

def rail_presentation(contract, mode, width, previous=None):
    """expanded, collapsed or classic. previous is the earlier presentation (hysteresis) or None for a fresh start."""
    if mode == "COLLAPSED":
        return "collapsed"
    if mode == "CLASSIC_TEXT":
        return "classic"
    limits = contract["rail"]["thresholds"][mode]
    if previous == "expanded":
        return "expanded" if width >= limits["collapse_below"] else "collapsed"
    if previous == "collapsed":
        return "expanded" if width >= limits["reexpand_at"] else "collapsed"
    return "expanded" if width >= limits["expand_at"] else "collapsed"


def rail_footprint(contract, presentation):
    return contract["rail"]["footprint"][presentation]


def rail_expect(contract, mode, width, previous=None):
    presentation = rail_presentation(contract, mode, width, previous)
    return {"rail": presentation, "footprint": rail_footprint(contract, presentation)}


# --------------------------------------------------------------------------------------
# Home feed
# --------------------------------------------------------------------------------------

def feed_layout(contract, width, mode="AUTO", previous=None, min_card=None):
    """Exact Home feed layout for a window width in dp. Returns exact Fractions for card."""
    rules = contract["feed"]["rules"]
    presentation = rail_presentation(contract, mode, width, previous)
    content = width - rail_footprint(contract, presentation)
    usable = content - 2 * rules["side_padding"]
    gutter = rules["gutter"]
    wanted = max(rules["min_card"] if min_card is None else min_card, rules["card_floor"])
    columns_cap = rules["max_columns_wide"] if content >= rules["wide_from_content_width"] else rules["max_columns_below_wide"]
    columns = min(max((usable + gutter) // (wanted + gutter), rules["min_columns"]), columns_cap)
    card = Fraction(usable - (columns - 1) * gutter, columns)
    capped = card > rules["max_card"]
    if capped:
        card = Fraction(rules["max_card"])
        grid = columns * rules["max_card"] + (columns - 1) * gutter
    else:
        grid = usable
    return {"rail": presentation, "footprint": rail_footprint(contract, presentation), "wc": content,
            "columns": columns, "card": card, "grid": grid, "capped": capped}


def feed_expect(layout):
    return {"rail": layout["rail"], "wc": layout["wc"], "cols": layout["columns"], "card": round_1dp(layout["card"]),
            "grid": layout["grid"], "capped": layout["capped"]}


# --------------------------------------------------------------------------------------
# Player layout
# --------------------------------------------------------------------------------------

def player_layout(contract, width, height, mode="windowed", panel="AUTO", user_panel_width=None, aspect=None,
                  entry_width=None):
    """Exact player layout. panel is the persisted tri-state AUTO/OPEN/CLOSED; AUTO is decided once at page entry
    (entry_width, default the current width), never on resize. Returns exact Fractions."""
    rules = contract["player"]["rules"]
    ratio = Fraction(*(aspect or rules["aspect"]))
    entry = width if entry_width is None else entry_width
    if panel == "AUTO":
        wanted = entry >= rules["default_open_from_width"]
    elif panel in ("OPEN", "CLOSED"):
        wanted = panel == "OPEN"
    else:
        raise ValueError("unknown panel " + str(panel))
    if mode == "windowed":
        allowed = width >= rules["dock_min_window_width"]
    elif mode == "fullscreen":
        allowed = Fraction(width, height) >= Fraction(str(rules["fullscreen_min_aspect"]))
    else:
        raise ValueError("unknown mode " + str(mode))
    panel_width = Fraction(0)
    if wanted and allowed:
        low, high = rules["panel_min"], rules["panel_max"]
        if user_panel_width is not None:
            candidate = Fraction(min(max(user_panel_width, low), high))
        else:
            default_low, default_high = rules["default_clamp"]
            share = Fraction(rules["default_percent"], 100) * width
            candidate = min(Fraction(high), max(min(max(share, default_low), default_high), width - height * ratio))
        candidate = min(candidate, Fraction(width - rules["min_stage"]))
        panel_width = candidate if candidate >= low else Fraction(0)
    stage = width - panel_width
    if Fraction(stage) / height >= ratio:
        video_w, video_h = height * ratio, Fraction(height)
    else:
        video_w, video_h = Fraction(stage), Fraction(stage) / ratio
    side = (stage - video_w) / 2
    vertical = (height - video_h) / 2
    if side > 0:
        axis, bar = "left_right", side
    elif vertical > 0:
        axis, bar = "top_bottom", vertical
    else:
        axis, bar = "none", Fraction(0)
    if video_w >= rules["tier_l_min"]:
        tier = "L"
    elif video_w >= rules["tier_m_min"]:
        tier = "M"
    else:
        tier = "S"
    dock = min(Fraction(rules["dock_capsule_max"]), video_w - rules["dock_capsule_margin"])
    return {"docked": panel_width > 0, "panel": panel_width, "video_w": video_w, "video_h": video_h, "tier": tier,
            "axis": axis, "bar": bar, "dock": dock}


def player_expect(layout):
    return {"docked": layout["docked"], "panel": round_int(layout["panel"]),
            "video": [round_int(layout["video_w"]), round_int(layout["video_h"])], "tier": layout["tier"],
            "bars": {"axis": layout["axis"], "size": round_int(layout["bar"])}, "dock": round_int(layout["dock"])}


# --------------------------------------------------------------------------------------
# Golden rows
# --------------------------------------------------------------------------------------

def feed_row_layout(contract, row):
    width = row["width_dp"]
    return feed_layout(contract, width, row.get("mode", "AUTO"), row.get("previous"), row.get("min_card"))


def player_row_layout(contract, row):
    return player_layout(contract, row["w"], row["h"], row.get("mode", "windowed"), row.get("panel", "AUTO"),
                         row.get("user_panel_width"), row.get("aspect"), row.get("entry_width"))


def regenerate(contract):
    """A copy of the contract with every derived value recomputed from its parameters."""
    out = copy.deepcopy(contract)
    for name, display in out["displays"].items():
        if name == "common":
            continue
        width, screen_height = extent_dp(out, name, None, "screen")
        display["derived"] = {"width_dp": width, "screen_height_dp": screen_height,
                              "maximised_height_dp": extent_dp(out, name, None, "maximised")[1]}
        display["derived_status"] = {"width_dp": value_status(out, name, False),
                                     "screen_height_dp": value_status(out, name, False),
                                     "maximised_height_dp": value_status(out, name, True)}
    for row in out["rail"]["golden"]:
        row["expect"] = rail_expect(out, row["mode"], row["width_dp"], row.get("previous"))
    for section, uses_height in (("feed", False), ("player", True)):
        out[section]["golden"] = [_regenerate_row(out, section, row, uses_height) for row in out[section]["golden"]]
    return out


def _regenerate_row(contract, section, row, uses_height):
    if "derive" in row:
        if section == "feed":
            row["width_dp"] = resolve_derive(contract, row["derive"])[0]
        else:
            row["w"], row["h"] = resolve_derive(contract, row["derive"])
    if section == "feed":
        row["expect"] = feed_expect(feed_row_layout(contract, row))
    else:
        row["expect"] = player_expect(player_row_layout(contract, row))
    if "derive" in row:
        return _after_derive(row, derive_status(contract, row["derive"], uses_height))
    return row


def problems(contract_path=CONTRACT_PATH, kotlin_path=KOTLIN_COPY_PATH):
    """Human-readable reasons why the contract, its derived values or the Kotlin copy are stale. Empty = in sync."""
    found = []
    raw = Path(contract_path).read_bytes()
    contract = json.loads(raw.decode("utf-8"))
    if raw != canonical_text(contract).encode("utf-8"):
        found.append("contract is not in canonical format (run ux_model.py --write)")
    if regenerate(contract) != contract:
        found.append("a derived value is stale (run ux_model.py --write)")
    if not Path(kotlin_path).is_file() or Path(kotlin_path).read_bytes() != raw:
        found.append("the Kotlin test copy differs from the contract (run ux_model.py --write)")
    return found


def write(contract_path=CONTRACT_PATH, kotlin_path=KOTLIN_COPY_PATH):
    text = canonical_text(regenerate(load(contract_path))).encode("utf-8")
    for path in (contract_path, kotlin_path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_bytes(text)


# --------------------------------------------------------------------------------------
# Shortcut chords
# --------------------------------------------------------------------------------------

MODIFIERS = ("Ctrl", "Alt", "Shift", "Meta")


def parse_chord(chord):
    """(modifiers, key) of 'Ctrl+Shift+Left'. Modifiers must appear in the order Ctrl, Alt, Shift, Meta, once each."""
    parts = chord.split("+")
    key, mods = parts[-1], parts[:-1]
    if not key or key in MODIFIERS:
        raise ValueError("a chord needs a key: " + chord)
    order = [MODIFIERS.index(mod) for mod in mods]
    if any(mod not in MODIFIERS for mod in mods) or order != sorted(set(order)):
        raise ValueError("modifiers must be unique and ordered Ctrl, Alt, Shift, Meta: " + chord)
    return tuple(mods), key


def main(argv):
    if "--write" in argv:
        write()
        print("contract and Kotlin copy rewritten")
        return 0
    found = problems()
    for line in found:
        print(line)
    if not found:
        print("contract, derived values and Kotlin copy are in sync")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
