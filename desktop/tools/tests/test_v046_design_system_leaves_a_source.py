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
import v025_source_paths as v025

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


# Gradle's mergedRepositoryRoot is the live tree; inside the gate read point it is BILIPAI_LIVE_ROOT.
MERGED = Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO).resolve()


def gradle_modules(build, name):
    """`val <name> = listOf("a/", ...)`, followed through one `+ otherList` reference."""
    match = re.search(r'^val ' + name + r' = listOf\(([^)]*)\)(?: \+ (\w+))?$', build, re.M)
    if match is None:
        raise ValueError('build.gradle.kts no longer declares ' + name)
    modules = re.findall(r'"([^"]+)"', match.group(1))
    return modules + (gradle_modules(build, match.group(2)) if match.group(2) else [])


def canonical_original_identity(catalog, relative):
    """build.gradle.kts canonicalOriginalIdentity(): the legacy file prepareUpstreamSources copies for a live row."""
    identity = relative
    if relative.startswith(tuple(catalog['sourceRoots'])):
        identity = catalog['previousPaths'].get(relative, relative)
        if identity is None or identity not in catalog['paths']:
            raise ValueError('Unknown canonical original source: ' + relative)
    if (REPO / identity).is_file():
        return identity
    predecessors = sorted({old for old, new in catalog['previousPaths'].items() if new == identity and (REPO / old).is_file()})
    return predecessors[0] if len(predecessors) == 1 else identity


def gradle_block(build, header):
    """The lines of one top-level `val ... = mapOf(` / `setOf(` block of build.gradle.kts."""
    start = build.index('\n' + header + '\n') + len(header) + 2
    return build[start:build.index('\n)\n', start)]


def gradle_keys(build, name):
    """The string keys of `val <name>: Map<String, String> = mapOf(...)`."""
    return re.findall(r'^\s*"([^"]+)" to ', gradle_block(build, 'val ' + name + ': Map<String, String> = mapOf('), re.M)


def tsv_keys(relative_directories, trim):
    keys = []
    for directory in relative_directories:
        table = REPO / 'desktop' / directory / 'later-steps.tsv'
        lines = table.read_text(encoding='utf-8').splitlines() if table.is_file() else []
        keys += [(line.split('\t', 1)[0].strip() if trim else line.split('\t', 1)[0])
                 for line in lines if line.strip() and not line.startswith('#')]
    return set(keys)


def kotlin_files(root):
    return [path.relative_to(MERGED / 'app/src/main/java').as_posix() for path in sorted(root.rglob('*.kt'))] \
        if root.is_dir() else []


def migrated_app_paths(build):
    """build.gradle.kts migratedAppPaths (TON-1000, M4b, M4c, M4d): the app files compiled from the merged root."""
    java = MERGED / 'app/src/main/java'
    prefix = 'com/android/purebilibili/'
    held = tuple(gradle_keys(build, 'appCoreDesktopOwned') + gradle_keys(build, 'appCoreLaterSteps'))
    feature_later = tsv_keys(gradle_modules(build, 'appFeatureListDirectories'), trim=False)
    core = [path for area in ['core'] + gradle_modules(build, 'migratedAppFeatureAreas')
            for path in kotlin_files(java / prefix / area)
            if not path[len(prefix):].startswith(held) and path[len(prefix):] not in feature_later]
    m4d_later = tsv_keys(['upstream-core/m4d'], trim=True)
    m4d = [path for area in gradle_modules(build, 'migratedAppM4dAreas') for path in kotlin_files(java / prefix / area)
           if path[len(prefix):] not in m4d_later]
    single = re.findall(r'^\s*"([^"]+)",', gradle_block(build, 'val migratedAppSingleFiles: Set<String> = setOf('), re.M)
    m4c_held = set(gradle_keys(build, 'appM4cDesktopOwned') + gradle_keys(build, 'appM4cLaterSteps'))
    m4c = [path for root in gradle_modules(build, 'migratedAppM4cRoots') for path in kotlin_files(java / root)
           if path not in m4c_held]
    return set(core) | set(m4d) | set(single) | set(m4c)


@functools.lru_cache(maxsize=1)
def merged_modules():
    """(migratedUpstreamModules, migrated app paths, migrated identities): the module prefixes build.gradle.kts compiles
    whole from the merged root, the app files it compiles from the merged root, and every `com/android/purebilibili/...`
    identity in migratedUpstreamRelativePaths, which the main source set drops from build-dir copies."""
    build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
    modules = gradle_modules(build, 'migratedUpstreamModules')
    app = migrated_app_paths(build)
    identities = set(gate.parse_gradle(build).migrated_paths) | app
    for module in gradle_modules(build, 'migratedWholeModules'):
        root = MERGED / module / 'src/main/java'
        identities |= {path.relative_to(root).as_posix() for path in root.rglob('*.kt')}
    return tuple(modules), frozenset(app), frozenset(identities)


