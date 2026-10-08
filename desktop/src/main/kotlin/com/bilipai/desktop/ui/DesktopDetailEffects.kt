package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import dev.chrisbanes.haze.HazeState

/** Root must provide its actual displayability/background state; Android SDK is not a capability. */
internal val LocalDesktopDetailForeground = staticCompositionLocalOf<Boolean> {
    error("Dynamic detail foreground owner is not mounted")
}

internal fun desktopDetailRenderEffectsSupported(): Boolean =
    runCatching { BlurEffect(radiusX = 1f, radiusY = 1f).isSupported() }.getOrDefault(false)

/** One current effect per actual detail/item composition. No quantization, LRU,
 * animation state or Snapshot writes; support is probed once for this owner.
 */
internal class DesktopDetailBlurEffectCache(val supported: Boolean = desktopDetailRenderEffectsSupported()) {
    private var radiusPx = Float.NaN
    private var effect: BlurEffect? = null
    fun resolve(nextRadiusPx: Float): BlurEffect? {
        if (!supported || !nextRadiusPx.isFinite() || nextRadiusPx <= 0.01f) return null
        if (radiusPx != nextRadiusPx) {
            val next = BlurEffect(radiusX = nextRadiusPx, radiusY = nextRadiusPx, edgeTreatment = TileMode.Clamp)
            effect = next
            radiusPx = nextRadiusPx
        }
        return effect
    }
}

/** Only UI graphics state; no account, store, list or transport is created. */
@Composable internal fun rememberDesktopDetailHazeState(): HazeState {
    val foreground = LocalDesktopDetailForeground.current
    return remember(foreground) { HazeState() }
}
