"""v0.4.6 UserVipBadge.kt replaces the pinned home-page policy-extract output."""
from pathlib import Path
import contextlib
import importlib.util
import io
import json
import re
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_identity_badges as badges

PINK_PACKAGE = 'com.android.purebilibili.core.ui.components'
PINK_FQCN = PINK_PACKAGE+'.'
DECLS = {
    'resolveUserVipPink': re.compile(r'\bfun\s+resolveUserVipPink\s*\('),
    'userVipPink': re.compile(r'\bfun\s+userVipPink\s*\('),
    'resolveUserVipBadgeLabel': re.compile(r'\bfun\s+resolveUserVipBadgeLabel\s*\('),
    'UserVipBadge': re.compile(r'\bfun\s+UserVipBadge\s*\('),
    'resolveVipBadgeColors': re.compile(r'\bfun\s+resolveVipBadgeColors\s*\('),
    'AccessibleVipBadgeColors': re.compile(r'\bclass\s+AccessibleVipBadgeColors\b'),
}
CALL = re.compile(r'userVipPink\s*\(')
DIRECT_DEFINERS = {
    'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSurface.kt': 'direct',
    'design-system/src/main/java/com/android/purebilibili/core/ui/AppShapes.kt': 'direct',
    'design-system/src/main/java/com/android/purebilibili/core/theme/ThemeContrastPolicy.kt': 'direct',
    'design-tokens/src/main/java/com/android/purebilibili/core/ui/AppShapeTokens.kt': 'direct',
    'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppText.kt': 'platform-adapter-reference',
    'design-system/src/main/java/com/android/purebilibili/core/ui/components/UserVipBadge.kt': 'policy-extract',
}


