"""Fresh source contracts for the confirmed decorative Windows mount.

These tests do not create a window or load user32. Native alpha/input behavior
requires the separate actual Main fixture; parsing cannot prove it.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import v029_video_feedback as feedback
import v029_video_feedback_host as host


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


producer = load("video_feedback_host_actual_producer", TOOLS / "extract-upstream-video-detail-full-units.py")


def read(path):
    return producer.wide(path).read_bytes().decode("utf-8").replace("\r\n", "\n")


class VideoFeedbackHostExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-video-feedback-host-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.output = Path(cls.temp.name) / "generated"
        producer.generate(REPO, cls.output, True)
        cls.motion = read(cls.output / host.OUTPUT_MOTION)
        cls.proof = json.loads(read(cls.output / "v029-video-feedback-motion-proof.json"))

    def test_actual_sole_producer_consumes_complete_motion_once_with_final_output_hash(self):
        source = read(TOOLS / "extract-upstream-video-detail-full-units.py")
        self.assertEqual(1, source.count("motion,motionProof=feedback_motion_source()"))
        outputs = json.loads(read(self.output / "source-bindings.json"))["outputs"]
        self.assertEqual(len(outputs), len({row["path"] for row in outputs}))
        motion = [row for row in outputs if row["path"] == host.OUTPUT_MOTION]
        self.assertEqual(1, len(motion))
        self.assertTrue(motion[0]["generated"])
        self.assertEqual(feedback.COMMIT, motion[0]["fixedCommit"])
        self.assertEqual(self.proof["generatedSha256LF"], motion[0]["sha256LF"])
        for row in outputs:
            if row["generated"]:
                self.assertEqual(row["sha256LF"], feedback.sha(read(self.output / row["path"])), row["path"])
        origin_proof = json.loads(read(self.output / "v029-video-feedback-origin-proof.json"))
        lifetime = json.loads(read(self.output / "v029-video-feedback-lifetime-proof.json"))
        final_vm = read(self.output / feedback.OUTPUT_VM)
        self.assertEqual(lifetime["beforeSha256LF"], origin_proof["afterSha256LF"])
        self.assertEqual(lifetime["afterSha256LF"], feedback.sha(final_vm))
        restored = final_vm
        for edit in reversed(lifetime["edits"]):
            self.assertEqual(1, restored.count(edit["after"]))
            restored = restored.replace(edit["after"], edit["before"], 1)
        self.assertEqual(origin_proof["afterSha256LF"], feedback.sha(restored))
        self.assertIn("else presentation.admitFeedback(check)", final_vm)

    def test_both_complete_original_decorative_blocks_have_exact_counted_inverse(self):
        _, originals = feedback.sources()
        full = originals[host.ORIGIN]
        self.assertEqual(feedback.COMMIT, self.proof["fixedCommit"])
        self.assertEqual(feedback.sha(full), self.proof["originalFullRawSha256"])
        wrapper = self.proof["wrapper"]
        self.assertEqual(self.motion, wrapper["prefix"] + self.proof["generatedExcerpts"]["likeMaid"] +
                         wrapper["middle"] + self.proof["generatedExcerpts"]["triple"] + wrapper["suffix"])
        self.assertEqual(8, len(self.proof["countedAdaptations"]))
        for name, excerpt in self.proof["generatedExcerpts"].items():
            restored = excerpt
            for row in reversed(self.proof["countedAdaptations"]):
                if row["part"] == name:
                    self.assertEqual(1, restored.count(row["after"]), row["label"])
                    restored = restored.replace(row["after"], row["before"], 1)
            start, end = self.proof["originalExcerptRanges"][name]
            self.assertEqual(full[start:end], restored)
            self.assertEqual(self.proof["originalExcerpts"][name], restored)
        self.assertIn("staticDisplayDurationMs = 1_000L", self.motion)
        self.assertIn("completionHoldDurationMs = if (action == VideoMaidAction.DISLIKE) 400L else 500L", self.motion)
        self.assertIn("key(action, maidOrigin, likeOrigin)", self.motion)
        self.assertIn("key(tripleOrigin)", self.motion)
        for marker in ("LikeBurstAnchorRegistry", "LocalConfiguration", "VideoShareFeedbackEvents",
                       "popupMessage", "resumePosition"):
            self.assertNotIn(marker, self.motion)

    def test_actual_leaf_dispatch_and_each_completion_use_confirmed_source_receipts(self):
        ui = TOOLS.parent / "src/main/kotlin/com/bilipai/desktop/ui"
        leaf = read(ui / "DesktopWindowsVideoPhysicalLeaf.kt")
        mount = read(ui / "DesktopWindowsConfirmedVideoFeedback.kt")
        self.assertEqual(1, leaf.count("DesktopWindowsConfirmedVideoFeedback(binding, viewportSize, native.surface)"))
        self.assertIn("engagementBinding?.like()", leaf)
        read_start = leaf.index("val feedbackSource = assembly.native.current()?.takeIf")
        read_end = leaf.index("val engagementBinding = remember", read_start)
        self.assertIn("feedbackPresentationCurrent()", leaf[read_start:read_end])
        self.assertNotIn("current() &&", leaf[read_start:read_end])
        self.assertIn("remember(assembly, feedbackSource, engagementSubject, presentationAlive, pipActive, brandEvents)", leaf)
        self.assertIn("val expected = feedbackSource", leaf)
        feedback_index = leaf.index("DesktopWindowsConfirmedVideoFeedback(binding, viewportSize, native.surface)")
        self.assertIn("if (presentationAlive && !pipActive)", leaf[feedback_index - 150:feedback_index])
        self.assertIn("if (engagementBinding?.coin(count, false) == true)", leaf)
        self.assertIn("LocalAwtWindow.current !== root.window", mount)
        self.assertIn("it === snapshot.desktopFeedbackOrigin(kind)", mount)
        self.assertIn("root.owns() && binding.isFeedbackOwned() && binding.admitFeedback {}", mount)
        self.assertIn("sourceOwner = binding.sourceOwner, subject = binding.subject", mount)
        self.assertIn("key(receipt)", mount)
        self.assertIn("DisposableEffect(binding, receipt)", mount)
        self.assertIn("onDispose { binding.cancelFeedback(receipt) }", mount)
        self.assertIn("if (available) everAvailable = true", mount)
        self.assertIn("(available || everAvailable)", mount)
        self.assertNotIn("if (!available)", mount)
        self.assertEqual(3, self.motion.count("binding.completeFeedback(checkNotNull("))

    def test_native_carrier_retains_named_command_callbacks_and_rejects_unproven_alpha(self):
        ui = TOOLS.parent / "src/main/kotlin/com/bilipai/desktop/ui"
        carrier = read(ui / "DesktopCommandPopupWindow.kt")
        style = read(ui / "DesktopDecorativeWindowStyle.kt")
        self.assertIn("onNativeWindowAvailability = { window, ready -> latestNativeWindowAvailability?.invoke(window, ready) }", carrier)
        self.assertIn("onWindowAvailability = { identity, ready -> latestWindowAvailability?.invoke(identity, ready) }", carrier)
        self.assertIn("isTransparent = true", carrier)
        self.assertIn("if (!window.isDisplayable) window.addNotify()", carrier)
        self.assertIn("if (!decorative) shape = Area()", carrier)
        self.assertIn("if (closed || decorative) return", carrier)
        self.assertIn("current and LAYERED == 0", style)
        self.assertIn("val actual = native.GetWindowLongW(pointer, -20)", style)
        self.assertIn("DesktopDecorativeWindowStylePolicy.acknowledged(actual)", style)
        hidden = carrier[carrier.index("override fun componentHidden(event: ComponentEvent) {"):
                         carrier.index("    val regions = DesktopCommandHitRegions")]
        self.assertNotIn("onDecorativeRejection", hidden)
        decoration = carrier[carrier.index("internal fun DesktopDecorativeVideoFeedbackPopup("):]
        self.assertIn("key(sourceOwner, subject)", decoration)
        self.assertIn("currentCompositionLocalContext", decoration)
        self.assertIn("onDispose { host.close() }", decoration)
        self.assertIn("onDecorativeRejection = { latestRejection() }", decoration)
        self.assertNotIn("toFront", decoration + style)
        self.assertNotIn("requestFocus", decoration + style)


if __name__ == "__main__":
    unittest.main()
