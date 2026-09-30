from pathlib import Path
import hashlib,json,os,subprocess,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if(p/'.git').exists())
LANE=REPO/'desktop/.local/dynamic-follow-observer-parity';OUT=REPO/'desktop/verification/dynamic-follow'
assert not OUT.exists(),'New immutable acceptance only'
def ext(p):
 value=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(value if value.startswith(prefix)else prefix+value)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def read(p):return json.loads(ext(p).read_bytes())
def save(p,v):ext(p).parent.mkdir(parents=True,exist_ok=True);ext(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
def files(root):
 for base,dirs,names in os.walk(ext(root)):
  for name in names:
   value=str(Path(base)/name);prefix=chr(92)*2+'?'+chr(92)
   yield Path(value[len(prefix):]if value.startswith(prefix)else value)
raw=[]
def copy(source,destination):
 assert source.suffix.lower()in ['.kt','.json','.args','.log','.py','.patch','.md','.xml'],str(source)
 target=OUT/destination;ext(target).parent.mkdir(parents=True,exist_ok=True);ext(target).write_bytes(ext(source).read_bytes())
 assert sha(source)==sha(target)
 raw.append(dict(path=target.relative_to(REPO).as_posix(),sha256Bytes=sha(target),bytes=ext(target).stat().st_size))
handoff=LANE/'frozen-handoff-v2.json';assert sha(handoff)=='fe16610024638f562846e409c549f72542cfb79abed50bf678a2998dbf3d0635'
prepared=read(handoff);assert len(prepared['files'])==237
for row in prepared['files']:
 source=LANE/row['path'];assert sha(source)==row['sha256Bytes']and ext(source).stat().st_size==row['bytes']
 copy(source,Path('prepared-history')/row['path'])
copy(handoff,Path('prepared-history/frozen-handoff-v2.json'))
for source in sorted(HERE.iterdir()):
 if source.is_file()and source.suffix.lower()in ['.py','.json','.log']:copy(source,Path('root-recipes')/source.name)
for directory in ['main-product-snapshot-01','follow-proof01']:
 for source in sorted(files(HERE/directory)):
  if source.suffix.lower()in ['.json','.kt','.args','.log','.xml']:copy(source,Path('actual-main')/source.relative_to(HERE))
snapshot=HERE/'main-product-snapshot-01/manifest.json';cp=snapshot.with_name('ordered-runtime-cp.json')
assert sha(snapshot)=='d3fd2de6caf3302a65547ef29350ba1a158dead69d84c9b680131db88f7f62cd'
assert sha(cp)=='2dc490f5604c2029bbee4f83fb1e20ee3ad83607b18c5a6a79505d90f941e629'
snap=read(snapshot);proof=read(HERE/'follow-proof01/accepted-evidence.json');contract=read(HERE/'source-contract-result.json')
assert proof['passed']and proof['assertions']==92 and proof['caseCount']==18 and proof['productionOverrides']==0
assert contract['passed']and contract['checkCount']==35 and contract['MainIntegrated']
assert len(snap['sourceFiles'])==383 and len(snap['generatedProductFiles'])==703
assert snap['mainCompile']['focusedTests']==36
for report in snap['testReports']:
 e=ET.parse(ext(report['path'])).getroot();assert all(int(e.get(k))==0 for k in ['failures','errors','skipped'])
record=dict(upstreamTag='v0.2.3-alpha.9',upstreamCommit='fcf84853b287662e8a9129ea0d38576c36522a34',
 windowsBaseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),MainIntegrated=True,
 actualMainSnapshotSha256Bytes=sha(snapshot),actualOrderedRuntimeCpSha256Bytes=sha(cp),orderedRuntimeArtifacts=89,
 actualMainSourceFiles=383,generatedSourceFiles=703,sourceRegistryCount=588,originalFollowInventoryEntries=3,
 newSourceIdentities=0,newDependencies=0,preparedKotlinSourcesInstalled=7,preparedProducersInstalled=3,
 MainFocusedJUnit=dict(tests=36,failures=0,errors=0,skipped=0),originalSourceExtractionTests=12,originalSourceContracts=35,
 actualMainFollowObserver=dict(assertions=92,scenarios=18,productionOverrides=0,loadedClassIdentities=13,
  fixtureSourceSha256Bytes=proof['fixtureSourceSha256Bytes'],resultSha256Bytes=proof['resultSha256Bytes'],
  acceptedEvidenceSha256Bytes=sha(HERE/'follow-proof01/accepted-evidence.json'),actualRetrofitTerminalTransport=True,externalHttp=False),
 acceptedChanges=['Original success-only FollowStateChange with buffer32 and replay0; tryEmit false preserves confirmed server success',
  'Existing Repository owns one credential-epoch tagged event flow; current SessionStore guards request and cookie authority',
  'Root Compose epoch-keyed LaunchedEffect collects on existing model owner and cancels with the existing Session/Registry lifetime',
  'Original unfollow reducers apply to retained home All/video/pgc/article and selected-UP owners',
  'Existing followings/live sidebar owners remove the author; selected-UP cancellation and original logical-tab behavior preserved',
  'Follow success invalidates original followings TTL/full flag and requests original queued hydration',
  'Old home, followings, live and UP responses cannot replace a confirmed follow change; original cursor checkpoints restored under existing mutex',
  'Current All is the only cache writer; cold disk readback matches reduced All and not-interested IDs remain unchanged',
  'Existing Space/Topic/detail card mutation owners remain outside the original home-only follow observer scope'],
 RootEpochEffectCompiled=True,RootShellExecuted=False,MainWindowOrNativeChooserExecuted=False,
 realAccountAccepted=False,externalServiceAccepted=False,packageAccepted=False,newDesktopDeployment=False,
 deployedDesktopVersion='0.2.406.5',fullFeatureParityVerified=False,weightedReviewedProgressPercent=69.7,progressEstimateChanged=False,
 remaining=['Actual live-account follow UI and Root Shell regression on the new package',
  'Complete original detail container, primary comment scrolling and thread integration',
  'Native gallery/MotionPhoto/EXIF/share, remaining original feature parity and final Desktop deployment'],
 historicalBoundaries=['Prepared runs declare 102 candidate class overlaps; accepted new actual Main proof has zero overrides',
  'Prepared07 queue-full notification failure semantics are rejected history; Prepared08 and actual Main preserve original server success',
  'Prepared first raw manifest missed long-path artifacts; immutable v2 contains complete extended-path enumeration with prior bytes retained'])
