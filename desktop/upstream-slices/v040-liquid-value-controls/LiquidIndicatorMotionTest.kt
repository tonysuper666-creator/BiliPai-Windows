package com.android.purebilibili.core.ui.animation

import kotlin.test.Test
import kotlin.test.assertEquals

class LiquidIndicatorMotionTest {
    @Test
    fun `zero velocity preserves press scale`() {
        val scale = LiquidIndicatorMotion.pressedScale
        assertEquals(scale, LiquidIndicatorMotion.scaleX(scale, 0f))
        assertEquals(scale, LiquidIndicatorMotion.scaleY(scale, 0f))
    }

    @Test
    fun `directional deformation remains bounded at extreme velocities`() {
        assertEquals(1.25f, LiquidIndicatorMotion.scaleX(1f, 1000f), 0.0001f)
        assertEquals(0.8f, LiquidIndicatorMotion.scaleY(1f, 1000f), 0.0001f)
        assertEquals(1f / 1.2f, LiquidIndicatorMotion.scaleX(1f, -1000f), 0.0001f)
        assertEquals(1.2f, LiquidIndicatorMotion.scaleY(1f, -1000f), 0.0001f)
    }
}
