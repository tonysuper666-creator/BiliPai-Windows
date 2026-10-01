from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-repository-core-ports-parity'
OUT = HERE / 'video-repository-core-install55'
assert not OUT.exists()
sha = lambda b: hashlib.sha256(b).hexdigest()

def wide(p):
    s = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)

def read(p):
    return wide(p).read_bytes()

def lf(p):
    return read(p).replace(b'\r\n', b'\n')

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == 'c9128187e1828c0b18a1f1c776d3c3b4f900751bf676f2edbfcbefd628e4437e'
rows = json.loads(raw)['rawArtifacts']
assert len(rows) == 41
for row in rows:
    data = read(LANE / row['path'])
    assert len(data) == row['bytes'] and sha(data) == row['sha256Bytes'], row['path']
raw = read(LANE / 'INSTALL-RECIPE.json')
assert sha(raw) == 'ca483487ea2960db984ccd422c815b9c831d85d0d5046e383f64de9ca3be9cf0'
recipe = json.loads(raw)
assert recipe['upstreamCommit'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
pending = {}
for row in recipe['payload']:
    source = Path(row['path'])
    assert source.is_relative_to(LANE)
    data = lf(source)
    assert sha(data) == row['sha256LF']
    target = REPO / row['target']
    assert not wide(target).exists()
    pending[target] = data
for item in recipe['localHunks']:
    delta = json.loads(read(LANE / item['file']))
    target = REPO / delta['path']
    before = lf(target).decode()
    assert sha(before.encode()) == delta['baseLFsha256'] == item['baselineLF']
    assert len(delta['hunks']) == item['hunks']
    after = before
    for hunk in delta['hunks']:
        assert after.count(hunk['before']) == 1, hunk['name']
        after = after.replace(hunk['before'], hunk['after'], 1)
    assert sha(after.encode()) == delta['candidateLFsha256'] == item['candidateLF']
    pending[target] = after.encode()

# These two policies now have one audited original identity each. The source
# registry adds no generated PlayUrlCache object; the existing cache remains sole.
registry_path = REPO / 'desktop/upstream-sources.json'
registry = json.loads(read(registry_path))
assert len(registry['sources']) == 1055
assert registry['upstreamCommit'] == recipe['upstreamCommit']
inventory = json.loads(read(LANE / 'original-source-inventory.json'))
assert inventory['commit'] == recipe['upstreamCommit']
feature = 'stable-video-repository-core-ports'
cache = inventory['identities'][0]
video = inventory['identities'][1]
assert cache['path'] == 'app/src/main/java/com/android/purebilibili/core/cache/PlayUrlCache.kt'
assert cache['sha256Bytes'] == '7ba14a4b369229afee920422a98a669b27a171b906f215edaab7c7704d341eda'
assert not any(r['path'] == cache['path'] for r in registry['sources'])
assert sha(lf(REPO / cache['path'])) == cache['sha256Bytes']
registry['sources'].append(dict(path=cache['path'], sha256=cache['sha256Bytes'], mode='policy-extract', features=[feature]))
current = next(r for r in registry['sources'] if r['path'] == video['path'])
assert current['sha256'] == video['sha256Bytes'] == sha(lf(REPO / video['path']))
assert feature not in current['features']
current['features'].append(feature)
assert len(registry['sources']) == 1056
pending[registry_path] = (json.dumps(registry, indent=2, ensure_ascii=False) + '\n').encode()

OUT.mkdir()
installed = []
for target, data in pending.items():
    relative = target.relative_to(REPO).as_posix()
    original = read(target) if wide(target).exists() else None
    if original is not None:
        p = wide(OUT / 'before' / relative)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(original)
    p = wide(OUT / 'after' / relative)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    wide(target).parent.mkdir(parents=True, exist_ok=True)
    wide(target).write_bytes(data)
    installed.append(dict(path=relative, baseSha256Bytes=sha(original) if original is not None else None,
        installedSha256Bytes=sha(data), installedSha256LF=sha(data.replace(b'\r\n', b'\n'))))
report = dict(applied=True, targets=installed, sourceIdentityCount=1056, newSourceIdentities=1,
    newDependencies=0, repositoryLocalHunks=5, soleCacheLocalHunks=3, separateCacheOrClientConstructed=False,
    originalCacheCapacity=80, originalCacheMinutes=10, originalCacheExpiryComparison='strict greater than',
    soleWbiMinutes=30, wholeCompilationPending=True, fullOrdinaryVideoRootMountAccepted=False,
    requiredOwnedTvRefreshTailPending=True)
(OUT / 'installed.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf8', newline='\n')
print(json.dumps(dict(applied=True, targets=len(installed), sourceIdentityCount=1056)))
