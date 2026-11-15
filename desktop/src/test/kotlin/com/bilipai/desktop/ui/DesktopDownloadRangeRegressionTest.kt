package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopDownloadRangeRegressionTest {
    @Test fun additiveRangeCanShrinkWithoutLosingTheOriginalSelection() {
        val state = DesktopDownloadListSelection()
        state.reconcile(listOf("a", "b", "c", "d", "e"))
        state.click("a")
        state.click("d", ctrl = true)
        state.click("b", ctrl = true, shift = true)
        assertEquals(setOf("a", "b", "c", "d"), state.selected)
        state.click("c", ctrl = true, shift = true)
        assertEquals(setOf("a", "c", "d"), state.selected)
    }

    @Test fun additiveRangeTracksStableIdsAfterReorderAndResetsForANewAnchor() {
        val state = DesktopDownloadListSelection()
        state.reconcile(listOf("a", "b", "c", "d", "e"))
        state.click("a")
        state.click("d", ctrl = true)
        state.click("b", ctrl = true, shift = true)
        state.reconcile(listOf("e", "d", "c", "b", "a"))
        state.click("c", ctrl = true, shift = true)
        assertEquals(setOf("a", "c", "d"), state.selected)
        state.click("e")
        state.click("c", ctrl = true, shift = true)
        assertEquals(setOf("e", "d", "c"), state.selected)
    }

    @Test fun actualPointerModifierUsesModifiersAndTargetsThePointerRow() {
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        val state = DesktopDownloadListSelection()
        state.reconcile(listOf("a", "b", "c", "d"))
        val focus = FocusRequester()
        val scene = ImageComposeScene(200, 160, Density(1f), coroutineContext = scope.coroutineContext) {
            Column(Modifier.downloadSelectionKeyboard(state, focus)) {
                listOf("a", "b", "c", "d").forEach { id ->
                    Box(Modifier.fillMaxWidth().height(40.dp).downloadSelectionPointer(id, state, focus))
                }
            }
        }
        fun render() {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), "Unexpected pointer errors: $errors")
        }
        fun click(row: Int, ctrl: Boolean = false, shift: Boolean = false, right: Boolean = false) {
            val pos = Offset(20f, 20f + 40f * row)
            val button = if (right) PointerButton.Secondary else PointerButton.Primary
            val keys = PointerKeyboardModifiers(isCtrlPressed = ctrl, isShiftPressed = shift)
            scene.sendPointerEvent(PointerEventType.Press, pos, type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = !right, isSecondaryPressed = right),
                keyboardModifiers = keys, button = button)
            render()
            scene.sendPointerEvent(PointerEventType.Release, pos, type = PointerType.Mouse,
                buttons = PointerButtons(), keyboardModifiers = keys, button = button)
            render()
        }
        try {
            render()
            click(0)
            click(3, ctrl = true)
            click(1, ctrl = true, shift = true)
            assertEquals(setOf("a", "b", "c", "d"), state.selected)
            click(2, ctrl = true, shift = true)
            assertEquals(setOf("a", "c", "d"), state.selected)
            click(1, right = true)
            assertEquals("b", state.contextTarget)
            assertEquals(setOf("a", "c", "d"), state.selected)
            scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyUp))
            render()
            assertEquals("c", state.focused)
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
            render()
            assertEquals(setOf("a", "d"), state.selected)
            scene.sendKeyEvent(KeyEvent(Key.DirectionUp, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.DirectionUp, KeyEventType.KeyUp))
            render()
            assertEquals("b", state.focused)
        } finally {
            scene.close()
            scope.cancel()
            scheduler.runCurrent()
        }
    }
}
