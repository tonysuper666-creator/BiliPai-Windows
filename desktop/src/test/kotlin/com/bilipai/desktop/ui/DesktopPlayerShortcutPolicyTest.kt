package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure player key table and resolver against the shared contract. No window, no GPU, no network. */
class DesktopPlayerShortcutPolicyTest {
    private val shortcuts = DesktopUxContract.section("shortcuts")
    private val playerRows = DesktopUxContract.objects(shortcuts.getValue("rows")).filter { DesktopUxContract.string(it, "scope") == "player" }
    private val chordParts = listOf("Ctrl", "Alt", "Shift", "Meta")

    /** "Ctrl+Shift+Left" as key data. */
    private fun event(chord: String, keyDown: Boolean = true): DesktopShellKeyData {
        val parts = chord.split("+")
        val modifiers = parts.dropLast(1).toSet()
        return DesktopShellKeyData(
            key = parts.last(), ctrl = "Ctrl" in modifiers, alt = "Alt" in modifiers, shift = "Shift" in modifiers,
            meta = "Meta" in modifiers, keyDown = keyDown,
        )
    }

    private fun resolve(chord: String, paused: Boolean = false, textEditorFocused: Boolean = false): DesktopPlayerAction? =
        resolveDesktopPlayerKey(event(chord), DesktopPlayerKeyContext(textEditorFocused, paused))

    private fun statusOf(name: String?): DesktopPlayerShortcutStatus = when (name) {
        "existing-at-root" -> DesktopPlayerShortcutStatus.EXISTING_AT_ROOT
        "omitted-until-mpv-api" -> DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API
        else -> DesktopPlayerShortcutStatus.ACTIVE
    }

    @Test fun theTableIsTheContractPlayerRowsInOrderWithTheirStatus() {
        assertEquals(50, playerRows.size)
        assertEquals(playerRows.size, DESKTOP_PLAYER_SHORTCUTS.size)
        for ((row, shortcut) in playerRows.zip(DESKTOP_PLAYER_SHORTCUTS)) {
            val chord = DesktopUxContract.string(row, "chord")
            assertEquals(chord, shortcut.chord)
            assertEquals(DesktopUxContract.string(row, "action"), shortcut.action.kind.name, chord)
            assertEquals(if ("arg" in row) DesktopUxContract.int(row, "arg") else 0, shortcut.action.arg, chord)
            assertEquals(statusOf(row["status"]?.jsonPrimitive?.content), shortcut.status, chord)
            assertEquals(row["when"]?.jsonPrimitive?.content == "paused", shortcut.whenPaused, chord)
        }
    }

    @Test fun everyActionKindOfTheContractIsAnEnumEntryAndBack() {
        assertEquals(
            playerRows.map { DesktopUxContract.string(it, "action") }.toSet(),
            DesktopPlayerActionKind.entries.map { it.name }.toSet(),
        )
        assertEquals(23, DesktopPlayerActionKind.entries.size)
    }

    @Test fun theTableObeysTheCollisionRules() {
        val chords = DESKTOP_PLAYER_SHORTCUTS.map { it.chord }
        assertEquals(chords.size, chords.toSet().size, "no duplicate chord")
        for (chord in chords) {
            val parts = chord.split("+")
            val modifiers = parts.dropLast(1)
            val key = parts.last()
            assertFalse(chord in DESKTOP_SHELL_RESERVED_CHORDS, "$chord is reserved")
            assertFalse("Ctrl" in modifiers && "Alt" in modifiers, "$chord uses Ctrl+Alt")
            assertFalse("Meta" in modifiers, "$chord uses Meta")
            assertFalse("Ctrl" in modifiers && key in DESKTOP_SHELL_ZOOM_KEYS, "$chord is a zoom chord")
            assertEquals(modifiers, modifiers.sortedBy { chordParts.indexOf(it) }, "$chord modifier order")
        }
        val shellChords = DESKTOP_SHELL_SHORTCUTS.associate { it.chord to it.action.kind.name }
        for (shortcut in DESKTOP_PLAYER_SHORTCUTS) {
            val shellKind = shellChords[shortcut.chord] ?: continue
            // A chord used by both scopes has the same meaning, or is the documented dual Ctrl+B.
            assertTrue(shellKind == shortcut.action.kind.name || shortcut.chord == "Ctrl+B", shortcut.chord)
        }
    }

