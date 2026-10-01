from pathlib import Path
import hashlib, json, os
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
accepted=json.loads(read(HERE/'runs/02/accepted-evidence.json'))
assert accepted['passed'] and accepted['cases']==3 and accepted['assertions']==16 and accepted['transportCalls']==0
assert len(accepted['existingMainProductOverrides'])==188
retention=json.loads(read(HERE/'full-editor-ui-retention-proof.json'))
assert retention['passed'] and retention['fullOriginalBodyReconstitutedLfByteEqualForAllFourUiFiles']
assert len(retention['sources'])==4 and all(r['byteEqual'] for r in retention['sources'])
source_states=[]
for row in inventory['sourceCandidates']:
    assert sha(HERE/'prepared'/row['path'])==row['candidateLfSha256']
    current=hashlib.sha256(read(REPO/row['path']).encode()).hexdigest()
    assert current in {row['baseLfSha256'],row['candidateLfSha256']},row['path']
    source_states.append(dict(path=row['path'],currentLfSha256=current,state='Root-installed candidate' if current==row['candidateLfSha256'] else 'baseline, prepared install payload'))
dump(HERE/'integration-boundaries.json',dict(
    scope='Sole stable full editor producer and 18-image existing adapter delta only',
    fixedStableCommit=inventory['fixedStableCommit'],sourceCandidates=inventory['sourceCandidates'],sourceStatesAtFreeze=source_states,
    requiredExistingIdentityProducerLfSha256=inventory['requiredIdentityProducerLfSha256'],
    install='Install exact 4 payloads at their existing paths; apply gradle-input-only.patch in the existing task. Retain the already installed streaming Root/Host/Operations consumer files unchanged.',
    fullOriginalUi='All four complete original stable UI file bodies reconstitute to identical LF bytes after reversing only required platform seams. Original renderer branches and full-window Material sheet remain.',
    originalUiChange='Composer has full-window sheet, 18 images, title, header save/publish, attached vote/reserve and visibility menu; other 3 UI files have unchanged per-method tokens versus alpha9.',
    originalSourceAudit='25 exact manifest LF hashes and fixed Git blobs; 471 method token/original-text records, changed method diffs and full alpha/stable source retention.',
    imageSelection='Same existing SelectedImages, WindowsPickers and GallerySelection raise the publishing capacity to 18. Comment caller capacity stays 9.',
    streaming='The previous frozen stream chain still supplies actual SessionStore owner admission, selected owner lock, live caller job, one-shot RequestBody, 15MiB cap and input close. This lane changes only selected list capacity.',
    commentImages='New stable nine-image comment UI is audited here and owned by separate dynamic_action_review producer lane; this lane does not claim its complete upload/publish path.',
    verification='Accepted run02 manual Kotlin/Compose compile uses original file names, actual Main04 92 pinned artifact bytes and frozen stream payloads; 188 explicit product overrides. Actual Main04 Store/Repository capacity/lifetime proof: 3 cases,16 assertions,0 transports.',
    rejectedEvidence='run01 compile was successful with prefixed frozen Kotlin filenames; runtime failed ClassNotFound because filename altered the JVM facade. Raw failure retained; it is not accepted runtime or actual ABI evidence.',
    sourceTestLimitation='Original stable Android Composer policy test retains removed alpha dialog/liquid string assertions. It is preserved as source, but does not define the new stable full-window UI.',
    notProven=['actual stable whole Main zero-override build','full mounted editor pointer/keyboard interaction','real chooser or HWND','Bilibili upload server acceptance','Windows EXE deployment'],
    MainWrittenByThisLane=False,sharedGradle=False,newDependencies=False,newHttpOrStore=False,credentialsSerialized=False))
files=[HERE/name for name in ['prepare.py','audit.py','runner.py','freeze.py','CapacityFixture.kt','candidate.patch','gradle-input-only.patch',
    'candidate-source-inventory.json','generated-inventory.json','method-token-original-diff.json','source-delta.json',
    'full-composer-reconstituted-original-body.kt','full-composer-retention-proof.json','full-editor-ui-retention-proof.json',
    'comment-image-boundary-audit.json','selected-editor-source-summary.json','integration-boundaries.json']]
for folder in ['prepared','original','original-alpha9','original-stable','method-diffs','generated','reconstituted-original-bodies',
               'runs/01/frozen-sources','runs/02/frozen-sources']:
    for base,dirs,names in os.walk(safe(HERE/folder)):
        dirs[:]=[d for d in dirs if d!='__pycache__'];files += [Path(base)/name for name in names if not name.endswith('.pyc')]
for run in ['01','02']:
    for p in safe(HERE/'runs'/run).iterdir():
        if p.is_file() and p.suffix in ['.args','.log','.json']:files.append(p)
files.append(HERE/'runs/02/proof/result.json')
rows=[dict(path=rel(p),sha256Bytes=sha(p),sizeBytes=safe(p).stat().st_size) for p in sorted(files,key=rel)]
assert all(not row['path'].endswith(('.jar','.dll','.dat','.lock','.x','.zip')) for row in rows)
manifest=dict(scope='Prepared complete stable editor UI producer plus 18-image adapter only',fixedStableCommit=inventory['fixedStableCommit'],
    artifactCount=len(rows),artifacts=rows,sourceCandidates=inventory['sourceCandidates'],acceptedEvidencePath='runs/02/accepted-evidence.json',
    acceptedEvidenceSha256=sha(HERE/'runs/02/accepted-evidence.json'),
    runtimeReferencesOnly=[dict(path=str(HERE/'runs/02/candidate.jar'),sha256Bytes=sha(HERE/'runs/02/candidate.jar'),excludedFromFormalGit=True)],
    MainWrittenByThisLane=False,sharedGradle=False)
dump(HERE/'evidence-manifest.json',manifest)
dump(HERE/'frozen-handoff.json',dict(lane=str(HERE),manifestSha256Bytes=sha(HERE/'evidence-manifest.json'),artifactCount=len(rows),
    sourceCandidates=inventory['sourceCandidates'],sourceStatesAtFreeze=source_states,
    acceptedEvidenceSha256=sha(HERE/'runs/02/accepted-evidence.json'),candidateJarSha256=sha(HERE/'runs/02/candidate.jar'),
    candidateProductOverrides=188,sourceFilesFixed=25,methodRecords=471,fullUiBodyByteEqualProofSha256=sha(HERE/'full-editor-ui-retention-proof.json'),
    runtimeCases=3,assertions=16,transportCalls=0,chooserCalls=0,HWND=False,gradleInputPatchSha256=sha(HERE/'gradle-input-only.patch'),
    MainWrittenByThisLane=False,sharedGradle=False,newHttpOrStore=False))
print(json.dumps(dict(manifestSha256=sha(HERE/'evidence-manifest.json'),handoffSha256=sha(HERE/'frozen-handoff.json'),artifactCount=len(rows),
    acceptedEvidenceSha256=sha(HERE/'runs/02/accepted-evidence.json'),candidateJarSha256=sha(HERE/'runs/02/candidate.jar'),
    fullUiBodyByteEqualProofSha256=sha(HERE/'full-editor-ui-retention-proof.json')),indent=2))
