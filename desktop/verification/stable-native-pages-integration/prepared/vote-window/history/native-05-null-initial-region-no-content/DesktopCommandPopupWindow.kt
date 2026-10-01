@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val LocalDesktopCommandHitRegions = staticCompositionLocalOf<DesktopCommandHitRegions?> { null }

/** Native hit region uses actual laid-out cards, rather than reimplementing layout policy. */
internal fun Modifier.desktopCommandHitRegion(id: String): Modifier = composed {
    val regions = LocalDesktopCommandHitRegions.current
    val token = remember(id, regions) { Any() }
    DisposableEffect(token) { onDispose { regions?.remove(token) } }
    onGloballyPositioned { coordinates -> regions?.update(token, coordinates.boundsInWindow()) }
}

private class DesktopCommandHitRegions(private val apply: (List<Rect>) -> Unit) {
    private val values = linkedMapOf<Any, Rect>()
    private var closed = false
    fun update(token: Any, rect: Rect) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        if (values[token] != rect) { values[token] = rect; apply(values.values.toList()) }
    }
    fun remove(token: Any) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        if (values.remove(token) != null) apply(values.values.toList())
    }
    fun close() { closed = true; values.clear() }
}

/** AWT owns/releases the native region backing Window.shape. Transparent empty content keeps
 * the original composition alive, including an already-open DynamicVoteDialog. */
private class DesktopCommandPopupWindow(private val owner: Window) : AutoCloseable {
    private var closed = false
    private var anchor: Rect? = null
    private var requested = IntSize.Zero
    private var rectangles = emptyList<Rect>()
    private var popup: ComposeDialog? = null
    val regions = DesktopCommandHitRegions { rectangles = it; applyShape() }
    private val moveListener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = updateGeometry()
        override fun componentResized(event: ComponentEvent) = updateGeometry()
        override fun componentShown(event: ComponentEvent) = updateGeometry()
        override fun componentHidden(event: ComponentEvent) = updateGeometry()
    }
    private val windowListener = object : WindowAdapter() {
        override fun windowIconified(event: WindowEvent) = updateGeometry()
        override fun windowDeiconified(event: WindowEvent) = updateGeometry()
        override fun windowClosed(event: WindowEvent) = close()
    }
    fun create(context: CompositionLocalContext, content: @Composable () -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        check(!closed && popup == null)
        popup = ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isResizable = false
            isTransparent = true
            focusableWindowState = false
            isAutoRequestFocus = false
            type = Window.Type.POPUP
            // Keep initial geometry renderable until original cards have measured.
            // Windows alpha-zero layered pixels pass input without an empty region.
            shape = null
            compositionLocalContext = context
            setContent { CompositionLocalProvider(LocalDesktopCommandHitRegions provides regions) { content() } }
        }
        owner.addComponentListener(moveListener)
        owner.addWindowListener(windowListener)
    }
    fun update(context: CompositionLocalContext, bounds: Rect?, size: IntSize) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        popup?.compositionLocalContext = context
        anchor = bounds
        requested = size
        updateGeometry()
    }
    private fun updateGeometry() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        val window = popup ?: return
        val bounds = anchor
        val showing = owner.isShowing && (owner !is Frame || owner.extendedState and Frame.ICONIFIED == 0) &&
            bounds != null && requested.width > 0 && requested.height > 0
        if (!showing) { window.isVisible = false; return }
        val root = (owner as? RootPaneContainer)?.contentPane ?: owner
        if (!root.isShowing) { window.isVisible = false; return }
        val location = root.locationOnScreen
        val transform = owner.graphicsConfiguration.defaultTransform
        val rectangle = Rectangle(location.x + (bounds!!.left / transform.scaleX).roundToInt(),
            location.y + (bounds.top / transform.scaleY).roundToInt(),
            (requested.width / transform.scaleX).roundToInt().coerceAtLeast(1),
            (requested.height / transform.scaleY).roundToInt().coerceAtLeast(1))
        if (window.bounds != rectangle) window.bounds = rectangle
        if (window.isAlwaysOnTop != owner.isAlwaysOnTop) window.isAlwaysOnTop = owner.isAlwaysOnTop
        applyShape()
        if (!window.isVisible) window.isVisible = true
    }
    private fun applyShape() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        val window = popup ?: return
        val transform = window.graphicsConfiguration.defaultTransform
        val shape = Area()
        rectangles.forEach { r ->
            if (!r.isEmpty && r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite()) {
                val left = floor(r.left / transform.scaleX)
                val top = floor(r.top / transform.scaleY)
                shape.add(Area(Rectangle2D.Double(left, top,
                    ceil(r.right / transform.scaleX) - left, ceil(r.bottom / transform.scaleY) - top)))
            }
        }
        shape.intersect(Area(Rectangle(0, 0, window.width, window.height)))
        window.shape = if (shape.isEmpty) null else shape
    }
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        owner.removeComponentListener(moveListener)
        owner.removeWindowListener(windowListener)
        regions.close()
        popup?.dispose()
        popup = null
    }
}

/** Anchored native window above mpv. No active-command gate unmounts content. */
@Composable
internal fun DesktopShapedVideoCommandPopup(surfaceSize: IntSize, content: @Composable () -> Unit) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
    val owner = LocalAwtWindow.current ?: return
    val context = currentCompositionLocalContext
    val latestContent by rememberUpdatedState(content)
    val density = LocalDensity.current
    val latestDensity by rememberUpdatedState(density)
    val host = remember(owner) { DesktopCommandPopupWindow(owner) }
    var bounds by remember(owner) { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInWindow() })
    DisposableEffect(host) {
        host.create(context) {
            CompositionLocalProvider(LocalDensity provides latestDensity) {
                Box(Modifier.fillMaxSize()) { latestContent() }
            }
        }
        onDispose { host.close() }
    }
    SideEffect { host.update(context, bounds, surfaceSize) }
}
