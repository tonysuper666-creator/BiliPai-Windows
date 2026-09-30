import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("watchdog_extractor", ROOT / "desktop/tools/extract-playback-watchdogs.py")
EXTRACTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXTRACTOR)


class PlaybackWatchdogExtractorTest(unittest.TestCase):
    def test_original_decisions_are_preserved_verbatim_with_verified_player_import(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            EXTRACTOR.generate(ROOT, output)
            source = EXTRACTOR.read(ROOT, EXTRACTOR.SOURCE)
            generated = (output / "com/android/purebilibili/feature/video/viewmodel/DesktopPlaybackWatchdogPolicies.kt").read_text(encoding="utf-8")
            boundaries = [
                ("private const val PLAYBACK_CDN_FIRST_FRAME_FALLBACK_TIMEOUT_MS", "data class CommentMentionSearchUiState("),
                ("internal data class PlaybackCdnFallbackState(", "internal fun buildPlaybackAudioUrlCandidates("),
                ("internal fun shouldFallbackFromCdnRewrite(\n    state: PlaybackCdnFallbackState,\n    playbackReady: Boolean\n)",
                    "internal fun hostForPlaybackLog("),
            ]
            for start, end in boundaries:
                self.assertIn(EXTRACTOR.section(source, start, end), generated)
            self.assertNotIn("import androidx.media3", generated)
            self.assertIn("import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player", generated)
            self.assertEqual(2, generated.count("internal fun shouldFallbackFromCdnRewrite("))

    def test_unverified_media3_state_fails_before_any_source_is_published(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "generated"
            metadata = Path(directory) / "metadata.json"
            codes = json.loads((ROOT / "desktop/third-party/media3-player-state-codes.json").read_text(encoding="utf-8"))
            codes["constants"]["STATE_BUFFERING"] = 3
            metadata.write_text(json.dumps(codes), encoding="utf-8")
            with self.assertRaises(AssertionError):
                EXTRACTOR.generate(ROOT, output, metadata)
            self.assertFalse(output.exists())

    def test_upstream_boundary_drift_rejects_extraction(self):
        with self.assertRaises(AssertionError):
            EXTRACTOR.section("duplicate boundary duplicate boundary end", "duplicate boundary", "end")
        with self.assertRaises(AssertionError):
            EXTRACTOR.section("renamed start end", "original start", "end")


if __name__ == "__main__":
    unittest.main()
