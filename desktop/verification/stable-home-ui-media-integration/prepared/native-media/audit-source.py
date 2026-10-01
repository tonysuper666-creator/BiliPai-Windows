from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def sha(b):return hashlib.sha256(b).hexdigest()
def lf(p):return p.read_bytes().replace(b'\r\n',b'\n')
def save(p,o):p.write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def body(raw,name):
 spec=importlib.util.spec_from_file_location('tokens',MAIN/'desktop/tools/sync-upstream.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 ts=m.kotlin_tokens(raw);starts=[i for i in range(len(ts)-1) if ts[i][0]=='fun' and ts[i+1][0]==name];assert len(starts)==1,(name,starts)
 start=starts[0];i=start
 while ts[i][0]!='(':i+=1
 depth=1
 while depth:i+=1;depth+=(ts[i][0]=='(')-(ts[i][0]==')')
 while ts[i][0] not in ['{','=']:i+=1
 if ts[i][0]=='=':
  end=raw.index('\n\n',ts[i][2]);return raw[ts[start][1]:end]
 depth=1;begin=i
 while depth:i+=1;depth+=(ts[i][0]=='{')-(ts[i][0]=='}')
 return raw[ts[start][1]:ts[i][2]]
def main():
 snap=MAIN/'desktop/.local/stable-product-snapshot-30'
 manifest=json.loads((snap/'manifest.json').read_text(encoding='utf-8'))
 originals={
 'app/src/main/java/com/android/purebilibili/core/ui/wallpaper/WallpaperMedia.kt':['isVideoWallpaper','WallpaperMedia'],
 'app/src/main/java/com/android/purebilibili/feature/home/components/HomeHeroCarousel.kt':['MutedHeroVideoPlayer'],
 'app/src/main/java/com/android/purebilibili/feature/home/components/VideoPreviewDialog.kt':['VideoPreviewDialog','DisposableVideoPlayer'],
 'app/src/main/java/com/android/purebilibili/core/ui/performance/JankTracking.kt':[],
 'app/src/main/java/com/android/purebilibili/core/ui/SystemBarCompat.kt':[],
 'app/src/main/java/com/android/purebilibili/feature/home/HomeWallpaperBackdrop.kt':[],
 }
 rows=[]
 for path,names in originals.items():
  raw=subprocess.run(['git','show',COMMIT+':'+path],cwd=CANDIDATE,capture_output=True,check=True).stdout.replace(b'\r\n',b'\n');text=raw.decode('utf-8')
  target=HERE/'original-source'/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
  rows.append(dict(path=path,commit=COMMIT,sha256LF=sha(raw),declarations=[dict(name=n,bodySha256LF=sha(body(text,n).encode())) for n in names]))
 homePath='app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt'
 homeRaw=subprocess.run(['git','show',COMMIT+':'+homePath],cwd=CANDIDATE,capture_output=True,check=True).stdout.replace(b'\r\n',b'\n')
 homeLines=homeRaw.decode('utf-8').splitlines();calls=[i for i,line in enumerate(homeLines) if 'com.android.purebilibili.feature.home.components.VideoPreviewDialog(' in line];assert len(calls)==1
 start=calls[0]-3;fragment='\n'.join(homeLines[start:start+18])+'\n'
 save(HERE/'preview-subject-boundary.json',dict(source=homePath,commit=COMMIT,sourceSha256LF=sha(homeRaw),startLine=start+1,endLine=start+18,originalCallerFragment=fragment,callee='app/src/main/java/com/android/purebilibili/feature/home/components/VideoPreviewDialog.kt',calleeRememberInputs=['videoUrl = remember { ... }','isPlaying = remember { ... }','LaunchedEffect(isPlaying)'],requiredCallerAdaptation='Same parent sole Home producer: inside original if(item!=null), key(item.bvid,item.cid) around the one unchanged full original VideoPreviewDialog call. Outer Root full Home composition key(capturedEpoch).',notAppliedHere=True,frozen524NotChanged=True,reason='Fast subject replacement while AnimatedVisibility retains the composition otherwise retains old URL/playback coroutine; media sourceVersion checks cannot reconstruct the lost subject identity.'))
 local=[]
 for row in json.loads((HERE/'patch-baselines.json').read_text(encoding='utf-8')):
  raw=lf(MAIN/row['path']);assert sha(raw)==row['baseSha256LF'],(row['path'],sha(raw),row['baseSha256LF'])
  snapshot=next(x for x in manifest['inputs'] if x['path']==row['path'])
  # Snapshot raw pin plus current raw Main path establishes that this optional patch is based on actual30 source.
  assert sha((MAIN/row['path']).read_bytes())==snapshot['sha256Bytes'],(row['path'],snapshot)
  local.append(dict(path=row['path'],actual30RawSha256=snapshot['sha256Bytes'],baseSha256LF=row['baseSha256LF'],candidateSha256LF=row['candidateSha256LF'],inverse=json.loads((HERE/'hunks'/(Path(row['path']).name+'.adaptations.json')).read_text(encoding='utf-8'))))
 inputs=json.loads((HERE/'runs/04/compile-result.json').read_text(encoding='utf-8'))['sourceInputs']
 assert len(inputs)==6
 for x in inputs:
  p=Path(x['path']);prepared=HERE/'prepared'/p.relative_to(HERE/'runs/04/source-inputs');assert sha(prepared.read_bytes())==x['sha256Bytes']
 checked=[]
 for p in sorted((HERE/'prepared').rglob('*.kt')):checked.append(dict(path=str(p.relative_to(HERE)),sha256LF=sha(lf(p)),physicalLines=len(lf(p).splitlines())))
 seams=[]
 for path,anchors in {
 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt':['val listen =','listening by','NowPlayingBar'],
 'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt':['windowState','hostWindow'],
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicUserPlatform.kt':['rememberDesktopDynamicReduceMotion'],
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDetailEffects.kt':['desktopDetailRenderEffectsSupported'],
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopSessionStore.kt':['withCurrentDynamicCacheOwner'],
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopAnimatedSkinImage.kt':['decodeOrNull','render('],
 }.items():
  p=MAIN/path;text=lf(p).decode('utf-8');lines=text.splitlines();seams.append(dict(path=path,sourceSha256LF=sha(lf(p)),anchors=[dict(text=a,lines=[i+1 for i,line in enumerate(lines) if a in line]) for a in anchors]))
 save(HERE/'source-checks.json',dict(status='PASS',originalSources=rows,existingProductBases=local,preparedPayloadSources=checked,currentRootSeams=seams,sourceOnly=True,compilePhase='04',nativeRuntimeExecuted=False,networkOperations=['official pinned mpv headers only'],newApiClientStoreDecoderEngine=False,fullOriginalSourcePreservedOutsideDeclaredHunks=True))
 save(HERE/'history.json',dict(run01='Kotlin compile succeeded; postcompile overlap audit failed because own two existing MPV files also emit MpvNodes/MpvCallException; exact allowed file family corrected. Preserved run01 byte evidence. No runtime attempted.',run02='5-source compile PASS; old constructor ABI preserved with secondary required target constructor.',run03='6-source compile PASS with real local GIF/WebP consumer.',run04='Final6-source compile PASS; lifecycle/cancel/error feedback/opaque padding source guard tightened. No runtime/native acceptance claimed.'))
 print(json.dumps(dict(status='PASS',originalSources=len(rows),payloadSources=len(checked),rootSeams=len(seams),sourceChecksSha256=sha((HERE/'source-checks.json').read_bytes())),indent=2))
if __name__=='__main__':main()
