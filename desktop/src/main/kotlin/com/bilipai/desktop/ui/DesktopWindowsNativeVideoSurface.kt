package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Constraints
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.player.MpvPlayer
import kotlin.math.roundToInt
import java.awt.EventQueue

/** Root-owned geometry for the one Windows Canvas. The SwingPanel is rendered at
 * one fixed Root callsite: leaves report pixels, never move/reparent the native
 * component. Its single presentation receipt only recognizes a returning
 * actual navigation entry; native/account/source admission remains authoritative.
 */
internal class DesktopWindowsNativeVideoSurface(val player: MpvPlayer) {
    private data class Viewport(val route: BiliPaiNavKey.VideoDetail, val lease: Any, val bounds: Rect)
    private var viewport by mutableStateOf<Viewport?>(null)
    private class Presentation(
        val route: BiliPaiNavKey.VideoDetail,
        val assembly: DesktopOriginalVideoOwnerAssembly,
        val excludedPrior: DesktopOriginalVideoAcceptedPublication?,
        var accepted: DesktopOriginalVideoAcceptedPublication? = null,
    )
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

    fun reportViewport(route: BiliPaiNavKey.VideoDetail, lease: Any, bounds: Rect) {
        if (bounds.width <= 0f || bounds.height <= 0f ||
            !bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite()) return
        val previous = viewport
        if (previous?.route === route && previous.lease === lease && previous.bounds == bounds) return
        viewport = Viewport(route, lease, bounds)
    }

    fun releaseViewport(route: BiliPaiNavKey.VideoDetail, lease: Any) {
        val previous = viewport
        // Disposing an outgoing entry must not erase a newer entry's geometry.
        if (previous?.route === route && previous.lease === lease) viewport = null
    }

    @Composable fun Render(
        expectedPlayer: MpvPlayer,
        visibleRoute: BiliPaiNavKey.VideoDetail?,
        rootOriginInWindow: Offset?,
        modifier: Modifier,
    ) {
        check(expectedPlayer === player) { "Native surface belongs to another player" }
        Layout(modifier = modifier, content = {
            SwingPanel(factory = { player.surface }, background = Color.Black)
        }) { measurables, constraints ->
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
            val target = viewport?.takeIf { it.route === visibleRoute }
            val origin = rootOriginInWindow
            val bounds = if (target != null && origin != null) target.bounds else null
            // Both coordinates are Compose window pixels. Placement stays in
            // pixels, avoiding a second system-DPI/application-density scale.
            val left = bounds?.let { (it.left - origin!!.x).roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0)) } ?: 0
            val top = bounds?.let { (it.top - origin!!.y).roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0)) } ?: 0
            val right = bounds?.let { (it.right - origin!!.x).roundToInt().coerceIn(left + 1, width.coerceAtLeast(left + 1)) } ?: 1
            val bottom = bounds?.let { (it.bottom - origin!!.y).roundToInt().coerceIn(top + 1, height.coerceAtLeast(top + 1)) } ?: 1
            // Covered pages keep the same peer alive at 1px. They never take
            // the existing Canvas from another parent or the PiP controller.
            val panel = measurables.single().measure(Constraints.fixed(right - left, bottom - top))
            layout(width, height) { panel.place(left, top) }
        }
    }
}

@Composable internal fun rememberDesktopWindowsNativeVideoSurface(player: MpvPlayer): DesktopWindowsNativeVideoSurface =
    remember(player) { DesktopWindowsNativeVideoSurface(player) }

internal val LocalDesktopWindowsNativeVideoSurface = staticCompositionLocalOf<DesktopWindowsNativeVideoSurface?> { null }
