"""Settings-search producer: the real build inputs, then the original boundary assertions.

The desktop build runs this producer with `--repo` at the pinned legacy read point
(desktop/upstream-core/legacy-root.commit, materialized at <repo>/.legacy-root by
desktop/settings.gradle.kts), never at the merged repository root. Reading the merged
tree here failed on the first original before any assertion ran, which left the boundary
assertions below unexercised.

The build also feeds the symbols side from the merged tree (`--symbols-repo <repo>`):
the two icon policies and every vector XML come from there. Both inputs are exercised
below — the old snapshot alone cannot see the merged icons, and none of the icons it can
see carries a stroke, so a snapshot-only test would leave stroke mapping unproven.
"""
from pathlib import Path
import hashlib,importlib.util,inspect,json,re,tempfile,unittest,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'desktop/tools/extract-upstream-plugins.py').is_file())
TOOL=next(p for p in [HERE/'extract-upstream-settings-search.py',HERE.parent/'extract-upstream-settings-search.py'] if p.is_file())
spec=importlib.util.spec_from_file_location('settings_search',TOOL);e=importlib.util.module_from_spec(spec);spec.loader.exec_module(e)
PIN=REPO/'desktop/upstream-core/legacy-root.commit'
DRAWABLE='app/src/main/res/drawable/'
SOURCE=None
MERGED=REPO
DUAL='symbols_repo' in inspect.signature(e.generate).parameters
_WORKSPACE=None

def pinned_commit():
 return [line.strip() for line in PIN.read_text(encoding='utf-8').splitlines() if line.strip() and not line.startswith('#')][0]

def drawable(root,name):
 return Path(root)/DRAWABLE/(name+'.xml')

def android_attrs(node):
 return {k[len(e.A):]:v for k,v in node.attrib.items() if k.startswith(e.A)}

def stroked_paths(root,name):
 return [android_attrs(node) for node in ET.fromstring(drawable(root,name).read_text(encoding='utf-8')) if 'strokeColor' in android_attrs(node)]

def setUpModule():
 global SOURCE,_WORKSPACE
 SOURCE=REPO if REPO.name=='.legacy-root' else REPO/'.legacy-root'
 if (SOURCE/e.S/'SettingsSemanticIconPolicy.kt').is_file():return
 # Nothing materialized this machine: build the same pinned read point without Gradle.
 import sys;sys.path.insert(0,str(HERE.parent))
 from legacy_gate_workspace import legacy_gate_workspace
 _WORKSPACE=legacy_gate_workspace(REPO);SOURCE=Path(_WORKSPACE.__enter__())

def tearDownModule():
 if _WORKSPACE is not None:_WORKSPACE.__exit__(None,None,None)

