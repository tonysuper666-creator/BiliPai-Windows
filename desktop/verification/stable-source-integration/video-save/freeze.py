from pathlib import Path
import hashlib, json, os
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def dump(p,v):safe(p).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
inventory=json.loads(safe(HERE/'source-inventory.json').read_text())
accepted=json.loads(safe(HERE/'runs/03/accepted-evidence.json').read_text())
assert accepted['passed'] and accepted['assertions']==45 and accepted['requests']==3
for row in inventory['candidates']:
 assert sha(row['candidate'])==row['candidateLfSha256']
 assert hashlib.sha256((REPO/row['path']).read_text(encoding='utf-8').encode()).hexdigest()==row['baseLfSha256']
dump(HERE/'attempt-history.json',{
 'attempt01':{'status':'fixture compile failure','cause':'Ambiguous java.util.concurrent and kotlinx.coroutines CancellationException imports','productChanged':False,
 'correction':'Explicit kotlinx.coroutines.CancellationException fixture import'},
 'attempt02':{'status':'candidate compile and 12 fake ABI pass; real native failed before path proof',
 'cause':'Task USERPROFILE isolation influenced real Shell folder expansion; HRESULT 0x80070002',
 'productChanged':False,'correction':'Preserve actual Windows account native environment; isolate only JNA and Java temp, flags0 no CREATE'},
 'attempt03':{'status':'accepted','candidateCompiled':True,'declaredProductClassOverrides':42,'fakeAbiCases':12,
 'actualReadOnlyNativeCases':4,'isolatedRuntimeCases':5,'assertions':45,'anonymousLoopbackGetCount':3,
 'MainIntegrated':False,'oldMain04MatricesRerun':False},
})
dump(HERE/'integration-boundaries.json',{
 'baseCommit':inventory['baseCommit'],'stableOriginalCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 'stableScope':'standalone saveLivePhotoVideoToGallery helper only; exact LF equality to alpha.9',
 'installation':'Root must review and apply exact three candidates, then compile actual Main and pin a new immutable snapshot',
 'ownedCommitOrder':['actual SessionStore current-owner monitor','existing Assets close lock','same Root global application/window save lifetime','current save-job ensureActive + no-clobber Files move'],
 'preferences':'No new key/store/second prefs, no image-tree read for standalone video, no MID/epoch preference binding',
 'existingPictures':'Same ctor last trailing Pictures resolver and same save API; only one actual Root construction, which supplies first three args',
 'nativeKnownFolder':'Existing JNA core only, FOLDERID_Videos not VideosLibrary, flags0 NULL token, COM ref and output memory ownership retained',
 'failure':'Native failure remains failure; no USERPROFILE path guess, Pictures/custom-tree fallback or chooser in Root default-video path',
 'saveAs':'Null imageSaveLocations legacy explicit chooser seam retained; not exercised as native UI',
 'streaming':'Existing raw writer and anonymous HTTP unchanged; 64KiB bounded copy, 200MiB limit, callback drain before cleanup',
 'windowsDifference':'Uses redirected Videos/BiliPai and collision-only suffix; Windows Photos indexing and Android MediaStore permission behavior not claimed',
 'notProven':['Main installation/zero product overrides','whole Root mounted flow','actual Windows SaveAs/chooser/HWND','new desktop EXE/package','system Photos indexing','all v0.2.3 functions'],
 'network':'Three isolated 127.0.0.1 GETs only, external DNS/hosts rejected; no external network',
 'writes':'All save paths/settings fixtures and native extraction within this new .local lane; real KnownFolder queries read-only',
})
root_names=['prepare.py','runner.py','freeze.py','VideoDefaultFixture.kt','VideoKnownFolderFixture.kt','source-inventory.json',
 'stable-scope-comparison.json','windows-sdk-videos-evidence.json','candidate.patch','attempt-history.json','integration-boundaries.json']
files=[HERE/name for name in root_names]
for directory in ['original','prepared']:
 for base,dirs,names in os.walk(safe(HERE/directory)):
  files += [Path(base)/name for name in names]
for attempt in ['01','02','03']:
 run=HERE/'runs'/attempt
 for p in safe(run).iterdir():
  if p.is_file() and p.suffix in ['.json','.log','.args']:files.append(p)
 frozen=run/'frozen-sources'
 if safe(frozen).exists():files += list(safe(frozen).iterdir())
proof=HERE/'runs/03/proof'
files.append(proof/'result.json')
files.append(proof/'input.mp4')
files+=list(safe(proof/'videos-base'/'BiliPai').iterdir())
def rel(p):
 value=str(p)
 if value.startswith(EXT):value=value[len(EXT):]
 return Path(value).relative_to(HERE).as_posix()
files=sorted(set(files),key=rel)
rows=[{'path':rel(p),'sha256Bytes':sha(p),'sizeBytes':safe(p).stat().st_size} for p in files]
assert all(not r['path'].endswith(('.jar','.dll','.dat','.lock','.x','.zip')) for r in rows)
references=[]
for attempt in ['02','03']:
 p=HERE/'runs'/attempt/'candidate.jar'
 references.append({'path':str(p),'sha256Bytes':sha(p),'excludedFromFormalGit':True})
snapshot=REPO/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
manifest={'scope':'prepared standalone default-video candidate, NOT Main installed','baseCommit':inventory['baseCommit'],
 'sourceCandidates':inventory['candidates'],'Main04ManifestSha256':sha(snapshot/'manifest.json'),
 'Main04Ordered92ClasspathSha256':sha(snapshot/'ordered-runtime-cp.json'),
 'acceptedEvidencePath':'runs/03/accepted-evidence.json','acceptedEvidenceSha256':sha(HERE/'runs/03/accepted-evidence.json'),
 'artifacts':rows,'runtimeReferencesOnly':references,'allRawArtifactsWithinLane':True,'newDependencies':False,
 'MainIntegrated':False,'sharedGradle':False,'HWND':False}
dump(HERE/'evidence-manifest.json',manifest)
dump(HERE/'frozen-handoff.json',{'lane':str(HERE),'manifestSha256Bytes':sha(HERE/'evidence-manifest.json'),
 'artifactCount':len(rows),'sourceCandidates':inventory['candidates'],'acceptedEvidenceSha256':sha(HERE/'runs/03/accepted-evidence.json'),
 'candidateJarSha256':sha(HERE/'runs/03/candidate.jar'),'candidateClassOverrides':42,
 'runtimeCases':5,'assertions':45,'anonymousLoopbackGetCount':3,'fakeAbiCases':12,'actualReadOnlyNativeCases':4,
 'stableHelperLfSha256':'3071039726dcf9070df9a7cb59f198fce2f6c02a4844dc449c0b968a840c449e',
 'MainIntegrated':False,'MainFilesStillMatchBaseline':True,'sharedGradle':False,'allRawSha256Verified':True})
print(json.dumps({'manifestSha256':sha(HERE/'evidence-manifest.json'),'handoffSha256':sha(HERE/'frozen-handoff.json'),
 'acceptedEvidenceSha256':sha(HERE/'runs/03/accepted-evidence.json'),'candidateJarSha256':sha(HERE/'runs/03/candidate.jar'),
 'artifacts':len(rows)},indent=2))
