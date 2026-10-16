import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

TOOL = Path(__file__).resolve().parents[1] / 'validate-source-cohort.py'
SPEC = importlib.util.spec_from_file_location('source_cohort', TOOL)
cohort = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cohort)


class SourceCohortChangeInventoryTest(unittest.TestCase):
    def plan(self, **extra):
        return {'sources': [{'path': 'changed.kt'}, {'path': 'retained-test.kt'}], **extra}

    def test_exact_git_delta_can_pin_unchanged_regression_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            (repo / 'changed.kt').write_text('before\n', encoding='utf-8')
            (repo / 'retained-test.kt').write_text('retained\n', encoding='utf-8')
            git('add', '.')
            git('commit', '-qm', 'base')
            base = git('rev-parse', 'HEAD')
            (repo / 'changed.kt').write_text('after\n', encoding='utf-8')
            (repo / '.github').mkdir()
            (repo / cohort.PLAN).write_text('{}\n', encoding='utf-8')
            git('add', '.')
            git('commit', '-qm', 'candidate')
            changed = set(git('diff', '--name-only', base, 'HEAD').splitlines())
            cohort.assert_change_inventory(changed, self.plan(changedPaths=['changed.kt']))
            with self.assertRaises(AssertionError):
                cohort.assert_change_inventory(changed, self.plan())

    def test_undeclared_mutation_is_rejected_even_when_pinned(self):
        with self.assertRaises(AssertionError):
            cohort.assert_change_inventory({'changed.kt', 'retained-test.kt', cohort.PLAN},
                                           self.plan(changedPaths=['changed.kt']))

    def test_declared_but_unchanged_path_is_rejected(self):
        with self.assertRaises(AssertionError):
            cohort.assert_change_inventory({'changed.kt', cohort.PLAN},
                                           self.plan(changedPaths=['changed.kt', 'retained-test.kt']))

    def test_unpinned_declared_path_is_rejected(self):
        with self.assertRaises(AssertionError):
            cohort.changed_source_paths(self.plan(changedPaths=['outside.kt']))

    def test_duplicate_and_non_string_change_entries_are_rejected(self):
        for paths in (['changed.kt', 'changed.kt'], [None], 'changed.kt', []):
            with self.subTest(paths=paths), self.assertRaises(AssertionError):
                cohort.changed_source_paths(self.plan(changedPaths=paths))

    def test_plan_itself_cannot_be_declared_as_source(self):
        with self.assertRaises(AssertionError):
            cohort.changed_source_paths({'sources': [{'path': cohort.PLAN}], 'changedPaths': [cohort.PLAN]})

    def test_legacy_cohort_still_requires_every_pinned_path_to_change(self):
        cohort.assert_change_inventory({'changed.kt', 'retained-test.kt', cohort.PLAN}, self.plan())

    def test_export_flags_append_only_input_capture_tasks_to_one_command(self):
        for updater in (False, True):
            for ui in (False, True):
                with self.subTest(updater=updater, ui=ui):
                    args = cohort.harness_input_arguments(dict(exportPackagedUpdaterHarness=updater,
                        exportOriginalRootUiHarness=ui), Path('repo'), Path('output'))
                    self.assertEqual(args.count('--init-script'), int(updater) + int(ui))
                    self.assertEqual('writePackagedUpdaterHarnessInputs' in args, updater)
                    self.assertEqual('writeOriginalRootUiHarnessInputs' in args, ui)
                    self.assertNotIn('windowsVideoLocalReplayUiSmoke', args)

    def test_truthy_strings_cannot_enable_harness_exports(self):
        self.assertEqual([], cohort.harness_input_arguments(dict(exportPackagedUpdaterHarness='true',
            exportOriginalRootUiHarness=1), Path('repo'), Path('output')))

    def test_export_flags_are_published_independently_without_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            for updater in (None, {}):
                for ui in (None, {}):
                    path = Path(tmp) / ('flags-' + str(updater is not None) + '-' + str(ui is not None))
                    path.write_text('existing=value\n', encoding='utf-8')
                    cohort.write_harness_flags(path, True, updater, ui)
                    expected = 'existing=value\n'
                    if updater is not None: expected += 'updater_harness_exported=true\n'
                    if ui is not None: expected += 'original_root_ui_harness_exported=true\n'
                    self.assertEqual(path.read_text(), expected)

    def test_failed_source_validation_never_publishes_either_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'flags'
            cohort.write_harness_flags(path, False, {}, {})
            self.assertFalse(path.exists())


if __name__ == '__main__':
    unittest.main()
