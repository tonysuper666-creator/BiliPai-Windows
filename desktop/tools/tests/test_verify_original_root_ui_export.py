"""Synthetic CI envelopes and structural classes only; no real CI/JVM/UI acceptance."""
from pathlib import Path
from unittest.mock import patch
from contextlib import contextmanager
import base64
import copy
import gzip
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import unittest
import zipfile
TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location('original_ui_export_readback', TOOLS / 'verify-original-root-ui-harness-export.py')
tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(tool)
spec = importlib.util.spec_from_file_location('structural_ui_export_fixture', Path(__file__).with_name('test_export_original_root_ui_harness.py'))
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)


class UiExportReadbackTests(unittest.TestCase):
    def setUp(self):
        self.f = fixture.ExportAdmissionTests()
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        build = 'desktop/build.gradle.kts'
        self.plan = dict(self.f.plan, baseHead='b' * 40, expectedTests=1, classes={'sample.StructuralOnlyTest': 1},
            sources=[dict(path=build, normalization='lf', sha256=tool.jars.file_digest(self.f.repo / build)['sha256'])])
        self.plan_raw = (json.dumps(self.plan, indent=2) + '\n').encode()
        (self.f.repo / '.github/windows-source-validation-plan.json').write_bytes(self.plan_raw)
        self.f.git('add', '--', '.github/windows-source-validation-plan.json')
        self.f.git('-c', 'user.name=Boundary Fixture', '-c', 'user.email=fixture@invalid.local',
            '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'Structural readback plan')
        self.f.sha = self.f.git('rev-parse', 'HEAD').decode().strip()
        self.f.model.update(sourceSha=self.f.sha, planSha256=tool.digest(self.plan_raw))
        self.manifest = self.f.run_export()
        self.root = self.f.root / 'export'
        self.run = dict(id=1, repository=dict(full_name=tool.gate.REPOSITORY, private=False), head_sha=self.f.sha,
            head_branch='desktop/source-validation-structural-fixture', status='completed', conclusion='success',
            path='.github/workflows/windows-source-validation.yml')
        self.jobs = {'jobs': [dict(id=2, run_id=1, head_sha=self.f.sha, name='source-validation',
            status='completed', conclusion='success', labels=['windows-latest'])]}
        pins = {row['path']: row['sha256'] for row in self.plan['sources']}
        self.payload = dict(receipt=dict(passed=True, exitCode=0, sourceSha=self.f.sha, baseHead=self.plan['baseHead'],
            windowsVersion=self.plan['windowsVersion'], sourceUnchanged=True, sourceBefore=pins, sourceAfter=pins,
            expectedTests=1, suites=[dict(name='sample.StructuralOnlyTest', fresh=True, backgroundError=False)],
            originalRootUiHarnessExport=dict(schema=1, sourceSha=self.f.sha,
                manifestSha256=tool.jars.file_digest(self.root / 'manifest.json')['sha256'],
                payloadFiles=len(self.manifest['payload']), payloadBytes=sum(row['bytes'] for row in self.manifest['payload']),
                productionRuntimeBindingRequired=True, productionClassesExported=False,
                applicationExecuted=False, hardwareExecuted=False)),
            rawXml={'sample.StructuralOnlyTest': '<testsuite name="sample.StructuralOnlyTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="structural-only"/></testsuite>'})

    def log(self):
        raw = json.dumps(self.payload).encode()
        return 'BILIPAI_SOURCE_RECEIPT_BEGIN ' + tool.digest(raw) + '\n' + base64.b64encode(gzip.compress(raw)).decode() + '\nBILIPAI_SOURCE_RECEIPT_END\n'

    def verify(self):
        return tool.verify_export(self.root, self.run, self.jobs, self.log(), self.plan_raw, self.f.sha)

    def reject(self, message):
        with self.assertRaisesRegex(ValueError, message): self.verify()

    def reseal(self, *changed_payloads):
        for name in changed_payloads:
            measured = tool.jars.file_digest(self.root / name)
            next(row for row in self.manifest['payload'] if row['path'] == name).update(measured)
        (self.root / 'manifest.json').write_bytes((json.dumps(self.manifest, indent=2) + '\n').encode())
        sealed = self.payload['receipt']['originalRootUiHarnessExport']
        sealed.update(manifestSha256=tool.jars.file_digest(self.root / 'manifest.json')['sha256'],
            payloadFiles=len(self.manifest['payload']), payloadBytes=sum(row['bytes'] for row in self.manifest['payload']))

    def modify_json(self, name, change):
        value = json.loads((self.root / name).read_bytes())
        change(value)
        (self.root / name).write_bytes((json.dumps(value, indent=2) + '\n').encode())
        self.reseal(name)

    def test_valid_structural_readback_requires_future_candidate_and_jdk_binding(self):
        proof = self.verify()['proof']
        self.assertTrue(proof['originalRootUiHarnessReadbackVerified'])
        for key in ('candidateRuntimeBound', 'actualJdkBound', 'applicationExecuted', 'uiRuntimeVerified', 'releaseQualified'):
            self.assertIs(proof[key], False)

    def test_failed_source_run_cannot_be_replaced_by_an_export(self):
        self.run['conclusion'] = 'failure'
        self.reject('run is not successful')

    def test_manifest_must_match_sealed_ci_digest(self):
        (self.root / 'manifest.json').write_bytes(b'{}')
        self.reject('not the sealed CI result')

    def test_export_from_another_source_is_rejected_even_after_resealing(self):
        self.manifest['sourceSha'] = '0' * 40
        self.reseal()
        self.reject('source/version/plan differs')

    def test_export_cannot_claim_application_execution(self):
        self.manifest['applicationExecuted'] = True
        self.reseal()
        self.reject('cannot claim runtime')

    def test_extra_payload_file_is_rejected(self):
        (self.root / 'unlisted.txt').write_bytes(b'extra')
        self.reject('actual inventory differs')

    def test_changed_dependency_bytes_are_rejected(self):
        (self.root / 'lib/00-test-api.jar').write_bytes(b'changed')
        self.reject('payload bytes differ')

    def test_duplicate_payload_path_is_rejected(self):
        self.manifest['payload'].append(copy.deepcopy(self.manifest['payload'][0]))
        self.reseal()
        self.reject('duplicate UI payload')

    def test_payload_traversal_is_rejected(self):
        self.manifest['payload'][0]['path'] = '../escape.jar'
        self.reseal()
        self.reject('Invalid export entry path')

    def test_runtime_model_source_must_match_the_same_export(self):
        self.modify_json('runtime-model.json', lambda value: value.update(sourceSha='0' * 40))
        self.reject('runtime model identity differs')

    def test_boolean_cannot_stand_in_for_runtime_model_schema(self):
        self.modify_json('runtime-model.json', lambda value: value.update(schema=True))
        self.reject('runtime model identity differs')

    def test_compiler_identity_must_match_the_frozen_build_pin(self):
        self.manifest['compiler']['buildScriptSha256'] = '0' * 64
        self.modify_json('runtime-model.json', lambda value: value['compiler'].update(buildScriptSha256='0' * 64))
        self.reject('Compiler/JDK model differs')

    def test_test_sources_cannot_differ_from_actual_runtime_model(self):
        self.modify_json('test-sources.json', lambda value: value[0].update(sha256='0' * 64))
        self.reject('test source snapshot differs')

    def test_missing_production_main_is_rejected(self):
        self.modify_json('production-ledger.json', lambda value: value['mainOutput'].pop(tool.jars.MAIN))
        self.reject('Main/version ledger is absent')

    def test_test_dependency_identity_must_match_resolved_graph(self):
        self.manifest['dependencies'][0]['version'] = 'other'
        self.reseal()
        self.reject('test dependency identity differs')

    def test_class_source_label_must_match_actual_bytecode(self):
        self.manifest['classes'][0]['sourceFileLabel'] = 'Different.kt'
        self.reseal()
        self.reject('class identity/source label differs')

    def test_fixture_jar_cannot_include_unreachable_test_classes(self):
        name = 'sample/UnrelatedTest.class'
        raw = fixture.compiled('sample/UnrelatedTest', 'UnrelatedTest.kt')
        with zipfile.ZipFile(self.root / 'ui-fixture.jar', 'a') as archive: archive.writestr(name, raw)
        self.manifest['classes'].append(dict(path=name, bytes=len(raw), sha256=tool.digest(raw), sourceFileLabel='UnrelatedTest.kt'))
        self.reseal('ui-fixture.jar')
        self.reject('unreachable fixture')

    def test_main_kotlin_metadata_is_retained_in_production_ledger(self):
        name = 'META-INF/main.kotlin_module'
        self.modify_json('production-ledger.json', lambda value: value['mainOutput'].update({name: dict(bytes=8, sha256='1' * 64)}))
        self.assertIn(name, self.verify()['production']['mainOutput'])

    def test_license_source_jars_are_valid_main_resources(self):
        name = 'licenses/library/library-sources.jar'
        self.modify_json('production-ledger.json', lambda value: value['mainResources'].update({name: dict(bytes=20, sha256='2' * 64)}))
        self.assertIn(name, self.verify()['production']['mainResources'])

    def test_metadata_resource_cannot_shadow_production(self):
        name = self.manifest['resources'][0]['path']
        self.modify_json('production-ledger.json', lambda value: value['mainOutput'].update({name: dict(bytes=3, sha256='3' * 64)}))
        self.reject('shadows production output')

    def test_mutation_during_dependency_inspection_is_rejected(self):
        original = tool.jars.checked_jar
        path = self.root / 'test-sources.json'
        @contextmanager
        def changed(candidate):
            with original(candidate) as value:
                yield value
                if Path(candidate).name.endswith('test-api.jar'): path.write_bytes(path.read_bytes() + b' ')
        with patch.object(tool.jars, 'checked_jar', changed): self.reject('payload changed during readback')

    def cli(self, output):
        folder = self.f.root / 'synthetic-ci-envelopes'
        folder.mkdir()
        for name, value in (('run', self.run), ('jobs', self.jobs)):
            (folder / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')
        (folder / 'plan.json').write_bytes(self.plan_raw)
        (folder / 'job.log').write_text(self.log(), encoding='utf-8')
        return subprocess.run([sys.executable, '-B', str(TOOLS / 'verify-original-root-ui-harness-export.py'),
            '--export', str(self.root), '--run', str(folder / 'run.json'), '--jobs', str(folder / 'jobs.json'),
            '--log', str(folder / 'job.log'), '--plan', str(folder / 'plan.json'), '--source-sha', self.f.sha,
            '--output', str(output)], capture_output=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

    def test_cli_emits_file_readback_without_claiming_candidate_runtime_acceptance(self):
        output = self.f.root / 'readback-proof.json'
        result = self.cli(output)
        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        proof = json.loads(output.read_bytes())
        self.assertTrue(proof['originalRootUiHarnessReadbackVerified'])
        self.assertFalse(proof['candidateRuntimeBound'])
        self.assertFalse(proof['uiRuntimeVerified'])
        self.assertNotIn('readback-proof.json', tool.files_under(self.root))

    def test_cli_refuses_to_write_proof_inside_immutable_export(self):
        output = self.root / 'readback-proof.json'
        result = self.cli(output)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())
        self.assertIn('outside the immutable export', result.stderr.decode('utf-8', 'replace'))


if __name__ == '__main__': unittest.main(verbosity=2)
