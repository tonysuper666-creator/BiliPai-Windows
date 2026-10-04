"""Actual pinned whole-source replay; no compiled tree, native account or HTTP."""
from pathlib import Path
import hashlib
import importlib.util
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    return tool


owner = load("interaction_owner", "extract-upstream-video-full-owner.py")
share = load("interaction_share", "extract-upstream-video-share-consent.py")
controls = load("interaction_controls", "extract-upstream-video-player-full-controls.py")
VM = "com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"
SEND = "app/src/main/java/com/android/purebilibili/feature/video/ui/components/DanmakuSendDialog.kt"


class OriginalVideoInteractionExtractionTest(unittest.TestCase):
    def test_composer_lifecycle_is_only_five_exact_edits_over_whole_vm(self):
        original_path = owner._desktop_canonical_source(REPO, owner.RECIPES[0]["originalPath"])
        original_raw = owner.wide(original_path).read_bytes()
        with tempfile.TemporaryDirectory(prefix="original-interaction-owner-") as temp:
            output = Path(temp)
            owner.generate(REPO, output / "actual")
            with patch.object(owner, "composer_source_lifetime_delta", side_effect=lambda path, body: body):
                owner.generate(REPO, output / "before")
            actual = (output / "actual" / VM).read_text(encoding="utf-8")
            before = (output / "before" / VM).read_text(encoding="utf-8")
            edits = []
            self.assertEqual(actual, owner.composer_source_lifetime_delta(VM, before, edits))
            self.assertEqual(5, len(edits))
            inverse = actual
            for old, new in reversed(edits):
                self.assertEqual(1, inverse.count(new))
                inverse = inverse.replace(new, old, 1)
            self.assertEqual(before, inverse)
            # The login/disabled policy, full draft reducers and actual send request
            # remain byte-for-byte part of the original owner, outside the 5 edits.
            self.assertEqual(original_raw, owner.wide(original_path).read_bytes())
            self.assertEqual(owner.RECIPES[0]["originalSha256LF"],
                hashlib.sha256(original_raw.replace(b"\r\n", b"\n")).hexdigest())
            with self.assertRaises(AssertionError):
                owner.composer_source_lifetime_delta(VM, before.replace(
                    "    private var wasPlayingBeforeDanmakuComposer = false\n", "", 1))

    def test_whole_composer_container_inverse_restores_pinned_original(self):
        original = controls.wide(controls._desktop_canonical_source(REPO, SEND)).read_text(
            encoding="utf-8").replace("\r\n", "\n")
        self.assertEqual(controls.SOURCE_PINS[SEND]["sha256LF"],
            hashlib.sha256(original.encode()).hexdigest())
        with tempfile.TemporaryDirectory(prefix="original-interaction-controls-") as temp:
            controls.generate(REPO, Path(temp))
            actual = (Path(temp) / "com/android/purebilibili/feature/video/ui/components/DanmakuSendDialog.kt").read_text(
                encoding="utf-8")
        # Restore the existing configuration leaf and the single new Dialog import.
        inverse = actual.replace("import com.bilipai.desktop.ui.DesktopWindowsVideoInteractionDialog as Dialog\n",
            "import androidx.compose.ui.window.Dialog\n", 1)
        inverse = inverse.replace("import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration",
            "import androidx.compose.ui.platform.LocalConfiguration", 1)
        original_without_android_flag = re.sub(r"(?m)^\s*decorFitsSystemWindows = false,?\s*\n", "", original)
        self.assertEqual(original_without_android_flag, inverse)
        matching = [row for row in controls.ADAPT if row["label"] ==
            "Whole original composer uses opt-in owned Windows native container"]
        self.assertEqual(1, len(matching))

    def test_all_three_share_bodies_restore_whole_previous_adaptation(self):
        with tempfile.TemporaryDirectory(prefix="original-interaction-share-") as temp:
            output = Path(temp)
            share.generate(REPO, output / "actual")
            with patch.object(share, "share_presentation_delta", side_effect=lambda path, body: (body, 0)):
                share.generate(REPO, output / "before")
            for spec in share.SPECS:
                target = spec["target"]
                actual = share.safe(output / "actual" / target).read_text(encoding="utf-8")
                before = share.safe(output / "before" / target).read_text(encoding="utf-8")
                edits = []
                replay, count = share.share_presentation_delta(target, before, edits)
                self.assertEqual(actual, replay)
                inverse = actual
                for old, new, expected in reversed(edits):
                    self.assertEqual(expected, inverse.count(new))
                    inverse = inverse.replace(new, old, expected)
                self.assertEqual(before, inverse)
                self.assertEqual(count, sum(row[2] for row in edits))
                if edits:
                    with self.assertRaises(AssertionError):
                        share.share_presentation_delta(target, before.replace(edits[0][0], "", 1))


if __name__ == "__main__":
    unittest.main()
