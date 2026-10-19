package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout

/*
 * The pure part of the window-level shortcut router of the ordinary-video page (spec 6.5 and 6.6, slice V6). No Compose, AWT or
 * Windows dependency.
 *
 * DesktopWindowsVideoShortcutInput reduces a key event to a key name and asks resolveDesktopPlayerKey (the X1b table) what the press
 * means. This file holds what happens next and what surrounds it:
 *
 *   - desktopPlayerKeyName names the AWT virtual key code of a press the way the chords of the contract do ("Left", "Space", "F10");
 *   - DesktopPlayerShortcutEffects is one effect per action kind. Every effect runs through the admitted actions of the leaf (the
 *     DesktopPlayerControlsBinding of slice V2 and a few lambdas of the leaf), so the router never reaches the player or the assembly.
 *     A press is claimed only when the player can act on it right now; every other press falls through to the focused control and to
 *     the Root key handler exactly as before. A kind whose target does not exist yet (video info card, subtitle flyout, context menu,
 *     shortcut help, frame step) is not claimed until the slice that builds the target wires it;
 *   - the chapter jump, the speed step and the repeat rule are small pure functions;
 *   - desktopPlayerShortcutOwnsFocus is the focus rule of spec 6.6: the LEGACY layout keeps its gate (focus on the top strip, the bar,
 *     the viewport or the native surface), the DESKTOP layout has no such controls and owns the keys unless a text editor or a flyout does.
 *
 * Mirrored by desktop/tools/ux/ux_player_keys_model.py. DesktopPlayerShortcutEffectsTest and test_ux_player_keys_source.py check the
 * key codes, the effects and the rules against the shared contract (section player_keys).
 */

// UX-PLAYER-KEY-CODES-BEGIN
private val DESKTOP_PLAYER_KEY_LETTERS = 65..90
private val DESKTOP_PLAYER_KEY_DIGITS = 48..57
private val DESKTOP_PLAYER_KEY_NUMPAD_DIGITS = 96..105
private val DESKTOP_PLAYER_KEY_FUNCTION_KEYS = 112..123
private val DESKTOP_PLAYER_NAMED_KEY_CODES: Map<Int, String> = mapOf(
    37 to "Left", 38 to "Up", 39 to "Right", 40 to "Down", 36 to "Home", 35 to "End", 32 to "Space", 27 to "Esc", 10 to "Enter",
    8 to "Backspace", 44 to "Comma", 46 to "Period", 47 to "Slash", 91 to "LeftBracket", 93 to "RightBracket", 525 to "Menu",
    521 to "Plus", 61 to "Equals", 107 to "Add", 45 to "Minus", 109 to "Subtract",
)
// UX-PLAYER-KEY-CODES-END

/** The key name of a java.awt.event.KeyEvent virtual key code in the spelling of the contract chords, or null for a key that no chord names. */
internal fun desktopPlayerKeyName(keyCode: Int): String? = when (keyCode) {
    in DESKTOP_PLAYER_KEY_LETTERS, in DESKTOP_PLAYER_KEY_DIGITS -> Char(keyCode).toString()
    in DESKTOP_PLAYER_KEY_NUMPAD_DIGITS -> "Numpad" + (keyCode - DESKTOP_PLAYER_KEY_NUMPAD_DIGITS.first)
    in DESKTOP_PLAYER_KEY_FUNCTION_KEYS -> "F" + (keyCode - DESKTOP_PLAYER_KEY_FUNCTION_KEYS.first + 1)
    else -> DESKTOP_PLAYER_NAMED_KEY_CODES[keyCode]
}

/** Space keeps its owner: DesktopWindowsVideoKeyInput (and Root behind it) handle it, the router never claims it. */
internal const val DESKTOP_PLAYER_SPACE_KEY_NAME = "Space"

/**
 * The keys that move a focused control: the seek slider of the bar seeks with them and Compose moves the focus between buttons with them.
 * While a control of the LEGACY chrome holds the focus the router leaves them to it, as the existing key input leaves them to it.
 */
