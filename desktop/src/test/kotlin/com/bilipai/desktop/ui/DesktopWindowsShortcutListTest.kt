package com.bilipai.desktop.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shortcut lists of the Tips page follow the shell and player tables: every claimed chord is listed once, a row
 * waiting for an mpv API is not, and every action kind has a label. Plain JVM, no Compose.
 */
class DesktopWindowsShortcutListTest {
    private fun chordsOf(rows: List<DesktopShortcutListRow>): Set<String> =
        rows.flatMap { it.keys.split(" / ") }.toSet()

    @Test fun everyShellChordIsListed() {
        val shown = chordsOf(desktopShellShortcutListRows())
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) assertTrue(desktopShortcutChordText(shortcut.chord) in shown, shortcut.chord)
    }

    @Test fun everyPlayerChordWithAKeyIsListedAndTheOmittedRowsAreNot() {
        val shown = chordsOf(desktopPlayerShortcutListRows())
        for (shortcut in DESKTOP_PLAYER_SHORTCUTS) {
            val isDigit = shortcut.chord.length == 1 && shortcut.chord[0].isDigit()
            if (isDigit) continue
            val listed = desktopShortcutChordText(shortcut.chord) in shown
            assertEquals(shortcut.status != DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API, listed, shortcut.chord)
        }
        assertFalse("," in shown || "." in shown)
    }

    @Test fun theTenSeekPercentDigitsReadAsOneRange() {
        val row = desktopPlayerShortcutListRows().single { it.keys == "0 - 9" }
        assertEquals(DesktopWindowsTipsStrings.playerActionLabel(DesktopPlayerAction(DesktopPlayerActionKind.SEEK_PERCENT, 30)), row.label)
        assertEquals("1 - 3", desktopShortcutKeysText(listOf("1", "2", "3")))
        assertEquals("1 / 2", desktopShortcutKeysText(listOf("1", "2")))
    }

    @Test fun aLabelIsOneRowAndTheChordsThatShareItAreJoined() {
        val shell = desktopShellShortcutListRows()
        assertEquals(shell.size, shell.map { it.label }.toSet().size)
        assertEquals("Ctrl+K / Ctrl+F", shell.single { it.label == DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.OPEN_SEARCH)) }.keys)
        val player = desktopPlayerShortcutListRows()
        assertEquals(player.size, player.map { it.label }.toSet().size)
        assertTrue(player.all { it.keys.isNotBlank() && it.label.isNotBlank() })
    }

    @Test fun chordsAreShownWithSymbolsForArrowsAndPunctuation() {
        assertEquals("Ctrl+,", desktopShortcutChordText("Ctrl+Comma"))
        assertEquals("Alt+←", desktopShortcutChordText("Alt+Left"))
        assertEquals("Shift+/", desktopShortcutChordText("Shift+Slash"))
        assertEquals("Shift+F10", desktopShortcutChordText("Shift+F10"))
        assertEquals("[", desktopShortcutChordText("LeftBracket"))
        assertEquals("F11", desktopShortcutChordText("F11"))
    }

    @Test fun ctrlBMeansTwoThingsAndBothListsShowIt() {
        val shell = desktopShellShortcutListRows().single { "Ctrl+B" in it.keys.split(" / ") }
        val player = desktopPlayerShortcutListRows().single { "Ctrl+B" in it.keys.split(" / ") }
        assertTrue(shell.label != player.label)
        assertTrue(DesktopShellStrings.CTRL_B_NOTE.contains("Ctrl+B"))
    }

    @Test fun everyPlayerActionKindHasALabelInBothDirections() {
        for (kind in DesktopPlayerActionKind.entries) for (arg in listOf(-10, -1, 0, 1, 5)) {
            assertTrue(DesktopWindowsTipsStrings.playerActionLabel(DesktopPlayerAction(kind, arg)).isNotBlank(), "$kind $arg")
        }
    }

    @Test fun theListsStayInTheOrderOfTheTables() {
        val player = desktopPlayerShortcutListRows()
        val firstChords = player.map { it.keys.substringBefore(" / ") }
        assertEquals(desktopShortcutChordText(DESKTOP_PLAYER_SHORTCUTS.first().chord), firstChords.first())
        val shell = desktopShellShortcutListRows()
        assertEquals(desktopShortcutChordText(DESKTOP_SHELL_SHORTCUTS.first().chord), shell.first().keys.substringBefore(" / "))
    }
}
