package com.bilipai.desktop.ui

/*
 * Pure shell shortcut table and resolver (spec 6.1). No Compose, AWT or Windows dependency: the shell host
 * (slice S2) reduces a key event to DesktopShellKeyData and asks resolveDesktopShellKey, which never throws.
 *
 * Keys are named like the chords of desktop/tools/ux/desktop-ux-contract.json ("shortcuts"): letters and digits
 * as themselves, Left, Right, Up, Down, Home, End, F1 to F12, Comma, Period, Slash, LeftBracket, RightBracket,
 * Space, Esc, Enter, Backspace, Menu, Plus, Equals, Add, Minus, Subtract and Numpad0. A chord is the modifiers
 * in the order Ctrl, Alt, Shift, Meta followed by the key, joined by plus signs: Ctrl+Shift+Left.
 *
 * The table holds the shell rows of the contract. The player rows belong to the player policy. A chord is claimed
 * only when it is a table row, no text editor owns focus, it is neither reserved nor a zoom key chord, it carries
 * no Ctrl+Alt (AltGr) and no Meta, and the page allows it. The Python suite test_ux_shortcut_table.py and
 * DesktopShellShortcutsTest check the table and the resolver against the contract.
 */

internal enum class DesktopShellActionKind {
    OPEN_SEARCH, BACK, REFRESH, GO_DESTINATION, RAIL_TOGGLE, OPEN_SETTINGS, OPEN_DOWNLOADS, CYCLE_PANES, SHORTCUT_HELP,
    FULLSCREEN_TOGGLE,
}

/** What a shortcut asks the shell to do. destination is set for GO_DESTINATION, direction (1 or -1) for CYCLE_PANES. */
internal data class DesktopShellAction(
    val kind: DesktopShellActionKind,
    val destination: DesktopRailItem? = null,
    val direction: Int = 0,
)

/** A table row. existing marks a chord that the shell root already handles itself. */
internal data class DesktopShellShortcut(val chord: String, val action: DesktopShellAction, val existing: Boolean = false)

// UX-SHELL-SHORTCUTS-BEGIN
internal val DESKTOP_SHELL_SHORTCUTS: List<DesktopShellShortcut> = listOf(
    DesktopShellShortcut("Ctrl+K", DesktopShellAction(DesktopShellActionKind.OPEN_SEARCH)),
    DesktopShellShortcut("Ctrl+F", DesktopShellAction(DesktopShellActionKind.OPEN_SEARCH)),
    DesktopShellShortcut("Alt+Left", DesktopShellAction(DesktopShellActionKind.BACK)),
    DesktopShellShortcut("F5", DesktopShellAction(DesktopShellActionKind.REFRESH)),
    DesktopShellShortcut("Ctrl+R", DesktopShellAction(DesktopShellActionKind.REFRESH)),
) + DESKTOP_RAIL_FIXED_DESTINATIONS.mapIndexed { index, item ->
    DesktopShellShortcut("Ctrl+${index + 1}", DesktopShellAction(DesktopShellActionKind.GO_DESTINATION, destination = item))
} + listOf(
    DesktopShellShortcut("Ctrl+B", DesktopShellAction(DesktopShellActionKind.RAIL_TOGGLE)),
    DesktopShellShortcut("Ctrl+Comma", DesktopShellAction(DesktopShellActionKind.OPEN_SETTINGS)),
    DesktopShellShortcut("Ctrl+J", DesktopShellAction(DesktopShellActionKind.OPEN_DOWNLOADS)),
    DesktopShellShortcut("F6", DesktopShellAction(DesktopShellActionKind.CYCLE_PANES, direction = 1)),
    DesktopShellShortcut("Shift+F6", DesktopShellAction(DesktopShellActionKind.CYCLE_PANES, direction = -1)),
    DesktopShellShortcut("F1", DesktopShellAction(DesktopShellActionKind.SHORTCUT_HELP)),
    DesktopShellShortcut("F11", DesktopShellAction(DesktopShellActionKind.FULLSCREEN_TOGGLE), existing = true),
    DesktopShellShortcut("Alt+Enter", DesktopShellAction(DesktopShellActionKind.FULLSCREEN_TOGGLE)),
)
// UX-SHELL-SHORTCUTS-END

