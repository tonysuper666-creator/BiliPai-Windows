import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v038_listen_viewmodel as model


class V038ListenViewModelSourceTest(unittest.TestCase):
    def test_complete_original_class_is_recovered_after_every_owner_platform_edit(self):
        original = model.read(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proof = model.emit(REPO, root)
            body = (root/proof['output']).read_text(encoding='utf-8')
            self.assertTrue(proof['wholeOriginalClassRetained'])
            self.assertEqual(4, proof['actualOriginalLaunchesOwned'])
            for edit in reversed(proof['edits']):
                i = edit['offset']
                self.assertEqual(edit['after'], body[i:i+len(edit['after'])])
                body = body[:i]+edit['before']+body[i+len(edit['after']):]
            self.assertEqual(original, body)

    def test_all_actual_async_jobs_capture_their_own_caller_and_publish_through_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proof = model.emit(REPO, root)
            body = (root/proof['output']).read_text(encoding='utf-8')
            for alias in ('desktopPreviewCaller', 'desktopRefreshCaller', 'desktopIndexCaller', 'desktopDetailCaller'):
                self.assertIn('val '+alias+' = environment.request(currentCoroutineContext())', body)
                self.assertIn(alias+'.check()', body)
                self.assertIn(alias+'.updateState(_uiState)', body)
            self.assertNotIn('_uiState.update {', body)
            self.assertNotIn('_uiState.value =', body)
            self.assertIn('desktopIndexCaller.commit { indexedResources = allResources }', body)
            self.assertIn('desktopIndexCaller.commit { indexedResources = desktopMappedResources }', body)
            self.assertNotIn('desktopIndexCaller.commit { indexedResources = withContext', body)

    def test_latest_incremental_and_background_functions_keep_the_original_read_and_mapping_algorithms(self):
        original = model.read(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proof = model.emit(REPO, root)
            body = (root/proof['output']).read_text(encoding='utf-8')
            self.assertEqual(original.count('withContext(Dispatchers.Default)'), body.count('withContext(Dispatchers.Default)'))
            for marker in ('fun pauseBackgroundLoading()', 'fun resumeBackgroundLoading()', 'loadedMid != currentMid()',
                           'if (backgroundPaused || isFoldersLoading || indexJob?.isActive == true || _uiState.value.isLoading) return',
                           'onResourcesIndexed = { resources ->', 'loader.loadPlaylistPreviewCovers(ownedFolders)'):
                self.assertIn(marker, body)
            self.assertNotIn('TokenManager', body)
            self.assertNotIn('ViewModel()', body)
            self.assertIn('environment.dataSource', body)
            self.assertIn('environment.scope', body)

    def test_unreviewed_state_machine_source_and_manifest_are_rejected(self):
        for leaf in ('ListenVideoViewModel.kt', 'manifest.json'):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)/model.ARCHIVE
                shutil.copytree(REPO/model.ARCHIVE, root)
                path = root/leaf
                path.write_bytes(path.read_bytes()+b'\n')
                with self.assertRaises(ValueError): model.read(Path(tmp))

    def test_actual_screen_lifecycle_uses_the_same_retained_page_and_has_a_complete_inverse(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('listen_lifecycle95', REPO/'desktop/tools/extract-upstream-listen-video-root.py')
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            producer.produce(REPO, root)
            receipts = json.loads((root/'listen-original-source-receipt.json').read_text(encoding='utf-8'))
            receipt = next(r for r in receipts if r['source'].endswith('/ListenVideoScreen.kt'))
            body = (root/receipt['output']).read_text(encoding='utf-8')
            self.assertIn('page.runUi { viewModel.resumeBackgroundLoading() }', body)
            self.assertIn('currentCoroutineContext().ensureActive()', body)
            self.assertIn('if (!page.entry.owns()) return@LaunchedEffect', body)
            self.assertIn('onDispose {', body)
            row = next(r for r in producer.RECIPES if r['originalPath']==receipt['source'])
            body = body.split('\n',2)[2]
            for edit in reversed(receipt['lifecycleAdaptation']['edits']):
                i = edit['offset']
                self.assertEqual(edit['after'], body[i:i+len(edit['after'])])
                body = body[:i]+edit['before']+body[i+len(edit['after']):]
            self.assertEqual(row['adaptedSha256LF'], producer.sha(body))
            for edit in reversed(row['edits']):
                i = edit['offset']
                self.assertEqual(edit['after'], body[i:i+len(edit['after'])])
                body = body[:i]+edit['before']+body[i+len(edit['after']):]
            self.assertEqual(producer.read(REPO,row['originalPath']), body)


if __name__ == '__main__':
    unittest.main()
