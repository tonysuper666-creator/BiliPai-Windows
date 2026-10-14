package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The actual parent content layer; separate popup windows retain their own pixels. */
internal interface DesktopOriginalModalContentBlurTarget {
    val radiusPx: Float
    fun setBlurRadiusPx(radius: Float)
    fun observeProgress(progress: () -> Float, onChange: () -> Unit): AutoCloseable
}

internal val LocalDesktopOriginalModalContentBlurTarget =
    staticCompositionLocalOf<DesktopOriginalModalContentBlurTarget?> { null }

private class WindowContentBlurTarget(private val scope: CoroutineScope) : DesktopOriginalModalContentBlurTarget {
    private val requestedRadius = mutableFloatStateOf(0f)
    private var alive = true
    override val radiusPx get() = requestedRadius.floatValue
    override fun setBlurRadiusPx(radius: Float) {
        if (alive) requestedRadius.floatValue = if (radius.isFinite()) radius.coerceAtLeast(0f) else 0f
    }
    override fun observeProgress(progress: () -> Float, onChange: () -> Unit): AutoCloseable {
        // Snapshot changes follow the real sheet state and never request an idle refresh loop.
        val job = scope.launch {
            snapshotFlow { progress() }.collect { if (alive) onChange() }
        }
        return AutoCloseable { job.cancel() }
    }
    fun close() { alive = false; requestedRadius.floatValue = 0f }
}

@Composable
internal fun rememberDesktopOriginalWindowModalBlur(window: Any?): DesktopOriginalModalContentBlurTarget {
    val scope = rememberCoroutineScope()
    val target = remember(window, scope) { WindowContentBlurTarget(scope) }
    DisposableEffect(target) { onDispose { target.close() } }
    return target
}

internal fun Modifier.desktopOriginalModalContentBlur(target: DesktopOriginalModalContentBlurTarget): Modifier =
    graphicsLayer {
        val radius = target.radiusPx
        renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
    }
