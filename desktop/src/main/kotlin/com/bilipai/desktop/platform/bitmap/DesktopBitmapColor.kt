package com.bilipai.desktop.platform.bitmap

import com.bilipai.desktop.palette.DesktopPalette
import org.jetbrains.skia.Bitmap
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 桌面平台层的「位图」接口：只暴露取色需要的像素访问。
 *
 * 桌面真实类型是 `org.jetbrains.skia.Bitmap`，这里用接口把它隔开，便于注入合成像素、
 * 也便于后续换成别的图片来源（封面、壁纸、截图），不必重新实现取色。
 */
internal interface DesktopBitmapPixelSource {
    fun width(): Int

    fun height(): Int

    fun isClosed(): Boolean

    /** 返回 (x, y) 处的 ARGB 颜色。越界行为由实现方负责。 */
    fun getColor(x: Int, y: Int): Int
}

/** Skia 位图适配。位图生命周期仍归调用方，本类不 close。 */
internal class SkiaBitmapPixelSource(private val bitmap: Bitmap) : DesktopBitmapPixelSource {
    override fun width(): Int = bitmap.width

    override fun height(): Int = bitmap.height

    override fun isClosed(): Boolean = bitmap.isClosed

    override fun getColor(x: Int, y: Int): Int = bitmap.getColor(x, y)
}

/** AndroidX Palette 的 48×48 面积采样上限，与既有封面取色一致。 */
internal const val BITMAP_COLOR_SAMPLE_SIDE = 48

/** 量化颜色上限，与既有封面取色一致。 */
internal const val BITMAP_COLOR_MAX_COLORS = 16

/** 可采样像素上限，与 `DesktopWallpaperPalettePlatform` 同一上限。 */
internal const val BITMAP_COLOR_MAX_PIXELS = 16_000_000L

/**
 * 用真实的 median-cut 量化器（[DesktopPalette.quantize]）取位图代表色，返回 ARGB。
 *
 * 采样与选择照搬 `DesktopVideoCardPalette`：先按 48×48 面积做最近邻重采样，
 * 再过滤掉「无彩色 / 近黑 / 近白」的 swatch，取人口最多的一个。
 *
 * 与既有消费者的唯一差异：全部 swatch 都被过滤时返回 `null`，而既有消费者会回退到
 * 全部 swatch（于是把灰/黑/白当成强调色）。本接口是给强调色用的，无彩色不算代表色。
 *
 * 返回值是**量化后的** ARGB（每通道 5 bit，`ColorCutQuantizer` 的既有精度），
 * 与既有封面消费者拿到的值完全一致，不是原始像素值。
 *
 * @return 代表色 ARGB；位图已关闭、尺寸为空、或没有可用颜色时返回 `null`。
 * @throws IllegalArgumentException 位图面积超过 [BITMAP_COLOR_MAX_PIXELS]，此时不做任何采样。
 */
internal fun extractRepresentativeColor(
    source: DesktopBitmapPixelSource,
    maxColors: Int = BITMAP_COLOR_MAX_COLORS,
): Int? {
    val width = source.width()
    val height = source.height()
    if (source.isClosed() || width <= 0 || height <= 0) return null
    require(width.toLong() * height <= BITMAP_COLOR_MAX_PIXELS) { "位图采样尺寸过大" }
    val swatches = DesktopPalette.quantize(samplePixels(source, width, height), maxColors)
    return swatches.filter { it.isAccentCandidate() }.maxByOrNull { it.population }?.rgb
}

/** `org.jetbrains.skia.Bitmap` 的便捷入口，内部包一层 [SkiaBitmapPixelSource]。 */
internal fun extractRepresentativeColor(
    bitmap: Bitmap,
    maxColors: Int = BITMAP_COLOR_MAX_COLORS,
): Int? = extractRepresentativeColor(SkiaBitmapPixelSource(bitmap), maxColors)

/** Palette.Builder.resizeBitmapArea 的等价实现：面积缩到 48×48，最近邻、不滤波。 */
private fun samplePixels(source: DesktopBitmapPixelSource, width: Int, height: Int): IntArray {
    val area = width.toLong() * height
    val sampleArea = BITMAP_COLOR_SAMPLE_SIDE.toDouble() * BITMAP_COLOR_SAMPLE_SIDE
    val ratio = if (area > sampleArea) sqrt(sampleArea / area) else 1.0
    val sampleWidth = ceil(width * ratio).toInt()
    val sampleHeight = ceil(height * ratio).toInt()
    return IntArray(sampleWidth * sampleHeight) { index ->
        val x = (((index % sampleWidth) + 0.5) * width / sampleWidth).toInt().coerceAtMost(width - 1)
        val y = (((index / sampleWidth) + 0.5) * height / sampleHeight).toInt().coerceAtMost(height - 1)
        source.getColor(x, y)
    }
}

/** 真正的强调色候选：有饱和度、不是近黑、也不是近白。 */
private fun DesktopPalette.Swatch.isAccentCandidate(): Boolean {
    val color = rgb
    val red = (color ushr 16) and 255
    val green = (color ushr 8) and 255
    val blue = color and 255
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val saturation = if (max == 0) 0f else (max - min).toFloat() / max
    val brightness = max / 255f
    return saturation >= 0.08f && brightness >= 0.08f && brightness <= 0.96f
}
