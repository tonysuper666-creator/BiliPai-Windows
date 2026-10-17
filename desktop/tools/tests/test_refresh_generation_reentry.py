from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
TOOLS=REPO/'desktop/tools'
sys.path.insert(0,str(TOOLS))
import v036_comment_refresh_dependencies as refresh


def snapshot(root):
    return {p.relative_to(root).as_posix():p.read_bytes() for p in root.rglob('*') if p.is_file()}


class RefreshGenerationReentryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory(prefix='bp-home-reentry-seed-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root=Path(cls.temporary.name)
        cls.seed=cls.root/'complete'
        cls.seed_tests=cls.root/'tests'
        cls.base=cls.root/'base'
        cls.run_script('extract-upstream-home-core-refresh.py',cls.seed,cls.seed_tests)
        cls.run_script('extract-upstream-home-page.py',cls.base)

    @classmethod
    def run_script(cls,name,output,test_output=None):
        args=[sys.executable,'-X','utf8','-B',str(TOOLS/name),'--repo',str(REPO),'--output',str(output)]
        if test_output is not None:args+=['--test-output',str(test_output)]
        result=subprocess.run(args,cwd=REPO,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',PYTHONIOENCODING='utf-8'),capture_output=True,text=True,encoding='utf-8',errors='replace')
        if result.returncode:raise AssertionError(name+' failed: '+result.stderr[-5000:])

    def outputs(self):
        temporary=tempfile.TemporaryDirectory(prefix='bp-home-reentry-case-')
        self.addCleanup(temporary.cleanup)
        root=Path(temporary.name);out=root/'main';tests=root/'tests'
        shutil.copytree(self.base,out)
        core=out/'com/android/purebilibili/core/ui'
        shutil.copyfile(self.seed/'com/android/purebilibili/core/ui/AdaptivePullToRefreshPolicy.kt',core/'AdaptivePullToRefreshPolicy.kt')
        shutil.copyfile(self.seed/'v036-refresh-closure-proof.json',out/'v036-refresh-closure-proof.json')
        return out,tests

    def test_actual_complete_home_wrapper_can_run_twice_with_identical_outputs(self):
        with tempfile.TemporaryDirectory(prefix='bp-home-reentry-cli-')as temporary:
            out=Path(temporary)/'main';tests=Path(temporary)/'tests'
            shutil.copytree(self.seed,out);shutil.copytree(self.seed_tests,tests)
            expected=snapshot(out);expected_tests=snapshot(tests)
            self.run_script('extract-upstream-home-core-refresh.py',out,tests)
            self.assertEqual(expected,snapshot(out))
            self.assertEqual(expected_tests,snapshot(tests))

    def test_exact_previous_policy_and_receipt_allow_the_same_sole_producer(self):
        out,tests=self.outputs()
        refresh.extend_same_home_producer(out,tests)
        self.assertEqual(snapshot(self.seed),snapshot(out))
        self.assertEqual(snapshot(self.seed_tests),snapshot(tests))

    def test_unknown_existing_policy_is_rejected_before_any_output_is_modified(self):
        out,tests=self.outputs();policy=out/'com/android/purebilibili/core/ui/AdaptivePullToRefreshPolicy.kt'
        policy.write_bytes(policy.read_bytes()+b'\n');before=snapshot(out)
        with self.assertRaisesRegex(ValueError,'Unexpected second full refresh policy producer'):
            refresh.extend_same_home_producer(out,tests)
        self.assertEqual(before,snapshot(out));self.assertFalse(tests.exists())

    def test_matching_policy_without_its_completed_receipt_remains_rejected(self):
        out,tests=self.outputs();(out/'v036-refresh-closure-proof.json').unlink();before=snapshot(out)
        with self.assertRaisesRegex(ValueError,'Unexpected second full refresh policy producer'):
            refresh.extend_same_home_producer(out,tests)
        self.assertEqual(before,snapshot(out));self.assertFalse(tests.exists())

    def test_receipt_from_a_different_source_cannot_authorize_policy_reuse(self):
        out,tests=self.outputs();receipt=out/'v036-refresh-closure-proof.json'
        value=json.loads(receipt.read_bytes());value['upstreamCommit']='0'*40
        receipt.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n');before=snapshot(out)
        with self.assertRaisesRegex(ValueError,'Unexpected second full refresh policy producer'):
            refresh.extend_same_home_producer(out,tests)
        self.assertEqual(before,snapshot(out));self.assertFalse(tests.exists())

    def test_existing_original_wrapper_pins_are_still_checked_before_reuse(self):
        out,tests=self.outputs();box=out/'com/android/purebilibili/core/ui/AdaptivePullToRefreshBox.kt'
        box.write_bytes(box.read_bytes()+b'\n');before=snapshot(out)
        with self.assertRaisesRegex(ValueError,'Unknown existing Home refresh output'):
            refresh.extend_same_home_producer(out,tests)
        self.assertEqual(before,snapshot(out));self.assertFalse(tests.exists())


if __name__=='__main__':
    unittest.main()
