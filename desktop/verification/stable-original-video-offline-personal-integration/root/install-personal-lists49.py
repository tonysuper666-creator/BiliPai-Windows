from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-personal-history-liked-parity'; OUT=HERE/'personal-lists-install49'
assert not OUT.exists()
def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92); return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def lf(p): return read(p).replace(b'\r\n',b'\n')
sha=lambda b:hashlib.sha256(b).hexdigest()
raw=read(LANE/'frozen-handoff.json'); assert sha(raw)=='f763a58f2e370e6139d2dba60d1daa4ae35792defcac5ff24d4b941e48d1cad8'
for row in json.loads(raw)['artifacts']: assert sha(read(LANE/row['path']))==row['sha256Bytes'],row['path']
raw=read(LANE/'install-whitelist.json'); assert sha(raw)=='0697a5f80f93e3faccdbf897feb6ec336d670bc312b7c4715108d1bacddfdfdb'
contract=json.loads(raw); pending={}
for row in contract['copy']:
 data=read(LANE/row['from']); assert sha(data)==row['sha256Bytes']; target=REPO/row['to']; assert not wide(target).exists(); pending[target]=data
patch=LANE/contract['patch']['path']; assert sha(read(patch))==contract['patch']['sha256Bytes']
subprocess.run(['git','apply','--check',str(patch)],cwd=REPO,check=True,capture_output=True)
reg=REPO/'desktop/upstream-sources.json'; registry=json.loads(read(reg)); assert len(registry['sources'])==955
delta=json.loads(read(LANE/contract['registry']))
for row in delta['newSources']:
 assert not any(x['path']==row['path'] for x in registry['sources']); assert sha(lf(REPO/row['path']))==row['sha256']; registry['sources'].append(row)
for row in delta['mergeFeatures']:
 existing=next(x for x in registry['sources'] if x['path']==row['path']); assert existing['sha256']==row['requiredExistingSha256']
 for feature in row['addFeatures']:
  if feature not in existing['features']: existing['features'].append(feature)
assert len(registry['sources'])==956; pending[reg]=(json.dumps(registry,ensure_ascii=False,indent=2)+'\n').encode()
gradle=REPO/'desktop/build.gradle.kts'; data=lf(gradle).decode(); assert 'extractOriginalPersonalLists' not in data
pending[gradle]=(data.rstrip('\n')+'\n\n'+lf(LANE/contract['gradle']).decode()).encode()
OUT.mkdir()
for target in list(pending)+[REPO/x for x in contract['patch']['targets']]:
 if wide(target).exists():
  baseline=wide(OUT/'baseline'/target.relative_to(REPO)); baseline.parent.mkdir(parents=True,exist_ok=True); baseline.write_bytes(read(target))
for target,data in pending.items(): wide(target).parent.mkdir(parents=True,exist_ok=True); wide(target).write_bytes(data)
subprocess.run(['git','apply',str(patch)],cwd=REPO,check=True,capture_output=True)
report=dict(applied=True,payloads=4,patchedConsumers=2,newOriginalIdentities=1,sourceRegistryCount=956,wholeCompilationPending=True,rootRuntimePending=True,newExeDeployed=False)
wide(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8'); print(json.dumps(report))
