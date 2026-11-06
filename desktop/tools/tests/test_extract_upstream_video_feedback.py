"""Fresh whole-source feedback producer contracts; no Kotlin/UI/API/profile.

The pre-feedback hash identifies the complete existing canonical owned VM,
including its reviewed desktop adaptation. It is not a replacement raw pin.

Since 75522724c the producer's two feedback stages are the v037 latest-feedback
adapter and the v037 feedback origin, and the latest original already carries the
repost contract (v037-repost-contract-source-proof.json, no v031 delta). The
coin request boundary (75522724c), the v042 owner follow (daff05d71), the v046
triple submit guard (6e5839bb4) and the v046 like write guard (3d205010a) are
stacked above the feedback lifetime; each is peeled with its own recorded proof.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v029_video_feedback as feedback
import v029_video_feedback_origin as legacy_origin
import v037_feedback_origin as origin
import v037_video_feedback_adapter as adapter


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


producer = load("video_feedback_actual_producer", TOOLS / "extract-upstream-video-detail-full-units.py")
# Re-pinned at 75522724c: the latest original VM (repost contract included) is the pre-feedback input.
PRE_FEEDBACK_OWNED_VM_SHA256_LF = "b3214587cdb01e8e8a16c85ed2bdd80b6abdf2cd96c994957a4f40ecd7cd8573"


def read(path: Path) -> str:
    return producer.wide(path).read_bytes().decode("utf-8").replace("\r\n", "\n")


def undo(text: str, proof: dict) -> str:
    for edit in reversed(proof["edits"]):
        if text.count(edit["after"]) != 1:
            raise AssertionError("Counted complete-source inverse mismatch: " + edit["label"])
        text = text.replace(edit["after"], edit["before"], 1)
    return text


def undo_indexed(text: str, proof: dict) -> str:
    if feedback.sha(text) != proof["afterSha256LF"]:
        raise AssertionError("Indexed complete-source inverse starts from a different source")
    for edit in reversed(proof["indexedEdits"]):
        start = edit["afterOffset"]
        if text[start:start + len(edit["after"])] != edit["after"]:
            raise AssertionError("Indexed complete-source inverse mismatch")
        text = text[:start] + edit["before"] + text[start + len(edit["after"]):]
    if feedback.sha(text) != proof["beforeSha256LF"]:
        raise AssertionError("Indexed complete-source inverse did not restore its input")
    return text


def peel_engagement_guards(output: Path, text: str):
    """Undo the like guard, triple guard and owner follow; return the repost-contract VM."""
    guard = json.loads(read(output / "v046-like-write-guard-proofs/VideoEngagementViewModel.kt.json"))
    if feedback.sha(text) != guard["outputSHA256"]:
        raise AssertionError("Like write guard proof is not the final view model")
    at = guard["offset"]
    if text[at:at + len(guard["after"])] != guard["after"]:
        raise AssertionError("Like write guard inverse mismatch")
    text = text[:at] + guard["before"] + text[at + len(guard["after"]):]
    triple = json.loads(read(output / "v046-triple-submission-proofs/VideoEngagementViewModel.kt.json"))
    if feedback.sha(text) != guard["inputSHA256"] or feedback.sha(text) != triple["outputSHA256"]:
        raise AssertionError("Triple submit guard is not directly below the like write guard")
    for edit in reversed(triple["edits"]):
        if text.count(edit["after"]) != 1:
            raise AssertionError("Triple submit guard inverse mismatch: " + edit["label"])
        text = text.replace(edit["after"], edit["before"], 1)
    owner = json.loads(read(output / "v042-owner-follow-projection-proof.json"))
    if owner['outputSHA256'] != guard['outputSHA256']:
        raise AssertionError('Final owner follow receipt does not cover the physical VM')
    final_owner = owner
    owner = owner['ownerFollowStage']
    if owner['inputSHA256'] != final_owner['inputSHA256']:
        raise AssertionError('Owner follow stage input changed in the final receipt')
    if feedback.sha(text) != triple["inputSHA256"] or feedback.sha(text) != owner["outputSHA256"]:
        raise AssertionError("Owner follow is not directly below the triple submit guard")
    for edit in reversed(owner["edits"]):
        start = edit["offset"]
        if text[start:start + len(edit["after"])] != edit["after"]:
            raise AssertionError("Owner follow inverse mismatch")
        text = text[:start] + edit["before"] + text[start + len(edit["after"]):]
    if feedback.sha(text) != owner["inputSHA256"]:
        raise AssertionError("Owner follow inverse did not restore its input")
    return text, guard


class VideoFeedbackExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-video-feedback-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.output = Path(cls.temp.name) / "generated"
        cls.actual_feedback_inputs = []
        actual_adapter = adapter.apply_latest_feedback
        def capture(path, text, parser):
            if path == feedback.OUTPUT_VM:
                cls.actual_feedback_inputs.append(text)
            return actual_adapter(path, text, parser)
        with mock.patch.object(adapter, 'apply_latest_feedback', side_effect=capture):
            producer.generate(REPO, cls.output, True)
        cls.output_vm = read(cls.output / feedback.OUTPUT_VM)
        cls.repost_contract_vm, cls.guard_proof = peel_engagement_guards(cls.output, cls.output_vm)
        cls.repost_proof = json.loads(read(cls.output / "v037-repost-contract-source-proof.json"))
        cls.coin_request_proof = json.loads(read(cls.output / "v037-video-coin-request-boundary-proof.json"))
        cls.final_vm = undo_indexed(cls.repost_contract_vm, cls.coin_request_proof)
        cls.lifetime_proof = json.loads(read(cls.output / 'v029-video-feedback-lifetime-proof.json'))
        cls.vm = undo(cls.final_vm, cls.lifetime_proof)
        cls.state_proof = json.loads(read(cls.output / "v029-video-feedback-state-proof.json"))
        cls.origin_proof = json.loads(read(cls.output / "v029-video-feedback-origin-proof.json"))
        cls.animation_proof = json.loads(read(cls.output / "v029-video-feedback-celebration-proof.json"))

    def test_actual_unique_producer_consumption_has_final_physical_output_hashes(self):
        source = read(TOOLS / "extract-upstream-video-detail-full-units.py")
        self.assertEqual(1, source.count("t,feedbackProof=apply_latest_feedback(path,t,parser)"))
        self.assertEqual(1, source.count("from v037_feedback_origin import apply_feedback_origin"))
        self.assertEqual(1, source.count("t,feedbackOriginProof=apply_feedback_origin(path,t)"))
        self.assertLess(source.index("t,feedbackProof=apply_latest_feedback(path,t,parser)"),
                        source.index("t,feedbackOriginProof=apply_feedback_origin(path,t)"))
        binding = json.loads(read(self.output / "source-bindings.json"))
        outputs = binding["outputs"]
        self.assertEqual(len(outputs), len({row["path"] for row in outputs}))
        for row in outputs:
            if row["generated"]:
                self.assertEqual(row["sha256LF"], feedback.sha(read(self.output / row["path"])), row["path"])
        vm = [row for row in outputs if row["path"] == feedback.OUTPUT_VM]
        animation = [row for row in outputs if row["path"] == feedback.OUTPUT_ANIMATIONS]
        self.assertEqual(1, len(vm)); self.assertEqual(1, len(animation))
        self.assertEqual(vm[0]["sha256LF"], self.guard_proof["outputSHA256"])
        self.assertEqual(self.repost_proof["generatedSha256LF"], feedback.sha(self.repost_contract_vm))
        self.assertTrue(self.repost_proof["wholeLatestOriginalRepostContractAlreadySelected"])
        self.assertEqual(self.repost_proof["generatedSha256LF"], self.coin_request_proof["afterSha256LF"])
        self.assertEqual(self.coin_request_proof["beforeSha256LF"], self.lifetime_proof["afterSha256LF"])
        self.assertEqual(self.origin_proof["afterSha256LF"], self.lifetime_proof["beforeSha256LF"])
        self.assertEqual(feedback.sha(self.vm), self.origin_proof["afterSha256LF"])
        self.assertEqual(animation[0]["fixedCommit"], feedback.COMMIT)
        self.assertEqual({'VideoInteractionUseCase.kt.repost-coin-proof.json',
                          'TripleActionVisualStatePolicy.kt.repost-coin-proof.json'},
                         {path.name for path in self.output.glob('*.repost-coin-proof.json')})
        self.assertFalse((self.output / 'v046-follow-confirmation-proofs/VideoEngagementViewModel.kt.json').exists())

    def test_complete_vm_two_stage_inverse_and_reapplication_are_exact(self):
        intermediate = undo(self.vm, self.origin_proof)
        self.assertEqual(feedback.sha(intermediate), self.origin_proof["beforeSha256LF"])
        self.assertEqual(feedback.sha(intermediate), self.state_proof["afterSha256LF"])
        original_owned = undo_indexed(intermediate, self.state_proof)
        self.assertEqual(PRE_FEEDBACK_OWNED_VM_SHA256_LF, feedback.sha(original_owned))
        self.assertEqual(feedback.sha(original_owned), self.state_proof["beforeSha256LF"])
        self.assertEqual([original_owned], self.actual_feedback_inputs)
        self.assertEqual(PRE_FEEDBACK_OWNED_VM_SHA256_LF, feedback.sha(self.actual_feedback_inputs[0]))
        first, first_proof = adapter.apply_latest_feedback(feedback.OUTPUT_VM, original_owned, producer.parser)
        final, final_proof = origin.apply_feedback_origin(feedback.OUTPUT_VM, first)
        self.assertEqual(intermediate, first); self.assertEqual(self.vm, final)
        self.assertEqual(self.state_proof, first_proof); self.assertEqual(self.origin_proof, final_proof)

    def test_whole_original_animations_and_all_fixed_raw_identities_are_exact(self):
        manifest, originals = feedback.sources()
        self.assertEqual(8, len(manifest["sources"]))
        self.assertEqual("a4b77f894d0a2dd26c0b9fc144b8adb88ac05480", manifest["upstreamCommit"])
        for row in manifest["sources"]:
            raw = originals[row["path"]].encode("utf-8")
            self.assertEqual(row["bytes"], len(raw))
            self.assertEqual(row["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(row["gitBlob"], hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest())
        animation = read(self.output / feedback.OUTPUT_ANIMATIONS)
        self.assertEqual(originals[feedback.ANIMATIONS], undo(animation, self.animation_proof))
        self.assertEqual(feedback.sha(animation), self.animation_proof["afterSha256LF"])

    def clone_fixed_inputs(self, root: Path):
        clone_tools = root / "desktop/tools"
        producer.wide(clone_tools).mkdir(parents=True)
        producer.wide(clone_tools / "v029_video_feedback.py").write_bytes(producer.wide(TOOLS / "v029_video_feedback.py").read_bytes())
        archive = root / "desktop/upstream-slices/v029-video-feedback"
        source_archive = TOOLS.parent / "upstream-slices/v029-video-feedback"
        manifest, _ = feedback.sources()
        for relative in ["manifest.json"] + [row["path"] for row in manifest["sources"]]:
            target = producer.wide(archive / relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(producer.wide(source_archive / relative).read_bytes())
        return archive, load("isolated_feedback_tamper", producer.wide(clone_tools / "v029_video_feedback.py"))

    def test_raw_and_manifest_tamper_cannot_repin_or_accept_modified_bytes(self):
        for mode in ("raw", "manifest", "raw-and-repinned-manifest"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix="feedback-tamper-") as temp:
                archive, cloned = self.clone_fixed_inputs(Path(temp))
                manifest_path = producer.wide(archive / "manifest.json")
                manifest = json.loads(manifest_path.read_bytes())
                row = next(row for row in manifest["sources"] if row["path"] == feedback.VM)
                source = producer.wide(archive / feedback.VM)
                if mode != "manifest":
                    changed = source.read_bytes() + b"\n// changed raw input\n"
                    source.write_bytes(changed)
                    if mode == "raw-and-repinned-manifest":
                        row.update(bytes=len(changed), sha256=hashlib.sha256(changed).hexdigest(),
                                   gitBlob=hashlib.sha1(b"blob " + str(len(changed)).encode() + b"\0" + changed).hexdigest())
                else:
                    manifest["upstreamCommit"] = "unknown"
                if mode != "raw":
                    manifest_path.write_bytes((json.dumps(manifest) + "\n").encode())
                with self.assertRaises(AssertionError):
                    cloned.sources()
                self.assertFalse((Path(temp) / "generated").exists())

    def test_real_confirmation_and_original_instance_callbacks_keep_source_metadata(self):
        for name, kind in (("toggleLikeWithDesktopPresentation", "LIKE"),
                           ("toggleDislikeWithDesktopPresentation", "DISLIKE"),
                           ("doCoinWithDesktopPresentation", "COIN"), ("doTripleAction", "TRIPLE")):
            function = feedback.function(self.vm.replace("    internal fun " + name, "    fun " + name), name, producer.parser)
            self.assertIn(".onSuccess", function)
            self.assertIn("DesktopWindowsVideoFeedbackKind." + kind, function)
            self.assertTrue("environment.commit" in function or "updateOwned" in function)
        triple = feedback.function(self.vm, "doTripleAction", producer.parser)
        self.assertIn("desktopTripleFeedbackOrigin = if (result.allSuccess)", triple)
        self.assertIn("DesktopWindowsVideoFeedbackKind.TRIPLE, celebrationId, current.subject", triple)
        self.assertIn("withTimeoutOrNull(5_000L)", triple)
        self.assertNotIn("val celebrationId = _uiState.value", triple)
        for name, field in (("dismissLikeBurst", "desktopLikeFeedbackOrigin"), ("dismissMaidAction", "desktopMaidFeedbackOrigin"),
                            ("completeTripleCelebration", "desktopTripleFeedbackOrigin"), ("cancelTripleCelebration", "desktopTripleFeedbackOrigin")):
            function = feedback.function(self.vm, name, producer.parser)
            self.assertIn("updateOwned", function); self.assertIn(field + " = null", function)
            self.assertIn("expectedId" if "dismiss" in name else "celebrationId", function)
        self.assertIn("tripleCelebrationFinished = false", feedback.function(self.vm, "cancelTripleCelebration", producer.parser))
        self.assertNotIn("VideoShareFeedbackEvents.events.collect", self.vm)

    def test_projection_consumes_exact_accepted_reference_and_both_existing_permissions(self):
        main = TOOLS.parent / "src/main/kotlin/com/bilipai/desktop/ui"
        model = read(main / "DesktopWindowsVideoFeedbackOrigin.kt")
        binding = read(main / "DesktopWindowsVideoEngagementSection.kt")
        presentation = read(main / "DesktopOriginalVideoEngagementBindings.kt")
        command = read(main / "DesktopWindowsCommandAttentionBinding.kt")
        self.assertIn("source.accepted === accepted", model)
        self.assertIn("return presentation.admit", model)
        self.assertIn("expected.admitCurrent(sourceOwner, subject)", binding)
        self.assertIn("state.value.desktopFeedbackOrigin(expected.kind) === expected", binding)
        self.assertIn("constructor(owns: () -> Boolean, admission: (() -> Unit) -> Boolean)", presentation)
        self.assertIn("DesktopOriginalVideoEngagementPresentation(sourceLease, subject, ::isOwned, withAdmission,", command)
        self.assertIn("::feedbackLifetimeOwned, feedbackAdmission", command)

    def test_foreign_paths_noop_and_wrong_or_duplicate_stage_anchors_fail_closed(self):
        self.assertEqual(feedback.apply_video_feedback('foreign.kt', 'unchanged', producer.parser), ('unchanged', None))
        self.assertEqual(origin.apply_feedback_origin("foreign.kt", "unchanged"), ("unchanged", None))
        self.assertEqual(adapter.apply_latest_feedback("foreign.kt", "unchanged", producer.parser), ("unchanged", None))
        first = undo(self.vm, self.origin_proof)
        pre = undo_indexed(first, self.state_proof)
        with self.assertRaises(AssertionError):
            feedback.apply_video_feedback(feedback.OUTPUT_VM, pre, producer.parser)
        with self.assertRaises(AssertionError):
            legacy_origin.apply_feedback_origin(feedback.OUTPUT_VM, first)
        anchor = "    val tripleCelebrationFinished: Boolean = false\n)"
        for broken in (first.replace(anchor, "    val foreign: Boolean = false\n)", 1), first + "\n" + anchor):
            with self.assertRaises(AssertionError):
                origin.apply_feedback_origin(feedback.OUTPUT_VM, broken)
        with self.assertRaises(AssertionError):
            origin.apply_feedback_origin(feedback.OUTPUT_VM, self.vm)


if __name__ == "__main__":
    unittest.main()
