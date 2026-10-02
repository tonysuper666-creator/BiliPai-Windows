from pathlib import Path
import difflib
import hashlib
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-original-bangumi-player-native-owner-root-parity'

def wide(path):
    value = str(path)
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + str(path.absolute()))

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain', '-z').strip()
manifest_raw = read(packet / 'frozen-handoff.json')
assert sha(manifest_raw) == '225b1e47214de40c3e0f3a19db177066bb4af89985345ddbcffb729bbb0edb8d'
manifest = json.loads(manifest_raw)
assert len(manifest['files']) == 96
assert len({r['path'] for r in manifest['files']}) == 96
for row in manifest['files']:
    path = Path(row['path'])
    assert not path.is_absolute() and '..' not in path.parts
    raw = read(packet / path)
    assert len(raw) == row['bytes'] and sha(raw) == row['sha256Bytes'], row['path']
contract_raw = read(packet / 'install-contract.json')
assert sha(contract_raw) == '8a40e96100a8a8bcc06523ea3ebad33ce205232983d3f44b4afa05df9ffb032a'
contract = json.loads(contract_raw)
assert git('rev-parse', 'HEAD').decode().strip() == contract['candidateBase']
assert contract['installableBridgeSlice'] and not contract['installableFullPlayer']

def metadata(key):
    descriptor = contract[key]
    raw = read(Path(descriptor['path']))
    assert sha(raw) == descriptor['sha256Bytes']
    return json.loads(raw)

hunks = metadata('existingExactHunks')
target_receipt = metadata('existingTargets')
delta = metadata('registryDelta')
audit = metadata('sourceInverseAudit')
assert audit['reverseReplayByteEqual'] and audit['ordinarySoleProducerRecipesUnchanged']
assert audit['ordinaryOutputEqualsActualPlusDeclaredDelta'] and audit['protocolOriginalInverse']
assert audit['protocolOutputSameAsFrozen'] and len(hunks) == 17
before, desired, replays = {}, {}, []
for row in target_receipt['targets']:
    name = row['path']
    assert not Path(name).is_absolute() and '..' not in Path(name).parts
    raw = read(candidate / name)
    text = raw.replace(b'\r\n', b'\n').decode('utf-8')
    assert sha(text.encode()) == row['beforeSha256LF'], name
    lines = text.splitlines(keepends=True)
    pieces, reverse_pieces, cursor = [], [], 0
    target_hunks = sorted((h for h in hunks if h['target'] == name), key=lambda h: h['startLine'])
    assert len(target_hunks) == row['hunkCount']
    for hunk in target_hunks:
        start, end = hunk['startLine'], hunk['endLineExclusive']
        assert cursor <= start <= end <= len(lines)
        original = ''.join(lines[start:end])
        assert original == hunk['before']
        assert sha(original.encode()) == hunk['beforeSha256LF']
        unchanged = ''.join(lines[cursor:start])
        pieces.extend((unchanged, hunk['after']))
        reverse_pieces.extend((unchanged, hunk['before']))
        cursor = end
    tail = ''.join(lines[cursor:])
    pieces.append(tail)
    reverse_pieces.append(tail)
    output = ''.join(pieces).encode()
    assert sha(output) == row['afterSha256LF'], name
    assert ''.join(reverse_pieces) == text
    before[name], desired[name] = raw, output
    replays.append(dict(path=name, exactHunks=len(target_hunks), fullBeforeHashMatched=True,
        fullAfterHashMatched=True, reverseReplayByteEqualLF=True))
for row in contract['newFileCopyWhitelist']:
    name = row['target']
    assert name not in desired and not Path(name).is_absolute() and '..' not in Path(name).parts
    assert not wide(candidate / name).exists(), name
    raw = read(Path(row['source']))
    assert sha(raw) == row['sha256Bytes']
    before[name], desired[name] = None, raw
assert len(desired) == 15
old_registry = json.loads(before['desktop/upstream-sources.json'])
new_registry = json.loads(desired['desktop/upstream-sources.json'])
assert len(old_registry['sources']) == 1219 and len(new_registry['sources']) == 1225
assert old_registry['resources'] == new_registry['resources'] and len(new_registry['resources']) == 244
old_by_path = {r['path']: r for r in old_registry['sources']}
new_by_path = {r['path']: r for r in new_registry['sources']}
assert len(new_by_path) == 1225
declared_paths = {r['path'] for r in delta['sourceDelta']}
assert {p for p,r in new_by_path.items() if old_by_path.get(p) != r} == declared_paths
assert set(old_by_path) <= set(new_by_path)
for row in delta['sourceDelta']:
    if row['operation'] == 'add-original-identity':
        assert row['path'] not in old_by_path and new_by_path[row['path']] == row['row']
    else:
        assert old_by_path[row['path']] == row['before'] and new_by_path[row['path']] == row['after']
        assert set(row['before']['features']) < set(row['after']['features'])

# All hashes, exact original spans, registry unions, and new-file absence pass
# before the first production write. No prepared existing file is copied.
for name, raw in before.items():
    if raw is not None:
        backup = wide(root / 'before' / name)
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_bytes(raw)
for name, raw in desired.items():
    target = wide(candidate / name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
targets, patch = [], []
for name, raw in desired.items():
    assert read(candidate / name) == raw
    targets.append(dict(path=name, beforeSha256Bytes=sha(before[name]) if before[name] is not None else None,
        afterSha256Bytes=sha(raw), afterSha256LF=sha(raw.replace(b'\r\n', b'\n'))))
    patch.extend(difflib.unified_diff((before[name] or b'').replace(b'\r\n', b'\n').decode().splitlines(keepends=True),
        raw.replace(b'\r\n', b'\n').decode().splitlines(keepends=True), fromfile='a/'+name, tofile='b/'+name))
receipt = dict(baseCommit=contract['candidateBase'], sourceTargets=targets, sourceCount=1225, resourceCount=244,
    exactExistingTargets=9, exactExistingFragments=17, newCanonicalFiles=6, actualProductOverrides=0,
    frozenManifestSha256Bytes=sha(manifest_raw), frozenContractSha256Bytes=sha(contract_raw),
    independentlyValidatedFrozenArtifacts=96, reverseReplays=replays, newDependencies=[],
    fullPlayerScreenAccepted=False, rootRuntimeAccepted=False, nativeRuntimeAccepted=False,
    realAccountAccepted=False, desktopPackageUpdated=False)
(root/'installation.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
(root/'source-diff.patch').write_text(''.join(patch),encoding='utf-8',newline='\n')
print(json.dumps({k:v for k,v in receipt.items() if k not in ('sourceTargets','reverseReplays')},indent=2))
