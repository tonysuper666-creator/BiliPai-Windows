from pathlib import Path
import hashlib,importlib.util,json,sys,tempfile,unittest
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
spec=importlib.util.spec_from_file_location('crashproducer',HERE/'extract-crash-prompt.py');producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
class OriginalCrashPromptExtractionTest(unittest.TestCase):
    def test_original_expression_policies_and_dialog_copy_are_exact(self):
        original=(REPO/producer.SOURCE).read_text(encoding='utf-8')
        policy=(HERE/'generated/sources/com/android/purebilibili/DesktopCrashLogPromptPolicy.kt').read_text(encoding='utf-8')
        start=original.index('internal enum class CrashLogPromptAction {')
        self.assertIn(original[start:original.index('internal fun shouldUseRealtimeSplashBlur',start)].rstrip(),policy)
        ui=(HERE/'generated/sources/com/android/purebilibili/DesktopPendingCrashLogPrompt.kt').read_text(encoding='utf-8')
        self.assertIn('AppAlertDialog(',ui);self.assertIn('Text(text = "检测到上次闪退日志")',ui)
        self.assertIn('应用已在私有目录保存一份脱敏后的崩溃快照，不会自动上传或写入公共下载目录。现在可以主动分享给开发者排查，也可以关闭提示。',ui)
        self.assertEqual(2,ui.count('onAction(CrashLogPromptAction.DISMISS)'));self.assertEqual(1,ui.count('onAction(CrashLogPromptAction.SHARE)'))
        self.assertNotIn('CrashLogPromptAction.IGNORE',ui);self.assertNotIn('Logger.',ui)
    def test_producer_is_deterministic_and_keeps_reference_outside_compile_sources(self):
        with tempfile.TemporaryDirectory(prefix='crash-extract-',dir=HERE) as temporary:
            output=Path(temporary)/'sources';producer.generate(REPO,output)
            self.assertEqual(2,len(list(output.rglob('*.kt'))))
            for file in output.rglob('*.kt'):self.assertEqual(file.read_bytes(),(HERE/'generated/sources'/file.relative_to(output)).read_bytes())
            self.assertTrue((output.parent/'reference-only/OriginalCrashLogPrompt.kt').exists())
    def test_changed_original_fails_before_any_output_or_tool_dependency(self):
        with tempfile.TemporaryDirectory(prefix='crash-pin-',dir=HERE) as temporary:
            fake=Path(temporary);source=fake/producer.SOURCE;source.parent.mkdir(parents=True)
            source.write_text((REPO/producer.SOURCE).read_text(encoding='utf-8')+'\n// changed\n',encoding='utf-8')
            with self.assertRaisesRegex(AssertionError,'Fixed original MainActivity changed'):producer.generate(fake,fake/'output')
            self.assertFalse((fake/'output').exists())
if __name__=='__main__':unittest.main()
