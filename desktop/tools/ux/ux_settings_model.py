"""Reference and mirror model of the settings chrome (desktop UX program, slice X3). No JVM, no network.

Two pure Kotlin files of the slice have no Compose, AWT or Windows dependency, so their logic can be mirrored exactly:

  ui/DesktopWindowsSettingsModel.kt      the metrics of the settings chrome and the rule that picks the form of a choice
  settings/DesktopSettingsTreeModel.kt   the rail categories, the category of a page, the direct details and the stack rewrite

and the navigator (settings/DesktopSettingsNavigator.kt) holds the landing state that the tree uses. This module holds:

  * parsers for the marked blocks and the vendored category policy of those files, so the suites compare the Kotlin text
    with the contract instead of repeating numbers or tables;
  * mirrors: line-by-line Python transliterations of the Kotlin functions (Kotlin integer semantics, UTF-16 units for the
    label width), pinned in the suites as source needles, so editing the Kotlin without the mirror, or the mirror
    without the Kotlin, fails;
  * oracles: the same rules written differently, used by the sweeps;
  * the Navigator mirror, which replays the scenarios of the contract's "settings.tree.scenarios" table.

The composables (DesktopWindowsSettingsControls, DesktopSettingsTree, DesktopWindowsUxSettings, DesktopWindowsTipsShortcuts)
are not mirrored: they are judged by test_ux_settings_signatures.py (source rules) and, after a compile, by the Windows
fixtures. Python proves nothing about Kotlin compilation.
"""
import re
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

MODEL_KT = source.K / "ui/DesktopWindowsSettingsModel.kt"
TREE_MODEL_KT = source.K / "settings/DesktopSettingsTreeModel.kt"
NAVIGATOR_KT = source.K / "settings/DesktopSettingsNavigator.kt"
TREE_KT = source.K / "settings/DesktopSettingsTree.kt"
CONTROLS_KT = source.K / "ui/DesktopWindowsSettingsControls.kt"
STRINGS_KT = source.K / "ui/DesktopWindowsSettingsStrings.kt"
UX_GROUP_KT = source.K / "ui/DesktopWindowsUxSettings.kt"
UX_GROUP_STRINGS_KT = source.K / "ui/DesktopWindowsUxSettingsStrings.kt"
TIPS_KT = source.K / "ui/DesktopWindowsTipsSettings.kt"
TIPS_SHORTCUTS_KT = source.K / "ui/DesktopWindowsTipsShortcuts.kt"
TIPS_STRINGS_KT = source.K / "ui/DesktopWindowsTipsStrings.kt"
SHORTCUT_LIST_KT = source.K / "ui/DesktopWindowsShortcutList.kt"
PLAYER_SHORTCUTS_KT = source.K / "ui/DesktopPlayerShortcutPolicy.kt"
DISPLAY_SCALE_KT = source.K / "appearance/DesktopWindowsDisplayScaleSettings.kt"
PLAYBACK_SETTINGS_KT = source.K / "ui/DesktopWindowsPlaybackSettings.kt"
VENDORED_CATEGORY_POLICY = source.REPO / "app/src/main/java/com/android/purebilibili/feature/settings/SettingsRootCategoryPolicy.kt"
VENDORED_HIERARCHY_POLICY = source.REPO / "app/src/main/java/com/android/purebilibili/feature/settings/SettingsNavHierarchyPolicy.kt"

read = shell.read
squash = shell.squash
marked_block = shell.marked_block


# --------------------------------------------------------------------------------------
# Kotlin parsers
# --------------------------------------------------------------------------------------

def metric_constants(text):
    """{name: int} of the const vals between the UX-SETTINGS-METRICS markers."""
    return shell.int_constants(marked_block(text, "SETTINGS-METRICS"))


