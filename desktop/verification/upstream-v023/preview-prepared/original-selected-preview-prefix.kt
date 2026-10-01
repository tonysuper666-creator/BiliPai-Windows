// 文件路径: feature/dynamic/components/ImagePreviewDialog.kt
package com.android.purebilibili.feature.dynamic.components

import coil3.network.NetworkHeaders
import coil3.network.httpHeaders

import coil3.request.crossfade
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText

import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.AppChromeSizeTokens

import com.android.purebilibili.core.ui.MediaContrastPalette

import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel

import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Window
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
//  Material Icons
import androidx.compose.material3.*
import com.android.purebilibili.core.ui.components.AppFilledIconButton
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppIconButtonDefaults
import com.android.purebilibili.core.ui.components.AppTextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import com.android.purebilibili.core.ui.LocalPredictiveBackGestureEnabled
import com.android.purebilibili.core.ui.rememberAppShareIcon
import com.android.purebilibili.core.ui.setWindowNavigationBarColor
import com.android.purebilibili.core.ui.rememberAppLikeFilledIcon
import com.android.purebilibili.core.ui.rememberAppLikeIcon
import androidx.compose.ui.geometry.Offset
import androidx.media3.common.Player
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import com.android.purebilibili.core.ui.rememberAppRefreshIcon
import com.android.purebilibili.core.ui.rememberAppChevronDownIcon
import com.android.purebilibili.core.ui.rememberAppChevronUpIcon
import com.android.purebilibili.core.ui.rememberAppClearIcon
import com.android.purebilibili.core.ui.rememberAppDownloadIcon
import com.android.purebilibili.core.ui.rememberAppVisibilityOffIcon
import com.android.purebilibili.core.ui.rememberAppVisibilityOnIcon
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.motion.emphasizedEnterTween
import com.android.purebilibili.core.ui.motion.emphasizedExitTween
import com.android.purebilibili.core.ui.motion.interactiveSnapSpring
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.util.rememberHapticFeedback
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 *  图片预览对话框 - 支持左右滑动切换和3D立体动画
 */

internal const val IMAGE_PREVIEW_BACKDROP_TAG = "image_preview_backdrop"
internal const val IMAGE_PREVIEW_PAGE_TAG = "image_preview_page"
internal const val IMAGE_PREVIEW_COMMENT_PANEL_TAG = "image_preview_comment_panel"
internal const val IMAGE_PREVIEW_ORIGINAL_CHIP_TAG = "image_preview_original_chip"
internal const val IMAGE_PREVIEW_PAGE_INDICATOR_TAG = "image_preview_page_indicator"
private const val IMAGE_PREVIEW_SHARE_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

/**
 * 按轴分别给出水平/垂直圆角的轮廓，抵消 graphicsLayer 非均匀缩放造成的椭圆拉伸。
 * 圆角值逐帧变化，Outline 在 createOutline 内按当帧 px 生成。
 */
private class CounterScaledCornerShape(
    private val horizontalDp: Float,
    private val verticalDp: Float
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val horizontalPx = with(density) { horizontalDp.dp.toPx() }
        val verticalPx = with(density) { verticalDp.dp.toPx() }
        return androidx.compose.ui.graphics.Outline.Rounded(
            androidx.compose.ui.geometry.RoundRect(
                rect = androidx.compose.ui.geometry.Rect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(horizontalPx, verticalPx)
            )
        )
    }
}

/** 导航栏颜色用短动画过渡，替代进出场瞬间的硬切。Android 15+ 强制透明时自动短路。 */
private fun animateWindowNavigationBarColor(window: Window?, targetColor: Int, durationMillis: Long = 180L) {
    if (window == null) return
    val from = window.navigationBarColor
    if (from == targetColor) return
    ValueAnimator.ofArgb(from, targetColor).apply {
        this.duration = durationMillis
        addUpdateListener { setWindowNavigationBarColor(window, it.animatedValue as Int) }
        start()
    }
}

private data class ImagePreviewOverlayRequest(
    val token: Long,
    val images: List<String>,
    val livePhotoVideos: Map<String, String>,
    val initialIndex: Int,
    val sourceRect: androidx.compose.ui.geometry.Rect?,
    val sourceRects: Map<Int, androidx.compose.ui.geometry.Rect>,
    val activeSourceRect: androidx.compose.ui.geometry.Rect? = sourceRect,
    val sourceKey: String? = null,
    val sourceCornerRadiusDp: Float,
    val textContent: ImagePreviewTextContent?,
    val defaultTextVisible: Boolean,
    val onImageLongPress: ((String) -> Unit)?,
    val onDismiss: () -> Unit
)

/**
 * 源缩略图在图片预览打开期间应隐藏，否则飞出的图片会与原位卡片重影；
 * overlay request 在回位动画结束后才清空，因此卡片等「飞回落地」才恢复。
 * 匹配规则：捕获的 bounds 中心落在当前页来源矩形外扩 8px 范围内。
 */
@Composable
fun isImagePreviewSourceHidden(
    bounds: androidx.compose.ui.geometry.Rect?,
    sourceKey: String? = null,
): Boolean {
    val activeKey by ImagePreviewOverlayController.activeSourceKey.collectAsStateWithLifecycle()
    // 身份匹配优先：九宫格等入口用图片 URL 判定，不受窗口坐标/缩放差异影响。
    if (sourceKey != null && activeKey != null) {
        return sourceKey == activeKey
    }
    val activeSourceRect by ImagePreviewOverlayController.activeSourceRect.collectAsStateWithLifecycle()
    val sourceRect = activeSourceRect ?: return false
    if (bounds == null) return false
    return sourceRect.inflate(8f).contains(bounds.center)
}

internal fun prepareImagePreviewSourceTransition(
    sourceRect: androidx.compose.ui.geometry.Rect?,
    sourceKey: String? = null,
) {
    ImagePreviewOverlayController.prepareSourceTransition(sourceRect, sourceKey)
}

private object ImagePreviewOverlayController {
    private val _request = MutableStateFlow<ImagePreviewOverlayRequest?>(null)
    private val _activeSourceRect = MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null)
    private val _preparedSourceRect = MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null)
    private val _activeSourceKey = MutableStateFlow<String?>(null)
    val request = _request.asStateFlow()
    val activeSourceRect = _activeSourceRect.asStateFlow()
    val activeSourceKey = _activeSourceKey.asStateFlow()

    fun prepareSourceTransition(
        sourceRect: androidx.compose.ui.geometry.Rect?,
        sourceKey: String? = null,
    ) {
        // Stage the anchor without hiding the source yet. The source stays painted until
        // the preview request is committed, avoiding a blank frame between the click and
        // the first Dialog composition.
        _preparedSourceRect.value = sourceRect
        _activeSourceKey.value = sourceKey
    }

    fun show(request: ImagePreviewOverlayRequest) {
        val activeSourceRect = request.activeSourceRect ?: _preparedSourceRect.value
        _request.value = request.copy(activeSourceRect = activeSourceRect)
        // 不在这里发布 activeSourceRect：Dialog 窗口要晚 1-2 帧才画出第一帧，
        // 若提交时立刻隐藏源缩略图，窗口出现前会露出一个"洞"（感知为顿挫）。
        // 发布动作延迟到 overlay 首次组合的 SideEffect（同帧绘制，无缝衔接）。
        _preparedSourceRect.value = null
    }

    fun updateActiveSourceRect(
        token: Long,
        sourceRect: androidx.compose.ui.geometry.Rect?,
        sourceKey: String? = _activeSourceKey.value,
    ) {
        val current = _request.value ?: return
        if (current.token != token) return
        // 翻到无身份键的页时清空键，回退到几何判定。
        if (_activeSourceKey.value != sourceKey) {
            _activeSourceKey.value = sourceKey
        }
        if (current.activeSourceRect != sourceRect) {
            _request.value = current.copy(activeSourceRect = sourceRect)
        }
        if (_activeSourceRect.value != sourceRect) {
            _activeSourceRect.value = sourceRect
        }
    }

    fun dismiss(token: Long? = null) {
        val current = _request.value ?: return
        if (token == null || current.token == token) {
            _request.value = null
            _activeSourceRect.value = null
            _preparedSourceRect.value = null
            _activeSourceKey.value = null
        }
    }

    /**
     * 回位落位后的交接第一步：在 Dialog 仍显示 Hero 末帧时先恢复源缩略图，
     * 网格在其下方完成一帧重绘后再移除窗口。若把 request 清空与恢复缩略图
     * 合在同一次状态变更，两个窗口的重绘帧不对齐，落点会漏出一帧空档（闪一下）。
     */
    fun revealSourceBeforeRemoval(token: Long) {
        val current = _request.value ?: return
        if (current.token == token && _activeSourceRect.value != null) {
            _activeSourceRect.value = null
            _activeSourceKey.value = null
        }
    }
}

