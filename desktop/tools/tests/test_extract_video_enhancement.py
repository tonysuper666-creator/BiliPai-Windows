from pathlib import Path
import importlib.util
import hashlib
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

    def test_windows_settings_entrance_uses_only_the_required_root_nvidia_leaf(self):
        original = plugins.read(ROOT, enhancement.BASE + 'feature/plugin/Anime4KPlugin.kt')
        selector, parser = plugins.media_extractor(ROOT), plugins.parser_for(ROOT)
        with tempfile.TemporaryDirectory() as directory:
            files = self.generate(Path(directory))
            result = next(path for path in files if path.name == 'DesktopVideoEnhancementSettingsContent.kt').read_text(encoding='utf-8')
        actual = selector.function(result, 'DesktopVideoEnhancementSettingsContent', parser)
        self.assertEqual(actual, '''fun DesktopVideoEnhancementSettingsContent(configuration: com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration) {
    DesktopWindowsVideoEnhancementSettingsContent(configuration)
}''')
        self.assertIn(hashlib.sha256(original.encode('utf-8')).hexdigest(), result)
        for forbidden in ('setAlgorithm(', 'setPreset(', 'setFsrSharpness(', 'setRememberAcrossVideos(',
                          'VideoEnhancementAlgorithm', 'AppFilterChip', 'FSR', 'CNN'):
            self.assertNotIn(forbidden, result)

    def test_shared_original_player_widget_changes_only_the_legacy_enhancement_item(self):
        spec = importlib.util.spec_from_file_location('nvidia_player_widget', ROOT / 'desktop/tools/extract-upstream-video-player-full-controls.py')
        controls = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(controls)
        original = plugins.read(ROOT, enhancement.BASE + 'feature/video/ui/components/VideoSettingsPanel.kt')
        controls.ADAPT = []
        result = controls.windows_nvidia_enhancement_leaf(original, record=True)
        self.assertEqual(len(controls.ADAPT), 1)
        edit = controls.ADAPT[0]
        self.assertEqual(edit['label'], 'windows-nvidia-only-enhancement-widget')
        self.assertEqual(result.count(edit['after']), 1)
        self.assertEqual(result.replace(edit['after'], edit['before'], 1), original)
        self.assertIn('DesktopWindowsVideoEnhancementSettingsContent()', edit['after'])
        for widget in ('VideoEnhancementAlgorithmOptions(', 'Anime4KPresetOptions(', 'FsrSharpnessOptions('):
            self.assertEqual(edit['before'].count(widget), 1)
            self.assertNotIn(widget, result)
        # Required source signatures and every unrelated control remain present.
        self.assertIn('onVideoEnhancementAlgorithmChange:', result)
        self.assertIn('// [New] 资源下载', result)

    def test_changed_or_duplicated_legacy_widget_boundary_requires_review(self):
        spec = importlib.util.spec_from_file_location('nvidia_widget_reject', ROOT / 'desktop/tools/extract-upstream-video-player-full-controls.py')
        controls = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(controls)
        original = plugins.read(ROOT, enhancement.BASE + 'feature/video/ui/components/VideoSettingsPanel.kt')
        candidates = (original.replace('title = "画质增强",', 'title = "changed enhancement",'),
                      original + original, original.replace('FsrSharpnessOptions(', 'ChangedSharpnessOptions('))
        for source in candidates:
            with self.subTest(candidateSize=len(source)), self.assertRaises(ValueError):
                controls.windows_nvidia_enhancement_leaf(source)

    def test_retired_glsl_backend_is_not_generated_but_upstream_inputs_remain_exact(self):
        with tempfile.TemporaryDirectory() as directory:
            files = self.generate(Path(directory))
        self.assertNotIn('DesktopFsrShaderLicense.kt', {path.name for path in files})
        rows = {row['path']: row for row in plugins.inventory(ROOT)}
        for name in ('Fsr1Shaders.kt', 'MpvAnime4KShader.kt', 'Anime4KShaderRepository.kt'):
            path = enhancement.BASE + 'feature/anime4k/gl/' + name
            self.assertEqual('reference-only', rows[path]['mode'])
            self.assertNotIn(path, plugins.DIRECT)
            self.assertEqual(hashlib.sha256(plugins.read(ROOT, path).encode()).hexdigest(), rows[path]['sha256'])

    def test_native_normal_initialization_and_self_test_have_no_retired_shader_command(self):
        # Inspect the actual source using the existing Kotlin body parser, including run/init.
        selector, parser = plugins.media_extractor(ROOT), plugins.parser_for(ROOT)
        source = (ROOT / 'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt').read_text(encoding='utf-8')
        run = selector.function(source, 'run', parser)
        self.assertIn('native.mpv_initialize(handle)', run)
        self.assertIn('perform(native, handle, Action.Load(', run)
        self.assertIn('applyNvidiaVideo(native, handle, action)', source)
        for legacy in ('Action.VideoShaders', 'glsl-shaders', 'glsl-shader-opts', 'MpvVideoShaderProperties',
                       'setVideoShaders', 'refreshPausedVideoFrame'):
            self.assertNotIn(legacy, source)
        self_test = (ROOT / 'desktop/src/main/kotlin/com/bilipai/desktop/player/PlayerSelfTest.kt').read_text(encoding='utf-8')
        self.assertNotIn('DesktopShaderNativeSmoke', self_test)
        self.assertNotIn('nativeAnime4KPresetsExecutedAndChangedPixels', self_test)
        self.assertIn('DesktopOverlayNativeSmoke.run', self_test)

    def test_incremental_generation_retires_only_its_owned_old_renderer_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            origin = enhancement.BASE + 'feature/anime4k/gl/Fsr1Shaders.kt'
            owned = output / 'com/bilipai/desktop/player/DesktopFsrShaderLicense.kt'
            owned.parent.mkdir(parents=True)
            owned.write_text(f'// GENERATED from {origin}; do not edit.\nold generated leaf\n', encoding='utf-8')
            foreign = output / 'com/android/purebilibili/feature/anime4k/gl/DesktopAnime4KShaderList.kt'
            foreign.parent.mkdir(parents=True)
            foreign.write_bytes(b'// foreign source is retained\n')
            before = foreign.read_bytes()
            plugins.prune_retired_renderer_outputs(ROOT, output)
            self.assertFalse(owned.exists())
            self.assertEqual(before, foreign.read_bytes())
            with self.assertRaises(ValueError):
                plugins.checked_output(output, output / '..' / 'outside.kt')


if __name__ == '__main__':
    unittest.main()
