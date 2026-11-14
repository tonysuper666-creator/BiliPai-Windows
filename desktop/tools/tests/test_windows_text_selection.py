"""Windows drag-to-copy edits of the comment and dynamic body producers apply to their real upstream sources."""
from pathlib import Path
import importlib.util
import sys
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v046_comment_list as comment_list
from v025_source_paths import canonical_source

BASE = "app/src/main/java/com/android/purebilibili/"


spec = importlib.util.spec_from_file_location("windows_selection_dynamic_card", TOOLS / "extract-upstream-dynamic-card.py")
dynamic_card = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dynamic_card)


def dynamic_card_source():
    return dynamic_card.read(REPO, BASE + "feature/dynamic/components/DynamicCard.kt")


class WindowsTextSelectionTest(unittest.TestCase):
    def test_dynamic_body_is_selectable_and_keeps_the_sheet_for_touch(self):
        adapted = dynamic_card.dynamic_rich_text(dynamic_card_source())
        body = adapted[adapted.index("val textGestureModifier"):]
        body = body[:body.index("\n}\n")]
        self.assertLess(body.index("SelectionContainer(modifier = modifier)"), body.index("AppText("),
                        "The body text sits inside a selection container")
        self.assertIn("!pressedByMouse", body, "A mouse press never opens the full-text sheet")
        self.assertIn("showTextSelectionSheet = true", body, "Touch long press still opens the sheet")

    def test_dynamic_body_anchor_change_fails_generation(self):
        changed = dynamic_card_source().replace("showTextSelectionSheet = true", "showTextSelectionSheet = !showTextSelectionSheet")
        with self.assertRaises(ValueError):
            dynamic_card.dynamic_rich_text(changed)

    def test_comment_text_is_selectable_with_tap_targets(self):
        source = canonical_source(REPO, BASE + "feature/video/ui/components/ReplyComponents.kt").read_text(encoding="utf-8")
        adapted = comment_list.windows_drag_selection(source.replace("\r\n", "\n"))
        self.assertIn("val selectionEnabled = true\n", adapted)
        self.assertIn("DesktopSelectionContainer {\n            content()", adapted,
                      "Comment text uses the selection container that Tab skips")
        self.assertNotIn("hasInteractiveAnnotations = hasTapHandler", adapted,
                         "Links, @ and timestamps no longer switch selection off")

    def test_comment_selection_anchor_change_fails_generation(self):
        with self.assertRaises(AssertionError):
            comment_list.windows_drag_selection("    val selectionEnabled = remember { false }\n")


if __name__ == "__main__":
    unittest.main()
