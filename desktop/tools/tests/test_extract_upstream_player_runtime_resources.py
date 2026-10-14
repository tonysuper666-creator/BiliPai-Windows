from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v037_player_runtime_resources as resources


class OriginalPlayerRuntimeResourcesTest(unittest.TestCase):
    def test_original_observer_inverse_preserves_input_passes_and_log_consent(self):
        with tempfile.TemporaryDirectory(prefix='bp-input-observer-') as directory:
            output = Path(directory)
            rows = []
            resources.emit(REPO, output, rows)
            actual = (output / rows[0]['path']).read_text(encoding='utf-8')
            proof = json.loads((output / 'v037-video-input-diagnostics-platform-proof.json').read_bytes())
            restored = actual
            for edit in reversed(proof['indexedEdits']):
                start = edit['afterOffset']
                self.assertEqual(restored[start:start + len(edit['after'])], edit['after'])
                restored = restored[:start] + edit['before'] + restored[start + len(edit['after']):]
            self.assertEqual(restored, resources.fixed_bytes(REPO, 'VideoInputDiagnostics.kt')[0].decode())
            self.assertIn('PointerEventPass.Initial', actual)
            self.assertIn('PointerEventPass.Final', actual)
            self.assertIn('Logger.areVerboseRuntimeLogsEnabled()', actual)
            self.assertNotIn('.consume()', actual)

    def test_changed_bitmap_cannot_be_emitted_as_the_original_resource(self):
        with tempfile.TemporaryDirectory(prefix='bp-player-icon-pins-') as directory:
            repo = Path(directory)
            shutil.copytree(REPO / resources.ARCHIVE, repo / resources.ARCHIVE)
            path = repo / resources.ARCHIVE / 'ic_pili_tv_play.png'
            path.write_bytes(path.read_bytes() + b'changed')
            with self.assertRaisesRegex(AssertionError, 'Fixed runtime resource bytes changed'):
                resources.fixed_bytes(repo, 'ic_pili_tv_play.png')

    def test_same_section_producer_uses_bitmap_values_and_existing_navigation_tuple(self):
        path = REPO / 'desktop/tools/extract-upstream-video-player-section-full.py'
        spec = importlib.util.spec_from_file_location('player_runtime_resource_producer', path)
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory(prefix='bp-player-runtime-') as directory:
            output = Path(directory)
            producer.generate(REPO, output)
            source = (output / 'com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt').read_text(encoding='utf-8')
            self.assertIn('painterResource("icons/ic_pili_tv_play.png")', source)
            self.assertNotIn('import com.android.purebilibili.R', source)
            self.assertIn('revealAlpha = playerSurfaceAlpha.value, revealScale = playerSurfaceScale.value', source)
            self.assertIn('onRelatedVideoClick(dialogTargetBvid, 0L, null)', source)
            observer = output / 'com/android/purebilibili/feature/video/screen/VideoInputDiagnostics.kt'
            self.assertTrue(observer.exists())
            build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
            self.assertIn('from("upstream-slices/v037-player-runtime-resources/ic_pili_tv_play.png") { into("icons") }', build)


if __name__ == '__main__':
    unittest.main()
