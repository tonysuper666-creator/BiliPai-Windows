import importlib.util
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v038_listen_collection as collection


class V038ListenCollectionSourceTest(unittest.TestCase):
    def test_complete_upstream_pagination_has_an_exact_inverse_after_platform_adaptation(self):
        original = collection.read(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            proof = collection.emit(REPO, output)
            body = (output / proof['output']).read_text(encoding='utf-8')
            for edit in reversed(proof['edits']):
                at = edit['offset']
                self.assertEqual(edit['after'], body[at:at + len(edit['after'])])
                body = body[:at] + edit['before'] + body[at + len(edit['after']):]
            self.assertEqual(original, body)
            self.assertFalse(proof['collectionPlayerUiMounted'])
            self.assertFalse(proof['mainCompiled'])

    def test_source_or_manifest_tampering_is_rejected_before_generation(self):
        for name in ('manifest.json', 'ListenVideoCollectionViewModel.kt'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                shutil.copytree(REPO / collection.ARCHIVE, root / collection.ARCHIVE)
                path = root / collection.ARCHIVE / name
                path.write_bytes(path.read_bytes() + b'\n')
                with self.assertRaises(ValueError): collection.emit(root, root / 'generated')
                self.assertFalse((root / 'generated').exists())

    def test_the_existing_sole_producer_emits_the_complete_collection_vm(self):
        spec = importlib.util.spec_from_file_location('listen_collection_producer102',
            REPO / 'desktop/tools/extract-upstream-listen-video-root.py')
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            producer.produce(REPO, output)
            targets = list(output.rglob('ListenVideoCollectionViewModel.kt'))
            self.assertEqual(1, len(targets))
            expected, _ = collection.adapt(collection.read(REPO))
            self.assertEqual(expected, targets[0].read_text(encoding='utf-8'))
            self.assertEqual(1, len(list(output.rglob('ListenVideoViewModel.kt'))))


if __name__ == '__main__': unittest.main()
