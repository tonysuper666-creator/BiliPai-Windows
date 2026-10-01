from pathlib import Path
import json,hashlib,zipfile,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8')
snap=MAIN/'desktop/.local/stable-product-snapshot-26'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sourceRows=json.loads(safe(HERE/'compile-ports-05-sources.json').read_text(encoding='utf-8'))
for row in sourceRows:assert sha(row['path'])==row['sha256Bytes']
protocolClasses=[]
for source in (HERE/'classes-ports-04/com/android/purebilibili').rglob('*.class'):
 relative=source.relative_to(HERE/'classes-ports-04');new=HERE/'classes-ports-05'/relative
 assert sha(source)==sha(new),str(relative)
 protocolClasses.append({'path':relative.as_posix(),'sha256Bytes':sha(source),'fourToFiveByteIdentical':True})
write(HERE/'protocol-class-continuity.json',{'protocol05FixtureUsesPorts04':True,'finalPorts05OriginalProtocolBytesIdentical':protocolClasses,'otherRootAdapterAndTransportChangesNotPartOfProtocol05Fixture':True})
with zipfile.ZipFile(safe(snap/'main-kotlin.jar')) as z:baseClasses=set(z.namelist())
overlap=[p.relative_to(HERE/'classes-ports-05').as_posix() for p in (HERE/'classes-ports-05').rglob('*.class') if p.relative_to(HERE/'classes-ports-05').as_posix() in baseClasses]
assert all(path.startswith('com/bilipai/desktop/data/DesktopRepository') or path.startswith('com/bilipai/desktop/data/DesktopSessionStore') or path.startswith('com/bilipai/desktop/data/DesktopSessionEpoch') for path in overlap),overlap
write(HERE/'class-owner-audit.json',{'actualProductClassOverlap':overlap,'intentionalSharedFileDeltaOverridesOnly':True,'newAccountModel':False,'newClientOrCookieJar':False})
patchProof=[]
for file in ['shared-http-owner-admission.patch','home-preview-subject.patch']:
 r=subprocess.run(['git','-C',str(REPO),'apply','--check','--ignore-space-change',str(HERE/file)],capture_output=True,text=True)
 assert r.returncode==0,(file,r.stderr)
 patchProof.append({'patch':file,'sha256Bytes':sha(HERE/file),'checkOnlyExit':r.returncode,'output':r.stdout+r.stderr})
write(HERE/'patch-check.json',{'candidate':str(REPO),'checkOnlyNoSharedWrite':True,'rows':patchProof})
payload=[]
files=[HERE/'prepared/tools/extract-upstream-home-protocols.py']+list((HERE/'prepared/manual').rglob('*.kt'))
for file in files:
 relative=file.relative_to(HERE).as_posix()
 target='desktop/tools/'+file.name if file.suffix=='.py' else 'desktop/src/main/kotlin/'+file.relative_to(HERE/'prepared/manual').as_posix()
 payload.append({'source':relative,'target':target,'sha256Bytes':sha(file)})
assert len(payload)==5
write(HERE/'install-whitelist.json',{'newSourcePayload':payload,'applyNarrowPatches':['shared-http-owner-admission.patch','home-preview-subject.patch'],'mergeSourceInventory':'source-inventory.json','gradleSnippetOnly':'gradle-tasks.snippet.kts','neverInstall':['prepared/generated','prepared/transport-delta','prepared/preview-delta','prepared/sole-producer-delta','classes-*','task-store','producer-replay'],'productDependenciesAdded':0})
write(HERE/'history-boundary.json',{'compileHistoriesPreserved':True,'mutablePreparationInputsInOldPerRunReceipts':True,'oldPerRunInputLocationsNotClaimedCurrentlyHashMatching':True,'finalCompile05InputRowsCurrentlyVerified':True,'protocolFixtureHistoryCorrections':['DynamicRegionItem original response type','original merged ordering App first','original tid202 ranking rid1009','original legacy fnver/fourk arguments precede platform/highQuality'],'algorithmsChangedToPassFixture':False,'initialGeneratorReplayFailure':'missing PREFIX constant in new script, fixed before exact 6/7 replay','frozenUi524Vm287Unchanged':True,'vm287MetadataCorrection':'MERGED two concurrent Web/App requests, four raw helper functions across modes'})
excluded=[];artifacts=[]
for file in sorted(HERE.rglob('*')):
 if not file.is_file() or file.name=='frozen-handoff.json':continue
 rel=file.relative_to(HERE).as_posix()
 if '__pycache__' in file.parts or 'task-store' in file.parts:
  excluded.append({'path':rel,'reason':'task-only temporary fake-credential/cache/store or Python runtime cache; not handoff artifact'});continue
 artifacts.append({'path':rel,'sha256Bytes':sha(file),'size':safe(file).stat().st_size})
manifest={'targetCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','productBase':str(snap/'manifest.json'),'productBaseSha':sha(snap/'manifest.json'),'orderedEntries':len(cp),'artifacts':artifacts,'artifactCount':len(artifacts),'exclusions':excluded,'sourceOnlyInstallation':True,'proof':{'preparedCompileSources':13,'nativePreviewKeyCallerCompile':True,'transportLoopbackAndStoreGates':7,'proxyOriginalProtocolGates':6,'actualLocalDiagnosticsGates':4,'actualMainAcceptance':False,'realAccountHttpOrTls':False,'nativeWindowOrMountedHome':False,'executablePackaged':False,'firebaseTransport':False},'whitelist':'install-whitelist.json'}
write(HERE/'frozen-handoff.json',manifest)
print(json.dumps({'manifest':str(HERE/'frozen-handoff.json'),'sha256Bytes':sha(HERE/'frozen-handoff.json'),'artifactCount':len(artifacts),'installNewSources':len(payload),'patchCount':len(patchProof)}))
