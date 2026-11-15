package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.android.purebilibili.feature.video.ui.components.CommentInputDraft
import com.android.purebilibili.feature.video.ui.components.CommentInputDraftEffect
import com.android.purebilibili.feature.video.ui.components.rememberCommentInputDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * The keyed draft seam the generated CommentInputDialog runs (rememberCommentInputDraft + CommentInputDraftEffect), composed
 * without a window. The dialog's own text field is an AWT panel and its Dialog a window, so the state seam is driven directly;
 * the screen's close/send guards and the real image callback are not covered here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBgmComposerDraftKeyTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Inputs(
        val key: String, val text: String, val images: List<String>, val sync: Boolean, val selStart: Int = -1, val selEnd: Int = -1,
    )

    private class Host(private val scheduler: TestCoroutineScheduler) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val runner = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val composition = Composition(EmptyApplier(), recomposer)
        val inputs = mutableStateOf(Inputs("k1", "A-text", listOf("a.png"), false, 1, 1))
        val visible = mutableStateOf(true)
        val tick = mutableStateOf(0)
        var hidden = 0
        var seeded = 0
        lateinit var draft: CommentInputDraft

        init {
            composition.setContent {
                tick.value
                val i = inputs.value
                draft = rememberCommentInputDraft(i.key, i.text, i.images, i.sync)
                CommentInputDraftEffect(draft, visible.value, i.key, i.text, i.images, i.sync, i.selStart, i.selEnd,
                    onHidden = { hidden++ }, onSeeded = { seeded++ })
            }
            frames()
        }

        fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                scheduler.runCurrent()
                clock.sendFrame(scheduler.currentTime * 1_000_000)
                scheduler.runCurrent()
                scheduler.advanceTimeBy(20)
            }
        }

        override fun close() { composition.dispose(); recomposer.close(); runner.cancel(); scope.cancel() }
    }

    @Test fun changingTheKeyLoadsThatKeysTextSelectionImagesAndSync() = runTest {
        Host(testScheduler).use { h ->
            assertEquals("A-text", h.draft.textFieldValue.text)
            assertEquals(TextRange(1, 1), h.draft.textFieldValue.selection)
            assertEquals(listOf("a.png"), h.draft.selectedImageUris)
            assertFalse(h.draft.isForwardToDynamic)
            h.inputs.value = Inputs("k2", "BB", listOf("b1.png", "b2.png"), true, 0, 2)
            h.frames()
            assertEquals("BB", h.draft.textFieldValue.text)
            assertEquals(TextRange(0, 2), h.draft.textFieldValue.selection)
            assertEquals(listOf("b1.png", "b2.png"), h.draft.selectedImageUris)
            assertTrue(h.draft.isForwardToDynamic)
            h.inputs.value = Inputs("k3", "", emptyList(), false)
            h.frames()
            assertEquals(TextFieldValue("", TextRange(0)), h.draft.textFieldValue)
            assertEquals(emptyList(), h.draft.selectedImageUris)
            assertFalse(h.draft.isForwardToDynamic)
            assertEquals(3, h.seeded, "Each key is seeded exactly once")
        }
    }

    @Test fun recompositionWithTheSameKeyKeepsTheEditsAndDoesNotReseed() = runTest {
        Host(testScheduler).use { h ->
            h.draft.textFieldValue = TextFieldValue("edited", TextRange(3))
            h.draft.selectedImageUris = listOf("a.png", "c.png")
            h.draft.isForwardToDynamic = true
            // The upstream draft flows back with every edit and the caller passes it again as the initial values.
            h.inputs.value = Inputs("k1", "edited", listOf("a.png", "c.png"), true, 6, 6)
            h.tick.value++
            h.frames()
            assertEquals(TextFieldValue("edited", TextRange(3)), h.draft.textFieldValue, "Caret is not overwritten")
            assertEquals(listOf("a.png", "c.png"), h.draft.selectedImageUris)
            assertTrue(h.draft.isForwardToDynamic)
            assertEquals(1, h.seeded)
        }
    }

    @Test fun hidingClearsFocusOnceAndReopeningLoadsTheCurrentDraft() = runTest {
        Host(testScheduler).use { h ->
            h.draft.textFieldValue = TextFieldValue("typed", TextRange(5))
            h.visible.value = false
            h.frames()
            assertEquals(1, h.hidden)
            h.visible.value = false
            h.tick.value++
            h.frames()
            assertEquals(1, h.hidden, "Focus is cleared once per visible session")
            h.inputs.value = Inputs("k1", "typed", listOf("a.png"), false, 5, 5)
            h.visible.value = true
            h.frames()
            assertEquals(2, h.seeded)
            assertEquals(TextFieldValue("typed", TextRange(5)), h.draft.textFieldValue)
        }
    }

    @Test fun lateWritesThroughAnOldKeysHolderCannotReachTheNewKey() = runTest {
        Host(testScheduler).use { h ->
            val old = h.draft
            h.inputs.value = Inputs("k2", "new", listOf("n.png"), true)
            h.frames()
            val current = h.draft
            assertNotSame(old, current)
            old.textFieldValue = TextFieldValue("late old text")
            old.selectedImageUris = listOf("late.png")
            old.isForwardToDynamic = false
            old.inputWasVisible = false
            h.frames()
            assertEquals("new", current.textFieldValue.text)
            assertEquals(listOf("n.png"), current.selectedImageUris)
            assertTrue(current.isForwardToDynamic)
            assertTrue(current.inputWasVisible)
        }
    }
}
