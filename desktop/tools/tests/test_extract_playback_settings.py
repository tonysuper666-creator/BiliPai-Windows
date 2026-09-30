import importlib.util
import json
from pathlib import Path
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]

def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT / 'desktop/tools' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

PLAYBACK = load('extract-playback-platform')
SETTINGS = load('extract-upstream-settings')
HELPER = load('extract-upstream-media')

class PlaybackSettingsExtractorTest(unittest.TestCase):
    def test_recovery_policy_keeps_every_original_branch_with_one_verified_import_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            PLAYBACK.generate(ROOT, output)
            original = PLAYBACK.read(ROOT, PLAYBACK.SOURCE)
            generated = (output / 'com/android/purebilibili/feature/video/state/PlayerErrorRecoveryPolicy.kt').read_text(encoding='utf-8')
            self.assertEqual(original.replace('import androidx.media3.common.PlaybackException',
                'import com.bilipai.desktop.player.platform.DesktopMedia3ErrorCodes as PlaybackException'), generated.split('\n', 2)[2])

    def test_unverified_media3_constant_is_rejected_before_a_build_can_use_it(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            for name in [PLAYBACK.SOURCE, 'desktop/third-party/media3-error-codes.json']:
                file = repo / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text((ROOT / name).read_text(encoding='utf-8'), encoding='utf-8')
            file = repo / 'desktop/third-party/media3-error-codes.json'
            codes = json.loads(file.read_text(encoding='utf-8'))
            codes['constants']['ERROR_CODE_IO_NETWORK_CONNECTION_FAILED'] = 2005
            file.write_text(json.dumps(codes), encoding='utf-8')
            with self.assertRaises(AssertionError):
                PLAYBACK.generate(repo, repo / 'generated')

    def test_webdav_http_methods_reuse_original_logic_with_bounded_same_origin_restore(self):
        parser = HELPER.parser_for(ROOT)
        original = SETTINGS.read(ROOT, SETTINGS.BASE + 'WebDavBackupService.kt')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            SETTINGS.generate(ROOT, output)
            generated = (output / 'com/android/purebilibili/feature/settings/webdav/WebDavBackupService.kt').read_text(encoding='utf-8')
            methods = ['testConnection', 'listBackups', 'backupNow', 'restoreLatest', 'listBackupsInternal',
                'ensureRemoteDirectory', 'probeDirectoryDepthZero', 'mkcolDirectory', 'validateConfig']
            for name in methods:
                expected = HELPER.function(original, name, parser)
                if name == 'restoreLatest':
                    expected = expected.replace('val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href)',
                        'val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href).also { archive.requireSameOrigin(config.baseUrl, it) }')
                    expected = expected.replace('response.body.bytes()', 'archive.readDownload(response.body)')
                actual = HELPER.function(generated, name, parser)
                self.assertEqual(textwrap.dedent(expected).strip(), textwrap.dedent(actual).strip(), name)

    def test_windows_webdav_keeps_the_original_configuration_contract(self):
        parser = HELPER.parser_for(ROOT)
        original = SETTINGS.read(ROOT, SETTINGS.BASE + 'WebDavBackupStore.kt')
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            SETTINGS.generate(ROOT, output)
            generated = (output / 'com/android/purebilibili/feature/settings/webdav/DesktopWebDavConfig.kt').read_text(encoding='utf-8')
            self.assertEqual(HELPER.data_class(original, 'WebDavBackupConfig', parser).strip(),
                HELPER.data_class(generated, 'WebDavBackupConfig', parser).strip())

if __name__ == '__main__':
    unittest.main()
