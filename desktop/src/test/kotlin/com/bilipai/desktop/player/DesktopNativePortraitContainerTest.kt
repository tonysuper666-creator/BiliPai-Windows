package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopNativePortraitContainerTest {
    @Test fun actualContainerKeepsEdgePaddingOutsideTheSeekTarget() {
        val seeks = mutableListOf<Long>()
        val scene = ImageComposeScene(400, 100, Density(1f)) {
            Box(Modifier.fillMaxSize()) {
                PortraitBottomContainer(progress = 0.25f, duration = 100_000L,
                    onSeek = { seeks += it }, onSeekStart = {})
            }
        }
        fun click(x: Float, time: Long) {
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 20f),
                timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 20f),
                timeMillis = time + 40, buttons = PointerButtons())
            scene.render().close()
        }
        try {
            scene.render().close()
            assertTrue(seeks.isEmpty())
            click(10f, 100)
            assertTrue(seeks.isEmpty(), "20dp native edge padding is not a seek target")
            click(200f, 200)
            assertEquals(listOf(50_000L), seeks)
        } finally { scene.close() }
    }
}
