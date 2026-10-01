from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'full-video-owner-factory-install71'
assert not OUT.exists()
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip() == 'efc9b0c0f1827b9d1d29b324f9ad25e9be3e513c'
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

packets = [
    ('stable-video-captured-metadata-binding-parity', '73b2cb6a872a561c14f67eb5d72288d8e26e6f07ec8a64abdabd5da4165639f2', 16),
    ('stable-video-full-owner-factory-parity', '04b2b35a64c7fea1fcc691a487b45665c61b38ab0a773ca810ddbeae51363319', 73),
    ('stable-video-domain-owner-factory-parity', '5c571ec3907e6037707b5430b4c92042b920a828f21affd8f28774df2ed70c12', 26),
    ('stable-video-media-intent-parity', '8357e7cdf392d34811330c10a722ef5133abd109a3b6e5767bad5a5c92806a82', 52),
]
lanes = {}
for name, expected, count in packets:
    lane = MAIN / 'desktop/.local' / name
    raw = read(lane / 'frozen-handoff.json')
    assert sha(raw) == expected, name
    packet = json.loads(raw)
    rows = packet.get('artifacts', packet.get('raw'))
    assert len(rows) == count, (name, len(rows))
    for row in rows:
        value = read(lane / row['path'])
        assert sha(value) == row['sha256Bytes'], row['path']
        size = row.get('bytes', row.get('size', row.get('sizeBytes')))
        assert len(value) == size, row['path']
    lanes[name] = lane

metadata, request, domain, intent = [lanes[row[0]] for row in packets]
changes = {}
originals = {}
records = []
def patch(item, rebased=False, key='hunks'):
    name = item.get('target', item.get('path'))
    path = REPO / name
    original = read(path)
    base = changes.get(name, lf(original))
    expected = item['baseSHA256LF']
    if not rebased:
        assert sha(base) == expected, (name, sha(base), expected)
    elif sha(base) != expected:
        # The exact cache70 Repository is the only accepted rebase. Its new
        # transport/header/namespace hunks remain untouched by this accessor.
        installed70 = json.loads(read(HERE / 'native-media-byte-cache-install70/installed.json'))
        installed_row = next(r for r in installed70['changedFiles'] if r['path'] == name)
        assert sha(original) == installed_row['afterSha256Bytes'], name
    before = base
    positions = []
    hunks = item[key]
    for hunk in hunks:
        old = hunk['before'].encode()
        new = hunk['after'].encode()
        assert base.count(old) == 1, (name, old[:100], base.count(old))
        position = base.index(old)
        positions.append((position, old, new))
        base = base[:position] + new + base[position + len(old):]
    inverse = base
    for position, old, new in reversed(positions):
        assert inverse[position:position + len(new)] == new
        inverse = inverse[:position] + old + inverse[position + len(new):]
    assert inverse == before, name
    desired = item.get('desiredSHA256LF', item.get('candidateSHA256LF'))
    if not rebased:
        assert sha(base) == desired, (name, sha(base), desired)
    changes[name] = base
    originals.setdefault(name, original)
    records.append(dict(path=name, hunks=len(hunks), inputSha256LF=sha(before),
        outputSha256LF=sha(base), frozenBaseSha256LF=expected, frozenDesiredSha256LF=desired,
        rebasedOverExactCache70=rebased, exactIndexedInverse=True))

patch(json.loads(read(metadata / 'local-hunks/binding.json')))
patch(json.loads(read(metadata / 'local-hunks/repository-visitor.json')), rebased=True)
for item in json.loads(read(request / 'exact-hunks.json')): patch(item)
patch(json.loads(read(request / 'retained-pause-producer-hunk.json')), key='changes')
for item in json.loads(read(intent / 'exact-hunks.json')): patch(item)

payloads = []
for lane in (request, intent):
    contract = json.loads(read(lane / 'install-contract.json'))
    for row in contract['copyWhitelist']:
        value = read(lane / row['source'])
        assert sha(lf(value)) == row['sha256LF'], row['source']
        payloads.append((row['target'], value, row['sha256LF']))
contract = json.loads(read(domain / 'install-contract.json'))
assert contract['onlyInstall'] == ['prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoDomainOwners.kt']
value = read(domain / contract['onlyInstall'][0])
payloads.append(('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoDomainOwners.kt',
    value, sha(lf(value))))
assert len(payloads) == 3 and len(changes) == 8
assert sum(row['hunks'] for row in records) == 15
for name, value, expected in payloads:
    assert not wide(REPO / name).exists(), name
    assert sha(lf(value)) == expected

# All prerequisites, before bytes, exact anchors, desired hashes and reverse
# replay have passed before any production write.
targets = []
for name, value, expected in payloads:
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    targets.append(dict(path=name, sha256Bytes=sha(value), sha256LF=expected))
changed = []
for name, value in changes.items():
    dump(OUT / 'before' / name, originals[name])
    dump(OUT / 'after' / name, value)
    dump(REPO / name, value)
    changed.append(dict(path=name, beforeSha256Bytes=sha(originals[name]), afterSha256Bytes=sha(value)))
report = dict(phase=71, baseCommit='efc9b0c0f1827b9d1d29b324f9ad25e9be3e513c',
    frozenPackets=[dict(lane=name, manifestSha256Bytes=expected, rawCount=count) for name, expected, count in packets],
    payloadCount=3, existingFamilyCount=8, exactHunks=15, patchRecords=records,
    targets=targets, changedFiles=changed, wholeFileReplacement=False,
    sourceIdentityDelta=0, resourceDelta=0, dependencyDelta=0, gradleDelta=0,
    fullRootMounted=False, nativeInitialIntentAccepted=False, desktopExeReplaced=False)
dump(OUT / 'installed.json', (json.dumps(report, indent=2) + '\n').encode())
print(json.dumps(dict(payloadCount=len(payloads), existingFamilies=len(changes), exactHunks=15)))
