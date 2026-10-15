"""File/provenance checks only; marker class bytes never execute Java."""
from pathlib import Path
import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


tool = load('export_readback', TOOLS / 'verify-updater-harness-export.py')
fixture = load('export_gate_fixture', TOOLS / 'tests/test_package_source_qualification.py')


class UpdaterHarnessReadbackTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='bp-harness-readback-')
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.f = fixture.PackageSourceQualificationTest(); self.f.setUp()
        self.plan = self.f.plan; self.plan['exportPackagedUpdaterHarness'] = True
        sources = [dict(path='desktop/src/test/kotlin/com/bilipai/desktop/update/' + name + '.kt',
                        sha256='c' * 64, normalization='lf') for name in
                   ['DesktopUpdaterIntegrationTest', 'PackagedUpdaterChildEnvironment', 'PackagedUpdaterHarnessMain']]
        self.plan['sources'] = sources
        pins = {row['path']: row['sha256'] for row in sources}
        self.f.payload['receipt'].update(sourceBefore=pins, sourceAfter=pins)
        self.plan_bytes = (json.dumps(self.plan) + '\n').encode()
        self.manifest = dict(schema=1, sourceSha=self.f.sha, windowsVersion=self.plan['windowsVersion'],
            planSha256=hashlib.sha256(self.plan_bytes).hexdigest(),
            mainClass='com.bilipai.desktop.update.PackagedUpdaterHarnessMain',
            sources=[dict(path=row['path'], sha256=row['sha256']) for row in sources],
            applicationExecuted=False, updaterE2EExecuted=False, releaseQualified=False,
            dependencies=[], classes=[], payload=[])
        with zipfile.ZipFile(self.root / 'updater-harness.jar', 'w') as archive:
            for row in sources:
                name = row['path'].removeprefix('desktop/src/test/kotlin/').removesuffix('.kt') + '.class'
                data = bytes.fromhex('cafebabe00000041')
                archive.writestr(name, data)
                self.manifest['classes'].append(dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
        self.payload('updater-harness.jar')
        (self.root / 'lib').mkdir()
        for i, (group, name) in enumerate([('org.jetbrains.kotlin', 'kotlin-test'),
                ('org.jetbrains.kotlin', 'kotlin-test-junit5'), ('org.junit.jupiter', 'junit-jupiter-api'),
                ('org.junit.platform', 'junit-platform-commons'), ('org.opentest4j', 'opentest4j')]):
            relative = 'lib/' + str(i).zfill(2) + '-' + name + '.jar'
            with zipfile.ZipFile(self.root / relative, 'w') as archive:
                archive.writestr('marker', 'file-only fixture')
            self.payload(relative)
            self.manifest['dependencies'].append(dict(group=group, name=name, version='1.0', path=relative,
                                                       sha256=tool.digest(self.root / relative)))
        self.seal()

    def payload(self, relative):
        path = self.root / relative
        rows = self.manifest['payload']
        rows[:] = [row for row in rows if row['path'] != relative]
        rows.append(dict(path=relative, bytes=path.stat().st_size, sha256=tool.digest(path)))

    def seal(self):
        path = self.root / 'manifest.json'; path.write_text(json.dumps(self.manifest), encoding='utf-8')
        self.f.payload['receipt']['updaterHarnessExport'] = dict(schema=1, sourceSha=self.f.sha,
            manifestSha256=tool.digest(path), payloadFiles=len(self.manifest['payload']),
            payloadBytes=sum(row['bytes'] for row in self.manifest['payload']),
            applicationExecuted=False, updaterE2EExecuted=False)

    def verify(self):
        return tool.verify(self.root, self.f.run, self.f.jobs, self.f.log(), self.plan_bytes, self.f.sha)

    def test_source_bound_export_does_not_claim_any_exe_or_updater_execution(self):
        proof = self.verify()
        self.assertTrue(proof['sourceQualified']); self.assertTrue(proof['harnessReadbackVerified'])
        self.assertEqual(3, proof['testClasses']); self.assertEqual(6, proof['harnessFiles'])
        for name in ['applicationExecuted', 'updaterE2EExecuted', 'releaseQualified', 'desktopReplaced']:
            self.assertFalse(proof[name])

    def test_failed_or_unfinished_run_cannot_authenticate_the_export(self):
        for status, conclusion in [('in_progress', None), ('completed', 'failure')]:
            self.f.run.update(status=status, conclusion=conclusion)
            with self.assertRaises(ValueError): self.verify()

    def test_manifest_tampering_is_rejected_without_resealing(self):
        with (self.root / 'manifest.json').open('a') as stream: stream.write(' ')
        with self.assertRaises(ValueError): self.verify()

    def test_wrong_manifest_source_plan_or_version_is_rejected(self):
        for key, value in [('sourceSha', 'e' * 40), ('planSha256', 'f' * 64), ('windowsVersion', '0.0.1')]:
            original = self.manifest[key]; self.manifest[key] = value; self.seal()
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify()
            self.manifest[key] = original

    def test_missing_source_closure_or_unpinned_source_is_rejected(self):
        self.manifest['sources'][0]['sha256'] = 'f' * 64; self.seal()
        with self.assertRaises(ValueError): self.verify()
        self.manifest['sources'].pop(); self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_payload_tampering_missing_file_or_extra_file_is_rejected(self):
        path = self.root / 'lib/00-kotlin-test.jar'; original = path.read_bytes()
        path.write_bytes(original + b'drift')
        with self.assertRaises(ValueError): self.verify()
        path.unlink()
        with self.assertRaises(FileNotFoundError): self.verify()
        path.write_bytes(original); (self.root / 'unexpected.jar').write_bytes(b'extra')
        with self.assertRaises(ValueError): self.verify()

    def test_relative_escape_and_duplicate_payload_names_are_rejected(self):
        original = copy.deepcopy(self.manifest['payload'])
        for name in ['../escape.jar', '/absolute.jar', 'lib/../outside.jar', 'lib\\outside.jar']:
            self.manifest['payload'] = copy.deepcopy(original); self.manifest['payload'][0]['path'] = name; self.seal()
            with self.subTest(name=name), self.assertRaises(ValueError): self.verify()
        self.manifest['payload'] = copy.deepcopy(original)
        self.manifest['payload'][1]['path'] = self.manifest['payload'][0]['path']; self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_unknown_or_missing_dependency_is_rejected(self):
        self.manifest['dependencies'][0]['group'] = 'unapproved'; self.seal()
        with self.assertRaises(ValueError): self.verify()
        self.manifest['dependencies'].pop(); self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_declared_class_bytes_and_archive_class_closure_must_match(self):
        self.manifest['classes'][0]['sha256'] = 'a' * 64; self.seal()
        with self.assertRaises(ValueError): self.verify()
        self.manifest['classes'].pop(); self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_extra_or_duplicate_archive_entry_is_rejected(self):
        with zipfile.ZipFile(self.root / 'updater-harness.jar', 'a') as archive:
            archive.writestr('unapproved.class', bytes.fromhex('cafebabe00000041'))
        self.payload('updater-harness.jar'); self.seal()
        with self.assertRaises(ValueError): self.verify()

    def test_export_claims_and_entry_override_are_rejected(self):
        for key, value in [('applicationExecuted', True), ('updaterE2EExecuted', True),
                           ('releaseQualified', True), ('mainClass', 'OtherMain')]:
            original = self.manifest[key]; self.manifest[key] = value; self.seal()
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify()
            self.manifest[key] = original

    def test_duplicate_manifest_field_is_rejected(self):
        path = self.root / 'manifest.json'
        path.write_text(path.read_text()[:-1] + ', "schema": 1}', encoding='utf-8')
        self.f.payload['receipt']['updaterHarnessExport']['manifestSha256'] = tool.digest(path)
        with self.assertRaises(ValueError): self.verify()
