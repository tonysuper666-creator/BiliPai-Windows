"""Freeze prepared native repair evidence; never writes Main or approves a build."""
from pathlib import Path
import argparse,hashlib,json,datetime
HERE=Path(__file__).resolve().parent;DESKTOP=HERE.parents[1]
p=argparse.ArgumentParser();p.add_argument('--independent-review',type=Path,required=True);p.add_argument('--independent-review-sha',required=True);o=p.parse_args()
def ext(path):
 s=str(Path(path).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def pin(path):return {'path':str(path),'sha256Bytes':sha(path),'bytes':ext(path).stat().st_size}
def save(path,value):
 assert not path.exists(),str(path);path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(o.independent_review)==o.independent_review_sha
copy=HERE/'independent-review.json';assert not copy.exists();copy.write_bytes(o.independent_review.read_bytes())
candidate=HERE/'candidate06/DesktopDiagnosticShare.cpp';assert sha(candidate)=='1606f70eb26cbff4d4e3278703753b27d271996b4971dae2abf13fbd50d5d8ea'
main_cpp=DESKTOP/'native/diagnostic-share/DesktopDiagnosticShare.cpp';approval=DESKTOP/'native/diagnostic-share/approved-development-build.json';driver=DESKTOP/'tools/compile-native-diagnostic-share.py'
assert sha(main_cpp)=='dc810bdb5b6c43c7842768270795d998d76f0b0a221e84fed97a245c220c2a9a'
assert sha(approval)=='88a8d70d549c1c253775f4f2d02152c7429aad0d4391762f5aa40b52d082c71a'
assert sha(driver)=='67adac30b8cb9aa5016f7dd2c6382fddac733ffadfbbc6f8c26ed24098d7c861'
graphs=[]
for build in ['build06','build06-repro','native-helper-build06-attempt01']:
 file=HERE/build/'producer-input-graph.json';g=json.loads(file.read_bytes())
 for key in ['source','dll','sourceDependencies']:
  row=g[key];assert sha(row['path'])==row['sha256Bytes'] and ext(row['path']).stat().st_size==row['bytes']
 for key in ['transitiveHeaders','searchedLibrariesConservativePins','compilerBinDirectoryConservativePins']:
  for row in g[key]:assert sha(row['path'])==row['sha256Bytes'] and ext(row['path']).stat().st_size==row['bytes']
 graphs.append({'graph':pin(file),'source':g['source'],'dll':g['dll'],'transitiveHeaders':len(g['transitiveHeaders']),
                'conservativeSearchedLibraries':len(g['searchedLibrariesConservativePins']),'conservativeCompilerFiles':len(g['compilerBinDirectoryConservativePins']),
                'allRealGraphInputsRechecked':True,'staticArchiveMemberSelectionClaimed':False,'taskFaultHelper':build.startswith('native-helper')})
assert graphs[0]['dll']['sha256Bytes']==graphs[1]['dll']['sha256Bytes']=='2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514'
assert graphs[2]['dll']['sha256Bytes']=='93ed97330684a2a38c871aeb09504e5915df8f8826031bc0d0d6b01305f0ba0b'
bind=json.loads((HERE/'bind-proof08/accepted-evidence.json').read_bytes());guard=json.loads((HERE/'guard-proof03/accepted-evidence.json').read_bytes())
assert bind['passed'] and bind['assertions']==106 and bind['taskDllSha256Bytes']==graphs[0]['dll']['sha256Bytes']
assert guard['passed'] and guard['assertions']==99 and guard['candidateSourceSha256Bytes']==sha(candidate) and guard['taskDllSha256Bytes']==graphs[2]['dll']['sha256Bytes']
assert json.loads((HERE/'guard-proof03/input-pins.json').read_bytes())['taskNativeSourceSha256Bytes']==sha(candidate)
contract=HERE/'source-contract06-result.json';assert json.loads(contract.read_bytes())['checksCount']==31
receipt={
 'preparedNativeOwnerBoundaryReadyForRootReviewAndInstallation':True,'independentSourceReview':pin(copy),
 'nativeShareFeatureAccepted':False,'actualMainWithCandidateAccepted':False,'DesktopDeployment':False,'MainAndApprovalModifiedByThisTask':False,
 'createdUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'candidateSource':pin(candidate),'candidateDll':graphs[0]['dll'],
 'controlledCompilerDriver':pin(driver),'nativeGraphCohorts':graphs,'twoFreshCandidateOutputDllBytesEqual':True,
 'historicalActualMainRuntimeDependencies':{'manifestSha256Bytes':'4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f','orderedCpSha256Bytes':'a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc','orderedCount':89,'externalCount':86,'zeroProductionClassOverrides':True,'MainOrShellExecuted':False},
 'plainCandidateNativeProof':pin(HERE/'bind-proof08/accepted-evidence.json'),'plainCandidateAssertions':106,
 'declaredSyntheticCallbackAndAbiFaultProof':pin(HERE/'guard-proof03/accepted-evidence.json'),'guardAssertions':99,
 'sourceContractAudit':pin(contract),'sourceContractChecks':31,
 'confirmedOldIssuesResolved':[
  'Swing EDT is not actual ComposeWindow HWND owner; Bind/Show/Close now dispatch to exact checked owner',
  'asynchronous callbacks are not drained by a recursive mutex alone; lifetime depth tracks all three event bodies',
  'WinRT projected event removal ignores HRESULT; checked ABI remove preserves failed registrations and apartment for real retry',
  'deferred destruction leaves refs alive; closed storage admission is checked after all preparatory COM calls immediately before a possible grant'
 ],
 'actualNativeProofBoundary':[
  'plain candidate DLL actually initializes owner STA/GetForWindow/subscription and retires it on hidden own ComposeWindow',
  '8 Bind/Retire cycles, second-source rejection, owner pump blockage, unknown timeouts, bounded Close retry and actual WM_NCDESTROY passed',
  'guard helper includes candidate source exactly, then adds task-only exports and a declared synthetic CallbackScope/ABI forwarding fault',
  'real STA manager/file/local package stay intact through depth2 nested Close/Retire and actual own-window destruction; inner unwind never releases them',
  'outer unwind performs actual owner cleanup; failed native Retire leaves (-1,supplied1) and token/copy before retry',
  'ABI fault first returns E_FAIL before removing the real DataRequested registration; second retry calls actual WinRT remove once successfully',
  'recursive Close during remove is pending and does not empty actual COM refs/apartment',
  'actual beginStorageSupply member rejects closed authority without executing SetStorageItems'
 ],
 'preconditionsAndRemainingBoundaries':[
  'Main provider operationsMutex serializes native transport operations; arbitrary concurrent exported-API callers are not proven',
  'no actual ShareShow, system share pane, real DataRequested body, SetStorageItems, receiver or terminal OS event trace',
  'guard03 is a synthetic callback/fault test against actual native Session/manager, not an OS sharing or receiver acceptance claim',
  'no actual Main actor/cache with candidate DLL integration, packaged-runtime or deployment acceptance',
  'package ShareCompleted/ShareCanceled removal uses the same checked-ABI policy but failure injection covered DataRequested only',
  'non-owner callback exit cannot clean owner COM resources; unacknowledged drain retains unknown token/copy for owner retry',
  'no public toolchain redistribution authorization claimed; actual producer is installed BuildTools /MT'
 ],
 'history':[
  'candidate04/review-candidate and proof06 are historical before confirmed async callback/revoke blockers',
  'candidate05/proof07 fixes callback/revoke but precedes the final post-COM storage admission guard',
  'bind-proof04 retained fixture deadline/assertion failure; direct existing caller retries in later proofs avoid repeated Swing enqueue assumptions',
  'guard-proof01 retained failure: helper called nested actual AWT DestroyWindow while synchronously blocking EDT; later destruction helper uses existing non-owner caller',
  'guard-proof02 runtime passed but copied input metadata field still pinned candidate05; guard-proof03 corrected it and freshly reran all cases',
  'native-helper-build05-attempt01 compile error preserved with source-at-compile; attempt02 basic helper and attempt03 stable-interface pin remain history',
  'all prior source/native graphs, logs/args, proof receipts and failure bytes remain unchanged'
 ],
 'mainNativeStillOriginal':pin(main_cpp),'mainApprovedDigestStillOriginal':pin(approval),
}
save(HERE/'review-final.json',receipt)
install={
 'rootActionsRequired':[
  {'step':1,'action':'Review this immutable raw handoff and independent source review; verify exact candidate source/graph/DLL hashes','candidateSourceSha256Bytes':sha(candidate),'candidateDllSha256Bytes':graphs[0]['dll']['sha256Bytes']},
  {'step':2,'action':'Copy only candidate06/DesktopDiagnosticShare.cpp to desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp; never copy task helper exports/DLL into Main'},
  {'step':3,'action':'Update source-controlled approved-development-build.json using reviewed source SHA, compiler-script SHA, all same normalized tool/header/library input pins, and this handoff manifest SHA; DLL expected digest comes from two fresh controlled builds'},
  {'step':4,'action':'Let the controlled Gradle native producer rebuild from the installed source and verify fresh complete graph/input pins and resulting expected DLL. Do not trust an old mutable cache DLL/receipt'},
  {'step':5,'action':'Confirm staged common/native/windows-x64/bilipai-diagnostic-share.dll and generated DesktopNativeDiagnosticShareAssetHash use the fresh approved digest; Compose app resources flatten common into native/windows-x64'},
  {'step':6,'action':'Compile actual Main, freeze new immutable Kotlin/Java/resources JARs and actual ordered CP, rerun relevant actual UI/actor/native tests against those current bytes'},
  {'step':7,'action':'Keep nativeShareFeatureAccepted=false until actual Show/system pane/receiver and packaged runtime boundaries are separately verified. Do not call a hidden Bind or synthetic guard proof feature acceptance'}
 ],
 'installableProductionInputs':{'source':pin(candidate),'freshCandidateDllForDigestReview':graphs[0]['dll'],'graph':graphs[0]['graph'],'compilerDriver':pin(driver)},
 'mustNeverInstall':'native-helper*/GuardRevokeTaskHelper.cpp, native-helper-build*/bilipai-diagnostic-share.dll, fixture-only jars, task/private synthetic files',
 'noInstallationPerformedByThisTask':True,
}
save(HERE/'root-installation-checklist.json',install)
readme='''# Prepared native owner-thread repair, candidate06

Final prepared C++ source is `candidate06/DesktopDiagnosticShare.cpp`, SHA-256 `1606f70eb26cbff4d4e3278703753b27d271996b4971dae2abf13fbd50d5d8ea`. Two fresh controlled BuildTools /MT outputs (`build06` and `build06-repro`) produced the same plain candidate DLL, SHA-256 `2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514`. Each has the full actual graph: 363 transitive headers, 11 conservatively pinned searched libraries and 64 compiler-directory binaries. All graph bytes were rechecked before freezing. Current Main C++ and source-controlled approved digest remain unchanged.

The confirmed original bug was that the existing ComposeWindow HWND belongs to the AWT native toolkit thread, which differs from Swing EDT. This candidate keeps the own-process/exact-HWND/thread checks and sends Bind/Show/Close/Retire to that actual owner through a thread-specific WH_CALLWNDPROC and owned registered command. There is no new window, global hook or foreign-process injection. Prepare remains MTA, and normal Show reuses Bind. Registry gates are released before native sends/waits. Timeouts are unknown outcomes; pending Close retry uses one command and cannot invent a terminal event or permission to delete a copy.

`bind-proof08` runs the plain candidate DLL with an actual displayable but invisible task-owned ComposeWindow. It passes 106 assertions: actual owner STA/GetForWindow/subscription, eight Bind/Retire cycles, same-window second-source rejection, real owner-pump blockage, unknown Bind/Retire retention, explicit retry, hook cancellation/quiescence, and actual WM_NCDESTROY. It uses the historical actual Main snapshot 4e97c4c0's 89 ordered dependencies (86 external), has zero production class overlap and does not run Main/Shell. Compiler and dependency inputs were checked before/after. The fixture-only SOFTWARE renderer and short private Skiko home avoid earlier platform-initialization stalls.

Independent source review found two further lifecycle bugs in candidate04: asynchronous DataRequested reentry was outside the owner-command guard, and projected C++/WinRT event removal ignores remove HRESULT. Candidate06 tracks depth across all three event bodies, returns pending rather than tearing down active or recursive cleanup, and performs outermost cleanup only on the actual owner after callback locals unwind. Checked ABI remove clears each registration only on its real success; failed registrations keep the corresponding COM objects, file and STA for retry. A post-COM admission method checks closed authority immediately before the conservative supply-attempt flag. Source audit has 31 passing checks: original weak callback, metadata, storage call and terminal algorithms match after only the declared lifetime/admission additions, with checked revoke changes separately declared.

`guard-proof03` passes 99 assertions (50 Kotlin, 49 native) in four cases. Its separately built task-only DLL includes the exact candidate source and adds helper exports plus a declared synthetic CallbackScope and ABI forwarding fault. Real Session/STA/file/WinRT manager/local DataPackage stay intact through depth2 nested Close/Retire and actual own-window DestroyWindow. Inner unwind cannot release resources; outer owner unwind performs actual event revocation and cleanup. A first E_FAIL before real DataRequested remove retains that actual registration/STA; the second attempt invokes real WinRT removal once and succeeds. Recursive close during removal remains pending. The actual beginStorageSupply member rejects closed authority. The local package is never supplied to a request or receiver. No real DataRequested body, SetStorageItems or ShareShow was executed. Failure injection covered DataRequested removal; package event removal failure handling is checked in source.

All history is retained. Candidate04/proof06 precede the confirmed callback/revoke blockers and remain unapproved history; candidate05/proof07 precede the final supply admission guard. Bind-proof04 failed an unsupported timing expectation after its task blocker deadline expired before a repeated Swing enqueue. Guard-proof01 failed because its task helper synchronously blocked EDT while nested actual AWT destruction needed that thread; the later destruction test uses an existing non-owner caller and leaves EDT available. Guard-proof02 runtime passed but one copied input metadata field still pinned candidate05; guard-proof03 fixes the metadata and reruns all cases. Earlier compiler failures and their raw source/logs are preserved. The archived candidate04 README and risk receipt keep their original context.

Independent review found no new blocker within the owner/callback/revoke slice under Main's operationsMutex serialization. Arbitrary concurrent exported-API callers are not claimed. The [same-process/thread hook contract](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowshookexw) is followed using NULL hmod. [Unhook can leave an earlier callback running](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-unhookwindowshookex), so separate quiescence is required. [Message delivery timeouts](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendmessagetimeoutw) never imply share completion. WinRT projected remove-HRESULT behavior was verified against the pinned SDK 26100 header bytes.

This is prepared source/producer/owner-boundary evidence, not actual Main or feature acceptance. No system share pane, receiver, OS terminal-event trace, packaged runtime, public redistribution authorization or desktop deployment is claimed. `nativeShareFeatureAccepted=false`. Earlier actual Main UI/actor evidence used its declared synthetic native transport. Root must install/rebuild/re-pin and verify the current Main separately, following `root-installation-checklist.json`.

The handoff lists raw sources, JSON, logs, args and hashes. Class/JAR/OBJ/LIB/PDB/DLL binaries and task-private synthetic homes/files/stores are excluded from raw Git evidence; DLL bytes remain in the isolated producer directories with complete hashes. Never install the task helper DLL or fixtures into Main. No real account, original crash data, external HTTP or receiver is part of these tests.
'''
(HERE/'README.md').write_text(readme,encoding='utf-8',newline='\n')
files=[]
extensions={'.cpp','.kt','.py','.json','.log','.args','.md','.txt','.patch'}
for file in sorted(HERE.rglob('*'),key=lambda x:str(x).casefold()):
 if not file.is_file() or file.suffix.casefold() not in extensions or file.name=='frozen-handoff.json':continue
 if '__pycache__' in file.parts:continue
 files.append({'path':str(file.relative_to(HERE)).replace('\\','/'),'sha256Bytes':sha(file),'bytes':file.stat().st_size})
handoff={'preparedNativeOwnerBoundaryPassed':True,'MainIntegrated':False,'nativeShareFeatureAccepted':False,'DesktopDeployment':False,
 'candidateSourceSha256Bytes':sha(candidate),'candidateDllSha256Bytes':graphs[0]['dll']['sha256Bytes'],'taskOnlyGuardHelperDllSha256Bytes':graphs[2]['dll']['sha256Bytes'],
 'independentReviewSha256Bytes':sha(copy),'reviewReceiptSha256Bytes':sha(HERE/'review-final.json'),
 'plainNativeAssertions':106,'declaredSyntheticGuardAssertions':99,'staticSourceContracts':31,
 'includesFailedAndHistoricalEvidence':True,'binaryClassesJarsDllsAndPrivateStoresIncluded':False,'files':files,'fileCount':len(files)}
save(HERE/'frozen-handoff.json',handoff)
for row in files:assert sha(HERE/row['path'])==row['sha256Bytes'] and (HERE/row['path']).stat().st_size==row['bytes']
print(json.dumps({'handoffSha256Bytes':sha(HERE/'frozen-handoff.json'),'reviewSha256Bytes':sha(HERE/'review-final.json'),'installChecklistSha256Bytes':sha(HERE/'root-installation-checklist.json'),'files':len(files)}))
