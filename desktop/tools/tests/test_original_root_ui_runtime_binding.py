from pathlib import Path
import hashlib
import json
import tempfile
import unittest
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import original_root_ui_runtime_binding as binding


def pin(data):
    return dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def jar(path, entries):
    with zipfile.ZipFile(path, 'w') as archive:
        for name, data in entries.items(): archive.writestr(name, data)


class Fixture:
    def __init__(self, root):
        self.root = root; self.app = root / 'app'; self.app.mkdir()
        resources = self.app / 'resources'; resources.mkdir()
        (resources / 'payload.txt').write_bytes(b'actual resource')
        self.main = {'com/bilipai/desktop/MainKt.class': b'compiled main',
            'META-INF/actual.kotlin_module': b'compiled metadata',
            'windows-update.json': b'{"version":"0.2.427.1"}'}
        jar(self.app / 'main.jar', self.main)
        jar(self.app / 'dependency.jar', {'example/Dependency.class': b'compiled dependency'})
        self.dll = b'original DLL'; self.icu = b'original ICU'
        self.native_entries = {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n',
            'skiko-windows-x64.dll': self.dll,
            'skiko-windows-x64.dll.sha256': hashlib.sha256(self.dll).hexdigest().encode(),
            'icudtl.dat': self.icu}
        self.native = root / 'qualified-native.jar'; jar(self.native, self.native_entries)
        jar(self.app / 'native-projection.jar', {'META-INF/MANIFEST.MF': self.native_entries['META-INF/MANIFEST.MF']})
        for name in binding.NATIVE_ASSETS: (self.app / name).write_bytes(self.native_entries[name])
        self.cfg = ('[Application]\napp.mainclass=com.bilipai.desktop.MainKt\n'
            'app.classpath=$APPDIR\\main.jar\napp.classpath=$APPDIR\\dependency.jar\n'
            'app.classpath=$APPDIR\\native-projection.jar\n[JavaOptions]\n'
            'java-options=-Dcompose.application.resources.dir=$APPDIR\\resources\n'
            'java-options=-Dskiko.library.path=$APPDIR\n')
        (self.app / 'BiliPai Windows.cfg').write_text(self.cfg, encoding='utf-8')
        self.ledger = dict(mainOutput={name: pin(data) for name, data in self.main.items() if not name.endswith('.json')},
            mainResources={'windows-update.json': pin(self.main['windows-update.json'])},
            applicationResources={'payload.txt': pin(b'actual resource')})
        self.model = dict(mainClassRoots=['ci/main'],
            mainRuntimeClasspath=['ci/main', 'ci/resources/main', 'ci/dependency.jar', 'ci/native.jar'],
            mainArtifacts=[dict(file='ci/dependency.jar', group='example', name='dependency', version='1',
                                **pin((self.app / 'dependency.jar').read_bytes())),
                           dict(file='ci/native.jar', group='org.jetbrains.skiko', name='skiko-awt-runtime-windows-x64',
                                version='0.150.1', **pin(self.native.read_bytes()))])

    def verify(self, fixture_names=()):
        return binding._verify_production(self.root, self.model, self.ledger, fixture_names, self.native)


class RuntimeBindingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bilipai-runtime-binding-')
        self.fixture = Fixture(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def test_actual_graph_classes_metadata_resources_and_native_projection_bind(self):
        value = self.fixture.verify()
        self.assertEqual(dict(classes=1, metadataAndResources=2), value['mainOutput'])
        self.assertEqual(1, value['applicationResources'])
        self.assertEqual(4, len(value['nativeProjection']))
        self.assertFalse(value['bundledJavaExecutableExists'])

    def test_changed_kotlin_metadata_is_rejected(self):
        f = self.fixture; f.main['META-INF/actual.kotlin_module'] = b'changed metadata'; jar(f.app / 'main.jar', f.main)
        with self.assertRaisesRegex(ValueError, 'Main resource bytes differ'): f.verify()

    def test_changed_compiled_main_bytes_are_rejected(self):
        f = self.fixture; f.main['com/bilipai/desktop/MainKt.class'] = b'different main'; jar(f.app / 'main.jar', f.main)
        with self.assertRaisesRegex(ValueError, 'main class bytes differ'): f.verify()

    def test_extra_production_class_is_rejected(self):
        f = self.fixture; f.main['example/Extra.class'] = b'extra'; jar(f.app / 'main.jar', f.main)
        with self.assertRaisesRegex(ValueError, 'class inventory differs'): f.verify()

    def test_extra_classpath_jar_is_rejected(self):
        f = self.fixture; jar(f.app / 'extra.jar', {'example/Extra.class': b'extra'})
        (f.app / 'BiliPai Windows.cfg').write_text(f.cfg.replace('[JavaOptions]', 'app.classpath=$APPDIR\\extra.jar\n[JavaOptions]'))
        with self.assertRaisesRegex(ValueError, 'dependency bytes differ'): f.verify()

    def test_changed_dependency_is_rejected(self):
        jar(self.fixture.app / 'dependency.jar', {'example/Dependency.class': b'changed'})
        with self.assertRaisesRegex(ValueError, 'dependency bytes differ'): self.fixture.verify()

    def test_changed_native_dll_is_rejected(self):
        (self.fixture.app / 'skiko-windows-x64.dll').write_bytes(b'changed DLL')
        with self.assertRaisesRegex(ValueError, 'native asset differs'): self.fixture.verify()

    def test_changed_icu_data_is_rejected(self):
        (self.fixture.app / 'icudtl.dat').write_bytes(b'changed ICU')
        with self.assertRaisesRegex(ValueError, 'native asset differs'): self.fixture.verify()

    def test_changed_application_resource_is_rejected(self):
        (self.fixture.app / 'resources/payload.txt').write_bytes(b'changed resource')
        with self.assertRaisesRegex(ValueError, 'resource bytes differ'): self.fixture.verify()

    def test_extra_application_resource_is_rejected(self):
        (self.fixture.app / 'resources/extra.txt').write_bytes(b'unqualified')
        with self.assertRaisesRegex(ValueError, 'resource inventory differs'): self.fixture.verify()

    def test_fixture_cannot_shadow_production_classes(self):
        with self.assertRaisesRegex(ValueError, 'shadows production'): self.fixture.verify({'com/bilipai/desktop/MainKt'})

    def test_original_native_artifact_must_match_authenticated_hash(self):
        self.fixture.native.write_bytes(b'not the approved original')
        with self.assertRaisesRegex(ValueError, 'Skiko artifact bytes differ'): self.fixture.verify()

    def test_changed_effective_class_winner_is_rejected(self):
        app = self.fixture.app
        jar(app / 'a.jar', {'example/Duplicate.class': b'first'})
        jar(app / 'b.jar', {'example/Duplicate.class': b'second'})
        with self.assertRaisesRegex(ValueError, 'effective class bytes'):
            binding.verify_classpath_order(self.fixture.root, ['a.jar', 'b.jar'], ['b.jar', 'a.jar'])

    def test_identical_duplicate_class_bytes_are_order_equivalent(self):
        app = self.fixture.app
        for name in ('a.jar', 'b.jar'): jar(app / name, {'example/Duplicate.class': b'identical'})
        self.assertEqual(dict(winnerChanges=1, equivalentChanges=1),
            binding.verify_classpath_order(self.fixture.root, ['a.jar', 'b.jar'], ['b.jar', 'a.jar']))

    def test_java21_multi_release_winner_is_used_and_future_class_is_ignored(self):
        app = self.fixture.app
        jar(app / 'a.jar', {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n',
            'example/Duplicate.class': b'old', 'META-INF/versions/21/example/Duplicate.class': b'current',
            'META-INF/versions/22/example/Duplicate.class': b'future'})
        jar(app / 'b.jar', {'example/Duplicate.class': b'current'})
        self.assertEqual(1, binding.verify_classpath_order(self.fixture.root, ['a.jar', 'b.jar'], ['b.jar', 'a.jar'])['equivalentChanges'])

    def test_raw_paths_and_duplicate_json_keys_are_rejected(self):
        for path in ('../x', '/absolute', 'x//y', 'x/./y', 'C:/foreign', 'x\\y', 'x\x00y'):
            with self.subTest(path=path), self.assertRaises(ValueError): binding.relative(path)
        with self.assertRaisesRegex(ValueError, 'Duplicate binding JSON key'):
            binding.json_bytes(b'{"source":"a","source":"b"}')


if __name__ == '__main__':
    unittest.main()
