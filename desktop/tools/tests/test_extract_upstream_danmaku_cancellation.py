from pathlib import Path
import importlib.util
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "desktop/tools"))
spec = importlib.util.spec_from_file_location("danmaku_cancellation_plugins", REPO / "desktop/tools/extract-upstream-plugins.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class OriginalDanmakuCancellationExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-plugin-cancellation-")
        cls.output = Path(cls.temp.name)
        tool.generate(REPO, cls.output)
        cls.policy = (cls.output / "com/android/purebilibili/feature/video/danmaku/DesktopPluginDanmakuPolicy.kt").read_text(encoding="utf-8")
        cls.original = tool.read(REPO, tool.BASE + "feature/video/danmaku/DanmakuManager.kt")
        cls.selector, cls.parser = tool.media_extractor(REPO), tool.parser_for(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_two_original_methods_invert_all_four_exact_catch_edits(self):
        before = "} catch (e: Exception) {"
        after = "} catch (cancelled: kotlinx.coroutines.CancellationException) {\n                throw cancelled\n            } catch (e: Exception) {"
        for name in ("runDanmakuFilters", "collectDanmakuStyle"):
            original = self.selector.function(self.original, name, self.parser)
            adapted = tool.preserve_danmaku_cancellation(original, name)
            self.assertEqual(2, adapted.count(after))
            self.assertEqual(original, tool.substitute(adapted, after, before, 2))
            emitted = self.selector.function(self.policy, name, self.parser)
            expected = tool.substitute(adapted, "private fun " + name, "internal fun " + name)
            self.assertEqual(expected, emitted)
        self.assertEqual(4, self.policy.count("catch (cancelled: kotlinx.coroutines.CancellationException)"))
        self.assertEqual(self.selector.function(self.original, "mergeDanmakuStyle", self.parser),
                         self.selector.function(self.policy, "mergeDanmakuStyle", self.parser))

    def test_changed_catch_count_and_other_policy_names_fail_closed(self):
        original = self.selector.function(self.original, "runDanmakuFilters", self.parser)
        with self.assertRaises(ValueError):
            tool.preserve_danmaku_cancellation(original.replace("catch (e: Exception)", "catch (e: Throwable)", 1), "runDanmakuFilters")
        with self.assertRaises(ValueError):
            tool.preserve_danmaku_cancellation(original, "mergeDanmakuStyle")
        with self.assertRaises(ValueError):
            tool.preserve_danmaku_cancellation(tool.preserve_danmaku_cancellation(original, "runDanmakuFilters"), "runDanmakuFilters")


if __name__ == "__main__":
    unittest.main()
