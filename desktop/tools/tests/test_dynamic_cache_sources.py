from pathlib import Path
import importlib.util,tempfile,unittest,sys
sys.dont_write_bytecode=True
REPO=next(p for p in Path(__file__).resolve().parents
 if (p/'desktop/tools/extract-upstream-dynamic-tabs.py').is_file() and (p/'desktop/upstream-sources.json').is_file())
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,REPO/path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
tabs=module('cache_tabs_sources','desktop/tools/extract-upstream-dynamic-tabs.py')
settings=module('cache_settings_sources','desktop/tools/extract-upstream-dynamic-settings.py')
host=module('cache_source_host','desktop/tools/extract-upstream-plugins.py')
media=host.media_extractor(REPO);parser=media.parser_for(REPO)
declarations=module('cache_source_declarations','desktop/tools/extract-appearance-platform.py')
class DynamicCacheSourcesTest(unittest.TestCase):
 def test_original_not_interested_expression_body_is_preserved(self):
  with tempfile.TemporaryDirectory(prefix='bp-cache-policy-') as temporary:
   out=Path(temporary);tabs.generate(REPO,out)
   original=tabs.read(REPO,tabs.BASE+'feature/dynamic/DynamicScreenStatePolicy.kt')
   actual=next(out.rglob('DesktopOriginalDynamicUserStatePolicy.kt')).read_text(encoding='utf-8')
   self.assertEqual(declarations.declarations(parser,original,['normalizeDynamicNotInterestedIds']).strip(),
    declarations.declarations(parser,actual,['normalizeDynamicNotInterestedIds']).strip())
 def test_original_cache_namespace_keys_and_limits_are_extracted_without_policy_changes(self):
  with tempfile.TemporaryDirectory(prefix='bp-cache-keys-') as temporary:
   out=Path(temporary);settings.generate(REPO,out)
   original=settings.read(REPO,settings.BASE+'feature/dynamic/DynamicViewModel.kt')
   actual=next(out.rglob('DesktopOriginalDynamicTimelinePage.kt')).read_text(encoding='utf-8')
   keys=['PREFS_DYNAMIC_CACHE','KEY_DYNAMIC_CACHE','KEY_DYNAMIC_CACHE_TIME','KEY_NOT_INTERESTED_DYNAMIC_IDS','MAX_CACHE_ITEMS','MAX_NOT_INTERESTED_DYNAMIC_IDS']
   for key in keys:
    lines=[line.strip().replace('private const val','const val',1)for line in original.splitlines()if line.strip().startswith('private const val '+key+' =')]
    self.assertEqual(1,len(lines),key);self.assertEqual(1,actual.count(lines[0]),key)
   self.assertEqual(media.data_class(original,'DynamicTimelinePageState',parser),
    media.data_class(actual,'DynamicTimelinePageState',parser))
if __name__=='__main__':unittest.main()
