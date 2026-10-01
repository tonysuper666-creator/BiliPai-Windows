from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
target=HERE/'frozen-handoff.json';assert not target.exists()
compile=json.loads(read(HERE/'runs/compile-04/compile-result.json'));assert compile['status']=='PASS'
accepted=json.loads(read(HERE/'runs/fixture-03/accepted.json'));assert accepted['status']=='PASS' and accepted['groups']==4 and accepted['assertions']==47
audit=json.loads(read(HERE/'source-checks.json'));assert audit['status']=='PASS'
for row in compile['sourceInputs']:
 old=Path(row['path']);relative=old.relative_to(HERE/'runs/compile-04/source-inputs')
 assert sha(HERE/relative)==row['sha256Bytes'],relative
artifacts=[]
for p in sorted(HERE.rglob('*')):
 if p.is_file() and '__pycache__' not in p.parts:
  artifacts.append(dict(path=p.relative_to(HERE).as_posix(),bytes=safe(p).stat().st_size,sha256Bytes=sha(p)))
manifest=dict(status='FROZEN_PREPARED',lane=str(HERE),targetStableCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 actualBase='actual39 immutable 97-entry runtime; five explicit existing platform source families overridden in prospective proof',
 productSnapshotManifestSHA=compile['snapshotManifest'],orderedRuntimeCpSHA=compile['CP'],
 sourceContractSHA=sha(HERE/'source-contract.json'),sourceInventorySHA=sha(HERE/'source-inventory.json'),
 exactLocalHunksSHA=sha(HERE/'local-hunks.json'),rootIntegrationSHA=sha(HERE/'ROOT-INTEGRATION.md'),
 sourceChecksSHA=sha(HERE/'source-checks.json'),compileResultSHA=sha(HERE/'runs/compile-04/compile-result.json'),
 acceptedFixtureSHA=sha(HERE/'runs/fixture-03/accepted.json'),candidateJarSHA=compile['jarSha256'],
 sourceInstall=dict(newManualSources=1,existingPlatformFileFamilies=5,proofWholeFilesNotInstallPayload=True,mandatoryRootFinalSourceReceiptConsumers=True),
 sourceAudit='Five whole-file exact inverse checks plus full original private isolated CookieJar body; only declaration name/visibility adaptation',
 fixture=dict(groups=4,assertions=47,loadedCodeSourceAndClassByteChecks=9,transport='actual Retrofit and WBI with task-only terminating application interceptor; explicit same-Store CookieJar calls; zero sockets'),
 pending=['Root Profile ports and final native/recovery/download/cast/video-audio authorization admission consumers','Whole product build and actual immutable runtime proof after installation','Real network Bridge/CookieJar path, native playback/Profile UI/EXE acceptance','Complete additional original VideoRepository app/TV premium branches are outside this account-only slice'],
 history=['compile-01 missing explicit Job','fixture-01 proof compiler omitted Compose plugin/$stable ABI','fixture-02 incorrect fixture legacy PGC URL expectation'],
 noSharedSourceEdits=True,noGradle=True,noExternalHTTP=True,noUserAccountRead=True,noGUIOrHWND=True,actualProductAcceptance=False,
 artifacts=artifacts,artifactCount=len(artifacts))
safe(target).write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print('FROZEN '+str(len(artifacts))+' artifacts; manifest '+sha(target))
