"""Fixed raw v029 sources, complete adaptation inverse, and one generated owner."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v029_command_vote as source


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


votes = load("v029_votes_test", "extract-stable-video-votes.py")
section = load("v029_section_test", "extract-upstream-video-player-section-full.py")
TEST_PINS = {
    "app/src/test/java/com/android/purebilibili/feature/video/danmaku/CommandDanmakuPolicyTest.kt":
        (18579, "13005cce5d2277464608d14500fb00978ebe217e490052ea9bc01ba117836aae", "f0f3bf5e0a1a3ebe006c0a43b1d2d981f1d921a5"),
    "app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlayStateTest.kt":
        (7638, "affcb2ce0dec65e48bbb75969ab2902f8d84c21157c810a975a02a1b0a8bfb72", "258ad34b2286f7402088ac77910b0a5ad4213044"),
    "app/src/test/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlayPolicyTest.kt":
        (4754, "7b84159743a530ec7295c08d93902aecde1c0c476051f877fff414f763895dce", "221759b368860b20c83e001c36dec8b661c0d023"),
    "app/src/test/java/com/android/purebilibili/data/repository/DanmakuRepositoryPolicyTest.kt":
        (5296, "f9b22207531205964d66aade778f0aced6ddba218a52292ec0a0475fe53a4af0", "44100f1206ed559d8c07bd25d2fcd3b2cdb467cf"),
}


class V029CommandVoteExtractionTest(unittest.TestCase):
    def test_all_fixed_sources_validate_raw_identity_and_complete_overlay_inverse(self):
        for path in source.PINS:
            original = source.read(REPO, path)
            self.assertTrue(original)
        original = source.read(REPO, source.OVERLAY)
        edits = []
        adapted = source.adapt_overlay(original, edits)
        self.assertEqual(original, source.inverse(adapted, edits))
        self.assertNotIn("votePanelVoteId", adapted)  # removed by the actual original v029 component
        self.assertEqual(1, adapted.count(".desktopCommandHitRegion(item.id)"))
        self.assertEqual(1, adapted.count("platform.releasePending(pending) { isLoading = false }"))
        with self.assertRaises(AssertionError):
            source.adapt_overlay(original.replace("player.currentPosition", "unexpectedPosition", 1), [])

    def test_raw_tampering_and_manifest_commit_change_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="v029-vote-raw-") as temp:
            repo = Path(temp)
            copied = repo / "desktop/upstream-slices/v029-command-vote"
            shutil.copytree(REPO / "desktop/upstream-slices/v029-command-vote", copied)
            file = copied / source.STATE
            original = file.read_bytes()
            file.write_bytes(original + b"\n")
            with self.assertRaises(AssertionError):
                source.read(repo, source.STATE)
            file.write_bytes(original)
            manifest_path = copied / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["upstreamCommit"] = "0" * 40
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(AssertionError):
                source.read(repo, source.STATE)

    def test_original_submission_all_branches_round_trip_without_fabricated_acceptance(self):
        original, adapted = source.submission_body(source.read(REPO, source.SECTION), edits := [])
        self.assertEqual(original, source.inverse(adapted, edits))
        self.assertEqual(2, adapted.count("CoroutineStart.UNDISPATCHED"))
        self.assertEqual(4, adapted.count("platform.releasePending(pending) { commandState.endSubmission(item.id) }"))
        self.assertEqual(1, adapted.count("platform.capturePending(commandState to item.id)"))
        self.assertEqual(1, adapted.count("platform.getGradeDanmakuSummary("))
        self.assertEqual(1, adapted.count("optionIndexes = listOf(optionIndex)"))
        self.assertIn("catch (e: kotlinx.coroutines.CancellationException)", adapted)

    def test_actual_producer_emits_whole_policy_state_and_inverse_proof(self):
        with tempfile.TemporaryDirectory(prefix="v029-vote-producer-") as temp:
            output = Path(temp)
            votes.generate(REPO, output)
            proof = json.loads((output / "v029-command-vote-proof.json").read_text(encoding="utf-8"))
            self.assertEqual(source.COMMIT, proof["upstreamCommit"])
            self.assertTrue(proof["overlayFullInverse"])
            self.assertTrue(proof["submissionFullInverse"])
            self.assertTrue(proof["gradeFullInverse"])
            for path in (source.POLICY, source.STATE):
                actual = (output / path.split("/java/", 1)[1]).read_text(encoding="utf-8")
                self.assertEqual(source.read(REPO, path), "\n".join(actual.split("\n")[2:]))
            protocol = (output / "com/android/purebilibili/data/repository/DesktopVideoGradeProtocol.kt").read_text(encoding="utf-8")
            self.assertIn("api.getDanmakuView(oid = cid, pid = aid)", protocol)
            self.assertIn("resolveGradeDanmakuSummary(metadata.commandDms, gradeId)", protocol)
            self.assertEqual(votes.OPS_FRAGMENT, (output / "platform/DesktopVideoGradeMembers.fragment").read_text(encoding="utf-8"))

    def test_existing_full_section_uses_actual_required_port_and_inverse(self):
        with tempfile.TemporaryDirectory(prefix="v029-vote-section-") as temp:
            actual_dir, before_dir = Path(temp) / "actual", Path(temp) / "before"
            section.generate(REPO, actual_dir)
            with patch.object(source, "section_delta", side_effect=lambda text, edits: text):
                section.generate(REPO, before_dir)
            path = "com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt"
            actual = section.wide(actual_dir / path).read_text(encoding="utf-8")
            before = section.wide(before_dir / path).read_text(encoding="utf-8")
            replay = source.section_delta(before, edits := [])
            self.assertEqual(actual, replay)
            self.assertEqual(before, source.inverse(actual, edits))
            self.assertIn("remember(platform, candidateCommandPort?.sourceLease) { candidateCommandPort }", actual)
            self.assertEqual(1, actual.count("submitOriginalDesktopCommandVote("))

    def test_original_tests_are_pinned_and_adapt_only_declared_test_selection(self):
        root = REPO / "desktop/upstream-slices/v029-command-vote-tests"
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(source.COMMIT, manifest["upstreamCommit"])
        self.assertEqual(set(TEST_PINS), {r["path"] for r in manifest["files"]})
        for row in manifest["files"]:
            raw = section.wide(root / row["path"]).read_bytes()
            size, sha, blob = TEST_PINS[row["path"]]
            self.assertEqual((size, sha, blob, source.COMMIT), (row["bytes"], row["sha256"], row["gitBlob"], row["commit"]))
            self.assertEqual(size, len(raw)); self.assertEqual(sha, hashlib.sha256(raw).hexdigest())
            self.assertEqual(blob, hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest())
            adapted = next(r for r in manifest["testAdaptations"] if r["originalPath"] == row["path"])
            actual = (REPO / adapted["target"]).read_text(encoding="utf-8").split("\n", 1)[1]
            original = raw.decode("utf-8").replace("\r\n", "\n")
            if adapted.get("wholeOriginalPreserved"):
                self.assertEqual(original, actual)
            else:
                selected = original[original.index("    @Test\n    fun resolveGradeDanmakuSummary_matchesActualGradeId"):original.rfind("\n}")]
                self.assertEqual(adapted["selectedBodySha256LF"], hashlib.sha256(selected.encode()).hexdigest())
                self.assertIn(selected, actual)
            self.assertEqual(adapted["testCount"], actual.count("@Test"))

    def test_original_registry_pins_stay_v025_with_unique_extracted_owners(self):
        manifest = json.loads((REPO / "desktop/upstream-sources.json").read_text(encoding="utf-8"))
        self.assertEqual("79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40", manifest["upstreamCommit"])
        for path in (source.POLICY, source.STATE):
            rows = [r for r in manifest["sources"] if r["path"] == path]
            self.assertEqual(1, len(rows)); self.assertEqual("extracted", rows[0]["mode"])
            from v025_source_paths import canonical_source
            original = section.wide(canonical_source(REPO, path)).read_bytes().replace(b"\r\n", b"\n")
            self.assertEqual(rows[0]["sha256"], hashlib.sha256(original).hexdigest())


if __name__ == "__main__":
    unittest.main()
