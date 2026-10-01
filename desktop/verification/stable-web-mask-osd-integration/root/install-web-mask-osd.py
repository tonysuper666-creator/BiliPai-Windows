from pathlib import Path
import hashlib,json,sys
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2].parent/'BiliPai-v023'
OUT=HERE/'web-mask-osd-install';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def pin(p,h):
    b=read(p);assert sha(b)==h,str(p);return b
lanes=[(REPO/'desktop/.local/stable-danmaku-web-mask-parity','5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5'),
    (REPO/'desktop/.local/stable-danmaku-native-osd-mask-delta','bb8d61645481ba0eba1eeb2c0069cff4f9fde1bc46bbd57d655b5e0b327029b2')]
for lane,h in lanes:
    m=json.loads(pin(lane/'frozen-handoff.json',h))
    for r in m['evidence']:pin(lane/r['relative'],r['sha256Bytes'])
contents={};before={};newpaths=[];checks=[]
mask=lanes[0][0];contract=json.loads(read(mask/'install-contract.json'))
for r in contract['payloads']:
    b=pin(r['prepared'],r['sha256Bytes'])
    p=REPO/r['path']
    if not wide(p).exists():contents[r['path']]=lf(b);newpaths.append(r['path'])
for lane,_ in lanes:
    hunks=json.loads(read(lane/'local-hunks.json'))
    for r in hunks:
        path=r['path']
        if path not in contents:
            before[path]=read(REPO/path);contents[path]=lf(before[path])
        b=contents[path];assert sha(b)==r['beforeSha256LF'],('before',path,sha(b),r['beforeSha256LF'])
        s=b.decode('utf-8');count=r.get('requiredOldCount',1);assert s.count(r['old'])==count,(path,s.count(r['old']),count)
        b=s.replace(r['old'],r['new']).encode('utf-8');assert sha(b)==r['afterSha256LF'],('after',path)
        contents[path]=b;checks.append(dict(path=path,oldCount=count,reason=r['reason'],beforeSha256LF=r['beforeSha256LF'],afterSha256LF=r['afterSha256LF']))
    if lane==mask:
        for r in contract['payloads']:
            assert contents[r['path']]==lf(read(r['prepared'])),('payload-hunk equivalence',r['path'])
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));index={r['path']:r for r in registry['sources']}
recipe=json.loads(read(mask/'registry-recipe.json'));added=[];merged=[]
for r in recipe['newRows']:
    assert r['path'] not in index
    assert sha(lf(read(REPO/r['path'])))==r['sha256']
    registry['sources'].append(r);index[r['path']]=r;added.append(r['path'])
for r in recipe['mergeOnly']:
    old=index[r['path']];old['features']=list(dict.fromkeys(old['features']+r['addFeatures']));merged.append(r['path'])
assert len(registry['sources'])==833
print(json.dumps(dict(verified=True,productionFiles=len(contents),exactLocalHunks=len(checks),newPayloads=len(newpaths),registryCount=833)))
if '--apply' not in sys.argv:sys.exit(0)
OUT.mkdir()
for name,b in before.items():
    p=wide(OUT/'baseline'/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
wide(OUT/'baseline-registry.json').write_bytes(read(REPO/'desktop/upstream-sources.json'))
for name,b in contents.items():
    p=wide(REPO/name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
(REPO/'desktop/upstream-sources.json').write_text(json.dumps(registry,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
report=dict(applied=True,maskFrozenSHA256=lanes[0][1],osdFrozenSHA256=lanes[1][1],newSources=newpaths,
    localHunks=checks,registryAdded=added,registryMerged=merged,sourceIdentityCount=833,
    files=[dict(path=p,sha256LF=sha(b))for p,b in contents.items()],sourceOnly=True,nativeOrFullRootAccepted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