def metric_map(contract):
    """{Kotlin constant: contract number} for every constant of the metrics block."""
    section = contract["settings"]
    metrics, choice = section["metrics"], section["choice"]
    return {
        "DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP": metrics["content_max_width"],
        "DESKTOP_SETTINGS_RAIL_WIDTH_DP": metrics["rail_width"],
        "DESKTOP_SETTINGS_HEADER_HEIGHT_DP": metrics["header_height"],
        "DESKTOP_SETTINGS_ROW_PADDING_DP": metrics["row_padding"],
        "DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP": metrics["row_padding_vertical"],
        "DESKTOP_SETTINGS_PAGE_PADDING_DP": metrics["page_padding"],
        "DESKTOP_SETTINGS_SECTION_GAP_DP": metrics["section_gap"],
        "DESKTOP_SETTINGS_SEGMENTED_MAX": choice["segmented_max"],
        "DESKTOP_SETTINGS_SEGMENT_PADDING_DP": choice["segment_padding"],
        "DESKTOP_SETTINGS_SEGMENT_MIN_DP": choice["segment_min"],
        "DESKTOP_SETTINGS_TRACK_PADDING_DP": choice["track_padding"],
        "DESKTOP_SETTINGS_SEGMENT_GAP_DP": choice["segment_gap"],
        "DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP": choice["inline_control_max"],
        "DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP": choice["inline_title_min"],
        "DESKTOP_SETTINGS_GLYPH_WIDE_DP": choice["glyph"]["wide"],
        "DESKTOP_SETTINGS_GLYPH_NARROW_DP": choice["glyph"]["narrow"],
        "DESKTOP_SETTINGS_GLYPH_SPACE_DP": choice["glyph"]["space"],
    }


def kotlin_wide_ranges(text):
    """[(low, high)] of the wide-glyph ranges in desktopSettingsGlyphWidthDp, in source order."""
    start = text.index("private fun desktopSettingsGlyphWidthDp(")
    body = text[start:text.index("\n}\n", start)]
    return [(int(low, 16), int(high, 16)) for low, high in re.findall(r"code in 0x([0-9A-Fa-f]+)\.\.0x([0-9A-Fa-f]+)", body)]


def rail_categories(text):
    """The categories of DESKTOP_SETTINGS_RAIL_CATEGORIES in order, and the default category."""
    block = marked_block(text, "SETTINGS-RAIL")
    listed = re.search(r"DESKTOP_SETTINGS_RAIL_CATEGORIES: List<SettingsRootCategory> = listOf\((.*?)\n\)", block, re.S)
    default = re.search(r"DESKTOP_SETTINGS_DEFAULT_CATEGORY: SettingsRootCategory = SettingsRootCategory\.(\w+)", block)
    return re.findall(r"SettingsRootCategory\.(\w+)", listed.group(1)), default.group(1)


def rail_titles(text):
    """{category: title} of DesktopWindowsSettingsStrings.railTitle, read through the const vals it names."""
    consts = dict(re.findall(r'const val ([A-Z_]+) = "([^"]+)"', text))
    start = text.index("fun railTitle(category: SettingsRootCategory)")
    body = text[start:text.index("\n    }\n", start)]
    return {category: consts[name] for category, name in re.findall(r"SettingsRootCategory\.(\w+) -> ([A-Z_]+)\n", body)}


def vendored_category_policy():
    """(canonical, owner): the canonical-category map of canonicalSettingsRootCategory and the target -> category map of
    resolveSettingsRootCategoryForSearchTarget, both read from the vendored source (the tree delegates to them)."""
    text = read(VENDORED_CATEGORY_POLICY)
    start = text.index("internal fun canonicalSettingsRootCategory(")
    body = text[start:text.index("\n    }\n", start)]
    canonical = dict(re.findall(r"SettingsRootCategory\.(\w+) -> SettingsRootCategory\.(\w+)", body))
    start = text.index("internal fun resolveSettingsRootCategoryForSearchTarget(")
    body = text[start:text.index("\n}\n", start)]
    owner = {}
    for targets, category in re.findall(r"((?:SettingsSearchTarget\.\w+,\s*)*SettingsSearchTarget\.\w+)\s*->\s*SettingsRootCategory\.(\w+)", body):
        for target in re.findall(r"SettingsSearchTarget\.(\w+)", targets):
            owner[target] = category
    return canonical, owner


def vendored_direct_targets():
    """{category: target} of the original direct rule (resolveSettingsCategoryDirectTargetKey), with the two route types
    replaced the way the generator replaces them (AppearanceSettings -> APPEARANCE, PluginsSettings() -> PLUGINS)."""
    text = read(VENDORED_HIERARCHY_POLICY)
    start = text.index("internal fun resolveSettingsCategoryDirectTargetKey(")
    body = text[start:text.index("\n}\n", start)]
    found = re.findall(r"SettingsRootCategory\.(\w+) -> BiliPaiNavKey\.(AppearanceSettings|PluginsSettings\(\))", body)
    return {category: {"AppearanceSettings": "APPEARANCE", "PluginsSettings()": "PLUGINS"}[key] for category, key in found}