@Composable
fun ImagePreviewDialog(
    images: List<String>,
    initialIndex: Int,
    livePhotoVideos: Map<String, String> = emptyMap(),
    sourceRect: androidx.compose.ui.geometry.Rect? = null,
    sourceRects: Map<Int, androidx.compose.ui.geometry.Rect> = emptyMap(),
    sourceKey: String? = null,
    sourceCornerRadiusDp: Float = resolveDrawGridCornerRadiusDp().toFloat(),
    textContent: ImagePreviewTextContent? = null,
    defaultTextVisible: Boolean = true,
    onImageLongPress: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val requestToken = remember(images, initialIndex, sourceRect, sourceRects, sourceCornerRadiusDp, livePhotoVideos) { System.nanoTime() }

    DisposableEffect(requestToken) {
        ImagePreviewOverlayController.show(
            ImagePreviewOverlayRequest(
                token = requestToken,
                images = images,
                livePhotoVideos = livePhotoVideos,
                initialIndex = initialIndex,
                sourceRect = sourceRect,
                sourceRects = sourceRects,
                sourceKey = sourceKey,
                sourceCornerRadiusDp = sourceCornerRadiusDp,
                textContent = textContent,
                defaultTextVisible = defaultTextVisible,
                onImageLongPress = onImageLongPress,
                onDismiss = { latestOnDismiss() }
            )
        )
        onDispose {
            ImagePreviewOverlayController.dismiss(requestToken)
        }
    }
}

