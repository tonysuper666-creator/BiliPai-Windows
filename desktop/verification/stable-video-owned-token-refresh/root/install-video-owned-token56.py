from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-video-owned-token-refresh-parity'
OUT = HERE / 'video-owned-token-install56'
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
assert sha(raw) == '8725f0374227e10d4bb716a2d847bdef72be0fbbd90dd45848f6e3fbb3e5202c'
rows = json.loads(raw)['rawArtifacts']
assert len(rows) == 58
for row in rows:
    data = read(LANE / row['path'])
    assert sha(data) == row['sha256Bytes'] and len(data) == row['bytes'], row['path']
raw = read(LANE / 'INSTALL-RECIPE.json')
assert sha(raw) == '9337f4313f143dd1d609c364e008d97dd7c5a1aaaaf9ac12b1c89e26f1e0eedb'
recipe = json.loads(raw)
assert sha(read(recipe['dependsOnBridge41']['path'])) == recipe['dependsOnBridge41']['sha256Bytes']
pending = {}
for item in recipe['hunks']:
    delta = json.loads(read(LANE / item['file']))
    target = REPO / delta['path']
    before = lf(target).decode()
    assert sha(before.encode()) == item['baseLF'] == delta['baseLFsha256']
    assert len(delta['hunks']) == item['count']
    after = before
    for hunk in delta['hunks']:
        assert after.count(hunk['before']) == 1, hunk['label']
        after = after.replace(hunk['before'], hunk['after'], 1)
    assert sha(after.encode()) == item['candidateLF'] == delta['candidateLFsha256']
    pending[target] = after.encode()
manual = recipe['manual']
assert Path(manual['path']).is_relative_to(LANE)
data = lf(manual['path'])
assert sha(data) == manual['sha256LF']
target = REPO / manual['target']
assert not wide(target).exists()
pending[target] = data

registry_path = REPO / 'desktop/upstream-sources.json'
registry = json.loads(read(registry_path))
assert len(registry['sources']) == 1056
added = 0
unions = 0
for row in recipe['sourceIdentityMerge']:
    assert sha(lf(REPO / row['path'])) == row['sha256Bytes']
    current = next((r for r in registry['sources'] if r['path'] == row['path']), None)
    if current:
        assert current['sha256'] == row['sha256Bytes']
        assert row['feature'] not in current['features']
        current['features'].append(row['feature'])
        unions += 1
    else:
        mode = 'reference-only' if row['mode'] == 'reference' else row['mode']
        assert mode in ('policy-extract', 'reference-only')
        registry['sources'].append(dict(path=row['path'], sha256=row['sha256Bytes'], mode=mode, features=[row['feature']]))
        added += 1
assert len(registry['sources']) == 1056 + added
pending[registry_path] = (json.dumps(registry, ensure_ascii=False, indent=2) + '\n').encode()

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
report = dict(applied=True, targets=installed, sourceIdentityCount=len(registry['sources']), newSourceIdentities=added,
    existingIdentityUnions=unions, newDependencies=0, newClientsOrActors=0, repositoryHunks=4, loginHunks=1,
    requiredOwnedTvRefreshTailInstalled=True, wholeCompilationPending=True, fullOrdinaryVideoRootMountAccepted=False,
    originalDedicatedPlaybackAccountNoRefreshPolicyPreserved=True, nativeAuthorityRetired=False)
(OUT / 'installed.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf8', newline='\n')
print(json.dumps(dict(applied=True, targets=len(installed), sourceIdentityCount=len(registry['sources']), newSourceIdentities=added)))
