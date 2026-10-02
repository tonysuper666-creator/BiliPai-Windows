from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-original-story-native-baseline-delta'

def wide(path):
    return Path('\\\\?\\' + str(path.absolute()))

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

assert not (root / 'native-baseline-fix-installation.json').exists()
manifest_raw = read(packet / 'frozen-handoff.json')
assert sha(manifest_raw) == '368c0703c8d6572a61e44286ffb36b445999511017f5850f92c10d62025d6bbd'
manifest = json.loads(manifest_raw)
for entry in manifest['files']:
    raw = read(packet / entry['path'])
    assert len(raw) == entry['bytes'] and sha(raw) == entry['sha256Bytes'], entry['path']
assert len(manifest['files']) == manifest['rawCount'] == 69
contract_raw = read(packet / 'install-contract.json')
assert sha(contract_raw) == '62599048ead09f28e55401482a35ffbd89980a0a71723adc08f5dcfeb387f4fe'
contract = json.loads(contract_raw)
assert contract['copyWhitelist'] == [] and contract['newRegistryIdentities'] == 0
hunks = json.loads(read(packet / contract['exactHunks']))
assert len(hunks) == contract['hunkCount'] == 3
changes = []
for family in contract['targets']:
    path = family['path']
    old = read(candidate / path)
    text = old.replace(b'\r\n', b'\n').decode('utf-8')
    assert sha(text.encode()) == family['beforeSha256LF'], path
    selected = [h for h in hunks if h['path'] == path]
    for hunk in selected:
        assert sha(hunk['before'].encode()) == hunk['beforeSha256LF']
        assert sha(hunk['after'].encode()) == hunk['afterSha256LF']
        assert text.count(hunk['before']) == 1, hunk['name']
        text = text.replace(hunk['before'], hunk['after'])
    assert sha(text.encode()) == family['afterSha256LF'], path
    inverse = text
    for hunk in reversed(selected):
        assert inverse.count(hunk['after']) == 1
        inverse = inverse.replace(hunk['after'], hunk['before'])
    assert inverse.encode() == old.replace(b'\r\n', b'\n')
    raw = text.encode()
    if b'\r\n' in old:
        raw = raw.replace(b'\n', b'\r\n')
    changes.append((path, old, raw))
assert {h['path'] for h in hunks} == {path for path, _, _ in changes}
registry_hash = sha(read(candidate / 'desktop/upstream-sources.json'))
evidence = json.loads(read(packet / contract['nativeEvidence']))
assert evidence['passed']
receipt = {'frozenManifestSha256Bytes': sha(manifest_raw), 'verifiedRawArtifacts': 69,
           'exactHunksApplied': 3, 'existingSourceFamilies': 2,
           'preparedNativeEvidence': contract['nativeEvidence'],
           'preparedActualNativeChecks': 11,
           'nativeBaselineReplacementFixPending': False,
           'ordinaryVideoSuccessAccepted': False, 'storyVisualAccepted': False,
           'rawPayloadAdoptionAccepted': False, 'accountNetworkAccepted': False,
           'sourceTargets': []}
for path, old, raw in changes:
    assert read(candidate / path) == old
    backup = root / 'before-native-baseline-fix' / path
    wide(backup.parent).mkdir(parents=True, exist_ok=True)
    wide(backup).write_bytes(old)
    wide(candidate / path).write_bytes(raw)
    assert read(candidate / path) == raw
    receipt['sourceTargets'].append({'path': path, 'beforeSha256Bytes': sha(old),
                                    'afterSha256Bytes': sha(raw),
                                    'afterSha256LF': sha(raw.replace(b'\r\n', b'\n'))})
assert sha(read(candidate / 'desktop/upstream-sources.json')) == registry_hash
(root / 'native-baseline-fix-installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'sourceFamilies': len(changes), 'exactHunks': 3,
                  'nativeReplacementFixturePassed': True,
                  'accountNetworkAccepted': False}, indent=2))
