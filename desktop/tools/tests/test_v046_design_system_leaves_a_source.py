"""v0.4.6 design-system leaf files, part A: MediaContrastPalette, AppPopupSurface, AppSemanticVisualPolicy,
AppEntryIconPalette and AppChromeSizeTokens emitted whole by the component producer.

Source checks only: nothing is compiled and no JVM test runs here. The Kotlin regression classes are counted and
checked for balanced delimiters; the Linux compile and JVM run happen later.
"""
from pathlib import Path
import difflib
import functools
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
import v040_chrome_actions as chrome_actions
import v046_design_system_leaves_a as leaves

spec = importlib.util.spec_from_file_location('sync_upstream_design_system_leaves_a', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

components_spec = importlib.util.spec_from_file_location(
    'extract_upstream_components_design_system_leaves_a', TOOLS / 'extract-upstream-components.py')
components = importlib.util.module_from_spec(components_spec)
components_spec.loader.exec_module(components)

PRODUCER = 'tools/extract-upstream-components.py'
BASE = leaves.BASE
CANONICAL = {name: BASE + name for name in leaves.MAIN_FILES}
EXCLUDED_NOW = ('MediaContrastPalette.kt', 'AppPopupSurface.kt', 'AppSemanticVisualPolicy.kt')
ALREADY_EXCLUDED = ('AppChromeSizeTokens.kt',)
V040_ARCHIVE = 'desktop/upstream-slices/v040-chrome-actions'
HAND_WRITTEN_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopDesignSystemLeavesATest.kt'
TOP_LEVEL = {
    'MediaContrastPalette.kt': {('public', 'object', 'MediaContrastPalette')},
    'AppPopupSurface.kt': {
        ('public', 'class', 'AppPopupSurfaceType'), ('public', 'interface', 'AppPopupSurfaceRenderer'),
        ('public', 'val', 'LocalAppPopupSurfaceRenderer'), ('public', 'fun', 'AppPopupSurface')},
    'AppSemanticVisualPolicy.kt': {
        ('public', 'class', 'AppSemanticIconFamily'), ('public', 'class', 'AppIconStyle'),
        ('public', 'fun', 'resolveAppIconStyle'), ('public', 'fun', 'resolveAppIconStylePreference'),
        ('public', 'val', 'LocalAppIconStyle'), ('public', 'fun', 'rememberResolvedAppIconStyle'),
        ('public', 'class', 'AppSemanticAccentRole'), ('public', 'class', 'AppSemanticAccentPalette'),
        ('public', 'class', 'AppSemanticVisualPolicy'), ('public', 'fun', 'resolveAppSemanticAccentPalette'),
        ('public', 'fun', 'resolveAppSemanticVisualPolicy'), ('public', 'fun', 'rememberAppSemanticVisualPolicy'),
        ('public', 'fun', 'resolveAppChromeLiquidGlassEnabled'), ('public', 'fun', 'rememberAppChromeLiquidGlassEnabled')},
    'AppEntryIconPalette.kt': {
        ('public', 'val', 'AppEntryIconPalette'), ('public', 'fun', 'resolveAppEntryIconColor'),
        ('public', 'class', 'AppEntryIconColorCursor'), ('internal', 'val', 'LocalAppEntryIconColorCursor'),
        ('public', 'fun', 'ProvideAppEntryIconColors')},
    'AppChromeSizeTokens.kt': {
        ('public', 'object', 'AppChromeSizeTokens'), ('public', 'class', 'CompactCapsuleChromeSpec'),
        ('public', 'fun', 'resolveCompactCapsuleChromeSpec')},
}
# Same-package simple names a carried file uses without importing them. They come with the whole file, so each must
# be declared somewhere in the compile universe; AppPopupSurface reads AppContentColors.primaryText().
SAME_PACKAGE_USES = {'AppPopupSurface.kt': (('AppContentColors', 'fun primaryText()'),)}
# files whose emission or reading by other producers is known and reviewed (names only)
STANDALONE_ONLY_READERS = {
    'AppSemanticVisualPolicy': 'extract-upstream-preferences.py',
    'MediaContrastPalette': ('extract-upstream-dynamic-card.py', 'extract-upstream-home-full-card.py'),
}
ALREADY_CARRIED_AT_V046 = ('AppContentColors', 'ButtonSlotColor', 'AppActionColors', 'AppSurfaceTokens',
                           'AppChromeOverflow', 'AppTextSearchHighlight')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def lf(raw):
    return raw.replace(b'\r\n', b'\n')


def git_blob(path, commit=leaves.COMMIT):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', commit + ':' + path],
                          cwd=str(REPO), capture_output=True, check=True).stdout


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / leaves.ARCHIVE).iterdir() if p.is_file()}


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