class SettingsSearchExtractionTest(unittest.TestCase):
 def generate(self,standalone=True,symbols_repo=None):
  root=Path(self.enterContext(tempfile.TemporaryDirectory()))
  kwargs={} if symbols_repo is None else {'symbols_repo':symbols_repo}
  files=e.generate(SOURCE,root,standalone,**kwargs)
  return root,{p.name:p.read_text(encoding='utf-8') for p in files}
 def test_original_source_identity(self):
  rows=e.inventory(SOURCE);self.assertEqual(14,len(rows));self.assertEqual(14,len({r['path'] for r in rows}))
  for r in rows:self.assertEqual(hashlib.sha256(e.read(SOURCE,r['path']).encode()).hexdigest(),r['sha256'])
 def test_reads_the_pinned_build_snapshot(self):
  self.assertRegex(pinned_commit(),'^[0-9a-f]{40}$')
  s=e.read(SOURCE,e.S+'SettingsSemanticIconPolicy.kt')
  self.assertIn('/**\n * Miuix 设置页只使用 miuix-icons 字形。',s)
  self.assertIn('MiuixIcons.Theme',s)
 def test_build_declares_the_same_two_inputs_as_the_producer(self):
  recipe=(REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
  self.assertIn('repositoryRoot = file(gradle.extra["bilipai.legacyRoot"] as String)',recipe)
  self.assertIn('.legacy-root',(REPO/'desktop/settings.gradle.kts').read_text(encoding='utf-8'))
  block=recipe[recipe.index('val extractUpstreamSettingsSearch by'):recipe.index('val extractUpstreamSettingsCategories')]
  self.assertIn('"--repo", repositoryRoot.absolutePath',block)
  self.assertEqual(DUAL,'--symbols-repo' in block)
  if DUAL:self.assertIn('"--symbols-repo", mergedRepositoryRoot.absolutePath',block)
 def test_pure_index_focus_and_category_are_unmodified(self):
  _,files=self.generate()
  for p in e.DIRECT:self.assertTrue(files[Path(p).name].endswith(e.read(SOURCE,p)))
  self.assertIn('PinyinUtils.matches(',files['SettingsSearchPolicy.kt'])
  self.assertIn('.distinctBy { it.target }',files['SettingsSearchPolicy.kt'])
 def test_direct_sync_boundary_not_duplicated(self):
  root,files=self.generate(False);self.assertEqual(9,len(files))
  # The fixed v032 implementation is emitted next to the extracted adapters; a count
  # padded with any other file must not pass for it.
  self.assertIn('PinyinUtils.kt',files)
  self.assertIn('object PinyinUtils',files['PinyinUtils.kt'])
  self.assertIn('fun matches(',files['PinyinUtils.kt'])
  for p in e.DIRECT:self.assertNotIn(Path(p).name,files)
 def test_stale_owned_direct_removed_nonowned_preserved(self):
  root,files=self.generate();self.assertEqual(15,len(files))
  e.generate(SOURCE,root,False)
  self.assertFalse((root/'com/android/purebilibili/feature/settings/SettingsSearchPolicy.kt').exists())
  other=root/'com/android/purebilibili/feature/settings/SettingsSearchPolicy.kt';other.write_text('not generated by this source',encoding='utf-8')
  e.generate(SOURCE,root,False);self.assertEqual('not generated by this source',other.read_text())
 def test_original_history_object_only_storage_imports_changed(self):
  _,files=self.generate();s=e.read(SOURCE,e.BASE+'core/store/SettingsSearchHistoryStore.kt')
  expected=s.replace('import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context\nimport com.bilipai.desktop.settings.settingsSearchDataStore').replace('import androidx.datastore.preferences.core.edit\n','').replace('import androidx.datastore.preferences.core.stringPreferencesKey','import com.bilipai.desktop.plugins.stringPreferencesKey').replace('context.settingsDataStore','context.settingsSearchDataStore')
  self.assertTrue(files['SettingsSearchHistoryStore.kt'].endswith(expected))
  self.assertIn('SettingsSearchHistoryStore.record(context, query)',files['SettingsSearchHistoryOperations.kt'])
  self.assertIn('if (!privacyModeEnabled())',files['SettingsSearchHistoryOperations.kt'])
 def test_resource_identity_and_complete_original_paths(self):
  _,files=self.generate();body=files['DesktopSettingsVectors.kt'];rows=e.resource_inventory(SOURCE)
  self.assertEqual(182,len(rows));self.assertEqual(182,len({r['path'] for r in rows}))
  for r in rows:
   source=e.read(SOURCE,r['path']);self.assertEqual(hashlib.sha256(source.encode()).hexdigest(),r['sha256'])
   root=ET.fromstring(source)
   for path in root:self.assertIn(json.dumps(path.attrib[e.A+'pathData']),body)
 def test_merged_symbols_are_the_second_build_input(self):
  legacy=e.symbol_names(SOURCE);merged=e.symbol_names(MERGED)
  self.assertEqual(182,len(legacy));self.assertEqual(215,len(merged))
  self.assertEqual(179,len(set(legacy)&set(merged)))
  self.assertEqual(3,len(set(legacy)-set(merged)));self.assertEqual(36,len(set(merged)-set(legacy)))
  for name in merged:
   path=drawable(MERGED,name);self.assertTrue(path.is_file(),name)
   root=ET.fromstring(path.read_text(encoding='utf-8'));self.assertEqual('vector',root.tag,name)
   self.assertTrue(any(e.A+'pathData' in node.attrib for node in root),name)
  if not DUAL:return
  rows=e.resource_inventory(MERGED);self.assertEqual(215,len(rows));self.assertEqual(215,len({r['path'] for r in rows}))
  for r in rows:
   self.assertEqual(DRAWABLE+r['path'].rsplit('/',1)[-1],r['path'])
   self.assertEqual(hashlib.sha256(e.read(MERGED,r['path']).encode()).hexdigest(),r['sha256'])
  _,files=self.generate(symbols_repo=MERGED);body=files['DesktopSettingsVectors.kt']
  for name in merged:
   self.assertIn('const val '+name+': Int = com.android.purebilibili.R.drawable.'+name,body)
   self.assertIn('com.android.purebilibili.R.drawable.'+name+' to "'+name+'"',body)
  for r in rows:
   for node in ET.fromstring(e.read(MERGED,r['path'])):self.assertIn(json.dumps(node.attrib[e.A+'pathData']),body)
 def test_merged_stroke_symbols_are_mapped_never_dropped(self):
  # Only the merged tree carries stroked originals: dropping stroke would lose the glyph
  # outline, so every stroked path must reach the generated vector.
  stroke={name:stroked_paths(MERGED,name) for name in e.symbol_names(MERGED)}
  stroke={name:paths for name,paths in stroke.items() if paths}
  self.assertEqual(['ic_settings_action_counts_24','ic_settings_bottom_bar_compact_24','ic_settings_comment_avatar_push_24','ic_settings_cover_pulse_24','ic_settings_indicator_wobble_24','ic_settings_login_activity_24','ic_settings_nearby_handoff_24','ic_settings_palette_swatches_24','ic_settings_part_resume_24','ic_settings_portrait_ambient_24','ic_settings_resilient_buffer_24','ic_settings_skeleton_breathing_24','ic_settings_top_search_24'],sorted(stroke))
  self.assertEqual(14,sum(len(paths) for paths in stroke.values()))
  first=stroke['ic_settings_action_counts_24'][0]
  self.assertEqual(('#FFFFFFFF','2','round','round'),(first['strokeColor'],first['strokeWidth'],first['strokeLineCap'],first['strokeLineJoin']))
  if not DUAL:
   # Single-input producer: the merged symbols side is not a declared input yet, so a
   # stroked original must fail closed rather than silently keep only its fill path.
   from unittest.mock import patch
   with patch.object(e,'read',side_effect=lambda repo,path:(MERGED/path).read_text(encoding='utf-8').replace('\r\n','\n')):
    with self.assertRaisesRegex(ValueError,'Unbound path attributes') as raised:e.vectors(MERGED)
   match=re.search(r'Unbound path attributes: (\w+)',str(raised.exception));self.assertIn(match.group(1),stroke)
   return
  _,files=self.generate(symbols_repo=MERGED);body=files['DesktopSettingsVectors.kt']
  for name,paths in stroke.items():
   for path in paths:self.assertIn(json.dumps(path['pathData']),body)
  # Exact stroke output: 14 stroked paths, all white, 7 at 2.0f and 7 at 1.8f.
  self.assertEqual(14,body.count('stroke=SolidColor(Color(0xFFFFFFFF))'))
  self.assertEqual(7,body.count('strokeLineWidth=2.0f'));self.assertEqual(7,body.count('strokeLineWidth=1.8f'))
  self.assertEqual(11,body.count('strokeLineCap=StrokeCap.Round'));self.assertEqual(14,body.count('strokeLineJoin=StrokeJoin.Round'))
 def test_new_xml_feature_fails_closed(self):
  from unittest.mock import patch
  old=e.read
  def changed(repo,path):return old(repo,path).replace('<path','<path android:trimPathStart="0.25"',1) if path.endswith('/ms_memory_24.xml') else old(repo,path)
  with patch.object(e,'read',side_effect=changed):
   with self.assertRaisesRegex(ValueError,'Unbound path attributes'):e.vectors(SOURCE)
 def test_active_icon_mapping_preserves_original_roles(self):
  _,files=self.generate();s=files['SettingsSemanticIconPolicy.kt']
  self.assertIn('DesktopSettingsVectors.vector(resolveSettingsMaterialSymbolResource(role))',s)
  self.assertIn('SettingsIconRole.HARDWARE_DECODER -> DesktopSettingsSymbols.ms_memory_24',s)
  self.assertNotIn('resolveMiuixSettingsSemanticIcon',s)
 def test_search_results_keep_actual_original_body(self):
  _,files=self.generate();s=files['SettingsSearchUi.kt']
  self.assertIn('results.forEachIndexed { index, result ->',s)
  self.assertIn('title = result.title',s);self.assertIn('onClick = { onResultClick(result) }',s)
  self.assertIn('rememberVectorPainter(DesktopSettingsVectors.vector(it))',s)
  self.assertNotIn('R.string',s);self.assertNotIn('R.drawable',s)
 def test_navigation_remains_original_target_no_parallel_enum(self):
  _,files=self.generate();s=files['SettingsSearchNavigationPolicy.kt']
  # BiliPaiNavKey is the Windows root's own typed route API, so the complete original
  # policy ships verbatim instead of being projected onto a parallel enum.
  original=e.read(SOURCE,e.S+'SettingsSearchNavigationPolicy.kt')
  header=f"// GENERATED from {e.S}SettingsSearchNavigationPolicy.kt; do not edit.\n// LF-normalized SHA-256: {hashlib.sha256(original.encode()).hexdigest()}\n"
  self.assertEqual(header,s[:len(header)])
  self.assertEqual(original,s[len(header):])
  self.assertIn('resolveSettingsSceneDetailFocus(result.target)',s)
  self.assertIn('else -> resolveSettingsRootCategoryForSearchTarget(result.target)',s)
  self.assertIn('?.let { category -> BiliPaiNavKey.SettingsCategory(category) }',s)
 def test_category_direct_target_changes_only_platform_route_types(self):
  _,files=self.generate();h=e.helper(SOURCE);m=h.media_extractor(SOURCE);parser=m.parser_for(SOURCE)
  original=m.function(e.read(SOURCE,e.S+'SettingsNavHierarchyPolicy.kt'),'resolveSettingsCategoryDirectTargetKey',parser)
  expected=original.replace('resolveSettingsCategoryDirectTargetKey','resolveDesktopSettingsCategoryDirectTarget').replace('BiliPaiNavKey?','SettingsSearchTarget?').replace('BiliPaiNavKey.AppearanceSettings','SettingsSearchTarget.APPEARANCE').replace('BiliPaiNavKey.PluginsSettings()','SettingsSearchTarget.PLUGINS')
  self.assertEqual(expected,m.function(files['DesktopSettingsRouteHierarchy.kt'],'resolveDesktopSettingsCategoryDirectTarget',parser))
  self.assertNotIn('BiliPaiNavKey',files['DesktopSettingsRouteHierarchy.kt'])
if __name__=='__main__':unittest.main()
