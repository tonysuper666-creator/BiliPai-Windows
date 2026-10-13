"""The complete original cross-platform scrollable list component; no renderer substitute."""
from pathlib import Path
import argparse, hashlib, json, os

ROOT = Path(__file__).resolve().parent.parent / 'upstream-slices/v035-scrollable-lists'
MANIFEST_SHA256 = 'c30b46788b2676262fbac882d6bcd13a25752393fb15eeb037b34d5ead2bbef0'

def wide(path):
    value = str(path.resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value) if os.name == 'nt' else path

def main(output):
    manifest_raw = wide(ROOT / 'manifest.json').read_bytes()
    assert hashlib.sha256(manifest_raw).hexdigest() == MANIFEST_SHA256
    manifest = json.loads(manifest_raw)
    assert manifest['mode'] == 'whole-original-direct' and manifest['adaptations'] == []
    original = wide(ROOT / manifest['archiveFile']).read_bytes()
    assert len(original) == manifest['bytesRaw'] and hashlib.sha256(original).hexdigest() == manifest['rawSha256']
    assert hashlib.sha1(b'blob ' + str(len(original)).encode() + b'\0' + original).hexdigest() == manifest['gitBlob']
    target = wide(output / manifest['output'])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(original)
    print(json.dumps(dict(path=manifest['output'], rawSha256=manifest['rawSha256'], fullOriginal=True, compiled=False)))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    main(parser.parse_args().output.resolve())
