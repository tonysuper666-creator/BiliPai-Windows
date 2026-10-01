from pathlib import Path
import hashlib,json,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-download-profile-video-entry-integration';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest();rows=[];excluded=[]
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data);rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def register(name,data):
 if Path(name).suffix in ('.jar','.class','.kotlin_module','.pyc'):
  excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable proof output'))
 else:put(name,data)
def frozen(lane,prefix,filename,pin,count=None):
 raw=read(lane/filename);assert sha(raw)==pin;put(prefix+'/'+filename,raw);m=json.loads(raw)
 entries=m.get('artifacts',m.get('files',m.get('registeredRawArtifacts')));assert isinstance(entries,list)
 if count is not None:assert len(entries)==count
 for r in entries:
  data=read(lane/r['path']);assert sha(data)==r['sha256Bytes'];register(prefix+'/'+r['path'],data)
 return m
for name,lane,pin,count in [
 ('download','stable-download-list-parity','582f49bc0b43b01f31b6ca93f2381a2ac299771af5d80d8648f86c7d62a50958',58),
 ('accounts','stable-profile-account-port-parity','250100d194c524add5b3baf05b1efd1d470a63d4045ed4274c624e9b6292f5c2',56),
 ('video','stable-video-detail-admission-parity','ee4c6d9fea64b8294bfe6bd995c68f12304f41820594fae1181fc6b2787b4da9',69)]:
 frozen(MAIN/'desktop/.local'/lane,'prepared/'+name,'frozen-handoff.json',pin,count)
accountLane=MAIN/'desktop/.local/stable-profile-video-admission-actual47-proof'
frozen(accountLane,'root/actual47/accounts-video','frozen-proof.json','5a09c4e52a2fcba617e41481481b04c36dd6a3ce4e6d5fcb8a2b6620cff04d0f',25)
account=json.loads(read(accountLane/'result.json'));assert account['passed'] and account['productionOverrides']==0 and account['assertions']==54
downloadLane=Path(sys.argv[1]);assert downloadLane.is_absolute() and downloadLane.parent==MAIN/'desktop/.local'
downloadManifest=frozen(downloadLane,'root/actual47/download','frozen-handoff.json',sys.argv[2],15)
download=downloadManifest['originalUiProof'];assert download['status']=='PASS' and download['runtimeProductionOverrides']==0 and download['assertions']==21
folder=HERE/'download-profile-video-install47'
for p in sorted(wide(folder).rglob('*'),key=str):
 if p.is_file():register('root/install47/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
for name in ['install-download-profile-video47.py','freeze-download-profile-video47.py','jvm-method-name-audit-47.json','freeze47-attempt01.json']:put('root/'+name,read(HERE/name))
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot47/'+name,read(MAIN/'desktop/.local/stable-product-snapshot-47'/name))
for name in ['classes-47.log','export-47.log']:put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
report=dict(wholeClassesPhase=47,wholeClassesPassed=True,wholeTestSourcesCompiled=True,sourceIdentityCount=938,resourceCount=213,actualRuntimeEntries=97,actualKotlinClasses=12005,invalidJvmMethodNames=0,actualFocusedAssertions=75,actualFocusedGroups=8,productionOverrides=0,accountsVideoProof=account,downloadProof=download,fullOriginalDownloadListInstalled=True,sameQueueAndAccountStoreAndMpv=True,canonicalDownloadDestinationUsedByRootEnqueue=True,profileOriginalAccountPortAll14MethodsInstalled=True,videoMillisecondResumeAndInitialCommentRouteInstalled=True,newOriginalIdentities=3,newRuntimeArtifacts=0,rootMounted=False,newWindowsExeDeployed=False,pending=['Physical original Window/MainHost/Profile and typed leaf mount','Initial offline taskId bridge followed by full original offline controls','Full original normal/portrait/landscape video renderer and actual native/Root/user-account interaction','Portable desktop EXE'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''Original download list, Profile accounts and full video-entry parameters
======================================================================

Stable v0.2.3 remains pinned at 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589. The whole original DownloadListScreen and storage policy use the existing manager and original speed sampler, pause/continue, delete confirmation and responsive cards. Three source identities are appended once; the direct presentation helper comes only from registry sync. Root enqueue now reads the same canonical managed destination displayed by the list. Imported Android SAF export remains an explicit unsupported boundary rather than a successful Windows write.

All14 original Profile account methods bind to the same encrypted primary SessionStore, captured epoch/MID and entry admission. Metadata preserves the original Nav fields and selected-playback identity remains independent. Logout completion is accepted only for its own terminal. No new account authority/client is created. Millisecond resume requests and complete initial comment root/target/openId are accepted by the existing controller and sole comment owner; compatible BVID/CID/source reuse preserves queue and pause. Positive resume does not fabricate a native seek acknowledgement.

Whole classes47 and test-source compilation pass with938 source identities,213 resources and97 existing runtime entries. Actual47 contains12005 Kotlin classes and no invalid JVM method names. Focused immutable-product tests have zero production replacements:8 groups/75 original assertions, including the full original download confirmation's2 pointer pairs. Account/video fixture bodies remain byte-identical. Download's original fixture required a fixture-only constructor adapter after the actual manager's publication port became mandatory; original assertions and product code remain unchanged, and the required unused publication effect fails closed. Its first compile failure is retained by the actual proof packet.

These are actual product class/ownership and offscreen Compose checks, without real HTTP, accounts or user assets. Physical Root Window mounting, native route effects, complete original player controls, taskId offline leaf and portable EXE are pending. The source packets, exact small installation hunks and acceptance history are preserved; rebuildable and private outputs are excluded.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(raw))))