def load(name):
    spec = importlib.util.spec_from_file_location('identity_badges_'+name.replace('-', '_').replace('.py', ''), TOOLS/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def quiet(function, *args):
    with contextlib.redirect_stdout(io.StringIO()):
        return function(*args)


def files_under(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob('*') if p.is_file()}


def kotlin_under(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob('*.kt') if p.is_file()}


def package_name(text):
    match = re.search(r'(?m)^package\s+(\S+)', text)
    return match.group(1) if match else ''


def imports_user_vip_pink(text):
    for raw in text.splitlines():
        line = raw.split('//', 1)[0].strip()
        if line in ('import '+PINK_PACKAGE+'.userVipPink', 'import '+PINK_PACKAGE+'.*'):
            return True
        if line.startswith('import '+PINK_PACKAGE+'.userVipPink as '):
            return True
    return False


def call_sites(text):
    sites = []
    for match in CALL.finditer(text):
        index = match.start()
        window = text[max(0, index-40):index]
        if re.search(r'\bfun\s+$', window) or window.endswith('resolve'):
            continue
        sites.append(index)
    return sites


def call_resolves(text, index):
    if text[max(0, index-len(PINK_FQCN)):index] == PINK_FQCN:
        return True
    if package_name(text) == PINK_PACKAGE:
        return True
    return imports_user_vip_pink(text)


class OriginalIdentityBadgeSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-vip-')
        cls.output = Path(cls.temp.name)
        cls.page = load('extract-upstream-home-page.py')
        cls.sources, cls.manifest = badges.fixed(REPO)
        quiet(cls.page.generate, REPO, cls.output/'home')
        with patch('v046_identity_badges.advance', side_effect=lambda repo, before: (before, None)):
            quiet(cls.page.generate, REPO, cls.output/'before')
        cls.current = (cls.output/'home'/badges.OUTPUT_RELATIVE).read_text(encoding='utf8')
        cls.previous = (cls.output/'before'/badges.OUTPUT_RELATIVE).read_text(encoding='utf8')
        cls.proof = json.loads((cls.output/'home'/'v046-identity-badges-proof.json').read_text(encoding='utf8'))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_archive_license_and_manifest_reject_tampered_or_extra_leaves(self):
        self.assertEqual(badges.COMMIT, self.manifest['commit'])
        self.assertEqual(self.sources['UserVipBadge.kt'], (REPO/badges.ARCHIVE/'UserVipBadge.kt').read_text(encoding='utf8'))
        self.assertEqual('ad4304d88607d708e0f958c636d1392d210d390c', self.manifest['files'][0]['gitBlob'])
        for name in [*badges.EXPECTED, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-vip-t-') as temp:
                root = Path(temp)/badges.ARCHIVE
                shutil.copytree(REPO/badges.ARCHIVE, root)
                file = root/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    badges.fixed(Path(temp))

    def test_emitted_file_keeps_producer_header_and_restores_original_and_predecessor(self):
        self.assertTrue(self.current.startswith(badges.HEADER))
        self.assertEqual(self.sources['UserVipBadge.kt'], self.current[len(badges.HEADER):])
        self.assertEqual(self.sources['UserVipBadge.kt'], badges.restore(self.current, self.proof['originalToWindows']))
        self.assertEqual(self.previous, badges.restore(self.current, self.proof['retainedToCurrent']))
        self.assertEqual(self.manifest['beforeFinalOutputs'][badges.OUTPUT_RELATIVE]['sha256'], badges.sha(self.previous))
        self.assertTrue(self.proof['completeOriginalFile'])
        self.assertTrue(self.proof['producerHeaderRetained'])
        self.assertNotIn(b'\r', self.current.encode('utf8'))
        self.assertNotIn('AppSurfaceTokens', self.current)
        self.assertNotIn('remember', self.current)
        self.assertIn('import androidx.compose.ui.graphics.luminance\n', self.current)
        self.assertIn('fun resolveUserVipPink(isDark: Boolean): Color =\n    if (isDark) Color(0xFFD44E7D) else Color(0xFFFF6699)', self.current)
        self.assertIn('color = vipPink', self.current)
        self.assertIn('contentColor = Color.White', self.current)
        self.assertEqual(1, self.current.count('fun userVipPink('))
        self.assertEqual(1, self.current.count('fun resolveUserVipPink('))
        self.assertEqual('fun UserVipBadge(\n    label: String = "大会员",\n    modifier: Modifier = Modifier,\n    fontSize: TextUnit = MaterialTheme.typography.labelSmall.fontSize,\n    compact: Boolean = false,\n)',
                         self.current[self.current.index('fun UserVipBadge('):self.current.index('fun UserVipBadge(')+self.current[self.current.index('fun UserVipBadge('):].index(') {')+1])

    def test_layer_changes_only_user_vip_badge_and_predecessor_drift_fails_closed(self):
        before = kotlin_under(self.output/'before')
        after = kotlin_under(self.output/'home')
        diff = sorted(path for path in set(before) | set(after) if before.get(path) != after.get(path))
        self.assertEqual([badges.OUTPUT_RELATIVE], diff)
        self.assertFalse((self.output/'before'/'v046-identity-badges-proof.json').exists())
        self.assertTrue((self.output/'home'/'v046-identity-badges-proof.json').is_file())
        advanced, proof = badges.advance(REPO, self.previous)
        self.assertEqual(self.current, advanced)
        self.assertEqual(self.sources['UserVipBadge.kt'], badges.restore(advanced, proof['originalToWindows']))
        for bad in (self.current, self.previous+'\n', self.previous.replace('package ', 'pakage ', 1), ''):
            with self.subTest(bad=bad[:40]), self.assertRaisesRegex(ValueError, 'predecessor changed'):
                badges.advance(REPO, bad)

    def test_real_producer_repeats_and_overlap_rejection_preserves_raw_leaves(self):
        original = {p.name: p.read_bytes() for p in (REPO/badges.ARCHIVE).iterdir()}
        snapshot = files_under(self.output/'home')
        quiet(self.page.generate, REPO, self.output/'home')
        self.assertEqual(snapshot, files_under(self.output/'home'))
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.page.generate(REPO, REPO/badges.ARCHIVE/'child')
        self.assertEqual(original, {p.name: p.read_bytes() for p in (REPO/badges.ARCHIVE).iterdir()})
        self.assertFalse((REPO/badges.ARCHIVE/'child').exists())

    def test_gradle_task_and_catalog_track_the_archive_without_an_exclude(self):
        sync = load('sync-upstream.py')
        rows = [row for row in sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(badges.ARCHIVE+'/')]
        self.assertEqual([badges.ARCHIVE+'/UserVipBadge.kt'], [row['localPath'] for row in rows])
        self.assertEqual(['v046-identity-badges'], rows[0]['features'])
        self.assertFalse(rows[0]['referenceOnly'])
        self.assertFalse(rows[0]['partialReuse'])
        self.assertIn('userVipPink', rows[0]['usedScope'])
        catalog = json.loads((TOOLS/'compatibility-source-inputs.json').read_bytes())
        self.assertIn(badges.ARCHIVE, catalog['buildInputRoots'])
        archives = [row for row in catalog['archives'] if row['directory'] == badges.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual([badges.ARCHIVE+'/LICENSE', badges.ARCHIVE+'/UserVipBadge.kt', badges.ARCHIVE+'/manifest.json'],
                         sorted(leaf['path'] for leaf in archives[0]['files']))
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        at = build.index('val extractOriginalHomePage by tasks.registering(')
        block = build[at:build.index('\n}', at)]
        self.assertEqual(1, block.count('inputs.file("tools/v046_identity_badges.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-identity-badges")'))
        self.assertNotIn('exclude(', block)
        full = build.index('val extractUpstreamHomeFullCard by tasks.registering(')
        full_block = build[full:build.index('\n}', full)]
        self.assertNotIn('v046_identity_badges', full_block)
        self.assertNotIn('v046-identity-badges', full_block)
        producer = (TOOLS/'extract-upstream-home-page.py').read_text(encoding='utf8')
        self.assertLess(producer.index('def generate('), producer.index('from v046_identity_badges import validate_outputs'))
        self.assertEqual(1, producer.count('from v046_identity_badges import advance'))
        self.assertEqual(1, producer.count('v046-identity-badges-proof.json'))

    def test_pink_definers_are_unique_and_every_emitted_call_resolves_to_the_components_package(self):
        # extract-upstream-space-pages.py reads two Gradle-generated owners that only exist after the desktop build has run
        owners = [REPO/'desktop/build/generated/space-overview/com/android/purebilibili/feature/space/DesktopOriginalSpaceOverview.kt',
                  REPO/'desktop/build/generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt']
        if not all(owner.is_file() for owner in owners):
            self.skipTest('needs the Gradle-generated Space owners; run the desktop build first')
        generated = {'home': self.output/'home'}
        for label, filename in (
            ('reply', 'extract-upstream-dynamic-reply.py'),
            ('search', 'extract-upstream-video-comment-ui.py'),
            ('space', 'extract-upstream-space-pages.py'),
            ('editor', 'extract-upstream-dynamic-editor.py'),
            ('overview', 'extract-space-overview.py'),
            ('components', 'extract-upstream-components.py'),
        ):
            module = load(filename)
            target = self.output/label
            quiet(module.generate, REPO, target)
            generated[label] = target
        texts = {}
        for label, root in generated.items():
            for path in root.rglob('*.kt'):
                texts['generated/'+label+'/'+path.relative_to(root).as_posix()] = path.read_text(encoding='utf8')
        for path in (REPO/'desktop/src').rglob('*.kt'):
            texts['desktop/'+path.relative_to(REPO).as_posix()] = path.read_text(encoding='utf8')
        catalog = json.loads((REPO/'desktop/upstream-sources.json').read_bytes())
        modes = {row['path']: row['mode'] for row in catalog['sources']}
        for path, mode in DIRECT_DEFINERS.items():
            self.assertEqual(mode, modes[path], path)
        for row in catalog['sources']:
            if row.get('mode') != 'direct' or not str(row['path']).endswith('.kt'):
                continue
            texts['direct/'+row['path']] = (REPO/row['path']).read_text(encoding='utf8')
        owners = {name: [] for name in DECLS}
        for path, text in texts.items():
            for name, pattern in DECLS.items():
                found = len(pattern.findall(text))
                if found:
                    owners[name].append((path, found))
        for name, hits in owners.items():
            self.assertEqual([( 'generated/home/'+badges.OUTPUT_RELATIVE, 1)], hits, name)
        unresolved = []
        resolved = []
        for path, text in texts.items():
            if not path.startswith('generated/') and not path.startswith('desktop/'):
                continue
            for index in call_sites(text):
                item = (path, text[max(0, index-80):index+20].replace('\n', ' '))
                if call_resolves(text, index):
                    resolved.append(item)
                else:
                    unresolved.append(item)
        self.assertEqual([], unresolved)
        by_name = {}
        for path, _snippet in resolved:
            by_name.setdefault(Path(path).name, 0)
            by_name[Path(path).name] += 1
        self.assertEqual(1, by_name.get('UserVipBadge.kt'))
        self.assertEqual(2, by_name.get('DesktopOriginalReplyComponents.kt'))
        self.assertEqual(1, by_name.get('DesktopOriginalSubReplyDetailComponents.kt'))
        self.assertEqual(1, by_name.get('CommentSearchSheet.kt'))
        self.assertGreaterEqual(sum(by_name.values()), 5)
        for path, text in texts.items():
            if Path(path).name not in ('UserVipBadge.kt', 'DesktopOriginalReplyComponents.kt',
                                       'DesktopOriginalSubReplyDetailComponents.kt', 'CommentSearchSheet.kt'):
                continue
            if 'desktop/' in path:
                continue
            for index in call_sites(text):
                same_package = package_name(text) == PINK_PACKAGE
                fqcn = text[max(0, index-len(PINK_FQCN)):index] == PINK_FQCN
                self.assertTrue(same_package or fqcn, path)

    def test_called_simple_names_have_definers_and_the_badge_file_stays_balanced(self):
        surface = (REPO/'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSurface.kt').read_text(encoding='utf8')
        shapes = (REPO/'design-system/src/main/java/com/android/purebilibili/core/ui/AppShapes.kt').read_text(encoding='utf8')
        theme = (REPO/'design-system/src/main/java/com/android/purebilibili/core/theme/ThemeContrastPolicy.kt').read_text(encoding='utf8')
        tokens = (REPO/'design-tokens/src/main/java/com/android/purebilibili/core/ui/AppShapeTokens.kt').read_text(encoding='utf8')
        app_text = (REPO/'design-system/src/main/java/com/android/purebilibili/core/ui/components/AppText.kt').read_text(encoding='utf8')
        self.assertIn('fun AppSurface(', surface)
        self.assertIn('object AppShapes', shapes)
        self.assertIn('enum class ContainerLevel', tokens)
        self.assertIn('const val ACCESSIBLE_TEXT_MIN_CONTRAST', theme)
        self.assertIn('fun resolveAccessibleContainerColors(', theme)
        self.assertGreaterEqual(app_text.count('fun AppText('), 1)
        self.assertIn('components/AppText.kt', (TOOLS/'extract-upstream-components.py').read_text(encoding='utf8'))
        for token in ('AppSurface(', 'AppText(', 'AppShapes.container(ContainerLevel.Pill)',
                      'resolveAccessibleContainerColors(', 'ACCESSIBLE_TEXT_MIN_CONTRAST'):
            self.assertIn(token, self.current)
        syntax = load('sync-upstream.py')
        stack = []
        for token, first, _last in syntax.kotlin_tokens(self.current):
            if token in '([{':
                stack.append(token)
            elif token in ')]}':
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)
        self.assertNotIn('desktopUserVipPink', self.current)
        self.assertFalse((REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopUserVipPink.kt').exists())
        self.assertFalse((REPO/'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopUserVipPinkTest.kt').exists())


if __name__ == '__main__':
    unittest.main()
