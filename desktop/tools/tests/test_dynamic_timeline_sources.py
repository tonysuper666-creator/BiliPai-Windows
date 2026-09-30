from pathlib import Path
import hashlib,importlib.util,os,tempfile,unittest

REPO=Path(os.environ.get('BILIPAI_DYNAMIC_SOURCE_REPO',Path(__file__).resolve().parents[3]))
TOOL=Path(os.environ.get('BILIPAI_DYNAMIC_SOURCE_TOOL',REPO/'desktop/tools/extract-upstream-dynamic-settings.py'))
spec=importlib.util.spec_from_file_location('dynamic_source_test_extractor',TOOL)
extractor=importlib.util.module_from_spec(spec);spec.loader.exec_module(extractor)
host=extractor.module(REPO,'dynamic_original_host_test','desktop/tools/extract-upstream-plugins.py')
helper=host.media_extractor(REPO);parser=helper.parser_for(REPO)

class DynamicTimelineSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory=tempfile.TemporaryDirectory(prefix='bp-original-dynamic-source-')
        cls.output=Path(cls.directory.name)
        extractor.generate(REPO,cls.output,standalone=True)
    @classmethod
    def tearDownClass(cls):cls.directory.cleanup()
    def generated(self,name):return next(self.output.rglob(name)).read_text(encoding='utf-8')
    def test_original_direct_sources_are_full_bodies_and_have_exact_original_hash_headers(self):
        for path in extractor.DIRECT:
            original=extractor.read(REPO,extractor.BASE+path)
            actual=self.generated(Path(path).name)
            self.assertEqual(original,actual[actual.index('\npackage ')+1:])
            self.assertIn(hashlib.sha256(original.encode()).hexdigest(),actual)
    def test_page_and_incremental_decisions_remain_original_functions(self):
        groups=[('feature/dynamic/DynamicScreenStatePolicy.kt','DesktopOriginalDynamicTimelinePolicy.kt',
            ['resolveDynamicTimelinePageForLoadStart','resolveDynamicTimelinePageAfterSuccess','resolveDynamicTimelinePageAfterFailure','sortDynamicTimelineItemsByPublishTime']),
            ('feature/dynamic/DynamicIncrementalRefreshPolicy.kt','DesktopOriginalDynamicIncrementalPolicy.kt',
            ['dynamicFeedItemKey','dynamicTimelineItemsOverlap','canPerformIncrementalTimelineRefresh','resolveIncrementalRefreshBoundary','resolveOldContentDividerIndex','resolveDynamicRefreshDividerGridIndex','shouldReloadFollowings'])]
        for path,file,names in groups:
            original=extractor.read(REPO,extractor.BASE+path);actual=self.generated(file)
            for name in names:self.assertEqual(helper.function(original,name,parser),helper.function(actual,name,parser),name)
    def test_followings_ttl_uses_the_exact_original_constant(self):
        original=extractor.read(REPO,extractor.BASE+'feature/dynamic/DynamicIncrementalRefreshPolicy.kt')
        expected=next(line for line in original.splitlines() if line.startswith('internal const val FOLLOWINGS_REFRESH_TTL_MS:'))
        self.assertIn(expected,self.generated('DesktopOriginalDynamicIncrementalPolicy.kt'))
    def test_original_fetch_loop_has_only_documented_transport_cancellation_and_safe_log_bindings(self):
        original=extractor.read(REPO,extractor.BASE+'data/repository/DynamicRepository.kt')
        expected=helper.function(original,'getDynamicFeed',parser).replace('NetworkModule.dynamicApi.getDynamicFeed(','getPage(').replace(
            '        e.printStackTrace()','        // Windows boundary never logs raw response/URL exception details.').replace(
            'catch (e: Exception) {','catch (cancelled: kotlinx.coroutines.CancellationException) {\n        throw cancelled\n    } catch (e: Exception) {')
        self.assertEqual(expected,helper.function(self.generated('DesktopOriginalDynamicTimelineRepository.kt'),'getDynamicFeed',parser))
    def test_generation_is_deterministic_per_source_identity(self):
        with tempfile.TemporaryDirectory(prefix='bp-dynamic-repeat-') as folder:
            output=Path(folder);extractor.generate(REPO,output,standalone=True)
            left={p.relative_to(self.output):p.read_bytes() for p in self.output.rglob('*.kt')}
            right={p.relative_to(output):p.read_bytes() for p in output.rglob('*.kt')}
            self.assertEqual(14,len(left));self.assertEqual(left,right)

if __name__=='__main__':unittest.main()
