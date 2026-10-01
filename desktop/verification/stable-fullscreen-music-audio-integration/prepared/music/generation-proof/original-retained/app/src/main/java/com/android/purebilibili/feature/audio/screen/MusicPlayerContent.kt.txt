package com.android.purebilibili.feature.audio.screen

import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider

import com.android.purebilibili.navigation.animatePagerSelection

import coil3.request.allowHardware

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.motion.AppMotionTokens
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.theme.calculateContrastRatio
import com.android.purebilibili.core.ui.components.AppFilledIconButton
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppIconButtonDefaults
import com.android.purebilibili.core.ui.components.AppLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.AppOutlinedTextField
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.android.purebilibili.core.lifecycle.BackgroundManager
import com.android.purebilibili.core.util.AppHingeOrientation
import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo
import com.android.purebilibili.feature.audio.lyrics.BiliSubtitleLyricsPolicy
import com.android.purebilibili.feature.audio.lyrics.LyricDocument
import com.android.purebilibili.feature.audio.lyrics.LyricLine
import com.android.purebilibili.feature.audio.lyrics.halcyon.HALCYON_DEFAULT_PERSPECTIVE_ANGLE
import com.android.purebilibili.feature.audio.lyrics.halcyon.AppleMusicLyricsView
import com.android.purebilibili.feature.audio.lyrics.halcyon.mapToHalcyonLyrics
import com.android.purebilibili.feature.audio.lyrics.halcyon.currentLyricIndexAt
import com.android.purebilibili.feature.audio.lyrics.halcyon.playerLyricPerspective
import com.android.purebilibili.feature.audio.lyrics.resolveActiveLyricIndex
import com.android.purebilibili.feature.audio.lyrics.resolveDisplaySecondaryRows
import com.android.purebilibili.feature.audio.lyrics.resolveLastStartedLyricIndex
import com.android.purebilibili.feature.audio.lyrics.resolveStableLyricIndex
import com.android.purebilibili.feature.audio.lyrics.resolveCharHighlightAlpha
import com.android.purebilibili.feature.audio.lyrics.resolveLineSweepProgress
import com.android.purebilibili.feature.audio.lyrics.resolveLyricFocusScrollOffsetPx
import com.android.purebilibili.feature.audio.lyrics.resolveSpanHighlightProgress
import com.android.purebilibili.feature.audio.player.MusicPlayerUiState
import com.android.purebilibili.feature.audio.player.MusicQueueItemUi
import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl
import com.android.purebilibili.feature.home.components.LiquidGlassTuning
import com.android.purebilibili.feature.home.components.biliPaiFloatingDockShell
import com.android.purebilibili.feature.home.components.resolveLiquidGlassTuning
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.SettingsManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.android.purebilibili.feature.video.playback.audio.AudioQualityOption
import com.android.purebilibili.feature.video.player.PlayMode
import com.android.purebilibili.feature.video.ui.components.AudioQualitySelectionMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop
import com.android.purebilibili.core.ui.blur.rememberChromeBackdropSource

internal enum class MusicGlassMaterialMode {
    LIQUID,
    FROSTED,
    SURFACE,
}

internal data class MusicPlayerMaterial(
    val mode: MusicGlassMaterialMode,
    val backdropColor: Color,
    val surfaceColor: Color,
    val contentColor: Color,
    val accentColor: Color,
    val borderColor: Color,
    val shadowColor: Color,
    val likeColor: Color,
)

internal val LocalMusicPlayerMaterial = staticCompositionLocalOf {
    MusicPlayerMaterial(
        mode = MusicGlassMaterialMode.SURFACE,
        backdropColor = Color.Unspecified,
        surfaceColor = Color.Unspecified,
        contentColor = Color.Unspecified,
        accentColor = Color.Unspecified,
        borderColor = Color.Unspecified,
        shadowColor = Color.Unspecified,
        likeColor = Color.Unspecified,
    )
}

/** 当前听视频页前景色（随封面色板明暗切换，保证可读）。 */
internal val MusicContentColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalMusicPlayerMaterial.current.contentColor

/** 与视频播放器一致的主题强调色（控件高亮、进度、选中态）。 */
internal val MusicAccentColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalMusicPlayerMaterial.current.accentColor

internal val MusicLikeColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalMusicPlayerMaterial.current.likeColor

internal val MusicShadowColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalMusicPlayerMaterial.current.shadowColor

/**
 * 听视频/音乐页正文色：按背景亮度在可读 token 间切换，并确保高对比度。
 *
 * - 亮底 → [onLightBackground]（对应高对比暗色字）
 * - 暗底 → [onDarkBackground]（对应高对比亮色字）
 */
internal fun resolveMusicPlayerContentColor(
    backgroundColor: Color,
    onLightBackground: Color,
    onDarkBackground: Color,
    lightLuminanceThreshold: Float = 0.45f,
): Color {
    val isLightBackground = backgroundColor.luminance() >= lightLuminanceThreshold
    val target = if (isLightBackground) onLightBackground else onDarkBackground
    val alternate = if (isLightBackground) onDarkBackground else onLightBackground
    val targetContrast = calculateContrastRatio(target, backgroundColor)
    val alternateContrast = calculateContrastRatio(alternate, backgroundColor)
    return if (targetContrast >= alternateContrast) {
        target
    } else {
        alternate
    }
}

internal fun resolveMusicPlayerThemeContentColors(
    colorScheme: ColorScheme,
): Pair<Color, Color> {
    val isDark = colorScheme.surface.luminance() < 0.5f
    // onLightBackground: 浅色背景下使用暗色文字
    // onDarkBackground: 深色背景下使用浅色文字
    val onLight = if (isDark) colorScheme.inverseOnSurface else colorScheme.onSurface
    val onDark = if (isDark) colorScheme.onSurface else colorScheme.inverseOnSurface
    return onLight to onDark
}

@Composable
internal fun resolveMusicPlayerThemeContentColors(): Pair<Color, Color> =
    resolveMusicPlayerThemeContentColors(MaterialTheme.colorScheme)

/** Bottom controls inherit the artwork palette while staying on the dark immersive floor. */
internal fun resolveMusicImmersivePanelColor(
    backgroundColor: Color,
    surfaceColor: Color,
    darkOverlayFraction: Float = 0.45f,
): Color = lerp(
    start = backgroundColor,
    stop = surfaceColor,
    fraction = darkOverlayFraction.coerceIn(0f, 1f),
)

