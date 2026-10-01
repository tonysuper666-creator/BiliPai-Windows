package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt

/** Actual Windows ARGB image backend for the original Halcyon CPU frame algorithm.
 * This is an owned pixel copy, never a borrowed Coil/Skia bitmap. No Android package,
 * service, image cache or palette authority is provided by this adapter.
 */
internal class DesktopMusicRaster private constructor(internal val image: BufferedImage) {
    val width: Int get() = image.width
    val height: Int get() = image.height
    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)
    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        image.getRGB(x, y, width, height, out, offset, stride)
    }
    fun setPixels(input: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        image.setRGB(x, y, width, height, input, offset, stride)
    }
    fun recycle() { image.flush() }
    enum class Config { ARGB_8888 }
    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): DesktopMusicRaster {
            require(config == Config.ARGB_8888 && width > 0 && height > 0)
            require(width.toLong() * height <= 16_777_216L)
            return DesktopMusicRaster(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))
        }
        fun createBitmap(source: DesktopMusicRaster, x: Int, y: Int, width: Int, height: Int): DesktopMusicRaster {
            require(x >= 0 && y >= 0 && x + width <= source.width && y + height <= source.height)
            return createBitmap(width, height, Config.ARGB_8888).also {
                val pixels = IntArray(width * height)
                source.getPixels(pixels, 0, width, x, y, width, height)
                it.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
        fun createScaledBitmap(source: DesktopMusicRaster, width: Int, height: Int, filter: Boolean): DesktopMusicRaster {
            if (width == source.width && height == source.height) return source
            return createBitmap(width, height, Config.ARGB_8888).also { target ->
                val graphics = target.image.createGraphics()
                try {
                    graphics.composite = AlphaComposite.Src
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        if (filter) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    graphics.drawImage(source.image, 0, 0, width, height, null)
                } finally { graphics.dispose() }
            }
        }
    }
}

internal fun DesktopMusicRaster.asImageBitmap(): ImageBitmap {
    val bytes = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
        val argb = getPixel(x, y); val index = (y * width + x) * 4
        bytes[index] = argb.toByte(); bytes[index + 1] = (argb ushr 8).toByte()
        bytes[index + 2] = (argb ushr 16).toByte(); bytes[index + 3] = (argb ushr 24).toByte()
    }
    // Compose owns this newly created Skia image. Closing it here would invalidate
    // its borrowed painting handle. The Java2D raster remains a separate owned copy.
    return SkiaImage.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4).toComposeImageBitmap()
}

internal class DesktopMusicRasterMatrix {
    internal val transform = AffineTransform()
    fun reset() = transform.setToIdentity()
    fun setScale(x: Float, y: Float) = transform.setToScale(x.toDouble(), y.toDouble())
    // Android post* composes the new operation on the left of the existing matrix.
    fun postRotate(degrees: Float, pivotX: Float, pivotY: Float) = transform.preConcatenate(
        AffineTransform.getRotateInstance(Math.toRadians(degrees.toDouble()), pivotX.toDouble(), pivotY.toDouble()))
    fun postTranslate(x: Float, y: Float) = transform.preConcatenate(AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
}

internal class DesktopMusicRasterColorMatrix {
    internal var saturation: Float = 1f
    fun setSaturation(value: Float) { saturation = value }
}
internal class DesktopMusicRasterColorFilter(internal val matrix: DesktopMusicRasterColorMatrix)
internal class DesktopMusicRasterPaint(flags: Int) {
    var isFilterBitmap: Boolean = false
    var colorFilter: DesktopMusicRasterColorFilter? = null
    internal val antialias: Boolean = flags and ANTI_ALIAS_FLAG != 0
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}
internal class DesktopMusicRasterCanvas(target: DesktopMusicRaster) : AutoCloseable {
    private val graphics = target.image.createGraphics()
    fun drawColor(argb: Int) {
        graphics.transform = AffineTransform()
        graphics.composite = AlphaComposite.SrcOver
        graphics.color = java.awt.Color(argb, true)
        graphics.fillRect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
    }
    fun drawBitmap(source: DesktopMusicRaster, matrix: DesktopMusicRasterMatrix, paint: DesktopMusicRasterPaint) {
        val saturation = paint.colorFilter?.matrix?.saturation ?: 1f
        val filtered = if (saturation == 1f) null else DesktopMusicRaster.createBitmap(source.width, source.height, DesktopMusicRaster.Config.ARGB_8888)
        try {
            if (filtered != null) {
                val inverse = 1f - saturation
                val rw = .213f * inverse; val gw = .715f * inverse; val bw = .072f * inverse
                for (y in 0 until source.height) for (x in 0 until source.width) {
                    val argb = source.getPixel(x, y)
                    val r = (argb ushr 16) and 255; val g = (argb ushr 8) and 255; val b = argb and 255
                    val nr = ((rw + saturation) * r + gw * g + bw * b).roundToInt().coerceIn(0, 255)
                    val ng = (rw * r + (gw + saturation) * g + bw * b).roundToInt().coerceIn(0, 255)
                    val nb = (rw * r + gw * g + (bw + saturation) * b).roundToInt().coerceIn(0, 255)
                    filtered.image.setRGB(x, y, (argb and -0x1000000) or (nr shl 16) or (ng shl 8) or nb)
                }
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                if (paint.isFilterBitmap) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                if (paint.antialias) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.composite = AlphaComposite.SrcOver
            graphics.drawImage((filtered ?: source).image, matrix.transform, null)
        } finally { filtered?.recycle() }
    }
    override fun close() { graphics.dispose() }
}

internal object DesktopMusicRasterColor {
    fun alpha(argb: Int): Int = argb ushr 24
    fun red(argb: Int): Int = (argb ushr 16) and 255
    fun green(argb: Int): Int = (argb ushr 8) and 255
    fun blue(argb: Int): Int = argb and 255
    fun RGBToHSV(r: Int, g: Int, b: Int, out: FloatArray) {
        java.awt.Color.RGBtoHSB(r, g, b, out); out[0] *= 360f
    }
    fun HSVToColor(hsv: FloatArray): Int = java.awt.Color.HSBtoRGB(hsv[0] / 360f, hsv[1], hsv[2])
}
