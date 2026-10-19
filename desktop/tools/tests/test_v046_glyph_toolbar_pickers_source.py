"""v0.4.6 glyph text style, pickers, floating toolbar and window action menu emitted whole by the detail producer."""
from pathlib import Path
import difflib
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
import v046_glyph_toolbar_pickers as glyph

spec = importlib.util.spec_from_file_location('sync_upstream_glyph_toolbar_pickers', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

page_spec = importlib.util.spec_from_file_location(
    'extract_upstream_dynamic_detail_container_glyph', TOOLS / 'extract-upstream-dynamic-detail-container.py')
page = importlib.util.module_from_spec(page_spec)
page_spec.loader.exec_module(page)

PRODUCER = 'tools/extract-upstream-dynamic-detail-container.py'
TASK = 'extractUpstreamDynamicDetailContainer'
FEATURE = 'v046-glyph-toolbar-pickers'
VERBATIM = ('AppDurationPicker.kt', 'AppFloatingToolbar.kt', 'AppNumberPicker.kt', 'AppWindowActionMenu.kt')
# The two vendored v0.2.5 direct-mode files that the archive replaces whole.
REPLACED = ('AppPrimitiveThemeDefaults.kt', 'AppWindowActionMenu.kt')
NEW_FILES = ('AppDurationPicker.kt', 'AppFloatingToolbar.kt', 'AppNumberPicker.kt')
TOP_LEVEL = {
    'AppDurationPicker.kt': {('public', 'fun', 'AppDurationPicker')},
    'AppFloatingToolbar.kt': {('public', 'fun', 'AppFloatingToolbar')},
    'AppNumberPicker.kt': {('public', 'fun', 'AppNumberPicker')},
    'AppPrimitiveThemeDefaults.kt': {
        ('internal', 'fun', 'currentAppContentColor'),
        ('internal', 'fun', 'currentAppTextStyle'),
        ('public', 'fun', 'appIconGlyphTextStyle'),
    },
    'AppWindowActionMenu.kt': {
        ('private', 'fun', 'toDropdownItem'),
        ('public', 'class', 'AppWindowAction'),
        ('public', 'fun', 'AppWindowActionMenu'),
    },
}
# The names that must have exactly one definer in the compiled universe (the private helper is file-local).
GLOBAL_NAMES = ('AppDurationPicker', 'AppFloatingToolbar', 'AppNumberPicker', 'appIconGlyphTextStyle',
                'AppWindowAction', 'AppWindowActionMenu')
STATIC_ROOTS = (
    'app/src/main/java',
    'design-system/src/main/java',
    'core-data/src/main/java',
    'core-player/src/main/java',
    'desktop/src',
    'desktop/upstream-slices',
)
MIUIX = 'desktop/third-party/miuix5157/upstream/miuix-ui/src/commonMain/kotlin/'
# The eight carried locations that import and call AppFloatingToolbar (symbol gate gap closed by this slice).
TOOLBAR_CONSUMERS = (
    'v035-favorite-cover/CommonListScreen.kt',
    'v037-explicit-tab-roles/CommonListScreen.kt',
    'v037-explicit-tab-roles/FavoriteCategoryScreen.kt',
    'v037-explicit-tab-roles/WatchLaterScreen.kt',
    'v041-video-status-labels/WatchLaterScreen.kt',
    'v044-favorite-detail/CommonListScreen.kt',
    'v044-favorite-subscription/CommonListScreen.kt',
    'v046-favorite-editor/CommonListScreen.kt',
)
OWNER = {
    'AppDurationPicker': 'AppDurationPicker.kt',
    'AppFloatingToolbar': 'AppFloatingToolbar.kt',
    'AppNumberPicker': 'AppNumberPicker.kt',
    'appIconGlyphTextStyle': 'AppPrimitiveThemeDefaults.kt',
    'AppWindowAction': 'AppWindowActionMenu.kt',
    'AppWindowActionMenu': 'AppWindowActionMenu.kt',
}
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopGlyphToolbarPickersTest.kt'
SHEET_JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopSheetMotionPolicyTest.kt'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / glyph.ARCHIVE).iterdir() if p.is_file()}


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


def upstream_blob(path):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', glyph.COMMIT + ':' + path],
                          cwd=str(REPO), capture_output=True, check=True).stdout


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, {(item.visibility, item.kind, item.name) for item in info.decls}


