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
        self.assertEqual(1, bar.count("windowsEnhancementContent { showWindowsEnhancementMenu = it }"))
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
        self.assertEqual(5, len(edits))
        self.assertIn("windowsEnhancementContent == null", adapted)
        self.assertIn("windowsEnhancementContent: (@Composable ((Boolean) -> Unit) -> Unit)? = null", adapted)
        for edit in reversed(edits):
            self.assertEqual(1, adapted.count(edit["after"]))
            adapted = adapted.replace(edit["after"], edit["before"], 1)
        self.assertEqual(original, adapted)

    def test_changed_or_duplicate_original_entry_anchor_is_rejected(self):
        original = self.controls.read("feature/video/ui/overlay/BottomControlBar.kt")
        needle = "if (anime4kAvailable && !showDirectTools) {"
        self.assertEqual(1, original.count(needle))
        with self.assertRaises(AssertionError):
            self.controls.windows_nvidia_control_bar_ui(original.replace(needle, "if (false) {", 1))
        with self.assertRaises(AssertionError):
            self.controls.windows_nvidia_control_bar_ui(original + "\n" + needle)


if __name__ == "__main__":
    unittest.main()
