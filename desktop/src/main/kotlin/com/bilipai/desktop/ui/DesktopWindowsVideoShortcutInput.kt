package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.*
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import javax.swing.text.JTextComponent

/**
 * Window-level player shortcuts of the ordinary-video page (spec 6.5 and 6.6, slice V6). The leaf composes this with the existing
 * DesktopWindowsVideoKeyInput into one handler of its single leaf slot of the window key input: the existing input first (Space, and Esc
 * in fullscreen, exactly as before), then this one. A key that neither claims falls through to the focused control, to the Root key
 * handler and to the native key adapter, as it did before this class existed. No global input hook is installed.
 *
 * The press is reduced to a key name and resolved by resolveDesktopPlayerKey, the pure table of slice X1b. perform runs the action and
 * answers whether the player acted on it; a press that nobody acted on is not claimed. Space is never claimed here. Esc is claimed
 * only when the layout asks for it to be swallowed (spec decision D4: a windowed Esc of the DESKTOP layout leaves nothing); a windowed
 * Esc of the LEGACY layout and a fullscreen Esc stay with Root and with the existing input. The arrow keys, Home and End are not claimed
 * while a control of the LEGACY chrome holds the focus (controlHoldsFocus): the seek slider and the focus traversal of the bar use them.
 *
 * Held keys follow the existing input: a claimed press owns its repeats and its release. A repeat never runs again on a replacement
 * source, and it never runs again at all unless the action repeats (seeking and volume do), so a held K does not toggle playback over and
 * over. The release of a claimed press is consumed while the page still owns the keyboard, so a focused button cannot act on it. Focus
 * loss and closing forget every held press. Every method runs on the event dispatch thread.
 */
internal class DesktopWindowsVideoShortcutInput<Source : Any>(
    private val currentSource: () -> Source?,
    private val ownsFocus: () -> Boolean,
    private val controlHoldsFocus: () -> Boolean,
    private val textEditorFocused: () -> Boolean,
    private val isFullscreen: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val layout: () -> DesktopWindowsUxPlayerLayout,
    private val perform: (DesktopPlayerAction) -> Boolean,
) : AutoCloseable {
    private class Held<Owner : Any>(val source: Owner, val action: DesktopPlayerAction)

    private var closed = false
    private val held = HashMap<Int, Held<Source>>()

    fun onKey(event: KeyEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (closed) return false
        val code = event.key.nativeKeyCode
        val name = desktopPlayerKeyName(code) ?: return false
        if (name == DESKTOP_PLAYER_SPACE_KEY_NAME) return false
        if (event.type == KeyEventType.KeyUp) {
            val press = held.remove(code) ?: return false
            return currentSource() === press.source && ownsFocus()
        }
        if (event.type != KeyEventType.KeyDown || !ownsFocus()) return false
        if (name in DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS && controlHoldsFocus()) return false
        val source = currentSource() ?: return false
        val action = resolveDesktopPlayerKey(
            DesktopShellKeyData(
                key = name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed,
                meta = event.isMetaPressed, keyDown = true,
            ),
            DesktopPlayerKeyContext(textEditorFocused = textEditorFocused(), paused = isPaused()),
        )
        val press = held[code]
        if (press != null) {
            // A repeat belongs to its original physical press, also across a source switch. Only a repeating action runs again,
            // and only on the source that got the press.
            if (desktopPlayerShortcutRepeats(press.action.kind) && press.source === source && action == press.action) perform(press.action)
            return true
        }
        if (action == null || currentSource() !== source) return false
        val handled = if (action.kind == DesktopPlayerActionKind.ESC_CHAIN) escapeSwallowed() else perform(action)
        if (!handled) return false
        held[code] = Held(source, action)
        return true
    }

    /** Spec decision D4: only the DESKTOP layout swallows a windowed Esc. No popup is tracked here: a flyout is a window of its own and takes its Esc. */
    private fun escapeSwallowed(): Boolean =
        resolveDesktopPlayerEscStep(popupOpen = false, fullscreen = isFullscreen(), layout = layout()) == DesktopPlayerEscStep.NONE

    fun releaseHeldInput() {
        check(EventQueue.isDispatchThread())
        held.clear()
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        closed = true
        releaseHeldInput()
    }
}

/** True while a Swing text component owns the keyboard (the same test the story native input uses). */
internal fun desktopWindowsTextEditorFocused(): Boolean =
    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent

/**
 * Spec 6.6: on page entry the Compose viewport takes the keyboard focus unless a text editor owns it. A focus requester that is not attached
 * yet throws IllegalStateException; that is "not now", never a failure of the page.
 */
internal fun desktopWindowsRequestPageEntryFocus(textEditorFocused: Boolean, request: () -> Unit): Boolean {
    if (textEditorFocused) return false
    return try {
        request()
        true
    } catch (_: IllegalStateException) {
        false
    }
}
