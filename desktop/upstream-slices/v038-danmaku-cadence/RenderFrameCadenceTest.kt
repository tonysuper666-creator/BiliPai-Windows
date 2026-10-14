package com.bytedance.danmaku.render.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RenderFrameCadenceTest {
    @Test
    fun `60 90 and 120Hz displays produce 60 renderer frames per second`() {
        listOf(60, 90, 120).forEach { hz ->
            val cadence = RenderFrameCadence()
            var rendered = 0
            repeat(hz * 10) { tick ->
                val time = tick * 1_000_000_000L / hz
                if (cadence.shouldRender(time)) {
                    cadence.onFrame(time)
                    rendered++
                }
            }
            assertEquals(600, rendered, "display=$hz Hz")
        }
    }

    @Test
    fun `resume has no delayed or accumulated frame debt`() {
        val cadence = RenderFrameCadence()
        cadence.onFrame(1_000_000_000L)
        assertFalse(cadence.shouldRender(1_008_333_333L))
        cadence.onFrame(9_000_000_000L)
        assertFalse(cadence.shouldRender(9_008_333_333L))
        cadence.reset()
        assertTrue(cadence.shouldRender(9_008_333_333L))
    }
}
