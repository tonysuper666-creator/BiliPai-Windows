package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.player.MpvPlayer
import java.awt.EventQueue

/** Root-owned geometry for the one Windows Canvas. The SwingPanel is rendered at
 * one fixed Root callsite: leaves report pixels, never move/reparent the native
 * component. Its single presentation receipt only recognizes a returning
 * actual navigation entry; native/account/source admission remains authoritative.
 */
internal class DesktopWindowsNativeVideoSurface(val player: MpvPlayer) {
    private data class Viewport(val route: BiliPaiNavKey.VideoDetail, val lease: Any, val renderBounds: Rect, val clipBounds: Rect)
    private class SourceBinding(val route: BiliPaiNavKey.VideoDetail, val lease: Any,
        val assembly: DesktopOriginalVideoOwnerAssembly, val source: DesktopOriginalVideoAcceptedPublication,
        var stillEntryOwned: () -> Boolean)
    private var sourceBinding: SourceBinding? = null
    private var renderedVisibleRoute: BiliPaiNavKey.VideoDetail? = null
    private var measuredRootBounds: Rect? = null
    private var measuredCanvasBounds: Rect? = null
    private var composeWindowSize = IntSize.Zero
    private var pendingNativeClip: (() -> Unit)? = null
    private var nativeClipScheduled = false

    fun bindViewportSource(route: BiliPaiNavKey.VideoDetail, lease: Any, assembly: DesktopOriginalVideoOwnerAssembly,
        source: DesktopOriginalVideoAcceptedPublication?, stillEntryOwned: () -> Boolean) {
        check(EventQueue.isDispatchThread())
        val previous = sourceBinding
        if (source == null) {
            if (previous?.route === route && previous.lease === lease) {
                player.releaseNativeVideoCanvasClip(previous)
                sourceBinding = null
            }
            return
        }
        if (previous?.route === route && previous.lease === lease && previous.assembly === assembly && previous.source === source) {
            previous.stillEntryOwned = stillEntryOwned
        } else {
            if (previous != null) player.releaseNativeVideoCanvasClip(previous)
            sourceBinding = SourceBinding(route, lease, assembly, source, stillEntryOwned)
        }
        // Source/revision changes need a new capture even at identical bounds.
        queueNativeClip()
    }

    private fun queueNativeClip() {
        check(EventQueue.isDispatchThread())
        val binding = sourceBinding ?: return
        val target = viewport ?: return
        if (renderedVisibleRoute !== binding.route || target.route !== binding.route || target.lease !== binding.lease) {
            pendingNativeClip = null
            player.releaseNativeVideoCanvasClip(binding)
            return
        }
        val actual = measuredCanvasBounds ?: return
        val root = measuredRootBounds ?: return
        val windowSize = composeWindowSize
        val clip = resolveDesktopWindowsNativeVideoClip(target.clipBounds, root, windowSize) ?: return
        var lease: com.bilipai.desktop.player.DesktopNativeVideoCanvasLease? = null
        binding.assembly.native.admitPlaybackDispatch(binding.source) {
            if (binding.stillEntryOwned()) lease = player.captureNativeVideoCanvasLease(binding, binding.source.nativeSource)
        }
        val captured = lease ?: return
        pendingNativeClip = {
            fun current(): Boolean = sourceBinding === binding && viewport === target &&
                renderedVisibleRoute === binding.route && measuredCanvasBounds == actual && measuredRootBounds == root &&
                composeWindowSize == windowSize && binding.stillEntryOwned()
            if (current()) binding.assembly.native.admitPlaybackDispatch(binding.source) {
                if (current()) player.applyNativeVideoCanvasClip(captured, clip, ::current)
            }
        }
        if (nativeClipScheduled) return
        nativeClipScheduled = true
        EventQueue.invokeLater {
            val apply = pendingNativeClip
            pendingNativeClip = null; nativeClipScheduled = false
            apply?.invoke()
        }
    }
    private var viewport by mutableStateOf<Viewport?>(null)
    private class Presentation(
        val route: BiliPaiNavKey.VideoDetail,
        val assembly: DesktopOriginalVideoOwnerAssembly,
        val excludedPrior: DesktopOriginalVideoAcceptedPublication?,
    ) {
        // The existing bounded UI receipt now recomposes its readers when the
        // unchanged recordPresentedSource admits a different exact reference.
        var accepted by mutableStateOf<DesktopOriginalVideoAcceptedPublication?>(null)
    }
    // One bounded UI receipt, not another source or navigation authority. A new
    // same-value route is a different entry and must perform its normal open.
    private var presentation: Presentation? = null

