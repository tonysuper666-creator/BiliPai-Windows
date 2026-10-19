"""v0.4.6 identity badge palette emitted whole by the home-page producer, its suites by the home refresh wrapper."""
from pathlib import Path
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_identity_badge_palette as palette

spec = importlib.util.spec_from_file_location('sync_upstream_identity_badge_palette', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

page_spec = importlib.util.spec_from_file_location(
    'extract_upstream_home_page_identity_badge_palette', TOOLS / 'extract-upstream-home-page.py')
page = importlib.util.module_from_spec(page_spec)
page_spec.loader.exec_module(page)

PAGE_PRODUCER = 'tools/extract-upstream-home-page.py'
WRAPPER_PRODUCER = 'tools/extract-upstream-home-core-refresh.py'
CANONICAL = {name: palette.UPSTREAM_PATHS[name] for name in palette.MAIN_FILES}
MAIN_RELATIVE = {name: palette.RELATIVE[name] for name in palette.MAIN_FILES}
TEST_RELATIVE = {name: palette.RELATIVE[name] for name in palette.TEST_FILES}
FEATURE = 'v046-identity-badge-palette'
# (visibility, kind, name) of every top-level declaration of each emitted source.
TOP_LEVEL = {
    'AppBadge.kt': {('public', 'fun', 'AppBadge')},
    'OfficialVerifyBadgePolicy.kt': {
        ('public', 'class', 'OfficialVerifyBadgeTone'),
        ('public', 'fun', 'officialVerifyColor'),
        ('public', 'class', 'OfficialVerifyBadgeSpec'),
        ('public', 'fun', 'resolveOfficialVerifyBadge'),
        ('public', 'fun', 'resolveOfficialVerifyBadgeFromRole'),
        ('private', 'fun', 'buildOfficialVerifyBadgeSpec'),
        ('public', 'fun', 'OfficialVerifyBadge'),
        ('public', 'fun', 'OfficialVerifyAvatarBadge'),
    },
    'UpBadgeNamePolicy.kt': {
        ('internal', 'fun', 'resolveUpNameColor'),
        ('internal', 'fun', 'resolveUpStatsText'),
        ('internal', 'fun', 'shouldRenderUpBadgeTrailingSlot'),
        ('internal', 'fun', 'shouldRenderUserUpBadge'),
    },
    'UserAvatarCornerMark.kt': {
        ('public', 'class', 'UserAvatarCornerMark'),
        ('public', 'fun', 'resolveUserAvatarCornerMark'),
        ('public', 'fun', 'UserAvatarCornerMarkBadge'),
    },
    'UserUpBadge.kt': {
        ('internal', 'class', 'UserUpBadgeVisualSpec'),
        ('internal', 'fun', 'resolveUserUpBadgeVisualSpec'),
        ('public', 'fun', 'UserUpBadge'),
    },
}
SHARED_NAMES = {name for rows in TOP_LEVEL.values() for visibility, _kind, name in rows if visibility != 'private'}
TEST_CLASSES = {
    'OfficialVerifyBadgePolicyTest.kt': 'OfficialVerifyBadgePolicyTest',
    'UpBadgeNamePolicyTest.kt': 'UpBadgeNamePolicyTest',
    'UserAvatarCornerMarkTest.kt': 'UserAvatarCornerMarkTest',
}
# Names the emitted sources and suites call, each with exactly one definer in the compiled universe.
DEPENDENCIES = {
    'resolveButtonContentColor': ('com.android.purebilibili.core.ui', 3),
    'userVipPink': ('com.android.purebilibili.core.ui.components', 0),
    'resolveUserVipPink': ('com.android.purebilibili.core.ui.components', 1),
    'resolveContentSizedTabScrollOffsetPx': ('com.android.purebilibili.core.ui.components', 4),
    'AppMaterial3Badge': ('com.android.purebilibili.core.ui.renderer.material3', 4),
    'AppMiuixBadge': ('com.android.purebilibili.core.ui.renderer.miuix', 4),
}
CALLEES = {
    'AppBadge': 'AppBadge.kt',
    'OfficialVerifyBadge': 'OfficialVerifyBadgePolicy.kt',
    'OfficialVerifyAvatarBadge': 'OfficialVerifyBadgePolicy.kt',
    'officialVerifyColor': 'OfficialVerifyBadgePolicy.kt',
    'resolveOfficialVerifyBadge': 'OfficialVerifyBadgePolicy.kt',
    'resolveOfficialVerifyBadgeFromRole': 'OfficialVerifyBadgePolicy.kt',
    'resolveUpNameColor': 'UpBadgeNamePolicy.kt',
    'resolveUpStatsText': 'UpBadgeNamePolicy.kt',
    'shouldRenderUpBadgeTrailingSlot': 'UpBadgeNamePolicy.kt',
    'shouldRenderUserUpBadge': 'UpBadgeNamePolicy.kt',
    'resolveUserAvatarCornerMark': 'UserAvatarCornerMark.kt',
    'UserAvatarCornerMarkBadge': 'UserAvatarCornerMark.kt',
    'resolveUserUpBadgeVisualSpec': 'UserUpBadge.kt',
    'UserUpBadge': 'UserUpBadge.kt',
}


# The three compiled owners that still passed the theme secondary to resolveUpNameColor, with the v0.4.6 file whose call
# the producers now match and the generated output each producer writes.
UP_NAME_OWNERS = {
    'DynamicCard.kt': dict(
        upstream='app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCard.kt',
        output='com/android/purebilibili/feature/dynamic/components/DesktopOriginalDynamicCard.kt',
        generated='build/generated/dynamic-full-card/', producer='extract-upstream-dynamic-card.py'),
    'SpaceScreen.kt': dict(
        upstream='app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt',
        output='com/android/purebilibili/feature/space/SpaceScreen.kt',
        generated='build/generated/original-space-pages/', producer='extract-upstream-space-pages.py'),
    'VideoInfoSection.kt': dict(
        upstream='app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoInfoSection.kt',
        output='com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoSection.kt',
        generated='build/generated/original-video-detail-units/',
        producer='extract-upstream-video-detail-full-units.py'),
}
# A finished producer mirror (check_slice_symbol_closure.py --keep-scratch, the directory named r) may be shared with
# the symbol gate so that the universe class below does not run every producer again.
SHARED_MIRROR = 'BP_IDENTITY_BADGE_MIRROR'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def load_tool(name):
    tool_spec = importlib.util.spec_from_file_location(
        'identity_badge_palette_' + name.replace('-', '_').replace('.py', ''), TOOLS / name)
    module = importlib.util.module_from_spec(tool_spec)
    tool_spec.loader.exec_module(module)
    return module


def upstream_blob(path):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', palette.COMMIT + ':' + path],
                          cwd=str(REPO), capture_output=True, check=True).stdout.decode('utf-8')


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / palette.ARCHIVE).iterdir() if p.is_file()}


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


