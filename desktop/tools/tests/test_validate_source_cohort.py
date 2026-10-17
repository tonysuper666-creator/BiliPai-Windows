import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import sys
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


class SourceCohortFileSelectionTest(unittest.TestCase):
    def plan(self, count=103):
        return dict(schemaVersion=1, repository=cohort.REPOSITORY,
            classes={'com.bilipai.desktop.regression.SourceCohortActualProductionClass'+str(i): 1 for i in range(count)},
            expectedTests=count, exportPackagedUpdaterHarness=True, exportOriginalRootUiHarness=True)

    def write_plan(self, repo, plan):
        p=repo/cohort.PLAN
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(plan, indent=2)+'\n', encoding='utf-8', newline='\n')
        return p

    def test_large_exact_cohort_stays_out_of_the_real_batch_command_budget(self):
        with tempfile.TemporaryDirectory(prefix='bp-cohort-command-') as tmp:
            repo=Path(tmp);plan=self.plan(5000);p=self.write_plan(repo,plan)
            command=cohort.gradle_command(plan,repo,repo/'output with spaces',repo/'jdk with spaces')
            self.assertLess(len(subprocess.list2cmdline(command)),4096)
            self.assertGreater(len(subprocess.list2cmdline([arg for name in plan['classes'] for arg in ['--tests',name]])),8191)
            self.assertNotIn('--tests',command)
            self.assertEqual(3,command.count('--init-script'))
            self.assertEqual(1,command.count('compileKotlin'))
            self.assertEqual(1,command.count('compileTestKotlin'))
            self.assertEqual(1,command.count('test'))
            self.assertIn('-PbilipaiSourceCohortPlan='+str(p.resolve()),command)
            self.assertIn('-PbilipaiSourceCohortPlanSHA256='+hashlib.sha256(p.read_bytes()).hexdigest(),command)

    @unittest.skipUnless(sys.platform == 'win32', 'Real Windows batch boundary')
    def test_real_cmd_batch_receives_the_entire_exact_plan_without_java_or_class_expansion(self):
        with tempfile.TemporaryDirectory(prefix='bp-cohort-batch-') as tmp:
            root=Path(tmp);repo=root/'repository with spaces';plan=self.plan(5000);p=self.write_plan(repo,plan)
            recorder=root/'record.py'
            recorder.write_text('''import sys,json,pathlib,hashlib
args=sys.argv[1:]
path=next(x.split('=',1)[1] for x in args if x.startswith('-PbilipaiSourceCohortPlan='))
raw=pathlib.Path(path).read_bytes()
print(json.dumps(dict(args=args,classes=json.loads(raw)['classes'],sha256=hashlib.sha256(raw).hexdigest())))
''',encoding='utf-8',newline='\n')
            wrapper=root/'gradlew.bat'
            wrapper.write_bytes(('@echo off\r\n"'+sys.executable+'" -X utf8 -B "'+str(recorder)+'" %*\r\nexit /b %errorlevel%\r\n').encode('utf-8'))
            command=cohort.gradle_command(plan,repo,root/'output with spaces',root/'jdk with spaces')
            command[3]=str(wrapper)  # This owned fixture records argv; it never starts Gradle or Java.
            done=subprocess.run(command,cwd=root,capture_output=True,check=True,text=True,encoding='utf-8')
            actual=json.loads(done.stdout)
            self.assertEqual(plan['classes'],actual['classes'])
            self.assertEqual(hashlib.sha256(p.read_bytes()).hexdigest(),actual['sha256'])
            self.assertEqual(command[4:],actual['args'])
            self.assertNotIn('--tests',actual['args'])

    def test_changed_plan_is_rejected_before_the_wrapper_can_start(self):
        with tempfile.TemporaryDirectory(prefix='bp-cohort-changed-') as tmp:
            repo=Path(tmp);plan=self.plan();changed=dict(plan,expectedTests=999)
            self.write_plan(repo,changed)
            with self.assertRaisesRegex(AssertionError,'plan changed before launch'):
                cohort.gradle_command(plan,repo,repo/'output',repo/'jdk')

    def test_wildcards_invalid_counts_and_changed_totals_cannot_broaden_selection(self):
        for classes, total in [({'com.bilipai.*':1},1),({'com.bilipai.Test':True},1),
                               ({'com.bilipai.Test':0},0),({'com.bilipai.Test':1},2),({},0)]:
            with self.subTest(classes=classes,total=total),tempfile.TemporaryDirectory(prefix='bp-cohort-invalid-') as tmp:
                repo=Path(tmp);plan=dict(self.plan(),classes=classes,expectedTests=total);self.write_plan(repo,plan)
                with self.assertRaises(AssertionError):
                    cohort.cohort_filter_arguments(plan,repo)


