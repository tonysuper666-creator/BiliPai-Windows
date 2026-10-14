package com.android.purebilibili.core.ui.animation

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidIndicatorVelocityTest {
    @Test
    fun `rapid targets converge to latest and idle stops requesting frames`() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job() + clock)
        try {
            val velocity = LiquidIndicatorVelocity(scope)
            velocity.updateTarget(20f)
            velocity.updateTarget(-5f)
            velocity.updateTarget(3f)
            repeat(180) { clock.sendFrame(it * 16_666_667L) }
            assertEquals(3f, velocity.value)
            assertFalse(clock.hasAwaiters)

            velocity.updateTarget(0f)
            assertTrue(clock.hasAwaiters)
            repeat(180) { clock.sendFrame((180L + it) * 16_666_667L) }
            assertEquals(0f, velocity.value)
            assertFalse(clock.hasAwaiters)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `synchronous reset cannot be overwritten by an earlier spring`() {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job() + clock)
        try {
            val velocity = LiquidIndicatorVelocity(scope)
            velocity.updateTarget(10f)
            clock.sendFrame(0L)
            clock.sendFrame(16_666_667L)
            assertTrue(velocity.value > 0f)
            velocity.snapTo(0f)
            repeat(10) { clock.sendFrame((2L + it) * 16_666_667L) }
            assertEquals(0f, velocity.value)
            assertFalse(clock.hasAwaiters)
        } finally {
            scope.cancel()
        }
    }
}
