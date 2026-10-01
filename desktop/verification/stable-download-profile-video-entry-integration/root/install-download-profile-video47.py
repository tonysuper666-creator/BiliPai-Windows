from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=HERE/'download-profile-video-install47';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def txt(p):return read(p).decode('utf-8').replace('\r\n','\n')
def frozen(name,pin,count):
 lane=MAIN/'desktop/.local'/name;raw=read(lane/'frozen-handoff.json');assert sha(raw)==pin;rows=json.loads(raw)['artifacts'];assert len(rows)==count
 for r in rows:assert sha(read(lane/r['path']))==r['sha256Bytes'],r['path']
 return lane
D=frozen('stable-download-list-parity','582f49bc0b43b01f31b6ca93f2381a2ac299771af5d80d8648f86c7d62a50958',58)
P=frozen('stable-profile-account-port-parity','250100d194c524add5b3baf05b1efd1d470a63d4045ed4274c624e9b6292f5c2',56)
V=frozen('stable-video-detail-admission-parity','ee4c6d9fea64b8294bfe6bd995c68f12304f41820594fae1181fc6b2787b4da9',69)
pending={};receipts=[]
def add(target,data):
 p=REPO/target;assert not p.exists() and p not in pending,target;pending[p]=data
for r in json.loads(read(D/'install-contract.json'))['payloads']:
 data=read(Path(r['source']));assert sha(data)==r['sha256Bytes'];add(r['target'],data)
r=json.loads(read(P/'installation-recipe.json'))['newManualSource'];data=read(P/r['source']);assert sha(data.replace(b'\r\n',b'\n'))==r['sha256Lf'];add(r['destination'],data)
for lane in [P,V]:
 for r in json.loads(read(lane/'local-hunks.json'))['changes']:
  p=REPO/r['path'];assert p not in pending;rbase=txt(p);assert sha(rbase.encode())==r['baseSha256Lf'],r['path'];body=rbase
  for h in r['hunks']:
   assert body.count(h['before'])==1,(r['path'],h['before']);body=body.replace(h['before'],h['after'],1)
  assert sha(body.encode())==r['candidateSha256Lf'],r['path'];pending[p]=body.encode();receipts.append(dict(path=r['path'],baseSha256Lf=sha(rbase.encode()),resultSha256Lf=sha(body.encode()),hunks=len(r['hunks'])))
dh=json.loads(read(D/'shared-local-hunks.json'))
for r in dh['targets']:
 p=REPO/r['path'];assert p not in pending;rbase=txt(p);assert sha(rbase.encode())==r['baseSha256LF'],(r['path'],sha(rbase.encode()));body=rbase;count=0
 for h in [h for h in dh['rows'] if h['target']==r['path']]:
  assert body.count(h['before'])==h['count']==1;(before,after)=(h['before'],h['after']);body=body.replace(before,after,1);count+=1
 assert sha(body.encode())==r['candidateSha256LF'],r['path'];pending[p]=body.encode();receipts.append(dict(path=r['path'],baseSha256Lf=sha(rbase.encode()),resultSha256Lf=sha(body.encode()),hunks=count))
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==935;merge=json.loads(read(D/'registry-merge-recipe.json'))
for r in merge['newRows']:
 assert not any(e['path']==r['path']for e in registry['sources']);assert sha(read(REPO/r['path']).replace(b'\r\n',b'\n'))==r['sha256'];registry['sources'].append(r)
for r in merge['existingFeatureMerges']:
 e=next(e for e in registry['sources']if e['path']==r['path']);assert e['sha256']==r['sha256'];assert r['appendFeature']not in e['features'];e['features'].append(r['appendFeature'])
assert len(registry['sources'])==938;pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
g=REPO/'desktop/build.gradle.kts';body=txt(g);marker='val extractOriginalRootHomeNavigation by tasks.registering(Exec::class) {';assert body.count(marker)==1;assert 'val extractOriginalDownloadList 'not in body;snippet=txt(D/'gradle-task.snippet.kts');pending[g]=body.replace(marker,snippet+'\n'+marker,1).encode()
OUT.mkdir()
for p,data in pending.items():
 if p.exists():
  backup=wide(OUT/'baseline'/p.relative_to(REPO));backup.parent.mkdir(parents=True,exist_ok=True);backup.write_bytes(read(p))
 wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(data)
report=dict(applied=True,newPayloads=4,existingFamilyHunks=sum(r['hunks']for r in receipts),families=receipts,newOriginalIdentities=3,sourceCount=938,resources=213,newRuntimeArtifacts=0,wholeCompilationPending=True,rootMounted=False,newExeDeployed=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
