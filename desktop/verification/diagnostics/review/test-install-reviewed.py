"""Actual disk rehearsals in owned temporary clones, never apply to the product."""
from pathlib import Path
import copy, importlib.util, json, shutil, sys, tempfile, unittest
from unittest.mock import patch
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('reviewinstall', HERE/'install-reviewed.py')
install = importlib.util.module_from_spec(spec); spec.loader.exec_module(install)
PLAN = install.load_plan()
RESULTS = []

class InstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='installer-', dir=HERE)
        self.root = Path(self.temp.name)/'repo'; self.root.mkdir()
        required = {e['path'] for e in PLAN['originalInputs']+PLAN['platformInputs']}
        required.update(e['path'] for e in PLAN['files'] if e['baselineSha256Bytes'])
        for relative in required:
            source = install.DEFAULT_REPO/relative
            target = self.root/relative
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(install.safe(source).read_bytes())
        install.verify(self.root, PLAN)
    def tearDown(self):
        # The verified absolute deletion target is wholly beneath the task-owned lane.
        Path(self.temp.name).resolve().relative_to(HERE.resolve())
        self.temp.cleanup()
    def state(self):
        return {e['path']:install.safe(self.root/e['path']).read_bytes() if install.safe(self.root/e['path']).exists() else None for e in PLAN['files']}
    def test_default_is_read_only(self):
        before=self.state(); result=install.install(self.root, PLAN)
        self.assertTrue(result['readOnly']); self.assertEqual(before,self.state())
    def test_apply_and_idempotent(self):
        self.assertEqual(10,install.install(self.root,PLAN,True)['changedFiles'])
        for e in PLAN['files']:self.assertEqual(e['payloadSha256Bytes'],install.digest(self.root/e['path']))
        self.assertEqual(0,install.install(self.root,PLAN,True)['changedFiles'])
    def test_baseline_conflict_rejects_before_any_write(self):
        target=self.root/PLAN['files'][-1]['path'];target.write_text('Root concurrent edit',encoding='utf-8')
        before=self.state()
        with self.assertRaisesRegex(ValueError,'baseline changed'):install.install(self.root,PLAN,True)
        self.assertEqual(before,self.state())
    def test_original_source_changed_rejects_before_any_write(self):
        target=self.root/PLAN['originalInputs'][0]['path'];target.write_text('changed original',encoding='utf-8')
        before=self.state()
        with self.assertRaisesRegex(ValueError,'Original source/resource changed'):install.install(self.root,PLAN,True)
        self.assertEqual(before,self.state())
    def test_payload_hash_rejects(self):
        plan=copy.deepcopy(PLAN);plan['files'][0]['payloadSha256Bytes']='0'*64;before=self.state()
        with self.assertRaisesRegex(ValueError,'Staged payload changed'):install.install(self.root,plan,True)
        self.assertEqual(before,self.state())
    def test_mid_commit_failure_rolls_back_exact_bytes(self):
        before=self.state();calls=0;actual=install.atomic_write
        def failing(target,content):
            nonlocal calls
            calls+=1
            if calls==4:raise OSError('fixture atomic replacement failed')
            actual(target,content)
        with patch.object(install,'atomic_write',failing):
            with self.assertRaisesRegex(OSError,'fixture atomic'):install.install(self.root,PLAN,True)
        self.assertEqual(before,self.state())
    def test_write_time_baseline_rechecked(self):
        before=self.state();actual=install.verify
        target=self.root/PLAN['files'][0]['path']
        def racing(repo,plan):
            result=actual(repo,plan);target.parent.mkdir(parents=True,exist_ok=True);target.write_text('late Root edit',encoding='utf-8');return result
        with patch.object(install,'verify',racing):
            with self.assertRaisesRegex(ValueError,'changed after preflight'):install.install(self.root,PLAN,True)
        now=self.state();self.assertEqual(b'late Root edit',now.pop(PLAN['files'][0]['path']));before.pop(PLAN['files'][0]['path']);self.assertEqual(before,now)
    def test_parent_traversal_rejected(self):
        plan=copy.deepcopy(PLAN);plan['files'][0]['path']='../outside.kt'
        with self.assertRaisesRegex(ValueError,'Invalid reviewed relative path'):install.install(self.root,plan,True)
    def test_sequential_boundary_delta_and_idempotence(self):
        path=HERE/'boundary-delta/install-boundary-delta.py'
        spec=importlib.util.spec_from_file_location('sequentialdelta',path)
        module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        plan=module.load_plan()
        with self.assertRaisesRegex(ValueError,'baseline changed'):module.installer.install(self.root,plan,True)
        install.install(self.root,PLAN,True)
        self.assertEqual(2,module.installer.install(self.root,plan,True)['changedFiles'])
        self.assertEqual(0,module.installer.install(self.root,plan,True)['changedFiles'])
        for e in plan['files']:self.assertEqual(e['payloadSha256Bytes'],install.digest(self.root/e['path']))

if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(InstallTest)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    output=dict(passed=result.wasSuccessful(), tests=result.testsRun, failures=len(result.failures),errors=len(result.errors),
                targets=10, temporaryDiskRehearsal=True,mainApplied=False,sharedGradleInvoked=False)
    (HERE/'installer-proof.json').write_text(json.dumps(output,indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)
