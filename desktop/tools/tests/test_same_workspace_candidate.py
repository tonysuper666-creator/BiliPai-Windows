from pathlib import Path
import base64
import copy
import gzip
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import test_original_root_ui_runtime_binding as runtime_fixture

spec = importlib.util.spec_from_file_location('same_workspace_image', TOOLS / 'verify-same-workspace-candidate.py')
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)


class SameWorkspaceCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bilipai-same-workspace-')
        self.root = Path(self.temp.name)
        self.candidate = self.root / 'candidate'
        self.candidate.mkdir()
        self.image = runtime_fixture.Fixture(self.candidate)
        self.export = self.root / 'source-export'
        self.export.mkdir()
        self.sha = 'a' * 40
        self.pins = {'desktop/main.kt': 'c' * 64}
        self.plan = dict(repository=reuse.gate.REPOSITORY, baseHead='b' * 40,
            windowsVersion='0.2.427.1', exportOriginalRootUiHarness=True,
            sources=[dict(path=k, sha256=v) for k, v in self.pins.items()])
        self.receipt = dict(passed=True, exitCode=0, sourceSha=self.sha, baseHead=self.plan['baseHead'],
            windowsVersion=self.plan['windowsVersion'], sourceUnchanged=True,
            sourceBefore=dict(self.pins), sourceAfter=dict(self.pins))
        self.write_export()

    def tearDown(self):
        self.temp.cleanup()

    def write_export(self):
        raw = json.dumps(self.image.ledger).encode()
        (self.export / 'production-ledger.json').write_bytes(raw)
        self.manifest = dict(schema=1, sourceSha=self.sha, windowsVersion=self.plan['windowsVersion'],
            planSha256=hashlib.sha256(json.dumps(self.plan).encode()).hexdigest(),
            payload=[dict(path='production-ledger.json', **runtime_fixture.pin(raw)),
                     *[dict(path=name, **runtime_fixture.pin(b'fixture'))
                       for name in ('runtime-model.json', 'ui-fixture.jar', 'test-sources.json')]])
        self.seal_manifest()

    def seal_manifest(self):
        raw = json.dumps(self.manifest).encode()
        (self.export / 'manifest.json').write_bytes(raw)
        self.receipt['originalRootUiHarnessExport'] = dict(sourceSha=self.sha,
            manifestSha256=hashlib.sha256(raw).hexdigest(), productionClassesExported=False,
            productionRuntimeBindingRequired=True)

    def log(self):
        raw = json.dumps(dict(receipt=self.receipt, rawXml={})).encode()
        return ('BILIPAI_SOURCE_RECEIPT_BEGIN ' + hashlib.sha256(raw).hexdigest() + '\n' +
            base64.b64encode(gzip.compress(raw)).decode() + '\nBILIPAI_SOURCE_RECEIPT_END\n')

    def verify(self, package_log='> Task :compileKotlin UP-TO-DATE\n> Task :compileJava NO-SOURCE\n'):
        return reuse.verify(self.candidate, self.export, json.dumps(self.plan).encode(),
            self.log(), self.sha, self.pins, package_log)

    def test_exact_main_classes_metadata_and_version_bind_without_qualifying_a_run(self):
        proof = self.verify()
        self.assertEqual(dict(classes=1, metadataAndResources=2), proof['mainOutput'])
        self.assertTrue(proof['packagedMainMatchesSourceStepOutputs'])
        self.assertTrue(proof['mainRecompileAvoided'])
        self.assertTrue(proof['successfulTerminalRunStillRequired'])
        for field in ('sourceQualified', 'packageQualified', 'applicationExecuted', 'hardwareExecuted',
                      'releaseQualified', 'executionAuthorized', 'desktopReplaced'):
            self.assertFalse(proof[field])

    def test_failed_foreign_or_changed_source_step_is_rejected(self):
        initial = copy.deepcopy(self.receipt)
        for edit in [dict(passed=False), dict(exitCode=1), dict(sourceSha='d' * 40),
                     dict(sourceUnchanged=False), dict(sourceAfter={})]:
            self.receipt = dict(initial, **edit)
            with self.subTest(edit=edit), self.assertRaises(ValueError): self.verify()
        self.receipt = initial
        self.pins['desktop/main.kt'] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'Source inputs changed'): self.verify()

    def test_ledger_or_manifest_changes_cannot_redefine_the_tested_main_output(self):
        (self.export / 'production-ledger.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError, 'JSON bytes differ'): self.verify()
        self.write_export()
        self.manifest['sourceSha'] = 'd' * 40
        self.seal_manifest()
        with self.assertRaisesRegex(ValueError, 'ledger identity differs'): self.verify()

    def test_changed_or_extra_main_classes_and_kotlin_metadata_are_rejected(self):
        original = dict(self.image.main)
        mutations = [dict(original, **{'com/bilipai/desktop/MainKt.class': b'changed'}),
                     dict(original, **{'extra/Injected.class': b'new'}),
                     dict(original, **{'META-INF/actual.kotlin_module': b'changed metadata'})]
        for rows in mutations:
            runtime_fixture.jar(self.image.app / 'main.jar', rows)
            with self.subTest(rows=list(rows)), self.assertRaises(ValueError): self.verify()

    def test_packaged_version_must_match_even_if_the_source_step_labels_a_different_version(self):
        self.plan['windowsVersion'] = self.receipt['windowsVersion'] = '0.2.427.2'
        self.write_export()
        with self.assertRaisesRegex(ValueError, 'Packaged Windows version differs'): self.verify()

    def test_recompilation_is_not_reported_as_avoided_and_cached_or_ambiguous_markers_fail(self):
        for log in ['', '> Task :compileKotlin\n> Task :compileJava UP-TO-DATE\n',
                    '> Task :compileKotlin UP-TO-DATE\n']:
            self.assertFalse(self.verify(log)['mainRecompileAvoided'])
        for log in ['> Task :compileKotlin FROM-CACHE\n', '> Task :compileKotlin FAILED\n',
                    '> Task :compileKotlin UP-TO-DATE\n> Task :compileKotlin UP-TO-DATE\n']:
            with self.assertRaises(ValueError): self.verify(log)


if __name__ == '__main__':
    unittest.main()
