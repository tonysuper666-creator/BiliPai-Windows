"""Verify the reviewed alpha.9 packing/gallery bodies against fresh extraction."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import sys
import tempfile

sys.dont_write_bytecode = True
EXPECTED = {
    'com/android/purebilibili/core/util/DesktopOriginalGalleryResultPolicy.kt':
        '4c6dff09291ee1c6732fc8af45fdb6d3fd288b228985599fcc47b9dedb45be1a',
    'com/android/purebilibili/feature/dynamic/components/DesktopOriginalMotionPhotoPacking.kt':
        '5659afa26c7eea3b21fe59f9cb158e1457b7315b2d2069c04e023dc11543384d',
}

def verify(repo, generated, output):
    tool = repo / 'desktop/tools/extract-upstream-dynamic-gallery-motion-photo.py'
    spec = importlib.util.spec_from_file_location('original_dynamic_media', tool)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix='bilipai-original-media-') as temporary:
        fresh = Path(temporary)
        module.generate(repo, fresh)
        assert {p.relative_to(fresh).as_posix() for p in fresh.rglob('*.kt')} == set(EXPECTED)
        assert {p.relative_to(generated).as_posix() for p in generated.rglob('*.kt')} == set(EXPECTED)
        for path, expected in EXPECTED.items():
            data = (fresh / path).read_bytes()
            assert hashlib.sha256(data).hexdigest() == expected, path + ' exceeds reviewed adaptations'
            assert (generated / path).read_bytes() == data, path + ' differs from the sole producer'
        receipt = json.loads((fresh / 'producer-receipt.json').read_text(encoding='utf-8'))
        assert receipt['selectedPacking']['deletedExactLines'] == [
            '        GCamera:MotionPhoto="1"',
            '        GCamera:MotionPhotoVersion="1"',
            '        GCamera:MotionPhotoPresentationTimestampUs="0"',
        ]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(dict(passed=True, originalTag=module.TAG,
        generated=EXPECTED, freshBytesEqual=True, nativeOrPackageAccepted=False), indent=2)+'\n',
        encoding='utf-8', newline='\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--generated', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    verify(args.repo, args.generated, args.output)
