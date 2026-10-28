package com.android.purebilibili.feature.home.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.blur.ProgressiveBlur

class HomeTopReadabilityProtectionTest {
    @Test
    fun searchStaysLightWhileLastTabRowIsProtectedAndTrailingEdgeClears() {
        val stops = homeTopReadabilityStops(152f, 104f, 152f, 196f, 16f, 0.24f, 1f)
        assertEquals(0.24f, stops.first().second, 0.0001f)
        assertEquals(0.65f, stops.first { it.first == 104f / 196f }.second, 0.0001f)
        assertEquals(0.65f, stops.first { it.first == 152f / 196f }.second, 0.0001f)
        assertEquals(0f, stops.last().second, 0.0001f)
        assertTrue(stops.zipWithNext().all { (a, b) -> a.first < b.first })
    }

    @Test
    fun tabsBeforeSearchDoNotApplyTheirStrongProtectionAcrossTheSearchRow() {
        val stops = homeTopReadabilityStops(152f, 24f, 64f, 196f, 16f, 0.24f, 1f)
        assertEquals(0.65f, stops.first { it.first == 64f / 196f }.second, 0.0001f)
        assertEquals(0.24f, stops.first { it.first == 80f / 196f }.second, 0.0001f)
    }

    @Test
    fun hiddenTabsDoNotLeaveAStrongBand() {
        val stops = homeTopReadabilityStops(80f, 80f, 80f, 112f, 16f, 0.24f, 0f)
        assertTrue(stops.all { it.second in 0f..0.24f })
        assertEquals(0f, stops.last().second, 0.0001f)
    }

    @Test
    fun clearEndMovesBelowLabelsEvenWhenCoverageSettingIsShort() {
        val original = ProgressiveBlur.Top.copy(endFraction = 0.5f, curve = 1.25f)
        val result = homeTopReadabilityBlurGradient(original, 0.75f, 0.95f)
        assertEquals(0.75f, result.startFraction, 0.0001f)
        assertEquals(0.95f, result.endFraction, 0.0001f)
        assertEquals(original.curve, result.curve, 0.0001f)
        assertTrue(result.endFraction > result.startFraction)
    }
}
