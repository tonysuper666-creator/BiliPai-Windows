from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-player-plugin-final-write-parity'
ROOT = MAIN / 'desktop/.local/stable-video-plugin-owner-bridge-parity'
OUT = HERE / 'player-plugin-owner-install72'
assert not OUT.exists()
base_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert base_commit == '5507a44a2085be09f52ff8e4561beb71713c9a97'
assert not subprocess.check_output(['git', 'status', '--porcelain=v1'], cwd=REPO)

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

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == '829c748364219b1458151c546bbdae62d8cd053f613d877b94285f9b9143f5cb'
packet = json.loads(raw)
assert len(packet['artifacts']) == packet['rawRows'] == 120
for row in packet['artifacts']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['bytes'], row['path']
operations_raw = read(LANE / 'install-exact-hunks.json')
assert sha(operations_raw) == 'f4e4780f5eabf9c38989f38bf40fd7ffb50c79b56cf1ae19c25ab80d154fc0ad'
operations = json.loads(operations_raw)['operations']
assert len(operations) == 3
changes = {}
originals = {}
records = []
def replay(name, text, hunks):
    positions = []
    original = text
    for row in hunks:
        old, new = row['before'].encode(), row['after'].encode()
        assert text.count(old) == row.get('count', 1) == 1, name
        at = text.index(old)
        positions.append((at, old, new))
        text = text[:at] + new + text[at + len(old):]
    inverse = text
    for at, old, new in reversed(positions):
        assert inverse[at:at + len(new)] == new
        inverse = inverse[:at] + old + inverse[at + len(new):]
    assert inverse == original, name
    return text

for operation in operations:
    name = operation['path']
    original = read(REPO / name)
    text = lf(original)
    assert sha(text) == operation['baseLF'], name
    updated = replay(name, text, operation['replacements'])
    assert sha(updated) == operation['candidateLF'], name
    changes[name] = updated
    originals[name] = original
    records.append(dict(path=name, hunks=len(operation['replacements']), beforeSha256LF=sha(text),
        afterSha256LF=sha(updated), exactIndexedInverse=True))
assert sum(row['hunks'] for row in records) == 7
request_hunk = json.loads(read(ROOT / 'runtime-request-hunk.json'))
name = request_hunk['path']
assert sha(lf(originals[name])) == request_hunk['baseSha256LF']
root_first = replay(name, lf(originals[name]), request_hunk['hunks'])
assert sha(root_first) == request_hunk['candidateSha256LF']
child_runtime = next(row for row in operations if row['path'] == name)
combined_root_first = replay(name, root_first, child_runtime['replacements'])
combined_child_first = replay(name, changes[name], request_hunk['hunks'])
assert combined_root_first == combined_child_first
assert sha(combined_child_first) == '2b33a3c46c0e28d41542a2b54cba4180c7e41a7b89c7ab29bdbe14c11a3d65d0'
changes[name] = combined_child_first
records.append(dict(path=name, hunks=1, rootRequestHelper=True,
    orderIndependentExactReplay=True, combinedSha256LF=sha(combined_child_first)))

helper = read(LANE / 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPlayerPluginWriteAdmission.kt')
assert sha(lf(helper)) == '307eea214e4b8efc7d67db6b853861ace3a35697c6b2ea7bc5463fa202731162'
bridge = read(ROOT / 'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt')
assert b'throw CancellationException("Sponsor upload seek owner retired")' in bridge
assert b'runtime.runPlaybackPluginCallback(dispatch, plugin, calls)' in bridge
payloads = [
    ('desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPlayerPluginWriteAdmission.kt', helper),
    ('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt', bridge),
]
for name, value in payloads:
    assert not wide(REPO / name).exists(), name

registry_name = 'desktop/upstream-sources.json'
registry_raw = read(REPO / registry_name)
registry = json.loads(registry_raw)
assert len(registry['sources']) == 1169 and len(registry['resources']) == 213
feature_rows = []
for suffix, expected in [
    ('/feature/plugin/CdnRegionPlugin.kt', 'd0e1c74436524d943c6b044ea984ad5564b6f3b4ee1b686883ead67a095094b9'),
    ('/feature/plugin/SponsorBlockInsightPolicy.kt', '981179d2b0828afc35df97bedb6b7d6c8283f7c7f9a1762415f40eb2b55fd113'),
]:
    row = next(row for row in registry['sources'] if row['path'].endswith(suffix))
    assert row['sha256'] == expected and row['mode'] == 'extracted'
    before_features = list(row['features'])
    feature = 'stable-original-video-plugin-owner'
    assert feature not in row['features']
    row['features'].append(feature)
    feature_rows.append(dict(path=row['path'], sha256=expected, modeUnchanged=True,
        beforeFeatures=before_features, afterFeatures=row['features']))
changes[registry_name] = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()
originals[registry_name] = registry_raw

# All production writes follow complete packet/anchor/hash/inverse validation.
targets = []
for name, value in payloads:
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    targets.append(dict(path=name, sha256Bytes=sha(value), sha256LF=sha(lf(value))))
changed = []
for name, value in changes.items():
    dump(OUT / 'before' / name, originals[name])
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    changed.append(dict(path=name, beforeSha256Bytes=sha(originals[name]), afterSha256Bytes=sha(value)))
dump(OUT / 'root-runtime-request-hunk.json', read(ROOT / 'runtime-request-hunk.json'))
report = dict(phase=72, baseCommit=base_commit, preparedRawArtifacts=120,
    preparedManifestSha256Bytes=sha(raw), payloadCount=2, existingFamilyCount=3,
    childExactHunks=7, rootExactHunks=1, rootHelperAndChildOrderIndependent=True,
    patchRecords=records, targets=targets, changedFiles=changed,
    featureUnions=feature_rows, newIdentityCount=0, newDependencyCount=0,
    fullRootMounted=False, actualNativePluginCompletedSeekAccepted=False,
    publicSponsorTransportClosurePending=True, desktopExeReplaced=False)
dump(OUT / 'installed.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(payloadCount=2, existingFamilies=3, exactHunks=8, existingIdentityFeatureUnions=2)))
