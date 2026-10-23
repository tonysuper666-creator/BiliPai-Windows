"""Slice H3 source checks: the feed scrollbar files exist and keep their key markers. No JVM, no network.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_feed_scrollbar.py

What is proved. The four Kotlin files of H3 (the pure estimate, the adapter and overlay, the Home card grid mount,
and the JVM test) are on disk, are plain LF, have no Chinese / global hook / KeyFallback, stay inside the hygiene
program scopes where they belong, and still contain the textual markers the design and the first T15 commits named.
DesktopFeedGridHost.kt is listed in PROGRAM_EXTRA_GLOBS (spec 8.2) but is not on disk: the mount is DesktopHomeCardGrid.kt
(Root decision, tasks/T15.md round 2), which this suite pins instead.

What is not proved. That the Kotlin compiles or that the estimate numbers are right: DesktopLazyScrollbarsTest is the
JVM proof. Windows look, drag feel and real keyboard focus are not proved here.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_feed_scrollbar as h3  # noqa: E402
import ux_source as source  # noqa: E402


class FilesExistTest(unittest.TestCase):
    def test_the_h3_kotlin_files_exist(self):
        for path in (h3.LAZY_KT, h3.FEED_KT, h3.MOUNT_KT, h3.TEST_KT):
            self.assertTrue(path.is_file(), path.as_posix())
        self.assertFalse((source.K / "ui/DesktopFeedGridHost.kt").exists(),
                         "DesktopFeedGridHost.kt stays absent; the mount is DesktopHomeCardGrid.kt")

    def test_the_spec_host_name_stays_armed_in_the_hygiene_scope(self):
        self.assertIn(h3.SPEC_HOST_NAME, source.PROGRAM_EXTRA_GLOBS)
        for name in ("ui/DesktopLazyScrollbars.kt", "ui/DesktopFeedScrollbar.kt"):
            self.assertIn(name, source.PROGRAM_EXTRA_GLOBS, name)


class MarkersTest(unittest.TestCase):
    def test_the_pure_estimate_keeps_its_public_names(self):
        text = h3.read(h3.LAZY_KT)
        for marker in h3.LAZY_MARKERS:
            self.assertIn(marker, text, marker)

    def test_the_adapter_overlay_and_paging_keep_their_public_names(self):
        text = h3.read(h3.FEED_KT)
        for marker in h3.FEED_MARKERS:
            self.assertIn(marker, text, marker)

    def test_the_home_card_grid_mounts_the_overlay_and_the_paging_keys(self):
        text = h3.read(h3.MOUNT_KT)
        for marker in h3.MOUNT_MARKERS:
            self.assertIn(marker, text, marker)
        # The overlay is a sibling of the grid inside the same BoxWithConstraints, not a nested Box.
        self.assertLess(text.index("FeedVerticalStaggeredGrid("), text.index("DesktopFeedScrollbar(state)"))

    def test_the_jvm_test_covers_the_pure_functions(self):
        text = h3.read(h3.TEST_KT)
        for marker in h3.TEST_MARKERS:
            self.assertIn(marker, text, marker)
        self.assertGreaterEqual(text.count("@Test"), 8)


class HygieneTest(unittest.TestCase):
    def test_the_new_files_are_clean_and_inside_the_program_scope(self):
        program = {source.rel(path) for path in source.kotlin_files(source.PROGRAM_GLOBS)}
        for name, path in (("ui/DesktopLazyScrollbars.kt", h3.LAZY_KT), ("ui/DesktopFeedScrollbar.kt", h3.FEED_KT)):
            self.assertIn(name, program, name)
            text = source.read(path)
            self.assertEqual([], source.cjk_positions(text), name)
            self.assertEqual([], source.hook_uses(text), name)
            self.assertEqual([], source.key_fallback_uses(text), name)
        source.assert_lf_only(self, h3.LAZY_KT, h3.FEED_KT, h3.MOUNT_KT, h3.TEST_KT)

    def test_reduce_motion_is_read_from_the_shell_local_not_polled(self):
        feed = source.mask_kotlin(source.read(h3.FEED_KT))
        self.assertIn("LocalDesktopKitReducedMotion.current", feed)
        self.assertNotIn("rememberSystemReduceMotion", feed)
        self.assertNotIn("rememberDesktopDynamicReduceMotion", feed)


if __name__ == "__main__":
    unittest.main()