// UX-SHELL-RESERVED-BEGIN
/** Chords the shell never claims: zoom, clipboard and edit keys, window close and Back, and Enter, which is not taken either. */
internal val DESKTOP_SHELL_RESERVED_CHORDS: Set<String> = setOf(
    "Ctrl+Plus", "Ctrl+Equals", "Ctrl+Add", "Ctrl+Minus", "Ctrl+Subtract", "Ctrl+0", "Ctrl+Numpad0",
    "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Alt+F4", "Backspace",
    "Enter",
)

/** The keys the UI zoom decoder reads; with Ctrl held, in any combination with Shift, they belong to zoom. */
internal val DESKTOP_SHELL_ZOOM_KEYS: Set<String> = setOf("Plus", "Equals", "Add", "Minus", "Subtract", "0", "Numpad0")
// UX-SHELL-RESERVED-END

/** Pages the shortcuts distinguish. PLAYER stands for every route without a rail: video, audio, Bangumi and offline. */
internal enum class DesktopShellPage { HOME, DYNAMIC, SEARCH, OTHER, PLAYER }

/** A key event reduced to data. Only a key press is resolved. */
internal data class DesktopShellKeyData(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val keyDown: Boolean = true,
)

internal data class DesktopShellKeyContext(val page: DesktopShellPage, val textEditorFocused: Boolean = false)

/** The chord string of a key event: modifiers in the order Ctrl, Alt, Shift, Meta, then the key. */
internal fun desktopShellChord(event: DesktopShellKeyData): String = buildString {
    if (event.ctrl) append("Ctrl+")
    if (event.alt) append("Alt+")
    if (event.shift) append("Shift+")
    if (event.meta) append("Meta+")
    append(event.key)
}

private val desktopShellActionByChord: Map<String, DesktopShellAction> =
    DESKTOP_SHELL_SHORTCUTS.associate { it.chord to it.action }

/**
 * On a PLAYER page the player owns the keys; only leaving the page, fullscreen, the help list and pane cycling stay
 * with the shell. F6 stays because the DESKTOP video page cannot take Tab focus into its carrier dock and spec 6.6
 * names F6 between the content and the side panel as part of its keyboard story; the player scope of the contract has
 * no F6 row, so the shell is the only owner and the host cycles just the panes that exist on the page.
 */
private val desktopShellPlayerPageKinds: Set<DesktopShellActionKind> = setOf(
    DesktopShellActionKind.BACK, DesktopShellActionKind.FULLSCREEN_TOGGLE, DesktopShellActionKind.SHORTCUT_HELP,
    DesktopShellActionKind.CYCLE_PANES,
)

private fun desktopShellPageAllows(kind: DesktopShellActionKind, page: DesktopShellPage): Boolean = when (page) {
    DesktopShellPage.PLAYER -> kind in desktopShellPlayerPageKinds
    DesktopShellPage.SEARCH -> kind != DesktopShellActionKind.OPEN_SEARCH && kind != DesktopShellActionKind.REFRESH
    DesktopShellPage.HOME, DesktopShellPage.DYNAMIC -> true
    DesktopShellPage.OTHER -> kind != DesktopShellActionKind.REFRESH
}

/** The shell action a key press asks for, or null when the shell does not claim it. */
internal fun resolveDesktopShellKey(event: DesktopShellKeyData, context: DesktopShellKeyContext): DesktopShellAction? {
    if (!event.keyDown || context.textEditorFocused) return null
    if (event.meta || (event.ctrl && event.alt)) return null
    if (event.ctrl && event.key in DESKTOP_SHELL_ZOOM_KEYS) return null
    val chord = desktopShellChord(event)
    if (chord in DESKTOP_SHELL_RESERVED_CHORDS) return null
    val action = desktopShellActionByChord[chord] ?: return null
    return if (desktopShellPageAllows(action.kind, context.page)) action else null
}
