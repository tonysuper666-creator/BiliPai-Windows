from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
from types import SimpleNamespace
from v025_source_paths import canonical_source as _desktop_canonical_source
sys.dont_write_bytecode=True
REPO=OUTPUT=None;STANDALONE=False
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def module(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
SOURCES={};OUTPUTS=[];EDITS=[]
def source(rel):
 path=_desktop_canonical_relative(REPO,BASE+rel)
 if path not in SOURCES:
  p=_desktop_canonical_source(REPO,path);assert not p.is_symlink() and p.resolve().is_relative_to(REPO.resolve())
  b=wide(p).read_bytes().replace(b'\r\n',b'\n');assert sha(b)==SOURCE_PINS[path]['sha256LF'],path
  blob=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+_desktop_canonical_relative(REPO,path),str(_desktop_canonical_source(REPO,path))],cwd=REPO,text=True).strip();assert blob==SOURCE_PINS[path]['gitBlob'],path
  SOURCES[path]=dict(text=b.decode(),**SOURCE_PINS[path])
 return SOURCES[path]['text']
def original_subtitle_auto_setter_delta(path,text):
    if path != 'com/android/purebilibili/core/store/DesktopOriginalPlayerSectionSettings.kt':return text
    before='    fun getSubtitleAutoPreference(context: Context): Flow<SubtitleAutoPreference> ='
    after='    suspend fun setSubtitleAutoPreference(context: Context, preference: SubtitleAutoPreference) {\n        context.settingsDataStore.edit { preferences ->\n            preferences[KEY_SUBTITLE_AUTO_PREFERENCE] = preference.ordinal\n        }\n    }\n\n    fun getSubtitleAutoPreference(context: Context): Flow<SubtitleAutoPreference> ='
    assert text.count(before)==1,'exact missing original setter over existing key/context/globalStore'
    text=text.replace(before,after,1)
    return text

def emit(rel,t):
 t=original_subtitle_auto_setter_delta('com/android/purebilibili/'+rel,t)
 direct=BASE+rel in DIRECT
 OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,sha256LF=sha(t),mode='direct-complete-original'if direct else'selected-platform',generated=STANDALONE or not direct))
 if STANDALONE or not direct:write(OUTPUT/'com/android/purebilibili'/rel,t)
def exact(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));EDITS.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def between(t,start,end,after,label):
 a=t.index(start);b=t.index(end,a);return exact(t,t[a:b],after,label)
