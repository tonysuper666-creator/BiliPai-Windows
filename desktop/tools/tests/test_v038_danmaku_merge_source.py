from pathlib import Path
import importlib.util
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("v038_merge", REPO / "desktop/tools/v038_danmaku_merge.py")
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


def undo(body, proof):
    for edit in reversed(proof["edits"]):
        if body.count(edit["after"]) != 1:
            raise AssertionError("Ambiguous inverse")
        body = body.replace(edit["after"], edit["before"], 1)
    return body


class V038DanmakuMergeSourceTest(unittest.TestCase):
    def test_whole_original_merger_keeps_fixed_batch_start_likes_and_original_content(self):
        raw, _ = source.fixed_sources(REPO)
        body, proof = source.merger_source(REPO)
        self.assertEqual(raw["DanmakuMerger.kt"], undo(body, proof))
        self.assertIn("item.showAtTime - firstItem.showAtTime <= intervalMs", body)
        self.assertIn("mergedText.likeCount = batch.sumOf { it.likeCount }", body)
        self.assertIn("mergedText.mergedOriginalText = base.mergedOriginalText ?: base.text", body)
        self.assertNotIn("item.showAtTime - lastItem.showAtTime", body)
        self.assertFalse(proof["mounted"])

    def test_complete_neutral_schema_retains_original_copy_fields_and_unique_emission_owner(self):
        raw, _ = source.fixed_sources(REPO)
        body, proof = source.neutral_item_source(REPO)
        self.assertEqual(raw["DanmakuModels.kt"].split("\ndata class DanmakuWindow(", 1)[0], undo(body, proof))
        self.assertIn("target.mergedOriginalText = mergedOriginalText", body)
        self.assertIn("target.likeCount = likeCount", body)
        self.assertEqual("extract-upstream-danmaku-list-menu.py", proof["emissionOwner"])

    def test_original_policy_cases_remain_byte_equal_except_test_class_name(self):
        raw, _ = source.fixed_sources(REPO)
        body, proof = source.original_policy_test(REPO)
        self.assertEqual(raw["DanmakuMergerPolicyTest.kt"], undo(body, proof))
        self.assertEqual(raw["DanmakuMergerPolicyTest.kt"].count("@Test"), body.count("@Test"))
        self.assertIn("merge_anchorsTheInclusiveWindowToTheFirstItem", body)
        self.assertIn("merge_sumsLikesWithinEachWindowWithoutMutatingTheSource", body)

    def test_changed_raw_source_or_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="bp-v038-merge-source-") as directory:
            repo = Path(directory)
            shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
            file = repo / source.ARCHIVE / "DanmakuMerger.kt"
            raw = file.read_bytes(); file.write_bytes(raw + b"\n// changed\n")
            with self.assertRaisesRegex(ValueError, "source bytes changed"):
                source.merger_source(repo)
            file.write_bytes(raw)
            manifest = repo / source.ARCHIVE / "manifest.json"
            manifest.write_bytes(manifest.read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "manifest changed"):
                source.fixed_sources(repo)


if __name__ == "__main__":
    unittest.main()
