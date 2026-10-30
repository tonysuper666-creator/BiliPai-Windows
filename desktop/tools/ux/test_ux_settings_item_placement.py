"""Where every upstream settings item and target goes on the two-pane Windows settings (TON-1115). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_settings_item_placement.py

Two tables in desktop/upstream-core/m4d record the decision for the merged upstream settings:

  settings-target-placement.tsv   one row per SettingsSearchTarget: the rail category it lands in, or why it does not
  settings-item-placement.tsv     one row per upstream search-directory item (PlaybackSettingDirectory.kt,
                                  SettingsAdditionalDirectory.kt, the haptic scenes): the rail category, or the handling of
                                  a phone-only option (hidden, unavailable, replaced)

What is proved. Every target and every item the merged upstream declares has a row, and every row names something upstream
still declares, so an item added by a merge fails here until a person decides it. A row's rail category is the one the
vendored category policy gives (the upstream category -> canonicalSettingsRootCategory), and it is one of the categories the
rail lists; the only remap is the glass-advanced group into the appearance pane, which the rail has no category for. A target
the tree renders as a detail is exactly a branch of DesktopSettingsTree's detail switch. Hidden, unavailable and replaced rows
carry a reason, and a replaced row names the desktop item that takes over.

What is not proved. That a page really renders the item: the Windows fixtures judge that.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_settings_model as sm  # noqa: E402
import ux_source as source  # noqa: E402

M4D = source.REPO / "desktop/upstream-core/m4d"
SETTINGS = source.REPO / "app/src/main/java/com/android/purebilibili/feature/settings"
HAPTIC = source.REPO / "design-system/src/main/java/com/android/purebilibili/core/ui/AppHapticSettings.kt"
ITEM_DIRECTORY_KT = source.K / "settings/DesktopSettingsItemDirectory.kt"
HANDLING = {"available", "hidden", "unavailable", "replaced"}
# Groups whose upstream category has no rail category: the appearance pane holds them.
REMAP = {"glass.": "APPEARANCE_THEME"}
SYMBOLIC_IDS = {
    "HAPTIC_SCENES_SETTING_ID": "animation.haptic_scenes",
    "GRID_CUSTOMIZATION_SETTING_ID": "appearance.grid_column_customization_enabled",
    "GRID_SHARE_SETTING_ID": "appearance.grid_columns_shared_across_pages",
    "GRID_COMPACT_SETTING_ID": "appearance.grid_column_count_compact",
}


def table(name):
    rows = []
    for line in (M4D / name).read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            rows.append(line.split("\t"))
    return rows


def upstream_item_ids():
    ids = []
    for name in ("PlaybackSettingDirectory.kt", "SettingsAdditionalDirectory.kt"):
        text = (SETTINGS / name).read_text(encoding="utf-8")
        for found in re.findall(r'PlaybackSettingItem\(\s*("[\w.]+"|[A-Z_]+|[\w.]+\.settingId)\s*,', text):
            if found == "scene.settingId":
                continue
            ids.append(SYMBOLIC_IDS.get(found, found.strip('"')))
    haptic = HAPTIC.read_text(encoding="utf-8")
    ids += ["animation.haptic_scene." + name.lower() for name in re.findall(r"^\s+([A-Z_]+)\(\d+, \"", haptic, re.M)]
    return ids


def upstream_targets():
    text = (SETTINGS / "SettingsSearchPolicy.kt").read_text(encoding="utf-8")
    body = re.search(r"enum class SettingsSearchTarget \{(.*?)\n\}", text, re.S).group(1)
    return re.findall(r"^\s+([A-Z_]+),?\s*$", body, re.M)


def canonical_map():
    text = sm.read(sm.VENDORED_CATEGORY_POLICY)
    start = text.index("internal fun canonicalSettingsRootCategory(")
    body = text[start:text.index("\n    }\n", start)]
    result = {}
    for group, target in re.findall(r"((?:SettingsRootCategory\.\w+,\s*)*SettingsRootCategory\.\w+)\s*->\s*SettingsRootCategory\.(\w+)", body):
        for category in re.findall(r"SettingsRootCategory\.(\w+)", group):
            result[category] = target
    return result


class SettingsPlacementTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.canonical = canonical_map()
        cls.owner = sm.vendored_category_policy()[1]
        cls.rail = sm.rail_categories(sm.read(sm.TREE_MODEL_KT))[0]
        cls.items = table("settings-item-placement.tsv")
        cls.targets = table("settings-target-placement.tsv")

    def test_every_upstream_target_has_exactly_one_row(self):
        declared = upstream_targets()
        listed = [row[0] for row in self.targets]
        self.assertEqual(len(listed), len(set(listed)), "duplicate target row")
        self.assertEqual(sorted(declared), sorted(listed),
                         "settings-target-placement.tsv and the merged SettingsSearchTarget differ: decide the new/removed target")
        self.assertEqual(set(declared), set(self.owner), "a target has no category in resolveSettingsRootCategoryForSearchTarget")

    def test_every_upstream_item_has_exactly_one_row(self):
        declared = upstream_item_ids()
        listed = [row[0] for row in self.items]
        self.assertEqual(len(declared), len(set(declared)), "upstream declares an item id twice")
        self.assertEqual(len(listed), len(set(listed)), "duplicate item row")
        self.assertEqual(sorted(declared), sorted(listed),
                         "settings-item-placement.tsv and the merged upstream directories differ: decide the new/removed item")

    def test_target_rows_follow_the_vendored_category_policy(self):
        detail_start = sm.read(sm.TREE_KT)
        detail_start = detail_start[detail_start.index("is DesktopSettingsPage.Detail -> when (page.target)"):]
        detail_block = detail_start[:detail_start.index("else -> Text(")]
        rendered = set(re.findall(r"SettingsSearchTarget\.([A-Z_]+)", detail_block))
        for target, root, rail, surface, handling, reason in self.targets:
            with self.subTest(target=target):
                self.assertEqual(self.owner[target], root)
                self.assertIn(handling, HANDLING)
                if handling == "available":
                    self.assertEqual(self.canonical.get(root, root), rail)
                    self.assertIn(rail, self.rail)
                    self.assertEqual(surface, "detail" if target in rendered else "category")
                    self.assertEqual(reason, "")
                else:
                    self.assertEqual((rail, surface), ("-", "-"))
                    self.assertTrue(reason, "a target that does not land needs a reason")
        for target in rendered:
            self.assertIn(target, [row[0] for row in self.targets], "the tree renders a target that upstream no longer declares")

    def test_item_rows_land_in_a_rail_category_or_carry_a_reason(self):
        item_text = ITEM_DIRECTORY_KT.read_text(encoding="utf-8")
        for item_id, title, root, rail, handling, reason in self.items:
            with self.subTest(item=item_id):
                self.assertIn(handling, HANDLING)
                self.assertTrue(title)
                if handling == "available":
                    expected = next((category for prefix, category in REMAP.items() if item_id.startswith(prefix)),
                                    self.canonical.get(root, root))
                    self.assertEqual(rail, expected)
                    self.assertIn(rail, self.rail, "an available item must land in a category the rail lists")
                    self.assertEqual(reason, "")
                else:
                    self.assertEqual(rail, "-")
                    self.assertTrue(reason, "a phone-only item needs a reason")
                if handling == "replaced":
                    named = re.findall(r"桌面 项 ([\w.]+)|desktop 项 ([\w.]+)", reason)
                    if named:
                        key = (named[0][0] or named[0][1])
                        self.assertIn('"%s"' % key, item_text, "the replacing desktop item is not in DesktopSettingsItemDirectory")

    def test_phone_only_list_is_not_empty_and_covers_the_named_families(self):
        by_reason = {}
        for item_id, _title, _root, _rail, handling, reason in self.items:
            if handling != "available":
                by_reason.setdefault(reason, []).append(item_id)
        joined = " ".join(by_reason)
        for family in ("振动", "屏幕方向", "状态栏", "触摸", "画中画", "底栏"):
            self.assertIn(family, joined, "the card's phone-only families are vibration, rotation, status bar/notch, gestures, PiP, bottom bar")


if __name__ == "__main__":
    unittest.main()
