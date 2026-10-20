"""v0.4.6 wallpaper foundation: four verbatim files and two original suites from the home refresh wrapper, the Windows
crop adapter, header probe and adjust sheet that bind them. Source checks only: nothing here compiles or runs Kotlin."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

REPO =Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_wallpaper_foundation as wf

spec = importlib.util.spec_from_file_location('sync_upstream_wallpaper_foundation', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

FEATURE = 'v046-wallpaper-foundation'
PAGE_PRODUCER = 'tools/extract-upstream-home-page.py'
WRAPPER_PRODUCER = 'tools/extract-upstream-home-core-refresh.py'
WALLPAPER = 'com.android.purebilibili.core.ui.wallpaper'
PROBE = REPO / 'desktop/src/main/kotlin/com/android/purebilibili/core/ui/wallpaper/DesktopWallpaperImageProbe.kt'
CROP = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWallpaperCrop.kt'
SHEET = REPO / 'desktop/src/main/kotlin/com/android/purebilibili/feature/profile/DesktopHomeWallpaperAdjustmentSheet.kt'
PROBE_TEST = REPO / 'desktop/src/test/kotlin/com/android/purebilibili/core/ui/wallpaper/DesktopWallpaperImageProbeTest.kt'
CROP_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopWallpaperCropTest.kt'
JVM_TESTS = {PROBE_TEST: 6, CROP_TEST: 8}
WINDOWS_FILES = (PROBE, CROP, SHEET, PROBE_TEST, CROP_TEST)
# (visibility, kind, name) of every top-level declaration of each emitted source.
TOP_LEVEL = {
    'HomeWallpaperPresentation.kt': {('internal', 'val', 'LocalHomeWallpaperSelection')},
    'HomeWallpaperSelection.kt': {('public', 'class', 'HomeWallpaperSelection')},
    'WallpaperPlaybackCoordinator.kt': {
        ('internal', 'class', 'WallpaperPlaybackCoordinator'),
        ('internal', 'val', 'LocalWallpaperPlaybackCoordinator'),
    },
    'WallpaperTransformPolicy.kt': {
        ('public', 'class', 'WallpaperTransform'),
        ('public', 'fun', 'sanitizeWallpaperTransform'),
        ('public', 'class', 'WallpaperCropGeometry'),
        ('public', 'class', 'WallpaperSourceRegion'),
        ('public', 'fun', 'resolveWallpaperVisibleSourceRegion'),
        ('public', 'fun', 'resolveWallpaperCropGeometry'),
        ('public', 'fun', 'applyWallpaperGesture'),
    },
}
# The only imports each verbatim file needs: all of them are in the Windows compile universe without a shim.
IMPORTS = {
    'HomeWallpaperPresentation.kt': {
        'androidx.compose.runtime.compositionLocalOf', 'com.android.purebilibili.core.store.home.HomeWallpaperSelection'},
    'HomeWallpaperSelection.kt': {'com.android.purebilibili.core.ui.wallpaper.WallpaperTransform'},
    'WallpaperPlaybackCoordinator.kt': {'androidx.compose.runtime.staticCompositionLocalOf'},
    'WallpaperTransformPolicy.kt': {'kotlin.math.max'},
}
WINDOWS_NAMES = {
    PROBE: {'DesktopWallpaperImageFormat', 'DesktopWallpaperImageHeader', 'DESKTOP_WALLPAPER_HEADER_LIMIT_BYTES',
            'parseDesktopWallpaperImageHeader', 'desktopWallpaperHeaderIsStatic', 'probeDesktopWallpaperImageHeader',
            'probeDesktopWallpaperUri', 'desktopWallpaperNeedsHeaderProbe', 'desktopWallpaperIsStatic',
            'rememberIsStaticWallpaper'},
    CROP: {'DesktopWallpaperDevice', 'DESKTOP_WALLPAPER_TABLET_MIN_WIDTH_DP', 'desktopWallpaperDeviceForWidth',
           'DesktopWallpaperPreviewSpace', 'desktopWallpaperPreviewSpace', 'desktopWallpaperTransformFor',
           'desktopWallpaperSelectionWith', 'DESKTOP_WALLPAPER_WHEEL_ZOOM_BASE', 'desktopWallpaperWheelZoomFactor',
           'desktopWallpaperDrag', 'desktopWallpaperWheel', 'DesktopHomeWallpaperKeys',
           'desktopHomeWallpaperSelectionEntries', 'desktopHomeWallpaperSelectionFromEntries',
           'DESKTOP_WALLPAPER_MAX_RENDERED_PX', 'desktopWallpaperCrop', 'DesktopWallpaperCropImage'},
    SHEET: {'HomeWallpaperAdjustmentSheet', 'WallpaperSourceState', 'WallpaperSheetMessage', 'WallpaperZoomControls'},
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / wf.ARCHIVE).iterdir() if p.is_file()}


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def run_cli(tool, *arguments, seed=0):
    environment = dict(os.environ, PYTHONHASHSEED=str(seed), PYTHONIOENCODING='utf-8',
                       PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1')
    done = subprocess.run(
        [sys.executable, '-X', 'utf8', '-B', str(REPO / 'desktop' / tool), '--repo', str(REPO), *arguments],
        cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=False)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.decode('utf-8', 'replace')[-4000:])
    return done.stdout.decode('utf-8')


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, {(item.visibility, item.kind, item.name) for item in info.decls}


def imports(text):
    return set(re.findall(r'(?m)^import ([\w.]+)\s*$', text))


def balanced(test, text):
    stack, pairs = [], {')': '(', ']': '[', '}': '{'}
    for token in sync.kotlin_tokens(text):
        word = token[0]
        if word in ('(', '[', '{'):
            stack.append(word)
        elif word in pairs:
            test.assertTrue(stack and stack.pop() == pairs[word], 'unbalanced ' + word)
    test.assertEqual([], stack)


def parenthesized(tokens, open_at):
    """The depth-1 comma separated token lists of the balanced parentheses that start at open_at."""
    depth, parts, current = 0, [], []
    for index in range(open_at, len(tokens)):
        text = tokens[index][0]
        if text in ('(', '[', '{'):
            depth += 1
            if depth == 1:
                continue
        elif text in (')', ']', '}'):
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


def parameters(source, name):
    """[(label, has default)] of the one function called name in source."""
    tokens = sync.kotlin_tokens(source)
    found = [i for i, token in enumerate(tokens[:-2])
             if token[0] == 'fun' and tokens[i + 1][0] == name and tokens[i + 2][0] == '(']
    assert len(found) == 1, name
    parts, _end = parenthesized(tokens, found[0] + 2)
    rows = []
    for part in parts:
        texts = [token[0] for token in part]
        label = next(text for text in texts if text not in {'vararg', 'noinline', 'crossinline'})
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
    """Every call of one of names: positional count and named arguments; declarations are skipped."""
    tokens = sync.kotlin_tokens(text)
    found = []
    for index, token in enumerate(tokens):
        if token[0] not in names or index + 1 >= len(tokens):
            continue
        if index and tokens[index - 1][0] in {'fun', '::', 'class'}:
            continue
        if tokens[index + 1][0] != '(':
            continue
        parts, end = parenthesized(tokens, index + 1)
        positional, named = 0, []
        for part in parts:
            if len(part) >= 3 and part[1][0] == '=' and part[2][0] != '=' and re.fullmatch(r'[^\W\d]\w*', part[0][0]):
                named.append(part[0][0])
            else:
                positional += 1
        trailing = end + 1 < len(tokens) and tokens[end + 1][0] == '{'
        found.append(dict(name=token[0], positional=positional, named=named, trailing=trailing))
    return found


def f32(value):
    """value rounded to a JVM Float; every +, -, * and / of two Floats is one such rounding of the exact result."""
    return struct.unpack('<f', struct.pack('<f', value))[0]


def f32_bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def crop_geometry_f32(viewport_width, viewport_height, source_width, source_height, scale=1.0, offset_x=0.0, offset_y=0.0):
    """(width, height, left, top) of resolveWallpaperCropGeometry for an already sane transform, in Float arithmetic.

    Mirrors the carried WallpaperTransformPolicy.kt operation by operation, including the sign of a zero: a negative
    overflow times +0 is -0, which a boxed Float equality (kotlin.test.assertEquals without a tolerance) tells apart from 0.
    """
    crop_scale = f32(max(f32(viewport_width / source_width), f32(viewport_height / source_height)) * scale)
    width = max(viewport_width, f32(source_width * crop_scale))
    height = max(viewport_height, f32(source_height * crop_scale))
    left = f32(f32(f32(-f32(width - viewport_width)) * f32(offset_x + 1.0)) / 2.0)
    top = f32(f32(f32(-f32(height - viewport_height)) * f32(offset_y + 1.0)) / 2.0)
    return width, height, left, top


def visible_region_f32(viewport_width, viewport_height, source_width, source_height, scale=1.0, offset_x=0.0, offset_y=0.0):
    """(left, top, right, bottom) of resolveWallpaperVisibleSourceRegion in Float arithmetic."""
    width, _height, left, top = crop_geometry_f32(
        viewport_width, viewport_height, source_width, source_height, scale, offset_x, offset_y)
    factor = f32(width / source_width)
    clamp = lambda value, high: min(max(value, 0.0), high)
    return (clamp(f32(f32(-left) / factor), source_width), clamp(f32(f32(-top) / factor), source_height),
            clamp(f32(f32(viewport_width - left) / factor), source_width),
            clamp(f32(f32(viewport_height - top) / factor), source_height))


def call_problem(call, params):
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


class V046WallpaperFoundationSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-wf-')
        cls.root = Path(cls.temporary.name)
        run_cli(PAGE_PRODUCER, '--output', str(cls.root / 'page-main'))
        cls.page_main = tree(cls.root / 'page-main')
        run_cli(WRAPPER_PRODUCER, '--output', str(cls.root / 'main'), '--test-output', str(cls.root / 'tests'))
        cls.main = tree(cls.root / 'main')
        cls.tests = tree(cls.root / 'tests')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.sources, cls.raws, cls.manifest = wf.fixed(REPO)
        cls.windows = {path: path.read_text(encoding='utf-8') for path in WINDOWS_FILES}

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        original = archive_files()
        self.assertEqual(self.archive, original)
        self.assertEqual(sorted(wf.EXPECTED | {'LICENSE', 'manifest.json'}), sorted(original))
        self.assertEqual(7, len(wf.EXPECTED))
        for leaf in sorted(original):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-wf-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / wf.ARCHIVE, repo / wf.ARCHIVE)
                target = repo / wf.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    wf.fixed(repo)
        for tamper in ('extra', 'license'):
            with self.subTest(tamper=tamper), tempfile.TemporaryDirectory(prefix='bp-wf-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / wf.ARCHIVE, repo / wf.ARCHIVE)
                if tamper == 'extra':
                    (repo / wf.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
                else:
                    (repo / wf.ARCHIVE / 'LICENSE').unlink()
                with self.assertRaises(ValueError):
                    wf.fixed(repo)
        self.assertEqual(original, archive_files())

    def test_archive_matches_the_upstream_git_blobs_and_the_manifest_rows(self):
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual(wf.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        self.assertEqual(sorted(wf.EXPECTED), sorted(row['filename'] for row in manifest['files']))
        for row in manifest['files']:
            with self.subTest(name=row['filename']):
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', wf.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, self.archive[row['filename']])
                self.assertEqual(row['path'], wf.UPSTREAM_PATHS[row['filename']])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertNotIn(b'\r', blob)

    def test_the_wrapper_emits_the_four_verbatim_files_and_a_receipt_into_the_main_output(self):
        for name in wf.MAIN_FILES:
            with self.subTest(name=name):
                self.assertIn(wf.RELATIVE[name], self.main)
                self.assertEqual(self.archive[name], self.main[wf.RELATIVE[name]])
                self.assertNotIn(wf.RELATIVE[name], self.tests)
        proof = json.loads(self.main[wf.PROOF_NAME].decode('utf-8'))
        self.assertEqual({name: sha(self.archive[name]) for name in wf.MAIN_FILES}, proof['verbatimFiles'])
        self.assertEqual(list(wf.REFERENCE_FILES), list(proof['referenceOnlyFiles']))
        self.assertEqual({}, proof['adaptedFiles'])
        self.assertFalse(proof['vendoredDirectCopiesExcluded'])
        # the reference sheet is carried, never emitted, and nothing of it reaches either output
        sheet = 'HomeWallpaperAdjustmentSheet.kt'
        for tree_ in (self.main, self.tests):
            self.assertEqual([], [path for path in tree_ if path.endswith('/' + sheet) or path == sheet])

    def test_the_wrapper_emits_the_two_original_suites_and_a_receipt_into_the_test_root_only(self):
        for name in wf.TEST_FILES:
            with self.subTest(name=name):
                self.assertIn(wf.RELATIVE[name], self.tests)
                self.assertEqual(self.archive[name], self.tests[wf.RELATIVE[name]])
                self.assertNotIn(wf.RELATIVE[name], self.main)
                self.assertEqual(wf.TEST_COUNTS[name], self.archive[name].decode('utf-8').count('@Test'))
                self.assertNotIn(b'org.junit', self.archive[name])
        proof = json.loads(self.tests[wf.TESTS_PROOF_NAME].decode('utf-8'))
        self.assertEqual(wf.TEST_COUNTS, proof['testCounts'])
        self.assertEqual({name: sha(self.archive[name]) for name in wf.TEST_FILES}, proof['verbatimFiles'])
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertNotIn(wf.TESTS_PROOF_NAME, self.main)
        for path in self.tests:
            self.assertFalse(path.endswith(tuple('/' + name for name in wf.MAIN_FILES)), path)

    def test_the_page_producer_alone_does_not_emit_them_and_the_wrapper_only_adds_them(self):
        added = {wf.RELATIVE[name] for name in wf.MAIN_FILES} | {wf.PROOF_NAME}
        for path in added:
            self.assertNotIn(path, self.page_main, path)
        self.assertTrue(added <= set(self.main))
        # of everything the wrapper adds to the page producer's output, exactly these are wallpaper foundation files
        # (the rest is the refresh extension's own policy and receipt)
        self.assertEqual(added, {path for path in set(self.main) - set(self.page_main) if 'allpaper' in path})

    def test_emit_is_deterministic_and_a_rerun_over_the_same_roots_matches(self):
        with tempfile.TemporaryDirectory(prefix='bp-wf-') as tmp:
            out, tests = Path(tmp) / 'main', Path(tmp) / 'tests'
            shutil.copytree(self.root / 'main', out)
            shutil.copytree(self.root / 'tests', tests)
            run_cli(WRAPPER_PRODUCER, '--output', str(out), '--test-output', str(tests), seed=5)
            self.assertEqual(self.main, tree(out))
            self.assertEqual(self.tests, tree(tests))
        with tempfile.TemporaryDirectory(prefix='bp-wf-') as tmp:
            first = wf.emit(REPO, Path(tmp) / 'a'), wf.emit_tests(REPO, Path(tmp) / 't')
            second = wf.emit(REPO, Path(tmp) / 'a'), wf.emit_tests(REPO, Path(tmp) / 't')
            self.assertEqual(first, second)

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        for target in (REPO / wf.ARCHIVE, REPO / wf.ARCHIVE / 'child', REPO / 'desktop/upstream-slices'):
            with self.subTest(target=str(target)):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    wf.validate_outputs(REPO, target)
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    wf.emit(REPO, target)
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    wf.emit_tests(REPO, target)
        wf.validate_outputs(REPO, None, self.root / 'elsewhere')
        self.assertEqual(before, archive_files())

    def test_a_stale_regular_file_is_replaced_and_a_directory_target_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix='bp-wf-') as tmp:
            out = Path(tmp) / 'main'
            stale = out / wf.RELATIVE['WallpaperTransformPolicy.kt']
            stale.parent.mkdir(parents=True)
            stale.write_bytes(b'stale')
            wf.emit(REPO, out)
            self.assertEqual(self.archive['WallpaperTransformPolicy.kt'], stale.read_bytes())
        with tempfile.TemporaryDirectory(prefix='bp-wf-') as tmp:
            out = Path(tmp) / 'main'
            (out / wf.RELATIVE['HomeWallpaperSelection.kt']).mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                wf.emit(REPO, out)
            # nothing was written before the refusal
            self.assertEqual([], [p for p in out.rglob('*') if p.is_file()])

    def test_gradle_wires_the_archive_and_generator_into_the_home_task_without_an_exclude(self):
        block = task_block(self.build, 'extractOriginalHomePage')
        for literal in ('inputs.file("tools/v046_wallpaper_foundation.py")',
                        'inputs.dir("upstream-slices/v046-wallpaper-foundation")'):
            self.assertEqual(1, self.build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertIn('"tools/extract-upstream-home-core-refresh.py"', block)
        self.assertEqual(1, self.build.count('v046-wallpaper-foundation'))
        self.assertEqual(1, self.build.count('v046_wallpaper_foundation'))
        self.assertEqual([], [row for row in self.build.splitlines() if 'srcDir' in row and 'wallpaper-foundation' in row])
        # all six names are new to the tree: no vendored direct copy exists, so nothing is excluded
        for name in set(wf.MAIN_FILES) | set(wf.TEST_FILES) | set(wf.REFERENCE_FILES):
            self.assertEqual([], [row for row in self.build.splitlines() if 'exclude(' in row and name in row], name)
        manifest = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        paths = {row['path'] for row in manifest['sources']}
        for name, upstream in wf.UPSTREAM_PATHS.items():
            self.assertNotIn(upstream, paths, name)
        # the producer reaches its generator only through the wrapper, validating first and emitting after the extension
        wrapper = (TOOLS / 'extract-upstream-home-core-refresh.py').read_text(encoding='utf-8')
        validate_call = 'validate_wallpaper_foundation_outputs(args.repo,args.output,args.test_output)'
        run_at = wrapper.index('result=subprocess.run(command)')
        extend_at = wrapper.index('extend_same_home_producer(args.output,args.test_output)')
        palette_at = wrapper.index('emit_identity_badge_palette_tests(args.repo,args.test_output)')
        main_call = 'emit_wallpaper_foundation(args.repo,args.output)'
        tests_call = 'emit_wallpaper_foundation_tests(args.repo,args.test_output)'
        for call in (validate_call, main_call, tests_call):
            self.assertEqual(1, wrapper.count(call), call)
        self.assertLess(wrapper.index(validate_call), run_at)
        self.assertLess(extend_at, wrapper.index(main_call))
        self.assertLess(palette_at, wrapper.index(main_call))
        self.assertLess(wrapper.index(main_call), wrapper.index(tests_call))
        self.assertLess(wrapper.index(tests_call), wrapper.index('return 0'))
        page = (TOOLS / 'extract-upstream-home-page.py').read_text(encoding='utf-8')
        self.assertNotIn('v046_wallpaper_foundation', page)
        self.assertFalse(any(line.startswith(('import v046_wallpaper_foundation', 'from v046_wallpaper_foundation'))
                             for line in wrapper.splitlines()))

    def test_inventory_rows_and_catalog_pin_every_leaf(self):
        self.assertEqual(1, self.catalog['buildInputRoots'].count(wf.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(wf.ARCHIVE + '/')]
        self.assertEqual(7, len(rows))
        self.assertEqual(sorted(wf.UPSTREAM_PATHS.values()), sorted(row['path'] for row in rows))
        for row in rows:
            name = Path(row['localPath']).name
            with self.subTest(name=name):
                self.assertEqual(name in wf.REFERENCE_FILES, row['referenceOnly'])
                self.assertFalse(row['partialReuse'])
                self.assertEqual([FEATURE], row['features'])
                self.assertEqual('raw', row['hashNormalization'])
                self.assertEqual('adaptedSource', row['kind'])
                self.assertEqual(wf.COMMIT, row['upstreamCommit'])
                self.assertEqual(sha(self.archive[name]), row['sha256'])
                self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == wf.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({wf.ARCHIVE + '/' + name for name in wf.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(wf.ARCHIVE + '/')])

    def test_every_emitted_declaration_is_pinned_and_the_files_import_nothing_android(self):
        for name in wf.MAIN_FILES:
            with self.subTest(name=name):
                text = self.archive[name].decode('utf-8')
                package, found = declarations(text, wf.RELATIVE[name])
                self.assertEqual(wf.PACKAGE[name], package)
                self.assertEqual(TOP_LEVEL[name], found)
                self.assertEqual(IMPORTS[name], imports(text))
                self.assertNotIn('import android.', text)
                self.assertTrue(wf.RELATIVE[name].startswith(wf.PACKAGE[name].replace('.', '/') + '/'))

    def test_the_emitted_and_windows_owned_names_have_exactly_one_definer(self):
        names = set()
        for name in wf.MAIN_FILES:
            names |= {item[2] for item in TOP_LEVEL[name]}
        owned = {path: WINDOWS_NAMES[path] for path in (PROBE, CROP, SHEET)}
        everything = set(names)
        for path_names in owned.values():
            everything |= path_names
        roots = ['app', 'design-system', 'core-data', 'core-player', 'network-core', 'settings-core', 'danmaku-engine',
                 'plugins', 'plugin-sdk', 'miuix-navigation', 'desktop/src']
        pattern = re.compile(r'(?m)^[ \t]*(?:@\w+[ \t]+|(?:internal|private|public|data|sealed|enum|const|inline|suspend)[ \t]+)*'
                             r'(?:fun|val|var|class|object|interface|typealias)[ \t]+(?:<[^>]*>[ \t]+)?(?:[\w.<>?, ]+\.)?(\w+)')
        found = {}
        for root in roots:
            for path in (REPO / root).rglob('*.kt'):
                text = path.read_text(encoding='utf-8', errors='replace')
                if not any(name in text for name in everything):
                    continue
                for match in pattern.finditer(text):
                    if match.group(1) in everything:
                        found.setdefault(match.group(1), []).append(path.relative_to(REPO).as_posix())
        for name in names:
            with self.subTest(name=name):
                self.assertEqual([], found.get(name, []), 'the vendored tree or desktop/src already declares it')
        for path, path_names in owned.items():
            for name in path_names:
                with self.subTest(owner=path.name, name=name):
                    self.assertEqual([path.relative_to(REPO).as_posix()], found.get(name, []))
        # no other carried archive declares a name this slice emits (they only import or call it)
        for archive in sorted((REPO / 'desktop/upstream-slices').iterdir()):
            if not archive.is_dir() or archive.name == FEATURE:
                continue
            for path in archive.rglob('*.kt'):
                text = path.read_text(encoding='utf-8', errors='replace')
                if not any(name in text for name in names):
                    continue
                for match in pattern.finditer(text):
                    self.assertNotIn(match.group(1), names, path.relative_to(REPO).as_posix())

    def test_windows_sources_are_lf_utf8_balanced_and_declare_the_pinned_names(self):
        for path in WINDOWS_FILES:
            with self.subTest(path=path.name):
                raw = path.read_bytes()
                self.assertNotIn(b'\r', raw)
                self.assertTrue(raw.endswith(b'\n'))
                text = raw.decode('utf-8')
                balanced(self, text)
                for forbidden in ('Toast', 'LocalContext', 'LocalConfiguration', 'BitmapFactory.', 'import android.'):
                    self.assertFalse(forbidden in text, path.name + ' uses ' + forbidden)
        for path, expected in WINDOWS_NAMES.items():
            package, found = declarations(self.windows[path], str(path.relative_to(REPO)))
            self.assertEqual(expected, {item[2] for item in found}, path.name)
            self.assertEqual(path.relative_to(REPO / 'desktop/src/main/kotlin').parent.as_posix().replace('/', '.'),
                             package, path.name)

    def test_the_windows_sheet_keeps_the_exact_public_parameter_list_of_the_carried_original(self):
        original = self.sources['HomeWallpaperAdjustmentSheet.kt']
        windows = self.windows[SHEET]
        signature = wf.sheet_signature(original)
        self.assertEqual(['uri', 'initialMobileTransform', 'initialTabletTransform', 'effectMode', 'saving', 'saveError',
                          'onDismiss', 'onSave'], [name for name, _type, _default in signature])
        self.assertEqual(signature, wf.sheet_signature(windows))
        for text in (original, windows):
            self.assertIn('@Composable\ninternal fun HomeWallpaperAdjustmentSheet(', text)
        self.assertTrue(windows.startswith('@file:OptIn('))
        self.assertIn('package com.android.purebilibili.feature.profile\n', windows)
        # the two callers of the original (carried AppearanceSettingsScreen and SplashWallpaperPickerSheet) use only these names
        liquid = (REPO / 'desktop/upstream-slices/v040-liquid-value-controls/AppearanceSettingsScreen.kt').read_text(encoding='utf-8')
        at = liquid.index('HomeWallpaperAdjustmentSheet(')
        call = liquid[at:at + 900]
        for name, _type, _default in signature:
            self.assertIn(name + ' =', call, name)
        # a drifting signature is caught: dropping a parameter or changing a default or a type changes the list
        for old, new in (('    saving: Boolean = false,\n', ''),
                         ('effectMode: HomeWallpaperEffectMode = HomeWallpaperEffectMode.ORIGINAL', 'effectMode: HomeWallpaperEffectMode = HomeWallpaperEffectMode.OFF'),
                         ('saveError: String? = null', 'saveError: String = ""')):
            self.assertEqual(1, windows.count(old), old)
            self.assertNotEqual(signature, wf.sheet_signature(windows.replace(old, new)), old)
        with self.assertRaises(ValueError):
            wf.sheet_signature(original + original)
        with self.assertRaises(ValueError):
            wf.sheet_signature('package x\n')

    def test_the_windows_adapter_keeps_the_numbers_of_the_android_sheet_and_the_preference_names(self):
        original = self.sources['HomeWallpaperAdjustmentSheet.kt']
        crop = self.windows[CROP]
        self.assertIn('configuration.screenWidthDp >= 600', original)
        self.assertIn('DESKTOP_WALLPAPER_TABLET_MIN_WIDTH_DP = 600f', crop)
        self.assertIn('if (selectedTab == 0) 400f else 960f', original)
        self.assertIn('if (selectedTab == 0) 800f else 600f', original)
        for literal in ('DesktopWallpaperPreviewSpace(400f, 800f)', 'DesktopWallpaperPreviewSpace(960f, 600f)'):
            self.assertEqual(1, crop.count(literal), literal)
        self.assertIn('valueRange = 1f..3f', original)
        self.assertIn('valueRange = 1f..3f', self.windows[SHEET])
        # the seven preference keys of the carried SettingsManager selection store, in the same order
        carried = (REPO / 'desktop/upstream-slices/v046-comment-list/SettingsManager.kt').read_text(encoding='utf-8')
        keys = re.findall(r'private val KEY_HOME_WALLPAPER_(?:URI|(?:SCALE|OFFSET_[XY])_(?:MOBILE|TABLET)) = '
                          r'\w+PreferencesKey\("([^"]+)"\)', carried)
        self.assertEqual(7, len(keys))
        block = crop[crop.index('internal object DesktopHomeWallpaperKeys {'):]
        block = block[:block.index('\n}\n')]
        ours = re.findall(r'const val \w+ = "([^"]+)"', block)
        self.assertEqual(sorted(keys), sorted(ours))
        self.assertEqual(7, len(ours))
        self.assertIn('val ALL = listOf(URI, SCALE_MOBILE, OFFSET_X_MOBILE, OFFSET_Y_MOBILE, SCALE_TABLET, OFFSET_X_TABLET, OFFSET_Y_TABLET)', block)

    def test_every_call_into_the_carried_policy_and_the_windows_helpers_matches_the_real_signature(self):
        policy = self.sources['WallpaperTransformPolicy.kt']
        crop, sheet, probe = self.windows[CROP], self.windows[SHEET], self.windows[PROBE]
        expected = {'applyWallpaperGesture': 8, 'resolveWallpaperCropGeometry': 5, 'sanitizeWallpaperTransform': 1}
        for name, count in expected.items():
            self.assertEqual(count, len(parameters(policy, name)), name)
        used = 0
        for text in (crop, sheet):
            for call in calls(text, set(expected)):
                used += 1
                self.assertEqual('', call_problem(call, parameters(policy, call['name'])), call)
        self.assertGreaterEqual(used, 6)
        helpers = {'desktopWallpaperDrag': (crop, 7), 'desktopWallpaperWheel': (crop, 6),
                   'desktopWallpaperPreviewSpace': (crop, 3), 'desktopWallpaperDeviceForWidth': (crop, 1),
                   'probeDesktopWallpaperUri': (probe, 1), 'DesktopWallpaperCropImage': (crop, 6)}
        for name, (source, count) in helpers.items():
            self.assertEqual(count, len(parameters(source, name)), name)
        for call in calls(sheet, set(helpers)):
            problem = call_problem(call, parameters(helpers[call['name']][0], call['name']))
            self.assertEqual('', problem, call)
        self.assertEqual(set(helpers), {call['name'] for call in calls(sheet, set(helpers))})
        # the UI kit calls: every named argument exists in the real definition the Windows tree compiles
        primitives = REPO / 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveComponents.kt'
        kit = {
            'AppSlider': REPO / 'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSlider.kt',
            'AppTextButton': primitives,
            'AppFilterChip': primitives,
            'AppModalBottomSheet': REPO / 'design-system/src/main/java/com/android/purebilibili/core/ui/AppSheetComponents.kt',
        }
        for name, path in kit.items():
            params = parameters(path.read_text(encoding='utf-8'), name)
            for call in calls(sheet, {name}):
                self.assertEqual('', call_problem(call, params), (name, call))
        # the window chips are the design-system chip (it follows AppUiStyle and the elastic press), never the raw material
        # FilterChip; the vendored AppPrimitiveComponents.kt is excluded from the Windows build, so the chip the sheet really
        # links against is the layered copy of the later archives, and its leading parameters must be the same
        chip_calls = calls(sheet, {'AppFilterChip'})
        self.assertEqual(1, len(chip_calls))
        self.assertEqual(['selected', 'onClick', 'label', 'enabled'], chip_calls[0]['named'])
        self.assertIn('import com.android.purebilibili.core.ui.components.AppFilterChip\n', sheet)
        self.assertNotIn('import androidx.compose.material3.FilterChip', sheet)
        self.assertIsNone(re.search(r'(?<![\w.])FilterChip\(', sheet))
        for archive in ('v040-chrome-actions', 'v043-theme-chrome'):
            layered = (REPO / 'desktop/upstream-slices' / archive / 'AppPrimitiveComponents.kt').read_text(encoding='utf-8')
            self.assertIn('package com.android.purebilibili.core.ui.components\n', layered)
            layered_params = parameters(layered, 'AppFilterChip')
            self.assertEqual([label for label, _default in parameters(primitives.read_text(encoding='utf-8'), 'AppFilterChip')],
                             [label for label, _default in layered_params], archive)
            self.assertEqual('', call_problem(chip_calls[0], layered_params), archive)

    def test_the_windows_sheet_drives_the_policy_with_the_pointer_and_leaves_ctrl_wheel_to_the_display_scale(self):
        sheet = self.windows[SHEET]
        for token in ('detectDragGestures {', 'PointerEventType.Scroll', 'desktopWallpaperDrag(', 'desktopWallpaperWheel(',
                      '!event.keyboardModifiers.isCtrlPressed', 'it.scrollDelta.y', 'isVideoWallpaper(uri)',
                      'probeDesktopWallpaperUri(uri)', 'desktopWallpaperPreviewSpace(device, windowWidthDp, windowHeightDp)',
                      'sanitizeWallpaperTransform('):
            self.assertGreaterEqual(sheet.count(token), 1, token)
        # dismissal is blocked while saving and save hands back both drafts: the draft-only contract of the original
        for token in ('onDismissRequest = { if (!saving) onDismiss() }', 'onClick = { onSave(mobile, tablet) }'):
            self.assertEqual(1, sheet.count(token), token)
        # window size is read in the caller's window, before the sheet content, and the sheet never writes settings
        self.assertLess(sheet.index('LocalWindowInfo.current.containerSize'), sheet.index('AppModalBottomSheet('))
        for forbidden in ('SettingsManager', 'DesktopOriginalHomeSettingsManager', 'setHomeWallpaper', 'Files.write'):
            self.assertNotIn(forbidden, sheet)
        probe = self.windows[PROBE]
        self.assertIn('fun rememberIsStaticWallpaper(uri: String): Boolean', probe)
        self.assertIn('package ' + WALLPAPER + '\n', probe)
        self.assertIn('withContext(Dispatchers.IO)', probe)

    def test_the_wheel_zoom_rate_does_not_depend_on_the_units_the_platform_reports_per_notch(self):
        crop, sheet, jvm = self.windows[CROP], self.windows[SHEET], self.windows[CROP_TEST]
        # one event moves by one notch at most, so a platform that reports three lines per notch zooms as one that reports one
        self.assertEqual(1, crop.count('DESKTOP_WALLPAPER_WHEEL_ZOOM_BASE.pow(-wheelDeltaY.coerceIn(-1f, 1f))'))
        self.assertEqual(1, crop.count('if (wheelDeltaY.isFinite())'))
        self.assertNotIn('coerceIn(0.5f, 2f)', crop)
        self.assertIn('internal const val DESKTOP_WALLPAPER_WHEEL_ZOOM_BASE = 1.1f', crop)
        # the sheet hands the summed delta of one event on unchanged; the normalisation lives in the one adapter function
        self.assertEqual(1, sheet.count('it.scrollDelta.y'))
        self.assertEqual(1, sheet.count('desktopWallpaperWheel('))
        for literal in ('desktopWallpaperWheelZoomFactor(-3f)', 'desktopWallpaperWheelZoomFactor(3f)',
                        'desktopWallpaperWheelZoomFactor(-80f)', 'desktopWallpaperWheelZoomFactor(80f)',
                        'desktopWallpaperWheelZoomFactor(-0.5f)', 'desktopWallpaperWheelZoomFactor(Float.POSITIVE_INFINITY)'):
            self.assertEqual(1, jvm.count(literal), literal)
        # the old "two notches are 1.21" expectation is gone with the old clamp
        self.assertNotIn('1.21f', jvm)

    def test_the_one_probable_upstream_red_case_is_recorded_with_its_exact_literals(self):
        # The carried v0.4.6 suite is emitted verbatim and its production code is unchanged, so nothing here edits either.
        # Emulating the policy in Float arithmetic, landscapeImageFillsPortraitViewportAndCanReachBothEdges asserts
        # assertEquals(0f, left.left) with no tolerance, but offsetX = -1 makes the crop left -(overflow) * 0f / 2f = -0.0f,
        # and kotlin.test.assertEquals on two Floats compares the boxed values (Float.equals), which tells -0.0f from 0.0f.
        # The case is therefore a probable upstream-red JVM case (a Linux compile run confirms it). Root alone decides
        # whether to pin a one-literal adaptation (playbook 6.2: "a case to flag, not a licence"); these are the exact
        # original literal and the one candidate replacement, recorded here and applied nowhere.
        suite = self.sources['WallpaperTransformPolicyTest.kt']
        policy = self.sources['WallpaperTransformPolicy.kt']
        original = '        assertEquals(0f, left.left)\n'
        candidate = '        assertEquals(0f, left.left, 0.01f)\n'
        self.assertEqual(1, suite.count(original))
        # the candidate literal already exists once, in the visible region case (with its tolerance, on a
        # WallpaperSourceRegion), never in the case that asserts the crop left
        case = suite[suite.index('fun landscapeImageFillsPortraitViewportAndCanReachBothEdges()'):]
        case = case[:case.index('@Test')]
        self.assertIn(original, case)
        self.assertNotIn(candidate, case)
        self.assertEqual(1, suite.count(candidate))
        self.assertIn('        val left = resolveWallpaperCropGeometry(400f, 800f, 1600f, 900f, WallpaperTransform(offsetX = -1f))\n', suite)
        self.assertIn('left = -(renderedWidth - width) * (safe.offsetX + 1f) / 2f,', policy)
        self.assertIn('val cropScale = max(width / sw, height / sh) * safe.scale', policy)
        # no other test in the suite compares two Floats without a tolerance except the data class equality below
        exact = re.findall(r'(?m)^\s*assertEquals\(([^(),]+), ([^(),]+)\)$', suite)
        self.assertEqual([('0f', 'left.left'), ('zoomed', 'preview')], exact)
        _width, _height, left_left, _top = crop_geometry_f32(400.0, 800.0, 1600.0, 900.0, 1.0, -1.0, 0.0)
        self.assertEqual(0.0, left_left)
        self.assertEqual(0x80000000, f32_bits(left_left), 'left.left is negative zero')
        self.assertNotEqual(f32_bits(0.0), f32_bits(left_left), 'a boxed Float equality tells it from 0f')
        # the other tolerance-free comparison holds in the same emulation: the preview region equals the full size region
        self.assertEqual(visible_region_f32(400.0, 800.0, 1600.0, 900.0, 2.0), visible_region_f32(100.0, 200.0, 1600.0, 900.0, 2.0))
        # and the emulation reproduces the tolerance assertions of the neighbouring case (the right edge)
        right = crop_geometry_f32(400.0, 800.0, 1600.0, 900.0, 1.0, 1.0, 0.0)
        self.assertAlmostEqual(400.0, f32(right[2] + right[0]), delta=0.01)

    def test_the_hand_written_jvm_classes_have_every_case_and_need_no_window_gpu_or_network(self):
        for path, count in JVM_TESTS.items():
            with self.subTest(path=path.name):
                text = path.read_text(encoding='utf-8')
                self.assertEqual(count, len(re.findall(r'(?m)^    @Test\n    fun ', text)))
                balanced(self, text)
                self.assertIn('import kotlin.test.Test', text)
                self.assertNotIn('org.junit', text)
                for forbidden in ('runComposeUiTest', 'Window(', 'java.net', 'HttpClient', 'GraphicsEnvironment'):
                    self.assertNotIn(forbidden, text)
        # the emitted original suites and the hand-written ones are all in test roots, never under a main root
        for path in JVM_TESTS:
            self.assertIn('/src/test/kotlin/', path.as_posix())

    def test_the_gate_expectations_retire_eight_symbols_and_keep_six_open_for_the_consumer_slice(self):
        expected = gate.load_expected(TOOLS / 'symbol-closure-expected-gaps.json')
        retired = {entry['symbol']: entry for entry in expected['retired']}
        closed = {'WallpaperTransform': WALLPAPER, 'sanitizeWallpaperTransform': WALLPAPER,
                  'HomeWallpaperSelection': 'com.android.purebilibili.core.store.home',
                  'LocalHomeWallpaperSelection': WALLPAPER, 'WallpaperPlaybackCoordinator': WALLPAPER,
                  'LocalWallpaperPlaybackCoordinator': WALLPAPER, 'rememberIsStaticWallpaper': WALLPAPER,
                  'HomeWallpaperAdjustmentSheet': 'com.android.purebilibili.feature.profile'}
        for name, package in closed.items():
            with self.subTest(name=name):
                self.assertEqual(package, retired[name]['package'])
                self.assertEqual('declaration', retired[name]['kind'])
                self.assertIn('d6bf8b16', retired[name]['evidence'])
                if name in ('rememberIsStaticWallpaper', 'HomeWallpaperAdjustmentSheet'):
                    # the two Windows-owned definers name the desktop file that declares them
                    owner = retired[name]['retiredBy'].split(' ', 1)[0]
                    self.assertTrue((REPO / owner).is_file(), owner)
                    self.assertIn(name, (REPO / owner).read_text(encoding='utf-8'))
                else:
                    self.assertIn('desktop/upstream-slices/v046-wallpaper-foundation', retired[name]['retiredBy'])
                    self.assertIn('v046_wallpaper_foundation.py', retired[name]['retiredBy'])
                self.assertIn('test_v046_wallpaper_foundation_source.py', retired[name]['guardedBy'])
        open_symbols = {gap['symbol'].rsplit('.', 1)[-1] for gap in expected['gaps']}
        self.assertEqual(set(), open_symbols & set(closed))
        left = {'readSeedWallpaperColorScheme', 'readSystemWallpaperSeed', 'WallpaperMediaLoadState',
                'WallpaperPaletteRequest', 'WallpaperPaletteResult', 'shouldRenderRootHomeWallpaperMedia'}
        self.assertTrue(left <= open_symbols)
        for gap in expected['gaps']:
            if gap['symbol'].rsplit('.', 1)[-1] in left:
                self.assertEqual('wallpaper-surfaces-consumers', gap['closedBy'])
                self.assertEqual('carried', gap['scope'])
                self.assertEqual('unresolved-import', gap['check'])

    def test_new_python_files_are_lf(self):
        for path in (TOOLS / 'v046_wallpaper_foundation.py', Path(__file__)):
            self.assertNotIn(b'\r', path.read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
