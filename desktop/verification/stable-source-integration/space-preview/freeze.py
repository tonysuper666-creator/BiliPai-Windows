"""Freeze raw task evidence; compiler JARs are references, never an install payload."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,obj):safe(p).write_text(json.dumps(obj,indent=2,ensure_ascii=False),encoding='utf-8',newline='\n')
assert not safe(HERE/'frozen-handoff.json').exists()
result=json.loads(safe(HERE/'compile-03/compile-result.json').read_text(encoding='utf-8'))
assert result['status']=='PASS' and result['mainAcceptance']==False
assert result['candidateProductClassOverrideCount']==25
audit=json.loads(safe(HERE/'source-audit.json').read_text(encoding='utf-8'))
assert audit['status']=='PASS' and audit['sourceContractCheckCount']==22
externalRefs=[
 ('historical stable caller',HERE.parent/'space-avatar-save-v023-parity/frozen-handoff.json','424ef334084c32f067bcfa0df976fc22ee5ea500e7894a21a261391a61f8ee0d'),
 ('sole stable renderer',HERE.parent/'stable-image-preview-producer-parity/evidence-manifest.json','aa2f0fded76142156e7470d40d999939e78fe9ea3a86300353ab8e1e805a92d9'),
 ('source dependency review',HERE.parent/'space-header-v023-dependency-review/evidence-manifest.json','dff7f9f62ae58ee56676c4db191466a332090c480f6ccd63a7f920f0b6833ad0'),
 ('actual Main04',HERE.parent/'image-save-main-integration/main-product-snapshot-04/manifest.json','7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865')]
for label,path,pin in externalRefs:assert sha(path)==pin,(label,path)
rows=[];exclusions=[]
for path in sorted(safe(HERE).rglob('*')):
 if not path.is_file():continue
 relative=str(path.relative_to(safe(HERE))).replace('\\','/')
 if '__pycache__' in relative or path.suffix in ['.pyc','.class']:continue
 row=dict(path=relative,sha256Bytes=sha(path),sizeBytes=path.stat().st_size)
 if path.suffix in ['.jar','.dll']:
  row['reason']='Task compiled candidate/runtime binary; evidence reference only, never install or commit.';exclusions.append(row)
 else:rows.append(row)
write(HERE/'excluded-runtime-artifacts.json',exclusions)
rows.append(dict(path='excluded-runtime-artifacts.json',sha256Bytes=sha(HERE/'excluded-runtime-artifacts.json'),sizeBytes=safe(HERE/'excluded-runtime-artifacts.json').stat().st_size))
write(HERE/'frozen-handoff.json',dict(schema='stable-space-complete-preview-consumer-prepared-v1',
 preparedOnly=True,actualMainRuntimeAccepted=False,originalTag='v0.2.3',
 originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',artifactCount=len(rows),artifacts=rows,
 originalSelectedBodyCount=4,compiledSourceCount=5,candidateProductClassOverrideCount=25,
 previewCandidateDependencySourceCount=6,previewCandidateDependencyProductClassOverrideCount=42,
 sourceChecks=22,sourceOnlyPayloadCount=5,consumerPatchAppliesToMain04=True,
 preservedHistoricalReferences=[dict(kind=l,path=str(p),sha256Bytes=h) for l,p,h in externalRefs],
 scope=dict(MainWrites=False,sharedGradle=False,HTTP=False,accountWrites=False,HWND=False,
  userDirectoryWrites=False,systemChooser=False,stableMountedUI=False,EXE=False),
 integration='ROOT-INTEGRATION.md',inventory='source-inventory.json'))
for row in rows:assert sha(HERE/row['path'])==row['sha256Bytes']
print('FROZEN',len(rows),'raw artifacts;',sha(HERE/'frozen-handoff.json'))