def declaration_hits(root, names):
    """Repository paths under root whose top-level declarations include one of names."""
    hits = []
    base = Path(root)
    if not base.exists():
        return hits
    for path in base.rglob('*'):
        if not path.is_file() or path.suffix not in {'.kt', '.kts'}:
            continue
        raw = path.read_bytes()
        if not any(name.encode('utf-8') in raw for name in names):
            continue
        _package, decls = declarations(raw.decode('utf-8'), path.name)
        found = sorted({kind_name[2] for kind_name in decls} & set(names))
        if found:
            hits.append((path.relative_to(REPO).as_posix(), found))
    return hits


def parenthesized(tokens, open_at):
    """Split the balanced parentheses that start at open_at into depth-1 comma separated token lists."""
    depth, parts, current = 0, [], []
    for index in range(open_at, len(tokens)):
        text = tokens[index][0]
        if text in '([{' and len(text) == 1:
            depth += 1
            if depth == 1:
                continue
        elif text in ')]}' and len(text) == 1:
            depth -= 1
            if depth == 0:
                if current:
                    parts.append(current)
                return parts, index
        if depth == 1 and text == ',':
            parts.append(current)
            current = []
        else:
            current.append(tokens[index])
    raise AssertionError('Unbalanced parentheses')


def parameters(source, name, keyword='fun'):
    """(label, has_default) of the first declaration `keyword name(` in source (primary constructor for a class)."""
    tokens = sync.kotlin_tokens(source)
    found = [i for i, token in enumerate(tokens[:-2])
             if token[0] == keyword and tokens[i + 1][0] == name and tokens[i + 2][0] == '(']
    assert found, name
    parts, _end = parenthesized(tokens, found[0] + 2)
    rows = []
    for part in parts:
        texts = [token[0] for token in part]
        label = next(text for text in texts if text not in {'vararg', 'noinline', 'crossinline', 'val', 'var'})
        depth, has_default = 0, False
        for text in texts:
            if text in ('(', '[', '{'):
                depth += 1
            elif text in (')', ']', '}'):
                depth -= 1
            elif text == '=' and depth == 0:
                has_default = True
        rows.append((label, has_default))
    return rows


def calls(text, names):
    """Every call of one of the names: positional count, named arguments, trailing lambda; no declarations."""
    tokens = sync.kotlin_tokens(text)
    found = []
    for index, token in enumerate(tokens):
        if token[0] not in names or index + 1 >= len(tokens):
            continue
        if index and tokens[index - 1][0] in {'fun', '::', 'class'}:
            continue
        following = tokens[index + 1][0]
        if following not in ('(', '{'):
            continue
        positional, named, end, lambda_only = 0, [], index, following == '{'
        if following == '(':
            parts, end = parenthesized(tokens, index + 1)
            for part in parts:
                if (len(part) >= 3 and part[1][0] == '=' and part[2][0] != '=' and
                        re.fullmatch(r'[^\W\d]\w*', part[0][0])):
                    named.append(part[0][0])
                else:
                    positional += 1
        trailing = lambda_only or (end + 1 < len(tokens) and tokens[end + 1][0] == '{')
        found.append(dict(name=token[0], positional=positional, named=named, trailing=trailing,
                          line=text.count('\n', 0, token[1]) + 1))
    return found


def callee_problem(call, params):
    labels = [label for label, _default in params]
    unknown = [label for label in call['named'] if label not in labels]
    if unknown:
        return 'unknown named argument ' + ','.join(unknown)
    supplied = call['positional'] + len(call['named']) + (1 if call['trailing'] else 0)
    if supplied > len(params):
        return 'too many arguments'
    covered = set(labels[:call['positional']]) | set(call['named'])
    if call['trailing']:
        if labels[-1] in covered:
            return 'trailing lambda collides with ' + labels[-1]
        covered.add(labels[-1])
    if len(covered) != call['positional'] + len(call['named']) + (1 if call['trailing'] else 0):
        return 'argument given twice'
    missing = [label for label, default in params if not default and label not in covered]
    return 'missing ' + ','.join(missing) if missing else ''


