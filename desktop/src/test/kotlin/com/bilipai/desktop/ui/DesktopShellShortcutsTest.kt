package com.bilipai.desktop.ui

import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure shell shortcut table and resolver against the shared contract. No window, no GPU, no network. */
class DesktopShellShortcutsTest {
    private val shortcuts = DesktopUxContract.section("shortcuts")
    private val shellRows = DesktopUxContract.objects(shortcuts.getValue("rows")).filter { DesktopUxContract.string(it, "scope") == "shell" }

    private val pages = DesktopShellPage.entries
    private val home = DesktopShellKeyContext(DesktopShellPage.HOME)

    /** "Ctrl+Shift+Left" as key data. */
    private fun event(chord: String, keyDown: Boolean = true): DesktopShellKeyData {
        val parts = chord.split("+")
        val modifiers = parts.dropLast(1).toSet()
        return DesktopShellKeyData(
            key = parts.last(), ctrl = "Ctrl" in modifiers, alt = "Alt" in modifiers, shift = "Shift" in modifiers,
            meta = "Meta" in modifiers, keyDown = keyDown,
        )
    }

    private fun resolve(chord: String, page: DesktopShellPage = DesktopShellPage.HOME, textEditorFocused: Boolean = false) =
        resolveDesktopShellKey(event(chord), DesktopShellKeyContext(page, textEditorFocused))

    private fun resolved(chord: String, page: DesktopShellPage = DesktopShellPage.HOME): DesktopShellActionKind? = resolve(chord, page)?.kind

    @Test fun theTableIsTheContractShellRowsInOrder() {
        assertEquals(shellRows.size, DESKTOP_SHELL_SHORTCUTS.size)
        for ((row, shortcut) in shellRows.zip(DESKTOP_SHELL_SHORTCUTS)) {
            val chord = DesktopUxContract.string(row, "chord")
            assertEquals(chord, shortcut.chord)
            val kind = DesktopUxContract.string(row, "action")
            assertEquals(kind, shortcut.action.kind.name, chord)
            when (kind) {
                "GO_DESTINATION" -> assertEquals(DesktopUxContract.string(row, "arg"), shortcut.action.destination?.name, chord)
                "CYCLE_PANES" -> assertEquals(DesktopUxContract.int(row, "arg"), shortcut.action.direction, chord)
                else -> {
                    assertNull(shortcut.action.destination, chord)
                    assertEquals(0, shortcut.action.direction, chord)
                }
            }
            assertEquals(row["status"]?.jsonPrimitive?.content == "existing", shortcut.existing, chord)
        }
        assertEquals(1, DESKTOP_SHELL_SHORTCUTS.count { it.existing })
        assertTrue(DESKTOP_SHELL_SHORTCUTS.first { it.existing }.chord == "F11")
    }

    @Test fun everyActionKindOfTheContractIsAnEnumEntry() {
        assertEquals(
            shellRows.map { DesktopUxContract.string(it, "action") }.toSet(),
            DesktopShellActionKind.entries.map { it.name }.toSet(),
        )
    }

    @Test fun reservedAndZoomSetsAreTheContractSets() {
        assertEquals(DesktopUxContract.strings(shortcuts.getValue("zoom_decoder_keys")).toSet(), DESKTOP_SHELL_ZOOM_KEYS)
        assertEquals(
            (DesktopUxContract.strings(shortcuts.getValue("reserved")) + DesktopUxContract.strings(shortcuts.getValue("not_taken"))).toSet(),
            DESKTOP_SHELL_RESERVED_CHORDS,
        )
    }

