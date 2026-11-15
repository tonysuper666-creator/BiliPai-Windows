"""Windows fixture budget, identity and deep legacy-entry cleanup regressions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import check_slice_symbol_closure as gate
import legacy_gate_workspace as legacy
from compiled_source_fixture import legacy_workspace, relative_inside, remove_tree, scratch_directory, temporary_directory


def git(root, *args):
    return subprocess.run(['git', '-c', 'core.longpaths=true', '-C', str(root), *args],
                          check=True, capture_output=True).stdout.decode('utf8').strip()


def put(root, relative, text):
    file = Path(gate._long(root / relative))
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text, encoding='utf8')
    return file


class WindowsFixturePathTests(unittest.TestCase):
    def test_mirror_budget_and_original_temp(self):
        before = {key: os.environ.get(key) for key in ('TEMP', 'TMP', 'TMPDIR')}
        self.assertEqual((259, 181, 8, 69), (gate.WINDOWS_MAX_PATH, gate.LONGEST_PRODUCER_PATH,
                                            gate.PRODUCER_PATH_MARGIN, gate.MAX_SCRATCH_ROOT))
        for source in (Path('live'), Path('.legacy-root')):
            with self.subTest(source=source), scratch_directory(source) as scratch:
                self.assertEqual(Path(tempfile.gettempdir()).resolve(), scratch.parent.resolve())
                mirror = gate.mirror_root(source, scratch)
                print('scratch:', mirror, 'length:', len(str(mirror)), flush=True)
                gate.check_scratch_root(mirror, windows=True)
            self.assertFalse(scratch.exists())
        self.assertEqual(before, {key: os.environ.get(key) for key in before})

    def test_69_allowed_and_70_rejected(self):
        parent = os.path.abspath(tempfile.gettempdir())
        self.assertLess(len(parent) + 1, 69)
        root = parent + os.sep + 'x' * (69 - len(parent) - 1)
        self.assertEqual(69, len(root))
        gate.check_scratch_root(root, windows=True)
        with self.assertRaisesRegex(gate.ToolError, 'MAX_PATH'):
            gate.check_scratch_root(root + 'x', windows=True)

    def test_full_identity_and_escapes(self):
        with temporary_directory() as base:
            root, outside = base / 'root', base / 'outside'
            expected = put(root, 'one/file.kt', 'expected')
            different = put(root, 'two/file.kt', 'different')
            escaped = put(outside, 'file.kt', 'outside')
            self.assertEqual(Path('one/file.kt'), relative_inside(gate._long(expected), root))
            self.assertEqual(relative_inside(expected, gate._long(root)), relative_inside(gate._long(expected), root))
            self.assertNotEqual(relative_inside(expected, root), relative_inside(different, root))
            self.assertFalse(expected.samefile(different))
            with self.assertRaises(ValueError):
                relative_inside(escaped, root)
            with self.assertRaises(ValueError):
                relative_inside(root / '../outside/file.kt', root)
            link = root / 'escape'
            if os.name == 'nt':
                subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)], check=True, capture_output=True)
            else:
                link.symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                relative_inside(link / 'file.kt', root)
            remove_tree(root)
            self.assertEqual('outside', escaped.read_text())

    def test_deep_tree_and_readonly_git_objects_are_removed(self):
        with temporary_directory() as base:
            root = base / 'repo'
            root.mkdir()
            git(root, 'init', '-q')
            deep = put(root, '/'.join(['a' * 90, 'b' * 90, 'c' * 90, 'Deep.kt']), 'class Deep')
            git(root, 'add', '.')
            git(root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
            self.assertGreater(len(str(deep)), 260)
            print('deep cleanup length:', len(str(deep)), flush=True)
            remove_tree(root)
            self.assertFalse(os.path.exists(gate._long(root)))

    def test_legacy_entry_cleans_deep_paths_and_keeps_live_build(self):
        before_path, before_process = legacy.Path, legacy.subprocess
        with temporary_directory() as base:
            root = base / 'repo'
            root.mkdir()
            git(root, 'init', '-q')
            put(root, 'original.txt', 'legacy')
            git(root, 'add', '.')
            git(root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'legacy')
            pin = git(root, 'rev-parse', 'HEAD')
            put(root, 'original.txt', 'live')
            put(root, 'desktop/upstream-core/legacy-root.commit', pin)
            put(root, '.github/workflow.txt', 'live workflow')
            relative = '/'.join(['desktop', 'a' * 90, 'b' * 90, 'c' * 90, 'Deep.kt'])
            put(root, relative, 'class LiveDeep')
            git(root, 'add', '.')
            git(root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'live')
            sentinel = put(root, 'desktop/build/keep.txt', 'live build must survive')
            with legacy_workspace(root) as view:
                self.assertEqual('legacy', (view / 'original.txt').read_text())
                self.assertEqual('live workflow', (view / '.github/workflow.txt').read_text())
                self.assertEqual('class LiveDeep', Path(gate._long(view / relative)).read_text())
                self.assertEqual(sentinel.read_text(), (view / 'desktop/build/keep.txt').read_text())
                print('legacy deep length:', len(str(view / relative)), flush=True)
            self.assertFalse(os.path.exists(gate._long(view)))
            self.assertEqual('live build must survive', sentinel.read_text())
            self.assertEqual(1, git(root, 'worktree', 'list', '--porcelain').count('worktree '))
        self.assertIs(before_path, legacy.Path)
        self.assertIs(before_process, legacy.subprocess)
