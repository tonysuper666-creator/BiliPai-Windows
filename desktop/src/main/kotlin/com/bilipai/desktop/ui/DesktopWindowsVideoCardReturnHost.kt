package com.bilipai.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.navigation.isVideoCardReturnTargetRoute
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute
import com.android.purebilibili.navigation3.predictiveback.*
import kotlinx.coroutines.*

/** Signals actual Root visual ownership, not a saved transition preference. The zero-duration
 * NavDisplay may settle its original clock only when this current driver has released it. */
internal val LocalDesktopWindowsVideoReturnDriver = staticCompositionLocalOf<() -> Boolean> { { false } }

private class DesktopWindowsVideoReturnVisual(
    val request: DesktopWindowsVideoReturnRequest,
    val snapshot: DesktopWindowsVideoCardReturnSnapshot,
    val native: DesktopWindowsNativeVideoSurface.ReturnNativeCapture,
    val rootBoundsInWindow: Rect,
    val layoutDensity: Float,
) {
    val coverDrawn = CompletableDeferred<Boolean>()
    val depth = Animatable(1f)
    var committedRevision: Long? = null
    var committedDestination: BiliPaiNavKey? = null
    var job: Job? = null
}

private class DesktopWindowsVideoReturnClockOwner {
    var visual: DesktopWindowsVideoReturnVisual? = null
}

/** Survives the real physical pop at the one Window-level Root callsite. No SharedTransition,
 * Lookahead, copied navigation list, reparented SwingPanel or screen capture is introduced.
 *
 * The resident cover is drawn into the existing Compose root first (behind the video HWND).
 * Only then does an exact source/peer lease hide that HWND, exposing the already drawn cover.
 * A subsequent frame precedes the real Root checkpoint/pop. All animation drawing continues
 * from frozen original inputs after related return restores its parent's mutable session.
 * Draw acknowledgement and OS clip acknowledgement are deliberately separate; neither claims
 * that the decoded native video frame, RTX enhancement, or the final desktop UI was verified.
 */