def parameters(source, name):
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
    """Every call of one of the names: positional count, named arguments, trailing lambda; no declarations."""
    tokens = sync.kotlin_tokens(text)
    found = []
    for index, token in enumerate(tokens):
        if token[0] not in names or index + 1 >= len(tokens):
            continue
        if index and tokens[index - 1][0] in {'fun', '::'}:
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


class V046IdentityBadgePaletteSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-ibp-')
        cls.root = Path(cls.temporary.name)
        cls.page_stdout = run_cli(PAGE_PRODUCER, '--output', str(cls.root / 'page-main'))
        cls.page_main = tree(cls.root / 'page-main')
        cls.wrapper_stdout = run_cli('tools/extract-upstream-home-core-refresh.py', '--output', str(cls.root / 'main'),
                                     '--test-output', str(cls.root / 'tests'))
        cls.main = tree(cls.root / 'main')
        cls.tests = tree(cls.root / 'tests')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.sources, cls.raws, cls.manifest = palette.fixed(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        original = archive_files()
        self.assertEqual(self.archive, original)
        leaves = sorted(original)
        self.assertEqual(sorted(palette.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        self.assertEqual(8, len(palette.EXPECTED))
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / palette.ARCHIVE, repo / palette.ARCHIVE)
                target = repo / palette.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    palette.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / palette.ARCHIVE, repo / palette.ARCHIVE)
            (repo / palette.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                palette.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / palette.ARCHIVE, repo / palette.ARCHIVE)
            (repo / palette.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                palette.fixed(repo)
        self.assertEqual(original, archive_files())

    def test_archive_matches_the_upstream_git_blobs_and_the_manifest_rows(self):
        manifest = json.loads(self.archive['manifest.json'])
        self.assertEqual(palette.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        self.assertEqual(sorted(palette.EXPECTED), sorted(row['filename'] for row in manifest['files']))
        for row in manifest['files']:
            with self.subTest(name=row['filename']):
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', palette.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, self.archive[row['filename']])
                self.assertEqual(row['path'], palette.UPSTREAM_PATHS[row['filename']])
                self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\0' + blob).hexdigest())
                self.assertNotIn(b'\r', blob)

    def test_page_producer_emits_the_four_verbatim_files_and_the_adapted_user_up_badge(self):
        self.assertIn('original Home outputs', self.page_stdout)
        for name in palette.MAIN_FILES:
            with self.subTest(name=name):
                relative = MAIN_RELATIVE[name]
                self.assertIn(relative, self.page_main)
                self.assertEqual(self.page_main[relative], self.main[relative])
                if name == 'UserUpBadge.kt':
                    self.assertNotEqual(self.archive[name], self.page_main[relative])
                else:
                    self.assertEqual(self.archive[name], self.page_main[relative])
                self.assertNotIn(b'\r', self.page_main[relative])
        proof = json.loads(self.page_main[palette.PROOF_NAME].decode('utf-8'))
        self.assertEqual(palette.COMMIT, proof['upstreamCommit'])
        self.assertEqual(palette.MANIFEST_SHA256, proof['sourceManifestSHA256'])
        self.assertEqual(['UserUpBadge.kt'], list(proof['adaptedFiles']))
        self.assertEqual({name: sha(self.archive[name]) for name in palette.MAIN_FILES if name != 'UserUpBadge.kt'},
                         proof['verbatimFiles'])
        self.assertTrue(proof['vendoredDirectCopiesExcluded'])
        matches = {name: [path for path, raw in self.page_main.items() if raw == self.archive[name]]
                   for name in palette.MAIN_FILES if name != 'UserUpBadge.kt'}
        for name, found in matches.items():
            self.assertEqual([MAIN_RELATIVE[name]], found, name)

    def test_user_up_badge_drops_only_the_android_font_padding_and_restores_the_original(self):
        original = self.sources['UserUpBadge.kt']
        emitted = self.page_main[MAIN_RELATIVE['UserUpBadge.kt']].decode('utf-8')
        proof = json.loads(self.page_main[palette.PROOF_NAME].decode('utf-8'))['adaptedFiles']['UserUpBadge.kt']
        self.assertEqual(original, palette.restore(emitted, proof['originalToWindows']))
        self.assertEqual(original.replace(palette.USER_UP_BADGE_IMPORT, '', 1)
                         .replace(palette.USER_UP_BADGE_ARGUMENT, '', 1), emitted)
        self.assertEqual(2, len(proof['originalToWindows']['hunks']))
        self.assertTrue(all(hunk['after'] == '' for hunk in proof['originalToWindows']['hunks']))
        self.assertNotIn('PlatformTextStyle', emitted)
        self.assertNotIn('includeFontPadding', emitted)
        for token in ('lineHeightStyle = LineHeightStyle(', 'trim = LineHeightStyle.Trim.Both,',
                      'MaterialTheme.typography.labelSmall.copy(', 'contentAlignment = Alignment.Center,',
                      'maxLines = 1,', 'softWrap = false,'):
            self.assertEqual(1, emitted.count(token), token)
        self.assertNotIn('fontSp', emitted)
        for name in palette.MAIN_FILES:
            if name != 'UserUpBadge.kt':
                self.assertNotIn(b'includeFontPadding', self.archive[name])
        for bad in (original.replace(palette.USER_UP_BADGE_IMPORT, ''),
                    original.replace(palette.USER_UP_BADGE_ARGUMENT, ''),
                    original + palette.USER_UP_BADGE_ARGUMENT,
                    original.replace('package ', 'pakage ', 1) + 'val x = PlatformTextStyle(includeFontPadding = true)\n'):
            with self.subTest(bad=bad[-60:]), self.assertRaises(ValueError):
                palette.windows_user_up_badge(bad)

    def test_wrapper_emits_the_suites_and_a_receipt_into_the_test_root_only(self):
        for path in [*MAIN_RELATIVE.values(), palette.PROOF_NAME]:
            self.assertEqual(self.page_main[path], self.main[path], path)
        for name in palette.TEST_FILES:
            self.assertIn(TEST_RELATIVE[name], self.tests, name)
            self.assertNotIn(TEST_RELATIVE[name], self.main, name)
        self.assertIn(palette.TESTS_PROOF_NAME, self.tests)
        self.assertNotIn(palette.TESTS_PROOF_NAME, self.main)
        for name in palette.MAIN_FILES:
            self.assertNotIn(MAIN_RELATIVE[name], self.tests, name)
        for path in self.tests:
            self.assertFalse(path.endswith(tuple('/' + name for name in palette.MAIN_FILES)), path)
        proof = json.loads(self.tests[palette.TESTS_PROOF_NAME].decode('utf-8'))
        self.assertEqual(palette.TEST_COUNTS, proof['testCounts'])
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertEqual(['UpBadgeNamePolicyTest.kt'], list(proof['adaptedFiles']))

    def test_emitted_suites_keep_every_original_case_and_only_swap_the_junit_imports(self):
        for name, count in palette.TEST_COUNTS.items():
            with self.subTest(name=name):
                text = self.tests[TEST_RELATIVE[name]].decode('utf-8')
                original = self.archive[name].decode('utf-8')
                self.assertEqual(count, text.count('@Test'))
                self.assertEqual(count, original.count('@Test'))
                self.assertEqual(1, text.count('class ' + TEST_CLASSES[name] + ' {'))
                self.assertNotIn('org.junit', text)
                self.assertNotIn('\r', text)
                package, decls = declarations(text, name)
                self.assertEqual(palette.PACKAGE[name], package)
                self.assertEqual({('public', 'class', TEST_CLASSES[name])}, decls)
                if name == 'UpBadgeNamePolicyTest.kt':
                    self.assertIn('org.junit.Assert.assertEquals', original)
                    self.assertEqual(5, original.count('import org.junit.'))
                    proof = json.loads(self.tests[palette.TESTS_PROOF_NAME].decode('utf-8'))
                    restored = palette.restore(text, proof['adaptedFiles'][name]['originalToWindows'])
                    self.assertEqual(original, restored)
                    self.assertEqual(5, sum(1 for line in text.splitlines() if line.startswith('import kotlin.test.')))
                else:
                    self.assertEqual(original, text)
        user_up_case = self.tests[TEST_RELATIVE['UpBadgeNamePolicyTest.kt']].decode('utf-8')
        self.assertIn('UserUpBadgeVisualSpec(\n                cornerRadiusDp = 3,\n'
                      '                horizontalPaddingDp = 3,\n                verticalPaddingDp = 2\n            )', user_up_case)
        self.assertNotIn('fontSp', user_up_case)
        corner = self.tests[TEST_RELATIVE['UserAvatarCornerMarkTest.kt']].decode('utf-8')
        self.assertEqual(1, corner.count('import com.android.purebilibili.core.ui.components.resolveContentSizedTabScrollOffsetPx\n'))

    def test_emit_is_deterministic_and_a_rerun_matches(self):
        def once(seed):
            main, tests = self.root / ('emit-main-%d' % seed), self.root / ('emit-tests-%d' % seed)
            return palette.emit(REPO, main), palette.emit_tests(REPO, tests), tree(main), tree(tests)

        first, second = once(1), once(2)
        self.assertEqual(first, second)
        self.assertEqual([MAIN_RELATIVE[name] for name in palette.MAIN_FILES] + [palette.PROOF_NAME],
                         [row['path'] for row in first[0]['files']])
        self.assertEqual([TEST_RELATIVE[name] for name in palette.TEST_FILES] + [palette.TESTS_PROOF_NAME],
                         [row['path'] for row in first[1]['files']])
        self.assertEqual(first[0], palette.emit(REPO, self.root / 'emit-main-1'))
        self.assertEqual(first[1], palette.emit_tests(REPO, self.root / 'emit-tests-1'))
        self.assertEqual(first[2], tree(self.root / 'emit-main-1'))
        self.assertEqual(first[3], tree(self.root / 'emit-tests-1'))
        for path, raw in first[2].items():
            self.assertEqual(raw, self.main[path], path)
        for path, raw in first[3].items():
            self.assertEqual(raw, self.tests[path], path)
        with tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
            out, tests = Path(tmp) / 'main', Path(tmp) / 'tests'
            run_cli('tools/extract-upstream-home-core-refresh.py', '--output', str(out), '--test-output', str(tests))
            first_tree, first_tests = tree(out), tree(tests)
            self.assertEqual(self.main, first_tree)
            self.assertEqual(self.tests, first_tests)
            run_cli('tools/extract-upstream-home-core-refresh.py', '--output', str(out), '--test-output', str(tests), seed=5)
            self.assertEqual(first_tree, tree(out))
            self.assertEqual(first_tests, tree(tests))

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        nested = REPO / palette.ARCHIVE / 'child'
        try:
            for call in (palette.emit, palette.emit_tests):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    call(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                palette.validate_outputs(REPO, REPO)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                palette.validate_outputs(REPO, self.root / 'fine', nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                page.generate(REPO, nested)
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
            for name, raw in before.items():
                target = REPO / palette.ARCHIVE / name
                if not target.is_file() or target.read_bytes() != raw:
                    target.write_bytes(raw)
        completed = subprocess.run(
            [sys.executable, '-X', 'utf8', '-B', str(TOOLS / 'extract-upstream-home-core-refresh.py'),
             '--repo', str(REPO), '--output', str(self.root / 'wrapper-fine'),
             '--test-output', str(REPO / palette.ARCHIVE / 'tests')],
            cwd=str(REPO / 'desktop'), capture_output=True, check=False)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn('overlaps', completed.stderr.decode('utf-8', 'replace'))
        self.assertFalse((self.root / 'wrapper-fine').exists())
        self.assertFalse((REPO / palette.ARCHIVE / 'tests').exists())
        self.assertEqual(before, archive_files())

    def test_a_stale_regular_file_is_replaced_and_special_targets_fail_closed(self):
        for label, call, names, relative_map in (('main', palette.emit, palette.MAIN_FILES, MAIN_RELATIVE),
                                                 ('tests', palette.emit_tests, palette.TEST_FILES, TEST_RELATIVE)):
            expected = palette.main_bodies(REPO)[0] if label == 'main' else palette.test_bodies(REPO)[0]
            for name in names:
                relative = relative_map[name]
                with self.subTest(kind='stale', label=label, name=name), tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
                    root = Path(tmp)
                    before_archive = archive_files()
                    (root / 'outside.txt').write_bytes(b'OUTSIDE')
                    target = root / relative
                    target.parent.mkdir(parents=True)
                    (target.parent / 'decoy.kt').write_bytes(b'DECOY')
                    target.write_bytes(b'SENTINEL-FOREIGN')
                    call(REPO, root)
                    self.assertEqual(expected[name], target.read_bytes())
                    call(REPO, root)
                    self.assertEqual(expected[name], target.read_bytes())
                    self.assertTrue(stat.S_ISREG(os.lstat(target).st_mode))
                    self.assertEqual(b'OUTSIDE', (root / 'outside.txt').read_bytes())
                    self.assertEqual(b'DECOY', (target.parent / 'decoy.kt').read_bytes())
                    self.assertEqual(before_archive, archive_files())
            for kind in ('symlink', 'directory', 'fifo'):
                for name in names:
                    relative = relative_map[name]
                    with self.subTest(kind=kind, label=label, name=name), tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
                        if kind == 'fifo' and not hasattr(os, 'mkfifo'):
                            self.skipTest('os.mkfifo is unavailable')
                        root = Path(tmp)
                        before_archive = archive_files()
                        (root / 'outside.txt').write_bytes(b'OUTSIDE')
                        target = root / relative
                        target.parent.mkdir(parents=True)
                        (target.parent / 'decoy.kt').write_bytes(b'DECOY')
                        if kind == 'symlink':
                            neighbor = target.parent / 'neighbor.bin'
                            neighbor.write_bytes(b'NEIGHBOR')
                            try:
                                target.symlink_to(neighbor)
                            except OSError:
                                self.skipTest('symlink creation is not permitted')
                        elif kind == 'directory':
                            target.mkdir()
                            (target / 'nested.txt').write_bytes(b'NESTED')
                        else:
                            os.mkfifo(target)
                        before = frozen(root)
                        with self.assertRaises(ValueError) as caught:
                            call(REPO, root)
                        self.assertIn('not a regular file: ' + relative, str(caught.exception))
                        self.assertEqual(before, frozen(root))
                        self.assertEqual(before_archive, archive_files())

    def test_gradle_excludes_each_vendored_copy_once_and_wires_the_archive_into_the_home_task(self):
        build = self.build
        prepared = prepare_block(build)
        model = gate.parse_gradle(build)
        for name, path in CANONICAL.items():
            with self.subTest(name=name):
                line = 'exclude(canonicalOriginalIdentity("' + path + '"))'
                self.assertEqual(1, build.count(line), line)
                self.assertEqual(1, prepared.count(line), line)
                self.assertEqual(1, model.sync_excludes.count(path))
        block = task_block(build, 'extractOriginalHomePage')
        # The home task also reads the archive; the three producers below only call the generator's rewrite helper.
        for literal, total in (('inputs.file("tools/v046_identity_badge_palette.py")', 4),
                               ('inputs.dir("upstream-slices/v046-identity-badge-palette")', 1)):
            self.assertEqual(total, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'identity-badge-palette' in row])
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/home-page"))'))
        self.assertEqual(1, build.count('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-refresh-policy-tests")) }'))
        self.assertIn('"--test-output", layout.buildDirectory.dir("generated/original-refresh-policy-tests")', block)
        self.assertIn('"tools/extract-upstream-home-core-refresh.py"', block)
        full = task_block(build, 'extractUpstreamHomeFullCard')
        self.assertNotIn('v046_identity_badge_palette', full)
        self.assertNotIn('v046-identity-badge-palette', full)
        self.assertEqual(1, build.count('v046-identity-badge-palette'))
        # The generator is an input of the home task and of the three producers that call up_name_vip_pink.
        self.assertEqual(4, build.count('v046_identity_badge_palette'))
        for task in ('extractUpstreamDynamicFullCard', 'extractOriginalVideoDetailUnits', 'extractOriginalSpacePages'):
            with self.subTest(task=task):
                producer_block = task_block(build, task)
                self.assertEqual(1, producer_block.count('inputs.file("tools/v046_identity_badge_palette.py")'))
                self.assertNotIn('upstream-slices/v046-identity-badge-palette', producer_block)
        manifest = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        for path in CANONICAL.values():
            listed = [row for row in manifest['sources'] if row['path'] == path]
            self.assertEqual(1, len(listed), path)
            self.assertEqual('direct', listed[0]['mode'], path)

    def test_inventory_rows_and_catalog_pin_every_leaf(self):
        self.assertIn(palette.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(palette.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(palette.ARCHIVE + '/')]
        self.assertEqual(8, len(rows))
        self.assertEqual(sorted(palette.UPSTREAM_PATHS.values()), sorted(row['path'] for row in rows))
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual([FEATURE], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual('adaptedSource', row['kind'])
            self.assertEqual(palette.COMMIT, row['upstreamCommit'])
            self.assertEqual(sha(self.archive[Path(row['localPath']).name]), row['sha256'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == palette.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({palette.ARCHIVE + '/' + name for name in palette.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        loaded = sync.adapted_compatibility_inputs(REPO)
        self.assertEqual(rows, [row for row in loaded if row['localPath'].startswith(palette.ARCHIVE + '/')])

    def test_producer_hooks_validate_before_any_write_and_emit_in_the_owning_producers(self):
        producer = (TOOLS / 'extract-upstream-home-page.py').read_text(encoding='utf-8')
        start = producer.index('def generate(')
        refresh = producer.index('validate_refresh_ui_policy_outputs(repo, output)', start)
        validate_import = 'from v046_identity_badge_palette import validate_outputs as validate_identity_badge_palette_outputs'
        validate_at = producer.index(validate_import, start)
        check = producer.index('from v043_home_recommendation_badges import check_output', start)
        resolve_at = producer.index('output=Path(output).resolve()', start)
        self.assertEqual(1, producer.count(validate_import))
        self.assertEqual(1, producer.count('validate_identity_badge_palette_outputs(repo, output)'))
        self.assertLess(refresh, validate_at)
        self.assertLess(validate_at, check)
        self.assertLess(validate_at, resolve_at)
        unlink_at = producer.index('legacy_agreement.unlink()', start)
        emit_import = 'from v046_identity_badge_palette import emit as emit_identity_badge_palette'
        self.assertEqual(1, producer.count(emit_import))
        self.assertEqual(1, producer.count('emit_identity_badge_palette(repo, output)'))
        emit_at = producer.index(emit_import, start)
        call_at = producer.index('emit_identity_badge_palette(repo, output)', emit_at)
        cascade_at = producer.index('from v046_home_card_cascade_definers import emit as emit_home_card_cascade_definers', start)
        ret_at = producer.index('return written', call_at)
        self.assertLess(unlink_at, emit_at)
        self.assertLess(emit_at, call_at)
        self.assertLess(call_at, cascade_at)
        self.assertLess(cascade_at, ret_at)
        self.assertFalse(any(line.startswith(('import v046_identity_badge_palette', 'from v046_identity_badge_palette'))
                             for line in producer.splitlines()))
        wrapper = (TOOLS / 'extract-upstream-home-core-refresh.py').read_text(encoding='utf-8')
        validate_call = 'validate_identity_badge_palette_outputs(args.repo,args.output,args.test_output)'
        run_at = wrapper.index('result=subprocess.run(command)')
        extend_at = wrapper.index('extend_same_home_producer(args.output,args.test_output)')
        tests_import = 'from v046_identity_badge_palette import emit_tests as emit_identity_badge_palette_tests'
        tests_call = 'emit_identity_badge_palette_tests(args.repo,args.test_output)'
        self.assertEqual(1, wrapper.count(validate_call))
        self.assertEqual(1, wrapper.count(tests_import))
        self.assertEqual(1, wrapper.count(tests_call))
        self.assertLess(wrapper.index(validate_call), run_at)
        self.assertLess(run_at, extend_at)
        self.assertLess(extend_at, wrapper.index(tests_import))
        self.assertLess(wrapper.index(tests_import), wrapper.index(tests_call))
        self.assertLess(wrapper.index(tests_call), wrapper.index('return 0'))
        self.assertFalse(any(line.startswith(('import v046_identity_badge_palette', 'from v046_identity_badge_palette'))
                             for line in wrapper.splitlines()))

    def test_the_vendored_v025_copies_differ_and_are_the_only_other_static_declarers(self):
        old_tokens = {
            'UserUpBadge.kt': 'fontSp',
            'UserAvatarCornerMark.kt': 'VipAvatarPink',
            'UpBadgeNamePolicy.kt': 'Color(0xFFFB7299)',
            'OfficialVerifyBadgePolicy.kt': 'fontSize = 10.sp,',
            'AppBadge.kt': 'containerColor = containerColor,',
        }
        for name, token in old_tokens.items():
            with self.subTest(name=name):
                vendored = (REPO / CANONICAL[name]).read_bytes().replace(b'\r\n', b'\n').decode('utf-8')
                self.assertNotEqual(self.archive[name].decode('utf-8'), vendored)
                self.assertIn(token, vendored)
                self.assertNotIn(token, self.archive[name].decode('utf-8'))
        roots = ('app/src/main/java', 'design-system/src/main/java', 'core-data/src/main/java', 'core-player/src/main/java',
                 'network-core/src/main/java', 'settings-core/src/main/java', 'desktop/src', 'desktop/upstream-slices')
        hits = {}
        for relative in roots:
            base = REPO / relative
            for path in base.rglob('*.kt'):
                raw = path.read_bytes()
                if not any(name.encode('utf-8') in raw for name in SHARED_NAMES):
                    continue
                package, decls = declarations(raw.decode('utf-8'), path.name)
                for visibility, _kind, declared in decls:
                    if declared in SHARED_NAMES and visibility != 'private':
                        hits.setdefault(declared, set()).add(path.relative_to(REPO).as_posix())
        for declared in SHARED_NAMES:
            owner = next(name for name, rows in TOP_LEVEL.items() if any(row[2] == declared for row in rows))
            vendored = (REPO / CANONICAL[owner]).read_bytes().replace(b'\r\n', b'\n').decode('utf-8')
            expected = {palette.ARCHIVE + '/' + owner}
            # officialVerifyColor is new in v0.4.6; every other name also lives in the excluded v0.2.5 copy.
            if declared != 'officialVerifyColor':
                self.assertIn(declared, {row[2] for row in declarations(vendored, owner)[1]}, declared)
                expected.add(CANONICAL[owner])
            else:
                self.assertNotIn(declared, {row[2] for row in declarations(vendored, owner)[1]}, declared)
            self.assertEqual(expected, hits.get(declared), declared)

    def test_hand_written_regression_class_has_every_case_and_balanced_delimiters(self):
        path = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopIdentityBadgePaletteTest.kt'
        text = path.read_bytes().decode('utf-8')
        self.assertNotIn('\r', text)
        self.assertEqual(5, text.count('@Test'))
        self.assertEqual(5, len(re.findall(r'(?m)^    fun `', text)))
        self.assertEqual('com.bilipai.desktop.ui', declarations(text, path.name)[0])
        self.assertEqual({('public', 'class', 'DesktopIdentityBadgePaletteTest')}, declarations(text, path.name)[1])
        for forbidden in ('java.awt', 'ImageComposeScene', 'java.net', 'HttpURLConnection', 'NetworkModule', 'File('):
            self.assertNotIn(forbidden, text)
        self.balanced(text)
        for relative in MAIN_RELATIVE.values():
            self.balanced(self.main[relative].decode('utf-8'))
        for relative in TEST_RELATIVE.values():
            self.balanced(self.tests[relative].decode('utf-8'))

    def test_up_name_rows_are_pinned_and_match_the_v046_upstream_callers(self):
        self.assertEqual(sorted(UP_NAME_OWNERS), sorted(palette.UP_NAME_MIGRATIONS))
        for name, row in palette.UP_NAME_MIGRATIONS.items():
            with self.subTest(name=name):
                self.assertEqual(UP_NAME_OWNERS[name]['producer'], row['producer'])
                self.assertEqual(sha(row['before'].encode('utf-8')), row['beforeSHA256'])
                self.assertEqual(sha(row['after'].encode('utf-8')), row['afterSHA256'])
                self.assertTrue(row['before'].endswith('\n') and row['after'].endswith('\n'))
                self.assertEqual(row['before'].replace(palette.THEME_SECONDARY, palette.USER_VIP_PINK), row['after'])
                upstream = upstream_blob(UP_NAME_OWNERS[name]['upstream'])
                # The rewritten line is byte for byte the v0.4.6 line, and no v0.4.6 caller keeps the theme colour.
                self.assertEqual(1, upstream.count(row['after']))
                self.assertEqual(0, upstream.count(row['before']))
                calls = palette.up_name_calls(upstream)
                self.assertEqual(row['calls'], len(calls))
                expected = [palette.USER_VIP_PINK] if row['form'] == 'argument' else ['secondaryColor'] * row['calls']
                self.assertEqual(expected, [dict(arguments)['secondary'] for arguments in calls])
                for arguments in calls:
                    self.assertEqual(palette.UP_NAME_ARGUMENTS, [label for label, _value in arguments])
        manifest = json.loads(self.archive['manifest.json'])
        for token in ('DynamicCard', 'SpaceScreen', 'VideoInfoSection', 'UP_NAME_MIGRATIONS', 'not compiled'):
            self.assertIn(token, manifest['purpose'])

    def test_up_name_rewrite_fails_closed_when_the_pin_or_the_owner_text_drifts(self):
        def owner_text(name, line, calls=None, binding=None):
            row = palette.UP_NAME_MIGRATIONS[name]
            count = row['calls'] if calls is None else calls
            if row['form'] == 'argument':
                call = ('val c = resolveUpNameColor(\n    vipStatus = a,\n    vipType = b,\n    onSurface = c,\n'
                        + line + ')\n')
                return 'fun f() {\n' + call * count + '}\n'
            call = ('val c = resolveUpNameColor(\n    vipStatus = a,\n    vipType = b,\n    onSurface = c,\n'
                    '    secondary = ' + (binding or 'secondaryColor') + ',\n)\n')
            return 'fun f() {\n' + line + call * count + '}\n'

        for name, row in palette.UP_NAME_MIGRATIONS.items():
            with self.subTest(name=name):
                good = owner_text(name, row['before'])
                self.assertEqual((row['before'], row['after']), palette.up_name_vip_pink(name, good))
                bad_texts = [
                    owner_text(name, row['before'], calls=row['calls'] + 1),
                    owner_text(name, row['before'], calls=row['calls'] - 1),
                    owner_text(name, row['after']),
                    owner_text(name, ''),
                    good + row['before'],
                    good.replace('vipType', 'vipKind'),
                    good.replace('resolveUpNameColor(\n    vipStatus = a,', 'resolveUpNameColor(\n    a,', 1),
                    good.replace('onSurface = c,', 'onSurface = c, extra = d,', 1),
                ]
                if row['form'] == 'local':
                    bad_texts.append(owner_text(name, row['before'], binding='MaterialTheme.colorScheme.secondary'))
                    bad_texts.append(good + 'val again = secondaryColor\n')
                for bad in bad_texts:
                    with self.subTest(bad=bad[-70:]), self.assertRaises(ValueError):
                        palette.up_name_vip_pink(name, bad)
                for key in ('beforeSHA256', 'afterSHA256'):
                    changed = dict(row, **{key: '0' * 64})
                    with patch.dict(palette.UP_NAME_MIGRATIONS, {name: changed}), self.assertRaises(ValueError):
                        palette.up_name_vip_pink(name, good)
        with self.assertRaises(ValueError):
            palette.up_name_vip_pink('DynamicCommentSheet.kt', 'resolveUpNameColor(a = b)')

    def test_each_producer_holds_the_pinned_line_once_and_hooks_it_in_the_owning_producer(self):
        from v025_source_paths import canonical_source
        from v037_tab_roles import apply as apply_tab_roles
        from v041_video_status_labels import apply as apply_status_labels
        # The Space producer cannot run without Gradle outputs here, so its real input is rebuilt from the same two
        # stages the optical-dependency test uses; the universe class below checks its emitted line and receipt.
        path = UP_NAME_OWNERS['SpaceScreen.kt']['upstream']
        space = apply_tab_roles(path, apply_status_labels(
            path, canonical_source(REPO, path).read_text('utf-8').replace('\r\n', '\n')))
        row = palette.UP_NAME_MIGRATIONS['SpaceScreen.kt']
        self.assertEqual((row['before'], row['after']), palette.up_name_vip_pink('SpaceScreen.kt', space))
        for name, owner in UP_NAME_OWNERS.items():
            with self.subTest(name=name):
                producer = (TOOLS / owner['producer']).read_text(encoding='utf-8')
                self.assertEqual(1, producer.count('from v046_identity_badge_palette import up_name_vip_pink'))
                self.assertEqual(1, producer.count("up_name_vip_pink('" + name + "',"))
                self.assertEqual(1, producer.count('up_name_vip_pink('))
                self.assertFalse(any(line.startswith(('import v046_identity_badge_palette',
                                                      'from v046_identity_badge_palette'))
                                     for line in producer.splitlines()))

    def test_the_standalone_producers_change_exactly_the_pinned_line(self):
        for name in ('DynamicCard.kt', 'VideoInfoSection.kt'):
            owner, row = UP_NAME_OWNERS[name], palette.UP_NAME_MIGRATIONS[name]
            marker = row['before'][:-1] + ' // unmigrated\n'
            real, seen = palette.up_name_vip_pink, []

            def unmigrated(owner_name, text, real=real, seen=seen, marker=marker):
                before, _after = real(owner_name, text)
                seen.append(owner_name)
                return before, marker

            module = load_tool(owner['producer'])
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-ibp-') as tmp:
                migrated, plain = Path(tmp) / 'm', Path(tmp) / 'u'
                with contextlib.redirect_stdout(io.StringIO()):
                    module.generate(REPO, migrated)
                    with patch.object(palette, 'up_name_vip_pink', unmigrated):
                        module.generate(REPO, plain)
                self.assertEqual([name], seen)
                after, before = tree(migrated), tree(plain)
                self.assertEqual(set(after), set(before))
                differing = sorted(path for path, raw in after.items() if raw != before[path])
                self.assertIn(owner['output'], differing)
                self.assertEqual([], [path for path in differing if path != owner['output'] and not path.endswith('.json')])
                # The Home-style writers emit the platform line ending, so compare as LF.
                text = after[owner['output']].decode('utf-8').replace('\r\n', '\n')
                plain_text = before[owner['output']].decode('utf-8').replace('\r\n', '\n')
                self.assertEqual(1, text.count(row['after']))
                self.assertEqual(0, text.count(row['before']))
                self.assertEqual(1, plain_text.count(marker))
                self.assertEqual(text.replace(row['after'], row['before']), plain_text.replace(marker, row['before']))
                calls = palette.up_name_calls(text)
                self.assertEqual(row['calls'], len(calls))
                expected = [palette.USER_VIP_PINK] if row['form'] == 'argument' else ['secondaryColor'] * row['calls']
                self.assertEqual(expected, [dict(arguments)['secondary'] for arguments in calls])

    def balanced(self, source):
        stack = []
        for token, first, _end in sync.kotlin_tokens(source):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', ']', '}'):
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)


class V046IdentityBadgePaletteUniverseTest(unittest.TestCase):
    """What only the compiled universe shows: the arity of every caller and the colour each name caller passes.

    Cost: without a shared mirror it runs every producer the way Gradle does in a throw-away mirror (141 producers,
    about three minutes on Windows, one process at a time). The symbol gate (test_symbol_closure.py) already proves one
    definer per top-level name and resolvable imports, so none of that is repeated here. Point BP_IDENTITY_BADGE_MIRROR
    at the `r` directory of a mirror the gate kept (check_slice_symbol_closure.py --keep-scratch) to read that universe
    instead of running the producers again; a mirror built from other bytes of the five producers is refused.
    """

    @classmethod
    def setUpClass(cls):
        shared = os.environ.get(SHARED_MIRROR)
        if shared:
            root = Path(shared)
            for relative in ('desktop/build.gradle.kts', 'desktop/tools/v046_identity_badge_palette.py',
                             'desktop/tools/extract-upstream-home-page.py',
                             *('desktop/tools/' + owner['producer'] for owner in UP_NAME_OWNERS.values())):
                if not (root / relative).is_file() or (root / relative).read_bytes() != (REPO / relative).read_bytes():
                    raise AssertionError('The shared producer mirror is stale or incomplete: ' + relative)
            cls.model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
        else:
            scratch = tempfile.mkdtemp(prefix='bp-ibu-')
            cls.addClassCleanup(shutil.rmtree, scratch, True)
            root, env = gate.make_scratch_repo(REPO, scratch)
            cls.model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
            tasks, _skipped = gate.select_producers(cls.model)
            results = gate.run_producers(root, tasks, env)
            failed = [(row['task'], row['returncode'], row['stderrTail']) for row in results if row['returncode']]
            if failed:
                raise AssertionError(failed)
        compiled, _notes = gate.collect_sources(root, cls.model)
        cls.compiled = [source for source in compiled if source.set in ('main', 'test')]
        cls.root = root
        watched = set(DEPENDENCIES)
        cls.declared = {}
        cls.call_rows = []
        cls.up_name_rows = []
        names = set(CALLEES)
        for source in cls.compiled:
            raw = Path(source.real).read_bytes()
            if not source.logical.endswith('.kt'):
                continue
            if any(name.encode('utf-8') in raw for name in watched):
                text = raw.decode('utf-8')
                info = gate.parse_kotlin(text, source.logical, source.origin, source.set)
                for item in info.decls:
                    if item.name in watched and item.visibility != 'private':
                        cls.declared.setdefault(item.name, []).append((source.logical, info.package, item.visibility, item.kind, item.sig))
            if any(name.encode('utf-8') in raw for name in names):
                text = raw.decode('utf-8')
                for call in calls(text, names):
                    call['file'] = source.logical
                    cls.call_rows.append(call)
                if source.set == 'main':
                    lf_text = text.replace('\r\n', '\n')
                    for arguments in palette.up_name_calls(lf_text):
                        cls.up_name_rows.append((source.logical, lf_text, dict(arguments)['secondary']))

    def test_every_name_the_palette_and_suites_call_has_one_definer_with_the_right_arity(self):
        for name, (package, arity) in DEPENDENCIES.items():
            with self.subTest(name=name):
                found = self.declared.get(name, [])
                self.assertEqual(1, len(found), (name, found))
                self.assertEqual(package, found[0][1], name)
                self.assertEqual('fun', found[0][3], name)
        compiled_text = {source.logical: Path(source.real).read_bytes().decode('utf-8') for source in self.compiled
                         if source.logical.endswith(('ButtonVisualPolicy.kt', 'AppMaterial3Badge.kt', 'AppMiuixBadge.kt',
                                                     'UserVipBadge.kt', 'AppPrimitiveComponents.kt'))}
        self.assertEqual(5, len(compiled_text), sorted(compiled_text))
        for logical, text in compiled_text.items():
            if logical.endswith('ButtonVisualPolicy.kt'):
                self.assertEqual(['containerColor', 'contentColor', 'colorScheme'],
                                 [label for label, _ in parameters(text, 'resolveButtonContentColor')])
            elif logical.endswith('AppPrimitiveComponents.kt'):
                self.assertEqual(['tabLeftPx', 'tabWidthPx', 'viewportWidthPx', 'maxScrollPx'],
                                 [label for label, _ in parameters(text, 'resolveContentSizedTabScrollOffsetPx')])
            elif logical.endswith('UserVipBadge.kt'):
                self.assertEqual([], parameters(text, 'userVipPink'))
                self.assertEqual(['isDark'], [label for label, _ in parameters(text, 'resolveUserVipPink')])
            else:
                self.assertEqual(['modifier', 'containerColor', 'contentColor', 'content'],
                                 [label for label, _ in parameters(text, 'AppMaterial3Badge' if 'Material3' in logical else 'AppMiuixBadge')])
        self.assertEqual({'build/generated/home-page/com/android/purebilibili/core/ui/components/UserVipBadge.kt'},
                         {row[0] for row in self.declared['userVipPink']})
        self.assertEqual(['build/generated/components/com/android/purebilibili/core/ui/ButtonVisualPolicy.kt'],
                         [row[0] for row in self.declared['resolveButtonContentColor']])
        corner = palette.main_bodies(REPO)[0]['UserAvatarCornerMark.kt'].decode('utf-8')
        self.assertEqual(1, corner.count('import com.android.purebilibili.core.ui.components.userVipPink\n'))
        self.assertEqual(1, corner.count('.background(userVipPink())'))
        badge = palette.main_bodies(REPO)[0]['AppBadge.kt'].decode('utf-8')
        self.assertEqual(1, badge.count('com.android.purebilibili.core.ui.resolveButtonContentColor('))
        self.assertEqual(1, badge.count('androidx.compose.material3.contentColorFor('))

    def test_every_caller_of_the_five_files_matches_the_new_signatures(self):
        sources = palette.main_bodies(REPO)[0]
        params = {name: parameters(sources[owner].decode('utf-8'), name) for name, owner in CALLEES.items()}
        self.assertEqual([('vipStatus', False), ('vipType', False), ('onSurface', False), ('secondary', False)],
                         params['resolveUpNameColor'])
        self.assertEqual([('modifier', True), ('containerColor', True), ('contentColor', True)], params['UserUpBadge'])
        self.assertEqual([('modifier', True), ('containerColor', True), ('contentColor', True), ('content', True)],
                         params['AppBadge'])
        by_name = {}
        for call in self.call_rows:
            by_name.setdefault(call['name'], []).append(call)
        self.assertGreaterEqual(len(self.call_rows), 30)
        for name in ('AppBadge', 'UserUpBadge', 'UserAvatarCornerMarkBadge', 'OfficialVerifyBadge',
                     'OfficialVerifyAvatarBadge', 'resolveUpNameColor'):
            self.assertTrue(by_name.get(name), name)
        problems = []
        for call in self.call_rows:
            problem = callee_problem(call, params[call['name']])
            if problem:
                problems.append((call['file'], call['line'], call['name'], problem))
        self.assertEqual([], problems)

    def test_every_compiled_up_name_caller_passes_the_annual_vip_pink(self):
        # v0.4.6 resolveUpNameColor treats `secondary` as the annual-VIP colour; the theme secondary is no longer valid.
        by_file = {}
        for logical, text, secondary in self.up_name_rows:
            by_file.setdefault(logical, (text, []))[1].append(secondary)
        self.assertGreaterEqual(len(self.up_name_rows), 8)
        stale = []
        for logical, (text, secondaries) in by_file.items():
            for secondary in secondaries:
                local = re.fullmatch(r'\w+', secondary) and ('val ' + secondary + ' = ' + palette.USER_VIP_PINK + '\n') in text
                if secondary != palette.USER_VIP_PINK and not local:
                    stale.append((logical, secondary))
        self.assertEqual([], stale)
        for name, owner in UP_NAME_OWNERS.items():
            with self.subTest(name=name):
                row = palette.UP_NAME_MIGRATIONS[name]
                text, secondaries = by_file[owner['generated'] + owner['output']]
                self.assertEqual(row['calls'], len(secondaries))
                self.assertEqual(1, text.count(row['after']))
                self.assertEqual(0, text.count(row['before']))
        # The two producers that keep their own receipts record the rewrite and still invert to the original.
        replay = json.loads((self.root / 'desktop/build/generated/original-space-pages/source-transform-replay.json')
                            .read_text(encoding='utf-8'))
        screen = [file for file in replay['files'] if file['source'].endswith('/feature/space/SpaceScreen.kt')]
        self.assertEqual(1, len(screen))
        self.assertTrue(screen[0]['completeOriginalFileReconstructed'])
        space_row = palette.UP_NAME_MIGRATIONS['SpaceScreen.kt']
        self.assertEqual([space_row['before']], [step['original'] for step in screen[0]['transforms']
                                                  if step['adapted'] == space_row['after']])
        bindings = json.loads((self.root / 'desktop/build/generated/original-video-detail-units/source-bindings.json')
                              .read_text(encoding='utf-8'))
        video_row = palette.UP_NAME_MIGRATIONS['VideoInfoSection.kt']
        self.assertEqual([video_row['before']], [edit['before'] for edit in bindings['adaptations']
                                                  if edit['after'] == video_row['after']])

    def test_nothing_reads_the_removed_user_up_badge_font_size_or_the_old_vip_names_policy(self):
        for source in self.compiled:
            if not source.logical.endswith('.kt'):
                continue
            raw = Path(source.real).read_bytes()
            if b'UserUpBadgeVisualSpec' in raw or b'resolveUserUpBadgeVisualSpec' in raw:
                self.assertIn(source.logical, {
                    'build/generated/home-page/' + MAIN_RELATIVE['UserUpBadge.kt'],
                    'build/generated/original-refresh-policy-tests/' + TEST_RELATIVE['UpBadgeNamePolicyTest.kt'],
                }, source.logical)
            if source.logical.startswith('build/generated/home-page/') and source.logical.endswith('UserUpBadge.kt'):
                self.assertNotIn(b'fontSp', raw)
        old_pink = [source.logical for source in self.compiled
                    if source.logical.endswith('.kt') and b'Color(0xFFFB7299)' in Path(source.real).read_bytes()
                    and 'UpBadgeNamePolicy' in source.logical]
        self.assertEqual([], old_pink)


if __name__ == '__main__':
    unittest.main()
