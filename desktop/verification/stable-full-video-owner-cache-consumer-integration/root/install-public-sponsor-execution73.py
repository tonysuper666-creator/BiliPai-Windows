from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-sponsor-public-execution-parity'
OUT = HERE / 'public-sponsor-execution-install73'
assert not OUT.exists()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert head == '08d31bd020c515fc1e48775809766082fead6117'
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
assert sha(raw) == '068527d481025253f76c4fd2a57b827b7ff34a2f443cddc4b6bf428b706e6cd3'
packet = json.loads(raw)
assert len(packet['artifacts']) == packet['rawRows'] == 34
for row in packet['artifacts']:
    value = read(LANE / row['path'])
    assert sha(value) == row['sha256Bytes'] and len(value) == row['bytes'], row['path']
contract_raw = read(LANE / 'install-exact-hunks.json')
assert sha(contract_raw) == 'ad391610ead85bbe9bbc7518709aebd560fdef73b9b43798d83e9017d4514379'
contract = json.loads(contract_raw)
assert contract['baseFrozen120SHA256Bytes'] == '829c748364219b1458151c546bbdae62d8cd053f613d877b94285f9b9143f5cb'
assert len(contract['operations']) == 2
changes = []
for operation in contract['operations']:
    name = operation['path']
    before = read(REPO / name)
    text = lf(before)
    assert sha(text) == operation['baseLF'], name
    positions = []
    for hunk in operation['replacements']:
        old, new = hunk['before'].encode(), hunk['after'].encode()
        assert text.count(old) == hunk['count'] == 1, name
        at = text.index(old)
        positions.append((at, old, new))
        text = text[:at] + new + text[at + len(old):]
    assert sha(text) == operation['candidateLF'], name
    inverse = text
    for at, old, new in reversed(positions):
        assert inverse[at:at + len(new)] == new
        inverse = inverse[:at] + old + inverse[at + len(new):]
    assert inverse == lf(before), name
    changes.append((name, before, text, len(positions)))
assert sum(row[3] for row in changes) == 3

# Complete immutable input/whole-family/hash/anchor/inverse validation precedes
# writes. The Runtime bridge and original public HTTP client remain untouched.
records = []
for name, before, after, count in changes:
    dump(OUT / 'before' / name, before)
    dump(OUT / 'after' / name, after)
    dump(REPO / name, after)
    records.append(dict(path=name, beforeSha256Bytes=sha(before), afterSha256Bytes=sha(after),
        beforeSha256LF=sha(lf(before)), afterSha256LF=sha(after), hunks=count, exactIndexedInverse=True))
report = dict(phase=73, baseCommit=head, preparedRawArtifacts=34,
    preparedManifestSha256Bytes=sha(raw), payloadCount=0, existingFamilyCount=2,
    exactHunks=3, changedFiles=records, sharedWholeFilesReplaced=False,
    originalPublicClientPreserved=True, originalPostJsonProtocolPreserved=True,
    sameRuntimeCapturedOperationAndActualCallerJob=True,
    finalRequestPermitLinearizesInFlightIo=True, executeAndBodyReadOutsideRootGates=True,
    newIdentityStoreClientActorScopeDependency=False,
    originalOnLoadGetTransportNewlyAdmitted=False, realPublicHttpAccepted=False,
    fullRootMounted=False, desktopExeReplaced=False)
dump(OUT / 'installed.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(existingFamilies=2, exactHunks=3, originalPublicClientPreserved=True)))
