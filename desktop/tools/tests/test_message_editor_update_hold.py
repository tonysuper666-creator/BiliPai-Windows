"""The real sole Message producer, exact complete-body inverse and guarded pane seam."""
import hashlib
import importlib.util
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
REPO = Path(os.environ.get("BILIPAI_SOURCE_REPO", str(TOOLS.parents[1])))
SPEC = importlib.util.spec_from_file_location("message_hold_contract", TOOLS / "extract-upstream-message-pages.py")
producer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(producer)
PRE_HOLD_BODY_SHA = "0866e19c4c166239bf53f6a45774a2fd9afb080f666fc4f0296d01291e4b7281"
MESSAGE_PATH = "com/android/purebilibili/feature/message/MessageCenterScreen.kt"

class MessageEditorUpdateHoldContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="message-hold-contract-")
        cls.out = Path(cls.temp.name)
        cls.original_adapter = producer.message_editor_hold
        producer.message_editor_hold = lambda body, output: body
        try:
            producer.generate(REPO, cls.out / "before")
        finally:
            producer.message_editor_hold = cls.original_adapter
        producer.generate(REPO, cls.out / "after")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_original_body_inverse_and_counted_selection(self):
        before = (self.out / "before" / MESSAGE_PATH).read_text(encoding="utf8").encode("utf8")
        self.assertEqual(hashlib.sha256(before).hexdigest(), PRE_HOLD_BODY_SHA)
        after = (self.out / "after" / MESSAGE_PATH).read_text(encoding="utf8")
        proof = json.loads((self.out / "after/message-editor-hold-source-inventory.json").read_text(encoding="utf8"))
        self.assertTrue(proof["fullInverse"])
        self.assertFalse(proof["initialDraftOrMessageBusinessChanged"])
        self.assertEqual(len(proof["edits"]), 4)
        inverse = after
        for edit in reversed(proof["edits"]):
            self.assertEqual(inverse.count(edit["after"]), edit["count"])
            inverse = inverse.replace(edit["after"], edit["before"])
        self.assertEqual(inverse.encode("utf8"), before)
        self.assertEqual(hashlib.sha256(after.encode("utf8")).hexdigest(), proof["afterSha256LF"])
        self.assertIn("pageOwner.selectPaneChat(talkerId, sessionType) {", after)
        self.assertIn("activeTalkerId != 0L && retainedPaneChat", after)
        self.assertIn("compactPageOwner.keepPaneChat(0L, 0)", after)

    def test_only_actual_message_center_changes_other_original_outputs_stay_identical(self):
        before = {f.relative_to(self.out / "before").as_posix(): f.read_bytes() for f in (self.out / "before").rglob("*") if f.is_file()}
        after = {f.relative_to(self.out / "after").as_posix(): f.read_bytes() for f in (self.out / "after").rglob("*") if f.is_file()}
        self.assertEqual(set(after) - set(before), {"message-editor-hold-source-inventory.json"})
        self.assertEqual(set(before) - set(after), set())
        self.assertEqual([name for name in before if before[name] != after[name]], [MESSAGE_PATH])
        self.assertIn("rememberSaveable(talkerId, sessionType)", after["com/android/purebilibili/feature/message/ChatScreen.kt"].decode("utf8"))
        self.assertIn('owner.launchMutation("chat-send")', after["com/android/purebilibili/feature/message/ChatViewModel.kt"].decode("utf8"))

    def test_missing_or_ambiguous_original_anchor_fails_closed(self):
        original = (self.out / "before" / MESSAGE_PATH).read_text(encoding="utf8")
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            for edit in producer.MESSAGE_EDITOR_HOLD_EDITS:
                with self.subTest(anchor=edit["name"]):
                    with self.assertRaises(AssertionError):
                        producer.message_editor_hold(original.replace(edit["before"], "missing-anchor", 1), target)
            with self.assertRaises(AssertionError):
                producer.message_editor_hold(original + original, target)

    def test_actual_source_pins_are_the_existing_canonical_sources(self):
        source = "app/src/main/java/com/android/purebilibili/feature/message/MessageCenterScreen.kt"
        rows = producer.inventory(REPO)
        row = next(row for row in rows if row["path"] == source)
        self.assertEqual(hashlib.sha256(producer.read(REPO, source).encode("utf8")).hexdigest(), row["sha256"])
        self.assertNotIn("message-editor-hold-source-inventory", row["path"])
        proof = json.loads((self.out / "after/message-editor-hold-source-inventory.json").read_text(encoding="utf8"))
        self.assertEqual(proof["beforeSha256LF"], PRE_HOLD_BODY_SHA)

if __name__ == "__main__":
    unittest.main()
