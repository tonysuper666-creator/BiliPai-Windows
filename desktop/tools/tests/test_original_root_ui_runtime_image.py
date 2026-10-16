"""Structural byte fixtures only: no executable Java, real source or hardware proof."""
from pathlib import Path
from unittest.mock import patch
import copy
import hashlib
import json
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import original_root_ui_runtime_image as image

SOURCE = 'a' * 40


def pin(raw): return dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def packed(path, files):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, raw in files.items(): archive.writestr(name, raw)


class RuntimeImageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bilipai-runtime-image-')
        self.root = Path(self.temp.name); self.candidate = self.root / 'candidate'; self.candidate.mkdir()
        self.output = self.root / 'private-runtime'; self.package = self.root / 'candidate.zip'; self.jdk = self.root / 'publisher.zip'
        self.runtime = {'release': b'JAVA_VERSION="21.0.12.1"\nMODULES="java.base java.desktop jdk.httpserver"\n',
            'lib/modules': b'actual jlink-produced module image, not the full JDK module image',
            'bin/jli.dll': b'fixed publisher jli', 'bin/server/jvm.dll': b'fixed publisher JVM',
            'bin/java.dll': b'fixed publisher Java DLL', 'legal/java.base/LICENSE': b'fixed publisher notice'}
        self.full_release = b'JAVA_VERSION="21.0.12.1"\nIMPLEMENTOR="publisher fixture"\n'
        self.launcher = b'publisher launcher byte fixture; not executable'
        full_jdk = dict(self.runtime);full_jdk.update(release=self.full_release)
        full_jdk['lib/modules'] = b'full JDK modules must not enter the fixture image'
        full_jdk['bin/java.exe'] = self.launcher
        full_jdk['bin/javac.exe'] = b'compiler must not enter the fixture image'
        packed(self.jdk, {'publisher-root/' + name: raw for name, raw in full_jdk.items()})
        self.patch = patch.object(image.worker, 'JDK_SHA256', pin(self.jdk.read_bytes())['sha256']); self.patch.start()
        self.addCleanup(self.patch.stop); self.addCleanup(self.temp.cleanup)
        self.production = dict(sourceSha=SOURCE, windowsVersion='0.2.427.146',candidateRoot=str(self.candidate),
            qualifiedJdkReleaseSHA256=pin(self.full_release)['sha256'],
            productionClasspathAndResourcesBound=True, applicationExecuted=False)
        self.run = dict(id=321, head_sha=SOURCE, status='completed', conclusion='success', event='workflow_dispatch',
            path='.github/workflows/windows-package-candidate.yml', repository=dict(full_name=image.REPOSITORY, private=False))
        self.jobs = dict(jobs=[dict(id=654, run_id=321, name='package', head_sha=SOURCE,
            status='completed', conclusion='success', labels=['windows-latest'], runner_group_name='GitHub Actions')])
        self.repackage()

    def repackage(self):
        for name, raw in self.runtime.items():
            p=self.candidate/'runtime'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
        packed(self.package, {image.APP+'runtime/'+name: raw for name,raw in self.runtime.items()})
        self.toolchain = dict(schema=1,sourceSha=SOURCE,windowsVersion='0.2.427.146',javaVersion='21.0.12.1',
            javaReleaseSha256=pin(self.full_release)['sha256'],runtimeReleaseSha256=pin(self.runtime['release'])['sha256'],
            candidateZip=pin(self.package.read_bytes()), toolchainAutoDetection=False,toolchainAutoDownload=False)
        self.reseal()

    def reseal(self):
        self.raw = (json.dumps(self.toolchain)+'\n').encode()
        self.log = '2026-10-16T00:00:00.0000000Z BILIPAI_CANDIDATE_TOOLCHAIN_SHA256 '+pin(self.raw)['sha256']+'\n'

    def provision(self):
        return image.provision_runtime(self.production,self.package,self.run,self.jobs,self.log,self.raw,self.jdk,self.output)

    def test_exact_packaged_module_image_and_fixed_launcher_form_private_runtime(self):
        proof = self.provision()
        actual = {p.relative_to(self.output).as_posix():p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
        self.assertEqual(dict(self.runtime, **{'bin/java.exe': self.launcher}),actual)
        self.assertEqual(self.runtime['lib/modules'],(self.output/'lib/modules').read_bytes())
        self.assertFalse((self.output/'bin/javac.exe').exists())
        self.assertFalse((self.candidate/'runtime/bin/java.exe').exists())
        self.assertTrue(proof['completeRuntimeByteBindingVerified']);self.assertTrue(proof['actualJdkBound'])
        for field in ['executionAuthorized','javaExecuted','applicationExecuted','uiRuntimeVerified','hardwareVerified','desktopReplaced']:
            self.assertFalse(proof[field])

    def test_changed_live_module_image_is_rejected(self):
        (self.candidate/'runtime/lib/modules').write_bytes(b'a different full JDK module image')
        with self.assertRaisesRegex(ValueError,'runtime bytes differ'):self.provision()
        self.assertFalse(self.output.exists())

    def test_extra_live_runtime_file_is_rejected(self):
        (self.candidate/'runtime/extra.dll').write_bytes(b'unqualified')
        with self.assertRaisesRegex(ValueError,'runtime inventory differs'):self.provision()

    def test_missing_live_runtime_file_is_rejected(self):
        (self.candidate/'runtime/bin/java.dll').unlink()
        with self.assertRaisesRegex(ValueError,'runtime inventory differs'):self.provision()

    def test_changed_package_transport_cannot_supply_runtime_bytes(self):
        with self.package.open('ab') as stream:stream.write(b'changed')
        with self.assertRaisesRegex(ValueError,'Archive size differs'):self.provision()

    def test_toolchain_metadata_must_match_the_single_actual_job_log_seal(self):
        self.raw += b' '
        with self.assertRaisesRegex(ValueError,'toolchain identity'):self.provision()

    def test_ambiguous_job_log_seal_is_rejected(self):
        self.log += self.log
        with self.assertRaisesRegex(ValueError,'toolchain identity'):self.provision()

    def test_unqualified_wrong_source_private_repo_paid_runner_and_unfinished_jobs_are_rejected(self):
        mutations=[('run',{'head_sha':'b'*40}),('run',{'status':'in_progress'}),('run',{'conclusion':'failure'}),
            ('run',{'path':'.github/workflows/unreviewed.yml'}),('run',{'repository':dict(full_name=image.REPOSITORY,private=True)}),
            ('job',{'labels':['windows-16core']}),('job',{'run_id':999}),('job',{'runner_group_name':'paid'}),
            ('job',{'status':'in_progress'})]
        for target,fields in mutations:
            with self.subTest(fields=fields):
                oldrun=copy.deepcopy(self.run);oldjobs=copy.deepcopy(self.jobs)
                (self.run if target=='run' else self.jobs['jobs'][0]).update(fields)
                with self.assertRaises(ValueError):self.provision()
                self.run=oldrun;self.jobs=oldjobs
        self.assertFalse(self.output.exists())

    def test_publisher_archive_checksum_cannot_be_replaced_by_a_release_version_string(self):
        raw=self.jdk.read_bytes();self.jdk.write_bytes(raw[:-1]+bytes([raw[-1]^1]))
        with self.assertRaisesRegex(ValueError,'Archive bytes differ'):self.provision()

    def test_actual_publisher_release_must_match_the_authenticated_compilation_release(self):
        self.production['qualifiedJdkReleaseSHA256']='0'*64
        self.toolchain['javaReleaseSha256']='0'*64;self.reseal()
        with self.assertRaisesRegex(ValueError,'publisher JDK differs'):self.provision()

    def test_native_jvm_must_match_fixed_publisher_even_when_package_is_consistently_resealed(self):
        self.runtime['bin/server/jvm.dll']=b'another JVM';self.repackage()
        with self.assertRaisesRegex(ValueError,'fixed publisher JDK'):self.provision()

    def test_existing_image_is_never_overwritten(self):
        self.output.mkdir();(self.output/'keep').write_bytes(b'keep')
        with self.assertRaisesRegex(ValueError,'fresh private runtime'):self.provision()
        self.assertEqual(b'keep',(self.output/'keep').read_bytes())

    def test_candidate_installation_cannot_be_used_as_private_image_destination(self):
        self.output=self.candidate/'runtime/private-image'
        with self.assertRaisesRegex(ValueError,'fresh private runtime'):self.provision()

    def test_archive_alias_and_parent_traversal_are_rejected_before_extraction(self):
        for names in [('safe','SAFE'),('../escape','safe')]:
            with self.subTest(names=names):
                packed(self.package,{name:b'entry' for name in names});expected=pin(self.package.read_bytes())
                with self.assertRaises(ValueError):
                    with image.archive_snapshot(self.package,expected):pass

    def test_duplicate_toolchain_json_fields_are_rejected(self):
        self.raw=b'{"schema":1,"schema":1}'
        self.log='BILIPAI_CANDIDATE_TOOLCHAIN_SHA256 '+pin(self.raw)['sha256']+'\n'
        with self.assertRaisesRegex(ValueError,'Duplicate binding JSON key'):self.provision()

    def test_package_metadata_alone_cannot_admit_unbound_production_classes(self):
        self.production['productionClasspathAndResourcesBound']=False
        with self.assertRaisesRegex(ValueError,'production class/resource binding'):self.provision()

    def test_fixture_cannot_silently_substitute_a_full_jdk_for_missing_production_modules(self):
        self.runtime['release']=b'JAVA_VERSION="21.0.12.1"\nMODULES="java.base"\n';self.repackage()
        with self.assertRaisesRegex(ValueError,'lacks the modules'):self.provision()

    def test_packaged_runtime_release_must_match_logged_package_record(self):
        self.toolchain['runtimeReleaseSha256']='0'*64;self.reseal()
        with self.assertRaisesRegex(ValueError,'runtime release differs'):self.provision()

    def test_java_arguments_use_regular_drive_and_unc_paths_for_the_same_checked_files(self):
        self.assertEqual('C:\\private image\\bin\\java.exe', image.java_argument_path('\\\\?\\C:\\private image\\bin\\java.exe'))
        self.assertEqual('\\\\host\\share\\dependency.jar', image.java_argument_path('\\\\?\\UNC\\host\\share\\dependency.jar'))
        self.assertEqual('UTF-8', image.java_argument_path('UTF-8'))


if __name__=='__main__':unittest.main()
