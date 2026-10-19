package com.bilipai.desktop.ui

/*
 * Pure rows of the shortcut lists on the Tips page (spec 3.7): the shell table of spec 6.1 and the player table of spec
 * 6.5, one row per label with every chord that reaches it. No Compose, AWT or Windows dependency. The tables are the
 * source of truth (DESKTOP_SHELL_SHORTCUTS, DESKTOP_PLAYER_SHORTCUTS), so a chord that is added to a table appears
 * here without a second edit; the labels come from DesktopShellStrings and DesktopWindowsTipsStrings.
 */

/** keys: the chords of one label, as they are shown. */
internal data class DesktopShortcutListRow(val keys: String, val label: String)

/** A chord as it is shown: modifiers as they are named, the key through DesktopWindowsTipsStrings.keyName. */
internal fun desktopShortcutChordText(chord: String): String {
    val parts = chord.split('+')
    return parts.dropLast(1).joinToString("") { "$it+" } + DesktopWindowsTipsStrings.keyName(parts.last())
}

/** The chords of one row: three or more single digits read as a range (0 - 9), every other chord is listed. */
internal fun desktopShortcutKeysText(chords: List<String>): String {
    val digits = chords.size >= 3 && chords.all { it.length == 1 && it[0].isDigit() }
    return if (digits) chords.first() + " - " + chords.last() else chords.joinToString(" / ") { desktopShortcutChordText(it) }
}

private fun desktopShortcutListRows(entries: List<Pair<String, String>>): List<DesktopShortcutListRow> {
    val chordsByLabel = LinkedHashMap<String, MutableList<String>>()
    for ((chord, label) in entries) chordsByLabel.getOrPut(label) { mutableListOf() }.add(chord)
    return chordsByLabel.map { (label, chords) -> DesktopShortcutListRow(desktopShortcutKeysText(chords), label) }
}

/** Every row of the shell table. */
internal fun desktopShellShortcutListRows(table: List<DesktopShellShortcut> = DESKTOP_SHELL_SHORTCUTS): List<DesktopShortcutListRow> =
    desktopShortcutListRows(table.map { it.chord to DesktopShellStrings.actionLabel(it.action) })

/** Every row of the player table that has a key now: the rows that wait for an mpv API are left out. */
internal fun desktopPlayerShortcutListRows(table: List<DesktopPlayerShortcut> = DESKTOP_PLAYER_SHORTCUTS): List<DesktopShortcutListRow> =
    desktopShortcutListRows(
        table.filter { it.status != DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API }
            .map { it.chord to DesktopWindowsTipsStrings.playerActionLabel(it.action) },
    )