/** Frosted glass container tint adapting to background color and dark/light environment. */
@Composable
internal fun resolveMusicGlassContainerColor(
    glassTintColor: Color,
    isDark: Boolean
): Color {
    val materialColor = LocalMusicPlayerMaterial.current.surfaceColor
    if (materialColor != Color.Unspecified) return materialColor
    val base = glassTintColor.takeOrElse { MaterialTheme.colorScheme.surface }
    val tonalTarget = if (isDark) {
        MaterialTheme.colorScheme.surfaceBright
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    return lerp(base, tonalTarget, if (isDark) 0.24f else 0.40f)
        .copy(alpha = if (isDark) 0.28f else 0.42f)
}

/** Frosted glass subtle border adapting to dark/light environment. */
@Composable
internal fun resolveMusicGlassBorderColor(
    glassTintColor: Color,
    isDark: Boolean
): Color {
    val materialColor = LocalMusicPlayerMaterial.current.borderColor
    if (materialColor != Color.Unspecified) return materialColor
    val base = glassTintColor.takeOrElse { MaterialTheme.colorScheme.surface }
    val edge = if (isDark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    return lerp(base, edge, if (isDark) 0.42f else 0.28f)
        .copy(alpha = if (isDark) 0.30f else 0.22f)
}

/** Pick a theme accent that remains readable in the player controls and lyrics area. */
internal fun resolveMusicPlayerAccentColor(primary: Color, inversePrimary: Color): Color {
    val brightestFloor = Color(0xFF4D4D4D)
    return listOf(primary, inversePrimary, Color.White)
        .firstOrNull { calculateContrastRatio(it, brightestFloor) >= 4.5f }
        ?: Color.White
}

// Halcyon-style palette backdrop: keep the cover color readable under lyrics without
// washing the page into flat grey. Lighter scrim than the old heavy black stack.
internal val MusicArtworkScrimColors = listOf(
    Color.Black.copy(alpha = 0.18f),
    Color.Black.copy(alpha = 0.34f),
    Color.Black.copy(alpha = 0.50f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicPlayerContent(
    state: MusicPlayerUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onQueueItemSelected: (Int) -> Unit = {},
    onImportToQueue: ((List<com.android.purebilibili.feature.video.player.PlaylistItem>) -> Unit)? = null,
    onPlayFromHistory: ((bvid: String, cid: Long) -> Unit)? = null,
    onPlayModeChange: (PlayMode) -> Unit = {},
    onShuffleEnabledChange: (Boolean) -> Unit = {},
    onLyricsOffsetChange: (Long) -> Unit = {},
    subtitleLanguageOptions: List<Pair<String, String>> = emptyList(),
    selectedSubtitleTrackKey: String? = null,
    onSubtitleTrackSelected: (String) -> Unit = {},
    onLyricsRetry: () -> Unit = {},
    onLyricsSearch: (String) -> Unit = {},
    onLyricsCandidateSelected: (Int) -> Unit = {},
    onVideoModeClick: (() -> Unit)? = null,
    onCollectionClick: (() -> Unit)? = null,
    onSleepTimerClick: (() -> Unit)? = null,
    sleepTimerLabel: String = "定时关闭",
    audioQualityLabel: String = "音质",
    audioQualityOptions: List<AudioQualityOption> = emptyList(),
    requestedAudioQuality: Int = -1,
    isHiResAudioSelected: Boolean = false,
    isDolbyAudioSelected: Boolean = false,
    onAudioQualitySelected: ((Int) -> Unit)? = null,
    onPipClick: (() -> Unit)? = null,
    onToggleOrientation: (() -> Unit)? = null,
    orientationActionLabel: String = "横屏",
    isLiked: Boolean = false,
    onLikeClick: (() -> Unit)? = null,
    onCommentsClick: (() -> Unit)? = null,
    isFavorited: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onShareClick: (() -> Unit)? = null,
    onSpeedClick: (() -> Unit)? = null,
    speedLabel: String = "倍速",
    isInPipMode: Boolean = false,
    liquidGlassEffectsEnabled: Boolean = false,
    lyricsBlurEffectsEnabled: Boolean = true,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themeSurfaceColor = MaterialTheme.colorScheme.surface
    val adaptiveInfo = LocalAppWindowAdaptiveInfo.current
    val density = LocalDensity.current
    var paletteColor by remember(themeSurfaceColor) { mutableStateOf(themeSurfaceColor) }
    var artworkBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var isQueueCoverFlow by remember { mutableStateOf(true) }
    var musicTitleCollapsed by rememberSaveable { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showLyricsOffsetSettings by remember { mutableStateOf(false) }
    var expandedRightPaneTab by remember { mutableStateOf(ExpandedRightPaneTab.LYRICS) }
    var layoutPreferenceName by rememberSaveable {
        mutableStateOf(MusicPlayerLayoutPreference.AUTO.name)
    }
    var showAudioQuality by remember { mutableStateOf(false) }
    var showLyricsSearch by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showPlayHistory by remember { mutableStateOf(false) }
    var progressSeekRevision by remember { mutableIntStateOf(0) }
    // 沉浸模式：播放中静置数秒后控制元素自动减淡，任意点击恢复。
    var musicChromeVisible by remember(state.title) { mutableStateOf(true) }
    var chromeInteractionTick by remember { mutableIntStateOf(0) }
    // 第二段沉浸：降透明静置后进一步真隐藏（顶栏胶囊/进度/音量/次操作/分段控件），
    // 保留封面、歌词与播放三键；进度退化为 2dp 细线。任意点击回到第一段。
    var musicChromeHidden by remember(state.title) { mutableStateOf(false) }
    val showMusicChrome = {
        musicChromeVisible = true
        musicChromeHidden = false
        chromeInteractionTick += 1
    }
    LaunchedEffect(state.isPlaying, chromeInteractionTick, musicChromeVisible) {
        if (state.isPlaying && musicChromeVisible) {
            kotlinx.coroutines.delay(4000)
            musicChromeVisible = false
        }
    }
    LaunchedEffect(state.isPlaying, chromeInteractionTick, musicChromeVisible, musicChromeHidden) {
        if (state.isPlaying && !musicChromeVisible && !musicChromeHidden) {
            kotlinx.coroutines.delay(4000)
            musicChromeHidden = true
        }
    }
    val musicChromeAlpha by animateFloatAsState(
        targetValue = if (musicChromeVisible) 1f else 0.28f,
        animationSpec = if (reduceMotion) snap() else tween(400),
        label = "music_chrome_alpha"
    )
    var lyricsControlsVisible by remember(state.title) { mutableStateOf(false) }
    var lyricSearchText by remember(state.title) { mutableStateOf(state.title) }
    val currentItem = remember(state.title, state.artist, state.coverUrl) {
        state.queue.getOrNull(state.currentQueueIndex) ?: MusicQueueItemUi(
            stableId = "current",
            title = state.title.ifBlank { "正在播放" },
            artist = state.artist,
            coverUrl = state.coverUrl
        )
    }
    val (effectiveQueue, effectiveCurrentIndex) = remember(state.queue, state.currentQueueIndex, currentItem) {
        val realQueue = state.queue.ifEmpty { listOf(currentItem) }
        realQueue to if (state.queue.isEmpty()) {
            0
        } else {
            state.currentQueueIndex.coerceIn(0, realQueue.lastIndex)
        }
    }
    val systemReduceMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    val effectiveReduceMotion = reduceMotion || systemReduceMotion || BackgroundManager.isInBackground
    val musicBackdropSource = rememberChromeBackdropSource()
    // The source and all liquid overlays are siblings in this draw tree, so the content layer is
    // recorded before the overlays sample it. Mount the glass chrome on the first composition.
    val musicBackdrop = musicBackdropSource.backdrop
    val homeSettings by SettingsManager
        .getHomeSettings(context)
        .collectAsStateWithLifecycle(initialValue = HomeSettings())
    val settingsScope = rememberCoroutineScope()
    val lyricsUiStyle by SettingsManager
        .getMusicLyricsUiStyle(context)
        .collectAsStateWithLifecycle(initialValue = SettingsManager.MusicLyricsUiStyle.CLASSIC)
    val liquidGlassTuning = remember(
        homeSettings.liquidGlassProgress,
        homeSettings.liquidGlassAdvancedSettings,
        homeSettings.liquidGlassReadabilityMode,
    ) {
        resolveLiquidGlassTuning(
            progress = homeSettings.liquidGlassProgress,
            advancedSettings = homeSettings.liquidGlassAdvancedSettings,
            readabilityMode = homeSettings.liquidGlassReadabilityMode,
        )
    }

    var paletteAccent by remember(themeSurfaceColor) { mutableStateOf(themeSurfaceColor) }
    var playerPalette by remember(themeSurfaceColor) {
        mutableStateOf(com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.Default)
    }
    LaunchedEffect(state.coverUrl, themeSurfaceColor) {
        val result = loadMusicArtwork(context.imageLoader, state.coverUrl, context)
        artworkBitmap = result?.bitmap
        paletteColor = result?.baseColor ?: themeSurfaceColor
        paletteAccent = result?.accentColor ?: (result?.baseColor ?: themeSurfaceColor)
        playerPalette = result?.palette
            ?: com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.Default
    }

    val backgroundColor by animateColorAsState(
        targetValue = paletteColor,
        animationSpec = if (effectiveReduceMotion) snap() else AppMotionTokens.emphasizedSpec(),
        label = "music_palette"
    )
    val glassEnabled = resolveMusicLiquidGlassEnabled(
        sdkInt = Build.VERSION.SDK_INT,
        effectsEnabled = liquidGlassEffectsEnabled,
        isAppInBackground = BackgroundManager.isInBackground,
        reduceMotion = effectiveReduceMotion
    )
    var coverStyle by remember { mutableStateOf(MusicCoverStyle.APPLE_MUSIC_CARD) }
    val chromeSpec = resolveMusicPlayerChromeSpec(
        uiStyle = LocalAppUiStyle.current,
        glassEnabled = glassEnabled,
        coverStyle = coverStyle
    )
    val pageBackground = backgroundColor
    val resolvedContentColor = Color.White
    val resolvedAccentColor = resolveMusicPlayerAccentColor(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.inversePrimary,
    )
    val isDarkEnvironment = true
    val materialMode = when {
        glassEnabled -> MusicGlassMaterialMode.LIQUID
        musicBackdrop != null -> MusicGlassMaterialMode.FROSTED
        else -> MusicGlassMaterialMode.SURFACE
    }
    val materialSurfaceColor = Color.Black.copy(alpha = 0.42f)
    val materialBorderColor = Color.White.copy(alpha = 0.28f)
    val musicMaterial = MusicPlayerMaterial(
        mode = materialMode,
        backdropColor = backgroundColor,
        surfaceColor = materialSurfaceColor,
        contentColor = resolvedContentColor,
        accentColor = resolvedAccentColor,
        borderColor = materialBorderColor,
        shadowColor = MaterialTheme.colorScheme.scrim,
        likeColor = MaterialTheme.colorScheme.error,
    )

    CompositionLocalProvider(
        LocalMusicPlayerMaterial provides musicMaterial,
    ) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        val availableWidthDp = maxWidth.value.roundToInt()
        val availableHeightDp = maxHeight.value.roundToInt()
        val layoutPreference = remember(layoutPreferenceName) {
            runCatching { MusicPlayerLayoutPreference.valueOf(layoutPreferenceName) }
                .getOrDefault(MusicPlayerLayoutPreference.AUTO)
        }
        val hingeBounds = adaptiveInfo.foldingFeature.hingeBounds
        val hingeStartDp = hingeBounds?.let { bounds ->
            val startPx = when (adaptiveInfo.foldingFeature.hingeOrientation) {
                AppHingeOrientation.Horizontal -> bounds.top
                AppHingeOrientation.Vertical -> bounds.left
                AppHingeOrientation.None -> return@let null
            }
            (startPx / density.density).roundToInt()
        }
        val hingeEndDp = hingeBounds?.let { bounds ->
            val endPx = when (adaptiveInfo.foldingFeature.hingeOrientation) {
                AppHingeOrientation.Horizontal -> bounds.bottom
                AppHingeOrientation.Vertical -> bounds.right
                AppHingeOrientation.None -> return@let null
            }
            (endPx / density.density).roundToInt()
        }
        val layout = resolveMusicPlayerLayout(
            widthDp = availableWidthDp,
            heightDp = availableHeightDp,
            fontScale = density.fontScale,
            isInPipMode = isInPipMode,
            preference = layoutPreference,
            posture = adaptiveInfo.posture,
            hingeOrientation = adaptiveInfo.foldingFeature.hingeOrientation,
            hingeStartDp = hingeStartDp,
            hingeEndDp = hingeEndDp,
            hasObstructingHinge = adaptiveInfo.foldingFeature.hasObstructingHinge,
        )
        if (layout != MusicPlayerLayout.PIP_ARTWORK) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(musicBackdropSource.modifier)
                    .background(pageBackground)
            ) {
                MusicArtworkBackground(
                    coverUrl = state.coverUrl,
                    bitmap = artworkBitmap,
                    palette = playerPalette,
                    isPlaying = state.isPlaying,
                    positionMs = state.positionMs,
                )
            }
        }
        when (layout) {
            MusicPlayerLayout.PIP_ARTWORK -> MusicArtwork(
                coverUrl = state.coverUrl,
                bitmap = artworkBitmap,
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape
            )

            MusicPlayerLayout.COMPACT_LANDSCAPE -> {
                var landscapeLyrics by rememberSaveable { mutableStateOf(false) }
                val landscapeHeaderHeight = 48.dp
                Row(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding()
                        .padding(horizontal = 64.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoxWithConstraints(
                        modifier = Modifier.weight(0.85f).fillMaxHeight(),
                        contentAlignment = if (landscapeLyrics) {
                            Alignment.Center
                        } else {
                            Alignment.TopCenter
                        },
                    ) {
                        val artworkWidth = minOf(maxWidth, maxHeight * 0.70f, 280.dp)
                        Column(
                            modifier = Modifier
                                .width(artworkWidth)
                                .then(
                                    if (landscapeLyrics) {
                                        Modifier
                                    } else {
                                        Modifier.padding(top = landscapeHeaderHeight)
                                    }
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            MusicArtwork(
                                coverUrl = state.coverUrl,
                                bitmap = artworkBitmap,
                                modifier = Modifier.width(artworkWidth),
                                coverStyle = coverStyle,
                                isPlaying = state.isPlaying,
                                rotate = state.isPlaying && !effectiveReduceMotion,
                                playbackSpeed = state.playbackSpeed,
                                reduceMotion = effectiveReduceMotion,
                                isDarkEnvironment = isDarkEnvironment,
                                onClick = { coverStyle = resolveNextCoverStyle(coverStyle) },
                            )
                            if (landscapeLyrics) {
                                Spacer(Modifier.height(8.dp))
                                MusicProgress(
                                    state = state,
                                    onSeek = { positionMs ->
                                        progressSeekRevision += 1
                                        onSeek(positionMs)
                                    },
                                    glassEnabled = glassEnabled,
                                    glassTintColor = backgroundColor,
                                    isDarkEnvironment = isDarkEnvironment,
                                    miuixBackdrop = musicBackdrop,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                MusicPlayPauseButton(
                                    state = state,
                                    onPlayPause = onPlayPause,
                                    sizeDp = 56,
                                    isDarkEnvironment = isDarkEnvironment,
                                    glassTintColor = backgroundColor,
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(landscapeHeaderHeight),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppTextButton(onClick = { landscapeLyrics = !landscapeLyrics }) {
                                AppText(if (landscapeLyrics) "返回播放" else "歌词", color = MusicContentColor)
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (landscapeLyrics) {
                            LyricsPage(
                                state = state,
                                glassEnabled = glassEnabled,
                                onPlayPause = onPlayPause,
                                onSeek = { positionMs ->
                                    progressSeekRevision += 1
                                    onSeek(positionMs)
                                },
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onLyricsOffsetChange = onLyricsOffsetChange,
                                onLyricsRetry = onLyricsRetry,
                                onOpenLyricsSearch = { showLyricsSearch = true },
                                blurEffectsEnabled = lyricsBlurEffectsEnabled,
                                reduceMotion = effectiveReduceMotion,
                                glassTintColor = backgroundColor,
                                isDarkEnvironment = isDarkEnvironment,
                                liquidGlassTuning = liquidGlassTuning,
                                miuixBackdrop = musicBackdrop,
                                progressSeekRevision = progressSeekRevision,
                                controlsVisible = lyricsControlsVisible,
                                onControlsVisibleChange = { lyricsControlsVisible = it },
                                showBottomControls = false,
                                lyricsUiStyle = lyricsUiStyle,
                                modifier = Modifier.fillMaxSize()
                            )
                            } else {
                            PlayerPage(
                                state = state,
                                artworkBitmap = artworkBitmap,
                                artworkSizeDp = resolveMusicArtworkSizeDp(
                                    availableWidthDp,
                                    availableHeightDp,
                                    layout
                                ),
                                chromeSpec = chromeSpec,
                                glassEnabled = glassEnabled,
                                reduceMotion = effectiveReduceMotion,
                                onPlayPause = onPlayPause,
                                onSeek = { positionMs ->
                                    progressSeekRevision += 1
                                    onSeek(positionMs)
                                },
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onPlayModeChange = onPlayModeChange,
                                onShuffleEnabledChange = onShuffleEnabledChange,
                                isLiked = isLiked,
                                onLikeClick = onLikeClick,
                                onCommentsClick = onCommentsClick,
                                onQueueClick = { showQueue = !showQueue },
                                isQueueActive = showQueue,
                                miuixBackdrop = musicBackdrop,
                                glassTintColor = backgroundColor,
                                isDarkEnvironment = isDarkEnvironment,
                                coverStyle = coverStyle,
                                onToggleCoverStyle = {
                                    coverStyle = resolveNextCoverStyle(coverStyle)
                                },
                                showLyricsPreview = false,
                                onOpenLyrics = { landscapeLyrics = true },
                                isExpandedLayout = true,
                                compactLandscape = true,
                                modifier = Modifier.fillMaxSize()
                            )
                            }
                        }
                    }
                }
            }

            MusicPlayerLayout.COMPACT_PAGER -> {
                val pagerState = rememberPagerState(pageCount = { 2 })
                val pagerScope = rememberCoroutineScope()
                val openCoverPage: () -> Unit = {
                    showMusicChrome()
                    pagerScope.launch { pagerState.animateScrollToPage(0) }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        // 翻页过渡：随偏移淡出 + 轻微缩放，减少硬切感
                        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                        val pageAlpha = if (effectiveReduceMotion) {
                            1f
                        } else {
                            1f - (kotlin.math.abs(pageOffset) * 0.45f).coerceIn(0f, 0.55f)
                        }
                        val pageScale = if (effectiveReduceMotion) {
                            1f
                        } else {
                            1f - (kotlin.math.abs(pageOffset) * 0.06f).coerceIn(0f, 0.12f)
                        }
                        Box(
                            modifier = Modifier.graphicsLayer {
                                alpha = pageAlpha
                                scaleX = pageScale
                                scaleY = pageScale
                            }
                        ) {
                        if (page == 0) {
                            PlayerPage(
                                state = state,
                                artworkBitmap = artworkBitmap,
                                artworkSizeDp = resolveMusicArtworkSizeDp(
                                    availableWidthDp,
                                    availableHeightDp,
                                    layout
                                ),
                                chromeSpec = chromeSpec,
                                glassEnabled = glassEnabled,
                                reduceMotion = effectiveReduceMotion,
                                onPlayPause = onPlayPause,
                                onSeek = { positionMs ->
                                    progressSeekRevision += 1
                                    onSeek(positionMs)
                                },
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onPlayModeChange = onPlayModeChange,
                                onShuffleEnabledChange = onShuffleEnabledChange,
                                isLiked = isLiked,
                                onLikeClick = onLikeClick,
                                onCommentsClick = onCommentsClick,
                                onQueueClick = { showQueue = !showQueue },
                                isQueueActive = showQueue,
                                miuixBackdrop = musicBackdrop,
                                glassTintColor = backgroundColor,
                                isDarkEnvironment = isDarkEnvironment,
                                coverStyle = coverStyle,
                                onToggleCoverStyle = {
                                    coverStyle = resolveNextCoverStyle(coverStyle)
                                },
                                showLyricsPreview = true,
                                onOpenLyrics = {
                                    pagerScope.launch {
                                        pagerState.animateScrollToPage(1)
                                    }
                                },
                                chromeVisible = musicChromeVisible,
                                chromeHidden = musicChromeHidden,
                                chromeAlpha = musicChromeAlpha,
                                onChromeTap = { showMusicChrome() },
                                titleCollapsed = musicTitleCollapsed,
                                onToggleTitleCollapsed = { musicTitleCollapsed = !musicTitleCollapsed },
                                modifier = Modifier.padding(bottom = MUSIC_PLAYER_COMPACT_DOCK_BOTTOM_PADDING_DP.dp)
                            )
                        } else {
                            LyricsPage(
                                state = state,
                                glassEnabled = glassEnabled,
                                onPlayPause = onPlayPause,
                                onSeek = { positionMs ->
                                    progressSeekRevision += 1
                                    onSeek(positionMs)
                                },
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onLyricsOffsetChange = onLyricsOffsetChange,
                                onLyricsRetry = onLyricsRetry,
                                onOpenLyricsSearch = { showLyricsSearch = true },
                                blurEffectsEnabled = lyricsBlurEffectsEnabled,
                                reduceMotion = effectiveReduceMotion,
                                glassTintColor = backgroundColor,
                                isDarkEnvironment = isDarkEnvironment,
                                liquidGlassTuning = liquidGlassTuning,
                                miuixBackdrop = musicBackdrop,
                                progressSeekRevision = progressSeekRevision,
                                controlsVisible = lyricsControlsVisible,
                                onControlsVisibleChange = {
                                    lyricsControlsVisible = it
                                    showMusicChrome()
                                },
                                showBottomControls = false,
                                onPageTap = openCoverPage,
                                lyricsUiStyle = lyricsUiStyle,
                                modifier = Modifier.padding(bottom = MUSIC_PLAYER_COMPACT_DOCK_BOTTOM_PADDING_DP.dp)
                            )
                            MusicArtwork(
                                coverUrl = state.coverUrl,
                                bitmap = artworkBitmap,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(top = 12.dp, end = 20.dp)
                                    .size(56.dp),
                                coverStyle = MusicCoverStyle.APPLE_MUSIC_SQUARE,
                                reduceMotion = effectiveReduceMotion,
                                onClick = openCoverPage,
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(
                                        start = 24.dp,
                                        end = 24.dp,
                                        top = 12.dp,
                                        bottom = (MUSIC_PLAYER_COMPACT_DOCK_BOTTOM_PADDING_DP + 12).dp,
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                PlaybackControls(
                                    state = state,
                                    onPlayPause = onPlayPause,
                                    onPrevious = onPrevious,
                                    onNext = onNext,
                                    playButtonSizeDp = 56,
                                    skipButtonSizeDp = 48,
                                    isDarkEnvironment = isDarkEnvironment,
                                    glassTintColor = backgroundColor,
                                    modifier = Modifier.graphicsLayer { alpha = 0.72f },
                                )
                                Spacer(Modifier.height(12.dp))
                                LyricsImmersiveProgress(state = state)
                            }
                        }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !musicChromeHidden,
                        enter = androidx.compose.animation.fadeIn(tween(300)) +
                            androidx.compose.animation.slideInVertically(tween(300)) { it / 2 },
                        exit = androidx.compose.animation.fadeOut(tween(300)) +
                            androidx.compose.animation.slideOutVertically(tween(300)) { it / 2 },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally),
                    ) {
                        BottomBarLiquidSegmentedControl(
                            items = resolveMusicPlayerPageTabs(),
                            selectedIndex = pagerState.currentPage,
                            onSelected = { page ->
                                pagerScope.launch {
                                    animatePagerSelection(pagerState, page)
                                }
                            },
                            itemWidth = 84.dp,
                            height = 48.dp,
                            indicatorHeight = 36.dp,
                            containerVerticalPadding = 6.dp,
                            selectedTextColorOverride = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColorOverride = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            liquidGlassEffectsEnabled = liquidGlassEffectsEnabled,
                            preferInlineContentStyle = false,
                            miuixBackdrop = musicBackdrop,
                            dragSelectionEnabled = true,
                            tapPressRefractionEnabled = true,
                            isScrollInProgressProvider = { pagerState.isScrollInProgress },
                            indicatorPositionProvider = {
                                resolveMusicPagerIndicatorPosition(
                                    currentPage = pagerState.currentPage,
                                    currentPageOffsetFraction = pagerState.currentPageOffsetFraction
                                )
                            },
                            externalPagerMotionEffectsEnabled = true,
                        )
                    }
                }
            }

            MusicPlayerLayout.TABLETOP -> TabletopPlayerLayout(
                coverStyle = coverStyle,
                state = state,
                queue = effectiveQueue,
                currentIndex = effectiveCurrentIndex,
                artworkBitmap = artworkBitmap,
                glassEnabled = glassEnabled,
                reduceMotion = effectiveReduceMotion,
                lyricsBlurEffectsEnabled = lyricsBlurEffectsEnabled,
                backgroundColor = backgroundColor,
                musicBackdrop = musicBackdrop,
                liquidGlassTuning = liquidGlassTuning,
                progressSeekRevision = progressSeekRevision,
                isLiked = isLiked,
                onPlayPause = onPlayPause,
                onSeek = { positionMs ->
                    progressSeekRevision += 1
                    onSeek(positionMs)
                },
                onPrevious = onPrevious,
                onNext = onNext,
                onQueueItemSelected = onQueueItemSelected,
                onLikeClick = onLikeClick,
                onToggleCoverStyle = { coverStyle = resolveNextCoverStyle(coverStyle) },
                onLyricsOffsetChange = onLyricsOffsetChange,
                onLyricsRetry = onLyricsRetry,
                onOpenLyricsSearch = { showLyricsSearch = true },
                lyricsUiStyle = lyricsUiStyle,
                availableWidthDp = availableWidthDp,
                hingeStartDp = if (adaptiveInfo.foldingFeature.hingeOrientation == AppHingeOrientation.Horizontal) {
                    hingeStartDp
                } else null,
                hingeEndDp = if (adaptiveInfo.foldingFeature.hingeOrientation == AppHingeOrientation.Horizontal) {
                    hingeEndDp
                } else null,
                isDarkEnvironment = isDarkEnvironment,
                modifier = Modifier.fillMaxSize()
            )

            MusicPlayerLayout.EXPANDED_SPLIT -> {
                    val hasVerticalHinge =
                        adaptiveInfo.foldingFeature.hasObstructingHinge &&
                            adaptiveInfo.foldingFeature.hingeOrientation == AppHingeOrientation.Vertical &&
                            hingeStartDp != null &&
                            hingeEndDp != null
                    val primaryPaneWeight = if (hasVerticalHinge) {
                        (hingeStartDp!! - MUSIC_PLAYER_HINGE_CLEARANCE_DP)
                            .coerceAtLeast(1)
                            .toFloat()
                    } else {
                        1f
                    }
                    val secondaryPaneWeight = if (hasVerticalHinge) {
                        (availableWidthDp - hingeEndDp!! - MUSIC_PLAYER_HINGE_CLEARANCE_DP)
                            .coerceAtLeast(1)
                            .toFloat()
                    } else {
                        1.15f
                    }
                    val horizontalPadding = if (hasVerticalHinge) {
                        0.dp
                    } else {
                        resolveLargeScreenHorizontalPaddingDp(availableWidthDp).dp
                    }
                    val gutter = if (hasVerticalHinge) {
                        (hingeEndDp!! - hingeStartDp!! + MUSIC_PLAYER_HINGE_CLEARANCE_DP * 2).dp
                    } else {
                        resolveLargeScreenGutterDp(availableWidthDp).dp
                    }
                    val maximumContentWidth = if (hasVerticalHinge) {
                        availableWidthDp.dp
                    } else {
                        LARGE_SCREEN_MAX_CONTENT_WIDTH_DP.dp
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = maximumContentWidth)
                                .padding(top = 48.dp, start = horizontalPadding, end = horizontalPadding, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(gutter)
                        ) {
                            PlayerPage(
                                state = state,
                                artworkBitmap = artworkBitmap,
                                artworkSizeDp = resolveMusicArtworkSizeDp(
                                    availableWidthDp,
                                    availableHeightDp,
                                    layout
                                ),
                                chromeSpec = chromeSpec,
                                glassEnabled = glassEnabled,
                                reduceMotion = effectiveReduceMotion,
                                onPlayPause = onPlayPause,
                                onSeek = { positionMs ->
                                    progressSeekRevision += 1
                                    onSeek(positionMs)
                                },
                                onPrevious = onPrevious,
                                onNext = onNext,
                                onPlayModeChange = onPlayModeChange,
                                onShuffleEnabledChange = onShuffleEnabledChange,
                                isLiked = isLiked,
                                onLikeClick = onLikeClick,
                                onCommentsClick = onCommentsClick,
                                onQueueClick = {
                                    expandedRightPaneTab = if (expandedRightPaneTab == ExpandedRightPaneTab.QUEUE) {
                                        ExpandedRightPaneTab.LYRICS
                                    } else {
                                        ExpandedRightPaneTab.QUEUE
                                    }
                                },
                                miuixBackdrop = musicBackdrop,
                                glassTintColor = backgroundColor,
                                isDarkEnvironment = isDarkEnvironment,
                                coverStyle = coverStyle,
                                onToggleCoverStyle = {
                                    coverStyle = resolveNextCoverStyle(coverStyle)
                                },
                                showLyricsPreview = false,
                                onOpenLyrics = null,
                                isExpandedLayout = true,
                                isQueueActive = expandedRightPaneTab == ExpandedRightPaneTab.QUEUE,
                                modifier = Modifier.weight(primaryPaneWeight)
                            )
                            Box(modifier = Modifier.weight(secondaryPaneWeight).fillMaxHeight()) {
                                Crossfade(
                                    targetState = expandedRightPaneTab,
                                    label = "expanded_right_pane"
                                ) { tab ->
                                    when (tab) {
                                        ExpandedRightPaneTab.LYRICS -> {
                                            LyricsPage(
                                                state = state,
                                                glassEnabled = glassEnabled,
                                                onPlayPause = onPlayPause,
                                                onSeek = onSeek,
                                                onPrevious = onPrevious,
                                                onNext = onNext,
                                                onLyricsOffsetChange = onLyricsOffsetChange,
                                                onLyricsRetry = onLyricsRetry,
                                                onOpenLyricsSearch = { showLyricsSearch = true },
                                                blurEffectsEnabled = lyricsBlurEffectsEnabled,
                                                reduceMotion = effectiveReduceMotion,
                                                glassTintColor = backgroundColor,
                                                isDarkEnvironment = isDarkEnvironment,
                                                liquidGlassTuning = liquidGlassTuning,
                                                miuixBackdrop = musicBackdrop,
                                                progressSeekRevision = progressSeekRevision,
                                                controlsVisible = lyricsControlsVisible,
                                                onControlsVisibleChange = { lyricsControlsVisible = it },
                                                showBottomControls = false,
                                                lyricsUiStyle = lyricsUiStyle,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        ExpandedRightPaneTab.QUEUE -> {
                                            ExpandedQueuePane(
                                                queue = effectiveQueue,
                                                currentIndex = effectiveCurrentIndex,
                                                onItemClick = onQueueItemSelected,
                                                onClose = { expandedRightPaneTab = ExpandedRightPaneTab.LYRICS },
                                                glassEnabled = glassEnabled,
                                                reduceMotion = effectiveReduceMotion,
                                                miuixBackdrop = musicBackdrop,
                                                glassTintColor = backgroundColor,
                                                isDarkEnvironment = isDarkEnvironment,
                                                liquidGlassTuning = liquidGlassTuning,
                                                isPlaying = state.isPlaying,
                                                onPlayPause = onPlayPause,
                                                onPrevious = onPrevious,
                                                onNext = onNext,
                                                isLiked = isLiked,
                                                onLikeClick = onLikeClick,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }

        // 沉浸式悬浮待播唱片架 / 待播列表 (非弹窗式，浮于底部)
        AnimatedVisibility(
            visible = showQueue && layout in setOf(MusicPlayerLayout.COMPACT_PAGER, MusicPlayerLayout.COMPACT_LANDSCAPE) && !isInPipMode,
            enter = if (effectiveReduceMotion) {
                fadeIn(animationSpec = AppMotionTokens.standardSpec())
            } else {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = AppMotionTokens.emphasizedSpec()
                ) + fadeIn(animationSpec = AppMotionTokens.emphasizedSpec())
            },
            exit = if (effectiveReduceMotion) {
                fadeOut(animationSpec = AppMotionTokens.standardSpec())
            } else {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = AppMotionTokens.standardSpec()
                ) + fadeOut(animationSpec = AppMotionTokens.standardSpec())
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 点击上半部分透明区域收起待播架，完全无暗色遮罩 scrim
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showQueue = false
                        }
                )

                // 悬浮于底部的沉浸式唱片架面板
                ImmersiveBottomQueueShelf(
                    queue = effectiveQueue,
                    currentIndex = effectiveCurrentIndex,
                    isPlaying = state.isPlaying,
                    isLiked = isLiked,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onLikeClick = onLikeClick,
                    onQueueItemSelected = onQueueItemSelected,
                    onImportClick = { showImportDialog = true },
                    onClose = { showQueue = false },
                    isQueueCoverFlow = isQueueCoverFlow,
                    onToggleQueueCoverFlow = { isQueueCoverFlow = !isQueueCoverFlow },
                    glassEnabled = glassEnabled,
                    reduceMotion = effectiveReduceMotion,
                    miuixBackdrop = musicBackdrop,
                    glassTintColor = backgroundColor,
                    liquidGlassTuning = liquidGlassTuning,
                    isDarkEnvironment = isDarkEnvironment,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = LARGE_SCREEN_MAX_CONTENT_WIDTH_DP.dp)
                        .fillMaxWidth()
                )
            }
        }

        if (!isInPipMode) {
            MusicTopBar(
                glassEnabled = glassEnabled,
                miuixBackdrop = musicBackdrop,
                liquidGlassTuning = liquidGlassTuning,
                glassTintColor = backgroundColor,
                isDarkEnvironment = isDarkEnvironment,
                onBack = onBack,
                onMore = { showActions = true },
                actionsPopup = {
                    if (showActions) {
                        // 菜单使用主题文字色，与动态封面背景解耦。
                        val sheetContentColor = MaterialTheme.colorScheme.onSurface
                        WindowListPopup(
                            show = showActions,
                            onDismissRequest = { showActions = false },
                            alignment = PopupPositionProvider.Align.End,
                            popupPositionProvider = ListPopupDefaults.dropdownPositionProvider(
                                verticalMargin = 8.dp,
                                horizontalMargin = 12.dp,
                            ),
                            enableWindowDim = false,
                            maxHeight = 440.dp,
                        ) {
                            ListPopupColumn {
                                AppText(
                                    text = "播放器操作",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = sheetContentColor,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                                )
                                MusicActionSheetItem(
                                    "切换封面：${resolveCoverStyleLabel(resolveNextCoverStyle(coverStyle))}",
                                    contentColor = sheetContentColor
                                ) {
                                    showActions = false
                                    coverStyle = resolveNextCoverStyle(coverStyle)
                                }
                                MusicActionSheetItem(
                                    "歌词界面：${lyricsUiStyle.next().label}",
                                    contentColor = sheetContentColor
                                ) {
                                    showActions = false
                                    val nextStyle = lyricsUiStyle.next()
                                    settingsScope.launch {
                                        SettingsManager.setMusicLyricsUiStyle(context, nextStyle)
                                    }
                                }
                                MusicActionSheetItem(
                                    if (musicTitleCollapsed) "展开标题" else "折叠标题",
                                    contentColor = sheetContentColor
                                ) {
                                    showActions = false
                                    musicTitleCollapsed = !musicTitleCollapsed
                                }
                                if (
                                    !adaptiveInfo.foldingFeature.hasObstructingHinge &&
                                    adaptiveInfo.windowSizeClass.widthDp.value >= MUSIC_PLAYER_EXPANDED_WIDTH_DP
                                ) {
                                    MusicPlayerLayoutPreference.entries.forEach { preference ->
                                        MusicActionSheetItem(
                                            label = "布局：${resolveMusicPlayerLayoutPreferenceLabel(preference)}" +
                                                if (layoutPreferenceName == preference.name) "（当前）" else "",
                                            contentColor = sheetContentColor,
                                        ) {
                                            layoutPreferenceName = preference.name
                                            showActions = false
                                        }
                                    }
                                }
                                if (onAudioQualitySelected != null) {
                                    MusicActionSheetItem(
                                        "音频音质：$audioQualityLabel",
                                        contentColor = sheetContentColor
                                    ) {
                                        showActions = false
                                        showAudioQuality = true
                                    }
                                }
                                MusicActionSheetItem("3D 唱片架 / 播放队列", contentColor = sheetContentColor) {
                                    showActions = false
                                    showQueue = true
                                }
                                MusicActionSheetItem("导入歌单", contentColor = sheetContentColor) {
                                    showActions = false
                                    showImportDialog = true
                                }
                                onVideoModeClick?.let { action ->
                                    MusicActionSheetItem("返回视频", contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onCollectionClick?.let { action ->
                                    MusicActionSheetItem("选集 / 合集", contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onSpeedClick?.let { action ->
                                    MusicActionSheetItem(speedLabel, contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                if (onPlayFromHistory != null) {
                                    MusicActionSheetItem("最近播放", contentColor = sheetContentColor) {
                                        showActions = false
                                        showPlayHistory = true
                                    }
                                }
                                onSleepTimerClick?.let { action ->
                                    MusicActionSheetItem(sleepTimerLabel, contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onFavoriteClick?.let { action ->
                                    MusicActionSheetItem(
                                        if (isFavorited) "已收藏" else "收藏",
                                        contentColor = sheetContentColor
                                    ) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onDownloadClick?.let { action ->
                                    MusicActionSheetItem("缓存音频", contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onShareClick?.let { action ->
                                    MusicActionSheetItem("分享", contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onPipClick?.let { action ->
                                    MusicActionSheetItem("画中画", contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                onToggleOrientation?.let { action ->
                                    MusicActionSheetItem(orientationActionLabel, contentColor = sheetContentColor) {
                                        showActions = false
                                        action()
                                    }
                                }
                                MusicActionSheetItem("搜索歌词", contentColor = sheetContentColor) {
                                    showActions = false
                                    showLyricsSearch = true
                                }
                                if (state.lyrics != null || subtitleLanguageOptions.isNotEmpty()) {
                                    MusicActionSheetItem("字幕 / 歌词设置", contentColor = sheetContentColor) {
                                        showActions = false
                                        showLyricsOffsetSettings = true
                                    }
                                }
                            }
                        }
                    }
                },
                onToggleLayout = if (
                    !adaptiveInfo.foldingFeature.hasObstructingHinge &&
                    availableWidthDp >= MUSIC_PLAYER_EXPANDED_WIDTH_DP &&
                    availableHeightDp >= 480
                ) {
                    {
                        layoutPreferenceName = nextMusicPlayerLayoutPreference(layoutPreference).name
                    }
                } else null,
                layoutActionLabel = resolveMusicPlayerLayoutPreferenceLabel(
                    nextMusicPlayerLayoutPreference(layoutPreference)
                ),
                leadingActions = if (layout == MusicPlayerLayout.COMPACT_PAGER) {
                    {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.graphicsLayer {
                                alpha = if (musicChromeHidden) 0f else musicChromeAlpha
                            }
                        ) {
                            if (onAudioQualitySelected != null) {
                                GlassTextButton(
                                    label = audioQualityLabel.ifBlank { "音质" },
                                    glassEnabled = glassEnabled,
                                    miuixBackdrop = musicBackdrop,
                                    onClick = {
                                        showMusicChrome()
                                        showAudioQuality = true
                                    },
                                    glassTintColor = backgroundColor,
                                    isDarkEnvironment = isDarkEnvironment,
                                    liquidGlassTuning = liquidGlassTuning,
                                )
                            }
                            GlassTextButton(
                                label = resolveCoverStyleShortLabel(coverStyle),
                                glassEnabled = glassEnabled,
                                miuixBackdrop = musicBackdrop,
                                onClick = {
                                    showMusicChrome()
                                    coverStyle = resolveNextCoverStyle(coverStyle)
                                },
                                glassTintColor = backgroundColor,
                                isDarkEnvironment = isDarkEnvironment,
                                liquidGlassTuning = liquidGlassTuning,
                            )
                        }
                    }
                } else null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }


    if (showAudioQuality && onAudioQualitySelected != null) {
        AudioQualitySelectionMenu(
            options = audioQualityOptions,
            requestedAudioQuality = requestedAudioQuality,
            onAudioQualitySelected = { quality ->
                onAudioQualitySelected(quality)
                showAudioQuality = false
            },
            onDismiss = { showAudioQuality = false }
        )
    }

    if (showImportDialog) {
        ExternalPlaylistImportDialog(
            onDismiss = { showImportDialog = false },
            backdrop = musicBackdrop,
            glassEnabled = glassEnabled,
            liquidGlassTuning = liquidGlassTuning,
            onSaved = { local ->
                // 导入成功后立即作为播放队列加载，第一首进入待播起点
                val items = local.items.map {
                    com.android.purebilibili.feature.video.player.PlaylistItem(
                        bvid = it.bvid,
                        title = it.title,
                        cover = it.cover,
                        owner = it.owner,
                        duration = it.durationSec
                    )
                }
                if (items.isNotEmpty() && onImportToQueue != null) {
                    onImportToQueue(items)
                }
            }
        )
    }
    if (showPlayHistory) {
        PlayHistorySheet(
            onPlay = { entry ->
                showPlayHistory = false
                onPlayFromHistory?.invoke(entry.bvid, entry.cid)
            },
            onDismiss = { showPlayHistory = false }
        )
    }
    if (showLyricsSearch) {
        AppModalBottomSheet(
            onDismissRequest = { showLyricsSearch = false },
            containerColor = AppSurfaceTokens.surface(),
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            AppText(
                text = "手动匹配歌词",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppOutlinedTextField(
                    value = lyricSearchText,
                    onValueChange = { lyricSearchText = it },
                    label = { AppText("歌名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                AppTextButton(onClick = { onLyricsSearch(lyricSearchText) }) {
                    AppText("搜索")
                }
            }
            if (state.isLyricsSearching) {
                AppCircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(24.dp)
                )
            } else if (state.lyricCandidates.isEmpty()) {
                AppText(
                    text = "输入歌名后搜索网易云、QQ 音乐与酷狗",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    itemsIndexed(state.lyricCandidates) { index, candidate ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLyricsCandidateSelected(index)
                                    showLyricsSearch = false
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            AppText(
                                candidate.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            AppText(
                                text = "${candidate.artist} · ${candidate.sourceLabel}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLyricsOffsetSettings) {
        AppModalBottomSheet(
            onDismissRequest = { showLyricsOffsetSettings = false },
            containerColor = AppSurfaceTokens.surface(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            LyricsOffsetSettingsContent(
                offsetMs = state.lyrics?.offsetMs ?: 0L,
                sourceLabel = state.lyrics?.let(BiliSubtitleLyricsPolicy::resolveSourceLabel).orEmpty(),
                onOffsetChange = onLyricsOffsetChange,
                subtitleLanguageOptions = subtitleLanguageOptions,
                selectedSubtitleTrackKey = selectedSubtitleTrackKey,
                onSubtitleTrackSelected = onSubtitleTrackSelected,
            )
        }
    }
    }
}

/**
 * 沉浸式悬浮待播唱片架 / 待播列表（非弹窗式，浮于底部）。
 *
 * 核心设计：
 * 1. 悬浮浮层：浮于播放器底层舞台之上，无全屏暗色遮罩 (scrim)，保持上半部封面与歌词通透沉浸。
 * 2. 质感材质：RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) + biliPaiFloatingDockShell 流体毛玻璃。
 * 3. 极简导览：待播清单 (数量) + [切换列表 / 3D 唱片架] 模式胶囊 + [v] 优雅下推收起按钮，支持顶部下推手势收起。
 * 4. 模式无缝切换：3D 实体 CD 唱片架 (Cover Flow) 与 高级毛玻璃清单双模态切换。
 */
@Composable
private fun ImmersiveBottomQueueShelf(
    queue: List<MusicQueueItemUi>,
    currentIndex: Int,
    isPlaying: Boolean,
    isLiked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onLikeClick: (() -> Unit)?,
    onQueueItemSelected: (Int) -> Unit,
    onImportClick: (() -> Unit)? = null,
    onClose: () -> Unit,
    isQueueCoverFlow: Boolean,
    onToggleQueueCoverFlow: () -> Unit,
    glassEnabled: Boolean,
    miuixBackdrop: MiuixBackdrop?,
    glassTintColor: Color,
    liquidGlassTuning: LiquidGlassTuning,
    reduceMotion: Boolean,
    isDarkEnvironment: Boolean = true,
    modifier: Modifier = Modifier
) {
    val panelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val panelColor = resolveMusicImmersivePanelColor(
        backgroundColor = glassTintColor,
        surfaceColor = MaterialTheme.colorScheme.surface,
    ).copy(alpha = 0.92f)
    val coverFlowMode = isQueueCoverFlow && queue.isNotEmpty()
    // 跟手拖拽：下拉随手偏移，松手按阈值决定关闭或回弹，可中途打断反向。
    val dragScope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    AppSurface(
        shape = panelShape,
        color = if (coverFlowMode) panelColor else if (miuixBackdrop != null) Color.Transparent else panelColor,
        contentColor = MusicContentColor,
        border = BorderStroke(1.dp, resolveMusicGlassBorderColor(glassTintColor, isDarkEnvironment)),
        shadowElevation = 16.dp,
        modifier = modifier
            .offset { IntOffset(0, dragOffsetY.value.roundToInt()) }
            .graphicsLayer {
                alpha = 1f - (dragOffsetY.value / (dismissThresholdPx * 2.5f)).coerceIn(0f, 0.4f)
            }
            .shadow(
                elevation = 28.dp,
                shape = panelShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = Color.Black.copy(alpha = 0.55f),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .then(
                if (coverFlowMode) {
                    Modifier
                } else {
                    Modifier.biliPaiFloatingDockShell(
                        backdrop = miuixBackdrop,
                        containerColor = panelColor,
                        pressProgress = 0f,
                        shape = panelShape,
                        enabled = glassEnabled,
                        blurEnabled = !glassEnabled,
                        liquidGlassTuning = liquidGlassTuning
                    )
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(dismissThresholdPx) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            val settled = dragOffsetY.value
                            if (settled > dismissThresholdPx) {
                                dragScope.launch {
                                    dragOffsetY.animateTo(settled + 1200f, tween(160))
                                    onClose()
                                    dragOffsetY.snapTo(0f)
                                }
                            } else {
                                dragScope.launch {
                                    dragOffsetY.animateTo(0f, spring(dampingRatio = 0.8f))
                                }
                            }
                        },
                        onDragCancel = {
                            dragScope.launch {
                                dragOffsetY.animateTo(0f, spring(dampingRatio = 0.8f))
                            }
                        },
                    ) { _, dragAmount ->
                        dragScope.launch {
                            dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceAtLeast(0f))
                        }
                    }
                }
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // 顶栏：待播清单 (数量) + 模式切换 + 收起按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppText(
                        text = if (queue.isNotEmpty()) "待播清单 (${queue.size})" else "待播清单",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MusicContentColor
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (queue.isNotEmpty()) {
                        AppSurface(
                            onClick = onToggleQueueCoverFlow,
                            shape = AppShapes.container(ContainerLevel.Pill),
                            color = resolveMusicGlassContainerColor(glassTintColor, isDarkEnvironment),
                            border = BorderStroke(0.8.dp, resolveMusicGlassBorderColor(glassTintColor, isDarkEnvironment)),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AppText(
                                    text = if (isQueueCoverFlow) "切换列表" else "3D 唱片架",
                                    color = MusicAccentColor,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    if (onImportClick != null) {
                        AppIconButton(
                            onClick = onImportClick,
                            modifier = Modifier.size(48.dp)
                        ) {
                            AppIcon(
                                imageVector = androidx.compose.material.icons.Icons.Outlined.QueueMusic,
                                contentDescription = "导入外部歌单",
                                tint = MusicContentColor.copy(alpha = 0.72f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    AppIconButton(
                        onClick = onClose,
                        modifier = Modifier.size(48.dp)
                    ) {
                        AppIcon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "收起待播清单",
                            tint = MusicContentColor.copy(alpha = 0.72f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AppText(
                        text = "待播清单为空",
                        color = MusicContentColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else if (isQueueCoverFlow) {
                Music3DCoverFlow(
                    queue = queue,
                    currentIndex = currentIndex,
                    isPlaying = isPlaying,
                    onItemClick = onQueueItemSelected,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    isLiked = isLiked,
                    onLikeClick = onLikeClick,
                    cardSizeDp = 150,
                    showTransportControls = true,
                    glassEnabled = glassEnabled,
                    miuixBackdrop = miuixBackdrop,
                    liquidGlassTuning = liquidGlassTuning,
                    glassTintColor = glassTintColor,
                    isDarkEnvironment = isDarkEnvironment,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    itemsIndexed(queue, key = { _, item -> item.stableId }) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onQueueItemSelected(index)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.coverUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                AppText(
                                    text = item.title,
                                    color = if (index == currentIndex) MusicAccentColor else MusicContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(2.dp))
                                AppText(
                                    text = item.artist,
                                    color = MusicContentColor.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (index == currentIndex) {
                                AppIcon(
                                    Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    tint = MusicAccentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicArtworkBackground(
    coverUrl: String,
    bitmap: ImageBitmap? = null,
    palette: com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette =
        com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.Default,
    isPlaying: Boolean = false,
    positionMs: Long = 0L,
) {
    val coverBitmap = remember(bitmap) { bitmap?.toAndroidBitmapOrNull() }
    if (coverBitmap != null) {
        // Halcyon default lyric/player backdrop: AppleCoverFlowBackground (three-layer
        // oversaturated cover aurora). BeautifulLyrics is opt-in in Halcyon, not the default.
        com.android.purebilibili.feature.audio.lyrics.halcyon.AppleCoverFlowBackground(
            coverBitmap = coverBitmap,
            backgroundColor = palette.middle,
            isDark = !palette.isLight,
            isPlaying = isPlaying,
            animate = isPlaying,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerBlurBackground(
            palette = palette,
            coverBitmap = null,
            animate = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun ImageBitmap.toAndroidBitmapOrNull(): android.graphics.Bitmap? {
    return try {
        this.asAndroidBitmap()
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun PlayerPage(
    state: MusicPlayerUiState,
    artworkBitmap: ImageBitmap?,
    artworkSizeDp: Int,
    chromeSpec: MusicPlayerChromeSpec,
    glassEnabled: Boolean,
    reduceMotion: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onPlayModeChange: (PlayMode) -> Unit,
    onShuffleEnabledChange: (Boolean) -> Unit,
    isLiked: Boolean,
    onLikeClick: (() -> Unit)?,
    onCommentsClick: (() -> Unit)?,
    onQueueClick: () -> Unit,
    miuixBackdrop: MiuixBackdrop?,
    glassTintColor: Color,
    isDarkEnvironment: Boolean = true,
    coverStyle: MusicCoverStyle = MusicCoverStyle.APPLE_MUSIC_CARD,
    onToggleCoverStyle: () -> Unit = {},
    showLyricsPreview: Boolean = true,
    onOpenLyrics: (() -> Unit)? = null,
    isExpandedLayout: Boolean = false,
    compactLandscape: Boolean = false,
    isQueueActive: Boolean = false,
    chromeVisible: Boolean = true,
    /** 沉浸第二段：真隐藏进度交互/音量/次操作行，仅留细进度线与播放三键。 */
    chromeHidden: Boolean = false,
    /** 第一段降透明系数（标题/点赞行沿用）。 */
    chromeAlpha: Float = 1f,
    onChromeTap: (() -> Unit)? = null,
    titleCollapsed: Boolean = false,
    onToggleTitleCollapsed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val topPadding = if (compactLandscape) 0.dp else if (isExpandedLayout) 12.dp else 56.dp
    val bottomPadding = if (isExpandedLayout) 12.dp else 12.dp
    val horizontalPadding = if (isExpandedLayout) 16.dp else chromeSpec.horizontalPaddingDp.dp
    val portraitArtworkSizeDp = if (!isExpandedLayout && !compactLandscape) {
        (artworkSizeDp * 1.12f).roundToInt()
    } else {
        artworkSizeDp
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (!isExpandedLayout && !compactLandscape) Modifier.statusBarsPadding() else Modifier)
            .then(if (compactLandscape) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .then(if (isExpandedLayout) Modifier else Modifier.navigationBarsPadding())
            .padding(
                start = horizontalPadding,
                top = topPadding,
                end = horizontalPadding,
                bottom = bottomPadding
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (!compactLandscape) {
            // 上半部：封面展示与实时歌词空间（弹性居中占满可用剩余空间，绝不挤压底部控制栏）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(onChromeTap) {
                        if (onChromeTap == null) return@pointerInput
                        detectTapGestures { onChromeTap() }
                    }
                    .then(
                        if (!isExpandedLayout && !compactLandscape) {
                            Modifier.offset(y = 12.dp)
                        } else {
                            Modifier
                        }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (state.isLoading && state.coverUrl.isBlank()) {
                    AdaptiveLoadingIndicator(color = MusicContentColor)
                } else {
                    MusicArtwork(
                        coverUrl = state.coverUrl,
                        bitmap = artworkBitmap,
                        modifier = Modifier.width(portraitArtworkSizeDp.dp),
                        shape = if (coverStyle == MusicCoverStyle.TURNTABLE) CircleShape else AppShapes.container(ContainerLevel.Card),
                        rotate = shouldRotateMusicArtwork(
                            isPlaying = state.isPlaying,
                            reduceMotion = reduceMotion
                        ),
                        playbackSpeed = state.playbackSpeed,
                        coverStyle = coverStyle,
                        isPlaying = state.isPlaying,
                        reduceMotion = reduceMotion,
                        isDarkEnvironment = isDarkEnvironment,
                        onClick = onOpenLyrics ?: onToggleCoverStyle
                    )
                }
                if (showLyricsPreview) {
                    Spacer(Modifier.height(14.dp))
                    val lyricsUiStyle by SettingsManager
                        .getMusicLyricsUiStyle(LocalContext.current)
                        .collectAsStateWithLifecycle(
                            initialValue = SettingsManager.MusicLyricsUiStyle.CLASSIC
                        )
                    if (lyricsUiStyle == SettingsManager.MusicLyricsUiStyle.IMMERSIVE &&
                        state.lyrics != null
                    ) {
                        // Immersive cover pane uses Halcyon PlayerMiniLyrics.
                        val halcyonLines = remember(state.lyrics) {
                            mapToHalcyonLyrics(state.lyrics!!.lines)
                        }
                        val miniIndex = remember(halcyonLines, state.positionMs) {
                            currentLyricIndexAt(
                                positionMs = state.positionMs - (state.lyrics?.offsetMs ?: 0L),
                                lyrics = halcyonLines,
                                suppressLeadingZero = true,
                            ).index.coerceIn(0, (halcyonLines.size - 1).coerceAtLeast(0))
                        }
                        com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerMiniLyrics(
                            lines = halcyonLines,
                            activeIndex = miniIndex,
                            positionMs = state.positionMs - (state.lyrics?.offsetMs ?: 0L),
                            contentColor = MusicContentColor,
                            showTranslation = true,
                            onOpenLyrics = onOpenLyrics,
                        )
                    } else {
                        PlayerLyricsPreview(
                            lyrics = state.lyrics,
                            positionMs = state.positionMs,
                            onOpenLyrics = onOpenLyrics
                        )
                    }
                }
            }

        }

        // 下半部：歌曲信息与控制组件区（始终稳定坐落于底端，完整展示播放/暂停与切歌）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onChromeTap) {
                    if (onChromeTap == null) return@pointerInput
                    detectTapGestures { onChromeTap() }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = chromeAlpha }
                    .pointerInput(onToggleTitleCollapsed, titleCollapsed, chromeVisible) {
                        if (onToggleTitleCollapsed == null) return@pointerInput
                        var accumulatedDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { accumulatedDrag = 0f },
                            onDragEnd = {
                                // 沉浸态（控件已隐藏）下滑折叠标题；任意时刻上滑展开。
                                when {
                                    chromeVisible && accumulatedDrag < -90f ->
                                        if (titleCollapsed) onToggleTitleCollapsed()
                                    !chromeVisible && accumulatedDrag > 90f && !titleCollapsed ->
                                        onToggleTitleCollapsed()
                                }
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            accumulatedDrag += dragAmount
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedVisibility(
                    visible = !titleCollapsed,
                    enter = fadeIn(AppMotionTokens.standardSpec()) +
                        slideInVertically(AppMotionTokens.standardSpec()) { it / 2 },
                    exit = fadeOut(AppMotionTokens.standardSpec()) +
                        slideOutVertically(AppMotionTokens.standardSpec()) { it / 2 },
                ) {
                    Column(Modifier.weight(1f)) {
                    AppText(
                        text = state.title,
                        color = MusicContentColor,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(
                            text = state.artist.ifBlank { "未知艺术家" },
                            color = MusicContentColor.copy(alpha = 0.82f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    state.error?.let {
                        AppText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    }
                }
                onLikeClick?.let { like ->
                    AppIconButton(
                        onClick = like,
                        modifier = Modifier
                            .size(48.dp)
                    ) {
                        AppIcon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isLiked) "取消点赞" else "点赞",
                            tint = if (isLiked) MusicLikeColor else MusicContentColor.copy(alpha = 0.72f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (chromeHidden) {
                // 沉浸第二段：进度退化为不可交互细线，位置信息让位于内容。
                // 复用歌词页的沉浸进度线，保持两页视觉一致。
                LyricsImmersiveProgress(
                    state = state,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                MusicProgress(
                    state = state,
                    onSeek = onSeek,
                    glassEnabled = chromeSpec.glassEnabled,
                    glassTintColor = glassTintColor,
                    isDarkEnvironment = isDarkEnvironment,
                    miuixBackdrop = miuixBackdrop,
                )
            }
            Spacer(Modifier.height(8.dp))
            PlaybackControls(
                state = state,
                playButtonSizeDp = if (compactLandscape) 56 else chromeSpec.playButtonSizeDp,
                skipButtonSizeDp = if (compactLandscape) 48 else chromeSpec.skipButtonSizeDp,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                isDarkEnvironment = isDarkEnvironment,
                glassTintColor = glassTintColor
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = !chromeHidden && !compactLandscape,
                enter = androidx.compose.animation.fadeIn(tween(260)),
                exit = androidx.compose.animation.fadeOut(tween(260)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(10.dp))
                    MusicVolumeSlider(
                        glassTintColor = glassTintColor,
                        isDarkEnvironment = isDarkEnvironment
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !chromeHidden,
                enter = androidx.compose.animation.fadeIn(tween(260)),
                exit = androidx.compose.animation.fadeOut(tween(260)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(10.dp))
                    MusicSecondaryControls(
                        mode = state.playMode,
                        shuffleEnabled = state.shuffleEnabled,
                        showQueue = state.queueControls.showQueue || state.queue.isNotEmpty(),
                        onPlayModeChange = onPlayModeChange,
                        onShuffleEnabledChange = onShuffleEnabledChange,
                        onCommentsClick = onCommentsClick,
                        onQueueClick = onQueueClick,
                        isQueueActive = isQueueActive
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicSecondaryControls(
    mode: PlayMode,
    shuffleEnabled: Boolean,
    showQueue: Boolean,
    onPlayModeChange: (PlayMode) -> Unit,
    onShuffleEnabledChange: (Boolean) -> Unit,
    onCommentsClick: (() -> Unit)?,
    onQueueClick: () -> Unit,
    isQueueActive: Boolean = false
) {
    val transport = resolveMusicSecondaryTransport(mode, shuffleEnabled)
    val active = MusicAccentColor
    val inactive = MusicContentColor.copy(alpha = 0.62f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconButton(
            onClick = { onShuffleEnabledChange(!transport.shuffleEnabled) },
            modifier = Modifier.size(48.dp)
        ) {
            AppIcon(
                Icons.Outlined.Shuffle,
                contentDescription = "随机播放",
                tint = if (transport.shuffleEnabled) active else inactive
            )
        }
        AppIconButton(
            onClick = { onPlayModeChange(resolveRepeatModeAfterToggle(mode)) },
            modifier = Modifier.size(48.dp)
        ) {
            AppIcon(
                imageVector = if (transport.repeatGlyph == MusicRepeatGlyph.ONE) {
                    Icons.Outlined.RepeatOne
                } else {
                    Icons.Outlined.Repeat
                },
                contentDescription = "循环模式",
                tint = if (transport.repeatGlyph == MusicRepeatGlyph.OFF) inactive else active
            )
        }
        AppIconButton(
            onClick = onCommentsClick ?: {},
            enabled = onCommentsClick != null,
            modifier = Modifier.size(48.dp)
        ) {
            AppIcon(
                Icons.AutoMirrored.Outlined.Comment,
                contentDescription = "评论",
                tint = if (onCommentsClick != null) inactive else inactive.copy(alpha = 0.28f)
            )
        }
        AppIconButton(
            onClick = onQueueClick,
            enabled = showQueue,
            modifier = Modifier.size(48.dp)
        ) {
            AppIcon(
                Icons.Outlined.QueueMusic,
                contentDescription = "播放队列",
                tint = if (isQueueActive) active else if (showQueue) inactive else inactive.copy(alpha = 0.28f)
            )
        }
    }
}

@Composable
private fun MusicArtwork(
    coverUrl: String,
    bitmap: ImageBitmap?,
    modifier: Modifier,
    shape: Shape = CircleShape,
    rotate: Boolean = false,
    playbackSpeed: Float = 1f,
    coverStyle: MusicCoverStyle = MusicCoverStyle.APPLE_MUSIC_CARD,
    isPlaying: Boolean = false,
    reduceMotion: Boolean = false,
    isDarkEnvironment: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val artworkShadowColor = MusicShadowColor
    val artworkBorderColor = resolveMusicGlassBorderColor(
        LocalMusicPlayerMaterial.current.backdropColor,
        isDarkEnvironment,
    )
    val artworkFallbackBrush = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface,
        )
    )
    if (shape == RectangleShape) {
        // PiP 模式：直接铺满画中画窗口
        Box(
            modifier = modifier
                .clip(shape)
                .background(
                    artworkFallbackBrush
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                bitmap != null -> androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                coverUrl.isNotBlank() -> AsyncImage(
                    model = coverUrl,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                else -> AppIcon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MusicContentColor.copy(alpha = 0.78f),
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    } else if (coverStyle == MusicCoverStyle.TURNTABLE) {
        val rotationDegrees = rememberMusicArtworkRotationDegrees(
            active = rotate,
            contentKey = coverUrl,
            playbackSpeed = playbackSpeed
        )
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .shadow(
                    elevation = if (isPlaying) 18.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = artworkShadowColor.copy(alpha = if (isDarkEnvironment) 0.55f else 0.20f),
                    spotColor = artworkShadowColor.copy(alpha = if (isDarkEnvironment) 0.65f else 0.25f)
                )
                .graphicsLayer { rotationZ = rotationDegrees() }
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = artworkBorderColor,
                    shape = CircleShape
                )
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            when {
                bitmap != null -> androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                coverUrl.isNotBlank() -> AsyncImage(
                    model = coverUrl,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MusicContentColor.copy(alpha = 0.78f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }
    } else {
        // Apple Music Style: 宽屏卡片（16:10，自适应视频比例）或经典方图（1:1）与氛围弥散阴影
        val isCard = coverStyle == MusicCoverStyle.APPLE_MUSIC_CARD
        val cardAspectRatio = if (isCard) (16f / 10f) else 1f
        val cornerRadius = if (isCard) APPLE_MUSIC_CARD_CORNER_RADIUS_DP.dp else APPLE_MUSIC_COVER_CORNER_RADIUS_DP.dp
        val cornerShape = RoundedCornerShape(cornerRadius)
        val playbackProgress by animateFloatAsState(
            targetValue = if (isPlaying) 1f else 0f,
            animationSpec = if (reduceMotion) {
                snap()
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = APPLE_MUSIC_COVER_MOTION_STIFFNESS,
                )
            },
            label = "music_artwork_playback_progress",
        )
        val playingScale = resolveAppleMusicCoverScale(isPlaying = true)
        val pausedScale = resolveAppleMusicCoverScale(isPlaying = false)
        val artworkScale = pausedScale + (playingScale - pausedScale) * playbackProgress
        val shadowElevation = resolveAppleMusicCoverShadowElevation(playbackProgress).dp
        Box(
            modifier = modifier
                // Put the transform before the visual chrome so the artwork, rounded clip,
                // border and shadow settle as one card. A later graphicsLayer only scales the
                // image subtree and leaves the old-size frame behind while pausing.
                .graphicsLayer {
                    scaleX = artworkScale
                    scaleY = artworkScale
                }
                .aspectRatio(cardAspectRatio)
                .shadow(
                    elevation = shadowElevation,
                    shape = cornerShape,
                    ambientColor = artworkShadowColor.copy(alpha = if (isDarkEnvironment) 0.45f else 0.15f),
                    spotColor = artworkShadowColor.copy(alpha = if (isDarkEnvironment) 0.55f else 0.20f)
                )
                .clip(cornerShape)
                .border(
                    width = 1.dp,
                    color = artworkBorderColor.copy(alpha = if (isDarkEnvironment) 0.20f else 0.14f),
                    shape = cornerShape
                )
                .background(
                    artworkFallbackBrush
                )
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            when {
                bitmap != null -> androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                coverUrl.isNotBlank() -> AsyncImage(
                    model = coverUrl,
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                else -> AppIcon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MusicContentColor.copy(alpha = 0.78f),
                    modifier = Modifier.size(if (isCard) 64.dp else 96.dp)
                )
            }
        }
    }
}

@Composable
private fun MusicProgress(
    state: MusicPlayerUiState,
    onSeek: (Long) -> Unit,
    glassEnabled: Boolean,
    glassTintColor: Color = Color.Unspecified,
    isDarkEnvironment: Boolean = true,
    miuixBackdrop: MiuixBackdrop? = null,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
    modifier: Modifier = Modifier
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    var draggedPosition by remember { mutableStateOf<Float?>(null) }
    val sliderValue = draggedPosition ?: state.positionMs.coerceIn(0L, duration).toFloat()
    val onSliderChange: (Float) -> Unit = { draggedPosition = it }
    val onSliderChangeFinished = {
        draggedPosition?.let { onSeek(it.toLong()) }
        draggedPosition = null
    }
    val inactiveTrackColor = lerp(
        glassTintColor.takeOrElse { MaterialTheme.colorScheme.surface },
        MaterialTheme.colorScheme.onSurface,
        if (isDarkEnvironment) 0.34f else 0.22f,
    ).copy(alpha = if (isDarkEnvironment) 0.36f else 0.24f)
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            MusicWavySlider(
                value = sliderValue,
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderChangeFinished,
                valueRange = 0f..duration.toFloat(),
                wavy = false,
                activeColor = MusicAccentColor,
                inactiveColor = inactiveTrackColor,
                thumbColor = MusicAccentColor,
                hapticStep = (duration.toFloat() * 0.05f).coerceAtLeast(1_000f),
                modifier = Modifier.height(48.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val displayedPositionMs = draggedPosition?.toLong() ?: state.positionMs
            AppText(
                formatMusicTime(displayedPositionMs),
                color = MusicContentColor.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall
            )
            AppText(
                "-${formatMusicTime((state.durationMs - displayedPositionMs).coerceAtLeast(0L))}",
                color = MusicContentColor.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * 音量滑条：沿用进度条同款波形组件，直接调节媒体音量；
 * 注册 ContentObserver 以跟随物理音量键同步。
 */
@Composable
private fun MusicVolumeSlider(
    glassTintColor: Color,
    isDarkEnvironment: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE)
            as? android.media.AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }
    fun readVolume(): Float = audioManager
        ?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        ?.toFloat()?.div(maxVolume) ?: 0f

    var volume by remember(audioManager) { mutableFloatStateOf(readVolume()) }

    DisposableEffect(audioManager) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volume = readVolume()
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    val inactiveTrackColor = lerp(
        glassTintColor.takeOrElse { MaterialTheme.colorScheme.surface },
        MaterialTheme.colorScheme.onSurface,
        if (isDarkEnvironment) 0.34f else 0.22f,
    ).copy(alpha = if (isDarkEnvironment) 0.36f else 0.24f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(
            imageVector = Icons.Outlined.VolumeDown,
            contentDescription = "静音",
            tint = MusicContentColor.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            MusicWavySlider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    audioManager?.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        (volume * maxVolume).roundToInt(),
                        0
                    )
                },
                valueRange = 0f..1f,
                wavy = false,
                activeColor = MusicAccentColor,
                inactiveColor = inactiveTrackColor,
                thumbColor = MusicAccentColor,
                hapticStep = 1f / maxVolume.toFloat(),
                modifier = Modifier.height(40.dp)
            )
        }
        AppIcon(
            imageVector = Icons.Outlined.VolumeUp,
            contentDescription = "最大音量",
            tint = MusicContentColor.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun PlaybackControls(
    state: MusicPlayerUiState,
    onPlayPause: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playButtonSizeDp: Int = 72,
    skipButtonSizeDp: Int = 56,
    isDarkEnvironment: Boolean = true,
    glassTintColor: Color = Color.Unspecified
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaybackIconButton(
            icon = Icons.Filled.SkipPrevious,
            description = "上一首",
            enabled = state.queueControls.hasPrevious && onPrevious != null,
            onClick = onPrevious ?: {},
            sizeDp = skipButtonSizeDp
        )
        MusicPlayPauseButton(
            state = state,
            onPlayPause = onPlayPause,
            sizeDp = playButtonSizeDp,
            isDarkEnvironment = isDarkEnvironment,
            glassTintColor = glassTintColor,
        )
        PlaybackIconButton(
            icon = Icons.Filled.SkipNext,
            description = "下一首",
            enabled = state.queueControls.hasNext && onNext != null,
            onClick = onNext ?: {},
            sizeDp = skipButtonSizeDp
        )
    }
}

@Composable
private fun PlayerLyricsPreview(
    lyrics: LyricDocument?,
    positionMs: Long,
    onOpenLyrics: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val activeIndex = lyrics?.let { resolveActiveLyricIndex(it, positionMs) } ?: -1
    val lines = lyrics?.lines.orEmpty()
    val prevLine = if (activeIndex > 0 && activeIndex - 1 in lines.indices) lines[activeIndex - 1] else null
    val activeLine = if (activeIndex in lines.indices) lines[activeIndex] else null
    val nextLine1 = if (activeIndex + 1 in lines.indices) lines[activeIndex + 1] else null
    val nextLine2 = if (activeIndex + 2 in lines.indices) lines[activeIndex + 2] else null

    AppSurface(
        onClick = onOpenLyrics ?: {},
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (activeLine != null) {
                // 上一行（淡出弱化呈现）
                if (prevLine != null) {
                    AppText(
                        text = prevLine.text,
                        color = MusicContentColor.copy(alpha = 0.38f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 当前行（醒目高亮）
                AppText(
                    text = activeLine.text,
                    color = MusicAccentColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // 翻译（若有）
                val (previewTranslation, _) = resolveDisplaySecondaryRows(
                    primaryText = activeLine.text,
                    translation = activeLine.translations.firstOrNull(),
                    romanization = activeLine.romanization,
                    showTranslation = true,
                )
                if (!previewTranslation.isNullOrBlank()) {
                    AppText(
                        text = previewTranslation,
                        color = MusicAccentColor.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 下一行（预览）
                if (nextLine1 != null) {
                    AppText(
                        text = nextLine1.text,
                        color = MusicContentColor.copy(alpha = 0.58f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 再下一行（若无翻译且存在下下句，展示保持 3~4 行层次感）
                if (previewTranslation.isNullOrBlank() && nextLine2 != null) {
                    AppText(
                        text = nextLine2.text,
                        color = MusicContentColor.copy(alpha = 0.32f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (lyrics != null && lines.isNotEmpty()) {
                val firstLine = lines.firstOrNull()
                val isPrelude = firstLine != null && positionMs < firstLine.startTimeMs
                val hint = if (isPrelude) "··· 前奏 ···" else "··· 间奏 ···"

                AppText(
                    text = hint,
                    color = MusicAccentColor.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                // 前奏时展示前 2~3 句歌词预览
                if (isPrelude) {
                    lines.take(3).forEachIndexed { idx, line ->
                        val alpha = when (idx) {
                            0 -> 0.65f
                            1 -> 0.45f
                            else -> 0.28f
                        }
                        AppText(
                            text = line.text,
                            color = MusicContentColor.copy(alpha = alpha),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    AppIcon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MusicContentColor.copy(alpha = 0.78f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    AppText(
                        text = "轻点查看完整歌词",
                        color = MusicContentColor.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    sizeDp: Int = 56
) {
    AppIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(sizeDp.dp)) {
        AppIcon(
            imageVector = icon,
            contentDescription = description,
            tint = MusicContentColor.copy(alpha = if (enabled) 1f else 0.28f),
            modifier = Modifier.size(32.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsPage(
    state: MusicPlayerUiState,
    glassEnabled: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onLyricsOffsetChange: (Long) -> Unit,
    onLyricsRetry: () -> Unit,
    onOpenLyricsSearch: () -> Unit,
    blurEffectsEnabled: Boolean,
    reduceMotion: Boolean,
    glassTintColor: Color,
    liquidGlassTuning: LiquidGlassTuning,
    miuixBackdrop: MiuixBackdrop?,
    progressSeekRevision: Int,
    controlsVisible: Boolean,
    onControlsVisibleChange: (Boolean) -> Unit,
    showBottomControls: Boolean = true,
    onPageTap: (() -> Unit)? = null,
    isDarkEnvironment: Boolean = true,
    lyricsUiStyle: SettingsManager.MusicLyricsUiStyle = SettingsManager.MusicLyricsUiStyle.CLASSIC,
    modifier: Modifier = Modifier
) {
    val document = state.lyrics
    val immersiveLyrics = lyricsUiStyle == SettingsManager.MusicLyricsUiStyle.IMMERSIVE
    val fullScreenLyrics = onPageTap != null
    var stableCurrentIndex by remember(document) {
        mutableIntStateOf(
            document?.let {
                resolveLastStartedLyricIndex(it.lines, state.positionMs - it.offsetMs)
            } ?: -1
        )
    }
    LaunchedEffect(state.positionMs, document) {
        if (document == null) return@LaunchedEffect
        stableCurrentIndex = resolveStableLyricIndex(
            lines = document.lines,
            positionMs = state.positionMs - document.offsetMs,
            previousIndex = stableCurrentIndex,
        )
    }
    val currentIndex = stableCurrentIndex
    val blurEnabled = resolveMusicLyricsBlurEnabled(
        sdkInt = Build.VERSION.SDK_INT,
        effectsEnabled = blurEffectsEnabled,
        reduceMotion = reduceMotion
    )
    val listState = rememberLazyListState()
    val isLyricsDragged by listState.interactionSource.collectIsDraggedAsState()
    var showTranslations by remember { mutableStateOf(true) }
    var showLyricsSettings by remember { mutableStateOf(false) }
    var isAutoFollowPaused by remember(document) { mutableStateOf(false) }
    LaunchedEffect(progressSeekRevision) {
        if (progressSeekRevision > 0) {
            isAutoFollowPaused = false
        }
    }
    LaunchedEffect(isLyricsDragged) {
        if (isLyricsDragged) {
            isAutoFollowPaused = true
        }
    }
    LaunchedEffect(currentIndex, isAutoFollowPaused, reduceMotion, immersiveLyrics) {
        // Immersive list owns its spring auto-follow; classic list uses this effect.
        if (!immersiveLyrics && currentIndex >= 0 && !isAutoFollowPaused) {
            val focusOffset = resolveLyricFocusScrollOffsetPx(
                listState.layoutInfo.viewportSize.height
            )
            if (reduceMotion) {
                listState.scrollToItem(currentIndex, focusOffset)
            } else {
                listState.animateScrollToItem(currentIndex, focusOffset)
            }
        }
    }
    // Immersive lyrics follow Halcyon PlayerLyricsPage: one horizontal 28.dp inset and a
    // single 72/72 content pad inside AppleMusicLyricsView. Do not stack the classic
    // top/bottom chrome padding on top of that.
    val chromeTopPadding = when {
        immersiveLyrics -> 0.dp
        fullScreenLyrics -> 72.dp
        showBottomControls -> 72.dp
        else -> 16.dp
    }
    val chromeBottomPadding = if (immersiveLyrics) 0.dp else if (fullScreenLyrics) 104.dp else 16.dp
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable { onControlsVisibleChange(!controlsVisible) }
            .padding(top = chromeTopPadding, bottom = chromeBottomPadding)
    ) {
        if (document == null || document.lines.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppText(
                    text = when {
                        state.isLyricsSearching -> "正在匹配歌词…"
                        state.lyricsError != null -> "歌词加载失败"
                        else -> "未找到匹配歌词"
                    },
                    color = MusicContentColor.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.headlineSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextButton(
                        label = "重新匹配",
                        glassEnabled = glassEnabled,
                        miuixBackdrop = miuixBackdrop,
                        glassTintColor = glassTintColor,
                        isDarkEnvironment = isDarkEnvironment,
                        onClick = onLyricsRetry
                    )
                    GlassTextButton(
                        label = "手动搜索",
                        glassEnabled = glassEnabled,
                        miuixBackdrop = miuixBackdrop,
                        glassTintColor = glassTintColor,
                        isDarkEnvironment = isDarkEnvironment,
                        onClick = onOpenLyricsSearch
                    )
                }
            }
        } else if (immersiveLyrics) {
            val halcyonLines = remember(document) {
                mapToHalcyonLyrics(document.lines)
            }
            val lyricMs = state.positionMs - document.offsetMs
            val halcyonIndex = remember(halcyonLines, lyricMs) {
                currentLyricIndexAt(
                    positionMs = lyricMs,
                    lyrics = halcyonLines,
                    suppressLeadingZero = true,
                ).index.coerceIn(-1, (halcyonLines.size - 1).coerceAtLeast(0))
            }
            // Wholesale Halcyon `LyricsPlayerPage` layout (header/footer chrome stay BiliPai's).
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 28.dp)
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .playerLyricPerspective(
                            // Halcyon `lyricPerspectiveEffect` defaults to false.
                            enabled = false,
                            angle = HALCYON_DEFAULT_PERSPECTIVE_ANGLE,
                            lyricTextAlign = if (fullScreenLyrics) {
                                com.android.purebilibili.feature.audio.lyrics.halcyon.PLAYER_LYRIC_ALIGN_CENTER
                            } else {
                                com.android.purebilibili.feature.audio.lyrics.halcyon.PLAYER_LYRIC_ALIGN_LEFT
                            },
                        )
                ) {
                    AppleMusicLyricsView(
                        lyrics = halcyonLines,
                        currentIndex = halcyonIndex,
                        currentPositionMs = lyricMs,
                        isPlaying = state.isPlaying,
                        isPaused = !state.isPlaying,
                        pageVisible = true,
                        showTranslation = showTranslations,
                        showPronunciation = false,
                        fontFamily = null,
                        fontWeight = androidx.compose.ui.text.font.FontWeight(
                            com.android.purebilibili.feature.audio.lyrics.halcyon.HalcyonLyricSettings.lyricFontWeight,
                        ),
                        fontScale = 1f,
                        secondaryFontScale = 1f,
                        primaryTextSizeSp = com.android.purebilibili.feature.audio.lyrics.halcyon.HalcyonLyricSettings.primaryTextSizeSp,
                        secondaryTextSizeSp = com.android.purebilibili.feature.audio.lyrics.halcyon.HalcyonLyricSettings.secondaryTextSizeSp,
                        lyricTextAlign = if (fullScreenLyrics) {
                            com.android.purebilibili.feature.audio.lyrics.halcyon.PLAYER_LYRIC_ALIGN_CENTER
                        } else {
                            com.android.purebilibili.feature.audio.lyrics.halcyon.PLAYER_LYRIC_ALIGN_LEFT
                        },
                        contentColor = MusicContentColor,
                        wordLiftEnabled = true,
                        onLineClick = { line ->
                            if (onPageTap != null) {
                                onPageTap()
                            } else {
                                isAutoFollowPaused = false
                                val seekMs = line.words.firstOrNull()?.startMs ?: line.timeMs
                                onSeek(seekMs + document.offsetMs)
                            }
                        },
                        onLineLongClick = {},
                        topContentPadding = 72.dp,
                        bottomContentPadding = 72.dp,
                        nonCurrentLineBlurEnabled = blurEnabled,
                        focusOffsetRatio = if (fullScreenLyrics) 0.40f else 0.24f,
                        useFocusLeadingPadding = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = if (showBottomControls || fullScreenLyrics) 28.dp else 12.dp,
                    top = if (showBottomControls || fullScreenLyrics) 120.dp else 24.dp,
                    end = if (showBottomControls || fullScreenLyrics) 28.dp else 16.dp,
                    bottom = 260.dp
                ),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                itemsIndexed(document.lines, key = { index, line -> "${line.startTimeMs}:$index" }) { index, line ->
                    LyricLineContent(
                        line = line,
                        isCurrent = index == currentIndex,
                        positionMs = state.positionMs - document.offsetMs,
                        showTranslations = showTranslations,
                        focusStyle = resolveMusicLyricFocusStyle(index, currentIndex, blurEnabled),
                        reduceMotion = reduceMotion,
                        centerAligned = fullScreenLyrics,
                        onClick = {
                            if (onPageTap != null) {
                                onPageTap()
                            } else {
                                isAutoFollowPaused = false
                                val seekMs = line.spans.firstOrNull()?.startTimeMs ?: line.startTimeMs
                                onSeek(seekMs + document.offsetMs)
                            }
                        }
                    )
                }
            }
        }

        if (showBottomControls) {
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp),
                enter = if (reduceMotion) EnterTransition.None else fadeIn() + slideInVertically { it / 2 },
                exit = if (reduceMotion) ExitTransition.None else fadeOut() + slideOutVertically { it / 2 }
            ) {
                LyricsPrimaryControls(
                    state = state,
                    glassEnabled = glassEnabled,
                    miuixBackdrop = miuixBackdrop,
                    glassTintColor = glassTintColor,
                    liquidGlassTuning = liquidGlassTuning,
                    isDarkEnvironment = isDarkEnvironment,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onOpenSettings = { showLyricsSettings = true },
                    onHideControls = { onControlsVisibleChange(false) },
                    showProgress = !immersiveLyrics,
                )
            }
            // Halcyon immersive lyrics page has no progress bar.
            if (!controlsVisible && !immersiveLyrics) {
                LyricsImmersiveProgress(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        } else {
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 20.dp, end = 20.dp),
                enter = if (reduceMotion) EnterTransition.None else fadeIn() + slideInVertically { -it / 2 },
                exit = if (reduceMotion) ExitTransition.None else fadeOut() + slideOutVertically { -it / 2 }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isAutoFollowPaused) {
                        GlassTextButton(
                            label = "回到当前歌词",
                            glassEnabled = glassEnabled,
                            miuixBackdrop = miuixBackdrop,
                            glassTintColor = glassTintColor,
                            isDarkEnvironment = isDarkEnvironment,
                            onClick = { isAutoFollowPaused = false }
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassTextButton(
                            label = if (showTranslations) "译:开" else "译:关",
                            glassEnabled = glassEnabled,
                            miuixBackdrop = miuixBackdrop,
                            glassTintColor = glassTintColor,
                            isDarkEnvironment = isDarkEnvironment,
                            onClick = { showTranslations = !showTranslations }
                        )
                        GlassTextButton(
                            label = "搜索",
                            glassEnabled = glassEnabled,
                            miuixBackdrop = miuixBackdrop,
                            glassTintColor = glassTintColor,
                            isDarkEnvironment = isDarkEnvironment,
                            onClick = onOpenLyricsSearch
                        )
                        GlassTextButton(
                            label = "歌词设置",
                            glassEnabled = glassEnabled,
                            miuixBackdrop = miuixBackdrop,
                            glassTintColor = glassTintColor,
                            isDarkEnvironment = isDarkEnvironment,
                            onClick = { showLyricsSettings = true }
                        )
                    }
                }
            }
        }
        if (showBottomControls && isAutoFollowPaused && controlsVisible) {
            GlassTextButton(
                label = "回到当前歌词",
                glassEnabled = glassEnabled,
                miuixBackdrop = miuixBackdrop,
                glassTintColor = glassTintColor,
                isDarkEnvironment = isDarkEnvironment,
                onClick = { isAutoFollowPaused = false },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
        }
    }

    if (showLyricsSettings) {
        val sheetContentColor = MaterialTheme.colorScheme.onSurface
        val sheetSecondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
        AppModalBottomSheet(
            onDismissRequest = { showLyricsSettings = false },
            containerColor = AppSurfaceTokens.surface(),
            contentColor = sheetContentColor
        ) {
            LyricsSettingsContent(
                showTranslations = showTranslations,
                lyricsOffsetMs = document?.offsetMs ?: 0L,
                sourceLabel = BiliSubtitleLyricsPolicy.resolveSourceLabel(document),
                contentColor = sheetContentColor,
                secondaryColor = sheetSecondaryColor,
                onToggleTranslations = { showTranslations = !showTranslations },
                onLyricsOffsetChange = onLyricsOffsetChange,
                onLyricsRetry = onLyricsRetry,
                onOpenLyricsSearch = {
                    showLyricsSettings = false
                    onOpenLyricsSearch()
                }
            )
        }
    }
}

@Composable
private fun LyricsPrimaryControls(
    state: MusicPlayerUiState,
    glassEnabled: Boolean,
    miuixBackdrop: MiuixBackdrop?,
    glassTintColor: Color,
    liquidGlassTuning: LiquidGlassTuning,
    isDarkEnvironment: Boolean = true,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onHideControls: () -> Unit,
    showProgress: Boolean = true,
) {
    val chromeSpec = resolveMusicPlayerChromeSpec(
        uiStyle = LocalAppUiStyle.current,
        glassEnabled = glassEnabled
    )
    val panelColor = resolveMusicImmersivePanelColor(
        glassTintColor,
        MaterialTheme.colorScheme.surface,
    )
    val (themeOnLight, themeOnDark) = resolveMusicPlayerThemeContentColors()
    val panelContentColor = resolveMusicPlayerContentColor(
        backgroundColor = panelColor,
        onLightBackground = themeOnLight,
        onDarkBackground = themeOnDark,
    )
    val panelShape = AppShapes.borderedContainer(ContainerLevel.Card)
    val panelMaterial = LocalMusicPlayerMaterial.current.copy(
        surfaceColor = panelColor,
        contentColor = panelContentColor,
    )
    AppSurface(
        modifier = Modifier
            .fillMaxWidth()
            .biliPaiFloatingDockShell(
                backdrop = miuixBackdrop,
                containerColor = panelColor,
                pressProgress = 0f,
                shape = panelShape,
                enabled = glassEnabled,
                blurEnabled = !glassEnabled,
                liquidGlassTuning = liquidGlassTuning,
            ),
        shape = panelShape,
        // color = Color.Transparent
        color = if (miuixBackdrop != null) Color.Transparent else panelColor,
        contentColor = panelContentColor,
        tonalElevation = if (
            chromeSpec.uiStyle == com.android.purebilibili.core.theme.AppUiStyle.MATERIAL3 &&
            miuixBackdrop == null
        ) {
            1.dp
        } else {
            0.dp
        }
    ) {
        CompositionLocalProvider(LocalMusicPlayerMaterial provides panelMaterial) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showProgress) {
                    MusicProgress(
                        state = state,
                        onSeek = onSeek,
                        glassEnabled = glassEnabled,
                        glassTintColor = glassTintColor,
                        isDarkEnvironment = isDarkEnvironment,
                        miuixBackdrop = miuixBackdrop,
                        liquidGlassTuning = liquidGlassTuning,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaybackControls(
                        state = state,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        modifier = Modifier.weight(1f),
                        playButtonSizeDp = chromeSpec.playButtonSizeDp,
                        skipButtonSizeDp = chromeSpec.skipButtonSizeDp,
                        isDarkEnvironment = isDarkEnvironment,
                        glassTintColor = glassTintColor
                    )
                    AppTextButton(onClick = onOpenSettings, modifier = Modifier.height(48.dp)) {
                        AppText("歌词设置", color = MusicContentColor, style = MaterialTheme.typography.labelMedium)
                    }
                    AppTextButton(onClick = onHideControls, modifier = Modifier.height(48.dp)) {
                        AppText("收起", color = MusicContentColor, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun formatLyricsOffset(offsetMs: Long): String {
    if (offsetMs == 0L) return "校正 0.00s"
    val absoluteMs = kotlin.math.abs(offsetMs)
    val seconds = absoluteMs / 1_000L
    val hundredths = (absoluteMs % 1_000L) / 10L
    val sign = if (offsetMs > 0L) "+" else "-"
    return "校正 $sign$seconds.${hundredths.toString().padStart(2, '0')}s"
}

@Composable
private fun LyricsOffsetSettingsContent(
    offsetMs: Long,
    sourceLabel: String,
    onOffsetChange: (Long) -> Unit,
    subtitleLanguageOptions: List<Pair<String, String>> = emptyList(),
    selectedSubtitleTrackKey: String? = null,
    onSubtitleTrackSelected: (String) -> Unit = {},
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppText(
            text = "字幕 / 歌词设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
        AppText("字幕语言", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = contentColor)
        if (subtitleLanguageOptions.isEmpty()) {
            AppText("当前视频没有可切换的字幕语言", color = secondaryColor)
        } else {
            subtitleLanguageOptions.forEach { (trackKey, label) ->
                AppTextButton(
                    onClick = { onSubtitleTrackSelected(trackKey) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    AppText(
                        text = if (trackKey == selectedSubtitleTrackKey) "✓  $label" else label,
                        color = if (trackKey == selectedSubtitleTrackKey) MaterialTheme.colorScheme.primary else contentColor,
                    )
                }
            }
        }
        if (sourceLabel.isNotBlank()) {
            AppText(
                text = "当前来源 · $sourceLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
            )
        }
        AppText(
            text = formatLyricsOffset(offsetMs),
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryColor,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppTextButton(
                onClick = { onOffsetChange(-250L) },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                AppText("提前 0.25 秒", color = contentColor)
            }
            AppTextButton(
                onClick = { onOffsetChange(250L) },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                AppText("延后 0.25 秒", color = contentColor)
            }
        }
        AppTextButton(
            onClick = { onOffsetChange(-offsetMs) },
            modifier = Modifier.height(48.dp),
        ) {
            AppText("重置时间校正", color = contentColor)
        }
    }
}

@Composable
private fun LyricsImmersiveProgress(
    state: MusicPlayerUiState,
    modifier: Modifier = Modifier
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    AppLinearProgressIndicator(
        progress = { state.positionMs.coerceIn(0L, duration).toFloat() / duration.toFloat() },
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MusicContentColor,
        trackColor = MusicContentColor.copy(alpha = 0.22f)
    )
}

@Composable
private fun MusicPlayPauseButton(
    state: MusicPlayerUiState,
    onPlayPause: () -> Unit,
    sizeDp: Int,
    isDarkEnvironment: Boolean,
    glassTintColor: Color,
    modifier: Modifier = Modifier,
) {
    // 实心亮面主按钮：白底深色图标，与动态封面背景解耦，任何色板下都干净醒目。
    val playButtonBg = Color.White.copy(alpha = 0.94f)
    val playButtonFg = Color(0xFF1C1B1F)
    val playButtonBorder = Color.Black.copy(alpha = 0.10f)
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.35f),
            )
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(color = playButtonBg, radius = radius)
                drawCircle(
                    color = playButtonBorder,
                    radius = radius - 0.8.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlayPause,
            ),
        contentAlignment = Alignment.Center
    ) {
        if (state.isBuffering) {
            AppCircularProgressIndicator(
                color = playButtonFg,
                modifier = Modifier.size((sizeDp * 0.45f).dp)
            )
        } else {
            AppIcon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                tint = playButtonFg,
                modifier = Modifier.size((sizeDp * 0.50f).dp)
            )
        }
    }
}

@Composable
private fun LyricsSettingsContent(
    showTranslations: Boolean,
    lyricsOffsetMs: Long,
    sourceLabel: String = "",
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onToggleTranslations: () -> Unit,
    onLyricsOffsetChange: (Long) -> Unit,
    onLyricsRetry: () -> Unit,
    onOpenLyricsSearch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppText(
            "歌词设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
        if (sourceLabel.isNotBlank()) {
            AppText(
                "当前来源 · $sourceLabel",
                color = secondaryColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        MusicActionSheetItem(
            if (showTranslations) "隐藏翻译与罗马音" else "显示翻译与罗马音",
            contentColor = contentColor,
            onClick = onToggleTranslations
        )
        AppText(
            "歌词时间校正 · ${formatLyricsOffset(lyricsOffsetMs)}",
            color = secondaryColor
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextButton(onClick = { onLyricsOffsetChange(-250L) }, modifier = Modifier.height(48.dp)) {
                AppText("歌词提前 0.25 秒", color = contentColor)
            }
            AppTextButton(onClick = { onLyricsOffsetChange(250L) }, modifier = Modifier.height(48.dp)) {
                AppText("歌词延后 0.25 秒", color = contentColor)
            }
        }
        AppTextButton(onClick = { onLyricsOffsetChange(-lyricsOffsetMs) }, modifier = Modifier.height(48.dp)) {
            AppText("重置歌词时间", color = contentColor)
        }
        MusicActionSheetItem("重新匹配歌词", contentColor = contentColor, onClick = onLyricsRetry)
        MusicActionSheetItem("手动搜索歌词", contentColor = contentColor, onClick = onOpenLyricsSearch)
    }
}

@Composable
private fun LyricLineContent(
    line: LyricLine,
    isCurrent: Boolean,
    positionMs: Long,
    showTranslations: Boolean,
    focusStyle: MusicLyricFocusStyle,
    reduceMotion: Boolean,
    immersive: Boolean = false,
    centerAligned: Boolean = false,
    onClick: () -> Unit
) {
    val transition = updateTransition(targetState = focusStyle, label = "lyric_focus")
    val blurRadius = transition.animateDp(
        transitionSpec = { if (reduceMotion) snap() else AppMotionTokens.standardSpec() },
        label = "lyric_blur"
    ) { it.blurRadiusDp.dp }
    val alpha = transition.animateFloat(
        transitionSpec = { if (reduceMotion) snap() else AppMotionTokens.standardSpec() },
        label = "lyric_alpha"
    ) { it.alphaPercent / 100f }
    val focusModifier = if (Build.VERSION.SDK_INT >= 31 && blurRadius.value > 0.dp) {
        Modifier.blur(blurRadius.value, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }
    val textStyle = when {
        immersive && isCurrent -> MaterialTheme.typography.headlineLarge
        immersive -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.headlineSmall
    }
    val lineHeight = when {
        immersive && isCurrent -> 42.sp
        immersive -> 26.sp
        else -> 34.sp
    }
    val fontWeight = when {
        immersive && isCurrent -> FontWeight.Bold
        immersive -> FontWeight.Medium
        else -> FontWeight.Bold
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusModifier)
            .graphicsLayer { this.alpha = alpha.value }
            .clickable(onClick = onClick),
        horizontalAlignment = if (centerAligned) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        AppText(
            text = buildLyricText(line, isCurrent, positionMs, MusicContentColor),
            color = MusicContentColor,
            style = textStyle,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
        )
        val (displayTranslation, displayRomanization) = resolveDisplaySecondaryRows(
            primaryText = line.text,
            translation = line.translations.firstOrNull(),
            romanization = line.romanization,
            showTranslation = showTranslations,
        )
        displayTranslation?.let {
            AppText(
                text = it,
                color = MusicContentColor.copy(alpha = if (immersive) 0.64f else 0.72f),
                style = if (immersive) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = if (immersive) 4.dp else 5.dp),
                textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
            )
        }
        displayRomanization?.let {
            AppText(
                text = it,
                color = MusicContentColor.copy(alpha = if (immersive) 0.48f else 0.58f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 3.dp),
                textAlign = if (centerAligned) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

private fun buildLyricText(
    line: LyricLine,
    isCurrent: Boolean,
    positionMs: Long,
    contentColor: Color,
): AnnotatedString {
    if (!isCurrent) return AnnotatedString(line.text)
    return buildAnnotatedString {
        if (line.spans.isEmpty()) {
            val progress = resolveLineSweepProgress(line, positionMs)
            appendKaraokeFill(line.text, progress, contentColor)
        } else {
            line.spans.forEach { span ->
                val progress = resolveSpanHighlightProgress(span, positionMs)
                appendKaraokeFill(span.text, progress, contentColor)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendKaraokeFill(
    text: String,
    progress: Float,
    contentColor: Color,
) {
    if (text.isEmpty()) return
    text.forEachIndexed { index, char ->
        val alpha = resolveCharHighlightAlpha(
            charIndex = index,
            charCount = text.length,
            progress = progress,
        )
        pushStyle(SpanStyle(color = contentColor.copy(alpha = alpha)))
        append(char)
        pop()
    }
}

@Composable
private fun MusicTopBar(
    glassEnabled: Boolean,
    miuixBackdrop: MiuixBackdrop?,
    liquidGlassTuning: LiquidGlassTuning,
    glassTintColor: Color = Color.Unspecified,
    isDarkEnvironment: Boolean = true,
    onBack: () -> Unit,
    onMore: () -> Unit,
    actionsPopup: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onToggleLayout: (() -> Unit)? = null,
    layoutActionLabel: String = "切换布局",
    leadingActions: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(
            icon = Icons.Outlined.KeyboardArrowDown,
            description = "返回",
            glassEnabled = glassEnabled,
            miuixBackdrop = miuixBackdrop,
            liquidGlassTuning = liquidGlassTuning,
            glassTintColor = glassTintColor,
            isDarkEnvironment = isDarkEnvironment,
            onClick = onBack
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (leadingActions != null) {
                leadingActions()
            }
            if (onToggleLayout != null) {
                GlassIconButton(
                    icon = Icons.Outlined.QueueMusic,
                    description = layoutActionLabel,
                    glassEnabled = glassEnabled,
                    miuixBackdrop = miuixBackdrop,
                    liquidGlassTuning = liquidGlassTuning,
                    glassTintColor = glassTintColor,
                    isDarkEnvironment = isDarkEnvironment,
                    onClick = onToggleLayout
                )
            }
            Box {
                GlassIconButton(
                    icon = Icons.Outlined.MoreHoriz,
                    description = "更多操作",
                    glassEnabled = glassEnabled,
                    miuixBackdrop = miuixBackdrop,
                    liquidGlassTuning = liquidGlassTuning,
                    glassTintColor = glassTintColor,
                    isDarkEnvironment = isDarkEnvironment,
                    onClick = onMore
                )
                actionsPopup()
            }
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    description: String,
    glassEnabled: Boolean,
    miuixBackdrop: MiuixBackdrop?,
    liquidGlassTuning: LiquidGlassTuning,
    glassTintColor: Color = Color.Unspecified,
    isDarkEnvironment: Boolean = true,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    val maxDragPx = with(LocalDensity.current) { 36.dp.toPx() }
    val expansionPx = with(LocalDensity.current) { 4.dp.toPx() }
    val releaseSpec = remember {
        spring<Float>(
            dampingRatio = 0.5f,
            stiffness = 300f,
        )
    }

    AppIconButton(
        onClick = onClick,
        modifier = Modifier
            .graphicsLayer {
                val transform = resolveMusicTopControlTransform(
                    dragX = dragX.value,
                    dragY = dragY.value,
                    maxDragPx = maxDragPx,
                    widthPx = size.width,
                    heightPx = size.height,
                    expansionPx = expansionPx,
                )
                scaleX = transform.scaleX
                scaleY = transform.scaleY
                translationX = transform.translationX
                translationY = transform.translationY
            }
            .pointerInput(maxDragPx, releaseSpec) {
                detectDragGestures(
                    onDragCancel = {
                        scope.launch {
                            launch { dragX.animateTo(0f, releaseSpec) }
                            launch { dragY.animateTo(0f, releaseSpec) }
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            launch { dragX.animateTo(0f, releaseSpec) }
                            launch { dragY.animateTo(0f, releaseSpec) }
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch {
                        dragX.snapTo((dragX.value + dragAmount.x).coerceIn(-maxDragPx, maxDragPx))
                        dragY.snapTo((dragY.value + dragAmount.y).coerceIn(-maxDragPx, maxDragPx))
                    }
                }
            }
            .biliPaiFloatingDockShell(
                backdrop = miuixBackdrop,
                containerColor = resolveMusicGlassContainerColor(glassTintColor, isDarkEnvironment),
                pressProgress = 0f,
                shape = CircleShape,
                enabled = glassEnabled,
                blurEnabled = !glassEnabled,
                liquidGlassTuning = liquidGlassTuning,
            )
            .border(
                width = 0.5.dp,
                color = resolveMusicGlassBorderColor(glassTintColor, isDarkEnvironment),
                shape = CircleShape
            )
    ) {
        AppIcon(
            icon,
            contentDescription = description,
            tint = MusicContentColor,
        )
    }
}

@Composable
private fun GlassTextButton(
    label: String,
    glassEnabled: Boolean,
    miuixBackdrop: MiuixBackdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    glassTintColor: Color = Color.Unspecified,
    isDarkEnvironment: Boolean = true,
    liquidGlassTuning: LiquidGlassTuning = resolveLiquidGlassTuning(progress = 0.5f),
) {
    val shape = CircleShape
    val containerColor = if (isSelected) {
        MusicAccentColor.copy(alpha = 0.26f)
    } else {
        resolveMusicGlassContainerColor(glassTintColor, isDarkEnvironment)
    }
    val borderColor = if (isSelected) {
        MusicAccentColor.copy(alpha = 0.40f)
    } else {
        resolveMusicGlassBorderColor(glassTintColor, isDarkEnvironment)
    }
    val textColor = if (isSelected) {
        MusicAccentColor
    } else {
        MusicContentColor
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .biliPaiFloatingDockShell(
                backdrop = miuixBackdrop,
                containerColor = containerColor,
                pressProgress = 0f,
                shape = shape,
                enabled = glassEnabled,
                blurEnabled = !glassEnabled,
                liquidGlassTuning = liquidGlassTuning,
            )
            .border(0.8.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        AppText(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun TabletopPlayerLayout(
    coverStyle: MusicCoverStyle,
    state: MusicPlayerUiState,
    queue: List<MusicQueueItemUi>,
    currentIndex: Int,
    artworkBitmap: ImageBitmap?,
    glassEnabled: Boolean,
    reduceMotion: Boolean,
    lyricsBlurEffectsEnabled: Boolean,
    backgroundColor: Color,
    musicBackdrop: MiuixBackdrop?,
    liquidGlassTuning: LiquidGlassTuning,
    progressSeekRevision: Int,
    isLiked: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onQueueItemSelected: (Int) -> Unit,
    onLikeClick: (() -> Unit)?,
    onToggleCoverStyle: () -> Unit,
    onLyricsOffsetChange: (Long) -> Unit,
    onLyricsRetry: () -> Unit,
    onOpenLyricsSearch: () -> Unit,
    availableWidthDp: Int,
    hingeStartDp: Int? = null,
    hingeEndDp: Int? = null,
    isDarkEnvironment: Boolean = true,
    lyricsUiStyle: SettingsManager.MusicLyricsUiStyle = SettingsManager.MusicLyricsUiStyle.CLASSIC,
    modifier: Modifier = Modifier
) {
    val tabletopDensity = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 40.dp, bottom = 4.dp)
    ) {
        val contentHeightDp = maxHeight.value.roundToInt()
        val topChromeOffsetDp = with(tabletopDensity) {
            WindowInsets.statusBars.getTop(this).toDp().value.roundToInt()
        } + 40
        val paneSizes = resolveMusicTabletopPaneSizes(
            availableHeightDp = contentHeightDp,
            hingeStartDp = hingeStartDp?.minus(topChromeOffsetDp),
            hingeEndDp = hingeEndDp?.minus(topChromeOffsetDp),
        )
        Column(modifier = Modifier.fillMaxSize()) {
        // 上半部分（观赏区）：左侧封面 + 右侧滚动歌词
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(paneSizes.upperHeightDp.dp)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面在独立左栏内居中，与歌词保留稳定间距。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val artworkSize = minOf(
                    (((availableWidthDp - 68) / 2) * 0.88f).toInt().coerceAtLeast(0),
                    (paneSizes.upperHeightDp - 24).coerceAtLeast(0),
                    340
                ).coerceAtLeast(0)

                MusicArtwork(
                    coverUrl = state.coverUrl,
                    bitmap = artworkBitmap,
                    isPlaying = state.isPlaying,
                    rotate = state.isPlaying && !reduceMotion,
                    playbackSpeed = state.playbackSpeed,
                    coverStyle = coverStyle,
                    reduceMotion = reduceMotion,
                    shape = CircleShape,
                    isDarkEnvironment = isDarkEnvironment,
                    onClick = onToggleCoverStyle,
                    modifier = Modifier.width(artworkSize.dp)
                )
            }

            // 右侧歌词
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                LyricsPage(
                    state = state,
                    glassEnabled = glassEnabled,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onLyricsOffsetChange = onLyricsOffsetChange,
                    onLyricsRetry = onLyricsRetry,
                    onOpenLyricsSearch = onOpenLyricsSearch,
                    blurEffectsEnabled = lyricsBlurEffectsEnabled,
                    reduceMotion = reduceMotion,
                    glassTintColor = backgroundColor,
                    isDarkEnvironment = isDarkEnvironment,
                    liquidGlassTuning = liquidGlassTuning,
                    miuixBackdrop = musicBackdrop,
                    progressSeekRevision = progressSeekRevision,
                    controlsVisible = false,
                    onControlsVisibleChange = {},
                    showBottomControls = false,
                    lyricsUiStyle = lyricsUiStyle,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (paneSizes.hingeGapDp > 0) {
            Spacer(Modifier.height(paneSizes.hingeGapDp.dp))
        }

        // 下半部分：同宽进度与控制区，下方展开唱片架。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(paneSizes.lowerHeightDp.dp),
            contentAlignment = Alignment.Center
        ) {
            val cardSizeDp = minOf(
                (availableWidthDp * 0.24f).toInt(),
                215
            ).coerceAtLeast(0)

            Music3DCoverFlow(
                queue = queue,
                currentIndex = currentIndex,
                isPlaying = state.isPlaying,
                onItemClick = onQueueItemSelected,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                isLiked = isLiked,
                onLikeClick = onLikeClick,
                cardSizeDp = cardSizeDp,
                // 让细进度轨道接近截图中的内容宽度，同时给左右保留呼吸空间。
                controlsWidthDp = (availableWidthDp * 0.80f).toInt().coerceIn(320, 920),
                showTransportControls = true,
                shelfBelowControls = true,
                progressContent = {
                    MusicProgress(
                        state = state,
                        onSeek = onSeek,
                glassEnabled = glassEnabled,
                        glassTintColor = backgroundColor,
                        isDarkEnvironment = isDarkEnvironment,
                        miuixBackdrop = musicBackdrop,
                        liquidGlassTuning = liquidGlassTuning,
                    )
                },
                glassEnabled = glassEnabled,
                miuixBackdrop = musicBackdrop,
                liquidGlassTuning = liquidGlassTuning,
                glassTintColor = backgroundColor,
                isDarkEnvironment = isDarkEnvironment,
                reduceMotion = reduceMotion,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 6.dp)
            )
        }
        }
    }
}

@Composable
private fun ExpandedQueuePane(
    queue: List<MusicQueueItemUi>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    onClose: () -> Unit,
    glassEnabled: Boolean,
    reduceMotion: Boolean,
    miuixBackdrop: MiuixBackdrop?,
    glassTintColor: Color,
    isDarkEnvironment: Boolean = true,
    liquidGlassTuning: LiquidGlassTuning,
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit = {},
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    isLiked: Boolean = false,
    onLikeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isCoverFlowView by remember { mutableStateOf(true) }
    val panelShape = AppShapes.borderedContainer(ContainerLevel.Card)
    val panelColor = resolveMusicImmersivePanelColor(
        glassTintColor,
        MaterialTheme.colorScheme.surface,
    )
    AppSurface(
        shape = panelShape,
        color = if (miuixBackdrop != null) Color.Transparent else panelColor,
        contentColor = MusicContentColor,
        border = BorderStroke(1.dp, resolveMusicGlassBorderColor(glassTintColor, isDarkEnvironment)),
        modifier = modifier
            .fillMaxSize()
            .biliPaiFloatingDockShell(
                backdrop = miuixBackdrop,
                containerColor = panelColor,
                pressProgress = 0f,
                shape = panelShape,
                enabled = glassEnabled,
                blurEnabled = !glassEnabled,
                liquidGlassTuning = liquidGlassTuning
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppText(
                        text = if (queue.isNotEmpty()) "待播清单 (${queue.size})" else "待播清单",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MusicContentColor
                    )
                    if (queue.isNotEmpty()) {
                        GlassTextButton(
                            label = if (isCoverFlowView) "3D 唱片架" else "列表",
                            isSelected = true,
                            glassEnabled = glassEnabled,
                            miuixBackdrop = miuixBackdrop,
                            glassTintColor = glassTintColor,
                            isDarkEnvironment = isDarkEnvironment,
                            onClick = { isCoverFlowView = !isCoverFlowView }
                        )
                    }
                }
                GlassTextButton(
                    label = "返回歌词",
                    glassEnabled = glassEnabled,
                    miuixBackdrop = miuixBackdrop,
                    glassTintColor = glassTintColor,
                    isDarkEnvironment = isDarkEnvironment,
                    onClick = onClose
                )
            }
            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AppText(
                        text = "待播清单为空",
                        color = MusicContentColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else if (isCoverFlowView) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val availableWidth = maxWidth
                    val availableHeight = maxHeight
                    val adaptiveCardSizeDp = minOf(
                        (availableWidth.value * 0.52f).toInt(),
                        (availableHeight.value * 0.46f).toInt(),
                        230
                    ).coerceAtLeast(165)

                    Music3DCoverFlow(
                        queue = queue,
                        currentIndex = currentIndex,
                        isPlaying = isPlaying,
                        onItemClick = onItemClick,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        isLiked = isLiked,
                        onLikeClick = onLikeClick,
                        cardSizeDp = adaptiveCardSizeDp,
                        showTransportControls = false,
                        glassEnabled = glassEnabled,
                        miuixBackdrop = miuixBackdrop,
                        liquidGlassTuning = liquidGlassTuning,
                        glassTintColor = glassTintColor,
                        isDarkEnvironment = isDarkEnvironment,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(queue, key = { _, item -> item.stableId }) { index, item ->
                        val isPlayingItem = index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isPlayingItem) MusicAccentColor.copy(alpha = 0.16f) else Color.Transparent
                                )
                                .clickable { onItemClick(index) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.coverUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                AppText(
                                    text = item.title,
                                    color = if (isPlayingItem) MusicAccentColor else MusicContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isPlayingItem) FontWeight.Bold else FontWeight.Normal,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.height(2.dp))
                                AppText(
                                    text = item.artist.ifBlank { "未知艺术家" },
                                    color = if (isPlayingItem) MusicAccentColor.copy(alpha = 0.78f) else MusicContentColor.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (isPlayingItem) {
                                Spacer(Modifier.width(8.dp))
                                AppIcon(
                                    Icons.Outlined.MusicNote,
                                    contentDescription = "正在播放",
                                    tint = MusicAccentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicActionSheetItem(
    label: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        AppText(label, color = contentColor, style = MaterialTheme.typography.bodyLarge)
    }
}

private data class MusicArtworkPalette(
    val bitmap: ImageBitmap,
    val baseColor: Color,
    val accentColor: Color,
    val palette: com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette,
)

private suspend fun loadMusicArtwork(
    imageLoader: ImageLoader,
    coverUrl: String,
    context: android.content.Context
): MusicArtworkPalette? = withContext(Dispatchers.IO) {
    if (coverUrl.isBlank()) return@withContext null
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(coverUrl)
            .allowHardware(false)
            .size(512, 512)
            .build()
        val result = imageLoader.execute(request) as SuccessResult
        val bitmap = (result.image as coil3.BitmapImage).bitmap
        val playerPalette =
            com.android.purebilibili.feature.audio.lyrics.halcyon.PlayerPalette.fromCoverBackground(
                bitmap = bitmap,
                light = false,
            )
        MusicArtworkPalette(
            bitmap = bitmap.asImageBitmap(),
            baseColor = playerPalette.middle,
            accentColor = playerPalette.accent,
            palette = playerPalette,
        )
    }.getOrNull()
}

internal fun formatMusicTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
