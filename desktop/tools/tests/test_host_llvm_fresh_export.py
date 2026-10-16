"""CPU-only HOST failure-delivery regressions; no cloud, compiler or native execution."""
from pathlib import Path
import copy
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[3]
IMPORTER_PATH='desktop/tools/native/import-host-llvm-source-snapshot.py'
PRODUCER_PATH='desktop/tools/native/build-mpv-rtx-core-runtime.py'

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

UPLOADER=load('fresh_host_delivery_uploader',ROOT/'desktop/tools/native/upload-mpv-rtx-core-draft.py')

class ColdExportFixture:
    def __init__(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='bilipai-host-export-fixture-')
        self.root=Path(self.temporary.name)
        for relative in (IMPORTER_PATH,PRODUCER_PATH):
            path=self.root/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes((ROOT/relative).read_bytes())
        def git(*args):
            return subprocess.run(['git','-c','core.autocrlf=false','-c','commit.gpgSign=false',
                '-c','core.hooksPath='+str(self.root/'disabled-fixture-hooks'),'-c','user.name=Fixture',
                '-c','user.email=fixture@example.invalid','-C',str(self.root),*args],
                check=True,capture_output=True).stdout
        git('init','--quiet');git('add','--',IMPORTER_PATH,PRODUCER_PATH)
        git('commit','--quiet','-m','Exact synthetic export-source binding fixture')
        self.head=git('rev-parse','HEAD').decode().strip()
        self.module=load('cold_export_fixture_importer',self.root/IMPORTER_PATH)
        self.environment={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':self.module.REPOSITORY,
            'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF_TYPE':'tag',
            'GITHUB_SHA':self.head,'GITHUB_REF_NAME':'rtx-source-cold-export-fixture',
            'GITHUB_RUN_ID':'123456789','GITHUB_RUN_ATTEMPT':'1'}
        self.workspace=self.root/'synthetic-workspace'
        self.fixed={'recipeCommit':self.module.RECIPE_COMMIT,
            'recipeArchivePrefix':'mpv-winbuild-cmake-'+self.module.RECIPE_COMMIT,
            'archives':[{'kind':'recipes','sha256':self.module.RECIPE_ARCHIVE_SHA256}]}
        self.record={'sourceCommit':self.head,'sourceTag':self.environment['GITHUB_REF_NAME'],
            'workflowRunId':123456789,'workflowRunAttempt':1,
            'assets':{leaf:{'fileName':leaf,'bytes':1,'sha256':'a'*64}for leaf in self.module.ASSETS}}
        self.reset_model(self.record,1)
        m=self.module
        self.receipt={'schema':1,'kind':'BILIPAI_HOST_LLVM_BUILD_RECEIPT',
            'state':'LOCAL_HOST_TARGET_SUCCEEDED_NATIVE_BUILD_NOT_ASSERTED',
            'ownrepoSourceCommit':self.head,'ownrepoSourceTag':self.environment['GITHUB_REF_NAME'],
            'workflowRunId':'123456789','workflowRunAttempt':'1',
            'hostLlvmSnapshotStatus':'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY',
            'hostLlvmToolchainImported':False,'hostLlvmFreshCompileExecuted':True,
            'hostLlvmImportReceiptSha256':None,'snapshotInputsSha256':m.INPUTS_SHA256,
            'containerImage':m.CONTAINER_IMAGE,'recipeCommit':m.RECIPE_COMMIT,
            'recipeArchiveSha256':m.RECIPE_ARCHIVE_SHA256,'hostLlvmActualRecipeSha256':m.LLVM_RECIPE_SHA256,
            'hostLlvmSnapshotSourceBindingSha256':self.binding_sha,
            'producerSourceSha256':m.sha((self.root/PRODUCER_PATH).read_bytes()),
            'importCollectorSourceSha256':m.sha((self.root/IMPORTER_PATH).read_bytes()),
            **{key:False for key in ('reuseReady','nativeBuildSucceeded','binaryProduced',
                                    'gpuOrDriverTested','closedSdkOrRuntimeIncluded')}}

    def reset_model(self,record,worker):
        m=self.module;w=self.workspace
        self.command=['cmake','-DTARGET_ARCH=x86_64-w64-mingw32','-DCOMPILER_TOOLCHAIN=clang',
            '-DGCC_ARCH=x86-64','-DMAKEJOBS='+str(worker),'-DCLANG_PACKAGES_LTO=ON',
            '-DCMAKE_INSTALL_PREFIX='+str(w/'clang-root'),
            '-DMINGW_INSTALL_PREFIX='+str(w/'build-x64/x86_64-w64-mingw32'),
            '-DSINGLE_SOURCE_LOCATION='+str(w/'sources'),'-DRUSTUP_LOCATION='+str(w/'rustup'),
            '-G','Ninja','--fresh','-B',str(w/'build-x64'),
            '-S',str(w/('mpv-winbuild-cmake-'+m.RECIPE_COMMIT))]
        source=str(w/'sources/llvm');build=str(w/'build-x64/toolchain/llvm-prefix/src/llvm-build')
        built={'schema':1,'gitStatusPorcelainZHex':'','sourceRemote':'https://github.com/llvm/llvm-project.git',
            'actualSourceDirectory':source,'sourceCommit':'b'*40,'sourceTree':'c'*40,
            'completeSourceLsTreeSha256':'d'*64,'actualSourceWorktreeEntries':2}
        assertions={'CMAKE_HOME_DIRECTORY':source+'/llvm','CMAKE_CACHEFILE_DIR':build,
                    'CMAKE_INSTALL_PREFIX':str(w/'clang-root')}
        files=[{'path':name,'bytes':1,'sha256':'e'*64}for name in
            ['CMakeCache.txt','build.ninja','CMakeFiles/rules.ninja','actual-source-worktree.json','actual-source-ls-tree.bin']]
        pre={'schema':2,'sourceBindingSchema':1,'stage':'AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD',
            'helperSha256':m.EXPORTER_SHA256,'actualBuildSource':built,'actualSourceDirectory':source,
            'actualBuildDirectory':build,'workspace':str(w),'resolvedConfigurationAssertions':assertions,'files':files}
        post=copy.deepcopy(pre);post['stage']='AFTER_REAL_LLVM_INSTALL_BEFORE_RECIPE_CLEANUP'
        post['files'].append({'path':'install_manifest.txt','bytes':1,'sha256':'f'*64})
        pre_sha=m.sha(m.encoded(pre));post['prebuildCaptureReceiptSha256']=pre_sha
        binding={'schema':1,'state':m.BINDING_STATE,'prebuild':built,'postinstall':built,'postcleanup':built,
            'actualSourceDirectory':source,'actualBuildDirectory':build,'resolvedConfigurationAssertions':assertions,
            'prebuildCaptureReceiptSha256':pre_sha,'postinstallCaptureReceiptSha256':m.sha(m.encoded(post))}
        self.binding_sha=m.sha(m.encoded(binding));record['actualSourceBuildBindingSha256']=self.binding_sha
        origin={'ownrepoSourceCommit':record['sourceCommit'],'ownrepoSourceTag':record['sourceTag'],
            'workflowRunId':record['workflowRunId'],'workflowRunAttempt':record['workflowRunAttempt']}
        self.manifest={'schema':2,'kind':m.KIND,'snapshotStatus':'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY',
            **origin,'sourceBindingSchema':1,'helperSha256':m.EXPORTER_SHA256,
            'recipeCommit':m.RECIPE_COMMIT,'recipeArchiveSha256':m.RECIPE_ARCHIVE_SHA256,
            'originalAbsoluteWorkspace':str(w),'originalAbsoluteInstallPrefix':str(w/'clang-root'),
            'hostTargetExitCode':0,'actualSourceBuildBinding':binding,'actualSourceBuildBindingSha256':self.binding_sha,
            'sourceCommit':built['sourceCommit'],'sourceTree':built['sourceTree'],
            'completeSourceLsTreeSha256':built['completeSourceLsTreeSha256'],'actualSourcePostBuildGitStatus':'',
            'actualInitialBuildCommand':self.command,'actualHostTargetCommand':['ninja','-C',str(w/'build-x64'),'-j'+str(worker),'llvm'],
            'actualPatchedLlvmRecipeSha256':m.LLVM_RECIPE_SHA256,
            'prebuildConfigCaptureReceipt':pre,'configCaptureReceipt':post,'resolvedConfigurationAssertions':assertions,
            **{key:False for key in ('toolchainImported','reproducible','gpuOrDriverTested',
                'closedSdkOrRuntimeIncluded','hostCompilerOrNativeTestedByExporter')}}
        self.descriptor=copy.deepcopy(self.manifest)
        self.descriptor.update(reuseReady=False,deliveryStatus='LOCAL_SNAPSHOT_ONLY_NOT_RELEASED')
        for key,leaf in [('snapshotManifest',m.ASSETS[1]),('installArchive',m.ASSETS[2]),('correspondingSourceArchive',m.ASSETS[3])]:
            self.descriptor[key]={name:record['assets'][leaf][name]for name in ('fileName','bytes','sha256')}

    def deliver(self,original_record=None):
        with patch.dict(os.environ,self.environment):
            return UPLOADER.verify_host_delivery_lifecycle(self.module,self.manifest,self.descriptor,
                self.record,self.workspace,self.fixed,self.receipt,self.root,original_record)

    def close(self):self.temporary.cleanup()


