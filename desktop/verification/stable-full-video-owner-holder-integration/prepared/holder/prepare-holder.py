from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023'
ORIGIN='app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode())
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
spec=importlib.util.spec_from_file_location('mask',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');mask=importlib.util.module_from_spec(spec);spec.loader.exec_module(mask)
raw=wide(P/'original-stable'/ORIGIN).read_bytes().replace(b'\r\n',b'\n').decode();assert sha(raw)=='4fe3b94adbd3a4f9eea165e68b8efdee0f06d74c78bc3de9269b2fa067ba8db3';t=raw;edits=[]
def exact(before,after,label):
 global t
 assert t.count(before)==1,(label,t.count(before));at=t.index(before);edits.append(dict(at=at,before=before,after=after,label=label));t=t[:at]+after+t[at+len(before):]
def each(before,after,label,required=True):
 global t
 if before not in t:
  assert not required,(label,before)
  return
 # Store individual indexed substitutions; exact inverse reconstructs all original bytes.
 for at in reversed([m.start() for m in re.finditer(re.escape(before),t)]):
  edits.append(dict(at=at,before=before,after=after,label=label));t=t[:at]+after+t[at+len(before):]
def between(start,end,after,label):
 a=t.index(start);b=t.index(end,a);exact(t[a:b],after,label)
def call(start,after,label):
 a=t.index(start);m=mask.masked(t);op=m.index('(',a);end=mask.balanced(m,op,'(',')');exact(t[a:end],after,label)
each(' = viewModel()','','Five domain owners are explicit original objects, never constructed by another lifecycle factory')
exact('    onVideoClick: (String, android.os.Bundle?) -> Unit,','    onVideoClick: (String, Long, String?) -> Unit,','Actual immutable CID/cover navigation port; Root retains the full route key')
exact('    val context = LocalContext.current\n    val view = LocalView.current','    val platform = LocalDesktopOriginalVideoHolderPlatform.current\n    val context = platform.settingsContext\n    val section = platform.section\n    val scope = rememberCoroutineScope()\n    val PlaylistManager = platform.playlist','Required same retained Root window/settings/native/playlist views')
exact('    val sharedDanmakuManager = rememberDanmakuManager(currentBvid)','    val sharedDanmakuManager = platform.danmaku','Same source document; no another parser/session/cache')
exact('{ targetBvid: String, options: android.os.Bundle? ->','{ targetBvid: String, explicitCid: Long, targetCover: String? ->','Original three-field navigation adapter')
exact('            val explicitCid = options?.getLong(VIDEO_NAV_TARGET_CID_KEY) ?: 0L\n','','CID already captured by the typed caller')
between('                    val navOptions = android.os.Bundle','\n\n                    // 先摘掉父详情壳', '                    val navOptions = buildDesktopOriginalVideoNavigationOptions(\n                        cid = resolvedCid, coverUrl = targetCover,\n                    )','Reuse sole existing original CID/cover omission and normalization policy')
exact('onVideoClick(targetBvid, navOptions)','onVideoClick(targetBvid, navOptions?.first ?: 0L, navOptions?.second)','Same Root navigation callback, no Bundle mirror')
between('    val homeSettings by com.android.purebilibili.core.store.SettingsManager','    val tabletCommentPanelWidthPreset','    val homeSettings by platform.homeSettings.homeSettings.collectAsStateWithLifecycle()\n','Actual ready same-global original projection; no constructor-default first frame')
exact('    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE','    val isLandscape = platform.window.presentation.isLandscape','Actual Window presentation orientation')
between('    val activity = remember { context.findActivity() }','\n    // 🔧 [修复] 追踪用户是否主动请求全屏', '    val activity: DesktopOriginalVideoHolderPresentation? = platform.window.presentation\n    val isActivityInMultiWindowMode = platform.window.presentation.isInMultiWindowMode\n','Actual retained Window capability instead of inferred Android Activity')
each('context.findActivity()?.let { activity ->','activity?.let { activity ->','Existing same Root Window owner, no Android Context wrapper')
call('isActivityInMultiWindowOrFloatingMode(\n                    activity = activity,','activity.isInMultiWindowMode','Actual fullscreen presentation capability readback')
each('.applyPlayerRequestedOrientation(','.requestOrientation(','Preserved original decision feeds required Window owner')
each('activity?.requestedOrientation','activity?.currentRequestedOrientation','Actual requested presentation readback')
exact('var videoPlayerBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }','var videoPlayerBounds by remember { mutableStateOf<androidx.compose.ui.unit.IntRect?>(null) }','Actual immutable Compose pixel bounds')
between('    val window = remember { activity?.window }','    //  [新增] 恢复状态栏的函数（可复用）','''    val window = platform.window
    var entryRequestedOrientation by rememberSaveable {
        mutableIntStateOf(resolveVideoDetailEntryOrientationSnapshot(
            currentRequestedOrientation = activity?.currentRequestedOrientation,
        ))
    }
    val originalWindowChromeLease = remember(window) { window.captureEntryWindowChrome() }

''','Android system bar snapshot is a real same-Window restoration lease, explicit Windows client boundary')
between('    val restoreStatusBar = remember {','    //  [修复] 包装的 onBack','''    val restoreStatusBar = remember(window, originalWindowChromeLease) {
        { originalWindowChromeLease.close() }
    }

''','Root restores only this entry unchanged chrome registration')
exact('    val topBarActionHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }','    val topBarActionHandler = remember(scope, platform) { DesktopOriginalVideoEntryScheduler(scope, platform::isCurrent) }','Same composition request cancellation replaces Handler delayed callback')
between('    val rotationResolver = context.applicationContext.contentResolver','    val sensorAutoRotateEnabled = autoRotateEnabled && systemAutoRotateEnabled','''    val rotationSensor = platform.window.presentation.rotationSensor
    val systemAutoRotateEnabled by (rotationSensor?.automaticRotationEnabled
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    // Null sensor is explicit unsupported physical-rotation capability; it does
    // not pretend that Windows monitor/window aspect is Android accelerometer data.
''','Required optional physical sensor capability with original automatic-rotation policy')
exact('''        if (window != null) {
            AppWindowSystemUiController.ensureEdgeToEdge(window)
        }''','        val edgeToEdgeLease = window.acquireEdgeToEdge()','Actual same Window client lease')
between('            val layoutParams = window?.attributes','            restoreStatusBar()','            window.releaseEntryBrightness()\n            window.releaseEntryKeepAwake()\n            edgeToEdgeLease.close()\n','Restore only same entry/native viewport resources')
between('            val deferredActivity = activity','        }\n    }\n\n    val playbackEventState','''            window.deferEntryExit(
                navigationExit = shouldHandleAsNavigationExit,
                originalRequestedOrientation = resolveVideoDetailExitRequestedOrientation(
                    originalRequestedOrientation = entryRequestedOrientation,
                ),
            )
''','Required deferred same-entry PiP/notification/window cleanup; cannot stop transferred/replacement native subject')
exact('''        context = context,
        activity = activity,
        playerBounds = videoPlayerBounds,''','''        window = window,
        playerBounds = videoPlayerBounds,''','Original PiP throttle/bounds effect targets same Window port')
exact('    val playerState = rememberVideoPlayerState(\n        context = context,\n        viewModel = viewModel,','    val playerState = platform.BindPlayerState(','Bind same native owner projection, preserve original route/readiness/resume inputs')
between('    val subtitleAudioManager = remember {','    val subtitlePreferenceSession','''    val subtitleAutoModeMuted = remember(playerState.player, platform, currentBvid) {
        platform.streamVolumeIsMuted() || playerState.player.volume <= 0f
    }
''','Read true existing MPV/root volume, no Android AudioManager')
each('com.android.purebilibili.core.player.PlayerVolumeController\n                    .applyPreferredVolume(context, playerState.player)','platform.applyPreferredVolume(playerState.player)','Same owned player volume preference',False)
between('        val orientationListener = object : OrientationEventListener(context) {','        onDispose {\n            orientationListener.disable()',None if False else '', 'temporary') if False else None
# Preserve the complete original sensor callback policy, replacing only its host/lifetime.
a=t.index('        val orientationListener = object : OrientationEventListener(context) {');b=t.index('        onDispose {\n            orientationListener.disable()',a);c=t.index('        }\n    }',b)+len('        }')
chunk=t[a:c];body=chunk[chunk.index('            override fun onOrientationChanged(orientation: Int) {')+len('            override fun onOrientationChanged(orientation: Int) {'):chunk.index('\n            }\n        }')]
body=body.replace('hostActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE','hostActivity.isLandscape').replace('return','return@observeDegrees')
exact(chunk,'        val orientationLease = rotationSensor?.observeDegrees { orientation ->'+body+'\n        }\n        onDispose {\n            orientationLease?.close()\n            // Original settle guard survives a same-entry sensor lease handoff.\n        }','Exact original sensor target/settle/portrait-hold math over an explicit owned sensor lease')
each('SystemClock.elapsedRealtime()','platform.elapsedRealtimeMillis()','Actual monotonic Root clock')
each('android.graphics.Rect(','androidx.compose.ui.unit.IntRect(','Actual immutable Compose pixel bounds')
exact('        view = view,\n        window = window,\n        insetsController = insetsController,','        window = window,','Original pure system-bars spec passed to required actual Window consumer')
exact('WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE','2','Original system-bars policy constant; Windows consumer has explicit capability boundary')
each('com.android.purebilibili.core.util.AnalyticsHelper.logPictureInPicture(','platform.logPictureInPicture(','Required actual Root analytics')
each('com.android.purebilibili.feature.video.player.PlaylistManager.togglePlayMode()','PlaylistManager.togglePlayMode()','Same playlist view, no singleton authority')
each('android.util.Log.','android.util.Log.','Existing local logger',False)
each('com.android.purebilibili.core.util.Logger.','android.util.Log.','Existing local logging adapter')
each('configuration.orientation','if (platform.window.presentation.isLandscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT','Actual real Window orientation input',False)
aliases={
 'import android.content.pm.ActivityInfo':'import com.bilipai.desktop.ui.DesktopOriginalVideoOrientationRequest as ActivityInfo',
 'import android.content.res.Configuration':'import com.bilipai.desktop.ui.DesktopOriginalVideoWindowOrientation as Configuration',
 'import androidx.compose.ui.platform.LocalConfiguration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration',
 'import androidx.media3.common.Player':'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player',
 'import com.android.purebilibili.feature.video.state.VideoPlayerState':'import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState',
 'import com.android.purebilibili.feature.video.player.MiniPlayerManager':'import com.bilipai.desktop.ui.DesktopOriginalVideoHolderMini as MiniPlayerManager',
}
for a,b in aliases.items():each(a,b,'Existing actual platform aliases')
for line in t.splitlines(True):
 if line.startswith('import ') and (line.startswith('import android.') or any(x in line for x in ['androidx.core.view.','LocalContext','LocalView','lifecycle.viewmodel','rememberDanmakuManager','rememberVideoPlayerState','PlaybackService','feature.video.player.PlaylistManager','AppWindowSystemUiController','setWindowNavigationBarColor','setWindowStatusBarColor','core.util.applyPlayerRequestedOrientation'])):exact(line,'','Consumed Android-only import')
each('@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','','Android compiler annotation')
each('@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")\n','','Android compiler annotation')
exact('import androidx.compose.runtime.*\n','import androidx.compose.runtime.*\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform\nimport com.bilipai.desktop.ui.DesktopOriginalVideoHolderPresentation\nimport com.bilipai.desktop.ui.DesktopOriginalVideoEntryScheduler\n','Required owned platform imports')
# Verify exact indexed inverse, including every removed platform import/block.
back=t
for e in reversed(edits):
 assert back[e['at']:e['at']+len(e['after'])]==e['after'],e['label']
 back=back[:e['at']]+e['before']+back[e['at']+len(e['after']):]
assert back==raw
out='com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt';put(P/'prepared/generated'/out,t)
put(P/'holder-source-audit.json',json.dumps(dict(passed=True,sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',path=ORIGIN,originalSHA256LF=sha(raw),originalLines=len(raw.splitlines()),output=out,outputSHA256LF=sha(t),outputLines=len(t.splitlines()),completeOriginalBody=True,exactIndexedInverse=True,edits=edits,status='Prepared, remaining required adapters and compiler followups pending',productRuntimeAccepted=False),ensure_ascii=False,indent=2)+'\n')
print('Prepared complete Holder',len(raw.splitlines()),'->',len(t.splitlines()),'lines',len(edits),'indexed edits')
