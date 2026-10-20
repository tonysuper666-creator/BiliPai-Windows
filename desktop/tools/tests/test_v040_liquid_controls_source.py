from pathlib import Path
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v040_liquid_value_controls as source
import v043_theme_chrome as chrome
import v046_theme_tokens as theme_tokens
import v046_theme_palette_schedule as palette_schedule


def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,REPO/'desktop/tools'/filename)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


class V040LiquidControlsSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bilipai-liquid-actual-owners-')
        cls.root=Path(cls.temp.name)
        cls.components=module('liquid_actual_components','extract-upstream-components.py')
        cls.appearance=module('liquid_actual_appearance','extract-appearance-platform.py')
        cls.foundation=module('liquid_actual_foundations','v037_player_foundations.py')
        cls.progress=module('liquid_actual_progress','v037_shared_tabs_progress.py')
        cls.components.generate(REPO,cls.root/'components')
        cls.appearance.generate(REPO,cls.root/'appearance',resource_output=cls.root/'resources',
            policy_only=True,test_output=cls.root/'tests')
        cls.records=[]
        cls.foundation.emit_foundations(cls.root/'controls',cls.records)
        cls.progress.emit_control_foundations(cls.root/'controls',cls.records)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_actual_existing_producers_emit_every_fixed_unit_once_with_correct_identity(self):
        fixed=source.fixed_sources(REPO)
        checked=0
        for path,raw in fixed.items():
            if '/main/java/' not in path or path==source.SCREEN:continue
            relative=path.split('/main/java/',1)[1]
            files=[p/relative for p in [self.root/'components',self.root/'appearance',self.root/'controls'] if (p/relative).is_file()]
            self.assertEqual(1,len(files),path)
            body=files[0].read_text(encoding='utf-8')
            if body.startswith('// GENERATED from '):body='\n'.join(body.splitlines()[2:])+'\n'
            from v042_optical_dependencies import selected_source, MOTION
            effective_raw = selected_source(REPO,path,raw.decode('utf8')).encode('utf8') if path == MOTION else raw
            if path in chrome.COMPONENTS:
                effective_raw = chrome.advance(REPO, Path(path).name, effective_raw)
            expected=self.foundation.adapt_foundation(path,effective_raw).decode('utf-8') if path.endswith('/AppColorPicker.kt') else source.adapt(path,effective_raw)[0]
            self.assertEqual(expected,body,path)
            checked+=1
        self.assertEqual(16,checked)
        # This existing shared owner already emits the exact latest semantic palette.
        # Its neutralAction/link/disabled methods satisfy the complete original action colors.
        from v038_danmaku_merge import content_colors_source
        semantic_palette, _ = content_colors_source(REPO)
        self.assertEqual('755543c78bd3ba7c2685362e1c98e77daefefb28a7e7ed6e338c4644b1d4e90f',
            source.sha(semantic_palette.encode('utf-8')))
        for leaf in ['AppThemeConfig.kt','AppColorPicker.kt','AppLiquidValueControlPolicy.kt']:
            records=[r for r in self.records if r['path'].endswith('/'+leaf)]
            self.assertEqual(1,len(records),leaf)
            from v042_search_dependencies import COMMIT as current_commit, MANIFEST_SHA256 as current_manifest
            self.assertEqual(current_commit if leaf == 'AppThemeConfig.kt' else source.COMMIT,records[0]['fixedCommit'])
            self.assertEqual(current_manifest if leaf == 'AppThemeConfig.kt' else source.MANIFEST_SHA256,records[0]['sourceManifestSHA256'])
        build=(REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for path in list(source.NEW_COMPONENT_DIRECT_PATHS)+[source.DS+'MatchedLiquidIndicatorGeometry.kt']:
            self.assertEqual(1,build.count('exclude(canonicalOriginalIdentity("'+path+'"))'))

    def test_platform_boundaries_recover_whole_originals_and_keep_real_windows_owners(self):
        fixed=source.fixed_sources(REPO)
        for path in [source.DS+'components/'+n+'.kt' for n in ['AppSlider','AppLiquidValueControlPolicy','AppLiquidValueControls']]+[source.PICKER]:
            selected,proof=source.adapt(path,fixed[path]);restored=selected
            for edit in reversed(proof['edits']):
                at,after=edit['offset'],edit['after'];self.assertEqual(after,restored[at:at+len(after)])
                restored=restored[:at]+edit['before']+restored[at+len(after):]
            self.assertEqual(fixed[path].decode('utf-8'),restored,path)
            self.assertNotRegex(selected,r'(?m)^import android\.')
        picker,_=source.adapt(source.PICKER,fixed[source.PICKER])
        self.assertIn('DesktopWindowConfiguration as LocalConfiguration',picker)
        self.assertIn('LocalDesktopDarkTheme provides previewDark',picker)
        self.assertIn('desktopDetailRenderEffectsSupported()',picker)
        self.assertIn('java.awt.Color.RGBtoHSB',picker)

    def test_color_resources_follow_existing_effective_theme_and_keep_each_original_argb(self):
        colors=source.resource_colors(REPO)
        actual=(self.root/'components/com/android/purebilibili/core/ui/components/DesktopLiquidValueColorResources.kt').read_text(encoding='utf-8')
        self.assertIn('isDesktopInDarkTheme()',actual)
        for mode in colors.values():
            for name,argb in mode.items():self.assertIn('"'+name+'" -> Color(0x'+argb[1:]+')',actual)
        self.assertNotEqual(colors[False]['liquid_value_inactive'],colors[True]['liquid_value_inactive'])
        owner=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopAppearanceSettings.kt').read_text(encoding='utf-8')
        self.assertEqual(2,owner.count('ThemeCustomColorPickerDialog('))
        self.assertIn('prefs.setCustomColor(selected)',owner)
        self.assertIn('draft = target.write(draft, selected)',owner)
        self.assertIn('prefs.setCustomColor(custom)',owner)
        self.assertIn('onSave(draft)',owner)

    def test_original_motion_geometry_and_picker_cases_are_preserved_as_executable_test_sources(self):
        fixed=source.fixed_sources(REPO)
        cases=0
        for path,raw in fixed.items():
            if '/test/java/' not in path:continue
            target=self.root/'tests'/path.split('/test/java/',1)[1]
            self.assertEqual(raw,target.read_bytes(),path)
            cases+=raw.count(b'@Test')
        self.assertEqual(10,cases)
        seed_proof=json.loads((self.root/'appearance/v042-seed-palette-proof.json').read_bytes())
        seed_names={row['filename'] for row in seed_proof['wholeOriginalTestProofs']}
        self.assertEqual({
            'PaletteSpecPolicyTest.kt','SeedColorSchemeTest.kt','SeedTextContrastPolicyTest.kt',
            'ThemePresetPalettePreviewTest.kt','AppThemeEndpointPolicyTest.kt',
            'WallpaperPaletteOverridePolicyTest.kt','LiquidAccentColorSchemeTest.kt',
            'SeedPaletteSettingsPolicyTest.kt','MiuixThemeBridgePolicyTest.kt',
            'CustomSelectionContentColorTest.kt',
        },seed_names)
        all_tests=list((self.root/'tests').rglob('*.kt'))
        seed_tests=[p for p in all_tests if p.name in seed_names]
        self.assertEqual(10,len(seed_tests))
        self.assertEqual(46,sum(p.read_bytes().count(b'@Test') for p in seed_tests))
        # The v046-theme-tokens and v046-theme-palette-schedule original tests are counted by those slices' own tests.
        self.assertEqual(27,sum(p.read_bytes().count(b'@Test') for p in all_tests
                                if p.name not in seed_names and p.name not in theme_tokens.TEST_FILES
                                and p.name not in palette_schedule.TEST_FILES))
        velocity=(self.root/'components/com/android/purebilibili/core/ui/animation/LiquidIndicatorVelocity.kt').read_bytes()
        self.assertEqual(fixed[source.DS+'animation/LiquidIndicatorVelocity.kt'],velocity)

    def test_refraction_dependency_reaches_the_actual_pinned_windows_skia_backend(self):
        path = source.DS+'effect/LiquidLens.kt'
        original = source.fixed_sources(REPO)[path]
        target = self.root/'components'/path.split('/main/java/',1)[1]
        selected, proof = source.adapt(path, original)
        self.assertEqual(selected.encode(), target.read_bytes())
        self.assertEqual(5, len(proof['edits']))
        restored = selected
        for edit in reversed(proof['edits']):
            at, after = edit['offset'], edit['after']
            self.assertEqual(after, restored[at:at+len(after)])
            restored = restored[:at]+edit['before']+restored[at+len(after):]
        self.assertEqual(original.decode('utf-8'), restored)
        self.assertEqual(re.findall(r'"""(.*?)"""', original.decode('utf-8'), re.S),
            re.findall(r'"""(.*?)"""', selected, re.S))
        self.assertIn('withDesktopWindowsGlassUniformCache {', selected)
        caller = (self.root/'components/com/android/purebilibili/core/ui/components/AppLiquidValueControls.kt').read_text('utf-8')
        self.assertIn('import com.android.purebilibili.core.ui.effect.lens', caller)
        self.assertIn('lens(', caller)
        self.assertIn('uniformShaderName = "content"', selected)
        self.assertIn('downscaleFactor.coerceAtLeast(1)', selected)
        backend = REPO/'desktop/third-party/miuix5157/upstream'
        bridge = (backend/'miuix-blur/src/skikoMain/kotlin/blur/internal/RenderEffectCompat.skiko.kt').read_text('utf-8')
        shader = (backend/'miuix-shader/src/skikoMain/kotlin/shader/RuntimeShader.skiko.kt').read_text('utf-8')
        self.assertIn('ImageFilter.makeRuntimeShader(', bridge)
        self.assertIn('runtimeShader.asSkikoRuntimeShader()', bridge)
        self.assertIn('RuntimeEffect.makeForShader(shaderString)', shader)
        self.assertIn('shader.uniform(name, values)', shader)
        build = (REPO/'desktop/third-party/miuix5157/build.gradle.kts').read_text('utf-8')
        self.assertIn('upstream/$it/src/skikoMain/kotlin', build)

    def test_modified_fixed_manifests_or_original_sources_are_rejected(self):
        for leaf in ['manifest.json',*(r['filename'] for r in source.PINS)]:
            with self.subTest(leaf=leaf),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp);shutil.copytree(REPO/source.ARCHIVE,root/source.ARCHIVE)
                p=root/source.ARCHIVE/leaf;p.write_bytes(p.read_bytes()+b'\n')
                with self.assertRaises(ValueError):source.fixed_sources(root)


if __name__=='__main__':unittest.main()