internal val DESKTOP_PLAYER_CONTROL_NAVIGATION_KEYS: Set<String> = setOf("Left", "Right", "Up", "Down", "Home", "End")

/** The ceiling of the player volume (the floor is zero). */
internal const val DESKTOP_PLAYER_VOLUME_MAX = 100.0

/** A previous-chapter press within this time of a chapter start goes to the chapter before it. */
internal const val DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS = 2_000L

/** Two speeds closer than this are the same speed. */
internal const val DESKTOP_PLAYER_SPEED_EPSILON = 0.001

/** Seeking and the volume run again for every repeat of a held key; every other action runs once per physical press. */
internal fun desktopPlayerShortcutRepeats(kind: DesktopPlayerActionKind): Boolean =
    kind == DesktopPlayerActionKind.SEEK || kind == DesktopPlayerActionKind.VOLUME

/**
 * Where a chapter jump goes, in milliseconds, or null when there is nothing to jump to. starts are the chapter starts in ascending order.
 * Forward: the first start after the position. Backward: the last start more than the margin before the position, and the video start when
 * the position is past the margin and no chapter lies before it. No chapters, or the very beginning of the video, give null.
 */
internal fun desktopPlayerChapterTarget(starts: List<Long>, positionMs: Long, direction: Int): Long? {
    if (starts.isEmpty()) return null
    if (direction > 0) return starts.firstOrNull { it > positionMs }
    val earlier = starts.lastOrNull { it < positionMs - DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS }
    return earlier ?: if (positionMs > DESKTOP_PLAYER_CHAPTER_BACK_MARGIN_MS) 0L else null
}

/** The next speed up (direction 1) or down (direction -1) of the offered speeds, or null at the end of the list. */
internal fun desktopPlayerSpeedStep(options: List<Double>, current: Double, direction: Int): Double? =
    if (direction > 0) options.filter { it > current + DESKTOP_PLAYER_SPEED_EPSILON }.minOrNull()
    else options.filter { it < current - DESKTOP_PLAYER_SPEED_EPSILON }.maxOrNull()

/**
 * Spec 6.6. legacyGate is the focus gate the LEGACY layout always had (top strip, bar, viewport or native surface holds the focus). The
 * DESKTOP layout has none of those controls, so it owns the keyboard unless a text editor or a flyout does. The caller adds what is common
 * to both: the route is current and the main window is the active one.
 */
internal fun desktopPlayerShortcutOwnsFocus(
    layout: DesktopWindowsUxPlayerLayout,
    legacyGate: Boolean,
    textEditorFocused: Boolean,
    flyoutOpen: Boolean,
): Boolean = when (layout) {
    DesktopWindowsUxPlayerLayout.LEGACY -> legacyGate
    DesktopWindowsUxPlayerLayout.DESKTOP -> !textEditorFocused && !flyoutOpen
}

/** What the effects read from the player when a key is pressed. */
internal class DesktopPlayerShortcutSnapshot(val positionSeconds: Double, val durationSeconds: Double, val volume: Double)

/**
 * One effect per action kind of the player key table. controls is the admitted actions of the leaf; the other capabilities are the
 * leaf's own admitted lambdas for what the controls binding does not hold. perform answers whether the press was acted on.
 */
