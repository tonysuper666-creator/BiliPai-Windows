from pathlib import Path
import importlib.util
import re
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v040_chrome_actions as source
import v043_theme_chrome as chrome


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO/'desktop/tools'/filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def body(path):
    text = path.read_text('utf-8')
    return '\n'.join(text.splitlines()[2:])+'\n' if text.startswith('// GENERATED from ') else text


class V040ChromeActionsSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-chrome-action-owners-')
        cls.root = Path(cls.temp.name)
        cls.components = load('chrome_test_components', 'extract-upstream-components.py')
        cls.render = load('chrome_test_render', 'v037_player_render.py')
        cls.shared = load('chrome_test_shared', 'v037_player_shared_material.py')
        cls.components.generate(REPO, cls.root/'components', test_output=cls.root/'tests')
        cls.render.generate_render_sources(cls.root/'render')
        cls.shared.generate_decoration(cls.root/'shared')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_every_complete_original_main_unit_reaches_one_existing_owner(self):
        count = 0
        for path, raw in source.fixed_sources(REPO).items():
            if '/main/java/' not in path:
                continue
            rel = path.split('/main/java/', 1)[1]
            files = [p/rel for p in [self.root/'components', self.root/'render', self.root/'shared'] if (p/rel).is_file()]
            self.assertEqual(1, len(files), path)
            actual = body(files[0])
            if path in source.COMPONENT_ADAPTED_PATHS:
                expected = self.components.adapt(path, raw.decode('utf-8'), self.components.helper(REPO))
            elif path == source.RENDER_PATH:
                expected = raw.decode('utf-8')
                for edit in self.render.PRESS_EDITS:
                    self.assertEqual(1, expected.count(edit['before']))
                    expected = expected.replace(edit['before'], edit['after'], 1)
            else:
                expected = raw.decode('utf-8')
            if path in chrome.COMPONENTS:
                expected = chrome.component_source(REPO, path, expected)
            self.assertEqual(expected, actual, path)
            self.assertNotRegex(actual, r'(?m)^import android\.')
            count += 1
        self.assertEqual(17, count)

    def test_latest_press_keeps_whole_original_and_real_skia_boundary(self):
        original = source.fixed_sources(REPO)[source.RENDER_PATH].decode('utf-8')
        selected = original
        changes = []
        for edit in self.render.PRESS_EDITS:
            before, after = edit['before'], edit['after']
            self.assertEqual(1, selected.count(before))
            at = selected.index(before)
            changes.append((at, before, after))
            selected = selected[:at]+after+selected[at+len(before):]
        relative = source.RENDER_PATH.split('/main/java/', 1)[1]
        self.assertEqual(selected, body(self.root/'render'/relative))
        restored = selected
        for at, before, after in reversed(changes):
            self.assertEqual(after, restored[at:at+len(after)])
            restored = restored[:at]+before+restored[at+len(after):]
        self.assertEqual(original, restored)
        self.assertIn('RuntimeEffect.makeForShader(', selected)
        self.assertIn('frameShader.asComposeShader()', selected)
        self.assertIn('LocalAppThemeConfig.current', selected)
        text = body(self.root/'components/com/android/purebilibili/core/ui/components/AppText.kt')
        self.assertIn('LocalDesktopTextClipboard.current', text)
        self.assertIn('clipboard.copyText(text.trim())', text)
        self.assertNotIn('LocalContext.current', text)

    def test_all_original_policy_cases_are_emitted_byte_for_byte(self):
        cases = 0
        files = 0
        for path, raw in source.fixed_sources(REPO).items():
            if '/test/java/' not in path:
                continue
            target = self.root/'tests'/path.split('/test/java/', 1)[1]
            if target.name in chrome.COMPONENT_TESTS:
                raw = chrome.advance(REPO, target.name, raw)
            self.assertEqual(raw, target.read_bytes(), path)
            self.assertEqual(1, len(list((self.root/'tests').rglob(target.name))))
            cases += raw.count(b'@Test')
            files += 1
        self.assertEqual(20, cases)
        self.assertEqual(5, files)

    def test_build_excludes_old_direct_copies_and_tracks_both_real_producers(self):
        build = (REPO/'desktop/build.gradle.kts').read_text('utf-8')
        for path in source.COMPONENT_DIRECT_PATHS:
            self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("'+path+'"))'))
        self.assertEqual(1, build.count('kotlin.srcDir(generatedChromeActionTests)'))
        self.assertEqual(1, build.count('"--test-output", generatedChromeActionTests.get().asFile.absolutePath'))
        self.assertEqual(2, build.count('inputs.dir("upstream-slices/v040-chrome-actions")'))
        self.assertEqual(source.fixed_sources(REPO)[source.EXISTING_DECORATION_PATH],
            (self.root/'shared/com/android/purebilibili/core/ui/components/AppTopBarIconDecoration.kt').read_bytes())

    def test_actual_text_facade_has_its_complete_original_search_highlight_dependency(self):
        path = source.DS+'components/AppTextSearchHighlight.kt'
        original = source.fixed_sources(REPO)[path]
        target = self.root/'components'/path.split('/main/java/',1)[1]
        self.assertEqual(original, target.read_bytes())
        caller = body(self.root/'components/com/android/purebilibili/core/ui/components/AppText.kt')
        units = [p.read_text('utf-8') for p in (self.root/'components').rglob('*.kt')]
        for symbol, declaration in [('appTextSearchMatchRanges','fun appTextSearchMatchRanges('),
            ('LocalAppTextSearchQuery','val LocalAppTextSearchQuery ='),
            ('appSearchHighlightedText','fun appSearchHighlightedText(')]:
            self.assertIn(symbol, caller)
            self.assertEqual(1, sum(text.count(declaration) for text in units), symbol)

    def test_manifest_and_each_retained_original_fail_closed_on_modification(self):
        for name in ['manifest.json', *(r['filename'] for r in source.PINS)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                shutil.copytree(REPO/source.ARCHIVE, root/source.ARCHIVE)
                path = root/source.ARCHIVE/name
                path.write_bytes(path.read_bytes()+b'\n')
                with self.assertRaises(ValueError):
                    source.fixed_sources(root)


if __name__ == '__main__':
    unittest.main()
