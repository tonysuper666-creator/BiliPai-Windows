package com.bilipai.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext as LocalContext
import coil3.request.*
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.feature.video.ui.section.*
import com.bilipai.desktop.player.DesktopNativeVideoCanvasLease
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.delay

/** Display identity only. Actual Store/entry/native admission stays at the caller.
 * Value-equal entries/requests/peers must never inherit another reveal timeline. */
internal class DesktopWindowsVideoEntryCoverIdentity(
    val assembly: Any, val route: Any, val request: Any?, val requestToken: Long,
    val publication: Any?, val playbackRevision: Long?, val peer: Any?,
) {
    override fun equals(other: Any?): Boolean = other is DesktopWindowsVideoEntryCoverIdentity &&
        assembly === other.assembly && route === other.route && request === other.request &&
        requestToken == other.requestToken && publication === other.publication &&
        playbackRevision == other.playbackRevision && peer === other.peer
    override fun hashCode(): Int = arrayOf(System.identityHashCode(assembly),System.identityHashCode(route),
        System.identityHashCode(request),requestToken.hashCode(),System.identityHashCode(publication),
        playbackRevision.hashCode(),System.identityHashCode(peer)).contentHashCode()
    override fun toString() = "DesktopWindowsVideoEntryCoverIdentity(requestToken=$requestToken, revision=$playbackRevision)"
}

/** A short readback captured inside the genuine NativeOwner gate, never a new owner. */
internal class DesktopWindowsVideoEntryCoverRuntime(
    val source: DesktopOriginalVideoAcceptedPublication,
    val lease: DesktopNativeVideoCanvasLease,
    val state: PlayerState,
    val playWhenReady: Boolean, val isPlaying: Boolean, val currentPositionMs: Long,
)

/** Complete fixed v041 image/manual-play subtree and reveal effects with finite
 * Windows adapters. The sole retained native Canvas continues drawing underneath. */
