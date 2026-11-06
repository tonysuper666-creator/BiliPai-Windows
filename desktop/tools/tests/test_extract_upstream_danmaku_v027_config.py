from pathlib import Path
import importlib.util
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import sys
import unittest

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
LIVE=Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO)
TOOLS=REPO/'desktop/tools'
sys.path.insert(0,str(TOOLS))
spec=importlib.util.spec_from_file_location('danmaku_v027_config',REPO/'desktop/tools/extract-upstream-danmaku-list-menu.py')
tool=importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class CompleteV027DanmakuConfigTest(unittest.TestCase):
    def test_complete_config_inverse_reconstructs_the_actual_fixed_original(self):
        original,identity=tool.fixed_v027_config(REPO)
        adapted,patches=tool.adapt_complete_v027_config(original)
        self.assertEqual(len(patches),9)
        restored=adapted
        for patch in reversed(patches):
            self.assertEqual(restored.count(patch['after']),1)
            restored=restored.replace(patch['after'],patch['before'])
        self.assertEqual(restored,original)
        self.assertEqual(identity['pinnedCommit'],tool.V027_CONFIG_COMMIT)
        self.assertEqual(identity['sha256Bytes'],tool.V027_CONFIG_SHA256)

    def test_every_original_reservation_and_geometry_algorithm_is_unchanged(self):
        original,_=tool.fixed_v027_config(REPO)
        adapted,_=tool.adapt_complete_v027_config(original)
        for name,indent in [('resolveRenderConfig','    '),('resolveActiveDisplayBand','    '),
                            ('resolveDanmakuHotBarReservedHeightPx','')]:
            if name=='resolveDanmakuHotBarReservedHeightPx':
                # The original expression-bodied helper extends to the next declaration.
                begin=original.index('internal fun '+name)
                end=original.index('\ninternal fun resolveDanmakuMinimumVisibleLines',begin)
                self.assertIn(original[begin:end],adapted)
            elif name=='resolveRenderConfig':
                expected=tool.function(original,name,indent)[0]
                expected=expected.replace('typeface = resolveDanmakuTypeface(fontWeight),',
                    'typeface = resolveDanmakuTypeface(fontWeight, platform),')
                expected=expected.replace('strokeColor = android.graphics.Color.BLACK,',
                    'strokeColor = java.awt.Color.BLACK.rgb,')
                self.assertEqual(tool.function(adapted,name,indent)[0],expected)
            else:
                self.assertEqual(tool.function(adapted,name,indent)[0],tool.function(original,name,indent)[0])

    def test_original_manifest_commit_pin_path_and_raw_bytes_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-config-pins-') as temp:
            repo=Path(temp)
            archive=repo/tool.V027_CONFIG_ARCHIVE
            shutil.copytree(REPO/tool.V027_CONFIG_ARCHIVE,archive)
            path=archive/'manifest.json'
            manifest=json.loads(path.read_bytes())
            for field,value in [('fixedUpstreamCommit','unknown'),('originalPath','another.kt'),('sha256Bytes','0'*64)]:
                path.write_text(json.dumps(dict(manifest,**{field:value})),encoding='utf-8')
                with self.assertRaises(AssertionError):tool.fixed_v027_config(repo)
            path.write_text(json.dumps(manifest),encoding='utf-8')
            source=archive/'DanmakuConfig.kt'
            source.write_bytes(source.read_bytes()+b'\n// altered\n')
            with self.assertRaisesRegex(AssertionError,'Changed fixed v027 config bytes'):
                tool.fixed_v027_config(repo)

    def test_missing_platform_clause_is_rejected_instead_of_emitting_unbound_config(self):
        original,_=tool.fixed_v027_config(REPO)
        with self.assertRaises(AssertionError):
            tool.adapt_complete_v027_config(original.replace('typeface = resolveDanmakuTypeface(fontWeight),','typeface = anotherFont(),'))

    def test_all_twelve_original_policy_tests_keep_full_inverse_with_explicit_inline_presentation(self):
        raw=(REPO/tool.V027_CONFIG_ARCHIVE/'DanmakuConfigPolicyTest.kt').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),'e1a17f9fc160cab2f0b7287e701d53e8e179d33b6154cbb494bccb900ad25c6a')
        original=raw.decode('utf-8').replace('\r\n','\n')
        expected=original
        edits=[('import org.junit.Assert.assertEquals','import kotlin.test.assertEquals'),
                             ('import org.junit.Assert.assertTrue','import kotlin.test.assertTrue'),
                             ('import org.junit.Test','import kotlin.test.Test'),
               # v029 makes presentation explicit. Preserve the old assertion:
               # changing only viewport geometry cannot itself imply fullscreen.
               ('fun `text size ignores the container box so every surface renders the same`()',
                'fun `viewport geometry alone does not imply fullscreen presentation`()'),
               ('resolveDanmakuTextSizePx(inline.density, 1f)',
                'resolveDanmakuTextSizePx(inline.density, 1f, isFullscreen = false)'),
               ('resolveDanmakuTextSizePx(fullscreen.density, 1f)',
                'resolveDanmakuTextSizePx(fullscreen.density, 1f, isFullscreen = false)')]
        for before,after in edits:
            self.assertEqual(expected.count(before),1)
            expected=expected.replace(before,after)
        app_path='com/android/purebilibili/feature/video/danmaku/DanmakuConfigPolicyTest.kt'
        app_test=(LIVE/'app/src/test/java'/app_path).read_text(encoding='utf-8')
        old_viewport='text size ignores the container box so every surface renders the same'
        new_viewport='viewport geometry alone does not imply fullscreen presentation'
        pinned='pinned duration should clamp to safe bounds'
        names=re.findall(r'(?m)^    fun `([^`]+)`\(\)',original)
        self.assertEqual(len(names),12)
        self.assertEqual(len(set(names)),12)
        self.assertIn(old_viewport,names)
        self.assertIn(pinned,names)
        def function(text,name):
            pattern=r'(?ms)^    @Test\n    fun `'+re.escape(name)+r'`\(\) \{.*?^    \}'
            matches=re.findall(pattern,text)
            self.assertEqual(len(matches),1,name)
            return matches[0]
        for name in names:
            if name not in (old_viewport,pinned):
                self.assertEqual(function(original,name),function(app_test,name),name)

        self.assertIn('coerceIn(1f, 50f)',(LIVE/'app/src/main/java/com/android/purebilibili/feature/video/danmaku/DanmakuConfig.kt').read_text(encoding='utf-8'))
        desktop=(LIVE/'desktop/src/test/kotlin/com/android/purebilibili/feature/video/danmaku/DanmakuConfigV029PolicyTest.kt').read_text(encoding='utf-8')
        for text in (app_test,desktop):
            body=function(text,pinned)
            for value,argument in [('1000L','0.5f'),('4000L','4.0f'),('18000L','18.0f'),('50000L','60f')]:
                self.assertIn(f'assertEquals({value}, resolveDanmakuPinnedDurationMillis({argument}))',body)

        patch_path=LIVE/'desktop/upstream-core/patches/app/src/test/java'/f'{app_path}.patch'
        patch=patch_path.read_text(encoding='utf-8')
        # The original viewport regression now belongs to the patched upstream
        # class. Inspect the actual patch postimage, not a retired desktop copy.
        with tempfile.TemporaryDirectory(prefix='bilipai-config-tests-') as temp:
            root=Path(temp)
            target=root/'app/src/test/java'/app_path
            target.parent.mkdir(parents=True)
            target.write_text(app_test,encoding='utf-8',newline='\n')
            subprocess.run(['git','apply','--no-index',str(patch_path.resolve())],
                           cwd=root,check=True,capture_output=True,text=True)
            viewport=target.read_text(encoding='utf-8')
        adapted=function(viewport,new_viewport)
        self.assertEqual(adapted,function(expected,new_viewport))
        restored=adapted
        for before,after in reversed(edits[3:]):
            self.assertEqual(restored.count(after),1)
            restored=restored.replace(after,before)
        self.assertEqual(restored,function(original,old_viewport))

        self.assertIn('Upstream-source-sha256: '+hashlib.sha256(app_test.encode('utf-8')).hexdigest(),patch)
        removed=[line for line in patch.splitlines() if line.startswith('-') and not line.startswith('---')]
        added=[line for line in patch.splitlines() if line.startswith('+') and not line.startswith('+++')]
        self.assertEqual(removed,['-import org.junit.Assert.assertEquals','-import org.junit.Assert.assertTrue','-import org.junit.Test'])
        junit5=['import org.junit.jupiter.api.Assertions.assertEquals','import org.junit.jupiter.api.Assertions.assertTrue','import org.junit.jupiter.api.Test']
        body=function(expected,new_viewport)
        self.assertEqual(added,['+'+line for line in junit5]+['+'+line for line in body.splitlines()]+['+'])
        postimage=app_test
        for before,after in zip(['import org.junit.Assert.assertEquals','import org.junit.Assert.assertTrue','import org.junit.Test'],junit5):
            self.assertEqual(postimage.count(before),1)
            postimage=postimage.replace(before,after)
        anchor='class DanmakuConfigPolicyTest {\n'
        self.assertEqual(postimage.count(anchor),1)
        self.assertEqual(viewport,postimage.replace(anchor,anchor+'\n'+body+'\n'))
        gradle=(LIVE/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for name,path in [('correctedUpstreamTestPaths','app/src/test/java/'+app_path),('migratedVideoFeatureTestPaths',app_path)]:
            block=re.search(r'val '+name+r'\s*=\s*setOf\((.*?)\n\)',gradle,re.S)
            self.assertIsNotNone(block,name)
            self.assertIn('"'+path+'"',block.group(1))


if __name__=='__main__':unittest.main()
