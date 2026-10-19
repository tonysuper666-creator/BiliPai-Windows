package com.bilipai.desktop.ui

import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure seek rail model against the shared contract: buffered fraction, fine scrub factor, chapter gaps. No window, no GPU, no network. */
class DesktopPlayerSeekModelTest {
    private val pointer = DesktopUxContract.section("pointer")

    private fun near(expected: Double, actual: Double?, message: String? = null) {
        assertTrue(actual != null && abs(expected - actual) <= 1e-9, "$message: expected $expected, got $actual")
    }

    @Test fun theKnotsAndTheGapWidthAreTheContractNumbers() {
        val contractKnots = pointer.getValue("fine_scrub_knots").jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double }
        assertEquals(contractKnots, DESKTOP_PLAYER_FINE_SCRUB_KNOTS)
        val gap = DesktopUxContract.section("player").getValue("overlay").jsonObject.getValue("seek_rail").jsonObject.getValue("chapter_gap").jsonPrimitive.int
        assertEquals(gap.toDouble(), DESKTOP_PLAYER_CHAPTER_GAP_DP)
    }

    @Test fun theBufferedFraction() {
        near(0.5, desktopPlayerBufferedFraction(30.0, 20.0, 100.0))
        assertEquals(1.0, desktopPlayerBufferedFraction(500.0, 20.0, 100.0))
        near(0.2, desktopPlayerBufferedFraction(0.0, 20.0, 100.0))
        near(0.2, desktopPlayerBufferedFraction(-5.0, 20.0, 100.0)) // a negative figure counts as no cache
        near(0.1, desktopPlayerBufferedFraction(10.0, -3.0, 100.0)) // a negative position counts as the start
        assertEquals(0.0, desktopPlayerBufferedFraction(0.0, 0.0, 100.0))
        val unknown = listOf(
            Triple(null, 20.0, 100.0), Triple(Double.NaN, 20.0, 100.0), Triple(Double.POSITIVE_INFINITY, 20.0, 100.0),
            Triple(Double.NEGATIVE_INFINITY, 20.0, 100.0), Triple(10.0, Double.NaN, 100.0), Triple(10.0, Double.POSITIVE_INFINITY, 100.0),
            Triple(10.0, 20.0, Double.NaN), Triple(10.0, 20.0, Double.POSITIVE_INFINITY), Triple(10.0, 20.0, 0.0), Triple(10.0, 20.0, -1.0),
        )
        for ((buffered, position, duration) in unknown) {
            assertNull(desktopPlayerBufferedFraction(buffered, position, duration), "$buffered $position $duration")
        }
    }

    @Test fun theBufferedRangeNeverEndsBeforeThePlayheadAndStaysInTheUnitInterval() {
        for (position in listOf(0.0, 1.0, 33.3, 99.9, 100.0, 250.0)) {
            for (buffered in listOf(0.0, 0.5, 10.0, 1000.0)) {
                val got = desktopPlayerBufferedFraction(buffered, position, 100.0)
                assertTrue(got != null && got in 0.0..1.0, "$buffered $position")
                assertTrue(got!! >= minOf(position / 100.0, 1.0) - 1e-12, "$buffered $position")
            }
        }
    }

    @Test fun theFineScrubFactorAtTheKnotsAndBetweenThem() {
        for ((distance, factor) in DESKTOP_PLAYER_FINE_SCRUB_KNOTS) assertEquals(factor, desktopPlayerFineScrubFactor(distance), distance.toString())
        assertEquals(1.0, desktopPlayerFineScrubFactor(0.0))
        assertEquals(1.0, desktopPlayerFineScrubFactor(47.0))
        near(0.75, desktopPlayerFineScrubFactor(72.0))
        near(0.375, desktopPlayerFineScrubFactor(144.0))
        near(0.175, desktopPlayerFineScrubFactor(240.0))
        assertEquals(0.1, desktopPlayerFineScrubFactor(288.0))
        assertEquals(0.1, desktopPlayerFineScrubFactor(5000.0))
        assertEquals(0.1, desktopPlayerFineScrubFactor(Double.POSITIVE_INFINITY))
        assertEquals(0.5, desktopPlayerFineScrubFactor(-96.0)) // above or below the rail is the same distance
        assertEquals(1.0, desktopPlayerFineScrubFactor(Double.NaN))
    }

    @Test fun theFineScrubFactorIsMonotoneAndNeverBelowOneTenth() {
        var previous = 2.0
        var distance = 0.0
        while (distance <= 400.0) {
            val factor = desktopPlayerFineScrubFactor(distance)
            assertTrue(factor <= previous, "monotone at $distance")
            assertTrue(factor >= 0.1 && factor <= 1.0, "range at $distance")
            previous = factor
            distance += 0.25
        }
    }

    @Test fun chapterGaps() {
        val rail = 1565.0
        val hour = 3_600_000L
        assertEquals(listOf(1.0 / 6.0, 1.0 / 3.0), desktopPlayerChapterGapFractions(listOf(0L, 600_000L, 1_200_000L, hour), hour, rail))
        assertEquals(listOf(0.25), desktopPlayerChapterGapFractions(listOf(900_000L, 900_000L), hour, rail)) // duplicates collapse
        assertEquals(listOf(0.25, 0.5), desktopPlayerChapterGapFractions(listOf(1_800_000L, 900_000L), hour, rail)) // sorted
        // Two starts closer than a gap width on the rail: the later one is dropped (1000 ms and 1100 ms are 0.04 dp apart).
        assertEquals(listOf(1000.0 / hour), desktopPlayerChapterGapFractions(listOf(1000L, 1100L), hour, rail))
        assertEquals(listOf(1000.0 / hour, 6000.0 / hour), desktopPlayerChapterGapFractions(listOf(1000L, 1100L, 6000L), hour, rail))
        assertEquals(emptyList<Double>(), desktopPlayerChapterGapFractions(listOf(-5L, 0L, hour, hour + 1L), hour, rail))
        assertEquals(emptyList<Double>(), desktopPlayerChapterGapFractions(emptyList(), hour, rail))
        assertEquals(emptyList<Double>(), desktopPlayerChapterGapFractions(listOf(1000L), 0L, rail))
        assertEquals(emptyList<Double>(), desktopPlayerChapterGapFractions(listOf(1000L), hour, 0.0))
        assertEquals(emptyList<Double>(), desktopPlayerChapterGapFractions(listOf(1000L), hour, Double.NaN))
        assertEquals(emptyList<Double>(), desktopPlayerChapterGapFractions(listOf(1000L), hour, -4.0))
    }

    @Test fun noTwoGapsOverlapOnASweep() {
        val duration = 5_400_000L
        for (rail in listOf(320.0, 800.0, 1565.0, 3000.0)) {
            for (count in listOf(1, 3, 12, 60)) {
                val starts = (0 until count).map { (it.toLong() * 7919L * 13L + 101L) % duration }
                val gaps = desktopPlayerChapterGapFractions(starts, duration, rail)
                for ((left, right) in gaps.zip(gaps.drop(1))) assertTrue((right - left) * rail >= DESKTOP_PLAYER_CHAPTER_GAP_DP - 1e-9, "$rail $count")
                assertTrue(gaps.all { it > 0.0 && it < 1.0 }, "$rail $count")
                assertEquals(gaps.sorted(), gaps, "$rail $count")
            }
        }
    }
}