class SourceCohortProductionVersionTest(unittest.TestCase):
    def fixture(self, root):
        from v025_source_paths import canonical_source, canonical_relative
        repo = Path(root)
        real_repo = TOOL.parents[2]
        relative = canonical_relative(real_repo, 'app/build.gradle.kts')
        build = repo/relative; build.parent.mkdir(parents=True)
        build.write_bytes(canonical_source(real_repo, 'app/build.gradle.kts').read_bytes())
        meta = repo/'desktop/upstream-sources.json';meta.parent.mkdir(parents=True, exist_ok=True)
        meta.write_text(json.dumps(dict(windowsRevision='195')), encoding='utf8')
        props = repo/'desktop/gradle.properties'
        props.write_text('org.gradle.workers.max=1\norg.gradle.parallel=false\norg.gradle.daemon=false\nkotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx4g\n', encoding='utf8')
        plan = dict(schemaVersion=1, repository=cohort.REPOSITORY, cloudGradleHeapMaximum=cohort.CLOUD_GRADLE_HEAP,
            classes={'com.bilipai.synthetic.Only':1}, expectedTests=1,
            windowsVersion=cohort.actual_windows_version(repo),
            sources=[dict(path=name, normalization='raw', sha256='') for name in [relative, 'desktop/upstream-sources.json', 'desktop/gradle.properties']])
        self.save(repo, plan)
        return repo, plan

    def save(self, repo, plan):
        for row in plan['sources']: row['sha256']=hashlib.sha256((repo/row['path']).read_bytes()).hexdigest()
        path=repo/cohort.PLAN;path.parent.mkdir(parents=True, exist_ok=True);path.write_text(json.dumps(plan), encoding='utf8')

    def test_real_canonical_build_and_actual_revision_match_without_subprocess(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp)
            with patch.object(cohort.subprocess,'run') as child:
                self.assertEqual(plan,cohort.check(repo))
            child.assert_not_called()
            self.assertTrue(plan['windowsVersion'].endswith('.195'))

    def test_plan_only_revision_change_is_rejected_before_any_build(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp);plan['windowsVersion']=plan['windowsVersion'].rsplit('.',1)[0]+'.196';self.save(repo,plan)
            with patch.object(cohort.subprocess,'run') as child, self.assertRaisesRegex(AssertionError,'Actual production Windows version differs'):
                cohort.check(repo)
            child.assert_not_called()

    def test_manifest_only_revision_change_is_rejected_even_with_updated_input_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp)
            (repo/'desktop/upstream-sources.json').write_text(json.dumps(dict(windowsRevision='196')),encoding='utf8');self.save(repo,plan)
            with self.assertRaisesRegex(AssertionError,'Actual production Windows version differs'):cohort.check(repo)

    def test_missing_or_null_revision_uses_the_same_existing_gradle_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp)
            for meta in [{},dict(windowsRevision=None)]:
                (repo/'desktop/upstream-sources.json').write_text(json.dumps(meta),encoding='utf8')
                self.assertTrue(cohort.actual_windows_version(repo).endswith('.1'))

    def test_malformed_or_out_of_range_revision_cannot_reach_resource_generation(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp)
            for value in [False,1.5,0,-1,65536,'one','1.0','']:
                with self.subTest(value=value):
                    (repo/'desktop/upstream-sources.json').write_text(json.dumps(dict(windowsRevision=value)),encoding='utf8')
                    with self.assertRaisesRegex(AssertionError,'Invalid production Windows revision'):cohort.actual_windows_version(repo)

    def test_changed_original_version_file_cannot_be_admitted_by_repinning_the_cohort(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp);build=repo/plan['sources'][0]['path'];build.write_bytes(build.read_bytes()+b'\n// foreign original\n');self.save(repo,plan)
            with self.assertRaisesRegex(ValueError,'digest mismatch'):cohort.check(repo)

    def test_actual_run_entry_rejects_version_drift_before_environment_or_wrapper(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            repo,plan=self.fixture(tmp);plan['windowsVersion']='0.2.1.1';self.save(repo,plan);output=repo/'must-not-exist'
            with patch.object(sys,'argv',['cohort','--run','--repo',str(repo),'--output',str(output)]),patch.object(cohort.subprocess,'run') as child:
                with self.assertRaisesRegex(AssertionError,'Actual production Windows version differs'):cohort.main()
            child.assert_not_called();self.assertFalse(output.exists())


class SourceCohortExportFailureReceiptTest(unittest.TestCase):
    def test_success_returns_only_the_actual_receipt_and_calls_exporter_once(self):
        calls=[];actual=dict(schema=1,sourceSha='synthetic-only')
        def action():calls.append(1);return actual
        passed,receipt,error=cohort.guarded_harness_export('original-root-ui',action)
        self.assertTrue(passed);self.assertIs(receipt,actual);self.assertIsNone(error);self.assertEqual([1],calls)

    def test_failed_or_missing_export_receipt_stays_failed_and_never_publishes_flags(self):
        import contextlib,io
        for missing in [False,True]:
            with self.subTest(missing=missing),tempfile.TemporaryDirectory() as tmp:
                def action():
                    if missing:return None
                    raise ValueError('injected export failure')
                with contextlib.redirect_stderr(io.StringIO()) as trace:
                    passed,receipt,error=cohort.guarded_harness_export('original-root-ui',action)
                self.assertFalse(passed);self.assertIsNone(receipt);self.assertEqual('original-root-ui',error['stage'])
                self.assertIn(error['errorType'],['ValueError','AssertionError']);self.assertTrue(trace.getvalue())
                path=Path(tmp)/'flags';cohort.write_harness_flags(path,passed,{},receipt);self.assertFalse(path.exists())

    def test_process_interruption_is_never_swallowed_as_an_export_receipt(self):
        def action():raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):cohort.guarded_harness_export('original-root-ui',action)


if __name__ == '__main__':
    unittest.main()
