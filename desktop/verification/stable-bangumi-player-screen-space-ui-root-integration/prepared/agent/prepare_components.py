from pathlib import Path
import difflib,hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
def exact(s,a,b):
 assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)
def between(s,a,b,c):
 assert s.count(a)==1 and s.count(b)==1,(a,b);i=s.index(a);j=s.index(b,i);return s[:i]+c+s[j:]
path='app/src/main/java/com/android/purebilibili/feature/bangumi/ui/player/BangumiPlayerComponents.kt'
raw=subprocess.check_output(['git','-C',str(C),'show',UP+':'+path]).decode('utf8').replace('\r\n','\n');s=raw
for line in ['import android.app.Activity','import android.content.Context','import android.media.AudioManager','import android.view.Surface','import android.view.View','import androidx.compose.ui.viewinterop.AndroidView','import androidx.media3.common.Format','import androidx.media3.common.Player','import androidx.media3.common.VideoSize','import androidx.media3.exoplayer.analytics.AnalyticsListener','import androidx.media3.ui.PlayerView','import com.android.purebilibili.feature.anime4k.gl.Anime4KGLSurfaceView','import com.android.purebilibili.feature.anime4k.isAnime4KGles3Available','import com.android.purebilibili.danmaku.engine.DanmakuRenderView','import com.android.purebilibili.feature.video.ui.section.VideoOutputRouter','import com.android.purebilibili.feature.video.util.captureAndSaveVideoScreenshot']:
 s=exact(s,line+'\n','')
s=exact(s,'import androidx.compose.ui.platform.LocalContext\n','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\n')
s=exact(s,'import com.android.purebilibili.core.store.SettingsManager\n','import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings as SettingsManager\n')
s=exact(s,'import androidx.media3.exoplayer.ExoPlayer\n','import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer\n')
s=exact(s,'import com.android.purebilibili.feature.video.danmaku.DanmakuManager\n','import com.bilipai.desktop.ui.DesktopOriginalSectionDanmakuPort as DanmakuManager\n')
s=exact(s,'@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','')
s=exact(s,'fun BangumiPlayerView(','internal fun BangumiPlayerView(')
s=exact(s,'    val context = LocalContext.current\n','    val platform = LocalDesktopOriginalVideoSectionPlatform.current\n    val context = platform.settingsContext\n')
s=exact(s,'    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }','    val audioManager = platform.volume')
s=exact(s,'    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }','    val maxVolume = audioManager.maximumStep()')
s=exact(s,'    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }','    val playerViewRef = platform.viewport')
s=between(s,'    val registeredPlugins by PluginManager.pluginsFlow.collectAsStateWithLifecycle()','    // 手势状态', '''    // Same existing native enhancement owner; its capability/error/frame readback
    // replaces only the Android GLES surface/router boundary.
    val anime4kConfig by platform.enhancementConfig.collectAsStateWithLifecycle()
    val enhancementState by platform.enhancementState.collectAsStateWithLifecycle()
    val nativeReadback by exoPlayer.state.collectAsStateWithLifecycle()
    val videoSizeState = exoPlayer.videoSize.let { it.width to it.height }
    val anime4kGlesAvailable = enhancementState.available
    val videoEnhancementEnabled = enhancementState.requested
    val anime4kBypassReason = enhancementState.bypassReason
    LaunchedEffect(hostLifecycleStarted) { platform.setViewportActive(hostLifecycleStarted) }
    DisposableEffect(platform) {
        val lease = platform.acquireViewportLease()
        onDispose { lease.close() }
    }

''')
s=between(s,'    var currentBrightness by remember {','    // 播放器状态', '''    var currentBrightness by remember(platform) {
        mutableFloatStateOf(platform.readViewportBrightness())
    }

''')
s=exact(s,'            .background(Color.Black)','            .background(Color.Transparent)')
s=exact(s,'audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)','audioManager.currentStep()')
s=between(s,'                                    (context as? Activity)?.window?.let { window ->','                                }\n                                BangumiGestureMode.Volume', '''                                    platform.setViewportBrightness(gestureValue, requestSystemBrightness = false)
''')
s=exact(s,'''                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        newVolumeStep,
                                        0
                                    )''','                                    audioManager.setStep(newVolumeStep)')
