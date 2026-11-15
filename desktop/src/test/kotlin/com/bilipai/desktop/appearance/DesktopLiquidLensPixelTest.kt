package com.bilipai.desktop.appearance

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface

/** Rasterize the production shaders over a coordinate-sensitive child, including the exact lens centre. */
class DesktopLiquidLensPixelTest {
    @Test
    fun refractionShortCapsuleCentreAndAxesRemainFinite() = checkCapsule("ROUNDED_RECT_REFRACTION_SHADER", false)

    @Test
    fun dispersionShortCapsuleCentreAndAxesRemainFinite() = checkCapsule("ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER", true)

    private fun checkCapsule(fieldName: String, dispersion: Boolean) {
        val field = Class.forName("com.android.purebilibili.core.ui.effect.LiquidLensKt")
            .getDeclaredField(fieldName).apply { isAccessible = true }
        RuntimeEffect.makeForShader(field.get(null) as String).use { effect ->
            RuntimeEffect.makeForShader("half4 main(float2 p) { return half4(0.25 + p.x / 256.0, 0.25 + p.y / 256.0, 0.5, 1.0); }").use { childEffect ->
                RuntimeShaderBuilder(childEffect).use { childBuilder ->
                    childBuilder.makeShader().use { child ->
                        RuntimeShaderBuilder(effect).use { builder ->
                            builder.child("content", child)
                            builder.uniform("size", 65f, 7f)
                            builder.uniform("offset", 0f, 0f)
                            builder.uniform("cornerRadii", FloatArray(4) { 3.5f })
                            builder.uniform("refractionHeight", 8f)
                            builder.uniform("refractionAmount", -8f)
                            builder.uniform("depthEffect", 1f)
                            if (dispersion) {
                                builder.uniform("chromaticAberration", 0.2f)
                                builder.uniform("chromaticEdgeBand", -1f)
                                builder.uniform("chromaticBandHorizontal", 0f)
                            }
                            builder.makeShader().use { shader ->
                                val info = ImageInfo(65, 7, ColorType.RGBA_F16, ColorAlphaType.PREMUL)
                                Surface.makeRaster(info).use { surface ->
                                    Paint().use { paint ->
                                        paint.shader = shader
                                        surface.canvas.drawPaint(paint)
                                    }
                                    Bitmap().use { bitmap ->
                                        assertTrue(bitmap.allocPixels(info))
                                        assertTrue(surface.readPixels(bitmap, 0, 0))
                                        val pixels = ByteBuffer.wrap(bitmap.readPixels(info, 65 * 8, 0, 0)!!)
                                            .order(ByteOrder.nativeOrder()).asShortBuffer()
                                        fun component(i: Int) = java.lang.Float.float16ToFloat(pixels.get(i))
                                        for (i in 0 until pixels.limit()) {
                                            assertTrue(component(i).isFinite(), "$fieldName non-finite component $i")
                                        }
                                        val centre = (3 * 65 + 32) * 4
                                        val red = component(centre)
                                        val green = component(centre + 1)
                                        assertTrue(abs(red - (0.25f + 32.5f / 256f)) < 0.01f &&
                                            abs(green - (0.25f + 3.5f / 256f)) < 0.01f,
                                            "$fieldName centre pixel differs from undistorted content: $red, $green")
                                        for (distance in 1..2) {
                                            val left = component(centre - distance * 4)
                                            val right = component(centre + distance * 4)
                                            val above = component(centre - distance * 65 * 4 + 1)
                                            val below = component(centre + distance * 65 * 4 + 1)
                                            assertTrue(abs(left + right - 2 * red) < 0.01f &&
                                                abs(above + below - 2 * green) < 0.01f,
                                                "$fieldName centre axis is discontinuous at distance $distance")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
