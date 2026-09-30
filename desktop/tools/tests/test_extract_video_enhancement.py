from pathlib import Path
import importlib.util
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('enhancement_plugins', ROOT / 'desktop/tools/extract-upstream-plugins.py')
plugins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plugins)
spec = importlib.util.spec_from_file_location('enhancement_boundary', ROOT / 'desktop/tools/extract-video-enhancement.py')
enhancement = importlib.util.module_from_spec(spec)
spec.loader.exec_module(enhancement)


class EnhancementExtractionTest(unittest.TestCase):
    def generate(self, output):
        selector = plugins.media_extractor(ROOT)
        parser = plugins.parser_for(ROOT)
        return enhancement.generate(ROOT, output, selector, parser, plugins.write, plugins.substitute)

    def test_output_decision_and_hdr_predicate_keep_original_bodies(self):
        original = plugins.read(ROOT, enhancement.BASE + 'feature/anime4k/Anime4KOutputPolicy.kt')
        selector, parser = plugins.media_extractor(ROOT), plugins.parser_for(ROOT)
        with tempfile.TemporaryDirectory() as directory:
            files = self.generate(Path(directory))
            result = next(path for path in files if path.name == 'Anime4KOutputPolicy.kt').read_text(encoding='utf-8')
        for name in ('resolveAnime4KOutputDecision', 'isAnime4kHdrOrDolbyVision'):
            self.assertEqual(selector.function(original, name, parser), selector.function(result, name, parser))
        self.assertNotIn('import android.', result)
        self.assertNotIn('isAnime4KGles3Available', result)

    def test_original_ui_survives_with_only_platform_event_bindings(self):
        original = plugins.read(ROOT, enhancement.BASE + 'feature/plugin/Anime4KPlugin.kt')
        selector, parser = plugins.media_extractor(ROOT), plugins.parser_for(ROOT)
        expected = selector.function(original.replace('override fun SettingsContent()', 'fun SettingsContent()'), 'SettingsContent', parser)
        with tempfile.TemporaryDirectory() as directory:
            files = self.generate(Path(directory))
            result = next(path for path in files if path.name == 'DesktopVideoEnhancementSettingsContent.kt').read_text(encoding='utf-8')
        actual = selector.function(result, 'DesktopVideoEnhancementSettingsContent', parser)
        actual = actual.replace('fun DesktopVideoEnhancementSettingsContent(configuration: com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration)', 'fun SettingsContent()')
        actual = actual.replace('configuration.configState.collectAsState()', 'configState.collectAsStateWithLifecycle()')
        actual = actual.replace('onValueChange = { configuration.setFsrSharpness(it) }', 'onValueChange = ::setFsrSharpness')
        actual = actual.replace('configuration.setAlgorithm(', 'setAlgorithm(').replace('configuration.setPreset(', 'setPreset(')
        actual = actual.replace('configuration.setRememberAcrossVideos(', 'setRememberAcrossVideos(')
        self.assertEqual(expected, actual)

    def test_shader_notice_comes_from_original_amd_mit_notice(self):
        with tempfile.TemporaryDirectory() as directory:
            files = self.generate(Path(directory))
            result = next(path for path in files if path.name == 'DesktopFsrShaderLicense.kt').read_text(encoding='utf-8')
        self.assertIn('Copyright (c) 2021 Advanced Micro Devices', result)
        self.assertIn('Permission is hereby granted, free of charge', result)
        self.assertIn('THE SOFTWARE IS PROVIDED "AS IS"', result)
        original = plugins.read(ROOT, enhancement.BASE + 'feature/anime4k/gl/Fsr1Shaders.kt')
        for line in result.splitlines():
            if line.startswith(('Copyright', 'Permission', 'THE SOFTWARE')):
                self.assertIn(line, original)


if __name__ == '__main__':
    unittest.main()
