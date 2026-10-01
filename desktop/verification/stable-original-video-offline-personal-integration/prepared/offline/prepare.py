from pathlib import Path
import sys, json, hashlib, subprocess, importlib.util, re
sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent
MAIN=LANE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
PIN='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
changes=[]; inventory=[]
def sha(v): return hashlib.sha256(v).hexdigest()
def original(path):
 raw=subprocess.check_output(['git','show',PIN+':'+path],cwd=REPO)
 s=raw.decode().replace('\r\n','\n')
 assert (REPO/path).read_text(encoding='utf-8').replace('\r\n','\n')==s,path
 p=LANE/'original-stable'/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
 inventory.append(dict(path=path,commit=PIN,gitBlobSHA256=sha(raw),lfSHA256=sha(s.encode()),retained=str(p)))
 return s
def emit(path,s):
 p=LANE/'prepared'/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n');return p
def replace(s,a,b,label,count=1):
 assert s.count(a)==count,(label,s.count(a),count)
 start=0
 for i in range(count):
  index=s.index(a,start)
  changes.append(dict(label=label,before=a,after=b,count=1,index=index))
  s=s[:index]+b+s[index+len(a):];start=index+len(b)
 return s
path=BASE+'feature/download/OfflineVideoPlayerScreen.kt'
raw=original(path);s=raw
imports=[x for x in s.splitlines() if x.startswith('import ') and (x.startswith('import android.') or x.startswith('import androidx.core.view.') or x.startswith('import androidx.media3.') or x.startswith('import androidx.compose.ui.viewinterop.') or x in ['import androidx.compose.ui.platform.LocalContext','import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo','import com.android.purebilibili.core.util.applyPlayerRequestedOrientation','import com.android.purebilibili.feature.video.player.MiniPlayerManager','import com.android.purebilibili.feature.video.danmaku.configureAsPassiveDanmakuOverlay','import com.android.purebilibili.feature.video.danmaku.rememberDanmakuManager','import com.android.purebilibili.danmaku.engine.DanmakuRenderView','import com.android.purebilibili.core.store.SettingsManager'])]
for x in imports:s=replace(s,x+'\n','', 'platform import '+x)
s=replace(s,'@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','','Media3 annotation')
s=replace(s,'import androidx.compose.ui.platform.LocalContext\n','', 'unreachable',0) if False else s
s=replace(s,'import com.android.purebilibili.core.ui.ContainerLevel\n','import com.android.purebilibili.core.ui.ContainerLevel\nimport com.bilipai.desktop.ui.*\nimport androidx.compose.ui.platform.LocalDensity\nimport com.android.purebilibili.core.store.player.DesktopOriginalLongPressSpeedSettings\n','Windows actual imports')
a=s[s.index('    val context = LocalContext.current'):s.index('    val playerChromeProfile')]
s=replace(s,a,'''    val bindings = requireNotNull(LocalDesktopOriginalOfflineBindings.current) { "Original offline player requires its actual Root owner" }
    val context = bindings.context
    val density = LocalDensity.current.density
    val maxVolume = 100
''','required actual Root and app-volume boundary')
s=replace(s,'    val tasks by DownloadManager.tasks.collectAsStateWithLifecycle()','    val tasks by bindings.tasks.collectAsStateWithLifecycle()', 'same managed queue view')
a=s[s.index('    val danmakuManager = rememberDanmakuManager'):s.index('    val task = tasks[currentTaskId]')]
s=replace(s,a,'''    val danmakuManager = remember(bindings, currentTaskId) { bindings.danmaku(currentTaskId) }
    val danmakuSettingsScope = bindings.presentation.currentPresentation().originalScope()
    val danmakuSettings by remember(bindings, danmakuSettingsScope) {
        bindings.preferences.getDanmakuSettings(danmakuSettingsScope)
    }.collectAsStateWithLifecycle(initialValue = bindings.preferences.currentSettings(danmakuSettingsScope))
    val longPressSpeed by DesktopOriginalLongPressSpeedSettings
        .getLongPressSpeed(context)
        .collectAsStateWithLifecycle(initialValue = DEFAULT_LONG_PRESS_SPEED)
''','same canonical settings and explicit Windows presentation')
s=replace(s,'''    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build()
    }''','''    val player = remember(bindings, file.absolutePath, task.id) {
        bindings.control(task.id)
    }''','reuse actual retained MPV control')
