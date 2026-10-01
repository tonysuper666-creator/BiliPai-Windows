from pathlib import Path
import json,hashlib
LANE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(read(p).replace('\r\n','\n').encode()).hexdigest()
pins=json.loads(read(LANE/'source-pins.json'));install=json.loads(read(LANE/'install-contract.json'))
compiled=json.loads(read(LANE/'compile-06/compile-result.json'));abi=json.loads(read(LANE/'abi-audit.json'));proof=json.loads(read(LANE/'proof-03/proof-result.json'));audit=json.loads(read(LANE/'source-audit.json'))
assert compiled['sources']==4 and compiled['classes']==18 and compiled['overrides']==0
assert abi['loadedWithoutInitialization']==18 and abi['candidateMethods']==146 and not abi['invalidJvmMethodNames'] and not abi['samePackagePublicStaticMethodOverlap']
assert proof['groups']==3 and proof['assertions']==34 and proof['candidateJarSha256Bytes']==compiled['jarSha256Bytes']
assert audit['checks']==89 and audit['fullOriginalFilesRecovered']==2 and audit['officialMethodsExact']==6
for p in compiled['sourcePins']:assert sha(p['path'])==p['sha256Bytes'],p['path']
for p in install['payloads']:assert sha(p['source'])==p['sha256Bytes'] and lfsha(p['source'])==p['sha256LF'],p['source']
inputs=json.loads(read(LANE/'input-audit.json'));assert len(inputs['verifiedDependencyPins'])==97
for row in inputs['verifiedDependencyPins']:assert sha(row['path'])==row['sha256Bytes'],row['path']
assert sha(pins['officialSourcesJar'])==pins['officialSourcesJarSha256Bytes']
files=['prepare.py','store-methods.template.kt','compile.py','finish.py','audit.py','run-proof.py','WallpaperPaletteFixture.kt','freeze.py',
 'source-pins.json','selection-methods.json','official-selection-receipt.json','input-audit.json','source-audit.json','abi-audit.json',
 'registry-merge-recipe.json','gradle-tasks.snippet.kts','install-contract.json','ROOT-INTEGRATION.md','replay-production.log',
 'compile-06/java.log','compile-06/kotlin.log','compile-06/compile-result.json','compile-06/original-wallpaper-palette.jar',
 'proof-03/compile.log','proof-03/run.log','proof-03/proof-result.json','proof-03/wallpaper-palette-fixture.jar',
 'classload-proof/PaletteClassLoadProof.java','classload-proof/compile.log','classload-proof/run.log']
for directory in ['prepared','official-palette-1.0.0','original-stable','reverse-adapters','replay-production','proof-03/local-fixture']:
 files += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/directory).rglob('*') if p.is_file() and '__pycache__' not in p.as_posix()]
manifest={'scope':'Full original WallpaperPaletteStore and AndroidX Target/scoring closure with required sameRoot owned Windows image/system effects',
 'pinnedCommit':pins['upstreamCommit'],'originalSourceSha256LF':pins['originalSha256LF'],
 'actualSnapshot':41,'actualRuntimeEntries':97,'actualOrderedClasspathSha256Bytes':'23e35645396f8b5b1d720c9604799bacccb75e3edca551dcf49da54497564abe',
 'productionSources':4,'installPayloads':4,'generatedOriginalSources':1,'newOriginalRegistryRows':1,'newDependencies':0,'secondQuantizer':False,
 'compile':{'candidateJarSha256Bytes':compiled['jarSha256Bytes'],'classes':18,'methods':146,'runtimeOverrides':0,'classAndTopMethodOverlap':0,'invalidJvmMethodNames':0,'loadedWithoutInitialization':18},
 'proof':{'groups':3,'assertions':34,'sourceChecks':89,'fullOriginalFilesRecovered':2,'originalOfficialScoringMethods':6,'sameActualSingletonImageLoaderLocalDecode':True,'personalWallpaperRead':False},
 'windowsSystemColorUnsupportedGuard':True,'sameRootBindingRequired':True,'rootMounted':False,'actualWindowsWallpaperAccepted':False,'externalHttpOrAccountsUsed':False,'sharedSourcesModified':False,'sharedGradleRun':False,'deployedExe':False,
 'installContractSha256Bytes':sha(LANE/'install-contract.json'),
 'artifacts':[{'path':p,'sha256Bytes':sha(LANE/p)} for p in sorted(set(files))]}
safe(LANE/'frozen-handoff.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'sha256Bytes':sha(LANE/'frozen-handoff.json'),'artifacts':len(manifest['artifacts']),'installContractSha256Bytes':manifest['installContractSha256Bytes'],'compile':manifest['compile'],'proof':manifest['proof']},indent=2))
