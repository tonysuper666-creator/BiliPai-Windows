"""v0.4.6 core-data progress and card-stats policies emitted whole by the v041 status-label CLI."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_core_data_policies as policies

spec = importlib.util.spec_from_file_location('sync_upstream_core_data_policies', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

PRODUCER = 'tools/extract-v041-video-status-labels.py'
PACKAGE = 'com.android.purebilibili.data.model'
TOP_LEVEL_NAMES = {
    'COMPLETED_PROGRESS_THRESHOLD',
    'VideoProgressDisplayState',
    'resolveVideoDisplayProgressState',
    'normalizeServerProgressSec',
    'normalizeLocalProgressSec',
    'isCompletedProgress',
    'resolveHistoryProgressLabel',
    'isWatchLaterViewed',
    'resolveWatchLaterDisplayProgressState',
    'VideoCardStatsTexts',
    'resolveVideoCardStatsTexts',
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / policies.ARCHIVE).iterdir() if p.is_file()}


def frozen(root):
    rows = []
    for path in sorted(Path(root).rglob('*')):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            rows.append((rel, 'link', os.readlink(path)))
        elif path.is_file():
            rows.append((rel, 'file', path.read_bytes()))
        elif path.is_dir():
            rows.append((rel, 'dir'))
        else:
            rows.append((rel, 'other', path.lstat().st_mode))
    return tuple(rows)


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def run_cli(output, tests_output, seed):
    environment = dict(os.environ, PYTHONHASHSEED=str(seed), PYTHONIOENCODING='utf-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1')
    environment.pop('PYTHONPATH', None)
    done = subprocess.run(
        [sys.executable, '-X', 'utf8', '-B', str(REPO / 'desktop' / PRODUCER),
         '--repo', str(REPO), '--output', str(output), '--tests-output', str(tests_output)],
        cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=True)
    return json.loads(done.stdout.decode('utf-8').strip().splitlines()[-1])


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, [(item.visibility, item.kind, item.name) for item in info.decls]


class V046CoreDataPoliciesSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = {p.name: p.read_bytes() for p in (REPO / policies.ARCHIVE).iterdir() if p.is_file()}
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-cdp-')
        cls.root = Path(cls.temporary.name)
        cls.summary = run_cli(cls.root / 'main', cls.root / 'tests', 0)
        cls.main = tree(cls.root / 'main')
        cls.tests = tree(cls.root / 'tests')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        original = {p.name: p.read_bytes() for p in (REPO / policies.ARCHIVE).iterdir() if p.is_file()}
        self.assertEqual(self.archive, original)
        leaves = sorted(original)
        self.assertEqual(sorted(policies.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-cdp-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / policies.ARCHIVE, repo / policies.ARCHIVE)
                target = repo / policies.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    policies.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-cdp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / policies.ARCHIVE, repo / policies.ARCHIVE)
            (repo / policies.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                policies.fixed(repo)
        self.assertEqual(original, {p.name: p.read_bytes() for p in (REPO / policies.ARCHIVE).iterdir() if p.is_file()})

    def test_outputs_match_the_archive_and_the_upstream_git_blobs(self):
        manifest = json.loads(self.archive['manifest.json'])
        emitted = {**self.main, **self.tests}
        for row in manifest['files']:
            with self.subTest(name=row['filename']):
                archived = self.archive[row['filename']]
                self.assertEqual(archived, (REPO / policies.ARCHIVE / row['filename']).read_bytes())
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', policies.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, archived)
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                matches = [raw for path, raw in emitted.items() if path.endswith('/' + row['filename'])]
                self.assertEqual([archived], matches)
        self.assertEqual({'commit', 'hooks', 'mainSources', 'testSources', 'proofSha256'}, set(self.summary))
        self.assertEqual(7, self.summary['mainSources'])
        self.assertEqual(7, self.summary['testSources'])
        self.assertEqual(21, self.summary['hooks'])

    def test_emit_is_deterministic_and_a_rerun_matches_the_archive(self):
        def once(seed):
            directory = self.root / ('emit-' + str(seed))
            receipt = policies.emit(REPO, directory / 'main', directory / 'tests')
            return receipt, tree(directory / 'main'), tree(directory / 'tests')

        first, second = once(1), once(2)
        self.assertEqual(first, second)
        self.assertEqual(
            [policies.PACKAGE_DIR + '/' + name for name in policies.MAIN_FILES],
            [row['path'] for row in first[0]['files'][:3]])
        self.assertEqual(policies.PACKAGE_DIR + '/' + policies.TEST_FILES[0], first[0]['files'][3]['path'])
        for row in first[0]['files']:
            self.assertEqual(sha(self.archive[Path(row['path']).name]), row['sha256'])
        rerun = policies.emit(REPO, self.root / 'emit-1' / 'main', self.root / 'emit-1' / 'tests')
        self.assertEqual(first[0], rerun)
        self.assertEqual(first[1], tree(self.root / 'emit-1' / 'main'))
        self.assertEqual(first[2], tree(self.root / 'emit-1' / 'tests'))
        for name in policies.MAIN_FILES:
            self.assertEqual(self.archive[name], first[1][policies.PACKAGE_DIR + '/' + name])
        for name in policies.TEST_FILES:
            self.assertEqual(self.archive[name], first[2][policies.PACKAGE_DIR + '/' + name])
        cli_main, cli_tests = self.root / 'cli-same' / 'main', self.root / 'cli-same' / 'tests'
        first_cli = run_cli(cli_main, cli_tests, 4)
        first_cli_main, first_cli_tests = tree(cli_main), tree(cli_tests)
        second_cli = run_cli(cli_main, cli_tests, 4)
        self.assertEqual(first_cli, second_cli)
        self.assertEqual(first_cli_main, tree(cli_main))
        self.assertEqual(first_cli_tests, tree(cli_tests))
        for name in policies.MAIN_FILES:
            self.assertEqual(self.archive[name], first_cli_main[policies.PACKAGE_DIR + '/' + name])
        for name in policies.TEST_FILES:
            self.assertEqual(self.archive[name], first_cli_tests[policies.PACKAGE_DIR + '/' + name])
        again = run_cli(self.root / 'main-2', self.root / 'tests-2', 7)
        self.assertEqual(self.summary, again)
        self.assertEqual(self.main, tree(self.root / 'main-2'))
        self.assertEqual(self.tests, tree(self.root / 'tests-2'))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = {p.name: p.read_bytes() for p in (REPO / policies.ARCHIVE).iterdir() if p.is_file()}
        nested = REPO / policies.ARCHIVE / 'nested'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                policies.emit(REPO, nested, self.root / 'overlap-tests')
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                policies.validate_outputs(REPO, REPO)
            self.assertFalse(nested.exists())
            self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / policies.ARCHIVE).iterdir() if p.is_file()})
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / policies.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    def test_a_stale_regular_file_is_replaced_and_special_targets_fail_closed(self):
        relatives = [policies.PACKAGE_DIR + '/' + name for name in policies.MAIN_FILES + policies.TEST_FILES]

        def prepare(root, relative):
            main, tests = root / 'main', root / 'tests'
            (root / 'outside.txt').write_bytes(b'OUTSIDE')
            base = tests if relative.endswith('Test.kt') else main
            target = base / relative
            target.parent.mkdir(parents=True)
            (target.parent / 'decoy.kt').write_bytes(b'DECOY')
            return main, tests, target

        for relative in relatives:
            with self.subTest(kind='stale', relative=relative), tempfile.TemporaryDirectory(prefix='bp-cdp-') as tmp:
                root = Path(tmp)
                before_archive = archive_files()
                main, tests, target = prepare(root, relative)
                target.write_bytes(b'SENTINEL-FOREIGN')
                policies.emit(REPO, main, tests)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                policies.emit(REPO, main, tests)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                for name in policies.MAIN_FILES:
                    self.assertEqual(self.archive[name], (main / policies.PACKAGE_DIR / name).read_bytes())
                for name in policies.TEST_FILES:
                    self.assertEqual(self.archive[name], (tests / policies.PACKAGE_DIR / name).read_bytes())
                self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                self.assertEqual(before_archive, archive_files())
        for kind in ('symlink', 'directory', 'fifo'):
            for relative in relatives:
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-cdp-') as tmp:
                    if kind == 'fifo' and not hasattr(os, 'mkfifo'):
                        self.skipTest('os.mkfifo is unavailable')
                    root = Path(tmp)
                    before_archive = archive_files()
                    main, tests, target = prepare(root, relative)
                    if kind == 'symlink':
                        dest = target.parent / 'neighbor.bin'
                        dest.write_bytes(b'NEIGHBOR')
                        try:
                            target.symlink_to(dest)
                        except OSError:
                            self.skipTest('symlink creation is not permitted')
                    elif kind == 'directory':
                        target.mkdir()
                        (target / 'nested.txt').write_bytes(b'NESTED')
                    else:
                        os.mkfifo(target)
                    before = frozen(root)
                    with self.assertRaises(ValueError) as caught:
                        policies.emit(REPO, main, tests)
                    self.assertIn('not a regular file: ' + relative, str(caught.exception))
                    self.assertEqual(before, frozen(root))
                    self.assertEqual(before_archive, archive_files())
                    self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                    self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())

    def test_gradle_task_and_inventory_wire_the_archive_once_without_an_exclude(self):
        build = self.build
        block = task_block(build, 'extractOriginalVideoStatusLabels')
        for literal in ('inputs.file("tools/v046_core_data_policies.py")',
                        'inputs.dir("upstream-slices/v046-core-data-policies")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        for path in policies.UPSTREAM_PATHS.values():
            self.assertEqual(0, build.count('exclude(canonicalOriginalIdentity("' + path + '"))'), path)
        self.assertIn(policies.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(policies.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(policies.ARCHIVE + '/')]
        self.assertEqual(4, len(rows))
        self.assertEqual(sorted(policies.UPSTREAM_PATHS.values()), sorted(row['path'] for row in rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-core-data-policies'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(policies.COMMIT, row['upstreamCommit'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == policies.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({policies.ARCHIVE + '/' + name for name in policies.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(policies.ARCHIVE + '/')])

    def test_each_top_level_name_is_declared_once_in_package_data_model(self):
        own = {}
        for name in policies.MAIN_FILES:
            text = self.archive[name].decode('utf-8')
            package, decls = declarations(text, name)
            self.assertEqual(PACKAGE, package, name)
            own[name] = {item[2] for item in decls}
        self.assertEqual(TOP_LEVEL_NAMES, set().union(*own.values()))
        for found in own.values():
            self.assertTrue(found)
        self.assertEqual([], [name for name, found in own.items() if found & set().union(*(v for k, v in own.items() if k != name))])
        scratch = tempfile.mkdtemp(prefix='bp-cdp-')
        try:
            root, env = gate.make_scratch_repo(REPO, scratch)
            model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
            tasks, skipped = gate.select_producers(model)
            results = gate.run_producers(root, tasks, env)
            failed = [(row['task'], row['returncode'], row['stderrTail']) for row in results if row['returncode']]
            self.assertEqual([], failed)
            self.assertGreater(len(results), 100)
            self.assertIn('extractOriginalVideoStatusLabels', {row['task'] for row in results})
            hits = []
            star_imports = []
            roots = [root / 'desktop/src', root / 'desktop/build']
            for base in roots:
                for path in base.rglob('*'):
                    if not path.is_file() or path.suffix not in {'.kt', '.kts'}:
                        continue
                    raw = path.read_bytes()
                    if b'import com.android.purebilibili.data.model.*' in raw:
                        star_imports.append(path.relative_to(root).as_posix())
                    if not any(name.encode('utf-8') in raw for name in TOP_LEVEL_NAMES):
                        continue
                    package, decls = declarations(raw.decode('utf-8'), path.relative_to(root).as_posix())
                    for _visibility, _kind, declared in decls:
                        if declared in TOP_LEVEL_NAMES:
                            hits.append((package, declared, path.relative_to(root).as_posix()))
            self.assertEqual([], star_imports)
            owned = [hit for hit in hits if hit[0] == PACKAGE]
            counts = {name: [hit[2] for hit in owned if hit[1] == name] for name in TOP_LEVEL_NAMES}
            self.assertEqual({name: 1 for name in TOP_LEVEL_NAMES}, {name: len(paths) for name, paths in counts.items()})
            for name, paths in counts.items():
                self.assertEqual(1, len(paths), (name, paths))
                self.assertTrue(paths[0].startswith('desktop/build/generated/v041-video-status-labels/'), paths[0])
            others = [hit for hit in hits if hit[0] != PACKAGE]
            self.assertEqual([], [hit for hit in others if hit[0] == PACKAGE])
            self.assertTrue(all(hit[0] != PACKAGE for hit in others))
        finally:
            shutil.rmtree(scratch, ignore_errors=True)

    def test_emitted_test_keeps_four_junit5_cases_and_delimiters_balance(self):
        relative = PACKAGE.replace('.', '/') + '/VideoProgressDisplayPolicyTest.kt'
        emitted = self.tests[relative].decode('utf-8')
        self.assertEqual(self.archive['VideoProgressDisplayPolicyTest.kt'].decode('utf-8'), emitted)
        self.assertEqual(4, emitted.count('\n    @Test\n'))
        self.assertEqual(4, emitted.count('@Test'))
        self.assertIn('import org.junit.jupiter.api.Test\n', emitted)
        self.assertIn('org.junit.jupiter', emitted)
        for name in policies.MAIN_FILES:
            text = self.main[PACKAGE.replace('.', '/') + '/' + name].decode('utf-8')
            self.balanced(text)
        self.balanced(emitted)

    def balanced(self, source):
        stack = []
        for token, first, _end in sync.kotlin_tokens(source):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', ']', '}'):
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)


if __name__ == '__main__':
    unittest.main()
