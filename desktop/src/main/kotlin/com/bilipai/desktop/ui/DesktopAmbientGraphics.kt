package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.*

/** Actual ARGB pixels at the platform boundary. There is no Android view, surface or decoder. */
internal class DesktopAmbientBitmap private constructor(val width: Int, val height: Int, private val argb: IntArray) {
    enum class Config { ARGB_8888 }
    fun getPixels(output: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {
        require(x >= 0 && y >= 0 && width >= 0 && height >= 0 && x + width <= this.width && y + height <= this.height)
        require(stride >= width && offset >= 0 && output.size >= offset + (height - 1).coerceAtLeast(0) * stride + width)
        for (row in 0 until height) argb.copyInto(output, offset + row * stride, (y + row) * this.width + x, (y + row) * this.width + x + width)
    }
    fun copy(config: Config, mutable: Boolean): DesktopAmbientBitmap {
        require(config == Config.ARGB_8888 && !mutable)
        return DesktopAmbientBitmap(width, height, argb.copyOf())
    }
    fun image(): ImageBitmap = skiaImage().use { it.toComposeImageBitmap() }
    internal fun skiaImage(): Image {
        val bytes = ByteArray(argb.size * 4)
        argb.forEachIndexed { i, c ->
            bytes[i * 4] = c.toByte(); bytes[i * 4 + 1] = (c ushr 8).toByte()
            bytes[i * 4 + 2] = (c ushr 16).toByte(); bytes[i * 4 + 3] = (c ushr 24).toByte()
        }
        return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
    }
    companion object {
        fun createBitmap(pixels: IntArray, width: Int, height: Int, config: Config): DesktopAmbientBitmap {
            require(config == Config.ARGB_8888 && width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
            return DesktopAmbientBitmap(width, height, pixels.copyOf())
        }
        fun fromImage(image: ImageBitmap): DesktopAmbientBitmap {
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            return DesktopAmbientBitmap(image.width, image.height, pixels)
        }
    }
}
internal fun DesktopAmbientBitmap.asDesktopAmbientImageBitmap(): ImageBitmap = image()
internal fun ImageBitmap.asDesktopAmbientBitmap(): DesktopAmbientBitmap = DesktopAmbientBitmap.fromImage(this)
internal fun desktopAmbientNowMs(): Long = System.nanoTime() / 1_000_000L

/** Original edge/mask math uses these thin Skia operations; no parallel Compose renderer. */
internal open class DesktopAmbientShader(private val base: Shader) : AutoCloseable {
    enum class TileMode { CLAMP }
    private var transformed: Shader? = null
    internal val native: Shader get() = transformed ?: base
    fun setLocalMatrix(matrix: DesktopAmbientMatrix) {
        val next = base.makeWithLocalMatrix(matrix.value)
        transformed?.close(); transformed = next
    }
    override fun close() { transformed?.close(); transformed = null; base.close() }
}
internal class DesktopAmbientBitmapShader(bitmap: DesktopAmbientBitmap, x: DesktopAmbientShader.TileMode, y: DesktopAmbientShader.TileMode) :
    DesktopAmbientShader(bitmap.skiaImage().use {
        require(x == DesktopAmbientShader.TileMode.CLAMP && y == DesktopAmbientShader.TileMode.CLAMP)
        it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR)
    })
private fun gradient(colors: IntArray, stops: FloatArray, mode: DesktopAmbientShader.TileMode): Gradient {
    require(mode == DesktopAmbientShader.TileMode.CLAMP && colors.size == stops.size)
    return Gradient(Gradient.Colors(colors.map { c -> Color4f((c ushr 16 and 255) / 255f, (c ushr 8 and 255) / 255f,
        (c and 255) / 255f, (c ushr 24 and 255) / 255f) }.toTypedArray(), stops.copyOf(), FilterTileMode.CLAMP))
}
internal class DesktopAmbientLinearGradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray, mode: DesktopAmbientShader.TileMode) :
    DesktopAmbientShader(Shader.makeLinearGradient(x0, y0, x1, y1, gradient(colors, stops, mode)))
internal class DesktopAmbientRadialGradient(x: Float, y: Float, radius: Float, colors: IntArray, stops: FloatArray, mode: DesktopAmbientShader.TileMode) :
    DesktopAmbientShader(Shader.makeRadialGradient(x, y, radius, gradient(colors, stops, mode)))
internal class DesktopAmbientRectF {
    var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
    fun set(left: Float, top: Float, right: Float, bottom: Float) { this.left = left; this.top = top; this.right = right; this.bottom = bottom }
}
internal class DesktopAmbientMatrix {
    enum class ScaleToFit { FILL }
    internal var value = Matrix33.IDENTITY
    fun setScale(x: Float, y: Float) { value = Matrix33.makeScale(x, y) }
    fun postTranslate(x: Float, y: Float) { value = Matrix33.makeTranslate(x, y).makeConcat(value) }
    fun setRectToRect(source: DesktopAmbientRectF, destination: DesktopAmbientRectF, mode: ScaleToFit) {
        require(mode == ScaleToFit.FILL && source.right > source.left && source.bottom > source.top)
        val sx = (destination.right - destination.left) / (source.right - source.left)
        val sy = (destination.bottom - destination.top) / (source.bottom - source.top)
        value = Matrix33(sx, 0f, destination.left - source.left * sx, 0f, sy, destination.top - source.top * sy, 0f, 0f, 1f)
    }
}
internal object DesktopAmbientPorterDuff { enum class Mode { DST_IN } }
internal class DesktopAmbientPorterDuffXfermode(val mode: DesktopAmbientPorterDuff.Mode)
internal class DesktopAmbientPaint(flags: Int) : AutoCloseable {
    companion object { const val ANTI_ALIAS_FLAG = 1; const val FILTER_BITMAP_FLAG = 2 }
    internal val native = Paint().apply { isAntiAlias = flags and ANTI_ALIAS_FLAG != 0 }
    var shader: DesktopAmbientShader? = null
        set(value) { field = value; native.shader = value?.native }
    var alpha: Int = 255
        set(value) { require(value in 0..255); field = value; native.alpha = value }
    var xfermode: DesktopAmbientPorterDuffXfermode? = null
        set(value) { field = value; native.blendMode = if (value == null) BlendMode.SRC_OVER else {
            require(value.mode == DesktopAmbientPorterDuff.Mode.DST_IN); BlendMode.DST_IN
        } }
    override fun close() { native.close() }
}
internal class DesktopAmbientCanvas(private val native: Canvas) {
    fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: DesktopAmbientPaint?): Int = native.saveLayer(left, top, right, bottom, paint?.native)
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: DesktopAmbientPaint) { native.drawRect(left, top, right, bottom, paint.native) }
    fun restoreToCount(count: Int) { native.restoreToCount(count) }
}
