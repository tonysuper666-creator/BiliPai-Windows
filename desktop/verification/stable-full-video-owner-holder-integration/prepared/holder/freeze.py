from pathlib import Path
import hashlib,json
P=Path(__file__).resolve().parent
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
compile=json.loads(read(P/'runs/holder-05/compile-result.json'));symbols=json.loads(read(P/'symbol-audit.json'));checks=json.loads(read(P/'source-checks.json'))
assert compile['passed'] and symbols['passed'] and checks['passed']
manual=sorted(wide(P/'install-packet/manual').rglob('*.kt'))
whitelist=[dict(path='tools/extract-upstream-video-detail-holder.py',sha256Bytes=sha(P/'install-packet/tools/extract-upstream-video-detail-holder.py'),operation='install sole source producer')]
whitelist += [dict(path=p.relative_to(wide(P/'install-packet')).as_posix(),sha256Bytes=sha(p),operation='install source') for p in manual]
whitelist += [dict(path='registry-delta.json',sha256Bytes=sha(P/'install-packet/registry-delta.json'),operation='source identity/feature merge only'),dict(path='local-hunks/context-remove.json',sha256Bytes=sha(P/'install-packet/local-hunks/context-remove.json'),operation='three exact hunks only; whole reference .kt files are not installers')]
save(P/'install-packet/install-whitelist.json',dict(sourceOnly=True,installJar=False,files=whitelist,generatedSources=dict(production=32,directSyncedOnce=21,standalone=53),referenceOnlyDirectories=['standalone-generated','local-hunks/context-remove-base.kt','local-hunks/context-remove-candidate-reference.kt'],runtimeFactoriesRequired='ROOT-INTEGRATION.md'))
artifacts=[]
def register(p,role):
 data=read(p);artifacts.append(dict(path=p.relative_to(wide(P)) .as_posix(),sha256Bytes=hashlib.sha256(data).hexdigest(),bytes=len(data),role=role))
for p in sorted(wide(P).iterdir()):
 if p.is_file() and p.suffix in['.py','.json'] and p.name not in['frozen-handoff.json']:register(p,'source auditor/producer or source-stage evidence')
for dirname,role in [('install-packet','source installation or reference evidence (whitelist controls installation)'),('original-stable','pinned original source'),('producer-audit','fresh producer default/standalone byte evidence'),('local-hunks','exact shared family hunk evidence')]:
 for p in sorted(wide(P/dirname).rglob('*')):
  if p.is_file() and p.suffix in['.py','.kt','.json','.md']:register(p,role)
for dirname in ['holder-01','holder-02','holder-03','holder-04','holder-05']:
 run=P/'runs'/dirname
 for name in ['compile.log','compile.args','compile-result.json','pins-before.json','pins-after.json']:
  if wide(run/name).is_file():register(wide(run/name),'final successful compile'if dirname=='holder-05'else'historical actual failed compile')
 if dirname=='holder-05':
  for p in sorted(wide(run/'source-inputs').rglob('*.kt')):register(p,'actual candidate compile input; context source is declared compile-only')
manifest=dict(frozen=True,status='SOURCE_READY_COMPILED_PROSPECTIVE',sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',scope='Complete original composable VideoDetailScreenStateHolder/Composer and renderer closure, four required same-owner Windows seams; no Root mount/runtime claim',originalHolder=dict(path='app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt',lines=5464,sha256LF='4fe3b94adbd3a4f9eea165e68b8efdee0f06d74c78bc3de9269b2fa067ba8db3'),counts=dict(originalIdentities=52,generatedOutputs=53,productionSelectedOutputs=32,directOriginalOutputs=21,manualInputs=4,compileInputs=58,compiledClasses=441),compile=dict(run='runs/holder-05',actualSnapshot=65,orderedEntries=101,candidateJarSHA256Bytes=compile['candidateJarSha256Bytes'],explicitProspectiveVMModuleSHA256Bytes='e310cb71a3ccd2ae2fdc81f69fa8da8dbf0b06c4e28e11209bdf65765b1c7f67',compileOnlyContextExactHunks=3,sourceIdentityAndOutputAudits=True,methodAndFieldIntersectionZero=True,invalidJvmMethodNames=0),runtimeAcceptance=False,allCandidateClassesRuntimeLoaded=False,sharedSourcesChanged=False,gradleRun=False,accountOrHttpOrGuiOrNativeRun=False,pending=['Root same-entry five-domain owner factory and actual full PlaybackVM authority switch','Actual sameWindow/presentation/PiP/wake/brightness/edge-to-edge capability consumer','Owned Story/analytics/diagnostic export-and-cache setter binding','Existing Portrait prefetch real request/pageGeneration/Job byte-cache receipt remains separate pending design'],artifacts=sorted(artifacts,key=lambda r:r['path']),rawArtifactCount=len(artifacts),excluded=['*.jar','*.class','__pycache__','historical source-input duplicates (01–04), retained privately with source hashes in raw pins','task dependency JARs and actual product runtime binaries; SHA references retained'],immutableHistoricalFailures=['runs/holder-01','runs/holder-02','runs/holder-03','runs/holder-04'],installationWhitelist='install-packet/install-whitelist.json',installationContract='install-packet/ROOT-INTEGRATION.md')
save(P/'frozen-handoff.json',manifest)
print(json.dumps(dict(path=str(P/'frozen-handoff.json'),sha256Bytes=sha(P/'frozen-handoff.json'),rawArtifacts=len(artifacts),counts=manifest['counts'])))