class HostLlvmFreshExportTests(unittest.TestCase):
    def setUp(self):self.fixture=ColdExportFixture()
    def tearDown(self):self.fixture.close()

    def test_current_cold_host_failure_delivery_no_longer_needs_import_admission(self):
        f=self.fixture
        with self.assertRaisesRegex(RuntimeError,'Original source worker contract differs'):
            f.module.lifecycle(f.manifest,f.descriptor,f.record,f.workspace,f.fixed,f.command)
        self.assertEqual(f.binding_sha,f.deliver())
        with self.assertRaisesRegex(RuntimeError,'Original source worker contract differs'):
            f.module.source_worker_maximum(f.record)

    def test_export_rejects_retired_or_different_workflow_identity(self):
        f=self.fixture
        for key,value in [('sourceCommit','0'*40),('sourceTag','rtx-source-other'),
                          ('workflowRunId',123456790),('workflowRunAttempt',2)]:
            with self.subTest(key=key):
                before=f.record[key];f.record[key]=value
                try:
                    with self.assertRaisesRegex(RuntimeError,'Current cold export context/receipt differs'):f.deliver()
                finally:f.record[key]=before

    def test_export_never_accepts_imported_or_success_relabelled_provenance(self):
        f=self.fixture
        changes=[('hostLlvmToolchainImported',True),('hostLlvmFreshCompileExecuted',False),
            ('nativeBuildSucceeded',True),('binaryProduced',True),('reuseReady',True),
            ('workflowRunAttempt','2'),('producerSourceSha256','0'*64),('snapshotInputsSha256','0'*64)]
        for key,value in changes:
            with self.subTest(key=key):
                old=f.receipt[key];f.receipt[key]=value
                try:
                    with self.assertRaises(RuntimeError):f.deliver()
                finally:f.receipt[key]=old
        f.receipt['hostLlvmSnapshotStatus']=f.module.IMPORT_STATUS
        with self.assertRaisesRegex(UPLOADER.DeliveryError,'independently reviewed origin'):f.deliver()

    def test_full_initial_and_llvm_target_commands_are_fixed_to_one_worker(self):
        f=self.fixture;original=f.manifest['actualInitialBuildCommand']
        for command in [original[:4]+['-DMAKEJOBS=2']+original[5:],original+['-DMAKEJOBS=1'],original[:-1]]:
            f.manifest['actualInitialBuildCommand']=command
            with self.assertRaisesRegex(RuntimeError,'one-worker recipe/command differs'):f.deliver()
        f.manifest['actualInitialBuildCommand']=original
        f.manifest['actualHostTargetCommand'][-2]='-j2'
        with self.assertRaisesRegex(RuntimeError,'Actual prebuild/install/cleanup source identity is not exact'):f.deliver()

    def test_tagged_producer_and_verifier_cannot_be_replaced_in_worktree(self):
        f=self.fixture
        for relative in (PRODUCER_PATH,IMPORTER_PATH):
            with self.subTest(path=relative):
                path=f.root/relative;before=path.read_bytes();path.write_bytes(before+b'\n# modified fixture\n')
                try:
                    with self.assertRaisesRegex(RuntimeError,'producer/verifier differs from tagged source'):f.deliver()
                finally:path.write_bytes(before)

    def test_shared_source_capture_binding_still_rejects_mutation(self):
        f=self.fixture
        f.descriptor['actualSourceBuildBinding']['postcleanup']['sourceTree']='0'*40
        with self.assertRaisesRegex(RuntimeError,'Actual prebuild/install/cleanup source identity is not exact'):f.deliver()

    def test_imported_delivery_keeps_both_original_reviewed_worker_contracts(self):
        f=self.fixture
        for original in f.module.load_catalog(ROOT)['records']:
            with self.subTest(record=original['id']):
                record=copy.deepcopy(original)
                f.reset_model(record,f.module.source_worker_maximum(record))
                f.receipt['hostLlvmSnapshotStatus']=f.module.IMPORT_STATUS
                self.assertEqual(f.binding_sha,f.deliver(record))
                changed=copy.deepcopy(record);changed['workflowRunId']+=1
                with self.assertRaisesRegex(RuntimeError,'Original source worker contract differs'):f.deliver(changed)


if __name__=='__main__':unittest.main()
