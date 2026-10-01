from pathlib import Path
import hashlib,importlib.util,json,re,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';BASE='app/src/main/java/com/android/purebilibili/'
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
f=module('pager_exact_adaptation',P/'prepare-fullscreen.py');c=f.module('pager_members',MAIN/'desktop/.local/stable-video-player-full-controls-parity/prepare.py')
def read(rel):return f.read(P/'original-stable'/BASE/rel).decode()
def emit(rel,t):f.write(P/'prepared/generated/com/android/purebilibili'/rel,t)
def audit(rel,original,t,edits):
 replay=original
 for e in edits:replay=replay.replace(e['before'],e['after'],e.get('count',1))
 assert replay==t
 f.write(P/('adaptations/'+Path(rel).name+'.json'),json.dumps(dict(path=BASE+rel,originalSHA256LF=f.sha(original),candidateSHA256LF=f.sha(t),completeOriginalFile=True,orderedExactReplay=True,edits=edits),ensure_ascii=False,indent=2)+'\n')
def main():
 rel='feature/video/ui/pager/PortraitVideoPager.kt';original=read(rel);t=original;f.EDITS=[]
 t=f.exact(t,'fun PortraitVideoPager(','internal fun PortraitVideoPager(','Required Windows full-owner interface')
 t=f.each(t,'    val context = LocalContext.current','    val platform = LocalDesktopOriginalPortraitPlatform.current\n    val section = platform.section\n    val context = section.settingsContext','Same page Entry/Section platform')
 t=f.between(t,'    val view = LocalView.current','    val playbackDomainState by', '''    val favoriteQuickSaveDefaultFolder by platform.favoriteQuickSaveDefaultFolder
        .collectAsStateWithLifecycle(initialValue = false)
    DisposableEffect(platform, isActive) {
        val lease = platform.acquirePresentation(isActive)
        onDispose { lease.close() }
    }
    val composerViewModel = platform.composer
    val supplementViewModel = platform.supplement
    val commentViewModel = platform.comments
''','Android system bars and ViewModel acquisition become required same retained owner capabilities')
 t=f.exact(t,'    val danmakuManager = rememberIsolatedDanmakuManager(\n        sessionKey = "portrait_danmaku_$initialBvid"\n    )','    val danmakuManager = platform.danmaku','Sole source/CID document owner; no isolated second parser/session')
 t=f.exact(t,'    val isExternalPlaylist by PlaylistManager.isExternalPlaylist.collectAsStateWithLifecycle()','    val isExternalPlaylist by platform.externalPlaylist.collectAsStateWithLifecycle()','Existing queue coordinator readback')
 t=f.balanced(t,'NetworkUtils.getPlayableDefaultQualityId(', 'platform.playableDefaultQuality(\n                isLoggedIn = platform.requests.isPlaybackLoggedIn(),\n                isVip = platform.requests.isPlaybackVip(),\n            )','Actual preferred connection/quality policy from existing same global settings/authorization')
 t=f.exact(t,'    val portraitMediaSourceFactory = remember(context) {\n        buildPortraitCachedMediaSourceFactory(context)\n    }','    val portraitMediaSourceFactory = remember(platform) { platform.mediaFactory }','Existing source publication/cache transport, not Media3 decoder/cache')
 t=f.exact(t,'    val portraitPlaybackCdnPlugin = remember {\n        PluginManager.getEnabledPlugins(PlaybackCdnPlugin::class).firstOrNull()\n    }','    val portraitPlaybackCdnPlugin = platform.playbackCdnPlugin','Existing enabled plugin owner')
 t=f.exact(t,'TokenManager.sessDataCache.isNullOrEmpty()','!platform.requests.hasPrimarySessionCookie()','Actual main cookie availability, no second account cache')
 t=f.exact(t,'NetworkModule.api.getWatchLaterList()','platform.requests.getWatchLaterList()','Same owned existing WatchLater API')
 t=f.between(t,'    val exoPlayer = sharedPlayer ?: remember(context) {','    LaunchedEffect(exoPlayer, playbackCompletionBehavior)', '''    val exoPlayer = sharedPlayer ?: platform.player
    require(exoPlayer === platform.player) { "Portrait pager must share the installed MPV source owner" }
''','Same MPV instance is required; Android fallback decoder construction is not available')
 t=f.exact(t,'                exoPlayer.release()','                platform.releasePagerLease(exoPlayer)','Disposal retires this lease only, never the shared native player')
 t=f.between(t,'    DisposableEffect(exoPlayer) {\n        danmakuManager.attachPlayer','    LaunchedEffect(exoPlayer, isPortraitPlaybackAllowed)', '''    DisposableEffect(exoPlayer) {
        val lease = platform.acquireDanmakuPlayer(exoPlayer)
        onDispose { lease.close() }
    }

''','Same document/native source lease replaces Android player attach/detach')
 t=f.each(t,'exoPlayer.currentMediaItem?.mediaId','platform.currentMediaId(exoPlayer)','Real accepted BVID/CID media identity instead of Media3 item')
 t=f.exact(t,'        exoPlayer.stop()\n        exoPlayer.clearMediaItems()','        platform.clearPlaybackForReplacement(exoPlayer)','Source-owned stop/clear existing actor; no other current source touched')
 t=f.exact(t,'                val result = VideoRepository.getPortraitPlaybackDetails(','                val playbackRequest = platform.capturePlaybackRequest()\n                val result = playbackRequest.protocol.getPortraitPlaybackDetails(','Immutable request Job/receipt captured once for info/playurl/source publication')
 t=f.between(t,'                        val videoItem = MediaItem.Builder()','                        if (!shouldApplyLoadResult(','''                        val finalSource = platform.prepareSource(
                            request = playbackRequest, info = info, playData = playData,
                            videoUrl = resolvedUrls.videoUrl, audioUrl = resolvedUrls.audioUrl,
                            mediaId = mediaId,
                        )

''','Original chosen DASH/CDN URLs become existing PlaybackSource with captured receipt, not new Media3 sources')
 t=f.exact(t,'                        exoPlayer.setMediaSource(finalSource)','''                        if (!platform.publishSource(playbackRequest, finalSource, requestGeneration) {
                                shouldApplyLoadResult(requestGeneration, activeLoadGeneration, bvid, currentPlayingBvid)
                            }) return@fold''','Same Store/entry atomic final admission rejects retired receipt/load before native queue publication')
 t=f.each(t,'VideoRepository.preloadPortraitPlayUrl(', 'platform.capturePlaybackRequest().protocol.preloadPortraitPlayUrl(','Each preload operation captures its own still-active request receipt')
 t=f.each(t,'VideoRepository.getHomeVideos(', 'platform.requests.getHomeVideos(','Reference sole original Home protocol/preload authority')
 t=f.each(t,'VideoRepository.getRelatedVideos(', 'platform.requests.getRelatedVideos(','Reference existing raw related request')
 t=f.each(t,'VideoRepository.isPlaybackLoggedIn()', 'platform.requests.isPlaybackLoggedIn()','Captured playback auth state')
 t=f.each(t,'VideoRepository.isPlaybackVip()', 'platform.requests.isPlaybackVip()','Captured playback VIP state')
 t=f.each(t,'NetworkUtils.isWifi(context)', 'platform.isWifi()','Actual Windows transport capability')
 t=f.each(t,'prefetchPortraitPlaybackHead(context, streamUrls)','prefetchPortraitPlaybackHead(platform, streamUrls)','Existing bounded media prefetch capability')
 t=f.each(t,'playData.accept_quality','playData.acceptQuality','Canonical actual API DTO property mapping')
 t=f.exact(t,'    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }','    val playerViewRef: PlayerView? = section.viewport','Existing native viewport only')
 start='            override fun onVideoSizeChanged(videoSize: VideoSize) {';a=t.index(start);m=f.parser.masked(t);op=m.index('{',a);b=f.parser.balanced(m,op,'{','}')
 t=f.exact(t,t[a:b],'','Native size observed through same StateFlow below; no unsupported synthetic listener')
 t=f.exact(t,'    // [逻辑] 只有当播放器正在播放当前视频时，才显示 PlayerView','''    LaunchedEffect(exoPlayer, knownVideoAspectRatio) {
        exoPlayer.state.collect { _ ->
            val videoSize = exoPlayer.videoSize
            if (videoSize.width > 0 && videoSize.height > 0) {
                currentVideoAspect = resolvePortraitRuntimeVideoAspectRatio(
                    knownVideoAspectRatio, videoSize.width, videoSize.height)
            }
        }
    }

    // [逻辑] 只有当播放器正在播放当前视频时，才显示 PlayerView''','Actual video-size StateFlow uses original aspect resolver')
 for before in ['rebindPlayerSurfaceIfNeeded(playerView = view, player = exoPlayer)','rebindPlayerSurfaceIfNeeded(playerView = playerView, player = exoPlayer)','rebindPlayerSurfaceIfNeeded(playerView = retryView, player = exoPlayer)']:
  t=f.exact(t,before,'platform.recoverSurface(exoPlayer)','Required existing surface handover/recovery')
 t=f.each(t,'playerView.isAttachedToWindow','section.viewportAttached','Actual viewport attachment')
 t=f.balanced(t,'captureVideoAmbientFrame(', '''section.captureAmbientFrame(
                        targetWidth = VIDEO_STATUS_BAR_AMBIENT_SAMPLE_WIDTH_PX,
                        targetHeight = VIDEO_STATUS_BAR_AMBIENT_SAMPLE_HEIGHT_PX,
                    )''','Native frame capture receipt instead of Android Bitmap/View')
 t=f.exact(t,')?.asImageBitmap()',')','Actual native capture already returns ImageBitmap')
 t=f.balanced(t,'                        AndroidView(','''                        platform.Viewport(
                            player = exoPlayer, modifier = Modifier.fillMaxSize(),
                            resizeMode = portraitPagerResizeMode,
                            keepAwake = shouldKeepPortraitPagerItemAwake,
                            navigationTextureRequested = useTextureSurfaceForNavigation,
                            requiresHdr = requiresHdrSurfaceOutput(currentQualityId = selectedQualityId),
                        )''','Sole native viewport consumes exact original layout/HDR/navigation intent; capability remains explicit')
 t=f.between(t,'                val shareIntent = android.content.Intent()','            },\n            \n            currentSpeed', '                platform.shareText(shareText)\n','Same Root typed text-share actor replaces Android chooser')
 t=f.balanced(t,'    AndroidView(','''    LocalDesktopOriginalPortraitPlatform.current.DanmakuSurface(
        modifier, videoWidth, videoHeight, resizeMode,
    )''','Existing document/carrier renders original video/page danmaku geometry')
 # Keep foreground route semantics while using the existing HWND shaped surface.
 t=f.exact(t,'    VerticalPager(', '    platform.Surface(exoPlayer) {\n    VerticalPager(','Sole Surface foreground encloses the complete original pager')
 t=f.exact(t,'    com.android.purebilibili.feature.video.ui.components.CoinDialog(', '    }\n    com.android.purebilibili.feature.video.ui.components.CoinDialog(','Close whole pager carrier before original coin dialog')
 t=f.exact(t,'.background(Color.Black)\n            .onSizeChanged', '.background(Color.Transparent)\n            .onSizeChanged','Only native foreground page root becomes transparent; original covers/scrims remain black')
 # All codec decisions come from required same-MPV capabilities, no guessed Android result.
 for name,fields in [('resolvePortraitPlaybackStreamUrls',['isHevcSupported = platform.codecs.hevcSupported','isAv1Supported = platform.codecs.av1Supported','isDolbyAudioSupported = platform.codecs.dolbyAudioSupported','isDolbyAudioSoftwareDecoded = platform.codecs.dolbyAudioSoftwareDecoded']),('switchPortraitPlaybackAudioSource',['isDolbyAudioSupported = platform.codecs.dolbyAudioSupported','isDolbyAudioSoftwareDecoded = platform.codecs.dolbyAudioSoftwareDecoded'])]:
  for match in reversed(list(re.finditer(r'\b'+name+r'\(',t))):
   m=f.parser.masked(t);end=f.parser.balanced(m,match.end()-1,'(',')');before=t[match.start():end]
   after=before[:len(name)+1]+'\n            '+',\n            '.join(fields)+','+before[len(name)+1:]
   t=f.exact(t,before,after,'Required actual native codec capability fields')
 for before in ['settings = danmakuSettings,\n                fontScaleOverride = effectiveDanmakuFontScale','settings = danmakuSettings,\n            fontScaleOverride = effectiveDanmakuFontScale']:
  t=f.exact(t,before,'settings = danmakuSettings.copy(fontScale = effectiveDanmakuFontScale)','Original font scale override is supplied to the same renderer settings')
 # Existing original setting mapper retains exact keys/default/migrations; no second schema.
 mapping={'getLongPressSpeed':'longPressSpeed','getDoubleTapSeekEnabled':'doubleTapSeekEnabled','getSeekForwardSeconds':'seekForwardSeconds','getSeekBackwardSeconds':'seekBackwardSeconds','getLongPressSpeedHintCloseEnabled':'longPressSpeedHintCloseEnabled','getLongPressSpeedHintHidden':'longPressSpeedHintHidden','getLongPressSpeedHintScale':'longPressSpeedHintScale','getLongPressSpeedHintAlpha':'longPressSpeedHintAlpha','getPortraitLetterboxAmbientHaze':'portraitLetterboxAmbientHaze'}
 for method,field in mapping.items():
  for match in reversed(list(re.finditer(r'SettingsManager\s*\.\s*'+method+r'\(context\)',t))):
   t=f.exact(t,match.group(),'com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings.getPlayerInteractionSettings(context).map { it.'+field+' }','Sole original settings mapper '+field)
 for method in sorted(set(re.findall(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*(\w+)',t))):
  if 'Danmaku' in method:
   pattern=r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+method+r'\(\s*context\s*,?\s*'
   for match in reversed(list(re.finditer(pattern,t))):t=f.exact(t,match.group(),'section.danmakuPreferences.'+method+'(','Sole original danmaku preferences '+method)
  else:
   owner='DesktopOriginalPortraitSettings'if method in ['getAutoPlay','getExternalPlaylistAutoContinue','getPrefetchVideo','setAudioQuality','getPortraitLetterboxAmbientHazeSync']else 'DesktopOriginalVideoControlSettings'if method=='getPlaybackCompletionBehavior'else 'DesktopOriginalReplySettings'if method=='getCommentFraudDetectionEnabled'else 'player.DesktopOriginalVideoPlayerSettings'if method=='setLastPlaybackSpeed'else 'DesktopOriginalPlayerSectionSettings'
   for match in reversed(list(re.finditer(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+method,t))):t=f.exact(t,match.group(),'com.android.purebilibili.core.store.'+owner+'.'+method,'Sole original settings member '+method)
 t=f.each(t,'getCommentFraudDetectionEnabled(context)','getCommentFraudDetectionEnabled(context.pluginContext)','Global reply settings context type boundary')
 for a,b in {
  'import androidx.media3.common.Player':'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player',
  'import androidx.media3.common.PlaybackParameters':'import com.bilipai.desktop.ui.DesktopOriginalPlaybackRate as PlaybackParameters',
  'import androidx.media3.exoplayer.ExoPlayer':'import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer',
  'import androidx.media3.ui.PlayerView':'import com.bilipai.desktop.ui.DesktopOriginalPlayerViewportPort as PlayerView',
  'import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel':'import com.bilipai.desktop.ui.DesktopOriginalPortraitPlaybackOwner as VideoPlaybackViewModel',
  'import com.android.purebilibili.feature.video.danmaku.DanmakuManager':'import com.bilipai.desktop.ui.DesktopOriginalPortraitDanmakuPort as DanmakuManager',
  'import com.android.purebilibili.core.store.player.PlayerSettingsStore':'import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore',
  'androidx.compose.ui.platform.LocalConfiguration':'com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics',
  'com.android.purebilibili.core.util.Logger.':'android.util.Log.',
 }.items():
  if a in t:t=f.each(t,a,b,'Existing platform/type boundary '+a)
 for line in t.splitlines(True):
  if line.startswith('import ') and any(s in line for s in ['LocalView','LocalContext','WindowInsetsCompat','WindowInsetsControllerCompat','AppWindowSystemUiController','VideoDetailHiddenSystemBars','VideoDetailSystemBarsApplySpec','applyVideoDetailSystemBarsSpec','findActivity','resolveVideoDetailSystemBarsApplySpec','resolveVideoDetailSystemBarsVisibilityPolicy','AndroidView','androidx.media3.','NetworkModule','HiResCompatibleRenderersFactory','PluginManager','NetworkUtils','SettingsManager','TokenManager','data.repository.VideoRepository','player.PlaylistManager','configureAsPassiveDanmakuOverlay','rememberIsolatedDanmakuManager','captureVideoAmbientFrame','player.resolveHandleAudioFocusByPolicy','rebindPlayerSurfaceIfNeeded','VideoComposerViewModel']):t=f.exact(t,line,'','Android-only or fully mapped producer import')
 t=f.each(t,'@UnstableApi\n','','Android native annotation boundary')
 t=f.exact(t,'import androidx.compose.runtime.Composable\n','import androidx.compose.runtime.Composable\nimport com.bilipai.desktop.ui.LocalDesktopOriginalPortraitPlatform\nimport kotlinx.coroutines.flow.map\n','Same Entry local/prefs map imports')
 t=f.exact(t,'import androidx.compose.foundation.layout.statusBarsIgnoringVisibility\n','','Actual status-bar inset supplied by Section')
 t=f.exact(t,'WindowInsets.statusBarsIgnoringVisibility.getTop(this)','section.statusBarInsetPixels','Actual desktop viewport inset')
 t=f.exact(t,'import com.android.purebilibili.danmaku.engine.DanmakuRenderView\n','','Required sole document/carrier already mapped')
 for match in reversed(list(re.finditer(r'filterPortraitOnlyVerticalRecommendations\(',t))):
  m=f.parser.masked(t);end=f.parser.balanced(m,match.end()-1,'(',')');before=t[match.start():end]
  after=before[:before.rfind(')')]+'isVerticalVideo = platform.requests::isVerticalVideo,\n'+before[before.rfind(')'):]
  t=f.exact(t,before,after,'Actual selected filter policy requires the same owned vertical-details request')
 a=original.index('internal data class PortraitVideoViewportSize(');selector=f.module('portrait_viewport_selector',MAIN/'desktop/.local/stable-video-detail-full-ui-parity/prepare.py');_,b=selector.function_range(original,'resolvePortraitVideoViewportSize');viewport=original[a:b]
 t=f.exact(t,viewport,'','Whole original viewport model/resolver moved to same producer shared declaration file')
 shared='feature/video/ui/pager/DesktopOriginalPortraitVideoViewport.kt';emit(shared,'package com.android.purebilibili.feature.video.ui.pager\nimport kotlin.math.roundToInt\n'+viewport+'\n')
 f.write(P/'adaptations/DesktopOriginalPortraitVideoViewport.kt.json',json.dumps(dict(originalPath=BASE+rel,originalSHA256LF=f.sha(original),declarations=['PortraitVideoViewportSize','resolvePortraitVideoViewportSize'],originalDeclarations=viewport,selectedWholeDeclaration=True),indent=2)+'\n')
 emit(rel,t);audit(rel,original,t,f.EDITS)
 # Original full audio-selection/CDN algorithm, with native publication capability only.
 rel='feature/video/ui/pager/PortraitAudioPlaybackController.kt';original=read(rel);t=original;f.EDITS=[]
 t=f.between(t,'    val videoSource = mediaSourceFactory.createMediaSource(','    return PortraitAudioSourceSwitchResult(','''    val currentPosition = player.currentPosition.coerceAtLeast(0L)
    val playWhenReady = player.playWhenReady
    if (!mediaSourceFactory.replaceAudioSource(player, resolvedUrls.videoUrl,
            resolvedAudioUrl, mediaId, currentPosition, playWhenReady)) return null
''','Original selected audio/video URLs publish through same source/receipt actor, retain position/readiness')
 for a,b in {'import androidx.media3.exoplayer.ExoPlayer':'import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer','import androidx.media3.exoplayer.source.DefaultMediaSourceFactory':'import com.bilipai.desktop.ui.DesktopOriginalPortraitMediaFactory as DefaultMediaSourceFactory'}.items():t=f.exact(t,a,b,'Same native capability type')
 t=f.exact(t,'@androidx.annotation.OptIn(UnstableApi::class)\n','','Android native annotation')
 for line in t.splitlines(True):
  if line.startswith('import ') and any(s in line for s in ['androidx.media3.','MediaUtils']):t=f.exact(t,line,'','Mapped native import')
 t=f.exact(t,'Boolean = MediaUtils.isDolbyAtmosAudioSupported()','Boolean','Actual MPV capability is required, not false/default')
 t=f.exact(t,'Boolean = MediaUtils.isDolbySoftwareAudioDecoderRequired()','Boolean','Actual MPV software Dolby capability is required')
 emit(rel,t);audit(rel,original,t,f.EDITS)
 print('Whole Pager and audio source draft generated')
if __name__=='__main__':main()