# --------------------------------------------------------------------------------------
# Mirrors of DesktopWindowsSettingsModel.kt
# --------------------------------------------------------------------------------------

class Choice:
    """The constants of the metrics block and the wide-glyph ranges, as read from the Kotlin text."""

    def __init__(self, constants, wide_ranges):
        self.c = constants
        self.wide_ranges = wide_ranges

    @classmethod
    def from_kotlin(cls):
        text = read(MODEL_KT)
        return cls(metric_constants(text), kotlin_wide_ranges(text))

    def glyph_width(self, unit):
        """desktopSettingsGlyphWidthDp for one UTF-16 unit (Kotlin Char.code)."""
        if any(low <= unit <= high for low, high in self.wide_ranges):
            return self.c["DESKTOP_SETTINGS_GLYPH_WIDE_DP"]
        if unit == 0x20:
            return self.c["DESKTOP_SETTINGS_GLYPH_SPACE_DP"]
        return self.c["DESKTOP_SETTINGS_GLYPH_NARROW_DP"]

    def label_width(self, label):
        """desktopSettingsLabelWidthDp: the sum over the UTF-16 units of the string, like Kotlin's String.sumOf."""
        encoded = label.encode("utf-16-le")
        units = struct.unpack("<%dH" % (len(encoded) // 2), encoded)
        return sum(self.glyph_width(unit) for unit in units)

    def segmented_width(self, labels):
        """desktopSettingsSegmentedWidthDp."""
        if not labels:
            return 0
        widest = max(self.label_width(label) for label in labels)
        segment = max(widest + self.c["DESKTOP_SETTINGS_SEGMENT_PADDING_DP"], self.c["DESKTOP_SETTINGS_SEGMENT_MIN_DP"])
        return len(labels) * segment + self.c["DESKTOP_SETTINGS_TRACK_PADDING_DP"] + self.c["DESKTOP_SETTINGS_SEGMENT_GAP_DP"] * (len(labels) - 1)

    def layout(self, presentation, labels, available_width):
        """desktopSettingsChoiceLayout: (form, controlWidthDp)."""
        available = max(available_width, 0)
        if presentation == "DROPDOWN":
            return "DROPDOWN", available
        if not labels or len(labels) > self.c["DESKTOP_SETTINGS_SEGMENTED_MAX"]:
            return "RADIO_ROWS", available
        needed = self.segmented_width(labels)
        if needed > available:
            return "RADIO_ROWS", available
        inline = (needed <= self.c["DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP"]
                  and available - needed >= self.c["DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP"])
        return ("SEGMENTED_INLINE" if inline else "SEGMENTED_STACKED"), needed


def make_label(descriptor):
    """A label of descriptor's wide, narrow and space glyph counts (the contract holds no Chinese text)."""
    return "中" * descriptor.get("wide", 0) + "a" * descriptor.get("narrow", 0) + " " * descriptor.get("space", 0)


def oracle_form(contract_choice, presentation, label_widths, available):
    """The same rule from the spec prose, over label widths instead of labels: DROPDOWN only when asked; otherwise
    segmented when there are one to four labels and the equal segments fit, inline when the control is at most 360 and
    leaves 200 for the title; otherwise radio rows."""
    if presentation == "DROPDOWN":
        return "DROPDOWN"
    count = len(label_widths)
    if count == 0 or count > contract_choice["segmented_max"]:
        return "RADIO_ROWS"
    segment = max(max(label_widths) + contract_choice["segment_padding"], contract_choice["segment_min"])
    needed = segment * count + contract_choice["track_padding"] + contract_choice["segment_gap"] * (count - 1)
    if needed > max(available, 0):
        return "RADIO_ROWS"
    room = max(available, 0) - needed
    return "SEGMENTED_INLINE" if needed <= contract_choice["inline_control_max"] and room >= contract_choice["inline_title_min"] else "SEGMENTED_STACKED"


# --------------------------------------------------------------------------------------
# Mirrors of DesktopSettingsTreeModel.kt and the navigator
# --------------------------------------------------------------------------------------

class Tree:
    """The category rules of the tree, over the vendored category policy and the Kotlin rail list."""

    def __init__(self):
        self.rail, self.default = rail_categories(read(TREE_MODEL_KT))
        self.canonical_map, self.owner = vendored_category_policy()
        self.original_direct = vendored_direct_targets()

    def canonical(self, category):
        return self.canonical_map.get(category, category)

    def page_category(self, page):
        """desktopSettingsPageCategory. A page is ("Root",), ("Search",), ("Category", c), ("Detail", target) or ("CommentFraud",)."""
        kind = page[0]
        if kind in ("Root", "Search"):
            category = None
        elif kind == "Category":
            category = self.canonical(page[1])
        elif kind == "Detail":
            owner = self.owner.get(page[1])
            category = None if owner is None else self.canonical(owner)
        else:
            category = "PRIVACY_PERMISSION"
        return category if category in self.rail else None

    def landable(self, page):
        """desktopSettingsLandableCategory."""
        category = self.page_category(page)
        return category if category != "SYSTEM_ABOUT" else None

    def direct_detail(self, category):
        """desktopSettingsDirectDetail."""
        canonical = self.canonical(category)
        original = self.original_direct.get(canonical)
        if original is not None:
            return original
        return {"PLAYBACK_QUALITY": "PLAYBACK", "HOME_RECOMMENDATION": "HOME_FEED"}.get(canonical)

    def rail_target(self, category):
        """desktopSettingsRailTarget."""
        canonical = self.canonical(category)
        detail = self.direct_detail(canonical)
        return ("Detail", detail) if detail is not None else ("Category", canonical)

    def rail_click_redundant(self, page, category):
        """desktopSettingsRailClickIsRedundant: the page that is open is the page the click would open."""
        return tuple(page) == self.rail_target(category)

    @staticmethod
    def replace_stack(stack, target):
        """desktopSettingsReplaceStack."""
        end = len(stack)
        while end > 1 and stack[end - 1][0] in ("Category", "Detail"):
            end -= 1
        return list(stack[:end]) + [target]


class Navigator:
    """DesktopSettingsNavigator over the tree rules. Pages are the tuples of Tree.page_category."""

    def __init__(self, tree, can_open_detail=lambda target: True):
        self.tree = tree
        self.can_open_detail = can_open_detail
        self.stack = [("Root",)]
        self.pending = False
        self.last = tree.default
        self.searches = 0

    def note(self, page):
        category = self.tree.landable(page)
        if category is not None:
            self.last = category

    def open_root(self):
        self.stack, self.pending = [("Root",)], True

    def replace_category(self, category):
        target = self.tree.rail_target(category)
        if target[0] == "Detail" and not self.can_open_detail(target[1]):
            return
        stack = self.tree.replace_stack(self.stack, target)
        if stack == self.stack:
            return
        self.note(target)
        self.stack, self.pending = stack, False

    def land_on_last_category(self):
        if not self.pending or self.stack[-1] != ("Root",):
            return
        self.replace_category(self.last)

    def push(self, page):
        self.note(page)
        self.stack, self.pending = self.stack + [page], False

    def open_search(self):
        self.searches += 1
        self.push(("Search",))

    def open_category(self, category):
        canonical = self.tree.canonical(category)
        direct = self.tree.original_direct.get(canonical)
        if direct is not None:
            self.open_detail(direct)
        else:
            self.push(("Category", canonical))

    def open_detail(self, target):
        if not self.can_open_detail(target):
            return
        self.push(("Detail", target))

    def pop(self):
        if len(self.stack) <= 1:
            return False
        self.stack, self.pending = self.stack[:-1], False
        self.note(self.stack[-1])
        return True

    def run(self, steps):
        for step in steps:
            name, _, argument = step.partition(":")
            if name == "root":
                self.open_root()
            elif name == "land":
                self.land_on_last_category()
            elif name == "rail":
                self.replace_category(argument)
            elif name == "open_category":
                self.open_category(argument)
            elif name == "open_detail":
                self.open_detail(argument)
            elif name == "search":
                self.open_search()
            elif name == "pop":
                self.pop()
            else:
                raise ValueError("unknown step " + step)
        return self

    def describe(self):
        """The stack as the contract writes it, the last landable category and whether a landing is pending."""
        names = []
        for page in self.stack:
            names.append(page[0] if page[0] in ("Root", "Search", "CommentFraud") else "%s:%s" % (page[0], page[1]))
        return {"stack": names, "last": self.last, "pending": self.pending}
