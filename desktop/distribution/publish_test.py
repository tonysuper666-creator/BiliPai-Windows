import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'data'
        self.incoming = Path(self.temp.name) / 'incoming'
        self.incoming.mkdir()
        self.zip_name = 'BiliPai-Windows-0.2.427.201-x64.zip'
        self.installer = 'BiliPai-Windows-0.2.427.201.msi'
        for name in [self.zip_name, self.installer]:
            (self.incoming / name).write_bytes(b'fixture only')
        self.metadata = {'channel': 'dev', 'version': '0.2.427.201', 'sequence': 1,
                         'files': [{'name': name, 'sha256': hashlib.sha256(b'fixture only').hexdigest()}
                                   for name in [self.zip_name, self.installer]],
                         'catalog': {'version': '0.2.427.201', 'channel': 'dev', 'installer': self.installer}}
        for kind in ['manifest', 'config']:
            (self.incoming / f'{kind}.json').write_text('{"fixture":"not a real signed update"}')
        self.save()

    def tearDown(self):
        self.temp.cleanup()

    def save(self):
        (self.incoming / 'publish.json').write_text(json.dumps(self.metadata))

    def test_publish_preserves_immutable_files_and_atomically_switches_channel(self):
        publisher.publish(self.root, self.incoming)
        pointer = self.root / 'channels/dev/current.json'
        self.assertEqual(json.loads(pointer.read_text())['sequence'], 1)
        self.assertTrue((self.root / 'channels/dev/1/manifest.json').is_file())
        self.assertTrue((self.root / 'channels/dev/1/config.json').is_file())
        with self.assertRaises(ValueError):
            publisher.publish(self.root, self.incoming)
        self.metadata['sequence'] = 2
        self.metadata['files'] = []  # Signed withdrawal reuses immutable artifacts.
        self.save()
        publisher.publish(self.root, self.incoming)
        self.assertEqual(json.loads(pointer.read_text())['sequence'], 2)
        self.assertEqual((self.root / 'public/artifacts/0.2.427.201' / self.zip_name).read_bytes(), b'fixture only')

    def test_corrupt_upload_and_immutable_conflict_leave_current_pointer_intact(self):
        publisher.publish(self.root, self.incoming)
        pointer = self.root / 'channels/dev/current.json'
        original = pointer.read_bytes()
        self.metadata['sequence'] = 2
        (self.incoming / self.zip_name).write_bytes(b'corruption')
        self.save()
        with self.assertRaises(ValueError):
            publisher.publish(self.root, self.incoming)
        self.assertEqual(pointer.read_bytes(), original)
        self.metadata['files'][0]['sha256'] = hashlib.sha256(b'corruption').hexdigest()
        self.save()
        with self.assertRaises(ValueError):
            publisher.publish(self.root, self.incoming)
        self.assertEqual(pointer.read_bytes(), original)

    def test_stable_requires_explicit_admission_and_paths_cannot_escape(self):
        self.metadata['channel'] = 'stable'
        self.save()
        with self.assertRaises(ValueError):
            publisher.publish(self.root, self.incoming)
        self.metadata['channel'] = 'dev'
        self.metadata['files'][0]['name'] = '../escape.zip'
        self.save()
        with self.assertRaises(ValueError):
            publisher.publish(self.root, self.incoming)
        self.assertFalse((self.root / 'channels/dev/current.json').exists())


if __name__ == '__main__':
    unittest.main()
