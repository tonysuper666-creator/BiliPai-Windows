package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.*
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.feature.video.screen.VideoDetailReturnCoverChrome
import com.android.purebilibili.feature.video.screen.VideoDetailReturnSourceCardChrome
import com.android.purebilibili.feature.video.screen.videoDetailReturnMediaLayout
import com.android.purebilibili.navigation3.predictiveback.resolveMiuixVideoCardInverseScaleForDepth
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Actual original card/cover drawing in the Root-owned survivor host.
 *
 * This component does not pop a route, control an HWND, start a clock or authorize media.
 * [onCoverRecorded] reports only a Compose draw containing a successfully loaded resident
 * image; it is NOT a native clip receipt or proof of a displayed video frame. The outer Root
 * host must compare this same snapshot, apply a real lease-checked clip, then commit back.
 * All callers must stay in the actual Root theme and existing application image-loader scope.
 * The outer host must also apply the original FillWidthTop content compensation for its SAME
 * viewport, depth and landing-scale policy before its outer scale/translation/clip. The inverse
 * below deliberately follows that original contract; a bare nonuniform rectangle scale is wrong.
 */
@Composable
internal fun DesktopWindowsVideoCardReturnContent(
    snapshot: DesktopWindowsVideoCardReturnSnapshot,
    morphDepthProvider: () -> Float,
    isVisualCurrent: () -> Boolean,
    onCoverRecorded: (DesktopWindowsVideoCardReturnSnapshot) -> Unit,
    onCoverFailed: (DesktopWindowsVideoCardReturnSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A replacement visual must retire both this scope and its remembered callback cells.
    // Otherwise an old delayed image/frame event could call a successor's callbacks with A.
    key(snapshot) {
    val latestCurrent by rememberUpdatedState(isVisualCurrent)
    val latestRecorded by rememberUpdatedState(onCoverRecorded)
    val latestFailed by rememberUpdatedState(onCoverFailed)
    val latestDepth by rememberUpdatedState(morphDepthProvider)
    val density = LocalDensity.current
    val platformContext = LocalPlatformContext.current
    val callbackScope = rememberCoroutineScope()
    var imageLoaded by remember(snapshot) { mutableStateOf(false) }
    val recorded = remember(snapshot) { AtomicBoolean(false) }
    val request = remember(snapshot, platformContext) {
        val cover = snapshot.residentCover
        ImageRequest.Builder(platformContext).data(cover.url)
            .apply {
                if (cover.decodeWidthPx > 0 && cover.decodeHeightPx > 0)
                    size(cover.decodeWidthPx, cover.decodeHeightPx)
            }
            .placeholderMemoryCacheKey(cover.cacheKey)
            .memoryCacheKey(cover.cacheKey)
            .diskCacheKey(cover.cacheKey)
            .crossfade(false)
            .build()
    }
    val depth: () -> Float = { latestDepth().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f }
    val sourceScaleX = (snapshot.sourceCardInRoot.width / snapshot.viewportInRoot.width).coerceIn(.01f, 1f)
    val sourceScaleY = (snapshot.sourceCardInRoot.height / snapshot.viewportInRoot.height).coerceIn(.01f, 1f)
    val inverse = {
        resolveMiuixVideoCardInverseScaleForDepth(sourceScaleX, sourceScaleY, depth(), autoReturning = true)
    }
    val handoff = {
        resolveVideoDetailReturnMediaLayoutHandoffProgress(depth(),
            VideoCardTransitionBackgroundPhase.RETURNING, false, snapshot.transitionSession.sourceLayout)
    }
    val hero = remember(snapshot) {
        MiuixVideoCardTransitionState(enabled = true,
            progressProvider = depth,
            layoutWidthProvider = { snapshot.viewportInRoot.width },
            layoutHeightProvider = { snapshot.viewportInRoot.height },
            sourceBoundsProvider = { snapshot.sourceCardInRoot },
            sourceCoverBoundsProvider = { snapshot.sourceCoverInRoot },
            sourceLayout = snapshot.transitionSession.sourceLayout,
            sourceChromeSnapshot = snapshot.transitionSession.sourceChromeSnapshot)
    }
    CompositionLocalProvider(LocalMiuixVideoCardTransitionState provides hero) {
        Box(modifier.requiredSize(with(density) { snapshot.viewportInRoot.width.toDp() },
            with(density) { snapshot.viewportInRoot.height.toDp() })
            .drawWithContent {
                if (latestCurrent() && imageLoaded) {
                    drawContent()
                    if (imageLoaded && recorded.compareAndSet(false, true)) callbackScope.launch {
                        // A Root caller may prepare navigation here. Never call it reentrantly
                        // from draw or let a disposed/obsolete visual send a delayed receipt.
                        withFrameNanos { }
                        if (latestCurrent()) latestRecorded(snapshot)
                    }
                }
            }) {
            val media = Modifier.fillMaxSize().videoDetailReturnMediaLayout(
                landingLayout = snapshot.sourceLayout,
                handoffProgressProvider = handoff,
                inverseScaleXProvider = { inverse().scaleX },
                inverseScaleYProvider = { inverse().scaleY })
            // The physical HWND may remain behind this slot until Root's real clip acknowledgement.
            Box(media.background(Color.Black)) {
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop,
                    onSuccess = { if (latestCurrent()) imageLoaded = true },
                    onError = { callbackScope.launch {
                        yield()
                        if (latestCurrent()) latestFailed(snapshot)
                    } },
                    modifier = Modifier.fillMaxSize())
            }
            VideoDetailReturnCoverChrome(
                sourceChromeSnapshot = snapshot.transitionSession.sourceChromeSnapshot,
                sourceScale = sourceScaleX,
                modifier = media.zIndex(1f),
                inverseScaleXProvider = { inverse().scaleX },
                inverseScaleYProvider = { inverse().scaleY },
                frozenSnapshot = snapshot)
            VideoDetailReturnSourceCardChrome(
                sourceBounds = snapshot.sourceCardInRoot,
                sourceCoverBounds = snapshot.sourceCoverInRoot,
                morphDepthProgressProvider = depth,
                sourceLayout = snapshot.transitionSession.sourceLayout,
                sourceChromeSnapshot = snapshot.transitionSession.sourceChromeSnapshot,
                frozenSnapshot = snapshot)
        }
    }
    }
}
