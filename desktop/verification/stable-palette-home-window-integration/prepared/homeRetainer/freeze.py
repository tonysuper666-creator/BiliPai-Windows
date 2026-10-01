from pathlib import Path
import os,hashlib,json
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p)
 return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snap=MAIN/'desktop/.local/stable-product-snapshot-42'
assert sha(snap/'manifest.json')=='d84593c1a3dc908015f0526ae17375027082d91a9eba12e81b70dadde9779d54'
assert sha(snap/'ordered-runtime-cp.json')=='6fb3e971ee12e0f77c7a7dd6e54a4af64456b3e8bab8446660c1f10947b2d63c'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text())
assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
inputs=json.loads(safe(HERE/'compile-04/inputs.json').read_text())
assert inputs['exit']==0 and len(inputs['sources'])==5
for row in inputs['sources']:assert sha(row['path'])==row['sha256Bytes']
proof=json.loads(safe(HERE/'proof-02/source-binding.json').read_text())
assert proof['compileExit']==0 and proof['runExit']==0 and proof['productOverrideCount']==2
assert proof['fixtureSha']==sha(HERE/'fixtures/HomeRetirementFixture.kt')
assert proof['candidateSha']==sha(HERE/'compile-04/candidate.jar')
assert 'PASS shutdown cancels unbound startup' in safe(HERE/'proof-02/run.log').read_text()
checks={'actual42ManifestSha':sha(snap/'manifest.json'),'cpSha':sha(snap/'ordered-runtime-cp.json'),'cpCount':97,'allDependencyHashesMatched':True,'finalSourceHashesMatched':True,'finalFixtureMatched':True,'manualInstallFiles':4,'newOriginalRows':0,'newDependency':0,'compiledInputs':5,'actualOriginalFamilyReused':True,'declaredProductOverrideFamilies':['DesktopPluginRuntime','DesktopTodayWatchRepository'],'RootMainMounted':False,'GUIWindow':False,'nativeLeaseOrHTTP':False,'accountMutation':False,'package':False}
safe(HERE/'verification.json').write_text(json.dumps(checks,indent=2)+'\n',encoding='utf-8')
excluded=[]
for d in ['compile-01','compile-02','compile-03','compile-04','proof-01','proof-02']:
 for f in (HERE/d).rglob('*'):
  if safe(f).is_file() and f.suffix in ['.class','.jar','.kotlin_module']:
   excluded.append({'path':f.relative_to(HERE).as_posix(),'sha256Bytes':sha(f),'reason':'task compiled evidence; never install/commit'})
safe(HERE/'excluded-runtime-artifacts.json').write_text(json.dumps(excluded,indent=2)+'\n',encoding='utf-8')
rows=[]
for f in HERE.rglob('*'):
 if safe(f).is_file() and f.name!='frozen-handoff.json' and f.suffix not in ['.class','.jar','.kotlin_module']:
  rows.append({'path':f.relative_to(HERE).as_posix(),'sha256Bytes':sha(f)})
rows.sort(key=lambda r:r['path'])
manifest={'stableCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','scope':checks,'artifactCount':len(rows),'artifacts':rows}
safe(HERE/'frozen-handoff.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print('Frozen',len(rows),'raw artifacts',sha(HERE/'frozen-handoff.json'))