s=replace(s,'    // 创建播放器\n','''    if (bindings.backend.player == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppText("播放器未能初始化", color = Color.White)
                Spacer(Modifier.height(16.dp))
                AppButton(onClick = onBack) { AppText("返回") }
            }
        }
        return
    }

    // 创建播放器
''','truthful Windows native initialization failure before surface creation')
s=replace(s,'DownloadDanmakuAssetService.readLocalSource(task)','bindings.readLocalDanmaku(task)','same manager managed-file validation')
s=replace(s,'activePlayer: ExoPlayer','activePlayer: DesktopOfflineMpvControl','actual control type')
s=replace(s,'DownloadManager.updatePlaybackPosition(', 'bindings.updatePlaybackPosition(', 'same owned manager checkpoint',2)
s=replace(s,'    val task = tasks[currentTaskId]','''    val task = tasks[currentTaskId]
    val actualSourceVersion = bindings.backend.memory.sourceVersion.takeIf { bindings.backend.memory.current == currentTaskId }
''','owned native-version publication triggers original effects')
s=replace(s,'LaunchedEffect(danmakuManager, danmakuSettings)','LaunchedEffect(danmakuManager, actualSourceVersion, danmakuSettings)','apply original settings once the owned native source publishes')
s=replace(s,'LaunchedEffect(danmakuManager, task.id, localDanmakuSource)','LaunchedEffect(danmakuManager, actualSourceVersion, task.id, localDanmakuSource)','load original assets for the actual accepted native source')
s=replace(s,'LaunchedEffect(danmakuManager, showDanmakuLayer)','LaunchedEffect(danmakuManager, actualSourceVersion, showDanmakuLayer)','apply original layer visibility for the actual accepted source')
a=s[s.index('    // 获取 Activity'):s.index('    // 全屏切换函数')]
s=replace(s,a,'''    // Windows window/chrome lease belongs to the actual Root window, not a phone orientation lock.
    fun applyWindowMode(fullscreen: Boolean) { bindings.window.applyFullscreen(fullscreen) }

''','Root fullscreen and chrome lease')
a=s[s.index('    var previousOfflineDisplayRole'):s.index('    LaunchedEffect(player, file.absolutePath, task.id)')]
s=replace(s,a,'''    LaunchedEffect(bindings.window, isFullscreen) { applyWindowMode(isFullscreen) }

''','Windows display owner instead of Android foldable role')
a=s[s.index('    LaunchedEffect(player, file.absolutePath, task.id)'):s.index('    DisposableEffect(player, task.id)')]
s=replace(s,a,'''    LaunchedEffect(player, file.absolutePath, task.id) {
        player.load { nextTaskId -> currentTaskId = nextTaskId; showControls = true }
    }

''','one versioned load uses exact task path and restored position')
a=s[s.index('    DisposableEffect(player, task.id)'):s.index('    LaunchedEffect(danmakuManager, actualSourceVersion, task.id, localDanmakuSource)')]
s=replace(s,a,'''    DisposableEffect(player, task.id) {
        onDispose {
            persistCurrentPlaybackPosition(task, player)
            bindings.media.clearIfOwned(player)
            player.release()
            bindings.window.restoreCurrentRootChrome()
        }
    }

''','exact source release and actual Root metadata/chrome retirement')
s=replace(s,'object : Player.Listener','object : DesktopOfflineMpvListener','native observer interface')
a=s[s.index('            miniPlayerManager.setVideoInfo('):s.index('\n        }\n\n        val listener =',s.index('            miniPlayerManager.setVideoInfo('))]
s=replace(s,a,'''            bindings.media.publish(offlineMiniPlayerPayload, player)''','same system media actor receives original metadata payload')
a=s[s.index('                miniPlayerManager.updateMediaMetadata('):s.index('\n            }\n\n            override fun onPlaybackStateChanged',s.index('                miniPlayerManager.updateMediaMetadata('))]
s=replace(s,a,'                bindings.media.publish(offlineMiniPlayerPayload, player)','original playing callback metadata publication')
s=replace(s,'Player.STATE_IDLE','DesktopOfflinePlaybackState.IDLE','actual MPV state mapping idle')
s=replace(s,'Player.STATE_ENDED','DesktopOfflinePlaybackState.ENDED','actual MPV state mapping ended')
s=replace(s,'                        val density = context.resources.displayMetrics.density\n','','real Compose viewport density')
s=replace(s,'audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)','player.volumePercent','actual app volume getter')
a=s[s.index('                            val attributes = getActivity()'):s.index('\n                        }\n                    },\n                    onDragEnd',s.index('                            val attributes = getActivity()'))]
s=replace(s,a,'                            startBrightness = player.viewportBrightness','real source-owned video viewport brightness')
s=replace(s,'context.resources.displayMetrics.widthPixels','size.width','actual owned viewport width')
s=replace(s,'context.resources.displayMetrics.heightPixels','size.height','actual owned viewport height',2)
a='''                                        getActivity()?.window?.attributes = getActivity()?.window?.attributes?.apply {
                                            screenBrightness = newBrightness
                                        }'''
