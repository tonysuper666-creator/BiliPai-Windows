from pathlib import Path
import hashlib
import json
import difflib
import subprocess

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
packet = main / 'desktop/.local/stable-image-preview-native-share'

def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def read(path): return wide(path).read_bytes()
def sha(data): return hashlib.sha256(data).hexdigest()

assert not (root / 'installation.json').exists()
assert not subprocess.check_output(['git', '-c', 'core.longpaths=true', 'status', '--porcelain'], cwd=candidate)
base = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=candidate, text=True).strip()
frozen = read(packet / 'frozen-handoff.json')
assert sha(frozen) == '08348a61492eb0ae499e576b013a1e2741c13e93861597f0299c795d0db6e01c'
for entry in json.loads(frozen)['files']:
    path = packet / entry['path']
    assert path.resolve().is_relative_to(packet.resolve())
    raw = read(path)
    assert sha(raw) == entry['sha256Bytes'], entry['path']
contract = json.loads(read(packet / 'installation-contract.json'))
assert base == contract['currentCandidateHead']
changes = []
diffs = []
for entry in contract['targets']:
    path = candidate / entry['target']
    assert path.resolve().is_relative_to(candidate.resolve())
    old = read(path) if wide(path).exists() else None
    assert (sha(old) if old is not None else None) == entry['beforeSha256Bytes'], entry['target']
    new = read(packet / entry['prepared'])
    assert sha(new) == entry['afterSha256Bytes']
    # Reviewed exact-baseline target edits only; preserve byte contracts and record the narrow text diff.
    changes.append((entry['target'], old, new))
    diffs.extend(difflib.unified_diff((old or b'').decode().replace('\r\n','\n').splitlines(True),
                 new.decode().replace('\r\n','\n').splitlines(True), fromfile=entry['target'], tofile=entry['target']))
approval = 'desktop/native/diagnostic-share/approved-development-build.json'
old_approval = json.loads(read(candidate / approval))
new_approval = json.loads(read(packet / 'prepared' / approval))
assert set(old_approval) == set(new_approval)
changed_keys = {key for key in old_approval if old_approval[key] != new_approval[key]}
assert changed_keys == {'sourceSha256Bytes', 'dllSha256Bytes'}
assert new_approval['dllSha256Bytes'] == contract['binaryCheckOnlySha']
relative = 'desktop/upstream-sources.json'
old = read(candidate / relative)
registry = json.loads(old)
entry = contract['registry']
matches = [row for row in registry['sources'] if row['path'] == entry['existingIdentity']]
assert len(matches) == 1 and matches[0]['mode'] == entry['mode'] and matches[0]['sha256'] == entry['sha256']
assert sha(read(candidate / entry['existingIdentity']).replace(b'\r\n', b'\n')) == entry['sha256']
for feature in entry['suggestedFeatureUnion']:
    assert feature not in matches[0]['features']
    matches[0]['features'].append(feature)
assert len(registry['sources']) == 1174
new = (json.dumps(registry, ensure_ascii=False, indent=2)+'\n').replace('\n','\r\n').encode()
changes.append((relative, old, new))
receipt = {'baseCommit': base, 'sourceTargets': [], 'originalSourceCountUnchanged': 1174,
           'approvalChangedKeys': sorted(changed_keys), 'binaryCopied': False,
           'freshNativeProducerPending': True, 'receiverUiAccepted': False, 'longPathSupported': False}
for relative, old, new in changes:
    path = candidate / relative
    assert (read(path) if wide(path).exists() else None) == old
    if old is not None:
        backup = root / 'before' / relative
        wide(backup.parent).mkdir(parents=True, exist_ok=True); wide(backup).write_bytes(old)
    wide(path.parent).mkdir(parents=True, exist_ok=True); wide(path).write_bytes(new)
    assert read(path) == new
    receipt['sourceTargets'].append({'path': relative, 'beforeSha256Bytes': sha(old) if old else None,
                                    'afterSha256Bytes': sha(new), 'afterSha256Lf': sha(new.replace(b'\r\n',b'\n'))})
(root / 'source-diff.patch').write_text(''.join(diffs), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'installedSourceTargets': len(changes), 'originalSourceCount': 1174,
                  'nativeDllPin': contract['binaryCheckOnlySha'], 'binaryCopied': False}, indent=2))
