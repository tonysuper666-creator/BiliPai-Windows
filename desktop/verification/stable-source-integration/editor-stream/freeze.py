from pathlib import Path
import hashlib, json, os, subprocess
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
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
assert accepted['passed'] and accepted['cases']==6 and accepted['assertions']==29
for row in inventory['sourceCandidates']:
 assert sha(HERE/'prepared'/row['path'])==row['candidateLfSha256']
 assert hashlib.sha256(read(REPO/row['path']).encode()).hexdigest()==row['baseLfSha256'],row['path']
alpha='fcf84853b287662e8a9129ea0d38576c36522a34';stable='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
model='app/src/main/java/com/android/purebilibili/data/model/response/DynamicCreateModels.kt'
before=subprocess.check_output(['git','-c','core.longpaths=true','show',alpha+':'+model],cwd=REPO)
after=subprocess.check_output(['git','-c','core.longpaths=true','show',stable+':'+model],cwd=REPO)
assert before==after
delta=json.loads(read(REPO/'desktop/.local/stable-dynamic-protocol-rebase/method-token-original-diff.json'))
api=[r for r in delta['methods'] if r['path'].endswith('/ApiClient.kt') and r['method'] in ['uploadCommentImage#1','createFeedDynamic#1','editFeedDynamic#1']]
assert len(api)==3 and all(r['status']=='unchanged' for r in api)
dump(HERE/'used-contract-stable-comparison.json',{'fixedStableCommit':stable,'modelPath':model,'fullModelGitBytesAlpha9EqualStable':True,
 'modelGitSha256':hashlib.sha256(after).hexdigest(),'usedApiMethods':api,'scope':'used dynamic create/edit/upload contracts only; unrelated stable model/API additions remain Root responsibility'})
dump(HERE/'integration-boundaries.json',{'scope':'prepared complete dynamic editor streaming chain, not actual stable Main/HWND/EXE',
 'candidateFiles':inventory['sourceCandidates'],'requiresIdentityProducerLfSha256':inventory['identityProducerLfSha256'],
 'installOrder':'Keep the already prepared stable detail/reply identity producer; install editor producer plus 4 Kotlin consumers, unchanged editor verifier, and the one task-input patch',
 'soleProducer':'stable uploadCommentImagePart-derived multipart algorithm once; original byte overload and editor streaming input share it',
 'sourceInputs':'stable fixed Git commit + manifest LF SHA + exact Git blob, with selected original helper/method-token source map',
 'completeConsumers':'Root supplies same forEditor Operations actual SessionStore gate; selected::read returns owned RequestBody; Host and Operations provider types agree',
 'ownership':'current actual SessionStore owner admission, selected owner lock and active caller publishing/editing context; no new API/client/store or serialized credentials',
 'stream':'15MiB known-size/empty preflight before image input, one-shot body, no entire-image buffer, 64KiB slice, input closed on success/failure/job cancellation/selected close/same-MID replacement',
 'lockBoundary':'A bounded input read or sink.write slice runs inside Store -> selected lock; the entire image is never one held transaction. Actual network backpressure/HWND responsiveness not measured by terminal fixture',
 'platform':'Windows exact selected Paths have a known filesystem size; Android unknown-size DocumentsProvider fallback is not a Windows input interface',
 'retainedByteApi':'original public reply ByteArray overload remains; dynamic editor no longer uses it. This slice does not claim full reply-picture composer migration',
 'runtimeProof':'6 cases/29 assertions, actual Main04 SessionStore/Repository, 139 explicitly declared candidate product class overrides, 1 declared terminal upload + 1 publish; no sockets/DNS/real Bilibili request',
 'notProven':['actual stable Main installation with zero class overrides','full original composer mounted interaction','real chooser/HWND','Windows EXE packaging','actual Bilibili upload acceptance'],
 'MainChanged':False,'sharedGradle':False,'newDependencies':False})
root_files=['DesktopDynamicEditorSelectedImages.kt','EditorStreamFixture.kt','prepare.py','runner.py','prove-sole-producers.py','freeze.py',
 'stream-upload.patch','gradle-input-only.patch','stable-upload-source-map.json','candidate-source-inventory.json',
 'editor-sole-producer-proof.json','retained-detail-reply-proof.json','editor-verify.log','detail-reply-verify.log','consumer-chain-review.json',
 'used-contract-stable-comparison.json','integration-boundaries.json']
files=[HERE/n for n in root_files]
for folder in ['prepared','original','generated','runs/01/frozen-sources']:
 for base,dirs,names in os.walk(safe(HERE/folder)):
  dirs[:]=[d for d in dirs if d!='__pycache__'];files += [Path(base)/n for n in names if not n.endswith('.pyc')]
for p in safe(HERE/'runs/01').iterdir():
 if p.is_file() and p.suffix in ['.args','.log','.json']:files.append(p)
files.append(HERE/'runs/01/proof/result.json')
rows=[{'path':rel(p),'sha256Bytes':sha(p),'sizeBytes':safe(p).stat().st_size} for p in sorted(files,key=rel)]
assert all(not r['path'].endswith(('.jar','.dll','.dat','.lock','.x','.zip')) for r in rows)
manifest={'scope':'prepared full editor streaming chain only','fixedStableCommit':stable,'artifacts':rows,'artifactCount':len(rows),
 'runtimeReferencesOnly':[{'path':str(HERE/'runs/01/candidate.jar'),'sha256Bytes':sha(HERE/'runs/01/candidate.jar'),'excludedFromFormalGit':True}],
 'acceptedEvidencePath':'runs/01/accepted-evidence.json','acceptedEvidenceSha256':sha(HERE/'runs/01/accepted-evidence.json'),
 'sourceCandidates':inventory['sourceCandidates'],'MainChanged':False,'sharedGradle':False}
dump(HERE/'evidence-manifest.json',manifest)
dump(HERE/'frozen-handoff.json',{'lane':str(HERE),'manifestSha256Bytes':sha(HERE/'evidence-manifest.json'),'artifactCount':len(rows),
 'sourceCandidates':inventory['sourceCandidates'],'acceptedEvidenceSha256':sha(HERE/'runs/01/accepted-evidence.json'),
 'candidateJarSha256':sha(HERE/'runs/01/candidate.jar'),'candidateProductOverrides':139,'runtimeCases':6,'assertions':29,
 'editorSoleProducerVerifierPassed':True,'detailReplyProducerVerifierPassed':True,'sourceFilesRemainAtBaseline':True,
 'gradleInputPatchSha256':sha(HERE/'gradle-input-only.patch'),'MainChanged':False,'sharedGradle':False,'credentialsSerialized':False,
 'sourceSocketCount':0,'terminalDeclaredRequests':2})
print(json.dumps({'manifestSha256':sha(HERE/'evidence-manifest.json'),'handoffSha256':sha(HERE/'frozen-handoff.json'),
 'acceptedEvidenceSha256':sha(HERE/'runs/01/accepted-evidence.json'),'candidateJarSha256':sha(HERE/'runs/01/candidate.jar'),'artifactCount':len(rows)},indent=2))
