package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion
import com.bilipai.desktop.appearance.DesktopWindowsUxCommand
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.appearance.LocalDesktopWindowsSystemDensity
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The frame of the desktop shell (spec 3.1 and 3.4): the rail on the left, the content column on the right with the
 * toast host over it and the now-playing strip under it.
 *
 * The content slot has exactly one call site, at a fixed place in the tree, whatever the rail does: the rail is shown,
 * hidden, expanded, collapsed or the classic block, and the content composition below keeps its place, so the retained
 * Home root and the NavDisplay are never remounted by a rail change (test_ux_rail_source.py counts the call sites). What
 * sits left of the content is a slot of the caller, which receives the rail state; the caller owns the classic block.
 *
 * The frame also provides, once for everything below it, the shell insets (the rail footprint), the toaster, and the
 * reduced-motion flag of the kit, and it shows the zoom toast after a zoom change. It adds the toast host beside the Root
 * SnackbarHost, it does not replace it.
 */

/**
 * What the rail slot of the frame is told: the mode in force, what the rail shows, the way to write a new mode, and the
 * toggle. The toggle (the hamburger, and the collapse and expand entries of the menu; the Ctrl+B of the shell shortcut host uses it too)
 * is decided by the frame: it writes the opposite explicit mode, or, when that would change nothing on screen because the
 * window is too narrow for the expanded rail, it writes nothing and tells the user so.
 */
internal class DesktopWindowsShellRail(
    val mode: DesktopWindowsUxRailMode,
    val presentation: DesktopRailPresentation,
    val setMode: (DesktopWindowsUxRailMode) -> Unit,
    val toggle: () -> Unit,
)

/** Values the frame carries from one composition to the next without being state: the previous rail presentation (the hysteresis memory), the last zoom read and the time of the last rail write. */
private class DesktopShellFrameMemory {
    var presentation: DesktopRailPresentation? = null
    var zoomPercent: Int? = null
    var railWriteMs: Long? = null
}

private fun desktopShellNowMs(): Long = System.nanoTime() / 1_000_000L

@Composable
internal fun DesktopWindowsShellFrame(
    railVisible: Boolean,
    nowPlayingVisible: Boolean,
    rail: @Composable (DesktopWindowsShellRail) -> Unit,
    nowPlaying: @Composable (DesktopWindowsShellRail) -> Unit,
    content: @Composable () -> Unit,
) {
    val ux = LocalDesktopWindowsUx.current
    val settings by ux.settings.collectAsState()
    val toaster = rememberDesktopToastController()
    val memory = remember { DesktopShellFrameMemory() }
    val reducedMotion = rememberSystemReduceMotion()

    // The rail presentation: the pure mode rule over the window width, with the previous presentation as its memory.
    val density = LocalDensity.current.density
    val windowWidthDp = (LocalWindowInfo.current.containerSize.width / density).toInt()
    val presentation = desktopRailPresentation(settings.railMode, windowWidthDp, memory.presentation)
    SideEffect { memory.presentation = presentation }
    val footprintDp = if (railVisible) presentation.footprintDp else 0

    // A mode change is written through the flags store, and a toast says when it was not saved. A refused command (nothing owns
    // the store, or it is closed) changes nothing at all. A write error that follows within the window is the rail's own save
    // problem, and the controller keeps the mode in force for this session. The published error is one value for every failure,
    // so a failure after a failure emits nothing: a write that finds an error already standing looks at it again after a short
    // wait, and shows the toast if the error is still there.
    val scope = rememberCoroutineScope()
    val setMode: (DesktopWindowsUxRailMode) -> Unit = { mode ->
        memory.railWriteMs = desktopShellNowMs()
        val standing = ux.writeError.value != null
        if (!ux.submit(DesktopWindowsUxCommand.SetRailMode(mode))) {
            toaster.show(DesktopShellStrings.RAIL_SAVE_FAILED)
        } else if (standing) {
            scope.launch {
                delay(DESKTOP_RAIL_SAVE_RECHECK_MS)
                if (ux.writeError.value != null) toaster.show(DesktopShellStrings.RAIL_SAVE_FAILED)
            }
        }
    }
    LaunchedEffect(ux, toaster) {
        ux.writeError.collect { problem ->
            if (desktopRailWriteFailureShown(memory.railWriteMs, desktopShellNowMs(), problem != null)) {
                toaster.show(DesktopShellStrings.RAIL_SAVE_FAILED)
            }
        }
    }

    // The toggle writes the opposite mode only where that changes what the rail shows. In a window too narrow for the expanded
    // rail (the default window is one) writing EXPANDED would change nothing on screen and then leave the toggle without a
    // target, so the toggle says why instead. The classic block has no toggle.
    val toggle: () -> Unit = {
        val target = desktopRailToggleMode(settings.railMode, presentation)
        if (target != null && desktopRailToggleEffective(settings.railMode, presentation, windowWidthDp)) {
            setMode(target)
        } else if (presentation != DesktopRailPresentation.CLASSIC) {
            toaster.show(DesktopShellStrings.RAIL_TOO_NARROW)
        }
    }

    // The zoom toast: the percent is read back from the scaled and the system density, so no controller is needed here.
    val zoomPercent = desktopZoomPercent(density, LocalDesktopWindowsSystemDensity.current?.density)
    LaunchedEffect(zoomPercent) {
        val shown = desktopZoomToastPercent(memory.zoomPercent, zoomPercent)
        if (zoomPercent != null) memory.zoomPercent = zoomPercent
        if (shown != null) toaster.show(DesktopShellStrings.zoomToast(shown))
    }

    val shell = DesktopWindowsShellRail(settings.railMode, presentation, setMode, toggle)

    // Slice S2: the shell shortcut host. The frame lends it the rail toggle (Ctrl+B) and the two panes that F6 cycles; the rail
    // slot and the content box are focus groups that the pane modifier names, and the mouse Back button is read on the whole frame.
    // The wrappers are plain modifiers and one box around the rail slot: content() keeps its one call site and its place.
    val shortcuts = LocalDesktopShellShortcutHost.current
    val railPane = remember { FocusRequester() }
    val contentPane = remember { FocusRequester() }
    DesktopShellFrameBinding(railVisible, presentation != DesktopRailPresentation.CLASSIC, toggle, railPane, contentPane)
    CompositionLocalProvider(
        LocalDesktopKitReducedMotion provides reducedMotion,
        LocalDesktopToaster provides toaster,
        LocalDesktopShellInsets provides DesktopShellInsets(footprintDp),
    ) {
        Row(Modifier.fillMaxSize().desktopShellBackButton(shortcuts)) {
            if (railVisible) Box(Modifier.fillMaxHeight().desktopShellPane(shortcuts, DesktopShellPane.RAIL, railPane)) { rail(shell) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // The toast host is over the content and ends where the now-playing strip begins, so a toast is always above it.
                Box(Modifier.weight(1f).fillMaxWidth().desktopShellPane(shortcuts, DesktopShellPane.CONTENT, contentPane)) {
                    content()
                    DesktopToastHost(toaster)
                }
                if (nowPlayingVisible) nowPlaying(shell)
            }
        }
    }
}
