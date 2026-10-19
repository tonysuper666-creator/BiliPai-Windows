"""Early-detail desktop boundary: anchors, generated text, inverse, double apply.

No Gradle output directory. generate() writes a temp directory.
"""
from pathlib import Path
import contextlib
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))


def load_core():
    spec = importlib.util.spec_from_file_location(
        "early_detail_state_core", TOOLS / "extract-upstream-video-state-core.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def seed_slice():
    from v037_player_interaction import fixed_sources
    original = fixed_sources()["VideoPlaybackUseCase.kt"]
    start = original.index("internal fun resolvePlaybackBootstrapCid(")
    end = original.index("\n\n", start)
    return original[start:end] + "\n\n" + original[original.index("class VideoPlaybackUseCase("):]


class EarlyVideoDetailBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.core = load_core()
        cls.source = seed_slice()

    def test_anchors_exist_exactly_in_seed_derived_text(self):
        _adapted, proof = self.core.apply_early_video_detail_boundary(self.source)
        counts = {row["label"]: row["count"] for row in proof["replacements"]}
        self.assertEqual({
            "Ordinary playurl failure must not cancel the original detail waiter": 1,
            "Consume boxed playurl failure only after the detail callback": 1,
            "Retired owner cannot publish original detail callback": 2,
        }, counts)
        for row in proof["replacements"]:
            self.assertEqual(row["count"], self.source.count(row["before"]))
            self.assertEqual(0, row["after"].count(row["before"]))

    def test_inverse_restores_preimage_and_second_apply_fails(self):
        before_sha = hashlib.sha256(self.source.encode()).hexdigest()
        adapted, proof = self.core.apply_early_video_detail_boundary(self.source)
        self.assertEqual(before_sha, proof["beforeSha256"])
        self.assertEqual(hashlib.sha256(adapted.encode()).hexdigest(), proof["afterSha256"])
        self.assertTrue(proof["inverseExact"])
        self.assertEqual(1, adapted.count("async<Result<PlayUrlData?>>"))
        self.assertEqual(1, adapted.count("playUrlDeferred.await().getOrThrow()\n"))
        self.assertEqual(0, adapted.count("                                    playUrlDeferred.await()\n"))
        self.assertEqual(2, adapted.count(
            "                                environment.assertOwned()\n"
            "                                onVideoInfo(info)\n"))
        self.assertIn(
            "An ordinary URL failure must wait for the original\n"
            "                                // metadata result/callback before caller classification.",
            adapted)
        inverse = adapted
        for row in reversed(proof["replacements"]):
            self.assertEqual(row["count"], inverse.count(row["after"]))
            inverse = inverse.replace(row["after"], row["before"])
        self.assertEqual(self.source, inverse)
        self.assertEqual(before_sha, hashlib.sha256(inverse.encode()).hexdigest())
        offset_inverse = adapted
        for edit in reversed(proof["edits"]):
            index = edit["offset"]
            self.assertEqual(edit["after"], offset_inverse[index:index + len(edit["after"])])
            offset_inverse = (offset_inverse[:index] + edit["before"]
                              + offset_inverse[index + len(edit["after"]):])
        self.assertEqual(self.source, offset_inverse)
        with self.assertRaises(AssertionError):
            self.core.apply_early_video_detail_boundary(adapted)

    def test_generated_usecase_contains_the_boundary(self):
        with tempfile.TemporaryDirectory(prefix="bp-early-detail-") as root:
            with contextlib.redirect_stdout(io.StringIO()):
                self.core.generate(REPO, Path(root), True)
            text = (Path(root) / "com/android/purebilibili/feature/video/usecase"
                    / "DesktopOriginalVideoPlaybackUseCase.kt").read_text(encoding="utf-8")
            self.assertEqual(1, text.count("val playUrlDeferred = async<Result<PlayUrlData?>>"))
            self.assertNotIn("val playUrlDeferred = async {\n", text)
            self.assertEqual(1, text.count("playUrlDeferred.await().getOrThrow()"))
            self.assertEqual(2, text.count(
                "environment.assertOwned()\n                                onVideoInfo(info)\n"))
            audit = json.loads((Path(root) / "original-video-state-core-selection.json")
                               .read_text(encoding="utf-8"))
            labels = [row["label"] for row in audit["audits"]["usecase-adaptations.json"]]
            self.assertIn("Retired owner cannot publish original detail callback", labels)
            self.assertIn("Ordinary playurl failure must not cancel the original detail waiter", labels)
            selection = audit["audits"]["DesktopOriginalVideoPlaybackUseCase.kt.v037-selection.json"]
            self.assertTrue(selection["legacyV034SemanticDeltasNotReapplied"])


if __name__ == "__main__":
    unittest.main()
