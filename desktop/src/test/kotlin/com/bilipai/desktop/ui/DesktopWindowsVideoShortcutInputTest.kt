@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.*
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The window-level player shortcut router with fake capabilities and recorders: held presses, claims, the arrow keys of a focused control,
 * Esc under decision D4, and the composition with the existing Space and Esc input in one slot of the window key input. No real OS input,
 * no window, no GPU, no network. Every call runs on the event dispatch thread, as the router requires.
 */
class DesktopWindowsVideoShortcutInputTest {
    private companion object {
        const val SPACE = 32
        const val ESC = 27
        const val ENTER = 10
        const val LEFT = 37
        const val UP = 38
        const val RIGHT = 39
        const val DOWN = 40
        const val HOME = 36
        const val END = 35
        const val COMMA = 44
        const val MENU = 525
        const val PLUS = 521
        const val TAB = 9
        const val K = 75
        const val M = 77
        const val X = 88
        const val F10 = 121
        const val DIGIT_1 = 49
        const val DIGIT_5 = 53
        const val NUMPAD_5 = 101
    }

    private fun act(kind: DesktopPlayerActionKind, arg: Int = 0) = DesktopPlayerAction(kind, arg)

    private class Fixture {
        var source: Any? = Any()
        var owns = true
        var controlHolds = false
        var editor = false
        var fullscreen = false
        var paused = false
        var layout = DesktopWindowsUxPlayerLayout.LEGACY
        var accept = true
        val performed = mutableListOf<DesktopPlayerAction>()
        val input = DesktopWindowsVideoShortcutInput(
            currentSource = { source },
            ownsFocus = { owns },
            controlHoldsFocus = { controlHolds },
            textEditorFocused = { editor },
            isFullscreen = { fullscreen },
            isPaused = { paused },
            layout = { layout },
            perform = { action -> performed += action; accept },
        )
    }

    private fun key(
        code: Int,
        type: KeyEventType = KeyEventType.KeyDown,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ): KeyEvent = KeyEvent(Key(code), type, isCtrlPressed = ctrl, isAltPressed = alt, isMetaPressed = meta, isShiftPressed = shift)

    private fun up(code: Int) = key(code, KeyEventType.KeyUp)

    private fun edt(block: () -> Unit) { EventQueue.invokeAndWait(block) }

