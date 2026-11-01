package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import java.awt.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDanmakuLineAdmissionTest {
    private val settings = DanmakuSettings(mergeDuplicates = false, displayAreaRatio = 1f)
    private fun item(id: Int, time: Double, mode: Int = 1) =
        DanmakuComment(id, time, mode, 25, 0xffffff, "item$id")
    private fun config(lines: Int = 1) = DanmakuRenderConfig(
        typeface = Font("Dialog", Font.PLAIN, 15), textSizePx = 15f,
        lineHeightPx = 24f, lineMarginPx = 0f, lineCount = lines,
        topMarginPx = 0f, bottomMarginPx = 0f,
        scrollDurationMs = 10_000L, pinnedDurationMs = 10_000L,
    )
    private fun frame(scheduler: DanmakuScheduler, time: Double, config: DanmakuRenderConfig = config(),
        width: Int = 500, widths: Map<Int, Int> = emptyMap()) =
        scheduler.frame(time, width, 300, config) {
            DesktopDanmakuTextMetrics(widths[it.id] ?: 100, 15.0)
        }
    private fun ids(items: List<PositionedDanmaku>) = items.map { it.comment.id }

    @Test fun `equal speed and faster follower use actual admission boundaries`() {
        fun admitted(time: Double, newWidth: Int): Boolean {
            val scheduler = DanmakuScheduler(listOf(item(0, 0.0), item(1, time)), settings, false)
            return 1 in ids(frame(scheduler, time, widths = mapOf(1 to newWidth)))
        }
        assertFalse(admitted(2.06, 100))
        assertTrue(admitted(2.07, 100))
        assertFalse(admitted(3.19, 200))
        assertTrue(admitted(3.21, 200))
    }

    @Test fun `design counterexample keeps previous remaining lifetime`() {
        // At margin 50 the design example is allowed by desktop but rejected by full moveTime.
        assertTrue(desktopDanmakuLineAdmits(500, 100, 200, 4.0, 10.0, 10.0, 50f))
        val scheduler = DanmakuScheduler(listOf(item(0, 0.0), item(1, 4.0)), settings, false)
        // Scale the example to the real 24 px margin: gap 67.2, desktop threshold 52.8,
        // full-lifetime threshold 72.0. Both measured widths are exact integers.
        val visible = frame(scheduler, 4.0, width = 240, widths = mapOf(0 to 48, 1 to 96))
        assertEquals(listOf(0, 1), ids(visible))
        assertEquals(124.8, visible[0].x, 0.000001)
        assertEquals(240.0, visible[1].x, 0.000001)
    }

    @Test fun `fixed items wait until the physical lanes are empty`() {
        val scheduler = DanmakuScheduler(listOf(item(0, 0.0, 5), item(1, 0.0, 5),
            item(2, 4.0, 5), item(3, 10.0, 5)), settings, false)
        val config = config(5) // Two pinned lanes.
        assertEquals(listOf(0, 1), ids(frame(scheduler, 4.0, config)))
        for (time in 5..10) frame(scheduler, time.toDouble(), config)
        assertEquals(listOf(3), ids(frame(scheduler, 10.0, config)))
    }

    @Test fun `opposite scroll directions cannot share a physical lane`() {
        for ((first, second) in listOf(1 to 6, 6 to 1)) {
            val scheduler = DanmakuScheduler(listOf(item(0, 0.0, first), item(1, 4.0, second),
                item(2, 10.0, second)), settings, false)
            assertEquals(listOf(0), ids(frame(scheduler, 4.0)))
            for (time in 5..10) frame(scheduler, time.toDouble())
            assertEquals(listOf(2), ids(frame(scheduler, 10.0)))
        }
    }

    @Test fun `fixed and scrolling layers cannot share a physical lane`() {
        val config = config(5)
        val fixedFirst = DanmakuScheduler(listOf(item(0, 0.0, 5)) +
            (1..4).map { item(it, 0.0) } + item(5, 1.0), settings, false)
        assertEquals(listOf(0, 1, 2, 3, 4), ids(frame(fixedFirst, 1.0, config)))

        val scrollFirst = DanmakuScheduler((0..4).map { item(it, 0.0) } +
            item(5, 1.0, 5) + item(6, 10.0, 5), settings, false)
        assertEquals(listOf(0, 1, 2, 3, 4), ids(frame(scrollFirst, 1.0, config)))
        for (time in 2..10) frame(scrollFirst, time.toDouble(), config)
        assertEquals(listOf(6), ids(frame(scrollFirst, 10.0, config)))
    }

    @Test fun `pause resize and seek retain their timeline semantics`() {
        val scheduler = DanmakuScheduler(listOf(item(0, 0.0), item(1, 4.0)), settings, false)
        val original = config()
        val first = frame(scheduler, 0.0, original).single()
        assertEquals(first, frame(scheduler, 0.0, original).single())
        val resized = original.copy(scrollDurationMs = 20_000L)
        for (time in 1..4) frame(scheduler, time.toDouble(), resized)
        val atFour = frame(scheduler, 4.0, resized)
        assertEquals(listOf(0, 1), ids(atFour)) // Old item keeps its 10-second lifetime.
        assertEquals(260.0, atFour[0].x, 0.000001)
        assertEquals(atFour, frame(scheduler, 4.0, resized))
        assertEquals(listOf(0), ids(frame(scheduler, 0.0, resized, width = 600)))
    }
}
