from pathlib import Path
import importlib.util, unittest
HERE=Path(__file__).resolve().parent; ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('backup_source_parser',ROOT/'desktop/tools/extract-upstream-media.py')
helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
parser=helper.parser_for(ROOT)
def read(p): return p.read_text(encoding='utf-8')
class OriginalWebDavRetentionTest(unittest.TestCase):
    def test_original_network_methods_remain_exact_with_previous_windows_bounds(self):
        original=read(ROOT/'app/src/main/java/com/android/purebilibili/feature/settings/webdav/WebDavBackupService.kt')
        generated=read(HERE/'generated/com/android/purebilibili/feature/settings/webdav/WebDavBackupService.kt')
        for name in ['testConnection','listBackups','backupNow','restoreLatest','listBackupsInternal',
            'ensureRemoteDirectory','probeDirectoryDepthZero','mkcolDirectory','validateConfig']:
            expected=helper.function(original,name,parser)
            if name=='restoreLatest':
                expected=expected.replace('val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href)',
                    'val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href).also { archive.requireSameOrigin(config.baseUrl, it) }')
                expected=expected.replace('response.body.bytes()','archive.readDownload(response.body)')
            # Extracted nested declarations preserve four-space indentation only.
            actual=helper.function(generated,name,parser)
            self.assertEqual(expected.strip(),actual.strip(),name)
    def test_success_signal_lies_outside_failure_rollback_path(self):
        text=read(HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/backup/DesktopBackupArchive.kt')
        signal=text.index('        onSuccessfulRestore()')
        self.assertGreater(signal,text.index('            throw failure'))
        self.assertLess(signal,text.index('        return replaced.size'))
if __name__=='__main__': unittest.main()
