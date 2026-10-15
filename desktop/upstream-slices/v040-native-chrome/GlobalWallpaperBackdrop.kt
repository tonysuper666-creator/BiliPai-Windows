package com.android.purebilibili.core.ui.wallpaper

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.android.purebilibili.core.ui.LocalGlobalWallpaperBackdropVisible
import com.android.purebilibili.core.ui.blur.ChromeBackdropSource

/** A wallpaper-only recording. Never attach page/chrome content to this source. */
val LocalGlobalWallpaperBackdropSource = compositionLocalOf<ChromeBackdropSource?> { null }
val LocalGlobalWallpaperUri = compositionLocalOf { "" }

/** Replay in screen coordinates, so retained entries never reveal the previous page. */
@Composable
fun Modifier.sharedGlobalWallpaperBackground(fallbackColor: Color): Modifier {
    val visible = LocalGlobalWallpaperBackdropVisible.current
    val source = LocalGlobalWallpaperBackdropSource.current
        ?.takeIf { visible && it.isReady }
        ?: return background(fallbackColor)
    // Replay directly: drawBackdrop's effect modifier requires Android 13 even when effects
    // are empty. Drawing the recorded layer itself also supports older Android versions.
    val coordinates = remember(source) { mutableStateOf<LayoutCoordinates?>(null) }
    return background(fallbackColor.copy(alpha = 1f))
        .onGloballyPositioned { coordinates.value = it }
        .drawWithContent {
            clipRect {
                with(source.backdrop) {
                    drawBackdrop(
                        density = this@drawWithContent,
                        coordinates = coordinates.value,
                    )
                }
            }
            drawContent()
        }
}
