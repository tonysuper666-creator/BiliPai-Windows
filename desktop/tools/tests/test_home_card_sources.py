from pathlib import Path
import hashlib,importlib.util,re,tempfile,unittest
REPO=next(path for path in Path(__file__).resolve().parents if (path/'app/src/main').is_dir())
TOOL=Path(__file__).resolve().parent.parent/'extract-upstream-home-cards.py'
spec=importlib.util.spec_from_file_location('hc_test_generator',TOOL);gen=importlib.util.module_from_spec(spec);spec.loader.exec_module(gen)

class HomeCardOriginalSourceTest(unittest.TestCase):
 def generate(self,standalone=True):
  directory=Path(tempfile.mkdtemp(prefix='bp-hc-src-'));gen.generate(REPO,directory,standalone);return directory
 def test_inventory_has_unique_actual_original_hashes(self):
  entries=gen.inventory(REPO);self.assertEqual(7,len(entries));self.assertEqual(7,len({row['path'] for row in entries}))
  for row in entries:self.assertEqual(hashlib.sha256(gen.read(REPO,row['path']).encode()).hexdigest(),row['sha256'])
 def test_two_pure_direct_policies_are_full_original_bodies(self):
  directory=self.generate()
  for short in gen.DIRECT:
   original=gen.read(REPO,gen.BASE+short)
   output=(directory/'com/android/purebilibili'/short).read_text(encoding='utf-8')
   self.assertTrue(output.endswith(original));self.assertEqual('direct',next(row['mode'] for row in gen.inventory(REPO) if row['path']==gen.BASE+short))
 def test_production_generator_does_not_duplicate_shared_direct_sources(self):
  directory=self.generate(False)
  self.assertEqual(4,len(list(directory.rglob('*.kt'))))
  self.assertFalse((directory/'com/android/purebilibili/feature/settings/DesktopOriginalHomeCardWidthOptions.kt').exists())
  for short in gen.DIRECT:self.assertFalse((directory/'com/android/purebilibili'/short).exists())
 def test_exact_actual_persisted_four_expressions_are_emitted(self):
  directory=self.generate();generated=(directory/'com/android/purebilibili/core/store/DesktopOriginalHomeCardSettings.kt').read_text(encoding='utf-8')
  actual=gen.read(REPO,gen.BASE+'core/store/SettingsManager.kt')
  begin=actual.index('    internal fun mapHomeSettingsFromPreferences(preferences: Preferences): HomeSettings {')
  mapper=actual[begin:actual.index('\n    fun getHomeSettings(',begin)]
  for field in ['gridColumnCount','gridColumnCountCompact','homeFeedCardWidthPreset','homeFeedCardStyle']:
   expression=re.search(r'^            '+field+r' = (.*?)(?=^            \w+ =|\n        \))',mapper,re.M|re.S).group(1).strip().rstrip(',')
   self.assertIn(re.sub(r'\s+',' ',expression),re.sub(r'\s+',' ',generated))
  self.assertNotIn('val gridColumnCount:Int=',generated)
 def test_actual_setters_and_width_options_are_preserved(self):
  directory=self.generate();generated=(directory/'com/android/purebilibili/core/store/DesktopOriginalHomeCardSettings.kt').read_text(encoding='utf-8')
  host=gen.module(REPO,'hc_body_test_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(REPO);parser=media.parser_for(REPO)
  original=gen.read(REPO,gen.BASE+'core/store/SettingsManager.kt')
  for name in ['setGridColumnCount','setGridColumnCountCompact','setHomeFeedCardWidthPreset','setHomeFeedCardStyle']:
   self.assertIn(re.sub(r'\s+',' ',media.function(original,name,parser)),re.sub(r'\s+',' ',generated))
  original=gen.read(REPO,gen.BASE+'feature/settings/PlaybackSettingsSelectionPolicy.kt')
  generated=(directory/'com/android/purebilibili/feature/settings/DesktopOriginalHomeCardWidthOptions.kt').read_text(encoding='utf-8')
  self.assertIn(media.function(original,'resolveHomeFeedCardWidthPresetSegmentOptions',parser),generated)
 def test_original_controls_keep_options_and_explicit_windows_scope(self):
  directory=self.generate();source=(directory/'com/android/purebilibili/feature/settings/DesktopOriginalHomeCardFields.kt').read_text(encoding='utf-8')
  self.assertIn('(0..6).map',source);self.assertIn('HomeFeedCardStyle.entries.map',source)
  self.assertIn('resolveHomeFeedCardWidthPresetSegmentOptions()',source);self.assertIn('if(isTablet)',source)
  self.assertIn('（推荐、热门、分区等视频列表）',source)
  self.assertNotIn('可用双指缩放调整',source);self.assertNotIn('搜索、列表、相关推荐等同步',source)
  self.assertNotIn('viewModel',source);self.assertNotIn('SettingsManager',source)
if __name__=='__main__':unittest.main()
