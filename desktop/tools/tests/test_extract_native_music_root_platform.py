from pathlib import Path
import hashlib
import importlib.util
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('native_music_root', REPO/'desktop/tools/extract-native-music-root-platform.py')
extractor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extractor)


class NativeMusicRootExtractionTest(unittest.TestCase):
    def test_function_body_is_original_and_generated_once(self):
        output = Path(self.enterContext(tempfile.TemporaryDirectory()))
        result = extractor.generate(REPO, output)
        declaration = extractor.declaration(REPO)
        self.assertEqual('package com.android.purebilibili.feature.video.ui.section\n\nimport com.android.purebilibili.data.model.response.BgmInfo\n\n'+declaration+'\n', result.read_text(encoding='utf-8'))
        self.assertEqual([result], list(output.rglob('*.kt')))
        row = extractor.inventory(REPO)[0]
        self.assertEqual(hashlib.sha256(declaration.encode()).hexdigest(), row['declarationSha256'])

    def test_source_identity_is_stable_for_windows_and_linux_checkouts(self):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        source = root/extractor.SOURCE
        source.parent.mkdir(parents=True)
        parser = root/'desktop/tools/sync-upstream.py'
        parser.parent.mkdir(parents=True)
        shutil.copyfile(REPO/'desktop/tools/sync-upstream.py', parser)
        original = (REPO/extractor.SOURCE).read_text(encoding='utf-8')
        source.write_bytes(original.encode())
        unix = extractor.inventory(root)
        source.write_bytes(original.replace('\n', '\r\n').encode())
        self.assertEqual(unix, extractor.inventory(root))
        self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), unix[0]['sha256'])


if __name__ == '__main__':
    unittest.main()
