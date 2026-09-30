"""Actual temp-disk installer cases; only copies of source files are modified."""
from pathlib import Path
import copy, hashlib, importlib.util, json, sys, tempfile, unittest
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
spec=importlib.util.spec_from_file_location('installer_fixture',HERE/'install-reviewed.py')
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)
ORIGINAL_ATOMIC=installer.atomic_write
def digest(p):return hashlib.sha256(installer.safe(p).read_bytes()).hexdigest()
class InstallerFixture(unittest.TestCase):
 def setUp(self):
  self.directory=Path(tempfile.mkdtemp(prefix='owned-installer-test-',dir=HERE/'proof'))
  self.root=self.directory/'fake-product';self.root.mkdir()
  self.capsule=self.directory/'review-capsule';self.capsule.mkdir()
  installer.HERE=self.capsule;installer.atomic_write=ORIGINAL_ATOMIC
  self.plan=json.loads((HERE/'install-plan.json').read_text(encoding='utf-8'))
  self.paths=set(i['path'] for i in self.plan['originalInputs']+self.plan['platformInputs'])
  self.paths.update(i['path'] for i in self.plan['files'] if i['baselineSha256Bytes'] is not None)
  for name in self.paths:
   target=installer.safe(self.root/name);target.parent.mkdir(parents=True,exist_ok=True)
   installer.safe(target).write_bytes(installer.safe(REPO/name).read_bytes())
  for item in self.plan['files']:
   target=installer.safe(self.capsule/'payload'/item['path']);target.parent.mkdir(parents=True,exist_ok=True)
   installer.safe(target).write_bytes(installer.safe(HERE/'payload'/item['path']).read_bytes())
  (self.capsule/'install-plan.json').write_bytes((HERE/'install-plan.json').read_bytes())
  self.before=self.snapshot()
 def snapshot(self):return {p.relative_to(installer.safe(self.root)).as_posix():digest(p) for p in installer.safe(self.root).rglob('*') if p.is_file()}
 def assertRejectedBeforeWrites(self,plan=None):
  before=self.snapshot()
  with self.assertRaises(ValueError):installer.install(self.root,plan or self.plan,apply=True)
  self.assertEqual(self.snapshot(),before)
 def test_dry_run_verifies_real_frozen_inputs_and_changes_no_files(self):
  result=installer.install(self.root,self.plan)
  self.assertTrue(result['readOnly']);self.assertEqual(result['verifiedFiles'],10)
  self.assertEqual(result['changedFiles'],0);self.assertEqual(self.snapshot(),self.before)
 def test_current_shell_change_refuses_whole_install_without_overwrite(self):
  target=installer.safe(self.root/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt')
  target.write_bytes(target.read_bytes()+b'\n// fixture concurrent Root change\n')
  self.assertRejectedBeforeWrites()
 def test_changed_original_policy_refuses_before_any_product_write(self):
  target=installer.safe(self.root/'network-core/src/main/java/com/android/purebilibili/core/network/policy/NetworkProxyPolicy.kt')
  target.write_bytes(target.read_bytes()+b'\n// fixture original identity change\n')
  self.assertRejectedBeforeWrites()
 def test_tampered_staged_binding_refuses_before_any_product_write(self):
  target=installer.safe(self.capsule/'payload'/self.plan['files'][1]['path'])
  target.write_bytes(target.read_bytes()+b'\n// fixture payload tamper\n')
  self.assertRejectedBeforeWrites()
 def test_wrong_dependency_identity_is_rejected_without_mutating_cache(self):
  plan=copy.deepcopy(self.plan);plan['dependencies'][0]['sha256Bytes']='0'*64
  self.assertRejectedBeforeWrites(plan)
 def test_exact_install_and_idempotent_reapply_on_copied_product(self):
  first=installer.install(self.root,self.plan,apply=True)
  self.assertEqual(first['changedFiles'],10)
  for item in self.plan['files']:self.assertEqual(digest(self.root/item['path']),item['payloadSha256Bytes'])
  second=installer.install(self.root,self.plan,apply=True)
  self.assertEqual(second['changedFiles'],0)
  self.assertFalse(first['compilationOrRuntimeExecuted'])
 def test_actual_temp_write_failure_rolls_back_previously_installed_files(self):
  calls=0
  def fail_third(target,content):
   nonlocal calls;calls+=1
   if calls==3:raise OSError('fixture disk replacement failure')
   ORIGINAL_ATOMIC(target,content)
  installer.atomic_write=fail_third
  with self.assertRaises(OSError):installer.install(self.root,self.plan,apply=True)
  self.assertEqual(self.snapshot(),self.before)
  self.assertFalse(list(installer.safe(self.root).rglob('network-proxy-install-*.tmp')))
 def test_reviewed_plan_hash_rejects_modified_capsule(self):
  path=self.capsule/'install-plan.json';path.write_bytes(path.read_bytes()+b'\n')
  with self.assertRaises(ValueError):installer.load_plan()
  self.assertEqual(self.snapshot(),self.before)
 def test_traversal_plan_target_is_rejected_before_product_writes(self):
  plan=copy.deepcopy(self.plan);plan['files'][0]['path']='../outside.py'
  self.assertRejectedBeforeWrites(plan)
 def tearDown(self):installer.HERE=HERE;installer.atomic_write=ORIGINAL_ATOMIC
if __name__=='__main__':
 (HERE/'proof').mkdir(exist_ok=True)
 suite=unittest.defaultTestLoader.loadTestsFromTestCase(InstallerFixture)
 result=unittest.TextTestRunner(verbosity=2).run(suite)
 (HERE/'installer-test-evidence.json').write_text(json.dumps(dict(passed=result.wasSuccessful(),
  testsRun=result.testsRun,failures=len(result.failures),errors=len(result.errors),
  onlyOwnedTempProductCopiesWritten=True,realProductFilesModified=False,sharedGradleInvoked=False,
  nativeWindowCreated=False,accountOrNetworkRequests=False),indent=2),encoding='utf-8',newline='\n')
 sys.exit(0 if result.wasSuccessful() else 1)
