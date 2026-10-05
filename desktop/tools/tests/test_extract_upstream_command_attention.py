"""Complete existing engagement extraction plus captured command-card permission."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("attention_full_units", TOOLS / "extract-upstream-video-detail-full-units.py")
units = importlib.util.module_from_spec(spec)
spec.loader.exec_module(units)
VM = "com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt"
PROTOCOL = "com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt"


class CommandAttentionExtractionTest(unittest.TestCase):
    def test_complete_owned_vm_adaptation_round_trips_and_existing_members_are_unchanged(self):
        with tempfile.TemporaryDirectory(prefix="command-attention-source-") as temp:
            out, before = Path(temp) / "after", Path(temp) / "before"
            units.generate(REPO, out)
            actual = units.wide(out / VM).read_text(encoding="utf-8")
            proof = json.loads((out / "engagement-presentation-proof.json").read_text(encoding="utf-8"))
            self.assertEqual("79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40", proof["upstreamCommit"])
            self.assertEqual("62c0eae9b9447cfd615a5972ab8f58be906ca2a0cee184ccd57cf8b997ebbb1f", proof["originalSourceSha256LF"])
            self.assertTrue(proof["fullOwnedBodyInverse"])
            inverse = actual
            # The same sole producer now applies confirmed feedback after the
            # command permission stage. Undo each complete stage, checking its
            # actual input/output digest, before checking the older recipe.
            for name in ("v029-video-feedback-origin-proof.json", "v029-video-feedback-state-proof.json"):
                later = json.loads((out / name).read_text(encoding="utf-8"))
                self.assertEqual(later["afterSha256LF"], hashlib.sha256(inverse.encode()).hexdigest())
                for edit in reversed(later["edits"]):
                    self.assertEqual(1, inverse.count(edit["after"]), edit["label"])
                    inverse = inverse.replace(edit["after"], edit["before"], 1)
                self.assertEqual(later["beforeSha256LF"], hashlib.sha256(inverse.encode()).hexdigest())
            for edit in reversed(proof["adaptations"]):
                self.assertEqual(1, inverse.count(edit["after"]), edit["label"])
                inverse = inverse.replace(edit["after"], edit["before"], 1)
            with patch.object(units, "engagement_presentation_delta", side_effect=lambda body: body), \
                    patch("v029_video_feedback.apply_video_feedback", side_effect=lambda path, body, parser: (body, None)), \
                    patch("v029_video_feedback_origin.apply_feedback_origin", side_effect=lambda path, body: (body, None)):
                units.generate(REPO, before)
            self.assertEqual(units.wide(before / VM).read_text(encoding="utf-8"), inverse)
            self.assertEqual((before / "video-operations-members.fragment").read_bytes(), (out / "video-operations-members.fragment").read_bytes())
            verifier_spec = importlib.util.spec_from_file_location("attention_members", TOOLS / "verify-upstream-video-detail-full-units.py")
            verifier = importlib.util.module_from_spec(verifier_spec); verifier_spec.loader.exec_module(verifier)
            verifier.verify(REPO, out, Path(temp) / "members.json")

    def test_inverse_guard_rejects_missing_original_launch_or_event_body(self):
        with tempfile.TemporaryDirectory(prefix="command-attention-tamper-") as temp:
            out = Path(temp)
            captured = []
            original = units.engagement_presentation_delta
            with patch.object(units, "engagement_presentation_delta", side_effect=lambda body: captured.append(body) or original(body)):
                units.generate(REPO, out)
            body = captured[0]
            with self.assertRaises(AssertionError):
                units.engagement_presentation_delta(body.replace("fun toggleFollow(mid:", "fun removedToggleFollow(mid:", 1))
            with self.assertRaises(AssertionError):
                units.engagement_presentation_delta(body.replace("_events.send(_uiState.value.subject?.generation to event)", "removedTransport(event)", 1))

    def test_protocol_guard_preserves_original_sequential_body_and_all_source_pins(self):
        with tempfile.TemporaryDirectory(prefix="command-attention-protocol-") as temp:
            out = Path(temp)
            units.generate(REPO, out)
            protocol = units.wide(out / PROTOCOL).read_text(encoding="utf-8")
            record = json.loads((out / "source-bindings.json").read_text(encoding="utf-8"))
            self.assertEqual(units.SOURCE_PINS.keys(), {row["path"] for row in record["sourceIdentities"]})
            for row in record["sourceIdentities"]:
                self.assertEqual(units.SOURCE_PINS[row["path"]]["gitBlob"], row["gitBlob"])
                self.assertEqual(units.SOURCE_PINS[row["path"]]["sha256LF"], row["sha256LF"])
            self.assertIn("DesktopOriginalVideoEngagementPresentation.assertCurrent()\n        ownerCheckpoint()", protocol)
            names = ["val likeResult = likeVideo(aid, true)", "val coinResult = coinVideo(aid, 2, true)", "val favoriteResult = favoriteVideo(aid, true)"]
            self.assertEqual(sorted(protocol.index(name) for name in names), [protocol.index(name) for name in names])
            self.assertIn("DesktopOriginalVideoEngagementPresentation.commitCurrent { assertOwned(); confirmFollow", protocol)
            self.assertEqual(hashlib.sha256(protocol.encode()).hexdigest(), next(row["sha256LF"] for row in record["outputs"] if row["path"] == PROTOCOL))


if __name__ == "__main__":
    unittest.main()
