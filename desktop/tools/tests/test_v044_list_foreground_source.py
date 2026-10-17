"""Actual retained list UI, whole original foreground effects and source receipts."""
from pathlib import Path
import importlib.util,json,re,shutil,subprocess,sys,tempfile,unittest
REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v044_list_foreground as foreground
from v025_source_paths import canonical_source

class OriginalListForegroundSourceTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name)
  spec=importlib.util.spec_from_file_location('actual_foreground_list_owner',REPO/'desktop/tools/extract-upstream-favorites.py')
  cls.owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.owner)
  cls.rows=cls.owner.generate(REPO,cls.root/'main',True,cls.root/'tests')
  cls.row=next(row for row in cls.rows if row.get('v044ListForegroundAdvance'))
  cls.proof=cls.row['v044ListForegroundAdvance']
  cls.body=(cls.root/'main/com/android/purebilibili/feature/list/CommonListScreen.kt').read_text(encoding='utf8')
  cls.before=foreground.restore_predecessor(cls.body,cls.proof)
 @classmethod
 def tearDownClass(cls):cls.temp.cleanup()

 def test_actual_sole_producer_has_a_complete_exact_retained_predecessor_inverse(self):
  self.assertEqual(foreground.BEFORE_UI_SHA256,foreground.sha(self.before))
  body,proof=foreground.advance_ui(REPO,self.before)
  self.assertEqual(self.body,body);self.assertEqual(self.proof,proof)
  actual=next(row for row in self.row['outputs'] if row['path'].endswith('/CommonListScreen.kt'))
  self.assertEqual(foreground.sha(body),actual['sha256LfUtf8'])

 def test_both_paging_effects_are_complete_current_original_segments(self):
  raw=foreground.fixed_sources(REPO)[0]['CommonListScreen.kt']
  segments=[row for row in self.proof['originalSegments'] if 'effect' in row['label']]
  self.assertEqual(2,len(segments))
  for row in segments:
   self.assertEqual(row['originalText'],raw[row['originalOffset']:row['originalOffset']+len(row['originalText'])])
   self.assertEqual(row['originalSHA256'],foreground.sha(row['originalText']))
   self.assertIn(row['originalText'],self.body)

 def test_each_real_common_list_caller_forwards_physical_foreground(self):
  syntax=foreground.parser(REPO);a,b=foreground.span(self.body,'fun','CommonListContent',syntax)
  calls=list(re.finditer(r'(?m)^([ ]*)CommonListContent\(\n',self.body[:a]))
  self.assertEqual(3,len(calls))
  for call in calls:self.assertTrue(self.body[call.end():].startswith(call[1]+'    allowAutomaticLoadMore = isCurrentPage,\n'))
  self.assertEqual(1,self.body[a:b].count('    allowAutomaticLoadMore: Boolean = true,\n'))
  self.assertTrue(self.proof['physicalForegroundOwnsAutomaticPaging'])

 def test_foreground_is_both_an_effect_key_and_a_request_gate(self):
  self.assertIn('            allowAutomaticLoadMore,\n',self.body)
  self.assertIn('allowAutomaticLoadMore && searchPaginationFallbackEnabled &&',self.body)
  self.assertIn('LaunchedEffect(shouldLoadMore.value, items.size, loadMoreError, isLoading, error, allowAutomaticLoadMore)',self.body)
  self.assertIn('allowAutomaticLoadMore && shouldLoadMore.value && loadMoreError == null && !isLoading &&',self.body)

 def test_manual_retry_root_authentication_and_subscription_renderer_are_preserved(self):
  self.assertEqual(self.before.count('onFirstReadAuthenticationRequired'),self.body.count('onFirstReadAuthenticationRequired'))
  self.assertEqual(self.before.count('detailVm.retryLoadMore()'),self.body.count('detailVm.retryLoadMore()'))
  syntax=foreground.parser(REPO)
  for name in ['FavoriteSubscribedFolderList','FavoriteSubscribedFolderRow','FavoriteSubscribedFolderPreview']:
   a,b=foreground.span(self.body,'fun',name,syntax);x,y=foreground.span(self.before,'fun',name,syntax)
   self.assertEqual(self.before[x:y],self.body[a:b])
  self.assertTrue(self.proof['manualRetryAndRootAdmissionUnchanged'])

 def test_all_four_original_policy_cases_target_the_identical_existing_policy(self):
  name='app/src/main/java/com/android/purebilibili/feature/list/CommonListPaginationPolicy.kt'
  # The current upstream policy has these exact same bytes as the owned repo's
  # reachable ancestor; a clean checkout need not contain an upstream Git object.
  original=subprocess.check_output(['git','-C',str(REPO),'show','79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40:'+name]).decode('utf8')
  self.assertEqual('e2fd522bbe5f396206f601266ece033c756dc76abdbf1ca15dfb6088676b3b61',foreground.sha(original))
  self.assertEqual(original,canonical_source(REPO,name).read_text(encoding='utf8'))
  test=(self.root/'tests/com/android/purebilibili/feature/list'/foreground.TEST).read_text(encoding='utf8')
  self.assertEqual(foreground.fixed_test(REPO),test);self.assertEqual(4,test.count('@Test'))

 def test_changed_predecessor_test_archive_and_overlapping_output_fail_closed(self):
  with self.assertRaises(ValueError):foreground.advance_ui(REPO,self.before+'\n')
  with self.assertRaises(ValueError):foreground.validate_outputs(REPO,REPO/foreground.ARCHIVE)
  for name in [foreground.TEST,'manifest.json']:
   with self.subTest(name=name),tempfile.TemporaryDirectory() as tmp:
    repo=Path(tmp);shutil.copytree(REPO/foreground.ARCHIVE,repo/foreground.ARCHIVE)
    path=repo/foreground.ARCHIVE/name;path.write_bytes(path.read_bytes()+b'\n')
    with self.assertRaises(ValueError):foreground.fixed_test(repo)

 def test_existing_gradle_owner_tracks_the_new_inputs_and_unchanged_test_destination(self):
  build=(REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
  self.assertEqual(1,build.count('inputs.file("tools/v044_list_foreground.py")'))
  self.assertEqual(1,build.count('inputs.dir("upstream-slices/v044-list-foreground")'))
  self.assertIn('"--test-output", layout.buildDirectory.dir("generated/original-favorite-policy-tests")',build)
  workflow=(REPO/'.github/workflows/windows-source-validation.yml').read_text(encoding='utf8')
  self.assertEqual(1,workflow.count('test_v044_list_foreground_source.py -v'))

if __name__=='__main__':unittest.main()