s=between(s,'            // 视频输出统一交给路由，避免 PlayerView 与 Anime4K 同时争抢 Surface。','        // 手势指示器（横屏：全部，竖屏：仅亮度和音量）', '''            // Original geometry and resize choice drive the sole native HWND viewport.
            platform.NativeViewport(
                modifier = with(density) { Modifier.requiredSize(
                    width = playerFrameViewport.width.toDp(),
                    height = playerFrameViewport.height.toDp()) },
                layout = playerFrameViewport, resizeMode = currentAspectRatio.playerResizeMode,
                revealAlpha = 1f, revealScale = 1f, freeScale = 1f, panX = 0f, panY = 0f,
                flipHorizontal = false, flipVertical = false,
                visible = hostLifecycleStarted, keepAwake = hostLifecycleStarted,
            )
        }

        if (danmakuEnabled) {
            val density = LocalDensity.current.density
            var size by remember(platform) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            val viewport = com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport(
                size.width, size.height, density, platform.danmakuReferencePixels)
            val modifier = Modifier.fillMaxSize().padding(top = danmakuTopInset)
                .clipToBounds().onSizeChanged { size = it }
            if (viewport != null) platform.NativeDanmakuSurface(viewport, modifier)
            else Box(modifier)
        }

''')
s=exact(s,'import androidx.compose.ui.input.pointer.pointerInput\n','import androidx.compose.ui.input.pointer.pointerInput\nimport androidx.compose.ui.layout.onSizeChanged\n')
s=between(s,'                val playerView = playerViewRef','            onReloadVideo = onReloadVideo,', '''                scope.launch {
                    val success = platform.captureAndSaveScreenshot(
                        videoWidth = exoPlayer.videoSize.width,
                        videoHeight = exoPlayer.videoSize.height,
                        title = subtitle.ifBlank { title.ifBlank { "bangumi" } }
                    )
                    onShowMessage(if (success) "截图已保存（PNG）" else "截图失败，请稍后重试")
                }
            },
''')
s=between(s,'            onAnime4kToggle = { enabled ->','            onShowMessage = onShowMessage', '''            onAnime4kToggle = { enabled -> platform.setCurrentVideoEnhancementEnabled(enabled) },
            onVideoEnhancementAlgorithmChange = platform.enhancementActions::setAlgorithm,
            onAnime4kPresetChange = platform.enhancementActions::setPreset,
            onFsrSharpnessChange = platform.enhancementActions::setFsrSharpness,
''')
assert not any(t in s for t in ['AndroidView',' PlayerView(','android.provider.','getStreamVolume','getStreamMaxVolume','anime4kPipelineFailed','videoEnhancementSessionOverride','onInputSurfaceChanged'])
a=raw.splitlines(keepends=True);b=s.splitlines(keepends=True);edits=[]
for tag,i,j,k,l in difflib.SequenceMatcher(None,a,b,autojunk=False).get_opcodes():
 if tag!='equal':edits.append(dict(startLine=i,endLineExclusive=j,before=''.join(a[i:j]),after=''.join(b[k:l]),beforeSha256LF=sha(''.join(a[i:j]))))
output='com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiPlayerComponents.kt'
write(P/'generated'/output,'// Complete original v0.2.3 body; Android surface/audio/GLES-only boundaries use the same Root Section.\n'+s)
write(P/'components-recipe.json',json.dumps(dict(upstreamCommit=UP,originalPath=path,originalSha256LF=sha(raw),output=output,adaptedSha256LF=sha(s),edits=edits,fullOriginalBody=True,actualRootConsumer=False,platformBoundaries=['One same Section volume and viewport brightness; original gesture formulas unchanged','Android PlayerView/GLES surfaces/listeners replaced by actual same native enhancement state/viewport','DanmakuRenderView maps same native Overlay without another parser/transport','Screenshot maps same frame/Locations writer; Windows PNG wording','Android player creation/release absent']),ensure_ascii=False,indent=2)+'\n')
print('Prepared full Components body',len(raw.splitlines()),'original lines',len(edits),'explicit boundary edits')
policyPath='app/src/main/java/com/android/purebilibili/feature/bangumi/policy/BangumiUiPolicy.kt'
policy=subprocess.check_output(['git','-C',str(C),'show',UP+':'+policyPath]).decode('utf8').replace('\r\n','\n')
first=policy.index('internal fun resolveBangumiPlayerTopControlsPaddingTopDp(');last=policy.index('internal fun resolveBangumiFullscreen(')
orientationStart=policy.index('internal fun resolveBangumiToggleOrientationTarget(');orientationEnd=policy.index('internal data class BangumiEpisodePreviewWindow(')
orientationRaw=policy[orientationStart:orientationEnd]
orientation=orientationRaw.replace('ActivityInfo.SCREEN_ORIENTATION_PORTRAIT','1 /* Original portrait intent; Windows adapter maps presentation */').replace('ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE','6 /* Original sensor-landscape intent; no physical rotation fabricated */')
body=policy[first:last]+orientation;policyOutput='com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerUiPolicy.kt'
write(P/'generated'/policyOutput,'package com.android.purebilibili.feature.bangumi\n\n'+body)
write(P/'ui-policy-recipe.json',json.dumps(dict(upstreamCommit=UP,originalPath=policyPath,originalSha256LF=sha(policy),output=policyOutput,exactOriginalBody=policy[first:last]+orientationRaw,exactBodySha256LF=sha(policy[first:last]+orientationRaw),adaptedBodySha256LF=sha(body),selectedNames=['resolveBangumiPlayerTopControlsPaddingTopDp','resolveBangumiDanmakuTopInsetDp','resolveBangumiPortraitPlayerContainerTopPaddingDp','resolveBangumiToggleOrientationTarget'],platformBoundary='ActivityInfo constants 1/6 retained as original Windows presentation intents',actualRootConsumer=False),ensure_ascii=False,indent=2)+'\n')
