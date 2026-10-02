// Complete original v0.2.3 body; Android surface/audio/GLES-only boundaries use the same Root Section.
// 文件路径: feature/bangumi/ui/player/BangumiPlayerComponents.kt
package com.android.purebilibili.feature.bangumi.ui.player
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppHorizontalDivider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
// 🌈 Material Icons Extended - 亮度图标
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoSectionPlatform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.data.model.response.Page
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.util.Logger
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import com.android.purebilibili.feature.anime4k.resolveInitialVideoEnhancementEnabled
import com.android.purebilibili.feature.anime4k.resolveAnime4KOutputDecision
import com.bilipai.desktop.ui.DesktopOriginalSectionDanmakuPort as DanmakuManager
import com.android.purebilibili.core.ui.rememberAppPlayerChromeProfile
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.feature.video.ui.components.AnimatedGesturePercentText
import com.android.purebilibili.feature.video.ui.components.SponsorSkipButton
import com.android.purebilibili.feature.video.ui.components.VideoAspectRatio
import com.android.purebilibili.feature.video.ui.components.resolveVideoViewportLayout
import com.android.purebilibili.feature.video.ui.components.toAnime4KDisplayScaleMode
import com.android.purebilibili.feature.video.ui.gesture.GestureLevelOverlayContent
import com.android.purebilibili.feature.video.ui.gesture.resolveGestureLevelKind
import com.android.purebilibili.feature.video.ui.gesture.resolveGestureLevelOverlaySpec
import com.android.purebilibili.feature.video.ui.gesture.rememberGestureLevelOverlayStyle
import com.android.purebilibili.feature.video.ui.overlay.PlaybackDebugInfo
import com.android.purebilibili.feature.video.playback.audio.AudioQualityOption
import com.android.purebilibili.feature.video.ui.section.resolveLongPressPlaybackParameters
import com.android.purebilibili.feature.video.ui.section.VideoGestureMode
import com.android.purebilibili.feature.video.ui.section.resolveSystemStreamVolumeFromGesture
import com.android.purebilibili.data.model.response.SponsorSegment
import com.android.purebilibili.feature.plugin.Anime4KPlugin
import com.android.purebilibili.feature.bangumi.resolveBangumiDanmakuTopInsetDp
import com.android.purebilibili.feature.bangumi.resolveBangumiPlayerTopControlsPaddingTopDp
import com.android.purebilibili.core.store.DEFAULT_LONG_PRESS_SPEED
import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings as SettingsManager
import kotlinx.coroutines.launch
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel

/**
 * 手势模式枚举
 */
enum class BangumiGestureMode { None, Brightness, Volume, Seek }

/**
 * 增强版播放器视图
 * 支持：左侧亮度调节、右侧音量调节、进度拖动、弹幕显示、倍速、弹幕设置
 */
