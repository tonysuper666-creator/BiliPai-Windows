from pathlib import Path
import hashlib,json
LANE=Path(__file__).resolve().parent
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def lfsha(p):return hashlib.sha256(read(p).replace('\r\n','\n').encode()).hexdigest()
compiled=json.loads(read(LANE/'compile-09/compile-result.json'))
proof=json.loads(read(LANE/'proof-09/proof-result.json'))
abi=json.loads(read(LANE/'abi-audit.json'));sources=json.loads(read(LANE/'source-audit.json'))
install=json.loads(read(LANE/'install-contract.json'));inputs=json.loads(read(LANE/'input-audit.json'))
assert compiled['sources']==9 and proof['groups']==3 and proof['assertions']==55
assert proof['candidateJarSha256Bytes']==compiled['jarSha256Bytes']==sha(compiled['jar'])
assert abi['candidateClasses']==compiled['classes']==abi['loadedWithoutInitialization']
assert len(abi['prospectiveExistingClassOverlaps'])==61 and abi['prospectiveSharedFileOverrideCount']==3
assert abi['newClassAndPublicTopMethodOverlap']==0 and not abi['invalidJvmMethodNames']
assert abi['existingPublicCoreAbiPreserved']==14
assert sources['checks']==49 and sources['fullOriginalFilesRecovered']==1 and sources['originalThemeMethodsRetained']==2
assert len(install['payloads'])==6 and install['sharedHunkCount']==5
for row in compiled['sourcePins']:assert sha(row['path'])==row['sha256Bytes'],row['path']
for row in install['payloads']:assert sha(row['source'])==row['sha256Bytes'] and lfsha(row['source'])==row['sha256LF'],row['source']
assert len(inputs['verifiedDependencyPins'])==97
for row in inputs['verifiedDependencyPins']:assert sha(row['path'])==row['sha256Bytes'],row['path']
hunks=json.loads(read(LANE/'shared-local-hunks.json'))
for row in hunks['targets']:
 assert lfsha(LANE/'shared-bases'/row['path'])==row['baseSha256LF']
 assert lfsha(LANE/'prepared/shared-candidates'/row['path'])==row['candidateSha256LF']
paths=['prepare.py','compile.py','finish.py','run-proof.py','freeze.py','ProfilePlatformFixture.kt','pins.json','input-audit.json',
 'source-audit.json','abi-audit.json','original-import-reverse.json','original-theme-methods.json','shared-local-hunks.json',
 'shared-hunks-replay.json','registry-merge-recipe.json','gradle-task.snippet.kts','install-contract.json','ROOT-INTEGRATION.md',
 'replay-production.log','compile-09/kotlin.log','compile-09/compile-result.json','compile-09/original-profile-platform.jar',
 'proof-09/compile.log','proof-09/run.log','proof-09/proof-result.json','proof-09/profile-platform-fixture.jar',
 'proof-09/media-inputs.json','proof-09/media-generator.log','proof-09/synthetic-local.mp4',
 'classload-proof/ProfilePlatformClassLoadProof.java','classload-proof/compile.log','classload-proof/run.log',
 'proof-01/run.log','proof-02/run.log']
for directory in ['prepared','shared-bases','original-stable','original-stable-selected','replay-production','proof-09/local-fixture']:
 paths += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/directory).rglob('*') if p.is_file() and '__pycache__' not in p.as_posix()]
paths += [p.relative_to(safe(LANE)).as_posix() for p in safe(LANE/'proof-02/local-fixture/io/home_wallpaper').glob('.profile-metadata-*')]
result={'scope':'Full original WallpaperImageImport with actual Windows Profile physical effects, concrete retained factory, same global theme/cache and sole gallery admission',
 'fixedCommit':json.loads(read(LANE/'pins.json'))['commit'],'actualSnapshot':42,'actualRuntimeEntries':97,
 'actualOrderedRuntimeCpSha256Bytes':'6fb3e971ee12e0f77c7a7dd6e54a4af64456b3e8bab8446660c1f10947b2d63c',
 'sourceProduction':{'manualSources':5,'generatedOriginalSources':1,'newOriginalRegistryRows':1,'sharedLocalHunks':5,'sharedTargets':4,'newDependencies':0,'wholeSharedCandidateInstall':False},
 'compile':{'sources':9,'classes':abi['candidateClasses'],'methods':abi['candidateMethods'],'jarSha256Bytes':compiled['jarSha256Bytes'],'prospectiveSharedSourceOverrides':3,
  'prospectiveExistingClassOverrides':61,'newClassAndPublicTopMethodOverlap':0,'invalidJvmMethodNames':0,'loadedWithoutInitialization':abi['loadedWithoutInitialization'],'existingPublicCoreAbiPreserved':14,'actualModule':'com_bilipai_desktop_bilipai_windows'},
 'proof':{'groups':3,'assertions':55,'sourceChecks':49,'wholeOriginalImportRecovered':True,'originalThemeMethods':2,'physicalLocalSkia':True,'trustedBundledFfprobe':True,'sameActualPluginStoreTheme':True,
 'fixtureOnlyAdmissionAndGallerySessionOwner':True,'actualChooserOpened':False,'actualRootWindowOrDwmAccepted':False,'externalHttp':False,'userWallpaperOrAccountsRead':False},
 'ownerContracts':{'all16PlatformEffectsConcrete':True,'factoryAllRequired':True,'sameGlobalAppearance':True,'sameStoreAndEntry':True,'soleAssetsAndLocations':True,'sameOwnedCallFactory':True,'sameExistingMediaLifetime':True},
 'rootMounted':False,'sharedSourcesModified':False,'sharedGradleRun':False,'nativeRebuilt':False,'deployed':False,
 'rootRequiredAcceptance':['Serial shared hunk install plus full product generation/compile','Retained Profile main navigation mount with actual services','Actual Root Window dimensions/capabilities/icon and chooser/clipboard/chrome','Owned HTTP/download and real user experience'],
 'installContractSha256Bytes':sha(LANE/'install-contract.json'),'rootRecipeSha256Bytes':sha(LANE/'ROOT-INTEGRATION.md'),
 'artifacts':[{'path':p,'sha256Bytes':sha(LANE/p)} for p in sorted(set(paths))]}
safe(LANE/'frozen-handoff.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
print(json.dumps({'manifest':str(LANE/'frozen-handoff.json'),'manifestSha256Bytes':sha(LANE/'frozen-handoff.json'),'artifacts':len(result['artifacts']),'installContractSha256Bytes':result['installContractSha256Bytes'],'rootRecipeSha256Bytes':result['rootRecipeSha256Bytes'],'compile':result['compile'],'proof':result['proof']},indent=2))
