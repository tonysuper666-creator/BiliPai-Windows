from pathlib import Path
import hashlib,json,subprocess,sys
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
kind=sys.argv[1];assert kind in ('queue','watchlater')
LANE=MAIN/'desktop/.local'/('stable-personal-queue-start-parity' if kind=='queue' else 'stable-personal-watchlater-parity')
OUT=HERE/('personal-'+kind+'-install50');assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def lf(p):return read(p).replace(b'\r\n',b'\n')
raw=read(LANE/'frozen-handoff.json');assert sha(raw)==({'queue':'4db0037aa5066bf15bece6588681c91fdf66d9061b835f8585aadff19feed1f9','watchlater':'2d5d6ba3e5830dc07e9716b6b21833c07f4153608a4d0c34c55f1a1182e891ac'}[kind])
frozen=json.loads(raw)
for row in frozen['artifacts']:assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'install-whitelist.json');assert sha(raw)==({'queue':'3f648f037098124f4eaabb28d1d26c2a68656007063769420c687877ed697598','watchlater':'4905c1b421706738067eedad83e9d0120a3a597fa9211dcf5ca5f4e940229bfc'}[kind])
contract=json.loads(raw);pending={}
for row in contract['copies']:
 data=read(LANE/row['source']);assert sha(data)==row['sha256Bytes'];target=REPO/row['target'];assert not wide(target).exists();pending[target]=data
patch=LANE/contract['patches'][0]['path'];assert sha(read(patch))==contract['patches'][0]['sha256Bytes']
subprocess.run(['git','apply','--check',str(patch)],cwd=REPO,check=True,capture_output=True)
if kind=='queue':
 targets=contract['patches'][0]['targets']
 for row in targets:assert sha(lf(REPO/row['path']))==row['baseSha256LfUtf8'],row['path']
else:
 assert json.loads(read(HERE/'personal-queue-install50/installed.json'))['applied']
 bases=json.loads(read(LANE/contract['patches'][0]['bases']))
 targets=bases if isinstance(bases,list) else bases['targets']
 reg=REPO/'desktop/upstream-sources.json';registry=json.loads(read(reg));assert len(registry['sources'])==956
 delta=json.loads(read(LANE/contract['registryUnion']))
 for row in delta['newSources']:
  assert not any(x['path']==row['path'] for x in registry['sources']);assert sha(lf(REPO/row['path']))==row['sha256'];registry['sources'].append(row)
 for row in delta['mergeFeatures']:
  existing=next(x for x in registry['sources'] if x['path']==row['path']);assert existing['sha256']==row['requiredExistingSha256']
  for f in row['addFeatures']:
   if f not in existing['features']:existing['features'].append(f)
 assert len(registry['sources'])==959;pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
 gradle=REPO/'desktop/build.gradle.kts';data=lf(gradle).decode();assert 'extractOriginalWatchLater' not in data
 pending[gradle]=(data.rstrip('\n')+'\n\n'+lf(LANE/contract['gradleSnippet']).decode()).encode()
OUT.mkdir()
paths=set(pending)
for row in targets:paths.add(REPO/(row['path'] if isinstance(row,dict) else row))
for target in paths:
 if wide(target).exists():
  p=wide(OUT/'baseline'/target.relative_to(REPO));p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(read(target))
for target,data in pending.items():wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(data)
subprocess.run(['git','apply',str(patch)],cwd=REPO,check=True,capture_output=True)
if kind=='queue':
 for row in targets:assert sha(lf(REPO/row['path']))==row['desiredSha256LfUtf8'],row['path']
report=dict(applied=True,kind=kind,copies=len(contract['copies']),exactPatchTargets=len(targets),newOriginalIdentities=0 if kind=='queue' else 3,wholeCompilationPending=True,actualRootRuntimePending=True,newExeDeployed=False)
wide(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