@Composable
internal fun BangumiPlayerView(
    exoPlayer: ExoPlayer,
    danmakuManager: DanmakuManager,
    danmakuEnabled: Boolean,
    onDanmakuToggle: () -> Unit = {},
    seasonId: Long = 0L,
    epId: Long = 0L,
    title: String = "",
    subtitle: String = "",
    bvid: String = "",
    aid: Long = 0L,
    cid: Long = 0L,
    coverUrl: String = "",
    currentVideoUrl: String = "",
    currentAudioUrl: String = "",
    debugInfo: PlaybackDebugInfo = PlaybackDebugInfo(),
    pages: List<Page> = emptyList(),
    currentPageIndex: Int = 0,
    onPageSelect: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    currentQuality: Int = 0,
    acceptQuality: List<Int> = emptyList(),
    acceptDescription: List<String> = emptyList(),
    isLoggedIn: Boolean = false,
    isVip: Boolean = false,
    onQualityChange: (Int) -> Unit = {},
    requestedAudioQuality: Int = -1,
    selectedAudioQuality: Int = -1,
    availableAudioQualities: List<AudioQualityOption> = emptyList(),
    onAudioQualityChange: (Int) -> Unit = {},
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onScreenLockChanged: (Boolean) -> Unit = {},
    sponsorSegment: SponsorSegment? = null,
    showSponsorSkipButton: Boolean = false,
    onSponsorSkip: () -> Unit = {},
    onSponsorDismiss: () -> Unit = {},
    //  新增：倍速控制
    currentSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
    //  新增：弹幕设置
    danmakuOpacity: Float = 0.85f,
    danmakuFontScale: Float = 1.0f,
    danmakuSpeed: Float = 1.0f,
    danmakuDisplayArea: Float = 0.5f,
    danmakuMergeDuplicates: Boolean = true,
    danmakuDuplicateMergeWindowMs: Int = 500,
    danmakuDuplicateMergeCountThreshold: Int = 2,
    onDanmakuOpacityChange: (Float) -> Unit = {},
    onDanmakuFontScaleChange: (Float) -> Unit = {},
    onDanmakuSpeedChange: (Float) -> Unit = {},
    onDanmakuDisplayAreaChange: (Float) -> Unit = {},
    onDanmakuMergeDuplicatesChange: (Boolean) -> Unit = {},
    onDanmakuDuplicateMergeWindowMsChange: (Int) -> Unit = {},
    onDanmakuDuplicateMergeCountThresholdChange: (Int) -> Unit = {},
    isLiked: Boolean = false,
    coinCount: Int = 0,
    onToggleLike: () -> Unit = {},
    onCoin: () -> Unit = {},
    onReloadVideo: () -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    val platform = LocalDesktopOriginalVideoSectionPlatform.current
    val context = platform.settingsContext
    val statusBarsInsetTopDp = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
        .value
    val topControlsPaddingTop = resolveBangumiPlayerTopControlsPaddingTopDp(
        isFullscreen = isFullscreen,
        statusBarsInsetDp = statusBarsInsetTopDp
    ).dp
    val danmakuTopInset = resolveBangumiDanmakuTopInsetDp(
        isFullscreen = isFullscreen,
        statusBarsInsetDp = statusBarsInsetTopDp
    ).dp
    
    val audioManager = platform.volume
    val maxVolume = audioManager.maximumStep()
    
    // 控制层状态
    var showControls by remember { mutableStateOf(true) }
    var isScreenLocked by rememberSaveable { mutableStateOf(false) }
    val latestOnScreenLockChanged by rememberUpdatedState(onScreenLockChanged)
    LaunchedEffect(isScreenLocked) {
        latestOnScreenLockChanged(isScreenLocked)
    }
    DisposableEffect(Unit) {
        onDispose { latestOnScreenLockChanged(false) }
    }
    var currentAspectRatio by remember { mutableStateOf(VideoAspectRatio.FIT) }
    val playerViewRef = platform.viewport
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val hostLifecycleStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    // Same existing native enhancement owner; its capability/error/frame readback
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

    // 手势状态
    var gestureMode by remember { mutableStateOf(BangumiGestureMode.None) }
    var gestureValue by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var startVolumeStep by remember { mutableIntStateOf(0) }
    var totalVolumeDragDistanceY by remember { mutableFloatStateOf(0f) }
    var seekPreviewPosition by remember { mutableLongStateOf(0L) }
    val longPressSpeed by SettingsManager.getLongPressSpeed(context)
        .collectAsState(initial = DEFAULT_LONG_PRESS_SPEED)
    var longPressOriginalPlaybackParameters by remember(exoPlayer) {
        mutableStateOf(exoPlayer.playbackParameters)
    }
    
    // 亮度状态
    var currentBrightness by remember(platform) {
        mutableFloatStateOf(platform.readViewportBrightness())
    }

    // 播放器状态
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var currentProgress by remember { mutableFloatStateOf(0f) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    
    // 监听播放器状态
    LaunchedEffect(exoPlayer) {
        while (true) {
            isPlaying = exoPlayer.isPlaying
            duration = exoPlayer.duration.coerceAtLeast(1L)
            currentPosition = exoPlayer.currentPosition
            if (gestureMode != BangumiGestureMode.Seek) {
                currentProgress = (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            }
            kotlinx.coroutines.delay(200)
        }
    }
    
    Box(
        modifier = modifier
            .background(Color.Transparent)
            .pointerInput(isScreenLocked, longPressSpeed, requestedAudioQuality, exoPlayer) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (isScreenLocked) return@detectDragGesturesAfterLongPress
                        longPressOriginalPlaybackParameters = exoPlayer.playbackParameters
                        exoPlayer.playbackParameters = resolveLongPressPlaybackParameters(
                            requestedSpeed = longPressSpeed,
                            currentAudioQuality = requestedAudioQuality
                        )
                    },
                    onDragEnd = {
                        exoPlayer.playbackParameters = longPressOriginalPlaybackParameters
                    },
                    onDragCancel = {
                        exoPlayer.playbackParameters = longPressOriginalPlaybackParameters
                    },
                    onDrag = { change, _ -> change.consume() }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    },
                    onDoubleTap = {
                        if (isScreenLocked) return@detectTapGestures
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
                )
            }
            .then(
                Modifier.pointerInput(isFullscreen, isScreenLocked) {
                    val screenWidth = size.width.toFloat()
                    val screenHeight = size.height.toFloat()
                    
                    detectDragGestures(
                        onDragStart = {
                            showControls = true
                            dragDelta = 0f
                            totalVolumeDragDistanceY = 0f
                            seekPreviewPosition = currentPosition
                            gestureMode = BangumiGestureMode.None
                        },
                        onDragEnd = {
                            if (gestureMode == BangumiGestureMode.Seek && kotlin.math.abs(dragDelta) > 20f) {
                                exoPlayer.seekTo(seekPreviewPosition)
                            }
                            gestureMode = BangumiGestureMode.None
                        },
                        onDragCancel = { gestureMode = BangumiGestureMode.None },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (isScreenLocked) return@detectDragGestures
                            
                            if (gestureMode == BangumiGestureMode.None) {
                                gestureMode = if (isFullscreen && kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                                    BangumiGestureMode.Seek
                                } else if (kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)) {
                                    if (change.position.x < screenWidth * 0.5f) {
                                        gestureValue = currentBrightness
                                        BangumiGestureMode.Brightness
                                    } else {
                                        startVolumeStep = audioManager.currentStep()
                                        gestureValue = if (maxVolume > 0) {
                                            startVolumeStep.toFloat() / maxVolume.toFloat()
                                        } else {
                                            0f
                                        }
                                        BangumiGestureMode.Volume
                                    }
                                } else {
                                    BangumiGestureMode.None
                                }
                            }
                            
                            when (gestureMode) {
                                BangumiGestureMode.Brightness -> {
                                    gestureValue = (gestureValue - dragAmount.y / screenHeight).coerceIn(0f, 1f)
                                    currentBrightness = gestureValue
                                    platform.setViewportBrightness(gestureValue, requestSystemBrightness = false)
                                }
                                BangumiGestureMode.Volume -> {
                                    totalVolumeDragDistanceY += dragAmount.y
                                    val newVolumeStep = resolveSystemStreamVolumeFromGesture(
                                        startVolumeStep = startVolumeStep,
                                        maxVolumeStep = maxVolume,
                                        totalDragDistanceY = totalVolumeDragDistanceY,
                                        screenHeightPx = screenHeight,
                                        gestureSensitivity = 1.0f
                                    )
                                    audioManager.setStep(newVolumeStep)
                                    gestureValue = if (maxVolume > 0) {
                                        newVolumeStep.toFloat() / maxVolume.toFloat()
                                    } else {
                                        0f
                                    }
                                }
                                BangumiGestureMode.Seek -> {
                                    dragDelta += dragAmount.x
                                    val seekDelta = (dragDelta / screenWidth * duration).toLong()
                                    seekPreviewPosition = (currentPosition + seekDelta).coerceIn(0L, duration)
                                    currentProgress = (seekPreviewPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                }
                                else -> {}
                            }
                        }
                    )
                }
            )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            val density = LocalDensity.current
            val playerFrameViewport = remember(
                maxWidth,
                maxHeight,
                currentAspectRatio,
                density
            ) {
                with(density) {
                    resolveVideoViewportLayout(
                        containerWidth = maxWidth.roundToPx(),
                        containerHeight = maxHeight.roundToPx(),
                        aspectRatio = currentAspectRatio
                    )
                }
            }
            // Original geometry and resize choice drive the sole native HWND viewport.
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

        // 手势指示器（横屏：全部，竖屏：仅亮度和音量）
        val showGestureIndicator = gestureMode != BangumiGestureMode.None && 
            (isFullscreen || gestureMode == BangumiGestureMode.Brightness || gestureMode == BangumiGestureMode.Volume)
        if (showGestureIndicator) {
            BangumiGestureIndicator(
                mode = gestureMode,
                value = when (gestureMode) {
                    BangumiGestureMode.Brightness -> currentBrightness
                    BangumiGestureMode.Volume -> gestureValue
                    BangumiGestureMode.Seek -> currentProgress
                    else -> 0f
                },
                seekTime = if (gestureMode == BangumiGestureMode.Seek) seekPreviewPosition else null,
                duration = duration,
                modifier = if (gestureMode == BangumiGestureMode.Seek) {
                    Modifier.align(Alignment.Center)
                } else {
                    Modifier.fillMaxSize()
                }
            )
        }
        
        BangumiPlayerOverlayHost(
            player = exoPlayer,
            seasonId = seasonId,
            epId = epId,
            title = title,
            subtitle = subtitle,
            bvid = bvid,
            aid = aid,
            cid = cid,
            coverUrl = coverUrl,
            currentVideoUrl = currentVideoUrl,
            currentAudioUrl = currentAudioUrl,
            debugInfo = debugInfo,
            isVisible = showControls && gestureMode == BangumiGestureMode.None,
            onToggleVisible = { showControls = !showControls },
            isFullscreen = isFullscreen,
            isScreenLocked = isScreenLocked,
            onLockToggle = { isScreenLocked = !isScreenLocked },
            currentQuality = currentQuality,
            acceptQuality = acceptQuality,
            acceptDescription = acceptDescription,
            isLoggedIn = isLoggedIn,
            isVip = isVip,
            onQualityChange = onQualityChange,
            requestedAudioQuality = requestedAudioQuality,
            selectedAudioQuality = selectedAudioQuality,
            availableAudioQualities = availableAudioQualities,
            onAudioQualityChange = onAudioQualityChange,
            onPlaybackSpeedChange = onSpeedChange,
            onBack = onBack,
            onToggleFullscreen = onToggleFullscreen,
            danmakuEnabled = danmakuEnabled,
            onDanmakuToggle = onDanmakuToggle,
            danmakuOpacity = danmakuOpacity,
            danmakuFontScale = danmakuFontScale,
            danmakuSpeed = danmakuSpeed,
            danmakuDisplayArea = danmakuDisplayArea,
            danmakuMergeDuplicates = danmakuMergeDuplicates,
            danmakuDuplicateMergeWindowMs = danmakuDuplicateMergeWindowMs,
            danmakuDuplicateMergeCountThreshold = danmakuDuplicateMergeCountThreshold,
            onDanmakuOpacityChange = onDanmakuOpacityChange,
            onDanmakuFontScaleChange = onDanmakuFontScaleChange,
            onDanmakuSpeedChange = onDanmakuSpeedChange,
            onDanmakuDisplayAreaChange = onDanmakuDisplayAreaChange,
            onDanmakuMergeDuplicatesChange = onDanmakuMergeDuplicatesChange,
            onDanmakuDuplicateMergeWindowMsChange = onDanmakuDuplicateMergeWindowMsChange,
            onDanmakuDuplicateMergeCountThresholdChange = onDanmakuDuplicateMergeCountThresholdChange,
            currentAspectRatio = currentAspectRatio,
            onAspectRatioChange = { currentAspectRatio = it },
            pages = pages,
            currentPageIndex = currentPageIndex,
            onPageSelect = onPageSelect,
            isLiked = isLiked,
            coinCount = coinCount,
            onToggleLike = onToggleLike,
            onCoin = onCoin,
            onCaptureScreenshot = {
                scope.launch {
                    val success = platform.captureAndSaveScreenshot(
                        videoWidth = exoPlayer.videoSize.width,
                        videoHeight = exoPlayer.videoSize.height,
                        title = subtitle.ifBlank { title.ifBlank { "bangumi" } }
                    )
                    onShowMessage(if (success) "截图已保存（PNG）" else "截图失败，请稍后重试")
                }
            },
            onReloadVideo = onReloadVideo,
            anime4kEnabled = videoEnhancementEnabled,
            anime4kAvailable = anime4kGlesAvailable,
            anime4kBypassReason = anime4kBypassReason,
            videoEnhancementAlgorithm = anime4kConfig.algorithm,
            anime4kPreset = anime4kConfig.preset,
            fsrSharpness = anime4kConfig.fsrSharpness,
            onAnime4kToggle = { enabled -> platform.setCurrentVideoEnhancementEnabled(enabled) },
            onVideoEnhancementAlgorithmChange = platform.enhancementActions::setAlgorithm,
            onAnime4kPresetChange = platform.enhancementActions::setPreset,
            onFsrSharpnessChange = platform.enhancementActions::setFsrSharpness,
            onShowMessage = onShowMessage
        )
        
        // 空降助手跳过按钮 (位置调整到进度条上方)
        SponsorSkipButton(
            segment = sponsorSegment,
            visible = showSponsorSkipButton,
            onSkip = onSponsorSkip,
            onDismiss = onSponsorDismiss,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 60.dp, end = 16.dp)  //  向上偏移避免与进度条重叠
        )
    }
}