    @Test fun theTableObeysTheCollisionRules() {
        val chords = DESKTOP_SHELL_SHORTCUTS.map { it.chord }
        assertEquals(chords.size, chords.toSet().size, "no duplicate chord")
        val printableNames = setOf("Comma", "Period", "Slash", "LeftBracket", "RightBracket", "Space")
        for (chord in chords) {
            val parts = chord.split("+")
            val modifiers = parts.dropLast(1)
            val key = parts.last()
            assertFalse(chord in DESKTOP_SHELL_RESERVED_CHORDS, "$chord is reserved")
            assertFalse("Ctrl" in modifiers && "Alt" in modifiers, "$chord uses Ctrl+Alt")
            assertFalse("Meta" in modifiers, "$chord uses Meta")
            assertFalse("Ctrl" in modifiers && key in DESKTOP_SHELL_ZOOM_KEYS, "$chord is a zoom chord")
            assertFalse(modifiers.isEmpty() && (key.length == 1 || key in printableNames), "$chord is a bare printable key")
            if ("Ctrl" in modifiers && key.length == 1 && key[0].isDigit()) assertTrue(key[0] in '1'..'9', chord)
            assertEquals(modifiers, modifiers.sortedBy { listOf("Ctrl", "Alt", "Shift", "Meta").indexOf(it) }, "$chord modifier order")
        }
    }

    @Test fun ctrlNumberGoesOverTheFixedDestinations() {
        for (number in 1..9) {
            val action = resolve("Ctrl+$number")
            assertEquals(DesktopShellActionKind.GO_DESTINATION, action?.kind, "Ctrl+$number")
            assertEquals(desktopRailDestinationForNumber(number), action?.destination, "Ctrl+$number")
        }
        assertEquals(DesktopRailItem.HOME, resolve("Ctrl+1")?.destination)
        assertEquals(DesktopRailItem.DOWNLOADS, resolve("Ctrl+8")?.destination)
        assertEquals(DesktopRailItem.PROFILE, resolve("Ctrl+9")?.destination)
        assertNull(resolve("Ctrl+0"))
        assertNull(resolve("Ctrl+Shift+1"))
    }

