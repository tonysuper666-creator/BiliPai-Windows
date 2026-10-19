"""v0.4.6 theme token files emitted whole by the appearance producer (roadmap slice fnd-theme-typography-accent-tokens).

Root decision R-T1 (2026-10-19) is recorded here: the Windows port adopts the v0.4.6 Material baseline
typography as is (bodySmall 13 to 12sp, labelSmall 12 to 11sp) and adds no 4K compensation, because the app
has its own display scale (default 125 percent times the Windows scale) and the redesigned component library
reads the theme typography slots directly.
"""
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
import v046_theme_tokens as tokens

spec = importlib.util.spec_from_file_location('sync_upstream_theme_tokens', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

appearance_spec = importlib.util.spec_from_file_location(
    'extract_appearance_platform_theme_tokens', TOOLS / 'extract-appearance-platform.py')
appearance = importlib.util.module_from_spec(appearance_spec)
appearance_spec.loader.exec_module(appearance)

PRODUCER = 'tools/extract-appearance-platform.py'
TASK = 'extractUpstreamAppearance'
FEATURE = 'v046-theme-tokens'
R_T1 = ('R-T1 (Root, 2026-10-19): adopt the v0.4.6 Material baseline typography as is, with no extra 4K '
        'compensation. The desktop app has its own display scale (default 125 percent times the Windows '
        'scale); bodySmall 13 to 12sp and labelSmall 12 to 11sp are accepted.')
JVM_TEST_CLASSES = (
    'com.android.purebilibili.core.theme.Md3TypographyPolicyTest',
    'com.android.purebilibili.core.theme.AdaptiveAccentColorPolicyTest',
    'com.android.purebilibili.core.theme.AndroidNativeVariantThemePolicyTest',
    'com.android.purebilibili.core.theme.AppDisplayPolicyTest',
    'com.android.purebilibili.core.theme.AppFontWeightPolicyTest',
)
REMOVED_COLORS = ('ActionLikeDark', 'ActionCoinDark', 'ActionFavoriteDark', 'ActionShareDark', 'ActionCommentDark')
COLOR_KT = tokens.UPSTREAM_PATHS['Color.kt']
WINDOWS_TEST = 'desktop/src/test/kotlin/com/bilipai/desktop/appearance/DesktopTypographyBaselineTest.kt'
VIDEO_PLAYER_COMPONENTS = 'app/src/main/java/com/android/purebilibili/feature/video/player/VideoPlayerComponents.kt'
MATERIAL_BASELINE = {
    'displayLarge': ('57', '64'), 'displayMedium': ('45', '52'), 'displaySmall': ('36', '44'),
    'headlineLarge': ('32', '40'), 'headlineMedium': ('28', '36'), 'headlineSmall': ('24', '32'),
    'titleLarge': ('22', '28'), 'titleMedium': ('16', '24'), 'titleSmall': ('14', '20'),
    'bodyLarge': ('16', '24'), 'bodyMedium': ('14', '20'), 'bodySmall': ('12', '16'),
    'labelLarge': ('14', '20'), 'labelMedium': ('12', '16'), 'labelSmall': ('11', '16'),
}
V025_CJK_ADAPTED = {'bodyMedium': ('14', '21'), 'bodySmall': ('13', '19'), 'labelMedium': ('12', '17'),
                    'labelSmall': ('12', '16')}
MIUIX_TITLES = {'title1': ('32', '38'), 'title2': ('24', '30'), 'title3': ('20', '26'), 'title4': ('18', '24')}
LETTER_SPACING_BASELINE = {
    'displayLarge': '(-0.25).sp', 'displayMedium': '0.sp', 'displaySmall': '0.sp',
    'headlineLarge': '0.sp', 'headlineMedium': '0.sp', 'headlineSmall': '0.sp',
    'titleLarge': '0.sp', 'titleMedium': '0.15.sp', 'titleSmall': '0.1.sp',
    'bodyLarge': '0.5.sp', 'bodyMedium': '0.25.sp', 'bodySmall': '0.4.sp',
    'labelLarge': '0.1.sp', 'labelMedium': '0.5.sp', 'labelSmall': '0.5.sp',
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / tokens.ARCHIVE).iterdir() if p.is_file()}


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


def text_styles(source):
    """{slot: (fontSize, lineHeight)} for every `val slot = TextStyle(...)` block of AppTextStyles."""
    found = {}
    for match in re.finditer(r'val (\w+) = TextStyle\((.*?)\n    \)', source, re.S):
        size = re.search(r'fontSize = (\d+)\.sp', match.group(2))
        height = re.search(r'lineHeight = (\d+)\.sp', match.group(2))
        found[match.group(1)] = (size.group(1), height.group(1))
    return found


def text_style_field(source, field):
    """{slot: raw value text} of one `field = value` line of every `val slot = TextStyle(...)` block."""
    found = {}
    for match in re.finditer(r'val (\w+) = TextStyle\((.*?)\n    \)', source, re.S):
        value = re.search(r'\n\s+' + field + r' = ([^,\n]+)', match.group(2))
        found[match.group(1)] = value.group(1).strip() if value else None
    return found


def miuix_titles(source):
    found = {}
    for match in re.finditer(r'(title\d) = \w+\.copy\(fontSize = (\d+)\.sp, lineHeight = (\d+)\.sp', source):
        found[match.group(1)] = (match.group(2), match.group(3))
    return found


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


