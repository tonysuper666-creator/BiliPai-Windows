package com.bilipai.desktop.ui.modal

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.test.*

class DesktopModalGeometryTest {
    @Test fun readingPanelUsesTheContractDefaultShareAndClampAtEveryScale() {
        for (density in listOf(1f, 1.5f, 2.25f)) {
            for ((windowWidthDp, expectedWidthDp) in listOf(960 to 340, 1280 to 340, 2000 to 440, 2400 to 480)) {
                val viewport = IntSize((windowWidthDp * density).roundToInt(), (800 * density).roundToInt())
                val reading = desktopModalBounds(DesktopModalPurpose.READING_PANEL, viewport, density)
                assertEquals((expectedWidthDp * density).roundToInt(), reading.width,
                    "window=${windowWidthDp}dp density=$density")
                assertEquals(viewport.width, reading.right)
            }
        }
    }

    @Test fun confirmationAndReadingStayInsideSmallAndUltrawideWindowsAtEveryScale() {
        for (density in listOf(1f, 1.5f, 2.25f)) for (size in listOf(IntSize(280, 180), IntSize(960, 640), IntSize(5120, 2160))) {
            val confirm = desktopModalBounds(DesktopModalPurpose.CONFIRM, size, density)
            assertTrue(confirm.width <= size.width - 32 * density)
            assertTrue(confirm.height <= size.height - 48 * density)
            assertTrue(kotlin.math.abs((size.width - confirm.width) / 2 - confirm.left) <= 1)
            assertTrue(kotlin.math.abs((size.height - confirm.height) / 2 - confirm.top) <= 1)
            val reading = desktopModalBounds(DesktopModalPurpose.READING_PANEL, size, density)
            assertEquals(size.width, reading.right)
            assertEquals(size.height, reading.height)
            assertTrue(reading.width <= 480 * density)
            assertTrue(reading.width >= minOf(size.width.toFloat(), 340 * density))
        }
    }

    @Test fun optionsFlipAboveTheAnchorAndClampAtEveryEdge() {
        val size = IntSize(800, 600)
        assertEquals(IntRect(100, 138, 320, 246), desktopModalBounds(DesktopModalPurpose.OPTIONS,
            size, 1f, 108, IntRect(100, 100, 180, 130), 220f))
        assertEquals(IntRect(564, 404, 784, 512), desktopModalBounds(DesktopModalPurpose.OPTIONS,
            size, 1f, 108, IntRect(750, 520, 800, 550), 220f))
        for (at in listOf(IntRect(-100, -100, 0, 0), IntRect(999, 999, 1000, 1000))) {
            val result = desktopModalBounds(DesktopModalPurpose.OPTIONS, size, 1f, 1000, at, 900f)
            assertEquals(260, result.width)
            assertEquals(568, result.height)
            assertTrue(result.left >= 16 && result.right <= 784 && result.top >= 16 && result.bottom <= 584)
        }
    }

    @Test fun optionsRequireARealAnchorAndDegenerateWindowsNeverProduceNegativeSizes() {
        assertFailsWith<IllegalArgumentException> { desktopModalBounds(DesktopModalPurpose.OPTIONS, IntSize(800, 600), 1f) }
        for (purpose in DesktopModalPurpose.entries) {
            val bounds = desktopModalBounds(purpose, IntSize(1, 1), 1.5f, anchor = IntRect.Zero)
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 1 && bounds.bottom <= 1)
        }
    }
}