    fun recordBootstrap(
        route: BiliPaiNavKey.VideoDetail,
        assembly: DesktopOriginalVideoOwnerAssembly,
        prior: DesktopOriginalVideoAcceptedPublication?,
        retainedExisting: Boolean,
    ) {
        check(EventQueue.isDispatchThread())
        // A non-retained open is asynchronous. Its predecessor's still-visible
        // Success must never be mistaken for this new entry's completed load.
        presentation = Presentation(route, assembly, if (retainedExisting) null else prior)
    }

    private fun matchesOriginalSuccess(assembly: DesktopOriginalVideoOwnerAssembly,
        accepted: DesktopOriginalVideoAcceptedPublication): Boolean {
        val success = assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success ?: return false
        return success.info.bvid == accepted.request.bvid && success.info.cid == accepted.request.cid
    }

    fun recordPresentedSource(route: BiliPaiNavKey.VideoDetail,
        assembly: DesktopOriginalVideoOwnerAssembly, stillEntryOwned: () -> Boolean) {
        check(EventQueue.isDispatchThread())
        val receipt = presentation?.takeIf { it.route === route && it.assembly === assembly } ?: return
        if (!stillEntryOwned()) return
        val source = assembly.native.current() ?: return
        if (source === receipt.excludedPrior || source.request.bvid != route.bvid ||
            !matchesOriginalSuccess(assembly, source)) return
        assembly.native.admitPlaybackDispatch(source) {
            if (presentation === receipt && stillEntryOwned() && matchesOriginalSuccess(assembly, source))
                receipt.accepted = source
        }
    }

