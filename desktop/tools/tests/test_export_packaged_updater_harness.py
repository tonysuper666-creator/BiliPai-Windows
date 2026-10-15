from pathlib import Path
from unittest import mock
import copy, hashlib, importlib.util, json, os, shutil, struct, subprocess, tempfile, time, unittest, zipfile

TOOL = Path(__file__).resolve().parents[1] / 'export-packaged-updater-harness.py'
spec = importlib.util.spec_from_file_location('updater_harness_export', TOOL)
tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(tool)


class UpdaterHarnessExportTest(unittest.TestCase):
    """Only file collection/provenance is exercised. No JVM or EXE is started."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='bp-harness-export-')
        self.root = Path(self.temporary.name); self.repo = self.root / 'repo'; self.repo.mkdir()
        self.output = self.root / 'export'; self.epoch = time.time() - 1
        self.class_root = self.repo / 'desktop/build/classes/kotlin/test'
        self.sources = []
        for index, name in enumerate(tool.SOURCE_FILES):
            path = self.repo / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('// file-only export fixture ' + str(index) + '\n').encode())
            self.sources.append(dict(path=name, normalization='lf', sha256=tool.digest(path)))
        self.plan = self.repo / '.github/windows-source-validation-plan.json'; self.plan.parent.mkdir()
        self.plan.write_text(json.dumps(dict(windowsVersion='0.2.427.121', sources=self.sources)) + '\n', encoding='utf-8')
        for name in tool.CLASS_BASES:
            path = self.class_root / tool.CLASS_PREFIX / (name + '.class'); path.parent.mkdir(parents=True, exist_ok=True)
            # Deliberately not executable bytecode: unit tests prove collection only.
            path.write_bytes(b'\xca\xfe\xba\xbe' + struct.pack('>HH', 0, 65) + name.encode())
        self.gradle_home = self.root / 'gradle-home'; artifacts = []
        for group, name in sorted(tool.REQUIRED_LIBRARIES):
            path = self.gradle_home / 'caches/modules-2/files-2.1' / group / name / '1.0' / 'content' / (name + '.jar')
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, 'x') as jar: jar.writestr('file-only-marker.txt', name)
            artifacts.append(dict(group=group, name=name, version='1.0', file=str(path), sha256=tool.digest(path)))
        self.git('-c', 'init.defaultBranch=fixture', 'init')
        self.git('-c', 'core.autocrlf=false', 'add', '.')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'Fixture inputs')
        self.head = self.git('rev-parse', 'HEAD').strip()
        self.inputs = dict(sourceSha=self.head, classRoots=[str(self.class_root)], gradleUserHome=str(self.gradle_home),
            planSha256=tool.digest(self.plan), artifacts=artifacts, jdk={'version':'21.0.12.1'}, compiler={'kotlin':'2.4.0'})

    def tearDown(self): self.temporary.cleanup()
    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.PIPE).decode()
    def export(self): return tool.export(self.repo, self.inputs, self.output, self.head, self.epoch)
    def rejected(self):
        with self.assertRaises((ValueError, FileNotFoundError)): self.export()
        self.assertFalse((self.output / 'manifest.json').exists())

    def test_inventory_binds_only_selected_classes_and_exact_dependency_bytes(self):
        unrelated = self.class_root / tool.CLASS_PREFIX / 'UnrelatedPrivateTest.class'; unrelated.write_bytes(b'excluded')
        result = self.export()
        self.assertEqual(self.head, result['sourceSha']); self.assertEqual(self.inputs['planSha256'], result['planSha256'])
        self.assertFalse(result['applicationExecuted']); self.assertFalse(result['updaterE2EExecuted']); self.assertFalse(result['releaseQualified'])
        self.assertEqual(3, len(result['classes']))
        with zipfile.ZipFile(self.output / 'updater-harness.jar') as jar:
            self.assertEqual({row['path'] for row in result['classes']}, set(jar.namelist()))
        for row in result['payload']:
            self.assertEqual(row['sha256'], tool.digest(self.output / row['path']))
        self.assertTrue(all('file' not in row for row in result['dependencies']))

    def test_existing_export_is_never_overwritten(self):
        self.export(); manifest = (self.output / 'manifest.json').read_bytes()
        with self.assertRaises(ValueError): self.export()
        self.assertEqual(manifest, (self.output / 'manifest.json').read_bytes())

    def test_wrong_source_head_is_rejected(self):
        self.inputs['sourceSha'] = 'a' * 40; self.rejected()

    def test_modified_or_unpinned_harness_source_is_rejected(self):
        (self.repo / tool.SOURCE_FILES[0]).write_bytes(b'changed')
        self.rejected()

    def test_missing_compiled_entry_is_rejected(self):
        (self.class_root / tool.CLASS_PREFIX / 'PackagedUpdaterHarnessMain.class').unlink(); self.rejected()

    def test_stale_compiled_entry_is_rejected(self):
        path = self.class_root / tool.CLASS_PREFIX / 'PackagedUpdaterHarnessMain.class'
        os.utime(path, (self.epoch - 30, self.epoch - 30)); self.rejected()

    def test_duplicate_class_roots_are_rejected(self):
        self.inputs['classRoots'] *= 2; self.rejected()

    def test_unknown_dependency_identity_is_rejected(self):
        self.inputs['artifacts'][0]['group'] = 'unexpected'; self.rejected()

    def test_dependency_outside_resolved_gradle_cache_is_rejected(self):
        row = self.inputs['artifacts'][0]; copied = self.root / 'foreign.jar'
        shutil.copyfile(row['file'], copied); row['file'] = str(copied); self.rejected()

    def test_missing_dependency_closure_is_rejected(self):
        self.inputs['artifacts'].pop(); self.rejected()

    def test_changed_dependency_digest_is_rejected(self):
        self.inputs['artifacts'][0]['sha256'] = '0' * 64; self.rejected()

    def test_compiled_buffer_is_bounded_before_export(self):
        with mock.patch.object(tool, 'MAX_CLASSES_BYTES', 1): self.rejected()
        self.assertFalse(self.output.exists())

    def test_source_drift_during_copy_cannot_produce_success_manifest(self):
        original = tool.shutil.copyfileobj
        def copy_and_change(source, target, length):
            original(source, target, length)
            (self.repo / tool.SOURCE_FILES[0]).write_bytes(b'changed during collection')
        with mock.patch.object(tool.shutil, 'copyfileobj', copy_and_change): self.rejected()

    def test_compiled_byte_drift_during_copy_cannot_produce_success_manifest(self):
        original = tool.shutil.copyfileobj
        def copy_and_change(source, target, length):
            original(source, target, length)
            (self.class_root / tool.CLASS_PREFIX / 'PackagedUpdaterHarnessMain.class').write_bytes(b'changed')
        with mock.patch.object(tool.shutil, 'copyfileobj', copy_and_change): self.rejected()

    def test_dependency_byte_drift_during_copy_cannot_produce_success_manifest(self):
        original = tool.shutil.copyfileobj
        def copy_and_change(source, target, length):
            original(source, target, length)
            Path(self.inputs['artifacts'][0]['file']).write_bytes(b'changed')
        with mock.patch.object(tool.shutil, 'copyfileobj', copy_and_change): self.rejected()

    def test_compilation_boundary_cannot_be_disabled(self):
        self.epoch = 0; self.rejected()


if __name__ == '__main__': unittest.main()
