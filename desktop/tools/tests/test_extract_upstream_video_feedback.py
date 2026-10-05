"""Fresh whole-source feedback producer contracts; no Kotlin/UI/API/profile.

The pre-feedback hash identifies the complete existing canonical owned VM,
including its reviewed desktop adaptation. It is not a replacement raw pin.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v029_video_feedback as feedback
import v029_video_feedback_origin as origin


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


producer = load("video_feedback_actual_producer", TOOLS / "extract-upstream-video-detail-full-units.py")
PRE_FEEDBACK_OWNED_VM_SHA256_LF = "c60acf33697141b6eff513f49162820a21ffcd9b7550aba411ae4f199d3837fe"


def read(path: Path) -> str:
    return producer.wide(path).read_bytes().decode("utf-8").replace("\r\n", "\n")


def undo(text: str, proof: dict) -> str:
    for edit in reversed(proof["edits"]):
        if text.count(edit["after"]) != 1:
            raise AssertionError("Counted complete-source inverse mismatch: " + edit["label"])
        text = text.replace(edit["after"], edit["before"], 1)
    return text


class VideoFeedbackExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-video-feedback-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.output = Path(cls.temp.name) / "generated"
        producer.generate(REPO, cls.output, True)
        cls.vm = read(cls.output / feedback.OUTPUT_VM)
        cls.state_proof = json.loads(read(cls.output / "v029-video-feedback-state-proof.json"))
        cls.origin_proof = json.loads(read(cls.output / "v029-video-feedback-origin-proof.json"))
        cls.animation_proof = json.loads(read(cls.output / "v029-video-feedback-celebration-proof.json"))

    def test_actual_unique_producer_consumption_has_final_physical_output_hashes(self):
        source = read(TOOLS / "extract-upstream-video-detail-full-units.py")
        self.assertEqual(1, source.count("t,feedbackProof=apply_video_feedback(path,t,parser)"))
        self.assertEqual(1, source.count("t,feedbackOriginProof=apply_feedback_origin(path,t)"))
        self.assertLess(source.index("t,feedbackProof=apply_video_feedback(path,t,parser)"),
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
        self.assertEqual(vm[0]["sha256LF"], self.origin_proof["afterSha256LF"])
        self.assertEqual(animation[0]["fixedCommit"], feedback.COMMIT)

    def test_complete_vm_two_stage_inverse_and_reapplication_are_exact(self):
        intermediate = undo(self.vm, self.origin_proof)
        self.assertEqual(feedback.sha(intermediate), self.origin_proof["beforeSha256LF"])
        self.assertEqual(feedback.sha(intermediate), self.state_proof["afterSha256LF"])
        original_owned = undo(intermediate, self.state_proof)
        self.assertEqual(PRE_FEEDBACK_OWNED_VM_SHA256_LF, feedback.sha(original_owned))
        self.assertEqual(feedback.sha(original_owned), self.state_proof["beforeSha256LF"])
        first, first_proof = feedback.apply_video_feedback(feedback.OUTPUT_VM, original_owned, producer.parser)
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
        self.assertIn("DesktopOriginalVideoEngagementPresentation(sourceLease, subject, ::isOwned, withAdmission)", command)

    def test_foreign_paths_noop_and_wrong_or_duplicate_stage_anchors_fail_closed(self):
        self.assertEqual(origin.apply_feedback_origin("foreign.kt", "unchanged"), ("unchanged", None))
        self.assertEqual(feedback.apply_video_feedback("foreign.kt", "unchanged", producer.parser), ("unchanged", None))
        first = undo(self.vm, self.origin_proof)
        anchor = "    val tripleCelebrationFinished: Boolean = false\n)"
        for broken in (first.replace(anchor, "    val foreign: Boolean = false\n)", 1), first + "\n" + anchor):
            with self.assertRaises(AssertionError):
                origin.apply_feedback_origin(feedback.OUTPUT_VM, broken)
        with self.assertRaises(AssertionError):
            origin.apply_feedback_origin(feedback.OUTPUT_VM, self.vm)


if __name__ == "__main__":
    unittest.main()
