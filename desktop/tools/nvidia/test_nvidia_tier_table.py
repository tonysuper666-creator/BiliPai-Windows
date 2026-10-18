"""Source checks for the NVIDIA tier table and the SKU cost factors (slice S1).

Data and source shape only: the tables are parsed from the marked Kotlin blocks and checked against
the written design. The JVM tests are the real drift detector for the Kotlin arithmetic."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

TIER_ORDER = ["ULTRA", "HIGH", "BALANCED", "LIGHT"]

# Design section 6.1 (every number is a hardware calibration proposal; changing one is a deliberate edit here too).
SPEC_TIERS = {
    "ULTRA": dict(qualityCeiling=4, maxScale=4.0, srInputMaxWidth=3440, srInputMaxHeight=1440,
                  nativeSizeMaxInputPixels=8_847_360, hdrOnlyMaxInputPixels=8_847_360, maxOutputPixels=14_745_600,
                  rateBaseMpixPerSecond=1000.0, hdrWithSrAllowed=True, hdrOnlyAllowed=True,
                  scalePolicy="NvidiaScalePolicy.STICKY_UP", mpvScaler="NvidiaMpvScalerClass.HQ_ULTRA"),
    "HIGH": dict(qualityCeiling=4, maxScale=4.0, srInputMaxWidth=3440, srInputMaxHeight=1440,
                 nativeSizeMaxInputPixels=8_847_360, hdrOnlyMaxInputPixels=8_847_360, maxOutputPixels=8_294_400,
                 rateBaseMpixPerSecond=500.0, hdrWithSrAllowed=True, hdrOnlyAllowed=True,
                 scalePolicy="NvidiaScalePolicy.STICKY_UP", mpvScaler="NvidiaMpvScalerClass.HQ"),
    "BALANCED": dict(qualityCeiling=3, maxScale=3.0, srInputMaxWidth=3440, srInputMaxHeight=1440,
                     nativeSizeMaxInputPixels=4_953_600, hdrOnlyMaxInputPixels=4_953_600, maxOutputPixels=3_686_400,
                     rateBaseMpixPerSecond=250.0, hdrWithSrAllowed=True, hdrOnlyAllowed=True,
                     scalePolicy="NvidiaScalePolicy.FOLLOWS_DISPLAY", mpvScaler="NvidiaMpvScalerClass.MPV_DEFAULT"),
    "LIGHT": dict(qualityCeiling=2, maxScale=2.0, srInputMaxWidth=2560, srInputMaxHeight=1080,
                  nativeSizeMaxInputPixels=2_764_800, hdrOnlyMaxInputPixels=2_764_800, maxOutputPixels=2_073_600,
                  rateBaseMpixPerSecond=125.0, hdrWithSrAllowed=False, hdrOnlyAllowed=True,
                  scalePolicy="NvidiaScalePolicy.FOLLOWS_DISPLAY", mpvScaler="NvidiaMpvScalerClass.MPV_DEFAULT"),
}

# Design section 6.2: (model, ti) -> (class, cost factor). Class D rows also start at quality ceiling 1.
SPEC_SKUS = {
    (5090, False): ("S", 1.0), (5080, False): ("S", 1.0), (5070, True): ("S", 0.5), (5070, False): ("A", 0.8),
    (5060, True): ("B", 1.0), (5060, False): ("B", 1.0), (5050, False): ("C", 1.0),
    (4090, False): ("S", 1.0), (4080, False): ("S", 0.5), (4070, True): ("A", 1.0), (4070, False): ("A", 0.7),
    (4060, True): ("B", 1.0), (4060, False): ("C", 1.0),
    (3090, False): ("A", 1.0), (3080, False): ("A", 0.9), (3070, False): ("B", 1.0), (3060, True): ("B", 1.0),
    (3060, False): ("C", 1.0), (3050, False): ("D", 0.6),
    (2080, False): ("B", 0.8), (2070, False): ("C", 0.8), (2060, False): ("C", 0.8), (2050, False): ("D", 0.6),
}

CLASS_TIER = {"S": "ULTRA", "A": "HIGH", "B": "BALANCED", "C": "LIGHT", "D": "LIGHT"}
CLASS_ORDER = ["S", "A", "B", "C", "D"]
TOP_THREE = {5090, 5080, 4090}


class TierTableSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tiers = source.tier_profiles()
        cls.skus = source.sku_rows()

    def test_blocks_are_marked_exactly_once(self):
        source.marked_block(source.read(source.TIER_PROFILE), "TIER-TABLE")
        source.marked_block(source.read(source.CAPABILITY), "GPU-CLASSIFY-TABLE")

    def test_four_tiers_in_best_first_order(self):
        self.assertEqual(TIER_ORDER, list(self.tiers))
        text = source.read(source.CAPABILITY)
        enum = re.search(r"enum class NvidiaTier \{ ([A-Z, ]+) \}", text)
        self.assertIsNotNone(enum)
        self.assertEqual(TIER_ORDER, [name.strip() for name in enum.group(1).split(",")])

    def test_table_equals_the_documented_values(self):
        for name, expected in SPEC_TIERS.items():
            actual = {key: self.tiers[name][key] for key in expected}
            self.assertEqual(expected, actual, name)

    def test_quality_and_scale_stay_inside_the_native_contract(self):
        for name, row in self.tiers.items():
            self.assertTrue(1 <= row["qualityCeiling"] <= 4, name)
            self.assertTrue(1.0 <= row["maxScale"] <= 4.0, name)
            self.assertTrue(row["hdrOnlyAllowed"], name)

    def test_tiers_are_monotone(self):
        for better, lower in zip(TIER_ORDER, TIER_ORDER[1:]):
            high, low = self.tiers[better], self.tiers[lower]
            for key in ("qualityCeiling", "maxScale", "nativeSizeMaxInputPixels", "hdrOnlyMaxInputPixels",
                        "maxOutputPixels", "rateBaseMpixPerSecond"):
                self.assertGreaterEqual(high[key], low[key], "%s %s" % (lower, key))
            self.assertGreaterEqual(high["srInputMaxWidth"] * high["srInputMaxHeight"],
                                    low["srInputMaxWidth"] * low["srInputMaxHeight"], lower)
            self.assertGreaterEqual(int(high["hdrWithSrAllowed"]), int(low["hdrWithSrAllowed"]), lower)

    def test_native_size_caps_cover_the_sr_box_and_hdr_only_equals_native_size_in_v1(self):
        for name, row in self.tiers.items():
            self.assertGreaterEqual(row["nativeSizeMaxInputPixels"], row["srInputMaxWidth"] * row["srInputMaxHeight"], name)
            self.assertEqual(row["nativeSizeMaxInputPixels"], row["hdrOnlyMaxInputPixels"], name)

    def test_top_tiers_take_four_k_inputs_and_lower_tiers_do_not(self):
        four_k = 3840 * 2160
        for name in ("ULTRA", "HIGH"):
            self.assertGreaterEqual(self.tiers[name]["nativeSizeMaxInputPixels"], 4096 * 2160, name)
        for name in ("BALANCED", "LIGHT"):
            self.assertLess(self.tiers[name]["nativeSizeMaxInputPixels"], four_k, name)

    def test_hdr_rate_factor_is_the_documented_proposal(self):
        self.assertEqual(1.25, source.hdr_rate_factor())

    def test_sku_table_equals_the_documented_rows(self):
        actual = {(row["model"], row["ti"]): (row["class"], row["costFactor"]) for row in self.skus}
        self.assertEqual(len(self.skus), len(actual), "one row per model and Ti flag")
        self.assertEqual(SPEC_SKUS, actual)

    def test_cost_factors_never_exceed_one_and_generations_follow_the_series(self):
        series = {2: "TURING", 3: "AMPERE", 4: "ADA", 5: "BLACKWELL"}
        for row in self.skus:
            self.assertTrue(0.0 < row["costFactor"] <= 1.0, row)
            self.assertEqual(series[row["model"] // 1000], row["generation"], row)

    def test_class_d_quality_ceiling_is_one_and_only_class_d_overrides(self):
        for row in self.skus:
            if row["class"] == "D":
                self.assertEqual(1, row["qualityCeilingOverride"], row)
            else:
                self.assertIsNone(row["qualityCeilingOverride"], row)
        self.assertTrue(any(row["class"] == "D" for row in self.skus))

    def test_no_sku_other_than_the_top_three_exceeds_five_hundred_megapixels_per_second(self):
        for row in self.skus:
            budget = self.tiers[CLASS_TIER[row["class"]]]["rateBaseMpixPerSecond"] * row["costFactor"]
            if row["model"] not in TOP_THREE:
                self.assertLessEqual(budget, 500.0, row)
        top = [self.tiers["ULTRA"]["rateBaseMpixPerSecond"] * row["costFactor"]
               for row in self.skus if row["model"] in TOP_THREE]
        self.assertTrue(all(value == 1000.0 for value in top))

    def test_budget_never_increases_for_a_lower_class_sku(self):
        budgets = {name: [] for name in CLASS_ORDER}
        for row in self.skus:
            budgets[row["class"]].append(self.tiers[CLASS_TIER[row["class"]]]["rateBaseMpixPerSecond"] * row["costFactor"])
        for better, lower in zip(CLASS_ORDER, CLASS_ORDER[1:]):
            self.assertGreaterEqual(min(budgets[better]), max(budgets[lower]), "%s versus %s" % (better, lower))

    def test_intensity_is_not_a_tier_lever(self):
        preferences = source.read(source.K / "player/DesktopNvidiaVideoPreferences.kt")
        line = next(item for item in preferences.splitlines() if "GENTLE(" in item)
        self.assertEqual([50, 75, 100], sorted(int(value) for value in re.findall(r"\((\d+), \"", line)))
        for path in (source.CAPABILITY, source.TIER_PROFILE, source.ACTIVATION):
            code, _ = source.lex_kotlin(source.read(path))
            self.assertNotRegex(code, r"intensity|Intensity", path.name)

    def test_native_presentation_settings_are_pinned_elsewhere_and_never_tier_driven(self):
        component = source.read(source.K / "player/DesktopVeyraPrivateComponent.kt")
        self.assertEqual(1, component.count(":peak=1000:timeout=1000"))
        for path in source.S1_CORE_FILES:
            code, _ = source.lex_kotlin(source.read(path))
            self.assertNotRegex(code, r"\bpeak\b|\btimeout\b", path.name)

    def test_four_x_ceiling_is_the_native_contract(self):
        video = source.read(source.K / "player/PlayerNvidiaVideo.kt")
        self.assertIn("scale in 1.0..4.0", video)
        self.assertIn("NVIDIA_ABSOLUTE_MAX_SCALE: Double = 4.0", source.read(source.TIER_PROFILE))

    def test_tier_file_keeps_the_new_types_out_of_the_reserved_policy_file(self):
        tier = source.read(source.TIER_PROFILE)
        self.assertEqual(1, tier.count("internal data class DesktopNvidiaVideoBudget("))
        self.assertEqual(1, tier.count("internal data class NvidiaPlan("))
        policy = source.read(source.K / "player/DesktopNvidiaVideoAutoPolicy.kt")
        # Slice S4: the policy file now consumes the budget, so it imports the type from the tier file's package
        # (design section 6: "which AutoPolicy.kt imports") but still never declares the budget or the plan.
        self.assertIn("import com.bilipai.desktop.nvidia.DesktopNvidiaVideoBudget\n", policy)
        self.assertNotIn("class DesktopNvidiaVideoBudget", policy)
        self.assertNotIn("class NvidiaPlan", policy)
        self.assertNotIn("NvidiaPlan", policy)


if __name__ == "__main__":
    unittest.main()
