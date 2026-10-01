from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2];MAIN=LANE.parents[4]/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(safe(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
def write(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
source=json.loads(read(LANE/'source-inventory.json'));pins=json.loads(read(LANE/'pins.json'));registry=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in registry['sources']}
generated={r['path']:r for r in source['generatedSources']};rows=[]
for path,h in pins.items():
 row=existing.get(path);item=generated.get(path)
 if row:assert row['sha256']==h,path
 mode='policy-extract' if item and item['preparedProductionSelected'] else 'direct' if item else None
 rows.append({'path':path,'sha256':h,'sha256Bytes':sha(REPO/path),'mode':row['mode'] if row else mode,
     'features':['profile-main-original'],'existingIdentity':row is not None,'existingMode':row['mode'] if row else None,
     'referenceOnly':item is None,'newSoleOriginalDefinition':item is not None and row is None})
write(LANE/'registry-merge-recipe.json',{'pinnedCommit':source['pinnedCommit'],'observedRegistryRows':len(existing),'observedRegistrySha256Bytes':sha(REPO/'desktop/upstream-sources.json'),
    'newProductionRows':sum(not r['existingIdentity'] and not r['referenceOnly'] for r in rows),
    'existingFeatureMerges':sum(r['existingIdentity'] and not r['referenceOnly'] for r in rows),'mergeExistingModeAndFeatures':True,'records':rows})
payloads=[]
for local,target in [('prepared/tools/extract-upstream-profile-main.py','desktop/tools/extract-upstream-profile-main.py'),
 ('prepared/manual/com/bilipai/desktop/ui/DesktopProfileBindings.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopProfileBindings.kt'),
 ('prepared/manual/com/bilipai/desktop/ui/DesktopOriginalProfilePreferences.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalProfilePreferences.kt')]:
 payloads.append({'source':str(LANE/local),'target':target,'sha256Bytes':sha(LANE/local),'sha256LF':lfsha(LANE/local),'newSoleDefinition':True})
write(LANE/'install-contract.json',{'pinnedCommit':source['pinnedCommit'],'payloads':payloads,'localSharedSourceHunks':0,'productionSelectedSources':10,'wholeOriginalDirectSources':12,
 'registryRecipe':'registry-merge-recipe.json','gradleRecipe':'gradle-tasks.snippet.kts','requiredCallerRecipe':'ROOT-INTEGRATION.md','generatedPreparedFilesInstall':False,'jarInstall':False,'newDependencies':0,
 'requiredRootBoundaries':['actual retained Profile nav/account epoch and 17 actions','sameStore verified playback MID and playback URL authorization consumer/invalidation','sameRoot original live theme setter',
 'sameRoot physical chooser/file/download/gallery/chrome ports','same existing Home media lifetime with real alignment/repeat/GIF support'],'rootMounted':False})
compile=json.loads(read(LANE/'compile-06/compile-result.json'));audit=json.loads(read(LANE/'source-audit.json'));abi=json.loads(read(LANE/'abi-audit.json'))
assert compile['exitCode']==0 and compile['sources']==24 and compile['runtimeSourceOverrides']==0
assert audit['checks']==313 and abi['candidateClasses']==180 and abi['candidateMethods']==2035 and abi['jvmLoadedPreparedClasses']==180
assert abi['invalidJvmMethodNames']==[] and abi['classFqnOverlap']==[] and abi['samePackagePublicStaticMethodOverlap']==[]
for row in compile['sourcePins']:assert sha(row['path'])==row['sha256Bytes'],row['path']
inputs=json.loads(read(LANE/'input-audit.json'));assert len(inputs['verifiedDependencyPins'])==97
for row in inputs['verifiedDependencyPins']:assert sha(row['path'])==row['sha256Bytes'],row['path']
whitelist=['prepare.py','compile.py','audit_source.py','audit_abi.py','freeze.py','pins.json','input-audit.json','source-inventory.json','method-inventory.json','source-audit.json','abi-audit.json',
 'ROOT-INTEGRATION.md','gradle-tasks.snippet.kts','registry-merge-recipe.json','install-contract.json','compile-06/compile.log','compile-06/compile-result.json','compile-06/original-profile-main.jar',
 'classload-proof/ProfileClassLoadProof.java','classload-proof/compile.log','classload-proof/run.log']
for directory in ['prepared','original-stable','adaptation-diffs','reverse-adapters','replay-production','replay-standalone']:
 whitelist += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/directory).rglob('*') if p.is_file() and '__pycache__' not in p.as_posix()]
out={'scope':'complete original Profile primary navigation source/UI/VM/pure closure with required real Root account/settings/media/file ports','pinnedCommit':source['pinnedCommit'],
 'actualProductSnapshot39':{'manifestSha256Bytes':'4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae','orderedClasspathSha256Bytes':'e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f','entries':97},
 'compile':{'sources':24,'classes':180,'methods':2035,'jarSha256Bytes':compile['jarSha256Bytes'],'runtimeSourceOverrides':0,'classAndTopMethodOverlap':0,'invalidJvmNames':0},
 'proof':{'sourceReplayReverseChecks':313,'fullOriginalFilesRecovered':7,'preparedClassesLoadedWithoutInitialization':180},'installPayloads':3,
 'sharedSourcesOrGradleModified':False,'actualRootMounted':False,'actualWindowNetworkAccountMediaAccepted':False,'deployedExe':False,
 'requiredRootBoundariesRemain':True,'installationContractSha256Bytes':sha(LANE/'install-contract.json'),
 'artifacts':[{'path':p,'sha256Bytes':sha(LANE/p)} for p in sorted(set(whitelist))]}
write(LANE/'frozen-handoff.json',out)
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'manifestSha256Bytes':sha(LANE/'frozen-handoff.json'),'artifacts':len(out['artifacts']),'payloads':len(payloads),'newProductionRows':sum(not r['existingIdentity'] and not r['referenceOnly'] for r in rows)},indent=2))
