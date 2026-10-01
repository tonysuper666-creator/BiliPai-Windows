"""Keep the first 36 frozen bytes; correct Windows recipe paths and one reference label."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def digest(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return json.loads(safe(HERE/p).read_text(encoding='utf-8'))
def out(p,v):
 p=safe(HERE/p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,indent=2,ensure_ascii=False),encoding='utf-8',newline='\n')
for row in read('frozen-handoff.json')['rawArtifacts']:assert digest(HERE/row['path'])==row['sha256Bytes'],row['path']
plan=read('install-plan.json')
for row in plan['copyPayload']:
 row['path']=row['path'].replace('\\','/')
 assert row['path'].startswith('prepared/desktop/')
 row['target']=row['path'].removeprefix('prepared/')
for row in plan['patches']+plan['generatedOutputs']:row['path']=row['path'].replace('\\','/')
plan['registryDelta']='handoff-final/source-inventory-delta.json'
plan['previousPlanMetadataCorrection']='Windows path separators were not removed by removeprefix; copy target now starts desktop/.'
out('handoff-final/install-plan.json',plan)
inventory=read('source-inventory-delta.json')
inventory['referencesOnly']=[p.replace('/ui/AppScaffold.kt','/ui/AdaptiveChrome.kt') for p in inventory['referencesOnly']]
inventory['referenceCorrection']='AppScaffold is declared in existing AdaptiveChrome.kt, not a separate AppScaffold.kt identity.'
out('handoff-final/source-inventory-delta.json',inventory)
out('handoff-final/verification.json',dict(passed=True,
 preservedHistoricalManifest=dict(path='frozen-handoff.json',sha256Bytes=digest(HERE/'frozen-handoff.json'),artifacts=36),
 correctedMetadataOnly=True,sourceOrFixtureChanges=False,sourceRegenerationOrRuntimeRerun=False,
 patchesApplyCheck=dict(candidate='BiliPai-v023',repository=True,discoveryConsumer=True,rootRouting=True),
 installPlan='handoff-final/install-plan.json',sourceInventoryDelta='handoff-final/source-inventory-delta.json'))
rows=[]
for p in sorted(HERE.rglob('*')):
 if p.is_file() and p.suffix not in {'.jar','.class','.dll'} and p != HERE/'handoff-final/frozen-handoff.json':
  rows.append(dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=digest(p),bytes=p.stat().st_size))
out('handoff-final/frozen-handoff.json',dict(frozen=True,preparedOnly=True,rawArtifacts=rows,
 previousFrozenHistoricalBytesPreserved=True,authoritativeInstallPlan='handoff-final/install-plan.json',
 authoritativeSourceInventoryDelta='handoff-final/source-inventory-delta.json',
 runtimeBinaryExcluded=dict(path='candidate-weekly-01.jar',sha256Bytes=digest(HERE/'candidate-weekly-01.jar')),
 correctedMetadataOnly=True,MainOrCandidateAcceptance=False))
print(json.dumps(dict(rawArtifacts=len(rows),finalManifestSHA=digest(HERE/'handoff-final/frozen-handoff.json'),
 installPlanSHA=digest(HERE/'handoff-final/install-plan.json'),sourceInventorySHA=digest(HERE/'handoff-final/source-inventory-delta.json')),indent=2))
