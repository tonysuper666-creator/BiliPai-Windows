from pathlib import Path
import hashlib,json,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snapshot=MAIN/'desktop/.local/stable-product-snapshot-39'
assert sha(snapshot/'manifest.json')=='4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae'
assert sha(snapshot/'ordered-runtime-cp.json')=='e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
compiled=json.loads(safe(HERE/'compile-02/inputs.json').read_text())
assert compiled['compileExit']==0 and compiled['classOverlap']==[] and compiled['preparedOverrides']==[]
for row in compiled['sources']:assert sha(row['path'])==row['sha256Bytes']
proof=json.loads(safe(HERE/'proof-01/inputs.json').read_text())
assert proof['compileExit']==0 and proof['runExit']==0 and proof['preparedOverrides']==[]
for row in proof['sources']:assert sha(row['path'])==row['sha256Bytes']
actual_references=[
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeEmbeddedRetainedOwner.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeEnvironment.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeRetainedEntry.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeRootRequestBinding.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPartitionRoot.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiHubRoot.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiveListBindings.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSubscriptionPageBindings.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDynamicCardHost.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicCardSession.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopImageSaveLocations.kt']
safe(HERE/'existing-owner-references.json').write_text(json.dumps({'installNone':True,
 'sources':[{'path':p,'sha256LF':hashlib.sha256(safe(REPO/p).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()}for p in actual_references],
 'newOriginalIdentityCount':0,'newDependencies':[],'newResources':[],'sourceProducers':[]},indent=2)+'\n',encoding='utf-8')
source_paths=list((HERE/'prepared/manual').rglob('*.kt'))
whitelist=[]
for p in source_paths:
 relative=p.relative_to(HERE/'prepared/manual').as_posix()
 whitelist.append({'source':p.relative_to(HERE).as_posix(),
 'destination':'desktop/src/main/kotlin/'+relative,'sha256LF':hashlib.sha256(safe(p).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()})
safe(HERE/'install-whitelist.json').write_text(json.dumps({'files':whitelist,
 'sharedPatchCount':0,'registryDelta':[],'GradleDelta':None,
 'neverInstall':['compile-*','proof-*','fixtures','task classes/JAR/DLL/store']},indent=2)+'\n',encoding='utf-8')
raw=[];binary=[]
for p in HERE.rglob('*'):
 if not p.is_file() or p.name=='frozen-handoff.json' or '__pycache__' in p.parts:continue
 row={'path':p.relative_to(HERE).as_posix(),'sha256Bytes':sha(p),'bytes':safe(p).stat().st_size}
 (binary if p.suffix in ('.class','.jar','.dll') else raw).append(row)
manifest={'target':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','scope':'Prepared four complete original embedded pages aggregate/platform factory',
 'rawArtifacts':sorted(raw,key=lambda r:r['path']),'excludedRuntimeArtifacts':sorted(binary,key=lambda r:r['path']),
 'installFiles':whitelist,'existingProducersUntouched':True,'actualMainBase':str(snapshot),
 'evidence':{'compile':'compile-02','candidateSources':3,'candidateClasses':43,'classOverlap':[],
 'proof':'proof-01','lifecycleGroups':4,'actualRootMounted':False,'actualComposeOrNativeUi':False,
 'actualRuntimeConstructed':False,'actualHttpOrAccount':False,'actualGallerySave':False}}
safe(HERE/'frozen-handoff.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print('freeze',sha(HERE/'frozen-handoff.json'),'raw',len(raw),'excluded',len(binary),'whitelist',len(whitelist))
