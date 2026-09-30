from pathlib import Path
import hashlib,importlib.util,json,unittest
HERE=Path(__file__).resolve().parent;ROOT=HERE.parent.parents[2]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('blocked_management_extractor',HERE/'prepared/desktop/tools/extract-upstream-blocked-list-ui.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
class Extraction(unittest.TestCase):
    def test_original_content_and_all_helpers_are_unchanged(self):
        text=tool.original(ROOT,tool.SOURCES[0]);body=text[text.index('@Composable\nfun BlockedListContent('):]
        generated=(HERE/'generated/com/android/purebilibili/feature/settings/DesktopUpstreamBlockedListContent.kt').read_text(encoding='utf-8')
        self.assertEqual(body,generated[generated.index('@Composable\nfun BlockedListContent('):])
    def test_original_padding_and_page_profile_delays_are_preserved(self):
        text=tool.original(ROOT,tool.SOURCES[2]);start=text.index('internal val LocalSettingsTopContentPadding');end=text.index('@Composable\ninternal fun SettingsBottomBarScrollEffect',start)
        generated=(HERE/'generated/com/android/purebilibili/feature/settings/ui/DesktopUpstreamBlockedListPadding.kt').read_text(encoding='utf-8')
        self.assertEqual(text[start:end],generated[generated.index('internal val LocalSettingsTopContentPadding'):])
        pacing=(HERE/'generated/com/android/purebilibili/data/repository/DesktopUpstreamBlockedListPacing.kt').read_text(encoding='utf-8')
        for path in [tool.SOURCES[3],tool.SOURCES[4]]:
            for line in tool.original(ROOT,path).splitlines():
                if line.startswith('private const val BLOCKED_') and any(part in line for part in ['DELAY_MS','PAGE_SIZE','MAX_PAGES']):self.assertIn(line,pacing)
    def test_all_original_badge_pixels_are_byte_identical(self):
        for asset in ['lv0','lv1','lv2','lv3','lv4','lv5','lv6','lv6_s']:
            self.assertEqual(sha(ROOT/f'app/src/main/res/drawable-nodpi/{asset}.png'),sha(HERE/f'prepared/desktop/src/main/resources/blocked-up-badges/{asset}.png'))
if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(Extraction);result=unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():raise SystemExit(1)
    (HERE/'extraction-evidence.json').write_text(json.dumps(dict(passed=True,testsRun=result.testsRun,extractorSha256Bytes=sha(HERE/'prepared/desktop/tools/extract-upstream-blocked-list-ui.py')))+'\n',encoding='utf-8',newline='\n')