def run_cli(output, test_output, seed):
    environment = dict(os.environ, PYTHONHASHSEED=str(seed), PYTHONIOENCODING='utf-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1')
    done = subprocess.run(
        [sys.executable, '-X', 'utf8', '-B', str(REPO / 'desktop' / PRODUCER),
         '--repo', str(REPO), '--output', str(output), '--test-output', str(test_output)],
        cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode('utf-8', 'replace')[-4000:])
    return done.stdout.decode('utf-8')


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, {(item.visibility, item.kind, item.name) for item in info.decls}


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


@functools.lru_cache(maxsize=1)
def definer_candidates():
    build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
    excluded = set(gate.parse_gradle(build).sync_excludes)
    sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
    candidates = [REPO / row['path'] for row in sources
                  if row.get('mode', 'direct') == 'direct' and row['path'].endswith('.kt') and row['path'] not in excluded]
    candidates += sorted((REPO / 'desktop/upstream-slices').glob('*/*.kt'))
    return tuple(candidates)


def definers(name, package):
    """Files of the compile-relevant sources that declare `name` in `package`: vendored direct files that are not
    excluded from prepareUpstreamSources, and every slice archive."""
    found = []
    for path in definer_candidates():
        raw = path.read_bytes()
        if name.encode('utf-8') not in raw:
            continue
        info = gate.parse_kotlin(raw.decode('utf-8'), path.name, 'carried', 'main')
        if info.package == package and any(d.name == name and d.visibility != 'private' for d in info.decls):
            found.append(path.relative_to(REPO).as_posix())
    return found


