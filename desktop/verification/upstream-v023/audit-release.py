"""Read Git blobs at immutable releases; leave the product checkout unchanged."""
from pathlib import Path
import hashlib, json, re, subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=REPO)
def sha(data): return hashlib.sha256(data).hexdigest()
def lf(data): return data.replace(b'\r\n', b'\n')
def tree(commit):
    result = {}
    for row in git('ls-tree', '-r', '-z', '--full-tree', commit).split(b'\0'):
        if not row: continue
        meta, path = row.split(b'\t', 1)
        mode, kind, oid = meta.decode().split()
        if kind == 'blob': result[path.decode('utf-8')] = dict(mode=mode, oid=oid)
    return result
def blobs(oids):
    oids = sorted(set(oids))
    raw = subprocess.run(['git', 'cat-file', '--batch'], input=('\n'.join(oids)+'\n').encode(),
                         cwd=REPO, check=True, capture_output=True).stdout
    result = {}; at = 0
    for expected in oids:
        end = raw.index(b'\n', at)
        actual, kind, size = raw[at:end].decode().split()
        assert actual == expected and kind == 'blob'
        at = end+1; size = int(size)
        result[actual] = raw[at:at+size]; at += size
        assert raw[at:at+1] == b'\n'; at += 1
    assert at == len(raw)
    return result
def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')

registry_raw = (REPO/'desktop/upstream-sources.json').read_bytes()
registry = json.loads(registry_raw)
release_raw = (HERE/'release-api.json').read_bytes()
release = json.loads(release_raw)
assert release['tag_name'] == 'v0.2.3' and not release['draft'] and not release['prerelease']
old_commit = registry['upstreamCommit']
new_commit = git('rev-parse', 'v0.2.3^{commit}').decode().strip()
old, new = tree(old_commit), tree(new_commit)
items = registry['sources'] + registry.get('resources', [])
resource_paths = {i['path'] for i in registry.get('resources', [])}
assert len(items) == 832 and len({i['path'] for i in items}) == 832
data = blobs(t[i['path']]['oid'] for i in items for t in (old,new) if i['path'] in t)
rows = []
for item in items:
    path = item['path']; resource = path in resource_paths
    raw_hash = item.get('hashNormalization',registry.get('hashNormalization','lf')) == 'raw'
    original = data[old[path]['oid']]
    old_hash = sha(original if raw_hash else lf(original))
    assert old_hash == item['sha256'], path
    candidate = data[new[path]['oid']] if path in new else None
    candidate_hash = sha(candidate if raw_hash else lf(candidate)) if candidate is not None else None
    rows.append(dict(path=path, mode=item.get('mode','resource' if resource else 'direct'),
                     hashNormalization='raw' if raw_hash else 'lf', features=item.get('features',[]),
                     pinnedHashVerified=True, currentSha256=old_hash, candidateSha256=candidate_hash,
                     status='removed' if candidate is None else 'changed' if candidate_hash != old_hash else 'unchanged'))

numstat = git('diff', '--numstat', old_commit, new_commit).decode('utf-8')
changed = []
for line in numstat.splitlines():
    added, removed, path = line.split('\t',2)
    changed.append(dict(path=path, added=added, removed=removed))
def production(paths):
    return sorted(p for p in paths if '/src/main/' in p and p.endswith(('.kt','.java'))
                  and not p.startswith('baselineprofile/'))
old_production, new_production = production(old), production(new)
changed_sources = [r for r in rows if r['mode'] != 'resource' and r['status'] != 'unchanged']
changed_resources = [r for r in rows if r['mode'] == 'resource' and r['status'] != 'unchanged']
new_production_paths = sorted(set(new_production)-set(old_production))
removed_production_paths = sorted(set(old_production)-set(new_production))
local_apk = Path('C:/Users/TONYS/Downloads/Telegram Desktop/BiliPai-0.2.3.apk')
asset = next(a for a in release['assets'] if a['name'] == local_apk.name)
local_apk_bytes = local_apk.read_bytes()
apk_sha = sha(local_apk_bytes)
assert len(local_apk_bytes) == asset['size']
assert 'sha256:'+apk_sha == asset['digest']

report = dict(schema='immutable-v023-target-audit-v1', currentProductHead=git('rev-parse','HEAD').decode().strip(),
    currentTag=registry['upstreamTag'], currentCommit=old_commit,
    candidateTag=release['tag_name'], candidateCommit=new_commit,
    releaseId=release['id'], releaseUrl=release['html_url'], publishedAt=release['published_at'],
    releaseApiSha256Bytes=sha(release_raw), registrySha256Bytes=sha(registry_raw),
    localApk=dict(path=str(local_apk), bytes=len(local_apk_bytes), sha256Bytes=apk_sha,
                  matchesOfficialPublishedAssetDigest=True, installed=False, executed=False),
    registeredSources=len(registry['sources']), registeredResources=len(registry.get('resources',[])),
    allCurrentSourceAndResourcePinsVerified=True,
    changedRegisteredSources=len(changed_sources), changedRegisteredResources=len(changed_resources),
    removedRegisteredPaths=[r['path'] for r in rows if r['status']=='removed'],
    affectedFeatureTags=sorted({f for r in changed_sources+changed_resources for f in r['features']}),
    originalProductionKotlinJavaFiles=len(old_production), candidateProductionKotlinJavaFiles=len(new_production),
    newProductionPaths=new_production_paths, removedProductionPaths=removed_production_paths,
    changedPaths=changed, registeredIdentityReview=rows,
    acceptance=dict(targetRecorded=True, productSourceRebased=False, generatedAdaptersRebased=False,
                    compiled=False, runtimeAccepted=False, packaged=False, released=False),
    previousAlpha9SourceReusePercent=591/1534*100,
    sourceReuseNote='The previous 38.5% file coverage is pinned to alpha.9; it is not a measured v0.2.3 reuse result.')
save(HERE/'source-delta.json', report)
save(HERE/'summary.json', {k:v for k,v in report.items() if k not in ('changedPaths','registeredIdentityReview')})
(HERE/'upstream-numstat.txt').write_text(numstat,encoding='utf-8',newline='\n')
print(json.dumps(dict(candidateCommit=new_commit,changedPaths=len(changed),
    changedRegisteredSources=len(changed_sources),changedRegisteredResources=len(changed_resources),
    removedRegisteredPaths=report['removedRegisteredPaths'],
    newProductionFiles=len(new_production_paths),candidateProductionFiles=len(new_production),
    localApkMatchesOfficial=True,reportSha256Bytes=sha((HERE/'source-delta.json').read_bytes())),ensure_ascii=False))
