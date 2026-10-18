"""Line-ending checks of the NVIDIA auto-tier work.

read() in nvidia_core_source normalises CRLF to LF, because a checkout with core.autocrlf=true holds CRLF (or, after an
editor touched a file, mixed) working copies of LF blobs. The committed blobs themselves must be LF: CI checks out with
core.autocrlf=false and core.eol=lf. This file pins both halves, so a CRLF working copy never fails a source check and a
CRLF blob never reaches the index unnoticed."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

TESTS = source.ST / "kotlin/com/bilipai/desktop"

# Files that this work changed or added outside the new package, the new test folder and this check folder.
CHANGED_MAIN = (
    "DesktopShell.kt", "Main.kt", "player/DesktopNvidiaVideoAutoPolicy.kt", "player/DesktopNvidiaVideoPreferences.kt",
    "player/DesktopVideoEnhancementSession.kt", "player/DesktopWindowsHdrDisplay.kt", "player/MpvPlayer.kt",
    "player/MpvRuntimeProbe.kt", "player/PlayerNvidiaVideo.kt", "player/PlayerVideoOutput.kt",
    "plugins/DesktopVideoEnhancementConfiguration.kt", "settings/DesktopWindowsSettingsCatalog.kt",
    "ui/DesktopVideoEnhancementControls.kt",
)
CHANGED_TESTS = (
    "player/DesktopNvidiaVideoAutoPolicyTest.kt", "player/DesktopVideoEnhancementSessionTest.kt",
    "player/DesktopWindowsDxgiAdaptersTest.kt", "player/PlayerNvidiaActorWithdrawalTest.kt",
    "player/PlayerNvidiaVideoTest.kt", "plugins/DesktopVideoEnhancementConfigurationTest.kt",
)
JAVA_DXGI = source.REPO / "desktop/src/main/java/com/bilipai/desktop/player/DesktopWindowsDxgiAdapters.java"


def work_files():
    here = Path(__file__).resolve().parent
    files = sorted(source.KN.glob("*.kt")) + sorted((TESTS / "nvidia").glob("*.kt")) + sorted(here.glob("*.py"))
    files += [source.K / name for name in CHANGED_MAIN] + [TESTS / name for name in CHANGED_TESTS]
    files += [JAVA_DXGI, source.VECTORS]
    return files


class ReadNormalisationTest(unittest.TestCase):
    def read_text(self, data):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "sample.kt"
            path.write_bytes(data)
            return source.read(path)

    def test_a_crlf_working_copy_reads_like_the_lf_blob(self):
        self.assertEqual("one\ntwo\n", self.read_text(b"one\r\ntwo\r\n"))

    def test_a_mixed_working_copy_reads_like_the_lf_blob(self):
        self.assertEqual("one\ntwo\nthree\n", self.read_text(b"one\r\ntwo\nthree\r\n"))

    def test_an_lf_file_and_non_ascii_text_are_unchanged(self):
        self.assertEqual("one\ncafé\n", self.read_text("one\ncafé\n".encode("utf-8")))

    def test_the_helper_never_raises_on_a_carriage_return_any_more(self):
        self.assertEqual("a\nb", self.read_text(b"a\r\nb"))


class CommittedLineEndingsTest(unittest.TestCase):
    def test_every_file_of_the_work_exists(self):
        files = work_files()
        self.assertGreaterEqual(len(files), 45)
        for path in files:
            self.assertTrue(path.is_file(), str(path))
        self.assertEqual(len(files), len(set(files)), "a file is listed twice")

    def test_every_tracked_file_of_the_work_is_committed_with_lf(self):
        source.assert_lf_only(self, *work_files())

    def test_the_index_query_answers_with_plain_words_or_not_at_all(self):
        words = source.index_eol([source.ACTIVATION, source.TIER_PROFILE])
        if words is None:
            self.skipTest("git cannot answer in this checkout")
        for name, word in words.items():
            self.assertIn(word, ("lf", "crlf", "mixed", "none", "-text"), name)
            self.assertTrue(name.endswith(".kt"), name)

    def test_an_empty_query_needs_no_git(self):
        self.assertEqual({}, source.index_eol([]))


if __name__ == "__main__":
    unittest.main()
