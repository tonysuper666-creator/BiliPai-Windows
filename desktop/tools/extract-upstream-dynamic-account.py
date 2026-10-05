"""Fixed v029 account namespace policy and its original JVM tests, with an identity inverse."""
from pathlib import Path
import argparse, hashlib, json

COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
MANIFEST_SHA256 = '347721064abb1b14d1d8d48f730ebab1c673f00330c3fc3dc6f92b1f92d8c510'
SLICE = Path('desktop/upstream-slices/v029-dynamic-account')
PINS = {
    'DynamicAccountCachePolicy.kt': '2bb62ce8e2061d17d3ee6d8925e22d1a9053f8198b8832131b7facb0f3a3eea9',
    'DynamicAccountCachePolicyTest.kt': '8d5e6e8357c81ac289aeb1e361de3d0ce79ca79b3411918730e3f106bdab14bc',
    'DynamicViewModel.kt': 'f53e125cfd9bbfe23e315ae4524b03170a3bfcd0f28454f7220a08d60e360746',
}
PACKAGE = Path('com/android/purebilibili/feature/dynamic')

def digest(data): return hashlib.sha256(data).hexdigest()
def generate(repo, output, tests_output):
    repo = Path(repo).resolve()
    source = repo / SLICE
    manifest_path = source / 'manifest.json'
    if manifest_path.is_symlink() or not manifest_path.resolve().is_relative_to(repo): raise ValueError('Fixed source path escapes repository')
    manifest_raw = manifest_path.read_bytes()
    if digest(manifest_raw) != MANIFEST_SHA256: raise ValueError('Fixed source manifest changed')
    manifest = json.loads(manifest_raw)
    if manifest.get('commit') != COMMIT: raise ValueError('Unexpected fixed source commit')
    rows = {row['file']: row for row in manifest['sources']}
    if set(rows) != set(PINS): raise ValueError('Unexpected fixed source inventory')
    originals = {}
    for name, expected in PINS.items():
        path = source / name
        if path.is_symlink() or not path.resolve().is_relative_to(repo): raise ValueError('Fixed source path escapes repository')
        raw = path.read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if digest(raw) != expected or rows[name]['sha256Raw'] != expected or rows[name]['bytes'] != len(raw) or rows[name]['gitBlob'] != blob or rows[name]['commit'] != COMMIT:
            raise ValueError('Fixed raw source changed: ' + name)
        originals[name] = raw
    main_root, test_root = Path(output).resolve(), Path(tests_output).resolve()
    if main_root.is_relative_to(test_root) or test_root.is_relative_to(main_root): raise ValueError('Main/test generated roots overlap')
    targets = []
    for root, name in [(main_root, 'DynamicAccountCachePolicy.kt'), (test_root, 'DynamicAccountCachePolicyTest.kt')]:
        target = root / PACKAGE / name
        if target.resolve().is_relative_to(source.resolve()): raise ValueError('Output overlaps fixed source')
        if target.is_symlink() or not target.resolve().is_relative_to(root): raise ValueError('Generated source escapes output')
        if target.exists() and target.read_bytes() != originals[name]:
            raise ValueError('Refusing to overwrite modified generated source: ' + name)
        targets.append((target, name))
    for target, name in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(originals[name])
        if target.read_bytes() != originals[name]: raise ValueError('Identity inverse failed')
    return {'commit': COMMIT, 'rawPins': PINS, 'mainSources': 1, 'testSources': 1,
            'originalTests': 2, 'platformEdits': [], 'fullInverse': 'identity-byte-exact'}

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tests-output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(generate(args.repo, args.output, args.tests_output), sort_keys=True))
