"""Meaningful installer I/O failure tests use only newly created task-owned fixture directories."""
from pathlib import Path
import hashlib, importlib.util, json, tempfile, unittest
from unittest import mock
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('blocked_installer_tests', HERE / 'install-reviewed.py')
installer = importlib.util.module_from_spec(spec); spec.loader.exec_module(installer)
def digest(data): return hashlib.sha256(data).hexdigest()

class InstallerFailureTest(unittest.TestCase):
    def setUp(self):
        owned = HERE / 'installer-fixtures'; owned.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=owned)
        self.area = Path(self.temporary.name)
        # Verify the absolute recursive-cleanup target is inside this task's explicitly owned directory.
        self.area.resolve().relative_to(owned.resolve())
        self.repo = self.area / 'repo'; self.repo.mkdir()
        self.review = self.area / 'review'; self.review.mkdir()
        self.old_here = installer.HERE; installer.HERE = self.review
        self.addCleanup(setattr, installer, 'HERE', self.old_here)
        self.addCleanup(self.temporary.cleanup)
        self.rows = []
        for relative, previous, next_data in [('desktop/existing.kt', b'old body\r\n', b'new body\n'), ('desktop/new.kt', None, b'new class\n')]:
            target = self.repo / relative; target.parent.mkdir(exist_ok=True)
            if previous is not None: target.write_bytes(previous)
            staged = self.review / 'payload' / relative; staged.parent.mkdir(parents=True, exist_ok=True); staged.write_bytes(next_data)
            self.rows.append(dict(path=relative, baselineSha256Bytes=None if previous is None else digest(previous), payloadSha256Bytes=digest(next_data)))
        frozen = self.area / 'frozen-evidence.json'; frozen.write_bytes(b'original evidence')
        source = self.repo / 'original.kt'; source.write_bytes(b'original\r\n')
        platform = self.repo / 'platform.kt'; platform.write_bytes(b'platform')
        self.plan = dict(files=self.rows, frozenChecks=[dict(path=str(frozen), sha256Bytes=digest(b'original evidence'))], dependencies=[],
                         originalInputs=[dict(path='original.kt', sha256=digest(b'original\n'))],
                         platformInputs=[dict(path='platform.kt', sha256Bytes=digest(b'platform'))])

    def test_read_only_does_not_create_or_replace_any_product_target(self):
        result = installer.install(self.repo, self.plan)
        self.assertTrue(result['readOnly']); self.assertEqual(0, result['changedFiles'])
        self.assertEqual(b'old body\r\n', (self.repo / self.rows[0]['path']).read_bytes())
        self.assertFalse((self.repo / self.rows[1]['path']).exists())

    def test_atomic_install_and_idempotent_second_application(self):
        self.assertEqual(2, installer.install(self.repo, self.plan, True)['changedFiles'])
        self.assertEqual(b'new body\n', (self.repo / self.rows[0]['path']).read_bytes())
        self.assertEqual(0, installer.install(self.repo, self.plan, True)['changedFiles'])
        self.assertFalse(list(self.repo.rglob('*.tmp')))

    def test_later_stale_baseline_prevents_first_file_write(self):
        (self.repo / self.rows[1]['path']).write_bytes(b'foreign body')
        with self.assertRaisesRegex(ValueError, 'baseline changed'): installer.install(self.repo, self.plan, True)
        self.assertEqual(b'old body\r\n', (self.repo / self.rows[0]['path']).read_bytes())

    def test_frozen_evidence_tamper_prevents_install(self):
        Path(self.plan['frozenChecks'][0]['path']).write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'Frozen artifact'): installer.install(self.repo, self.plan, True)
        self.assertFalse((self.repo / self.rows[1]['path']).exists())

    def test_staged_payload_tamper_prevents_install(self):
        (self.review / 'payload' / self.rows[0]['path']).write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'Staged payload'): installer.install(self.repo, self.plan, True)
        self.assertEqual(b'old body\r\n', (self.repo / self.rows[0]['path']).read_bytes())

    def test_crlf_source_is_accepted_but_real_source_change_prevents_install(self):
        self.assertEqual(2, installer.install(self.repo, self.plan)['verifiedFiles'])
        (self.repo / 'original.kt').write_bytes(b'changed\r\n')
        with self.assertRaisesRegex(ValueError, 'Original source'): installer.install(self.repo, self.plan, True)
        self.assertFalse((self.repo / self.rows[1]['path']).exists())

    def test_second_atomic_write_failure_restores_first_exact_bytes(self):
        actual = installer.atomic_write
        calls = 0
        def fail_second(target, contents):
            nonlocal calls
            calls += 1
            if calls == 2: raise OSError('task-owned injected replacement failure')
            actual(target, contents)
        with mock.patch.object(installer, 'atomic_write', side_effect=fail_second):
            with self.assertRaisesRegex(OSError, 'injected'): installer.install(self.repo, self.plan, True)
        self.assertEqual(b'old body\r\n', (self.repo / self.rows[0]['path']).read_bytes())
        self.assertFalse((self.repo / self.rows[1]['path']).exists())

    def test_path_traversal_and_drive_paths_are_rejected(self):
        for path in ['../foreign.kt', 'C:/foreign.kt', '/foreign.kt', 'desktop\\foreign.kt']:
            with self.subTest(path=path):
                with self.assertRaises(ValueError): installer.target_in(self.repo, path)
        self.assertFalse((self.area / 'foreign.kt').exists())

    def test_changed_platform_transport_prevents_install(self):
        (self.repo / 'platform.kt').write_bytes(b'new platform')
        with self.assertRaisesRegex(ValueError, 'Platform helper'): installer.install(self.repo, self.plan, True)
        self.assertEqual(b'old body\r\n', (self.repo / self.rows[0]['path']).read_bytes())

if __name__ == '__main__': unittest.main(verbosity=2)