def upstream_identity(path):
    """build.gradle.kts upstreamIdentity(): the path from the last `com/android/purebilibili/` on, or ''."""
    path = '/' + path
    index = path.rfind('/com/android/purebilibili/')
    return '' if index < 0 else path[index + 1:]


@functools.lru_cache(maxsize=1)
def compile_universe():
    """(label, file) of every upstream-named Kotlin file the desktop main compile sees after M2 (e2528efba), the way
    build.gradle.kts selects them: the migratedUpstreamModules compile whole from the merged root, and so do the
    migratedAppPaths of app/src/main/java; every other direct
    row of upstream-sources.json is copied by prepareUpstreamSources from the legacy read point at its
    canonicalOriginalIdentity unless the Sync excludes it; a slice archive file stands for the producer copy at its
    package path, which the main source set drops when that identity is a migrated module path."""
    build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
    model = gate.parse_gradle(build)
    modules, app, migrated = merged_modules()
    candidates = []
    for module in modules:
        root = MERGED / module / 'src/main/java'
        candidates += [(path.relative_to(MERGED).as_posix(), path) for path in sorted(root.rglob('*.kt'))]
    candidates += [('app/src/main/java/' + path, MERGED / 'app/src/main/java' / path) for path in sorted(app)
                   if (MERGED / 'app/src/main/java' / path).is_file()]
    catalog = v025._catalog()
    excluded = set(model.sync_excludes)
    sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))['sources']
    for row in sources:
        path = row['path']
        if row.get('mode', 'direct') != 'direct' or not path.endswith('.kt') or path in excluded:
            continue
        if path.startswith(tuple(modules)) or any(path.endswith('/src/main/java/' + name) for name in migrated):
            continue
        identity = canonical_original_identity(catalog, path)
        candidates.append((identity, REPO / identity))
    for path in sorted((REPO / 'desktop/upstream-slices').glob('*/*.kt')):
        package = gate.parse_kotlin(path.read_text(encoding='utf-8'), path.name, 'carried', 'main').package
        if package.replace('.', '/') + '/' + path.name not in migrated:
            candidates.append((path.relative_to(REPO).as_posix(), path))
    return tuple(candidates)


def definers(name, package):
    """Labels of the compile-universe files that declare `name` in `package`."""
    found = []
    for label, path in compile_universe():
        raw = path.read_bytes()
        if name.encode('utf-8') not in raw:
            continue
        info = gate.parse_kotlin(raw.decode('utf-8'), path.name, 'carried', 'main')
        if info.package == package and any(d.name == name and d.visibility != 'private' for d in info.decls):
            found.append(label)
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
        # ad2967bbf re-pinned the live manifest to the merged bytes; the catalog keeps the reviewed live/legacy pair
        catalog = v025._catalog()['paths']
        for name in EXCLUDED_NOW:
            row = by_path[CANONICAL[name]]
            entry = catalog[CANONICAL[name]]
            self.assertEqual('direct', row.get('mode', 'direct'), name)
            self.assertEqual(entry['sha256'], row['sha256'], name)
            self.assertEqual(leaves.VENDORED_LF_SHA256[name], entry['legacySha256'], name)
            self.assertEqual(v025.reviewed_sha256(REPO, entry), sha(lf((REPO / CANONICAL[name]).read_bytes())), name)
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
        # after M2 (e2528efba) the compile reads design-system whole from the merged root, so the slice outputs of
        # these paths are dropped from the main source set and the merged file is the one definer
        names = {declared for rows in TOP_LEVEL.values() for _v, _k, declared in rows}
        owners = {}
        scanned = list(compile_universe())
        for root in ('desktop/src/main', 'desktop/src/test'):
            scanned += [(path.relative_to(REPO).as_posix(), path) for path in sorted((REPO / root).rglob('*.kt'))]
        for label, path in scanned:
            raw = path.read_bytes()
            if not any(name.encode('utf-8') in raw for name in names):
                continue
            info = gate.parse_kotlin(raw.decode('utf-8'), path.name, 'carried', 'main')
            if info.package != leaves.PACKAGE:
                continue
            for decl in info.decls:
                if decl.name in names and decl.visibility != 'private':
                    owners.setdefault(decl.name, []).append(label)
        for name in names:
            found = owners.get(name, [])
            expected = [BASE + file for file, rows in TOP_LEVEL.items()
                        if any(declared == name for _v, _k, declared in rows)]
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
                    files = dict(compile_universe())
                    self.assertTrue(any(member in files[path].read_text(encoding='utf-8') for path in found), member)
        # after M2 (e2528efba) the merged design-system module is the one AppContentColors the compile sees, and it
        # keeps primaryText() in the form AppPopupSurface calls
        self.assertEqual([BASE + 'AppContentColors.kt'], definers('AppContentColors', leaves.PACKAGE))

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
        # 377cdbf77 split the compact capsule height check into one test per style
        self.assertEqual(7, len(re.findall(r'@Test\s+fun ', text)))
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
