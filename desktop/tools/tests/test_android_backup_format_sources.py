from pathlib import Path
import hashlib
import importlib.util
import json
import re
import unittest

REPO = Path(__file__).resolve().parents[3]


class AndroidBackupFormatSources(unittest.TestCase):
    def test_both_retained_formats_and_licenses_are_exact_frozen_inputs(self):
        plan = json.loads((REPO / '.github/windows-source-validation-plan.json').read_bytes())
        pins = {row['path']: row for row in plan['sources']}
        for directory, source, repository in [
            ('android-backup-format', 'WebDavBackupService.kt', 'https://github.com/jay3-yy/BiliPai'),
            ('datastore-preferences-wire', 'preferences.proto', 'https://github.com/androidx/androidx'),
        ]:
            root = REPO / 'desktop/third-party' / directory
            manifest = json.loads((root / 'manifest.json').read_bytes())
            self.assertEqual(repository, manifest['repository'])
            self.assertRegex(manifest['commit'], r'^[0-9a-f]{40}$')
            for name, key in [(source, 'sourceSHA256'), (manifest.get('licenseRetainedPath', 'LICENSE'), 'licenseSHA256')]:
                path = root / name
                self.assertTrue(path.resolve().is_relative_to(root.resolve()))
                relative = path.relative_to(REPO).as_posix()
                self.assertIn(relative, pins, 'A retained format or license was omitted, possibly by Git ignore rules')
                self.assertEqual('raw', pins[relative]['normalization'])
                digest = hashlib.sha256(path.read_bytes()).hexdigest()
                self.assertEqual(manifest[key], digest)
                self.assertEqual(pins[relative]['sha256'], digest)

    def test_original_zip_producer_and_official_wire_schema_match_the_supported_formats(self):
        folder = REPO / 'desktop/third-party/android-backup-format'
        original = (folder / 'WebDavBackupService.kt').read_text(encoding='utf8')
        paths = dict(re.findall(r'private const val (ZIP_ENTRY_[A-Z_]+) = "([^"\n]+)"', original))
        self.assertEqual({
            'ZIP_ENTRY_DATASTORE_SETTINGS': 'datastore/settings_prefs.preferences_pb',
            'ZIP_ENTRY_DATASTORE_PLUGIN': 'datastore/plugin_prefs.preferences_pb',
            'ZIP_ENTRY_SHARED_PREFS_JSON_PLUGIN': 'shared_prefs/json_plugins.xml',
            'ZIP_ENTRY_JSON_PLUGIN_DIR': 'json_plugins/',
        }, paths)
        entries = set(paths.values()) - {'json_plugins/'} | {'json_plugins/*.json'}
        self.assertEqual(entries, set(json.loads((folder / 'manifest.json').read_bytes())['originalEntries']))
        schema = (REPO / 'desktop/third-party/datastore-preferences-wire/preferences.proto').read_text(encoding='utf8')
        oneof = re.search(r'oneof value\s*\{([^}]+)\}', schema).group(1)
        self.assertEqual([
            ('bool', 'boolean', '1'), ('float', 'float', '2'), ('int32', 'integer', '3'),
            ('int64', 'long', '4'), ('string', 'string', '5'), ('StringSet', 'string_set', '6'),
            ('double', 'double', '7'), ('bytes', 'bytes', '8'),
        ], re.findall(r'(\w+)\s+(\w+)\s*=\s*(\d+)\s*;', oneof))

    def test_actual_root_refreshes_original_mirrors_before_constructing_video_resources(self):
        shell = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf8')
        call = 'com.bilipai.desktop.ui.ensureDesktopWindowsPlaybackDefaults(storageSettingsContext, legacyPlaybackRead.migrationSeed)'
        self.assertEqual(1, shell.count(call))
        self.assertEqual(1, shell.count('DesktopOriginalVideoAppResources.create('))
        self.assertLess(shell.index(call), shell.index('DesktopOriginalVideoAppResources.create('))
        defaults = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsPlaybackDefaults.kt').read_text(encoding='utf8')
        self.assertIn('DesktopSettingsShareService(context).reconcileRestoredAndroidBackup()', defaults)
        spec = importlib.util.spec_from_file_location('android_backup_source_helper', REPO / 'desktop/tools/extract-upstream-media.py')
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        service = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsShareService.kt').read_text(encoding='utf8')
        method = helper.function(service, 'reconcileRestoredAndroidBackup', helper.parser_for(REPO))
        self.assertIn('DesktopOriginalPlaybackPreferenceOperation.run(context, context::isCurrentForOriginalWrite)', method)
        self.assertIn('applyOriginalMirrorSetters(accepted)', method)
        self.assertIn('migration.edit().putBoolean("pending", false)', method)


if __name__ == '__main__':
    unittest.main()
