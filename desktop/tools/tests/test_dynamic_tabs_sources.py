from pathlib import Path
import importlib.util,hashlib,tempfile,unittest,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve();REPO=next(p for p in HERE.parents
 if (p/'desktop/tools/extract-upstream-dynamic-tabs.py').is_file() and (p/'desktop/upstream-sources.json').is_file())
if len(sys.argv)>1 and (Path(sys.argv[1])/'desktop/tools/extract-upstream-dynamic-tabs.py').is_file() and (Path(sys.argv[1])/'desktop/upstream-sources.json').is_file():REPO=Path(sys.argv.pop(1)).resolve()
TOOL=HERE.parents[1]/'extract-upstream-dynamic-tabs.py'
s=importlib.util.spec_from_file_location('tabs_source_test',TOOL);g=importlib.util.module_from_spec(s);s.loader.exec_module(g)
class DynamicTabsSourcesTest(unittest.TestCase):
 def setUp(self):
  self.temporary=tempfile.TemporaryDirectory(prefix='bpd-tab-source-');self.output=Path(self.temporary.name)
  self.files=g.generate(REPO,self.output,standalone=True);self.host=g.module(REPO,'tabs_test_host','desktop/tools/extract-upstream-plugins.py')
  self.media=self.host.media_extractor(REPO);self.parser=self.media.parser_for(REPO)
 def tearDown(self):self.temporary.cleanup()
 def generated(self,name):return next(self.output.rglob(name)).read_text(encoding='utf-8')
 def test_every_original_identity_header_and_determinism(self):
  before={str(p.relative_to(self.output)):p.read_bytes()for p in self.output.rglob('*.kt')}
  self.assertEqual(len(g.PATHS),len(set(g.PATHS)))
  self.assertEqual(20,len(g.inventory(REPO)))
  for row in g.inventory(REPO):
   original=g.read(REPO,row['path']);self.assertEqual(row['sha256'],hashlib.sha256(original.encode()).hexdigest())
   self.assertTrue(any(('GENERATED from '+row['path']+';') in body.decode()and('LF-normalized SHA-256: '+row['sha256']) in body.decode()for body in before.values()),row['path'])
  g.generate(REPO,self.output,standalone=True);self.assertEqual(before,{str(p.relative_to(self.output)):p.read_bytes()for p in self.output.rglob('*.kt')})
 def test_production_omits_full_direct_sources_owned_by_main_preparation(self):
  with tempfile.TemporaryDirectory(prefix='bpd-tabs-production-') as temporary:
   output=Path(temporary);g.generate(REPO,output)
   production={p.name for p in output.rglob('*.kt')}
   self.assertTrue({Path(p).name for p in g.DIRECT}.isdisjoint(production))
   self.assertEqual(len(self.files)-len(g.DIRECT),len(production))
 def test_startup_plan_and_followings_page_budget_are_original_declarations(self):
  original=g.read(REPO,g.BASE+'feature/dynamic/DynamicViewModel.kt')
  actual=self.generated('DesktopOriginalDynamicUsers.kt')
  self.assertEqual(self.media.data_class(original,'DynamicStartupLoadPlan',self.parser),self.media.data_class(actual,'DynamicStartupLoadPlan',self.parser))
  for name in ['resolveDynamicStartupLoadPlan','resolveDynamicFollowingsPageLimit','hasLoadedAllDynamicFollowings']:
   self.assertEqual(self.media.function(original,name,self.parser),self.media.function(actual,name,self.parser))
 def test_automatic_pagination_uses_the_original_furthest_lane_policy(self):
  original=g.read(REPO,g.BASE+'feature/dynamic/DynamicScreenStatePolicy.kt')
  actual=self.generated('DesktopOriginalDynamicUserStatePolicy.kt')
  self.assertEqual(self.media.function(original,'shouldLoadMoreDynamicFeed',self.parser),self.media.function(actual,'shouldLoadMoreDynamicFeed',self.parser))
 def test_settings_getters_setters_and_original_controls_are_preserved(self):
  original=g.read(REPO,g.BASE+'core/store/SettingsManager.kt');actual=self.generated('DesktopDynamicTabsSettings.kt')
  for name in ['getDynamicTabVisibleTabs','setDynamicTabVisibleTabs','getDynamicTabOrder','setDynamicTabOrder','getDynamicAllTabHorizontalUserListVisible','setDynamicAllTabHorizontalUserListVisible']:
   self.assertEqual(self.media.function(original,name,self.parser),self.media.function(actual,name,self.parser))
  source=g.read(REPO,g.BASE+'feature/settings/ui/SettingsSections.kt')
  self.assertEqual(self.media.function(source,'FeedDynamicTabVisibilityItem',self.parser),
   self.media.function(self.generated('DesktopOriginalDynamicTabsFields.kt'),'FeedDynamicTabVisibilityItem',self.parser))
 def test_native_component_body_is_original_with_only_platform_and_shared_declaration_binding(self):
  source=g.read(REPO,g.DS+'components/AppSegmentedControl.kt')
  a=g.module(REPO,'tabs_test_decl','desktop/tools/extract-appearance-platform.py')
  expected=source.replace(self.media.data_class(source,'AppSegmentOption',self.parser),'').replace(a.declarations(self.parser,source,['resolvePiliPlusScrollableUnderlineMinWidth']),'')
  expected=expected.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration as LocalConfiguration')
  expected=expected.replace('import androidx.compose.foundation.isSystemInDarkTheme','import com.bilipai.desktop.appearance.isDesktopInDarkTheme as isSystemInDarkTheme')
  actual=self.generated('DesktopOriginalDynamicNativeTabs.kt').split('\n',2)[2]
  self.assertEqual(expected.strip(),actual.strip())
  for path in g.DIRECT:
   actual=self.generated(Path(path).name).split('\n',2)[2]
   self.assertEqual(g.read(REPO,path).strip(),actual.strip())
 def test_user_fetch_retry_and_uid_cursor_changes_are_only_owned_transport_and_cancel_binding(self):
  source=g.read(REPO,g.BASE+'data/repository/DynamicRepository.kt');actual=self.generated('DesktopOriginalDynamicUserRepository.kt')
  expected=self.media.function(source,'getUserDynamicFeed',self.parser).replace('NetworkModule.dynamicApi.getUserDynamicFeed(','getPage(')
  expected=expected.replace('catch (e: Exception) {','catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) {').replace('            e.printStackTrace()','            // Task boundary never prints URLs or raw response exceptions.')
  self.assertEqual(expected,self.media.function(actual,'getUserDynamicFeed',self.parser))
  self.assertEqual(self.media.function(source,'buildSelectedUserDynamicFeedParams',self.parser),self.media.function(actual,'buildSelectedUserDynamicFeedParams',self.parser))
  body=self.generated('DesktopOriginalDynamicUserControls.kt')
  original=g.read(REPO,g.BASE+'feature/dynamic/DynamicScreen.kt')
  self.assertEqual(self.media.function(original,'HorizontalUserList',self.parser).replace('private fun','internal fun',1),self.media.function(body,'HorizontalUserList',self.parser))
if __name__=='__main__':unittest.main()
