"""Replay both real shared-comment producers; prove the retained split and ownership."""
from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_comment_chrome as chrome


def owner(filename):
    spec = importlib.util.spec_from_file_location('chrome_'+filename.replace('-', '_'), TOOLS/filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OriginalCommentChromeSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.bgm = owner('extract-upstream-bgm-detail.py')
        cls.content = owner('extract-upstream-video-content-full.py')
        cls.sources = chrome.fixed(REPO)
        cls.generate(cls.root/'actual')
        with patch.object(chrome, 'advance_sort_source', side_effect=lambda repo, before, output, consumer: before):
            cls.generate(cls.root/'before')

    @classmethod
    def generate(cls, output):
        cls.bgm.generate(REPO, output/'bgm')
        cls.content.generate(REPO, output/'content')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_original_declarations_are_split_once_at_the_existing_boundary(self):
        source = self.sources['CommentSortFilterBar.kt']
        prefix, suffix = source.split(chrome.SPLIT)
        header = self.root/'actual/bgm/com/android/purebilibili/feature/video/ui/components/DesktopOriginalCommentSortHeader.kt'
        filter = self.root/'actual/content/com/android/purebilibili/feature/video/ui/components/DesktopOriginalCommentSortFilterBar.kt'
        self.assertEqual(prefix.replace('import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl\n', ''), header.read_text(encoding='utf8'))
        imports = source[:source.index('internal data class CommentSortSegmentedControlSpec')]
        self.assertEqual(imports+chrome.SPLIT+suffix, filter.read_text(encoding='utf8'))
        combined = header.read_text(encoding='utf8')+filter.read_text(encoding='utf8')
        for name in ['CommentListHeader', 'CommentListTitle', 'CommentSortHeader', 'CommentSortFilterBar', 'CommentSegmentedControl', 'resolveCommentSortSegmentedControlSpec', 'resolveCommentSortDockViewportOverflowDp']:
            self.assertEqual(1, combined.count('fun '+name+'('), name)

    def test_all_other_real_generated_production_units_remain_byte_identical(self):
        actual = {p.relative_to(self.root/'actual').as_posix(): p.read_bytes()
                  for folder in ['bgm/com', 'content/com'] for p in (self.root/'actual'/folder).rglob('*.kt')}
        before = {p.relative_to(self.root/'before').as_posix(): p.read_bytes()
                  for folder in ['bgm/com', 'content/com'] for p in (self.root/'before'/folder).rglob('*.kt')}
        self.assertEqual(actual.keys(), before.keys())
        self.assertEqual({name for name in actual if actual[name] != before[name]}, {
            'bgm/com/android/purebilibili/feature/video/ui/components/DesktopOriginalCommentSortHeader.kt',
            'content/com/android/purebilibili/feature/video/ui/components/DesktopOriginalCommentSortFilterBar.kt'})

    def test_original_archive_rejects_tampered_missing_or_extra_inputs(self):
        for name in ['CommentSortFilterBar.kt', 'CommentSortFilterBarPolicyTest.kt', 'LICENSE', 'manifest.json', 'extra.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                repo = Path(temp)
                shutil.copytree(REPO/chrome.ARCHIVE, repo/chrome.ARCHIVE)
                file = repo/chrome.ARCHIVE/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(AssertionError):
                    chrome.fixed(repo)
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            shutil.copytree(REPO/chrome.ARCHIVE, repo/chrome.ARCHIVE)
            (repo/chrome.ARCHIVE/'LICENSE').unlink()
            with self.assertRaises(AssertionError):
                chrome.fixed(repo)

    def test_predecessor_drift_and_archive_output_overlap_are_rejected(self):
        from v025_source_paths import canonical_source
        before = canonical_source(REPO, chrome.SORT_PATH).read_text(encoding='utf8')
        self.assertEqual(chrome.PREDECESSOR_SHA256, chrome.sha(before))
        for body, output, consumer in [(before+'\n', self.root/'bad', 'header'),
                                       (before, REPO/chrome.ARCHIVE, 'filter'),
                                       (before, self.root/'bad', 'duplicate')]:
            with self.assertRaises(AssertionError):
                chrome.advance_sort_source(REPO, body, output, consumer)

    def test_whole_original_six_case_suite_changes_only_its_class_and_fixture_paths(self):
        original = self.sources['CommentSortFilterBarPolicyTest.kt']
        actual = (self.root/'actual/bgm/test/com/android/purebilibili/feature/video/ui/components/V046CommentSortOriginalPolicyTest.kt').read_text(encoding='utf8')
        self.assertEqual(6, actual.count('@Test'))
        self.assertEqual(original[:original.index('    private fun loadSource')].replace(
            'class CommentSortFilterBarPolicyTest {', 'class V046CommentSortOriginalPolicyTest {'),
            actual[:actual.index('    private fun loadSource')])
        self.assertIn('File(normalizedPath.removePrefix("desktop/"))', actual)
        self.assertNotIn('sourceSets', actual)

    def test_real_task_inputs_and_catalog_cover_both_production_owners(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        for task in ['extractBgmDetail', 'extractOriginalVideoContentFull']:
            body = build.split('val '+task+' by tasks.registering(Exec::class) {', 1)[1].split('\n}', 1)[0]
            self.assertIn('inputs.file("tools/v046_comment_chrome.py")', body)
            self.assertIn('inputs.dir("upstream-slices/v046-comment-chrome")', body)
        self.assertIn('kotlin.srcDir(layout.buildDirectory.dir("generated/bgm-detail/test"))', build)
        catalog = json.loads((TOOLS/'compatibility-source-inputs.json').read_bytes())
        self.assertIn(chrome.ARCHIVE, catalog['buildInputRoots'])
        rows = [row for row in catalog['inputs'] if row['localPath'].startswith(chrome.ARCHIVE+'/')]
        self.assertEqual(2, len(rows))
        self.assertTrue(all(row['upstreamCommit'] == chrome.COMMIT and not row['referenceOnly'] for row in rows))

    def test_two_real_producers_are_deterministic_and_record_exact_source_identity(self):
        self.generate(self.root/'again')
        for owner in ['bgm', 'content']:
            for path in (self.root/'actual'/owner).rglob('*.kt'):
                self.assertEqual(path.read_bytes(), (self.root/'again'/owner/path.relative_to(self.root/'actual'/owner)).read_bytes())
        for owner, consumer in [('bgm', 'header'), ('content', 'filter')]:
            proof = json.loads((self.root/'actual'/owner/('v046-comment-chrome-'+consumer+'-selection.json')).read_bytes())
            self.assertEqual(chrome.COMMIT, proof['upstreamCommit'])
            self.assertEqual(chrome.sha(self.sources['CommentSortFilterBar.kt']), proof['selectedSHA256'])
            self.assertTrue(proof['completeOriginalDeclarations'])


if __name__ == '__main__':
    unittest.main()
