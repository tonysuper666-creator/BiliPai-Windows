"""v0.4.6 HomeRefreshUiPolicy emitted whole by the home-page producer."""
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
import v046_home_refresh_ui_policy as policy

spec = importlib.util.spec_from_file_location('sync_upstream_home_refresh_ui_policy', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

page_spec = importlib.util.spec_from_file_location(
    'extract_upstream_home_page_refresh_ui_policy', TOOLS / 'extract-upstream-home-page.py')
page = importlib.util.module_from_spec(page_spec)
page_spec.loader.exec_module(page)

PRODUCER = 'tools/extract-upstream-home-page.py'
CANONICAL = policy.UPSTREAM_PATHS['HomeRefreshUiPolicy.kt']
RELATIVE = policy.RELATIVE['HomeRefreshUiPolicy.kt']
NEW_SYMBOLS = (
    'fun resolveOldContentDividerContainerColor(',
    'const val OLD_CONTENT_DIVIDER_CONTAINER_ALPHA',
)
TOP_LEVEL = {
    'shouldHandleRefreshNewItemsEvent': ('internal', 'fun'),
    'resolvePagedFeedPageToFetch': ('internal', 'fun'),
    'shouldAdvancePagedFeedOnManualRefresh': ('internal', 'fun'),
    'resolvePagedFeedPageIndexAfterFetch': ('internal', 'fun'),
    'resolveHomeFollowRefreshNewItemsCount': ('internal', 'fun'),
    'shouldFullReplaceFollowFeedAfterBaselineProbe': ('internal', 'fun'),
    'shouldShowRecommendOldContentDivider': ('internal', 'fun'),
    'resolveHomeRefreshKeptOldItemCount': ('internal', 'fun'),
    'resolveOldContentDividerContainerColor': ('internal', 'fun'),
    'OLD_CONTENT_DIVIDER_CONTAINER_ALPHA': ('internal', 'val'),
}
STATIC_ROOTS = (
    'app/src/main/java',
    'design-system/src/main/java',
    'core-data/src/main/java',
    'core-player/src/main/java',
    'desktop/src',
    'desktop/upstream-slices',
)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()}


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


