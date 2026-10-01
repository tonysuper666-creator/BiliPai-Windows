from pathlib import Path
import hashlib,json,re
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(read(p).encode()).hexdigest()
def write(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
pins=json.loads(read(LANE/'pins.json'));generated=json.loads(read(LANE/'generation-records.json'));registry=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in registry['sources']}
rows=[]
for row in generated:
 p=row['source'];old=existing.get(p)
 if old:assert old['sha256']==pins[p],p
 rows.append({'path':p,'sha256':pins[p],'mode':old['mode'] if old else 'policy-extract','features':['video-share-original-windows'],
     'existingIdentity':old is not None,'existingMode':old['mode'] if old else None,'mergeModeAndFeatures':True,'newSoleDefinition':old is None})
write(LANE/'registry-merge-recipe.json',{'pinnedCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','observedRegistryRows':len(existing),'observedRegistrySha256Bytes':sha(REPO/'desktop/upstream-sources.json'),'records':rows,'newRows':sum(not r['existingIdentity'] for r in rows),'existingFeatureMerges':sum(r['existingIdentity'] for r in rows)})
fragment=read(LANE/'operations-member.fragment.kt');opsPath='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt';base=read(REPO/opsPath)
marker='// GENERATED original editor members; do not hand-maintain a second request algorithm.\n';assert base.count(marker)==1
after=fragment+'\n'+marker
write(LANE/'operations-local-hunk.json',{'target':opsPath,'observedBaseSha256LF':lfsha(REPO/opsPath),'before':marker,'after':after,'candidateSha256LF':hashlib.sha256(base.replace(marker,after).encode()).hexdigest(),'insertionOutsideCanonicalEditorRange':True})
payloads=[{'source':str(LANE/'prepared/tools/extract-upstream-video-share-consent.py'),'target':'desktop/tools/extract-upstream-video-share-consent.py','sha256Bytes':sha(LANE/'prepared/tools/extract-upstream-video-share-consent.py')}]
for file in sorted(safe(LANE/'prepared/manual').rglob('*.kt')):
 rel=file.relative_to(safe(LANE/'prepared/manual')).as_posix()
 payloads.append({'source':str(LANE/'prepared/manual'/rel),'target':'desktop/src/main/kotlin/'+rel,'sha256Bytes':sha(file),'newSoleDefinition':True})
assert len(payloads)==6
compile=json.loads(read(LANE/'compile-07/compile-result.json'));abi=json.loads(read(LANE/'abi-audit.json'));audit=json.loads(read(LANE/'source-audit.json'));hunks=json.loads(read(LANE/'native-local-hunks.json'))
assert compile['sources']==14 and compile['classes']==83 and compile['runtimeSourceOverrides']==0
assert abi['candidateMethods']==498 and abi['jvmLoadedPreparedClasses']==83 and not abi['invalidJvmMethodNames'] and not abi['classFqnOverlap'] and not abi['samePackagePublicStaticMethodOverlap']
assert audit['checks']==96 and audit['fullOriginalFilesReverseRecovered']==9
assert len(hunks['rows'])==18
proof=read(LANE/'proof/run-02/run.log');assert 'PASS 4 groups / 24 assertions' in proof
for row in compile['sourcePins']:assert sha(row['path'])==row['sha256Bytes'],row['path']
inputs=json.loads(read(LANE/'input-audit.json'));assert len(inputs['verifiedDependencyPins'])==97
for row in inputs['verifiedDependencyPins']:assert sha(row['path'])==row['sha256Bytes']
contract={'pinnedCommit':'3d5d19a2f994daccd0e2f8b5f522b6d82f43d589','payloads':payloads,'generatedPreparedFilesInstall':False,'proofJarInstall':False,
    'generatedProductionSources':9,'manualProductionSources':5,'registryRecipe':'registry-merge-recipe.json','gradleRecipe':'gradle-tasks.snippet.kts','rootBindings':'ROOT-INTEGRATION.md',
    'operationsFragmentAndExactHunk':'operations-local-hunk.json','platformNativeDiagnosticExactHunks':'native-local-hunks.json','platformNativeDiagnosticHunks':18,
    'nativeRebuildFreshStageRequired':True,'nativeTargets':hunks['targets'],'newDependencies':0,'newHttpClients':0,'newPersistentStores':0,'rootMounted':False,
    'mandatoryActualEffects':['same owner raw following/send text Ops','actual Root Window chooser/clipboard/nativeTextShare','verified rebuilt native media export/DLL hash','same diagnostic actor consent queue/global original keys','native grant revocation before explicit cache clear']}
write(LANE/'install-contract.json',contract)
whitelist=['prepare.py','prepare_native_delta.py','compile.py','audit_abi.py','pack.py','freeze.py','proof.py','pins.json','generation-records.json','source-inventory.json','source-audit.json','abi-audit.json','input-audit.json',
    'platform-card.template.kt','platform-cover.template.kt','native-local-hunks.json','operations-member.fragment.kt','operations-local-hunk.json','registry-merge-recipe.json','install-contract.json','gradle-tasks.snippet.kts','ROOT-INTEGRATION.md',
    'compile-07/compile.log','compile-07/compile-result.json','compile-07/original-video-share-consent.jar','proof/VideoShareProof.kt','proof/run-02/compile.log','proof/run-02/run.log','classload-proof/compile.log','classload-proof/run.log','classload-proof/ProfileClassLoadProof.java']
for directory in ['prepared','original-stable','adaptation-diffs','reverse-adapters','replay-production','review-only-native']:
 whitelist += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/directory).rglob('*') if p.is_file() and '__pycache__' not in p.as_posix()]
receipt={'scope':'entire original video share/consent renderer and explicit physical Windows effects source-only closure','pinnedCommit':contract['pinnedCommit'],
    'actualSnapshot':40,'actualRuntimeEntries':97,'actualOrderedClasspathSha256Bytes':'3f3dc7c6fd682df07afa523fc31c0c43804549c0e1c0dbea81cf264a6062738d',
    'compile':{'sources':14,'classes':83,'methods':498,'jarSha256Bytes':compile['jarSha256Bytes'],'runtimeOverrides':0,'classAndTopMethodOverlap':0,'invalidMethodNames':0,'classesLoadedWithoutInit':83},
    'proof':{'groups':4,'assertions':24,'sourceReplayReverseChecks':96,'completeOriginalSourcesReverseRecovered':9,'physicalWindowsLocalFileAndNativeSkikoWebP':True,'nativeShareHWNDOrAccountNetwork':False},
    'installPayloads':6,'operationsExactHunks':1,'nativeDiagnosticExactHunks':18,'nativeBuilt':False,'actualRootMounted':False,'sharedSourcesOrGradleModified':False,'deployedExe':False,
    'installContractSha256Bytes':sha(LANE/'install-contract.json'),'artifacts':[{'path':p,'sha256Bytes':sha(LANE/p)} for p in sorted(set(whitelist))]}
write(LANE/'frozen-handoff.json',receipt)
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'sha256Bytes':sha(LANE/'frozen-handoff.json'),'artifacts':len(receipt['artifacts']),'payloads':len(payloads),'newRows':sum(not r['existingIdentity'] for r in rows),'existingMerges':sum(r['existingIdentity'] for r in rows)},indent=2))
