package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopChatImeLayoutTest {
    @Test
    fun sharedMessageAndComposerBoundsFollowImeAcrossTogglesAndNarrowWidths() {
        val imeBottom = androidx.compose.runtime.mutableIntStateOf(0)
        val windowWidth = androidx.compose.runtime.mutableIntStateOf(360)
        val duplicateChildPadding = androidx.compose.runtime.mutableIntStateOf(false)
        val bounds = mutableMapOf<String, Rect>()
        val scene = ImageComposeScene(400, 800, Density(1f)) {
            Box(Modifier.width(windowWidth.intValue.dp).height(800.dp)) {
                Column(Modifier.fillMaxSize().desktopChatImePadding(WindowInsets(bottom = imeBottom.intValue))) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().testTag("messages")
                            .onGloballyPositioned { bounds["messages"] = it.boundsInRoot() },
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(bottom = if (duplicateChildPadding.intValue) imeBottom.intValue.dp else 0.dp)
                            .height(48.dp)
                            .testTag("composer")
                            .onGloballyPositioned { bounds["composer"] = it.boundsInRoot() },
                    ) {
                        Box(
                            Modifier.fillMaxSize().testTag("composerSurface")
                                .onGloballyPositioned { bounds["composerSurface"] = it.boundsInRoot() },
                        )
                    }
                }
            }
        }
        var frame = 0L
        fun settle() {
            repeat(4) { frame += 16_000_000L; scene.render(frame).close() }
        }
        fun assertBounds(width: Float, composerBottom: Float, messageBottom: Float) {
            assertEquals(width, bounds.getValue("composer").right)
            assertEquals(composerBottom, bounds.getValue("composer").bottom)
            assertEquals(composerBottom, bounds.getValue("composerSurface").bottom)
            assertEquals(messageBottom, bounds.getValue("messages").bottom)
        }

        try {
            settle()
            assertBounds(360f, 800f, 752f)

            imeBottom.intValue = 200
            settle()
            assertBounds(360f, 600f, 552f)

            imeBottom.intValue = 0
            settle()
            assertBounds(360f, 800f, 752f)
            imeBottom.intValue = 200
            settle()
            assertBounds(360f, 600f, 552f)

            windowWidth.intValue = 320
            settle()
            assertBounds(320f, 600f, 552f)

            // The old child-local pattern stacks its inset on the shared parent.
            duplicateChildPadding.intValue = true
            settle()
            assertEquals(320f, bounds.getValue("composer").right)
            assertEquals(600f, bounds.getValue("composer").bottom)
            assertEquals(400f, bounds.getValue("composerSurface").bottom)
            assertEquals(352f, bounds.getValue("messages").bottom)
        } finally {
            scene.close()
        }
    }
}