class V046GlyphToolbarPickersSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.sources = {name: cls.archive[name].decode('utf-8') for name in glyph.MAIN_FILES}
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-gtp-')
        cls.root = Path(cls.temporary.name)
        cls.stdout = run_cli(cls.root / 'main', 0)
        cls.main = tree(cls.root / 'main')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        original = archive_files()
        self.assertEqual(self.archive, original)
        leaves = sorted(original)
        self.assertEqual(sorted(glyph.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-gtp-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / glyph.ARCHIVE, repo / glyph.ARCHIVE)
                target = repo / glyph.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    glyph.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-gtp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / glyph.ARCHIVE, repo / glyph.ARCHIVE)
            (repo / glyph.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                glyph.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-gtp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / glyph.ARCHIVE, repo / glyph.ARCHIVE)
            (repo / glyph.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                glyph.fixed(repo)
        self.assertEqual(original, archive_files())

    def test_archive_matches_the_upstream_git_blobs_and_the_manifest_rows(self):
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual(sha(self.archive['manifest.json']), glyph.MANIFEST_SHA256)
        self.assertEqual(list(glyph.MAIN_FILES), [row['filename'] for row in manifest['files']])
        self.assertEqual(['jay3-yy/BiliPai', 'v0.4.6', glyph.COMMIT, 'raw'],
                         [manifest[key] for key in ('repository', 'tag', 'commit', 'hashNormalization')])
        for row in manifest['files']:
            with self.subTest(name=row['filename']):
                blob = upstream_blob(row['path'])
                self.assertEqual(blob, self.archive[row['filename']])
                self.assertEqual(glyph.UPSTREAM_PATHS[row['filename']], row['path'])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertEqual(row['sha256'], sha(blob))
                self.assertEqual(row['bytes'], len(blob))
                self.assertNotIn(b'\r', blob)
        self.assertEqual(upstream_blob('LICENSE'), self.archive['LICENSE'])
        self.assertEqual(set(manifest['windowsAdaptations']), {glyph.ADAPTED})
        self.assertEqual(sorted(manifest['verbatimFiles']), sorted(VERBATIM))
        self.assertTrue(manifest['selection'].isascii() and manifest['purpose'].isascii())

    def test_producer_emits_the_four_verbatim_files_and_the_adapted_theme_defaults(self):
        for name in VERBATIM:
            with self.subTest(name=name):
                emitted = self.main[glyph.RELATIVE[name]]
                self.assertEqual(self.archive[name], emitted)
                self.assertEqual([glyph.RELATIVE[name]], [p for p, raw in self.main.items() if raw == self.archive[name]])
        adapted = self.main[glyph.RELATIVE[glyph.ADAPTED]].decode('utf-8')
        self.assertNotEqual(self.sources[glyph.ADAPTED], adapted)
        bodies, receipt = glyph.main_bodies(REPO)
        self.assertEqual(bodies[glyph.ADAPTED], adapted.encode('utf-8'))
        proof = json.loads(self.main[glyph.PROOF_NAME].decode('utf-8'))
        self.assertEqual(receipt, proof)
        self.assertEqual(sorted(VERBATIM), sorted(proof['verbatimFiles']))
        for name in VERBATIM:
            self.assertEqual(sha(self.archive[name]), proof['verbatimFiles'][name])
        self.assertEqual([glyph.ADAPTED], sorted(proof['adaptedFiles']))
        self.assertTrue(proof['vendoredDirectCopiesExcluded'])
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertEqual(glyph.MANIFEST_SHA256, proof['sourceManifestSHA256'])
        # Nothing else of the glyph slice is written, and the original sheet motion output is still there.
        written = {path for path in self.main if path.startswith('com/android/purebilibili/core/ui/')}
        self.assertTrue({glyph.RELATIVE[name] for name in glyph.MAIN_FILES} <= written)
        self.assertIn('v046-sheet-motion-proof.json', self.main)

    def test_theme_defaults_drops_only_the_font_padding_and_restores_the_original(self):
        original = self.sources[glyph.ADAPTED]
        adapted, proof = glyph.windows_theme_defaults(original)
        self.assertEqual(adapted, self.main[glyph.RELATIVE[glyph.ADAPTED]].decode('utf-8'))
        self.assertEqual(original, glyph.restore(adapted, proof))
        self.assertEqual(sha(original.encode('utf-8')), proof['beforeSHA256'])
        self.assertEqual(sha(adapted.encode('utf-8')), proof['afterSHA256'])
        self.assertTrue(proof['wholeInverseExact'])
        for token in ('PlatformTextStyle', 'includeFontPadding'):
            self.assertIn(token, original)
            self.assertNotIn(token, adapted)
        delta = list(difflib.unified_diff(original.splitlines(), adapted.splitlines(), lineterm='', n=0))
        removed = [line[1:] for line in delta if line.startswith('-') and not line.startswith('---')]
        added = [line[1:] for line in delta if line.startswith('+') and not line.startswith('+++')]
        self.assertEqual(
            ['import androidx.compose.ui.text.PlatformTextStyle',
             'fun appIconGlyphTextStyle(): TextStyle = currentAppTextStyle().copy(',
             '    platformStyle = PlatformTextStyle(includeFontPadding = false),',
             ')'],
            removed)
        self.assertEqual(
            ['fun appIconGlyphTextStyle(): TextStyle =',
             '    // The Skia JVM text renderer has no Android font padding to switch off, so the shared style is the glyph style.',
             '    currentAppTextStyle()'],
            added)
        # The glyph style stays public and composable; the two bridge functions stay internal.
        self.assertIn('@Composable\n@ReadOnlyComposable\nfun appIconGlyphTextStyle(): TextStyle =', adapted)
        # v0.4.6 only adds the glyph function and one import to the vendored v0.2.5 file.
        vendored = (REPO / glyph.UPSTREAM_PATHS[glyph.ADAPTED]).read_text(encoding='utf-8').replace('\r\n', '\n')
        block_start = original.index('/**\n * Shared text style with Android font padding turned off')
        block_end = original.index('/** Resolves the content color')
        self.assertEqual(vendored, original[:block_start].replace(glyph.THEME_IMPORT, '', 1) + original[block_end:])

    def test_theme_defaults_adaptation_fails_closed_when_the_anchor_drifts(self):
        original = self.sources[glyph.ADAPTED]
        cases = {
            'import': original.replace(glyph.THEME_IMPORT, ''),
            'body': original.replace(glyph.THEME_BODY, glyph.THEME_BODY.replace('false', 'true')),
            'duplicated': original + glyph.THEME_BODY,
            'already adapted': original.replace(glyph.THEME_IMPORT, '').replace(glyph.THEME_BODY, glyph.THEME_BODY_WINDOWS),
        }
        for label, text in cases.items():
            with self.subTest(label=label):
                with self.assertRaises(ValueError):
                    glyph.windows_theme_defaults(text)

    def test_emit_is_deterministic_and_a_rerun_matches_the_archive(self):
        def once(seed):
            directory = self.root / ('emit-' + str(seed))
            receipt = glyph.emit(REPO, directory)
            return receipt, tree(directory)

        first, second = once(1), once(2)
        self.assertEqual(first, second)
        self.assertEqual([glyph.RELATIVE[name] for name in glyph.MAIN_FILES] + [glyph.PROOF_NAME],
                         [row['path'] for row in first[0]['files']])
        rerun = glyph.emit(REPO, self.root / 'emit-1')
        self.assertEqual(first[0], rerun)
        self.assertEqual(first[1], tree(self.root / 'emit-1'))
        for name in VERBATIM:
            self.assertEqual(self.archive[name], first[1][glyph.RELATIVE[name]])
        again = run_cli(self.root / 'main-2', 7)
        self.assertEqual(self.stdout, again)
        self.assertEqual(self.main, tree(self.root / 'main-2'))
        same = self.root / 'cli-same'
        first_stdout = run_cli(same, 4)
        first_tree = tree(same)
        self.assertEqual(first_stdout, run_cli(same, 4))
        self.assertEqual(first_tree, tree(same))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        nested = REPO / glyph.ARCHIVE / 'child'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                glyph.emit(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                glyph.validate_outputs(REPO, REPO)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                page.generate(REPO, nested)
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / glyph.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    def test_a_stale_regular_file_is_replaced_and_special_targets_fail_closed(self):
        relatives = [glyph.RELATIVE[name] for name in glyph.MAIN_FILES]

        def prepare(root, relative):
            (root / 'outside.txt').write_bytes(b'OUTSIDE')
            target = root / relative
            target.parent.mkdir(parents=True)
            (target.parent / 'decoy.kt').write_bytes(b'DECOY')
            return target

        bodies, _receipt = glyph.main_bodies(REPO)
        for relative in relatives:
            with self.subTest(kind='stale', relative=relative), tempfile.TemporaryDirectory(prefix='bp-gtp-') as tmp:
                root = Path(tmp)
                before_archive = archive_files()
                target = prepare(root, relative)
                target.write_bytes(b'SENTINEL-FOREIGN')
                glyph.emit(REPO, root)
                self.assertEqual(bodies[Path(relative).name], target.read_bytes())
                glyph.emit(REPO, root)
                self.assertEqual(bodies[Path(relative).name], target.read_bytes())
                self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                self.assertEqual(before_archive, archive_files())
        for kind in ('symlink', 'directory', 'fifo'):
            for relative in relatives:
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-gtp-') as tmp:
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
                        glyph.emit(REPO, root)
                    self.assertIn('not a regular file: ' + relative, str(caught.exception))
                    self.assertEqual(before, frozen(root))
                    self.assertEqual(before_archive, archive_files())
                    self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())

    def test_gradle_excludes_each_replaced_copy_once_and_wires_the_archive_into_the_detail_task(self):
        build = self.build
        prepared = prepare_block(build)
        block = task_block(build, TASK)
        model = gate.parse_gradle(build)
        for name in REPLACED:
            canonical = glyph.UPSTREAM_PATHS[name]
            line = 'exclude(canonicalOriginalIdentity("' + canonical + '"))'
            with self.subTest(name=name):
                self.assertEqual(1, build.count(line), line)
                self.assertEqual(1, prepared.count(line), line)
                self.assertEqual(1, model.sync_excludes.count(canonical))
        for name in NEW_FILES:
            self.assertNotIn(glyph.UPSTREAM_PATHS[name], build)
        self.assertEqual(len(REPLACED), sum(1 for row in model.sync_excludes if row in
                                            {glyph.UPSTREAM_PATHS[name] for name in glyph.MAIN_FILES}))
        for literal in ('inputs.file("tools/v046_glyph_toolbar_pickers.py")',
                        'inputs.dir("upstream-slices/v046-glyph-toolbar-pickers")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'glyph-toolbar-pickers' in row])
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-detail-container"))'))
        self.assertIn('"generated/dynamic-detail-container"', block)
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        for name in glyph.MAIN_FILES:
            listed = [row for row in sources['sources'] if row['path'] == glyph.UPSTREAM_PATHS[name]]
            if name in REPLACED:
                self.assertEqual(1, len(listed), name)
                self.assertEqual('direct', listed[0]['mode'], name)
            else:
                self.assertEqual([], listed, name)
        for producer, name in (('extract-upstream-components.py', 'AppPrimitiveThemeDefaults'),
                               ('extract-upstream-preferences.py', 'AppWindowActionMenu')):
            text = (TOOLS / producer).read_text(encoding='utf-8')
            self.assertEqual(1, text.count('"' + name + '"'), producer)
        # The vendored copies are not emitted by any other producer in production mode.
        for other in sorted(TOOLS.glob('*.py')):
            if other.name in {'v046_glyph_toolbar_pickers.py', 'extract-upstream-components.py',
                              'extract-upstream-preferences.py'}:
                continue
            body = other.read_text(encoding='utf-8')
            for name in REPLACED:
                self.assertNotIn(name, body, other.name + ' ' + name)

    def test_inventory_rows_and_catalog_pin_every_leaf(self):
        self.assertEqual(1, self.catalog['buildInputRoots'].count(glyph.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(glyph.ARCHIVE + '/')]
        self.assertEqual(sorted(glyph.UPSTREAM_PATHS[name] for name in glyph.MAIN_FILES), sorted(row['path'] for row in rows))
        manifest = {row['filename']: row for row in json.loads(self.archive['manifest.json'])['files']}
        for row in rows:
            name = Path(row['localPath']).name
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual([FEATURE], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(glyph.COMMIT, row['upstreamCommit'])
            self.assertEqual(manifest[name]['gitBlob'], row['blob'])
            self.assertEqual(manifest[name]['sha256'], row['sha256'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == glyph.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({glyph.ARCHIVE + '/' + name for name in glyph.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(glyph.ARCHIVE + '/')])

    def test_producer_hooks_validate_before_any_write_and_emit_last(self):
        producer = (TOOLS / 'extract-upstream-dynamic-detail-container.py').read_text(encoding='utf-8')
        validate_import = 'from v046_glyph_toolbar_pickers import validate_outputs as validate_glyph_toolbar_pickers_outputs'
        emit_import = 'from v046_glyph_toolbar_pickers import emit as emit_glyph_toolbar_pickers'
        self.assertEqual(1, producer.count(validate_import))
        self.assertEqual(1, producer.count('validate_glyph_toolbar_pickers_outputs(REPO, OUTPUT)'))
        self.assertEqual(1, producer.count(emit_import))
        self.assertEqual(1, producer.count('emit_glyph_toolbar_pickers(REPO, OUTPUT)'))
        generate = producer[producer.index('def generate('):]
        self.assertLess(generate.index('from v046_sheet_motion import validate_outputs'), generate.index(validate_import))
        self.assertLess(generate.index(validate_import), generate.index('STANDALONE = standalone'))
        self.assertLess(generate.index(validate_import), generate.index('_generate_body()'))
        body = producer[producer.index('def _generate_body()'):producer.index('def generate(')]
        self.assertLess(body.index('thread_container()'), body.index(emit_import))
        self.assertLess(body.index(emit_import), body.index('emit_glyph_toolbar_pickers(REPO, OUTPUT)'))
        self.assertLess(body.index('emit_glyph_toolbar_pickers(REPO, OUTPUT)'), body.index('if STANDALONE:'))
        self.assertFalse(any(line.startswith('import v046_glyph_toolbar_pickers') or
                             line.startswith('from v046_glyph_toolbar_pickers') for line in producer.splitlines()))
        # Only this producer imports the generator.
        importers = [p.name for p in sorted(TOOLS.glob('*.py')) if 'v046_glyph_toolbar_pickers' in p.read_text(encoding='utf-8')
                     and p.name != 'v046_glyph_toolbar_pickers.py']
        self.assertEqual(['extract-upstream-dynamic-detail-container.py'], importers)

    def test_each_top_level_name_has_one_definer_among_the_static_sources(self):
        for name in glyph.MAIN_FILES:
            package, decls = declarations(self.sources[name], name)
            self.assertEqual(glyph.PACKAGE[name], package, name)
            self.assertEqual(TOP_LEVEL[name], decls, name)
            if name in VERBATIM:
                self.assertEqual(self.sources[name], self.main[glyph.RELATIVE[name]].decode('utf-8'), name)
        hits = []
        for relative in STATIC_ROOTS:
            hits.extend(declaration_hits(REPO / relative, GLOBAL_NAMES))
        archived = {glyph.ARCHIVE + '/' + name for name in glyph.MAIN_FILES}
        self.assertEqual(archived, {path for path, _names in hits if path in archived})
        others = [(path, names) for path, names in hits if path not in archived]
        # The only other declarers are the two vendored v0.2.5 copies; both are excluded from the Sync.
        self.assertEqual(
            [('design-system/src/main/java/com/android/purebilibili/core/ui/components/AppWindowActionMenu.kt',
              ['AppWindowAction', 'AppWindowActionMenu'])],
            others)
        menu_copy = 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppWindowActionMenu.kt'
        for name in GLOBAL_NAMES:
            owner = glyph.ARCHIVE + '/' + OWNER[name]
            expected_definers = [menu_copy, owner] if name in {'AppWindowAction', 'AppWindowActionMenu'} else [owner]
            self.assertEqual(sorted(expected_definers), sorted(path for path, names in hits if name in names), name)
        # AppPrimitiveThemeDefaults has no appIconGlyphTextStyle in the vendored copy: the archive is the only definer.
        vendored = (REPO / glyph.UPSTREAM_PATHS[glyph.ADAPTED]).read_text(encoding='utf-8')
        self.assertNotIn('appIconGlyphTextStyle', vendored)
        # Nothing desktop-owned reimplements or shadows these names.
        shadows = []
        for path in (REPO / 'desktop/src').rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            if re.search(r'(?m)^\s*(?:internal |private |public )?(?:fun|class|object|val)\s+(?:' +
                         '|'.join(GLOBAL_NAMES) + r')\b', text):
                shadows.append(path.relative_to(REPO).as_posix())
        self.assertEqual([], shadows)

    def test_window_action_menu_keeps_the_vendored_public_signatures(self):
        vendored = (REPO / glyph.UPSTREAM_PATHS['AppWindowActionMenu.kt']).read_text(encoding='utf-8')
        new = self.sources['AppWindowActionMenu.kt']
        self.assertEqual(parameters(vendored, 'AppWindowActionMenu'), parameters(new, 'AppWindowActionMenu'))
        self.assertEqual(parameters(vendored, 'AppWindowAction', 'class'), parameters(new, 'AppWindowAction', 'class'))
        self.assertEqual(['groups', 'modifier', 'enabled', 'onExpandedChange', 'content'],
                         [label for label, _default in parameters(new, 'AppWindowActionMenu')])
        self.assertEqual(['label', 'onClick', 'icon', 'iconTint', 'summary', 'enabled', 'selected', 'children'],
                         [label for label, _default in parameters(new, 'AppWindowAction', 'class')])
        # The new native branch hands the cascade to the Miuix popup instead of the removed DropdownImpl row loop.
        self.assertEqual(1, new.count('WindowCascadingListPopup('))
        self.assertNotIn('DropdownImpl', new)
        self.assertNotIn('WindowListPopup(', new)
        self.assertIn('LocalAppUiStyle.current != AppUiStyle.MIUIX || !LocalAppThemeConfig.current.nativeMiuixPopupsEnabled', new)
        self.assertIn('DropdownImpl', vendored)

    def test_every_miuix_call_resolves_against_the_vendored_miuix_sources(self):
        number = (REPO / (MIUIX + 'basic/NumberPicker.kt')).read_text(encoding='utf-8')
        toolbar = (REPO / (MIUIX + 'basic/FloatingToolbar.kt')).read_text(encoding='utf-8')
        cascade = (REPO / (MIUIX + 'window/WindowCascadingListPopup.kt')).read_text(encoding='utf-8')
        dropdown = (REPO / (MIUIX + 'basic/Dropdown.kt')).read_text(encoding='utf-8')
        picker = self.sources['AppNumberPicker.kt']
        toolbar_widget = self.sources['AppFloatingToolbar.kt']
        menu = self.sources['AppWindowActionMenu.kt']
        for text, widget, callee, owner, keyword in (
                (picker, 'AppNumberPicker', 'NumberPicker', number, 'fun'),
                (toolbar_widget, 'AppFloatingToolbar', 'FloatingToolbar', toolbar, 'fun'),
                (menu, 'AppWindowActionMenu', 'WindowCascadingListPopup', cascade, 'fun'),
                (menu, 'AppWindowActionMenu', 'DropdownItem', dropdown, 'class'),
                (menu, 'AppWindowActionMenu', 'DropdownEntry', dropdown, 'class')):
            params = parameters(owner, callee, keyword)
            found = calls(text, {callee})
            self.assertTrue(found, widget + ' ' + callee)
            for call in found:
                self.assertEqual('', callee_problem(call, params), (widget, callee, call))
        self.assertIn('import top.yukonga.miuix.kmp.basic.NumberPicker\n', picker)
        self.assertIn('import top.yukonga.miuix.kmp.basic.FloatingToolbar\n', toolbar_widget)
        self.assertIn('import top.yukonga.miuix.kmp.window.WindowCascadingListPopup\n', menu)
        self.assertIn('import top.yukonga.miuix.kmp.basic.DropdownEntry\n', menu)
        self.assertIn('import top.yukonga.miuix.kmp.basic.DropdownItem\n', menu)
        self.assertIn('import top.yukonga.miuix.kmp.basic.PopupPositionProvider\n', menu)
        popup = (REPO / (MIUIX + 'basic/ListPopup.kt')).read_text(encoding='utf-8')
        self.assertEqual(1, popup.count('interface PopupPositionProvider'))
        self.assertEqual(1, popup.count('enum class Align'))
        self.assertIn('alignment = PopupPositionProvider.Align.End,', menu)

    def test_the_material_calls_use_the_declared_picker_parameters_and_a_compiled_opt_in_marker(self):
        toolbar = self.sources['AppFloatingToolbar.kt']
        picker = self.sources['AppNumberPicker.kt']
        duration = self.sources['AppDurationPicker.kt']
        # The Material overload is HorizontalFloatingToolbar(expanded, modifier, ..., content); its presence in the pinned
        # material3-desktop 1.12.0-alpha03 jar is recorded in the slice report, not provable from the repository. The
        # opt-in marker it needs is imported by a compiled direct-mode design-system file.
        self.assertEqual(1, toolbar.count('HorizontalFloatingToolbar(modifier = modifier, expanded = true) { content() }'))
        self.assertIn('@OptIn(ExperimentalMaterial3ExpressiveApi::class)', toolbar)
        marker_path = 'design-system/src/main/java/com/android/purebilibili/core/ui/AdaptiveLoadingIndicator.kt'
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        self.assertEqual(['direct'], [row['mode'] for row in sources['sources'] if row['path'] == marker_path])
        marker = (REPO / marker_path).read_text(encoding='utf-8')
        self.assertIn('import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi\n', marker)
        # AppDurationPicker calls AppNumberPicker with names the picker declares.
        params = parameters(picker, 'AppNumberPicker')
        self.assertEqual(['value', 'onValueChange', 'modifier', 'range', 'label'], [label for label, _default in params])
        found = calls(duration, {'AppNumberPicker'})
        self.assertEqual(2, len(found))
        for call in found:
            self.assertEqual('', callee_problem(call, params), call)
        # AppText(label) resolves to the first AppText overload (a String).
        text_params = parameters((REPO / 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppText.kt')
                                 .read_text(encoding='utf-8'), 'AppText')
        self.assertEqual('text', text_params[0][0])

    def test_all_eight_carried_toolbar_consumers_call_the_real_signature(self):
        params = parameters(self.sources['AppFloatingToolbar.kt'], 'AppFloatingToolbar')
        self.assertEqual(['modifier', 'content'], [label for label, _default in params])
        found_files = []
        for relative in TOOLBAR_CONSUMERS:
            text = (REPO / 'desktop/upstream-slices' / relative).read_text(encoding='utf-8')
            self.assertIn('import com.android.purebilibili.core.ui.components.AppFloatingToolbar\n', text, relative)
            found = calls(text, {'AppFloatingToolbar'})
            self.assertEqual(1, len(found), relative)
            for call in found:
                self.assertEqual('', callee_problem(call, params), (relative, call))
            found_files.append(relative)
        every = [str(p.relative_to(REPO / 'desktop/upstream-slices')).replace('\\', '/')
                 for p in (REPO / 'desktop/upstream-slices').rglob('*.kt')
                 if 'import com.android.purebilibili.core.ui.components.AppFloatingToolbar' in p.read_text(encoding='utf-8')]
        self.assertEqual(sorted(TOOLBAR_CONSUMERS), sorted(every))
        self.assertEqual(8, len(found_files))

    def test_the_symbol_gate_retires_the_toolbar_gap_with_this_definer(self):
        expected = json.loads((TOOLS / 'symbol-closure-expected-gaps.json').read_text(encoding='utf-8'))
        symbols = [gap['symbol'] for group in expected['groups'] for gap in group['gaps']]
        self.assertFalse([s for s in symbols if s.endswith('AppFloatingToolbar')])
        self.assertFalse([g for g in expected['groups'] if g['closedBy'] == 'fnd-sheet-motion-and-glyph'])
        seeds = [row for row in expected['retiredSeeds'] if row['symbol'] == 'AppFloatingToolbar']
        self.assertEqual(1, len(seeds))
        seed = seeds[0]
        self.assertEqual('declaration', seed['kind'])
        self.assertEqual('com.android.purebilibili.core.ui.components', seed['package'])
        self.assertIn(glyph.ARCHIVE, seed['retiredBy'])
        self.assertEqual('fnd-sheet-motion-and-glyph', seed['closedBy'])
        self.assertIn('AppFloatingToolbar', [row['symbol'] for row in expected['retiredSeeds']])
        closed = (REPO / 'desktop/tools/tests/test_symbol_closure.py').read_text(encoding='utf-8')
        self.assertEqual(1, closed.count("('com.android.purebilibili.core.ui.components', 'AppFloatingToolbar'),"))

    def test_hand_written_regression_classes_have_every_case_and_balanced_delimiters(self):
        for path, cases, class_name, tokens in (
                (JVM_TEST, 7, 'DesktopGlyphToolbarPickersTest',
                 ('appIconGlyphTextStyle()', 'AppDurationPicker(', 'AppNumberPicker(', 'AppFloatingToolbar {',
                  'AppWindowActionMenu(')),
                (SHEET_JVM_TEST, 3, 'DesktopSheetMotionPolicyTest',
                 ('resolveAdaptiveBottomSheetMotionSpec(', 'resolveAppModalLayoutSpec(', 'resolveBottomSheetHost('))):
            with self.subTest(test=class_name):
                text = path.read_text(encoding='utf-8')
                self.assertNotIn('\r', text)
                self.assertEqual(cases, len(re.findall(r'(?m)^    @Test fun `', text)))
                self.assertEqual(cases, text.count('@Test'))
                names = re.findall(r'(?m)^    @Test fun `([^`]+)`', text)
                self.assertEqual(len(names), len(set(names)))
                for name in names:
                    self.assertFalse(set('.;[]/<>:\\') & set(name), name)
                self.assertNotIn('org.junit', text)
                self.assertIn('package com.bilipai.desktop.ui\n', text)
                self.assertEqual(1, text.count('class ' + class_name))
                self.assertEqual(path.stem, class_name)
                for token in tokens:
                    self.assertIn(token, text)
                self.balanced(text)

    def test_emitted_sources_keep_balanced_delimiters_and_lf(self):
        for name in glyph.MAIN_FILES:
            text = self.main[glyph.RELATIVE[name]].decode('utf-8')
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
