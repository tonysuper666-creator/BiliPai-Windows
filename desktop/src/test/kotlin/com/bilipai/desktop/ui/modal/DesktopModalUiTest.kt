@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.modal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.feature.profile.HomeWallpaperAdjustmentSheet
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import kotlin.test.*

class DesktopModalUiTest {
    private class Scene(reduced: Boolean = true, content: @Composable () -> Unit) : AutoCloseable {
        private var time = 0L
        private val scheduler = TestCoroutineScheduler()
        private val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = StandardTestDispatcher(scheduler)) {
            MaterialTheme {
                val original = LocalWindowInfo.current
                val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopKitReducedMotion provides reduced) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        init { check(GraphicsEnvironment.isHeadless()) }
        fun advance(frames: Int) { repeat(frames) {
            scheduler.runCurrent()
            time += 30_000_000
            scene.render(time).close()
            scheduler.runCurrent()
        } }
        fun settle() = advance(8)
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun tagged(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun focusedTag() = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.config?.getOrNull(SemanticsProperties.TestTag)
        fun key(key: Key, shift: Boolean = false, settle: Boolean = true) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
            if (settle) settle()
        }
        fun click(tag: String) {
            val p = tagged(tag).boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons()); settle()
        }
        fun scroll(point: Offset) {
            scene.sendPointerEvent(PointerEventType.Scroll, point, scrollDelta = Offset(0f, 8f)); settle()
        }
        override fun close() = scene.close()
    }

    @Test fun actualOptInSheetEntersFocusCyclesBothDirectionsAndRestoresTheOpener() {
        var open by mutableStateOf(false)
        Scene {
            Button({ open = true }, Modifier.testTag("opener")) { Text("Open") }
            if (open) AppModalBottomSheet(purpose = DesktopModalPurpose.CONFIRM, onDismissRequest = { open = false },
                footer = {
                    Button({}, Modifier.testTag("cancel")) { Text("Cancel") }
                    Button({}, Modifier.testTag("save")) { Text("Save") }
                }) { Text("Draft") }
        }.use { s ->
            s.settle(); s.click("opener")
            assertEquals("cancel", s.focusedTag())
            s.key(Key.Tab); assertEquals("save", s.focusedTag())
            s.key(Key.Tab); assertEquals("cancel", s.focusedTag())
            s.key(Key.Tab, shift = true); assertEquals("save", s.focusedTag())
            s.key(Key.Escape); assertFalse(open); assertEquals("opener", s.focusedTag())
        }
    }

    @Test fun escapeClosesOnlyTheTopmostLayerAndReturnsToTheParentControl() {
        var outer by mutableStateOf(true)
        var inner by mutableStateOf(false)
        Scene {
            if (outer) DesktopPurposeModal(DesktopModalPurpose.CONFIRM, { outer = false }) {
                Button({ inner = true }, Modifier.testTag("nested-opener")) { Text("Nested") }
                if (inner) DesktopPurposeModal(DesktopModalPurpose.OPTIONS, { inner = false }, anchor = IntRect(100, 100, 200, 130)) {
                    DesktopModalOption({}) { Text("Option") }
                }
            }
        }.use { s ->
            s.settle(); s.click("nested-opener"); assertTrue(inner)
            s.key(Key.Escape); assertFalse(inner); assertTrue(outer)
            assertEquals("nested-opener", s.focusedTag())
            s.key(Key.Escape); assertFalse(outer)
        }
    }

    @Test fun confirmationScrollsItsBodyWithFixedFooterAndNeverScrollsTheBackground() {
        var backgroundScroll = 0
        Scene {
            val background = rememberScrollState()
            SideEffect { backgroundScroll = background.value }
            Column(Modifier.fillMaxSize().verticalScroll(background)) { repeat(80) { Text("Background $it") } }
            DesktopPurposeModal(DesktopModalPurpose.CONFIRM, {}, footer = {
                Button({}, Modifier.testTag("save")) { Text("Save") }
            }) { repeat(80) { Text("Draft $it", Modifier.height(32.dp)) } }
        }.use { s ->
            s.settle()
            val footer = s.tagged("desktop-modal-footer").boundsInWindow
            val scroll = s.tagged("desktop-modal-body").config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(scroll.maxValue() > 1000f)
            assertEquals(0f, scroll.value())
            s.scroll(Offset(400f, 250f)); assertTrue(scroll.value() > 0)
            assertEquals(footer, s.tagged("desktop-modal-footer").boundsInWindow)
            s.scroll(Offset(20f, 250f)); assertEquals(0, backgroundScroll)
        }
    }

    @Test fun optionsUseRealAnchorFlipCompactRowsAndViewportClamping() {
        Scene {
            DesktopPurposeModal(DesktopModalPurpose.OPTIONS, {}, anchor = IntRect(750, 520, 800, 550), designWidth = 220.dp) {
                repeat(3) { DesktopModalOption({}) { Text("Option $it") } }
            }
        }.use { s ->
            s.settle()
            val b = s.tagged("desktop-modal-surface").boundsInWindow
            assertEquals(220f, b.width); assertEquals(108f, b.height)
            assertEquals(564f, b.left); assertEquals(404f, b.top)
        }
    }

    @Test fun readingPanelUsesTheSameRightAlignedLayerAsTheNativeHost() {
        var closed = 0
        Scene {
            DesktopModalLayer(DesktopModalPurpose.READING_PANEL, { closed++ }, Modifier.fillMaxSize()) {
                Button({}, Modifier.testTag("reading-control")) { Text("Read") }
                repeat(50) { Text("Paragraph $it") }
            }
        }.use { s ->
            s.settle()
            val b = s.tagged("desktop-modal-surface").boundsInWindow
            assertEquals(460f, b.left); assertEquals(800f, b.right); assertEquals(600f, b.height)
            assertEquals("reading-control", s.focusedTag())
            s.key(Key.Escape); assertEquals(1, closed)
        }
    }

    @Test fun readingPanelCompletesItsExitBeforeDismissingAndReducedMotionSkipsTheWait() {
        for (reduced in listOf(false, true)) {
            var closed = 0
            Scene(reduced) {
                DesktopModalLayer(DesktopModalPurpose.READING_PANEL, { closed++ }, Modifier.fillMaxSize()) {
                    Text("Read")
                }
            }.use { s ->
                s.settle()
                s.key(Key.Escape, settle = false)
                s.advance(3) // 90 ms: shorter than the specified 160 ms.
                assertEquals(if (reduced) 1 else 0, closed)
                s.advance(5)
                assertEquals(1, closed)
            }
        }
    }

    @Test fun actualWallpaperPilotKeepsSavingActionsDisabledAndRejectsEscapeUntilSavingEnds() {
        var saving by mutableStateOf(true)
        var closed = 0
        Scene {
            HomeWallpaperAdjustmentSheet(uri = "file:/nonexistent.mp4", saving = saving,
                onDismiss = { closed++ }, onSave = { _, _ -> error("Saving must stay disabled") })
        }.use { s ->
            s.settle()
            val surface = s.tagged("desktop-modal-surface").boundsInWindow
            val footer = s.tagged("desktop-modal-footer").boundsInWindow
            assertEquals(160f, surface.left); assertEquals(640f, surface.right)
            assertTrue(footer.bottom <= 576f && footer.height > 0f)
            val actions = s.nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null &&
                footer.contains(it.boundsInWindow.center) }
            assertEquals(2, actions.size)
            assertTrue(actions.all { it.config.contains(SemanticsProperties.Disabled) })
            s.key(Key.Escape); assertEquals(0, closed)
            saving = false; s.settle()
            s.key(Key.Escape); assertEquals(1, closed)
        }
    }
}
