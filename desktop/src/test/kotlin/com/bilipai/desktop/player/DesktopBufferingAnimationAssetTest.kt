package com.bilipai.desktop.player

import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Decodes the real upstream resources; this does not claim a production buffering UI binding. */
class DesktopBufferingAnimationAssetTest {
    @Test fun upstreamTelevisionHasChangingFramesAndAReusableStaticFallback() {
        val resources = Path.of("../app/src/main/res")
        val bytes = Files.readAllBytes(resources.resolve("raw/bili_tv_buffering.webp"))
        assertNotNull(DesktopAnimatedSkinImage.decodeOrNull(bytes)).use { animation ->
            assertTrue(animation.frameCount > 1)
            assertTrue(animation.durationSeconds > 0)
            Surface.makeRasterN32Premul(animation.width, animation.height).use { surface ->
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocN32Pixels(animation.width, animation.height))
                    fun frame(time: Double): List<Int> {
                        surface.canvas.clear(0)
                        animation.render(surface.canvas, Rect.makeWH(animation.width.toFloat(), animation.height.toFloat()), time)
                        assertTrue(surface.readPixels(bitmap, 0, 0))
                        return (0 until animation.height).flatMap { y ->
                            (0 until animation.width).map { x -> bitmap.getColor(x, y) }
                        }
                    }
                    val first = frame(0.0)
                    assertTrue(first.any { it ushr 24 != 0 }, "First frame must be visible")
                    assertTrue((1..8).any { frame(animation.durationSeconds * it / 9.0) != first },
                        "The actual upstream television must animate")
                    assertEquals(first, frame(animation.durationSeconds), "Asset must loop back to its first frame")
                    assertEquals(first, frame(0.0), "Seeking back must not retain pixels from a later frame")
                }
            }
        }
        Image.makeFromEncoded(Files.readAllBytes(resources.resolve("drawable-nodpi/bili_tv_buffering_static.png"))).use { image ->
            assertTrue(image.width > 0 && image.height > 0)
            Bitmap().use { bitmap ->
                assertTrue(bitmap.allocN32Pixels(image.width, image.height))
                assertTrue(image.readPixels(bitmap))
                assertTrue((0 until image.height).any { y ->
                    (0 until image.width).any { x -> bitmap.getColor(x, y) ushr 24 != 0 }
                }, "Static fallback must contain visible pixels")
            }
        }
    }
}
