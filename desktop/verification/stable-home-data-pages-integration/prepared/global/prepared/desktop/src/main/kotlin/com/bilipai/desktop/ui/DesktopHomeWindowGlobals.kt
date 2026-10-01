package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import java.awt.Color
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** Resource bundle of the existing Root WINDOW lifetime, independent of selected page or MID. */
internal class DesktopHomeActualWindowResources(
    window: Window,
    private val isCurrent: () -> Boolean,
    currentThemeColor: () -> Color,
    logger: Logger,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val background = DesktopHomeActualWindowBackground(window) { !closed.get() && isCurrent() }
    val metrics = DesktopHomeActualMetrics({ !closed.get() && isCurrent() }, logger)
    val clientPolicy = DesktopHomeWindowsClientPolicy(window, { !closed.get() && isCurrent() }, currentThemeColor, metrics)

    /** Called outside SessionStore/app monitors. This bundle itself owns no MPV or account state. */
    override fun close() {
        if (closed.compareAndSet(false, true)) { background.close(); metrics.close() }
    }
}

/** Call in the actual Compose Window context, once above all ReadyShell route branches. */
@Composable
internal fun rememberDesktopHomeActualWindowResources(
    window: Window,
    isCurrent: () -> Boolean,
    currentThemeColor: () -> Color,
    logger: Logger,
): DesktopHomeActualWindowResources {
    val latestCurrent by rememberUpdatedState(isCurrent)
    val latestTheme by rememberUpdatedState(currentThemeColor)
    val resources = remember(window, logger) {
        DesktopHomeActualWindowResources(window, { latestCurrent() }, { latestTheme() }, logger)
    }
    DisposableEffect(resources) { onDispose { resources.close() } }
    // The Window's genuine frame-clock coroutine context, never a VM/IO scope without a clock.
    LaunchedEffect(resources) { resources.metrics.startFrameClock(this).join() }
    return resources
}

/** Window capabilities and metric sink are shared by general transition/Home consumers.
 * The animation callback retains the SINGLE Home data owner's epoch/admission binding.
 * HomeRoot may receive these same objects again; do not construct a second resource bundle there.
 */
@Composable
internal fun DesktopHomeWindowGlobals(
    resources: DesktopHomeActualWindowResources,
    homeGraphicsLayerCaptureReady: Boolean,
    errorAnimation: @Composable (String, Dp, Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val platform = desktopHomeActualPlatform(resources.background, resources.metrics, homeGraphicsLayerCaptureReady)
    CompositionLocalProvider(
        LocalDesktopHomePlatform provides platform,
        LocalDesktopHomeMetricHolder provides resources.metrics.holder,
        LocalDesktopHomeErrorAnimation provides errorAnimation,
        content = content,
    )
}