internal class DesktopPlayerShortcutEffects(
    private val controls: DesktopPlayerControlsBinding,
    private val snapshot: () -> DesktopPlayerShortcutSnapshot,
    private val seekBy: (Double) -> Unit,
    private val hasPart: (Boolean) -> Boolean,
    private val canMiniPlayer: () -> Boolean,
    private val togglePanel: () -> Unit,
    private val toggleSubtitles: () -> Boolean,
    private val toggleDanmaku: () -> Unit,
    private val takeScreenshot: () -> Boolean,
    private val chapterStartsMs: () -> List<Long>,
    private val seekToChapter: (Long) -> Unit,
    private val speedChoices: () -> List<Double>,
    private val currentSpeed: () -> Double,
) {
    fun perform(action: DesktopPlayerAction): Boolean = when (action.kind) {
        DesktopPlayerActionKind.PLAY_PAUSE -> { controls.playPause(); true }
        DesktopPlayerActionKind.SEEK -> { seekBy(action.arg.toDouble()); true }
        DesktopPlayerActionKind.CHAPTER -> seekChapter(action.arg)
        DesktopPlayerActionKind.VOLUME -> {
            controls.volume((snapshot().volume + action.arg).coerceIn(0.0, DESKTOP_PLAYER_VOLUME_MAX))
            true
        }
        DesktopPlayerActionKind.MUTE -> { controls.mute(); true }
        DesktopPlayerActionKind.FULLSCREEN_TOGGLE -> { controls.fullscreen(); true }
        DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA -> { togglePanel(); true }
        DesktopPlayerActionKind.MINI_PLAYER -> miniPlayer()
        DesktopPlayerActionKind.SUBTITLE_TOGGLE -> toggleSubtitles()
        DesktopPlayerActionKind.DANMAKU_TOGGLE -> { toggleDanmaku(); true }
        DesktopPlayerActionKind.SCREENSHOT -> takeScreenshot()
        DesktopPlayerActionKind.PART -> navigatePart(action.arg > 0)
        DesktopPlayerActionKind.SEEK_PERCENT -> seekWithin { duration -> duration * action.arg / 100.0 }
        DesktopPlayerActionKind.SEEK_START -> { controls.seek(0.0); true }
        DesktopPlayerActionKind.SEEK_END -> seekWithin { duration -> duration }
        DesktopPlayerActionKind.SPEED_STEP -> stepSpeed(action.arg)
        DesktopPlayerActionKind.SPEED_SET -> { controls.speed(action.arg.toDouble()); true }
        DesktopPlayerActionKind.ESC_CHAIN, DesktopPlayerActionKind.VIDEO_INFO, DesktopPlayerActionKind.SUBTITLE_FLYOUT,
        DesktopPlayerActionKind.FRAME_STEP, DesktopPlayerActionKind.CONTEXT_MENU, DesktopPlayerActionKind.SHORTCUT_HELP -> false
    }

    private fun seekChapter(direction: Int): Boolean {
        val starts = chapterStartsMs()
        val target = desktopPlayerChapterTarget(starts, (snapshot().positionSeconds * 1000.0).toLong(), direction) ?: return false
        if (target in starts) seekToChapter(target) else controls.seek(target / 1000.0)
        return true
    }

    private fun seekWithin(target: (Double) -> Double): Boolean {
        val duration = snapshot().durationSeconds
        if (!duration.isFinite() || duration <= 0.0) return false
        controls.seek(target(duration))
        return true
    }

    private fun miniPlayer(): Boolean {
        if (!canMiniPlayer()) return false
        controls.pictureInPicture()
        return true
    }

    private fun navigatePart(forward: Boolean): Boolean {
        if (!hasPart(forward)) return false
        if (forward) controls.next() else controls.previous()
        return true
    }

    private fun stepSpeed(direction: Int): Boolean {
        val next = desktopPlayerSpeedStep(speedChoices(), currentSpeed(), direction) ?: return false
        controls.speed(next)
        return true
    }
}

/**
 * What the leaf fills in after the page state exists. The router is built once, early, because it is registered with the window key
 * input there; the effects and the layout need state that is declared further down, so they arrive here through the composition's
 * SideEffect. Until then the layout is LEGACY, no flyout is open and no effect is bound, so no press is claimed.
 */
internal class DesktopWindowsVideoShortcutBinding {
    var legacyLayout: Boolean = true
    var flyoutOpen: Boolean = false
    var effects: DesktopPlayerShortcutEffects? = null

    val layout: DesktopWindowsUxPlayerLayout
        get() = if (legacyLayout) DesktopWindowsUxPlayerLayout.LEGACY else DesktopWindowsUxPlayerLayout.DESKTOP

    fun ownsFocus(legacyGate: Boolean, textEditorFocused: Boolean): Boolean =
        desktopPlayerShortcutOwnsFocus(layout, legacyGate, textEditorFocused, flyoutOpen)

    fun perform(action: DesktopPlayerAction): Boolean = effects?.perform(action) ?: false
}
