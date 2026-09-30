from pathlib import Path
import hashlib,json,os,subprocess,xml.etree.ElementTree as ET
HERE=Path(__file__).resolve().parent;REPO=next(p for p in HERE.parents if (p/'.git').exists())
LANE=REPO/'desktop/.local/native-share-owner-thread-dispatch-parity'
OUT=REPO/'desktop/verification/native-share/owner-dispatch'
assert not (OUT/'artifact-manifest.json').exists(), 'Never overwrite completed immutable acceptance'
def ext(path):
 value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(value if value.startswith(prefix) else prefix+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def read(path):return json.loads(ext(path).read_bytes())
def save(path,value):ext(path).parent.mkdir(parents=True,exist_ok=True);ext(path).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
def files(root):
 for base,dirs,names in os.walk(ext(root)):
  for name in names:
   value=str(Path(base)/name);prefix=chr(92)*2+'?'+chr(92)
   yield Path(value[len(prefix):] if value.startswith(prefix) else value)
manifest=LANE/'frozen-handoff.json'
assert sha(manifest)=='3ca2b5767f69af7e1f0d3db3f084516376d0bdc3d3119f6548c9c33245a4ea24'
handoff=read(manifest);assert len(handoff['files'])==handoff['fileCount']==234
raw=[]
def copy(source,dest):
 assert source.suffix.lower() in ['.json','.kt','.py','.cpp','.log','.txt','.args','.md','.xml','.gradle','.jsonl','.ndjson','.patch'],str(source)
 target=OUT/dest;ext(target).parent.mkdir(parents=True,exist_ok=True)
 if ext(target).exists():assert sha(source)==sha(target), 'Partial preparation bytes changed'
 else:ext(target).write_bytes(ext(source).read_bytes())
 assert sha(source)==sha(target)
 raw.append(dict(path=target.relative_to(REPO).as_posix(),sha256Bytes=sha(target),bytes=ext(target).stat().st_size))
for row in handoff['files']:
 source=LANE/row['path'];assert sha(source)==row['sha256Bytes'] and ext(source).stat().st_size==row['bytes']
 copy(source,Path('candidate-raw')/row['path'])
copy(manifest,Path('candidate-raw/frozen-handoff.json'))
for source in sorted(HERE.iterdir()):
 if source.is_file() and source.suffix.lower() in ['.py','.kt','.json','.log']:copy(source,Path('root-recipes')/source.name)
for folder in ['bind-proof01','lease-proof01','default-native-proof01','main-product-snapshot-01']:
 for source in sorted(files(HERE/folder)):
  if source.suffix.lower() in ['.json','.kt','.py','.log','.args','.xml']:copy(source,Path('actual-main')/source.relative_to(HERE))
snapshot=HERE/'main-product-snapshot-01/manifest.json';cp=snapshot.with_name('ordered-runtime-cp.json')
assert sha(snapshot)=='9f58369713e6738bea2a6cde03c586768fa414188a82f127b0b0771ca8e50151'
assert sha(cp)=='334d1947e200a659f06ab4002bf4b94870bb6ffa369a72a994bd1afbe69d88a2'
snap=read(snapshot);assert len(snap['sourceFiles'])==378
producer=REPO/'desktop/build/generated/native-diagnostic-share/producer-receipt.json';receipt=read(producer)
copy(producer,Path('actual-main/native-producer-receipt.json'))
graph=REPO/'desktop/build/generated/native-diagnostic-share/builds/40e36168-78a9-4928-a857-27569ba03d2a/producer-input-graph.json'
assert sha(graph)=='4d789710471ac2aec3850d799e7de09ccd1c274fa37b6932aff27d49d1ae503e'
copy(graph,Path('actual-main/native-producer-input-graph.json'))
source=REPO/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp';approval=source.with_name('approved-development-build.json')
assert sha(source)=='1606f70eb26cbff4d4e3278703753b27d271996b4971dae2abf13fbd50d5d8ea'
assert sha(approval)=='499a28ba7dec7a34cad1c7e63a051aa33739dc52b701f9423cdffb3bc670c238'
dll=REPO/'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'
assert sha(dll)=='2a36fd5c59cd0957fa9934f1c634635db414b7bdc863b92a943edb53107b6514'
bind=read(HERE/'bind-proof01/accepted-evidence.json');lease=read(HERE/'lease-proof01/accepted-evidence.json');default=read(HERE/'default-native-proof01/accepted-evidence.json')
assert bind['passed'] and bind['assertions']==106 and bind['productionOverrides']==0
assert lease['passed'] and lease['checks']==50 and lease['freshJvms']==8 and lease['productionOverrides']==0
assert default['passed'] and default['checks']==36 and default['freshJvms']==4 and default['productionOverrides']==0
test=ET.parse(ext(HERE/'main-product-snapshot-01/TEST-com.bilipai.desktop.diagnostics.DesktopDiagnosticsTest.xml')).getroot()
assert [int(test.get(k)) for k in ['tests','failures','errors','skipped']]==[18,0,0,0]
integration=REPO/'desktop/verification/source9-native-diagnostic-share-integration.json'
previous=read(integration);previousSha=sha(integration);copy(integration,Path('historical/previous-main-integration.json'))
summary=dict(upstreamTag='v0.2.3-alpha.9',upstreamCommit='fcf84853b287662e8a9129ea0d38576c36522a34',
 windowsBaseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
 MainIntegrated=True,sourceSnapshotManifestSha256Bytes=sha(snapshot),runtimeCpSha256Bytes=sha(cp),actualMainSourceFiles=378,
 actualOrderedRuntimeArtifacts=89,productionOverridesInAcceptedTests=0,actualDiagnosticJUnit=dict(tests=18,failures=0,errors=0,skipped=0),
 acceptedChanges=['Dispatch bind, Show and close commands to the exact current-process HWND owner thread',
  'Keep native callback depth and COM registrations until nested callback bodies have unwound',
  'Check ABI removal HRESULT before clearing registration flags; keep failure state for bounded retry',
  'Recheck closed state after asynchronous COM preparation before marking storage supply possible',
  'Actual Main operations mutex serializes the private native transport calls',
  'Source-controlled expected DLL digest from two independent fresh native builds; actual Main producer rebuild is byte identical'],
 nativeBuild=dict(sourceSha256Bytes=sha(source),sourceControlledApprovalSha256Bytes=sha(approval),dllSha256Bytes=sha(dll),
  graphSha256Bytes=sha(graph),headers=363,libraries=11,compilerFiles=64,freshCompilationEveryInvocation=True,
  runtimeTrustedHashFromSourceApproval=True,mutableDllCacheTrusted=False,candidateGraphCount=2,MainGraphRebuilt=True),
 actualHiddenHwndNative=dict(assertions=106,ownComposeWindowAlwaysHidden=True,BindAndRetire=True,RetireTimeoutAndRetry=True,
  secondSourceRebind=True,windowDestroyed=True,ShareShow=False,MainRootWindowExecuted=False),
 actualDefaultMainTransport=dict(freshJvms=4,checks=36,syntheticTransportFactory=False,WinRtPrepareStateRetire=True,
  repeatedNoWindowCycles=40,compiledMainTrustedHash=True,tamperedDllRejectedBeforeNativeCall=True,
  retiredOwnerRejectsBeforeLoad=True,HWND=False,ShareShow=False,DataRequested=False,SetStorageItems=False),
 actualLeaseScenarios=dict(freshJvms=8,checks=50,syntheticNativeEvents=True,clearRetryRestoreFreeze=True,managed16MiBBudget=True),
 preparedNativeGuardProof=dict(assertions=99,kotlin=50,native=49,syntheticCallbackScopes=True,realAbiRemovalFault=True,
  actualDataRequestedBodyExecuted=False,actualSetStorageItemsExecuted=False),
 previousUiProof=dict(path='desktop/verification/native-share/owner-dispatch/historical/previous-main-integration.json',
  sha256Bytes=previousSha,originalUiAlreadyAcceptedOnEarlierMain=True,UIAndPromptSourcesUnchangedByThisSlice=True,
  UiNotRerunAgainstNewNativeDll=True),
 confirmedRuntimeBlocker=dict(SwingEdtDiffersFromActualComposeHwndOwner=True,currentCppShowGuardRejectsSwingCaller=False,
  ownerDispatchCorrectionCompiledAndHiddenBindRetireVerified=True,realMainShowStillUnverified=True),
 remaining=['Real Main HWND ShareShow and Windows system pane interaction','Real DataRequested body, SetStorageItems and chosen receiver consumption',
  'Packaged application resource flattening/DLL/runtime acceptance','Full original feature parity and final Desktop deployment'],
 nativeShareFeatureAccepted=False,MainRootWindowExecuted=False,ShareUiAccepted=False,receiverAccepted=False,
 packagedRuntimeAccepted=False,newDesktopDeployment=False,deployedDesktopVersion='0.2.406.5',publicReleaseAuthorizationVerified=False,
 fullFeatureParityVerified=False,progressEstimateChanged=False,weightedReviewedProgressPercent=69.7)
artifact=dict(**summary,rawEvidence=dict(files=sorted(raw,key=lambda r:r['path']),fileCount=len(raw),
  binariesJarsDllsExePrivateStoresIncluded=False,candidateFrozenHandoffSha256Bytes=sha(manifest)),
 integrationPoints=[dict(path=p.relative_to(REPO).as_posix(),sha256Bytes=sha(p)) for p in [source,approval,
  REPO/'desktop/tools/compile-native-diagnostic-share.py',REPO/'desktop/tools/prepare-native-diagnostic-share.py',
  REPO/'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopNativeCrashShare.kt',REPO/'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt']])
save(OUT/'artifact-manifest.json',artifact)
save(integration,dict(**summary,rawArtifacts=dict(path=(OUT/'artifact-manifest.json').relative_to(REPO).as_posix(),sha256Bytes=sha(OUT/'artifact-manifest.json'),files=len(raw))))
for row in raw:assert sha(REPO/row['path'])==row['sha256Bytes'] and ext(REPO/row['path']).stat().st_size==row['bytes']
save(HERE/'freeze-receipt.json',dict(passed=True,rawFiles=len(raw),manifestSha256Bytes=sha(OUT/'artifact-manifest.json'),
 integrationSha256Bytes=sha(integration),sourcePins=378,actualMainChecks=dict(JUnit=18,hiddenHwnd=106,actorLease=50,defaultNative=36)))
print(json.dumps(dict(rawFiles=len(raw),manifestSha256Bytes=sha(OUT/'artifact-manifest.json'),integrationSha256Bytes=sha(integration),passed=True)))
