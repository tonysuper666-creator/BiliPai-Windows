from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-original-video-byte-cache-consumers-parity'
OUT = HERE / 'original-cache-consumers-install73'
assert not OUT.exists()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert head == '08d31bd020c515fc1e48775809766082fead6117'

def wide(p):
    value = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def read(p): return wide(p).read_bytes()
def sha(value): return hashlib.sha256(value).hexdigest()
def lf(value): return value.replace(b'\r\n', b'\n')
def dump(p, value):
    target = wide(p)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(value)

preceding = [json.loads(read(HERE / name / 'installed.json')) for name in
    ('public-sponsor-execution-install73', 'video-root-effects-install73')]
expected_modified = set()
for installed in preceding:
    for row in installed['changedFiles']:
        expected_modified.add(row['path'])
        assert sha(read(REPO / row['path'])) == row['afterSha256Bytes']
    for row in installed.get('targets', []):
        assert sha(read(REPO / row['path'])) == row['sha256Bytes']
actual_modified = set(subprocess.check_output(['git', 'diff', '--name-only'], cwd=REPO, text=True).splitlines())
assert actual_modified == expected_modified, actual_modified
raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == 'b0f87d87632549bf9e5b28ae0f4447f999947253e8cf0bc8d66fde0466a07628'
packet = json.loads(raw)
assert len(packet['artifacts']) == packet['rawArtifactCount'] == 260
for row in packet['artifacts']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['size'], row['path']
contract_raw = read(LANE / 'install-contract.json')
assert sha(contract_raw) == 'ea1cf8c1b5bdfd4f269319c3fe500840d29c120ed7853d4e676c441b96de6151'
contract = json.loads(contract_raw)
for key in ('exactHunks', 'baselineFamilies', 'registryMerge', 'recipe'):
    row = contract[key]
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['size']
baselines = json.loads(read(LANE / contract['baselineFamilies']['path']))
assert baselines['baselineCommit'] == head
families = {row['target']: row for row in baselines['families']}
assert len(families) == 8
originals, changes, positions = {}, {}, {}
for name, row in families.items():
    before = read(REPO / name)
    assert sha(before) == row['baselineWholeFileSha256Bytes'], name
    assert sha(lf(before)) == row['baselineWholeFileSha256LF'], name
    originals[name] = before
    changes[name] = lf(before)
    positions[name] = []
hunks = json.loads(read(LANE / contract['exactHunks']['path']))['hunks']
assert len(hunks) == 12
for hunk in hunks:
    name = hunk['path']
    old, new = hunk['before'].encode(), hunk['after'].encode()
    assert sha(old) == hunk['beforeSha256LF'] and sha(new) == hunk['afterSha256LF']
    text = changes[name]
    assert text.count(old) == 1, (name, hunk['label'])
    at = text.index(old)
    positions[name].append((at, old, new))
    changes[name] = text[:at] + new + text[at + len(old):]
for name, row in families.items():
    assert sha(changes[name]) == row['candidateWholeFileSha256LF'], name
    inverse = changes[name]
    for at, old, new in reversed(positions[name]):
        assert inverse[at:at + len(new)] == new
        inverse = inverse[:at] + old + inverse[at + len(new):]
    assert inverse == lf(originals[name]), name
payloads = []
for row in contract['copyWhitelist']:
    source = row['source']
    value = read(LANE / source['path'])
    assert sha(value) == source['sha256Bytes'] and len(value) == source['size']
    assert not wide(REPO / row['target']).exists()
    payloads.append((row['target'], value))
assert len(payloads) == 2

registry_name = 'desktop/upstream-sources.json'
registry_before = read(REPO / registry_name)
registry = json.loads(registry_before)
assert len(registry['sources']) == 1169 and len(registry['resources']) == 213
unions = []
recipe = json.loads(read(LANE / contract['registryMerge']['path']))
assert recipe['identityRowsAdded'] == 0 and recipe['featuresOnly']
for item in recipe['rows']:
    matches = [row for row in registry['sources'] if row['path'] == item['path']]
    assert len(matches) == 1
    row = matches[0]
    assert row['sha256'] == item['sha256'] and row['mode'] == item['mode']
    assert set(item['existingFeatures']).issubset(row['features'])
    assert item['requiredFeature'] not in row['features']
    old = list(row['features'])
    row['features'].append(item['requiredFeature'])
    unions.append(dict(path=row['path'], sha256=row['sha256'], mode=row['mode'],
        beforeFeatures=old, afterFeatures=row['features']))
registry_after = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()
changes[registry_name] = registry_after
originals[registry_name] = registry_before

# Frozen payloads, whole-family bases, snippet hashes, forward/reverse replay,
# desired whole-family hashes and preserving registry unions all pass before IO.
targets, changed = [], []
for name, value in payloads:
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    targets.append(dict(path=name, sha256Bytes=sha(value)))
for name, value in changes.items():
    dump(OUT / 'before' / name, originals[name])
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    changed.append(dict(path=name, beforeSha256Bytes=sha(originals[name]), afterSha256Bytes=sha(value),
        exactHunks=len(positions.get(name, [])), exactIndexedInverse=name in positions))
report = dict(phase=73, baseCommit=head, preparedRawArtifacts=260,
    preparedManifestSha256Bytes=sha(raw), payloadCount=2, existingFamilyCount=8,
    exactHunks=12, targets=targets, changedFiles=changed, existingIdentityFeatureUnions=unions,
    sharedWholeFilesReplaced=False, newIdentityCount=0, newDependencyCount=0,
    sameBoundNativeAndOriginalCdnAndPortraitConsumersInstalled=True,
    exactFullPlanRequiredForRetainedCarrierReuse=True, callerAndLeaseCancellationThroughBodyClose=True,
    nativeCacheErrorDirectObserverInstalled=False, installedNativeDirectRecoveryAccepted=False,
    fullRootMounted=False, desktopExeReplaced=False)
dump(OUT / 'installed.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(payloadCount=2, existingFamilies=8, exactHunks=12, identityFeatureUnions=len(unions))))
