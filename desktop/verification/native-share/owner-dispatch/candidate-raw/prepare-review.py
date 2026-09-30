from pathlib import Path
import hashlib,json,datetime
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
def ext(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def pin(p):return {'path':str(p),'sha256Bytes':sha(p),'bytes':ext(p).stat().st_size}
def save(p,v):
 assert not p.exists(),str(p)
 p.write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
def copy(source,target):
 assert not target.exists(),str(target)
 target.write_bytes(ext(source).read_bytes());return pin(target)
inputs=HERE/'inputs';assert not inputs.exists();inputs.mkdir()
baseline=HERE.parent/'native-share-main-product-snapshot-01'
expected={'manifest.json':'4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f',
          'ordered-runtime-cp.json':'a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc'}
for name,expected_hash in expected.items():assert sha(baseline/name)==expected_hash;copy(baseline/name,inputs/('historical-actual-main-'+name))
original=HERE.parent/'native-share-current-main-review/stable-source-a/native/DesktopDiagnosticShare.cpp'
copy(original,inputs/'reviewed-original-DesktopDiagnosticShare.cpp')
native_main=ROOT/'native/diagnostic-share/DesktopDiagnosticShare.cpp';approval=ROOT/'native/diagnostic-share/approved-development-build.json'
driver=ROOT/'tools/compile-native-diagnostic-share.py'
assert sha(native_main)=='dc810bdb5b6c43c7842768270795d998d76f0b0a221e84fed97a245c220c2a9a'
assert sha(approval)=='88a8d70d549c1c253775f4f2d02152c7429aad0d4391762f5aa40b52d082c71a'
assert sha(driver)=='67adac30b8cb9aa5016f7dd2c6382fddac733ffadfbbc6f8c26ed24098d7c861'
copy(approval,inputs/'historical-approved-development-build.json');copy(driver,inputs/'controlled-compiler-driver.py')
graphs=[]
for build in ['build04','build04-repro']:
 path=HERE/build/'producer-input-graph.json';graph=json.loads(path.read_bytes())
 for key in ['source','dll','sourceDependencies']:assert sha(graph[key]['path'])==graph[key]['sha256Bytes']
 for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
  for row in graph[key]:assert sha(row['path'])==row['sha256Bytes'] and ext(row['path']).stat().st_size==row['bytes']
 graphs.append({'graph':pin(path),'dll':graph['dll'],'source':graph['source'],'headers':len(graph['transitiveHeaders']),
                'conservativeLibraries':len(graph['searchedLibrariesConservativePins']),'conservativeCompilerFiles':len(graph['compilerBinDirectoryConservativePins']),
                'fullRealInputHashesRechecked':True,'actualStaticArchiveMemberSelectionClaimed':False})
assert graphs[0]['dll']['sha256Bytes']==graphs[1]['dll']['sha256Bytes']=='726a3b2a036533dc2bf5913f77512a706c261ddb3aabbec92179a0293de3e65d'
probe=HERE.parent/'native-share-hwnd-owner-probe/attempt03/proof/result.json'
assert sha(probe)=='09366b168ab59414568655ba67536a6d7b47f0a1bdbfa84c968b0a9e3241b5a6';copy(probe,inputs/'historical-own-HWND-thread-proof03.json')
proof=HERE/'bind-proof06/accepted-evidence.json';result=json.loads((HERE/'bind-proof06/proof/result.json').read_bytes())
assert result['passed'] and result['assertions']==106
assert result['nativeHwndOwnerThreadId']!=result['nativeEdtThreadId']
contract=HERE/'source-contract-result.json';assert json.loads(contract.read_bytes())['checksCount']==23
review={
 'preparedCandidateReadyForIndependentReview':True,'independentReview':'pending','sourceControlledApprovalChanged':False,
 'MainIntegrated':False,'nativeShareFeatureAccepted':False,'DesktopDeployment':False,
 'createdUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'baselineHistoricalActualMain':expected,'currentNativeMainStillOriginal':pin(native_main),'currentApprovalStillOriginal':pin(approval),
 'candidateSource':pin(HERE/'candidate04/DesktopDiagnosticShare.cpp'),'controlledCompilerDriver':pin(driver),
 'freshControlledNativeBuilds':graphs,'twoFreshOutputDllBytesEqual':True,
 'actualHiddenWindowEvidence':pin(proof),'actualNativeResult':pin(HERE/'bind-proof06/proof/result.json'),
 'actualRuntimeDependencies':{'orderedCpCount':89,'externalCount':86,'productionOverrideClasses':0,'MainOrShellExecuted':False},
 'actualAssertions':result['assertions'],'sourceContracts':pin(contract),
 'actualNativeCases':[
  'same-process actual ComposeWindow HWND owner differs from Swing EDT',
  '8 actual STA/GetForWindow Bind and owner Retire cycles on invisible own HWND',
  'second source for same HWND rejected until prior owner retired',
  'actual native owner pump blocked by task-only exact-window/thread WH_CALLWNDPROC hook',
  'Retire timeout has fail-closed (-1, supplied1), token and immutable copy retained',
  'two additional direct caller native retries measured 2004/2005ms in proof05; one pending Close command reused',
  'Bind timeout cannot acquire owner apartment or report pane or terminal',
  'explicit owner Close/Retire retry acknowledges canceled queued commands, only then erases token',
  'WM_NCDESTROY closes native authority and releases actual owner COM resources',
  '11 product hooks registered, 11 unregistered, callback quiescence drained, no live dispatcher'
 ],
 'cppBoundary':{
  'PrepareMtaAndWeakDataRequestedBodyVerbatim':True,'originalTitleDescriptionTextStorageSupplyTerminalCallbacksVerbatim':True,
  'singleSessionRegistryCap':8,'oneBoundSourcePerHwnd':True,
  'onlyCheckedOwnHwndThreadHook':True,'globalOrForeignProcessHook':False,
  'ShowUsesSameActualOwnerBind':True,'newBindShowsPaneOrGrantsStorage':False,
  'registryLockHeldAcrossOwnerSendOrQuiescenceWait':False,'sameProcessHookModuleIsNull':True,
  'immediateClosedAuthorityOnRetire':True,'elapsedTimeCreatesTerminalEvent':False,
  'unknownDrainRetainsTokenAndCannotAuthorizeCopyDeletion':True,
  'nestedOwnerCommandRejectOrDeferWhileComExecuting':'static review only; no injected COM-body seam',
  'parallelPublicNativeApiContract':'Root provider transport calls are serialized by operations Mutex; arbitrary concurrent native Bind/Retire admission is not claimed'
 },
 'history':[
  {'path':'build01','outcome':'compile FAIL: ambiguous forward namespace declaration, raw source/log preserved'},
  {'path':'candidate02 + build02 + bind-proof01','outcome':'historical basic Bind/Retire + WM_NCDESTROY PASS; precedes timeout/nesting hardening'},
  {'path':'candidate03 + build03 + bind-proof02/03','outcome':'historical timeout unknown/owner retry PASS; precedes bounded pending Close and null module'},
  {'path':'candidate04 + build04 + bind-proof04','outcome':'fixture FAIL: 14s blocker deadline expired before repeated Swing-enqueued native call; successful real Retire violated an unsupported fixture timing expectation. The JNA task callback logged deadline exception. Native product failure is not established; queue-delay attribution is an inference.'},
  {'path':'candidate04 + build04 + bind-proof05','outcome':'106 PASS; additional retries call native directly on existing non-owner caller to avoid a second Swing enqueue while AWT owner intentionally held; raw native durations recorded'},
  {'path':'candidate04 + build04 + bind-proof06','outcome':'final 106 PASS on same candidate; compiler and 89 runtime input pins checked before/after, no fixture production overlap'},
 ],
 'boundariesNotAccepted':[
  'No BilipaiShareShow call, system share pane, DataRequested or receiver acceptance in this lane',
  'No actual SetStorageItems/terminal ShareCompleted/ShareCanceled receiver trace',
  'No actual Main/provider/actor/cache integration using the candidate DLL; earlier actual Main UI/actor evidence used a declared synthetic native transport',
  'No packaged runtime, public toolchain release authorization or deployment claim',
  'No arbitrary concurrent external native API caller, injected COM event revocation failure or reentrant pane teardown proof',
  'Unknown outcomes stay retained; process-exit retention and actual actor deletion policy require Root integration evidence'
 ],
 'primaryDocs':[
  {'url':'https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowshookexw','claim':'thread-scoped WH_CALLWNDPROC supported; current-process callback uses NULL hmod'},
  {'url':'https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendmessagetimeoutw','claim':'timeout/error differs from successful window-message processing; ERRORONEXIT observes destroyed target; same-queue timeout may be ignored'},
  {'url':'https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-unhookwindowshookex','claim':'unhook can return while an existing callback is still executing, so separate callback drain is required'},
  {'url':'https://learn.microsoft.com/en-us/windows/apps/develop/windows-integration/integrate-sharesheet-send','claim':'desktop share uses IDataTransferManagerInterop per HWND; hidden Bind is a limited pre-pane runtime check'},
 ],
}
save(HERE/'review-candidate.json',review)
print(json.dumps({'preparedReview':str(HERE/'review-candidate.json'),'sha256Bytes':sha(HERE/'review-candidate.json'),'dllSha256Bytes':graphs[0]['dll']['sha256Bytes'],'assertions':result['assertions']}))
