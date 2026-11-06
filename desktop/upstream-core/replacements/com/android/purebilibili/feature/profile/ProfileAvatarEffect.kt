// Replaces upstream app/src/main/java/com/android/purebilibili/feature/profile/ProfileAvatarEffect.kt sha256=d71e5ee92395b735aacd72cb4744781ad77d084cceb502f3070e878d36ad5f74
// Upstream hosts the avatar in ProfileAvatarFusionView, an Android View that merges it into the camera cutout. A window has
// no cutout, so the desktop draws the same avatar in Compose and moves it with upstream's geometry (resolveProfileAvatarSizePx,
// PROFILE_EXPANDED_AVATAR_TOP_DP, the no-cutout end point 29 dp above the top), without the cutout fusion.
package com.android.purebilibili.feature.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.android.purebilibili.feature.home.UserState

@Composable
internal fun ProfileAvatarEffect(
    user: UserState,
    expandedFractionProvider: () -> Float,
    statusBarTopPadding: Dp,
    borderColor: Color,
    listState: LazyListState,
    flingBehavior: FlingBehavior,
    onAvatarClick: () -> Unit,
    avatarSourceRect: androidx.compose.runtime.MutableState<androidx.compose.ui.geometry.Rect?>,
    modifier: Modifier = Modifier,
    photoScrollConnection: NestedScrollConnection? = null,
) {
    val density = LocalDensity.current
    val context = LocalPlatformContext.current
    val latestProvider = rememberUpdatedState(expandedFractionProvider)
    val latestClick = rememberUpdatedState(onAvatarClick)
    val topPx = with(density) { statusBarTopPadding.toPx() }
    val avatarRequest = remember(context, user.face) {
        ImageRequest.Builder(context)
            .data(user.face)
            .memoryCacheKey(com.android.purebilibili.feature.dynamic.components.resolveImagePreviewPlaceholderCacheKey(user.face) ?: user.face)
            .buildForProfileFusion()
    }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    BoxWithConstraints(modifier = modifier) {
        val hostWidthPx = with(density) { maxWidth.toPx() }
        val baseSizePx = 100f * density.density
        Box(
            modifier = Modifier
                .size(100.dp)
                .graphicsLayer {
                    // The same frame update as upstream's setFrameUpdater, applied in the draw phase.
                    val expanded = latestProvider.value().coerceIn(0f, 1f)
                    val sizePx = resolveProfileAvatarSizePx(density.density, expanded, circularCutoutWidthPx = 0f)
                    val scale = sizePx / baseSizePx
                    val endY = -29f * density.density
                    val startY = topPx + PROFILE_EXPANDED_AVATAR_TOP_DP * density.density
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = (hostWidthPx - sizePx) / 2f
                    translationY = endY + (startY - endY) * expanded
                }
                .onGloballyPositioned { coordinates[0] = it },
        ) {
            AsyncImage(
                model = avatarRequest,
                contentDescription = "${user.name}的头像，查看大图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .alpha(if (com.android.purebilibili.feature.dynamic.components.isImagePreviewSourceHidden(avatarSourceRect.value)) 0f else 1f)
                    .clip(CircleShape)
                    .border(2.dp, borderColor, CircleShape)
                    .then(photoScrollConnection?.let { Modifier.nestedScroll(it) } ?: Modifier)
                    .scrollable(listState, Orientation.Vertical, reverseDirection = true, flingBehavior = flingBehavior)
                    .clickable {
                        avatarSourceRect.value = coordinates[0]?.takeIf { it.isAttached }?.boundsInWindow()
                        latestClick.value()
                    },
            )
        }
    }
}

private fun Modifier.fillMaxSize(): Modifier = this.then(Modifier.size(100.dp))
