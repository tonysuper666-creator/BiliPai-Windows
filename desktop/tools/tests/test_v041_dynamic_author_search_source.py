from pathlib import Path
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v041_dynamic_author_search as source


class V041DynamicAuthorSearchSourceTest(unittest.TestCase):
    def producer(self):
        spec = importlib.util.spec_from_file_location('v041_actual_author_tabs', REPO / 'desktop/tools/extract-upstream-dynamic-tabs.py')
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        return module

    def test_real_tabs_producer_emits_whole_original_dialog_and_policy_once_and_deterministically(self):
        producer = self.producer(); originals = source.main_sources(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp); first = producer.generate(REPO, output)
            before = {p.relative_to(output).as_posix(): p.read_bytes() for p in first}
            second = producer.generate(REPO, output)
            self.assertEqual(first, second); self.assertEqual(len(first), len(set(first)))
            self.assertEqual(before, {p.relative_to(output).as_posix(): p.read_bytes() for p in second})
            rows = {row['path']: row for row in producer.inventory(REPO)}
            for path, original in originals.items():
                with self.subTest(path=path):
                    matches = list(output.rglob(Path(path).name)); self.assertEqual(1, len(matches))
                    lines = matches[0].read_text(encoding='utf-8').split('\n', 2)
                    self.assertEqual(original.strip() + '\n', lines[2])
                    self.assertIn(hashlib.sha256(original.encode()).hexdigest(), lines[1])
                    self.assertEqual(source.COMMIT, rows[path]['upstreamCommit'])
                    self.assertEqual('extracted', rows[path]['mode'])
            self.assertEqual([], list(output.rglob('*AuthorSearch*Test.kt')))
            self.assertEqual([], list(output.rglob('DynamicViewModel.kt')))
            self.assertEqual([], list(output.rglob('DynamicScreen.kt')))

    def test_manifest_reference_originals_and_existing_policy_tests_reject_changed_or_missing_bytes(self):
        archives = [source.ARCHIVE, 'desktop/upstream-slices/v041-rss-dynamic']
        leaves = [source.ARCHIVE + '/' + leaf for leaf in ['manifest.json', 'DynamicAuthorSearchDialog.kt', 'DynamicScreen.kt', 'DynamicViewModel.kt']]
        leaves += ['desktop/upstream-slices/v041-rss-dynamic/' + leaf for leaf in ['DynamicAuthorSearchPolicy.kt', 'DynamicAuthorSearchPolicyTest.kt']]
        for leaf in leaves:
            for changed in [True, False]:
                with self.subTest(leaf=leaf, changed=changed), tempfile.TemporaryDirectory() as tmp:
                    repo = Path(tmp)
                    for archive in archives: shutil.copytree(REPO / archive, repo / archive)
                    path = repo / leaf
                    if changed: path.write_bytes(path.read_bytes() + b'\n')
                    else: path.unlink()
                    with self.assertRaises((ValueError, FileNotFoundError)): source.main_sources(repo)

    def test_three_complete_original_policy_cases_are_generated_only_in_the_real_test_output(self):
        producer = self.producer(); original = source.original_test(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp); first = producer.generate_tests(REPO, output)
            self.assertEqual(first, producer.generate_tests(REPO, output)); self.assertEqual(1, len(first))
            body = first[0].read_text(encoding='utf-8').split('\n', 2)[2]
            self.assertEqual(original.strip() + '\n', body)
            self.assertEqual(3, body.count('@Test'))
            self.assertEqual(1, body.count('class DynamicAuthorSearchPolicyTest'))

    def test_complete_original_prepare_method_is_retained_in_the_actual_windows_owner(self):
        method = source.prepare_method(REPO)
        actual = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicUsersState.kt').read_text(encoding='utf-8')
        self.assertEqual(1, actual.count(method))
        self.assertIn('requestCompleteFollowingsLoad()', method)
        self.assertEqual(1, method.count('fun prepareAuthorSearch()'))


if __name__ == '__main__': unittest.main()
