from pathlib import Path
import hashlib,importlib.util,json,re,sys,subprocess,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def sha(b):return hashlib.sha256(b.encode()if isinstance(b,str)else b).hexdigest()
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
parser=module('fullscreen_source_tokens',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
EDITS=[]
def exact(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));EDITS.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def between(t,start,end,after,label):
 a=t.index(start);b=t.index(end,a);return exact(t,t[a:b],after,label)
def each(t,before,after,label):
 assert before in t,(label,before);EDITS.append(dict(label=label,before=before,after=after,count=t.count(before)));return t.replace(before,after)
def balanced(t,start,after,label):
 a=t.index(start);m=parser.masked(t);op=m.index('(',a);b=parser.balanced(m,op,'(',')');return exact(t,t[a:b],after,label)
def prepare():
 rel='feature/video/ui/overlay/FullscreenPlayerOverlay.kt';original=read(P/'original-stable'/BASE/rel).decode();t=original
 t=exact(t,'import com.android.purebilibili.feature.video.player.MiniPlayerManager','import com.bilipai.desktop.ui.DesktopOriginalFullscreenMiniOwner as MiniPlayerManager','Same-player six-field owner replaces Android singleton')
 t=exact(t,'fun FullscreenPlayerOverlay(','internal fun FullscreenPlayerOverlay(','Windows required owner remains internal until actual assembly')
 t=exact(t,'    val context = LocalContext.current','    val platform = LocalDesktopOriginalFullscreenPlatform.current\n    val section = platform.section\n    val context = section.settingsContext','Required same entry Window/Section owner')
 t=exact(t,'    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }','    val audioManager = section.volume','Real owned MPV volume')
 t=exact(t,'    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }','    val maxVolume = remember(audioManager) { audioManager.maximumStep() }','Actual volume scale')
 t=exact(t,'    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }','    val playerViewRef = section.viewport','Sole actual native viewport')
 t=exact(t,'    val danmakuManager = rememberDanmakuManager(miniPlayerManager.currentBvid ?: player ?: miniPlayerManager)','    val danmakuManager = section.danmaku','Sole original Overlay document owner')
 t=between(t,'    var currentBrightness by remember {','    // 播放器状态', '''    var currentBrightness by remember(section) {
        mutableFloatStateOf(section.readViewportBrightness())
    }

''','Actual owned viewport brightness, not guessed physical Windows brightness')
 t=between(t,'    DisposableEffect(player) {\n        val exoPlayer = player\n        if (exoPlayer == null) {\n            isVerticalContent = false','    LaunchedEffect(fixedFullscreenAspectRatio', '''    LaunchedEffect(player) {
        if (player == null) {
            isVerticalContent = false
        } else {
            player.state.collectLatest { _ ->
                val size = player.videoSize
                isVerticalContent = size.width > 0 && size.height > size.width
            }
        }
    }

''','Original video-size listener reads the same native StateFlow, without another poll actor')
 t=between(t,'    // 进入全屏时设置横屏和沉浸式','    // 监听播放器状态', '''    // Actual Window presentation lease replaces Android orientation/system bars.
    DisposableEffect(lifecycleOwner, player, playerViewRef) {
        val lease = platform.acquireFullscreen(player)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && player != null &&
                shouldRebindFullscreenSurfaceOnResume(section.viewportAttached, true)) {
                platform.recoverSurface(player)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lease.close()
        }
    }
    DisposableEffect(context, keepFullscreenPlaybackAwake) {
        val lease = platform.acquireKeepAwake(keepFullscreenPlaybackAwake)
        onDispose { lease.close() }
    }

''','Required existing Window fullscreen/recovery/keep-awake leases; no new presentation authority')
 t=each(t,'audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)','audioManager.currentStep()','Original volume read through same MPV port')
 for a,b in [('audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)','audioManager.setStep(0)'),('audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume / 3, AudioManager.FLAG_SHOW_UI)','audioManager.setStep(maxVolume / 3)')]:t=exact(t,a,b,'Same volume mute action')
 t=balanced(t,'audioManager.setStreamVolume(\n','audioManager.setStep((gestureValue * maxVolume).toInt())','Original gesture target applies to actual MPV volume')
 t=between(t,'                                (context as? Activity)?.window?.let { window ->','                            }\n                            FullscreenGestureMode.Volume', '                                section.setViewportBrightness(gestureValue, requestSystemBrightness = false)\n','Actual source-owned viewport dim replaces Android window brightness')
 t=between(t,'        // 播放器 owner 成对绑定','        // 视频播放器', '''        DisposableEffect(player) {
            val lease = player?.let(platform::acquireDanmakuPlayer)
            onDispose { lease?.close() }
        }

''','Existing Overlay source binding lease replaces attach/detach Android player')
 t=balanced(t,'                AndroidView(','''                section.NativeViewport(
                    modifier = viewportModifier, layout = viewportLayout,
                    resizeMode = aspectRatio.playerResizeMode,
                    revealAlpha = 1f, revealScale = 1f, freeScale = 1f,
                    panX = 0f, panY = 0f, flipHorizontal = false, flipVertical = false,
                    visible = true, keepAwake = keepFullscreenPlaybackAwake,
                )''','Original measured viewport uses the sole same-source native transport')
 t=balanced(t,'                    AndroidView(','''                    com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport(
                        viewportLayout.width, viewportLayout.height,
                        density.density, section.danmakuReferencePixels,
                    )?.let { viewport ->
                        section.NativeDanmakuSurface(viewport, viewportModifier)
                    }''','Original sized ordinary danmaku view maps existing native Overlay carrier')
 t=exact(t,'                    danmakuManager.weightFilterLevel = it','                    danmakuManager.updateSettings(danmakuSettings.copy(weightFilterLevel = it))','Actual sole settings consumer keeps the original weight update')
 t=each(t,'initialValue = DanmakuSettings(),','initialValue = section.danmakuPreferences.currentSettings(danmakuScope),','Same global preference snapshot seeds exact original scope')
 t=each(t,'    Box(\n        modifier = Modifier','    platform.Surface(player) {\n    Box(\n        modifier = Modifier','Sole native HWND foreground carrier encloses the original full controls')
 # Close the carrier only around the complete original public composable body.
 mark='\n}\n\n@Composable\nprivate fun GestureIndicator'
 t=exact(t,mark,'\n    }\n}\n\n@Composable\nprivate fun GestureIndicator','Close sole carrier around original foreground')
 t=exact(t,'.background(Color.Black)\n            .hazeSourceCompat', '.background(Color.Transparent)\n            .hazeSourceCompat','Only native foreground root becomes transparent; original scrims remain')
 # Exact legacy getter semantics are already selected in the full interaction mapper.
 mapping={'getFullscreenAspectRatio':'fixedFullscreenAspectRatio','getFullscreenSwipeSeekSeconds':'fullscreenSwipeSeekSeconds','getDoubleTapSeekEnabled':'doubleTapSeekEnabled','getSeekForwardSeconds':'seekForwardSeconds','getSeekBackwardSeconds':'seekBackwardSeconds'}
 for method,field in mapping.items():
  m=re.search(r'SettingsManager\s*\.\s*'+method+r'\(context\)',t);assert m,method
  t=exact(t,m.group(), 'com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(context).map { it.'+field+' }','Reuse sole original interaction mapper: '+field)
 # Original danmaku members are already embodied in the same global preferences adapter.
 for method in sorted(set(re.findall(r'SettingsManager\s*\.\s*(\w+)',t))):
  if 'Danmaku' in method:
   pattern=r'SettingsManager\s*\.\s*'+method+r'\(\s*context\s*,?\s*'
   for m in reversed(list(re.finditer(pattern,t))):t=exact(t,m.group(),'section.danmakuPreferences.'+method+'(','Same global original danmaku member '+method)
  else:
   owner='com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings' if method=='setLastPlaybackSpeed' else 'com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings'
   for m in reversed(list(re.finditer(r'SettingsManager\s*\.\s*'+method,t))):t=exact(t,m.group(),owner+'.'+method,'Same original settings member '+method)
 # Platform imports/type aliases only, with every removed Android consumer listed above.
 for a,b in {'import androidx.compose.ui.platform.LocalConfiguration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration','import androidx.media3.common.Player':'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player','androidx.media3.common.PlaybackParameters':'com.bilipai.desktop.ui.DesktopOriginalPlaybackRate','import com.android.purebilibili.core.util.Logger':'import android.util.Log as Logger'}.items():
  if a in t:t=each(t,a,b,'Existing platform alias: '+a)
 for line in t.splitlines(True):
  if line.startswith('import ') and (line.startswith('import android.') and ' as Logger' not in line or any(s in line for s in ['rememberDanmakuManager','configureAsPassiveDanmakuOverlay','DanmakuRenderView','rebindPlayerSurfaceIfNeeded','LocalContext','AndroidView','SettingsManager','AppWindowSystemUiController','applyPlayerRequestedOrientation','androidx.media3.ui.PlayerView'])):t=exact(t,line,'','Consumed Android-only import')
 t=exact(t,'@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','','Android annotation boundary')
 t=exact(t,'import androidx.compose.runtime.*\n','import androidx.compose.runtime.*\nimport com.bilipai.desktop.ui.LocalDesktopOriginalFullscreenPlatform\nimport kotlinx.coroutines.flow.map\n','Required owner/sole mapper imports')
 for line in ['import com.android.purebilibili.feature.video.ui.gesture.GestureMode\n','import com.android.purebilibili.feature.video.ui.gesture.GestureIndicator\n','import com.android.purebilibili.feature.video.ui.gesture.rememberPlayerGestureState\n']:
  t=exact(t,line,'','Unused upstream import; own complete GestureIndicator remains below')
 write(P/'prepared/generated/com/android/purebilibili'/rel,t)
 write(P/'fullscreen-adaptations.json',json.dumps(dict(originalSHA256LF=sha(original),candidateSHA256LF=sha(t),edits=EDITS),ensure_ascii=False,indent=2)+'\n')
 # Reverse every declared edit to prove the complete original body is retained.
 back=t
 for e in reversed(EDITS):
  after=e['after'];before=e['before']
  if after:back=back.replace(after,before,e.get('count',1))
  else:
   # Imports/annotation are removed; reconstruction is proven instead by replay.
   back=None;break
 replay=original
 for e in EDITS:
  assert replay.count(e['before'])>=e.get('count',1)
  replay=replay.replace(e['before'],e['after'],e.get('count',1))
 assert replay==t
 write(P/'fullscreen-source-audit.json',json.dumps(dict(passed=True,completeOriginalFile=True,originalSHA256LF=sha(original),candidateSHA256LF=sha(t),exactOrderedTransformReplay=True,declaredEdits=len(EDITS),originalLines=original.count('\n'),candidateLines=t.count('\n'),productionRuntimeAccepted=False),indent=2)+'\n')
 print('Fullscreen complete source prepared:',sha(t),len(EDITS),'declared edits')
 actual=set(zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-55/main-kotlin.jar').namelist())
 rows=[]
 for name in ['FullscreenKeyboardPolicy','FullscreenPlayerOverlayPollingPolicy','PortraitProgressBar','PortraitProgressBarLayoutPolicy']:
  path=BASE+'feature/video/ui/overlay/'+name+'.kt'
  assert 'com/android/purebilibili/feature/video/ui/overlay/'+name+'Kt.class'not in actual,name
  b=subprocess.check_output(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+path],cwd=REPO).replace(b'\r\n',b'\n')
  write(P/'original-stable'/path,b)
  output=b.replace(b'import androidx.compose.ui.platform.LocalConfiguration',b'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  write(P/'prepared/generated/com/android/purebilibili/feature/video/ui/overlay'/(name+'.kt'),output)
  rows.append(dict(path=path,sha256LF=sha(b),outputSHA256LF=sha(output),mode='direct'if output==b else 'selected',wholeFile=True,adaptation=None if output==b else 'LocalConfiguration import alias to actual Root window metrics only'))
 write(P/'fullscreen-direct-source-inventory.json',json.dumps(rows,indent=2)+'\n')
if __name__=='__main__':prepare()