    @Test fun everyActiveRowResolvesToItsActionAndTheOthersAreNotClaimed() {
        for (shortcut in DESKTOP_PLAYER_SHORTCUTS) {
            val got = resolve(shortcut.chord, paused = true)
            if (shortcut.status == DesktopPlayerShortcutStatus.ACTIVE) assertEquals(shortcut.action, got, shortcut.chord) else assertNull(got, shortcut.chord)
        }
    }

    @Test fun aTextEditorOwningFocusStopsEveryChordAndAReleaseIsNeverClaimed() {
        for (shortcut in DESKTOP_PLAYER_SHORTCUTS) {
            assertNull(resolve(shortcut.chord, paused = true, textEditorFocused = true), shortcut.chord)
            assertNull(resolveDesktopPlayerKey(event(shortcut.chord, keyDown = false), DesktopPlayerKeyContext(paused = true)), shortcut.chord)
        }
    }

    @Test fun altGrAndTheWindowsKeyAreNeverClaimed() {
        for (shortcut in DESKTOP_PLAYER_SHORTCUTS) {
            val base = event(shortcut.chord)
            val context = DesktopPlayerKeyContext(paused = true)
            assertNull(resolveDesktopPlayerKey(base.copy(ctrl = true, alt = true), context), "${shortcut.chord} with Ctrl+Alt")
            assertNull(resolveDesktopPlayerKey(base.copy(meta = true), context), "${shortcut.chord} with Meta")
        }
    }

    @Test fun thePlayerKeysOfTheSpec() {
        val expected = mapOf(
            "Space" to (DesktopPlayerActionKind.PLAY_PAUSE to 0), "K" to (DesktopPlayerActionKind.PLAY_PAUSE to 0),
            "J" to (DesktopPlayerActionKind.SEEK to -10), "L" to (DesktopPlayerActionKind.SEEK to 10),
            "Left" to (DesktopPlayerActionKind.SEEK to -5), "Right" to (DesktopPlayerActionKind.SEEK to 5),
            "Shift+Left" to (DesktopPlayerActionKind.SEEK to -10), "Shift+Right" to (DesktopPlayerActionKind.SEEK to 10),
            "Ctrl+Left" to (DesktopPlayerActionKind.CHAPTER to -1), "Ctrl+Right" to (DesktopPlayerActionKind.CHAPTER to 1),
            "Up" to (DesktopPlayerActionKind.VOLUME to 5), "Down" to (DesktopPlayerActionKind.VOLUME to -5),
            "M" to (DesktopPlayerActionKind.MUTE to 0), "F" to (DesktopPlayerActionKind.FULLSCREEN_TOGGLE to 0),
            "Alt+Enter" to (DesktopPlayerActionKind.FULLSCREEN_TOGGLE to 0), "Esc" to (DesktopPlayerActionKind.ESC_CHAIN to 0),
            "T" to (DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA to 0), "Ctrl+B" to (DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA to 0),
            "P" to (DesktopPlayerActionKind.MINI_PLAYER to 0), "I" to (DesktopPlayerActionKind.VIDEO_INFO to 0),
            "C" to (DesktopPlayerActionKind.SUBTITLE_TOGGLE to 0), "Shift+C" to (DesktopPlayerActionKind.SUBTITLE_FLYOUT to 0),
            "D" to (DesktopPlayerActionKind.DANMAKU_TOGGLE to 0), "S" to (DesktopPlayerActionKind.SCREENSHOT to 0),
            "LeftBracket" to (DesktopPlayerActionKind.PART to -1), "RightBracket" to (DesktopPlayerActionKind.PART to 1),
            "Shift+P" to (DesktopPlayerActionKind.PART to -1), "Shift+N" to (DesktopPlayerActionKind.PART to 1),
            "Home" to (DesktopPlayerActionKind.SEEK_START to 0), "End" to (DesktopPlayerActionKind.SEEK_END to 0),
            "Shift+Comma" to (DesktopPlayerActionKind.SPEED_STEP to -1), "Shift+Period" to (DesktopPlayerActionKind.SPEED_STEP to 1),
            "Shift+1" to (DesktopPlayerActionKind.SPEED_SET to 1), "Shift+2" to (DesktopPlayerActionKind.SPEED_SET to 2),
            "Menu" to (DesktopPlayerActionKind.CONTEXT_MENU to 0), "Shift+F10" to (DesktopPlayerActionKind.CONTEXT_MENU to 0),
            "Shift+Slash" to (DesktopPlayerActionKind.SHORTCUT_HELP to 0),
        )
        for ((chord, want) in expected) {
            assertEquals(DesktopPlayerAction(want.first, want.second), resolve(chord), chord)
        }
        for (digit in 0..9) assertEquals(DesktopPlayerAction(DesktopPlayerActionKind.SEEK_PERCENT, digit * 10), resolve(digit.toString()), digit.toString())
        assertNull(resolve("Shift+3"))
        assertNull(resolve("Ctrl+3"))
    }

