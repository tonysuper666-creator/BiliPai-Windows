@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.android.purebilibili.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppFilterChip
import com.android.purebilibili.core.ui.components.AppSlider
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.ui.wallpaper.WallpaperTransform
import com.android.purebilibili.core.ui.wallpaper.isVideoWallpaper
import com.android.purebilibili.core.ui.wallpaper.probeDesktopWallpaperUri
import com.android.purebilibili.core.ui.wallpaper.sanitizeWallpaperTransform
import com.bilipai.desktop.ui.DesktopWallpaperCropImage
import com.bilipai.desktop.ui.DesktopWallpaperDevice
import com.bilipai.desktop.ui.desktopWallpaperDeviceForWidth
import com.bilipai.desktop.ui.desktopWallpaperDrag
import com.bilipai.desktop.ui.desktopWallpaperPreviewSpace
import com.bilipai.desktop.ui.desktopWallpaperWheel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the header probe says about the file behind the wallpaper URI. */
private sealed interface WallpaperSourceState {
    object Loading : WallpaperSourceState
    class Ready(val width: Int, val height: Int) : WallpaperSourceState
    class Failed(val message: String, val retryable: Boolean) : WallpaperSourceState
}

/**
 * Windows binding of the v0.4.6 home wallpaper position and zoom sheet. The public parameter list is the Android one,
 * unchanged, so a caller written against the original compiles as it is (a test compares both lists).
 *
 * Draft-only editing, as in the original: this sheet never writes settings and never replaces the current wallpaper;
 * [onSave] hands back both drafts and the owner persists them.
 *
 * What differs from the Android sheet:
 *  - a pointer drag pans and the mouse wheel zooms (Ctrl+wheel stays the application display scale and is ignored here);
 *  - the two crops are the narrow and the wide window crop, split at 600 dp like the phone and tablet crops, and the
 *    preview takes the shape of the real window when the tab is the window's own class;
 *  - the image size comes from the file header and the preview is the shared crop rectangle drawn with Coil, so there
 *    is no video preview: a video background is out of scope on Windows and is reported instead of cropped;
 *  - the readability preview and the backdrop blur of the Android preview need the request based palette store and the
 *    v0.4.6 backdrop, which this tree does not have yet; [effectMode] still drives the "effect is off" hint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeWallpaperAdjustmentSheet(
    uri: String,
    initialMobileTransform: WallpaperTransform = WallpaperTransform(),
    initialTabletTransform: WallpaperTransform = WallpaperTransform(),
    effectMode: HomeWallpaperEffectMode = HomeWallpaperEffectMode.ORIGINAL,
    saving: Boolean = false,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onSave: (WallpaperTransform, WallpaperTransform) -> Unit,
) {
    // Read here, in the caller's window, not inside the sheet content.
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val windowWidthDp = windowSize.width / density
    val windowHeightDp = windowSize.height / density
    val currentDevice = desktopWallpaperDeviceForWidth(windowWidthDp)
    var mobile by remember(uri) { mutableStateOf(sanitizeWallpaperTransform(initialMobileTransform)) }
    var tablet by remember(uri) { mutableStateOf(sanitizeWallpaperTransform(initialTabletTransform)) }
    var device by remember(uri) { mutableStateOf(currentDevice) }
    var retry by remember(uri) { mutableIntStateOf(0) }
    var imageFailed by remember(uri, retry) { mutableStateOf(false) }
    val sourceState by produceState<WallpaperSourceState>(WallpaperSourceState.Loading, uri, retry) {
        value = WallpaperSourceState.Loading
        value = withContext<WallpaperSourceState>(Dispatchers.IO) {
            if (isVideoWallpaper(uri)) {
                WallpaperSourceState.Failed("视频壁纸暂不支持调整位置", false)
            } else {
                probeDesktopWallpaperUri(uri)?.let { WallpaperSourceState.Ready(it.width, it.height) }
                    ?: WallpaperSourceState.Failed("壁纸加载失败", true)
            }
        }
    }
    val ready = sourceState as? WallpaperSourceState.Ready
    val canEdit = !saving && ready != null && !imageFailed
    val transform = if (device == DesktopWallpaperDevice.MOBILE) mobile else tablet
    val update: (WallpaperTransform) -> Unit = { next ->
        val safe = sanitizeWallpaperTransform(next)
        if (device == DesktopWallpaperDevice.MOBILE) mobile = safe else tablet = safe
    }
    val latestTransform by rememberUpdatedState(transform)
    val latestUpdate by rememberUpdatedState(update)
    val latestReady by rememberUpdatedState(ready)
    val latestCanEdit by rememberUpdatedState(canEdit)
    var viewport by remember(uri, device) { mutableStateOf(IntSize.Zero) }
    val latestViewport by rememberUpdatedState(viewport)
    val space = desktopWallpaperPreviewSpace(device, windowWidthDp, windowHeightDp)
    val baseColor = MaterialTheme.colorScheme.background

    AppModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppTextButton(onClick = onDismiss, enabled = !saving) { AppText("取消") }
                AppText("调整壁纸", style = MaterialTheme.typography.titleMedium)
                AppTextButton(
                    onClick = { onSave(mobile, tablet) },
                    enabled = !saving && ready != null && !imageFailed,
                ) {
                    if (saving) AppCircularProgressIndicator(Modifier.size(18.dp)) else AppText("保存")
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                DesktopWallpaperDevice.entries.forEach { option ->
                    AppFilterChip(
                        selected = device == option,
                        onClick = { if (!saving && device != option) device = option },
                        label = { AppText(if (option == DesktopWallpaperDevice.MOBILE) "窄窗口" else "宽窗口") },
                        enabled = !saving,
                    )
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(300.dp).padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val previewHeight = minOf(maxHeight, maxWidth / space.aspectRatio)
                val previewWidth = previewHeight * space.aspectRatio
                Box(
                    Modifier
                        .size(previewWidth, previewHeight)
                        .clip(RoundedCornerShape(16.dp))
                        .onSizeChanged { viewport = it }
                        .pointerInput(uri, device) {
                            detectDragGestures { change, dragAmount ->
                                val source = latestReady
                                if (latestCanEdit && source != null) {
                                    change.consume()
                                    val size = latestViewport
                                    latestUpdate(desktopWallpaperDrag(
                                        latestTransform, dragAmount.x, dragAmount.y,
                                        size.width.toFloat(), size.height.toFloat(),
                                        source.width.toFloat(), source.height.toFloat(),
                                    ))
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val source = latestReady
                            if (latestCanEdit && source != null && !event.keyboardModifiers.isCtrlPressed) {
                                val delta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                                event.changes.forEach { it.consume() }
                                val size = latestViewport
                                latestUpdate(desktopWallpaperWheel(
                                    latestTransform, delta,
                                    size.width.toFloat(), size.height.toFloat(),
                                    source.width.toFloat(), source.height.toFloat(),
                                ))
                            }
                        },
                ) {
                    when (val state = sourceState) {
                        WallpaperSourceState.Loading -> AppCircularProgressIndicator(Modifier.align(Alignment.Center))
                        is WallpaperSourceState.Failed ->
                            WallpaperSheetMessage(state.message, state.retryable, baseColor) { retry++ }
                        is WallpaperSourceState.Ready -> {
                            key(uri, retry) {
                                DesktopWallpaperCropImage(
                                    uri = uri,
                                    sourceWidth = state.width,
                                    sourceHeight = state.height,
                                    transform = transform,
                                    modifier = Modifier.fillMaxSize(),
                                    onError = { imageFailed = true },
                                )
                            }
                            if (imageFailed) {
                                WallpaperSheetMessage("壁纸加载失败", true, baseColor) { retry++ }
                            }
                        }
                    }
                }
            }
            if (!saveError.isNullOrBlank()) {
                AppText(
                    saveError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            WallpaperZoomControls(transform = transform, enabled = canEdit, onChange = update)
            if (effectMode == HomeWallpaperEffectMode.OFF) {
                AppText(
                    "当前壁纸效果已关闭，保存位置后可在壁纸效果中开启。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            AppText(
                "滚轮缩放、按住拖动画面；窄窗口（不足 600 dp）和宽窗口分别保存。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.WallpaperSheetMessage(
    message: String,
    retryable: Boolean,
    background: Color,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.align(Alignment.Center).background(background).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(message)
        if (retryable) AppTextButton(onClick = onRetry) { AppText("重试") }
    }
}

@Composable
private fun WallpaperZoomControls(
    transform: WallpaperTransform,
    enabled: Boolean,
    onChange: (WallpaperTransform) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText("缩放 ${(transform.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            AppTextButton(onClick = { onChange(WallpaperTransform()) }, enabled = enabled) { AppText("重置位置") }
        }
        AppSlider(
            value = transform.scale,
            onValueChange = { onChange(transform.copy(scale = it)) },
            valueRange = 1f..3f,
            enabled = enabled,
        )
    }
}
