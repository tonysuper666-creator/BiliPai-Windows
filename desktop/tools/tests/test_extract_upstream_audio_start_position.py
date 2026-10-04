"""Fresh full-owner generation, without relying on a previously generated JVM source."""
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from v025_source_paths import canonical_source


class ExplicitAudioStartPositionGeneratorTests(unittest.TestCase):
    def test_fresh_full_owner_keeps_fixed_raw_source_and_both_request_decisions(self):
        repo = TOOLS.parents[1]
        path = "app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"
        fixed = canonical_source(repo, path)
        before = fixed.read_bytes()
        self.assertEqual(hashlib.sha256(before.replace(b"\r\n", b"\n")).hexdigest(),
            "94f0f773e693e430f0a4be8e0206b57f7f74dd00a84f5af571151b3278637426")
        spec = importlib.util.spec_from_file_location("audio_start_full_owner", TOOLS / "extract-upstream-video-full-owner.py")
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        # Inspect the exact delta's counted edits while the actual producer creates a fresh output.
        applied = []
        actual_delta = producer.explicit_audio_start_position_delta
        def observe_delta(output_path, body):
            edits = []
            result = actual_delta(output_path, body, edits)
            if edits:
                self.assertEqual(len(edits), 5)
                restored = result
                for edit in reversed(edits):
                    start = edit["offset"]
                    self.assertEqual(restored[start:start + len(edit["after"])], edit["after"])
                    restored = restored[:start] + edit["before"] + restored[start + len(edit["after"]):]
                self.assertEqual(restored, body)
                applied.append(output_path)
            return result
        producer.explicit_audio_start_position_delta = observe_delta
        with tempfile.TemporaryDirectory(prefix="bilipai-audio-start-generator-") as directory:
            output = Path(directory)
            producer.generate(repo, output)
            generated = (output / "com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt").read_text(encoding="utf-8")
            self.assertEqual(applied, ["com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"])
            self.assertEqual(generated.count("desktopExplicitStartPositionMs: Long? = null"), 2)
            self.assertEqual(generated.count("desktopExplicitStartPositionMs = desktopExplicitStartPositionMs"), 2)
            self.assertEqual(generated.count("desktopWindowsShouldRestartPlaybackAtEnd(videoDuration, startPos, desktopExplicitStartPositionMs)"), 1)
            self.assertIn("val cachedPosition = playbackUseCase.getCachedPosition(playbackRequest.bvid, progressCid)", generated)
            self.assertIn("cachedPositionMs = loadResult.cachedPositionMs", generated)
            self.assertIn("val safeCachedPositionMs = cachedPositionMs.coerceAtLeast(0L)\n    if (safeCachedPositionMs > 0L) return safeCachedPositionMs", generated)
        self.assertEqual(fixed.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