/**
 * 手势指示器
 */
@Composable
fun BangumiGestureIndicator(
    mode: BangumiGestureMode,
    value: Float,
    seekTime: Long?,
    duration: Long,
    modifier: Modifier = Modifier
) {
    val playerChromeProfile = rememberAppPlayerChromeProfile()
    val overlayStyle = rememberGestureLevelOverlayStyle(playerChromeProfile.tabPresentation)
    when (mode) {
        BangumiGestureMode.Brightness, BangumiGestureMode.Volume -> {
            val mappedMode = if (mode == BangumiGestureMode.Brightness) {
                VideoGestureMode.Brightness
            } else {
                VideoGestureMode.Volume
            }
            val kind = resolveGestureLevelKind(mappedMode) ?: return
            val alignment = resolveGestureLevelOverlaySpec(
                style = overlayStyle,
                kind = kind,
                percent = value
            ).alignment
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = alignment
            ) {
                GestureLevelOverlayContent(
                    mode = mappedMode,
                    percent = value,
                    style = overlayStyle,
                    modifier = if (playerChromeProfile.effects.usesTonalContainerTreatment) {
                        Modifier.padding(horizontal = 22.dp)
                    } else {
                        Modifier
                    }
                )
            }
        }
        BangumiGestureMode.Seek -> {
            AppSurface(
                modifier = modifier,
                shape = AppShapes.container(ContainerLevel.Card),
                color = Color.Black.copy(alpha = 0.74f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.58f)),
                shadowElevation = 6.dp,
                tonalElevation = 0.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    AppText(
                        "${FormatUtils.formatDuration(((seekTime ?: 0) / 1000).toInt())} / ${FormatUtils.formatDuration((duration / 1000).toInt())}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        else -> Unit
    }
}

/**
 * 可拖动的迷你进度条（竖屏模式） - 紧凑样式
 */
@Composable
fun BangumiMiniProgressBar(
    player: ExoPlayer,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var bufferedProgress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    
    // 定期更新进度
    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0 && !isDragging) {
                progress = player.currentPosition.toFloat() / player.duration
                bufferedProgress = player.bufferedPosition.toFloat() / player.duration
            }
            kotlinx.coroutines.delay(200)
        }
    }
    
    Box(
        modifier = modifier
            .height(12.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    val seekPosition = (fraction * player.duration).toLong()
                    player.seekTo(seekPosition)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val seekPosition = (dragProgress * player.duration).toLong()
                        player.seekTo(seekPosition)
                        isDragging = false
                    },
                    onDragCancel = { isDragging = false },
                    onDrag = { _, dragAmount ->
                        dragProgress = (dragProgress + dragAmount.x / size.width).coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 进度条容器 - 实际显示的细条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.DarkGray.copy(alpha = 0.5f))
        ) {
            // 缓冲进度
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedProgress.coerceIn(0f, 1f))
                    .background(Color.White.copy(alpha = 0.3f))
            )
            // 播放进度
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((if (isDragging) dragProgress else progress).coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * 番剧画质选择菜单
 */
@Composable
fun BangumiQualityMenu(
    qualities: List<String>,
    qualityIds: List<Int>,
    currentQualityId: Int,
    onQualitySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    fun getQualityTag(qn: Int): String? {
        return when (qn) {
            127, 126, 125, 120, 116, 112 -> "大会员"
            else -> null
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        AppSurface(
            modifier = Modifier
                .widthIn(min = 200.dp, max = 280.dp)
                .clip(AppShapes.container(ContainerLevel.Card))
                .clickable(enabled = false) {},
            color = Color(0xFF2B2B2B),
            shape = AppShapes.container(ContainerLevel.Card),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                AppText(
                    text = "画质选择",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                AppHorizontalDivider(color = Color.White.copy(0.1f))
                
                qualities.forEachIndexed { index, quality ->
                    val qn = qualityIds.getOrNull(index) ?: 0
                    val isSelected = qn == currentQualityId
                    val tag = getQualityTag(qn)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onQualitySelected(qn) }
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppText(
                            text = quality,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(0.9f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        
                        if (tag != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            AppSurface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = AppShapes.container(ContainerLevel.Tag)
                            ) {
                                AppText(
                                    text = tag,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        if (isSelected) {
                            AppIcon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 *  [优化] 细进度条组件 - 参考普通视频播放器的 VideoProgressBar 样式
 * 3dp 高度的细进度条，带圆角和可拖动的圆点滑块
 */
@Composable
fun BangumiSlimProgressBar(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var tempProgress by remember { mutableFloatStateOf(progress) }
    val primaryColor = MaterialTheme.colorScheme.primary
    
    // 同步外部进度
    LaunchedEffect(progress) {
        if (!isDragging) {
            tempProgress = progress
        }
    }
    
    val displayProgress = if (isDragging) tempProgress else progress
    
    Box(
        modifier = modifier
            .height(48.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    onProgressChange(newProgress)
                    onSeekFinished()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        tempProgress = (offset.x / size.width).coerceIn(0f, 1f)
                        onProgressChange(tempProgress)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        tempProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                        onProgressChange(tempProgress)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeekFinished()
                    },
                    onDragCancel = {
                        isDragging = false
                        tempProgress = progress
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // 背景轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.3f), AppShapes.container(ContainerLevel.Micro))
        )
        
        // 当前进度
        Box(
            modifier = Modifier
                .fillMaxWidth(displayProgress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(primaryColor, AppShapes.container(ContainerLevel.Micro))
        )
        
        // 滑块（圆点）- 拖动时放大
        Box(
            modifier = Modifier
                .fillMaxWidth(displayProgress.coerceIn(0f, 1f))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(if (isDragging) 16.dp else 12.dp)
                    .offset { IntOffset(x = (if (isDragging) 8.dp else 6.dp).roundToPx(), y = 0) }
                    .background(primaryColor, androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
