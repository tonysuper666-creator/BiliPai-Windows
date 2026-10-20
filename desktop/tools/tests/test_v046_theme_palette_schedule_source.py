"""Palette presets, preview widgets and the scheduled dark theme (roadmap slice theme-palette-presets-and-schedule).

Five whole v0.4.6 settings files and the original schedule test are emitted by the existing appearance producer; the
Windows side adds a minute timer, three preference keys, the settings rows, swatch cells and two license notices.
Nothing here starts a JVM, a window, a GPU or the network: the checks are source level (archive pins, emission, inverse
proofs, Kotlin shape, registration and the symbol bookkeeping).
"""
from pathlib import Path
import difflib
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_theme_palette_schedule as palette

KOTLIN = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop'
APPEARANCE = KOTLIN / 'appearance'
JVM_TESTS = REPO / 'desktop/src/test/kotlin'
FEATURE = 'v046-theme-palette-schedule'
TASK = 'extractUpstreamAppearance'
# JVM classes the Linux bot runs: the new ones and the existing ones this slice touches.
JVM_TEST_CLASSES = (
    'com.android.purebilibili.feature.settings.DarkThemeScheduleTest',
    'com.bilipai.desktop.appearance.DesktopScheduledDarkThemeTest',
    'com.bilipai.desktop.settings.DesktopWindowsSettingsCatalogTest',
    'com.bilipai.desktop.appearance.DesktopSeedPalettePreferencesTest',
    'com.bilipai.desktop.appearance.DesktopAppearanceTest',
)
# Top-level names this slice introduces. An empty tuple means the archive emission declares it and no tracked
# source may; otherwise exactly that desktop-owned file declares it.
SLICE_NAMES = {
    'DarkThemeSchedule': (), 'isScheduledDarkTheme': (), 'formatThemeScheduleTime': (),
    'normalizeDarkThemeSchedule': (), 'ThemeStyleSwatch': (), 'ThemeHomePreview': (),
    'DarkThemeSchedulePreference': (), 'ThemeTimeWheelPicker': (), 'closestTimeWheelIndex': (),
    'desktopMinuteOfDay': ('appearance/DesktopScheduledDarkTheme.kt',),
    'desktopScheduleWaitMillis': ('appearance/DesktopScheduledDarkTheme.kt',),
    'rememberDesktopScheduledDarkTheme': ('appearance/DesktopScheduledDarkTheme.kt',),
    'resolveDesktopEffectiveThemeMode': ('appearance/DesktopScheduledDarkTheme.kt',),
    'DESKTOP_SCHEDULE_MAX_WAIT_MILLIS': ('appearance/DesktopScheduledDarkTheme.kt',),
    'DesktopThemePresetPalette': ('appearance/DesktopThemePresetPalette.kt',),
    'DesktopThemePresetList': ('appearance/DesktopThemePresetPalette.kt',),
    'resolveDesktopThemePresetList': ('appearance/DesktopThemePresetPalette.kt',),
    'DesktopPresetPick': ('appearance/DesktopThemePresetPalette.kt',),
    'resolveDesktopPresetPick': ('appearance/DesktopThemePresetPalette.kt',),
    'DARK_THEME_SCHEDULE_FOCUS_ID': ('settings/DesktopWindowsSettingsCatalog.kt',),
}
DECLARATION = re.compile(r'^\s*(?:@\w+\s+)*(?:(?:internal|public|private|inline|const|data|enum|sealed|open|abstract|suspend)\s+)*'
                         r'(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s+)?(?:[\w.<>?, ]+\.)?(\w+)\b')
FLEX_ROW = ('    OpenSourceLibrary(\n        name = "Flex Seed Scheme",\n        license = "BSD-3-Clause",\n'
            '        url = "https://github.com/rydmike/flex_seed_scheme",\n'
            '        description = "扩展调色板的色调表与配色规则（原生移植自 5.0.1）"\n    ),\n')

spec = importlib.util.spec_from_file_location('sync_upstream_palette_schedule', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def module(name, filename):
    loaded = importlib.util.spec_from_file_location(name, TOOLS / filename)
    value = importlib.util.module_from_spec(loaded)
    loaded.loader.exec_module(value)
    return value


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def text(path):
    return Path(path).read_bytes().decode('utf8')


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def git(*args):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), *args],
                          check=True, capture_output=True).stdout


def commit_available():
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', palette.COMMIT + '^{commit}'],
                          capture_output=True).returncode == 0


def blob_oid(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def imports(source):
    return set(re.findall(r'(?m)^import\s+(\S+)\s*$', source))


def declared_names(source):
    found = []
    for line in source.splitlines():
        match = DECLARATION.match(line)
        if match:
            found.append(match.group(1))
    return found


def balanced(case, source, label):
    stack = []
    for token, _start, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, label)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), label)
    case.assertEqual([], stack, label)


