package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
class DesktopPortraitProgressCompositionTest {
    @Test
    fun glassAndPlainTracksKeepTheSameRealPointerSeekTimeline() {
        for (glass in listOf(false, true)) {
            val events = mutableListOf<String>()
            val scene = ImageComposeScene(400, 48, Density(1f)) {
                CompositionLocalProvider(LocalPlayerControlGlassEnabled provides glass) {
                    ThinWigglyProgressBar(
                        progress = 0.25f, seekPositionMs = 25_000L,
                        isSeekScrubbing = false,
                        layoutPolicy = resolvePortraitProgressBarLayoutPolicy(400),
                        onSeek = { events += "seek:$it" },
                        onSeekStart = { events += "start" },
                        onSeekDragStart = { events += "drag:$it" },
                        onSeekDragUpdate = { events += "update:$it" },
                        duration = 100_000L, bufferProgress = 0.8f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            try {
                scene.render().close()
                assertTrue(events.isEmpty(), "drawing must not seek")
                scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 24f),
                    timeMillis = 100, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 24f),
                    timeMillis = 140, buttons = PointerButtons())
                scene.render().close()
                assertEquals(listOf("start", "drag:75000", "update:75000", "seek:0.75"), events)
            } finally { scene.close() }
        }
    }

    @Test
    fun highlightUsesTheRealGlassCompositionSetting() {
        for (glass in listOf(false, true)) {
            var enabled: Boolean? = null
            val scene = ImageComposeScene(64, 48, Density(1.5f)) {
                CompositionLocalProvider(LocalPlayerControlGlassEnabled provides glass) {
                    enabled = rememberPlayerProgressGlassHighlight() != null
                }
            }
            try { scene.render().close(); assertEquals(glass, enabled) }
            finally { scene.close() }
        }
    }
}
