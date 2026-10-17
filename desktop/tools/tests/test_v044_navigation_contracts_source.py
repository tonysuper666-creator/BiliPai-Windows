"""Whole original navigation regressions against the actual sole Windows codec."""
from pathlib import Path
import importlib.util,json,re,shutil,sys,tempfile,unittest
REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v044_favorite_search as search
import v044_navigation_contracts as navigation

class WholeOriginalNavigationSourceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name)
  spec=importlib.util.spec_from_file_location('whole_navigation_sole_producer',REPO/'desktop/tools/extract-upstream-listen-video-root.py')
  cls.owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.owner)
  cls.owner.produce(REPO,cls.root/'main',cls.root/'tests')
  cls.raw=search.fixed(REPO)
  cls.body=(cls.root/'tests/com/android/purebilibili/navigation3'/navigation.TEST).read_bytes()
  cls.receipt=json.loads((cls.root/'tests/v044-whole-navigation-test-proof.json').read_bytes())
 @classmethod
 def tearDownClass(cls):cls.temp.cleanup()

 def test_the_existing_real_producer_emits_the_entire_original_suite_unchanged(self):
  self.assertEqual(self.raw[navigation.TEST].encode('utf8'),self.body)
  self.assertEqual(22,self.body.count(b'@Test'))
  self.assertIn(b'class BiliPaiNavKeyMappingPolicyTest {',self.body)

 def test_both_actual_production_codec_files_are_complete_current_originals(self):
  for name in ['BiliPaiNavKey.kt','BiliPaiNavKeyMappingPolicy.kt']:
   file=self.root/'main/com/android/purebilibili/navigation3'/name
   self.assertEqual(self.raw[name].encode('utf8'),file.read_bytes())

 def test_receipt_binds_the_whole_output_without_claiming_execution(self):
  self.assertEqual(search.COMMIT,self.receipt['upstreamCommit'])
  self.assertEqual(search.MANIFEST_SHA256,self.receipt['sourceManifestSHA256'])
  self.assertEqual(search.sha(self.body),self.receipt['originalSuiteSHA256'])
  self.assertEqual(search.sha(self.body),self.receipt['outputSHA256'])
  self.assertEqual(navigation.CLASS,self.receipt['testClass'])
  self.assertEqual(22,self.receipt['originalCasesSelected'])
  self.assertTrue(self.receipt['wholeOriginalSuite']);self.assertFalse(self.receipt['testsExecuted'])

 def test_prior_original_search_and_audio_methods_are_preserved_as_their_own_classes(self):
  for name,method in [('V044FavoriteSearchOriginalPolicyTest.kt','personalListSearchKeysRoundTripThroughLegacyRoutes'),
                       ('V044AudioCollectionRouteOriginalTest.kt','audioCollectionKeysPreserveSourceAndTitleAcrossRouteRestoration')]:
   body=(self.root/'tests/com/android/purebilibili/navigation3'/name).read_text(encoding='utf8')
   self.assertEqual(1,body.count('@Test'))
   marker='    @Test\n    fun '+method+'() {'
   first=self.raw[navigation.TEST].index(marker)
   last=self.raw[navigation.TEST].index('\n    }',first)+len('\n    }')
   self.assertIn(self.raw[navigation.TEST][first:last],body)

 def test_original_video_comment_audio_weekly_and_saveable_session_cases_remain_in_the_suite(self):
  names=re.findall(r'(?m)^    fun (.+?)\(',self.body.decode('utf8'))
  self.assertEqual(22,len(names));self.assertEqual(22,len(set(names)))
  for name in ['videoRoute_preservesNavigationArguments','dynamicDetailRoute_preservesCommentArguments',
   'commentDetailRoute_roundTripsThroughLegacyRoute','audioCollectionKeysPreserveSourceAndTitleAcrossRouteRestoration',
   'weeklySeriesKeysPreservePeriodAcrossRouteRestoration','`navigation state keys distinguish detail sessions for the same video`']:
   self.assertIn(name,names)
  imports=[line for line in self.body.decode('utf8').splitlines() if line.startswith('import ')]
  self.assertTrue(all(line.startswith(('import kotlin.test.','import com.android.purebilibili.navigation.ScreenRoutes')) for line in imports))

 def test_modified_original_and_overlapping_output_are_rejected_before_writing(self):
  before={p.name:p.read_bytes() for p in (REPO/search.ARCHIVE).iterdir()}
  with self.assertRaises(ValueError):navigation.emit(REPO,REPO/search.ARCHIVE)
  self.assertEqual(before,{p.name:p.read_bytes() for p in (REPO/search.ARCHIVE).iterdir()})
  with tempfile.TemporaryDirectory() as tmp:
   repo=Path(tmp);shutil.copytree(REPO/search.ARCHIVE,repo/search.ARCHIVE)
   file=repo/search.ARCHIVE/navigation.TEST;file.write_bytes(file.read_bytes()+b'\n')
   with self.assertRaises(ValueError):navigation.emit(repo,repo/'out')
   self.assertFalse((repo/'out').exists())

 def test_the_same_gradle_producer_uses_the_existing_test_destination(self):
  path='desktop/build.gradle.kts';body=(REPO/path).read_text(encoding='utf8')
  line='tasks.named("extractOriginalListenVideoPages") { inputs.file("tools/v044_navigation_contracts.py") }\n'
  self.assertEqual(1,body.count(line))
  self.assertEqual(1,body.count('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-listen-video-policy-tests")) }'))
  producer=(REPO/'desktop/tools/v044_favorite_search.py').read_text(encoding='utf8')
  self.assertEqual(1,producer.count('navigation_suite = emit_whole_navigation_suite(repo, output)'))
  workflow=(REPO/'.github/workflows/windows-source-validation.yml').read_text(encoding='utf8')
  self.assertEqual(1,workflow.count('test_v044_navigation_contracts_source.py -v'))

 def test_inventory_marks_complete_codec_and_complete_original_suite_without_promoting_android_graph(self):
  rows=json.loads((REPO/'desktop/tools/compatibility-source-inputs.json').read_bytes())['inputs']
  for name in ['BiliPaiNavKey.kt','BiliPaiNavKeyMappingPolicy.kt',navigation.TEST]:
   row=next(row for row in rows if row['localPath']==search.ARCHIVE+'/'+name)
   self.assertFalse(row['referenceOnly']);self.assertFalse(row['partialReuse'])
  graph=next(row for row in rows if row['localPath']==search.ARCHIVE+'/AppNavigation.kt')
  self.assertTrue(graph['referenceOnly'] or graph['partialReuse'])

if __name__=='__main__':unittest.main()