    /** Composition-lane capture of the existing admitted UI receipt only. This
     * never grants native/account/request ownership; the caller must still check
     * those authorities and original current Success at every submission. */
    fun capturePresentedSource(route: BiliPaiNavKey.VideoDetail,
        assembly: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoAcceptedPublication? {
        check(EventQueue.isDispatchThread())
        val receipt = presentation?.takeIf { it.route === route && it.assembly === assembly } ?: return null
        val source = receipt.accepted ?: return null
        if (source === receipt.excludedPrior || !matchesOriginalSuccess(assembly, source)) return null
        return source
    }

    fun retainPresentedEntry(route: BiliPaiNavKey.VideoDetail,
        assembly: DesktopOriginalVideoOwnerAssembly, stillEntryOwned: () -> Boolean): Boolean {
        check(EventQueue.isDispatchThread())
        val receipt = presentation?.takeIf { it.route === route && it.assembly === assembly } ?: return false
        val source = receipt.accepted ?: return false
        if (!stillEntryOwned() || source.request.bvid != route.bvid ||
            !matchesOriginalSuccess(assembly, source)) return false
        var retained = false
        val admitted = assembly.native.admitPlaybackDispatch(source) {
            retained = presentation === receipt && stillEntryOwned() && matchesOriginalSuccess(assembly, source)
        }
        // This does not seek, reload or replay the route's initial resume intent.
        return admitted && retained
    }

    fun reportViewport(route: BiliPaiNavKey.VideoDetail, lease: Any, bounds: Rect, clipBounds: Rect = bounds) {
        check(EventQueue.isDispatchThread())
        if (bounds.width <= 0f || bounds.height <= 0f ||
            !bounds.left.isFinite() || !bounds.top.isFinite() || !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
            !clipBounds.left.isFinite() || !clipBounds.top.isFinite() || !clipBounds.right.isFinite() || !clipBounds.bottom.isFinite()) return
        val previous = viewport
        if (previous?.route !== route || previous.lease !== lease || previous.renderBounds != bounds || previous.clipBounds != clipBounds)
            viewport = Viewport(route, lease, bounds, clipBounds)
        queueNativeClip()
    }

    fun releaseViewport(route: BiliPaiNavKey.VideoDetail, lease: Any) {
        check(EventQueue.isDispatchThread())
        val previous = viewport
        if (previous?.route === route && previous.lease === lease) viewport = null
        val binding = sourceBinding
        if (binding?.route === route && binding.lease === lease) {
            player.releaseNativeVideoCanvasClip(binding)
            sourceBinding = null
        }
    }

    @Composable fun Render(
        expectedPlayer: MpvPlayer,
        visibleRoute: BiliPaiNavKey.VideoDetail?,
        rootOriginInWindow: Offset?,
        modifier: Modifier,
    ) {
        check(expectedPlayer === player) { "Native surface belongs to another player" }
        val windowSize = LocalWindowInfo.current.containerSize
        SideEffect { renderedVisibleRoute = visibleRoute; composeWindowSize = windowSize; queueNativeClip() }
        DisposableEffect(player) {
            val observer = object : java.awt.event.ComponentAdapter() {
                override fun componentResized(event: java.awt.event.ComponentEvent) = queueNativeClip()
                override fun componentMoved(event: java.awt.event.ComponentEvent) = queueNativeClip()
                override fun componentShown(event: java.awt.event.ComponentEvent) = queueNativeClip()
            }
            val ancestors = object : java.awt.event.HierarchyBoundsAdapter() {
                override fun ancestorMoved(event: java.awt.event.HierarchyEvent) = queueNativeClip()
                override fun ancestorResized(event: java.awt.event.HierarchyEvent) = queueNativeClip()
            }
            player.surface.addComponentListener(observer)
            player.surface.addHierarchyBoundsListener(ancestors)
            onDispose {
                player.surface.removeComponentListener(observer)
                player.surface.removeHierarchyBoundsListener(ancestors)
                pendingNativeClip = null; renderedVisibleRoute = null
                measuredRootBounds = null; measuredCanvasBounds = null; composeWindowSize = IntSize.Zero
                sourceBinding?.let { player.releaseNativeVideoCanvasClip(it) }
            }
        }
        Layout(modifier = modifier.onGloballyPositioned { coordinates ->
            val origin = coordinates.positionInWindow()
            measuredRootBounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            queueNativeClip()
        }, content = {
            SwingPanel(factory = { player.surface }, background = Color.Black,
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val origin = coordinates.positionInWindow()
                    measuredCanvasBounds = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                    queueNativeClip()
                })
        }) { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
            val target = viewport?.takeIf { it.route === visibleRoute }
            val placement = resolveDesktopWindowsNativeVideoPlacement(target?.renderBounds, rootOriginInWindow)
            // Measure the FULL render bounds, including negative placement or
            // overscan. The independent native region crops the actual HWND.
            val panel = measurables.single().measure(Constraints.fixed(placement?.width ?: 1, placement?.height ?: 1))
            layout(width, height) { panel.place(placement?.left ?: 0, placement?.top ?: 0) }
        }
    }
}

@Composable internal fun rememberDesktopWindowsNativeVideoSurface(player: MpvPlayer): DesktopWindowsNativeVideoSurface =
    remember(player) { DesktopWindowsNativeVideoSurface(player) }

internal val LocalDesktopWindowsNativeVideoSurface = staticCompositionLocalOf<DesktopWindowsNativeVideoSurface?> { null }
