package com.bilipai.desktop.ui

import java.awt.AWTEvent
import java.awt.Color
import java.awt.EventQueue
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

/** Current-process AWT lifecycle of this actual Root window, including its OWN modal descendants. */
internal class DesktopHomeActualWindowBackground(private val root: Window,
    private val isCurrent: () -> Boolean) : DesktopHomeWindowBackgroundPort, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<DesktopHomeWindowBackgroundPort.Listener>()
    @Volatile private var background = computeBackground()
    override val isInBackground: Boolean get() = closed.get() || background
    private val events = AWTEventListener { event ->
        if (event is WindowEvent && belongsToRoot(event.window)) EventQueue.invokeLater { update() }
    }
    init { Toolkit.getDefaultToolkit().addAWTEventListener(events, AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_STATE_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK) }
    private fun belongsToRoot(window: Window?): Boolean {
        var candidate = window
        while (candidate != null) { if (candidate === root) return true; candidate = candidate.owner }
        return false
    }
    private fun computeBackground(): Boolean = !isCurrent() || !root.isDisplayable || !root.isVisible ||
        (root is Frame && (root.extendedState and Frame.ICONIFIED) != 0) ||
        !belongsToRoot(KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow)
    private fun update() {
        if (closed.get()) return
        val next = computeBackground()
        if (next != background) { background = next; listeners.forEach { if (!closed.get() && isCurrent()) { if (next) it.onEnterBackground() else it.onEnterForeground() } } }
    }
    override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) {
        if (!closed.get()) listeners += listener
    }
    override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) { listeners -= listener }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(events)
            listeners.forEach { it.onEnterBackground() }; listeners.clear()
        }
    }
}

/** Actual per-window local JVM metric state. No Firebase/Perfetto/Android JankStats transport. */
internal class DesktopHomeActualMetrics(private val isCurrent: () -> Boolean,
    private val logger: Logger) : DesktopHomeMetricSink, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val values = ConcurrentHashMap<String, String>()
    val holder = DesktopHomeMetricHolder(this)
    val debugEnabled: Boolean get() = logger.isLoggable(Level.FINE)
    override fun putState(name: String, value: String) {
        if (!closed.get() && isCurrent()) { values[name] = value; logger.log(Level.FINE, "Home metric {0}={1}", arrayOf(name, value)) }
    }
    override fun removeState(name: String) { if (!closed.get() && isCurrent()) values.remove(name) }
    fun startFrameClock(scope: CoroutineScope): Job = scope.launch {
        var previous = 0L
        while (!closed.get() && isCurrent()) {
            withFrameNanos { actual ->
                if (previous > 0 && debugEnabled) putState("ComposeFrameIntervalNanos", (actual - previous).toString())
                previous = actual
            }
        }
    }
    override fun close() { closed.set(true); values.clear() }
}

/** Explicit Windows decorated-client mapping for Android edge-to-edge/system-bar calls. */
internal class DesktopHomeWindowsClientPolicy(private val window: Window, private val isCurrent: () -> Boolean,
    private val clientBackground: () -> Color, private val metrics: DesktopHomeMetricSink) {
    fun ensureEdgeToEdge() {
        if (isCurrent()) metrics.putState("HomeViewportPolicy", "WINDOWS_DECORATED_CLIENT_AREA")
    }
    fun applyHomeSystemBars(statusBarDark: Boolean, backgroundIsLight: Boolean) {
        // Windows has neither Android bar. The actual client background is the current Root theme color.
        EventQueue.invokeLater {
            if (isCurrent()) {
                window.background = clientBackground()
                metrics.putState("AndroidBarPolicy", "WINDOWS_CLIENT_BACKGROUND(statusDark=$statusBarDark,backgroundIsLight=$backgroundIsLight)")
            }
        }
    }
}

internal fun desktopHomeActualPlatform(background: DesktopHomeWindowBackgroundPort,
    metrics: DesktopHomeActualMetrics, homeGraphicsLayerCaptureReady: Boolean): DesktopHomePlatform {
    val effects = desktopDetailRenderEffectsSupported()
    return DesktopHomePlatform(
        // The existing Windows consumer has no ThanosEffectView/GLSurface renderer.
        // Original Home uses its immediate completion path; no particle support is claimed.
        supportsNativeParticleDissolve = false,
        supportsHomeChromeLiquidGlass = effects && homeGraphicsLayerCaptureReady,
        supportsDirectHazeLiquidGlassFallback = effects,
        legacyTopChromeSafetyGapRequired = false, // Actual Windows client area has no Android status-bar inset.
        supportsRenderEffectBackedHaze = effects,
        recreateHazeOnResume = false, // No Android surface/background invalidation workaround is required by the Skiko client renderer.
        background = background,
        deviceCornerRadiusPx = 0f, // Explicit rectangular CLIENT viewport; physical monitor corner API is not available.
        debugFrameMetricsEnabled = metrics.debugEnabled,
    )
}