    @Test fun theKeysThatAreNotTakenOrNotOurs() {
        for (key in listOf("Q", "W", "E", "R")) { // the engagement keys are a non-goal
            assertNull(resolve(key), key)
            assertNull(resolve("Shift+$key"), key)
        }
        assertNull(resolve("Enter")) // decision D16
        assertNull(resolve("Backspace"))
        assertNull(resolve("F11")) // stays with the shell root
        assertNull(resolve("F6")) // the shell cycles panes
        assertNull(resolve("Ctrl+Esc"))
        assertNull(resolve("Tab"))
        for (chord in listOf("Ctrl+A", "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+Z", "Ctrl+Y", "Alt+F4")) assertNull(resolve(chord), chord)
        for (key in DESKTOP_SHELL_ZOOM_KEYS) {
            assertNull(resolve("Ctrl+$key"), key)
            assertNull(resolve("Ctrl+Shift+$key"), key)
        }
    }

    @Test fun theFrameStepIsInTheTableAndNotClaimedEvenWhilePaused() {
        for (chord in listOf("Comma", "Period")) {
            val shortcut = DESKTOP_PLAYER_SHORTCUTS.first { it.chord == chord }
            assertEquals(DesktopPlayerActionKind.FRAME_STEP, shortcut.action.kind)
            assertEquals(DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API, shortcut.status)
            assertTrue(shortcut.whenPaused)
            assertNull(resolve(chord, paused = false), chord)
            assertNull(resolve(chord, paused = true), chord)
        }
        assertEquals(DesktopPlayerShortcutStatus.EXISTING_AT_ROOT, DESKTOP_PLAYER_SHORTCUTS.first { it.chord == "F11" }.status)
        assertEquals(1, DESKTOP_PLAYER_SHORTCUTS.count { it.status == DesktopPlayerShortcutStatus.EXISTING_AT_ROOT })
    }

    @Test fun aPausedOnlyRowIsClaimedOnlyWhilePausedAndOnlyWhenItIsActive() {
        // Comma and Period are not ACTIVE yet, so the paused-only rule is checked on a synthetic table that makes them ACTIVE.
        val frameBack = DesktopPlayerAction(DesktopPlayerActionKind.FRAME_STEP, -1)
        val frameForward = DesktopPlayerAction(DesktopPlayerActionKind.FRAME_STEP, 1)
        val table = listOf(
            DesktopPlayerShortcut("Comma", frameBack, DesktopPlayerShortcutStatus.ACTIVE, whenPaused = true),
            DesktopPlayerShortcut("Period", frameForward, DesktopPlayerShortcutStatus.ACTIVE, whenPaused = false),
            DesktopPlayerShortcut("N", frameForward, DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API, whenPaused = true),
            DesktopPlayerShortcut("B", frameBack, DesktopPlayerShortcutStatus.EXISTING_AT_ROOT, whenPaused = false),
        ).associateBy { it.chord }
        fun resolveIn(chord: String, paused: Boolean) = resolveDesktopPlayerKey(event(chord), DesktopPlayerKeyContext(paused = paused), table)
        assertNull(resolveIn("Comma", paused = false))
        assertEquals(frameBack, resolveIn("Comma", paused = true))
        assertEquals(frameForward, resolveIn("Period", paused = false))
        assertEquals(frameForward, resolveIn("Period", paused = true))
        assertNull(resolveIn("N", paused = true)) // an omitted row is never claimed, even while paused
        assertNull(resolveIn("B", paused = true)) // a row that stays with the root is never claimed
        assertNull(resolveIn("Space", paused = true)) // the synthetic table replaces the shipped one
        assertNull(resolveDesktopPlayerKey(event("Comma"), DesktopPlayerKeyContext(textEditorFocused = true, paused = true), table))
        // Leaving the table out is the shipped table: Space plays and pauses, Comma is still not claimed.
        val shipped = DESKTOP_PLAYER_SHORTCUTS.associateBy { it.chord }
        for (chord in listOf("Space", "Comma", "Shift+Left", "3")) {
            val context = DesktopPlayerKeyContext(paused = true)
            assertEquals(resolveDesktopPlayerKey(event(chord), context, shipped), resolveDesktopPlayerKey(event(chord), context), chord)
        }
    }

