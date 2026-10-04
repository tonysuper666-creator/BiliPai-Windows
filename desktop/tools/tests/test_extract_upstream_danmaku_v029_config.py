from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
spec = importlib.util.spec_from_file_location('danmaku_v029_config', REPO / 'desktop/tools/extract-upstream-danmaku-list-menu.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class CompleteV029DanmakuConfigTest(unittest.TestCase):
    def test_complete_config_inverse_and_original_algorithms(self):
        source, identities = tool.fixed_v029_config_files(REPO)
        original = source['DanmakuConfig.kt']
        adapted, patches = tool.adapt_complete_v029_config(original)
        self.assertEqual(len(patches), 10)
        restored = adapted
        for patch in reversed(patches):
            self.assertEqual(restored.count(patch['after']), 1)
            restored = restored.replace(patch['after'], patch['before'])
        self.assertEqual(restored, original)
        self.assertTrue(all(row['pinnedCommit'] == tool.V029_CONFIG_COMMIT for row in identities))
        for name in ('resolveDanmakuTextSizePx', 'resolveDanmakuScrollDurationMillis', 'resolveDanmakuLayerLineHeightPx'):
            self.assertEqual(tool.function(original, name, '')[0], tool.function(adapted, name, '')[0])

    def test_raw_manifest_and_each_original_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-v029-font-pins-') as temp:
            repo = Path(temp)
            archive = repo / tool.V029_CONFIG_ARCHIVE
            shutil.copytree(REPO / tool.V029_CONFIG_ARCHIVE, archive)
            manifest = archive / 'manifest.json'
            raw = manifest.read_bytes()
            manifest.write_bytes(raw + b' ')
            with self.assertRaisesRegex(AssertionError, 'manifest bytes'):
                tool.fixed_v029_config_files(repo)
            manifest.write_bytes(raw)
            for name in tool.V029_CONFIG_PINS:
                path = archive / name
                original = path.read_bytes()
                path.write_bytes(original + b'\n')
                with self.assertRaisesRegex(AssertionError, 'source bytes'):
                    tool.fixed_v029_config_files(repo)
                path.write_bytes(original)
            self.assertEqual(len(tool.fixed_v029_config_files(repo)[0]), 3)

    def test_all_thirteen_original_tests_only_change_framework_and_unique_class_name(self):
        originals, _ = tool.fixed_v029_config_files(REPO)
        expected = originals['DanmakuConfigPolicyTest.kt']
        for before, after in (
            ('import org.junit.Assert.assertEquals', 'import kotlin.test.assertEquals'),
            ('import org.junit.Assert.assertTrue', 'import kotlin.test.assertTrue'),
            ('import org.junit.Test', 'import kotlin.test.Test'),
            ('class DanmakuConfigPolicyTest', 'class DanmakuConfigV029PolicyTest'),
        ):
            self.assertEqual(expected.count(before), 1)
            expected = expected.replace(before, after)
        actual = (REPO / 'desktop/src/test/kotlin/com/android/purebilibili/feature/video/danmaku/DanmakuConfigV029PolicyTest.kt').read_text(encoding='utf-8')
        self.assertEqual(expected, actual)
        self.assertEqual(actual.count('@Test'), 13)

    def test_fresh_whole_producer_uses_actual_v029_config_and_complete_live_constructor(self):
        sources, _ = tool.fixed_v029_config_files(REPO)
        with tempfile.TemporaryDirectory(prefix='bilipai-v029-font-generation-') as temp:
            output = Path(temp)
            inventory = tool.generate(REPO, output)
            self.assertEqual(inventory['originalConfigUpstreamCommit'], tool.V029_CONFIG_COMMIT)
            self.assertEqual(inventory['retainedLegacyConfigUpstreamCommit'], tool.V027_CONFIG_COMMIT)
            config_record = next(row for row in inventory['emitted'] if row['path'].endswith('/DanmakuConfig.kt'))
            self.assertEqual(config_record['upstreamCommit'], tool.V029_CONFIG_COMMIT)
            package = output / 'com/android/purebilibili/feature/video/danmaku'
            config = (package / 'DanmakuConfig.kt').read_text(encoding='utf-8')
            self.assertEqual(config, tool.adapt_complete_v029_config(sources['DanmakuConfig.kt'])[0])
            live = (package / 'DesktopOriginalLiveDanmakuRenderConfig.kt').read_text(encoding='utf-8')
            body = live[live.index('            val textSize = '):live.rindex('\n}')]
            for after, before in (
                ('            return (', '            view.engine.updateConfig('),
                ('typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight, platform),', 'typeface = resolveDanmakuTypeface(danmakuSettings.fontWeight),'),
                ('speedFactor = danmakuSettings.speedFactor,', 'speedFactor = danmakuSettings.speed,'),
                ('viewportWidthPx = viewWidthPx', 'viewportWidthPx = view.width'),
                ('visibleHeightPx = viewHeightPx.toFloat(),', 'visibleHeightPx = view.height.toFloat(),'),
                ('val strokeWidth = if(danmakuSettings.strokeEnabled)resolveDanmakuStrokeWidthPx(density, danmakuSettings.strokeWidth) else 0f', 'val strokeWidth = resolveDanmakuStrokeWidthPx(density, danmakuSettings.strokeWidth)'),
            ):
                self.assertEqual(body.count(after), 1)
                body = body.replace(after, before)
            live_original = sources['LiveDanmakuOverlay.kt']
            start = live_original.index('            val textSize = resolveDanmakuTextSizePx(')
            end = live_original.index('\n        }\n    )', start)
            self.assertEqual(body, live_original[start:end])

    def test_legacy_v027_original_pin_remains_unchanged(self):
        original, identity = tool.fixed_v027_config(REPO)
        self.assertEqual(identity['sha256Bytes'], tool.V027_CONFIG_SHA256)
        self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), tool.V027_CONFIG_SHA256)
        self.assertEqual(json.loads((REPO / tool.V027_CONFIG_ARCHIVE / 'manifest.json').read_bytes())['fixedUpstreamCommit'], tool.V027_CONFIG_COMMIT)


if __name__ == '__main__':
    unittest.main()
