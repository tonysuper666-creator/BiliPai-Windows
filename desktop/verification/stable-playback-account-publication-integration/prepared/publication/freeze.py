from pathlib import Path
import hashlib,json,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-43'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,x):safe(p).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
assert not (HERE/'frozen-handoff.json').exists()
compile=json.loads(read(HERE/'runs/compile-08/compile-result.json'));accepted=json.loads(read(HERE/'runs/fixture-05/accepted.json'))
assert compile['status']==accepted['status']=='PASS' and accepted['groups']==4 and accepted['assertions']==39
for row in compile['sourceInputs']:
 inputPath=Path(row['path']);assert sha(inputPath)==row['sha256Bytes']
 relative=inputPath.relative_to(HERE/'runs/compile-08/source-inputs')
 assert sha(HERE/relative)==row['sha256Bytes'],relative
for name in ['pins-before.json','pins-after.json']:
 pins=json.loads(read(HERE/'runs/compile-08'/name))
 for row in pins['runtime']+pins['tools']+pins['sources']:assert sha(row['path'])==row['sha256Bytes']
assert sha(SNAP/'manifest.json')=='6252d7e16a2f413d48de0265dc7debb315b2eaefca0b58cde0d34d9258feb362'
assert sha(SNAP/'ordered-runtime-cp.json')=='0785ee7cbe88b2615d9193882310a1fe36c368c71f9180b2a418a17136ab8440'
newFiles=[
 'desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopPlaybackPublication.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/cast/DesktopCastMediaPublication.kt',
 'desktop/src/main/java/su/litvak/chromecast/api/v2/DesktopCastPublication.java',
 'desktop/src/main/java/su/litvak/chromecast/api/v2/DesktopCastWriter.java',
 'desktop/tools/extract-upstream-download-transport.py']
install=dict(status='FROZEN_PREPARED_SOURCE_ONLY',existingBackend84MustInstallFirst=True,newFiles=[dict(path=p,source='prepared/'+p,sha256Bytes=sha(HERE/'prepared'/p)) for p in newFiles],
 exactSequentialHunks='local-hunks.json',existingManualFamilies=18,doNotModifyUpstreamAppRowWithProducerRequired=True,
 producerHunks='producer-hunks.json',buildRegistrationProposal='build-registration-hunks.json',sameIdentityRegistryMerge='registry-delta.json',castForkRecordOnlyUpdate='cast-provenance-hunks.json',
 syntheticCallerHunks='synthetic-migration/local-hunks.json',nativeSmokeOwnCallerMandatory=True,existingTestsAssertionsUnchanged=True,
 backend84AuthCopyProofOnly=True,wholeFilesProofOnly=True,unchangedPlaybackStreamHeadersProofOnly=True,
 shellSourceBase='Root-saved actual42 before Root43 four lifetime hunks; semantic exact-hunk merge mandatory',RootCompileTestKotlinRequired=True,
 prospectiveCompileOnly=True,productRuntimeAcceptance=False,newDependencyOrRuntimeArtifact=False)
save(HERE/'install-recipe.json',install)
rows=[]
for p in sorted(HERE.rglob('*')):
 if not p.is_file() or p.name=='frozen-handoff.json' or '__pycache__' in p.parts:continue
 rows.append(dict(path=p.relative_to(HERE).as_posix(),bytes=safe(p).stat().st_size,sha256Bytes=sha(p)))
manifest=dict(status='FROZEN_PREPARED',lane=str(HERE),originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 actualBase='actual43 immutable 97CP; prospective production source family overrides are explicit',
 productSnapshotManifestSHA=sha(SNAP/'manifest.json'),orderedRuntimeCpSHA=sha(SNAP/'ordered-runtime-cp.json'),
 originalBackend84FrozenManifestSHA='b990563ad4ec9cf4eca8ea495377fe0496073209684df8d8e922cbe8a08322a3',backend84ArtifactsVerifiedUnchanged=84,
 rootIntegrationSHA=sha(HERE/'ROOT-INTEGRATION.md'),sourceChecksSHA=sha(HERE/'source-checks.json'),installRecipeSHA=sha(HERE/'install-recipe.json'),
 exactLocalHunksSHA=sha(HERE/'local-hunks.json'),syntheticCallerHunksSHA=sha(HERE/'synthetic-migration/local-hunks.json'),
 producerHunksSHA=sha(HERE/'producer-hunks.json'),registryDeltaSHA=sha(HERE/'registry-delta.json'),
 buildRegistrationHunksSHA=sha(HERE/'build-registration-hunks.json'),castProvenanceHunksSHA=sha(HERE/'cast-provenance-hunks.json'),
 compileResultSHA=sha(HERE/'runs/compile-08/compile-result.json'),acceptedFixtureSHA=sha(HERE/'runs/fixture-05/accepted.json'),
 candidateJarSHA=compile['jarSha256'],prospectiveClassCount=len(compile['classes']),explicitProductionClassIntersections=len(compile['productionClassIntersections']),
 focusedProof=dict(groups=4,assertions=39,codeSourceClassByteIdentities=len(accepted['origins']),fixtureProductClassIntersections=0,sourceInputs=len(compile['sourceInputs']),allCompilerRuntimePinsBeforeAfter=True,nativeDLL=False,socket=False,HWND=False,GUI=False),
 sourceInstall=dict(newManualFiles=4,newSoleDownloadProducer=1,existingSoleCastProducerHunks=1,existingManualFamilies=18,nativeSmokeAdditionalExistingFile=1,testHarnessFiles=12,originalTaskDTOSchemaUnchanged=True,newHTTPClient=False,newStore=False,newPlayer=False,newCacheActor=False),
 pending=['Root serial installation/whole classes/compileTestKotlin','New immutable actual product zero-override final-publication proof','Physical native media playing/recovery, LAN receiver/ACK/cast proxy sockets, real Profile/PGC UI and Root shutdown acceptance'],
 sourceOnlyReviewLimit='Mpv requested-source snapshots and actual admission callback tested; task-only Listen ended stimulus is not native decoding proof',
 history=['compile01 OkHttp5 Call/old Jar overload errors retained','fixture01 exception import error retained','fixture02 copy-default ABI linkage error retained','synthetic prepare01 duplicate constructor-count error retained'],
 MainEdited=False,sharedGradleExecuted=False,externalHTTP=False,existingAccountRead=False,artifacts=rows,artifactCount=len(rows))
save(HERE/'frozen-handoff.json',manifest)
print(json.dumps(dict(manifest=str(HERE/'frozen-handoff.json'),sha256=sha(HERE/'frozen-handoff.json'),artifacts=len(rows),candidateJarSHA=compile['jarSha256'],acceptedFixtureSHA=manifest['acceptedFixtureSHA']),indent=2))
