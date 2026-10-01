"""Full original Section source draft. Writes only this task lane. No Main installation."""
from pathlib import Path
import hashlib, importlib.util, json, re, struct, subprocess, sys, zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'; BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def sha(b):return hashlib.sha256(b.encode()if isinstance(b,str)else b).hexdigest()
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
controls=module('section_control_tokens',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py')
SOURCES={};EDITS=[]
def source(rel):
 path=BASE+rel;b=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n');write(P/'original-stable'/path,b);SOURCES[path]=dict(sha256LF=sha(b),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip());return b.decode()
def exact(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));EDITS.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def between(t,start,end,after,label):
 a=t.index(start);b=t.index(end,a);return exact(t,t[a:b],after,label)
def balanced_call(t,start,after,label):
 a=t.index(start);mask=controls.parser.kotlin_tokens(t)
 # Use the already reviewed token mask/bracket parser to retain strings/comments.
 protocol=module('section_balanced',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 masked=protocol.masked(t);op=masked.index('(',a);b=protocol.balanced(masked,op,'(',')');return exact(t,t[a:b],after,label)
def emit(rel,t):write(P/'section-generated/com/android/purebilibili'/rel,t)
def main():
 rel='feature/video/ui/section/VideoPlayerSectionContracts.kt';t=source(rel)
 t=exact(t,'import android.os.Bundle\n','','Navigation platform type is the existing CID/cover tuple')
 t=exact(t,'import com.android.purebilibili.feature.video.state.VideoPlayerState','import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState','State is same MPV readback/required owner')
 t=exact(t,'(String, Bundle?) -> Unit = { _, _ -> }','(String, Long, String?) -> Unit = { _, _, _ -> }','Original related navigation Bundle maps exact CID/cover fields')
 emit(rel,t)
 rel='feature/video/ui/section/VideoPlayerSection.kt';t=source(rel)
 t=between(t,'    val onRecallDanmaku = actions.onRecallDanmaku\n    val context = LocalContext.current','    val configuration = LocalConfiguration.current', '''    val onRecallDanmaku = actions.onRecallDanmaku
    val platform = LocalDesktopOriginalVideoSectionPlatform.current
    val context = platform.settingsContext
    val localDensity = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val hostLifecycleStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val anime4kConfig by platform.enhancementConfig.collectAsStateWithLifecycle()
    val enhancementState by platform.enhancementState.collectAsStateWithLifecycle()
    val videoInputFormat by playerState.videoInputFormat.collectAsStateWithLifecycle()
    val videoEnhancementSessionRequested = enhancementState.requested
    val videoEnhancementEnabled = enhancementState.requested
    val shouldUseAnime4kPipeline = enhancementState.active || enhancementState.pending
    val anime4kBypassReason = enhancementState.bypassReason
    val anime4kDisplayedFirstFrame = enhancementState.active && playerState.player.firstVideoFrameReady
    val latestAnime4kPipelineRequested by rememberUpdatedState(shouldUseAnime4kPipeline)
    val latestAnime4kDisplayedFirstFrame by rememberUpdatedState(anime4kDisplayedFirstFrame)
    val latestFullscreenForOrientationRestore by rememberUpdatedState(isFullscreen)
''','Android PluginManager/GLES/InputSurface/GLView/output router maps existing owned enhancement session facts')
 t=exact(t,'    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }','    val audioManager = platform.volume','Android STREAM_MUSIC transport maps required actual volume port')
 t=exact(t,'    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }','    val playerViewRef = platform.viewport','Same sole native viewport; no PlayerView or new Canvas')
 t=between(t,'    DisposableEffect(isFullscreen, isScreenLocked) {','    // 「播放页沉浸状态栏」', '''    DisposableEffect(isFullscreen, isScreenLocked) {
        val lease = platform.setScreenshotAndOrientationLock(isFullscreen && isScreenLocked)
        onDispose { lease.close() }
    }

''','Android Activity orientation/screenshot lock maps required owned lock lease')
 t=exact(t,'playerView.isAttachedToWindow && playerView.width > 0 && playerView.height > 0','platform.viewportAttached && playerView.width > 0 && playerView.height > 0','Actual sole viewport attachment observation')
 t=balanced_call(t,'captureVideoAmbientFrame(','''platform.captureAmbientFrame(
                        targetWidth = VIDEO_STATUS_BAR_AMBIENT_SAMPLE_WIDTH_PX,
                        targetHeight = VIDEO_STATUS_BAR_AMBIENT_SAMPLE_HEIGHT_PX,
                    )''','Android bitmap ambient capture maps actual native frame capture')
 t=t.replace(')?.asImageBitmap()',')')
 t=between(t,'    val anime4kSurfaceReady =','    // 进度手势相关状态', '''    val anime4kSurfaceReady = enhancementState.active || enhancementState.pending
    val anime4kFrameVisible = enhancementState.active && anime4kDisplayedFirstFrame
    val shouldBindDirectPlayerView = shouldBindInlinePlayerView && !enhancementState.active
    // Native enhancement and its first-frame timeout are the existing version-owned session.
    LaunchedEffect(shouldBindInlinePlayerView, hostLifecycleStarted, currentPlaybackIdentity) {
        platform.setViewportActive(shouldBindInlinePlayerView && hostLifecycleStarted)
    }

''','GL surface routing/fallback maps sole native enhancement owner, not another shader actor')
 t=between(t,'    fun getActivity(): Activity?','    //  [新增] 缩放和平移状态','', 'Android Activity accessor has no Windows fake counterpart')
 t=exact(t,'        onDispose { playerViewRef = null }','        onDispose { platform.releaseViewportForThisEntry() }','Disposal only releases this entry viewport lease, never closes native player')
 t=exact(t,'    val danmakuManager = rememberDanmakuManager(bvid)','    val danmakuManager = platform.danmaku','Existing unique Overlay document/CID/source owner')
 t=between(t,'                                val attributes = getActivity()?.window?.attributes','                            }\n                        },\n                        onDragEnd', '''                                startBrightness = platform.readViewportBrightness()
''','Android window/system brightness initialization maps actual viewport brightness observation')
 t=between(t,'                                        getActivity()?.window?.attributes =','                                        gesturePercent = newBrightness', '''                                        platform.setViewportBrightness(newBrightness, requestSystemBrightness = setSystemBrightnessEnabled)
''','Actual owned viewport dim; system brightness separate explicit Windows capability')
 t=t.replace('audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)','audioManager.currentStep()').replace('audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)','audioManager.maximumStep()')
 t=balanced_call(t,'audioManager.setStreamVolume(', 'audioManager.setStep(newVolumeStep)','Same volume port applies original computed gesture step')
 for action,after in [('ADJUST_RAISE','audioManager.setStep((audioManager.currentStep() + 1).coerceAtMost(audioManager.maximumStep()))'),('ADJUST_LOWER','audioManager.setStep((audioManager.currentStep() - 1).coerceAtLeast(0))'),('ADJUST_TOGGLE_MUTE','audioManager.toggleMute()')]:
  before=re.search(r'audioManager\.adjustStreamVolume\(\s*AudioManager\.STREAM_MUSIC,\s*AudioManager\.'+action+r',\s*AudioManager\.FLAG_SHOW_UI\s*\)',t).group()
  t=exact(t,before,after,'Original keyboard volume intent maps actual same MPV volume: '+action)
 t=balanced_call(t,'captureAndSaveVideoScreenshot(','''platform.captureAndSaveScreenshot(
                                    videoWidth = videoSizeState.first,
                                    videoHeight = videoSizeState.second,
                                    title = (uiState as? VideoPlaybackUiState.Success)?.info?.title.orEmpty(),
                                )''','Original keyboard PNG screenshot uses the same owned capture/Locations port')
 t=t.replace('context.resources.displayMetrics.heightPixels','platform.viewportHeightPixels')
 t=exact(t,'        .background(Color.Black)\n        .hazeSourceCompat(overlayDrawerHazeState)', '        .background(Color.Transparent)\n        .hazeSourceCompat(overlayDrawerHazeState)', 'The sole native HWND foreground carrier stays transparent; original cover and scrims retain their own black backgrounds')
 # Sole Root preferences/700ms actor already owns these original settings; retain setters/render callbacks.
 t=between(t,'        var pendingDanmakuCloudSync by remember {','        fun buildDanmakuCloudSyncSettings(','''        val danmakuCloudSyncUiState = platform.cloudSync.uiState

''','Reuse installed one cloud sync actor and UI state')
 t=between(t,'            pendingDanmakuCloudSync = buildDanmakuCloudSyncSettings(','        //  当视频/开关状态变化时更新弹幕加载策略', '''            val requested = buildDanmakuCloudSyncSettings(
                enabled, allowScroll, allowTop, allowBottom, allowColorful,
                allowSpecial, opacity, displayAreaRatio, speed, fontScale
            )
            platform.cloudSync.queueChange { requested }
        }
        fun requestDanmakuCloudSyncNow() { if (canSyncDanmakuCloud) platform.cloudSync.requestNow() }

''','Original immutable config payload queues same Root actor; no second pending/manualVersion/debounce')
 t=between(t,'        //  横竖屏/小窗切换后，重绑 surface','        LaunchedEffect(isFullscreen) {', '''        LaunchedEffect(currentPlaybackIdentity, isFullscreen, isInPipMode, predictiveBackCancelRecoveryGeneration) {
            platform.recoverViewport(currentPlaybackIdentity, isFullscreen, isInPipMode, predictiveBackCancelRecoveryGeneration)
        }

''','Android surface rebind/recovery delegates sole native viewport/controller owner')
 t=between(t,'        LaunchedEffect(canSyncDanmakuCloud, danmakuCloudSyncEnabled) {','        // --- [优化] 视频封面逻辑 ---', '''        // Existing Root cloud actor remains outside transient UI. Root's unique native
        // Overlay attachment/lifecycle belongs to this same entry; no second owner here.

''','Remove duplicate cloud actor and Android Manager/player/view lifecycle; Root existing ownership retained')
 t=between(t,'                AndroidView(\n                    factory = { ctx ->','            }\n        }\n\n        LaunchedEffect(anime4kSurfaceReady', '''                platform.NativeViewport(
                    modifier = with(density) {
                        val sizeModifier = if (fillMaxViewport) Modifier.fillMaxSize() else Modifier.size(
                            width = viewportLayout.width.toDp(), height = viewportLayout.height.toDp())
                        sizeModifier.onSizeChanged { measuredPlayerViewportSize = it }
                            .alpha(playerSurfaceAlpha).graphicsLayer {
                                val revealAwareScaleX = scale * playerSurfaceScale
                                val revealAwareScaleY = scale * playerSurfaceScale
                                scaleX = if (isFlippedHorizontal) -revealAwareScaleX else revealAwareScaleX
                                scaleY = if (isFlippedVertical) -revealAwareScaleY else revealAwareScaleY
                                translationX = panX
                                translationY = panY
                            }
                    },
                    layout = viewportLayout, resizeMode = targetResizeMode,
                    revealAlpha = playerSurfaceAlpha, revealScale = playerSurfaceScale,
                    freeScale = scale, panX = panX, panY = panY,
                    flipHorizontal = isFlippedHorizontal, flipVertical = isFlippedVertical,
                    visible = shouldShowInlinePlayerView(isPortraitFullscreen, forceCoverDuringReturnAnimation, keepCoverForManualStart),
                    keepAwake = keepVideoPlaybackAwake,
                )
''','Original viewport geometry/reveal/transform inputs map required same sole HWND transport; native Texture alpha capability explicit')
 # The native first-frame bridge supplies one real receipt, so remove redundant Media3 Events adapter.
 t=between(t,'            // 兼容性：同时也监听 Events','            override fun onPlaybackStateChanged', '', 'Duplicate Media3 Events first-frame path uses the one typed native receipt')
 t=exact(t,'if (playerState.player.isPlaying && playerState.player.currentPosition > 0)', 'if (playerState.player.firstVideoFrameReady)','Initial reveal requires real native frame, never old position/progress heuristic')
 t=between(t,'                AndroidView(\n                    factory = { ctx ->\n                        DanmakuRenderView(ctx)','                com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlay(', '''                platform.NativeDanmakuSurface(viewport, Modifier.fillMaxSize())
''','Sole native Overlay paints ordinary/advanced raw document; no parallel DanmakuRenderView/Advanced renderer')
 t=t.replace('com.android.purebilibili.data.repository.DanmakuRepository.submitGradeDanmaku(', 'platform.submitGradeDanmaku(').replace('com.android.purebilibili.data.repository.DynamicVoteRepository.submitVote(', 'platform.submitVote(')
 t=between(t,'                    if (!enabled) {\n                        pendingDanmakuCloudSync = null','                },\n                onDanmakuSyncNowClick', '''                    platform.cloudSync.onEnabledChange(enabled)
''','Cloud enable edits notify same actor')
 t=between(t,'                onCaptureScreenshot = {','                onDownloadAudio =', '''                onCaptureScreenshot = {
                    scope.launch {
                        val success = platform.captureAndSaveScreenshot(videoSizeState.first, videoSizeState.second, uiState.info.title)
                        Toast.makeText(context, if (success) "截图已保存（PNG）" else "截图失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                },
''','Original PNG screenshot action maps same owned native capture and global Locations writer, Windows feedback wording')
 t=t.replace('onDrawerVideoClick = { vid, options ->\n                    onRelatedVideoClick(vid, options)', 'onDrawerVideoClick = { vid, cid ->\n                    onRelatedVideoClick(vid, cid, null)')
 t=between(t,'            val statusBarHeightPx = remember(context) {','            // 竖屏「屏幕顶部」模式', '            val statusBarHeightPx = platform.statusBarInsetPixels\n\n','Actual Window client inset replaces Android dimension lookup')
 t=exact(t,'Modifier.padding(top = with(LocalContext.current.resources.displayMetrics) {\n                                (topOffset / density).dp\n                            })','Modifier.padding(top = with(LocalDensity.current) { topOffset.toDp() })','Actual Compose density carries the measured inset')
 t=t.replace('player = playerState.player,\n                    onFollowClick', 'player = playerState.player.nativePlayer,\n                    onFollowClick')
 # All actual contexts/images, clock and platform-only logs remain explicit adaptations.
 mappings={
  'import androidx.compose.ui.platform.LocalConfiguration':'import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration',
  'import androidx.compose.ui.platform.LocalContext':'import coil3.compose.LocalPlatformContext as LocalContext',
  'import androidx.media3.common.Player':'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player',
  'import androidx.media3.common.PlaybackParameters':'import com.bilipai.desktop.ui.DesktopOriginalPlaybackRate as PlaybackParameters',
  'import androidx.media3.common.VideoSize':'import com.bilipai.desktop.ui.DesktopOriginalNativeVideoSize as VideoSize',
  'import androidx.media3.ui.PlayerView':'import com.bilipai.desktop.ui.DesktopOriginalPlayerViewportPort as PlayerView',
  'import com.android.purebilibili.feature.video.state.VideoPlayerState':'import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState',
  'import android.widget.Toast':'import com.bilipai.desktop.ui.DesktopOriginalPlayerFeedback as Toast',
  'import com.android.purebilibili.core.util.Logger':'import android.util.Log as Logger',
  'android.os.SystemClock.elapsedRealtime()':'(System.nanoTime() / 1_000_000L)',
  'android.util.Log.':'Logger.',
  'configuration.orientation':'(if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1)',
  'android.content.res.Configuration.ORIENTATION_LANDSCAPE':'2',
  'coil3.request.ImageRequest.Builder(context)':'coil3.request.ImageRequest.Builder(LocalContext.current)',
 }
 for a,b in mappings.items():t=t.replace(a,b)
 t=t.replace('    val context = LocalContext.current\n    val density = LocalDensity.current\n','    val context = LocalDesktopOriginalVideoSectionPlatform.current.settingsContext\n    val density = LocalDensity.current\n')
 t=re.sub(r'(?m)^@androidx\.annotation\.OptIn\(androidx\.media3\.common\.util\.UnstableApi::class\)\n','',t)
 t=re.sub(r'(?m)^import (?:android\.(?!util\.Log as Logger)|androidx\.compose\.ui\.viewinterop\.AndroidView|com\.android\.purebilibili\.feature\.video\.danmaku\.(?:DanmakuManager|rememberDanmakuManager|configureAsPassiveDanmakuOverlay)|com\.android\.purebilibili\.danmaku\.engine\.DanmakuRenderView|com\.android\.purebilibili\.feature\.anime4k\.gl\.|com\.android\.purebilibili\.core\.plugin\.PluginManager|com\.android\.purebilibili\.feature\.plugin\.Anime4KPlugin|com\.android\.purebilibili\.core\.util\.applyPlayerRequestedOrientation|com\.android\.purebilibili\.feature\.screenshot\.AppScreenshotGestureBlockState|com\.android\.purebilibili\.feature\.video\.util\.capture).*$\n','',t)
 t=t.replace('import com.android.purebilibili.core.store.SettingsManager\n','')
 for match in sorted(set(re.findall(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*(\w+)',t))):
  if 'Danmaku' in match:
   pattern=r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+match+r'\(\s*context\s*,?\s*'
   t=re.sub(pattern,'platform.danmakuPreferences.'+match+'(',t)
  else:
   owner='DesktopOriginalVideoControlSettings' if match in re.findall(r'fun\s+(\w+)',read(MAIN/'desktop/.local/stable-video-player-full-controls-parity/generated/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt').decode()) else 'DesktopOriginalPlayerSectionSettings'
   t=re.sub(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+match+r'\b','com.android.purebilibili.core.store.'+owner+'.'+match,t)
 t=t.replace('initialValue = com.android.purebilibili.core.store.DanmakuSettings(),','initialValue = platform.danmakuPreferences.currentSettings(activeDanmakuScope),')
 t=t.replace('platform.danmakuPreferences.getDanmakuFullscreenPanelWidthMode()', 'platform.danmakuPreferences.getDanmakuSettings(activeDanmakuScope).map { it.fullscreenPanelWidthMode }')
 t=t.replace('platform.danmakuPreferences.getDanmakuBlockRulesRaw(activeDanmakuScope)', 'platform.danmakuPreferences.getDanmakuSettings(activeDanmakuScope).map { it.blockRulesRaw }')
 t=t.replace('com.android.purebilibili.core.util.AnalyticsHelper.logDanmakuToggle(newState)','platform.recordDanmakuToggle(newState)')
 t=t.replace('anime4kPlugin?.','platform.enhancementActions.')
 t=t.replace('val hasPlaylistNext = com.android.purebilibili.feature.video.player.PlaylistManager\n                .isExternalPlaylist.value &&\n                com.android.purebilibili.feature.video.player.PlaylistManager.hasNext()','val hasPlaylistNext = platform.hasPlaylistNext()')
 t=t.replace('import com.android.purebilibili.feature.video.player.MiniPlayerManager\n','')
 t=between(t,'                onAnime4kToggle = { enabled ->','                onVideoEnhancementAlgorithmChange =', '''                onAnime4kToggle = { enabled -> platform.setCurrentVideoEnhancementEnabled(enabled) },
''','One existing native enhancement session owns per-video override and guarded plugin enable')
 t=t.replace('anime4kAvailable = anime4kGlesAvailable,','anime4kAvailable = enhancementState.available,')
 t=t.replace('com.android.purebilibili.core.util.Logger.','Logger.').replace('android.widget.Toast.','Toast.')
 t=t.replace('import com.android.purebilibili.feature.anime4k.isAnime4KGles3Available\n','')
 t=t.replace('import com.android.purebilibili.core.ui.AppShapes\n','import com.android.purebilibili.core.ui.AppShapes\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\n')
 t=t.replace('import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\n','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\nimport kotlinx.coroutines.flow.map\n')
 t+='\n/** Lazy original diagnostic call shape on the existing local diagnostics bridge. */\nprivate fun Logger.d(tag: String, message: () -> String) { Logger.d(tag, message()) }\n'
 emit(rel,t)
 write(P/'section-source-adaptations.json',json.dumps(dict(commit=COMMIT,sources=SOURCES,edits=EDITS,scope='Full original renderer draft with explicit required Windows/sole-owner boundaries. Not compiled or installed. No runtime acceptance.',mainChanged=False),ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(dict(sources=len(SOURCES),explicitBlockEdits=len(EDITS),rendererLines=len(t.splitlines()))))
if __name__=='__main__':main()
