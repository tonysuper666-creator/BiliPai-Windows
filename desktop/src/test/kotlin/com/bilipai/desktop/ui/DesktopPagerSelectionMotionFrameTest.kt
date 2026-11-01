@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.navigation.animatePagerSelection
import com.android.purebilibili.navigation.isTabSelectionInProgress
import com.android.purebilibili.navigation.tabIndicatorPosition
import com.android.purebilibili.navigation.tabSelectedPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real directly compiled navigation/PagerSelectionMotion over a real Compose pager, stepped frame by frame at the
 * 165 Hz frame interval of the target machine (the scene takes the frame time from the test, not from a clock).
 */
class DesktopPagerSelectionMotionFrameTest {
    private val frameNanos = 1_000_000_000L / 165

    private class Rig {
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        lateinit var scene: ImageComposeScene
        var now = 0L
        val positions = mutableListOf<Float>()
        val pagerPositions = mutableListOf<Float>()

        fun frame(step: Long) {
            now += step
            scene.render(now).close()
            positions += pager.tabIndicatorPosition
            pagerPositions += pager.currentPage + pager.currentPageOffsetFraction
        }

        fun run(step: Long, maxFrames: Int, until: () -> Boolean) {
            repeat(maxFrames) { if (until()) return; frame(step) }
            assertTrue(until(), "not settled after $maxFrames frames")
        }
    }

    private fun withRig(pages: Int = 5, body: Rig.() -> Unit) {
        val rig = Rig()
        rig.scene = ImageComposeScene(400, 300, Density(1f)) {
            rig.pager = rememberPagerState { pages }
            rig.scope = rememberCoroutineScope()
            HorizontalPager(rig.pager, modifier = Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) }
        }
        try { rig.frame(frameNanos); rig.body() } finally { rig.scene.close() }
    }

    private fun Rig.assertSettled(page: Int) {
        assertEquals(page, pager.currentPage)
        assertEquals(0f, pager.currentPageOffsetFraction, 1e-4f, "page offset residue after settling")
        assertFalse(pager.isTabSelectionInProgress)
        assertEquals(page, pager.tabSelectedPage)
        assertEquals(page.toFloat(), pager.tabIndicatorPosition, 1e-4f)
    }

    @Test
    fun clickJumpMovesTheRailContinuouslyWithoutSkippingFrames() = withRig {
        val job = scope.launch { animatePagerSelection(pager, 3) }
        frame(frameNanos)
        assertEquals(3, pager.tabSelectedPage, "selection semantics use the clicked destination at once")
        assertTrue(pager.isTabSelectionInProgress)
        run(frameNanos, 120) { job.isCompleted }
        frame(frameNanos)
        assertSettled(3)
        val indicator = positions.drop(1)
        assertTrue(indicator.zipWithNext().all { (a, b) -> b >= a - 1e-4f }, "rail never moves backwards: $indicator")
        assertTrue(indicator.all { it <= 3f + 1e-4f })
        val largestStep = indicator.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(largestStep < 0.25f, "rail jumps $largestStep pages in one 165 Hz frame")
        val frames = indicator.count { it > 0.01f && it < 2.99f }
        assertTrue(frames in 20..80, "rail is seen in $frames frames of a 300 ms switch")
    }

    @Test
    fun rapidReversingClicksSettleOnTheLastDestinationWithoutOffsetResidue() = withRig {
        val jobs = mutableListOf<Job>()
        jobs += scope.launch { animatePagerSelection(pager, 4) }
        repeat(6) { frame(frameNanos) }
        jobs += scope.launch { animatePagerSelection(pager, 1) }
        repeat(4) { frame(frameNanos) }
        jobs += scope.launch { animatePagerSelection(pager, 4) }
        repeat(3) { frame(frameNanos) }
        jobs += scope.launch { animatePagerSelection(pager, 2) }
        run(frameNanos, 200) { jobs.all { it.isCompleted } }
        frame(frameNanos)
        assertSettled(2)
        val largestStep = positions.drop(1).zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(largestStep < 0.35f, "rail jumps $largestStep pages in one 165 Hz frame while reversing")
    }

    @Test
    fun repeatedClickOnTheSameDestinationDoesNotRestartTheMotion() = withRig {
        val first = scope.launch { animatePagerSelection(pager, 2) }
        repeat(5) { frame(frameNanos) }
        val before = pager.tabIndicatorPosition
        val second = scope.launch { animatePagerSelection(pager, 2) }
        frame(frameNanos)
        assertTrue(second.isCompleted, "the duplicate click returns at once")
        assertTrue(pager.tabIndicatorPosition >= before, "the rail kept moving")
        run(frameNanos, 120) { first.isCompleted }
        frame(frameNanos)
        assertSettled(2)
    }

    @Test
    fun touchScrollingUsesTheActualPageOffsetNotTheRailAnimation() = withRig {
        scope.launch { pager.scrollToPage(1) }
        repeat(3) { frame(frameNanos) }
        scope.launch { pager.scrollBy(120f) }
        repeat(3) { frame(frameNanos) }
        assertFalse(pager.isTabSelectionInProgress)
        assertEquals(pager.currentPage + pager.currentPageOffsetFraction, pager.tabIndicatorPosition, 1e-6f)
        assertTrue(pager.tabIndicatorPosition > 1f && pager.tabIndicatorPosition < 2f)
    }
}
