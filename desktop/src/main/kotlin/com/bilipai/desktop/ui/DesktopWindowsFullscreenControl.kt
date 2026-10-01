package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsDevice
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.AffineTransform
import javax.swing.SwingUtilities

/** Uses the same Compose placement setter. On Windows, fullscreen exit can restore
 * the native peer in unscaled units while AWT still reports the original bounds.
 * An equal setBounds is a no-op in Component.reshape; two real bounds changes on
 * one EDT turn make the existing AWT peer reapply its own DPI conversion. */
internal class DesktopWindowsFullscreenControl(
    private val window: ComposeWindow,
    private val state: WindowState,
) : AutoCloseable {
    private var closed = false
    private var revision = 0L
    private var floatingBounds: Rectangle? = null
    private var floatingDevice: GraphicsDevice? = null
    private var floatingMonitorBounds: Rectangle? = null
    private var floatingTransform: AffineTransform? = null
    private var fullscreenRefreshRevision: Long? = null
    private val listener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = placementChanged()
        override fun componentMoved(event: ComponentEvent) = placementChanged()
        override fun componentShown(event: ComponentEvent) = placementChanged()
    }

    init {
        check(SwingUtilities.isEventDispatchThread())
        window.addComponentListener(listener)
    }

    fun setFullscreen(enabled: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        val destination = if (enabled) WindowPlacement.Fullscreen else WindowPlacement.Floating
        if (state.placement == destination) return
        revision++
        fullscreenRefreshRevision = if (enabled && window.isShowing) revision else null
        if (enabled) {
            floatingBounds = if (state.placement == WindowPlacement.Floating && window.isShowing)
                Rectangle(window.bounds) else null
            val configuration = window.graphicsConfiguration
            floatingDevice = configuration.device
            floatingMonitorBounds = Rectangle(configuration.bounds)
            floatingTransform = AffineTransform(configuration.defaultTransform)
        }
        state.placement = destination
        if (!enabled) placementChanged()
    }

    fun toggle() = setFullscreen(state.placement != WindowPlacement.Fullscreen)

    fun placementChanged() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        if (state.placement == WindowPlacement.Fullscreen) {
            val requestedRevision = fullscreenRefreshRevision ?: return
            SwingUtilities.invokeLater {
                if (closed || requestedRevision != revision || fullscreenRefreshRevision != requestedRevision ||
                    !window.isShowing || state.placement != WindowPlacement.Fullscreen ||
                    window.placement != WindowPlacement.Fullscreen) return@invokeLater
                fullscreenRefreshRevision = null
                if (matchesCapturedConfiguration()) reapplyCurrentPeerBounds()
            }
            return
        }
        val captured = floatingBounds ?: return
        if (closed || state.placement != WindowPlacement.Floating) return
        val capturedRevision = revision
        SwingUtilities.invokeLater {
            if (closed || capturedRevision != revision || floatingBounds !== captured ||
                !window.isShowing || state.placement != WindowPlacement.Floating ||
                window.placement != WindowPlacement.Floating) return@invokeLater
            floatingBounds = null
            if (matchesCapturedConfiguration()) reapplyCurrentPeerBounds()
        }
    }

    private fun matchesCapturedConfiguration(): Boolean {
        val configuration = window.graphicsConfiguration
        return configuration.device === floatingDevice && configuration.bounds == floatingMonitorBounds &&
            configuration.defaultTransform == floatingTransform
    }

    private fun reapplyCurrentPeerBounds() {
        // Preserve any user move/resize accepted before this queued EDT turn.
        val desired = Rectangle(window.bounds)
        if (desired.width <= 0 || desired.height <= 0) return
        // A real same-peer resize updates cached AWT insets after native fullscreen entry,
        // as well as reapplying the peer's DPI conversion after fullscreen exit.
        window.bounds = Rectangle(desired.x + 1, desired.y + 1, desired.width + 1, desired.height + 1)
        window.bounds = desired
        window.invalidate()
        window.validate()
    }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        revision++
        floatingBounds = null
        floatingDevice = null
        floatingMonitorBounds = null
        floatingTransform = null
        fullscreenRefreshRevision = null
        window.removeComponentListener(listener)
    }
}

@Composable
internal fun rememberDesktopWindowsFullscreenControl(
    window: ComposeWindow,
    state: WindowState,
): DesktopWindowsFullscreenControl {
    val control = remember(window, state) { DesktopWindowsFullscreenControl(window, state) }
    DisposableEffect(control) { onDispose { control.close() } }
    LaunchedEffect(control, state.placement) { control.placementChanged() }
    return control
}
