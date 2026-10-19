package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout

/*
 * Pure player key table and resolver (spec 6.5). No Compose, AWT or Windows dependency: the router of slice V6 reduces a key
 * event to DesktopShellKeyData (the key type of the shell resolver, with the chord names of the contract "shortcuts") and
 * asks resolveDesktopPlayerKey. The table holds the player rows of the contract. A chord is claimed only for a key press with
 * no text editor focused, no Meta and no Ctrl+Alt (AltGr), never a zoom or reserved chord (Enter and Backspace are not taken),
 * and only for an active row: F11 stays with the shell root and the frame step waits for an mpv API, so both stay in the
 * table and are not claimed. Keypad digits count as digits. test_ux_player_policy.py and DesktopPlayerShortcutPolicyTest
 * check the table and the resolver against the contract.
 */

/** arg: seconds (SEEK), direction (CHAPTER, PART, FRAME_STEP, SPEED_STEP), percent points (VOLUME), percent (SEEK_PERCENT), speed (SPEED_SET). */
internal enum class DesktopPlayerActionKind {
    PLAY_PAUSE, SEEK, CHAPTER, VOLUME, MUTE, FULLSCREEN_TOGGLE, ESC_CHAIN, SIDE_PANEL_OR_CINEMA, MINI_PLAYER, VIDEO_INFO,
    SUBTITLE_TOGGLE, SUBTITLE_FLYOUT, DANMAKU_TOGGLE, SCREENSHOT, PART, SEEK_PERCENT, SEEK_START, SEEK_END, FRAME_STEP,
    SPEED_STEP, SPEED_SET, CONTEXT_MENU, SHORTCUT_HELP,
}

internal data class DesktopPlayerAction(val kind: DesktopPlayerActionKind, val arg: Int = 0)

internal enum class DesktopPlayerShortcutStatus { ACTIVE, EXISTING_AT_ROOT, OMITTED_UNTIL_MPV_API }

internal data class DesktopPlayerShortcut(
    val chord: String,
    val action: DesktopPlayerAction,
    val status: DesktopPlayerShortcutStatus = DesktopPlayerShortcutStatus.ACTIVE,
    val whenPaused: Boolean = false,
)

private fun desktopPlayerKey(
    chord: String,
    kind: DesktopPlayerActionKind,
    arg: Int = 0,
    status: DesktopPlayerShortcutStatus = DesktopPlayerShortcutStatus.ACTIVE,
    whenPaused: Boolean = false,
) = DesktopPlayerShortcut(chord, DesktopPlayerAction(kind, arg), status, whenPaused)

