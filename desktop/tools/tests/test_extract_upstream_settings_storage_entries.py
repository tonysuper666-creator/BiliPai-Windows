from pathlib import Path
import importlib.util, tempfile, unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('storage_under_test',HERE.parent/'extract-upstream-settings-storage-entries.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)

class OriginalStorageExtractionTest(unittest.TestCase):
    def test_original_selected_rows_keep_callbacks_palette_and_order(self):
        declarations,body=tool.selected_body(ROOT)
        self.assertIn('resolveSettingsSiblingIconTints(7, paletteOffset = 2)',declarations)
        self.assertLess(body.index('onClick = onSettingsShareClick'),body.index('onClick = onWebDavBackupClick'))
        self.assertIn('siblingTints[0]',body);self.assertIn('siblingTints[1]',body)
        self.assertEqual(body.count('SettingsAdaptiveDivider()'),1)
        self.assertNotIn('onClearCacheClick',body)

    def test_callback_shape_drift_is_rejected(self):
        original=tool.read(ROOT)
        with patch.object(tool,'read',return_value=original.replace('onClick = onSettingsShareClick','onClick = renamedShareClick')):
            with self.assertRaisesRegex(ValueError,'callbacks changed'):tool.selected_body(ROOT)

    def test_original_palette_semantics_drift_is_rejected(self):
        original=tool.read(ROOT)
        with patch.object(tool,'read',return_value=original.replace('resolveSettingsSiblingIconTints(7, paletteOffset = 2)','resolveSettingsSiblingIconTints(2, paletteOffset = 0)')):
            with self.assertRaisesRegex(ValueError,'count/offset changed'):tool.selected_body(ROOT)

    def test_storage_row_shape_drift_is_rejected(self):
        original=tool.read(ROOT)
        original=original.replace('onClick = onClearCacheClick','onClick = onClearCacheClick',1)
        start=original.index('fun DataStorageSection(')
        location=original.index('SettingClickableItem(',start)
        changed=original[:location]+original[location:].replace('SettingClickableItem(','OtherRow(',1)
        with patch.object(tool,'read',return_value=changed):
            with self.assertRaisesRegex(ValueError,'row count changed'):tool.selected_body(ROOT)

    def test_generated_source_does_not_duplicate_shared_fqns(self):
        with tempfile.TemporaryDirectory(prefix='bp-storage-gen-') as temporary:
            paths=tool.generate(ROOT,Path(temporary));self.assertEqual(len(paths),1)
            source=paths[0].read_text(encoding='utf-8')
            self.assertIn('SettingsDetailGroup(title = "存储与备份")',source)
            for forbidden in ['fun SettingsDetailGroup(', 'class SettingsDetailEntry(', 'fun SettingsDetailEntrySection(', 'fun SettingsCardGroup(', 'fun DataStorageSection(']:
                self.assertNotIn(forbidden,source)

if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(OriginalStorageExtractionTest)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
