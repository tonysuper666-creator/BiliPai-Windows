package com.bilipai.desktop.ui

import com.bilipai.desktop.ui.kit.DESKTOP_KIT_CONTROL_HEIGHT_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_LIST_DP
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure rules of the LEGACY control bar against the shared contract ("control_bar"): the width breakpoints, the shortcut
 * hint of every button, the menu window heights and the quality label. No Compose scene, no window, no GPU, no network: the model
 * is plain Kotlin.
 */
class DesktopWindowsVideoControlBarModelTest {
    private val section = DesktopUxContract.section("control_bar")
    private val layout = section.getValue("layout").jsonObject
    private val menu = section.getValue("menu").jsonObject
    private val golden = section.getValue("golden").jsonObject

    private fun flag(row: JsonObject, key: String): Boolean = assertNotNull(row.getValue(key).jsonPrimitive.booleanOrNull, key)

    @Test fun constantsAreTheContractNumbers() {
        assertEquals(DesktopUxContract.int(layout, "expanded_min_width"), DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP)
        assertEquals(DesktopUxContract.int(layout, "status_min_width"), DESKTOP_CONTROL_BAR_STATUS_MIN_DP)
        assertEquals(DesktopUxContract.int(layout, "row_min_height"), DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP)
        assertEquals(DesktopUxContract.int(layout, "side_padding"), DESKTOP_CONTROL_BAR_SIDE_PADDING_DP)
        assertEquals(DesktopUxContract.int(layout, "button_gap"), DESKTOP_CONTROL_BAR_BUTTON_GAP_DP)
        assertEquals(DesktopUxContract.int(layout, "pill_padding"), DESKTOP_CONTROL_BAR_PILL_PADDING_DP)
        assertEquals(DesktopUxContract.int(menu, "host_padding"), DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP)
        assertEquals(DesktopUxContract.int(menu, "rounding_slack"), DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP)
        assertEquals(DesktopUxContract.int(menu, "start_inset"), DESKTOP_CONTROL_BAR_MENU_START_INSET_DP)
        assertEquals(DesktopUxContract.int(menu, "divider_block"), DESKTOP_CONTROL_BAR_MENU_DIVIDER_BLOCK_DP)
        assertEquals(DesktopUxContract.int(menu, "min_height"), DESKTOP_CONTROL_BAR_MENU_MIN_DP)
        assertEquals(DesktopUxContract.int(menu, "max_height"), DESKTOP_CONTROL_BAR_MENU_MAX_DP)
        assertEquals(DesktopUxContract.int(menu, "compact_more_max_height"), DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP)
        assertEquals(DesktopUxContract.int(menu, "more_base_rows"), DESKTOP_CONTROL_BAR_MORE_BASE_ROWS)
        assertEquals(DesktopUxContract.int(menu, "more_compact_fixed_rows"), DESKTOP_CONTROL_BAR_MORE_COMPACT_FIXED_ROWS)
        assertEquals(DesktopUxContract.int(menu, "more_compact_dividers"), DESKTOP_CONTROL_BAR_MORE_COMPACT_DIVIDERS)
        assertEquals(DesktopUxContract.int(menu, "more_compact_slider_rows"), DESKTOP_CONTROL_BAR_MORE_COMPACT_SLIDER_ROWS)
    }

    @Test fun theLayoutNumbersFollowTheKitTokensAndTheSpec() {
        val tokens = DesktopUxContract.section("tokens")
        val dock = DesktopUxContract.section("player").getValue("overlay").jsonObject.getValue("dock").jsonObject
        assertEquals(DesktopUxContract.int(tokens, "control_height"), DESKTOP_KIT_CONTROL_HEIGHT_DP)
        assertEquals(DesktopUxContract.int(tokens.getValue("row_height").jsonObject, "list"), DESKTOP_KIT_ROW_LIST_DP)
        assertEquals(DESKTOP_KIT_ROW_LIST_DP, DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP, "the bar row is the kit list row height")
        assertEquals(DesktopUxContract.int(dock, "button_gap"), DESKTOP_CONTROL_BAR_BUTTON_GAP_DP, "the gap between two buttons is the dock gap")
        assertEquals(940, DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP, "the legacy breakpoint of the full control set")
        assertEquals(520, DESKTOP_CONTROL_BAR_STATUS_MIN_DP, "the legacy breakpoint of the status text")
        assertEquals(64, DESKTOP_CONTROL_BAR_MENU_MIN_DP)
        assertEquals(420, DESKTOP_CONTROL_BAR_MENU_MAX_DP)
        assertEquals(564, DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP)
    }

    @Test fun everyBreakpointRowGetsItsLayout() {
        val rows = DesktopUxContract.objects(golden.getValue("breakpoints"))
        assertTrue(rows.size >= 6)
        for (row in rows) {
            val width = DesktopUxContract.double(row, "width").toFloat()
            assertEquals(flag(row, "expanded"), desktopControlBarExpanded(width), "expanded at $width")
            assertEquals(flag(row, "status"), desktopControlBarShowsStatus(width), "status at $width")
        }
    }