    @Test fun theKeypadDigitsAreTheDigitsButNeverTheZoomChord() {
        for (digit in 0..9) {
            assertEquals(DesktopPlayerAction(DesktopPlayerActionKind.SEEK_PERCENT, digit * 10), resolve("Numpad$digit"), "Numpad$digit")
        }
        assertEquals(DesktopPlayerAction(DesktopPlayerActionKind.SPEED_SET, 1), resolve("Shift+Numpad1"))
        assertEquals(DesktopPlayerAction(DesktopPlayerActionKind.SPEED_SET, 2), resolve("Shift+Numpad2"))
        assertNull(resolve("Ctrl+Numpad0"))
        assertNull(resolve("Alt+Numpad5"))
        assertNull(resolve("Ctrl+Numpad5"))
        assertNull(resolve("NumpadAdd"))
        assertNull(resolve("Numpad"))
        assertNull(resolve("Numpad12"))
        assertNotNull(resolve("Numpad9"))
    }

    @Test fun theEscapeChainOrderAndDecisionD4() {
        val legacy = DesktopWindowsUxPlayerLayout.LEGACY
        val desktop = DesktopWindowsUxPlayerLayout.DESKTOP
        for (layout in listOf(legacy, desktop)) {
            for (fullscreen in listOf(false, true)) assertEquals(DesktopPlayerEscStep.CLOSE_POPUP, resolveDesktopPlayerEscStep(true, fullscreen, layout))
            assertEquals(DesktopPlayerEscStep.LEAVE_FULLSCREEN, resolveDesktopPlayerEscStep(false, true, layout))
        }
        assertEquals(DesktopPlayerEscStep.LEAVE_PAGE, resolveDesktopPlayerEscStep(false, false, legacy))
        assertEquals(DesktopPlayerEscStep.NONE, resolveDesktopPlayerEscStep(false, false, desktop))
    }

    @Test fun theTooltipsNameTheKeyTheTableBinds() {
        val tooltips = mapOf(
            DesktopPlayerStrings.TOOLTIP_PLAY to ("Space" to DesktopPlayerActionKind.PLAY_PAUSE),
            DesktopPlayerStrings.TOOLTIP_MUTE to ("M" to DesktopPlayerActionKind.MUTE),
            DesktopPlayerStrings.TOOLTIP_FULLSCREEN to ("F" to DesktopPlayerActionKind.FULLSCREEN_TOGGLE),
            DesktopPlayerStrings.TOOLTIP_MINI to ("P" to DesktopPlayerActionKind.MINI_PLAYER),
            DesktopPlayerStrings.TOOLTIP_CINEMA to ("T" to DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA),
            DesktopPlayerStrings.TOOLTIP_DANMAKU to ("D" to DesktopPlayerActionKind.DANMAKU_TOGGLE),
            DesktopPlayerStrings.TOOLTIP_SUBTITLE to ("C" to DesktopPlayerActionKind.SUBTITLE_TOGGLE),
            DesktopPlayerStrings.TOOLTIP_DETAILS to ("Ctrl+B" to DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA),
        )
        assertEquals(8, tooltips.size)
        for ((tooltip, binding) in tooltips) {
            assertEquals(binding.second, resolve(binding.first)?.kind, tooltip)
            assertTrue(tooltip.contains('（') && tooltip.endsWith("）"), tooltip)
        }
        assertTrue(DesktopPlayerStrings.TOOLTIP_PLAY.contains("空格"))
    }
}
