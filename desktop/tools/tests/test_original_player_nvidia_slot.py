"""Actual original player/overlay/bar -> sole Root NVIDIA controls, without JVM or GUI."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))


def load(name):
    spec = importlib.util.spec_from_file_location("nvidia_slot_" + name, TOOLS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OriginalPlayerNvidiaSlotTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-original-nvidia-slot-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.section = load("extract-upstream-video-player-section-full")
        cls.controls = load("extract-upstream-video-player-full-controls")
        cls.section.generate(REPO, cls.root / "section", True)
        cls.controls.generate(REPO, cls.root / "controls", True)

    def test_actual_ordinary_player_forwards_same_root_session_and_menu_hold_to_original_bar(self):
        section = (self.root / "section/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt").read_text(encoding="utf-8")
        self.assertEqual(1, section.count("com.bilipai.desktop.ui.DesktopWindowsNvidiaPlayerSlot("))
        self.assertIn("windowsEnhancementContent = { onMenuExpandedChange ->", section)
        self.assertIn("platform.viewport.playbackIdentity != null &&", section)
        self.assertIn("onMenuExpandedChanged = onMenuExpandedChange", section)
        overlay = (self.root / "controls/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlay.kt").read_text(encoding="utf-8")
        self.assertIn("windowsEnhancementContent = windowsEnhancementContent", overlay)
        bar = (self.root / "controls/com/android/purebilibili/feature/video/ui/overlay/BottomControlBar.kt").read_text(encoding="utf-8")
        # v0.4.6 draws the right function row twice (plain landscape and glass); exactly one branch composes.
        self.assertEqual(2, bar.count("windowsEnhancementContent { showWindowsEnhancementMenu = it }"))
        self.assertEqual(1, bar.count("var showWindowsEnhancementMenu by remember { mutableStateOf(false) }"))
        self.assertIn("showVideoEnhancementPanel || showWindowsEnhancementMenu", bar)
        ui = (REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoEnhancementControls.kt").read_text(encoding="utf-8")
        start = ui.index("internal fun DesktopWindowsNvidiaPlayerSlot(")
        end = ui.index("\n@Composable", start)
        slot = ui[start:end]
        self.assertIn("LocalDesktopWindowsVideoEnhancement.current", slot)
        self.assertIn("binding.state.collectAsState()", slot)
        self.assertIn("binding.configuration::setAutomaticEnabled", slot)
        self.assertNotIn("DesktopVideoEnhancementSession(", slot)
        self.assertNotIn("DesktopVideoEnhancementConfiguration(", slot)

    def test_windows_single_entry_delta_reverses_to_whole_original_bar_without_removing_default_callers(self):
        original = self.controls.read("feature/video/ui/overlay/BottomControlBar.kt")
        old_edits = self.controls.ADAPT
        try:
            self.controls.ADAPT = []
            adapted = self.controls.windows_nvidia_control_bar_ui(original, record=True)
            edits = list(self.controls.ADAPT)
        finally:
            self.controls.ADAPT = old_edits
        # Five edits against the v0.3.7 bar; seven against v0.4.6 (the plain landscape row and the glass row each get the
        # slot and the direct entry, the more-menu entry exists once per row).
        self.assertEqual(7, len(edits))
        self.assertEqual([1, 1, 1, 1, 1, 1, 2], [edit["count"] for edit in edits])
        self.assertIn("windowsEnhancementContent == null", adapted)
        self.assertIn("windowsEnhancementContent: (@Composable ((Boolean) -> Unit) -> Unit)? = null", adapted)
        for edit in reversed(edits):
            self.assertEqual(edit["count"], adapted.count(edit["after"]))
            adapted = adapted.replace(edit["after"], edit["before"])
        self.assertEqual(original, adapted)

    def test_both_v046_action_rows_host_the_compact_slot_and_yield_the_legacy_entries(self):
        original = self.controls.read("feature/video/ui/overlay/BottomControlBar.kt")
        adapted = self.controls.windows_nvidia_control_bar_ui(original)
        plain = adapted.index("PlainLandscapeDockActionRow(")
        glass = adapted.index("minimumSpacing = when {", plain)
        slot = "windowsEnhancementContent { showWindowsEnhancementMenu = it }"
        first = adapted.index(slot)
        second = adapted.index(slot, first + 1)
        self.assertLess(plain, first)
        self.assertLess(first, glass)
        self.assertLess(glass, second)
        self.assertEqual(2, adapted.count(slot))
        self.assertEqual(2, adapted.count("if (anime4kAvailable && windowsEnhancementContent == null) {"))
        self.assertEqual(2, adapted.count("if (anime4kAvailable && !showDirectTools && windowsEnhancementContent == null) {"))
        # no legacy direct enhancement entry is left that ignores the slot
        self.assertEqual(0, adapted.count("if (anime4kAvailable) {"))
        self.assertEqual(0, original.count("windowsEnhancementContent"))

    def test_changed_or_duplicate_original_entry_anchor_is_rejected(self):
        original = self.controls.read("feature/video/ui/overlay/BottomControlBar.kt")
        needle = "if (anime4kAvailable && !showDirectTools) {"
        self.assertEqual(2, original.count(needle))  # once per v0.4.6 action row
        with self.assertRaises(AssertionError):
            self.controls.windows_nvidia_control_bar_ui(original.replace(needle, "if (false) {", 1))
        with self.assertRaises(AssertionError):
            self.controls.windows_nvidia_control_bar_ui(original + "\n" + needle)


if __name__ == "__main__":
    unittest.main()