class V046DesignSystemLeavesASourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-dsl-')
        cls.root = Path(cls.temporary.name)
        cls.stdout = run_cli(cls.root / 'main', cls.root / 'test', 0)
        cls.main = tree(cls.root / 'main')
        cls.tests = tree(cls.root / 'test')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.manifest = json.loads(cls.archive['manifest.json'])

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    # ---- archive -------------------------------------------------------------------------------------------

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        leaves_on_disk = sorted(self.archive)
        self.assertEqual(sorted(leaves.EXPECTED | {'LICENSE', 'manifest.json'}), leaves_on_disk)
        for leaf in leaves_on_disk:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / leaves.ARCHIVE, repo / leaves.ARCHIVE)
                target = repo / leaves.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    leaves.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / leaves.ARCHIVE, repo / leaves.ARCHIVE)
            (repo / leaves.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                leaves.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / leaves.ARCHIVE, repo / leaves.ARCHIVE)
            (repo / leaves.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                leaves.fixed(repo)
        self.assertEqual(self.archive, archive_files())

    def test_manifest_rows_match_the_upstream_git_blobs_and_the_pins(self):
        self.assertEqual(leaves.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        self.assertNotIn(b'\r', self.archive['manifest.json'])
        self.assertEqual(['MediaContrastPalette.kt', 'AppPopupSurface.kt', 'AppSemanticVisualPolicy.kt',
                          'AppEntryIconPalette.kt', 'AppChromeSizeTokens.kt', 'AppEntryIconPaletteTest.kt'],
                         [row['filename'] for row in self.manifest['files']])
        self.assertEqual(('jay3-yy/BiliPai', 'v0.4.6', leaves.COMMIT, 'raw'),
                         tuple(self.manifest[key] for key in ('repository', 'tag', 'commit', 'hashNormalization')))
        for row in self.manifest['files']:
            with self.subTest(name=row['filename']):
                blob = git_blob(row['path'])
                archived = self.archive[row['filename']]
                self.assertEqual(blob, archived)
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertEqual((row['bytes'], row['sha256']), (len(blob), sha(blob)))
                self.assertNotIn(b'\r', archived)
        self.assertEqual(git_blob('LICENSE'), self.archive['LICENSE'])
        self.assertEqual(sha(self.archive['LICENSE']), self.manifest['license']['sha256'])

    def test_the_slice_carries_exactly_the_five_named_files_and_redoes_nothing_already_carried(self):
        self.assertEqual({'MediaContrastPalette', 'AppPopupSurface', 'AppSemanticVisualPolicy', 'AppEntryIconPalette',
                          'AppChromeSizeTokens'}, {Path(name).stem for name in leaves.MAIN_FILES})
        for name in ALREADY_CARRIED_AT_V046:
            self.assertNotIn(name + '.kt', self.archive)
        # the v0.4.6 chrome token test is byte-identical to the one v040-chrome-actions already emits
        v040 = chrome_actions.fixed_sources(REPO)
        key = BASE.replace('/main/java/', '/test/java/') + 'AppChromeSizeTokensTest.kt'
        self.assertEqual(git_blob(key), v040[key])
        self.assertNotIn('AppChromeSizeTokensTest.kt', self.archive)

    def test_behaviour_changes_against_the_carried_predecessors_are_exactly_the_documented_ones(self):
        def changed(old, new):
            both = [line for line in difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm='', n=0)
                    if line[:1] in '+-' and line[:3] not in ('+++', '---')]
            return [line for line in both if line[:1] == '-'], [line for line in both if line[:1] == '+']

        text = {name: raw.decode('utf-8') for name, raw in self.archive.items() if name.endswith('.kt')}
        vendored = {name: (REPO / CANONICAL[name]).read_bytes().decode('utf-8').replace('\r\n', '\n')
                    for name in ('MediaContrastPalette.kt', 'AppPopupSurface.kt', 'AppSemanticVisualPolicy.kt')}
        removed, added = changed(vendored['MediaContrastPalette.kt'], text['MediaContrastPalette.kt'])
        self.assertEqual([], removed)
        self.assertEqual(8, len(added))
        for member in ('SecondaryForeground', 'ControlSurface', 'ControlScrim', 'Track', 'Success', 'Warning', 'Error', 'Rating'):
            self.assertEqual(1, sum(1 for line in added if line.startswith('+    val ' + member + ' = ')), member)
        removed, added = changed(vendored['AppPopupSurface.kt'], text['AppPopupSurface.kt'])
        self.assertEqual(['-        MaterialTheme.colorScheme.onSurface'], removed)
        self.assertEqual(['+        AppContentColors.primaryText()'], added)
        removed, added = changed(vendored['AppSemanticVisualPolicy.kt'], text['AppSemanticVisualPolicy.kt'])
        self.assertEqual(5, len(removed))
        self.assertEqual(5, len(added))
        for line in removed + added:
            self.assertTrue(line[1:].lstrip().startswith('*'), line)      # KDoc lines only
        previous = chrome_actions.fixed_sources(REPO)[BASE + 'AppChromeSizeTokens.kt'].decode('utf-8')
        removed, added = changed(previous, text['AppChromeSizeTokens.kt'])
        self.assertEqual([], removed)
        self.assertEqual(['+    // Glass search visuals have their own height; native search metrics stay unchanged.',
                          '+    const val LiquidSearchFieldHeightDp = 40'], added)

    def test_declared_top_level_names_are_exactly_the_reviewed_set(self):
        for name, wanted in TOP_LEVEL.items():
            with self.subTest(name=name):
                package, decls = declarations(self.archive[name].decode('utf-8'), name)
                self.assertEqual(leaves.PACKAGE, package)
                self.assertEqual(wanted, decls)

    # ---- emission ------------------------------------------------------------------------------------------

    def test_verbatim_files_and_the_original_test_are_written_byte_for_byte(self):
        self.assertIn('original component files', self.stdout)
        for name in leaves.VERBATIM_FILES:
            with self.subTest(name=name):
                self.assertEqual(self.archive[name], self.main[leaves.RELATIVE[name]])
        for name in leaves.TEST_FILES:
            self.assertEqual(self.archive[name], self.tests[leaves.RELATIVE[name]])
            self.assertEqual(2, self.archive[name].decode('utf-8').count('@Test'))
        self.assertEqual(1, len([path for path in self.main if path.endswith('/AppEntryIconPalette.kt')]))

    def test_chain_files_are_the_whole_v046_file_behind_the_producer_header(self):
        for name in leaves.CHAIN_FILES:
            with self.subTest(name=name):
                emitted = lf(self.main[leaves.RELATIVE[name]])
                header, digest, body = emitted.split(b'\n', 2)
                self.assertEqual(('// GENERATED from ' + CANONICAL[name] + '; do not edit.').encode(), header)
                self.assertEqual(('// LF-normalized SHA-256: ' + sha(self.archive[name])).encode(), digest)
                self.assertEqual(self.archive[name], body)

    def test_chain_layer_asserts_its_predecessor_and_leaves_every_other_path_alone(self):
        popup = BASE + 'AppPopupSurface.kt'
        tokens = BASE + 'AppChromeSizeTokens.kt'
        vendored_popup = (REPO / popup).read_bytes().decode('utf-8').replace('\r\n', '\n')
        v040_tokens = chrome_actions.fixed_sources(REPO)[tokens].decode('utf-8')
        self.assertEqual(self.archive['AppPopupSurface.kt'].decode('utf-8'), leaves.chain_source(REPO, popup, vendored_popup))
        self.assertEqual(self.archive['AppChromeSizeTokens.kt'].decode('utf-8'), leaves.chain_source(REPO, tokens, v040_tokens))
        self.assertEqual(sha(vendored_popup.encode()), leaves.CHAIN_PREDECESSOR_SHA256['AppPopupSurface.kt'])
        self.assertEqual(sha(v040_tokens.encode()), leaves.CHAIN_PREDECESSOR_SHA256['AppChromeSizeTokens.kt'])
        for path, foreign in ((popup, v040_tokens), (tokens, vendored_popup),
                              (tokens, (REPO / tokens).read_bytes().decode('utf-8').replace('\r\n', '\n')),
                              (popup, vendored_popup + '\n'), (tokens, self.archive['AppChromeSizeTokens.kt'].decode('utf-8'))):
            with self.subTest(path=path, foreign=sha(foreign.encode())[:8]):
                with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                    leaves.chain_source(REPO, path, foreign)
        other = BASE + 'AppShapes.kt'
        self.assertEqual('anything', leaves.chain_source(REPO, other, 'anything'))
        self.assertEqual(sorted(leaves.CHAIN_PATHS), sorted([popup, tokens]))
        self.assertEqual(sha(self.archive['AppChromeSizeTokens.kt']), self.manifest['files'][4]['sha256'])

    def test_the_chain_layer_sits_outside_every_earlier_layer_of_the_component_producer(self):
        producer = (TOOLS / 'extract-upstream-components.py').read_text(encoding='utf-8')
        loop_at = producer.index('    for path in DIRECT:\n')
        loop = producer[loop_at:producer.index('    for path in ADAPTED:', loop_at)]
        self.assertIn('original = _leaf_source(repo, path, _theme_chrome_source(repo, path, _optical_source(repo, path, '
                      '_chrome_source(repo, path, _liquid_source(repo, path, host.read(repo, path))))))', loop)
        self.assertEqual(1, producer.count('_leaf_source('))
        self.assertIn('or path in _leaf_chain or', loop)
        generate = producer[producer.index('def generate('):]
        self.assertLess(generate.index('_validate_theme_chrome_outputs(repo, output, test_output)'),
                        generate.index('_validate_leaf_outputs(repo, output, test_output)'))
        self.assertLess(generate.index('_validate_leaf_outputs(repo, output, test_output)'), generate.index('host = helper(repo)'))
        self.assertEqual(1, generate.count('_leaf_emit(repo, output, test_output)["files"]'))
        self.assertLess(generate.index('_native_chrome_components(repo, output, test_output)'),
                        generate.index('_leaf_emit(repo, output, test_output)'))
        self.assertEqual(1, producer.count('from v046_design_system_leaves_a import '))
        # standalone isolated output is the same single owner: the chain writes the v0.4.6 bytes there too
        with tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
            components.generate(REPO, Path(tmp) / 'o', standalone=True)
            body = lf((Path(tmp) / 'o' / leaves.RELATIVE['AppPopupSurface.kt']).read_bytes()).split(b'\n', 2)[2]
            self.assertEqual(self.archive['AppPopupSurface.kt'], body)

    def test_generation_without_a_test_root_writes_no_test_and_a_rerun_is_deterministic(self):
        with tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
            receipt = leaves.emit(REPO, Path(tmp) / 'a')
            self.assertEqual([], receipt['tests'])
            self.assertEqual([leaves.RELATIVE[name] for name in leaves.VERBATIM_FILES], [row['path'] for row in receipt['files']])
        first = leaves.emit(REPO, self.root / 'e1', self.root / 'et1')
        second = leaves.emit(REPO, self.root / 'e2', self.root / 'et2')
        self.assertEqual(first, second)
        self.assertEqual(tree(self.root / 'e1'), tree(self.root / 'e2'))
        for row in first['files'] + first['tests']:
            self.assertEqual(sha(self.archive[Path(row['path']).name]), row['sha256'])
        again = run_cli(self.root / 'main-2', self.root / 'test-2', 7)
        self.assertEqual(self.stdout, again)
        self.assertEqual(self.main, tree(self.root / 'main-2'))
        self.assertEqual(self.tests, tree(self.root / 'test-2'))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        nested = REPO / leaves.ARCHIVE / 'child'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                leaves.emit(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                leaves.emit(REPO, self.root / 'ok', nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                leaves.validate_outputs(REPO, REPO)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                components.generate(REPO, nested)
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / leaves.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    def test_a_stale_regular_file_is_replaced_and_special_targets_fail_closed(self):
        relatives = [leaves.RELATIVE[name] for name in leaves.VERBATIM_FILES]
        tests = [leaves.RELATIVE[name] for name in leaves.TEST_FILES]

        def prepare(root, relative):
            root.mkdir(parents=True, exist_ok=True)
            (root / 'outside.txt').write_bytes(b'OUTSIDE')
            target = root / relative
            target.parent.mkdir(parents=True)
            (target.parent / 'decoy.kt').write_bytes(b'DECOY')
            return target

        for relative in relatives:
            with self.subTest(kind='stale', relative=relative), tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
                root = Path(tmp)
                before_archive = archive_files()
                target = prepare(root, relative)
                target.write_bytes(b'SENTINEL-FOREIGN')
                leaves.emit(REPO, root)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                leaves.emit(REPO, root)
                self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                for name in leaves.VERBATIM_FILES:
                    self.assertEqual(self.archive[name], (root / leaves.RELATIVE[name]).read_bytes())
                self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                self.assertEqual(before_archive, archive_files())
        for kind in ('symlink', 'directory', 'fifo'):
            for relative in relatives + tests:
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-dsl-') as tmp:
                    if kind == 'fifo' and not hasattr(os, 'mkfifo'):
                        self.skipTest('os.mkfifo is unavailable')
                    root = Path(tmp)
                    main_root, test_root = root / 'main', root / 'test'
                    target = prepare(test_root if relative in tests else main_root, relative)
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
                    before_archive = archive_files()
                    with self.assertRaises(ValueError) as caught:
                        leaves.emit(REPO, main_root, test_root)
                    self.assertIn('not a regular file: ' + relative, str(caught.exception))
                    self.assertEqual(before, frozen(root))
                    self.assertEqual(before_archive, archive_files())

    # ---- ownership and wiring ------------------------------------------------------------------------------

    def test_gradle_excludes_the_three_direct_copies_once_and_tracks_the_archive_in_the_component_task(self):
        build = self.build
        prepared = prepare_block(build)
        model = gate.parse_gradle(build)
        for name in EXCLUDED_NOW + ALREADY_EXCLUDED:
            line = 'exclude(canonicalOriginalIdentity("' + CANONICAL[name] + '"))'
            with self.subTest(exclude=name):
                self.assertEqual(1, build.count(line), line)
                self.assertEqual(1, prepared.count(line), line)
                self.assertEqual(1, model.sync_excludes.count(CANONICAL[name]))
        self.assertNotIn('AppEntryIconPalette', build)
        block = task_block(build, 'extractUpstreamComponents')
        for literal in ('inputs.file("tools/v046_design_system_leaves_a.py")',
                        'inputs.dir("upstream-slices/v046-design-system-leaves-a")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertIn('"--test-output", generatedChromeActionTests.get().asFile.absolutePath', block)
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/components"))'))
        self.assertEqual(1, build.count('kotlin.sourceSets.named("test") { kotlin.srcDir(generatedChromeActionTests) }'))
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'design-system-leaves' in row])
        self.assertNotIn('"--standalone"', build)

    def test_upstream_sources_json_is_untouched_and_the_vendored_pins_match_the_manifest(self):
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
        by_path = {row['path']: row for row in sources}
        for name in EXCLUDED_NOW:
            row = by_path[CANONICAL[name]]
            self.assertEqual('direct', row.get('mode', 'direct'), name)
            self.assertEqual(leaves.VENDORED_LF_SHA256[name], row['sha256'], name)
            self.assertEqual(leaves.VENDORED_LF_SHA256[name], sha(lf((REPO / CANONICAL[name]).read_bytes())), name)
        self.assertNotIn(CANONICAL['AppEntryIconPalette.kt'], by_path)
        self.assertFalse((REPO / CANONICAL['AppEntryIconPalette.kt']).exists())
        self.assertEqual(chrome_actions.COMPONENT_DIRECT_PATHS & set(CANONICAL.values()), {CANONICAL['AppChromeSizeTokens.kt']})
        self.assertEqual({row['replacedBy'] for row in self.manifest['replacedVendoredCopies']}, set(EXCLUDED_NOW))

    def test_the_vendored_direct_copies_are_no_longer_compiled_and_the_leaf_files_have_one_definer(self):
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
        excluded = set(gate.parse_gradle(self.build).sync_excludes)
        compiled_direct = {row['path'] for row in sources if row.get('mode', 'direct') == 'direct'} - excluded
        for name in leaves.MAIN_FILES:
            self.assertNotIn(CANONICAL[name], compiled_direct, name)
        # static scan: no compiled direct file, no archive leaf outside the two reviewed ones and nothing desktop-owned
        # declares any top-level name of the five files
        names = {declared for rows in TOP_LEVEL.values() for _v, _k, declared in rows}
        owners = {}
        scanned = [REPO / path for path in sorted(compiled_direct) if path.endswith('.kt')]
        scanned += sorted((REPO / 'desktop/upstream-slices').glob('*/*.kt'))
        for root in ('desktop/src/main', 'desktop/src/test'):
            scanned += sorted((REPO / root).rglob('*.kt'))
        for path in scanned:
            raw = path.read_bytes()
            if not any(name.encode('utf-8') in raw for name in names):
                continue
            info = gate.parse_kotlin(raw.decode('utf-8'), path.name, 'carried', 'main')
            if info.package != leaves.PACKAGE:
                continue
            for decl in info.decls:
                if decl.name in names and decl.visibility != 'private':
                    owners.setdefault(decl.name, []).append(path.relative_to(REPO).as_posix())
        for name in names:
            found = owners.get(name, [])
            expected = [leaves.ARCHIVE + '/' + file for file, rows in TOP_LEVEL.items()
                        if any(declared == name for _v, _k, declared in rows)]
            if name in ('AppChromeSizeTokens', 'CompactCapsuleChromeSpec', 'resolveCompactCapsuleChromeSpec'):
                expected = sorted(expected + [V040_ARCHIVE + '/AppChromeSizeTokens.kt'])   # replaced inside the chain
            with self.subTest(name=name):
                self.assertEqual(sorted(expected), sorted(found))

    def test_nobody_else_emits_these_files_in_production(self):
        tools = sorted(path for path in TOOLS.glob('*.py') if not path.name.startswith('test_'))
        texts = {path.name: path.read_text(encoding='utf-8') for path in tools}
        mentions = {stem: sorted(file for file, text in texts.items() if stem in text)
                    for stem in ('AppEntryIconPalette', 'AppPopupSurface', 'AppSemanticVisualPolicy',
                                 'MediaContrastPalette', 'AppChromeSizeTokens')}
        self.assertEqual(['v046_design_system_leaves_a.py'],
                         [file for file in mentions['AppEntryIconPalette'] if file != 'design_system_consumer_table.py'])
        for stem, owners in STANDALONE_ONLY_READERS.items():
            for owner in (owners,) if isinstance(owners, str) else owners:
                self.assertIn(owner, mentions[stem])
        # the readers only write with --standalone/shared_closure, which no Gradle task passes
        for owner in ('extract-upstream-preferences.py', 'extract-upstream-dynamic-card.py'):
            self.assertIn('standalone' if 'preferences' in owner else 'shared_closure', texts[owner])
        self.assertIn('extract-upstream-components.py', mentions['AppPopupSurface'])
        self.assertIn('extract-upstream-components.py', mentions['AppChromeSizeTokens'])

    def test_every_project_import_of_the_leaf_files_has_a_definer_in_the_compile_inputs(self):
        for name in leaves.MAIN_FILES:
            info = gate.parse_kotlin(self.archive[name].decode('utf-8'), name, 'carried', 'main')
            for imp in info.imports:
                if not imp.path.startswith('com.android.purebilibili') or imp.star:
                    continue
                package, simple = imp.path.rsplit('.', 1)
                with self.subTest(file=name, imported=imp.path):
                    self.assertTrue(definers(simple, package), imp.path)
        for name, uses in SAME_PACKAGE_USES.items():
            for symbol, member in uses:
                with self.subTest(file=name, symbol=symbol):
                    found = definers(symbol, leaves.PACKAGE)
                    self.assertTrue(found)
                    self.assertTrue(any(member in (REPO / path).read_text(encoding='utf-8') for path in found), member)
        # AppContentColors is the v0.4.6 blob, so primaryText() exists in the form AppPopupSurface calls
        colors = [path for path in definers('AppContentColors', leaves.PACKAGE)]
        self.assertTrue(colors)
        for path in colors:
            if 'v042-search-dependencies' in path:
                self.assertEqual(git_blob(BASE + 'AppContentColors.kt'), (REPO / path).read_bytes())

    def test_inventory_and_catalog_register_the_archive(self):
        self.assertIn(leaves.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(leaves.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(leaves.ARCHIVE + '/')]
        self.assertEqual(sorted(row['path'] for row in self.manifest['files']), sorted(row['path'] for row in rows))
        self.assertEqual(6, len(rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(['v046-design-system-leaves-a'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(leaves.COMMIT, row['upstreamCommit'])
            self.assertEqual('adaptedSource', row['kind'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
            self.assertEqual(sha(self.archive[Path(row['localPath']).name]), row['sha256'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == leaves.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({leaves.ARCHIVE + '/' + name for name in leaves.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(leaves.ARCHIVE + '/')])
        self.assertEqual(hashlib.sha256(lf((TOOLS / 'compatibility-source-inputs.json').read_bytes())).hexdigest(),
                         sync.COMPATIBILITY_CATALOG_SHA256)

    # ---- source hygiene and the Kotlin regression class ----------------------------------------------------

    def test_emitted_sources_and_the_regression_class_keep_balanced_delimiters(self):
        for name in leaves.VERBATIM_FILES:
            text = self.main[leaves.RELATIVE[name]].decode('utf-8')
            self.assertNotIn('\r', text)
            balanced(self, text)
        for name in leaves.CHAIN_FILES:
            balanced(self, self.main[leaves.RELATIVE[name]].decode('utf-8'))
        balanced(self, self.tests[leaves.RELATIVE['AppEntryIconPaletteTest.kt']].decode('utf-8'))
        text = HAND_WRITTEN_TEST.read_bytes().decode('utf-8')
        self.assertNotIn('\r', text)
        balanced(self, text)
        self.assertEqual(5, len(re.findall(r'@Test\s+fun ', text)))
        self.assertIn('package com.bilipai.desktop.ui\n', text)
        self.assertIn('class DesktopDesignSystemLeavesATest {', text)
        imported = set(re.findall(r'^import (com\.android\.purebilibili\.core\.ui\.\w+)$', text, re.M))
        declared = {leaves.PACKAGE + '.' + declared for rows in TOP_LEVEL.values() for _v, _k, declared in rows}
        self.assertEqual(set(), {name for name in imported if name not in declared})
        for forbidden in ('java.io.File', 'Window(', 'application {', 'HttpClient', 'java.net', 'System.getenv'):
            self.assertNotIn(forbidden, text)

    def test_the_tools_are_lf_only(self):
        for path in (TOOLS / 'v046_design_system_leaves_a.py', Path(__file__), TOOLS / 'extract-upstream-components.py',
                     HAND_WRITTEN_TEST, REPO / 'desktop/build.gradle.kts'):
            self.assertNotIn(b'\r', path.read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