class V046ThemePaletteScheduleSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = {p.name: p.read_bytes() for p in (REPO / palette.ARCHIVE).iterdir() if p.is_file()}
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-tps-')
        cls.root = Path(cls.temporary.name)
        appearance_spec = importlib.util.spec_from_file_location(
            'extract_appearance_platform_palette_schedule', TOOLS / 'extract-appearance-platform.py')
        cls.appearance = importlib.util.module_from_spec(appearance_spec)
        appearance_spec.loader.exec_module(cls.appearance)
        cls.appearance.generate(REPO, cls.root / 'appearance', cls.root / 'resources', True, cls.root / 'tests')
        cls.main = tree(cls.root / 'appearance')
        cls.tests = tree(cls.root / 'tests')
        cls.build = text(REPO / 'desktop/build.gradle.kts')
        cls.catalog = json.loads(text(TOOLS / 'compatibility-source-inputs.json'))
        cls.manifest = json.loads(cls.archive['manifest.json'])
        cls.sources, _raws, _manifest = palette.fixed(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    # ---- archive -------------------------------------------------------------------------------

    def test_archive_listing_and_every_leaf_manifest_license_or_extra_leaf_rejects_tampering(self):
        self.assertEqual(sorted(palette.EXPECTED | {'LICENSE', 'manifest.json'}), sorted(self.archive))
        self.assertEqual(6, len(palette.EXPECTED))
        for leaf in sorted(self.archive):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-tps-') as tmp:
                shutil.copytree(REPO / palette.ARCHIVE, Path(tmp) / palette.ARCHIVE)
                target = Path(tmp) / palette.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    palette.fixed(Path(tmp))
        with tempfile.TemporaryDirectory(prefix='bp-tps-') as tmp:
            shutil.copytree(REPO / palette.ARCHIVE, Path(tmp) / palette.ARCHIVE)
            (Path(tmp) / palette.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                palette.fixed(Path(tmp))
        with tempfile.TemporaryDirectory(prefix='bp-tps-') as tmp:
            shutil.copytree(REPO / palette.ARCHIVE, Path(tmp) / palette.ARCHIVE)
            (Path(tmp) / palette.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                palette.fixed(Path(tmp))
        self.assertEqual(sha(self.archive['manifest.json']), palette.MANIFEST_SHA256)

    @unittest.skipUnless(commit_available(), 'upstream commit d6bf8b16 is not a local git object')
    def test_archive_equals_the_upstream_git_blobs(self):
        self.assertEqual(palette.COMMIT, self.manifest['commit'])
        for row in self.manifest['files']:
            with self.subTest(name=row['filename']):
                blob = git('cat-file', 'blob', palette.COMMIT + ':' + row['path'])
                self.assertEqual(blob, self.archive[row['filename']])
                self.assertEqual(row['gitBlob'], blob_oid(blob))
                self.assertEqual(row['gitBlob'], git('rev-parse', palette.COMMIT + ':' + row['path']).strip().decode())
                self.assertNotIn(b'\r', blob)
        self.assertEqual(git('cat-file', 'blob', palette.COMMIT + ':LICENSE'), self.archive['LICENSE'])
        self.assertEqual({row['filename'] for row in self.manifest['files']}, palette.EXPECTED)

    def test_the_two_carried_seed_files_are_not_repeated_because_v042_is_byte_identical(self):
        # SeedPresetColors.kt and ThemePresetPalettePreview.kt come from v042-seed-palette; v0.4.6 did not change them.
        self.assertNotIn('SeedPresetColors.kt', self.archive)
        self.assertNotIn('ThemePresetPalettePreview.kt', self.archive)
        if not commit_available():
            self.skipTest('upstream commit d6bf8b16 is not a local git object')
        theme = 'app/src/main/java/com/android/purebilibili/core/theme/'
        for name in ('SeedPresetColors.kt', 'ThemePresetPalettePreview.kt'):
            self.assertEqual(git('cat-file', 'blob', palette.COMMIT + ':' + theme + name),
                             (REPO / 'desktop/upstream-slices/v042-seed-palette' / name).read_bytes(), name)

    # ---- emission ------------------------------------------------------------------------------

    def test_real_producer_emits_whole_originals_and_the_adapted_ones_peel_back_exactly(self):
        proof = json.loads(self.main[palette.PROOF_NAME])
        rows = {Path(row['path']).name: row for row in proof['files'] + proof['tests']}
        self.assertEqual(sorted(palette.EXPECTED), sorted(rows))
        for name in palette.MAIN_FILES:
            with self.subTest(main=name):
                emitted = self.main[palette.RELATIVE[name]]
                self.assertEqual([palette.RELATIVE[name]], [p for p in self.main if p.endswith('/' + name)])
                self.assertEqual(sha(emitted), rows[name]['sha256'])
                if name in palette.ADAPTATIONS:
                    self.assertEqual('whole-original-with-counted-hunks', rows[name]['mode'])
                    self.assertEqual(palette.ADAPTED_SHA256[name], sha(emitted))
                    self.assertNotEqual(self.archive[name], emitted)
                    self.assertEqual(self.sources[name], palette.restore(emitted.decode('utf8'), rows[name]['hunks']))
                    self.assertEqual(len(palette.ADAPTATIONS[name]), len(rows[name]['hunks']))
                else:
                    self.assertEqual('whole-original', rows[name]['mode'])
                    self.assertEqual(self.archive[name], emitted)
                    self.assertEqual([], rows[name]['hunks'])
                self.assertEqual(sha(self.archive[name]), rows[name]['originalSHA256'])
        for name in palette.TEST_FILES:
            with self.subTest(test=name):
                self.assertEqual(self.archive[name], self.tests[palette.RELATIVE[name]])
                self.assertNotIn(palette.RELATIVE[name], self.main)
        for name in palette.MAIN_FILES:
            self.assertNotIn(palette.RELATIVE[name], self.tests)
        self.assertEqual(palette.COMMIT, proof['upstreamCommit'])
        self.assertEqual(palette.MANIFEST_SHA256, proof['sourceManifestSHA256'])
        self.assertFalse(proof['jvmTestsExecuted'])

    def test_adapted_files_differ_from_the_originals_only_by_the_counted_hunks(self):
        for name, hunks in palette.ADAPTATIONS.items():
            with self.subTest(name=name):
                original = self.sources[name].splitlines(True)
                adapted = self.main[palette.RELATIVE[name]].decode('utf8').splitlines(True)
                diff = list(difflib.unified_diff(original, adapted, n=0))
                removed = [line for line in diff if line.startswith('-') and not line.startswith('---')]
                added = [line for line in diff if line.startswith('+') and not line.startswith('+++')]
                self.assertEqual(len(hunks), len(removed))
                self.assertEqual(len(hunks), len(added))
        home = self.main[palette.RELATIVE['ThemeHomePreview.kt']].decode('utf8')
        for token in ('import com.bilipai.desktop.settings.DesktopSettingsSymbols\n',
                      'rememberMaterialSymbol(DesktopSettingsSymbols.ms_search_24)',
                      'rememberMaterialSymbol(DesktopSettingsSymbols.ms_person_24)'):
            self.assertEqual(1, home.count(token), token)
        for gone in ('R.drawable', 'import com.android.purebilibili.R'):
            self.assertNotIn(gone, home)
        wheel = self.main[palette.RELATIVE['ThemeTimeWheelPicker.kt']].decode('utf8')
        self.assertEqual(1, wheel.count('import com.bilipai.desktop.appearance.DesktopMonotonicClock\n'))
        self.assertEqual(1, wheel.count('val now = DesktopMonotonicClock.elapsedRealtime()'))
        self.assertNotIn('SystemClock', wheel)
        self.assertEqual([], [line for line in wheel.splitlines() if line.startswith('import android.')])
        # whole originals stay whole: the verbatim files carry no Windows token and no Android import
        for name in ('DarkThemeSchedule.kt', 'ThemeStylePalettePreview.kt', 'DarkThemeSchedulePreference.kt'):
            body = self.main[palette.RELATIVE[name]].decode('utf8')
            self.assertNotIn('com.bilipai', body, name)
            self.assertEqual([], [line for line in body.splitlines() if line.startswith('import android.')], name)

    def test_imports_of_the_adapted_files_change_only_by_the_hunks(self):
        removed = {'ThemeHomePreview.kt': {'com.android.purebilibili.R'}, 'ThemeTimeWheelPicker.kt': {'android.os.SystemClock'}}
        added = {'ThemeHomePreview.kt': {'com.bilipai.desktop.settings.DesktopSettingsSymbols'},
                 'ThemeTimeWheelPicker.kt': {'com.bilipai.desktop.appearance.DesktopMonotonicClock'}}
        for name in palette.ADAPTATIONS:
            emitted = self.main[palette.RELATIVE[name]].decode('utf8')
            self.assertEqual((imports(self.sources[name]) - removed[name]) | added[name], imports(emitted), name)

    def test_rerun_is_deterministic_and_a_stale_regular_file_is_replaced(self):
        again = self.root / 'again'
        self.appearance.generate(REPO, again / 'appearance', again / 'resources', True, again / 'tests')
        self.assertEqual(self.main, tree(again / 'appearance'))
        self.assertEqual(self.tests, tree(again / 'tests'))
        self.appearance.generate(REPO, again / 'appearance', again / 'resources', True, again / 'tests')
        self.assertEqual(self.main, tree(again / 'appearance'))
        stale = self.root / 'stale'
        target = stale / 'main' / palette.RELATIVE['ThemeHomePreview.kt']
        target.parent.mkdir(parents=True)
        target.write_bytes(b'SENTINEL-FOREIGN')
        receipt = palette.emit(REPO, stale / 'main', stale / 'tests')
        self.assertEqual(self.main[palette.RELATIVE['ThemeHomePreview.kt']], target.read_bytes())
        self.assertEqual([palette.RELATIVE[n] for n in palette.MAIN_FILES], [r['path'] for r in receipt['files']])
        self.assertEqual([palette.RELATIVE[n] for n in palette.TEST_FILES], [r['path'] for r in receipt['tests']])
        without = self.root / 'without-tests'
        palette.emit(REPO, without / 'main')
        self.assertFalse((without / 'tests').exists())
        self.assertEqual(self.archive['DarkThemeSchedule.kt'],
                         (without / 'main' / palette.RELATIVE['DarkThemeSchedule.kt']).read_bytes())

    def test_a_directory_at_a_target_path_fails_closed(self):
        blocked = self.root / 'blocked'
        (blocked / 'main' / palette.RELATIVE['DarkThemeSchedule.kt']).mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'not a regular file'):
            palette.emit(REPO, blocked / 'main', blocked / 'tests')

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = {p.name: p.read_bytes() for p in (REPO / palette.ARCHIVE).iterdir()}
        nested = REPO / palette.ARCHIVE / 'child'
        try:
            for call in (lambda: palette.emit(REPO, nested),
                         lambda: palette.emit(REPO, self.root / 'ok', nested),
                         lambda: palette.validate_outputs(REPO, REPO),
                         lambda: self.appearance.generate(REPO, nested),
                         lambda: self.appearance.generate(REPO, self.root / 'x', self.root / 'xr', True, nested)):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    call()
            self.assertFalse(nested.exists())
            self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / palette.ARCHIVE).iterdir()})
        finally:
            if nested.exists():
                shutil.rmtree(nested)

    def test_hunk_anchor_drift_fails_closed_and_the_inverse_rejects_a_tampered_body(self):
        name = 'ThemeHomePreview.kt'
        original = self.sources[name]
        before, after = palette.ADAPTATIONS[name][1]
        with self.assertRaises(ValueError):
            palette.adapt(name, original.replace(before, 'something else', 1))   # anchor missing
        with self.assertRaises(ValueError):
            palette.adapt(name, original + '\n' + before)                         # anchor duplicated
        with self.assertRaises(ValueError):
            palette.adapt(name, original + '\n' + after)                          # replacement already present
        adapted, hunks = palette.adapt(name, original)
        self.assertEqual(original, palette.restore(adapted, hunks))
        with self.assertRaises(ValueError):
            palette.restore(adapted.replace(hunks[1]['after'], 'x'), hunks)       # anchor lost
        with self.assertRaises(ValueError):
            palette.restore(adapted + '\n' + hunks[1]['after'], hunks)            # anchor duplicated
        self.assertEqual(('unchanged', []), palette.adapt('DarkThemeSchedule.kt', 'unchanged'))

    def test_package_lines_declarations_and_test_counts_match_the_pins(self):
        for name in palette.EXPECTED:
            source = self.sources[name]
            self.assertEqual(palette.PACKAGES[name], re.search(r'(?m)^package (\S+)', source).group(1), name)
            for emitted in (self.main.get(palette.RELATIVE[name]), self.tests.get(palette.RELATIVE[name])):
                if emitted is not None:
                    package = re.search(r'(?m)^package (\S+)', emitted.decode('utf8')).group(1)
                    self.assertEqual(Path(palette.RELATIVE[name]).parent.as_posix(), package.replace('.', '/'), name)
                    self.assertNotIn(b'\r', emitted)
        for name, tokens in palette.DECLARATIONS.items():
            for token in tokens:
                self.assertEqual(1, self.sources[name].count(token), token)
        self.assertEqual(4, self.sources['DarkThemeScheduleTest.kt'].count('@Test'))
        self.assertEqual(4, self.tests[palette.RELATIVE['DarkThemeScheduleTest.kt']].decode('utf8').count('@Test'))

    def test_emitted_and_hand_written_kotlin_have_balanced_delimiters(self):
        for relative, raw in list(self.main.items()) + list(self.tests.items()):
            if Path(relative).name in palette.EXPECTED:
                balanced(self, raw.decode('utf8'), relative)
        owned = [APPEARANCE / 'DesktopScheduledDarkTheme.kt', APPEARANCE / 'DesktopThemePresetPalette.kt',
                 APPEARANCE / 'DesktopThemePrefs.kt', APPEARANCE / 'DesktopAppearanceTheme.kt',
                 APPEARANCE / 'DesktopAppearanceSettings.kt', KOTLIN / 'settings/DesktopWindowsSettingsCatalog.kt',
                 JVM_TESTS / 'com/bilipai/desktop/appearance/DesktopScheduledDarkThemeTest.kt',
                 JVM_TESTS / 'com/bilipai/desktop/settings/DesktopWindowsSettingsCatalogTest.kt']
        for path in owned:
            self.assertNotIn(b'\r', path.read_bytes(), path.name)
            balanced(self, text(path), path.name)

    # ---- names and the producers that own what the adaptations bind to ---------------------------

    def test_every_new_top_level_name_has_exactly_the_expected_owner(self):
        found = subprocess.run(
            ['git', '-C', str(REPO), 'grep', '-n', '-w', '-E', '|'.join(sorted(SLICE_NAMES)), '--', 'desktop/src', 'design-system',
             'app', 'core-data', 'core-player', 'network-core', 'settings-core', 'desktop/upstream-slices', ':!' + palette.ARCHIVE],
            capture_output=True, text=True, encoding='utf8').stdout.splitlines()
        tracked = {}
        for line in found:
            path, _number, content = line.split(':', 2)
            match = DECLARATION.match(content)
            if match and match.group(1) in SLICE_NAMES:
                tracked.setdefault(match.group(1), set()).add(path)
        for name, allowed in SLICE_NAMES.items():
            owners = {path.split('desktop/src/main/kotlin/com/bilipai/desktop/', 1)[-1] for path in tracked.get(name, set())}
            self.assertLessEqual(owners, set(allowed), name)
            if allowed:
                self.assertEqual(1, declared_names(text(KOTLIN / allowed[0])).count(name), name)
        emitted = []
        for relative, raw in self.main.items():
            if relative.endswith('.kt'):
                emitted.extend(declared_names(raw.decode('utf8')))
        for name, allowed in SLICE_NAMES.items():
            if not allowed:
                self.assertEqual(1, emitted.count(name), name)

    def test_the_symbols_the_adaptations_bind_to_exist_in_their_own_producers(self):
        search = module('settings_search_palette_schedule', 'extract-upstream-settings-search.py')
        names = search.symbol_names(REPO)
        self.assertIn('ms_search_24', names)
        self.assertIn('ms_person_24', names)
        dock = module('linked_dock_palette_schedule', 'extract-upstream-linked-dock.py')
        with tempfile.TemporaryDirectory(prefix='bp-tps-') as tmp:
            search.generate(REPO, Path(tmp) / 'search')
            policy = text(Path(tmp) / 'search/com/android/purebilibili/feature/settings/SettingsSemanticIconPolicy.kt')
            self.assertRegex(policy, r'internal fun rememberMaterialSymbol\(\s*materialSymbolResource: String,\s*\): ImageVector')
            vectors = text(Path(tmp) / 'search/com/bilipai/desktop/settings/DesktopSettingsVectors.kt')
            for name in ('ms_search_24', 'ms_person_24'):
                self.assertEqual(1, vectors.count('const val %s="%s"' % (name, name)))
            self.assertIn('internal object DesktopSettingsSymbols {', vectors)
            dock.generate(REPO, Path(tmp) / 'dock')
            controls = text(Path(tmp) / 'dock/com/android/purebilibili/feature/home/components/DesktopOriginalLinkedDockControls.kt')
            for token in ('enum class BottomNavItem(', 'internal fun resolveMaterialBottomBarIcon(',
                          'internal fun resolveBottomNavItemLabel('):
                self.assertEqual(1, controls.count(token), token)
            for item in ('HOME', 'DYNAMIC', 'HISTORY', 'PROFILE'):
                self.assertRegex(controls, r'(?m)^    %s\(' % item)
        clock = text(APPEARANCE / 'DesktopMonotonicClock.kt')
        self.assertIn('object DesktopMonotonicClock', clock)
        self.assertIn('fun elapsedRealtime(): Long', clock)

    # ---- registration ----------------------------------------------------------------------------

    def test_gradle_task_catalog_and_inventory_track_the_archive(self):
        block = task_block(self.build, TASK)
        self.assertEqual(1, block.count('inputs.file("tools/v046_theme_palette_schedule.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-theme-palette-schedule")'))
        self.assertEqual(1, self.build.count('upstream-slices/v046-theme-palette-schedule"'))
        self.assertIn('"tools/extract-appearance-platform.py"', block)
        source = text(TOOLS / 'extract-appearance-platform.py')
        validate = "validate_palette_schedule_outputs(repo, output, resource_output or output.parent/'resources', test_output)"
        self.assertEqual(1, source.count(validate))
        self.assertEqual(1, source.count('emit_palette_schedule(repo, output, test_output)'))
        self.assertLess(source.index('emit_theme_tokens(repo, output, test_output)'),
                        source.index('emit_palette_schedule(repo, output, test_output)'))
        self.assertIn(palette.ARCHIVE, self.catalog['buildInputRoots'])
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(palette.ARCHIVE + '/')]
        self.assertEqual(sorted(palette.EXPECTED), sorted(Path(row['localPath']).name for row in rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertEqual([FEATURE], row['features'])
            self.assertEqual(palette.COMMIT, row['upstreamCommit'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(palette.UPSTREAM_PATHS[Path(row['localPath']).name], row['path'])
            self.assertEqual(sha(self.archive[Path(row['localPath']).name]), row['sha256'])
            self.assertTrue(row['usedScope'].isascii())
        archive = [row for row in self.catalog['archives'] if row['directory'] == palette.ARCHIVE]
        self.assertEqual(1, len(archive))
        self.assertEqual(sorted(self.archive), sorted(Path(f['path']).name for f in archive[0]['files']))
        self.assertIn(rows[0], sync.adapted_compatibility_inputs(REPO))

    def test_no_vendored_direct_file_is_replaced_so_no_exclude_was_added(self):
        listed = {row['path'] for row in json.loads(text(REPO / 'desktop/upstream-sources.json'))['sources']}
        prepare = self.build.split('val prepareUpstreamSources', 1)[1].split('val extractUpstreamApi', 1)[0]
        for path in palette.UPSTREAM_PATHS.values():
            self.assertNotIn(path, listed, path)
            self.assertNotIn(path.rsplit('/', 1)[1], prepare, path)

    # ---- desktop-owned Kotlin --------------------------------------------------------------------

    def test_theme_settings_carry_the_schedule_under_the_three_original_keys(self):
        prefs = text(APPEARANCE / 'DesktopThemePrefs.kt')
        upstream_store = text(REPO / 'desktop/upstream-slices/v042-seed-palette/SettingsManager.kt')
        self.assertEqual(1, prefs.count('val darkThemeSchedule: DarkThemeSchedule = DarkThemeSchedule(),'))
        for key, factory in (('dark_theme_schedule_enabled', 'booleanPreferencesKey'),
                             ('dark_theme_schedule_start_minute', 'intPreferencesKey'),
                             ('dark_theme_schedule_end_minute', 'intPreferencesKey')):
            self.assertEqual(2, prefs.count('"%s"' % key), key)
            self.assertIn('%s("%s")' % (factory, key), upstream_store, key)
        self.assertEqual(1, prefs.count('normalizeDarkThemeSchedule(DarkThemeSchedule('))
        self.assertEqual(1, prefs.count('require(schedule.startMinute in 0..1439 && schedule.endMinute in 0..1439)'))
        self.assertEqual(1, prefs.count('require(schedule.startMinute != schedule.endMinute)'))
        self.assertEqual(1, len(re.findall(r'suspend fun setDarkThemeSchedule\(schedule: DarkThemeSchedule\) \{', prefs)))
        # the liquid glass default and the display scale are not touched by this slice
        self.assertEqual(1, prefs.count('"windows_liquid_glass_default_v1" to JsonPrimitive(true)'))
        self.assertIn('val liquidGlassEnabled: Boolean = false,', prefs)
        self.assertIn('edits["android_native_liquid_glass_enabled"] = JsonPrimitive(true)', prefs)
        for name in ('DesktopWindowsDisplayScale.kt', 'DesktopWindowsDisplayScaleInput.kt',
                     'DesktopWindowsDisplayScaleScope.kt', 'DesktopWindowsDisplayScaleSettings.kt'):
            self.assertNotIn('DarkThemeSchedule', text(APPEARANCE / name), name)

    def test_the_theme_composable_feeds_the_schedule_to_the_palette_and_the_miuix_controller(self):
        flat = re.sub(r'\s+', '', text(APPEARANCE / 'DesktopAppearanceTheme.kt'))
        self.assertEqual(1, flat.count('valscheduledDark=rememberDesktopScheduledDarkTheme(settings.darkThemeSchedule)'))
        self.assertEqual(1, flat.count('remember(settings,systemDark,scheduledDark){buildDesktopAppearancePalette(settings,systemDark,scheduledDark)}'))
        self.assertEqual(1, flat.count('scheduledDarkTheme=scheduledDark'))
        self.assertEqual(1, flat.count('valmiuixThemeMode=resolveDesktopEffectiveThemeMode(settings.themeMode,scheduledDark)'))
        self.assertEqual(1, flat.count('ThemeController(resolveMiuixColorSchemeMode(miuixThemeMode,false),'))
        self.assertEqual(1, flat.count('scheduledDark:Boolean?=null'))
        timer = text(APPEARANCE / 'DesktopScheduledDarkTheme.kt')
        self.assertEqual(1, timer.count('LaunchedEffect(schedule.enabled) {'))
        self.assertIn('if (!schedule.enabled) return@LaunchedEffect', timer)
        self.assertEqual(1, timer.count('import androidx.compose.runtime.mutableIntStateOf'))
        self.assertEqual(1, timer.count('var minute by remember(schedule.enabled) { mutableIntStateOf(desktopMinuteOfDay()) }'))
        self.assertIn('return if (schedule.enabled) isScheduledDarkTheme(schedule, minute) else null', timer)
        self.assertEqual([], [line for line in timer.splitlines() if line.startswith('import android.')])
        for forbidden in ('Alarm', 'Thread', 'Timer(', 'java.net'):
            self.assertNotIn(forbidden, timer)

    def test_the_settings_page_mounts_the_schedule_swatches_palette_and_home_preview(self):
        page = text(APPEARANCE / 'DesktopAppearanceSettings.kt')
        self.assertEqual(1, page.count('DarkThemeSchedulePreference(settings.darkThemeSchedule,'))
        self.assertEqual(1, page.count('Modifier.desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, DARK_THEME_SCHEDULE_FOCUS_ID)'))
        self.assertEqual(1, page.count('import com.android.purebilibili.feature.settings.screen.DarkThemeSchedulePreference'))
        self.assertEqual(1, page.count('import com.bilipai.desktop.settings.DARK_THEME_SCHEDULE_FOCUS_ID'))
        self.assertEqual(1, page.count('DesktopThemePresetPalette(settings.seedPaletteStyle,'))
        self.assertEqual(1, page.count('ThemeHomePreview(Modifier.widthIn(max = 440.dp))'))
        self.assertEqual(2, page.count('optionLeading = {'))
        self.assertEqual(1, page.count('rememberSeedStyleSwatches(seedColor, previewDark, settings.colorSpec)'))
        self.assertEqual(1, page.count('rememberThemeStylePaletteSwatches(seedColor, previewDark, colorStyles,'))
        self.assertNotIn('ThemeColors.indices', page)
        self.assertEqual(1, page.count('DesktopPresetPick.THEME_COLOR_INDEX -> update { prefs.setThemeColorIndex(index) }'))
        self.assertEqual(1, page.count('prefs.setCustomColor(formatMd3CustomColorHex(color))'))
        # the pins test_v040_liquid_controls_source keeps on this file stay true
        self.assertEqual(2, page.count('ThemeCustomColorPickerDialog('))
        self.assertEqual(1, page.count('prefs.setCustomColor(custom)'))
        self.assertEqual(1, page.count('prefs.setCustomColor(selected)'))
        self.assertEqual(1, page.count('DesktopWindowsDisplayScaleSettings()'))
        self.assertIn('原版液态玻璃', page)
        self.assertIn('prefs.setLiquidGlassEnabled(it)', page)
        palette_source = text(APPEARANCE / 'DesktopThemePresetPalette.kt')
        for token in ('rememberThemePresetPaletteSwatches(', 'ThemeStyleSwatch(swatch, size = 48.dp)', 'SeedPresetColors',
                      'SeedPresetColorNames', '@OptIn(ExperimentalLayoutApi::class)', 'Role.RadioButton'):
            self.assertIn(token, palette_source, token)
        # the trailing lambda of the call in the page binds to the last parameter, so onPick must stay last
        self.assertEqual(1, palette_source.count('modifier: Modifier = Modifier,\n    onPick: (Int, Color) -> Unit,'))

    def test_the_search_entry_and_its_focus_anchor_use_the_original_ids(self):
        catalog = text(KOTLIN / 'settings/DesktopWindowsSettingsCatalog.kt')
        self.assertEqual(1, catalog.count('internal const val DARK_THEME_SCHEDULE_FOCUS_ID = "appearance.dark_theme_schedule"'))
        entry = [line for line in catalog.splitlines() if '"定时深色模式"' in line]
        self.assertEqual(1, len(entry))
        self.assertTrue(entry[0].rstrip().endswith('DARK_THEME_SCHEDULE_FOCUS_ID),'))
        self.assertIn('SettingsSearchTarget.APPEARANCE', entry[0])
        fields = re.search(r'SettingsSearchTarget\.APPEARANCE, "([^"]*)", "([^"]*)", "([^"]*)", listOf\(([^)]*)\), DARK_THEME', entry[0])
        self.assertIsNotNone(fields)
        words = re.findall(r'"([^"]+)"', fields.group(4))
        self.assertEqual(('定时深色模式', '自选开始和结束时间，支持跨午夜自动切换', '外观'), fields.group(1, 2, 3))
        self.assertEqual(['自动深色', '夜间模式', '深色时间', '定时夜间', '深色定时'], words)
        if commit_available():
            upstream = git('cat-file', 'blob', palette.COMMIT +
                           ':app/src/main/java/com/android/purebilibili/feature/settings/SettingsSearchPolicy.kt').decode('utf8')
            self.assertIn('settingId = "appearance.dark_theme_schedule"', upstream)
            self.assertIn('focusId = "appearance.dark_theme_schedule",', upstream)
            aliases = re.search(r'settingId = "appearance.dark_theme_schedule".*?aliases = listOf\(([^)]*)\)', upstream, re.S)
            self.assertEqual(words, re.findall(r'"([^"]+)"', aliases.group(1)))
            self.assertIn('title = "%s",' % fields.group(1), upstream)
            self.assertIn('subtitle = "%s",' % fields.group(2), upstream)
        jvm = text(JVM_TESTS / 'com/bilipai/desktop/settings/DesktopWindowsSettingsCatalogTest.kt')
        self.assertIn('SettingsSearchTarget.APPEARANCE to DARK_THEME_SCHEDULE_FOCUS_ID,', jvm)
        self.assertIn('assertEquals(9, focusEntries.size)', jvm)
        # none of the queries the existing catalog test pins can reach the new entry (it would break their single() checks)
        searchable = ['定时深色模式', '自选开始和结束时间，支持跨午夜自动切换', '自动深色', '夜间模式', '深色时间', '定时夜间', '深色定时']
        for query in set(re.findall(r'"([^"\n]+)"', jvm)):
            self.assertFalse(any(query.lower() in item.lower() for item in searchable), query)

    def test_the_two_seed_palette_licenses_ship_as_notices_and_the_flex_row_is_listed(self):
        notices = REPO / 'desktop/src/main/resources/licenses/upstream-carried-assets'
        document = json.loads(text(notices / 'provenance.json'))
        rows = {row['resource']: row for row in document['files']}
        for resource, blob in (('flex_seed_scheme.LICENSE', '30d5ad463f911a0c86d3d1d388975bc77587c0c5'),
                               ('material_color_utilities.LICENSE', '3879bcb2002e2b2e38a953e1149c14cef55ca555')):
            data = (notices / resource).read_bytes()
            self.assertEqual(blob, rows[resource]['gitBlob'])
            self.assertEqual(blob, blob_oid(data))
            self.assertEqual('app/src/main/assets/licenses/' + resource, rows[resource]['upstreamPath'])
            self.assertEqual(data, (REPO / 'desktop/upstream-slices/v042-seed-palette' / resource).read_bytes())
            self.assertNotIn(b'\r', data)
        covers = {row['name']: row for row in document['covers']}
        for name in ('SeedColorScheme.kt', 'SeedPaletteStyle.kt', 'SeedTones.kt', 'SeedToneResolver.kt'):
            self.assertTrue((REPO / covers[name]['archivePath']).is_file(), name)
            header = text(REPO / covers[name]['archivePath'])[:600]
            for notice in covers[name]['notices']:
                self.assertIn('assets/licenses/' + notice, header, name)
        self.assertEqual(['flex_seed_scheme.LICENSE', 'material_color_utilities.LICENSE'], covers['SeedColorScheme.kt']['notices'])
        extractor = text(TOOLS / 'extract-upstream-static-settings-pages.py')
        self.assertEqual(1, extractor.count('FLEX_SEED_SCHEME_ROW + '))
        namespace = {}
        exec(compile(re.search(r'(?s)FLEX_SEED_SCHEME_ROW = \(.*?\)\n', extractor).group(0), 'row', 'exec'), namespace)
        self.assertEqual(FLEX_ROW, namespace['FLEX_SEED_SCHEME_ROW'])
        if commit_available():
            upstream = git('cat-file', 'blob', palette.COMMIT +
                           ':app/src/main/java/com/android/purebilibili/feature/settings/screen/OpenSourceLicensesScreen.kt').decode('utf8')
            self.assertEqual(1, upstream.count(FLEX_ROW))
            self.assertLess(upstream.index(FLEX_ROW), upstream.index('        name = "Kotlin",'))
        static = module('static_settings_palette_schedule', 'extract-upstream-static-settings-pages.py')
        _before, after, operations = static.adapt(REPO, static.SOURCES[1])
        self.assertEqual(1, after.count(FLEX_ROW))
        self.assertLess(after.index(FLEX_ROW), after.index('        name = "Kotlin",'))
        self.assertEqual(1, len([op for op in operations if op['after'].startswith(FLEX_ROW)]))

    def test_the_three_seeds_are_retired_in_the_gate_file_and_its_test(self):
        expected = json.loads(text(TOOLS / 'symbol-closure-expected-gaps.json'))
        retired = {row['symbol']: row for row in expected['retiredSeeds']}
        listed = ' '.join(gap['symbol'] for group in expected['groups'] for gap in group['gaps'])
        gate_test = text(TOOLS / 'tests/test_symbol_closure.py')
        for name in ('DarkThemeSchedule', 'isScheduledDarkTheme', 'normalizeDarkThemeSchedule'):
            self.assertEqual('theme-palette-presets-and-schedule', retired[name]['closedBy'], name)
            self.assertEqual('com.android.purebilibili.feature.settings', retired[name]['package'])
            self.assertEqual('declaration', retired[name]['kind'])
            self.assertIn('v046-theme-palette-schedule', retired[name]['retiredBy'])
            self.assertNotIn(name, listed)
            self.assertIn("('com.android.purebilibili.feature.settings', '%s')," % name, gate_test)
        self.assertEqual([], [g for g in expected['groups'] if g['closedBy'] == 'theme-palette-presets-and-schedule'])

    def test_jvm_regression_classes_exist_with_their_exact_case_counts(self):
        windows = text(JVM_TESTS / 'com/bilipai/desktop/appearance/DesktopScheduledDarkThemeTest.kt')
        self.assertEqual(12, windows.count('@Test fun'))
        self.assertIn('class DesktopScheduledDarkThemeTest {', windows)
        for forbidden in ('java.awt', 'androidx.compose.ui.test', 'System.getenv', 'java.net', 'Thread.sleep'):
            self.assertNotIn(forbidden, windows)
        self.assertIn('class DarkThemeScheduleTest {', self.sources['DarkThemeScheduleTest.kt'])
        for qualified in JVM_TEST_CLASSES:
            name = qualified.rsplit('.', 1)[1]
            candidates = list(JVM_TESTS.rglob(name + '.kt')) + [REPO / palette.ARCHIVE / (name + '.kt')]
            existing = [p for p in candidates if p.is_file()]
            self.assertTrue(existing, qualified)
            self.assertIn('class ' + name, text(existing[0]), qualified)


if __name__ == '__main__':
    unittest.main()
