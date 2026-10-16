"""Exercise the actual bounded no-follow diagnostic collector; no compiler or network."""
from pathlib import Path
import importlib.util
import io
import os
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('actual_nested_diagnostic_producer',ROOT/'desktop/tools/native/build-mpv-rtx-core-runtime.py')
producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
SAFE_HOST=(os.open in os.supports_dir_fd and all(hasattr(os,name)for name in ['O_NOFOLLOW','O_DIRECTORY','O_NONBLOCK']))

class UnsupportedDiagnosticHostTest(unittest.TestCase):
    def test_unsupported_host_cannot_fall_back_to_unsafe_path_open(self):
        with patch.object(producer.os,'supports_dir_fd',set()),patch.object(producer.os,'open',side_effect=AssertionError('Must not open any path'))as opened:
            result=producer.print_nested_build_failure_logs(Path('/unopened'),(1,2),Path('/unopened-output'),(3,4))
        self.assertEqual('UNAVAILABLE_SAFE_OPEN',result['state']);opened.assert_not_called()

@unittest.skipUnless(SAFE_HOST,'Actual descriptor-relative no-follow collector requires the source-build Linux host')
class ActualNestedDownloadDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='bp-nested-download-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.workspace=self.root/'workspace';self.output=self.root/'output'
        self.workspace.mkdir();self.output.mkdir();self.log=self.output/'native-build.log'
        self.log.write_text('FAILED: packages/c-ares-prefix/src/c-ares-stamp/c-ares-download\n',encoding='utf-8')
    def nested(self,name,step,body,mtime=1000):
        path=self.workspace/'build-x64/packages'/(name+'-prefix')/'src'/(name+'-stamp')/(name+'-'+step+'.log')
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(body);os.utime(path,ns=(mtime,mtime));return path
    def collect(self):
        ws=self.workspace.stat();out=self.output.stat()
        stream=type('CapturedOutput',(),{'buffer':io.BytesIO()})()
        with patch.object(producer.sys,'stdout',stream):
            status=producer.print_nested_build_failure_logs(self.workspace,(ws.st_dev,ws.st_ino),self.output,(out.st_dev,out.st_ino))
        return status,stream.buffer.getvalue()
    def test_download_error_precedes_newer_unrelated_install_output(self):
        self.nested('c-ares','download-err',b'actual-c-ares-clone-error\n')
        self.nested('c-ares','download-out',b'actual-c-ares-clone-output\n')
        for i in range(12):self.nested('other'+str(i),'install-out',b'unrelated-install\n',2000+i)
        status,body=self.collect()
        self.assertEqual('BOUNDED_DIAGNOSTICS',status['state']);self.assertEqual(8,status['files'])
        self.assertLess(body.index(b'actual-c-ares-clone-error'),body.index(b'actual-c-ares-clone-output'))
        self.assertLess(body.index(b'actual-c-ares-clone-output'),body.index(b'unrelated-install'))
    def test_update_and_patch_errors_are_not_silently_excluded(self):
        for step in ['update','patch']:
            with self.subTest(step=step):
                self.log.write_text('FAILED: packages/c-ares-prefix/src/c-ares-stamp/c-ares-'+step+'\n',encoding='utf-8')
                self.nested('c-ares',step+'-err',('actual-'+step+'-error\n').encode())
                status,body=self.collect();self.assertEqual('BOUNDED_DIAGNOSTICS',status['state'])
                self.assertIn(('actual-'+step+'-error').encode(),body)
    def test_failed_download_secret_and_symlink_stay_filtered(self):
        self.nested('c-ares','download-err',b'Authorization: Bearer fake-secret\nsafe-failure-reason\n')
        outside=self.root/'outside';outside.write_text('outside-contents-must-not-be-read',encoding='utf-8')
        link=self.workspace/'build-x64/packages/c-ares-prefix/src/c-ares-stamp/c-ares-download-out.log'
        link.symlink_to(outside)
        status,body=self.collect();self.assertEqual('BOUNDED_DIAGNOSTICS',status['state'])
        self.assertNotIn(b'fake-secret',body);self.assertNotIn(b'outside-contents',body)
        self.assertIn(b'safe-failure-reason',body);self.assertIn(b'sensitive diagnostic line omitted',body)
    def test_failed_download_retains_file_and_total_byte_limits(self):
        self.nested('c-ares','download-err',b'x'*80000+b'\nend-of-failure\n')
        status,body=self.collect();self.assertEqual('BOUNDED_DIAGNOSTICS',status['state'])
        self.assertLessEqual(status['bytes'],256*1024);self.assertLessEqual(len(body),256*1024)
        self.assertIn(b'end-of-failure',body);self.assertIn(b'skippedHeadBytes=',body)

if __name__=='__main__':unittest.main()
