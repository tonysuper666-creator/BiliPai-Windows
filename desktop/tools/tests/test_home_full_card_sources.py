from pathlib import Path
import hashlib,importlib.util,json,sys,tempfile,unittest
sys.dont_write_bytecode=True
REPO=next(p for p in Path(__file__).resolve().parents if (p/'desktop/build.gradle.kts').is_file() and (p/'app/src/main/java').is_dir())
spec=importlib.util.spec_from_file_location('full_card_source',REPO/'desktop/tools/extract-upstream-home-full-card.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
class HomeFullCardSourcesTest(unittest.TestCase):
 def test_every_original_identity_is_in_main_inventory_with_its_original_features(self):
  rows=g.inventory(REPO);main=json.loads((REPO/'desktop/upstream-sources.json').read_text())
  byPath={r['path']:r for r in main['sources']}
  self.assertEqual(67,len(rows));self.assertEqual(len(rows),len({r['path']for r in rows}))
  for r in rows:
   self.assertEqual(hashlib.sha256((REPO/r['path']).read_text(encoding='utf-8').encode()).hexdigest(),r['sha256'])
   self.assertEqual(r['sha256'],byPath[r['path']]['sha256']);self.assertEqual(r['mode'],byPath[r['path']]['mode'])
   self.assertIn('home-full-card',byPath[r['path']]['features'])
 def test_shared_sync_owns_all_direct_originals_once(self):
  with tempfile.TemporaryDirectory(prefix='bp-full-card-source-')as temporary:
   out=Path(temporary);g.generate(REPO,out);g.generate_preferences()
   self.assertTrue({Path(p).name for p in g.DIRECT}.isdisjoint({p.name for p in(out/'generated').rglob('*.kt')}))
 def test_standalone_direct_bodies_are_verbatim_and_generation_is_deterministic(self):
  with tempfile.TemporaryDirectory(prefix='bp-full-card-source-')as temporary:
   out=Path(temporary);g.generate(REPO,out,True);g.generate_preferences()
   before={str(p.relative_to(out)):p.read_bytes()for p in out.rglob('*.kt')}
   for path in g.DIRECT:
    candidates=list((out/'generated').rglob(Path(path).name));self.assertEqual(1,len(candidates),path)
    self.assertEqual((REPO/path).read_text(encoding='utf-8'),candidates[0].read_text(encoding='utf-8').split('\n',2)[2])
   g.generate(REPO,out,True);g.generate_preferences()
   self.assertEqual(before,{str(p.relative_to(out)):p.read_bytes()for p in out.rglob('*.kt')})
 def test_original_visual_setters_keep_their_source_bodies(self):
  with tempfile.TemporaryDirectory(prefix='bp-full-card-source-')as temporary:
   out=Path(temporary);g.generate(REPO,out);g.generate_preferences()
   original=(REPO/'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt').read_text(encoding='utf-8')
   actual=next(out.rglob('DesktopOriginalHomeCardVisualSettings.kt')).read_text(encoding='utf-8')
   for name in ['setCardAnimationEnabled','setCompactVideoStatsOnCover','setHomeUpBadgesVisible','setHomeUpAvatarsVisible',
     'setHomePublishTimeVisible','setFullVideoCardContentVisible','setVideoCardLongPressActionEnabled',
     'setHomeCardDynamicTintEnabled','setHomeDurationStyle','setShowOnlineCount']:
    self.assertEqual(g.media.function(original,name,g.parser),g.media.function(actual,name,g.parser),name)
 def test_dynamic_resource_routes_only_adapt_the_platform_dispatch(self):
  with tempfile.TemporaryDirectory(prefix='bp-dynamic-route-source-')as temporary:
   out=Path(temporary);g.generate(REPO,out)
   original=(REPO/'app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt').read_text(encoding='utf-8')
   actual=next(out.rglob('AppNavigation.kt')).read_text(encoding='utf-8')
   callbacks,changes=g.desktop_dynamic_navigation(original,g.parser)
   self.assertIn(callbacks,actual)
   for callback,name in [('onCollectionClick','navigateOriginalDynamicCollection'),('onCourseClick','navigateOriginalDynamicCourse')]:
    adapted=g.media.function(actual,name,g.parser)
    body=adapted[adapted.index('{')+1:adapted.rindex('}')]
    for change in reversed([c for c in changes if c['callback']==callback]):
     self.assertEqual(1,body.count(change['after']))
     body=body.replace(change['after'],change['before'])
    expected=g.original_dynamic_navigation_body(original,callback,g.parser)
    self.assertEqual([t[0]for t in g.parser.kotlin_tokens(expected)],[t[0]for t in g.parser.kotlin_tokens(body)])
   changed=original.replace('type = "favorite",','type = "favorite_season",')
   with self.assertRaises(AssertionError):g.desktop_dynamic_navigation(changed,g.parser)
if __name__=='__main__':unittest.main()
