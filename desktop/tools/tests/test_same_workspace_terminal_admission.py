"""Terminal metadata/XML and byte fixtures; never execute Java or claim a cloud run."""
from pathlib import Path
import copy
import json
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import original_root_ui_runtime_image as image
import original_root_ui_packaged_native as native
import test_original_root_ui_runtime_image as runtime_fixture
import test_original_root_ui_packaged_native as native_fixture
import test_package_source_qualification as source_fixture


class SameWorkspaceTerminalAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.runtime = runtime_fixture.RuntimeImageTests(
            'test_exact_packaged_module_image_and_fixed_launcher_form_private_runtime')
        self.runtime.setUp()
        self.addCleanup(self.runtime.doCleanups)
        self.source = source_fixture.PackageSourceQualificationTest(
            'test_successful_fresh_source_checks_do_not_claim_package_launch_or_hardware')
        self.source.setUp()
        self.source.ui_export()
        self.source.plan.update(repository=image.REPOSITORY, windowsVersion=self.runtime.production['windowsVersion'])
        self.source.payload['receipt']['windowsVersion'] = self.source.plan['windowsVersion']
        self.source.run.update(event='push', head_branch='desktop/source-validation-package-fixture')
        self.source.jobs['jobs'][0].update(runner_group_name='GitHub Actions', steps=[
            dict(name=name, status='completed', conclusion='success') for name in (
                'Prepare one provisional candidate from the same tested workspace',
                'Export provisional same-workspace image for terminal-run admission')])

    def plan_raw(self):
        return json.dumps(self.source.plan).encode('utf-8')

    def log(self):
        return self.source.log() + self.runtime.log

    def verify(self, *, plan=True):
        return image.verify_package_evidence(self.source.run, self.source.jobs, self.log(), self.runtime.raw,
            self.source.sha, self.runtime.production['windowsVersion'],
            source_plan_raw=self.plan_raw() if plan else None)

    def provision(self):
        r = self.runtime
        return image.provision_runtime(r.production, r.package, self.source.run, self.source.jobs,
            self.log(), r.raw, r.jdk, r.output, source_plan_raw=self.plan_raw())

    def test_exact_terminal_source_run_can_bind_the_same_packaged_runtime_without_execution(self):
        record, authority = self.verify()
        self.assertEqual(self.source.sha, record['sourceSha'])
        self.assertTrue(authority['sameWorkspaceTerminalRun'])
        proof = self.provision()
        self.assertTrue(proof['completeRuntimeByteBindingVerified'])
        self.assertEqual(self.runtime.runtime['lib/modules'], (self.runtime.output / 'lib/modules').read_bytes())
        for field in ('executionAuthorized', 'javaExecuted', 'applicationExecuted', 'uiRuntimeVerified',
                      'hardwareVerified', 'desktopReplaced'):
            self.assertFalse(proof[field])

    def test_source_only_or_unplanned_branch_cannot_opt_itself_into_candidate_admission(self):
        with self.assertRaises(ValueError):
            self.verify(plan=False)
        for field, value in [('event', 'workflow_dispatch'), ('head_branch', 'desktop/source-validation-fixture'),
                             ('path', '.github/workflows/other.yml')]:
            before = self.source.run[field]
            self.source.run[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify()
            self.source.run[field] = before

    def test_failed_or_unfinished_source_or_job_cannot_admit_an_existing_image(self):
        for target, edit in [(self.source.run, dict(status='in_progress')),
                             (self.source.run, dict(conclusion='failure')),
                             (self.source.jobs['jobs'][0], dict(status='in_progress')),
                             (self.source.jobs['jobs'][0], dict(conclusion='failure'))]:
            before = copy.deepcopy(target)
            target.update(edit)
            with self.subTest(edit=edit), self.assertRaises(ValueError):
                self.provision()
            target.clear()
            target.update(before)
        self.assertFalse(self.runtime.output.exists())

    def test_full_source_receipt_checks_still_reject_stale_failed_and_missing_actual_cases(self):
        original = copy.deepcopy(self.source.payload)
        variants = []
        value = copy.deepcopy(original)
        value['receipt']['suites'][0]['fresh'] = False
        variants.append(value)
        value = copy.deepcopy(original)
        value['rawXml']['sample.ActualTest'] = value['rawXml']['sample.ActualTest'].replace('failures="0"', 'failures="1"')
        variants.append(value)
        value = copy.deepcopy(original)
        value['rawXml']['sample.ActualTest'] = value['rawXml']['sample.ActualTest'].replace('<testcase name="second"/>', '')
        variants.append(value)
        value = copy.deepcopy(original)
        value['receipt']['sourceAfter'] = {}
        variants.append(value)
        for value in variants:
            self.source.payload = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.verify()
        self.source.payload = original

    def test_missing_changed_or_ambiguous_candidate_steps_are_rejected(self):
        job = self.source.jobs['jobs'][0]
        original = copy.deepcopy(job['steps'])
        for steps in [[], original[:1], original + original[:1],
                      [dict(original[0], conclusion='failure'), original[1]],
                      [original[0], dict(original[1], status='in_progress')]]:
            job['steps'] = steps
            with self.subTest(steps=steps), self.assertRaises(ValueError):
                self.verify()
        job['steps'] = original

    def test_private_foreign_or_paid_runner_metadata_remains_rejected(self):
        for target, field, value in [(self.source.run, 'repository', dict(full_name=image.REPOSITORY, private=True)),
                                    (self.source.run, 'repository', dict(full_name='jay3-yy/BiliPai', private=False)),
                                    (self.source.jobs['jobs'][0], 'labels', ['windows-16core']),
                                    (self.source.jobs['jobs'][0], 'runner_group_name', 'paid')]:
            before = copy.deepcopy(target[field])
            target[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.verify()
            target[field] = before

    def test_export_plan_and_toolchain_must_match_the_terminal_source(self):
        original = copy.deepcopy(self.source.plan)
        for edit in [dict(repository='other/repository'), dict(windowsVersion='0.2.427.999'),
                     dict(exportOriginalRootUiHarness=False), dict(sources=[])]:
            self.source.plan = dict(original, **edit)
            with self.subTest(edit=edit), self.assertRaises(ValueError):
                self.verify()
        self.source.plan = original
        self.runtime.raw += b' '
        with self.assertRaises(ValueError):
            self.verify()

    def test_main_image_bytes_and_fixed_jdk_are_still_required_after_terminal_admission(self):
        (self.runtime.candidate / 'runtime/lib/modules').write_bytes(b'changed after packaging')
        with self.assertRaisesRegex(ValueError, 'runtime bytes differ'):
            self.provision()
        self.assertFalse(self.runtime.output.exists())

    def test_duplicate_source_jobs_and_unsealed_receipts_are_not_accepted(self):
        job = copy.deepcopy(self.source.jobs['jobs'][0])
        self.source.jobs['jobs'].append(job)
        with self.assertRaises(ValueError):
            self.verify()
        self.source.jobs['jobs'].pop()
        self.source.payload['receipt']['passed'] = False
        with self.assertRaises(ValueError):
            self.verify()

    def test_same_workspace_plan_reaches_the_actual_native_resource_binding(self):
        fixture = native_fixture.PackagedNativeBindingTest(
            'test_all_native_binaries_notices_and_provenance_bind_to_exact_git_and_package')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.source.sha = fixture.source
        self.source.run['head_sha'] = fixture.source
        self.source.jobs['jobs'][0]['head_sha'] = fixture.source
        self.source.payload['receipt']['sourceSha'] = fixture.source
        self.source.payload['receipt']['originalRootUiHarnessExport']['sourceSha'] = fixture.source
        package = dict(zip=fixture.f.package, run=self.source.run, jobs=self.source.jobs,
            log=self.source.log() + fixture.f.log, toolchainRaw=fixture.f.raw, sourcePlanRaw=self.plan_raw())
        proof = native.bind_packaged_native(fixture.repo, fixture.source,
            self.source.plan['windowsVersion'], package, fixture.compiled)
        self.assertEqual(11, len(proof['resources']))
        self.assertFalse(proof['nativeCodeLoaded'])
        del package['sourcePlanRaw']
        with self.assertRaises(ValueError):
            native.bind_packaged_native(fixture.repo, fixture.source,
                self.source.plan['windowsVersion'], package, fixture.compiled)


if __name__ == '__main__':
    unittest.main()
