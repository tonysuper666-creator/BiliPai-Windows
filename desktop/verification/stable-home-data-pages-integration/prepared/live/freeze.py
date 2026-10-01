from pathlib import Path
import hashlib,json,subprocess
LANE=Path(__file__).resolve().parent;REPO=LANE.parents[2];MAIN=LANE.parents[4]/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(safe(p).read_bytes().replace(b'\r\n',b'\n')).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
def write(p,value):safe(p).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
source=json.loads(read(LANE/'source-inventory.json'));manifest=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in manifest['sources']}
production={
 'feature/live/LiveListScreen':'policy-extract',
 'feature/live/LiveRoomCard':'direct',
 'feature/live/LiveChromePalette':'direct',
 'feature/live/LiveBiliPaiVisualPolicy':'direct',
 'feature/live/LiveHomeSelectableChip':'direct',
 'feature/live/LiveHomeCategoryIndicatorPolicy':'direct',
 'feature/live/LiveHomeAreaSelectionPolicy':'direct',
 'feature/live/LiveListTabColorPolicy':'direct',
 'feature/live/LiveRoomLayoutPolicy':'policy-extract',
 'data/repository/LiveRepository':'policy-extract',
 'data/repository/LiveFeedParsePolicy':'direct',
 'data/repository/LiveAreaRoomsPolicy':'direct',
 'core/util/ResultExtensions':'policy-extract',
}
rows=[];base='app/src/main/java/com/android/purebilibili/'
for item in source['sources']:
 p=item['path'];mode=production.get(p.removeprefix(base).removesuffix('.kt'));row=existing.get(p)
 if row:assert row['sha256']==item['sha256LF'],p
 rows.append({'path':p,'sha256':item['sha256LF'],'sha256Bytes':sha(REPO/p),'mode':(row['mode'] if row else mode),'features':['live-home-list'],'existingIdentity':row is not None,'existingMode':row['mode'] if row else None,'referenceOnly':mode is None})
write(LANE/'registry-merge-recipe.json',{'pinnedCommit':source['pinnedCommit'],'observedRegistryRows':len(existing),'observedRegistrySha256Bytes':sha(REPO/'desktop/upstream-sources.json'),'newProductionOriginalRows':sum(not r['existingIdentity'] and not r['referenceOnly'] for r in rows),'existingProductionFeatureMerge':sum(r['existingIdentity'] and not r['referenceOnly'] for r in rows),'mergeExistingRowsPreservingModeAndAllFeatures':True,'records':rows})
payloads=[]
for local,target in [('prepared/tools/extract-upstream-live-list.py','desktop/tools/extract-upstream-live-list.py'),('prepared/manual/com/bilipai/desktop/ui/DesktopLiveListBindings.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopLiveListBindings.kt')]:
 payloads.append({'source':str(LANE/local),'target':target,'sha256Bytes':sha(LANE/local),'sha256LF':lfsha(LANE/local),'newSoleDefinition':True})
write(LANE/'install-contract.json',{'pinnedCommit':source['pinnedCommit'],'payloads':payloads,'localSharedHunks':0,'productionSelectedGeneratedSources':4,'directOriginalFiles':9,'declaredProspectiveRootRequired':'parent raw694 must be installed with its exact retained owner environment and two same-source transport families before whole compile','registry':'registry-merge-recipe.json','gradleRecipe':'gradle-tasks.snippet.kts','callerRecipe':'ROOT-INTEGRATION.md','generatedPreparedFilesInstall':False,'jarInstall':False,'newExternalDependencies':0})
abi=json.loads(read(LANE/'abi-audit.json'));fixture=json.loads(read(LANE/'fixture-01/results/result.json'));audit=json.loads(read(LANE/'source-audit.json'));compile=json.loads(read(LANE/'compile-02/compile-result.json'))
assert abi['classFqnOverlap']==[] and abi['samePackagePublicStaticMethodOverlap']==[] and abi['invalidJvmMethodNames']==[]
assert fixture['groups']==10 and fixture['assertions']==28 and fixture['preparedClassLoadCount']==103
assert audit['checks']==155 and compile['exitCode']==0
whitelist=['prepare.py','audit_inputs.py','compile.py','fixture.py','audit_source.py','audit_abi.py','freeze.py','LiveListFixture.kt','ROOT-INTEGRATION.md','gradle-tasks.snippet.kts','input-audit.json','source-inventory.json','source-audit.json','abi-audit.json','live-list-windows-adaptation.diff','ui-exact-reverse-patches.json','registry-merge-recipe.json','install-contract.json','compile-02/compile.log','compile-02/compile-result.json','compile-02/original-live-list.jar','fixture-01/compile.log','fixture-01/run.log','fixture-01/results/result.json','stable-home-request-ports-parity-declared-prospective.jar','stable-home-viewmodel-parity-declared-prospective.jar']
for dir in ['prepared','original-stable','replay-production','replay-standalone']:
 whitelist += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/dir).rglob('*') if p.is_file() and '__pycache__' not in p.as_posix()]
artifacts=[{'path':p,'sha256Bytes':sha(LANE/p)} for p in sorted(set(whitelist))]
out={'scope':'complete original stable Live home list closure; required retained parent Root ports','pinnedCommit':source['pinnedCommit'],'actualRootRuntime':False,'actualRootComposeWindow':False,'actualHttpOrAccount':False,'sharedSourcesOrGradleModified':False,'deployedExe':False,'actualProductSnapshot36':{'manifestSha256Bytes':'a445ed0f4944ba6ac581b06401576aec23638ef242aa1da442a6baead60b637d','orderedClasspathSha256Bytes':'2fa3092ec985e32bf6b1b046266e6b84994adeeb023615549a9422eddb0aa493','entries':97},'declaredProspectiveRaw694Jar':{'sha256Bytes':compile['prospectiveOwnerNetworkPayload']['jarSha256Bytes'],'actualProduct':False,'existingSameSourceOverrideFamilies':abi['prospectiveRaw694OverrideFamilies']},'compile':{'sources':14,'classes':103,'methods':804,'jarSha256Bytes':compile['jarSha256Bytes'],'classAndTopMethodOverlap':0,'invalidJvmMethodNames':0},'proof':{'sourceChecks':155,'fixtureGroups':10,'fixtureAssertions':28,'jvmLoadedCandidateClasses':103},'installationContractSha256Bytes':sha(LANE/'install-contract.json'),'artifacts':artifacts}
write(LANE/'frozen-handoff.json',out)
print(json.dumps({'frozenHandoff':str(LANE/'frozen-handoff.json'),'sha256Bytes':sha(LANE/'frozen-handoff.json'),'artifacts':len(artifacts),'installPayloads':len(payloads),'newProductionRows':sum(not r['existingIdentity'] and not r['referenceOnly'] for r in rows)},indent=2))