    @Test fun theBreakpointsAreInclusiveAtTheirEdge() {
        assertEquals(false, desktopControlBarExpanded(939.99f))
        assertEquals(true, desktopControlBarExpanded(940f))
        assertEquals(false, desktopControlBarShowsStatus(519.99f))
        assertEquals(true, desktopControlBarShowsStatus(520f))
    }

    @Test fun everyMenuHeightRowGetsItsHeight() {
        val rows = DesktopUxContract.objects(golden.getValue("menu_height"))
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val actual = desktopControlBarMenuHeightDp(
                DesktopUxContract.int(row, "rows"), DesktopUxContract.int(row, "dividers"), DesktopUxContract.int(row, "slider_rows"),
            )
            assertEquals(DesktopUxContract.int(row, "expect"), actual, id)
        }
    }

    @Test fun theContentHeightIsTheHostPaddingPlusTheRoundingSlackPlusRowsSlidersAndDividers() {
        assertEquals(8 + 4, DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP + DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP)
        assertEquals(12, desktopControlBarMenuContentDp(0))
        assertEquals(12 + 3 * 32, desktopControlBarMenuContentDp(3))
        assertEquals(12 + 2 * 32 + 40, desktopControlBarMenuContentDp(2, sliderRows = 1))
        assertEquals(12 + 32 + 2 * 9, desktopControlBarMenuContentDp(1, dividers = 2))
        assertEquals(12, desktopControlBarMenuContentDp(-4, -2, -1), "a negative count is none")
    }

    @Test fun aMenuNeverFallsUnderTheFloorOrOverTheLimitAndGrowsWithItsRows() {
        var previous = 0
        for (rows in -2..60) {
            val height = desktopControlBarMenuHeightDp(rows)
            assertTrue(height in DESKTOP_CONTROL_BAR_MENU_MIN_DP..DESKTOP_CONTROL_BAR_MENU_MAX_DP, "rows $rows: $height")
            assertTrue(height >= previous, "rows $rows: monotone")
            previous = height
            assertTrue(desktopControlBarMenuHeightDp(rows, maxDp = 564) in DESKTOP_CONTROL_BAR_MENU_MIN_DP..564)
        }
        assertEquals(40, desktopControlBarMenuHeightDp(5, maxDp = 40), "a limit under the floor wins instead of throwing")
    }

    @Test fun everyMoreMenuRowGetsItsHeight() {
        val rows = DesktopUxContract.objects(golden.getValue("more_menu_height"))
        assertTrue(rows.size >= 6)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val actual = desktopControlBarMoreMenuHeightDp(
                flag(row, "expanded"), DesktopUxContract.int(row, "speed_rows"), DesktopUxContract.int(row, "quality_rows"),
                flag(row, "chapter_row"), flag(row, "collection_row"), flag(row, "queue_row"),
            )
            assertEquals(DesktopUxContract.int(row, "expect"), actual, id)
        }
    }

    @Test fun theCompactMoreMenuMayGrowToItsOwnLimitAndTheWideOneStaysUnderTheCommonLimit() {
        assertEquals(564, desktopControlBarMoreMenuHeightDp(false, 30, 30, true, true, true))
        assertEquals(300, desktopControlBarMoreMenuHeightDp(true, 30, 30, true, true, true), "the wide menu ignores the speed and quality rows")
        assertTrue(desktopControlBarMoreMenuHeightDp(true, 0, 0, true, true, true) <= DESKTOP_CONTROL_BAR_MENU_MAX_DP)
    }

    @Test fun theHintTableIsTheContractHintTable() {
        val hints = DesktopUxContract.objects(section.getValue("hints"))
        assertEquals(hints.size, DESKTOP_CONTROL_BAR_HINTS.size)
        assertEquals(DesktopControlBarHintTarget.entries.map { it.name }, hints.map { DesktopUxContract.string(it, "target") })
        for ((row, hint) in hints.zip(DESKTOP_CONTROL_BAR_HINTS)) {
            assertEquals(DesktopUxContract.string(row, "target"), hint.target.name)
            assertEquals(DesktopUxContract.string(row, "chord"), hint.chord)
            assertEquals(DesktopUxContract.string(row, "action"), hint.action.kind.name)
            assertEquals(row["arg"]?.let { DesktopUxContract.int(row, "arg") } ?: 0, hint.action.arg)
        }
    }

    @Test fun everyHintNamesAnActiveRowOfThePlayerTableAndReadsAsTheContractLabel() {
        for (row in DesktopUxContract.objects(section.getValue("hints"))) {
            val target = DesktopControlBarHintTarget.valueOf(DesktopUxContract.string(row, "target"))
            val shortcut = DESKTOP_PLAYER_SHORTCUTS.single { it.chord == DesktopUxContract.string(row, "chord") }
            assertEquals(DesktopPlayerShortcutStatus.ACTIVE, shortcut.status, target.name)
            val expected = if (row["label_from"] != null) {
                assertEquals("KEY_SPACE", DesktopUxContract.string(row, "label_from"))
                DesktopWindowsVideoControlBarStrings.KEY_SPACE
            } else {
                DesktopUxContract.string(row, "label")
            }
            assertEquals(expected, desktopControlBarShortcutHint(target), target.name)
        }
    }

    @Test fun aHintDisappearsWhenItsChordIsNoLongerAnActiveRowWithTheSameAction() {
        val target = DesktopControlBarHintTarget.MUTE
        val hint = DESKTOP_CONTROL_BAR_HINTS.single { it.target == target }
        val active = DesktopPlayerShortcut(hint.chord, hint.action)
        assertEquals("M", desktopControlBarShortcutHint(target, listOf(active)))
        assertNull(desktopControlBarShortcutHint(target, emptyList()), "no row")
        assertNull(desktopControlBarShortcutHint(target, listOf(active.copy(status = DesktopPlayerShortcutStatus.EXISTING_AT_ROOT))), "not claimed")
        assertNull(desktopControlBarShortcutHint(target, listOf(active.copy(status = DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API))), "omitted")
        assertNull(desktopControlBarShortcutHint(target, listOf(active.copy(action = DesktopPlayerAction(DesktopPlayerActionKind.PLAY_PAUSE)))), "rebound")
        assertNull(desktopControlBarShortcutHint(target, listOf(active.copy(chord = "N"))), "another chord")
        assertNull(desktopControlBarShortcutHint(target, listOf(active), hints = emptyList()), "no hint for the target")
    }

    @Test fun theKeyLabelsOfTheTooltips() {
        assertEquals(DesktopWindowsVideoControlBarStrings.KEY_SPACE, desktopControlBarKeyLabel("Space"))
        assertEquals("[", desktopControlBarKeyLabel("LeftBracket"))
        assertEquals("]", desktopControlBarKeyLabel("RightBracket"))
        assertEquals("Ctrl+B", desktopControlBarKeyLabel("Ctrl+B"))
        assertEquals("M", desktopControlBarKeyLabel("M"))
    }

    @Test fun theQualityLabelIsTheSelectedQualityOrThePlainWord() {
        val qualities = listOf(116 to "1080P60", 80 to "1080P", 64 to "720P")
        assertEquals("1080P", desktopControlBarQualityLabel(qualities, 80))
        assertEquals("1080P60", desktopControlBarQualityLabel(qualities, 116))
        assertEquals(DesktopWindowsVideoControlBarStrings.QUALITY, desktopControlBarQualityLabel(qualities, 32), "a selection that is not listed")
        assertEquals(DesktopWindowsVideoControlBarStrings.QUALITY, desktopControlBarQualityLabel(qualities, null))
        assertEquals(DesktopWindowsVideoControlBarStrings.QUALITY, desktopControlBarQualityLabel(emptyList(), 80))
    }

    @Test fun theStringsKeepTheNamesTheFixturesClickByName() {
        assertEquals(DesktopPlayerStrings.PLAY, DesktopWindowsVideoControlBarStrings.PLAY)
        assertEquals(DesktopPlayerStrings.PAUSE, DesktopWindowsVideoControlBarStrings.PAUSE)
        assertEquals(DesktopPlayerStrings.FULLSCREEN, DesktopWindowsVideoControlBarStrings.FULLSCREEN)
        assertEquals(DesktopPlayerStrings.EXIT_FULLSCREEN, DesktopWindowsVideoControlBarStrings.EXIT_FULLSCREEN)
        assertEquals(DesktopPlayerStrings.DETAILS, DesktopWindowsVideoControlBarStrings.DETAILS)
        assertEquals(DesktopPlayerStrings.MINI_PLAYER, DesktopWindowsVideoControlBarStrings.MINI_PLAYER)
        assertEquals(DesktopPlayerStrings.MORE_ACTIONS, DesktopWindowsVideoControlBarStrings.MORE_ACTIONS)
        assertEquals(DesktopPlayerStrings.INTRODUCTION_AND_SETTINGS, DesktopWindowsVideoControlBarStrings.INTRODUCTION_AND_SETTINGS)
        assertEquals(DesktopPlayerStrings.QUALITY_PILL_FALLBACK, DesktopWindowsVideoControlBarStrings.QUALITY)
    }

    @Test fun theTemplatesOfTheStrings() {
        assertEquals("1.5×", DesktopWindowsVideoControlBarStrings.speedValue("1.5"))
        assertEquals("倍速 2.0×", DesktopWindowsVideoControlBarStrings.speedRow("2.0"))
        assertEquals("画质 · 1080P", DesktopWindowsVideoControlBarStrings.qualityRow("1080P"))
        assertEquals("1:05 · Intro", DesktopWindowsVideoControlBarStrings.chapterRow("1:05", "Intro"))
        assertEquals("75%", DesktopWindowsVideoControlBarStrings.volumePercent(75))
    }
}
