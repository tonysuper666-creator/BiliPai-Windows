package com.bilipai.desktop.ui

import com.bilipai.desktop.palette.DesktopPalette
import com.bilipai.desktop.palette.DesktopWallpaperPaletteScoring
import com.bilipai.desktop.platform.bitmap.wallpaperRegionSwatches
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopWallpaperPalettePlatformTest {
    // Frozen reference for the old consumer algorithm, independent of the platform entry.
    private fun original(width: Int, height: Int, pixels: IntArray, top: Int, bottom: Int): List<DesktopPalette.Swatch> {
        val ratio = if (width.toLong() * height > 112 * 112) sqrt((112 * 112) / (width.toDouble() * height)) else 1.0
        val w = ceil(width * ratio).toInt()
        val h = ceil(height * ratio).toInt()
        val scale = w / width.toDouble()
        val first = if (ratio < 1) floor(top * scale).toInt() else top
        val last = if (ratio < 1) minOf(ceil(bottom * scale).toInt(), h) else bottom
        val sampled = IntArray(w * (last - first))
        for (y in first until last) for (x in 0 until w) {
            val sx = minOf(((x + .5) * width / w).toInt(), width - 1)
            val sy = minOf(((y + .5) * height / h).toInt(), height - 1)
            sampled[(y - first) * w + x] = pixels[sy * width + sx]
        }
        return DesktopPalette.quantize(sampled, 8)
    }

    private fun signature(swatches: List<DesktopPalette.Swatch>) = swatches.map { it.rgb to it.population }
    private fun selected(scoring: DesktopWallpaperPaletteScoring) =
        listOf(scoring.vibrantSwatch, scoring.lightVibrantSwatch, scoring.darkVibrantSwatch,
            scoring.mutedSwatch, scoring.dominantSwatch).map { it?.let { s -> s.rgb to s.population } }

    @Test fun regionalSamplingAndConsumerScoringMatchTheOriginalForDifferentShapes() {
        val cases = listOf(
            listOf(20, 20, 0, 20), listOf(40, 80, 13, 57),
            listOf(2, 500, 127, 374), listOf(701, 513, 100, 460),
            listOf(113, 112, 1, 111), listOf(333, 997, 401, 402),
        )
        for ((width, height, top, bottom) in cases) {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                when ((x / 7 + y / 11) % 5) {
                    0 -> 0xff808080.toInt()
                    1 -> 0xff000000.toInt()
                    2 -> 0xffffffff.toInt()
                    3 -> 0xff3366cc.toInt()
                    else -> 0xffcc6633.toInt()
                }
            }
            val raster = DesktopWallpaperPaletteRaster(width, height, pixels)
            val expected = original(width, height, pixels, top, bottom)
            val actual = wallpaperRegionSwatches(raster, top, bottom)!!
            assertEquals(signature(expected), signature(actual), "swatches for $width x $height [$top,$bottom)")
            assertEquals(selected(DesktopWallpaperPaletteScoring(expected)),
                selected(raster.paletteForOriginalRegion(top, bottom)),
                "consumer scoring for $width x $height [$top,$bottom)")
        }
    }

    /**
     * Twelve colours that stay distinct after the quantizer's 5-bit rounding, twelve equal stripes.
     * 120x96 is under the 112x112 area ceiling, so the whole image is sampled without rescaling and
     * the fixture differs from the platform entry only in how many colours it offers.
     */
    private fun twelveDistinctStripes(width: Int = 120, height: Int = 96): IntArray {
        val colors = intArrayOf(
            0xff1f3f7f.toInt(), 0xff2fa05f.toInt(), 0xffb02f2f.toInt(), 0xffd0a020.toInt(),
            0xff7f3fbf.toInt(), 0xff1fa0af.toInt(), 0xffb05f7f.toInt(), 0xff5f7f3f.toInt(),
            0xffdf7f3f.toInt(), 0xff4f5f9f.toInt(), 0xff9fcfdf.toInt(), 0xff3f2f1f.toInt(),
        )
        val stripe = width / colors.size
        return IntArray(width * height) { index -> colors[minOf((index % width) / stripe, colors.lastIndex)] }
    }

    @Test fun moreThanEightDistinctColorsStayCappedAtEightAndMatchTheFrozenReference() {
        val pixels = twelveDistinctStripes()
        assertEquals(12, pixels.toSet().size, "fixture must offer more than eight independent colours")
        val raster = DesktopWallpaperPaletteRaster(120, 96, pixels.copyOf())
        val expected = original(120, 96, pixels, 0, 96)
        val actual = wallpaperRegionSwatches(raster, 0, 96)!!
        assertEquals(signature(expected), signature(actual), "twelve colours must quantize like the frozen max-8 reference")
        assertEquals(8, actual.size, "max-8 contract: twelve distinct colours must stop at eight swatches")
        assertEquals(8, actual.map { it.rgb }.toSet().size, "the eight swatches must stay distinct")
        assertEquals(120 * 96, actual.sumOf { it.population }, "the cap must not drop pixels")
        assertEquals(selected(DesktopWallpaperPaletteScoring(expected)), selected(raster.paletteForOriginalRegion(0, 96)))
    }

    @Test fun invalidRegionsStillFail() {
        val raster = DesktopWallpaperPaletteRaster(1, 2, intArrayOf(-1, -1))
        assertFailsWith<IllegalArgumentException> { raster.paletteForOriginalRegion(-1, 1) }
        assertFailsWith<IllegalArgumentException> { raster.paletteForOriginalRegion(1, 1) }
        assertFailsWith<IllegalArgumentException> { raster.paletteForOriginalRegion(1, 3) }
    }
}
