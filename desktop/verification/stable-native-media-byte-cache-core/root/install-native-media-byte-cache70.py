from pathlib import Path
import hashlib,json,subprocess

HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-native-media-byte-cache-parity';OUT=HERE/'native-media-byte-cache-install70'
assert not OUT.exists(),'One-shot installation; never replay mutation'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()=='9fbfe50973de4dc2b04984ef6ef6870bee617f2b'
assert not subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=REPO)
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def write(p,b):
    p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
def encoded(d):return (json.dumps(d,indent=2)+'\n').encode()
manifest=read(LANE/'frozen-handoff.json');assert sha(manifest)=='62a15351de76f512ecb6ccae86e206f8e2fd5eeb8f0582124deff30d2af488e6'
frozen=json.loads(manifest);assert len(frozen['artifacts'])==frozen['rawCount']==240
pins={}
for row in frozen['artifacts']:
    p=LANE/row['path'];b=read(p)
    assert sha(b)==row['sha256Bytes'] and len(b)==row['size'],row['path']
    pins[row['path']]=row['sha256Bytes']
contract=json.loads(read(LANE/'install-contract.json'))
assert len(contract['payloadWhitelist'])==5
payloads=[]
for row in contract['payloadWhitelist']:
    b=read(LANE/row['source']['path'])
    assert sha(b)==row['source']['sha256Bytes']==pins[row['source']['path']]
    assert len(b)==row['source']['size'] and not wide(REPO/row['target']).exists(),row['target']
    payloads.append((row['target'],b))
for key in ('exactHunks','registryMerge','recipe','sourceAudit'):
    row=contract[key];b=read(LANE/row['path']);assert sha(b)==row['sha256Bytes']==pins[row['path']] and len(b)==row['size']
patches=json.loads(read(LANE/contract['exactHunks']['path']));assert len(patches['hunks'])==21
before,after={},{}
for row in patches['hunks']:
    path=row['path']
    if path not in before:before[path]=read(REPO/path);after[path]=lf(before[path]).decode()
    content=after[path]
    assert sha(content.encode())==row['beforeSha256LF'] and content.count(row['before'])==1,row['label']
    content=content.replace(row['before'],row['after'],1)
    assert sha(content.encode())==row['afterSha256LF'],row['label']
    after[path]=content
assert len(before)==9
registry_path='desktop/upstream-sources.json';raw_registry=read(REPO/registry_path);registry=json.loads(raw_registry)
assert len(registry['sources'])==1169 and len(registry['resources'])==213
merge=json.loads(read(LANE/contract['registryMerge']['path']));existing=merge['existing']
rows=[r for r in registry['sources'] if r['path']==existing['path']];assert len(rows)==1 and rows[0]==existing
assert sha(lf(read(REPO/existing['path'])))==existing['sha256']
rows[0]['features']=sorted(set(rows[0]['features'])|{merge['requiredFeature']});rows[0]['mode']=merge['newMode']
assert len(registry['sources'])==1169 and merge['identityRowsAdded']==0
before[registry_path]=raw_registry;after[registry_path]=encoded(registry).decode()
OUT.mkdir()
for path,b in before.items():
    a=after[path].encode();write(OUT/'before'/path,b);write(OUT/'after'/path,a);write(REPO/path,a)
for path,b in payloads:write(OUT/'after'/path,b);write(REPO/path,b)
report=dict(applied=True,payloadCount=5,rawPacketArtifactsVerified=240,exactHunks=21,exactHunkFiles=9,
    sourceIdentityCount=1169,resourceCount=213,newSourceIdentities=0,newDependencies=0,newHttpClients=0,
    wholeReferenceFilesInstalled=False,wholeSharedFamilyReplacement=False,
    targets=[dict(path=p,sha256Bytes=sha(b))for p,b in payloads],
    changedFiles=[dict(path=p,beforeSha256Bytes=sha(b),afterSha256Bytes=sha(after[p].encode()))for p,b in before.items()],
    originalPolicyModePromotion=rows[0],wholeCompilationPending=True,
    actualInstalledNativeCarrierAccepted=False,rootConsumerMounted=False,desktopExeReplaced=False)
write(OUT/'installed.json',encoded(report))
print(json.dumps({k:report[k]for k in ('applied','payloadCount','rawPacketArtifactsVerified','exactHunks','exactHunkFiles','sourceIdentityCount')}))
