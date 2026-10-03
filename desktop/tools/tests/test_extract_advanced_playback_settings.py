import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

REPO = next(path for path in Path(__file__).resolve().parents if (path / 'app/src/main/java').is_dir())
spec = importlib.util.spec_from_file_location('playback_settings', REPO / 'desktop/tools/extract-playback-settings.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
PLATFORM = REPO / 'desktop/third-party/premium-audio-platform.json'


class PlaybackSettingsExtractTest(unittest.TestCase):
    def test_original_audio_failure_body_has_only_platform_import_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module.generate(REPO, root, PLATFORM)
            body = (root / 'com/android/purebilibili/feature/video/playback/audio/PremiumAudioPlaybackFailurePolicy.kt').read_text(encoding='utf-8')
            body = body.split('\n', 2)[2].replace(
                'import com.bilipai.desktop.player.platform.DesktopPremiumAudioMedia3ErrorCodes as PlaybackException',
                'import androidx.media3.common.PlaybackException')
            self.assertEqual(module.read(REPO, module.FAILURE), body)

    def test_original_default_selection_options_and_normalizer_are_extracted_verbatim(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module.generate(REPO, root, PLATFORM)
            self.assertFalse((root / 'com/android/purebilibili/feature/settings/DefaultAudioQualitySelection.kt').exists())
            spec = importlib.util.spec_from_file_location('whole_playback_settings', REPO / 'desktop/tools/extract-upstream-whole-playback-settings.py')
            whole = importlib.util.module_from_spec(spec); spec.loader.exec_module(whole)
            whole.main(REPO, root / 'whole')
            body = (root / 'whole/generated/com/android/purebilibili/feature/settings/PlaybackSettingsSelectionPolicy.kt').read_text(encoding='utf-8')
            for name in ['normalizeDefaultAudioQualityOption', 'resolveDefaultAudioQualityOptions']:
                self.assertIn(module.function(module.read(REPO, module.SETTINGS), name), body)
            constant = (root / 'com/android/purebilibili/core/store/player/DefaultAudioQuality.kt').read_text(encoding='utf-8')
            self.assertIn('const val DEFAULT_AUDIO_QUALITY_FOLLOW_LAST = -2', constant)

    def test_unverified_platform_archive_or_audio_role_constant_fails_closed(self):
        for section, field, value in [('media3', 'sourceArchiveSha256', 'unverified'), ('mpv', 'value', -12)]:
            with self.subTest(section=section), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                platform = json.loads(PLATFORM.read_text(encoding='utf-8'))
                platform[section][field] = value
                altered = root / 'platform.json'
                altered.write_text(json.dumps(platform), encoding='utf-8')
                with self.assertRaises(AssertionError):
                    module.generate(REPO, root / 'generated', altered)

    def test_missing_original_media3_import_fails_closed(self):
        original_read = module.read
        try:
            def altered(repo, path):
                text = original_read(repo, path)
                return text.replace('import androidx.media3.common.PlaybackException', 'import other.Errors') if path == module.FAILURE else text
            module.read = altered
            with tempfile.TemporaryDirectory() as directory, self.assertRaises(AssertionError):
                module.generate(REPO, Path(directory), PLATFORM)
        finally:
            module.read = original_read


if __name__ == '__main__':
    unittest.main()
