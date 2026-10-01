from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-original-video-offline-personal-integration'
assert not OUT.exists()
sha=lambda b:hashlib.sha256(b).hexdigest();rows=[];excluded=[]
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def put(name,data):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(data)
 rows.append(dict(path=name,sha256Bytes=sha(data),sizeBytes=len(data)))
def register(name,data):
 if Path(name).suffix in ('.class','.jar','.kotlin_module','.pyc','.png','.jpg','.mp4'):
  excluded.append(dict(path=name,sha256Bytes=sha(data),reason='Rebuildable binary or private fixture media/render output'))
 else:put(name,data)
for lane,prefix,filename,pin,count in [
 ('stable-video-detail-full-ui-parity','prepared/video','frozen-stage1.json','6019bfa5ffdd3ca51b413add293c6dd724867e5f9c9731d3dbba2f515181e564',620),
 ('stable-original-offline-player-parity','prepared/offline','frozen-handoff.json','b7d3bbe5c4e95162e643056a8f0a316167b911f6c4313ee7683446cc68e53553',84),
 ('stable-personal-history-liked-parity','prepared/personal','frozen-handoff.json','f763a58f2e370e6139d2dba60d1daa4ae35792defcac5ff24d4b941e48d1cad8',66)]:
 folder=MAIN/'desktop/.local'/lane;raw=read(folder/filename);assert sha(raw)==pin;put(prefix+'/'+filename,raw)
 manifest=json.loads(raw);assert len(manifest['artifacts'])==count
 for item in manifest['artifacts']:
  data=read(folder/item['path']);assert sha(data)==item['sha256Bytes'],item['path'];register(prefix+'/'+item['path'],data)
native=MAIN/'desktop/.local/stable-offline-native-integration-proof'
raw=read(native/'closed01-13/frozen-handoff.json');assert sha(raw)=='c1478b0574ec48bc0d072f1a13f4b90afc61fb37bcd3c33cfafa6cd39f7a1966'
put('native49/closed01-13/frozen-handoff.json',raw);native_manifest=json.loads(raw)
assert len(native_manifest['artifacts'])==120 and native_manifest['realSkyMousePairs']==4
assert native_manifest['initialFullscreenAccepted'] is False and native_manifest['stableOriginalFullscreenButtonAccepted'] is True
for item in native_manifest['artifacts']:
 p=Path(item['path']);relative=p.relative_to(native).as_posix();data=read(p);assert sha(data)==item['sha256Bytes'],str(p)
 register('native49/'+relative,data)
for name in ['video-full-units-install49','video-full-units-install49-attempt01','original-offline-install49','personal-lists-install49','offline-root-install49']:
 folder=HERE/name
 assert folder.is_dir(),name
 for p in sorted(wide(folder).rglob('*'),key=str):
  if p.is_file():register('root/'+name+'/'+p.relative_to(wide(folder)).as_posix(),p.read_bytes())
folder=HERE/'physical-root-startup-49-attempt01'
for p in sorted(wide(folder).iterdir(),key=str):
 if p.is_file() and not p.name.startswith('private-'):register('root/physical-startup49/'+p.name,p.read_bytes())
for name in ['install-video-full-units49.py','install-original-offline49.py','install-personal-lists49.py','install-offline-root49.py','prepare-system-media49.py','freeze-full-units49.py','run-physical-root-startup.py','PhysicalRootStartupFixture.java','jvm-method-name-audit-49.json']:
 put('root/'+name,read(HERE/name))
for name in ['manifest.json','ordered-runtime-cp.json']:put('root/snapshot49/'+name,read(MAIN/'desktop/.local/stable-product-snapshot-49'/name))
for name in ['classes-49.log','classpath-49.log']:put('root/'+name,read(REPO/'desktop/.local/stable-build-repair'/name))
startup=json.loads(read(HERE/'physical-root-startup-49-attempt01/result.json'))
assert startup['passed'] and startup['productionOverrides']==0 and startup['processExitCode']==0
assert json.loads(read(HERE/'jvm-method-name-audit-49.json'))['issueCount']==0
report=dict(phase=49,wholeClassesPassed=True,wholeTestSourcesCompiled=True,sourceIdentityCount=956,resourceCount=213,actualRuntimeEntries=97,newRuntimeDependencies=0,
 actualProductWindowStartupPassed=True,actualProductNormalShutdownPassed=True,unchangedProductMainStartup=startup,
 fullOriginalOfflineMounted=True,fullOriginalHistoryAndLikedMounted=True,originalVideoFullUnitsSourceFoundation=True,
 actual49NativeStandaloneRootAdapter=True,nativeRootMainShellAccepted=False,initialOfflineFullscreenAccepted=False,lateOriginalFullscreenButtonAccepted=True,
 originalNextEpisodeAccepted=False,realSkyMousePairs=4,actualAccountAccepted=False,PiPAccepted=False,OSSMTCButtonAccepted=False,
 newExeDeployed=False,pending=['First original Offline fullscreen entry rolls back on initial control popup opening; platform fix and native rerun required','Next episode pointer acceptance','Ordinary complete video page, state holder and full controls integration','Remaining original personal and content leaves','Real account/media/PiP route acceptance','Final portable desktop EXE'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(artifacts=rows,excludedRebuildableOutputs=excluded),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''Original video, Offline and personal source integration
======================================================

Stable v0.2.3 remains pinned at 3d5d19a. The full original Offline renderer now uses the same retained native player, manager, root media actors and guarded Window adapter. Full original History, HistorySearch, Liked and CoinArchive pages use the same repository, account store and existing playback actors. Complete original video information, analytics and engagement units are source foundations; the remaining ordinary player page and state holder are still being integrated.

Whole classes49 and test-source compilation pass with956 registered source identities,213 resources and97 existing runtime entries. Actual unchanged product main passes native startup health and normal Window close in isolated application data, with zero product-class replacements. This does not prove authenticated routes or native media controls in Main Shell.

The separate actual49 native fixture consumes immutable product classes and existing native assets with no overrides. Four real computer-use mouse press/release pairs reach the original controls. A late original fullscreen button enters and holds actual Fullscreen, then exits; first landscape entry instead rolls back to Floating when its control popup opens. The next-episode click did not reach the intended target after a clipped-window coordinate mapping. Both failures are retained, and no full native/PiP/SMTC completion is claimed. Necessary later diagnostic runs and platform fixes are separate from this frozen cohort.

All raw source packets, exact install deltas, compile receipts, immutable classpath pins, closed native attempts01 through13, historical failures and startup evidence are bound by raw byte hashes. Private fixture state, media, render images and rebuildable binaries are excluded with hashes/reasons. No desktop EXE was replaced.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),excludedBinaries=len(excluded),manifestSha256Bytes=sha(raw))))
