@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.appearance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.math.roundToInt

internal fun desktopWindowsSafeMinimumSize(systemDensity: Float, percent: Int, usable: Rectangle,
    awtScaleX: Double, awtScaleY: Double): Dimension {
    require(systemDensity.isFinite() && systemDensity > 0)
    require(percent in WINDOWS_DISPLAY_MIN_PERCENT..WINDOWS_DISPLAY_MAX_PERCENT)
    require(awtScaleX.isFinite() && awtScaleX > 0 && awtScaleY.isFinite() && awtScaleY > 0)
    val physicalDensity = systemDensity * percent / 100f
    // Compose uses physical pixels; AWT minimumSize and monitor bounds use logical units.
    // Divide by the actual GC axis transform, so system DPI is never applied twice.
    return Dimension((960 * physicalDensity / awtScaleX).roundToInt().coerceIn(1, usable.width.coerceAtLeast(1)),
        (680 * physicalDensity / awtScaleY).roundToInt().coerceIn(1, usable.height.coerceAtLeast(1)))
}

/** The outer Main scope applies user scale once; theme typography still controls font choices. */
@Composable internal fun DesktopWindowsDisplayScaleScope(controller: DesktopWindowsDisplayScaleController,
    content: @Composable () -> Unit) {
    val system = LocalDensity.current
    val settings by controller.settings.collectAsState()
    CompositionLocalProvider(LocalDesktopWindowsDisplayScale provides controller,
        LocalDesktopWindowsSystemDensity provides system,
        LocalDensity provides desktopWindowsScaledDensity(system, settings.percent)) {
        // Pinned Compose mediator does not ignore already-consumed AWT mouse wheel events.
        // Prevent only this gesture's normal content scroll; all mutation is in the owned AWT path.
        Box(Modifier.fillMaxSize().onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
            if (event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isAltPressed &&
                !event.keyboardModifiers.isMetaPressed) event.changes.forEach { it.consume() }
        }) { content() }
    }
}