@Composable
fun ImagePreviewOverlayHost(
    modifier: Modifier = Modifier
) {
    val activeRequest by ImagePreviewOverlayController.request.collectAsStateWithLifecycle()
    activeRequest?.let { request ->
        var dismissRequestCount by remember(request.token) { mutableIntStateOf(0) }
        Dialog(
            onDismissRequest = {
                dismissRequestCount++
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogView = LocalView.current
            SideEffect {
                // The image itself already performs the return morph. The platform Dialog
                // window animation would scale it a second time when the window is removed.
                ((dialogView.parent as? DialogWindowProvider) ?: (dialogView as? DialogWindowProvider))
                    ?.window?.let { window ->
                        window.setWindowAnimations(0)
                        // 平台 Dialog 默认 FLAG_DIM_BEHIND 会在窗口挂上时把整个屏幕压暗、
                        // 关闭时瞬间变亮；画廊自带进度 scrim，这层额外 dim 表现为点击
                        // 放大/返回时的变暗闪烁，必须清掉。
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        window.setDimAmount(0f)
                    }
            }
            ImagePreviewOverlayContent(
                images = request.images,
                livePhotoVideos = request.livePhotoVideos,
                initialIndex = request.initialIndex,
                sourceRect = request.sourceRect,
                sourceRects = request.sourceRects,
                sourceKey = request.sourceKey,
                requestToken = request.token,
                sourceCornerRadiusDp = request.sourceCornerRadiusDp,
                textContent = request.textContent,
                defaultTextVisible = request.defaultTextVisible,
                onImageLongPress = request.onImageLongPress,
                dismissRequestCount = dismissRequestCount,
                onDismiss = {
                    ImagePreviewOverlayController.dismiss(request.token)
                    request.onDismiss()
                },
                modifier = modifier
                    .fillMaxSize()
                    .zIndex(100f)
            )
        }
    }
}

@Composable
private fun ImagePreviewOverlayContent(
    images: List<String>,
    initialIndex: Int,
    livePhotoVideos: Map<String, String> = emptyMap(),
    sourceRect: androidx.compose.ui.geometry.Rect? = null,
    sourceRects: Map<Int, androidx.compose.ui.geometry.Rect> = emptyMap(),
    sourceKey: String? = null,
    requestToken: Long,
    sourceCornerRadiusDp: Float = resolveDrawGridCornerRadiusDp().toFloat(),
    textContent: ImagePreviewTextContent? = null,
    defaultTextVisible: Boolean = true,
    onImageLongPress: ((String) -> Unit)? = null,
    dismissRequestCount: Int = 0,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHapticFeedback()
    val shareIcon = rememberAppShareIcon()
    val likeIcon = rememberAppLikeIcon()
    val likeFilledIcon = rememberAppLikeFilledIcon()
    val commentContext = textContent?.commentContext
    // 普通图片与评论图片共用同一套 PiliPlus 风格画廊，不再分叉评论专用 chrome。
    val useCommentPreviewChrome = false
    var isSaving by remember { mutableStateOf(false) }
    var isSharing by remember { mutableStateOf(false) }
    var showOrdinaryImageActions by remember { mutableStateOf(false) }
    
    //  获取 Activity 和 Window 用于沉浸式控制
    val activity = remember {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return@remember ctx
            ctx = ctx.baseContext
        }
        null
    }
    val window = remember { activity?.window }
    val insetsController = remember {
        window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }
    
    //  保存原始导航栏颜色
    val originalNavBarColor = remember { window?.navigationBarColor ?: android.graphics.Color.BLACK }
    
    //  进入时动画过渡到沉浸式导航栏（透明黑色），退出时动画恢复，避免颜色瞬间跳变
    DisposableEffect(Unit) {
        animateWindowNavigationBarColor(window, Color.Transparent.toArgb())
        insetsController?.isAppearanceLightNavigationBars = false

        onDispose {
            animateWindowNavigationBarColor(window, originalNavBarColor)
        }
    }
    
    //  动画状态控制
    // 0f = 关闭/初始状态 (at sourceRect), 1f = 打开状态 (Fullscreen)
    val animateTrigger = remember { androidx.compose.animation.core.Animatable(0f) }
    val backEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val predictiveBackGestureEnabled = LocalPredictiveBackGestureEnabled.current
    val backProgress = if (predictiveBackGestureEnabled) {
        (backEventState.transitionState as? NavigationEventTransitionState.InProgress)
            ?.latestEvent
            ?.progress
            ?: 0f
    } else {
        0f
    }
    var isDismissing by remember { mutableStateOf(false) }
    var dismissBackdropStartAlpha by remember { mutableFloatStateOf(1f) }
    var currentImageDisplayRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 打开飞行期间冻结首帧展示 rect，Coil 布局/缩放更新不再中途改变 counter-scale 基准。
    var flightAnchorDisplayRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var dismissImageDisplayRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 关闭起飞时冻结源缩略图 rect: dismiss 窗口内不再跟读 currentSourceRect,
    // 防止动画中途目标改道(切页/新页无 rect 时 flight 中断退化成淡出)。
    var dismissSourceRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var dismissStartProgress by remember { mutableFloatStateOf(1f) }
    var activeZoomScale by remember { mutableFloatStateOf(1f) }
    // 放大态退出: 先把 ZoomableImage 子层缩放回弹到 fit,再开始飞回。
    var zoomResetTrigger by remember { mutableIntStateOf(0) }
    var isVerticalDismissDragging by remember { mutableStateOf(false) }
    val longPressSaveEnabled by SettingsManager.getImagePreviewLongPressSaveEnabled(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val gallery3dPageEnabled by SettingsManager.getImagePreview3dPageEnabled(context)
        .collectAsStateWithLifecycle(initialValue = false)
    var imagePreviewTextVisible by remember(textContent, defaultTextVisible) {
        mutableStateOf(
            resolveImagePreviewInitialTextVisibility(
                hasText = textContent != null,
                defaultVisible = defaultTextVisible
            )
        )
    }
    
    // 竖滑跟手用状态值，避免每帧 launch snapTo 竞态导致滑不动。
    var verticalDismissOffsetYPx by remember { mutableFloatStateOf(0f) }
    // 竖滑退出时手指横向漂移的实时位移：图片跟随手指移动到屏幕各处
    var verticalDismissOffsetXPx by remember { mutableFloatStateOf(0f) }
    val verticalDismissSnapAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    val verticalDismissSnapAnimX = remember { androidx.compose.animation.core.Animatable(0f) }

    fun handleImageSaveResult(success: Boolean, successMessage: String = "图片已保存到相册") {
        haptic(resolveImagePreviewSaveFeedback(success))
        Toast.makeText(
            context,
            if (success) successMessage else "保存失败，请重试",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun handleImageShareResult(success: Boolean) {
        haptic(resolveImagePreviewSaveFeedback(success))
        if (!success) {
            Toast.makeText(context, "分享失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    //  GIF 图片加载器
    val gifImageLoader = context.imageLoader

    //  使用 HorizontalPager 实现滑动切换
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { images.size }
    )

    fun sourceRectForPage(page: Int): androidx.compose.ui.geometry.Rect? =
        sourceRect.takeIf { page == initialIndex } ?: sourceRects[page]

    SideEffect {
        // dismiss 期间源缩略图隐藏区即将被 revealSourceBeforeRemoval 交接,
        // 不再跟翻页更新,避免隐藏区错位。
        if (isDismissing) return@SideEffect
        ImagePreviewOverlayController.updateActiveSourceRect(
            token = requestToken,
            sourceRect = sourceRectForPage(pagerState.currentPage),
            // 身份键仅在起始页有效；翻到无锚点的页回退几何判定。
            sourceKey = sourceKey?.takeIf { pagerState.currentPage == initialIndex }
        )
    }

    // 已通过「查看原图」切换为高分辨率安全采样的页（按页索引记录）。
    var originalQualityPages by remember { mutableStateOf(setOf<Int>()) }

    LaunchedEffect(pagerState.currentPage) {
        activeZoomScale = 1f
        if (!isDismissing) {
            isVerticalDismissDragging = false
            verticalDismissOffsetYPx = 0f
            verticalDismissOffsetXPx = 0f
            verticalDismissSnapAnim.snapTo(0f)
            verticalDismissSnapAnimX.snapTo(0f)
            flightAnchorDisplayRect = null
        }
    }
    
    val currentLiveVideoUrl = remember(pagerState.currentPage, livePhotoVideos, images) {
        val raw = images.getOrNull(pagerState.currentPage).orEmpty()
        resolveLivePhotoVideoUrl(raw, pagerState.currentPage, livePhotoVideos)
    }
    var isLivePhotoPlaying by remember(pagerState.currentPage) { mutableStateOf(true) }
    var isLivePhotoEnabled by remember(pagerState.currentPage) { mutableStateOf(true) }
    var isLivePhotoMuted by remember(pagerState.currentPage) { mutableStateOf(false) }
    var showLivePhotoMenu by remember(pagerState.currentPage) { mutableStateOf(false) }
    var livePhotoPlayer by remember { mutableStateOf<Player?>(null) }

    var pendingSaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermission = com.android.purebilibili.core.util.rememberStoragePermissionState { granted ->
        if (granted) {
            val action = pendingSaveAction
            pendingSaveAction = null
            action?.invoke()
        }
    }

    fun requestSaveCurrentImage(imageUrl: String) {
        if (imageUrl.isEmpty() || isSaving) return
        if (onImageLongPress != null) {
            onImageLongPress(imageUrl)
            return
        }
        if (storagePermission.isGranted) {
            isSaving = true
            scope.launch {
                val success = saveImageToGallery(context, imageUrl)
                isSaving = false
                withContext(Dispatchers.Main.immediate) {
                    handleImageSaveResult(success)
                }
            }
        } else {
            pendingSaveAction = { requestSaveCurrentImage(imageUrl) }
            storagePermission.request()
        }
    }

    fun requestSaveMotionPhoto(imageUrl: String, videoUrl: String) {
        if (imageUrl.isEmpty() || videoUrl.isEmpty() || isSaving) return
        if (storagePermission.isGranted) {
            isSaving = true
            scope.launch {
                val success = saveMotionPhotoToGallery(context, imageUrl, videoUrl)
                isSaving = false
                withContext(Dispatchers.Main.immediate) {
                    handleImageSaveResult(success, successMessage = "实况照片已保存到相册")
                }
            }
        } else {
            pendingSaveAction = { requestSaveMotionPhoto(imageUrl, videoUrl) }
            storagePermission.request()
        }
    }

    fun requestSaveLivePhotoVideo(videoUrl: String) {
        if (videoUrl.isEmpty() || isSaving) return
        if (storagePermission.isGranted) {
            isSaving = true
            scope.launch {
                val success = saveLivePhotoVideoToGallery(context, videoUrl)
                isSaving = false
                withContext(Dispatchers.Main.immediate) {
                    handleImageSaveResult(success, successMessage = "实况视频已保存到相册")
                }
            }
        } else {
            pendingSaveAction = { requestSaveLivePhotoVideo(videoUrl) }
            storagePermission.request()
        }
    }

    fun requestSaveAllImages() {
        if (images.isEmpty() || isSaving) return
        val urls = images.map(::normalizeImageUrl).filter(String::isNotEmpty)
        if (storagePermission.isGranted) {
            isSaving = true
            scope.launch {
                val success = urls.map { saveImageToGallery(context, it) }.all { it }
                isSaving = false
                withContext(Dispatchers.Main.immediate) { handleImageSaveResult(success) }
            }
        } else {
            pendingSaveAction = { requestSaveAllImages() }
            storagePermission.request()
        }
    }

    fun requestShareCurrentImage(imageUrl: String) {
        if (imageUrl.isEmpty() || isSharing) return
        isSharing = true
        scope.launch {
            val success = shareImageFromPreview(context, imageUrl)
            isSharing = false
            withContext(Dispatchers.Main.immediate) {
                handleImageShareResult(success)
            }
        }
    }
    
    // 当前页的图片 URL
    val currentImageUrl = remember(pagerState.currentPage, images) {
        normalizeImageUrl(images.getOrNull(pagerState.currentPage) ?: "")
    }
    
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
            val constraints = this
            val fullWidth = constraints.maxWidth
            val fullHeight = constraints.maxHeight
            val fullWidthPx = with(density) { fullWidth.toPx() }
            val fullHeightPx = with(density) { fullHeight.toPx() }

            // 手势 scrub 期间画面由 backProgress 驱动；transitionState 离开 InProgress 的
            // 瞬间 backProgress 归零而 animateTrigger 仍为 1f，若直接回落会让画面先跳回
            // 全屏再重新飞出（双重回弹）。记住最后一帧 scrub 值，在此过渡窗口内保持。
            var lastScrubRawProgress by remember { mutableFloatStateOf(1f) }
            var backRecovering by remember { mutableStateOf(false) }
            // 恢复动画的所有权纪元:被新 scrub/dismiss 接管后,旧协程 finally 里的
            // 状态复位全部作废,防止晚到的写覆盖当前手势进度。
            var backRecoverEpoch by remember { mutableIntStateOf(0) }
            SideEffect {
                if (backProgress > 0f) {
                    lastScrubRawProgress = 1f - backProgress
                }
            }

            // Read frame-rate state inside the graphics/draw modifier blocks below. This keeps
            // the pager and its image subtree out of composition on every animation frame.
            fun currentTransitionProgress(): Float = when {
                isDismissing || backRecovering -> animateTrigger.value
                backProgress > 0f -> 1f - backProgress
                lastScrubRawProgress < 1f -> lastScrubRawProgress
                else -> animateTrigger.value
            }

            val currentSourceRect = sourceRectForPage(pagerState.currentPage)
            val shouldUseRectAnim = currentSourceRect != null && !pagerState.isScrollInProgress
            val previewSurfaceRect = remember(constraints.maxWidth, constraints.maxHeight) {
                androidx.compose.ui.geometry.Rect(
                    left = 0f,
                    top = 0f,
                    right = with(density) { constraints.maxWidth.toPx() },
                    bottom = with(density) { constraints.maxHeight.toPx() }
                )
            }

            fun currentFlightRect(progress: Float): androidx.compose.ui.geometry.Rect? {
                if (!shouldUseRectAnim && !isDismissing) return null
                // dismiss 起飞时已冻结源 rect,动画中途不再跟读(防切页改道)。
                val source = dismissSourceRect
                    ?: currentSourceRect
                    ?: return null
                return if (isDismissing) {
                    val start = dismissImageDisplayRect ?: previewSurfaceRect
                    val normalizedProgress = (progress / dismissStartProgress.coerceAtLeast(0.001f))
                        .coerceIn(0f, 1f)
                    resolveImagePreviewDismissRectFrame(
                        transitionProgress = normalizedProgress,
                        sourceRect = source,
                        displayedImageRect = start
                    )?.rect
                } else {
                    resolveImagePreviewOpenRect(
                        transitionProgress = progress,
                        sourceRect = source,
                        previewSurfaceRect = previewSurfaceRect
                    )
                }
            }

            LaunchedEffect(Unit) {
                animateTrigger.snapTo(0f)
                animateTrigger.animateTo(
                    targetValue = 1f,
                    animationSpec = imagePreviewOpenTween()
                )
            }

            fun triggerDismiss(
                startRect: androidx.compose.ui.geometry.Rect? = null,
                backdropStartAlpha: Float = 1f,
                initialVelocityY: Float = 0f,
            ) {
                if (isDismissing) return
                if (activeZoomScale > 1.01f) {
                    // 放大态退出:先让 ZoomableImage 子层回弹到 fit(同时更新 activeZoomScale),
                    // 再从 fit rect 起飞。直接飞会让外层 morph 与子层放大叠加,
                    // 落点与画面内容错位、窗口移除时内容跳变。
                    zoomResetTrigger += 1
                    scope.launch {
                        withTimeoutOrNull(450L) {
                            androidx.compose.runtime.snapshotFlow { activeZoomScale }
                                .first { it <= 1.01f }
                        }
                        triggerDismiss(
                            startRect = null,
                            backdropStartAlpha = backdropStartAlpha,
                            initialVelocityY = 0f,
                        )
                    }
                    return
                }
                val startProgress = currentTransitionProgress().coerceIn(0f, 1f)
                dismissStartProgress = startProgress
                dismissImageDisplayRect = startRect
                    ?: currentFlightRect(startProgress)
                    ?: previewSurfaceRect
                dismissSourceRect = currentSourceRect
                dismissBackdropStartAlpha = backdropStartAlpha.coerceIn(0f, 1f)
                isVerticalDismissDragging = false
                backRecoverEpoch += 1
                backRecovering = false
                isDismissing = true
                scope.launch {
                    verticalDismissOffsetYPx = 0f
                    verticalDismissOffsetXPx = 0f
                    verticalDismissSnapAnim.snapTo(0f)
                    verticalDismissSnapAnimX.snapTo(0f)
                    val dismissMotion = imagePreviewDismissMotion()
                    // 临界阻尼 spring 回位：起步可携带手势松手速度，落地自带减速。
                    // 进度向 0 收敛，竖滑方向的速度在进度空间取反号以延续手势动量。
                    animateTrigger.animateTo(
                        targetValue = dismissMotion.settleTarget,
                        animationSpec = imagePreviewCloseSpring(),
                        initialVelocity = clampImagePreviewDismissVelocity(-initialVelocityY),
                    )
                    // Keep the final Hero frame in the Dialog for one display frame so
                    // the source list can become visible before this window is removed.
                    withFrameNanos { }
                    // 交接两步走：先恢复源缩略图（Hero 末帧仍覆盖落点），让网格先重绘，
                    // 再移除 Dialog 窗口，消除落位处两窗口重绘错帧的闪烁。
                    ImagePreviewOverlayController.revealSourceBeforeRemoval(requestToken)
                    withFrameNanos { }
                    onDismiss()
                }
            }

            LaunchedEffect(dismissRequestCount) {
                if (dismissRequestCount > 0) triggerDismiss()
            }

            NavigationBackHandler(
                state = backEventState,
                isBackEnabled = !isDismissing,
                onBackCancelled = {
                    if (!isDismissing) {
                        scope.launch {
                            if (isDismissing) return@launch
                            backRecoverEpoch += 1
                            val epoch = backRecoverEpoch
                            backRecovering = true
                            try {
                                val dismissMotion = imagePreviewDismissMotion()
                                animateTrigger.snapTo(lastScrubRawProgress)
                                animateTrigger.animateTo(
                                    targetValue = 1f,
                                    animationSpec = emphasizedEnterTween(
                                        durationMillis = dismissMotion.cancelRecoverDurationMillis
                                    ),
                                )
                            } finally {
                                // animateTo 被新的 dismiss / scrub 动画取消时(CancellationException)
                                // 也必须复位,否则 currentTransitionProgress 的读口残留
                                // backRecovering=true,后续 scrub 进度错乱、画面跳变。
                                // 纪元不匹配说明已被接管,状态由接管方负责。
                                if (backRecoverEpoch == epoch) {
                                    backRecovering = false
                                    lastScrubRawProgress = animateTrigger.value.coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                },
                onBackCompleted = {
                    scope.launch {
                        if (isDismissing) return@launch
                        backRecovering = false
                        animateTrigger.snapTo(lastScrubRawProgress)
                        triggerDismiss()
                    }
                },
            )

            // 新一轮预测返回 scrub 开始时立即交还进度读口:
            // 恢复动画若还在跑,backProgress 分支必须优先,否则恢复动画与手势双驱动跳变。
            // 纪元 +1 使被接管协程的 finally 复位全部作废。
            LaunchedEffect(backProgress) {
                if (backProgress > 0f && backRecovering) {
                    backRecoverEpoch += 1
                    backRecovering = false
                }
            }
            
            // 1. 背景层 (淡入淡出)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(IMAGE_PREVIEW_BACKDROP_TAG)
                    .drawBehind {
                        val progress = currentTransitionProgress().coerceIn(0f, 1f)
                        val dragFrame = resolveImagePreviewVerticalDragFrame(
                            dragOffsetYPx = verticalDismissOffsetYPx,
                            containerHeightPx = fullHeightPx
                        )
                        val alpha = if (isDismissing) {
                            resolveImagePreviewDismissBackdropAlpha(
                                visualProgress = progress,
                                startAlpha = dismissBackdropStartAlpha
                            )
                        } else {
                            progress * dragFrame.backdropAlphaMultiplier
                        }
                        drawRect(MediaContrastPalette.Scrim.copy(alpha = alpha))
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { triggerDismiss() }
                        )
                    }
            )
            
            // 2. 内容层 (缩放位移)
            // Keep the page measured at viewport size. The outer layer animates the clipping
            // rect, while the pager layer counters non-uniform rect scaling so image pixels
            // retain their aspect ratio throughout the Hero flight.
            val contentModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = currentTransitionProgress().coerceIn(0f, 1f)
                    val flightRect = currentFlightRect(progress)
                    alpha = resolveImagePreviewDismissContentAlpha(
                        hasRectFlight = flightRect != null,
                        isDismissing = isDismissing,
                        visualProgress = progress
                    )
                    val dragFrame = resolveImagePreviewVerticalDragFrame(
                        dragOffsetYPx = verticalDismissOffsetYPx,
                        containerHeightPx = fullHeightPx
                    )
                    val presentedCornerRadius = resolveImagePreviewPresentedCornerRadiusDp(
                        visualProgress = progress,
                        verticalDragProgress = if (isDismissing) 0f else dragFrame.progress,
                        hasSourceRect = shouldUseRectAnim,
                        sourceCornerRadiusDp = sourceCornerRadiusDp
                    )
                    if (flightRect != null) {
                        val baseScaleX = (flightRect.width / size.width).coerceAtLeast(0.01f)
                        val baseScaleY = (flightRect.height / size.height).coerceAtLeast(0.01f)
                        val dragScale = if (isDismissing) 1f else dragFrame.scale
                        scaleX = baseScaleX * dragScale
                        scaleY = baseScaleY * dragScale
                        // 竖滑拖拽期间双轴跟手（X/Y），dismiss 动画接管后由 flightRect 驱动
                        translationX = (flightRect.left + flightRect.right - size.width) / 2f +
                            if (isDismissing) 0f else verticalDismissOffsetXPx
                        translationY = (flightRect.top + flightRect.bottom - size.height) / 2f +
                            if (isDismissing) 0f else verticalDismissOffsetYPx
                        val cornerRadii = resolveImagePreviewCounterScaledCornerRadii(
                            cornerRadiusDp = presentedCornerRadius,
                            scaleX = baseScaleX * dragScale,
                            scaleY = baseScaleY * dragScale
                        )
                        shape = CounterScaledCornerShape(cornerRadii.horizontalDp, cornerRadii.verticalDp)
                        clip = true
                        transformOrigin = TransformOrigin.Center
                    } else {
                        val fallbackScale = resolveImagePreviewTransitionFrame(
                            rawProgress = progress,
                            hasSourceRect = false,
                            sourceCornerRadiusDp = sourceCornerRadiusDp
                        ).fallbackScale * if (isDismissing) 1f else dragFrame.scale
                        scaleX = fallbackScale
                        scaleY = fallbackScale
                        translationX = if (isDismissing) 0f else verticalDismissOffsetXPx
                        translationY = if (isDismissing) 0f else verticalDismissOffsetYPx
                        shape = RoundedCornerShape(presentedCornerRadius.dp)
                        clip = true
                        transformOrigin = TransformOrigin.Center
                    }
                }

            Box(
                 modifier = contentModifier
            ) {
                //  使用 HorizontalPager 实现滑动切换 + 3D立体动画
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val flightRect = currentFlightRect(currentTransitionProgress())
                            if (flightRect != null) {
                                val baseScaleX = (flightRect.width / size.width).coerceAtLeast(0.01f)
                                val baseScaleY = (flightRect.height / size.height).coerceAtLeast(0.01f)
                                val sourceFillScale = currentSourceRect
                                    ?.let { source ->
                                        currentImageDisplayRect
                                            ?.takeIf { it.width > 0f && it.height > 0f }
                                            ?.let { displayed ->
                                                maxOf(
                                                    source.width / displayed.width,
                                                    source.height / displayed.height
                                                )
                                            }
                                    }
                                    ?: maxOf(baseScaleX, baseScaleY)
                                val imageScale = sourceFillScale +
                                    (1f - sourceFillScale) * currentTransitionProgress().coerceIn(0f, 1f)
                                scaleX = imageScale / baseScaleX
                                scaleY = imageScale / baseScaleY
                                val imageRect = if (isDismissing) {
                                    currentImageDisplayRect
                                } else {
                                    flightAnchorDisplayRect ?: currentImageDisplayRect
                                }
                                if (imageRect != null) {
                                    val remainingFlight = 1f - currentTransitionProgress().coerceIn(0f, 1f)
                                    val viewportCenterX = (previewSurfaceRect.left + previewSurfaceRect.right) / 2f
                                    val viewportCenterY = (previewSurfaceRect.top + previewSurfaceRect.bottom) / 2f
                                    translationX = -scaleX * (imageRect.center.x - viewportCenterX) * remainingFlight
                                    translationY = -scaleY * (imageRect.center.y - viewportCenterY) * remainingFlight
                                } else {
                                    translationX = 0f
                                    translationY = 0f
                                }
                                transformOrigin = TransformOrigin.Center
                            } else {
                                scaleX = 1f
                                scaleY = 1f
                                translationX = 0f
                                translationY = 0f
                            }
                        },
                    beyondViewportPageCount = 1,  // 预加载相邻页面
                    userScrollEnabled = !isVerticalDismissDragging &&
                        !isDismissing &&
                        activeZoomScale <= 1.01f,
                    key = { images.getOrElse(it) { "" } }
                ) { page ->
                    // 所有图片默认平面横滑，可由同一个设置启用轻量 3D。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(IMAGE_PREVIEW_PAGE_TAG)
                            .graphicsLayer {
                                // 页面偏移（0 = 居中，-1 = 左边，1 = 右边）在这里读取而不是
                                // 组合期。currentPageOffsetFraction 横滑时每帧都变，
                                // 在组合期读取等于把每一帧都升级成一次重组；
                                // 放进 graphicsLayer lambda 后只触发重绘，不触发重组。
                                val pageOffset =
                                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                // 3D 强度随进度平滑进入，替代 >0.92 硬阈值开关的尾段跳变。
                                val gallery3dBlend = if (gallery3dPageEnabled) {
                                    resolveImagePreviewGallery3DBlend(currentTransitionProgress())
                                } else {
                                    0f
                                }
                                if (gallery3dBlend > 0f) {
                                    val transform = resolveImagePreviewGalleryPageTransform(
                                        pageOffsetFraction = pageOffset,
                                        containerWidthPx = fullWidthPx
                                    )
                                    rotationY = transform.rotationY * gallery3dBlend
                                    translationX = transform.translationXPx * gallery3dBlend
                                    cameraDistance = 16f * density.density
                                    transformOrigin = TransformOrigin(
                                        pivotFractionX = transform.pivotFractionX,
                                        pivotFractionY = 0.5f
                                    )
                                    scaleX = 1f + (transform.scale - 1f) * gallery3dBlend
                                    scaleY = 1f + (transform.scale - 1f) * gallery3dBlend
                                    alpha = 1f + (transform.alpha - 1f) * gallery3dBlend
                                } else {
                                    rotationY = 0f
                                    translationX = 0f
                                    scaleX = 1f
                                    scaleY = 1f
                                    alpha = 1f
                                    transformOrigin = TransformOrigin.Center
                                }
                            }
                            .pointerInput(Unit) {
                                // 阻止点击穿透到关闭手势
                                detectTapGestures { 
                                     if (!useCommentPreviewChrome) {
                                         // 点击图片也关闭
                                         triggerDismiss()
                                     }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val imageUrl = remember(images.getOrNull(page)) {
                            normalizeImageUrl(images.getOrNull(page) ?: "")
                        }
                        // 缩略图与预览图的 URL 不同（预览剥离 @尺寸后缀），内存缓存键对不上，
                        // 原图下载前内容层只剩黑底。把网格已加载的缩略图 URL 设为
                        // placeholderMemoryCacheKey，morph 期间立即垫图，杜绝「先黑后图」。
                        val placeholderCacheKey = remember(images.getOrNull(page)) {
                            resolveImagePreviewPlaceholderCacheKey(images.getOrNull(page).orEmpty())
                        }
                        val decodeSize = remember(page, imageUrl, page in originalQualityPages) {
                            resolveImageDecodeSize(
                                if (page in originalQualityPages) {
                                    ImageDecodeTarget.ORIGINAL_QUALITY
                                } else {
                                    ImageDecodeTarget.FULLSCREEN_PREVIEW
                                }
                            )
                        }
                        val previewRequest = remember(context, imageUrl, decodeSize, placeholderCacheKey) {
                            ImageRequest.Builder(context)
                                .data(imageUrl)
                                // 预览必须采样解码，避免超大原图超过 Canvas 单位图绘制上限。
                                .size(decodeSize.widthPx, decodeSize.heightPx)
                                .placeholderMemoryCacheKey(placeholderCacheKey)
                                .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://www.bilibili.com/").build())
                                // 进出场由画廊自身的 morph 控制，图片请求不能随退出状态重建。
                                .crossfade(false)
                                .build()
                        }

                        ZoomableImage(
                            model = previewRequest,
                            contentDescription = null,
                            imageLoader = gifImageLoader,  //  使用 GIF 加载器
                            modifier = Modifier.fillMaxSize(),
                            resetZoomTrigger = zoomResetTrigger,
                            onZoomChange = {
                                activeZoomScale = it
                            },
                            onDisplayRectChange = { rect ->
                                if (!isDismissing && page == pagerState.currentPage) {
                                    if (flightAnchorDisplayRect == null) {
                                        flightAnchorDisplayRect = rect
                                    }
                                    currentImageDisplayRect = rect
                                }
                            },
                            onVerticalDismissDragStart = {
                                if (page == pagerState.currentPage && !isDismissing) {
                                    isVerticalDismissDragging = true
                                    scope.launch {
                                        verticalDismissSnapAnim.stop()
                                        verticalDismissSnapAnimX.stop()
                                    }
                                }
                            },
                            onVerticalDismissDrag = { dragDelta ->
                                if (page == pagerState.currentPage && !isDismissing && isVerticalDismissDragging) {
                                    verticalDismissOffsetYPx += dragDelta.y
                                    verticalDismissOffsetXPx += dragDelta.x
                                }
                            },
                            onVerticalDismissDragEnd = { releaseVelocityY ->
                                if (page == pagerState.currentPage && !isDismissing && isVerticalDismissDragging) {
                                    isVerticalDismissDragging = false
                                    val dragFrame = resolveImagePreviewVerticalDragFrame(
                                        dragOffsetYPx = verticalDismissOffsetYPx,
                                        containerHeightPx = fullHeightPx
                                    )
                                    val draggedRect = resolveImagePreviewDraggedDisplayRect(
                                        displayedImageRect = currentFlightRect(
                                            currentTransitionProgress()
                                        ) ?: previewSurfaceRect,
                                        translationYPx = verticalDismissOffsetYPx,
                                        translationXPx = verticalDismissOffsetXPx,
                                        scale = dragFrame.scale
                                    )
                                    when (
                                        resolveImagePreviewVerticalDismissDecision(
                                            dragOffsetYPx = verticalDismissOffsetYPx,
                                            containerHeightPx = fullHeightPx
                                        )
                                    ) {
                                        ImagePreviewVerticalDismissDecision.DISMISS -> triggerDismiss(
                                            startRect = draggedRect,
                                            backdropStartAlpha = dragFrame.backdropAlphaMultiplier,
                                            initialVelocityY = releaseVelocityY,
                                        )
                                        ImagePreviewVerticalDismissDecision.SNAP_BACK -> {
                                            scope.launch {
                                                launch {
                                                    verticalDismissSnapAnim.snapTo(verticalDismissOffsetYPx)
                                                    verticalDismissSnapAnim.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = interactiveSnapSpring()
                                                    ) {
                                                        verticalDismissOffsetYPx = value
                                                    }
                                                }
                                                launch {
                                                    verticalDismissSnapAnimX.snapTo(verticalDismissOffsetXPx)
                                                    verticalDismissSnapAnimX.animateTo(
                                                        targetValue = 0f,
                                                        animationSpec = interactiveSnapSpring()
                                                    ) {
                                                        verticalDismissOffsetXPx = value
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onExtremeAspectRatioDetected = {
                                // 长条图在 4096 方形采样档下短边像素不足，放大后仍会发糊。
                                // 自动提升到现有原图解码档；极端长宽比下实际内存远低于方形上限。
                                originalQualityPages = originalQualityPages + page
                            },
                            onVerticalDismissDragCancel = {
                                if (page == pagerState.currentPage && !isDismissing) {
                                    isVerticalDismissDragging = false
                                    scope.launch {
                                        launch {
                                            verticalDismissSnapAnim.snapTo(verticalDismissOffsetYPx)
                                            verticalDismissSnapAnim.animateTo(
                                                targetValue = 0f,
                                                animationSpec = interactiveSnapSpring()
                                            ) {
                                                verticalDismissOffsetYPx = value
                                            }
                                        }
                                        launch {
                                            verticalDismissSnapAnimX.snapTo(verticalDismissOffsetXPx)
                                            verticalDismissSnapAnimX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = interactiveSnapSpring()
                                            ) {
                                                verticalDismissOffsetXPx = value
                                            }
                                        }
                                    }
                                }
                            },
                            onLongPress = {
                                if (
                                    page == pagerState.currentPage &&
                                    shouldHandleImagePreviewLongPressSave(
                                        longPressSaveEnabled = longPressSaveEnabled,
                                        imageUrl = imageUrl,
                                        isSaving = isSaving
                                    )
                                ) {
                                    haptic(resolveImagePreviewLongPressSaveStartFeedback())
                                    if (onImageLongPress != null) {
                                        onImageLongPress(imageUrl)
                                    } else if (!useCommentPreviewChrome) {
                                        showOrdinaryImageActions = true
                                    } else {
                                        requestSaveCurrentImage(imageUrl)
                                    }
                                }
                            },
                            onClick = {
                                if (!useCommentPreviewChrome) {
                                    // 点击图片关闭预览
                                    triggerDismiss()
                                }
                            }
                        )
                        val currentRawUrl = images.getOrNull(page).orEmpty()
                        val liveVideoUrl = resolveLivePhotoVideoUrl(
                            rawUrl = currentRawUrl,
                            pageIndex = page,
                            livePhotoVideos = livePhotoVideos
                        )
                        // 实况照片按进度平滑淡入；0.7 前不合成，避免飞行早期白白挂播放器。
                        // 门限用 derivedStateOf 供组合期判断，alpha 在 graphicsLayer 内逐帧读取，不触发重组。
                        val livePhotoProgressReady by remember {
                            derivedStateOf {
                                val progress = if (backProgress > 0f) 1f - backProgress else animateTrigger.value
                                progress >= 0.7f
                            }
                        }
                        if (
                            !liveVideoUrl.isNullOrBlank() &&
                            isLivePhotoEnabled &&
                            page == pagerState.currentPage &&
                            !isDismissing &&
                            livePhotoProgressReady && activeZoomScale <= 1.05f
                        ) {
                            LivePhotoPlayback(
                                videoUrl = liveVideoUrl,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = resolveImagePreviewLivePhotoAlpha(
                                            if (backProgress > 0f) 1f - backProgress else currentTransitionProgress()
                                        )
                                    },
                                isPlaying = isLivePhotoPlaying,
                                isMuted = isLivePhotoMuted,
                                playerRef = { livePhotoPlayer = it },
                                onClick = {
                                    if (showLivePhotoMenu) {
                                        showLivePhotoMenu = false
                                    } else if (!useCommentPreviewChrome) {
                                        triggerDismiss()
                                    }
                                },
                                onLongPress = {
                                    if (
                                        page == pagerState.currentPage &&
                                        shouldHandleImagePreviewLongPressSave(
                                            longPressSaveEnabled = longPressSaveEnabled,
                                            imageUrl = imageUrl,
                                            isSaving = isSaving
                                        )
                                    ) {
                                        haptic(resolveImagePreviewLongPressSaveStartFeedback())
                                        if (onImageLongPress != null) {
                                            onImageLongPress(imageUrl)
                                        } else if (!useCommentPreviewChrome) {
                                            showOrdinaryImageActions = true
                                        } else {
                                            requestSaveCurrentImage(imageUrl)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            // 3. UI 覆盖层 - 退出时先于图片清掉 chrome，只剩干净一镜 morph
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = resolveImagePreviewChromeAlpha(
                            visualProgress = currentTransitionProgress(),
                            isDismissing = isDismissing
                        )
                    }
            ) {
                val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
                val overlayPadding = resolveImagePreviewOverlayPadding(
                    safeInsetStart = safeDrawingPadding.calculateStartPadding(layoutDirection),
                    safeInsetTop = safeDrawingPadding.calculateTopPadding(),
                    safeInsetEnd = safeDrawingPadding.calculateEndPadding(layoutDirection),
                    safeInsetBottom = safeDrawingPadding.calculateBottomPadding()
                )
                val resolvedText = resolveImagePreviewText(
                    textContent = textContent,
                    currentPage = pagerState.currentPage,
                    totalPages = images.size
                )
                val textPlacement = textContent?.placement ?: ImagePreviewTextPlacement.OVERLAY_BOTTOM
                val shouldShowResolvedText = shouldShowImagePreviewText(
                    hasText = resolvedText != null,
                    textVisible = imagePreviewTextVisible
                ) && useCommentPreviewChrome

                if (!useCommentPreviewChrome &&
                    resolvedText != null &&
                    shouldShowResolvedText &&
                    textPlacement == ImagePreviewTextPlacement.OVERLAY_BOTTOM
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(
                                start = overlayPadding.start + AppSpacingTokens.Small,
                                end = overlayPadding.end + AppSpacingTokens.Small,
                                bottom = overlayPadding.bottom + AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.Large + AppSpacingTokens.Micro
                            )
                            .graphicsLayer {
                                // transform 在这里就地求值：它只依赖 currentPageOffsetFraction，
                                // 而那个值横滑时每帧都变，放在组合期会拖着整段文字浮层一起重组。
                                val textTransform = resolveImagePreviewTextTransform(
                                    pageOffsetFraction = pagerState.currentPageOffsetFraction
                                )
                                alpha = textTransform.alpha
                                rotationX = textTransform.rotationX
                                translationY = textTransform.translateYDp.dp.toPx()
                                cameraDistance = 10f * this.density
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .clickable {
                                imagePreviewTextVisible =
                                    resolveImagePreviewTextVisibilityAfterToggle(imagePreviewTextVisible)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .widthIn(max = AppSpacingTokens.TripleExtraLarge * 11 + AppSpacingTokens.DoubleExtraLarge)
                                .clip(AppShapes.container(ContainerLevel.Sheet))
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            MediaContrastPalette.Scrim.copy(alpha = 0.72f),
                                            MediaContrastPalette.Scrim.copy(alpha = 0.56f)
                                        )
                                    )
                                )
                                .padding(horizontal = AppSpacingTokens.Large, vertical = AppSpacingTokens.Medium + AppSpacingTokens.Micro / 2)
                        ) {
                            AnimatedContent(
                                targetState = pagerState.currentPage,
                                transitionSpec = {
                                    (fadeIn(animationSpec = emphasizedEnterTween(250)) + slideInVertically(
                                        animationSpec = emphasizedEnterTween(250)
                                    ) { it / 3 }) togetherWith
                                        (fadeOut(animationSpec = emphasizedExitTween(180)) + slideOutVertically(
                                            animationSpec = emphasizedExitTween(180)
                                        ) { -it / 4 })
                                },
                                label = "imagePreviewTextSwitch"
                            ) { page ->
                                val currentText = resolveImagePreviewText(
                                    textContent = textContent,
                                    currentPage = page,
                                    totalPages = images.size
                                ) ?: resolvedText
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro)
                                ) {
                                    if (currentText.headline.isNotBlank() || currentText.pageIndicator.isNotBlank()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small + AppSpacingTokens.Micro),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (currentText.headline.isNotBlank()) {
                                                AppText(
                                                    text = currentText.headline,
                                                    color = MediaContrastPalette.Foreground.copy(alpha = 0.9f),
                                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                            }
                                            if (currentText.pageIndicator.isNotBlank()) {
                                                AppText(
                                                    text = currentText.pageIndicator,
                                                    color = MediaContrastPalette.Foreground.copy(alpha = 0.64f),
                                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                                )
                                            }
                                        }
                                    }
                                    if (currentText.body.isNotBlank()) {
                                        AppText(
                                            text = currentText.body,
                                            color = MediaContrastPalette.Foreground.copy(alpha = 0.94f),
                                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                                            maxLines = 4,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // PiliPlus 普通画廊：底部轻渐变 + 紧凑数字页码，单图也显示 1/1。
                if (!useCommentPreviewChrome) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .testTag(IMAGE_PREVIEW_PAGE_INDICATOR_TAG)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MediaContrastPalette.Scrim.copy(alpha = 0.3f)
                                    )
                                )
                            )
                            .padding(
                                start = overlayPadding.start + AppSpacingTokens.Medium,
                                top = AppSpacingTokens.Small,
                                end = overlayPadding.end + AppSpacingTokens.Medium,
                                bottom = overlayPadding.bottom + AppSpacingTokens.Small
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AppText(
                            text = "${pagerState.currentPage + 1}/${images.size}",
                            color = Color.White,
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize
                        )
                    }
                }
                
                val chromeModifier = Modifier.graphicsLayer {
                    // 同上：横滑期间 currentPageOffsetFraction 每帧变化，
                    // 原先在组合期读取会让整个 chrome（顶栏 + 底栏 + 页码）每帧重组。
                    // graphicsLayer 的 lambda 本身就是 Density，不需要外部的 with(density)。
                    val chromeOffset = pagerState.currentPageOffsetFraction.coerceIn(-1f, 1f)
                    rotationZ = -chromeOffset * 2.8f
                    translationX = (-chromeOffset * 10f).dp.toPx()
                    transformOrigin = TransformOrigin.Center
                }

                // 顶部按钮栏（关闭 + 页码 + 下载）
                if (useCommentPreviewChrome && commentContext != null) {
                    val currentPage = pagerState.currentPage
                    val isOriginalQuality = currentPage in originalQualityPages
                    ImagePreviewCommentTopBar(
                        label = if (isOriginalQuality) {
                            "原图已加载"
                        } else {
                            commentContext.originalSizeLabels.getOrNull(currentPage)
                                ?: resolveCommentImageOriginalSizeLabel(null)
                        },
                        shareIcon = shareIcon,
                        isSharing = isSharing,
                        enabled = !isSharing && !isSaving,
                        onDismiss = { triggerDismiss() },
                        onShare = { requestShareCurrentImage(currentImageUrl) },
                        onViewOriginal = {
                            // 按 API 文档：去掉 @ 尺寸参数即为原图 URL（预览已用该 URL），
                            // 此处切换为全分辨率解码重新加载，突破预览采样限制。
                            originalQualityPages = originalQualityPages + currentPage
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(
                                start = overlayPadding.start,
                                top = overlayPadding.top,
                                end = overlayPadding.end
                            )
                            .then(chromeModifier)
                    )
                } else if (useCommentPreviewChrome && textContent != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(
                            start = overlayPadding.start,
                            top = overlayPadding.top,
                            end = overlayPadding.end
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 关闭按钮
                    AppFilledIconButton(
                        onClick = { triggerDismiss() },
                        colors = AppIconButtonDefaults.colors(
                            containerColor = MediaContrastPalette.Scrim.copy(0.5f)
                        )
                    ) {
                        AppIcon(
                            imageVector = rememberAppClearIcon(),
                            contentDescription = "关闭",
                            tint = MediaContrastPalette.Foreground
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = AppSpacingTokens.Medium),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            resolvedText != null && shouldShowResolvedText && textPlacement == ImagePreviewTextPlacement.TOP_BAR -> {
                                Box(
                                    modifier = Modifier.graphicsLayer {
                                        val textTransform = resolveImagePreviewTextTransform(
                                            pageOffsetFraction = pagerState.currentPageOffsetFraction
                                        )
                                        alpha = textTransform.alpha
                                        translationY = (textTransform.translateYDp * 0.45f).dp.toPx()
                                    }
                                ) {
                                    AnimatedContent(
                                        targetState = pagerState.currentPage,
                                        transitionSpec = {
                                            val isForward = targetState > initialState
                                            (fadeIn(animationSpec = emphasizedEnterTween(220)) +
                                                slideInHorizontally { fullWidth ->
                                                    if (isForward) fullWidth / 3 else -fullWidth / 3
                                                }) togetherWith
                                                (fadeOut(animationSpec = emphasizedExitTween(160)) +
                                                    slideOutHorizontally { fullWidth ->
                                                        if (isForward) -fullWidth / 4 else fullWidth / 4
                                                    })
                                        },
                                        label = "imagePreviewTopBarTextSwitch"
                                    ) { page ->
                                        val pageText = resolveImagePreviewText(
                                            textContent = textContent,
                                            currentPage = page,
                                            totalPages = images.size
                                        ) ?: resolvedText
                                        val primaryText = pageText.body.ifBlank { pageText.headline }
                                        val secondaryText = if (
                                            pageText.body.isNotBlank() &&
                                            pageText.headline.isNotBlank()
                                        ) {
                                            pageText.headline
                                        } else {
                                            ""
                                        }
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Micro)
                                        ) {
                                            if (secondaryText.isNotBlank()) {
                                                AppText(
                                                    text = secondaryText,
                                                    color = MediaContrastPalette.Foreground.copy(alpha = 0.82f),
                                                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                            if (primaryText.isNotBlank()) {
                                                AppText(
                                                    text = primaryText,
                                                    color = MediaContrastPalette.Foreground,
                                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                                    maxLines = 2,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .background(MediaContrastPalette.Scrim.copy(0.5f), AppShapes.container(ContainerLevel.Card))
                                                        .padding(horizontal = AppSpacingTokens.Medium, vertical = AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro)
                                                )
                                            }
                                            if (images.size > 1) {
                                                AppText(
                                                    text = "${page + 1} / ${images.size}",
                                                    color = MediaContrastPalette.Foreground.copy(alpha = 0.8f),
                                                    fontSize = MaterialTheme.typography.labelSmall.fontSize
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            images.size > 1 -> {
                                AppText(
                                    "${pagerState.currentPage + 1} / ${images.size}",
                                    color = MediaContrastPalette.Foreground,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                    modifier = Modifier
                                        .background(MediaContrastPalette.Scrim.copy(0.5f), AppShapes.container(ContainerLevel.Card))
                                        .padding(horizontal = AppSpacingTokens.Medium, vertical = AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro)
                                )
                            }
                        }
                    }

                    if (resolvedText != null) {
                        AppFilledIconButton(
                            onClick = {
                                imagePreviewTextVisible =
                                    resolveImagePreviewTextVisibilityAfterToggle(imagePreviewTextVisible)
                            },
                            colors = AppIconButtonDefaults.colors(
                                containerColor = MediaContrastPalette.Scrim.copy(0.5f)
                            )
                        ) {
                            AppIcon(
                                imageVector = if (imagePreviewTextVisible) {
                                    rememberAppVisibilityOffIcon()
                                } else {
                                    rememberAppVisibilityOnIcon()
                                },
                                contentDescription = if (imagePreviewTextVisible) "隐藏图片文字" else "显示图片文字",
                                tint = MediaContrastPalette.Foreground
                            )
                        }
                        Spacer(modifier = Modifier.width(AppSpacingTokens.Small))
                    }
                    
                    // 分享按钮
                    AppFilledIconButton(
                        onClick = {
                            requestShareCurrentImage(currentImageUrl)
                        },
                        enabled = !isSharing && !isSaving,
                        colors = AppIconButtonDefaults.colors(
                            containerColor = MediaContrastPalette.Scrim.copy(0.5f)
                        )
                    ) {
                        if (isSharing) {
                            AdaptiveLoadingIndicator(
                                size = AppSpacingTokens.ExtraLarge,
                                color = MediaContrastPalette.Foreground,
                                strokeWidth = AppSpacingTokens.Micro
                            )
                        } else {
                            AppIcon(
                                imageVector = shareIcon,
                                contentDescription = "分享图片",
                                tint = MediaContrastPalette.Foreground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(AppSpacingTokens.Small))

                    //  下载按钮
                    AppFilledIconButton(
                        onClick = {
                            requestSaveCurrentImage(currentImageUrl)
                        },
                        enabled = !isSaving && !isSharing,
                        colors = AppIconButtonDefaults.colors(
                            containerColor = MediaContrastPalette.Scrim.copy(0.5f)
                        )
                    ) {
                        if (isSaving) {
                            AdaptiveLoadingIndicator(
                                size = AppSpacingTokens.ExtraLarge,
                                color = MediaContrastPalette.Foreground,
                                strokeWidth = AppSpacingTokens.Micro
                            )
                        } else {
                            AppIcon(
                                imageVector = rememberAppDownloadIcon(),
                                contentDescription = "保存图片",
                                tint = MediaContrastPalette.Foreground
                            )
                        }
                    }
                }
                }

                if (useCommentPreviewChrome && commentContext != null) {
                    ImagePreviewCommentPanel(
                        context = commentContext,
                        likeIcon = likeIcon,
                        likeFilledIcon = likeFilledIcon,
                        shareIcon = shareIcon,
                        isSharing = isSharing,
                        enabled = !isSharing && !isSaving,
                        onShare = { requestShareCurrentImage(currentImageUrl) },
                        onReply = {
                            commentContext.onReplyClick?.invoke()
                            triggerDismiss()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(
                                start = overlayPadding.start,
                                end = overlayPadding.end,
                                bottom = overlayPadding.bottom + AppSpacingTokens.Medium
                            )
                            .then(chromeModifier)
                    )
                }

                // 若展开了实况菜单，点击背景空白区域收起菜单
                if (showLivePhotoMenu) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { showLivePhotoMenu = false })
                            }
                    )
                }

                // 左上角实况照片控制胶囊与下拉菜单（对齐系统实况相册交互）
                if (!currentLiveVideoUrl.isNullOrBlank() && !useCommentPreviewChrome) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                start = maxOf(12.dp, safeDrawingPadding.calculateStartPadding(layoutDirection) + 4.dp),
                                top = overlayPadding.top
                            )
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MediaContrastPalette.Scrim.copy(alpha = 0.65f))
                                    .clickable { showLivePhotoMenu = !showLivePhotoMenu }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLivePhotoEnabled) {
                                    LivePhotoIcon(tint = Color.White)
                                } else {
                                    LivePhotoOffIcon(tint = Color.White.copy(alpha = 0.8f))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                AppText(
                                    text = if (isLivePhotoEnabled) "实况" else "实况已关",
                                    color = Color.White,
                                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                AppIcon(
                                    imageVector = if (showLivePhotoMenu) rememberAppChevronUpIcon() else rememberAppChevronDownIcon(),
                                    contentDescription = "实况菜单",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            if (showLivePhotoMenu) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MediaContrastPalette.Scrim.copy(alpha = 0.88f))
                                        .padding(vertical = 4.dp)
                                        .width(IntrinsicSize.Max)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    isLivePhotoEnabled = !isLivePhotoEnabled
                                                    if (isLivePhotoEnabled) {
                                                        isLivePhotoPlaying = true
                                                    }
                                                    showLivePhotoMenu = false
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isLivePhotoEnabled) {
                                                LivePhotoOffIcon(tint = Color.White)
                                            } else {
                                                LivePhotoIcon(tint = Color.White)
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            AppText(
                                                text = if (isLivePhotoEnabled) "关闭实况" else "开启实况",
                                                color = Color.White,
                                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(0.5.dp)
                                                .background(Color.White.copy(alpha = 0.15f))
                                        )

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    isLivePhotoEnabled = true
                                                    isLivePhotoPlaying = true
                                                    livePhotoPlayer?.seekTo(0)
                                                    livePhotoPlayer?.play()
                                                    showLivePhotoMenu = false
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AppIcon(
                                                imageVector = rememberAppRefreshIcon(),
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            AppText(
                                                text = "重新播放",
                                                color = Color.White,
                                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 右下角声音切换按钮（支持有声实况播放与静音切换）
                if (!currentLiveVideoUrl.isNullOrBlank() && isLivePhotoEnabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = maxOf(12.dp, safeDrawingPadding.calculateEndPadding(layoutDirection) + 4.dp),
                                bottom = overlayPadding.bottom
                            )
                            .clip(CircleShape)
                            .background(MediaContrastPalette.Scrim.copy(alpha = 0.65f))
                            .clickable { isLivePhotoMuted = !isLivePhotoMuted }
                            .padding(8.dp)
                    ) {
                        AppIcon(
                            imageVector = if (isLivePhotoMuted) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = if (isLivePhotoMuted) "开启声音" else "静音",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
    }

    if (showOrdinaryImageActions) {
        AppAlertDialog(
            onDismissRequest = { showOrdinaryImageActions = false },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ImagePreviewActionButton(
                        label = "分享",
                        onClick = {
                            showOrdinaryImageActions = false
                            requestShareCurrentImage(currentImageUrl)
                        }
                    )
                    ImagePreviewActionButton(
                        label = "复制链接",
                        onClick = {
                            showOrdinaryImageActions = false
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("图片链接", currentImageUrl))
                        }
                    )
                    ImagePreviewActionButton(
                        label = "保存图片",
                        onClick = {
                            showOrdinaryImageActions = false
                            requestSaveCurrentImage(currentImageUrl)
                        }
                    )
                    if (!currentLiveVideoUrl.isNullOrBlank()) {
                        ImagePreviewActionButton(
                            label = "保存实况照片 (Motion Photo)",
                            onClick = {
                                showOrdinaryImageActions = false
                                requestSaveMotionPhoto(currentImageUrl, currentLiveVideoUrl)
                            }
                        )
                        ImagePreviewActionButton(
                            label = "保存实况视频 (MP4)",
                            onClick = {
                                showOrdinaryImageActions = false
                                requestSaveLivePhotoVideo(currentLiveVideoUrl)
                            }
                        )
                    }
                    if (images.size > 1) {
                        ImagePreviewActionButton(
                            label = "保存全部图片",
                            onClick = {
                                showOrdinaryImageActions = false
                                requestSaveAllImages()
                            }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ImagePreviewActionButton(
    label: String,
    onClick: () -> Unit
) {
    AppTextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppChromeSizeTokens.MinimumTouchTarget),
        contentPadding = PaddingValues(horizontal = AppSpacingTokens.Medium),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        AppText(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ImagePreviewCommentTopBar(
    label: String,
    shareIcon: ImageVector,
    isSharing: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onViewOriginal: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconButton(
            onClick = onDismiss
        ) {
            AppIcon(
                imageVector = rememberAppClearIcon(),
                contentDescription = "关闭",
                tint = MediaContrastPalette.Foreground,
                modifier = Modifier.size(AppSpacingTokens.ExtraLarge)
            )
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AppText(
                text = label,
                color = MediaContrastPalette.Foreground.copy(alpha = if (onViewOriginal != null) 0.9f else 0.38f),
                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag(IMAGE_PREVIEW_ORIGINAL_CHIP_TAG)
                    .clip(AppShapes.container(ContainerLevel.Floating))
                    .background(MediaContrastPalette.Foreground.copy(alpha = 0.16f))
                    .then(
                        if (onViewOriginal != null) {
                            Modifier.clickable(
                                enabled = enabled,
                                onClick = onViewOriginal
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = AppSpacingTokens.Large + AppSpacingTokens.Micro, vertical = AppSpacingTokens.Small - AppSpacingTokens.Micro / 2)
            )
        }
        AppIconButton(
            onClick = onShare,
            enabled = enabled
        ) {
            if (isSharing) {
                AdaptiveLoadingIndicator(
                    size = AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro,
                    color = MediaContrastPalette.Foreground,
                    strokeWidth = AppSpacingTokens.Micro
                )
            } else {
                AppIcon(
                    imageVector = shareIcon,
                    contentDescription = "分享图片",
                    tint = MediaContrastPalette.Foreground,
                    modifier = Modifier.size(AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro / 2)
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewCommentPanel(
    context: ImagePreviewCommentContext,
    likeIcon: ImageVector,
    likeFilledIcon: ImageVector,
    shareIcon: ImageVector,
    isSharing: Boolean,
    enabled: Boolean,
    onShare: () -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    var localLiked by remember(context.replyId, context.liked) { mutableStateOf(context.liked) }
    var localLikeCount by remember(context.replyId, context.likeCount) { mutableIntStateOf(context.likeCount) }
    val displayLikeCount = remember(localLikeCount) {
        FormatUtils.formatStat(localLikeCount.coerceAtLeast(0).toLong())
    }

    Column(
        modifier = modifier.testTag(IMAGE_PREVIEW_COMMENT_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small + AppSpacingTokens.Micro)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = context.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AppSpacingTokens.DoubleExtraLarge + AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro)
                    .clip(CircleShape)
                    .background(MediaContrastPalette.Foreground.copy(alpha = 0.16f))
            )
            Spacer(modifier = Modifier.width(AppSpacingTokens.Small + AppSpacingTokens.Micro))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = context.authorName,
                    color = MediaContrastPalette.Foreground,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (context.timeText.isNotBlank()) {
                    AppText(
                        text = context.timeText,
                        color = MediaContrastPalette.Foreground.copy(alpha = 0.58f),
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1
                    )
                }
            }
        }

        if (context.body.isNotBlank()) {
            AppText(
                text = context.body,
                color = MediaContrastPalette.Foreground.copy(alpha = 0.94f),
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(AppSpacingTokens.DoubleExtraLarge + AppSpacingTokens.ExtraSmall + AppSpacingTokens.Micro)
                    .clip(AppShapes.container(ContainerLevel.Floating))
                    .background(MediaContrastPalette.Foreground.copy(alpha = 0.12f))
                    .clickable(enabled = context.onReplyClick != null, onClick = onReply)
                    .padding(horizontal = AppSpacingTokens.Medium + AppSpacingTokens.Micro),
                contentAlignment = Alignment.CenterStart
            ) {
                AppText(
                    text = "回复 ${context.authorName}",
                    color = MediaContrastPalette.Foreground.copy(alpha = 0.56f),
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(AppSpacingTokens.Large))
            ImagePreviewCommentActionButton(
                icon = if (localLiked) likeFilledIcon else likeIcon,
                label = displayLikeCount,
                selected = localLiked,
                enabled = context.onLikeClick != null,
                onClick = {
                    context.onLikeClick?.invoke()
                    if (!localLiked) {
                        localLiked = true
                        localLikeCount += 1
                    } else {
                        localLiked = false
                        localLikeCount = (localLikeCount - 1).coerceAtLeast(0)
                    }
                }
            )
            Spacer(modifier = Modifier.width(AppSpacingTokens.Medium + AppSpacingTokens.Micro))
            ImagePreviewCommentActionButton(
                icon = shareIcon,
                label = "转发",
                selected = false,
                enabled = enabled,
                onClick = onShare,
                busy = isSharing
            )
        }
    }
}

@Composable
private fun ImagePreviewCommentActionButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false
) {
    Column(
        modifier = Modifier
            .size(width = AppSpacingTokens.TripleExtraLarge - AppSpacingTokens.Micro, height = AppSpacingTokens.TripleExtraLarge)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (busy) {
            AdaptiveLoadingIndicator(
                size = AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro,
                color = MediaContrastPalette.Foreground,
                strokeWidth = AppSpacingTokens.Micro
            )
        } else {
            AppIcon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.primary else MediaContrastPalette.Foreground,
                modifier = Modifier.size(AppSpacingTokens.ExtraLarge)
            )
        }
        AppText(
            text = label,
            color = MediaContrastPalette.Foreground.copy(alpha = if (enabled) 0.88f else 0.38f),
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}


@Composable
private fun LivePhotoIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(16.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f - 1.5f
        val innerRadius = outerRadius * 0.46f
        drawCircle(
            color = tint,
            radius = outerRadius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f)
        )
        drawCircle(
            color = tint,
            radius = innerRadius,
            center = center
        )
    }
}
@Composable
private fun LivePhotoOffIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(16.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension / 2f - 1.5f
        val innerRadius = outerRadius * 0.46f
        drawCircle(
            color = tint,
            radius = outerRadius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f)
        )
        drawCircle(
            color = tint,
            radius = innerRadius,
            center = center
        )
        drawLine(
            color = tint,
            start = Offset(2f, size.height - 2f),
            end = Offset(size.width - 2f, 2f),
            strokeWidth = 1.8f
        )
    }
}
internal fun resolveLivePhotoVideoUrl(
    rawUrl: String,
    pageIndex: Int,
    livePhotoVideos: Map<String, String>
): String? {
    if (livePhotoVideos.isEmpty()) return null
    if (rawUrl.isNotBlank()) {
        // 1. 直接命中
        livePhotoVideos[rawUrl]?.let { return it }
        // 2. 归一化图片 URL 命中
        val normalized = normalizeImageUrl(rawUrl)
        livePhotoVideos[normalized]?.let { return it }
        // 3. 归一化实况视频 URL 命中
        normalizeLivePhotoVideoUrl(rawUrl)?.let { livePhotoVideos[it] }?.let { return it }
        // 4. 去除协议头匹配路径（兼容 http:// 与 https:// 混用场景）
        val stripped = rawUrl.removePrefix("https:").removePrefix("http:").substringBefore("@").substringBefore("?")
        for ((key, value) in livePhotoVideos) {
            val keyStripped = key.removePrefix("https:").removePrefix("http:").substringBefore("@").substringBefore("?")
            if (keyStripped.isNotEmpty() && (keyStripped == stripped || stripped.endsWith(keyStripped) || keyStripped.endsWith(stripped))) {
                return value
            }
        }
    }
    return null
}