integrationPaths=['desktop/build.gradle.kts','desktop/upstream-sources.json','desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityDynamicScreens.kt']
installation=read(HERE/'source-installation.json')
integrationPaths +=[r['path'].removeprefix('candidate/')for r in prepared['files']if r['path'].startswith('candidate/desktop/src/main/kotlin/')]
integrationPaths +=[r['target']for r in read(LANE/'root-integration-checklist.json')['producerInstall']]
assert len(integrationPaths)==14
manifest=dict(**record,rawEvidence=dict(files=sorted(raw,key=lambda r:r['path']),fileCount=len(raw),
 preparedHandoffManifestSha256Bytes=sha(handoff),binariesJarsDllsExePrivateStoresIncluded=False),
 integrationPoints=[dict(path=p,sha256Bytes=sha(REPO/p),sha256Lf=hashlib.sha256(ext(REPO/p).read_bytes().replace(b'\r\n',b'\n')).hexdigest())for p in integrationPaths])
target=OUT/'artifact-manifest.json';save(target,manifest)
integration=REPO/'desktop/verification/source9-dynamic-follow-integration.json'
save(integration,dict(**record,rawArtifacts=dict(path=target.relative_to(REPO).as_posix(),sha256Bytes=sha(target),files=len(raw))))
for row in raw:assert sha(REPO/row['path'])==row['sha256Bytes']and ext(REPO/row['path']).stat().st_size==row['bytes']
receipt=dict(passed=True,rawFiles=len(raw),manifestSha256Bytes=sha(target),integrationSha256Bytes=sha(integration),
 sourcePins=383,MainChecks=92,MainScenarios=18,JUnit=36,sourceExtractionTests=12,sourceContracts=35)
save(HERE/'freeze-receipt.json',receipt);print(json.dumps(receipt))
