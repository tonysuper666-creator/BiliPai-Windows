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
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private interface DesktopCommandWindowRegion : StdCallLibrary {
    fun SetWindowRgn(hwnd: Pointer, region: Pointer, redraw: Boolean): Int
}
private interface DesktopCommandGdiRegion : StdCallLibrary {
    fun CreateRectRgn(left: Int, top: Int, right: Int, bottom: Int): Pointer?
    fun DeleteObject(region: Pointer): Boolean
}
private object DesktopEmptyCommandRegion {
    private val user32 = Native.load("user32", DesktopCommandWindowRegion::class.java, W32APIOptions.DEFAULT_OPTIONS)
    private val gdi32 = Native.load("gdi32", DesktopCommandGdiRegion::class.java, W32APIOptions.DEFAULT_OPTIONS)
    fun apply(window: Window) {
        if (!window.isDisplayable) return
        val region = checkNotNull(gdi32.CreateRectRgn(0, 0, 0, 0)) { "Unable to create empty command window region" }
        if (user32.SetWindowRgn(Native.getWindowPointer(window), region, true) == 0) {
            gdi32.DeleteObject(region)
            error("Unable to apply empty command window region")
        }
        // Successful SetWindowRgn transfers HRGN ownership to Windows.
    }
}

private val LocalDesktopCommandHitRegions = staticCompositionLocalOf<DesktopCommandHitRegions?> { null }

/** Native hit region uses actual laid-out cards, rather than reimplementing layout policy. */
internal fun Modifier.desktopCommandHitRegion(id: String): Modifier = composed {
    val regions = LocalDesktopCommandHitRegions.current
    val token = remember(id, regions) { Any() }
    DisposableEffect(token) { onDispose { regions?.remove(token) } }
    onGloballyPositioned { coordinates -> regions?.update(token, coordinates.boundsInWindow()) }
}

private class DesktopCommandHitRegions(private val apply: (List<Rect>, Boolean) -> Unit) {
    private val values = linkedMapOf<Any, Rect>()
    private var closed = false
    private val modals = linkedSetOf<Any>()
    fun setModal(token: Any, present: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        val changed = if (present) modals.add(token) else modals.remove(token)
        if (changed) apply(values.values.toList(), modals.isNotEmpty())
    }
    fun update(token: Any, rect: Rect) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        if (values[token] != rect) { values[token] = rect; apply(values.values.toList(), modals.isNotEmpty()) }
    }
    fun remove(token: Any) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        if (values.remove(token) != null) apply(values.values.toList(), modals.isNotEmpty())
    }
    fun close() { closed = true; values.clear(); modals.clear() }
}

/** Preserve the original modal's scrim, input and drawing area on the same
 * canvas. The overlay remains mounted when its timed cards expire. */
@Composable
internal fun DesktopCommandModalRegion(present: Boolean) {
    val regions = LocalDesktopCommandHitRegions.current
    val token = remember(regions) { Any() }
    DisposableEffect(regions, present) {
        regions?.setModal(token, present)
        onDispose { regions?.setModal(token, false) }
    }
}

/** AWT owns/releases the native region backing Window.shape. Transparent empty content keeps
 * the original composition alive, including an already-open DynamicVoteDialog. */
