package com.bilipai.desktop.player

import com.bilipai.desktop.plugins.DesktopAnimatedSkinImage
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Diagnostic measurement, not a timing assertion or a packaged-resource/Compose acceptance test. */
class DesktopBufferingAnimationDecodeTimingTest {
    @Test fun recordsFirstAssetDecodeAndFirstFrameWithoutASharedCache() {
        val resources = Path.of("../app/src/main/res")
        // Initialize Skia before measuring: an existing Compose scene already has a canvas.
        // OS file-cache state is not controlled. No animation asset is decoded before sample zero.
        Surface.makeRasterN32Premul(72, 72).use { surface ->
            Bitmap().use { bitmap ->
                assertTrue(bitmap.allocN32Pixels(72, 72))
                repeat(3) { sample ->
                    val start = System.nanoTime()
                    val animationBytes = Files.readAllBytes(resources.resolve("raw/bili_tv_buffering.webp"))
                    val afterRead = System.nanoTime()
                    Image.makeFromEncoded(Files.readAllBytes(resources.resolve("drawable-nodpi/bili_tv_buffering_static.png"))).use { fallback ->
                        val afterFallback = System.nanoTime()
                        assertNotNull(DesktopAnimatedSkinImage.decodeOrNull(animationBytes)).use { animation ->
                            val afterDecode = System.nanoTime()
                            surface.canvas.clear(0)
                            animation.render(surface.canvas, Rect.makeWH(72f, 72f), 0.0)
                            val afterRender = System.nanoTime()
                            assertTrue(surface.readPixels(bitmap, 0, 0))
                            val afterReadback = System.nanoTime()
                            fun ms(from: Long, to: Long) = (to - from) / 1_000_000.0
                            println("BUFFERING_TIMING sample=$sample readMs=${ms(start, afterRead)} " +
                                "fallbackMs=${ms(afterRead, afterFallback)} decodeMs=${ms(afterFallback, afterDecode)} " +
                                "firstRenderMs=${ms(afterDecode, afterRender)} readbackMs=${ms(afterRender, afterReadback)} " +
                                "throughFirstRenderMs=${ms(start, afterRender)} dimensions=${animation.width}x${animation.height}")
                            assertTrue((0 until 72).any { y -> (0 until 72).any { x -> bitmap.getColor(x, y) ushr 24 != 0 } })
                            assertTrue(fallback.width > 0 && fallback.height > 0)
                        }
                    }
                }
            }
        }
    }
}