class V046ThemeTokensSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-ttk-')
        cls.root = Path(cls.temporary.name)
        cls.generate_into(cls.root, True)
        cls.main = tree(cls.root / 'appearance')
        cls.tests = tree(cls.root / 'tests')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        cls.universe_cache = None

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    @staticmethod
    def generate_into(root, policy_only, with_tests=True):
        appearance.generate(REPO, root / 'appearance', root / 'resources', policy_only,
                            root / 'tests' if with_tests else None)

    # ---- archive -------------------------------------------------------------------------------

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        self.assertEqual(self.archive, archive_files())
        leaves = sorted(self.archive)
        self.assertEqual(sorted(tokens.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        self.assertEqual(11, len(tokens.EXPECTED))
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-ttk-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / tokens.ARCHIVE, repo / tokens.ARCHIVE)
                target = repo / tokens.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    tokens.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-ttk-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / tokens.ARCHIVE, repo / tokens.ARCHIVE)
            (repo / tokens.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                tokens.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-ttk-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / tokens.ARCHIVE, repo / tokens.ARCHIVE)
            (repo / tokens.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                tokens.fixed(repo)
        self.assertEqual(self.archive, archive_files())

    def test_archive_equals_the_upstream_git_blobs_and_replaces_the_pinned_vendored_copies(self):
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual(sorted(tokens.EXPECTED), sorted(row['filename'] for row in manifest['files']))
        for row in manifest['files']:
            with self.subTest(name=row['filename']):
                archived = self.archive[row['filename']]
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', tokens.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, archived)
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertNotIn(b'\r', archived)
        listed = {row['path']: row for row in self.sources['sources']}
        for name in tokens.MAIN_FILES:
            with self.subTest(vendored=name):
                path = tokens.UPSTREAM_PATHS[name]
                vendored = (REPO / path).read_bytes().replace(b'\r\n', b'\n')
                self.assertEqual(tokens.VENDORED_LF_SHA256[name], sha(vendored))
                self.assertEqual(listed[path]['sha256'], sha(vendored))
                self.assertEqual('direct', listed[path].get('mode', 'direct'))
                self.assertNotEqual(vendored, self.archive[name])
        self.assertEqual(set(tokens.MAIN_FILES), {row['replacedBy'] for row in manifest['replacedVendoredCopies']})
        self.assertEqual({tokens.UPSTREAM_PATHS[name] for name in tokens.MAIN_FILES},
                         {row['path'] for row in manifest['replacedVendoredCopies']})

    def test_unadapted_files_stay_out_of_the_archive(self):
        manifest = json.loads(self.archive['manifest.json'])
        carried = {row['path'] for row in manifest['files']}
        reference = 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AdaptivePreferenceComponents.kt'
        settings = 'app/src/main/java/com/android/purebilibili/feature/settings/ui/SettingsSections.kt'
        self.assertNotIn(reference, carried)
        self.assertNotIn(settings, carried)
        listed = {row['path']: row for row in self.sources['sources']}
        self.assertEqual('platform-adapter-reference', listed[reference]['mode'])
        self.assertEqual('policy-extract', listed[settings]['mode'])
        self.assertEqual(2, len(manifest['notCarried']))
        self.assertTrue(manifest['notCarried'][0].startswith(reference))
        self.assertTrue(manifest['notCarried'][1].startswith(settings))

    # ---- emission ------------------------------------------------------------------------------

    def test_real_producer_emits_the_exact_bytes_and_the_original_tests(self):
        for name in tokens.MAIN_FILES:
            with self.subTest(main=name):
                self.assertEqual(self.archive[name], self.main[tokens.RELATIVE[name]])
                matches = [p for p, raw in self.main.items() if p.endswith('/' + name)]
                self.assertEqual([tokens.RELATIVE[name]], matches)
        for name in tokens.TEST_FILES:
            with self.subTest(test=name):
                self.assertEqual(self.archive[name], self.tests[tokens.RELATIVE[name]])
                matches = [p for p in self.tests if p.endswith('/' + name)]
                self.assertEqual([tokens.RELATIVE[name]], matches)
                self.assertNotIn(tokens.RELATIVE[name], self.main)
        for name in tokens.MAIN_FILES:
            self.assertNotIn(tokens.RELATIVE[name], self.tests)
        vendored_marker = (REPO / tokens.UPSTREAM_PATHS['Type.kt']).read_bytes()
        self.assertNotEqual(vendored_marker.replace(b'\r\n', b'\n'), self.main[tokens.RELATIVE['Type.kt']])
        receipt = tokens.emit(REPO, self.root / 'direct-emit', self.root / 'direct-emit-tests')
        self.assertEqual([tokens.RELATIVE[n] for n in tokens.MAIN_FILES], [r['path'] for r in receipt['files']])
        self.assertEqual([tokens.RELATIVE[n] for n in tokens.TEST_FILES], [r['path'] for r in receipt['tests']])
        for row in receipt['files'] + receipt['tests']:
            self.assertEqual(sha(self.archive[Path(row['path']).name]), row['sha256'])

    def test_full_mode_and_missing_test_root_and_reruns_are_consistent_and_deterministic(self):
        again = self.root / 'again'
        self.generate_into(again, True)
        self.assertEqual(self.main, tree(again / 'appearance'))
        self.assertEqual(self.tests, tree(again / 'tests'))
        self.generate_into(again, True)
        self.assertEqual(self.main, tree(again / 'appearance'))
        self.assertEqual(self.tests, tree(again / 'tests'))
        without = self.root / 'without-tests'
        self.generate_into(without, True, with_tests=False)
        self.assertFalse((without / 'tests').exists())
        for name in tokens.MAIN_FILES:
            self.assertEqual(self.archive[name], (without / 'appearance' / tokens.RELATIVE[name]).read_bytes())
        full = self.root / 'full'
        self.generate_into(full, False)
        for name in tokens.MAIN_FILES:
            # The whole-file DIRECT copy the full mode writes first is replaced by the archive bytes.
            self.assertEqual(self.archive[name], (full / 'appearance' / tokens.RELATIVE[name]).read_bytes())
        for name in tokens.TEST_FILES:
            self.assertEqual(self.archive[name], (full / 'tests' / tokens.RELATIVE[name]).read_bytes())

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        nested = REPO / tokens.ARCHIVE / 'child'
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                tokens.emit(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                tokens.emit(REPO, self.root / 'ok', nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                tokens.validate_outputs(REPO, REPO)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                appearance.generate(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                appearance.generate(REPO, self.root / 'x', self.root / 'xr', True, nested)
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / tokens.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)

    def test_a_stale_regular_file_is_replaced_and_special_targets_fail_closed(self):
        relatives = [tokens.RELATIVE[name] for name in tokens.MAIN_FILES + tokens.TEST_FILES]

        def prepare(root, relative):
            (root / 'outside.txt').write_bytes(b'OUTSIDE')
            target = root / relative
            target.parent.mkdir(parents=True)
            (target.parent / 'decoy.kt').write_bytes(b'DECOY')
            return target

        for relative in relatives:
            with self.subTest(kind='stale', relative=relative), tempfile.TemporaryDirectory(prefix='bp-ttk-') as tmp:
                root = Path(tmp)
                before_archive = archive_files()
                target = prepare(root, relative)
                target.write_bytes(b'SENTINEL-FOREIGN')
                if relative in {tokens.RELATIVE[n] for n in tokens.TEST_FILES}:
                    main, tests = root / 'other', root
                else:
                    main, tests = root, root / 'other'
                tokens.emit(REPO, main, tests)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                tokens.emit(REPO, main, tests)
                self.assertEqual(self.archive[Path(relative).name], target.read_bytes())
                self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                self.assertEqual(before_archive, archive_files())
        for kind in ('symlink', 'directory'):
            for relative in (tokens.RELATIVE['Type.kt'], tokens.RELATIVE['Md3TypographyPolicyTest.kt']):
                with self.subTest(kind=kind, relative=relative), tempfile.TemporaryDirectory(prefix='bp-ttk-') as tmp:
                    root = Path(tmp)
                    before_archive = archive_files()
                    is_test = relative.endswith('Test.kt')
                    holder = root / 'tests' if is_test else root / 'main'
                    holder.mkdir(parents=True)
                    target = prepare(holder, relative)
                    if kind == 'symlink':
                        dest = target.parent / 'neighbor.bin'
                        dest.write_bytes(b'NEIGHBOR')
                        try:
                            target.symlink_to(dest)
                        except OSError:
                            self.skipTest('symlink creation is not permitted')
                    else:
                        target.mkdir()
                        (target / 'nested.txt').write_bytes(b'NESTED')
                    before = frozen(root)
                    with self.assertRaises(ValueError) as caught:
                        tokens.emit(REPO, root / 'main', root / 'tests')
                    self.assertIn('not a regular file: ' + relative, str(caught.exception))
                    self.assertEqual(before, frozen(root))
                    self.assertEqual(before_archive, archive_files())

    # ---- wiring --------------------------------------------------------------------------------

    def test_gradle_inventory_and_producer_hook_wire_the_archive_and_exclude_the_direct_copies(self):
        build = self.build
        block = task_block(build, TASK)
        prepared = prepare_block(build)
        model = gate.parse_gradle(build)
        for name in tokens.MAIN_FILES:
            path = tokens.UPSTREAM_PATHS[name]
            line = 'exclude(canonicalOriginalIdentity("' + path + '"))'
            self.assertEqual(1, build.count(line), line)
            self.assertEqual(1, prepared.count(line), line)
            self.assertEqual(1, model.sync_excludes.count(path), path)
        for name in tokens.TEST_FILES:
            self.assertEqual(0, build.count(tokens.UPSTREAM_PATHS[name]), name)
        for literal in ('inputs.file("tools/v046_theme_tokens.py")', 'inputs.dir("upstream-slices/v046-theme-tokens")'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'v046-theme-tokens' in row])
        self.assertEqual(1, build.count('kotlin.srcDir(generatedAppearance)'))
        self.assertEqual(1, build.count('kotlin.sourceSets.named("test") { kotlin.srcDir(generatedThemeRoleTests) }'))
        self.assertIn('"--test-output", generatedThemeRoleTests.get().asFile.absolutePath', block)
        self.assertIn('"--policy-only"', block)
        # upstream-sources.json is untouched: the six paths stay listed as direct, the Sync excludes them.
        listed = [row for row in self.sources['sources'] if row['path'] in
                  {tokens.UPSTREAM_PATHS[n] for n in tokens.MAIN_FILES}]
        self.assertEqual(6, len(listed))
        self.assertEqual({'direct'}, {row.get('mode', 'direct') for row in listed})
        self.assertIn(tokens.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(tokens.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(tokens.ARCHIVE + '/')]
        self.assertEqual(len(tokens.EXPECTED), len(rows))
        self.assertEqual({tokens.UPSTREAM_PATHS[n] for n in tokens.EXPECTED}, {row['path'] for row in rows})
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual([FEATURE], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(tokens.COMMIT, row['upstreamCommit'])
            self.assertEqual(sha(self.archive[Path(row['localPath']).name]), row['sha256'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == tokens.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({tokens.ARCHIVE + '/' + name for name in tokens.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(tokens.ARCHIVE + '/')])
        producer = (TOOLS / 'extract-appearance-platform.py').read_text(encoding='utf-8')
        start = producer.index('def generate(')
        chrome = producer.index('validate_outputs(repo, output, resource_output or output.parent/\'resources\', test_output)', start)
        validate = 'from v046_theme_tokens import validate_outputs as validate_theme_token_outputs'
        validate_at = producer.index(validate, start)
        parser_at = producer.index("spec=importlib.util.spec_from_file_location('appearance_parser'", start)
        self.assertEqual(1, producer.count(validate))
        self.assertEqual(1, producer.count(
            "validate_theme_token_outputs(repo, output, resource_output or output.parent/'resources', test_output)"))
        self.assertLess(chrome, validate_at)
        self.assertLess(validate_at, parser_at)
        emit = 'from v046_theme_tokens import emit as emit_theme_tokens'
        self.assertEqual(1, producer.count(emit))
        self.assertEqual(1, producer.count('emit_theme_tokens(repo, output, test_output)'))
        seed_at = producer.index('emit_seed_palette(repo, output', start)
        emit_at = producer.index(emit, start)
        call_at = producer.index('emit_theme_tokens(repo, output, test_output)', emit_at)
        resources_at = producer.index('for language,path in RESOURCES.items():', call_at)
        self.assertLess(seed_at, emit_at)
        self.assertLess(emit_at, call_at)
        self.assertLess(call_at, resources_at)
        self.assertFalse(any(line.startswith('import v046_theme_tokens') or line.startswith('from v046_theme_tokens')
                             for line in producer.splitlines() if not line.startswith('    ')))
        # Only the appearance producer and this slice's own tests name the generator or the archive.
        namers = sorted(p.name for p in TOOLS.glob('*.py') if 'v046_theme_tokens' in p.read_text(encoding='utf-8')
                        or 'v046-theme-tokens' in p.read_text(encoding='utf-8'))
        self.assertEqual(['extract-appearance-platform.py', 'v046_theme_tokens.py'], namers)

    # ---- one definer, call chain, decision record ----------------------------------------------

    def compiled_declarations(self):
        """Declarations of the main and test source sets after the appearance producer ran (mirror run)."""
        if type(self).universe_cache is not None:
            return type(self).universe_cache
        scratch = tempfile.mkdtemp(prefix='bp-ttk-')
        try:
            root, env = gate.make_scratch_repo(REPO, scratch)
            model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
            tasks, _skipped = gate.select_producers(model, only={TASK})
            self.assertEqual([TASK], [task.name for task in tasks])
            results = gate.run_producers(root, tasks, env)
            self.assertEqual([0], [row['returncode'] for row in results], results)
            sources, _notes = gate.collect_sources(root, model)
            wanted = set()
            for name in tokens.MAIN_FILES:
                text = self.archive[name].decode('utf-8')
                info = gate.parse_kotlin(text, name, 'archive', 'carried')
                wanted.update(d.name for d in info.decls)
            wanted |= {'resolveMaterialTypography', 'toMiuixTextStyles', 'Md3Typography', 'BiliMiuixTypography',
                       'AppTextStyles', 'scaled', 'withFontWeight', 'AppUiStyle'}
            needles = [name.encode('utf-8') for name in wanted]
            test_needles = [name.rsplit('.', 1)[-1].encode('utf-8') for name in JVM_TEST_CLASSES]
            hits = []
            for source in sources:
                if source.set not in ('main', 'test'):
                    continue
                raw = source.real.read_bytes()
                if source.set == 'main' and not any(n in raw for n in needles):
                    continue
                if source.set == 'test' and not any(n in raw for n in test_needles):
                    continue
                info = gate.analyze_source(source)
                for decl in info.decls:
                    hits.append((source.set, source.logical, info.package, decl.visibility, decl.kind,
                                 decl.receiver, decl.name, decl.sig))
            type(self).universe_cache = hits
            return hits
        finally:
            shutil.rmtree(scratch, ignore_errors=True)

    def test_each_top_level_declaration_of_the_six_files_has_one_definer_and_it_is_the_archive_copy(self):
        hits = self.compiled_declarations()
        for name in tokens.MAIN_FILES:
            text = self.archive[name].decode('utf-8')
            info = gate.parse_kotlin(text, name, 'archive', 'carried')
            self.assertEqual(tokens.PACKAGE, info.package)
            self.assertGreater(len(info.decls), 0)
            expected_path = 'build/generated/appearance/' + tokens.RELATIVE[name]
            for decl in info.decls:
                with self.subTest(file=name, declaration=decl.label()):
                    found = [h for h in hits if h[0] == 'main' and h[2] == info.package and h[6] == decl.name
                             and h[4] == decl.kind and h[5] == decl.receiver and h[7] == decl.sig]
                    self.assertEqual(1, len(found), found)
                    self.assertEqual(expected_path, found[0][1])
                    self.assertEqual(decl.visibility, found[0][3])
        by_name = {}
        for hit in hits:
            if hit[0] == 'main':
                by_name.setdefault(hit[6], []).append(hit[1])
        for name in ('AppTextStyles', 'Md3Typography', 'BiliMiuixTypography', 'resolveMaterialTypography'):
            self.assertEqual(['build/generated/appearance/' + tokens.RELATIVE[
                {'AppTextStyles': 'AppTextStyles.kt', 'Md3Typography': 'Type.kt', 'BiliMiuixTypography': 'Type.kt',
                 'resolveMaterialTypography': 'AndroidNativeVariantThemePolicy.kt'}[name]]], by_name[name], name)
        for path in {h[1] for h in hits if h[0] == 'main'}:
            self.assertNotIn('generated/upstream/', path)

    def test_original_tests_have_one_definer_each(self):
        hits = self.compiled_declarations()
        for fqcn in JVM_TEST_CLASSES:
            package, name = fqcn.rsplit('.', 1)
            with self.subTest(test=name):
                found = [h for h in hits if h[0] == 'test' and h[2] == package and h[6] == name]
                self.assertEqual(1, len(found), found)
                self.assertEqual('build/generated/original-theme-role-tests/' + tokens.RELATIVE[name + '.kt'], found[0][1])
        self.assertEqual({tokens.PACKAGE + '.' + n[:-3] for n in tokens.TEST_FILES}, set(JVM_TEST_CLASSES))
        self.assertEqual(33, sum(tokens.TEST_COUNTS.values()))
        for name, count in tokens.TEST_COUNTS.items():
            self.assertEqual(count, self.archive[name].decode('utf-8').count('@Test'), name)
            self.assertEqual(count, self.tests[tokens.RELATIVE[name]].decode('utf-8').count('@Test'), name)
            self.assertIn('import kotlin.test.Test\n', self.archive[name].decode('utf-8'))
            self.assertNotIn('org.junit', self.archive[name].decode('utf-8'))

    def test_theme_call_chain_resolves_to_the_emitted_policy_functions(self):
        hits = self.compiled_declarations()
        resolve = [h for h in hits if h[0] == 'main' and h[6] == 'resolveMaterialTypography']
        self.assertEqual(1, len(resolve))
        self.assertEqual((tokens.PACKAGE, 'fun', ''), (resolve[0][2], resolve[0][4], resolve[0][5]))
        convert = [h for h in hits if h[0] == 'main' and h[6] == 'toMiuixTextStyles']
        self.assertEqual(1, len(convert))
        self.assertEqual((tokens.PACKAGE, 'fun', 'Typography'), (convert[0][2], convert[0][4], convert[0][5]))
        self.assertTrue(convert[0][1].endswith('AppDisplayPolicy.kt'))
        # The two Typography transforms the Windows theme applies between the calls exist once each.
        for name in ('scaled', 'withFontWeight'):
            ext = [h for h in hits if h[0] == 'main' and h[6] == name and h[5] == 'Typography' and h[3] == 'public']
            self.assertEqual(1, len(ext), name)
        signature = self.archive['AndroidNativeVariantThemePolicy.kt'].decode('utf-8')
        declared = re.search(r'fun resolveMaterialTypography\(\s*uiStyle: AppUiStyle,\s*'
                             r'liquidGlassEnabled: Boolean = true,?\s*\): Typography = Md3Typography', signature)
        self.assertIsNotNone(declared)
        self.assertIn('fun Typography.toMiuixTextStyles(): TextStyles = defaultTextStyles(',
                      self.archive['AppDisplayPolicy.kt'].decode('utf-8'))
        # Source-version evidence only, NOT compiled: the appearance producer emits no PureBiliBiliTheme, so
        # this archived v0.4.3 Theme.kt call chain is never part of the compile universe (asserted below).
        # It documents the order the original theme uses: convert first, then scale and re-weight the TextStyles.
        carried = (REPO / 'desktop/upstream-slices/v043-theme-chrome/Theme.kt').read_text(encoding='utf-8')
        miuix_chain = carried[carried.index('val miuixTextStyles = remember('):]
        miuix_chain = miuix_chain[:miuix_chain.index('\n    }\n')]
        order = [miuix_chain.index(token) for token in
                 ('resolveMaterialTypography(', '.toMiuixTextStyles()', '.scaled(', '.withFontWeight(')]
        self.assertEqual(sorted(order), order)
        self.assertEqual([], [h for h in hits if h[0] == 'main' and h[6] == 'PureBiliBiliTheme'])
        self.assertTrue(any(h[0] == 'main' and h[1].endswith('/Theme.kt') for h in hits),
                        'the emitted Theme.kt must be in the analysed universe for the PureBiliBiliTheme check')
        # Windows-owned theme: asserted by meaning, not by formatting. The Miuix text styles come only from
        # the desktop-owned helper (convert first, then scale, then weight); the Material typography keeps its own
        # scaled and weighted chain; nothing else in the Windows tree converts or resolves the typography.
        main_root = REPO / 'desktop/src/main'
        appearance_dir = 'kotlin/com/bilipai/desktop/appearance/'
        by_file = {p.relative_to(main_root).as_posix(): re.sub(r'\s+', '', p.read_text(encoding='utf-8'))
                   for p in sorted(main_root.rglob('*.kt'))}
        self.assertEqual([appearance_dir + 'DesktopMiuixTextStyles.kt'],
                         [n for n, body in by_file.items() if '.toMiuixTextStyles(' in body])
        self.assertEqual([appearance_dir + 'DesktopAppearanceTheme.kt', appearance_dir + 'DesktopMiuixTextStyles.kt'],
                         [n for n, body in by_file.items() if 'resolveMaterialTypography(' in body])
        desktop = by_file[appearance_dir + 'DesktopAppearanceTheme.kt']
        self.assertEqual(1, desktop.count('resolveMaterialTypography('))
        self.assertEqual(0, desktop.count('toMiuixTextStyles'))
        self.assertEqual(1, desktop.count('resolveDesktopMiuixTextStyles('))
        self.assertEqual(1, desktop.count('typography=typography,'))
        self.assertEqual(1, desktop.count('MiuixTheme(controller=controller,textStyles=textStyles)'))
        material = re.search(r'valtypography=remember\(([^)]*)\)\{(.*?)\}valtextStyles', desktop)
        self.assertIsNotNone(material)
        self.assertEqual('resolveMaterialTypography(settings.uiStyle).scaled(settings.appFontSizePreset.multiplier)'
                         '.withFontWeight(settings.appFontWeightPreset.fontWeight)', material.group(2))
        miuix = re.search(r'valtextStyles=remember\(([^)]*)\)\{resolveDesktopMiuixTextStyles\(([^)]*)\)\}', desktop)
        self.assertIsNotNone(miuix)
        keys = ['settings.uiStyle', 'settings.appFontSizePreset', 'settings.appFontWeightPreset']
        self.assertEqual(keys, miuix.group(1).split(','))
        self.assertEqual(keys, miuix.group(2).split(','))
        self.assertEqual(keys, material.group(1).split(','))
        # The pin for the conversion order is the helper's body, not the theme file's layout.
        helper = by_file[appearance_dir + 'DesktopMiuixTextStyles.kt'].replace(',)', ')')
        body = helper[helper.index('internalfunresolveDesktopMiuixTextStyles('):]
        self.assertEqual('internalfunresolveDesktopMiuixTextStyles(uiStyle:AppUiStyle,fontSize:AppFontSizePreset,'
                         'fontWeight:AppFontWeightPreset):TextStyles=resolveMaterialTypography(uiStyle)'
                         '.toMiuixTextStyles().scaled(fontSize.multiplier).withFontWeight(fontWeight.fontWeight)', body)
        # The Windows-owned test for the same chain stays valid for the baseline.
        windows_test = (REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/appearance/DesktopAppearanceTest.kt'
                        ).read_text(encoding='utf-8')
        self.assertIn('val base = resolveMaterialTypography(AppUiStyle.MATERIAL3)', windows_test)
        self.assertIn('assertEquals(base.bodyLarge.fontSize * 1.25f, scaled.bodyLarge.fontSize)', windows_test)

    def test_root_decision_r_t1_material_baseline_typography_without_4k_compensation(self):
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual(R_T1, manifest['typographyDecision'])
        self.assertTrue(manifest['typographyDecision'].startswith('R-T1'))
        text_styles_source = self.archive['AppTextStyles.kt'].decode('utf-8')
        self.assertEqual(MATERIAL_BASELINE, text_styles(text_styles_source))
        self.assertNotIn('lineHeightStyle', text_styles_source)
        type_source = self.archive['Type.kt'].decode('utf-8')
        self.assertIn('/** Material 3\'s unmodified typography scale. */\nval Md3Typography = Typography()\n', type_source)
        policy = self.archive['AndroidNativeVariantThemePolicy.kt'].decode('utf-8')
        self.assertIn('liquidGlassEnabled: Boolean = true,\n): Typography = Md3Typography\n', policy)
        self.assertNotIn('BiliMiuixTypography', policy)
        self.assertEqual(MIUIX_TITLES, miuix_titles(self.archive['AppDisplayPolicy.kt'].decode('utf-8')))
        # What changes against the retained v0.2.5 files (the CJK-adapted scale that is being replaced).
        vendored = (REPO / tokens.UPSTREAM_PATHS['AppTextStyles.kt']).read_text(encoding='utf-8').replace('\r\n', '\n')
        before = text_styles(vendored)
        changed = {slot: (before[slot], MATERIAL_BASELINE[slot]) for slot in MATERIAL_BASELINE
                   if before[slot] != MATERIAL_BASELINE[slot]}
        self.assertEqual({slot: (V025_CJK_ADAPTED[slot], MATERIAL_BASELINE[slot]) for slot in V025_CJK_ADAPTED},
                         changed)
        self.assertEqual(('13', '19'), before['bodySmall'])
        self.assertEqual(('12', '16'), MATERIAL_BASELINE['bodySmall'])
        self.assertEqual(('12', '16'), before['labelSmall'])
        self.assertEqual(('11', '16'), MATERIAL_BASELINE['labelSmall'])
        # The decision record lists every consequence, each checked against the files it describes, so Root can
        # eyeball CJK rendering on the real window: sizes and line heights, letter spacing, lineHeightStyle, titles.
        effects = manifest['typographyDecisionEffects']
        self.assertEqual(6, len(effects))
        self.assertIn('resolveDesktopMiuixTextStyles', manifest['purpose'])
        self.assertNotIn('No call sites change', manifest['purpose'])
        phrases = []
        for slot, (old_size, old_height) in V025_CJK_ADAPTED.items():
            new_size, new_height = MATERIAL_BASELINE[slot]
            if old_size != new_size:
                phrases.append(slot + ' ' + old_size + ' to ' + new_size + 'sp')
            if old_height != new_height:
                phrases.append(slot + ' ' + old_height + ' to ' + new_height + 'sp')
        self.assertEqual(5, len(phrases), phrases)
        for phrase in phrases:
            self.assertIn(phrase, effects[0])
        self.assertEqual(LETTER_SPACING_BASELINE, text_style_field(text_styles_source, 'letterSpacing'))
        self.assertEqual({slot: '0.sp' for slot in MATERIAL_BASELINE}, text_style_field(vendored, 'letterSpacing'))
        positive = {slot: re.search(r'-?\d+(?:\.\d+)?', value).group(0)
                    for slot, value in LETTER_SPACING_BASELINE.items() if value != '0.sp'}
        self.assertEqual(9, len(positive))
        for slot, number in positive.items():
            self.assertIn(slot + ' ' + number, effects[1])
        self.assertEqual(15, vendored.count('lineHeightStyle = CjkMd3LineHeightStyle'))
        self.assertIn('alignment = LineHeightStyle.Alignment.Center', vendored)
        self.assertIn('trim = LineHeightStyle.Trim.None', vendored)
        self.assertIn('LineHeightStyle(Center, Trim.None)', effects[2])
        vendored_policy = (REPO / tokens.UPSTREAM_PATHS['AppDisplayPolicy.kt']).read_text(encoding='utf-8').replace('\r\n', '\n')
        self.assertIn('    title1 = displayLarge,\n    title2 = headlineLarge,\n'
                      '    title3 = headlineMedium,\n    title4 = headlineSmall,\n', vendored_policy)
        self.assertEqual(('57', '32', '28', '24'), tuple(before[slot][0] for slot in
                                                          ('displayLarge', 'headlineLarge', 'headlineMedium', 'headlineSmall')))
        for text in ('(57/32/28/24sp)', '32/24/20/18sp', '38/30/26/24sp'):
            self.assertIn(text, effects[3])
        self.assertEqual(('32/24/20/18', '38/30/26/24'),
                         tuple('/'.join(MIUIX_TITLES['title' + str(n)][i] for n in range(1, 5)) for i in (0, 1)))
        self.assertIn('resolveDesktopMiuixTextStyles', effects[4])
        self.assertIn('visual check', effects[5])
        # No Windows-owned code compensates for the larger screen in the typography: the only transforms
        # are the user's font preset multiplier and weight, and the app has its own display scale.
        owners = [p for p in sorted((REPO / 'desktop/src/main').rglob('*.kt'))
                  if 'resolveMaterialTypography(' in p.read_text(encoding='utf-8')]
        self.assertEqual(['DesktopAppearanceTheme.kt', 'DesktopMiuixTextStyles.kt'], [p.name for p in owners])
        for path in sorted((REPO / 'desktop/src/main').rglob('*.kt')):
            body = path.read_text(encoding='utf-8')
            for forbidden in ('AppTextStyles', 'Md3Typography', 'BiliMiuixTypography'):
                self.assertNotIn(forbidden, body, path.name)
        display_scale = [p.name for p in sorted((REPO / 'desktop/src/main').rglob('*.kt'))
                         if 'fun DesktopWindowsDisplayScaleSettings(' in p.read_text(encoding='utf-8')]
        self.assertEqual(1, len(display_scale), display_scale)

    def test_color_kt_residue_removes_constants_nothing_compiled_consumes(self):
        archived = self.archive['Color.kt'].decode('utf-8')
        vendored = (REPO / COLOR_KT).read_text(encoding='utf-8')
        for name in REMOVED_COLORS:
            self.assertNotIn(name, archived, name)
            self.assertIn('val ' + name + ' = Color(', vendored, name)
        # The remaining palette keeps every other top-level value of the retained file.
        old_names = set(re.findall(r'(?m)^val (\w+)', vendored))
        new_names = set(re.findall(r'(?m)^val (\w+)', archived))
        self.assertEqual(set(REMOVED_COLORS), old_names - new_names)
        self.assertEqual(set(), new_names - old_names)
        pattern = re.compile(r'\b(' + '|'.join(REMOVED_COLORS) + r')\b')
        consumers = []
        for base in (REPO / 'desktop/src', REPO / 'desktop/upstream-slices', TOOLS):
            for path in sorted(base.rglob('*')):
                if not path.is_file() or path.suffix not in {'.kt', '.kts', '.java', '.py', '.json'}:
                    continue
                if path.resolve() == Path(__file__).resolve():
                    continue
                raw = path.read_bytes()
                if any(name.encode('utf-8') in raw for name in REMOVED_COLORS):
                    consumers.append(path.relative_to(REPO).as_posix())
        self.assertEqual([], consumers)
        # The one vendored consumer imports the constants but is not part of any compile input.
        vendored_consumers = []
        for root in ('app', 'design-system', 'core-data', 'core-player', 'network-core', 'settings-core',
                     'plugin-sdk', 'plugins', 'danmaku-engine', 'miuix-navigation'):
            for path in sorted((REPO / root).rglob('*.kt')):
                if pattern.search(path.read_text(encoding='utf-8', errors='replace')):
                    vendored_consumers.append(path.relative_to(REPO).as_posix())
        self.assertEqual([VIDEO_PLAYER_COMPONENTS], vendored_consumers)
        component = (REPO / VIDEO_PLAYER_COMPONENTS).read_text(encoding='utf-8')
        for name in REMOVED_COLORS:
            self.assertEqual(1, len(re.findall(r'\b' + name + r'\b', component)), name)
            self.assertIn('import com.android.purebilibili.core.theme.' + name, component)
        self.assertEqual([], [row['path'] for row in self.sources['sources'] if row['path'] == VIDEO_PLAYER_COMPONENTS])
        # The invariant is that nothing compiled references the five removed constants: the files the appearance
        # producer emits (main and test) carry none. The retained VideoPlayerComponents is not named here, so a
        # later slice may carry the v0.4.6 blob, which no longer references them (checked below).
        emitted = sorted(path for path, raw in list(self.main.items()) + list(self.tests.items())
                         if any(name.encode('utf-8') in raw for name in REMOVED_COLORS))
        self.assertEqual([], emitted, 'an emitted file references a constant that v0.4.6 Color.kt removed; '
                         'the v0.4.6 VideoPlayerComponents blob has no consumers, so carry it instead of '
                         're-adding the constants')
        found = subprocess.run(['git', '-c', 'core.autocrlf=false', 'grep', '-l', '-E',
                                r'\b(' + '|'.join(REMOVED_COLORS) + r')\b', tokens.COMMIT, '--', '*.kt'],
                               cwd=str(REPO), capture_output=True)
        self.assertEqual((1, b''), (found.returncode, found.stdout), found.stdout.decode('utf-8', 'replace'))

    def test_emitted_sources_keep_balanced_delimiters_and_lf_endings(self):
        for name in tokens.MAIN_FILES:
            text = self.main[tokens.RELATIVE[name]].decode('utf-8')
            self.assertEqual(self.archive[name].decode('utf-8'), text)
            self.assertNotIn('\r', text)
            balanced(self, text)
        for name in tokens.TEST_FILES:
            text = self.tests[tokens.RELATIVE[name]].decode('utf-8')
            self.assertNotIn('\r', text)
            balanced(self, text)
        self.assertNotIn(b'\r', (TOOLS / 'v046_theme_tokens.py').read_bytes())
        self.assertNotIn(b'\r', (TOOLS / 'tests/test_v046_theme_tokens_source.py').read_bytes())

    def test_windows_owned_jvm_regression_records_the_baseline_without_windows_or_gpu_or_network(self):
        raw = (REPO / WINDOWS_TEST).read_bytes()
        self.assertNotIn(b'\r', raw)
        text = raw.decode('utf-8')
        self.assertTrue(text.startswith('package com.bilipai.desktop.appearance\n'))
        self.assertEqual(6, text.count('@Test fun '))
        self.assertEqual(6, len(re.findall(r'(?m)^    @Test fun \w+\(\) \{$', text)))
        self.assertEqual(1, text.count('class DesktopTypographyBaselineTest {'))
        self.assertIn('R-T1', text)
        balanced(self, text)
        for imported in re.findall(r'(?m)^import (\S+)$', text):
            self.assertTrue(imported.startswith(('androidx.compose.ui.unit.', 'com.android.purebilibili.core.theme.',
                                                 'org.junit.jupiter.api.Test', 'kotlin.test.')) or imported in {
                'androidx.compose.ui.text.font.FontWeight', 'top.yukonga.miuix.kmp.theme.TextStyles'}, imported)
        for forbidden in ('java.awt', 'Composable', 'File(', 'Http', 'Mpv', 'runBlocking', 'TempDir'):
            self.assertNotIn(forbidden, text)
        # Every imported theme symbol is declared exactly once in the emitted universe.
        hits = self.compiled_declarations()
        for name in ('AppFontSizePreset', 'AppFontWeightPreset', 'AppUiStyle', 'Md3Typography',
                     'resolveMaterialTypography', 'toMiuixTextStyles'):
            declared = [h for h in hits if h[0] == 'main' and h[6] == name]
            self.assertEqual(1, len(declared), name)
        for receiver in ('Typography', 'TextStyles'):
            self.assertEqual(1, len([h for h in hits if h[0] == 'main' and h[6] == 'scaled'
                                     and h[5] == receiver and h[3] == 'public']), receiver)
        # The preset-through-pipeline cases exercise the Windows helper, not the Material typography alone.
        self.assertEqual(3, text.count('resolveDesktopMiuixTextStyles('))
        self.assertIn('AppFontSizePreset.EXTRA_EXTRA_LARGE, AppFontWeightPreset.BOLD', text)
        self.assertIn('AppFontSizePreset.DEFAULT, AppFontWeightPreset.FOLLOW_THEME', text)
        for expected in ('assertEquals(40.sp, styles.title1.fontSize)', 'assertEquals(22.5.sp, styles.title4.fontSize)',
                         'assertEquals(listOf(32.sp, 24.sp, 20.sp, 18.sp),',
                         'List(14) { FontWeight.Bold }'):
            self.assertIn(expected, text)

    def test_the_windows_miuix_text_style_helper_is_declared_once_and_converts_before_scaling(self):
        helper_path = 'desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopMiuixTextStyles.kt'
        raw = (REPO / helper_path).read_bytes()
        self.assertNotIn(b'\r', raw)
        text = raw.decode('utf-8')
        self.assertTrue(text.startswith('package com.bilipai.desktop.appearance\n'))
        self.assertEqual(1, text.count('internal fun resolveDesktopMiuixTextStyles('))
        self.assertNotIn('@Composable', text)
        balanced(self, text)
        hits = self.compiled_declarations()
        imports = re.findall(r'(?m)^import (\S+)$', text)
        self.assertEqual(sorted(imports), imports)
        theme = [name.rsplit('.', 1)[1] for name in imports if name.startswith(tokens.PACKAGE + '.')]
        self.assertEqual(['AppFontSizePreset', 'AppFontWeightPreset', 'AppUiStyle', 'resolveMaterialTypography',
                          'scaled', 'toMiuixTextStyles', 'withFontWeight'], theme)
        self.assertEqual(['top.yukonga.miuix.kmp.theme.TextStyles'], [n for n in imports if not n.startswith(tokens.PACKAGE)])
        for name in theme:
            declared = [h for h in hits if h[0] == 'main' and h[2] == tokens.PACKAGE and h[6] == name
                        and h[3] == 'public']
            self.assertGreaterEqual(len(declared), 1, name)
        # Both the Typography and the TextStyles overload exist for the two transforms the helper chains.
        for name in ('scaled', 'withFontWeight'):
            self.assertEqual(['TextStyles', 'Typography'], sorted(
                h[5] for h in hits if h[0] == 'main' and h[2] == tokens.PACKAGE and h[6] == name and h[3] == 'public'), name)
        # No other declaration of the helper name in the whole Windows source set.
        owners = [p.relative_to(REPO).as_posix() for p in sorted((REPO / 'desktop/src').rglob('*.kt'))
                  if 'fun resolveDesktopMiuixTextStyles(' in p.read_text(encoding='utf-8')]
        self.assertEqual([helper_path], owners)


if __name__ == '__main__':
    unittest.main()