private class DesktopCommandPopupWindow(
    private val owner: Window,
    private val anchorComponent: Component,
    private val onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    private val onWindowAvailability: (Any, Boolean) -> Unit,
    private val decorative: Boolean = false,
    private val ownsPresentation: (() -> Boolean)? = null,
    private val onDecorativeRejection: (() -> Unit)? = null,
) : AutoCloseable {
    init { require(!decorative || (ownsPresentation != null && onDecorativeRejection != null)) }
    private var closed = false
    private var requested = IntSize.Zero
    private var presented = false
    private var rectangles = emptyList<Rect>()
    private var popup: ComposeDialog? = null
    private var modal = false
    private val windowIdentity = Any()
    private var windowReady = false
    private fun publishWindowAvailability(ready: Boolean, force: Boolean = false) {
        if (force || windowReady != ready) {
            windowReady = ready
            popup?.let { onNativeWindowAvailability?.invoke(it, ready) }
            onWindowAvailability(windowIdentity, ready)
        }
    }
    private val popupVisibilityListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) {
            val shown = popup ?: return
            // Skiko applies this dialog's non-fullscreen mode during componentShown.
            // Deliver the original owner's pending mode only after those listeners finish.
            SwingUtilities.invokeLater {
                if (!closed && popup === shown && shown.isShowing && owner.isShowing &&
                    anchorComponent.isShowing && SwingUtilities.getWindowAncestor(anchorComponent) === owner) {
                    if (decorative && (!checkNotNull(ownsPresentation).invoke() ||
                        !DesktopDecorativeWindowStyle.applyTo(shown))) {
                        publishWindowAvailability(false, force = true); shown.isVisible = false
                        checkNotNull(onDecorativeRejection).invoke()
                    } else publishWindowAvailability(true)
                }
            }
        }
        override fun componentHidden(event: ComponentEvent) {
            if (!closed && event.component === popup) publishWindowAvailability(false)
        }
    }
    val regions = DesktopCommandHitRegions { cards, modalVisible ->
        rectangles = cards
        val wasModal = modal
        modal = modalVisible
        popup?.focusableWindowState = modalVisible
        applyShape()
        if (modalVisible && !wasModal) popup?.let { it.toFront(); it.requestFocus() }
    }
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
    private val hierarchyListener = HierarchyListener { updateGeometry() }
    private val hierarchyBoundsListener = object : HierarchyBoundsAdapter() {
        override fun ancestorMoved(event: HierarchyEvent) = updateGeometry()
        override fun ancestorResized(event: HierarchyEvent) = updateGeometry()
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
            // Actual AWT anchor gives valid geometry independently of occluded Compose layout.
            // Start with no input region, while keeping the composition mounted.
            if (!decorative) shape = Area()
            addComponentListener(popupVisibilityListener)
            compositionLocalContext = context
            setContent { CompositionLocalProvider(LocalDesktopCommandHitRegions provides if (decorative) null else regions) { content() } }
        }
        owner.addComponentListener(moveListener)
        owner.addWindowListener(windowListener)
        anchorComponent.addComponentListener(moveListener)
        anchorComponent.addHierarchyListener(hierarchyListener)
        anchorComponent.addHierarchyBoundsListener(hierarchyBoundsListener)
    }
    fun update(context: CompositionLocalContext, size: IntSize, presented: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        popup?.compositionLocalContext = context
        requested = size
        this.presented = presented
        updateGeometry()
    }
    private fun updateGeometry() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        val window = popup ?: return
        val showing = presented && (!decorative || checkNotNull(ownsPresentation).invoke()) && owner.isShowing && (owner !is Frame || owner.extendedState and Frame.ICONIFIED == 0) &&
            anchorComponent.isShowing && SwingUtilities.getWindowAncestor(anchorComponent) === owner &&
            requested.width > 0 && requested.height > 0 && anchorComponent.width > 0 && anchorComponent.height > 0
        if (!showing) { publishWindowAvailability(false); window.isVisible = false; return }
        val location = anchorComponent.locationOnScreen
        // AWT component geometry is already in logical screen units, including DPI conversion.
        val rectangle = Rectangle(location.x, location.y, anchorComponent.width, anchorComponent.height)
        if (window.bounds != rectangle) window.bounds = rectangle
        if (window.isAlwaysOnTop != owner.isAlwaysOnTop) window.isAlwaysOnTop = owner.isAlwaysOnTop
        if (decorative) {
            // AWT applies layered transparency during show, including restore.
            // Prepare input policy while hidden without claiming availability;
            // componentShown requires all native flags after Skiko's listeners.
            if (!window.isDisplayable) window.addNotify()
            if (!checkNotNull(ownsPresentation).invoke() || !(if (window.isShowing)
                DesktopDecorativeWindowStyle.applyTo(window) else DesktopDecorativeWindowStyle.prepareToShow(window))) {
                publishWindowAvailability(false, force = true); window.isVisible = false
                checkNotNull(onDecorativeRejection).invoke(); return
            }
            if (!checkNotNull(ownsPresentation).invoke()) {
                publishWindowAvailability(false, force = true); window.isVisible = false
                checkNotNull(onDecorativeRejection).invoke(); return
            }
            if (!window.isVisible) window.isVisible = true
        } else {
            applyShape()
            if (!window.isVisible) { window.isVisible = true; applyShape() }
        }
    }
    private fun applyShape() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed || decorative) return
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
        if (modal) shape.add(Area(Rectangle(0, 0, window.width, window.height)))
        shape.intersect(Area(Rectangle(0, 0, window.width, window.height)))
        window.shape = shape
        if (shape.isEmpty) DesktopEmptyCommandRegion.apply(window)
    }
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        publishWindowAvailability(false)
        owner.removeComponentListener(moveListener)
        owner.removeWindowListener(windowListener)
        anchorComponent.removeComponentListener(moveListener)
        anchorComponent.removeHierarchyListener(hierarchyListener)
        anchorComponent.removeHierarchyBoundsListener(hierarchyBoundsListener)
        regions.close()
        popup?.removeComponentListener(popupVisibilityListener)
        popup?.dispose()
        popup = null
    }
}

