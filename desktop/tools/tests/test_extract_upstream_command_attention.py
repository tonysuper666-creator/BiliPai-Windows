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
            # The Windows like write guard (v046) is the last stage on this view model: peel it first.
            guard = json.loads((out / "v046-like-write-guard-proofs/VideoEngagementViewModel.kt.json").read_text(encoding="utf-8"))
            self.assertEqual(guard["outputSHA256"], hashlib.sha256(actual.encode()).hexdigest())
            at = guard["offset"]
            self.assertEqual(guard["after"], actual[at:at + len(guard["after"])])
            actual = actual[:at] + guard["before"] + actual[at + len(guard["after"]):]
            self.assertEqual(guard["inputSHA256"], hashlib.sha256(actual.encode()).hexdigest())
            proof = json.loads((out / "engagement-presentation-proof.json").read_text(encoding="utf-8"))
            import v037_player_interaction as interaction
            self.assertEqual(interaction.COMMIT, proof["upstreamCommit"])
            self.assertEqual(hashlib.sha256(interaction.fixed_sources()["VideoEngagementViewModel.kt"].encode()).hexdigest(), proof["originalSourceSha256LF"])
            self.assertTrue(proof["fullOwnedBodyInverse"])
            inverse = actual
            owner = json.loads((out / "v042-owner-follow-projection-proof.json").read_text(encoding="utf-8"))
            self.assertEqual(owner["outputSHA256"], hashlib.sha256(inverse.encode()).hexdigest())
            self.assertTrue(owner["wholeOriginalMethodInverseRecovered"])
            for edit in reversed(owner["edits"]):
                start = edit["offset"]
                self.assertEqual(inverse[start:start + len(edit["after"])], edit["after"])
                inverse = inverse[:start] + edit["before"] + inverse[start + len(edit["after"]):]
            self.assertEqual(owner["inputSHA256"], hashlib.sha256(inverse.encode()).hexdigest())
            # Current original source already contains repost limits. Undo the
            # actual Windows stages in reverse order, preserving the complete VM.
            for name in ("v037-video-coin-request-boundary-proof.json", "v029-video-feedback-lifetime-proof.json", "v029-video-feedback-origin-proof.json", "v029-video-feedback-state-proof.json"):
                later = json.loads((out / name).read_text(encoding="utf-8"))
                self.assertEqual(later["afterSha256LF"], hashlib.sha256(inverse.encode()).hexdigest())
                if "indexedEdits" in later:
                    for edit in reversed(later["indexedEdits"]):
                        start = edit["afterOffset"]
                        self.assertEqual(inverse[start:start + len(edit["after"])], edit["after"])
                        inverse = inverse[:start] + edit["before"] + inverse[start + len(edit["after"]):]
                else:
                    for edit in reversed(later["edits"]):
                        self.assertEqual(1, inverse.count(edit["after"]), edit["label"])
                        inverse = inverse.replace(edit["after"], edit["before"], 1)
                self.assertEqual(later["beforeSha256LF"], hashlib.sha256(inverse.encode()).hexdigest())
            for edit in reversed(proof["adaptations"]):
                self.assertEqual(1, inverse.count(edit["after"]), edit["label"])
                inverse = inverse.replace(edit["after"], edit["before"], 1)
            with patch.object(units, "engagement_presentation_delta", side_effect=lambda body: body), \
                    patch("v037_video_feedback_adapter.apply_latest_feedback", side_effect=lambda path, body, parser: (body, None)), \
                    patch("v037_feedback_origin.apply_feedback_origin", side_effect=lambda path, body: (body, None)), \
                    patch("v029_video_feedback_host.apply_feedback_lifetime", side_effect=lambda path, body: (body, None)), \
                    patch("v037_video_coin_request.apply", side_effect=lambda path, body, parser: (body, None)), \
                    patch("v042_owner_follow.apply", side_effect=lambda path, body, globals: (body, None)), \
                    patch("v046_like_write_guard.apply_and_record", side_effect=lambda repo, relative, text, output: text):
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
            import v037_gesture_feedback as gesture
            import v037_player_interaction as interaction
            import v037_player_section_policy as section_policy
            selected = {path: interaction.selected_source_identity(path) for path in interaction.SELECTED_SOURCE_FILES}
            selected.update({path: gesture.original(REPO, path)[1] for path in gesture.SOURCE_PATHS})
            text, identity = section_policy.fixed_original(REPO, section_policy.RELATIVE_PATHS[0])
            selected[identity["originalPath"]] = identity
            for row in record["sourceIdentities"]:
                expected = selected.get(row["path"], units.SOURCE_PINS[row["path"]])
                self.assertEqual(expected["gitBlob"], row["gitBlob"])
                self.assertEqual(expected["sha256LF"], row["sha256LF"])
            self.assertIn("DesktopOriginalVideoEngagementPresentation.assertCurrent()\n        ownerCheckpoint()", protocol)
            names = ["val likeResult = likeVideo(aid, true)", "val coinResult = coinVideo(aid, coinCount, true)", "val favoriteResult = favoriteVideo(aid, true, showSuccessFeedback = false).rethrowCancellation()"]
            self.assertEqual(sorted(protocol.index(name) for name in names), [protocol.index(name) for name in names])
            self.assertIn("DesktopOriginalVideoEngagementPresentation.commitCurrent { assertOwned(); confirmFollow", protocol)
            self.assertEqual(hashlib.sha256(protocol.encode()).hexdigest(), next(row["sha256LF"] for row in record["outputs"] if row["path"] == PROTOCOL))


if __name__ == "__main__":
    unittest.main()
