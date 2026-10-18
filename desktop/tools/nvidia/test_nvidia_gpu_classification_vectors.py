"""Data checks for the shared NVIDIA golden vectors and the purity of the S1 core (slice S1).

The vectors are parsed as data and compared with the Kotlin tables as data. Nothing here
re-implements the Kotlin classification: the JVM tests run every vector through the real code."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

TIER_RANK = {"VRAM_LOW": -1, "LIGHT": 0, "BALANCED": 1, "HIGH": 2, "ULTRA": 3}
GENERATIONS = {"UNKNOWN", "PRE_RTX", "TURING", "AMPERE", "ADA", "BLACKWELL", "NEWER"}
REASONS = {"PRE_RTX", "NOT_PHYSICAL", "PIN_UNSAFE", "PHYSICAL_UNVERIFIED", "VRAM_LOW", "DRIVER_OLD"}
NOTES = {"CROSS_ADAPTER", "MOBILE_STEP_DOWN", "DRIVER_ADVISORY", "PHYSICAL_ASSUMED_UNIQUE", "VRAM_UNKNOWN",
         "UNKNOWN_MODEL", "PROFESSIONAL_PART", "OBSERVED_IDENTITY"}
STATES = {"OFF", "LEGACY", "PROBING", "NO_NVIDIA", "UNSUPPORTED", "UNIDENTIFIED", "STANDBY", "PAUSED_GAME",
          "PAUSED_BATTERY", "PAUSED_REMOTE", "FAILED_REMEMBERED", "FAILED_THIS_VIDEO", "FAILED_EXTENDED_RANGE",
          "ALLOWED"}
CLASS_CAP = {"S": "ULTRA", "A": "HIGH", "B": "BALANCED", "C": "LIGHT", "D": "LIGHT"}
CLASS_ORDER = ["S", "A", "B", "C", "D"]
HEX = re.compile(r"0x[0-9A-Fa-f]+")
LUID_HEX = re.compile(r"[0-9a-f]{16}")

# The shader-list option name is assembled so that this file never spells it out.
GATE_LITERALS = ("glsl" + "-shaders", "interpolation", "video-sync", "d3d11-flip", "target-colorspace-hint",
                 "VeyraPresentationTracker", "proved =", "veyraReceipt")
FORBIDDEN_APIS = ("nvmlDeviceSet", "nvmlUnit", "NvAPI_", "RegSetValue", "RegCreateKey", "ChangeDisplaySettings",
                  "SetPriorityClass", "SetThreadPriority", "powercfg", "D3D11CreateDevice", "D3D12CreateDevice",
                  "DLSS")
IO_MARKERS = ("import java.io", "import java.nio", "import com.sun.jna", "import kotlinx.coroutines",
              "import java.net", "import java.util.concurrent", "System.", "Runtime.", "Thread.", "File(",
              "Files.", "Native.", "Library", "currentTimeMillis", "nanoTime", "Instant.now", "Clock")
OVERLOADED = {"luidMatches"}
TOP_LEVEL = re.compile(
    r"^((?:(?:internal|private|public|data|sealed|enum|abstract|open|const|inline|value)\s+)*)"
    r"(class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)",
    re.MULTILINE)
FORBIDDEN_WORDS = ("DLSS", "Tensor", "帧生成", "插帧", "补帧", "HDR10+", "支持 HDR", "已应用")


def tier_of(expect):
    return expect["tier"] if expect["kind"] == "capable" else expect["reason"]


class VectorSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = source.load_vectors()
        cls.pci = source.pci_models()
        cls.skus = source.sku_rows()
        cls.gpus = {gpu["id"]: gpu for gpu in cls.doc["gpus"]}
        cls.scenarios = {scenario["id"]: scenario for scenario in cls.doc["scenarios"]}

    def test_file_is_utf8_lf_and_ends_with_a_newline(self):
        data = source.VECTORS.read_bytes()
        data.decode("utf-8")
        self.assertNotIn(b"\r", data)
        self.assertTrue(data.endswith(b"\n"))

    def test_top_level_shape(self):
        self.assertEqual(1, self.doc["schema"])
        for key in ("description", "notes", "driverConversions", "packedDrivers", "paths", "luidMatches", "gpus",
                    "vramLadder", "unknownModelLadder", "scenarios"):
            self.assertIn(key, self.doc)
        self.assertIsInstance(self.doc["notes"], list)

    def test_gpu_vectors_have_unique_ids_and_a_complete_shape(self):
        ids = [gpu["id"] for gpu in self.doc["gpus"]]
        self.assertEqual(len(ids), len(set(ids)))
        for gpu in self.doc["gpus"]:
            label = gpu["id"]
            self.assertRegex(gpu["deviceId"], HEX, label)
            self.assertTrue(gpu["description"].strip(), label)
            self.assertIsInstance(gpu["vramMiB"], int, label)
            self.assertGreaterEqual(gpu["vramMiB"], 0, label)
            driver = gpu["driver"]
            self.assertTrue(driver is None or (len(driver) == 4 and all(isinstance(v, int) for v in driver)), label)
            self.assertIn(gpu["pciTable"], ("known", "unknown"), label)
            expect = gpu["expect"]
            if expect["kind"] == "capable":
                self.assertIn(expect["tier"], ("ULTRA", "HIGH", "BALANCED", "LIGHT"), label)
                self.assertIn(expect["generation"], GENERATIONS, label)
                self.assertIn(expect["skuClass"], ("S", "A", "B", "C", "D", None), label)
                self.assertTrue(0.0 < expect["costFactor"] <= 1.0, label)
                self.assertIn(expect["qualityCeiling"], (1, 2, 3, 4), label)
                self.assertIsInstance(expect["mobile"], bool, label)
                self.assertIsInstance(expect["hdrDriverOk"], bool, label)
                self.assertTrue(set(expect["notes"]) <= NOTES, label)
            else:
                self.assertEqual("unsupported", expect["kind"], label)
                self.assertIn(expect["reason"], REASONS, label)

    def test_scenario_vectors_have_unique_ids_and_a_complete_shape(self):
        ids = [scenario["id"] for scenario in self.doc["scenarios"]]
        self.assertEqual(len(ids), len(set(ids)))
        for scenario in self.doc["scenarios"]:
            label = scenario["id"]
            self.assertIsInstance(scenario["dxgiComplete"], bool, label)
            display = scenario["displayAdapterLuid"]
            self.assertTrue(display is None or LUID_HEX.fullmatch(display), label)
            for adapter in scenario["adapters"]:
                for key in ("vendorId", "deviceId", "subSysId", "revision", "luidLow"):
                    self.assertRegex(adapter[key], HEX, label)
                for key in ("vramMiB", "luidHigh", "flags", "outputCount"):
                    self.assertIsInstance(adapter[key], int, label)
                self.assertTrue(adapter["pnpPath"] is None or isinstance(adapter["pnpPath"], str), label)
            expect = scenario["expect"]
            self.assertIn(expect["kind"], ("capable", "unsupported", "absent", "unknown"), label)
            if expect["kind"] == "capable":
                self.assertRegex(expect["primaryLuid"], LUID_HEX, label)
                self.assertRegex(expect["pin"], r"^(exact:.+|legacy|none)$", label)
                self.assertTrue(set(expect["notes"]) <= NOTES, label)
            for activation in scenario["activations"]:
                self.assertIn(activation["state"], STATES, label)
                self.assertEqual(activation["state"] == "ALLOWED", activation["request"], label)
                self.assertIn(activation.get("mode", "AUTO"), ("AUTO", "OFF"), label)

    def test_driver_and_path_and_luid_vectors_have_plain_shapes(self):
        for item in self.doc["driverConversions"]:
            self.assertEqual(4, len(item["fields"]))
            self.assertTrue(item["publicX100"] is None or isinstance(item["publicX100"], int))
            if item["publicX100"] is not None:
                value = item["publicX100"]
                self.assertEqual("%d.%02d" % (value // 100, value % 100), item["text"])
        for item in self.doc["packedDrivers"]:
            packed = (item["fields"][0] << 48) | (item["fields"][1] << 32) | (item["fields"][2] << 16) | item["fields"][3]
            self.assertEqual(str(packed), item["packed"])
        for item in self.doc["paths"]:
            self.assertIn(item["verdict"], ("PHYSICAL", "VIRTUAL", "UNKNOWN"))
        for item in self.doc["luidMatches"]:
            self.assertIsInstance(item["match"], bool)

    def test_every_known_device_id_is_in_the_kotlin_pci_table_and_unknown_ones_are_not(self):
        for gpu in self.doc["gpus"]:
            device = int(gpu["deviceId"], 16)
            if gpu["pciTable"] == "known":
                self.assertIn(device, self.pci, gpu["id"])
            else:
                self.assertNotIn(device, self.pci, gpu["id"])

    def test_every_kotlin_pci_id_is_exercised_by_a_vector(self):
        used = {int(gpu["deviceId"], 16) for gpu in self.doc["gpus"]}
        for scenario in self.doc["scenarios"]:
            used |= {int(adapter["deviceId"], 16) for adapter in scenario["adapters"]}
        self.assertEqual(set(), set(self.pci) - used)

    def test_pci_table_is_consistent_with_the_sku_table(self):
        rows = {(row["model"], row["ti"]): row for row in self.skus}
        for device, entry in self.pci.items():
            row = rows.get((entry["model"], entry["ti"])) or rows.get((entry["model"], False))
            self.assertIsNotNone(row, hex(device))
            self.assertEqual(row["generation"], entry["generation"], hex(device))

    def test_id_ranges_are_ordered_and_do_not_overlap(self):
        ranges = source.id_ranges()
        self.assertEqual(["TURING", "AMPERE", "ADA", "BLACKWELL"], [generation for _, _, generation in ranges])
        for (first, last, _), (next_first, _, _) in zip(ranges, ranges[1:]):
            self.assertLessEqual(first, last)
            self.assertLess(last, next_first)
        for device, entry in self.pci.items():
            owner = [generation for first, last, generation in ranges if first <= device <= last]
            self.assertEqual([entry["generation"]], owner, hex(device))


class SpecVectorCoverageTest(unittest.TestCase):
    """Every vector the design lists in section 16 is present with the stated outcome."""

    @classmethod
    def setUpClass(cls):
        doc = source.load_vectors()
        cls.doc = doc
        cls.gpus = {gpu["id"]: gpu["expect"] for gpu in doc["gpus"]}
        cls.scenarios = {scenario["id"]: scenario for scenario in doc["scenarios"]}
        cls.pci = source.pci_models()
        cls.skus = {(row["model"], row["ti"]): row for row in source.sku_rows()}

    def test_2c02_is_blackwell_class_s_and_the_5080_golden_is_ultra(self):
        self.assertEqual("BLACKWELL", self.pci[0x2C02]["generation"])
        self.assertEqual(5080, self.pci[0x2C02]["model"])
        self.assertEqual("S", self.skus[(5080, False)]["class"])
        self.assertEqual("ULTRA", self.gpus["rtx-5080-this-pc"]["tier"])
        self.assertEqual("S", self.gpus["rtx-5080-this-pc"]["skuClass"])
        self.assertEqual("ULTRA", self.scenarios["this-pc"]["expect"]["tier"])

    def test_this_pc_scenario_carries_the_four_measured_adapters(self):
        scenario = self.scenarios["this-pc"]
        adapters = scenario["adapters"]
        self.assertEqual(4, len(adapters))
        physical, amd, twin, software = adapters
        self.assertEqual(0xE782, int(physical["luidLow"], 16))
        self.assertEqual(1, physical["outputCount"])
        self.assertTrue(physical["pnpPath"].startswith("\\\\?\\PCI#VEN_10DE&DEV_2C02&SUBSYS_8A4A1043&REV_A1"))
        self.assertEqual(0x1002, int(amd["vendorId"], 16))
        self.assertEqual(0xB95AD, int(twin["luidLow"], 16))
        self.assertEqual(0, twin["outputCount"])
        self.assertTrue(twin["pnpPath"].startswith("\\\\?\\ROOT#DISPLAY#"))
        for key in ("vendorId", "deviceId", "subSysId", "revision", "description", "vramMiB", "umd"):
            self.assertEqual(physical[key], twin[key], "the twin is identical apart from the PnP path: %s" % key)
        self.assertEqual((0x1414, 0x008C), (int(software["vendorId"], 16), int(software["deviceId"], 16)))
        self.assertEqual(15978, physical["vramMiB"])
        self.assertEqual([32, 0, 16, 1742], physical["umd"])
        self.assertEqual("000000000000e782", scenario["displayAdapterLuid"])
        self.assertEqual("000000000000e782", scenario["expect"]["primaryLuid"])
        self.assertFalse(scenario["expect"]["crossAdapter"])
        self.assertEqual("exact:NVIDIA GeForce RTX 5080", scenario["expect"]["pin"])

    def test_virtual_twin_first_amd_only_and_only_virtual_outcomes(self):
        self.assertEqual({"kind": "unsupported", "reason": "PIN_UNSAFE"}, self.scenarios["twin-first-pin-unsafe"]["expect"])
        self.assertEqual({"kind": "unsupported", "reason": "NOT_PHYSICAL"}, self.scenarios["only-virtual"]["expect"])
        self.assertEqual({"kind": "absent"}, self.scenarios["amd-only"]["expect"])
        first = self.scenarios["twin-first-pin-unsafe"]["adapters"][0]
        self.assertEqual(0xB95AD, int(first["luidLow"], 16))

    def test_a_virtual_sounding_name_only_ever_excludes(self):
        for name in ("rtx-5080-virtual-name-excluded", "rtx-5080-remote-name-excluded"):
            self.assertEqual({"kind": "unsupported", "reason": "NOT_PHYSICAL"}, self.gpus[name], name)
        excluded = self.scenarios["virtual-name-excludes-even-with-a-pci-path"]
        self.assertTrue(excluded["adapters"][0]["pnpPath"].startswith("\\\\?\\PCI#VEN_10DE"))
        self.assertEqual({"kind": "unsupported", "reason": "NOT_PHYSICAL"}, excluded["expect"])
        pinned = self.scenarios["virtual-named-adapter-first-physical-still-pins"]["expect"]
        self.assertEqual("exact:NVIDIA GeForce RTX 5080", pinned["pin"])
        self.assertEqual("ULTRA", pinned["tier"])

    def test_dxgi_failed_with_the_twin_present_yields_no_request(self):
        scenario = self.scenarios["dxgi-failed-twin-present"]
        self.assertFalse(scenario["dxgiComplete"])
        self.assertTrue(any(int(a["luidLow"], 16) == 0xB95AD for a in scenario["adapters"]))
        self.assertEqual({"kind": "unknown", "reason": "PROBE_FAILED"}, scenario["expect"])
        plain = [a for a in scenario["activations"] if a["mode"] == "AUTO" and "observed" not in a]
        self.assertTrue(plain)
        for activation in plain:
            self.assertEqual("UNIDENTIFIED", activation["state"])
            self.assertFalse(activation["request"])
        unverified = [a for a in scenario["activations"] if a.get("observed", {}).get("verified") is False]
        self.assertTrue(unverified)
        self.assertTrue(all(a["state"] == "UNIDENTIFIED" for a in unverified))
        verified = [a for a in scenario["activations"]
                    if a.get("observed", {}).get("verified") is True and a["mode"] == "AUTO" and a["sourceLoaded"]]
        self.assertTrue(verified)
        self.assertTrue(all(a["state"] == "ALLOWED" for a in verified))

    def test_named_gpu_tiers_from_the_design(self):
        self.assertEqual({"kind": "unsupported", "reason": "PRE_RTX"}, self.gpus["gtx-1660-name-beats-turing-id"])
        self.assertEqual("LIGHT", self.gpus["rtx-2060"]["tier"])
        self.assertEqual("BALANCED", self.gpus["rtx-3060-ti"]["tier"])
        self.assertEqual("HIGH", self.gpus["rtx-4070"]["tier"])
        self.assertEqual("HIGH", self.gpus["rtx-4070-ti-super-16g"]["tier"])
        for name in ("rtx-4080", "rtx-4090", "rtx-5070-ti-16g", "rtx-5080-this-pc"):
            self.assertEqual("ULTRA", self.gpus[name]["tier"], name)
        self.assertEqual("BALANCED", self.gpus["rtx-5060-ti-8g"]["tier"])
        self.assertEqual("HIGH", self.gpus["rtx-5080-laptop"]["tier"])
        for name in ("rtx-a2000-6g", "rtx-a2000-12g"):
            self.assertLessEqual(TIER_RANK[self.gpus[name]["tier"]], TIER_RANK["HIGH"], name)

    def test_professional_names_keep_their_own_generation(self):
        self.assertEqual("ADA", self.gpus["rtx-2000-ada-not-turing"]["generation"])
        self.assertEqual("ADA", self.gpus["rtx-5000-ada-not-blackwell"]["generation"])
        self.assertEqual("TURING", self.gpus["quadro-rtx-5000-not-blackwell"]["generation"])
        for name in ("rtx-2000-ada-not-turing", "rtx-5000-ada-not-blackwell", "quadro-rtx-5000-not-blackwell",
                     "rtx-pro-6000-blackwell", "rtx-a6000"):
            self.assertLessEqual(TIER_RANK[self.gpus[name]["tier"]], TIER_RANK["HIGH"], name)
            self.assertIn("PROFESSIONAL_PART", self.gpus[name]["notes"], name)

    def test_modifier_names_keep_the_base_class_and_unknowns_are_capped(self):
        self.assertEqual("S", self.gpus["rtx-5080-super-base-class"]["skuClass"])
        self.assertEqual("S", self.gpus["rtx-5090-d-base-class"]["skuClass"])
        self.assertEqual("ULTRA", self.gpus["rtx-5080-super-base-class"]["tier"])
        self.assertEqual("BALANCED", self.gpus["unknown-id-8g"]["tier"])
        self.assertEqual("LIGHT", self.gpus["unknown-id-4g"]["tier"])
        self.assertEqual({"kind": "unsupported", "reason": "VRAM_LOW"}, self.gpus["unknown-id-2g"])
        self.assertLessEqual(TIER_RANK[self.gpus["unknown-model-in-blackwell-range"]["tier"]], TIER_RANK["BALANCED"])
        self.assertLessEqual(TIER_RANK[self.gpus["rtx-6090-unknown-base"]["tier"]], TIER_RANK["HIGH"])
        self.assertEqual("NEWER", self.gpus["rtx-6090-unknown-base"]["generation"])

    def test_driver_vectors_from_the_design(self):
        conversions = {tuple(item["fields"]): item["publicX100"] for item in self.doc["driverConversions"]}
        self.assertEqual(61742, conversions[(32, 0, 16, 1742)])
        self.assertEqual(54601, conversions[(31, 0, 15, 4601)])
        self.assertEqual(57602, conversions[(32, 0, 15, 7602)])
        advisory = self.gpus["rtx-4070-driver-530-advisory"]
        self.assertEqual("HIGH", advisory["tier"])
        self.assertIn("DRIVER_ADVISORY", advisory["notes"])
        self.assertEqual({"kind": "unsupported", "reason": "DRIVER_OLD"}, self.gpus["rtx-5080-driver-550-old"])
        self.assertEqual({"kind": "unsupported", "reason": "DRIVER_OLD"}, self.gpus["rtx-5080-driver-7215-old"])
        self.assertEqual("ULTRA", self.gpus["rtx-5080-driver-7216-floor"]["tier"])
        self.assertEqual("ULTRA", self.gpus["rtx-5080-driver-unknown"]["tier"])
        self.assertFalse(self.gpus["rtx-4070-driver-550-no-hdr"]["hdrDriverOk"])
        self.assertTrue(self.gpus["rtx-4070-driver-5123-hdr-boundary"]["hdrDriverOk"])
        self.assertFalse(self.gpus["rtx-4070-driver-5122-below-hdr"]["hdrDriverOk"])

    def test_class_d_quality_ceiling_is_one(self):
        for name in ("rtx-3050-class-d", "rtx-2050-class-d"):
            self.assertEqual("D", self.gpus[name]["skuClass"], name)
            self.assertEqual(1, self.gpus[name]["qualityCeiling"], name)
            self.assertEqual("LIGHT", self.gpus[name]["tier"], name)

    def test_pre_rtx_names_never_map_to_an_eligible_tier(self):
        pattern = re.compile(r"\b(GTX|MX ?\d|GT ?\d|QUADRO (?!RTX)|TITAN (?!RTX))", re.IGNORECASE)
        checked = 0
        for gpu in self.doc["gpus"]:
            if pattern.search(gpu["description"]):
                checked += 1
                self.assertEqual({"kind": "unsupported", "reason": "PRE_RTX"}, gpu["expect"], gpu["id"])
        self.assertGreaterEqual(checked, 5)
        for scenario in self.doc["scenarios"]:
            names = " ".join(a["description"] for a in scenario["adapters"] if a["vendorId"].upper() == "0X10DE")
            if pattern.search(names) and "RTX" not in names.upper():
                self.assertEqual("unsupported", scenario["expect"]["kind"], scenario["id"])

    def test_mobile_and_cross_adapter_step_down(self):
        order = ["ULTRA", "HIGH", "BALANCED", "LIGHT"]
        self.assertEqual("HIGH", self.gpus["rtx-4090-laptop"]["tier"])
        self.assertTrue(self.gpus["rtx-4090-laptop"]["mobile"])
        self.assertEqual("BALANCED", self.gpus["rtx-3080-max-q"]["tier"])
        self.assertEqual("LIGHT", self.gpus["rtx-3060-laptop"]["tier"], "LIGHT saturates")
        self.assertEqual("HIGH", self.scenarios["cross-adapter-no-outputs"]["expect"]["tier"])
        self.assertEqual("HIGH", self.scenarios["cross-adapter-display-elsewhere"]["expect"]["tier"])
        both = self.scenarios["mobile-and-cross-adapter"]["expect"]
        self.assertEqual("BALANCED", both["tier"])
        self.assertEqual({"CROSS_ADAPTER", "MOBILE_STEP_DOWN"}, set(both["notes"]))
        self.assertEqual(order[2], both["tier"])

    def test_unknown_vram_and_probe_fallbacks(self):
        self.assertEqual("HIGH", self.gpus["rtx-5080-unknown-vram"]["tier"])
        self.assertIn("VRAM_UNKNOWN", self.gpus["rtx-5080-unknown-vram"]["notes"])
        self.assertEqual({"kind": "unsupported", "reason": "VRAM_LOW"}, self.gpus["rtx-5080-vram-low"])
        unique = self.scenarios["displayconfig-failed-unique"]["expect"]
        self.assertEqual("ULTRA", unique["tier"])
        self.assertIn("PHYSICAL_ASSUMED_UNIQUE", unique["notes"])
        self.assertEqual({"kind": "unsupported", "reason": "PHYSICAL_UNVERIFIED"},
                         self.scenarios["displayconfig-failed-twin-present"]["expect"])


class LadderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = source.load_vectors()

    def test_tier_is_monotone_in_vram_for_every_class_and_capped_by_the_class(self):
        for ladder in self.doc["vramLadder"]:
            rows = sorted(ladder["rows"], key=lambda row: row["vramMiB"])
            ranks = [TIER_RANK[row["expect"]] for row in rows]
            self.assertEqual(ranks, sorted(ranks), ladder["skuClass"])
            self.assertLessEqual(max(ranks), TIER_RANK[CLASS_CAP[ladder["skuClass"]]], ladder["skuClass"])
            self.assertEqual(-1, ranks[0], "the smallest memory is below the floor")

    def test_a_better_class_never_gets_a_lower_tier_at_the_same_memory(self):
        by_class = {ladder["skuClass"]: {row["vramMiB"]: TIER_RANK[row["expect"]] for row in ladder["rows"]}
                    for ladder in self.doc["vramLadder"]}
        self.assertEqual(set(CLASS_ORDER), set(by_class))
        for better, lower in zip(CLASS_ORDER, CLASS_ORDER[1:]):
            for vram, rank in by_class[lower].items():
                self.assertGreaterEqual(by_class[better][vram], rank, "%s versus %s at %d" % (better, lower, vram))

    def test_the_documented_thresholds_and_slack_boundaries_are_present(self):
        s_rows = {row["vramMiB"]: row["expect"] for row in next(l for l in self.doc["vramLadder"] if l["skuClass"] == "S")["rows"]}
        for below, at in ((3394, 3395), (5334, 5335), (7274, 7275), (11154, 11155)):
            self.assertLess(TIER_RANK[s_rows[below]], TIER_RANK[s_rows[at]], "boundary %d" % at)

    def test_unknown_model_is_capped_at_balanced_and_monotone(self):
        rows = sorted(self.doc["unknownModelLadder"], key=lambda row: row["vramMiB"])
        ranks = [TIER_RANK[row["expect"]] for row in rows]
        self.assertEqual(ranks, sorted(ranks))
        self.assertEqual(TIER_RANK["BALANCED"], max(ranks))


class PureCoreSourceTest(unittest.TestCase):
    """The S1 core stays pure and keeps the presentation and wording gates untouched."""

    @classmethod
    def setUpClass(cls):
        cls.files = {path: source.read(path) for path in source.S1_CORE_FILES}

    def test_files_exist_with_the_right_package(self):
        for path, text in self.files.items():
            self.assertTrue(text.startswith("package com.bilipai.desktop.nvidia\n"), path.name)
            self.assertEqual(source.KN, path.parent)

    def test_no_io_clock_or_native_markers(self):
        for path, text in self.files.items():
            code, _ = source.lex_kotlin(text)
            for marker in IO_MARKERS:
                self.assertNotIn(marker, code, "%s uses %s" % (path.name, marker))

    def test_no_gate_literals_or_forbidden_apis_anywhere_even_in_comments(self):
        for path, text in self.files.items():
            for literal in GATE_LITERALS + FORBIDDEN_APIS:
                self.assertNotIn(literal, text, "%s mentions %s" % (path.name, literal))

    def test_no_forbidden_words_in_string_literals(self):
        for path, text in self.files.items():
            _, literals = source.lex_kotlin(text)
            for literal in literals:
                for word in FORBIDDEN_WORDS:
                    self.assertNotIn(word, literal, path.name)

    def test_no_comment_opens_a_nested_block_comment_by_accident(self):
        for path, text in self.files.items():
            source.lex_kotlin(text)
            self.assertEqual(text.count("/*"), text.count("*/"), path.name)

    def test_every_string_template_uses_braces(self):
        for path, text in self.files.items():
            _, literals = source.lex_kotlin(text)
            for literal in literals:
                self.assertNotRegex(literal, r"\$[A-Za-z_]", "%s: %s" % (path.name, literal))

    def test_top_level_names_are_unique_within_the_package(self):
        seen = {}
        for path, text in self.files.items():
            for modifiers, kind, name in TOP_LEVEL.findall(text):
                seen.setdefault(name, []).append((path.name, kind, "private" in modifiers.split()))
        for name, where in seen.items():
            if len(where) == 1:
                continue
            self.assertTrue(all(kind == "fun" for _, kind, _ in where) and name in OVERLOADED,
                            "%s is declared more than once: %s" % (name, where))

    def test_no_other_main_file_declares_the_nvidia_package(self):
        for other in source.K.rglob("*.kt"):
            if other.parent == source.KN:
                continue
            self.assertNotIn("package com.bilipai.desktop.nvidia", other.read_text(encoding="utf-8"), str(other))

    def test_test_helpers_do_not_redeclare_main_names(self):
        main_names = set()
        for text in self.files.values():
            main_names |= {name for _, _, name in TOP_LEVEL.findall(text)}
        for path in sorted((source.ST / "kotlin/com/bilipai/desktop/nvidia").glob("*.kt")):
            for _, _, name in TOP_LEVEL.findall(source.read(path)):
                self.assertNotIn(name, main_names, "%s redeclares %s" % (path.name, name))


if __name__ == "__main__":
    unittest.main()
