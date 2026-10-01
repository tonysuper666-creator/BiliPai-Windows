from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-concrete-original-root-integration';assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest();rows=[];excluded=[]
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data)
 rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def register(name,data):
 if Path(name).suffix in ('.class','.jar','.kotlin_module','.pyc'):
  excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable binary proof output'))
 else:put(name,data)
for lane,prefix,pin,count,key in [
 ('stable-home-concrete-mount-parity','prepared/root','2c6919d962ede9437e09e1094e88f7385bb28e7565f9a72ff56d3abe692086f4',108,'artifacts'),
 ('stable-offline-task-player-parity','prepared/offline-bridge','3146f18a8aa93dbabd88ea5bafda6192399db7ddf90d3226c4f093cb56cf4c23',57,'files')]:
 folder=MAIN/'desktop/.local'/lane;raw=read(folder/'frozen-handoff.json');assert sha(raw)==pin;put(prefix+'/frozen-handoff.json',raw)
 manifest=json.loads(raw);assert len(manifest[key])==count
 for item in manifest[key]:
  data=read(folder/item['path']);assert sha(data)==item['sha256Bytes'];register(prefix+'/'+item['path'],data)
for name in ['concrete-root-install48','offline-task-bridge-install48','offline-task-bridge-install48-attempt01']:
 folder=HERE/name
 for p in sorted(wide(folder).rglob('*'),key=str):
  if p.is_file():register('root/'+name+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
for attempt in ['01','02','03','04','05']:
 folder=HERE/('physical-root-startup-48-attempt'+attempt)
 for p in sorted(wide(folder).iterdir(),key=str):
  if p.is_file() and not p.name.startswith('private-'):
   register('root/physical-attempt'+attempt+'/'+p.name,p.read_bytes())
for name in ['install-concrete-root48.py','install-offline-task-bridge48.py','freeze-concrete-root48.py','run-physical-root-startup.py','PhysicalRootStartupFixture.java','PhysicalRootRoutesFixture.java','jvm-method-name-audit-48.json','windows-runtime-assets48.json']:
 put('root/'+name,read(HERE/name))
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot48/'+name,read(MAIN/'desktop/.local/stable-product-snapshot-48'/name))
for name in ['classes-48.log','classpath-48.log']:put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
startup=json.loads(read(HERE/'physical-root-startup-48-attempt03/result.json'))
routes=json.loads(read(HERE/'physical-root-startup-48-attempt05/result.json'))
assert startup['passed'] and routes['passed'] and routes['productionOverrides']==0
assert routes['actualRootRouteActions']['semanticActions']==3 and routes['processExitCode']==0
assert json.loads(read(HERE/'jvm-method-name-audit-48.json'))['issueCount']==0
report=dict(phase=48,wholeClassesPassed=True,wholeTestSourcesCompiled=True,sourceIdentityCount=939,resourceCount=213,actualRuntimeEntries=97,newRuntimeDependencies=0,
 actualPhysicalWindowStartupPassed=True,actualProductNormalShutdownPassed=True,actualGuestHomeRendered=True,actualProfileRendered=True,
 actualRootSemanticCallbacks=routes['actualRootRouteActions'],physicalMouseOrNativePlayerRouteAccepted=False,
 strictProductionOverrides=0,actualCapturedImagesSource='owned Skia render backing, private outputs excluded',
 startupProof=startup,routeProof=routes,exactInstallationHunks=53,existingSourceFamilies=12,newRootManuals=4,newOfflineBridgeManuals=2,
 newExeDeployed=False,pending=['Full original ordinary/portrait/landscape video and complete Offline controls','Full personal and remaining leaf pages','Real account and native player/PiP/focus route acceptance','Final portable desktop EXE'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''Concrete original Home and Window navigation
==========================================

The stable v0.2.3 source base stays pinned at 3d5d19a. Actual Shell now mounts the complete original retained Home/Profile/Category/Live pager, original NavDisplay/stack/Dock and installed typed leaves. Four new thin Windows bindings, twelve existing source-family deltas and one DIRECT original policy use the same repository, account Store, global image owner, Window lifecycle and native media actors. The separate taskId Offline bridge opens the existing retained offline source; complete original player controls are a subsequent slice.

Whole classes48 and test-source compilation pass with939 source identities,213 resources and97 existing runtime entries. Actual unchanged product main has passed native startup health and normal Window close with zero product-class replacements and fresh isolated application data. Product-owned Skia render images show the full guest Home and Profile. Three real production semantic actions verify Home to Profile, original guest download service to Login, and dialog close back to Profile. Original LoggedOut Profile intentionally requires login for download; no fake user account or successful authenticated Download route is claimed. These actions do not prove physical mouse input or native player routes.

History is preserved: first runtime pin preflight needed wide Windows paths; an initial rectangle screen image was invalid because other foreground pixels could be captured and was discarded; the subsequent own-Skia capture succeeds. The first route fixture expected guest DownloadList but observed the original guest login behavior. Its failure remains unchanged; the corrected fixture follows the original source. No product source was altered for these fixture corrections. Private user data, recommendation-label dumps, render images and rebuildable binaries are excluded.

The selected native assets reuse previously bundled libmpv/FFmpeg/ffprobe and the existing JNI actor. Full Video/Offline controls, remaining original personal pages, real account/native/PiP/focus interaction and desktop portable EXE remain pending.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(raw))))
