from pathlib import Path
import json,hashlib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-danmaku-list-menu-parity'
def wide(p):return Path('\\\\?\\'+str(p.absolute()))
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.decode('utf-8').replace('\r\n','\n').encode()
def pin(p,d):
    b=read(p);assert sha(b)==d,p;return b
raw=pin(LANE/'frozen-handoff.json','d1625f76f8af2adf198e3354951f5b88dc831bd936b588de868455f7b71c5fdb')
m=json.loads(raw)
for r in m['artifacts']:
    b=pin(LANE/r['path'],r['sha256Bytes']);assert len(b)==r['bytes']
c=json.loads(pin(LANE/'install-contract.json','d8475a11f405a019876e210cc0ec3bc03c60696d7792005ed253a96ea42790a6'))
d=json.loads(read(LANE/'platform-delta-contract.json'))
artifacts={r['path']:r for r in m['artifacts']}
targets=[c['producer'],
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDanmakuBindings.kt',
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDanmakuHost.kt']
changes=[];payload=[]
for p in targets:
    target=p.removeprefix('prepared/');assert not (REPO/target).exists(),target
    payload.append((target,pin(LANE/p,artifacts[p]['sha256Bytes'])))
for r in d['selectedFiles']:
    target=r['path'];assert sha(lf(read(REPO/target)))==r['baseSha256LF'],target
    p='prepared/'+target;b=pin(LANE/p,artifacts[p]['sha256Bytes']);assert sha(lf(b))==r['candidateSha256LF']
    payload.append((target,b))
registry_path=REPO/'desktop/upstream-sources.json';before=read(registry_path);registry=json.loads(before)
assert len(registry['sources'])==741
by={r['path']:r for r in registry['sources']}
direct={r['path'] for r in c['directOnceReferences']}
for r in c['originalSources']:
    path=r['path'];assert sha(lf(read(REPO/path)))==r['sha256LF']
    if path in by:
        row=by[path];assert row['sha256']==r['sha256LF'];changes.append(dict(path=path,operation='feature merge',mode=row['mode']))
    else:
        row=dict(path=path,sha256=r['sha256LF'],mode='direct' if path in direct else 'selected',features=[])
        registry['sources'].append(row);by[path]=row;changes.append(dict(path=path,operation='append',mode=row['mode']))
    if 'stable-danmaku-list-menu' not in row['features']:row['features'].append('stable-danmaku-list-menu')
ops=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt';ops_before=lf(read(ops))
fragment=pin(LANE/'fragments/DesktopDynamicCardOperations.danmaku.ktfrag','ba38d8f321674af90e6daa13c23e90031f41a0d900f9a7faf081404ba4b0cd38')
marker=b'// GENERATED original editor members; do not hand-maintain a second request algorithm.'
assert ops_before.count(marker)==1 and b'private val originalDanmakuActions' not in ops_before
ops_after=ops_before.replace(marker,lf(fragment)+b'\n'+marker)
for target,b in payload:
    p=wide(REPO/target);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
wide(ops).write_bytes(ops_after)
wide(registry_path).write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
report=dict(passed=True,frozenManifestSha256Bytes=sha(raw),frozenArtifactsVerified=len(m['artifacts']),
    beforeRegistrySha256Bytes=sha(before),sourceIdentities=len(registry['sources']),resourceCount=len(registry['resources']),
    preservedExistingModes=True,registryOperations=changes,payloads=[dict(path=p,sha256Bytes=sha(b)) for p,b in payload],
    opsBeforeLfSha256=sha(ops_before),opsAfterLfSha256=sha(ops_after),strictExistingProtocolFragmentsUnchanged=True,
    rootOriginalUiMounted=False,nativeOverlayPointerAccepted=False)
wide(HERE/'danmaku-source-install.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:report[k] for k in ('passed','sourceIdentities','resourceCount','frozenArtifactsVerified')}))