/** Anchored native window above mpv. No active-command gate unmounts content. */
@Composable
internal fun DesktopShapedVideoCommandPopup(surfaceSize: IntSize, anchorComponent: Component, content: @Composable () -> Unit) {
    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, null, content)
}

@Composable
internal fun DesktopShapedVideoCommandPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, onWindowAvailability, null, content)
}

@Composable
internal fun DesktopShapedVideoCommandPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    content: @Composable () -> Unit,
) {
    DesktopShapedVideoCommandPopup(surfaceSize, anchorComponent, onWindowAvailability,
        onNativeWindowAvailability, true, content)
}

/** PiP hides this actual popup while retaining its original Section composition. */
@Composable
internal fun DesktopShapedVideoCommandPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    onWindowAvailability: ((Any, Boolean) -> Unit)?,
    onNativeWindowAvailability: ((Window, Boolean) -> Unit)?,
    presented: Boolean,
    content: @Composable () -> Unit,
) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
    val owner = LocalAwtWindow.current ?: return
    val context = currentCompositionLocalContext
    val latestContent by rememberUpdatedState(content)
    val latestWindowAvailability by rememberUpdatedState(onWindowAvailability)
    val latestNativeWindowAvailability by rememberUpdatedState(onNativeWindowAvailability)
    val density = LocalDensity.current
    val latestDensity by rememberUpdatedState(density)
    val host = remember(owner, anchorComponent) {
        DesktopCommandPopupWindow(owner, anchorComponent,
            onNativeWindowAvailability = { window, ready -> latestNativeWindowAvailability?.invoke(window, ready) },
            onWindowAvailability = { identity, ready -> latestWindowAvailability?.invoke(identity, ready) })
    }
    DisposableEffect(host) {
        host.create(context) {
            CompositionLocalProvider(LocalDensity provides latestDensity) {
                Box(Modifier.fillMaxSize()) { latestContent() }
            }
        }
        onDispose { host.close() }
    }
    SideEffect { host.update(context, surfaceSize, presented) }
}

/** Decorative-only extension of the existing owned carrier. Never commands,
 * dialogs or retry controls. The actual Leaf supplies confirmed immutable-source
 * metadata and the original factory account/entry admission. Native input pass-
 * through and visible pixels still require actual Main acceptance. */
@Composable
internal fun DesktopDecorativeVideoFeedbackPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    subject: com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot,
    ownsPresentation: () -> Boolean,
    onWindowAvailability: (Any, Boolean) -> Unit,
    onWindowRejected: () -> Unit,
    content: @Composable (windowAvailable: Boolean) -> Unit,
) {
    key(sourceOwner, subject) {
        DesktopDecorativeBrandSuccessPopup(surfaceSize, anchorComponent, sourceOwner, ownsPresentation,
            onWindowAvailability, onWindowRejected, content)
    }
}

/** Same carrier for the original Root business receipt; no fake video subject. */
@Composable
internal fun DesktopDecorativeBrandSuccessPopup(
    surfaceSize: IntSize,
    anchorComponent: Component,
    receipt: Any,
    ownsPresentation: () -> Boolean,
    onWindowAvailability: (Any, Boolean) -> Unit,
    onWindowRejected: () -> Unit,
    content: @Composable (windowAvailable: Boolean) -> Unit,
) {
    // Keep early exits outside Compose's inline key block. The current compiler
    // otherwise leaves a NON_LOCAL_RETURN marker with an illegal JVM method name.
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
    val owner = LocalAwtWindow.current ?: return
    key(receipt) {
        val context = currentCompositionLocalContext
        // Keyed source scope prevents an old disposal callback from writing the
        // successor's available state. The original lifecycle and Root foreground
        // providers travel through this existing CompositionLocalContext.
        var available by remember { mutableStateOf(false) }
        val latestCurrent by rememberUpdatedState(ownsPresentation)
        val latestContent by rememberUpdatedState(content)
        val latestAvailability by rememberUpdatedState(onWindowAvailability)
        val latestRejection by rememberUpdatedState(onWindowRejected)
        val density = LocalDensity.current
        val latestDensity by rememberUpdatedState(density)
        val host = remember(owner, anchorComponent) {
            DesktopCommandPopupWindow(owner, anchorComponent, onNativeWindowAvailability = null,
                onWindowAvailability = { identity, ready -> available = ready; latestAvailability(identity, ready) },
                decorative = true, ownsPresentation = { latestCurrent() },
                onDecorativeRejection = { latestRejection() })
        }
        DisposableEffect(host) {
            host.create(context) {
                CompositionLocalProvider(LocalDensity provides latestDensity) {
                    Box(Modifier.fillMaxSize()) { latestContent(available && latestCurrent()) }
                }
            }
            onDispose { host.close() }
        }
        SideEffect { host.update(context, surfaceSize, latestCurrent()) }
    }
}