@Composable
internal fun DesktopWindowsVideoEntryCover(
    contentKey: DesktopWindowsVideoEntryCoverIdentity,
    bvid: String, entryCoverUrl: String, detailCoverUrl: String,
    autoPlayOnOpenEnabled: Boolean, firstVideoFrameReady: Boolean,
    observedPlayWhenReady: Boolean, observedIsPlaying: Boolean, currentPositionMs: Long,
    hasMediaItem: Boolean, isVerticalVideo: Boolean, isFullscreen: Boolean,
    stillCurrent: () -> Boolean, canReveal: () -> Boolean, onPlayFromCover: () -> Boolean,
) {
    key(contentKey) {
    val latestCurrent by rememberUpdatedState(stillCurrent)
    val latestCanReveal by rememberUpdatedState(canReveal)
    val latestPlay by rememberUpdatedState(onPlayFromCover)
    val forceCoverDuringReturnAnimation = false // Actual shared-card return witness is not yet connected.
    val preserveCurrentFrameOnFullscreenChange = false // Fullscreen itself does not replace contentKey.
    val persistedRenderedFirstFrame = firstVideoFrameReady
    var hasManualStartPlaybackIntent by remember { mutableStateOf(false) }
    LaunchedEffect(observedPlayWhenReady, observedIsPlaying, hasMediaItem) {
        if (latestCurrent() && hasMediaItem && (observedPlayWhenReady || observedIsPlaying)) {
            hasManualStartPlaybackIntent = true
        }
    }
    val playFromManualStartCover = {
        if (latestCurrent() && latestPlay()) hasManualStartPlaybackIntent = true
    }
    val keepCoverForManualStart = shouldKeepCoverForManualStart(
        playWhenReady = observedPlayWhenReady,
        currentPositionMs = currentPositionMs,
        autoPlayEnabled = autoPlayOnOpenEnabled,
        hasManualStartPlaybackIntent = hasManualStartPlaybackIntent,
    )
        val coverBootstrapState = remember(
            bvid,
            forceCoverDuringReturnAnimation,
            persistedRenderedFirstFrame,
            keepCoverForManualStart,
            preserveCurrentFrameOnFullscreenChange,
        ) {
            resolveVideoPlayerCoverBootstrapState(
                forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
                shouldKeepCoverForManualStart = keepCoverForManualStart,
                hasPersistedRenderedFirstFrame = persistedRenderedFirstFrame,
                preserveCurrentFrameOnFullscreenChange = preserveCurrentFrameOnFullscreenChange,
            )
        }
        var isFirstFrameRendered by remember(bvid) {
            mutableStateOf(coverBootstrapState.isFirstFrameRendered)
        }
        var hasStartedSmoothReveal by remember(bvid) {
            mutableStateOf(coverBootstrapState.hasStartedSmoothReveal)
        }
        // 揭开动画是否已完全落定（视频 surface 不透明）。落定前封面保持不透明垫底，
        // 落定后移除封面是不可见操作；全屏切换 bootstrap 直接以揭开态进场，无需垫底窗口。
        var hasSurfaceRevealSettled by remember(bvid) {
            mutableStateOf(coverBootstrapState.hasStartedSmoothReveal)
        }
        val reduceRevealMotion = rememberSystemReduceMotion()
        val revealMotionSpec = remember(reduceRevealMotion) {
            resolveVideoPlayerRevealMotionSpec(reducedMotion = reduceRevealMotion)
        }

    LaunchedEffect(firstVideoFrameReady) {
        if (!latestCurrent()) return@LaunchedEffect
        isFirstFrameRendered = firstVideoFrameReady
        if (!firstVideoFrameReady) {
            hasStartedSmoothReveal = false
            hasSurfaceRevealSettled = false
        }
    }
    LaunchedEffect(
        bvid,
        isFirstFrameRendered,
        forceCoverDuringReturnAnimation,
        keepCoverForManualStart,
        revealMotionSpec,
    ) {
        if (!latestCurrent()) return@LaunchedEffect
        if (
            shouldResetSmoothCoverReveal(
                forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
                shouldKeepCoverForManualStart = keepCoverForManualStart,
            )
        ) {
            hasStartedSmoothReveal = false
            return@LaunchedEffect
        }
        if (!isFirstFrameRendered) {
            // 等首帧；不要把 hasStartedSmoothReveal 清掉（避免与 bootstrap 竞态）
            return@LaunchedEffect
        }
        if (hasStartedSmoothReveal) return@LaunchedEffect
        // Let the rendered first frame reach a draw boundary instead of adding an
        // arbitrary 96ms hold on top of the card's spatial motion.
        withFrameNanos { }
        if (!latestCanReveal()) return@LaunchedEffect
        if (revealMotionSpec.coverRevealHoldDelayMillis > 0) {
            delay(revealMotionSpec.coverRevealHoldDelayMillis.toLong())
        }
        if (!latestCanReveal()) return@LaunchedEffect
        if (
            shouldCommitSmoothCoverReveal(
                isFirstFrameRendered = isFirstFrameRendered,
                forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
                shouldKeepCoverForManualStart = keepCoverForManualStart,
            )
        ) {
            hasStartedSmoothReveal = true
        }
    }
    // 揭开动画落定前封面保持不透明垫底；落定后再移除（此时视频已完全盖住封面，移除不可见）。
    LaunchedEffect(bvid, hasStartedSmoothReveal, revealMotionSpec.surfaceRevealDurationMillis) {
        if (!latestCurrent()) return@LaunchedEffect
        if (!hasStartedSmoothReveal) {
            hasSurfaceRevealSettled = false
            return@LaunchedEffect
        }
        if (hasSurfaceRevealSettled) return@LaunchedEffect
        delay(
            resolveVideoPlayerCoverRevealSettleDelayMillis(
                revealMotionSpec.surfaceRevealDurationMillis
            )
        )
        if (!latestCanReveal()) return@LaunchedEffect
        hasSurfaceRevealSettled = true
    }
    val isSurfaceRevealSettling = hasStartedSmoothReveal && !hasSurfaceRevealSettled
    val holdEntryCoverUnderlay = shouldHoldEntryCoverUnderlay(
        isFirstFrameRendered = isFirstFrameRendered,
        forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
        shouldKeepCoverForManualStart = keepCoverForManualStart,
        hasStartedSmoothReveal = hasStartedSmoothReveal,
        isSurfaceRevealSettling = isSurfaceRevealSettling,
    )
    val showCover = shouldShowCoverImage(
        isFirstFrameRendered = isFirstFrameRendered,
        forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
        shouldKeepCoverForManualStart = keepCoverForManualStart,
        hasStartedSmoothReveal = hasStartedSmoothReveal,
        isSurfaceRevealSettling = isSurfaceRevealSettling,
    )
    val manualStartPlayButtonLayoutSpec = remember {
        resolveManualStartPlayButtonLayoutSpec()
    }
    val loadCoverImage = shouldLoadVideoPlayerCoverImage(
        isVerticalVideo = isVerticalVideo,
        shouldKeepCoverForManualStart = keepCoverForManualStart,
        forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
    )

    val targetMode = if (isFullscreen) VideoSharedTransitionTargetMode.LandscapeFullscreen
        else VideoSharedTransitionTargetMode.InlinePlayer
    val entryPresentationSpec = remember(
        keepCoverForManualStart,
        forceCoverDuringReturnAnimation,
        isVerticalVideo,
        targetMode
    ) {
        resolveVideoPlayerEntryPresentationSpec(
            shouldKeepCoverForManualStart = keepCoverForManualStart,
            forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
            isVerticalVideo = isVerticalVideo,
            targetMode = targetMode
        )
    }

    val fillPlayerViewportForManualStartCover = entryPresentationSpec.fillCoverViewport
    val suppressCoverFade = forceCoverDuringReturnAnimation || holdEntryCoverUnderlay
    val surfaceCanCrossfade = canSurfaceParticipateInAlphaCrossfade(false)
    val coverMotionSpec = remember(
        suppressCoverFade,
        holdEntryCoverUnderlay,
        surfaceCanCrossfade,
        revealMotionSpec.surfaceRevealDurationMillis,
    ) {
        resolveVideoPlayerCoverMotionSpec(
            forceCoverDuringReturnAnimation = suppressCoverFade,
            holdEntryCoverUnderlay = holdEntryCoverUnderlay,
            // 保留 TextureView 的原有时长，SurfaceView/HDR 在落定后直接移除。
            surfaceRevealDurationMillis = revealMotionSpec.surfaceRevealDurationMillis,
            surfaceCanCrossfade = surfaceCanCrossfade,
        )
    }

    val disableCoverFadeAnimation = !coverMotionSpec.shouldAnimateFade
    val coverLayerZIndex = 0f // Foreground popup paints this before controls; HWND cannot use a negative Compose underlay.
    val currentCoverUrl = FormatUtils.fixImageUrl(resolvePreferredVideoCoverUrl(
        entryCoverUrl = entryCoverUrl, detailCoverUrl = detailCoverUrl,
        preferDetailCoverUrl = keepCoverForManualStart && isVerticalVideo,
    ))
    val playerContentModifier = Modifier.fillMaxSize()
    val useStationaryListCover = false
    val stationaryListKey = ""
    val stationaryListCoverDecodeWidthPx = 0
    val stationaryListCoverDecodeHeightPx = 0
    val manualStartPlayIcon = painterResource("icons/ic_pili_tv_play.png")
    AnimatedVisibility(
        visible = showCover, // Opaque loading slot also prevents an old native frame leaking when no new image exists.
        enter = if (disableCoverFadeAnimation) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = tween(coverMotionSpec.enterFadeDurationMillis))
        },
        exit = if (disableCoverFadeAnimation) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = tween(coverMotionSpec.exitFadeDurationMillis))
        },
        modifier = Modifier.zIndex(coverLayerZIndex)
    ) {
        // Keep the per-frame color read inside the cover subtree, away from PlayerView.
        val coverRevealPolishProgress by animateFloatAsState(
            targetValue = if (isSurfaceRevealSettling) 0f else 1f,
            animationSpec = tween(revealMotionSpec.surfaceRevealDurationMillis),
            label = "coverRevealPolish",
        )
        val coverRevealColorFilter = remember(coverRevealPolishProgress) {
            resolveVideoPlayerCoverRevealColorFilter(coverRevealPolishProgress)
        }
        val coverCardShape = RoundedCornerShape(0.dp)
        val sharedCoverOverlayModifier = Modifier // Native rectangular slot; shared-card return geometry is not yet connected.

        Box(
            modifier = playerContentModifier
                .desktopCommandHitRegion("windows-entry-cover"),
        ) {
            val coverContainerModifier = if (fillPlayerViewportForManualStartCover ||
                (!isVerticalVideo && !isFullscreen && !forceCoverDuringReturnAnimation)
            ) {
                sharedCoverOverlayModifier
                    .matchParentSize()
                    .background(Color.Black)
            } else {
                sharedCoverOverlayModifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .aspectRatio(VIDEO_SHARED_COVER_ASPECT_RATIO)
                    .clip(coverCardShape)
                    .background(Color.Black)
            }
            Box(
                modifier = coverContainerModifier
                    .clickable(enabled = entryPresentationSpec.enableManualStartCoverOverlay) {
                        playFromManualStartCover()
                    }
            ) {
                if (loadCoverImage && currentCoverUrl.isNotEmpty()) {
                    val sharedCoverCacheKey = if (useStationaryListCover) {
                        stationaryListKey
                    } else {
                        resolveVideoSharedCoverCacheKey(bvid)
                    }
                    val decodeW = stationaryListCoverDecodeWidthPx
                    val decodeH = stationaryListCoverDecodeHeightPx
                    AsyncImage(
                        model = coil3.request.ImageRequest.Builder(LocalContext.current)
                            .data(currentCoverUrl)
                            .apply {
                                if (useStationaryListCover && decodeW > 0 && decodeH > 0) {
                                    size(decodeW, decodeH)
                                }
                            }
                            // Same memory/disk key as list AsyncImage when stationary is set.
                            .placeholderMemoryCacheKey(sharedCoverCacheKey)
                            .memoryCacheKey(sharedCoverCacheKey)
                            .diskCacheKey(sharedCoverCacheKey)
                            .crossfade(
                                shouldEnableCoverImageCrossfade(
                                    forceCoverDuringReturnAnimation = forceCoverDuringReturnAnimation,
                                    holdEntryCoverUnderlay = holdEntryCoverUnderlay,
                                )
                            )
                            .build(),
                        contentDescription = null,
                        contentScale = when (entryPresentationSpec.coverContentScaleMode) {
                            VideoPlayerCoverContentScaleMode.Crop -> ContentScale.Crop
                            VideoPlayerCoverContentScaleMode.Fit -> ContentScale.Fit
                        },
                        colorFilter = coverRevealColorFilter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black)
                    )
                }

                if (entryPresentationSpec.showManualStartPlayButton) {
                    if (manualStartPlayButtonLayoutSpec.showCoverScrim) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.18f))
                        )
                    }
                    AppIconButton(
                        onClick = playFromManualStartCover,
                        modifier = Modifier
                            .align(
                                when (manualStartPlayButtonLayoutSpec.anchor) {
                                    ManualStartPlayButtonAnchor.Center -> Alignment.Center
                                    ManualStartPlayButtonAnchor.CenterEnd -> Alignment.CenterEnd
                                    ManualStartPlayButtonAnchor.BottomEnd -> Alignment.BottomEnd
                                }
                            )
                            .padding(
                                end = manualStartPlayButtonLayoutSpec.endPaddingDp.dp,
                                bottom = if (manualStartPlayButtonLayoutSpec.anchor == ManualStartPlayButtonAnchor.BottomEnd) {
                                    10.dp
                                } else {
                                    0.dp
                                }
                            )
                            .size(
                                width = manualStartPlayButtonLayoutSpec.iconWidthDp.dp,
                                height = manualStartPlayButtonLayoutSpec.iconHeightDp.dp
                            ),
                    ) {
                        AppIcon(
                            painter = manualStartPlayIcon,
                            contentDescription = "播放视频",
                            tint = AppContentColors.accent(),
                            modifier = Modifier.size(60.dp),
                        )
                    }
                }
            }
        }
    }
    }
}
