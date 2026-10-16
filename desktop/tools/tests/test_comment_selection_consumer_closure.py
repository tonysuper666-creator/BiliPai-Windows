"""Exercise production comment source producers and their retained typed call graph."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v029_comment_composer as composer

PREFIX = "com/android/purebilibili/feature/video/"
OWNER = PREFIX + "viewmodel/VideoPlaybackViewModel.kt"
HOLDER = PREFIX + "screen/VideoDetailInputOverlayAdapter.kt"
PORTRAIT = PREFIX + "ui/pager/PortraitVideoPager.kt"
OVERLAY = PREFIX + "screen/DesktopOriginalVideoCommentInputOverlay.kt"
DIALOG = PREFIX + "ui/components/CommentInputDialog.kt"


def load_producer(name):
    spec = importlib.util.spec_from_file_location("selection_closure_" + name.replace("-", "_"), TOOLS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CommentSelectionConsumerClosureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix="bilipai-comment-selection-closure-")
        cls.output = Path(cls.scratch.name)
        cls.originals = {}
        real_delta = composer.legacy_comment_selection_delta

        def capture(path, body, output):
            result = real_delta(path, body, output)
            if result != body:
                if path in cls.originals:
                    raise AssertionError("Duplicate production comment selection writer: " + path)
                cls.originals[path] = body
            return result

        with patch.object(composer, "legacy_comment_selection_delta", side_effect=capture):
            for name in [
                "extract-upstream-video-detail-holder",
                "extract-upstream-video-comment-ui",
                "extract-upstream-video-fullscreen-pager",
                "extract-upstream-video-full-owner",
                "extract-upstream-bgm-detail",
            ]:
                load_producer(name).generate(REPO, cls.output)

    @classmethod
    def tearDownClass(cls):
        cls.scratch.cleanup()

    def source(self, path):
        return (self.output / path).read_text(encoding="utf-8")

    def test_all_original_comment_dialogs_use_five_argument_callbacks_and_restore_selection(self):
        dialog = self.source(DIALOG)
        signature = re.search(r"onDraftChange:\s*\(([^)]+)\)\s*->\s*Unit", dialog)
        self.assertIsNotNone(signature)
        self.assertEqual(["String", "List<String>", "Boolean", "Int", "Int"],
                         [item.strip() for item in signature.group(1).split(",")])
        for path in [HOLDER, OVERLAY]:
            with self.subTest(path=path):
                source = self.source(path)
                callbacks = re.findall(r"val updateDraft:\s*\(([^)]+)\)\s*->\s*Unit", source)
                self.assertIn(["String", "List<String>", "Boolean", "Int", "Int"],
                              [[item.strip() for item in values.split(",")] for values in callbacks])
                self.assertIn("initialSelectionStart = snapshot.draft.selectionStart,", source)
                self.assertIn("initialSelectionEnd = snapshot.draft.selectionEnd,", source)
        portrait = self.source(PORTRAIT)
        self.assertIn("initialSelectionStart = commentDraft.selectionStart,", portrait)
        self.assertIn("initialSelectionEnd = commentDraft.selectionEnd,", portrait)
        self.assertIn("onDraftChange = viewModel::updateCommentDraft,", portrait)

    def test_full_playback_owner_records_directed_selection_in_existing_draft(self):
        source = self.source(OWNER)
        match = re.search(r"fun updateCommentDraft\((.*?)\n    \) \{(.*?)\n    \}", source, re.S)
        self.assertIsNotNone(match)
        self.assertIn("selectionStart: Int = -1", match.group(1))
        self.assertIn("selectionEnd: Int = -1", match.group(1))
        self.assertIn("ensureComposerDraftVideo()", match.group(2))
        self.assertIn("val previous = _composerDrafts.value.comments[key]", match.group(2))
        self.assertIn("resolveCommentDraftSelection(", match.group(2))
        self.assertIn("selectionStart, selectionEnd, previous, text.length", match.group(2))
        self.assertIn("CommentComposerDraft(text, imageUris, syncToDynamic, resolvedSelectionStart, resolvedSelectionEnd)", match.group(2))
        self.assertIn("state.copy(comments = state.comments + (key to draft))", match.group(2))
        self.assertNotIn("sorted", match.group(2))

    def test_portrait_port_preserves_both_selection_offsets_without_changing_ownership_guard(self):
        ui = REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui"
        port = (ui / "DesktopOriginalPortraitPlatform.kt").read_text(encoding="utf-8-sig")
        view = (ui / "DesktopOriginalVideoConsumedViews.kt").read_text(encoding="utf-8-sig")
        for source in [port, view]:
            match = re.search(r"fun updateCommentDraft\(([^)]+)\)", source)
            self.assertIsNotNone(match)
            self.assertEqual(5, len(match.group(1).split(",")))
            self.assertIn("selectionStart: Int", match.group(1))
            self.assertIn("selectionEnd: Int", match.group(1))
        self.assertIn("command { original.updateCommentDraft(text, imageUris, syncToDynamic, selectionStart, selectionEnd) }", view)
        self.assertIn("private inline fun command(action: () -> Unit) { assertOwned(); action() }", view)

    def test_three_generated_consumer_deltas_restore_the_entire_prior_body(self):
        self.assertEqual({OWNER, HOLDER, PORTRAIT}, set(self.originals))
        for path, original in self.originals.items():
            with self.subTest(path=path):
                proof = json.loads((self.output / ("v029-comment-selection-" + Path(path).stem + ".json")).read_text(encoding="utf-8"))
                current = self.source(path)
                self.assertTrue(proof["exactCompleteInverse"])
                self.assertEqual(hashlib.sha256(current.encode()).hexdigest(), proof["afterSha256LF"])
                for edit in reversed(proof["inverseEdits"]):
                    offset = edit["offset"]
                    self.assertEqual(edit["after"], current[offset:offset + len(edit["after"])])
                    current = current[:offset] + edit["before"] + current[offset + len(edit["after"]):]
                self.assertEqual(original, current)
                self.assertEqual(hashlib.sha256(current.encode()).hexdigest(), proof["beforeSha256LF"])

    def test_changed_original_anchor_is_rejected_without_publishing_a_proof(self):
        changed = self.originals[HOLDER].replace("initialText = snapshot.draft.text,", "initialText = unrelated.text,", 1)
        with tempfile.TemporaryDirectory(prefix="bilipai-selection-anchor-") as scratch:
            with self.assertRaisesRegex(ValueError, "Original comment selection consumer mismatch"):
                composer.legacy_comment_selection_delta(HOLDER, changed, Path(scratch))
            self.assertEqual([], list(Path(scratch).rglob("*")))


if __name__ == "__main__":
    unittest.main()