    @Test fun everyTableRowResolvesToItsActionOnAShellPage() {
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            for (page in listOf(DesktopShellPage.HOME, DesktopShellPage.DYNAMIC)) {
                assertEquals(shortcut.action, resolve(shortcut.chord, page), "${shortcut.chord} on $page")
            }
        }
    }

    @Test fun aTextEditorOwningFocusStopsEveryChord() {
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            for (page in pages) {
                assertNull(resolve(shortcut.chord, page, textEditorFocused = true), "${shortcut.chord} on $page")
            }
        }
    }

    @Test fun altGrAndTheWindowsKeyAreNeverClaimed() {
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            val base = event(shortcut.chord)
            assertNull(resolveDesktopShellKey(base.copy(ctrl = true, alt = true), home), "${shortcut.chord} with Ctrl+Alt")
            assertNull(resolveDesktopShellKey(base.copy(meta = true), home), "${shortcut.chord} with Meta")
        }
    }

    @Test fun aKeyReleaseIsNeverClaimed() {
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            assertNull(resolveDesktopShellKey(event(shortcut.chord, keyDown = false), home), shortcut.chord)
        }
    }

    @Test fun reservedChordsAndZoomKeysAreNeverClaimedEvenWithShift() {
        for (key in DESKTOP_SHELL_ZOOM_KEYS) {
            for (shift in listOf(false, true)) {
                for (page in pages) {
                    assertNull(resolveDesktopShellKey(DesktopShellKeyData(key, ctrl = true, shift = shift), DesktopShellKeyContext(page)), "$key $shift $page")
                }
            }
        }
        for (chord in DESKTOP_SHELL_RESERVED_CHORDS) {
            for (page in pages) assertNull(resolve(chord, page), "$chord on $page")
        }
        assertNotNull(resolve("Alt+Enter")) // a different chord from the Enter that is not taken
        assertNull(resolve("Enter"))
        assertNull(resolve("Backspace"))
    }

    @Test fun bareLettersAndDigitsAreNotClaimedOnAnyPage() {
        for (key in ('A'..'Z') + ('0'..'9')) {
            for (page in pages) {
                assertNull(resolveDesktopShellKey(DesktopShellKeyData(key.toString()), DesktopShellKeyContext(page)), "$key on $page")
                assertNull(resolveDesktopShellKey(DesktopShellKeyData(key.toString(), shift = true), DesktopShellKeyContext(page)), "Shift+$key on $page")
            }
        }
    }

    @Test fun nothingOutsideTheTableIsClaimedOverTheWholeKeySpace() {
        val keys = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } + (1..12).map { "F$it" } +
            listOf("Left", "Right", "Up", "Down", "Home", "End", "Comma", "Period", "Slash", "Space", "Esc", "Enter", "Backspace", "Tab", "Menu", "Plus", "Equals", "Minus")
        val chords = DESKTOP_SHELL_SHORTCUTS.map { it.chord }.toSet()
        var claimed = 0
        for (key in keys) {
            for (bits in 0 until 16) {
                val data = DesktopShellKeyData(key, ctrl = bits and 1 != 0, alt = bits and 2 != 0, shift = bits and 4 != 0, meta = bits and 8 != 0)
                for (page in pages) {
                    val action = resolveDesktopShellKey(data, DesktopShellKeyContext(page)) ?: continue
                    claimed++
                    assertTrue(desktopShellChord(data) in chords, desktopShellChord(data))
                    assertFalse(data.meta || data.ctrl && data.alt)
                    assertEquals(DESKTOP_SHELL_SHORTCUTS.first { it.chord == desktopShellChord(data) }.action, action)
                }
            }
        }
        assertTrue(claimed >= DESKTOP_SHELL_SHORTCUTS.size)
    }

    @Test fun theChordIsModifiersInOrderThenTheKey() {
        assertEquals("Left", desktopShellChord(DesktopShellKeyData("Left")))
        assertEquals("Ctrl+Shift+Left", desktopShellChord(DesktopShellKeyData("Left", shift = true, ctrl = true)))
        assertEquals("Ctrl+Alt+Shift+Meta+K", desktopShellChord(DesktopShellKeyData("K", ctrl = true, alt = true, shift = true, meta = true)))
        assertEquals("Alt+Left", desktopShellChord(DesktopShellKeyData("Left", alt = true)))
        assertEquals("Shift+F6", desktopShellChord(DesktopShellKeyData("F6", shift = true)))
    }

    @Test fun searchIsIgnoredOnTheSearchRoute() {
        assertEquals(DesktopShellActionKind.OPEN_SEARCH, resolved("Ctrl+K"))
        assertEquals(DesktopShellActionKind.OPEN_SEARCH, resolved("Ctrl+F", DesktopShellPage.OTHER))
        assertNull(resolved("Ctrl+K", DesktopShellPage.SEARCH))
        assertNull(resolved("Ctrl+F", DesktopShellPage.SEARCH))
    }

    @Test fun refreshIsForHomeAndDynamicOnly() {
        for (chord in listOf("F5", "Ctrl+R")) {
            assertEquals(DesktopShellActionKind.REFRESH, resolved(chord, DesktopShellPage.HOME))
            assertEquals(DesktopShellActionKind.REFRESH, resolved(chord, DesktopShellPage.DYNAMIC))
            for (page in listOf(DesktopShellPage.SEARCH, DesktopShellPage.OTHER, DesktopShellPage.PLAYER)) assertNull(resolved(chord, page), "$chord on $page")
        }
    }

    @Test fun ctrlBTogglesTheRailOnShellPagesAndIsLeftToThePlayerOnTheVideoPage() {
        for (page in listOf(DesktopShellPage.HOME, DesktopShellPage.DYNAMIC, DesktopShellPage.SEARCH, DesktopShellPage.OTHER)) {
            assertEquals(DesktopShellActionKind.RAIL_TOGGLE, resolved("Ctrl+B", page), page.name)
        }
        assertNull(resolved("Ctrl+B", DesktopShellPage.PLAYER))
    }

    @Test fun aPlayerPageKeepsOnlyBackFullscreenHelpAndPaneCycling() {
        assertEquals(DesktopShellActionKind.BACK, resolved("Alt+Left", DesktopShellPage.PLAYER))
        assertEquals(DesktopShellActionKind.FULLSCREEN_TOGGLE, resolved("F11", DesktopShellPage.PLAYER))
        assertEquals(DesktopShellActionKind.FULLSCREEN_TOGGLE, resolved("Alt+Enter", DesktopShellPage.PLAYER))
        assertEquals(DesktopShellActionKind.SHORTCUT_HELP, resolved("F1", DesktopShellPage.PLAYER))
        // F6 cycles the content and the side panel of the video page (spec 6.6); the contract has no player-scope F6 row.
        assertEquals(DesktopShellAction(DesktopShellActionKind.CYCLE_PANES, direction = 1), resolve("F6", DesktopShellPage.PLAYER))
        assertEquals(DesktopShellAction(DesktopShellActionKind.CYCLE_PANES, direction = -1), resolve("Shift+F6", DesktopShellPage.PLAYER))
        val kept = setOf(
            DesktopShellActionKind.BACK, DesktopShellActionKind.FULLSCREEN_TOGGLE, DesktopShellActionKind.SHORTCUT_HELP,
            DesktopShellActionKind.CYCLE_PANES,
        )
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            val action = resolve(shortcut.chord, DesktopShellPage.PLAYER)
            assertEquals(shortcut.action.kind in kept, action != null, shortcut.chord)
        }
        val playerScopeKeys = DesktopUxContract.objects(shortcuts.getValue("rows"))
            .filter { DesktopUxContract.string(it, "scope") == "player" }
            .map { DesktopUxContract.string(it, "chord") }
        assertFalse("F6" in playerScopeKeys || "Shift+F6" in playerScopeKeys, "the player scope must not also own F6")
    }

    @Test fun otherShortcutsWorkOnEveryShellPage() {
        for (page in listOf(DesktopShellPage.HOME, DesktopShellPage.DYNAMIC, DesktopShellPage.SEARCH, DesktopShellPage.OTHER)) {
            assertEquals(DesktopShellActionKind.BACK, resolved("Alt+Left", page))
            assertEquals(DesktopShellActionKind.OPEN_SETTINGS, resolved("Ctrl+Comma", page))
            assertEquals(DesktopShellActionKind.OPEN_DOWNLOADS, resolved("Ctrl+J", page))
            assertEquals(DesktopShellActionKind.SHORTCUT_HELP, resolved("F1", page))
            assertEquals(DesktopShellActionKind.FULLSCREEN_TOGGLE, resolved("F11", page))
            assertEquals(DesktopShellActionKind.GO_DESTINATION, resolved("Ctrl+3", page))
        }
        assertEquals(1, resolve("F6")?.direction)
        assertEquals(-1, resolve("Shift+F6")?.direction)
        assertNull(resolve("Left"))
        assertNull(resolve("Ctrl+Left"))
    }

    @Test fun everyActionHasALabel() {
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            assertTrue(DesktopShellStrings.actionLabel(shortcut.action).isNotBlank(), shortcut.chord)
        }
        assertEquals("转到首页", DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.GO_DESTINATION, DesktopRailItem.HOME)))
        assertEquals("切换到下一个区域", DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.CYCLE_PANES, direction = 1)))
        assertEquals("切换到上一个区域", DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.CYCLE_PANES, direction = -1)))
        assertEquals(DesktopShellActionKind.entries.size, DesktopShellActionKind.entries.map { kind ->
            DesktopShellStrings.actionLabel(DesktopShellAction(kind, DesktopRailItem.HOME, 1))
        }.toSet().size)
    }

    @Test fun everyActionLabelIsItsExactText() {
        val plain = mapOf(
            DesktopShellActionKind.OPEN_SEARCH to "搜索",
            DesktopShellActionKind.BACK to "返回",
            DesktopShellActionKind.REFRESH to "刷新",
            DesktopShellActionKind.RAIL_TOGGLE to "折叠或展开导航栏",
            DesktopShellActionKind.OPEN_SETTINGS to "设置",
            DesktopShellActionKind.OPEN_DOWNLOADS to "下载与离线",
            DesktopShellActionKind.SHORTCUT_HELP to "快捷键列表",
            DesktopShellActionKind.FULLSCREEN_TOGGLE to "全屏",
        )
        for ((kind, text) in plain) {
            assertEquals(text, DesktopShellStrings.actionLabel(DesktopShellAction(kind)), kind.name)
        }
        // The two kinds that depend on an argument: the destination label and the direction of the pane cycle.
        assertEquals(
            DesktopShellActionKind.entries.toSet(),
            plain.keys + DesktopShellActionKind.GO_DESTINATION + DesktopShellActionKind.CYCLE_PANES,
        )
        assertEquals("转到下载与离线", DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.GO_DESTINATION, DesktopRailItem.DOWNLOADS)))
        assertEquals("转到", DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.GO_DESTINATION)))
        assertEquals("切换到下一个区域", DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.CYCLE_PANES)))
        assertEquals("Ctrl+B 在视频页打开或关闭详情面板", DesktopShellStrings.CTRL_B_NOTE)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Slice S2: the collision test of spec 10.1 over the real table, and the keys the host reads
    // ---------------------------------------------------------------------------------------------------------------

    @Test fun everyKeyNameOfTheRealTableHasAnAwtKeyCodeSoTheHostCanReadIt() {
        val named = HashSet<String>()
        for (code in 0..2047) desktopPlayerKeyName(code)?.let { named += it }
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            val key = shortcut.chord.substringAfterLast("+")
            assertTrue(key in named, "${shortcut.chord}: no AWT key code is named $key")
        }
        for (key in DESKTOP_SHELL_ZOOM_KEYS) assertTrue(key in named, "the zoom key $key has no AWT key code")
    }

    @Test fun theRealTableCollidesWithNoWindowsSystemTextEditingOrFocusTraversalChord() {
        // Spec 6.1 reserved list and the chords a text field, the Windows shell and the Compose focus traversal already own.
        val owned = listOf(
            "Alt+F4", "Alt+Tab", "Alt+Space", "Alt+Esc", "Ctrl+Esc", "Ctrl+Alt+Delete", "Meta+D", "Meta+L", "F10", "Shift+F10", "Menu",
            "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+A", "Ctrl+Z", "Ctrl+Y", "Ctrl+Backspace", "Ctrl+Delete", "Ctrl+Left", "Ctrl+Right", "Ctrl+Home",
            "Ctrl+End", "Shift+Left", "Shift+Right", "Shift+Up", "Shift+Down", "Shift+Home", "Shift+End", "Home", "End", "Left", "Right", "Up",
            "Down", "PageUp", "PageDown", "Tab", "Shift+Tab", "Ctrl+Tab", "Space", "Enter", "Backspace", "Delete", "Esc", "Ctrl+Plus",
            "Ctrl+Minus", "Ctrl+0", "Ctrl+Equals",
        )
        val table = DESKTOP_SHELL_SHORTCUTS.map { it.chord }.toSet()
        for (chord in owned) assertFalse(chord in table, "$chord is in the shell table")
        for (chord in owned) {
            for (page in pages) assertNull(resolve(chord, page), "$chord on $page")
        }
    }

    @Test fun theRailRovingKeysAreUnmodifiedAndNoShellChordIsUnmodifiedArrowHomeOrEnd() {
        val roving = setOf("Up", "Down", "Home", "End")
        for (shortcut in DESKTOP_SHELL_SHORTCUTS) {
            assertFalse(shortcut.chord in roving, shortcut.chord)
        }
    }

    @Test fun aChordTheShellAndThePlayerBothListHasTheSameActionOrIsTheDualMeaningChord() {
        val rows = DesktopUxContract.objects(shortcuts.getValue("rows"))
        val dual = DesktopUxContract.strings(shortcuts.getValue("dual_meaning")).toSet()
        for (shell in rows.filter { DesktopUxContract.string(it, "scope") == "shell" }) {
            val chord = DesktopUxContract.string(shell, "chord")
            for (player in rows.filter { DesktopUxContract.string(it, "scope") == "player" && DesktopUxContract.string(it, "chord") == chord }) {
                assertTrue(
                    chord in dual || DesktopUxContract.string(player, "action") == DesktopUxContract.string(shell, "action"),
                    "$chord means ${DesktopUxContract.string(shell, "action")} in the shell and ${DesktopUxContract.string(player, "action")} in the player",
                )
            }
        }
    }

    @Test fun theSettingsAndDownloadsLabelsAreTheRailLabelsOfThoseRows() {
        assertEquals(
            DesktopShellStrings.railLabel(DesktopRailItem.SETTINGS),
            DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.OPEN_SETTINGS)),
        )
        assertEquals(
            DesktopShellStrings.railLabel(DesktopRailItem.DOWNLOADS),
            DesktopShellStrings.actionLabel(DesktopShellAction(DesktopShellActionKind.OPEN_DOWNLOADS)),
        )
    }
}
