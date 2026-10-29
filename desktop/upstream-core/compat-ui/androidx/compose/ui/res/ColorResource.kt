package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Desktop colorResource over the build-time table generated from the upstream `res/values` and `res/values-night`
 * XML files (`DesktopColorTable`). Night values apply when the effective app theme is dark, the way Android picks the
 * -night qualifier from the activity's configuration. An id that is not in the table is an error, not a default color.
 */
@Composable
fun colorResource(id: Int): Color {
    val dark = com.bilipai.desktop.appearance.isDesktopInDarkTheme()
    val argb = (if (dark) DesktopColorTable.night[id] else null) ?: DesktopColorTable.light[id]
        ?: throw IllegalArgumentException("No color resource 0x${id.toString(16)} in the generated desktop color table")
    return Color(argb)
}
