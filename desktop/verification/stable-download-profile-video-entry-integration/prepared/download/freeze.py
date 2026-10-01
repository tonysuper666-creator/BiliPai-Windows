from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
def js(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
compile=json.loads(read(LANE/'compile-03/compile-result.json'));proof=json.loads(read(LANE/'proof-05/proof-result.json'));abi=json.loads(read(LANE/'abi-audit.json'));install=json.loads(read(LANE/'install-contract.json'));receipt=json.loads(read(LANE/'generated/source-receipt.json'));replay=json.loads(read(LANE/'production-byte-equality.json'));inputs=json.loads(read(LANE/'input-audit.json'))
checks=[]
def check(name,value):assert value,name;checks.append(name)
check('Fixed original commit preserved',receipt['fixedCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589')
check('All three complete original list declarations',len(receipt['allThreeListDeclarationsRetained'])==3)
check('Whole UI/storage/direct bodies reverse to fixed source',all(r['exactReversePass'] for r in receipt['sources'] if r['wholeOriginalFile']))
check('Canonical getter bodies unchanged and selected settings inverse',receipt['canonicalGetterBodiesUnchanged'] and receipt['selectedSettingsExactInversePass'])
check('Actual strict97 graph',inputs['actualSnapshot']==44 and len(inputs['verifiedDependencyPins'])==97)
check('Zero prospective product overrides',compile['declaredProspectiveOverrideFamilies']==[])
check('Actual6source candidate compile',compile['sources']==6 and compile['classes']==36)
check('36 classes loaded without initialization',proof['loadedCandidateClasses']==abi['classloadWithoutInitialization']==36)
check('Zero class/public top method overlap',abi['newFqnOverlap']==abi['newPublicTopMethodOverlap']==0)
check('Zero invalid JVM names',abi['invalidJvmMethodNames']==[])
check('Focused original UI actual two pointer interactions',proof['assertions']==21 and proof['groups']==3 and proof['pointerPairs']==2)
check('Real existing queue and owned local asset deletion',proof['actualQueueAndLocalDelete'])
check('No HTTP chooser or Root acceptance claims',proof['HTTPCalls']==0 and not proof['chooserOpened'] and not proof['rootMounted'])
check('3 sole install source payloads',len(install['payloads'])==3)
check('3 exact local shared hunks with preserved Playback45 admission',install['sharedHunks']==3)
check('Production byte equality and direct skip',replay['allThreeProductionFilesEqualStandaloneCompiledInputs'] and not replay['directPolicyEmittedTwice'])
check('No new task model HTTP or persistence actor',not receipt['newHttpClient'] and not receipt['newTaskModelOrStore'])
for r in inputs['verifiedDependencyPins']:assert sha(r['path'])==r['sha256Bytes']
for r in compile['sourcePins']:assert sha(r['path'])==r['sha256Bytes']
for r in install['payloads']:assert sha(r['source'])==r['sha256Bytes']
check('Source files unchanged after final compile/proof',proof['candidateJarSha256Bytes']==compile['jarSha256Bytes']==sha(compile['jar']))
js(LANE/'source-audit.json',{'checks':checks,'checkCount':len(checks),'actualSourceConsumersNotCountBasedPercent':True,'exactReversePass':True,'newModelOrStore':False,'sourceIdentityRetargeted':False})
paths=['compile.py','run-proof.py','finish.py','freeze.py','DownloadListFixture.kt','source-pins.json','input-audit.json','source-audit.json','abi-audit.json','install-contract.json','registry-merge-recipe.json','shared-local-hunks.json','production-byte-equality.json','replay-production.log','gradle-task.snippet.kts','ROOT-INTEGRATION.md','compile-01/kotlin.log','compile-03/kotlin.log','compile-03/compile-result.json','compile-03/original-download-list.jar','proof-01/run.log','proof-02/run.log','proof-03/run.log','proof-05/compile.log','proof-05/run.log','proof-05/proof-result.json','proof-05/download-list-fixture.jar']
for directory in ['prepared','generated','replay-production','shared-bases','review-only-shared-candidates','proof-05/local-fixture']:
 paths += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/directory).rglob('*') if p.is_file() and '__pycache__' not in p.as_posix()]
handoff={'scope':'Complete original v0.2.3 DownloadListScreen and original storage/display policies with sole existing Windows queue bindings','fixedCommit':receipt['fixedCommit'],'actualSnapshot':44,'actualRuntimeEntries':97,'actualOrderedCpSha256Bytes':'b4d72f39a19b3704e657a9edb8c8e8d18c10a1a352481c34a96f0ad208e17141','production':{'payloads':3,'manual':2,'generated':3,'direct':1,'newRegistryRows':3,'settingsIdentityFeatureMerge':1,'sharedLocalHunks':3,'sharedTargets':2,'newDependencies':0,'prospectiveProductOverrides':0},'compile':{'sources':6,'classes':36,'methods':abi['candidateMethods'],'newFqnOverlap':0,'newPublicTopMethodOverlap':0,'invalidJvmMethodNames':0,'jarSha256Bytes':compile['jarSha256Bytes']},'proof':{'groups':3,'assertions':21,'sourceChecks':len(checks),'pointerPairs':2,'loadedClasses':36,'originalOffscreenUi':True,'sameActualQueueAndStore':True,'fixtureOnlyOwnedAdmission':True,'externalHTTP':False,'realAccountOrUserFiles':False,'chooser':False,'nativePlayer':False},'originalUIAndStorageWholeReverse':True,'sourceBodiesGitVerified':True,'newHttpTaskStoreOrPlayer':False,'rootMounted':False,'sharedSourcesModified':False,'sharedGradleRun':False,'deployed':False,'remainingRequiredRootAcceptance':['Serial source/registry/local-hunk install and whole classes','Actual retained DownloadList key and foreground/scaffold/theme provider','Required offline taskId consumer on existing retained native owner','Full original OfflineVideoPlayerScreen remains a separate UI closure','Windows Android-SAF export capability is unsupported, reported rather than simulated'],'installContractSha256Bytes':sha(LANE/'install-contract.json'),'rootRecipeSha256Bytes':sha(LANE/'ROOT-INTEGRATION.md'),'artifacts':[{'path':p,'sha256Bytes':sha(LANE/p)} for p in sorted(set(paths))]}
js(LANE/'frozen-handoff.json',handoff)
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'manifestSha256Bytes':sha(LANE/'frozen-handoff.json'),'artifacts':len(handoff['artifacts']),'installContractSha256Bytes':handoff['installContractSha256Bytes'],'rootRecipeSha256Bytes':handoff['rootRecipeSha256Bytes'],'compile':handoff['compile'],'proof':handoff['proof']},indent=2))
