from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
spec = importlib.util.spec_from_file_location("hot_danmaku", REPO / "desktop/tools/extract-upstream-hot-danmaku.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class HotDanmakuExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bilipai-hot-danmaku-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_every_complete_generated_file_reconstructs_its_actual_pinned_original(self):
        output = self.root / "generated"
        tests = self.root / "tests"
        produced = tool.generate(REPO, output, tests)
        self.assertEqual(len(produced), 5)
        proof = json.loads((output / "source-proof.json").read_text(encoding="utf-8"))
        originals = tool.checked_inputs(REPO)
        self.assertEqual({row["source"] for row in proof["files"]}, set(tool.PINS))
        for row in proof["files"]:
            root = tests if row["testSource"] else output
            raw = tool.wide(root / row["generatedPath"]).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row["generatedSha256Bytes"])
            source = raw.decode("utf-8")
            self.assertTrue(source.startswith(row["generatedPrefix"]))
            adapted = source[len(row["generatedPrefix"]):]
            self.assertEqual(tool.restore(adapted, row["changes"]), originals[row["source"]])
            self.assertEqual(row["originalRawSha256"], tool.PINS[row["source"]])
            self.assertTrue(all(change["occurrenceCount"] == 1 for change in row["changes"]))
        self.assertFalse(proof["compiled"])
        self.assertFalse(proof["actualMainRendered"])
        self.assertFalse(proof["overallCanonicalBaselineAdvanced"])

    def test_policy_and_original_six_policy_tests_have_no_adapter_changes(self):
        originals = tool.checked_inputs(REPO)
        for path in [tool.POLICY, tool.TEST]:
            source, changes = tool.port(path, originals[path])
            self.assertEqual(source, originals[path])
            self.assertEqual(changes, [])
        self.assertEqual(originals[tool.TEST].count("@Test"), 6)

    def test_platform_ports_preserve_whole_ui_and_remove_android_factory_dependencies(self):
        originals = tool.checked_inputs(REPO)
        adapted = {path: tool.port(path, original)[0] for path, original in originals.items()}
        for path in [tool.BAR, tool.COUNT, tool.CONFIRMATION]:
            self.assertNotIn("import android.", adapted[path])
            self.assertNotIn("import androidx.media3.", adapted[path])
            self.assertNotIn("import androidx.lifecycle.", adapted[path])
        self.assertIn("desktopDetailRenderEffectsSupported()", adapted[tool.COUNT])
        self.assertIn("edgeTreatment = TileMode.Decal", adapted[tool.COUNT])
        self.assertNotIn("Build.VERSION", adapted[tool.COUNT])
        self.assertNotIn("createBlurEffect", adapted[tool.COUNT])
        self.assertIn("context.expandedMode", adapted[tool.BAR])

    def copy_archive(self):
        repo = self.root / "repository"
        shutil.copytree(tool.wide(REPO / tool.ARCHIVE), tool.wide(repo / tool.ARCHIVE))
        return repo

    def test_raw_original_tamper_is_rejected_before_any_generated_output(self):
        repo = self.copy_archive()
        path = tool.wide(repo / tool.ARCHIVE / Path(tool.BAR).name)
        path.write_bytes(path.read_bytes() + b"\n// changed\n")
        output = self.root / "rejected"
        with self.assertRaisesRegex(ValueError, "Pinned original bytes changed"):
            tool.generate(repo, output)
        self.assertFalse(output.exists())

    def test_unknown_manifest_commit_and_source_set_are_rejected(self):
        repo = self.copy_archive()
        path = tool.wide(repo / tool.ARCHIVE / "manifest.json")
        original = json.loads(path.read_bytes())
        changed = dict(original, fixedUpstreamCommit="unknown")
        path.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unknown hot-danmaku"):
            tool.checked_inputs(repo)
        changed = dict(original, files=original["files"][:-1])
        path.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "exact fixed source set"):
            tool.checked_inputs(repo)

    def test_missing_or_repeated_original_adapter_clause_fails_closed(self):
        original = tool.checked_inputs(REPO)[tool.BAR]
        clause = "SettingsManager.getHotDanmakuExpandedMode(context)"
        for changed in [original.replace(clause, "newSettingsOwner(context)"), original + "\n" + clause]:
            with self.assertRaisesRegex(ValueError, "Expected exactly one"):
                tool.port(tool.BAR, changed)

    def test_one_bad_transform_does_not_publish_other_valid_files(self):
        originals = tool.checked_inputs(REPO)
        originals[tool.COUNT] = originals[tool.COUNT].replace("Build.VERSION_CODES.S", "Build.VERSION_CODES.T")
        output = self.root / "rejected"
        with patch.object(tool, "checked_inputs", return_value=originals):
            with self.assertRaisesRegex(ValueError, "Expected exactly one"):
                tool.generate(REPO, output)
        self.assertFalse(output.exists())

    def test_inventory_production_and_test_paths_are_pinned_without_modifying_v025_authority(self):
        rows = tool.inventory(REPO)
        self.assertEqual({row["path"] for row in rows}, set(tool.PINS))
        self.assertTrue(all(row["upstreamCommit"] == tool.COMMIT for row in rows))
        self.assertEqual([row["path"] for row in rows if row["mode"].endswith("-test")], [tool.TEST])
        self.assertTrue(all(row["sha256Bytes"] == tool.PINS[row["path"]] for row in rows))


if __name__ == "__main__":
    unittest.main()
