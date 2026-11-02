package com.bilipai.desktop.platform.bitmap

import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBitmapColorTest {
    /** 合成像素源：不渲染、不加载图片，直接按坐标给 ARGB。 */
    private class SyntheticBitmap(
        private val pixelWidth: Int,
        private val pixelHeight: Int,
        private val closed: Boolean = false,
        private val colorAt: (x: Int, y: Int) -> Int,
    ) : DesktopBitmapPixelSource {
        var reads = 0
            private set
        var minX = Int.MAX_VALUE
            private set
        var maxX = -1
            private set
        var minY = Int.MAX_VALUE
            private set
        var maxY = -1
            private set

        override fun width(): Int = pixelWidth

        override fun height(): Int = pixelHeight

        override fun isClosed(): Boolean = closed

        override fun getColor(x: Int, y: Int): Int {
            reads++
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
            return colorAt(x, y)
        }
    }

    private fun solid(width: Int, height: Int, color: Int, closed: Boolean = false) =
        SyntheticBitmap(width, height, closed) { _, _ -> color }

    @Test fun solidColorComesBackAsTheSameQuantizedColorTheCardConsumerSees() {
        val color = extractRepresentativeColor(solid(48, 48, BLUE))
        // 量化器每通道 5 bit，所以返回值是原色的近似，而不是原色本身。
        assertEquals(BLUE_QUANTIZED, color)
        assertApproximates(BLUE, color!!)
    }

    @Test fun denserColorWinsOverTheMinority() {
        val source = SyntheticBitmap(48, 48) { x, _ -> if (x % 4 == 0) ORANGE else BLUE }
        assertEquals(BLUE_QUANTIZED, extractRepresentativeColor(source))
    }

    @Test fun samePixelsAlwaysProduceTheSameColor() {
        val first = extractRepresentativeColor(SyntheticBitmap(64, 64) { x, _ -> if (x % 3 == 0) ORANGE else BLUE })
        val second = extractRepresentativeColor(SyntheticBitmap(64, 64) { x, _ -> if (x % 3 == 0) ORANGE else BLUE })
        assertEquals(BLUE_QUANTIZED, first)
        assertEquals(first, second)
    }

    @Test fun closedBitmapIsNotSampled() {
        val source = solid(48, 48, BLUE, closed = true)
        assertNull(extractRepresentativeColor(source))
        assertEquals(0, source.reads)
    }

    @Test fun emptyBitmapIsNotSampled() {
        assertNull(extractRepresentativeColor(SyntheticBitmap(0, 0) { _, _ -> BLUE }))
        assertNull(extractRepresentativeColor(SyntheticBitmap(10, 0) { _, _ -> BLUE }))
        assertNull(extractRepresentativeColor(SyntheticBitmap(0, 10) { _, _ -> BLUE }))
    }

    @Test fun oversizedBitmapFailsLoudlyBeforeReadingPixels() {
        val source = SyntheticBitmap(5000, 4000) { _, _ -> BLUE }
        val error = assertFailsWith<IllegalArgumentException> { extractRepresentativeColor(source) }
        assertEquals("位图采样尺寸过大", error.message)
        assertEquals(0, source.reads)
    }

    @Test fun bitmapAtThePixelLimitIsStillAccepted() {
        assertEquals(BLUE_QUANTIZED, extractRepresentativeColor(solid(4000, 4000, BLUE)))
    }

    @Test fun achromaticBitmapHasNoRepresentativeAccentColor() {
        assertNull(extractRepresentativeColor(solid(48, 48, 0xFF808080.toInt())))
        assertNull(extractRepresentativeColor(solid(48, 48, 0xFF000000.toInt())))
        assertNull(extractRepresentativeColor(solid(48, 48, 0xFFFFFFFF.toInt())))
    }

    @Test fun largeBitmapIsSampledWithinThe48By48Area() {
        val source = solid(200, 100, BLUE)
        assertEquals(BLUE_QUANTIZED, extractRepresentativeColor(source))
        assertEquals(2312, source.reads)
        assertTrue(source.reads < 200 * 100)
        assertTrue(source.minX >= 0 && source.maxX <= 199 && source.minY >= 0 && source.maxY <= 99)
        assertTrue(source.maxX >= 190)
    }

    @Test fun skiaBitmapIsReadThroughThePlatformAdapter() {
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(8, 8)
        bitmap.erase(BLUE)
        try {
            assertEquals(BLUE_QUANTIZED, extractRepresentativeColor(bitmap))
        } finally {
            bitmap.close()
        }
        assertNull(extractRepresentativeColor(bitmap))
    }

    /** 量化器每通道 5 bit，差值不应超过一个量化台阶。 */
    private fun assertApproximates(expected: Int, actual: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val delta = kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255))
            assertTrue(delta <= 8, "channel shift=$shift delta=$delta")
        }
    }

    private companion object {
        /** 蓝色：饱和度 0.75、明度 0.80，属于可用的强调色。 */
        const val BLUE = 0xFF3366CC.toInt()

        /** [BLUE] 经 5 bit/通道量化后的值，即既有封面消费者拿到的那一个颜色。 */
        const val BLUE_QUANTIZED = 0xFF3060C8.toInt()

        /** 橙色：饱和度 0.75、明度 0.80，与蓝色人口竞争时不占优。 */
        const val ORANGE = 0xFFCC6633.toInt()
    }
}
