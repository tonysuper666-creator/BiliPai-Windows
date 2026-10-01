from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';LANE=MAIN/'desktop/.local/stable-personal-following-parity';OUT=HERE/'following-install50'
assert not OUT.exists();sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def lf(p):return read(p).replace(b'\r\n',b'\n')
raw=read(LANE/'frozen-handoff.json');assert sha(raw)=='e45fd84a73f7db257351b66e29e737c19badceeeb70d76f7cad52c8f164d2830'
frozen=json.loads(raw);assert len(frozen['artifacts'])==81
for row in frozen['artifacts']:assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'install-whitelist.json');assert sha(raw)=='add19df9af061ac329354fbc472088916d5c2731458ca5067d345ffd887a14bb';contract=json.loads(raw);pending={};targets=[]
assert json.loads(read(HERE/'personal-watchlater-install50/installed.json'))['applied']
for row in contract['copies']:
 data=read(LANE/row['source']);assert sha(data)==row['sha256Bytes'];target=REPO/row['target'];assert not wide(target).exists();pending[target]=data
for patchrow in contract['patches']:
 patch=LANE/patchrow['path'];assert sha(read(patch))==patchrow['sha256Bytes']
 subprocess.run(['git','apply','--check',str(patch)],cwd=REPO,check=True,capture_output=True)
 bases=json.loads(read(LANE/patchrow['bases']));targets.extend(bases if isinstance(bases,list) else [bases])
reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==1002;delta=json.loads(read(LANE/contract['registryUnion']))
for row in delta['newSources']:
 assert not any(x['path']==row['path'] for x in registry['sources']);assert sha(lf(REPO/row['path']))==row['sha256'];registry['sources'].append(row)
for row in delta['mergeFeatures']:
 existing=next(x for x in registry['sources'] if x['path']==row['path']);assert existing['sha256']==row['requiredExistingSha256']
 for f in row['addFeatures']:
  if f not in existing['features']:existing['features'].append(f)
assert len(registry['sources'])==1007;pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts';data=lf(gradle).decode();assert 'extractOriginalFollowing' not in data
pending[gradle]=(data.rstrip('\n')+'\n\n'+lf(LANE/contract['gradleSnippet']).decode()).encode()
OUT.mkdir()
for target in set(pending)|{REPO/r['path'] for r in targets}:
 if wide(target).exists():
  p=wide(OUT/'baseline'/target.relative_to(REPO));p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(read(target))
for target,data in pending.items():wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(data)
for patchrow in contract['patches']:subprocess.run(['git','apply',str(LANE/patchrow['path'])],cwd=REPO,check=True,capture_output=True)
report=dict(applied=True,newOriginalIdentities=5,sourceRegistryCount=1007,copies=3,exactPatchTargets=len(targets),wholeCompilationPending=True,actualRootRuntimePending=True,newExeDeployed=False)
wide(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
