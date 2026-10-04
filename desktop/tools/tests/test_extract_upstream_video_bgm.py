"""Real sole-producer replay; no generated-tree prerequisite or JVM/network."""
from pathlib import Path
import hashlib
import importlib.util
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("original_bgm_video_owner", TOOLS / "extract-upstream-video-full-owner.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
VM = "com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"


class OriginalVideoBgmPublicationExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="original-video-bgm-")
        output = Path(cls.temp.name)
        cls.original_path = tool._desktop_canonical_source(REPO, tool.RECIPES[0]["originalPath"])
        cls.original_raw = cls.original_path.read_bytes()
        tool.generate(REPO, output / "actual")
        cls.actual = (output / "actual" / VM).read_text(encoding="utf-8")
        with patch.object(tool, "owned_bgm_result_delta", side_effect=lambda path, body: body):
            tool.generate(REPO, output / "before")
        cls.before = (output / "before" / VM).read_text(encoding="utf-8")
        cls.inverse = []
        cls.replayed = tool.owned_bgm_result_delta(VM, cls.before, cls.inverse)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_actual_generator_replays_and_entire_inverse_restores_previous_whole_vm(self):
        self.assertEqual(self.actual, self.replayed)
        restored = self.actual
        for before, after in reversed(self.inverse):
            self.assertEqual(1, restored.count(after))
            restored = restored.replace(after, before, 1)
        self.assertEqual(self.before, restored)
        self.assertEqual(self.original_raw, self.original_path.read_bytes())
        self.assertEqual("94f0f773e693e430f0a4be8e0206b57f7f74dd00a84f5af571151b3278637426",
            hashlib.sha256(self.original_raw.replace(b"\r\n", b"\n")).hexdigest())

    def test_two_original_async_bgm_branches_use_captured_request_and_actual_caller(self):
        start = self.actual.index("    private fun loadPlayerInfo(")
        end = self.actual.index("                    // 3. 字幕信息", start)
        body = self.actual[start:end]
        self.assertLess(body.index("DesktopOriginalVideoBgmRequest(bvid, cid, requestToken)"),
            body.index("playerInfoJob = environment.invocations.launch"))
        self.assertIn("val desktopBgmCaller = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])", body)
        single = body[body.index("                    // 2. 处理 BGM 信息"):body.index("                    // 2b. gRPC BGM list")]
        multiple = body[body.index("                    // 2b. gRPC BGM list"):]
        for branch in (single, multiple):
            self.assertIn("publishDesktopBgmResult(desktopBgmRequest, desktopBgmCaller)", branch)
            self.assertNotIn("_uiState.update", branch)
        self.assertIn("current.copy(bgmInfo = checkedDataBgmInfo)", single)
        self.assertIn("current.copy(bgmInfoList = bgmList.toList())", multiple)
        self.assertIn("if (data.bgmInfo != null)", single)
        self.assertIn("if (grpcAid > 0)", multiple)
        self.assertIn("if (bgmList.isNotEmpty())", multiple)
        self.assertLess(body.index("getPbpProgressData("), body.index("publishDesktopBgmResult("))

    def test_final_guard_is_inside_original_admission_and_rechecked_before_each_cas(self):
        a = self.actual.index("    private fun publishDesktopBgmResult(")
        z = self.actual.index("    // Internal state", a)
        publication = self.actual[a:z]
        ordered = ["val admitted = environment.commit", "while (caller.isActive)",
            "desktopOriginalVideoBgmRequestIsCurrent(request,", "_desktopBgmRequest.value, playbackSessionState, current, caller.isActive",
            "val next = update(current)", "if (_uiState.compareAndSet(current, next))",
            "_desktopBgmResult.value = com.bilipai.desktop.ui.DesktopOriginalVideoBgmResult("]
        positions = [publication.index(value) for value in ordered]
        self.assertEqual(sorted(positions), positions)
        self.assertIn("request, next.bgmInfo, next.bgmInfoList)", publication)
        self.assertNotIn("getBgm", publication)
        self.assertNotIn("uiState.value", publication.replace("_uiState.value", ""))

    def test_new_request_clears_both_fields_only_after_current_source_admission(self):
        a = self.actual.index("    private fun loadPlayerInfo(")
        z = self.actual.index("        playerInfoJob = environment.invocations.launch", a)
        issue = self.actual[a:z]
        self.assertLess(issue.index("environment.commit"), issue.index("_desktopBgmRequest.value = desktopBgmRequest"))
        self.assertLess(issue.index("desktopOriginalVideoBgmRequestIsCurrent"), issue.index("_desktopBgmResult.value = null"))
        self.assertIn("checkNotNull(success).copy(bgmInfo = null, bgmInfoList = emptyList()) else state", issue)
        self.assertIn("} || !bgmRequestIssued) return", issue)
        self.assertLess(issue.index("} || !bgmRequestIssued) return"), issue.index("playerInfoJob?.cancel()"))

    def test_part_transition_and_committed_part_clear_bgm_without_changing_original_rollback(self):
        original = "        val subtitleClearedState = clearTransientPlaybackPreviewData(clearSubtitleFields(current))\n"
        adapted = original + "            .copy(bgmInfo = null, bgmInfoList = emptyList())\n"
        self.assertEqual(1, self.before.count(original))
        self.assertEqual(1, self.actual.count(adapted))
        a = self.actual.index("    fun switchPage(")
        z = self.actual.index("    private fun", a)
        actual_switch = self.actual[a:z]
        a = self.before.index("    fun switchPage(")
        z = self.before.index("    private fun", a)
        before_switch = self.before[a:z]
        self.assertEqual(before_switch, actual_switch.replace(adapted, original, 1))
        self.assertIn("val switchedState = subtitleClearedState.copy(", actual_switch)
        self.assertIn("_uiState.value = current.copy(", actual_switch)

    def test_projection_uses_raw_source_and_does_not_require_completed_request_job(self):
        a = self.actual.index("    internal val desktopBgmResult = combine(")
        z = self.actual.index("    private fun publishDesktopBgmResult(", a)
        projection = self.actual[a:z]
        self.assertIn("_desktopBgmResult, _desktopBgmRequest, playbackSessionStore.state, _uiState", projection)
        self.assertIn("internal fun captureDesktopBgmResult()", projection)
        self.assertNotIn("caller.isActive", projection)
        self.assertNotIn("playerInfoJob", projection)

    def test_changed_original_anchor_is_rejected_instead_of_silent_partial_adaptation(self):
        original_anchor = "        val subtitleClearedState = clearTransientPlaybackPreviewData(clearSubtitleFields(current))\n"
        with self.assertRaises(AssertionError):
            tool.owned_bgm_result_delta(VM, self.before.replace(original_anchor, original_anchor + original_anchor, 1))
        with self.assertRaises(AssertionError):
            tool.owned_bgm_result_delta(VM, self.before.replace(original_anchor, "        val subtitleClearedState = changed()\n", 1))
        self.assertEqual("unchanged", tool.owned_bgm_result_delta("other.kt", "unchanged"))


if __name__ == "__main__":
    unittest.main()
