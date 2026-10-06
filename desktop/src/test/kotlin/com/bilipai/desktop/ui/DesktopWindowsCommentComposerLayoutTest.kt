@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import kotlin.test.*

/** Actual Compose measurement of the production modifier ports. This does not
 * instantiate a Window, Swing editor, native player or a second composer VM. */
class DesktopWindowsCommentComposerLayoutTest {
    @Test fun nativeClientPlacesEditorAtTopAndToolbarAtBottomWithoutUnusedSheetSpace() {
        val fixture = Fixture(native = true, initialHeight = 620)
        try {
            fixture.render()
            assertEquals(16f, fixture.editor.top)
            assertEquals(536f, fixture.editor.height)
            assertEquals(552f, fixture.toolbar.top)
            assertEquals(604f, fixture.toolbar.bottom)
            assertEquals(620, fixture.panelHeightBasis)
        } finally { fixture.close() }
    }

    @Test fun resizingAndOptionalPanelsReserveSpaceWithoutReplacingComposition() {
        val fixture = Fixture(native = true, initialHeight = 500)
        try {
            fixture.panel.value = 200
            fixture.images.value = 88
            fixture.render()
            val sameComposition = fixture.compositionIdentity
            assertEquals(128f, fixture.editor.height)
            assertEquals(500, fixture.panelHeightBasis)
            fixture.height.value = 620
            fixture.panel.value = 240
            fixture.render()
            assertSame(sameComposition, fixture.compositionIdentity)
            assertEquals(208f, fixture.editor.height)
            assertEquals(604f, fixture.toolbar.bottom)
            assertTrue(fixture.optionalBottom <= fixture.toolbar.top)
            assertEquals(620, fixture.panelHeightBasis)
            fixture.height.value = 400
            fixture.panel.value = 140
            fixture.render()
            assertSame(sameComposition, fixture.compositionIdentity)
            assertEquals(88f, fixture.editor.height)
            assertEquals(384f, fixture.toolbar.bottom)
            assertEquals(400, fixture.panelHeightBasis)
        } finally { fixture.close() }
    }

    @Test fun legacyCallerRetainsOriginalWrapHeightAndInputLimits() {
        val fixture = Fixture(native = false, initialHeight = 620)
        try {
            fixture.render()
            assertEquals(112f, fixture.editor.height)
            assertEquals(440f, fixture.editor.top)
            assertEquals(604f, fixture.toolbar.bottom)
            assertEquals(999, fixture.panelHeightBasis)
        } finally { fixture.close() }
    }

    @Test fun attachmentChangesKeepTheActualToolbarBudgetAndEditorCompositionAtNarrowHighScale() {
        check(GraphicsEnvironment.isHeadless())
        for (density in listOf(1.25f, 2f)) {
            val count = mutableStateOf(0)
            var editor = Rect.Zero
            var send = Rect.Zero
            var tray = Rect.Zero
            var firstImage = Rect.Zero
            var identity: Any? = null
            var panelBudget = -1
            val scene = ImageComposeScene(768, 720, Density(density)) {
                CompositionLocalProvider(LocalDesktopNativeCommentComposerClient provides true) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Box(Modifier.fillMaxWidth().then(desktopCommentComposerInputHeight(64.dp, 112.dp))
                            .onGloballyPositioned { editor = it.boundsInRoot() }) {
                            identity = remember { Any() }
                        }
                        panelBudget = desktopCommentComposerImagePanelBudget(count.value)
                        // Production toolbar measures both finite scroll slots and the
                        // fixed send slot. Sentinels only replace unchanged child UI.
                        DesktopCommentComposerToolbar(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            toolHeight = 40.dp, toolSpacing = 2.dp,
                            attachments = if (count.value > 0) {{
                                Box(Modifier.size(24.dp, 16.dp))
                                Row(Modifier.weight(1f).onGloballyPositioned { tray = it.boundsInRoot() }
                                    .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    repeat(count.value) { index ->
                                        key(index) {
                                            Box(Modifier.size(40.dp).then(if (index == 0)
                                                Modifier.onGloballyPositioned { firstImage = it.boundsInRoot() }
                                                else Modifier))
                                        }
                                    }
                                }
                            }} else null,
                            tools = { repeat(5) { Box(Modifier.size(40.dp)) } },
                            send = { Box(Modifier.size(64.dp, 36.dp)
                                .onGloballyPositioned { send = it.boundsInRoot() }) },
                        )
                    }
                }
            }
            try {
                fun render() { repeat(4) { scene.render(System.nanoTime()).close() } }
                render()
                val originalEditor = editor
                val originalSend = send
                val originalIdentity = identity
                for (images in listOf(1, 9, 0)) {
                    count.value = images; render()
                    assertEquals(originalEditor, editor)
                    assertEquals(originalSend, send)
                    assertSame(originalIdentity, identity)
                    assertEquals(0, panelBudget)
                    assertTrue(send.right <= 768f && send.bottom <= 720f)
                    if (images > 0) {
                        assertTrue(tray.width >= 40f * density, "one whole thumbnail must fit at $density")
                        assertTrue(firstImage.left >= tray.left && firstImage.right <= tray.right)
                        assertTrue(firstImage.top >= editor.bottom && firstImage.bottom <= send.bottom + 4f * density)
                    }
                }
            } finally { scene.close() }
        }
    }

    private class Fixture(native: Boolean, initialHeight: Int) : AutoCloseable {
        val height = mutableStateOf(initialHeight)
        val panel = mutableStateOf(0)
        val images = mutableStateOf(0)
        var editor = Rect.Zero
        var toolbar = Rect.Zero
        var optionalBottom = 0f
        var panelHeightBasis = 0
        var compositionIdentity: Any? = null
        private var nanos = 0L
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless())
            scene = ImageComposeScene(480, 620, Density(1f)) {
                Box(Modifier.fillMaxWidth().height(height.value.dp)) {
                    DesktopDetailWindow {
                        CompositionLocalProvider(LocalDesktopNativeCommentComposerClient provides native) {
                            compositionIdentity = remember { Any() }
                            panelHeightBasis = desktopCommentComposerClientHeightDp(999)
                            // These are the original host/surface/Column modifier
                            // positions; sentinel children stand for the unchanged
                            // editor, optional panels and toolbar only for sizing.
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                Box(Modifier.fillMaxWidth().then(desktopCommentComposerSurfaceHeight())) {
                                    Column(Modifier.then(desktopCommentComposerColumnHeight()).padding(16.dp)) {
                                        Box(Modifier.fillMaxWidth()
                                            .then(desktopCommentComposerInputHeight(64.dp, 112.dp))
                                            .onGloballyPositioned { editor = it.boundsInRoot() }) {
                                            Box(Modifier.fillMaxSize())
                                        }
                                        if (images.value > 0) Box(Modifier.fillMaxWidth().height(images.value.dp))
                                        if (panel.value > 0) Box(Modifier.fillMaxWidth().height(panel.value.dp)
                                            .onGloballyPositioned { optionalBottom = it.boundsInRoot().bottom })
                                        Box(Modifier.fillMaxWidth().height(52.dp)
                                            .onGloballyPositioned { toolbar = it.boundsInRoot() })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        fun render() {
            repeat(4) {
                nanos = maxOf(System.nanoTime(), nanos + 1)
                scene.render(nanos).close()
            }
            check(editor.width > 0 && editor.height > 0 && toolbar.height > 0)
        }
        override fun close() = scene.close()
    }
}
