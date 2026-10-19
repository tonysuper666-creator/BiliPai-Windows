"""v0.4.6 VideoCardAdaptiveTintPolicy and WallpaperReadabilityPolicy emitted whole by the home-page producer."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
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
import v046_home_card_tint_policy as policy

spec = importlib.util.spec_from_file_location('sync_upstream_home_card_tint_policy', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

page_spec = importlib.util.spec_from_file_location(
    'extract_upstream_home_page_card_tint_policy', TOOLS / 'extract-upstream-home-page.py')
page = importlib.util.module_from_spec(page_spec)
page_spec.loader.exec_module(page)

full_spec = importlib.util.spec_from_file_location(
    'extract_upstream_home_full_card_tint_policy', TOOLS / 'extract-upstream-home-full-card.py')
full_card = importlib.util.module_from_spec(full_spec)
full_spec.loader.exec_module(full_card)

PRODUCER = 'tools/extract-upstream-home-page.py'
TINT = 'VideoCardAdaptiveTintPolicy.kt'
READABILITY = 'WallpaperReadabilityPolicy.kt'
CANONICAL = policy.UPSTREAM_PATHS[TINT]
READABILITY_PATH = policy.UPSTREAM_PATHS[READABILITY]
V025_TINT_SHA256 = 'f4619ed5fe5023360a15e15d62cd0e7204516c7836d838356111f608c3eab7b6'
V025_TINT_BLOB = '6f774ef1f25bd19ee204146f11787143f52f4b86'
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopHomeCardTintPolicyTest.kt'
TOP_LEVEL = {
    'WallpaperPalette': (TINT, 'public', 'class'),
    'LocalWallpaperPalette': (TINT, 'public', 'val'),
    'LocalHomeCardDynamicTintEnabled': (TINT, 'public', 'val'),
    'LocalHomeCardFrostedGlassEnabled': (TINT, 'public', 'val'),
    'LocalHomeScrollTickProvider': (TINT, 'public', 'val'),
    'LocalHomeCardScrolling': (TINT, 'public', 'val'),
    'LocalHomeCardTransitionRunning': (TINT, 'public', 'val'),
    'SamplingRegion': (TINT, 'public', 'class'),
    'VideoCardAmbientDrawSpec': (TINT, 'public', 'class'),
    'resolveCoverBottomSamplingRegion': (TINT, 'public', 'fun'),
    'shouldUseCoverTintForCard': (TINT, 'internal', 'fun'),
    'interpolateWallpaperColor': (TINT, 'public', 'fun'),
    'resolveWallpaperGlassAlpha': (TINT, 'public', 'fun'),
    'resolveVideoCardAmbientDrawSpec': (TINT, 'public', 'fun'),
    'VideoCardAdaptiveContentColors': (TINT, 'public', 'class'),
    'resolveVideoCardAdaptiveContentColors': (TINT, 'public', 'fun'),
    'WallpaperLuminanceBand': (READABILITY, 'public', 'class'),
    'WallpaperReadabilityProfile': (READABILITY, 'public', 'class'),
    'resolveWallpaperProtectionAlpha': (READABILITY, 'public', 'fun'),
    'resolveWallpaperBodyTintAlpha': (READABILITY, 'public', 'fun'),
}
# Parameters of the v0.4.6 declarations a compiled caller may name or pass positionally.
CONTENT_COLOR_PARAMS = ('wallpaperPalette', 'coverTint', 'wallpaperTintEnabled', 'isDarkTheme', 'defaultOnSurface',
                        'defaultOnSurfaceVariant', 'homeCardDynamicTintEnabled', 'wallpaperYFraction',
                        'wallpaperGlassAlpha')
AMBIENT_PARAMS = ('wallpaperPalette', 'yFraction', 'coverTint', 'wallpaperTintEnabled', 'isDarkTheme',
                  'defaultContainerColor', 'defaultBorderColor', 'isDataSaverActive', 'frostedGlassEnabled',
                  'dynamicTintEnabled')
PALETTE_PARAMS = ('topColor', 'bottomColor', 'dominantColor', 'stops', 'readability')
CALLEES = {
    'resolveVideoCardAdaptiveContentColors': CONTENT_COLOR_PARAMS,
    'resolveVideoCardAmbientDrawSpec': AMBIENT_PARAMS,
    'WallpaperPalette': PALETTE_PARAMS,
}
JVM_TEST_COUNT = 14


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


def call_arguments(text, name):
    """(labels, positional count) of every call of `name(...)`; declarations and member calls are skipped."""
    tokens = sync.kotlin_tokens(text)
    found = []
    for index, (value, _first, _end) in enumerate(tokens[:-1]):
        if value != name or tokens[index + 1][0] != '(':
            continue
        if index and tokens[index - 1][0] in ('fun', 'class', '.'):
            continue
        depth, at, labels, positional, start_of_argument = 0, index + 1, [], 0, True
        while at < len(tokens):
            token = tokens[at][0]
            if token in ('(', '[', '{'):
                depth += 1
            elif token in (')', ']', '}'):
                depth -= 1
                if depth == 0:
                    break
            if depth == 1 and token == ',':
                start_of_argument = True
            elif depth == 1 and at > index + 1 and start_of_argument and token != ')':
                start_of_argument = False
                if at + 1 < len(tokens) and tokens[at + 1][0] == '=' and token.isidentifier():
                    labels.append(token)
                else:
                    positional += 1
            at += 1
        found.append((labels, positional))
    return found


class V046HomeCardTintPolicySourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = {p.name: p.read_bytes() for p in (REPO / policy.ARCHIVE).iterdir() if p.is_file()}
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-htp-')
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
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-htp-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / policy.ARCHIVE, repo / policy.ARCHIVE)
                target = repo / policy.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    policy.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-htp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / policy.ARCHIVE, repo / policy.ARCHIVE)
            (repo / policy.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                policy.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-htp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / policy.ARCHIVE, repo / policy.ARCHIVE)
            (repo / policy.ARCHIVE / 'LICENSE').unlink()
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
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', policy.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, archived)
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                matches = [path for path, raw in self.main.items() if raw == archived]
                self.assertEqual([policy.RELATIVE[row['filename']]], matches)
                self.assertTrue(matches[0].endswith('/' + row['filename']))
                self.assertNotIn('\r', archived.decode('utf-8'))
        self.assertNotEqual(vendored, self.archive[TINT])

    def test_the_vendored_copy_is_the_old_v025_file_and_the_new_members_are_not_in_it(self):
        vendored = (REPO / CANONICAL).read_bytes()
        self.assertEqual(V025_TINT_SHA256, sha(vendored.replace(b'\r\n', b'\n')))
        self.assertEqual(V025_TINT_BLOB, hashlib.sha1(b'blob ' + str(len(vendored)).encode() + b'\0' + vendored).hexdigest())
        text = vendored.decode('utf-8')
        for token in policy.ADDED_TO_TINT_POLICY:
            with self.subTest(token=token):
                self.assertNotIn(token, text)
                self.assertIn(token, self.archive[TINT].decode('utf-8'))
        canonical = json.loads((TOOLS / 'v025-canonical-sources.json').read_text(encoding='utf-8'))
        self.assertEqual(V025_TINT_SHA256, canonical['paths'][CANONICAL]['sha256'])
        self.assertEqual(V025_TINT_BLOB, canonical['paths'][CANONICAL]['blob'])
        # v0.4.6 is a strict superset: every top-level name of the old copy is still declared by the archive file
        old_package, old_decls = declarations(text, CANONICAL)
        new_package, new_decls = declarations(self.archive[TINT].decode('utf-8'), TINT)
        self.assertEqual(old_package, new_package)
        self.assertEqual(set(old_decls) - set(new_decls), set())
        self.assertEqual({'resolveWallpaperGlassAlpha', 'LocalHomeCardScrolling', 'LocalHomeCardTransitionRunning'},
                         {name for _vis, _kind, name in set(new_decls) - set(old_decls)})
        # the readability file is new to the tree: no vendored copy and no other declaration of its names
        self.assertFalse((REPO / READABILITY_PATH).exists())
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        self.assertEqual([], [row for row in sources['sources'] if row['path'] == READABILITY_PATH])

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
            with self.subTest(kind='stale', relative=relative), tempfile.TemporaryDirectory(prefix='bp-htp-') as tmp:
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
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-htp-') as tmp:
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
        self.assertNotIn('WallpaperReadabilityPolicy', prepared)
        model = gate.parse_gradle(build)
        self.assertEqual(1, model.sync_excludes.count(CANONICAL))
        self.assertEqual(0, model.sync_excludes.count(READABILITY_PATH))
        for literal in ('inputs.file("tools/v046_home_card_tint_policy.py")',
                        'inputs.dir("upstream-slices/v046-home-card-tint-policy")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        src_lines = [row for row in build.splitlines() if 'srcDir' in row and 'home-card-tint-policy' in row]
        self.assertEqual([], src_lines)
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/home-page"))'))
        full = task_block(build, 'extractUpstreamHomeFullCard')
        self.assertNotIn('v046_home_card_tint_policy', full)
        self.assertNotIn('v046-home-card-tint-policy', full)
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        listed = [row for row in sources['sources'] if row['path'] == CANONICAL]
        self.assertEqual(1, len(listed))
        self.assertEqual('direct', listed[0]['mode'])
        self.assertEqual(V025_TINT_SHA256, listed[0]['sha256'])
        self.assertIn('home-full-card', listed[0]['features'])
        self.assertIn(policy.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(policy.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(policy.ARCHIVE + '/')]
        self.assertEqual(2, len(rows))
        self.assertEqual([CANONICAL, READABILITY_PATH], [row['path'] for row in rows])
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-home-card-tint-policy'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(policy.COMMIT, row['upstreamCommit'])
            self.assertEqual('adaptedSource', row['kind'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == policy.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({policy.ARCHIVE + '/' + name for name in policy.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(policy.ARCHIVE + '/')])
        producer = (TOOLS / 'extract-upstream-home-page.py').read_text(encoding='utf-8')
        start = producer.index('def generate(')
        palette_validate = producer.index('validate_identity_badge_palette_outputs(repo, output)', start)
        tint_validate = 'from v046_home_card_tint_policy import validate_outputs as validate_home_card_tint_policy_outputs'
        tint_at = producer.index(tint_validate, start)
        check = producer.index('from v043_home_recommendation_badges import check_output', start)
        resolve_at = producer.index('output=Path(output).resolve()', start)
        self.assertEqual(1, producer.count(tint_validate))
        self.assertEqual(1, producer.count('validate_home_card_tint_policy_outputs(repo, output)'))
        self.assertLess(palette_validate, tint_at)
        self.assertLess(tint_at, check)
        self.assertLess(tint_at, resolve_at)
        emit_import = 'from v046_home_card_tint_policy import emit as emit_home_card_tint_policy'
        self.assertEqual(1, producer.count(emit_import))
        self.assertEqual(1, producer.count('emit_home_card_tint_policy(repo, output)'))
        refresh_call = producer.index('emit_home_refresh_ui_policy(repo, output)', start)
        emit_at = producer.index(emit_import, start)
        call_at = producer.index('emit_home_card_tint_policy(repo, output)', emit_at)
        ret_at = producer.index('return written', call_at)
        self.assertLess(refresh_call, emit_at)
        self.assertLess(emit_at, call_at)
        self.assertLess(call_at, ret_at)
        self.assertEqual('emit_home_card_tint_policy(repo, output)\n    return written',
                         producer[call_at:ret_at + len('return written')])
        self.assertFalse(any(line.startswith('import v046_home_card_tint_policy') or
                             line.startswith('from v046_home_card_tint_policy') for line in producer.splitlines()))

    def test_the_full_card_producer_keeps_its_pin_and_writes_no_tint_policy_in_production(self):
        # DIRECT still names the file: its identity must keep matching upstream-sources.json, which stays "direct".
        self.assertIn(CANONICAL, full_card.DIRECT)
        self.assertNotIn(CANONICAL, full_card.EXTRACTED)
        self.assertNotIn(CANONICAL, full_card.ADAPTED)
        rows = {row['path']: row for row in full_card.inventory(REPO)}
        self.assertEqual('direct', rows[CANONICAL]['mode'])
        self.assertEqual(V025_TINT_SHA256, rows[CANONICAL]['sha256'])
        # the shared Sync no longer copies it, so production must never emit a second copy from the full-card side
        producer = (TOOLS / 'extract-upstream-home-full-card.py').read_text(encoding='utf-8')
        self.assertEqual(1, producer.count("if path not in DIRECT or standalone:write(out,"))
        with tempfile.TemporaryDirectory(prefix='bp-htp-full-') as tmp:
            full_card.generate(REPO, Path(tmp))
            written = {p.name for p in Path(tmp).rglob('*.kt')}
            self.assertNotIn(TINT, written)
            self.assertNotIn(READABILITY, written)

    def test_each_top_level_name_is_declared_once_and_every_compiled_caller_still_matches(self):
        for name in policy.MAIN_FILES:
            text = self.archive[name].decode('utf-8')
            package, decls = declarations(text, name)
            self.assertEqual(policy.PACKAGE[name], package)
            self.assertEqual(
                {(visibility, kind, declared) for declared, (file, visibility, kind) in TOP_LEVEL.items() if file == name},
                set(decls))
        scratch = tempfile.mkdtemp(prefix='bp-htp-')
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
            compiled, _notes = gate.collect_sources(root, model)
            compiled = [source for source in compiled if source.set == 'main']
            self.assertEqual([], [s.logical for s in compiled if s.origin == 'vendored' and s.logical == CANONICAL])
            self.assertEqual(
                ['build/generated/home-page/' + policy.RELATIVE[TINT]],
                [s.logical for s in compiled if s.logical.endswith('/' + TINT)])
            self.assertEqual(
                ['build/generated/home-page/' + policy.RELATIVE[READABILITY]],
                [s.logical for s in compiled if s.logical.endswith('/' + READABILITY)])
            names = set(TOP_LEVEL)
            hits = []
            callers = []
            for source in compiled:
                raw = Path(source.real).read_bytes()
                if any(name.encode('utf-8') in raw for name in names):
                    package, decls = declarations(raw.decode('utf-8'), source.logical)
                    for visibility, _kind, declared in decls:
                        if declared in names:
                            hits.append((package, visibility, declared, source.logical))
                if any(callee.encode('utf-8') + b'(' in raw for callee in CALLEES):
                    text = raw.decode('utf-8')
                    for callee, allowed in CALLEES.items():
                        for labels, positional in call_arguments(text, callee):
                            callers.append((callee, source.logical, labels, positional, allowed))
            for name, (file, visibility, _kind) in TOP_LEVEL.items():
                found = [hit for hit in hits if hit[2] == name]
                self.assertEqual(1, len(found), (name, found))
                package, declared_visibility, _declared, logical = found[0]
                self.assertEqual(policy.PACKAGE[file], package, name)
                self.assertEqual(visibility, declared_visibility, name)
                self.assertEqual('build/generated/home-page/' + policy.RELATIVE[file], logical, name)
            # every compiled caller of the three changed signatures still passes only parameters v0.4.6 declares
            self.assertGreaterEqual(len(callers), 6)
            seen = {callee for callee, *_rest in callers}
            self.assertEqual(set(CALLEES), seen)
            for callee, logical, labels, positional, allowed in callers:
                with self.subTest(callee=callee, source=logical):
                    self.assertTrue(set(labels) <= set(allowed), (labels, allowed))
                    self.assertLessEqual(len(labels) + positional, len(allowed))
                    if positional:
                        self.assertEqual([], labels)
        finally:
            shutil.rmtree(scratch, ignore_errors=True)

    def test_emitted_sources_keep_balanced_delimiters(self):
        for name in policy.MAIN_FILES:
            text = self.main[policy.RELATIVE[name]].decode('utf-8')
            self.assertEqual(self.archive[name].decode('utf-8'), text)
            self.assertNotIn('\r', text)
            self.balanced(text)

    def test_the_hand_written_jvm_test_covers_the_new_members_and_is_well_formed(self):
        raw = JVM_TEST.read_bytes()
        self.assertNotIn(b'\r', raw)
        text = raw.decode('utf-8')
        self.assertTrue(text.startswith('package com.bilipai.desktop.ui\n'))
        self.assertEqual(JVM_TEST_COUNT, len(re.findall(r'(?m)^    @Test\n    fun ', text)))
        self.assertEqual(JVM_TEST_COUNT, text.count('@Test'))
        self.assertEqual(1, text.count('class DesktopHomeCardTintPolicyTest {'))
        self.balanced(text)
        imported = set(re.findall(r'(?m)^import ([\w.]+)$', text))
        for name in ('resolveWallpaperGlassAlpha', 'LocalHomeCardScrolling', 'LocalHomeCardTransitionRunning',
                     'WallpaperReadabilityProfile', 'WallpaperLuminanceBand', 'resolveWallpaperProtectionAlpha',
                     'resolveWallpaperBodyTintAlpha', 'resolveVideoCardAdaptiveContentColors',
                     'resolveVideoCardAmbientDrawSpec', 'WallpaperPalette', 'interpolateWallpaperColor'):
            with self.subTest(name=name):
                file, _visibility, _kind = TOP_LEVEL[name]
                self.assertIn(policy.PACKAGE[file] + '.' + name, imported)
                self.assertLess(text.index('import ' + policy.PACKAGE[file] + '.' + name),
                                text.index('class DesktopHomeCardTintPolicyTest {'))
        # imports are sorted and unique so a copy mistake shows up as a diff, not as a compile error
        self.assertEqual(sorted(imported), [line for line in re.findall(r'(?m)^import ([\w.]+)$', text)])
        self.assertEqual(len(imported), len(re.findall(r'(?m)^import ', text)))

    def test_the_symbol_gate_data_retires_the_seed_and_drops_the_two_closed_gaps(self):
        expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        seed = 'resolveWallpaperGlassAlpha'
        self.assertEqual([], [e for e in expected['latent'] if e['symbol'] == seed])
        retired = [e for e in expected['retired'] if e['symbol'] == seed]
        self.assertEqual(1, len(retired))
        self.assertEqual('declaration', retired[0]['kind'])
        self.assertEqual('com.android.purebilibili.feature.home.components.cards', retired[0]['package'])
        self.assertEqual('fnd-home-card-cascade-definers', retired[0]['closedBy'])
        self.assertIn('v046-home-card-tint-policy', retired[0]['retiredBy'])
        self.assertIn('v046_home_card_tint_policy.py', retired[0]['retiredBy'])
        self.assertIn('test_v046_home_card_tint_policy_source.py', retired[0]['guardedBy'])
        gaps = {g['symbol'] for g in expected['gaps']}
        for closed in ('LocalHomeCardScrolling', 'LocalHomeCardTransitionRunning'):
            self.assertEqual([], [symbol for symbol in gaps if symbol.rsplit('.', 1)[-1] == closed])
        for declared in TOP_LEVEL:
            self.assertEqual([], [symbol for symbol in gaps if symbol.rsplit('.', 1)[-1] == declared], declared)

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