def balanced_call(t,start,after,label):
 a=t.index(start);masked=protocol.masked(t);op=masked.index('(',a);b=protocol.balanced(masked,op,'(',')');return exact(t,t[a:b],after,label)
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'gitBlob': '5d24281dc2152c1e2113ab3476418f61261cd15a'}, 'app/src/main/java/com/android/purebilibili/core/store/player/PlayerSettingsStore.kt': {'sha256LF': 'a3af07012eae8421764464a7b857ac301a2dd9b2e77a049c62239b60779536ed', 'gitBlob': '4b2178864251903c79e5d70b98e2f0a4e10dddac'}, 'app/src/main/java/com/android/purebilibili/core/ui/adaptive/InputDevicePolicy.kt': {'sha256LF': '5f1ce6f6dd43bba6603700d9e79d1c2802f6da81aef347e589f83d001b712e90', 'gitBlob': '78f7c69bc7e54fce63e0bd2a441760a4f289a593'}, 'app/src/main/java/com/android/purebilibili/feature/video/playback/session/PlaybackSeekController.kt': {'sha256LF': '6b829c2bc168d335c5775e4e2d994ff7eb1279e0c6539d9fdf5f7b0b3e81049b', 'gitBlob': '21b03feaebaada01272d70295a7ef6aeab7a6e18'}, 'app/src/main/java/com/android/purebilibili/feature/video/state/VideoPlayerState.kt': {'sha256LF': '8a3e0cc7d1c7a4d32a3abf1698bd3e4ded06749279e97733c24ed553bcacc95b', 'gitBlob': '684a0b7382f5958b4cf88a41c941a888187032d9'}, 'app/src/main/java/com/android/purebilibili/feature/video/subtitle/SubtitleFeaturePolicy.kt': {'sha256LF': '3c63cc6ec1be30f3d90db92aa12ae57fc02481eb25f3d7ea3702d85acba16b20', 'gitBlob': 'cff25d093f4bd8674ab00ac6fd9e2fcfef3daba8'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt': {'sha256LF': 'b2dcee93edfcd618df90037d1d6fae6b94e011c13f39f0c8f3d5eeb57cfe1f37', 'gitBlob': 'ec94f7dfd54b3b7357eb00918f535c79abb498f3'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/TwoFingerSpeedFeedbackOverlay.kt': {'sha256LF': 'd8b16fbabbe28ddc00a99eba5537df6e1b08fa11e59a9a1cddcf2a1054c5672e', 'gitBlob': 'a4f0626ea3365d6301d755feb6e3f260b820804d'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoAspectRatioPreferenceMapper.kt': {'sha256LF': '880d8d7d3e2b8fc65877a5880745428cd27d94dee7a7da8d80a358f295945136', 'gitBlob': '66b5176b9d0c0cb066274bfa7184d2e43c6ac683'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/FullscreenDoubleTapPolicy.kt': {'sha256LF': '9b989575ac7fee838218dc77e80520b2808fc98808cc5748e4015fedb2a73478', 'gitBlob': 'dacadc0afaa172669713f446d9e4a9d49c126541'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/DanmakuViewportHost.kt': {'sha256LF': '975d90d9ecf4bc79e145a64c66edcb3a4e6fc54f0f86fd7b8db2f7078e931a1b', 'gitBlob': '083f5b27264aa16dbec1ac171bc47862cb0adc6b'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt': {'sha256LF': '883f579cb65942465f11b24bdbdc4a83155648ec82d99ca1d5cf286f025b126f', 'gitBlob': 'ce8bcfe8089b5661a01dd93efde2fdf73c2334c0'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt': {'sha256LF': 'db3d1ba69d077473e1f3433e8f0bbbe146d4fb9c15d592802038f2000931c0ad', 'gitBlob': '0acc9dff472a60dedd0e88cb2b66b9f5df44636c'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionContracts.kt': {'sha256LF': '1a42a990bd45b0d24ac6ec30346b68ccf6132880b3af81e7d79ab7573fed97b4', 'gitBlob': 'a5e9b271950bef186f9527ec0fb61344178172cd'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionPolicy.kt': {'sha256LF': '198913c1d091d5aed119b90fedfbb7e77183457584c49c737c4aae7f113f37c0', 'gitBlob': '1775313c3b78d802728b7ac9492a4ba8e27d07a0'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerTopBarPolicy.kt': {'sha256LF': 'af65cfb6af10178360684f9f6b6a76b39fbf7eabd811ec50707fee2ea3692144', 'gitBlob': 'fb21628e19a112281ce069aff2fcf885bc15ae1e'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerUiLayoutPolicy.kt': {'sha256LF': '426c9d2d1426b90e8e82f9c679979e65d242b90d4ce261bc3ed354c2bd8180a7', 'gitBlob': '41ee93fadc1bad0c764353e59d4c5a6e98473016'}}
DIRECT=['app/src/main/java/com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt', 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/TwoFingerSpeedFeedbackOverlay.kt', 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoAspectRatioPreferenceMapper.kt', 'app/src/main/java/com/android/purebilibili/feature/video/subtitle/SubtitleFeaturePolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerUiLayoutPolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerTopBarPolicy.kt', 'app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt', 'app/src/main/java/com/android/purebilibili/core/ui/adaptive/InputDevicePolicy.kt']
EXISTING_CONTROL_NAMES=['getBottomProgressBehavior', 'getCardAnimationEnabled', 'getDefaultPlaybackSpeed', 'getDoubleTapSeekEnabled', 'getHideVideoPageStatusBar', 'getHideVideoPageStatusBarSync', 'getLongPressSpeed', 'getLongPressSpeedLockEnabled', 'getPlaybackCompletionBehavior', 'getPlaybackSpeedOptions', 'getPlayerControlVisibilitySettings', 'getPlayerProgressPlacement', 'getProgressPeakDanmakuEnabled', 'getRememberLastPlaybackSpeed', 'getSeekBackwardSeconds', 'getSeekForwardSeconds', 'getShowFullscreenActionItems', 'getShowFullscreenBatteryLevel', 'getShowFullscreenLockButton', 'getShowFullscreenScreenshotButton', 'getShowFullscreenTime', 'getShowOnlineCount', 'getTwoFingerHorizontalSpeedEnabled', 'getTwoFingerVerticalSpeedEnabled', 'healPlaybackCompletionSharedPreferences', 'rememberPlaybackCompletionBehavior', 'setDefaultPlaybackSpeed', 'setDoubleTapSeekEnabled', 'setLastPlaybackSpeed', 'setLongPressSpeed', 'setLongPressSpeedLockEnabled', 'setLongPressSpeedLockHintShown', 'setPlaybackCompletionBehavior', 'setProgressPeakDanmakuEnabled', 'setRememberLastPlaybackSpeed', 'setSeekBackwardSeconds', 'setSeekForwardSeconds', 'setTwoFingerHorizontalSpeedEnabled', 'setTwoFingerVerticalSpeedEnabled']
POLICY_NAMES=['INITIAL_PLAYER_CONTROLS_VISIBLE', 'INITIAL_PLAYER_CHROME_AUTO_HIDE_HANDLED', 'resolvePlayerInteractionIdentity', 'PLAYER_DRAG_GESTURE_BOTTOM_EXCLUSION_BUFFER_DP', 'PLAYBACK_STALL_LOG_THRESHOLD_MS', 'VIDEO_PLAYER_COVER_FADE_ENTER_DURATION_MILLIS', 'VIDEO_PLAYER_COVER_FADE_EXIT_DURATION_MILLIS', 'VIDEO_PLAYER_COVER_REVEAL_HOLD_DELAY_MILLIS', 'VIDEO_PLAYER_SURFACE_REVEAL_DURATION_MILLIS', 'VIDEO_PLAYER_SURFACE_REVEAL_INITIAL_SCALE', 'VIDEO_PLAYER_COVER_REVEAL_SETTLE_BUFFER_MILLIS', 'LONG_PRESS_SPEED_TAP_SUPPRESSION_WINDOW_MS', 'LONG_PRESS_SPEED_UNLOCK_HOLD_MS', 'LONG_PRESS_SPEED_LOCK_ZONE_HEIGHT_DP', 'FOREGROUND_SURFACE_RECOVERY_DELAY_MS', 'FOREGROUND_SURFACE_RECOVERY_TIMEOUT_MS', 'resolveVideoPlayerCoverCornerDp', 'LongPressSpeedLockSensitivityPolicy', 'LongPressSpeedLockZoneVisualPolicy', 'LongPressSpeedStartDecision', 'resolveLongPressSpeedLockZoneVisualPolicy', 'resolveSubtitleBottomOffsetPx', 'resolveLongPressSpeedLockSensitivityPolicy', 'resolveGestureSeekableDurationMs', 'shouldKeepVideoPlaybackAwake', 'resolveVideoPlayerBottomGestureExclusionHeightDp', 'shouldIgnoreVideoPlayerDragStart', 'shouldIgnoreVideoPlayerHorizontalEdgeDragStart', 'VideoPlayerGestureVerticalExclusions', 'resolveVideoPlayerGestureVerticalExclusions', 'resolveEffectivePlaybackSpeed', 'resolveSpeedSafePlaybackParameters', 'resolveEffectiveLongPressSpeed', 'resolveLongPressPlaybackParameters', 'resolveLongPressSpeedStartDecision', 'shouldShowHiResLongPressCompatHint', 'shouldShowLongPressSpeedLockHint', 'shouldShowLongPressSpeedFeedback', 'shouldShowLongPressSpeedHintCloseButton', 'shouldEnableLongPressSpeedGesture', 'shouldEnableViewportTransformGesture', 'resolveSystemStreamVolumeFromGesture', 'shouldTriggerPinchExitFullscreen', 'shouldLockLongPressSpeedInTargetZone', 'shouldConsumeExclusiveLongPressSpeedDrag', 'shouldBypassPlaybackSeekSessionProgressOverride', 'resolveProgressDisplayOverridePositionMs', 'resolveGestureSeekStartPositionMs', 'shouldUnlockLockedLongPressSpeedFromRightDownDrag', 'shouldReapplyLockedLongPressSpeed', 'shouldClearLockedLongPressSpeedForExplicitSpeedChange', 'shouldRestorePlaybackParametersAfterLongPressRelease', 'shouldToggleControlsForVideoTap', 'resolveVerticalGestureMode', 'shouldCaptureInlineStatusBarAmbientFrame', 'shouldEnableInlinePlayerGestures', 'shouldShowDanmakuLayers', 'shouldPollVideoPlayerProgress', 'shouldUseScreenTopDanmakuSurface', 'resolveDanmakuLayerTopOffsetPx', 'resolveHorizontalSeekDeltaMs', 'VIDEO_PLAYER_HORIZONTAL_SEEK_DOMINANCE_RATIO', 'shouldEngageHorizontalPlayerSeek', 'resolveConfiguredSeekDeltaMs', 'resolveRelativeSeekTargetPosition', 'VIDEO_PLAYER_SEEK_CANCEL_EDGE_FRACTION', 'isInSeekCancelEscapeZone', 'shouldCommitGestureSeek', 'shouldTriggerSeekStepHaptic', 'resolveOrientationSwitchHintText', 'shouldTriggerFullscreenBySwipe', 'shouldEnterPortraitFullscreenFromSwipe', 'shouldAllowPlaybackStateAutoFullscreen', 'shouldToggleAutoFullscreenForCurrentPlaybackSnapshot', 'shouldToggleAutoFullscreenForPlaybackEvent', 'shouldAutoExitFullscreenOnPlaybackEnded', 'resolveWillContinuePlaybackAfterCurrentItem', 'resolveGestureIndicatorLabel', 'GestureLevelIconStyle', 'resolveGestureLevelIconStyle', 'GestureLevelIconStyle', 'resolveGestureDisplayIcon', 'resolveVolumeGestureIcon', 'resolveBrightnessGestureIcon', 'GestureLevelOverlayVisualPolicy', 'resolveGestureRenderProgress', 'resolveGestureLevelOverlayVisualPolicy', 'resolveGesturePercentDigits', 'resolveGesturePercentDigitChangeMask', 'resolveNavigationLiveSurfaceTextureEnabled', 'resolveAllowLivePlayerSharedElementForMorph', 'shouldUseTextureSurfaceForFlip', 'requiresHdrSurfaceOutput', 'shouldEnableLivePlayerSharedElement', 'resolveSubtitleLanguageLabel', 'shouldForceCoverDuringReturnAnimation', 'shouldShowCoverImage', 'shouldLoadVideoPlayerCoverImage', 'resolveVideoPlayerCoverLayerZIndex', 'shouldHoldEntryCoverUnderlay', 'resolveVideoPlayerCoverRevealSettleDelayMillis', 'VIDEO_PLAYER_COVER_REVEAL_MIN_SATURATION', 'resolveVideoPlayerCoverRevealSaturation', 'resolveVideoPlayerCoverRevealColorFilter', 'VideoPlayerCoverBootstrapState', 'resolveVideoPlayerCoverBootstrapState', 'shouldStartSmoothCoverReveal', 'shouldResetSmoothCoverReveal', 'shouldCommitSmoothCoverReveal', 'shouldKeepCoverForManualStart', 'shouldShowManualStartPlayButton', 'shouldEnableManualStartCoverOverlay', 'VideoPlayerCoverContentScaleMode', 'VideoPlayerEntryPresentationSpec', 'resolveVideoPlayerEntryPresentationSpec', 'shouldFillPlayerViewportForManualStartCover', 'ManualStartPlayButtonAnchor', 'ManualStartPlayButtonLayoutSpec', 'resolveManualStartPlayButtonLayoutSpec', 'shouldDisableCoverFadeAnimation', 'VideoPlayerCoverMotionSpec', 'VideoPlayerRevealMotionSpec', 'VideoPlayerSurfaceRevealSpec', 'resolveVideoPlayerCoverMotionSpec', 'resolveVideoPlayerRevealMotionSpec', 'resolveVideoPlayerSurfaceRevealSpec', 'shouldHidePlayerSurfaceDuringForcedReturn', 'shouldKeepInlinePlayerContentOnReset', 'shouldShowInlinePlayerView', 'shouldEnableCoverImageCrossfade', 'resolvePreferredVideoCoverUrl', 'shouldEnableForcedReturnCoverSharedBounds', 'shouldEnableCoverOverlaySharedBounds', 'resolveForcedReturnCoverSharedElementSourceRoute', 'shouldUseReturnLandingMotionForForcedReturnCover', 'shouldPromoteFirstFrameByPlaybackFallback', 'shouldAutoHidePlayerChromeOnPlaybackStart', 'MediaSwitchSurfaceRebindAction', 'resolveMediaSwitchSurfaceRebindAction', 'shouldRetryMediaSwitchSurfaceRebind', 'shouldRebindPlayerSurfaceOnForeground', 'shouldStartForegroundSurfaceRecovery', 'shouldKickPlaybackAfterSurfaceRecovery', 'shouldLogForegroundSurfaceRecoveryTimeout', 'shouldLogPlaybackStall', 'shouldBindInlinePlayerViewToPlayer', 'shouldRecoverInlinePlayerAfterPredictiveBackCancel', 'shouldLoadDanmakuForForegroundHost']
def member_closure(source,seeds):
 tokens=parser.kotlin_tokens(source);depth=parens=brackets=0;starts=[]
 for i,(word,a,b)in enumerate(tokens):
  if depth==parens==brackets==0 and word in ['fun','val','var','class','object','interface']:
   name=tokens[i+1][0];line=source.rfind('\n',0,a)+1
   while line>0:
    prior=source.rfind('\n',0,line-1)+1
    if source[prior:line].strip().startswith('@'):line=prior
    else:break
   starts.append((name,line))
  depth+=(word=='{')-(word=='}');parens+=(word=='(')-(word==')');brackets+=(word=='[')-(word==']')
 chunks={name:source[a:starts[i+1][1]if i+1<len(starts)else len(source)]for i,(name,a)in enumerate(starts)}
 duplicates={name for name,_ in starts if sum(n==name for n,_ in starts)>1}
 assert not set(seeds)&duplicates,(set(seeds)&duplicates)
 chosen=set(seeds);assert chosen<=chunks.keys(),chosen-chunks.keys()
 while True:
  more={word for name in chosen for word,_,_ in parser.kotlin_tokens(chunks[name])if word in chunks and word not in duplicates}
  if more<=chosen:break
  chosen|=more
 return '\n'.join(chunks[name]for name,_ in starts if name in chosen),sorted(chosen)
def render_section():
 rel='feature/video/ui/section/VideoPlayerSectionContracts.kt';t=source(rel)
 t=exact(t,'import android.os.Bundle\n','','Navigation platform type is the existing CID/cover tuple')
 t=exact(t,'import com.android.purebilibili.feature.video.state.VideoPlayerState','import com.bilipai.desktop.ui.DesktopOriginalMpvVideoPlayerState as VideoPlayerState','State is same MPV readback/required owner')
 t=exact(t,'(String, Bundle?) -> Unit = { _, _ -> }','(String, Long, String?) -> Unit = { _, _, _ -> }','Original related navigation Bundle maps exact CID/cover fields')
 emit(rel,t)
 rel='feature/video/ui/section/VideoPlayerSection.kt';t=source(rel)
 t=between(t,'    val onRecallDanmaku = actions.onRecallDanmaku\n    val context = LocalContext.current','    val configuration = LocalConfiguration.current', '''    val onRecallDanmaku = actions.onRecallDanmaku
    val platform = LocalDesktopOriginalVideoSectionPlatform.current
    val context = platform.settingsContext
    val captureScreenshot = rememberVideoScreenshotAction()
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
 # This obsolete v023 ambient/screenshot seam no longer exists in canonical v025.
 # This obsolete v023 ambient/screenshot seam no longer exists in canonical v025.
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
 # This obsolete v023 ambient/screenshot seam no longer exists in canonical v025.
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
                        // The native owner receives the original transform inputs below exactly once.
                        // This registration Box measures the untransformed viewport; HWND alpha is unsupported.
                        sizeModifier.onSizeChanged { measuredPlayerViewportSize = it }
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
 import v029_command_vote as command_vote
 command_edits=[];command_before=t
 t=command_vote.section_delta(t,command_edits)
 assert command_vote.inverse(t,command_edits)==command_before
 write(OUTPUT/"v029-command-section-proof.json",json.dumps({"upstreamCommit":command_vote.COMMIT,"fullPreviousSectionInverse":True,"edits":command_edits},ensure_ascii=False,indent=2)+"\n")
 t=between(t,'                        if (!enabled) {\n                            pendingDanmakuCloudSync = null','                    },\n                    onDanmakuSyncNowClick', '''                    platform.cloudSync.onEnabledChange(enabled)
''','Cloud enable edits notify same actor')
 # This obsolete v023 ambient/screenshot seam no longer exists in canonical v025.
 t=exact(t,'onDrawerVideoClick = { vid, options ->\n                        onRelatedVideoClick(vid, options)', 'onDrawerVideoClick = { vid, cid ->\n                        onRelatedVideoClick(vid, cid, null)', 'Original State/Actions drawer callback retains exact target CID in same Root navigation')
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
 t=exact(t,'    VideoPlayerSectionContent(state = state, actions = actions)', '    LocalDesktopOriginalVideoSectionPlatform.current.RenderPlayerForeground {\n        VideoPlayerSectionContent(state = state, actions = actions)\n    }','Whole original Section foreground in the sole Windows native carrier')
 t=t.replace('    val context = LocalContext.current\n    val density = LocalDensity.current\n','    val context = LocalDesktopOriginalVideoSectionPlatform.current.settingsContext\n    val density = LocalDensity.current\n')
 t=re.sub(r'(?m)^@androidx\.annotation\.OptIn\(androidx\.media3\.common\.util\.UnstableApi::class\)\n','',t)
 t=re.sub(r'(?m)^import (?:android\.(?!util\.Log as Logger)|androidx\.compose\.ui\.viewinterop\.AndroidView|com\.android\.purebilibili\.feature\.video\.danmaku\.(?:DanmakuManager|rememberDanmakuManager|configureAsPassiveDanmakuOverlay)|com\.android\.purebilibili\.danmaku\.engine\.DanmakuRenderView|com\.android\.purebilibili\.feature\.anime4k\.gl\.|com\.android\.purebilibili\.core\.plugin\.PluginManager|com\.android\.purebilibili\.feature\.plugin\.Anime4KPlugin|com\.android\.purebilibili\.core\.util\.applyPlayerRequestedOrientation|com\.android\.purebilibili\.feature\.screenshot\.AppScreenshotGestureBlockState|com\.android\.purebilibili\.feature\.video\.util\.capture).*$\n','',t)
 t=t.replace('import com.android.purebilibili.core.store.SettingsManager\n','')
 for match in sorted(set(re.findall(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*(\w+)',t))):
  if 'Danmaku' in match:
   pattern=r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+match+r'\(\s*context\s*,?\s*'
   t=re.sub(pattern,'platform.danmakuPreferences.'+match+'(',t)
  else:
   owner='DesktopOriginalVideoControlSettings' if match in EXISTING_CONTROL_NAMES else 'DesktopOriginalPlayerSectionSettings'
   t=re.sub(r'(?:com\.android\.purebilibili\.core\.store\.)?SettingsManager\s*\.\s*'+match+r'\b','com.android.purebilibili.core.store.'+owner+'.'+match,t)
 t=t.replace('initialValue = com.android.purebilibili.core.store.DanmakuSettings(),','initialValue = platform.danmakuPreferences.currentSettings(activeDanmakuScope),')
 t=t.replace('platform.danmakuPreferences.getDanmakuFullscreenPanelWidthMode()', 'platform.danmakuPreferences.getDanmakuSettings(activeDanmakuScope).map { it.fullscreenPanelWidthMode }')
 t=t.replace('platform.danmakuPreferences.getDanmakuBlockRulesRaw(activeDanmakuScope)', 'platform.danmakuPreferences.getDanmakuSettings(activeDanmakuScope).map { it.blockRulesRaw }')
 t=t.replace('com.android.purebilibili.core.util.AnalyticsHelper.logDanmakuToggle(newState)','platform.recordDanmakuToggle(newState)')
 t=t.replace('anime4kPlugin?.','platform.enhancementActions.')
 t=t.replace('val hasPlaylistNext = com.android.purebilibili.feature.video.player.PlaylistManager\n                .isExternalPlaylist.value &&\n                com.android.purebilibili.feature.video.player.PlaylistManager.hasNext()','val hasPlaylistNext = platform.hasPlaylistNext()')
 t=t.replace('import com.android.purebilibili.feature.video.player.MiniPlayerManager\n','')
 t=exact(t,'MiniPlayerManager.getInstance(context).isMiniMode','isInPipMode','The sole Root Mini binding is resources.pip.active, already supplied as isInPipMode; no second Mini state')
 t=between(t,'                onAnime4kToggle = { enabled ->','                onVideoEnhancementAlgorithmChange =', '''                onAnime4kToggle = { enabled -> platform.setCurrentVideoEnhancementEnabled(enabled) },
''','One existing native enhancement session owns per-video override and guarded plugin enable')
 t=t.replace('anime4kAvailable = anime4kGlesAvailable,','anime4kAvailable = enhancementState.available,')
 t=t.replace('com.android.purebilibili.core.util.Logger.','Logger.').replace('android.widget.Toast.','Toast.')
 t=t.replace('import com.android.purebilibili.feature.anime4k.isAnime4KGles3Available\n','')
 t=t.replace('import com.android.purebilibili.core.ui.AppShapes\n','import com.android.purebilibili.core.ui.AppShapes\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\n')
 t=t.replace('import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\n','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform\nimport kotlinx.coroutines.flow.map\n')
 t+='\n/** Lazy original diagnostic call shape on the existing local diagnostics bridge. */\nprivate fun Logger.d(tag: String, message: () -> String) { Logger.d(tag, message()) }\n'
 emit(rel,t)

def render_settings():
 exec("t=source('core/store/SettingsManager.kt')\nobj=t[t.index('{',t.index('object SettingsManager'))+1:t.rfind('}')]\nseeds=['getPlayerInteractionSettings','getAutoPortraitFullscreen','getAutoRotateEnabled','getClickToPlay','getClickToPlaySync','getFullscreenMode','getHomeUpBadgesVisible','getHorizontalAdaptationEnabled','getLiveSurfaceCardTransitionEnabled','getMiniPlayerModeSync','getPauseOnPlayerCollapseEnabled','getPortraitPlayerCollapseMode','getStopPlaybackOnExitSync','getSubtitleAutoPreference','getTabletCommentPanelWidthPreset','getVideoNoteDefaultCollapsed']\nsectionSource=source('feature/video/ui/section/VideoPlayerSection.kt')\nexistingControlNames=set(EXISTING_CONTROL_NAMES)\nsectionNames=set(re.findall(r'SettingsManager\\s*\\.\\s*(\\w+)',sectionSource))\nsectionNames={n for n in sectionNames if 'Danmaku' not in n and n not in existingControlNames}\nseeds=sorted(set(seeds)|sectionNames)\nbody,closure=c.member_closure(obj,seeds)\nbody=body.replace('?: DEFAULT_PLAYER_DIAGNOSTIC_LOGGING_ENABLED','?: context.defaultPlayerDiagnosticLoggingEnabled()')\nimports='''package com.android.purebilibili.core.store\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as Preferences\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences\nimport com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey\nimport com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey\nimport com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey\nimport com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey\nimport com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference\nimport com.android.purebilibili.feature.video.subtitle.normalizeSubtitleVerticalOffsetFraction\nimport com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore\nimport kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.map\nimport kotlinx.coroutines.flow.distinctUntilChanged\nimport kotlinx.coroutines.flow.onEach\nimport kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\nimport kotlin.math.abs\n'''\nkeySource='app/src/main/java/com/android/purebilibili/core/store/player/PlayerSettingsStore.kt'\nkeyText=source('core/store/player/PlayerSettingsStore.kt')\nkeyDecl=c.selector.declarations(c.parser,keyText,['longPressSpeedPreferenceKey'])\nemit('core/store/DesktopOriginalPlayerSectionSettings.kt',imports+'\\n'+keyDecl+'\\nobject DesktopOriginalPlayerSectionSettings {\\n'+body+'\\n}\\n')\nnames=['PlayerInteractionSettings','AutoExitFullscreenMode','resolveAutoExitFullscreenMode','FullscreenMode','FullscreenAspectRatio','PortraitPlayerCollapseMode','TabletCommentPanelWidthPreset','LONG_PRESS_SPEED_HINT_DEFAULT_SCALE','LONG_PRESS_SPEED_HINT_SCALE_MIN','LONG_PRESS_SPEED_HINT_SCALE_MAX','LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA','LONG_PRESS_SPEED_HINT_ALPHA_MIN','LONG_PRESS_SPEED_HINT_ALPHA_MAX','normalizeLongPressSpeedHintScale','normalizeLongPressSpeedHintAlpha','resolveDefaultPlayerDiagnosticLoggingEnabled']\nselected=c.selector.declarations(c.parser,t,names)\nemit('core/store/DesktopOriginalPlayerInteractionSettings.kt','package com.android.purebilibili.core.store\\nimport com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference\\n'+selected+'\\n')\n",globals())

def render_helpers():
 for rel in [
  'feature/video/playback/session/PlaybackSeekController.kt',
  'feature/video/ui/overlay/FullscreenDoubleTapPolicy.kt',
  'feature/video/ui/components/SponsorSkipUI.kt',
  'feature/video/ui/components/TwoFingerSpeedFeedbackOverlay.kt',
  'feature/video/ui/components/VideoAspectRatioPreferenceMapper.kt',
  'feature/video/subtitle/SubtitleFeaturePolicy.kt',
  'feature/video/ui/section/VideoPlayerUiLayoutPolicy.kt',
  'feature/video/ui/section/VideoPlayerTopBarPolicy.kt',
  'feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt',
  'core/ui/adaptive/InputDevicePolicy.kt',
  'feature/video/ui/section/DanmakuViewportHost.kt',
 ]:
  text=source(rel)
  adaptations=[]
  for a,b in [
   ('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player'),
  ]:
   if a in text:adaptations.append(dict(before=a,after=b));text=text.replace(a,b)
  # Canonical v025 DanmakuViewportHost is already pure measured density; retain its whole original body.
  emit(rel,text)

def render_policy():
 t=source('feature/video/ui/section/VideoPlayerSectionPolicy.kt')
 tokens=parser.kotlin_tokens(t);depth=parens=brackets=0;starts=[]
 for i,(word,a,b)in enumerate(tokens):
  if depth==parens==brackets==0 and word in ['fun','val','var','class','object','interface']:
   starts.append((tokens[i+1][0],t.rfind('\n',0,a)+1))
  depth+=(word=='{')-(word=='}');parens+=(word=='(')-(word==')');brackets+=(word=='[')-(word==']')
 chunks=[t[a:starts[i+1][1]if i+1<len(starts)else len(t)]for i,(name,a)in enumerate(starts)if name in POLICY_NAMES]
 assert len(chunks)==len(POLICY_NAMES)
 header=t[:t.index('\ninternal const val INITIAL_PLAYER_CONTROLS_VISIBLE')]
 header=re.sub(r'(?m)^@file:androidx.annotation.OptIn.*\n','',header)
 header=re.sub(r'(?m)^import (?:android\.|androidx.media3.ui.PlayerView).*$\n','',header)
 header=header.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player').replace('import androidx.media3.common.PlaybackParameters','import com.bilipai.desktop.ui.DesktopOriginalPlaybackRate as PlaybackParameters')
 emit('feature/video/ui/section/DesktopOriginalVideoPlayerSectionPolicy.kt',header+'\n'+'\n'.join(chunks))
def render_dimension():
 t=source('feature/video/state/VideoPlayerState.kt');a=t.index('internal fun resolveApiDimensionIsVertical(');masked=protocol.masked(t);b=protocol.balanced(masked,masked.index('{',a),'{','}')
 emit('feature/video/state/DesktopOriginalApiDimensionPolicy.kt','package com.android.purebilibili.feature.video.state\n'+t[a:b]+'\n')
def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,parser,selector,protocol,controls,c,SOURCES,OUTPUTS,EDITS
 REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone;SOURCES={};OUTPUTS=[];EDITS=[]
 manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'));assert manifest['upstreamCommit']==COMMIT
 registered={r['path']:r['sha256']for r in manifest['sources']}
 for path,pin in SOURCE_PINS.items():
  if path in registered:assert registered[path]==pin['sha256LF'],path+' registered original identity changed'
 parser=module('section_tokens',REPO/'desktop/tools/sync-upstream.py');selector=module('section_declarations',REPO/'desktop/tools/extract-appearance-platform.py');protocol=module('section_brackets',REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
 controls=SimpleNamespace(parser=parser);c=SimpleNamespace(parser=parser,selector=selector,member_closure=member_closure)
 render_section();render_settings();render_helpers();render_policy();render_dimension()
 return OUTPUTS
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone'in sys.argv[3:])
