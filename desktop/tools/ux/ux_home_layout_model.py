"""Reference and mirror model of the Home feed wrappers (desktop UX program, slice H1, spec 3.6). No JVM, no network.

Three kinds of code, all reading the shared contract through ux_model and the X1a mirrors of ux_shell_model:

  * ports of the three vendored Home layout functions that the wrappers delegate to (resolveHomeFeedGridColumns,
    resolveHomeFeedCardLayout's compact metadata, resolveHomeFeedMaxContentWidth). The suites pin the vendored Kotlin
    lines that each port transliterates, so a change of the vendored text fails here instead of silently drifting;
  * mirrors: line-by-line Python transliterations, with Kotlin integer semantics, of the functions of
    ui/DesktopHomeFeedLayoutPolicy.kt and ui/DesktopHomeFeedLayout.kt;
  * oracles: ux_model.feed_layout (exact rational arithmetic) is the oracle of the density rule, as in X1a.

A mirror is only as faithful as the needles that bind it, and it proves nothing about Kotlin compilation: the JVM test
DesktopHomeFeedLayoutTest is the proof of the real code.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402,F401
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

LAYOUT_KT = source.K / "ui/DesktopHomeFeedLayout.kt"
POLICY_KT = source.K / "ui/DesktopHomeFeedLayoutPolicy.kt"
VENDORED = source.REPO / "app/src/main/java/com/android/purebilibili"
GRID_POLICY = VENDORED / "feature/home/HomeFeedGridPolicy.kt"
STYLE_POLICY = VENDORED / "feature/home/HomeFeedCardStylePolicy.kt"
DENSITY_POLICY = VENDORED / "feature/home/HomeFeedCardDensityPolicy.kt"
WIDTH_UTILS = VENDORED / "core/util/WindowSizeUtils.kt"

# The order of the vendored WindowWidthSizeClass enum (resolveHomeFeedCardDensityPolicy compares with >=).
WIDTH_CLASSES = ("Compact", "Medium", "Expanded", "Large", "ExtraLarge")
# The vendored HomeFeedCardWidthPreset minimum card widths (null for AUTO), by preset name.
PRESET_MIN_CARD = {"AUTO": None, "COMPACT": 160, "BALANCED": 200, "WIDE": 260, "ULTRA_WIDE": 320}
# The vendored fallback minimum card width of the grid function when the preset has none, and the single-column minimum.
VENDORED_DEFAULT_MIN_CARD = 180
VENDORED_SINGLE_COLUMN_MIN = 280
VENDORED_MAX_COLUMNS = {"Compact": 4, "Medium": 6, "Expanded": 6, "Large": 7, "ExtraLarge": 8}
VENDORED_DENSE_COLUMNS = 6
VENDORED_CAP_DP = 1280
# The vendored card layout geometry (resolveHomeFeedCardLayout): outerPaddingDp, itemSpacingDp, verticalItemSpacingDp, for every style.
VENDORED_GEOMETRY = (6, 6, 6)


def width_class_of(width_dp):
    """resolveWindowWidthSizeClass(Dp) of the vendored WindowSizeUtils."""
    if width_dp < 600:
        return "Compact"
    if width_dp < 840:
        return "Medium"
    if width_dp < 1200:
        return "Expanded"
    if width_dp < 1600:
        return "Large"
    return "ExtraLarge"


# --------------------------------------------------------------------------------------
# Ports of the vendored functions
# --------------------------------------------------------------------------------------

def vendored_grid_columns(content_width, display_mode, fixed_column_count, preset_min_card, width_class):
    """resolveHomeFeedGridColumns (HomeFeedGridPolicy.kt)."""
    single = display_mode == 1
    if not single and fixed_column_count > 0:
        return fixed_column_count
    min_column_width = VENDORED_SINGLE_COLUMN_MIN if single else (
        preset_min_card if preset_min_card is not None else VENDORED_DEFAULT_MIN_CARD)
    max_columns = 2 if single else VENDORED_MAX_COLUMNS[width_class]
    columns = shell.kdiv(content_width, min_column_width)
    min_columns = 2 if (not single and content_width >= 300) else 1
    return shell.coerce_in(columns, min_columns, max_columns)


def vendored_compact_metadata(style, grid_columns, width_class):
    """resolveHomeFeedCardDensityPolicy(...).compactMetadata (HomeFeedCardDensityPolicy.kt)."""
    dense_large_grid = WIDTH_CLASSES.index(width_class) >= WIDTH_CLASSES.index("Expanded") and grid_columns >= VENDORED_DENSE_COLUMNS
    return style != "CURRENT" or dense_large_grid


def vendored_card_geometry(style):
    """(outerPaddingDp, itemSpacingDp, verticalItemSpacingDp) of resolveHomeFeedCardLayout (HomeFeedCardStylePolicy.kt): 6 dp for every style."""
    if style not in ("CURRENT", "OFFICIAL", "BILIPAI"):
        raise ValueError("unknown style " + str(style))
    return VENDORED_GEOMETRY


# --------------------------------------------------------------------------------------
# Mirrors of DesktopHomeFeedLayoutPolicy.kt and DesktopHomeFeedLayout.kt
# --------------------------------------------------------------------------------------

def desktop_width_kt(window_width, rail_footprint):
    """desktopHomeFeedDesktopWidthDp: Float in, Float out."""
    rest = shell.f32(shell.f32(window_width) - rail_footprint)
    return rest if rest > 0.0 else shell.f32(window_width)


def columns_delegated_kt(c, display_mode, fixed_column_count):
    """desktopHomeFeedColumnsDelegated: the single-column display mode or a stored column count, the delegation of the column function
    (shell.home_delegation_kt) for the desktop layout. The layout flag is not an input: each wrapper reads the flag for itself."""
    return shell.home_delegation_kt(c, "DESKTOP", display_mode, fixed_column_count) is not None


def measured_width_kt(window_width, rail_footprint, home_layout, delegated=False):
    """desktopHomeFeedMeasuredWidth, as a number of dp: the vendored cap on the window for CLASSIC, the width the rail leaves for DESKTOP,
    and the width the rail leaves held at the vendored cap for a DESKTOP feed whose columns are delegated."""
    if home_layout == "CLASSIC":
        return min(window_width, VENDORED_CAP_DP)
    width = desktop_width_kt(window_width, rail_footprint)
    return min(width, VENDORED_CAP_DP) if delegated else width


def side_inset_kt(c, content_width, columns):
    """desktopHomeDensitySideInsetDp: Int in, Int out; the capped rest is rounded up (Kotlin integer division of a positive number)."""
    count = max(columns, 1)
    gutters = (count - 1) * c["DESKTOP_HOME_GUTTER_DP"]
    room = content_width - 2 * c["DESKTOP_HOME_SIDE_PADDING_DP"] - gutters
    if room > count * c["DESKTOP_HOME_MAX_CARD_DP"]:
        return shell.kdiv(content_width - count * c["DESKTOP_HOME_MAX_CARD_DP"] - gutters + 1, 2)
    return c["DESKTOP_HOME_SIDE_PADDING_DP"]


def card_width_kt(c, content_width, columns):
    """desktopHomeDensityCardWidthDp: the width a card is drawn with, side space and gutters of the desktop layout."""
    count = max(columns, 1)
    room = content_width - 2 * side_inset_kt(c, content_width, count) - (count - 1) * c["DESKTOP_HOME_GUTTER_DP"]
    return room / count


def card_compact_kt(c, original_compact, content_width, columns):
    """desktopHomeDensityCardLayout: the compact metadata value of the layout it returns."""
    return shell.home_compact_metadata_kt(c, original_compact, card_width_kt(c, content_width, columns))


def card_geometry_kt(c, content_width, columns):
    """desktopHomeDensityCardLayout: (outerPaddingDp, itemSpacingDp, verticalItemSpacingDp) of the layout it returns."""
    return side_inset_kt(c, content_width, columns), c["DESKTOP_HOME_GUTTER_DP"], c["DESKTOP_HOME_GUTTER_DP"]


def grid_columns_kt(c, layout, content_width, display_mode, fixed_column_count, preset_min_card, width_class):
    """desktopResolveHomeFeedGridColumns."""
    if shell.home_delegation_kt(c, layout, display_mode, fixed_column_count) is not None:
        return vendored_grid_columns(content_width, display_mode, fixed_column_count, preset_min_card, width_class)
    return shell.home_density_kt(c, content_width, preset_min_card)[0]


def card_layout_compact_kt(c, layout, style, grid_columns, width_class, content_width, delegated=False):
    """The compact metadata of desktopResolveHomeFeedCardLayout's result: the vendored value for CLASSIC and for a delegated DESKTOP feed,
    the rule's for the other DESKTOP feeds."""
    original = vendored_compact_metadata(style, grid_columns, width_class)
    if layout == "CLASSIC" or delegated:
        return original
    return card_compact_kt(c, original, content_width, grid_columns)


def card_layout_geometry_kt(c, layout, style, grid_columns, content_width, delegated=False):
    """The (outerPaddingDp, itemSpacingDp, verticalItemSpacingDp) of desktopResolveHomeFeedCardLayout's result:
    the vendored 6, 6, 6 for CLASSIC and for a delegated DESKTOP feed (desktopSelectHomeFeedCardLayout returns the vendored layout),
    the rule's geometry for the other DESKTOP feeds."""
    if layout == "CLASSIC" or delegated:
        return vendored_card_geometry(style)
    return card_geometry_kt(c, content_width, grid_columns)


def drawn_card_kt(geometry, content_width, columns):
    """The card width the vendored feed draws for a card layout geometry: the formula of the vendored cover request (HomeScreen.kt),
    (content width - 2 * outerPaddingDp - itemSpacingDp * (columns - 1)) / columns, and the grid width the padding leaves."""
    outer, item, _vertical = geometry
    count = max(columns, 1)
    return (content_width - outer * 2 - item * (count - 1)) / count, content_width - outer * 2
