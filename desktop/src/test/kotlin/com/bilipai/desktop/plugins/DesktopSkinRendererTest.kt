package com.bilipai.desktop.plugins

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Rect
import java.util.Base64
import kotlin.test.*

class DesktopSkinRendererTest {
    @Test fun `real skottie frames move pixels and release native resources`() {
        DesktopLottieAsset.decode(MOVING_RECTANGLE.toByteArray()).use { animation ->
            assertEquals(1.0, animation.durationSeconds)
            Surface.makeRasterN32Premul(64, 64).use { surface ->
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocN32Pixels(64, 64))
                    surface.canvas.clear(0)
                    animation.render(surface.canvas, 64f, 64f, 0.0)
                    assertTrue(surface.readPixels(bitmap, 0, 0))
                    assertEquals(0xffff0000.toInt(), bitmap.getColor(8, 32))
                    assertEquals(0, bitmap.getColor(32, 32))
                    surface.canvas.clear(0)
                    animation.render(surface.canvas, 64f, 64f, 0.5)
                    assertTrue(surface.readPixels(bitmap, 0, 0))
                    assertEquals(0, bitmap.getColor(8, 32))
                    assertEquals(0xffff0000.toInt(), bitmap.getColor(32, 32))
                }
            }
        }
        val closed = DesktopLottieAsset.decode(MOVING_RECTANGLE.toByteArray())
        closed.close(); closed.close()
        Surface.makeRasterN32Premul(16, 16).use { surface ->
            assertFailsWith<IllegalStateException> { closed.render(surface.canvas, 16f, 16f, 0.0) }
        }
    }

    @Test fun `lottie rejects invalid dimensions and external image dependencies`() {
        assertFailsWith<IllegalArgumentException> {
            DesktopLottieAsset.decode(MOVING_RECTANGLE.replace("\"w\":64", "\"w\":99999").toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopLottieAsset.decode(MOVING_RECTANGLE.replace("\"assets\":[]", "\"assets\":[{\"p\":\"https://example.invalid/tracker.png\"}]").toByteArray())
        }
    }

    @Test fun `real animated webp renders both frames and loops without a frame cache`() {
        val bytes = Base64.getDecoder().decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAADwAADwAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAAJWUDhMDwAAAC8PwAMABxD1j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAGQAAABWUDhMDwAAAC8PwAMABxDR//4HIqL/AQA=")
        assertNotNull(DesktopAnimatedSkinImage.decodeOrNull(bytes)).use { animation ->
            assertEquals(2, animation.frameCount)
            assertEquals(0.2, animation.durationSeconds)
            Surface.makeRasterN32Premul(16, 16).use { surface ->
                Bitmap().use { bitmap ->
                    assertTrue(bitmap.allocN32Pixels(16, 16))
                    fun pixel(time: Double): Int {
                        surface.canvas.clear(0)
                        animation.render(surface.canvas, Rect.makeWH(16f, 16f), time)
                        assertTrue(surface.readPixels(bitmap, 0, 0))
                        return bitmap.getColor(8, 8)
                    }
                    val first = pixel(0.0); val second = pixel(0.15)
                    assertTrue((first ushr 16 and 255) > (first and 255))
                    assertTrue((second and 255) > (second ushr 16 and 255))
                    assertEquals(first, pixel(0.21))
                }
            }
        }
    }

    companion object {
        private const val MOVING_RECTANGLE = """{"v":"5.7.0","fr":30,"ip":0,"op":30,"w":64,"h":64,"assets":[],"layers":[{"ddd":0,"ind":1,"ty":1,"nm":"Fixture","sw":16,"sh":16,"sc":"#ff0000","ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":1,"k":[{"t":0,"s":[8,32,0],"e":[56,32,0],"i":{"x":0.667,"y":1},"o":{"x":0.333,"y":0}},{"t":30,"s":[56,32,0]}]},"a":{"a":0,"k":[8,8,0]},"s":{"a":0,"k":[100,100,100]}},"ip":0,"op":30,"st":0,"bm":0}]}"""
    }
}
