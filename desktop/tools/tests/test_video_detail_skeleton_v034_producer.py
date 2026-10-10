"""Full original-source contracts and real sole-producer projections; no app/UI."""
from pathlib import Path
import hashlib
import importlib.util
import os
import sys
import tempfile
import unittest

REPO = Path(os.environ.get("BILIPAI_VIDEO_SKELETON_TEST_REPO", Path(__file__).resolve().parents[3]))
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


tool = load("review_video_skeleton_bgm_producer", TOOLS / "extract-upstream-bgm-detail.py")
identity = load("review_video_skeleton_original_identity", TOOLS / "extract-upstream-dynamic-reply-protocol.py")
INPUT = "desktop/third-party/video-detail-skeleton-v034/SkeletonComponents.kt"
ORIGINAL = tool.BASE + "feature/video/ui/components/SkeletonComponents.kt"
OUTPUT = "com/android/purebilibili/feature/video/ui/components/SkeletonComponents.kt"
COMMIT = "b2091b1de636e499df80af7e700796b4f52b7f92"
RAW_BYTES = 13566
RAW_SHA = "b634aede022821f1aa8e08eaa02fa0983240968b6de816ec504d49c8027a0849"
RAW_BLOB = "54e29ba7c3eac09c3d6d9de64f14c1e31dd87c73"
CURRENT_PROJECTION_SHA = "a268f34a153d20de2ef88049c51f6bee9ad799aed33ca024fb5d739c4462cfe5"
NEW_PROJECTION_SHA = "8ec642bdddf00c103540bffd77dcf88f996086f61191d8f3143029109d4a0aab"
DISCOVERY_SOURCE = tool.BASE + "feature/video/ui/section/VideoInfoSection.kt"
DISCOVERY_SOURCES = [DISCOVERY_SOURCE] + [tool.BASE + path + ".kt" for path in [
    "core/ui/LocalNavigationBackHandler", "feature/video/ui/components/VideoCardSkeleton",
    "feature/video/ui/components/SkeletonComponents", "feature/video/ui/components/RelatedVideoItem",
]]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def blob(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()


class VideoDetailSkeletonProducerReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = (REPO / INPUT).read_bytes()
        cls.original, cls.identities = identity.load_pinned_sources(REPO, DISCOVERY_SOURCES)

    def fixture(self, root, raw=None):
        path = root / INPUT
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(self.raw if raw is None else raw)
        return root

    def projection(self, original):
        outputs, proofs = {}, []
        tool.generate_bgm_discovery(original, identity,
            lambda path, text: outputs.__setitem__(path, text), proofs)
        return outputs[OUTPUT], next(row for row in proofs if row["source"] == ORIGINAL)

    def test_actual_fixed_beta2_original_bytes_and_replaced_identity(self):
        self.assertEqual(RAW_BYTES, len(self.raw))
        self.assertEqual(RAW_SHA, sha(self.raw))
        self.assertEqual(RAW_BLOB, blob(self.raw))
        before_sources = dict(self.original)
        before_identities = [dict(row) for row in self.identities]
        advanced, identities = tool.advance_video_detail_skeleton_source(REPO, self.original, self.identities)
        self.assertEqual(before_sources, self.original)
        self.assertEqual(before_identities, self.identities)
        self.assertEqual(self.raw.decode("utf8"), advanced[ORIGINAL])
        self.assertEqual({key: value for key, value in before_sources.items() if key != ORIGINAL},
                         {key: value for key, value in advanced.items() if key != ORIGINAL})
        self.assertEqual(len(self.identities), len(identities))
        selected = [row for row in identities if row["requestedPath"] == ORIGINAL]
        self.assertEqual(1, len(selected))
        pin = selected[0]
        self.assertEqual(INPUT, pin["path"])
        self.assertEqual(COMMIT, pin["pinnedCommit"])
        self.assertEqual("v0.3.4-beta.2", pin["pinnedTag"])
        self.assertEqual(RAW_BLOB, pin["pinnedGitBlob"])
        self.assertEqual(RAW_BLOB, pin["currentGitBlob"])
        self.assertEqual(RAW_SHA, pin["sha256LfUtf8"])
        self.assertTrue(pin["matchesPinnedCommit"])

    def test_real_complete_projection_only_adds_original_two_panes(self):
        old_projection, _ = self.projection(self.original)
        self.assertEqual(CURRENT_PROJECTION_SHA, sha(old_projection.encode("utf8")))
        advanced, _ = tool.advance_video_detail_skeleton_source(REPO, self.original, self.identities)
        projection, proof = self.projection(advanced)
        self.assertEqual(NEW_PROJECTION_SHA, sha(projection.encode("utf8")))
        self.assertEqual(13496, len(projection.encode("utf8")))
        self.assertEqual(INPUT, proof["sourceInput"])
        self.assertEqual(COMMIT, proof["pinnedCommit"])
        self.assertEqual(RAW_BLOB, proof["pinnedGitBlob"])
        self.assertEqual(RAW_SHA, proof["originalSha256"])
        self.assertEqual(NEW_PROJECTION_SHA, proof["generatedSha256"])
        source = self.raw.decode("utf8")
        start = source.index("/** Loading content follows the pane bounds when a foldable changes posture. */")
        end = source.index("@Composable\nprivate fun VideoDetailTabBarSkeleton()", start)
        original_panes = source[start:end]
        self.assertEqual(1, projection.count(original_panes))
        self.assertEqual(1, source.count("import androidx.compose.foundation.lazy.LazyColumn\n"))
        self.assertEqual(1, projection.count("import androidx.compose.foundation.lazy.LazyColumn\n"))
        without_panes = projection.replace(original_panes, "", 1).replace(
            "import androidx.compose.foundation.lazy.LazyColumn\n", "", 1)
        self.assertEqual(old_projection, without_panes)
        without_original_panes = source.replace(original_panes, "", 1).replace(
            "import androidx.compose.foundation.lazy.LazyColumn\n", "", 1)
        self.assertEqual(self.original[ORIGINAL], without_original_panes)

    def test_raw_byte_drift_and_crlf_are_rejected_before_projection(self):
        for changed in [self.raw + b" ", self.raw.replace(b"\n", b"\r\n")]:
            with self.subTest(sha=sha(changed)), tempfile.TemporaryDirectory(prefix="bp-sk-raw-") as name:
                root = self.fixture(Path(name), changed)
                before_sources = dict(self.original)
                before_identities = [dict(row) for row in self.identities]
                with self.assertRaisesRegex(ValueError, "source drift"):
                    tool.advance_video_detail_skeleton_source(root, self.original, self.identities)
                self.assertEqual(before_sources, self.original)
                self.assertEqual(before_identities, self.identities)

    def test_missing_or_duplicate_original_identity_fails_closed(self):
        missing = [dict(row) for row in self.identities if row["requestedPath"] != ORIGINAL]
        duplicate = [dict(row) for row in self.identities] + [
            dict(next(row for row in self.identities if row["requestedPath"] == ORIGINAL))]
        for identities in [missing, duplicate]:
            with self.subTest(count=len(identities)):
                before_sources = dict(self.original)
                before_identities = [dict(row) for row in identities]
                with self.assertRaisesRegex(ValueError, "unique producer ownership"):
                    tool.advance_video_detail_skeleton_source(REPO, self.original, identities)
                self.assertEqual(before_sources, self.original)
                self.assertEqual(before_identities, identities)


if __name__ == "__main__":
    unittest.main()