// UX-PLAYER-SHORTCUTS-BEGIN
internal val DESKTOP_PLAYER_SHORTCUTS: List<DesktopPlayerShortcut> = listOf(
    desktopPlayerKey("Space", DesktopPlayerActionKind.PLAY_PAUSE),
    desktopPlayerKey("K", DesktopPlayerActionKind.PLAY_PAUSE),
    desktopPlayerKey("Left", DesktopPlayerActionKind.SEEK, -5),
    desktopPlayerKey("Right", DesktopPlayerActionKind.SEEK, 5),
    desktopPlayerKey("Shift+Left", DesktopPlayerActionKind.SEEK, -10),
    desktopPlayerKey("Shift+Right", DesktopPlayerActionKind.SEEK, 10),
    desktopPlayerKey("Ctrl+Left", DesktopPlayerActionKind.CHAPTER, -1),
    desktopPlayerKey("Ctrl+Right", DesktopPlayerActionKind.CHAPTER, 1),
    desktopPlayerKey("J", DesktopPlayerActionKind.SEEK, -10),
    desktopPlayerKey("L", DesktopPlayerActionKind.SEEK, 10),
    desktopPlayerKey("Up", DesktopPlayerActionKind.VOLUME, 5),
    desktopPlayerKey("Down", DesktopPlayerActionKind.VOLUME, -5),
    desktopPlayerKey("M", DesktopPlayerActionKind.MUTE),
    desktopPlayerKey("F", DesktopPlayerActionKind.FULLSCREEN_TOGGLE),
    desktopPlayerKey("Alt+Enter", DesktopPlayerActionKind.FULLSCREEN_TOGGLE),
    desktopPlayerKey("F11", DesktopPlayerActionKind.FULLSCREEN_TOGGLE, status = DesktopPlayerShortcutStatus.EXISTING_AT_ROOT),
    desktopPlayerKey("Esc", DesktopPlayerActionKind.ESC_CHAIN),
    desktopPlayerKey("T", DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA),
    desktopPlayerKey("Ctrl+B", DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA),
    desktopPlayerKey("P", DesktopPlayerActionKind.MINI_PLAYER),
    desktopPlayerKey("I", DesktopPlayerActionKind.VIDEO_INFO),
    desktopPlayerKey("C", DesktopPlayerActionKind.SUBTITLE_TOGGLE),
    desktopPlayerKey("Shift+C", DesktopPlayerActionKind.SUBTITLE_FLYOUT),
    desktopPlayerKey("D", DesktopPlayerActionKind.DANMAKU_TOGGLE),
    desktopPlayerKey("S", DesktopPlayerActionKind.SCREENSHOT),
    desktopPlayerKey("LeftBracket", DesktopPlayerActionKind.PART, -1),
    desktopPlayerKey("RightBracket", DesktopPlayerActionKind.PART, 1),
    desktopPlayerKey("Shift+P", DesktopPlayerActionKind.PART, -1),
    desktopPlayerKey("Shift+N", DesktopPlayerActionKind.PART, 1),
) + (0..9).map { digit -> desktopPlayerKey("$digit", DesktopPlayerActionKind.SEEK_PERCENT, digit * 10) } + listOf(
    desktopPlayerKey("Home", DesktopPlayerActionKind.SEEK_START),
    desktopPlayerKey("End", DesktopPlayerActionKind.SEEK_END),
    desktopPlayerKey("Comma", DesktopPlayerActionKind.FRAME_STEP, -1, DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API, whenPaused = true),
    desktopPlayerKey("Period", DesktopPlayerActionKind.FRAME_STEP, 1, DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API, whenPaused = true),
    desktopPlayerKey("Shift+Comma", DesktopPlayerActionKind.SPEED_STEP, -1),
    desktopPlayerKey("Shift+Period", DesktopPlayerActionKind.SPEED_STEP, 1),
    desktopPlayerKey("Shift+1", DesktopPlayerActionKind.SPEED_SET, 1),
    desktopPlayerKey("Shift+2", DesktopPlayerActionKind.SPEED_SET, 2),
    desktopPlayerKey("Menu", DesktopPlayerActionKind.CONTEXT_MENU),
    desktopPlayerKey("Shift+F10", DesktopPlayerActionKind.CONTEXT_MENU),
    desktopPlayerKey("Shift+Slash", DesktopPlayerActionKind.SHORTCUT_HELP),
)
// UX-PLAYER-SHORTCUTS-END

internal data class DesktopPlayerKeyContext(val textEditorFocused: Boolean = false, val paused: Boolean = false)

private val desktopPlayerShortcutByChord: Map<String, DesktopPlayerShortcut> = DESKTOP_PLAYER_SHORTCUTS.associateBy { it.chord }

/**
 * The player action a key press asks for, or null when the router does not claim it. byChord is the table the chord is looked up
 * in: the shipped one, or a synthetic one in a test, the only way to check an ACTIVE paused-only row while frame step is omitted.
 */
internal fun resolveDesktopPlayerKey(
    event: DesktopShellKeyData,
    context: DesktopPlayerKeyContext,
    byChord: Map<String, DesktopPlayerShortcut> = desktopPlayerShortcutByChord,
): DesktopPlayerAction? {
    if (!event.keyDown || context.textEditorFocused) return null
    if (event.meta || (event.ctrl && event.alt)) return null
    val keypadDigit = !event.ctrl && event.key.length == 7 && event.key.startsWith("Numpad") && event.key[6].isDigit()
    val key = if (keypadDigit) event.key.substring(6) else event.key
    if (event.ctrl && key in DESKTOP_SHELL_ZOOM_KEYS) return null
    val chord = desktopShellChord(event.copy(key = key))
    if (chord in DESKTOP_SHELL_RESERVED_CHORDS) return null
    val shortcut = byChord[chord] ?: return null
    if (shortcut.status != DesktopPlayerShortcutStatus.ACTIVE || (shortcut.whenPaused && !context.paused)) return null
    return shortcut.action
}

/** What one Esc press does, in the order of spec 6.5: an open popup, then fullscreen, then (legacy layout only) the page. */
internal enum class DesktopPlayerEscStep { CLOSE_POPUP, LEAVE_FULLSCREEN, LEAVE_PAGE, NONE }

/** Decision D4: in the desktop layout a windowed Esc leaves nothing; the legacy layout still leaves the page. */
internal fun resolveDesktopPlayerEscStep(
    popupOpen: Boolean,
    fullscreen: Boolean,
    layout: DesktopWindowsUxPlayerLayout,
): DesktopPlayerEscStep = when {
    popupOpen -> DesktopPlayerEscStep.CLOSE_POPUP
    fullscreen -> DesktopPlayerEscStep.LEAVE_FULLSCREEN
    layout == DesktopWindowsUxPlayerLayout.LEGACY -> DesktopPlayerEscStep.LEAVE_PAGE
    else -> DesktopPlayerEscStep.NONE
}
