import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v038_audio_library as library


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class V038AudioLibrarySourceTest(unittest.TestCase):
    def test_existing_audio_producer_emits_complete_latest_loader_and_interface(self):
        audio = module('audio93', REPO / 'desktop/tools/extract-upstream-audio.py')
        original = library.fixed_sources(REPO)['ListenVideoLibraryDataSource.kt']
        platform = audio.section(original, 'internal class BilibiliListenVideoLibraryDataSource',
                                 'internal class ListenVideoLibraryLoader')
        expected = original.replace(platform, '').replace(
            'import com.android.purebilibili.data.repository.FavoriteRepository\n', '')
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'audio'
            outputs = audio.generate(REPO, output)
            target = next(p for p in outputs if p.name == 'DesktopListenVideoLibraryLoader.kt')
            body = target.read_text(encoding='utf-8')
            self.assertEqual(expected.rstrip()+'\n', '\n'.join(body.split('\n')[2:]))
            self.assertIn('onPageLoaded(resources.values.toList())', body)
            self.assertIn('folderPages.remove(mediaId to lastRequestedPage)', body)
            self.assertIn('albumPages.remove(seasonId to lastRequestedPage)', body)
            self.assertFalse(any(output.rglob('*Test.kt')))
            proof = json.loads((output / 'v038-audio-library-source-proof.json').read_text(encoding='utf-8'))
            self.assertTrue(proof['completeLoaderAndInterfaceRetained'])
            self.assertFalse(proof['collectionPlayerUiMounted'])

    def test_eleven_loader_tests_have_complete_inverse_to_all_fifteen_original_tests(self):
        originals = library.fixed_sources(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'audio'
            proof = library.emit_loader_tests(REPO, output)
            target = Path(tmp) / 'audio-library-original-tests/com/android/purebilibili/feature/audio/library/DesktopV038ListenVideoLibraryLoaderTest.kt'
            body = target.read_text(encoding='utf-8')
            self.assertEqual(11, body.count('    @Test\n'))
            self.assertNotIn('ListenVideoViewModel(', body)
            for edit in reversed(proof['adaptations']):
                i = edit['offset']
                self.assertEqual(edit['after'], body[i:i+len(edit['after'])])
                body = body[:i]+edit['before']+body[i+len(edit['after']):]
            self.assertEqual(originals['ListenVideoLibraryLoaderTest.kt'], body)
            self.assertEqual(4, len(proof['deferredVmCases']))

    def test_actual_retained_windows_vm_names_only_its_existing_progress_callback(self):
        listen = module('listen93', REPO / 'desktop/tools/extract-upstream-listen-video-root.py')
        with tempfile.TemporaryDirectory() as tmp:
            listen.produce(REPO, Path(tmp))
            root = Path(tmp)
            proof = json.loads((root / 'listen-original-source-receipt.json').read_text(encoding='utf-8'))
            receipt = next(r for r in proof if r['source'].endswith('/ListenVideoViewModel.kt'))
            edit = receipt['loaderCompatibilityAdaptation']
            self.assertTrue(edit['completeInverse'])
            actual = (root / receipt['output']).read_text(encoding='utf-8')
            self.assertIn('loader.indexFolders(targets, onFolderIndexed = { completed, total ->', actual)
            self.assertIn('current.updateState(_uiState)', actual)
            self.assertIn('current.commit {', actual)
            self.assertNotIn('onResourcesIndexed =', actual)

    def test_changed_pagination_policy_or_manifest_is_rejected(self):
        for leaf in ('ListenVideoLibraryDataSource.kt', 'manifest.json'):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp) / library.ARCHIVE
                shutil.copytree(REPO / library.ARCHIVE, root)
                path = root / leaf
                path.write_bytes(path.read_bytes()+b'\n')
                with self.assertRaises(ValueError): library.fixed_sources(Path(tmp))

    def test_unknown_original_callback_shape_is_rejected(self):
        with self.assertRaises(ValueError): library.adapt_legacy_index_callback('loader.indexFolders(other) {}')


if __name__ == '__main__':
    unittest.main()
