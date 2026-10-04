from pathlib import Path
import importlib.util
import re
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[3]
GENERATED = REPO / 'desktop/build/generated/crash-prompt/sources'
spec = importlib.util.spec_from_file_location('product_crash_prompt_extractor', REPO / 'desktop/tools/extract-upstream-crash-prompt.py')
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class OriginalCrashPromptSources(unittest.TestCase):
    def test_original_expression_policies_and_dialog_actions_reach_product(self):
        original = producer._desktop_canonical_source(REPO, producer.SOURCE).read_text(encoding='utf-8')
        package = GENERATED / 'com/android/purebilibili'
        policy = (package / 'DesktopCrashLogPromptPolicy.kt').read_text(encoding='utf-8')
        start = original.index('internal enum class CrashLogPromptAction {')
        self.assertIn(original[start:original.index('internal fun shouldUseRealtimeSplashBlur', start)].rstrip(), policy)
        ui = (package / 'DesktopPendingCrashLogPrompt.kt').read_text(encoding='utf-8')
        self.assertIn('Text(text = "检测到上次闪退日志")', ui)
        self.assertIn('应用已在私有目录保存一份脱敏后的崩溃快照，不会自动上传或写入公共下载目录。现在可以主动分享给开发者排查，也可以关闭提示。', ui)
        self.assertEqual(2, ui.count('onAction(CrashLogPromptAction.DISMISS)'))
        self.assertEqual(1, ui.count('onAction(CrashLogPromptAction.SHARE)'))
        self.assertNotIn('CrashLogPromptAction.IGNORE', ui)
        self.assertNotIn('Logger.', ui)

    def test_actual_main_output_is_deterministic_and_reference_is_not_compiled(self):
        with tempfile.TemporaryDirectory(prefix='bp-original-crash-prompt-') as folder:
            output = Path(folder) / 'sources'
            producer.generate(REPO, output)
            self.assertEqual(2, len(list(output.rglob('*.kt'))))
            for path in output.rglob('*.kt'):
                self.assertEqual(path.read_bytes(), (GENERATED / path.relative_to(output)).read_bytes())
            self.assertTrue((output.parent / 'reference-only/OriginalCrashLogPrompt.kt').is_file())

    def test_changed_original_is_rejected_before_output(self):
        with tempfile.TemporaryDirectory(prefix='bp-crash-source-pin-') as folder:
            fake = Path(folder)
            original = producer._desktop_canonical_source(REPO, producer.SOURCE)
            source = fake / original.relative_to(REPO)
            source.parent.mkdir(parents=True)
            source.write_text(original.read_text(encoding='utf-8') + '\n// changed\n', encoding='utf-8')
            # The canonical source gate now rejects changed bytes before the
            # producer's narrower MainActivity pin or any output creation.
            expected='^Canonical original source digest mismatch: '+re.escape(original.relative_to(REPO).as_posix())+'$'
            with self.assertRaisesRegex(ValueError, expected):
                producer.generate(fake, fake / 'output')
            self.assertFalse((fake / 'output').exists())


if __name__ == '__main__':
    unittest.main()
