package com.bilipai.desktop.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.roundToInt

/*
 * Window bounds persistence (spec 3.5, slice W3, hunk RH8): the window opens where and how large it was, and keeps
 * the bounds of the next start up to date.
 *
 * The windows_ux store of slice X0 lives in the shell, which does not exist when the window is created, so this file
 * does not use its controller. It reads and writes the same namespace with the pure functions of that store
 * (decodeDesktopWindowsUx, desktopWindowsUxEdits, desktopWindowsUxEffectiveEdits) and the same atomic write of the
 * plugin store. Only the window_x, window_y, window_width, window_height and window_maximized keys are written. The
 * zoom namespace is only read (for the minimum window size), never written.
 *
 * The decisions are in DesktopWindowsUxWindowBoundsModel.kt. This file is the thin part that touches AWT, the
 * Compose window state and the disk. Nothing in it throws.
 */

/** Reads and writes the window bounds group of the windows_ux namespace. Never throws. */
internal class DesktopWindowsUxWindowBoundsStore(private val store: DesktopPluginStore) {
    /** The saved bounds, or null when none were saved or the saved group is not usable. */
    fun load(): DesktopWindowsUxWindowBounds? = try {
        decodeDesktopWindowsUx(DesktopPreferenceSnapshot(store.preferences(WINDOWS_UX_NAMESPACE))).windowBounds
    } catch (_: Exception) {
        null
    }

    /**
     * Writes the bounds when they differ from what is stored. True when the file holds these bounds afterwards (also
     * when nothing had to be written), false when the write was refused or failed. Bounds that are not sane are
     * never written.
     */
    fun save(bounds: DesktopWindowsUxWindowBounds): Boolean = try {
        val edits = desktopWindowsUxEdits(DesktopWindowsUxCommand.SetWindowBounds(bounds))
        if (edits.isEmpty()) {
            false
        } else {
            val changes = desktopWindowsUxEffectiveEdits(store.preferences(WINDOWS_UX_NAMESPACE), edits)
            if (changes.isNotEmpty()) store.updateFromSnapshot(WINDOWS_UX_NAMESPACE) { edits }
            true
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * The monitors as AWT reports them now. Empty on a headless JVM or when AWT cannot answer (the default screen device
 * throws an AWTError, not an exception, when no screen exists). Never throws.
 */
internal fun desktopWindowsUxProbeScreens(): List<DesktopWindowsUxScreen> = try {
    if (GraphicsEnvironment.isHeadless()) {
        emptyList()
    } else {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val toolkit = Toolkit.getDefaultToolkit()
        val primary = environment.defaultScreenDevice
        environment.screenDevices.map { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            val insets = toolkit.getScreenInsets(configuration)
            DesktopWindowsUxScreen(
                usable = DesktopWindowsUxRect(
                    x = bounds.x + insets.left,
                    y = bounds.y + insets.top,
                    width = bounds.width - insets.left - insets.right,
                    height = bounds.height - insets.top - insets.bottom),
                primary = device == primary)
        }
    }
} catch (_: Exception) {
    emptyList()
} catch (_: java.awt.AWTError) {
    emptyList()
}

/** The app zoom the minimum window size follows. Read only; the default when the setting cannot be read. */
private fun desktopWindowsUxStoredZoomPercent(store: DesktopPluginStore): Int = try {
    decodeDesktopWindowsDisplay(DesktopPreferenceSnapshot(store.preferences(WINDOWS_DISPLAY_NAMESPACE))).percent
} catch (_: Exception) {
    WINDOWS_DISPLAY_DEFAULT_PERCENT
}

/** The state as a pure sample, or null while the platform has not reported a position and a size yet. */
internal fun desktopWindowsUxSampleOf(state: WindowState): DesktopWindowsUxWindowSample? {
    val x = state.position.x.value
    val y = state.position.y.value
    val width = state.size.width.value
    val height = state.size.height.value
    if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null
    val placement = when (state.placement) {
        WindowPlacement.Floating -> DesktopWindowsUxPlacement.FLOATING
        WindowPlacement.Maximized -> DesktopWindowsUxPlacement.MAXIMIZED
        WindowPlacement.Fullscreen -> DesktopWindowsUxPlacement.FULLSCREEN
    }
    return DesktopWindowsUxWindowSample(
        placement = placement,
        minimized = state.isMinimized,
        x = x.roundToInt(),
        y = y.roundToInt(),
        width = width.roundToInt(),
        height = height.roundToInt())
}

/**
 * The window state Main.kt hands to the Window, restored from the saved bounds (clamped to the monitors that exist
 * now) and kept up to date while the window lives. Without saved bounds this is exactly the earlier state: floating,
 * 1360 by 900, position chosen by the platform.
 */
@Composable
internal fun rememberDesktopWindowsUxWindowState(store: DesktopPluginStore, registerFlush: ((() -> Unit) -> Unit) = {}): WindowState {
    val bounds = remember(store) { DesktopWindowsUxWindowBoundsStore(store) }
    val startup = remember(bounds) {
        desktopWindowsUxStartup(bounds.load(), desktopWindowsUxProbeScreens(), desktopWindowsUxStoredZoomPercent(store))
    }
    val plan = startup.plan
    val planX = plan.x
    val planY = plan.y
    val state = rememberWindowState(
        placement = if (plan.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (planX != null && planY != null) WindowPosition(planX.dp, planY.dp) else WindowPosition.PlatformDefault,
        size = DpSize(plan.width.dp, plan.height.dp))
    val tracker = remember(state) { DesktopWindowsUxWindowTracker(startup.seed) }
    val flush: () -> Unit = remember(state, tracker, bounds) {
        {
            desktopWindowsUxSampleOf(state)?.let(tracker::accept)
            // A refused write must not abort shutdown; the operation still reports itself.
            tracker.bounds()?.let { if (!bounds.save(it)) System.err.println("窗口位置保存失败：退出前写入被拒绝") }
        }
    }
    androidx.compose.runtime.SideEffect { registerFlush(flush) }
    DesktopWindowsUxWindowBoundsEffect(state, bounds, tracker)
    return state
}

/**
 * Saves the bounds a settle time after the window state stopped changing (a drag changes it continuously).
 *
 * There is deliberately no save when the composition ends. The shell retires the plugin store (freezeWrites) while it
 * shuts down, and that happens before the application composition is disposed, so a write at that point is refused.
 * The price is that a change made within the settle time before the application exits is not saved; the bounds of
 * the last settled state are.
 */
@Composable
private fun DesktopWindowsUxWindowBoundsEffect(
    state: WindowState,
    bounds: DesktopWindowsUxWindowBoundsStore,
    tracker: DesktopWindowsUxWindowTracker,
) {
    LaunchedEffect(state, bounds, tracker) {
        snapshotFlow { desktopWindowsUxSampleOf(state) }.collectLatest { sample ->
            if (sample == null) return@collectLatest
            tracker.accept(sample)
            delay(WINDOWS_UX_WINDOW_SETTLE_MS)
            val next = tracker.bounds()
            if (next != null) withContext(Dispatchers.IO) { check(bounds.save(next)) { "窗口位置保存失败" } }
        }
    }
}