@Composable
internal fun DesktopWindowsVideoCardReturnHost(
    routes: DesktopOriginalRootRouteAssembly,
    clock: VideoCardTransitionClock,
    enabled: Boolean,
    reduceMotion: Boolean,
    durationMillis: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val nativeSurface = LocalDesktopWindowsNativeVideoSurface.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val enabledNow by rememberUpdatedState(enabled && !reduceMotion)
    val durationNow by rememberUpdatedState(durationMillis)
    val densityNow by rememberUpdatedState(density.density)
    var boundsInRoot by remember(routes) { mutableStateOf<Rect?>(null) }
    var boundsInWindow by remember(routes) { mutableStateOf<Rect?>(null) }
    var visual by remember(routes, nativeSurface) { mutableStateOf<DesktopWindowsVideoReturnVisual?>(null) }
    val clockOwner = remember(routes, clock) { DesktopWindowsVideoReturnClockOwner() }

    fun current(value: DesktopWindowsVideoReturnVisual): Boolean {
        if (visual !== value || !routes.owns() || boundsInRoot != value.snapshot.viewportInRoot ||
            boundsInWindow != value.rootBoundsInWindow || densityNow != value.layoutDensity) return false
        val revision = value.committedRevision
        return if (revision == null) value.request.isCurrent() && value.native.isCurrent()
        else routes.sourceNavigationRevision == revision && routes.currentKey === value.committedDestination
    }

    fun release(value: DesktopWindowsVideoReturnVisual) {
        if (visual === value) visual = null
        try {
            nativeSurface?.endReturnVisual(value)
            value.native.close() // Exact native owner only, even after account/source retirement.
        } finally {
            if (clockOwner.visual === value) {
                clockOwner.visual = null
                clock.bindNavigationDriver(null)
                // An intervening click may already have armed the same original clock for opening.
                if (routes.owns() && clock.phase == VideoCardTransitionBackgroundPhase.RETURNING)
                    settleDesktopWindowsCommittedNavigationClock(clock, routes.stack.toList())
            }
        }
    }

    fun completeCommittedRelated(value: DesktopWindowsVideoReturnVisual) {
        // A cancelled visual still completes its genuine, already committed related pop.
        // A new actual navigation invalidates this exact revision/destination even if equal.
        if (routes.owns() && value.committedRevision == routes.sourceNavigationRevision &&
            value.committedDestination === routes.currentKey && value.request.targetKey is BiliPaiNavKey.VideoDetail) {
            completeDesktopWindowsRelatedVideoReturn(routes.root.returns, clock)
            if (clockOwner.visual === value && visual === value)
                clock.sourceRoute = routes.root.returns.session.value.transitionSession?.sourceRoute
        }
    }

    val accept: (DesktopWindowsVideoReturnRequest) -> Boolean = remember(routes, nativeSurface, clock, scope) {
        accept@{ request ->
            if (!enabledNow || nativeSurface == null || !request.isCurrent() ||
                !isVideoCardReturnTargetRoute(request.targetKey?.toLegacyRoute())) return@accept false
            val viewport = boundsInRoot ?: return@accept false
            val window = boundsInWindow ?: return@accept false
            val session = routes.root.returns.session.value.transitionSession
            if (session?.bvid != request.leavingKey.bvid) return@accept false
            val native = nativeSurface.captureReturnNativeSource(request.leavingKey) ?: return@accept false
            if (native.rootBoundsInWindow != window) { native.close(); return@accept false }
            val snapshot = DesktopWindowsVideoCardReturnSnapshot.capture(routes.root, request.leavingKey,
                session, viewport, native.lease)
            if (snapshot == null) { native.close(); return@accept false }
            visual?.let { previous -> previous.job?.cancel(); release(previous) }
            val value = DesktopWindowsVideoReturnVisual(request, snapshot, native, window, densityNow)
            visual = value
            value.job = scope.launch {
                try {
                    // A cache miss must not hold a user's back indefinitely. The existing real
                    // nonanimated path remains available; no placeholder image can authorize a pop.
                    val recorded = withTimeoutOrNull(350L) { value.coverDrawn.await() } == true
                    if (!current(value)) return@launch
                    if (!recorded || !native.hidePresentedSource()) {
                        if (request.commit()) {
                            value.committedRevision = routes.sourceNavigationRevision
                            value.committedDestination = routes.currentKey
                        }
                        return@launch
                    }
                    withFrameNanos { } // Actual clipped HWND now exposes the prior Compose cover draw.
                    if (!current(value)) return@launch
                    nativeSurface.beginReturnVisual(value) { current(value) }
                    clockOwner.visual = value
                    clock.beginReturning(snapshot.transitionSession.sourceRoute)
                    clock.bindNavigationDriver { if (current(value)) value.depth.value else null }
                    clock.followNavigationDriver(VideoCardTransitionSettleState.AutoReturn)
                    if (!request.commit()) return@launch
                    value.committedRevision = routes.sourceNavigationRevision
                    value.committedDestination = routes.currentKey
                    if (!current(value)) return@launch
                    val spec = resolveVideoHeroMotionSpec(durationNow, snapshot.sourceCardInRoot,
                        snapshot.viewportInRoot, densityNow)
                    value.depth.animateTo(0f, tween(spec.returnDurationMillis, easing = spec.returnSpatialSpec)) {
                        if (!current(value)) throw CancellationException("Actual Root return visual retired")
                    }
                    withFrameNanos { } // Keep the original endpoint for one recorded Compose frame.
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Rendering/native preparation must not strand a still-current back intent.
                    // A consumed or obsolete ticket cannot replay the pop, even after failure.
                    if (request.isCurrent() && request.commit()) {
                        value.committedRevision = routes.sourceNavigationRevision
                        value.committedDestination = routes.currentKey
                    }
                    java.util.logging.Logger.getLogger("BiliPai.VideoReturn")
                        .log(java.util.logging.Level.WARNING, "Original Windows return visual failed", failure)
                } finally {
                    try {
                        // Resize/PiP/source replacement can retire drawing while this SAME Root
                        // back intent remains current. Finish through its original checkpoint,
                        // rather than leave a coalesced request waiting forever. Unbind/account/
                        // real navigation retirement invalidates the ticket before this can run.
                        if (request.isCurrent() && request.commit()) {
                            value.committedRevision = routes.sourceNavigationRevision
                            value.committedDestination = routes.currentKey
                        }
                        completeCommittedRelated(value)
                    } finally { release(value) }
                }
            }
            true
        }
    }
    DisposableEffect(routes, accept) {
        routes.bindVideoReturn(accept)
        onDispose {
            routes.unbindVideoReturn(accept)
            visual?.let { it.job?.cancel(); release(it) }
        }
    }
    val driverOwns: () -> Boolean = { visual?.let { clockOwner.visual === it && current(it) } == true }
    val observed = visual
    SideEffect {
        // A real navigation/resize/account change retires both delayed preparation and flight.
        if (observed != null && !current(observed)) observed.job?.cancel()
    }
    CompositionLocalProvider(LocalDesktopWindowsVideoReturnDriver provides driverOwns) {
        Box(modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            val root = coordinates.boundsInRoot()
            val origin = coordinates.positionInWindow()
            val window = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            if (boundsInRoot != root) boundsInRoot = root
            if (boundsInWindow != window) boundsInWindow = window
        }) {
            content()
            visual?.let { value ->
                val snapshot = value.snapshot
                val source = snapshot.sourceCardInRoot
                val viewport = snapshot.viewportInRoot
                val localSource = Rect(source.left - viewport.left, source.top - viewport.top,
                    source.right - viewport.left, source.bottom - viewport.top)
                // Use the exact original outer path, click pose, corner and FillWidthTop
                // compensation. Its paired inverse is consumed by the retained original chrome.
                val outer = Modifier.zIndex(100f).graphicsLayer {
                    val depth = value.depth.value
                    val pose = resolveMiuixVideoCardClickTransform(depth, viewport.width, viewport.height, localSource)
                    transformOrigin = resolveMiuixVideoCardGestureVisualOrigin(localSource,
                        viewport.width, viewport.height, depth, pose.transformOrigin)
                    translationX = pose.translationX; translationY = pose.translationY
                    rotationX = pose.rotationX; rotationY = pose.rotationY; rotationZ = pose.rotationZ
                    scaleX = pose.liftScale; scaleY = pose.liftScale; cameraDistance = pose.cameraDistance
                }.graphicsLayer {
                    val depth = value.depth.value
                    val landing = resolveVideoHeroLandingScale(depth, autoReturning = true)
                    val sx = resolveMiuixVideoCardOuterScale((source.width / viewport.width).coerceIn(.01f, 1f), depth, landing)
                    val sy = resolveMiuixVideoCardOuterScale((source.height / viewport.height).coerceIn(.01f, 1f), depth, landing)
                    scaleX = sx; scaleY = sy; transformOrigin = TransformOrigin(0f, 0f)
                    val path = resolveVideoCardPathOffset(localSource, viewport.width, viewport.height, depth, 10.dp.toPx())
                    translationX = path.x; translationY = path.y
                    clip = depth < .999f
                    val radius = resolveMiuixVideoCardGestureCornerPx(
                        (snapshot.transitionSession.sourceCornerDp ?: 16).dp.toPx(), depth,
                        MIUIX_VIDEO_CARD_FLOATING_CORNER_DP.dp.toPx())
                    shape = MiuixVideoCardClipShape(radius / sx.coerceAtLeast(.01f), radius / sy.coerceAtLeast(.01f))
                }.graphicsLayer {
                    val depth = value.depth.value
                    val landing = resolveVideoHeroLandingScale(depth, autoReturning = true)
                    val compensation = resolveMiuixVideoCardContentCompensation(
                        resolveMiuixVideoCardOuterScale(source.width / viewport.width, depth, landing),
                        resolveMiuixVideoCardOuterScale(source.height / viewport.height, depth, landing),
                        MiuixVideoCardContentScale.FillWidthTop)
                    scaleX = compensation.scaleX; scaleY = compensation.scaleY
                    transformOrigin = compensation.transformOrigin
                }
                DesktopWindowsVideoCardReturnContent(snapshot, { value.depth.value }, { current(value) },
                    onCoverRecorded = { if (it === snapshot && current(value)) value.coverDrawn.complete(true) },
                    onCoverFailed = { if (it === snapshot && current(value)) value.coverDrawn.complete(false) },
                    modifier = outer)
            }
        }
    }
}
