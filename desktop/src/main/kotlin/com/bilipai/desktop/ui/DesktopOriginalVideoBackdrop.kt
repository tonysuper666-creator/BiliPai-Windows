package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.bilipai.desktop.player.MpvBackdropFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** One owned, bounded raster. It retains neither an mpv node nor native source credentials. */
internal class DesktopOriginalVideoBackdrop(
    val publication: DesktopOriginalVideoAcceptedPublication,
    val frame: MpvBackdropFrame,
    val image: ImageBitmap,
)

internal fun desktopOriginalVideoBackdropImage(frame: MpvBackdropFrame): ImageBitmap {
    require(frame.width in 1..960 && frame.height in 1..540)
    require(frame.opaqueBgra.size == frame.width * frame.height * 4)
    // The Compose bitmap retains this Skia Image. Do not close its backing image while
    // a recorded graphics layer may still replay it. Retirement releases the reference;
    // Skia's managed resource owns disposal. No disk encoding or full-resolution copy.
    return Image.makeRaster(
        ImageInfo(frame.width, frame.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
        frame.opaqueBgra, frame.width * 4,
    ).toComposeImageBitmap()
}
