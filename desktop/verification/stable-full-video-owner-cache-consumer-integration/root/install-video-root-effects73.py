from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-root-effects-parity'
OUT = HERE / 'video-root-effects-install73'
assert not OUT.exists()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert head == '08d31bd020c515fc1e48775809766082fead6117'

def wide(p):
    value = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def read(p): return wide(p).read_bytes()
def sha(value): return hashlib.sha256(value).hexdigest()
def dump(p, value):
    target = wide(p)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(value)

# The only existing uncommitted production changes must be this same phase's
# previously validated public Sponsor patch. No concurrent production mutation.
public = json.loads(read(HERE / 'public-sponsor-execution-install73/installed.json'))
expected_changed = {row['path'] for row in public['changedFiles']}
actual_changed = set(subprocess.check_output(['git', 'diff', '--name-only'], cwd=REPO, text=True).splitlines())
assert actual_changed == expected_changed, actual_changed
for row in public['changedFiles']:
    assert sha(read(REPO / row['path'])) == row['afterSha256Bytes']

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == '25771d468adc09c45e200ff8e85477688532e869d9c6acb4befcbd02eaa0374a'
packet = json.loads(raw)
assert len(packet['artifacts']) == 10 and packet['compilerSourceCount'] == 2
for row in packet['artifacts']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['bytes']
contract_raw = read(LANE / 'install-whitelist.json')
assert sha(contract_raw) == 'e57acfe4e3a95cf627432cde3f18d7580ed5fe5fa23bccbb00d0b4eac7f3ecad'
contract = json.loads(contract_raw)
assert len(contract['payloads']) == 2
payloads = []
for row in contract['payloads']:
    value = read(row['source'])
    assert sha(value) == row['sha256Bytes']
    assert sha(value.replace(b'\r\n', b'\n')) == row['sha256LF']
    assert not wide(REPO / row['destination']).exists()
    payloads.append((row['destination'], value))

name = 'desktop/upstream-sources.json'
before = read(REPO / name)
registry = json.loads(before)
assert len(registry['sources']) == 1169 and len(registry['resources']) == 213
union = contract['existingOriginalIdentitiesFeatureUnion']
unions = []
for identity in union['identities']:
    matches = [row for row in registry['sources'] if row['path'] == identity]
    assert len(matches) == 1, identity
    row = matches[0]
    old = list(row['features'])
    assert union['feature'] not in old
    row['features'].append(union['feature'])
    unions.append(dict(path=identity, sha256=row['sha256'], mode=row['mode'],
        beforeFeatures=old, afterFeatures=row['features']))
after = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()

# All validated new payloads and identity unions precede any writes.
targets = []
for destination, value in payloads:
    dump(OUT / 'after' / destination, value)
    dump(REPO / destination, value)
    targets.append(dict(path=destination, sha256Bytes=sha(value)))
dump(OUT / 'before' / name, before)
dump(OUT / 'after' / name, after)
dump(REPO / name, after)
report = dict(phase=73, baseCommit=head, preparedRawArtifacts=10,
    preparedManifestSha256Bytes=sha(raw), payloadCount=2, targets=targets,
    changedFiles=[dict(path=name, beforeSha256Bytes=sha(before), afterSha256Bytes=sha(after))],
    existingOriginalIdentityFeatureUnions=unions, newIdentityCount=0,
    newClientStoreActorScopeDependencyCount=0, realPreferredNetworkQueryRuntimeAccepted=False,
    diagnosticWriterRuntimeAccepted=False, firebaseTransportAvailable=False,
    fullRootMounted=False, desktopExeReplaced=False)
dump(OUT / 'installed.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(payloadCount=2, existingIdentityFeatureUnions=len(unions))))
