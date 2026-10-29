package androidx.compose.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable

/** The window facts upstream reads from android.content.res.Configuration, taken from the live Compose window. */
@Immutable
class Configuration(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val densityDpi: Int,
    val fontScale: Float,
) {
    val orientation: Int get() = if (screenWidthDp > screenHeightDp) ORIENTATION_LANDSCAPE else ORIENTATION_PORTRAIT

    companion object {
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}

/**
 * Desktop stand-in for Android's LocalConfiguration. Sizes are the enclosing window's content area in dp
 * (live through resizes and the host scale), so layouts that branch on screenWidthDp see the window, not the monitor.
 */
object LocalConfiguration {
    val current: Configuration
        @Composable @ReadOnlyComposable get() {
            val density = LocalDensity.current
            val size = LocalWindowInfo.current.containerSize
            return Configuration(
                screenWidthDp = (size.width / density.density).toInt(),
                screenHeightDp = (size.height / density.density).toInt(),
                densityDpi = (density.density * 160f).toInt(),
                fontScale = density.fontScale,
            )
        }
}
