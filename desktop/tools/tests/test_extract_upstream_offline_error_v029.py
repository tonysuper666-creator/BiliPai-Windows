"""Exact fixed offline policy and two-layer Screen recipe. No native execution."""
from pathlib import Path
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("offline_error_v029", TOOLS / "extract-upstream-offline-player.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class FixedOfflineErrorPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = tool.offline_error_sources(REPO)
        cls.path = tool.BASE + "feature/download/OfflineVideoPlayerScreen.kt"
        cls.original = tool.read(tool._desktop_canonical_source(REPO, cls.path))
        fixed = subprocess.check_output(["git", "show", tool.COMMIT + ":" + cls.path], cwd=REPO).decode("utf-8").replace("\r\n", "\n")
        assert fixed == cls.original
        cls.previous = cls.original
        for delta in tool.UI_DELTA:
            i = delta["index"]
            assert cls.previous[i:i + len(delta["before"])] == delta["before"]
            cls.previous = cls.previous[:i] + delta["after"] + cls.previous[i + len(delta["before"]):]

    def test_complete_original_policy_and_test_have_only_one_import_adaptation(self):
        for leaf in ("OfflinePlaybackErrorPolicy.kt", "OfflinePlaybackErrorPolicyTest.kt"):
            original = self.sources[leaf]
            adapted = tool.offline_error_policy(original)
            self.assertEqual(original, adapted.replace(tool.OFFLINE_ERROR_DESKTOP_IMPORT, tool.OFFLINE_ERROR_IMPORT))
            self.assertEqual(1, adapted.count(tool.OFFLINE_ERROR_DESKTOP_IMPORT))
        test_path = REPO / "desktop/src/test/kotlin/com/android/purebilibili/feature/download/OfflinePlaybackErrorPolicyTest.kt"
        self.assertEqual(tool.offline_error_policy(self.sources["OfflinePlaybackErrorPolicyTest.kt"]), tool.read(test_path))

    def test_full_failure_recipe_inverse_restores_complete_v025_screen(self):
        output, deltas, fragments = tool.offline_error_ui(self.previous, self.sources["OfflineVideoPlayerScreen.kt"])
        restored = output
        for delta in reversed(deltas):
            i = delta["index"]
            self.assertEqual(delta["after"], restored[i:i + len(delta["after"])])
            restored = restored[:i] + delta["before"] + restored[i + len(delta["after"]):]
        self.assertEqual(self.previous, restored)
        for delta in reversed(tool.UI_DELTA):
            i = delta["index"]
            self.assertEqual(delta["after"], restored[i:i + len(delta["after"])])
            restored = restored[:i] + delta["before"] + restored[i + len(delta["after"]):]
        self.assertEqual(self.original, restored)
        for fragment in fragments:
            self.assertEqual(fragment["sha256LF"], tool.sha(fragment["original"]))
            self.assertIn(fragment["original"], self.sources["OfflineVideoPlayerScreen.kt"])

    def test_complete_original_error_ui_and_fail_cursor_are_mounted(self):
        output, _, _ = tool.offline_error_ui(self.previous, self.sources["OfflineVideoPlayerScreen.kt"])
        original = self.sources["OfflineVideoPlayerScreen.kt"]
        start = original.index("        playbackFailure?.let { failure ->")
        end = original.index("    }\n    }\n    // 无二级内容", start)
        self.assertIn(original[start:end], output)
        self.assertIn("forceReload = retryVersion > 0", output)
        self.assertIn("resumePositionMs = restoredPosition", output)
        self.assertIn("activePlayer.playerError != null || activePlayer.playbackState == DesktopOfflinePlaybackState.IDLE", output)
        self.assertIn("player.playerError == null && player.playbackState == DesktopOfflinePlaybackState.READY", output)
        self.assertNotIn("ExoPlayer", output)

    def test_missing_or_duplicate_ui_recipe_input_is_rejected(self):
        _, changes, _ = tool.offline_error_ui(self.previous, self.sources["OfflineVideoPlayerScreen.kt"])
        initial = changes[0]["before"]
        for altered in (self.previous.replace(initial, "", 1), self.previous + initial):
            with self.assertRaises(AssertionError):
                tool.offline_error_ui(altered, self.sources["OfflineVideoPlayerScreen.kt"])
        for altered in (self.sources["OfflinePlaybackErrorPolicy.kt"].replace(tool.OFFLINE_ERROR_IMPORT, ""),
                        self.sources["OfflinePlaybackErrorPolicy.kt"] + tool.OFFLINE_ERROR_IMPORT):
            with self.assertRaises(AssertionError):
                tool.offline_error_policy(altered)

    def test_unsupported_source_slice_drift_is_rejected_before_emission(self):
        with tempfile.TemporaryDirectory(prefix="offline-source-negative-") as directory:
            repo = Path(directory)
            folder = repo / "desktop/upstream-slices/v029-offline-error"
            shutil.copytree(REPO / "desktop/upstream-slices/v029-offline-error", folder)
            path = folder / "OfflinePlaybackErrorPolicy.kt"
            path.write_text(tool.read(path) + "// drift\n", encoding="utf-8")
            with self.assertRaises(AssertionError):
                tool.offline_error_sources(repo)

    def test_wrong_commit_and_duplicate_manifest_record_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="offline-manifest-negative-") as directory:
            repo = Path(directory)
            folder = repo / "desktop/upstream-slices/v029-offline-error"
            shutil.copytree(REPO / "desktop/upstream-slices/v029-offline-error", folder)
            path = folder / "manifest.json"
            original = json.loads(tool.read(path))
            altered = dict(original, upstreamCommit="0" * 40)
            path.write_text(json.dumps(altered), encoding="utf-8")
            with self.assertRaises(AssertionError):
                tool.offline_error_sources(repo)
            altered = dict(original, sources=original["sources"] + [original["sources"][0]])
            path.write_text(json.dumps(altered), encoding="utf-8")
            with self.assertRaises(AssertionError):
                tool.offline_error_sources(repo)

    def test_media3_constant_evidence_drift_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="offline-constants-negative-") as directory:
            repo = Path(directory)
            folder = repo / "desktop/upstream-slices/v029-offline-error"
            shutil.copytree(REPO / "desktop/upstream-slices/v029-offline-error", folder)
            path = folder / "manifest.json"
            metadata = json.loads(tool.read(path))
            metadata["media3"]["constants"]["ERROR_CODE_IO_NO_PERMISSION"] = 0
            path.write_text(json.dumps(metadata), encoding="utf-8")
            with self.assertRaises(AssertionError):
                tool.offline_error_sources(repo)


if __name__ == "__main__":
    unittest.main()
