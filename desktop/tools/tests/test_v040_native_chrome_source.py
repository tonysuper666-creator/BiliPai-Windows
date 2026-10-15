from pathlib import Path
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v040_native_chrome as source
import v040_liquid_value_controls as controls
from v025_source_paths import canonical_source


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO/'desktop/tools'/filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def body(path):
    text = path.read_text('utf-8')
    lines = text.splitlines()
    if lines[0].startswith('// GENERATED from ') or lines[0].startswith('// Original source '):
        return '\n'.join(lines[2:])+'\n'
    if lines[0].startswith('// OriginalSource: '):
        return '\n'.join(lines[3:] if lines[2].startswith('// OriginalCommit: ') else lines[2:])+'\n'
    return text


class V040NativeChromeSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-native-chrome-owners-')
        cls.root = Path(cls.temp.name)
        cls.components = load('native_test_components', 'extract-upstream-components.py')
        cls.detail = load('native_test_detail', 'extract-upstream-dynamic-detail-container.py')
        cls.shared = load('native_test_shared', 'extract-upstream-shared-liquid-tabs.py')
        cls.components.generate(REPO, cls.root/'components', test_output=cls.root/'tests')
        cls.detail.generate(REPO, cls.root/'detail')
        cls.shared.generate(REPO, cls.root/'shared')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_original_chrome_units_use_the_existing_owners_and_width_binding(self):
        fixed = source.fixed_sources(REPO)
        expected, proof = source.adapt_adaptive(REPO)
        actual = body(self.root/'detail/com/android/purebilibili/core/ui/DesktopOriginalDetailAdaptiveChrome.kt')
        self.assertEqual(expected, actual)
        restored = actual
        for edit in reversed(proof['edits']):
            at, after = edit['offset'], edit['after']
            self.assertEqual(after, restored[at:at+len(after)])
            restored = restored[:at]+edit['before']+restored[at+len(after):]
        self.assertEqual(fixed[source.ADAPTIVE].decode('utf-8'), restored)
        self.assertIn('LocalWindowSizeClass.current.widthDp < 600.dp', actual)
        self.assertEqual(1, actual.count('AppChromeOverflowHost(modifier = modifier)'))
        self.assertEqual(2, actual.count('AppChromeOverflowSlot {'))
        for path in source.NEW_COMPONENT_PATHS:
            target = self.root/'components'/path.split('/main/java/', 1)[1]
            self.assertEqual(fixed[path], target.read_bytes(), path)
        for name in ['AdaptiveScaffoldPolicy.kt', 'TopReadabilityChromePolicy.kt']:
            path = source.DS+name
            self.assertEqual(fixed[path], canonical_source(REPO, path).read_bytes().replace(b'\r\n', b'\n'))
        inventory = json.loads((self.root/'shared/source-inventory.json').read_bytes())
        rows = {row['path']: row for row in inventory['sources']}
        for path in source.SHARED_PATHS:
            self.assertEqual(source.COMMIT, rows[path]['sourceIdentity']['pinnedCommit'])
            self.assertEqual(source.identity_for(REPO, path), rows[path]['sourceIdentity'])

    def test_home_lens_and_capability_probe_use_the_same_unchanged_shared_programs(self):
        fixed = source.fixed_sources(REPO)
        path = source.APP+'feature/home/components/liquid/Lens.kt'
        actual = body(self.root/'shared/generated'/path.split('/main/java/', 1)[1])
        self.assertEqual(fixed[path].decode('utf-8'), actual)
        self.assertIn('core.ui.effect.lens as sharedLens', actual)
        self.assertNotIn('uniform shader content', actual)
        path = controls.DS+'effect/LiquidLens.kt'
        raw = controls.fixed_sources(REPO)[path]
        generated = (self.root/'components'/path.split('/main/java/', 1)[1]).read_text('utf-8')
        self.assertEqual(controls.adapt(path, raw)[0], generated)
        self.assertEqual(re.findall(r'"""(.*?)"""', raw.decode('utf-8'), re.S), re.findall(r'"""(.*?)"""', generated, re.S))
        self.assertIn('withDesktopWindowsGlassUniformCache {', generated)
        self.assertIn('setFloatUniform("chromaticEdgeBand"', generated)
        host = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsLiquidGlassMaterial.kt').read_text('utf-8')
        for name in ['ROUNDED_RECT_REFRACTION_SHADER', 'ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER']:
            self.assertIn('import com.android.purebilibili.core.ui.effect.'+name, host)
            self.assertIn('RuntimeEffect.makeForShader('+name+').close()', host)

    def test_all_original_geometry_and_chrome_policy_cases_are_preserved(self):
        total = 0
        for path, raw in source.fixed_sources(REPO).items():
            if '/test/java/' not in path:
                continue
            target = self.root/'tests'/path.split('/test/java/', 1)[1]
            self.assertEqual(raw, target.read_bytes(), path)
            self.assertEqual(1, len(list((self.root/'tests').rglob(target.name))))
            total += raw.count(b'@Test')
        self.assertEqual(37, total)

    def test_actual_root_host_and_every_consuming_build_task_use_the_original_decorator(self):
        host = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsLiquidGlassMaterial.kt').read_text('utf-8')
        self.assertIn('LocalAppTopBarIconDecorator provides\n                com.android.purebilibili.core.ui.components.BiliPaiTopBarIconDecorator', host)
        build = (REPO/'desktop/build.gradle.kts').read_text('utf-8')
        self.assertEqual(4, build.count('inputs.file("tools/v040_native_chrome.py")'))
        self.assertEqual(4, build.count('inputs.dir("upstream-slices/v040-native-chrome")'))
        source_file = self.root/'components/com/android/purebilibili/core/ui/components/BiliPaiTopBarIconDecorator.kt'
        self.assertIn('BottomBarMatchedReusableLiquidDock(', source_file.read_text('utf-8'))
        self.assertIn('backdrop = LocalAppTopBarIconBackdrop.current', source_file.read_text('utf-8'))

    def test_modified_manifests_and_each_original_fail_closed(self):
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