def prepare_block(build):
    start = build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {')
    return build[start:build.index('\nval extractUpstreamApi ', start)]


def run_cli(output, seed):
    environment = dict(os.environ, PYTHONHASHSEED=str(seed), PYTHONIOENCODING='utf-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1')
    done = subprocess.run(
        [sys.executable, '-X', 'utf8', '-B', str(REPO / 'desktop' / PRODUCER),
         '--repo', str(REPO), '--output', str(output)],
        cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode('utf-8', 'replace')[-4000:])
    return done.stdout.decode('utf-8')


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, [(item.visibility, item.kind, item.name) for item in info.decls]


def declaration_hits(root):
    hits = []
    base = Path(root)
    if not base.exists():
        return hits
    for path in base.rglob('*'):
        if not path.is_file() or path.suffix not in {'.kt', '.kts'}:
            continue
        raw = path.read_bytes()
        if not any(token.encode('utf-8') in raw for token in NEW_SYMBOLS):
            continue
        text = raw.decode('utf-8')
        if any(token in text for token in NEW_SYMBOLS):
            hits.append(path.relative_to(REPO).as_posix())
    return hits


class V046HomeRefreshUiPolicySourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()}
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-hru-')
        cls.root = Path(cls.temporary.name)
        cls.stdout = run_cli(cls.root / 'main', 0)
        cls.main = tree(cls.root / 'main')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        original = {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()}
        self.assertEqual(self.archive, original)
        leaves = sorted(original)
        self.assertEqual(sorted(policy.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-hru-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / policy.ARCHIVE, repo / policy.ARCHIVE)
                target = repo / policy.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    policy.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-hru-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / policy.ARCHIVE, repo / policy.ARCHIVE)
            (repo / policy.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                policy.fixed(repo)
        self.assertEqual(original, {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()})

    def test_outputs_match_the_archive_and_the_upstream_git_blobs(self):
        self.assertIn('original Home outputs', self.stdout)
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual([row['filename'] for row in manifest['files']], list(policy.MAIN_FILES))
        vendored = (REPO / CANONICAL).read_bytes()
        for row in manifest['files']:
            with self.subTest(name=row['filename']):
                archived = self.archive[row['filename']]
                self.assertEqual(archived, (REPO / policy.ARCHIVE / row['filename']).read_bytes())
                self.assertNotEqual(vendored, archived)
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', policy.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, archived)
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                matches = [path for path, raw in self.main.items() if raw == archived]
                self.assertEqual([policy.RELATIVE[row['filename']]], matches)
                self.assertTrue(matches[0].endswith('/' + row['filename']))
                self.assertNotIn('\r', archived.decode('utf-8'))

    def test_emit_is_deterministic_and_a_rerun_matches_the_archive(self):
        def once(seed):
            directory = self.root / ('emit-' + str(seed))
            receipt = policy.emit(REPO, directory)
            return receipt, tree(directory)

        first, second = once(1), once(2)
        self.assertEqual(first, second)
        self.assertEqual([policy.RELATIVE[name] for name in policy.MAIN_FILES], [row['path'] for row in first[0]['files']])
        for row in first[0]['files']:
            self.assertEqual(sha(self.archive[Path(row['path']).name]), row['sha256'])
        rerun = policy.emit(REPO, self.root / 'emit-1')
        self.assertEqual(first[0], rerun)
        self.assertEqual(first[1], tree(self.root / 'emit-1'))
        for name in policy.MAIN_FILES:
            self.assertEqual(self.archive[name], first[1][policy.RELATIVE[name]])
        cli_main = self.root / 'cli-same'
        first_stdout = run_cli(cli_main, 4)
        first_tree = tree(cli_main)
        second_stdout = run_cli(cli_main, 4)
        self.assertEqual(first_stdout, second_stdout)
        self.assertIn('original Home outputs', first_stdout)
        self.assertEqual(first_tree, tree(cli_main))
        for name in policy.MAIN_FILES:
            self.assertEqual(self.archive[name], first_tree[policy.RELATIVE[name]])
        again = run_cli(self.root / 'main-2', 7)
        self.assertEqual(self.stdout, again)
        self.assertEqual(self.main, tree(self.root / 'main-2'))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()}
        nested = REPO / policy.ARCHIVE / 'child'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                policy.emit(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                policy.validate_outputs(REPO, REPO)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                page.generate(REPO, nested)
            self.assertFalse(nested.exists())
            self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()})
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / policy.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    def test_a_stale_regular_file_is_replaced_and_special_targets_fail_closed(self):
        relatives = [policy.RELATIVE[name] for name in policy.MAIN_FILES]

        def prepare(root, relative):
            (root / 'outside.txt').write_bytes(b'OUTSIDE')
            target = root / relative
            target.parent.mkdir(parents=True)
            (target.parent / 'decoy.kt').write_bytes(b'DECOY')
            return target

        for relative in relatives:
            with self.subTest(kind='stale', relative=relative), tempfile.TemporaryDirectory(prefix='bp-hru-') as tmp:
                root = Path(tmp)
                before_archive = archive_files()
                target = prepare(root, relative)
                target.write_bytes(b'SENTINEL-FOREIGN')
                policy.emit(REPO, root)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                policy.emit(REPO, root)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                for name in policy.MAIN_FILES:
                    self.assertEqual(self.archive[name], (root / policy.RELATIVE[name]).read_bytes())
                self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                self.assertEqual(before_archive, archive_files())
        for kind in ('symlink', 'directory', 'fifo'):
            for relative in relatives:
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-hru-') as tmp:
                    if kind == 'fifo' and not hasattr(os, 'mkfifo'):
                        self.skipTest('os.mkfifo is unavailable')
                    root = Path(tmp)
                    before_archive = archive_files()
                    target = prepare(root, relative)
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
                        policy.emit(REPO, root)
                    self.assertIn('not a regular file: ' + relative, str(caught.exception))
                    self.assertEqual(before, frozen(root))
                    self.assertEqual(before_archive, archive_files())
                    self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                    self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())

    def test_gradle_task_and_inventory_wire_the_archive_and_exclude_the_direct_copy(self):
        build = self.build
        line = 'exclude(canonicalOriginalIdentity("' + CANONICAL + '"))'
        block = task_block(build, 'extractOriginalHomePage')
        prepared = prepare_block(build)
        self.assertEqual(1, build.count(line), line)
        self.assertEqual(1, prepared.count(line), line)
        model = gate.parse_gradle(build)
        self.assertEqual(1, model.sync_excludes.count(CANONICAL))
        for literal in ('inputs.file("tools/v046_home_refresh_ui_policy.py")',
                        'inputs.dir("upstream-slices/v046-home-refresh-ui-policy")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        src_lines = [row for row in build.splitlines() if 'srcDir' in row and 'home-refresh-ui-policy' in row]
        self.assertEqual([], src_lines)
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/home-page"))'))
        full = task_block(build, 'extractUpstreamHomeFullCard')
        self.assertNotIn('v046_home_refresh_ui_policy', full)
        self.assertNotIn('v046-home-refresh-ui-policy', full)
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        listed = [row for row in sources['sources'] if row['path'] == CANONICAL]
        self.assertEqual(1, len(listed))
        self.assertEqual('direct', listed[0]['mode'])
        discovery = (TOOLS / 'extract-discovery-platform.py').read_text(encoding='utf-8')
        self.assertEqual(1, discovery.count('BASE + "feature/home/HomeRefreshUiPolicy.kt": "direct"'))
        vendored = (REPO / CANONICAL).read_text(encoding='utf-8')
        for token in NEW_SYMBOLS:
            self.assertNotIn(token, vendored, token)
        static = []
        for relative in STATIC_ROOTS:
            static.extend(declaration_hits(REPO / relative))
        self.assertEqual([policy.ARCHIVE + '/HomeRefreshUiPolicy.kt'], static)
        self.assertIn(policy.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(policy.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(policy.ARCHIVE + '/')]
        self.assertEqual(1, len(rows))
        self.assertEqual([CANONICAL], [row['path'] for row in rows])
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-home-refresh-ui-policy'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(policy.COMMIT, row['upstreamCommit'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == policy.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({policy.ARCHIVE + '/' + name for name in policy.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(policy.ARCHIVE + '/')])
        producer = (TOOLS / 'extract-upstream-home-page.py').read_text(encoding='utf-8')
        start = producer.index('def generate(')
        cascade_validate = producer.index('validate_cascade_outputs(repo, output)', start)
        refresh_validate = 'from v046_home_refresh_ui_policy import validate_outputs as validate_refresh_ui_policy_outputs'
        refresh_at = producer.index(refresh_validate, start)
        check = producer.index('from v043_home_recommendation_badges import check_output', start)
        resolve_at = producer.index('output=Path(output).resolve()', start)
        self.assertEqual(1, producer.count(refresh_validate))
        self.assertEqual(1, producer.count('validate_refresh_ui_policy_outputs(repo, output)'))
        self.assertLess(cascade_validate, refresh_at)
        self.assertLess(refresh_at, check)
        self.assertLess(refresh_at, resolve_at)
        emit_import = 'from v046_home_refresh_ui_policy import emit as emit_home_refresh_ui_policy'
        self.assertEqual(1, producer.count(emit_import))
        self.assertEqual(1, producer.count('emit_home_refresh_ui_policy(repo, output)'))
        cascade_call = producer.index('emit_home_card_cascade_definers(repo, output)', start)
        emit_at = producer.index(emit_import, start)
        call_at = producer.index('emit_home_refresh_ui_policy(repo, output)', emit_at)
        ret_at = producer.index('return written', call_at)
        self.assertLess(cascade_call, emit_at)
        self.assertLess(emit_at, call_at)
        self.assertLess(call_at, ret_at)
        self.assertEqual('emit_home_refresh_ui_policy(repo, output)\n    return written',
                         producer[call_at:ret_at + len('return written')])
        self.assertFalse(any(line.startswith('import v046_home_refresh_ui_policy') or
                             line.startswith('from v046_home_refresh_ui_policy') for line in producer.splitlines()))

    def test_each_top_level_name_is_declared_once(self):
        text = self.archive['HomeRefreshUiPolicy.kt'].decode('utf-8')
        package, decls = declarations(text, 'HomeRefreshUiPolicy.kt')
        self.assertEqual(policy.PACKAGE['HomeRefreshUiPolicy.kt'], package)
        self.assertEqual(
            {(visibility, kind, name) for name, (visibility, kind) in TOP_LEVEL.items()},
            {(visibility, kind, declared) for visibility, kind, declared in decls})
        scratch = tempfile.mkdtemp(prefix='bp-hru-')
        try:
            root, env = gate.make_scratch_repo(REPO, scratch)
            model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
            self.assertEqual(1, model.sync_excludes.count(CANONICAL))
            tasks, _skipped = gate.select_producers(model)
            results = gate.run_producers(root, tasks, env)
            failed = [(row['task'], row['returncode'], row['stderrTail']) for row in results if row['returncode']]
            self.assertEqual([], failed)
            self.assertGreater(len(results), 100)
            self.assertIn('extractOriginalHomePage', {row['task'] for row in results})
            hits = []
            roots = [root / 'desktop/src', root / 'desktop/build']
            names = set(TOP_LEVEL)
            for base in roots:
                for path in base.rglob('*'):
                    if not path.is_file() or path.suffix not in {'.kt', '.kts'}:
                        continue
                    raw = path.read_bytes()
                    if not any(name.encode('utf-8') in raw for name in names):
                        continue
                    relative = path.relative_to(root).as_posix()
                    package, decls = declarations(raw.decode('utf-8'), relative)
                    for visibility, _kind, declared in decls:
                        if declared in names:
                            hits.append((package, visibility, declared, relative))
            counts = {name: [hit for hit in hits if hit[2] == name] for name in names}
            for name, found in counts.items():
                self.assertEqual(1, len(found), (name, found))
                package, visibility, _declared, relative = found[0]
                want_visibility, _kind = TOP_LEVEL[name]
                self.assertEqual(policy.PACKAGE['HomeRefreshUiPolicy.kt'], package, name)
                self.assertEqual(want_visibility, visibility, name)
                self.assertTrue(relative.startswith('desktop/build/generated/home-page/'), relative)
                self.assertTrue(relative.endswith(RELATIVE), relative)
                self.assertNotIn('generated/upstream/', relative)
            others = [hit for hit in hits if hit[2] in {
                'resolveOldContentDividerContainerColor', 'OLD_CONTENT_DIVIDER_CONTAINER_ALPHA'}]
            self.assertEqual(2, len(others))
            self.assertEqual({RELATIVE}, {hit[3][len('desktop/build/generated/home-page/'):] for hit in others})
        finally:
            shutil.rmtree(scratch, ignore_errors=True)

    def test_emitted_sources_keep_balanced_delimiters(self):
        for name in policy.MAIN_FILES:
            text = self.main[policy.RELATIVE[name]].decode('utf-8')
            self.assertEqual(self.archive[name].decode('utf-8'), text)
            self.assertNotIn('\r', text)
            self.balanced(text)

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
