from pathlib import Path
import hashlib,json,os
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
    v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def dump(p,v):safe(p).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
def rel(p):
    v=str(p);v=v[len(EXT):] if v.startswith(EXT) else v
    return Path(v).relative_to(HERE).as_posix()
inventory=json.loads(read(HERE/'candidate-source-inventory.json'))
accepted=json.loads(read(HERE/'runs/01/accepted-evidence.json'))
assert accepted['passed'] and accepted['cases']==3 and accepted['assertions']==12 and accepted['networkCalls']==0
assert len(accepted['existingMainProductOverrides'])==25
retention=json.loads(read(HERE/'source-retention-proof.json'));assert retention['passed']
states=[]
for row in inventory['sourceCandidates']:
    assert sha(HERE/'prepared'/row['path'])==row['candidateLfSha256']
    current=hashlib.sha256(read(REPO/row['path']).encode()).hexdigest()
    assert current in {row['baseLfSha256'],row['candidateLfSha256']},row['path']
    states.append(dict(path=row['path'],currentLfSha256=current,state='Root-installed candidate' if current==row['candidateLfSha256'] else 'baseline, prepared install payload'))
dump(HERE/'integration-boundaries.json',dict(scope='Three compile07 sole-producer repairs, prepared only',fixedStableCommit=inventory['fixedStableCommit'],
    sourceCandidates=inventory['sourceCandidates'],sourceStatesAtFreeze=states,registryAppendRows=inventory['registryAppendRows'],
    install='Append the two new fixed source rows to the current manifest without replacing other Root changes. Install exact three existing producer payloads and diagnostics task-input patch.',
    diagnostics='Preserve current fixed Logger entire pure region and its nativeTrace branch; original pure JVM exception class selected from Android17Diagnostics and full pure NativeExitTrace file supplied once by this diagnostics producer. No Android profiling hooks or system-exit capture is added.',
    sourceGate='Diagnostics six source inputs match the current manifest LF hashes and exact fixed stable Git blobs; the .local source-shadow adds only two required registry rows for preparing this consumer.',
    tabs='One explicit AppIconButton original-name import resolves alias suppression; FeedDynamicTabVisibilityItem complete original body remains exact.',
    plugins='Only SponsorBlockRepository two android.os.SystemClock elapsed calls change to existing DesktopMonotonicClock; existing public client, complete TTL/cache generation/limit/endpoint logic retained.',
    compile='Six selected generated source targets compiled with existing Compose and serialization plugins against immutable actual Main04 92 pinned artifacts. 25 product class overrides are explicit; this is not whole stable Main verification.',
    runtime='Three data semantics/actual existing clock cases, twelve assertions; original trace roundtrip/cap, crash snapshot raw-payload exclusion, normal throwable branch and actual Main04 clock binding. No network, chooser, GUI or native process-exit trigger.',
    MainWrittenByThisLane=False,sharedGradle=False,newDependencies=False,newHttpOrStore=False,credentialsSerialized=False,
    notProven=['whole current stable Main build','Windows system process exit capture or native .pb generation','SponsorBlock real server acceptance','HWND/editor interaction','EXE packaging']))
root_names=['prepare.py','audit.py','runner.py','freeze.py','RepairFixture.kt','candidate.patch','gradle-input-only.patch',
    'candidate-source-inventory.json','registry-append-rows.json','new-helper-source-identity.json','generated-targets.json','source-retention-proof.json',
    'selected-original-body-Logger.txt','selected-original-body-AbnormalException.kt','selected-original-body-SponsorLoadSegments.kt',
    'selected-original-body-TabsVisibilityItem.kt','integration-boundaries.json']
files=[HERE/name for name in root_names]
for folder in ['prepared','original-tools','original-stable','original-alpha9','source-diffs','generated','runs/01/frozen-sources']:
    for base,dirs,names in os.walk(safe(HERE/folder)):
        dirs[:]=[d for d in dirs if d!='__pycache__'];files += [Path(base)/name for name in names if not name.endswith('.pyc')]
for p in safe(HERE/'runs/01').iterdir():
    if p.is_file() and p.suffix in ['.args','.log','.json']:files.append(p)
files += [HERE/'runs/01/proof/result.json',HERE/'source-shadow/desktop/upstream-sources.json']
rows=[dict(path=rel(p),sha256Bytes=sha(p),sizeBytes=safe(p).stat().st_size) for p in sorted(files,key=rel)]
assert all(not r['path'].endswith(('.jar','.dll','.dat','.lock','.x','.zip')) for r in rows)
manifest=dict(scope='Three stable compile07 sole producer repairs only',fixedStableCommit=inventory['fixedStableCommit'],artifacts=rows,artifactCount=len(rows),
    sourceCandidates=inventory['sourceCandidates'],registryAppendRows=inventory['registryAppendRows'],acceptedEvidencePath='runs/01/accepted-evidence.json',
    acceptedEvidenceSha256=sha(HERE/'runs/01/accepted-evidence.json'),
    runtimeReferencesOnly=[dict(path=str(HERE/'runs/01/candidate.jar'),sha256Bytes=sha(HERE/'runs/01/candidate.jar'),excludedFromFormalGit=True)],
    MainWrittenByThisLane=False,sharedGradle=False)
dump(HERE/'evidence-manifest.json',manifest)
dump(HERE/'frozen-handoff.json',dict(lane=str(HERE),manifestSha256Bytes=sha(HERE/'evidence-manifest.json'),artifactCount=len(rows),
    sourceCandidates=inventory['sourceCandidates'],sourceStatesAtFreeze=states,registryAppendRows=inventory['registryAppendRows'],
    acceptedEvidenceSha256=sha(HERE/'runs/01/accepted-evidence.json'),candidateJarSha256=sha(HERE/'runs/01/candidate.jar'),
    candidateProductOverrides=25,runtimeCases=3,assertions=12,networkCalls=0,sourceRetentionProofSha256=sha(HERE/'source-retention-proof.json'),
    gradleInputPatchSha256=sha(HERE/'gradle-input-only.patch'),MainWrittenByThisLane=False,sharedGradle=False,newHttpOrStore=False))
print(json.dumps(dict(manifestSha256=sha(HERE/'evidence-manifest.json'),handoffSha256=sha(HERE/'frozen-handoff.json'),artifactCount=len(rows),
    acceptedEvidenceSha256=sha(HERE/'runs/01/accepted-evidence.json'),candidateJarSha256=sha(HERE/'runs/01/candidate.jar'),
    sourceRetentionProofSha256=sha(HERE/'source-retention-proof.json')),indent=2))
