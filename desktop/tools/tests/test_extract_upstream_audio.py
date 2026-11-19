import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TOOLS = HERE.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from legacy_read_point import legacy_read_point

spec = importlib.util.spec_from_file_location("upstream_audio", TOOLS / "extract-upstream-audio.py")
audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio)

MUSIC = audio.AUDIO + "viewmodel/MusicViewModel.kt"


def source_root():
    # The build runs this producer with --repo <repo>/.legacy-root; reading the
    # merged root asserts a pin the build never sees.
    return legacy_read_point(REPO, MUSIC)


class AudioExtractionTest(unittest.TestCase):
    def test_music_state_body_is_verbatim_and_manifest_pinned(self):
        path = MUSIC
        root = source_root()
        original = audio.read_source(root, path)
        expected = audio.section(original, "internal data class MusicUiState(", "internal class MusicViewModel : ViewModel() {")
        self.assertEqual(hashlib.sha256(expected.rstrip().encode()).hexdigest(), "80dc0220a1a08a94ea3b7e6b251a5db9fb756742a51f99ecb02b1dd21b4edea7")
        with tempfile.TemporaryDirectory() as folder:
            outputs = audio.generate(root, Path(folder))
            actual = next(p for p in outputs if p.name == "MusicUiState.kt").read_text(encoding="utf-8")
            self.assertTrue(actual.endswith(expected))
            self.assertNotIn("import android.", actual)
            self.assertNotIn("ViewModel()", actual)
            self.assertEqual(audio.SOURCES[path], "extracted")

    def test_ambiguous_or_missing_music_boundary_stops_extraction(self):
        start = "internal data class MusicUiState("
        end = "internal class MusicViewModel : ViewModel() {"
        for changed in (start + start + end, end, start):
            with self.assertRaises(ValueError):
                audio.section(changed, start, end)


if __name__ == "__main__":
    unittest.main()
