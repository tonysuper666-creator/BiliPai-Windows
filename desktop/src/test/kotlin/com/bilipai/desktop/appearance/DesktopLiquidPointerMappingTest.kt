package com.bilipai.desktop.appearance

import com.android.purebilibili.core.ui.components.resolveAppSliderPointerFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopLiquidPointerMappingTest {
    @Test
    fun mouseTravelStartsAndEndsAtVisibleThumbCenters() {
        assertEquals(0f, resolveAppSliderPointerFraction(20f, 100f, 20f, true))
        assertEquals(0.5f, resolveAppSliderPointerFraction(50f, 100f, 20f, true))
        assertEquals(1f, resolveAppSliderPointerFraction(80f, 100f, 20f, true))
        assertEquals(0f, resolveAppSliderPointerFraction(-20f, 100f, 20f, true))
        assertEquals(1f, resolveAppSliderPointerFraction(120f, 100f, 20f, true))
    }

    @Test
    fun rightToLeftTravelReversesBothEndpointsAndOutsideClamping() {
        assertEquals(1f, resolveAppSliderPointerFraction(20f, 100f, 20f, false))
        assertEquals(0.5f, resolveAppSliderPointerFraction(50f, 100f, 20f, false))
        assertEquals(0f, resolveAppSliderPointerFraction(80f, 100f, 20f, false))
        assertEquals(1f, resolveAppSliderPointerFraction(-20f, 100f, 20f, false))
        assertEquals(0f, resolveAppSliderPointerFraction(120f, 100f, 20f, false))
    }

    @Test
    fun collapsedAndUndersizedTracksRemainFiniteInBothDirections() {
        for (width in listOf(0f, 20f, 40f)) {
            for (leftToRight in listOf(false, true)) {
                val actual = resolveAppSliderPointerFraction(15f, width, 20f, leftToRight)
                assertTrue(actual.isFinite())
                assertEquals(0f, actual)
            }
        }
    }

    @Test
    fun logicalProgressSurvivesUniformWindowAndDisplayScaling() {
        for (scale in listOf(0.8f, 1f, 1.25f, 1.5f, 2f, 3f)) {
            val width = 384f * scale
            val inset = 20f * scale
            val position = inset + (width - 2f * inset) * 0.25f
            assertEquals(0.25f, resolveAppSliderPointerFraction(position, width, inset, true), 0.00001f)
            assertEquals(0.75f, resolveAppSliderPointerFraction(position, width, inset, false), 0.00001f)
        }
    }
}
