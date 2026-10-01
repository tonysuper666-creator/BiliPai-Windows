from pathlib import Path
import hashlib,json
H=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def write(p,b):wide(p).write_bytes(b.encode())
def dump(p,v):write(p,json.dumps(v,indent=2,ensure_ascii=False)+'\n')
def row(p):return dict(path=Path(p).relative_to(wide(H)).as_posix(),sha256Bytes=sha(p),size=len(data(p)))
assert not wide(H/'frozen-handoff.json').exists()
assert json.loads(data(H/'runs/native-03/runtime-result.json'))['exit']==0
assert json.loads(data(H/'runs/native-02/runtime-result.json'))['exit']==1
assert json.loads(data(H/'runs/native-01/runtime-result.json'))['exit']==1
assert json.loads(data(H/'source-audit.json'))['count']==47
raw=[];excluded=[]
for p in sorted(wide(H).rglob('*')):
 if not p.is_file():continue
 r=row(p);parts=Path(r['path']).parts
 if p.name in ['frozen-handoff.json','raw-manifest.json','excluded-artifacts.json']:continue
 reason=None
 if 'owned-data'in parts:reason='fixture-owned synthetic session/plugin/cache data; never installed'
 elif 'classes'in parts or '__pycache__'in parts or p.suffix in ['.class','.jar','.pyc','.kotlin_module']:reason='rebuildable compiler output; raw inputs and logs retained'
 if reason:excluded.append(dict(**r,reason=reason))
 else:raw.append(r)
dump(H/'raw-manifest.json',raw);dump(H/'excluded-artifacts.json',excluded)
payload=H/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopNativeByteFailure.kt'
f=dict(schemaVersion=1,packet='native-byte-failure-recovery',rawCount=len(raw),rawManifest=dict(path='raw-manifest.json',sha256Bytes=sha(H/'raw-manifest.json'),size=len(data(H/'raw-manifest.json'))),excludedManifest=dict(path='excluded-artifacts.json',sha256Bytes=sha(H/'excluded-artifacts.json'),size=len(data(H/'excluded-artifacts.json'))),copyWhitelist=[row(wide(payload))],exactHunks=dict(path='exact-hunks.json',sha256Bytes=sha(H/'exact-hunks.json'),size=len(data(H/'exact-hunks.json'))),baselineFamilies=dict(path='baseline-families.json',sha256Bytes=sha(H/'baseline-families.json'),size=len(data(H/'baseline-families.json'))),sourceAudit=dict(path='source-audit.json',sha256Bytes=sha(H/'source-audit.json'),size=len(data(H/'source-audit.json'))),integration=dict(path='ROOT-INTEGRATION.md',sha256Bytes=sha(H/'ROOT-INTEGRATION.md'),size=len(data(H/'ROOT-INTEGRATION.md'))),snapshot=73,entries=101,declaredProductionFamilies=3,newManual=1,hunks=12,sourceChecks=47,native=dict(run='native-03',groups=3,assertions=19,PASS=True,real503=True,actualBridge=True,realDirectAck=True),closedFailures=['compile-01','native-01','native-02'],originalNativeFailureHelperUnchanged=True,originalRegistryDelta=0,dependencyDelta=0,CandidateWritten=False,installedProductAccepted=False,MainShellAccepted=False,wholeRootVMAccepted=False,windowAccepted=False,HTTPExternal=False,OSInput=False)
dump(H/'frozen-handoff.json',f)
print(json.dumps(dict(path=str(H/'frozen-handoff.json'),sha256Bytes=sha(H/'frozen-handoff.json'),rawCount=len(raw),excludedCount=len(excluded),payloadSha256=sha(payload),hunksSha256=sha(H/'exact-hunks.json'),baselinesSha256=sha(H/'baseline-families.json'))))