    @Test fun aClaimedPressOwnsItsRepeatsAndItsReleaseAndRunsOnlyOnce() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key(K)))
        repeat(3) { assertTrue(f.input.onKey(key(K))) }
        assertEquals(listOf(act(DesktopPlayerActionKind.PLAY_PAUSE)), f.performed)
        assertTrue(f.input.onKey(up(K)))
        assertTrue(f.input.onKey(key(K)))
        assertEquals(2, f.performed.size)
        assertTrue(f.input.onKey(up(K)))
        assertFalse(f.input.onKey(up(K)))
    }

    @Test fun seekingAndTheVolumeRunAgainForEveryRepeatOfAHeldKey() = edt {
        val f = Fixture()
        repeat(3) { assertTrue(f.input.onKey(key(RIGHT))) }
        assertEquals(List(3) { act(DesktopPlayerActionKind.SEEK, 5) }, f.performed)
        assertTrue(f.input.onKey(up(RIGHT)))
        f.performed.clear()
        repeat(2) { assertTrue(f.input.onKey(key(UP))) }
        repeat(2) { assertTrue(f.input.onKey(key(DOWN))) }
        assertEquals(listOf(5, 5, -5, -5), f.performed.map { it.arg })
        assertTrue(f.performed.all { it.kind == DesktopPlayerActionKind.VOLUME })
    }

    @Test fun aHeldKeyWhoseModifierChangedDoesNotRunADifferentActionOnItsRepeat() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key(RIGHT)))
        assertTrue(f.input.onKey(key(RIGHT, shift = true)))
        assertEquals(listOf(act(DesktopPlayerActionKind.SEEK, 5)), f.performed)
        assertTrue(f.input.onKey(up(RIGHT)))
    }

    @Test fun modifiersSelectTheirOwnRowsOfTheTable() = edt {
        val f = Fixture()
        val cases = listOf(
            key(RIGHT, shift = true) to act(DesktopPlayerActionKind.SEEK, 10),
            key(RIGHT, ctrl = true) to act(DesktopPlayerActionKind.CHAPTER, 1),
            key(ENTER, alt = true) to act(DesktopPlayerActionKind.FULLSCREEN_TOGGLE),
            key(COMMA, shift = true) to act(DesktopPlayerActionKind.SPEED_STEP, -1),
            key(DIGIT_1, shift = true) to act(DesktopPlayerActionKind.SPEED_SET, 1),
            key(DIGIT_5) to act(DesktopPlayerActionKind.SEEK_PERCENT, 50),
            key(NUMPAD_5) to act(DesktopPlayerActionKind.SEEK_PERCENT, 50),
            key(MENU) to act(DesktopPlayerActionKind.CONTEXT_MENU),
            key(F10, shift = true) to act(DesktopPlayerActionKind.CONTEXT_MENU),
            key(HOME) to act(DesktopPlayerActionKind.SEEK_START),
            key(END) to act(DesktopPlayerActionKind.SEEK_END),
        )
        for ((event, expected) in cases) {
            f.performed.clear()
            assertTrue(f.input.onKey(event), expected.toString())
            assertEquals(listOf(expected), f.performed)
            assertTrue(f.input.onKey(KeyEvent(event.key, KeyEventType.KeyUp)))
        }
    }

    @Test fun metaCtrlAltZoomKeysTabAndKeysWithoutARowAreNotClaimed() = edt {
        val f = Fixture()
        assertFalse(f.input.onKey(key(K, meta = true)))
        assertFalse(f.input.onKey(key(K, ctrl = true, alt = true)))
        assertFalse(f.input.onKey(key(PLUS, ctrl = true)))
        assertFalse(f.input.onKey(key(DIGIT_5, ctrl = true)))
        assertFalse(f.input.onKey(key(TAB)))
        assertFalse(f.input.onKey(key(X)))
        assertFalse(f.input.onKey(key(ENTER)))
        assertFalse(f.input.onKey(key(K, ctrl = true)))
        assertTrue(f.performed.isEmpty())
    }

    @Test fun spaceBelongsToTheExistingInputAndIsNeverClaimedHere() = edt {
        val f = Fixture()
        assertFalse(f.input.onKey(key(SPACE)))
        assertFalse(f.input.onKey(up(SPACE)))
        assertTrue(f.performed.isEmpty())
    }

    @Test fun theFrameStepStaysUnclaimedEvenWhilePaused() = edt {
        val f = Fixture()
        f.paused = true
        assertFalse(f.input.onKey(key(COMMA)))
        assertTrue(f.performed.isEmpty())
    }

    @Test fun noKeyIsClaimedWithoutTheKeyboardASourceOrWhileAnEditorHasIt() = edt {
        val f = Fixture()
        f.owns = false
        assertFalse(f.input.onKey(key(K)))
        f.owns = true
        f.source = null
        assertFalse(f.input.onKey(key(K)))
        f.source = Any()
        f.editor = true
        assertFalse(f.input.onKey(key(K)))
        assertFalse(f.input.onKey(key(DIGIT_5)))
        f.editor = false
        assertTrue(f.performed.isEmpty())
        assertTrue(f.input.onKey(key(K)))
    }

    @Test fun theArrowKeysHomeAndEndStayWithAControlOfTheChromeThatHoldsTheFocus() = edt {
        val f = Fixture()
        f.controlHolds = true
        for (code in listOf(LEFT, RIGHT, UP, DOWN, HOME, END)) {
            assertFalse(f.input.onKey(key(code)), "code $code")
            assertFalse(f.input.onKey(key(code, shift = true)), "shift code $code")
        }
        assertTrue(f.performed.isEmpty())
        assertTrue(f.input.onKey(key(K)))
        assertTrue(f.input.onKey(key(M)))
        f.controlHolds = false
        assertTrue(f.input.onKey(key(RIGHT)))
    }

    @Test fun aPressThatNobodyActedOnIsNotClaimedAndItsReleaseIsNotConsumed() = edt {
        val f = Fixture()
        f.accept = false
        assertFalse(f.input.onKey(key(K)))
        assertFalse(f.input.onKey(up(K)))
        f.accept = true
        assertTrue(f.input.onKey(key(K)))
        assertEquals(2, f.performed.size)
    }

    @Test fun aHeldPressCannotActOnAReplacementSource() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key(RIGHT)))
        f.source = Any()
        assertTrue(f.input.onKey(key(RIGHT)))
        assertEquals(1, f.performed.size)
        assertFalse(f.input.onKey(up(RIGHT)))
        assertTrue(f.input.onKey(key(RIGHT)))
        assertEquals(2, f.performed.size)
    }

    @Test fun aReleaseAfterTheKeyboardWasLostStillClearsTheHeldPress() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key(K)))
        f.owns = false
        assertFalse(f.input.onKey(up(K)))
        f.owns = true
        assertTrue(f.input.onKey(key(K)))
        assertEquals(2, f.performed.size)
    }

    @Test fun focusLossForgetsEveryHeldPressAndClosingRetiresTheRouter() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key(K)))
        f.input.releaseHeldInput()
        assertTrue(f.input.onKey(key(K)))
        assertEquals(2, f.performed.size)
        f.input.close()
        assertFalse(f.input.onKey(up(K)))
        assertFalse(f.input.onKey(key(K)))
        assertFalse(f.input.onKey(key(RIGHT)))
        assertEquals(2, f.performed.size)
    }

    @Test fun escapeIsSwallowedOnlyByTheDesktopLayoutOutsideFullscreen() = edt {
        val f = Fixture()
        // LEGACY: a windowed Esc stays with Root (it goes back) and a fullscreen Esc stays with the existing input.
        assertFalse(f.input.onKey(key(ESC)))
        f.fullscreen = true
        assertFalse(f.input.onKey(key(ESC)))
        // DESKTOP: a windowed Esc leaves nothing (decision D4), a fullscreen Esc still belongs to the existing input.
        f.layout = DesktopWindowsUxPlayerLayout.DESKTOP
        assertFalse(f.input.onKey(key(ESC)))
        f.fullscreen = false
        assertTrue(f.input.onKey(key(ESC)))
        assertTrue(f.input.onKey(key(ESC)))
        assertTrue(f.input.onKey(up(ESC)))
        assertFalse(f.input.onKey(up(ESC)))
        assertTrue(f.performed.isEmpty())
    }

    @Test fun oneSlotRunsTheExistingInputFirstAndTheRouterSecond() = edt {
        val f = Fixture()
        val commands = mutableListOf<Key>()
        val existing = DesktopWindowsVideoKeyInput({ f.source }, { f.owns }, { f.fullscreen }) { event -> commands += event.key; true }
        val fallback = DesktopWindowKeyFallback()
        fallback.register { error("a claimed key must not reach the Root handler") }
        val registration = fallback.registerLeaf { event -> existing.onKey(event) || f.input.onKey(event) }
        assertTrue(fallback.preview(key(SPACE)))
        assertTrue(fallback.preview(up(SPACE)))
        assertEquals(listOf(Key(SPACE)), commands)
        assertTrue(f.performed.isEmpty())
        assertTrue(fallback.preview(key(K)))
        assertTrue(fallback.preview(up(K)))
        assertEquals(listOf(act(DesktopPlayerActionKind.PLAY_PAUSE)), f.performed)
        assertEquals(1, commands.size)
        // A windowed Esc of the LEGACY layout reaches neither, so the scene and Root keep it.
        assertFalse(fallback.preview(key(ESC)))
        f.fullscreen = true
        assertTrue(fallback.preview(key(ESC)))
        assertTrue(fallback.preview(up(ESC)))
        assertEquals(listOf(Key(SPACE), Key(ESC)), commands)
        registration.close()
        assertFalse(fallback.preview(key(K)))
        fallback.close()
        existing.close()
        f.input.close()
    }

    @Test fun pageEntryFocusIsRequestedUnlessAnEditorOwnsItAndAnUnattachedRequesterIsNotAFailure() {
        var requests = 0
        assertFalse(desktopWindowsRequestPageEntryFocus(true) { requests++ })
        assertEquals(0, requests)
        assertTrue(desktopWindowsRequestPageEntryFocus(false) { requests++ })
        assertEquals(1, requests)
        assertFalse(desktopWindowsRequestPageEntryFocus(false) { throw IllegalStateException("FocusRequester is not initialized") })
        assertFailsWith<IllegalArgumentException> { desktopWindowsRequestPageEntryFocus(false) { throw IllegalArgumentException("a real failure") } }
    }
}
