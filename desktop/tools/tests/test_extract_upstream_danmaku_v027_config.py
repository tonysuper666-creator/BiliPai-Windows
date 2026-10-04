from pathlib import Path
import importlib.util
import hashlib
import json
import shutil
import tempfile
import sys
import unittest

HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
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

    def test_all_twelve_original_policy_tests_only_change_framework_imports(self):
        raw=(REPO/tool.V027_CONFIG_ARCHIVE/'DanmakuConfigPolicyTest.kt').read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(),'e1a17f9fc160cab2f0b7287e701d53e8e179d33b6154cbb494bccb900ad25c6a')
        original=raw.decode('utf-8').replace('\r\n','\n')
        expected=original
        for before,after in [('import org.junit.Assert.assertEquals','import kotlin.test.assertEquals'),
                             ('import org.junit.Assert.assertTrue','import kotlin.test.assertTrue'),
                             ('import org.junit.Test','import kotlin.test.Test')]:
            self.assertEqual(expected.count(before),1)
            expected=expected.replace(before,after)
        adapted=(REPO/'desktop/src/test/kotlin/com/android/purebilibili/feature/video/danmaku/DanmakuConfigPolicyTest.kt').read_text(encoding='utf-8')
        self.assertEqual(adapted,expected)
        self.assertEqual(adapted.count('@Test'),12)


if __name__=='__main__':unittest.main()
