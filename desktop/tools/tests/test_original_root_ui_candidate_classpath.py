from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib
import struct
import unittest
import zipfile
import zlib
import sys
from unittest.mock import patch
TOOLS = Path(__file__).resolve().parent
if not (TOOLS / 'original_root_ui_candidate_classpath.py').is_file(): TOOLS = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import original_root_ui_candidate_classpath as module


def cfg(paths=('main.jar', 'resources/js-engine/lib/worker.jar')):
    return ('[Application]\napp.mainclass=com.bilipai.desktop.MainKt\n' +
        ''.join('app.classpath=$APPDIR\\' + path.replace('/', '\\') + '\n' for path in paths) +
        '[JavaOptions]\njava-options=-Dcompose.application.resources.dir=$APPDIR\\resources\n'
        'java-options=-Dskiko.library.path=$APPDIR\n').encode()


class CandidateClasspathTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jar(self, rows, path=None):
        path = path or self.root / 'test.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            for name, raw in rows: archive.writestr(name, raw)
        return path

    def raw_name_jar(self, name, data):
        # writestr sanitizes NUL before encoding; build real raw ZIP name bytes.
        checksum = zlib.crc32(data)
        local = struct.pack('<4s5H3I2H', b'PK\x03\x04', 20, 0, 0, 0, 0,
                            checksum, len(data), len(data), len(name), 0) + name + data
        central = struct.pack('<4s6H3I5H2I', b'PK\x01\x02', 20, 20, 0, 0, 0, 0,
            checksum, len(data), len(data), len(name), 0, 0, 0, 0, 0, 0) + name
        end = struct.pack('<4s4H2IH', b'PK\x05\x06', 0, 0, 1, 1, len(central), len(local), 0)
        path = self.root / 'raw.jar'
        path.write_bytes(local + central + end)
        return path

    def test_cfg_preserves_nested_classpath_order(self):
        order = ['z.jar', 'resources/js-engine/lib/a.jar', 'a.jar']
        self.assertEqual(module.parse_cfg(cfg(order))['orderedClasspath'], order)

    def test_cfg_rejects_duplicate_case_variant_and_wrong_main(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'): module.parse_cfg(cfg(['a.jar', 'A.jar']))
        with self.assertRaisesRegex(ValueError, 'main'): module.parse_cfg(cfg().replace(b'MainKt', b'OtherKt'))

    def test_cfg_rejects_traversal_external_expansion_and_wildcards(self):
        for path in ('../x.jar', '/x.jar', '$OTHER/x.jar', 'x*.jar', 'C:/x.jar', 'a//x.jar'):
            with self.subTest(path=path), self.assertRaises(ValueError): module.parse_cfg(cfg([path]))
        with self.assertRaises(ValueError): module.parse_cfg(cfg().replace(b'$APPDIR\\main.jar', b'C:\\main.jar'))

    def test_cfg_requires_actual_graphics_resource_bindings(self):
        with self.assertRaisesRegex(ValueError, 'binding'):
            module.parse_cfg(cfg().replace(b'$APPDIR\\resources', b'$APPDIR\\other'))

    def test_cfg_read_is_bounded(self):
        with self.assertRaisesRegex(ValueError, 'bound'): module.parse_cfg(b'x' * (module.MAX_CFG + 1))

    def test_zip_directory_limits_are_checked_before_zipfile(self):
        path = self.jar([('sample/Test.class', b'byte fixture')])
        raw = bytearray(path.read_bytes())
        end = raw.rfind(b'PK\x05\x06')
        struct.pack_into('<H', raw, end + 10, 65535)
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, 'directory'): module.class_entries(path)

    def test_zip64_locator_cannot_override_a_small_plain_directory_before_allocation(self):
        path = self.jar([('sample/Test.class', b'a')])
        raw = path.read_bytes()
        position = raw.rfind(b'PK\x05\x06')
        directory_offset = struct.unpack_from('<I', raw, position + 16)[0]
        zip64 = struct.pack('<4sQ2H2I4Q', b'PK\x06\x06', 44, 45, 45, 0, 0, 1, 1,
                            module.MAX_CENTRAL_DIRECTORY + 1, directory_offset)
        locator = struct.pack('<4sIQI', b'PK\x06\x07', 0, position, 1)
        path.write_bytes(raw[:position] + zip64 + locator + raw[position:])
        with patch.object(module.zipfile, 'ZipFile', side_effect=AssertionError('ZIP parsing must not begin')):
            with self.assertRaisesRegex(ValueError, 'ZIP64'): module.class_entries(path)

    def test_zip64_locator_before_a_maximum_comment_is_also_rejected(self):
        path = self.jar([('sample/Test.class', b'a')])
        raw = path.read_bytes()
        position = raw.rfind(b'PK\x05\x06')
        end = bytearray(raw[position:])
        struct.pack_into('<H', end, 20, 65535)
        locator = struct.pack('<4sIQI', b'PK\x06\x07', 0, 0, 1)
        path.write_bytes(raw[:position] + locator + end + b'x' * 65535)
        with self.assertRaisesRegex(ValueError, 'ZIP64'): module.class_entries(path)

    def test_raw_nul_main_name_cannot_be_accepted_as_a_canonical_class(self):
        path = self.raw_name_jar(module.MAIN.encode() + b'\x00suffix', b'a')
        with self.assertRaisesRegex(ValueError, 'alias'): module.class_entries(path)

    def test_raw_nul_version_name_cannot_alias_the_real_version_resource(self):
        path = self.raw_name_jar(b'windows-update.json\x00suffix', b'{"version":"wrong"}')
        with self.assertRaisesRegex(ValueError, 'alias'): module.read_windows_version(path)

    def test_raw_backslash_class_alias_is_rejected_on_every_host(self):
        path = self.raw_name_jar(b'sample\\Test.class', b'a')
        with self.assertRaisesRegex(ValueError, 'alias'): module.class_entries(path)

    def test_duplicate_jar_entry_is_rejected(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            path = self.jar([('sample/Test.class', b'a'), ('sample/Test.class', b'b')])
        with self.assertRaisesRegex(ValueError, 'Ambiguous'): module.class_entries(path)

    def test_versioned_class_shadow_name_is_normalized_for_jdk21(self):
        path = self.jar([('META-INF/versions/21/sample/Test.class', b'a'),
                         ('META-INF/versions/25/sample/Future.class', b'b'),
                         ('META-INF/versions/9/module-info.class', b'c')])
        entries = module.class_entries(path)
        self.assertEqual([row['name'] for row in entries.values()], ['sample/Test'])
        with self.assertRaisesRegex(ValueError, 'shadows'):
            module.require_no_fixture_shadow([row['name'] for row in entries.values()], ['sample/Test'])

    def test_main_ledger_rejects_same_name_different_compiled_bytes(self):
        raw = b'compiled byte fixture'
        path = self.jar([(module.MAIN, raw)])
        expected = {module.MAIN: dict(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())}
        module.verify_main_class_ledger(path, expected)
        expected[module.MAIN]['sha256'] = hashlib.sha256(b'other').hexdigest()
        with self.assertRaisesRegex(ValueError, 'bytes differ'): module.verify_main_class_ledger(path, expected)

    def test_main_ledger_rejects_extra_production_classes(self):
        path = self.jar([(module.MAIN, b'a'), ('sample/Extra.class', b'b')])
        with self.assertRaisesRegex(ValueError, 'inventory differs'):
            module.verify_main_class_ledger(path, {module.MAIN: dict(bytes=1, sha256=hashlib.sha256(b'a').hexdigest())})

    def test_full_ledger_does_not_skip_module_or_future_versioned_class_entries(self):
        for extra in ('module-info.class', 'META-INF/versions/25/sample/Future.class'):
            with self.subTest(extra=extra):
                path = self.jar([(module.MAIN, b'a'), (extra, b'b')])
                expected = {module.MAIN: dict(bytes=1, sha256=hashlib.sha256(b'a').hexdigest())}
                with self.assertRaisesRegex(ValueError, 'inventory differs'): module.verify_main_class_ledger(path, expected)

    def test_inventory_rejects_jar_change_after_its_private_snapshot(self):
        app = self.root / 'app'
        app.mkdir()
        (app / 'BiliPai Windows.cfg').write_bytes(cfg(['main.jar']))
        path = self.jar([(module.MAIN, b'a')], app / 'main.jar')
        original = module.effective_class_entries
        def swapped(entries):
            result = original(entries)
            self.jar([(module.MAIN, b'b')], path)
            return result
        with patch.object(module, 'effective_class_entries', side_effect=swapped):
            with self.assertRaisesRegex(ValueError, 'changed'): module.inventory(self.root)

    def test_ledger_rejects_path_swap_adding_an_extra_class_during_actual_read(self):
        path = self.jar([(module.MAIN, b'a')])
        expected = {module.MAIN: dict(bytes=1, sha256=hashlib.sha256(b'a').hexdigest())}
        original = module.zipfile.ZipFile.open
        swapped = False
        def swapping_open(archive, name, mode='r', *args, **kwargs):
            nonlocal swapped
            if name == module.MAIN and mode == 'r' and not swapped:
                swapped = True
                self.jar([(module.MAIN, b'a'), ('sample/Extra.class', b'b')], path)
            return original(archive, name, mode, *args, **kwargs)
        with patch.object(module.zipfile.ZipFile, 'open', new=swapping_open):
            with self.assertRaisesRegex(ValueError, 'changed'): module.verify_main_class_ledger(path, expected)

    def test_version_is_read_from_bounded_production_resource(self):
        path = self.jar([('windows-update.json', b'{"version":"0.2.427.123"}')])
        self.assertEqual(module.read_windows_version(path), '0.2.427.123')

    def test_file_digest_rejects_oversized_input(self):
        path = self.root / 'large'
        path.write_bytes(b'x' * 32)
        with self.assertRaisesRegex(ValueError, 'bound'): module.file_digest(path, maximum=16)


if __name__ == '__main__': unittest.main(verbosity=2)
