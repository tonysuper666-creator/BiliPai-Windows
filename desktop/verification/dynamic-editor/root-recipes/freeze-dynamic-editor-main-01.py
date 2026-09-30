"""Freeze original editor integration evidence without account data or binaries."""
from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parent.parent;DEST=REPO/'desktop/verification/dynamic-editor'
assert not DEST.exists()
def ext(p):
 v=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(v if v.startswith(prefix) else prefix+v)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def load(p):return json.loads(ext(p).read_bytes())
def save(p,v):ext(p.parent).mkdir(parents=True,exist_ok=True);ext(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
allowed={'.json','.kt','.py','.md','.args','.log','.png','.xml','.kts','.patch','.txt'};rows=[]
def copy(p,relative):
 p=Path(p);assert p.suffix.lower() in allowed
 target=DEST/relative;assert not ext(target).exists();ext(target.parent).mkdir(parents=True,exist_ok=True);ext(target).write_bytes(ext(p).read_bytes())
 assert sha(target)==sha(p);rows.append(dict(path=target.relative_to(REPO).as_posix(),sourcePath=str(p),sha256Bytes=sha(p),bytes=ext(p).stat().st_size))
def tree(p,relative):
 for f in sorted(ext(p).rglob('*')):
  if f.is_file() and f.suffix.lower() in allowed and 'task-owned-global-store' not in f.parts:
   copy(f,Path(relative)/f.relative_to(ext(p)))
snapshot=HERE/'dynamic-editor-main-product-snapshot-01/manifest.json';assert sha(snapshot)=='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062'
snap=load(snapshot)
for row in snap['sourceFiles']:
 assert hashlib.sha256(ext(REPO/row['path']).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==row['sha256Lf'],row['path']
prepared=HERE/'dynamic-editor-detail-parity/final-production-safe';manifest=prepared/'frozen-handoff.json';assert sha(manifest)=='15465a9bf04fe3ac59839bfcf3717a3c35ac2ae45e4073d8452a29e2064c65a2'
p=load(manifest)
for row in p['artifacts']:assert sha(prepared/row['path'])==row['sha256Bytes'],row['path']
assert len(p['artifacts'])==250
accepted=HERE/'dynamic-editor-actual-main-proof-03';e=load(accepted/'run-evidence.json');assert e['passed'] and e['zeroProductOverrides']
rootUi=load(accepted/'runs/01/root-ui-result.json');assert len(rootUi)==4 and sum(c['pointers'] for c in rootUi)==24
oldUi=load(accepted/'runs/01/ui-result.json');assert len(oldUi)==2 and sum(c['pointers'] for c in oldUi)==54
api=load(accepted/'runs/01/editor-protocol-result.json');assert api['passed'] and len(api['cases'])==13
conformance=load(HERE/'dynamic-editor-root-conformance-01.json');assert conformance['passed'] and len(conformance['originalSourceIdentities'])==24
copy(snapshot,'actual-main/manifest.json');copy(snapshot.with_name('ordered-runtime-cp.json'),'actual-main/ordered-runtime-cp.json')
copy(manifest,'prepared-history/frozen-handoff.json')
for row in p['artifacts']:
 path=prepared/row['path']
 if path.suffix.lower() in allowed:copy(path,Path('prepared-history')/row['path'])
tree(prepared/'protocol-review/top-level-uniqueness-review','prepared-single-owner-review')
tree(HERE/'dynamic-editor-actual-main-proof-01','actual-history/01')
tree(HERE/'dynamic-editor-actual-main-proof-02','actual-history/02')
tree(accepted,'actual-accepted/03')
for name in ['dynamic-editor-root-main-compile-01.log','dynamic-editor-root-main-compile-02.log','dynamic-editor-root-source-install-01.json','dynamic-editor-root-conformance-01.json','dynamic-editor-original-timeline-conformance-01.log']:
 copy(HERE/name,Path('root-evidence')/name)
copy(REPO/'desktop/build/generated/dynamic-editor-protocol/verification.json','root-evidence/selected-member-verification.json')
for name in ['snapshot-dynamic-editor-main-01.py','verify-dynamic-editor-root-01.py','freeze-dynamic-editor-main-01.py']:
 copy(HERE/name,Path('root-recipes')/name)
save(DEST/'history.json',dict(prepared250VerifiedUnchanged=True,preparedMainOverride='Historical sole Operations override; never accepted as actual Main.',actualMainOverrides=0,
 sourceInstall01='Historical payload installation before Root mounting and compilation; flags remain false in that original receipt.',
 mainCompile01='Failed new Root compile: producer try fragment boundary, refresh helper omissions and retired composing label. Fixed in source before successful compile02.',
 mainCompile02='Actual owning-module compile/processResources passed in31s; this is the immutable Main basis.',
 actualFixturePrepare01='Task-only Windows path quoting syntax failure, preserved under prepare-history; no Main execution.',
 actualUi01And02='Root fixture used a22-character title; original20-character guard correctly retained the old title. Failed observations retained; no product change for this correction.',
 actualAccepted03='Original20-character guard asserted, then valid13-character title changed and actual signed edit passed in four theme cells. Unchanged UI/policy/protocol runs from01 retained with matching fixture hashes; only corrected Root UI rerun.',
 excluded=['JAR/class/native binaries','Task-private synthetic session and global-preference stores'],nativeChooserAccepted=False,realAccountAccepted=False,packageAccepted=False))
rows.append(dict(path=(DEST/'history.json').relative_to(REPO).as_posix(),sourcePath='Root verification history',sha256Bytes=sha(DEST/'history.json'),bytes=ext(DEST/'history.json').stat().st_size))
save(DEST/'raw-artifact-identities.json',dict(frozen=True,selectedNonbinaryEvidence=True,artifacts=rows,artifactCount=len(rows),artifactBytes=sum(r['bytes']for r in rows)))
receipt=dict(upstreamTag='v0.2.3-alpha.9',upstreamCommit='fcf84853b287662e8a9129ea0d38576c36522a34',windowsBaseCommit='8b3ef8be95888c9a9266220fada36aba34eb625c',
 scope='Actual Main original native-theme dynamic composer and editor operations, Root modal/epoch and post-publish refresh/verification bindings. Full Detail/liquid/native chooser/live-account/package acceptance remain pending.',
 actualMainSnapshotSha256Bytes=sha(snapshot),actualOrderedRuntimeCpSha256Bytes=sha(snapshot.with_name('ordered-runtime-cp.json')),actualRuntimeArtifacts=89,actualSourceFiles=len(snap['sourceFiles']),
 preparedManifestSha256Bytes=sha(manifest),preparedArtifactsVerified=250,originalTagIdentitiesVerified=24,productionSelectedUiFiles=8,sixOriginalDirectSourcesHaveOneProducer=True,
 sameExistingOperationsProducer=True,originalApiModelsWbiAndDraftSchemas=True,sharedSegmentedControlAndEmotesHaveOneOwner=True,sourceBodyConformanceTests=5,
 actualOriginalUi=dict(cells=2,pointerPairs=54,screenshots=10,fixturePlatformCallbacks=True),actualRootHostUi=dict(cells=4,pointerPairs=24,screenshots=8,actualMainPublishEditRequests=True,title20CharacterGuard=True),
 actualOriginalProtocolAndRootLifetime=dict(groupedCases=13,terminalApplicationResponsesOnly=True,BridgeCookiePersistenceAccepted=False,actualMainOperations=True,AUTHJarVsGUESTJarChecked=True,originalFiveSecondDelay=True,verificationSurvivesModalDismissal=True,sameMIDNewEpochRetirement=True),
 productionClassOverrides=0,RootShellMounted=True,cardDefaultEditRoute=True,dynamicAndTopicPublishRoutesMounted=True,currentFeedsDetailsAndSpaceRefreshMounted=True,
 nativeChooser=dict(compiled=True,parentIsExistingRootWindow=True,actualNativeDialogAcceptance=False),
 HWND=False,realAccount=False,persistentCredentials=False,packageAccepted=False,newDesktopDeployment=False,deployedDesktopVersion='0.2.406.5',fullFeatureParityVerified=False,fullReleaseGatePassed=False,
 nativeShareStillPendingOwnerDispatchCorrection=True,progressIsManuallyWeightedNotSourceCounts=True,progressPresentation='About70%',rawEvidenceManifestSha256Bytes=sha(DEST/'raw-artifact-identities.json'),rawArtifacts=len(rows))
save(REPO/'desktop/verification/source9-dynamic-editor-integration.json',receipt)
base=load(REPO/'desktop/verification/parity-progress-dynamic-full-card-delta.json');assessment=base['assessment'].copy();assessment.update(creditedMainPoints=96.15,unfinishedOrUnverifiedPoints=41.35,weightedReviewedProgressPercent=69.7,presentation='About70%, manual assessment against all original features and behavior.')
save(REPO/'desktop/verification/parity-progress-dynamic-editor-delta.json',dict(upstreamTag=receipt['upstreamTag'],upstreamCommit=receipt['upstreamCommit'],windowsBaseCommit=receipt['windowsBaseCommit'],previousDelta='desktop/verification/parity-progress-dynamic-full-card-delta.json',
 scope=base['scope'],changes=[dict(group='dynamic',weight=4,previousMainCredit=3.25,currentMainCredit=3.4,remainingCredit=.6,basis='Actual Main original editor/publish requests and modal/session integration accepted with four theme cells and original post-publish verification; native chooser, full liquid and live services remain unverified.',remaining=['Complete original DynamicDetail/Reply and sub-reply transport/UI','Original full liquid dock and segmented rendering','Actual native image/calendar chooser and live authorized service mutation','Original follow-event/navigation/motion-photo/system image share parity','Packaged EXE and real account regression'])],assessment=assessment,evidence='desktop/verification/source9-dynamic-editor-integration.json',newDesktopDeployment=False))
print(json.dumps(dict(rawArtifacts=len(rows),rawBytes=sum(r['bytes']for r in rows),rawManifestSha256Bytes=receipt['rawEvidenceManifestSha256Bytes'],mainSnapshotSha256Bytes=sha(snapshot),progressPercent=69.7)))