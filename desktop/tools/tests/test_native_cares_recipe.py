"""Complete fixed recipe transform and real offline shallow-clone regressions.

Fixture: shinchiro/mpv-winbuild-cmake cd1edc11dc6887a50f705717619d879f5a93a488,
packages/c-ares.cmake from the hash-verified complete published source archive.
Git fixtures are local repositories; no network, CMake compiler, JVM or GPU runs.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import shlex
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
INPUTS=ROOT/'desktop/third-party/libmpv/build/rtx-present-v1'
FIXTURE=ROOT/'desktop/tools/tests/fixtures/c-ares-cd1edc11.cmake'
spec=importlib.util.spec_from_file_location('cares_recipe_producer',ROOT/'desktop/tools/native/build-mpv-rtx-core-runtime.py')
producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)


class CaresRecipeSelectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='bp-cares-recipe-')
        self.root=Path(self.temporary.name).resolve()
        self.assertTrue(self.root.name.startswith('bp-cares-recipe-'))
        self.addCleanup(self.temporary.cleanup)
        self.edits_raw=(INPUTS/'recipe-edits.json').read_bytes()
        self.edits=json.loads(self.edits_raw)
        rows=[x for x in self.edits['targets']if x['targetPath']=='build-recipes/packages/c-ares.cmake']
        self.assertEqual(1,len(rows));self.row=rows[0]

    def transform(self):
        recipes=self.root/'recipes';path=recipes/'packages/c-ares.cmake';path.parent.mkdir(parents=True)
        path.write_bytes(FIXTURE.read_bytes());producer.apply_recipes(recipes,[self.row]);return path.read_bytes()

    def test_complete_fixed_original_forward_and_inverse(self):
        old=FIXTURE.read_bytes();self.assertEqual(938,len(old))
        self.assertEqual('ff158ffb4add510160a78eef6473625d965ebf4bd57b0fc1e23275fcb48c6013',hashlib.sha256(old).hexdigest())
        new=self.transform();self.assertEqual(952,len(new))
        self.assertEqual('f538cedd2317d3a581221e6fa8bb75940de324bdfab802c42614ac59a03567c1',hashlib.sha256(new).hexdigest())
        edit=self.row['rawEdits'][0]
        self.assertEqual(old,new.replace(edit['new'].encode(),edit['old'].encode()))
        self.assertIn(b'    GIT_TAG main\n',new)

    def test_original_recipe_drift_rejected_without_mutation(self):
        recipes=self.root/'recipes';path=recipes/'packages/c-ares.cmake';path.parent.mkdir(parents=True)
        drift=FIXTURE.read_bytes()+b'# drift\n';path.write_bytes(drift)
        with self.assertRaisesRegex(RuntimeError,'Wrong complete recipe bytes'):
            producer.apply_recipes(recipes,[self.row])
        self.assertEqual(drift,path.read_bytes())

    def test_producer_recipe_digest_and_complete_fixed_map_agree(self):
        source=(ROOT/'desktop/tools/native/build-mpv-rtx-core-runtime.py').read_text(encoding='utf-8')
        self.assertIn(hashlib.sha256(self.edits_raw).hexdigest(),source)
        fixed_raw=(INPUTS/'fixed-inputs.json').read_bytes()
        self.assertIn(hashlib.sha256(fixed_raw).hexdigest(),source)
        fixed=json.loads(fixed_raw)
        self.assertEqual(fixed['expectedPatchedRecipeSha256'],{x['targetPath']:x['afterSha256Bytes']for x in self.edits['targets']})
        self.assertEqual(9,len(self.edits['targets']))

    def git(self,*args,check=True):
        env=dict(os.environ,GIT_TERMINAL_PROMPT='0')
        return subprocess.run(['git','-c','commit.gpgsign=false','-c','user.name=BiliPai offline fixture','-c','user.email=offline-fixture@example.invalid',*map(str,args)],capture_output=True,text=True,env=env,check=check)

    def origin(self,with_main=True):
        origin=self.root/'origin';origin.mkdir()
        self.git('init','-b','main'if with_main else'coverity_scan',origin)
        (origin/'marker.txt').write_text('main'if with_main else'coverity',encoding='ascii')
        self.git('-C',origin,'add','marker.txt');self.git('-C',origin,'commit','-m','offline main fixture')
        main=self.git('-C',origin,'rev-parse','HEAD').stdout.strip()
        if with_main:
            self.git('-C',origin,'checkout','-b','coverity_scan')
            (origin/'marker.txt').write_text('coverity',encoding='ascii')
            self.git('-C',origin,'commit','-am','offline default branch fixture')
        return origin,main

    def flags(self,raw):
        match=re.search(rb'^    GIT_CLONE_FLAGS "([^"\n]+)"$',raw,re.M)
        self.assertIsNotNone(match)
        return shlex.split(match.group(1).decode('ascii'))

    def test_original_shallow_clone_reproduces_missing_main(self):
        origin,_=self.origin();dest=self.root/'old-clone'
        self.git('clone',*self.flags(FIXTURE.read_bytes()),origin.as_uri(),dest)
        result=self.git('-C',dest,'checkout','main',check=False)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('main',result.stderr)
        self.assertEqual('coverity',(dest/'marker.txt').read_text(encoding='ascii'))

    def test_selected_main_clone_ignores_remote_default_branch(self):
        origin,main=self.origin();dest=self.root/'fixed-clone'
        self.git('clone',*self.flags(self.transform()),origin.as_uri(),dest)
        self.git('-C',dest,'checkout','main')
        self.assertEqual(main,self.git('-C',dest,'rev-parse','HEAD').stdout.strip())
        self.assertEqual('main',(dest/'marker.txt').read_text(encoding='ascii'))
        self.assertEqual('true',self.git('-C',dest,'rev-parse','--is-shallow-repository').stdout.strip())
        self.assertEqual('1',self.git('-C',dest,'rev-list','--count','HEAD').stdout.strip())
        self.assertEqual('',self.git('-C',dest,'status','--porcelain','--untracked-files=all').stdout.strip())

    def test_missing_requested_main_rejects_without_default_fallback(self):
        origin,_=self.origin(with_main=False);dest=self.root/'missing-main'
        result=self.git('clone',*self.flags(self.transform()),origin.as_uri(),dest,check=False)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse((dest/'marker.txt').exists())


if __name__=='__main__':unittest.main()
