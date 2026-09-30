from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def ext(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,value):ext(p).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
review=REPO/'desktop/.local/dynamic-detail-reply-parity/protocol/follow-observer-review08/review.json'
assert sha(review)=='51e75b109536cc217881c38b3fe88a4c1abcf4b7c048b8dea7d3cc0898f4422f'
assert not(HERE/'independent-review.json').exists()
(HERE/'independent-review.json').write_bytes(ext(review).read_bytes())
assert sha(HERE/'runs/08/accepted-evidence.json')=='5eed34a21fffbb1e82f68960072426d69ba2a6188642a4570e4df5ba8615d108'
accepted=json.loads((HERE/'runs/08/accepted-evidence.json').read_bytes());assert accepted['passed'] and accepted['assertions']==92 and accepted['caseCount']==18
assert sha(HERE/'runs/08/result.json')==accepted['resultSha256Bytes']
assert sha(HERE/'runs/08/verified-loaded-class-identities.json')==accepted['loadedClassIdentitiesSha256Bytes']
inputs=json.loads((HERE/'runs/08/input-pins.json').read_bytes())
for row in inputs['orderedDependencies']:assert sha(row['path'])==row['sha256Bytes']
for row in inputs['candidateSources']:assert sha(HERE/row['path'])==row['sha256Bytes']
assert sha(HERE/'FollowObserverFixture.kt')==inputs['fixtureSourceSha256Bytes']
tools=json.loads((HERE/'runs/08/compiler-tool-pins.json').read_bytes())
for row in tools:assert sha(row['path'])==row['sha256Bytes']
assert sha(HERE/'runs/08/candidate.jar')==accepted['candidateJarSha256Bytes']
assert sha(HERE/'runs/08/fixture.jar')==accepted['fixtureJarSha256Bytes']
sourceAudit=json.loads((HERE/'source-contract-result.json').read_bytes());assert sourceAudit['passed'] and sourceAudit['checkCount']==32
for row in json.loads((HERE/'baseline-pins.json').read_bytes()):
 if row['path'].startswith('desktop/src/main/'):
  assert sha(REPO/row['path'])==row['sha256Bytes'],row['path']
producer=json.loads((HERE/'prepared-tool-receipt.json').read_bytes());assert producer['passed']
for row in producer['producerInputs']:assert sha(REPO/row['path'])==row['sha256Bytes'];assert sha(HERE/'prepared-tools'/row['path'])==row['preparedSha256Bytes']
for row in producer['generatedChecks']:assert sha(HERE/row['path'])==row['sha256Bytes']
install=[dict(source=row['path'],target=row['path'].removeprefix('candidate/'),sha256Bytes=row['sha256Bytes']) for row in inputs['candidateSources'] if row['path'].startswith('candidate/desktop/')]
toolInstall=[dict(source=str(p.relative_to(HERE)),target='desktop/tools/'+p.name,sha256Bytes=sha(p)) for p in sorted((HERE/'prepared-tools/desktop/tools').glob('*.py'))]
checklist=dict(MainIntegrated=False,DesktopDeployed=False,preparedSourceInstall=install,producerInstall=toolInstall,sourceRegistryFeature='dynamic-follow-observer-parity',generatedRoot='generated/dynamic-follow',rootEpochEffect='LaunchedEffect(dynamicCardSession, dynamicCardRegistry) { dynamicCardSession.observeFollowStateChanges(dynamicCardRegistry) }',modelOwnerThread='existing Root Compose owner; do not dispatch observer to IO',allCacheSeam='CommunityDynamicFeedReady onAllTimelineChanged={rows->if(cardRegistry.isCurrentAll(model))cache.saveTimeline(rows)}',cacheOwner='existing Root DesktopDynamicCache actor; Registry calls currentAll.persistCurrentItems()',revalidateActualMain='new frozen Main/89 ordered CP with zero prepared candidate class overrides; existing timeline/tabs/Registry checks and focused source/extraction contracts',actualReceiverAccountPackageAcceptance=False)
save(HERE/'root-integration-checklist.json',checklist)
receipt=dict(preparedFollowObserverPassed=True,MainIntegrated=False,DesktopDeployed=False,actualRootShellExecuted=False,actualAccountHTTP=False,HWND=False,package=False,finalRun='runs/08',acceptedEvidenceSha256Bytes=sha(HERE/'runs/08/accepted-evidence.json'),assertions=92,cases=18,sourceContracts=32,sourceContractSha256Bytes=sha(HERE/'source-contract-result.json'),preparedToolReceiptSha256Bytes=sha(HERE/'prepared-tool-receipt.json'),independentReviewSha256Bytes=sha(HERE/'independent-review.json'),rootIntegrationChecklistSha256Bytes=sha(HERE/'root-integration-checklist.json'),actualMainBaselineSha256Bytes=accepted['actualBaselineSha256Bytes'],orderedCpSha256Bytes=accepted['orderedCpSha256Bytes'],candidateClassOverrides=accepted['preparedCandidateOverrides'],fixtureProductClassOverrides=0,loadedClassPins=13,originalQueue='buffer32/replay0/drop without changing server-success result; tested actual Social one POST and caller success at saturation',historical07='rejected result contract; local queue-full failure could trigger UI rollback of server-confirmed result')
save(HERE/'review-final.json',receipt)
allowed={'.kt','.py','.json','.md','.patch','.args','.log','.txt'}
files=[]
for p in sorted(HERE.rglob('*')):
 if not p.is_file() or p.name=='frozen-handoff.json' or p.suffix not in allowed or '__pycache__' in p.parts:continue
 files.append(dict(path=p.relative_to(HERE).as_posix(),sha256Bytes=sha(p),bytes=ext(p).stat().st_size))
manifest=dict(frozen=True,kind='prepared-original-dynamic-follow-observer-parity',preparedFollowObserverPassed=True,MainIntegrated=False,DesktopDeployed=False,finalAcceptedRun='runs/08',actualMainBaselineSha256Bytes=accepted['actualBaselineSha256Bytes'],orderedRuntimeCpSha256Bytes=accepted['orderedCpSha256Bytes'],reviewFinalSha256Bytes=sha(HERE/'review-final.json'),rootIntegrationChecklistSha256Bytes=sha(HERE/'root-integration-checklist.json'),files=files,exclusions=['JAR/class binary outputs','private task synthetic session/store/account files','compiler/cache binaries'],historyAcceptedAsCurrent=False)
assert not(HERE/'frozen-handoff.json').exists();save(HERE/'frozen-handoff.json',manifest)
for row in files:assert sha(HERE/row['path'])==row['sha256Bytes'] and ext(HERE/row['path']).stat().st_size==row['bytes']
print(json.dumps(dict(rawFiles=len(files),frozenHandoffSha256Bytes=sha(HERE/'frozen-handoff.json'),reviewFinalSha256Bytes=sha(HERE/'review-final.json'),integrationChecklistSha256Bytes=sha(HERE/'root-integration-checklist.json')),indent=2))