s=replace(s,a,'''                                        if (!player.setViewportBrightness(newBrightness)) {
                                            isGestureVisible = false
                                            gestureMode = GestureMode.None
                                            bindings.feedback("当前视频视口亮度暂不可用")
                                            return@detectDragGestures
                                        }''','actual viewport dim admission, no unavailable capability success')
s=replace(s,'audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)','player.volumePercent = targetVol','actual MPV app-volume setter')
s=replace(s,'player.playbackParameters.speed','player.playbackSpeed','actual MPV playback-speed state')
a=s[s.index('        // 1. PlayerView'):s.index('        // 2. 封面图')]
s=replace(s,a,'        // The required native carrier owns the sole MPV HWND and renders this whole foreground above it.\n        \n','same Root native surface and passive overlay carrier')
# Wrap the entire original Box, including its two pointerInput blocks and complete original chrome.
s=replace(s,'    Box(\n        modifier = Modifier\n            .fillMaxSize()\n            .background(Color.Black)\n            // 🎛️','    bindings.surface.Render(player, Modifier.fillMaxSize()) {\n    Box(\n        modifier = Modifier\n            .fillMaxSize()\n            .background(Color.Transparent)\n            // 🎛️','required real foreground carrier, whole original UI')
s=replace(s,'\n    }\n}\n\n/**\n * 进度信息','\n    }\n    }\n}\n\n/**\n * 进度信息','close whole foreground carrier')
assert 'import android.' not in s and 'ExoPlayer' not in s and 'PlayerView' not in s and 'miniPlayerManager' not in s
emit('generated/com/android/purebilibili/feature/download/OfflineVideoPlayerScreen.kt',s)
(LANE/'ui-platform-delta.json').write_text(json.dumps(changes,ensure_ascii=False,indent=2),encoding='utf-8')
reversed=s
for c in list(changes)[::-1]:
 i=c['index'];a=c['after']
 assert reversed[i:i+len(a)]==a,('reverse',c['label'])
 reversed=reversed[:i]+c['before']+reversed[i+len(a):]
assert reversed==raw
# Exact before/after ordered replay is the inverse proof, including removed imports and platform blocks.
replay=raw
for c in changes:
 i=c['index'];a=c['before'];assert replay[i:i+len(a)]==a
 replay=replay[:i]+c['after']+replay[i+len(a):]
assert replay==s
spec=importlib.util.spec_from_file_location('media',REPO/'desktop/tools/extract-upstream-media.py');media=importlib.util.module_from_spec(spec);spec.loader.exec_module(media)
parser=media.parser_for(REPO)
p=BASE+'feature/download/OfflineVideoPlaybackPolicy.kt';r=original(p)
names=['resolveOfflineVideoStartFullscreen','shouldResumePlaybackAfterOfflineSeek','shouldShowOfflineDanmakuControl','shouldShowOfflineDanmakuLayer','resolveOfflineSeekProgressFromTouch','resolveOfflineSeekPositionFromTouch']
def policyfunction(r,n):
 begin=r.index('internal fun '+n+'(')
 next=re.search(r'\ninternal (?:fun|class|enum)',r[begin+1:])
 return r[begin:begin+1+next.start()].rstrip() if next else r[begin:].rstrip()
body='package com.android.purebilibili.feature.download\nimport com.bilipai.desktop.ui.DesktopOfflinePlaybackState\n\n'+'\n\n'.join(policyfunction(r,n).replace('Player.STATE_ENDED','DesktopOfflinePlaybackState.ENDED') for n in names)+'\n'
emit('generated/com/android/purebilibili/feature/download/DesktopOfflineInteractionPolicy.kt',body)
p=BASE+'feature/download/OfflinePlaybackSessionPolicy.kt';r=original(p);emit('generated/com/android/purebilibili/feature/download/OfflinePlaybackSessionPolicy.kt',r)
p=BASE+'core/store/player/PlayerSettingsStore.kt';r=original(p)
getter=media.function(r,'getLongPressSpeed',parser)
getter=getter.replace('context: Context','context: DesktopPluginContext').replace('context.settingsDataStore.data','context.store.snapshot("settings")')
emit('generated/com/android/purebilibili/core/store/player/DesktopOriginalLongPressSpeedSettings.kt','''package com.android.purebilibili.core.store.player
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.android.purebilibili.core.store.DEFAULT_LONG_PRESS_SPEED
import com.android.purebilibili.core.store.normalizeLongPressSpeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
private fun floatPreferencesKey(name:String)=DesktopPreferenceKey(name) { value -> (value as? JsonPrimitive)?.floatOrNull }
private val longPressSpeedPreferenceKey = floatPreferencesKey("long_press_speed")
object DesktopOriginalLongPressSpeedSettings {
'''+getter+'\n}\n')
(LANE/'source-inventory.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'generated':4,'originalUI':len(raw.splitlines()),'platformChanges':len(changes),'pin':PIN}))
