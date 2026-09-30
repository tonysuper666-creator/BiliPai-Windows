package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.skin.*
import com.android.purebilibili.feature.video.ui.overlay.resolveVideoProgressBarLayoutPolicy
import kotlinx.coroutines.delay

/** Original LOADING_INDICATOR selection, only composed while the caller really is loading. */
@Composable
fun DesktopLoadingIndicator(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val path = LocalUiSkinState.current.assetPath(UiSkinSurface.LOADING_INDICATOR) { it.loadingAnimation ?: it.loadingFrame }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (path == null || failure != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else DesktopUiSkinAsset(path, Modifier.size(size), loop = true, onError = { failure = it })
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

/** Native upstream LIKE_EFFECT uses one iteration and dismisses the visible burst after 800 ms. */
@Composable
internal fun DesktopSkinLikeEffect(visible: Boolean, onFinished: () -> Unit, reducedMotion: Boolean = false) {
    if (!visible) return
    val path = LocalUiSkinState.current.assetPath(UiSkinSurface.LIKE_EFFECT) { it.likeEffectAnimation ?: it.likeEffectPreview }
    val callback by rememberUpdatedState(onFinished)
    var failure by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, reducedMotion) { delay(if (path == null) 0 else if (reducedMotion) 300 else 800); callback() }
    if (path == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (failure == null) DesktopUiSkinAsset(path, Modifier.size(if (reducedMotion) 72.dp else 88.dp), loop = false, onError = { failure = it })
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}

/** Native cache position is used only when reported; dragging uses the original thumb selection/order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopSkinPlayerProgress(value: Float, duration: Float, bufferedFraction: Float?, dragging: Boolean,
    enabled: Boolean, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, onError: (String) -> Unit) {
    val state = LocalUiSkinState.current
    val skin = state.activeSkin?.takeIf { state.enabled && UiSkinSurface.PLAYER_PROGRESS in it.manifest.surfaces }
    if (skin == null) {
        Slider(value, onValueChange, onValueChangeFinished = onValueChangeFinished, valueRange = 0f..duration,
            enabled = enabled, modifier = Modifier.fillMaxWidth())
        return
    }
    val active = parseUiSkinColor(skin.manifest.colors.playerProgressActiveTint, MaterialTheme.colorScheme.primary)
    val buffered = parseUiSkinColor(skin.manifest.colors.playerProgressBufferedTint, MaterialTheme.colorScheme.onSurface.copy(alpha = .42f))
    val inactive = parseUiSkinColor(skin.manifest.colors.playerProgressTrackTint, MaterialTheme.colorScheme.onSurface.copy(alpha = .24f))
    val path = state.assetPath(UiSkinSurface.PLAYER_PROGRESS) { assets ->
        if (dragging) assets.playerProgressDraggingIcon ?: assets.playerProgressIcon ?: assets.playerProgressStaticIcon
        else assets.playerProgressIcon ?: assets.playerProgressStaticIcon
    }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(thumbColor = active, activeTrackColor = active, inactiveTrackColor = inactive)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = resolveVideoProgressBarLayoutPolicy(maxWidth.value.toInt())
        val thumbSize = (if (dragging) layout.thumbDraggingSizeDp else layout.thumbIdleSizeDp).dp
        Slider(value, onValueChange, onValueChangeFinished = onValueChangeFinished, valueRange = 0f..duration,
            enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = colors, interactionSource = interaction,
            thumb = {
                if (path != null && failure == null) DesktopUiSkinAsset(path, Modifier.size(thumbSize), loop = dragging,
                    onError = { failure = it; onError(it) })
                else SliderDefaults.Thumb(interaction, colors = colors, enabled = enabled)
            }, track = {
                Canvas(Modifier.fillMaxWidth().height(layout.trackHeightDp.dp)) {
                    drawRect(inactive)
                    bufferedFraction?.takeIf { it.isFinite() }?.let { fraction ->
                        drawRect(buffered, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
                    }
                    drawRect(active, size = Size(size.width * (value / duration).coerceIn(0f, 1f), size.height))
                }
            })
    }
}

@Composable
internal fun DesktopSkinDynamicPublishIcon(selected: Boolean) {
    val path = LocalUiSkinState.current.assetPath(UiSkinSurface.DYNAMIC_PUBLISH) {
        if (selected) it.dynamicPublishSelectedIcon ?: it.dynamicPublishIcon else it.dynamicPublishIcon
    } ?: return
    var failure by remember(path) { mutableStateOf<String?>(null) }
    if (failure == null) {
        DesktopUiSkinAsset(path, Modifier.size(24.dp), loop = selected, onError = { failure = it })
        Spacer(Modifier.width(6.dp))
    } else Text("!", color = MaterialTheme.colorScheme.error)
}

/** Original space decoration is personal: another uploader's page keeps its own presentation. */
@Composable
internal fun DesktopSkinSpaceBackground(isOwner: Boolean, modifier: Modifier = Modifier) {
    val state = LocalUiSkinState.current
    val skin = state.activeSkin?.takeIf { state.enabled && isOwner && UiSkinSurface.PROFILE in it.manifest.surfaces } ?: return
    val window = LocalWindowInfo.current.containerSize
    val preferLandscape = window.width > window.height
    val paths = skin.manifest.assets.spaceBackgrounds.mapNotNull { background ->
        skin.assetFilePath(if (preferLandscape) background.landscape ?: background.portrait else background.portrait ?: background.landscape)
    }
    if (paths.isEmpty()) return
    val pager = rememberPagerState { paths.size }
    var failure by remember(paths) { mutableStateOf<String?>(null) }
    Box(modifier) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            DesktopUiSkinAsset(paths[page], Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failure = it })
        }
        if (paths.size > 1) LinearProgressIndicator(progress = { (pager.currentPage + 1f) / paths.size },
            modifier = Modifier.fillMaxWidth().height(3.5.dp).align(Alignment.BottomCenter), color = Color.White,
            trackColor = Color(0x669e9e9e))
        failure?.let { Text(it, Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
    }
}
