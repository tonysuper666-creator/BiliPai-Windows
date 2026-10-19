package com.bilipai.desktop.appearance

import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure model of the windows_ux flags store against the shared contract. No window, no GPU, no network. */
class DesktopWindowsUxModelTest {
    private val flags = DesktopUxContract.section("flags")

    private fun decode(vararg values: Pair<String, JsonElement>): DesktopWindowsUxSettings =
        decodeDesktopWindowsUxValues(mapOf(*values))

    private val sane = DesktopWindowsUxWindowBounds(x = 40, y = 30, width = 1360, height = 900, maximized = false)

    @Test fun namespaceAndLimitsMatchTheContract() {
        assertEquals(DesktopUxContract.string(flags, "namespace"), WINDOWS_UX_NAMESPACE)
        val width = flags.getValue("details_panel_width").jsonObject
        assertEquals(DesktopUxContract.int(width, "min"), WINDOWS_UX_DETAILS_PANEL_MIN_WIDTH_DP)
        assertEquals(DesktopUxContract.int(width, "max"), WINDOWS_UX_DETAILS_PANEL_MAX_WIDTH_DP)
        val window = flags.getValue("window").jsonObject
        assertEquals(DesktopUxContract.int(window, "min_extent"), WINDOWS_UX_WINDOW_MIN_EXTENT)
        assertEquals(DesktopUxContract.int(window, "max_extent"), WINDOWS_UX_WINDOW_MAX_EXTENT)
        assertEquals(DesktopUxContract.int(window, "max_abs_origin"), WINDOWS_UX_WINDOW_MAX_ORIGIN)
    }

    @Test fun enumValuesAndDefaultsMatchTheContract() {
        val defaults = DesktopWindowsUxSettings()
        val entries = mapOf(
            "rail_mode" to DesktopWindowsUxRailMode.entries.map { it.name },
            "player_layout" to DesktopWindowsUxPlayerLayout.entries.map { it.name },
            "home_layout" to DesktopWindowsUxHomeLayout.entries.map { it.name },
            "card_interactions" to DesktopWindowsUxCardInteractions.entries.map { it.name },
            "details_panel" to DesktopWindowsUxDetailsPanel.entries.map { it.name },
            "wheel_action" to DesktopWindowsUxWheelAction.entries.map { it.name },
            "click_action" to DesktopWindowsUxClickAction.entries.map { it.name },
            "double_click_action" to DesktopWindowsUxDoubleClickAction.entries.map { it.name },
        )
        val actualDefaults = mapOf(
            "rail_mode" to defaults.railMode.name,
            "player_layout" to defaults.playerLayout.name,
            "home_layout" to defaults.homeLayout.name,
            "card_interactions" to defaults.cardInteractions.name,
            "details_panel" to defaults.detailsPanel.name,
            "wheel_action" to defaults.wheelAction.name,
            "click_action" to defaults.clickAction.name,
            "double_click_action" to defaults.doubleClickAction.name,
        )
        val fields = DesktopUxContract.objects(flags.getValue("fields"))
        assertEquals(entries.size, fields.size)
        for (field in fields) {
            val key = DesktopUxContract.string(field, "key")
            assertEquals(DesktopUxContract.strings(field.getValue("values")), entries.getValue(key), key)
            assertEquals(DesktopUxContract.string(field, "default"), actualDefaults.getValue(key), key)
        }
        assertNull(defaults.detailsPanelWidthDp)
        assertNull(defaults.windowBounds)
        assertTrue(defaults.invalidKeys.isEmpty())
        assertNull(defaults.error)
        assertEquals(DesktopWindowsUxPlayerLayout.LEGACY, defaults.playerLayout)
    }

    @Test fun storageKeysMatchTheContract() {
        val fieldKeys = DesktopUxContract.objects(flags.getValue("fields")).map { DesktopUxContract.string(it, "key") }
        val widthKey = DesktopUxContract.string(flags.getValue("details_panel_width").jsonObject, "key")
        val windowKeys = DesktopUxContract.strings(flags.getValue("window").jsonObject.getValue("keys"))
        assertEquals((fieldKeys + widthKey + windowKeys).toSet(), UX_ALL_KEYS.toSet())
        assertEquals(UX_ALL_KEYS.size, UX_ALL_KEYS.toSet().size)
        assertEquals(DesktopUxContract.strings(flags.getValue("reset_keys")), UX_LAYOUT_KEYS)
        assertTrue(UX_LAYOUT_KEYS.none { it in UX_WINDOW_KEYS })
    }

    @Test fun nothingStoredMeansDefaultsWithoutAnError() {
        assertEquals(DesktopWindowsUxSettings(), decodeDesktopWindowsUxValues(emptyMap()))
        assertEquals(DesktopWindowsUxSettings(), decodeDesktopWindowsUx(DesktopPreferenceSnapshot(JsonObject(emptyMap()))))
    }

    @Test fun everyStoredEnumValueDecodes() {
        for (mode in DesktopWindowsUxRailMode.entries) {
            assertEquals(mode, decode(UX_KEY_RAIL_MODE to JsonPrimitive(mode.name)).railMode)
        }
        for (value in DesktopWindowsUxPlayerLayout.entries) {
            assertEquals(value, decode(UX_KEY_PLAYER_LAYOUT to JsonPrimitive(value.name)).playerLayout)
        }
        for (value in DesktopWindowsUxHomeLayout.entries) {
            assertEquals(value, decode(UX_KEY_HOME_LAYOUT to JsonPrimitive(value.name)).homeLayout)
        }
        for (value in DesktopWindowsUxCardInteractions.entries) {
            assertEquals(value, decode(UX_KEY_CARD_INTERACTIONS to JsonPrimitive(value.name)).cardInteractions)
        }
        for (value in DesktopWindowsUxDetailsPanel.entries) {
            assertEquals(value, decode(UX_KEY_DETAILS_PANEL to JsonPrimitive(value.name)).detailsPanel)
        }
        for (value in DesktopWindowsUxWheelAction.entries) {
            assertEquals(value, decode(UX_KEY_WHEEL_ACTION to JsonPrimitive(value.name)).wheelAction)
        }
        for (value in DesktopWindowsUxClickAction.entries) {
            assertEquals(value, decode(UX_KEY_CLICK_ACTION to JsonPrimitive(value.name)).clickAction)
        }
        for (value in DesktopWindowsUxDoubleClickAction.entries) {
            assertEquals(value, decode(UX_KEY_DOUBLE_CLICK_ACTION to JsonPrimitive(value.name)).doubleClickAction)
        }
    }

    @Test fun everyNonDefaultSettingRoundTripsThroughTheEncoding() {
        val everything = DesktopWindowsUxSettings(
            railMode = DesktopWindowsUxRailMode.CLASSIC_TEXT,
            playerLayout = DesktopWindowsUxPlayerLayout.DESKTOP,
            homeLayout = DesktopWindowsUxHomeLayout.CLASSIC,
            cardInteractions = DesktopWindowsUxCardInteractions.OFF,
            detailsPanel = DesktopWindowsUxDetailsPanel.CLOSED,
            detailsPanelWidthDp = 444,
            wheelAction = DesktopWindowsUxWheelAction.SEEK,
            clickAction = DesktopWindowsUxClickAction.NONE,
            doubleClickAction = DesktopWindowsUxDoubleClickAction.NONE,
            windowBounds = DesktopWindowsUxWindowBounds(-1920, 25, 1200, 850, true),
        )
        assertEquals(everything, decodeDesktopWindowsUxValues(encodeDesktopWindowsUx(everything)))
        assertEquals(DesktopWindowsUxSettings(), decodeDesktopWindowsUxValues(encodeDesktopWindowsUx(DesktopWindowsUxSettings())))
    }

    @Test fun valuesThatAreNotUnderstoodFallBackToTheDefaultAndAreReported() {
        val decoded = decode(
            UX_KEY_RAIL_MODE to JsonPrimitive("bogus"),
            UX_KEY_PLAYER_LAYOUT to JsonPrimitive("desktop"), // case matters
            UX_KEY_HOME_LAYOUT to JsonPrimitive(3),
            UX_KEY_CARD_INTERACTIONS to JsonNull,
            UX_KEY_DETAILS_PANEL to JsonPrimitive(true),
            UX_KEY_WHEEL_ACTION to JsonPrimitive("SEEK"),
        )
        val defaults = DesktopWindowsUxSettings()
        assertEquals(defaults.railMode, decoded.railMode)
        assertEquals(defaults.playerLayout, decoded.playerLayout)
        assertEquals(defaults.homeLayout, decoded.homeLayout)
        assertEquals(defaults.cardInteractions, decoded.cardInteractions)
        assertEquals(defaults.detailsPanel, decoded.detailsPanel)
        assertEquals(DesktopWindowsUxWheelAction.SEEK, decoded.wheelAction) // a good value next to bad ones survives
        assertEquals(
            setOf(UX_KEY_RAIL_MODE, UX_KEY_PLAYER_LAYOUT, UX_KEY_HOME_LAYOUT, UX_KEY_CARD_INTERACTIONS, UX_KEY_DETAILS_PANEL),
            decoded.invalidKeys.toSet())
        assertEquals(DesktopWindowsUxStrings.INVALID_VALUES, decoded.error)
    }

    @Test fun detailsPanelWidthMustBeAWholeNumberInRange() {
        assertEquals(320, decode(UX_KEY_DETAILS_PANEL_WIDTH to JsonPrimitive(320)).detailsPanelWidthDp)
        assertEquals(560, decode(UX_KEY_DETAILS_PANEL_WIDTH to JsonPrimitive(560)).detailsPanelWidthDp)
        for (bad in listOf<JsonElement>(JsonPrimitive(319), JsonPrimitive(561), JsonPrimitive("400"), JsonPrimitive(400.5), JsonNull)) {
            val decoded = decode(UX_KEY_DETAILS_PANEL_WIDTH to bad)
            assertNull(decoded.detailsPanelWidthDp, bad.toString())
            assertEquals(listOf(UX_KEY_DETAILS_PANEL_WIDTH), decoded.invalidKeys, bad.toString())
        }
    }

    @Test fun windowBoundsAreOneGroup() {
        val full = decode(
            UX_KEY_WINDOW_X to JsonPrimitive(10), UX_KEY_WINDOW_Y to JsonPrimitive(20),
            UX_KEY_WINDOW_WIDTH to JsonPrimitive(1300), UX_KEY_WINDOW_HEIGHT to JsonPrimitive(800),
            UX_KEY_WINDOW_MAXIMIZED to JsonPrimitive(true),
        )
        assertEquals(DesktopWindowsUxWindowBounds(10, 20, 1300, 800, true), full.windowBounds)
        assertTrue(full.invalidKeys.isEmpty())
        // The maximised flag is optional.
        assertEquals(false, decode(
            UX_KEY_WINDOW_X to JsonPrimitive(10), UX_KEY_WINDOW_Y to JsonPrimitive(20),
            UX_KEY_WINDOW_WIDTH to JsonPrimitive(1300), UX_KEY_WINDOW_HEIGHT to JsonPrimitive(800)).windowBounds?.maximized)
        // A partial group is ignored as a whole and reported.
        val partial = decode(UX_KEY_WINDOW_X to JsonPrimitive(10), UX_KEY_WINDOW_WIDTH to JsonPrimitive(1300))
        assertNull(partial.windowBounds)
        assertEquals(setOf(UX_KEY_WINDOW_X, UX_KEY_WINDOW_WIDTH), partial.invalidKeys.toSet())
        // An insane group is ignored as well.
        val tiny = decode(
            UX_KEY_WINDOW_X to JsonPrimitive(0), UX_KEY_WINDOW_Y to JsonPrimitive(0),
            UX_KEY_WINDOW_WIDTH to JsonPrimitive(5), UX_KEY_WINDOW_HEIGHT to JsonPrimitive(800))
        assertNull(tiny.windowBounds)
        assertEquals(4, tiny.invalidKeys.size)
        // A bad flag is reported but does not by itself discard sane numbers.
        val badFlag = decode(
            UX_KEY_WINDOW_X to JsonPrimitive(10), UX_KEY_WINDOW_Y to JsonPrimitive(20),
            UX_KEY_WINDOW_WIDTH to JsonPrimitive(1300), UX_KEY_WINDOW_HEIGHT to JsonPrimitive(800),
            UX_KEY_WINDOW_MAXIMIZED to JsonPrimitive("yes"))
        assertNotNull(badFlag.windowBounds)
        assertEquals(listOf(UX_KEY_WINDOW_MAXIMIZED), badFlag.invalidKeys)
        // Negative origins are normal on a second monitor on the left.
        assertTrue(DesktopWindowsUxWindowBounds(-1920, -200, 1200, 850).isSane())
    }

    @Test fun commandsChangeExactlyTheirOwnValue() {
        val start = DesktopWindowsUxSettings()
        assertEquals(DesktopWindowsUxRailMode.COLLAPSED,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)).railMode)
        assertEquals(DesktopWindowsUxPlayerLayout.DESKTOP,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetPlayerLayout(DesktopWindowsUxPlayerLayout.DESKTOP)).playerLayout)
        assertEquals(DesktopWindowsUxHomeLayout.CLASSIC,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetHomeLayout(DesktopWindowsUxHomeLayout.CLASSIC)).homeLayout)
        assertEquals(DesktopWindowsUxCardInteractions.OFF,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetCardInteractions(DesktopWindowsUxCardInteractions.OFF)).cardInteractions)
        assertEquals(DesktopWindowsUxDetailsPanel.OPEN,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetDetailsPanel(DesktopWindowsUxDetailsPanel.OPEN)).detailsPanel)
        assertEquals(DesktopWindowsUxWheelAction.NONE,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetWheelAction(DesktopWindowsUxWheelAction.NONE)).wheelAction)
        assertEquals(DesktopWindowsUxClickAction.NONE,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetClickAction(DesktopWindowsUxClickAction.NONE)).clickAction)
        assertEquals(DesktopWindowsUxDoubleClickAction.NONE,
            desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetDoubleClickAction(DesktopWindowsUxDoubleClickAction.NONE)).doubleClickAction)
        val changed = desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.EXPANDED))
        assertEquals(start.copy(railMode = DesktopWindowsUxRailMode.EXPANDED), changed)
    }

    @Test fun panelWidthCommandCoercesIntoRangeAndNullRemoves() {
        fun width(value: Int?) = desktopWindowsUxAfter(DesktopWindowsUxSettings(), DesktopWindowsUxCommand.SetDetailsPanelWidth(value)).detailsPanelWidthDp
        assertEquals(560, width(1000))
        assertEquals(320, width(10))
        assertEquals(400, width(400))
        assertNull(width(null))
        val kept = DesktopWindowsUxSettings(detailsPanelWidthDp = 400)
        assertNull(desktopWindowsUxAfter(kept, DesktopWindowsUxCommand.SetDetailsPanelWidth(null)).detailsPanelWidthDp)
    }

    @Test fun windowBoundsCommandStoresForgetsAndIgnoresInsaneBounds() {
        val start = DesktopWindowsUxSettings()
        val stored = desktopWindowsUxAfter(start, DesktopWindowsUxCommand.SetWindowBounds(sane))
        assertEquals(sane, stored.windowBounds)
        assertEquals(sane, desktopWindowsUxAfter(stored, DesktopWindowsUxCommand.SetWindowBounds(
            DesktopWindowsUxWindowBounds(0, 0, 3, 3))).windowBounds)
        assertNull(desktopWindowsUxAfter(stored, DesktopWindowsUxCommand.SetWindowBounds(null)).windowBounds)
        assertTrue(desktopWindowsUxEdits(DesktopWindowsUxCommand.SetWindowBounds(DesktopWindowsUxWindowBounds(0, 0, 3, 3))).isEmpty())
        assertEquals(UX_WINDOW_KEYS.toSet(), desktopWindowsUxEdits(DesktopWindowsUxCommand.SetWindowBounds(null)).keys)
    }

    @Test fun resetRemovesEveryLayoutFlagAndKeepsTheWindowBounds() {
        val customised = DesktopWindowsUxSettings(
            railMode = DesktopWindowsUxRailMode.CLASSIC_TEXT, playerLayout = DesktopWindowsUxPlayerLayout.DESKTOP,
            homeLayout = DesktopWindowsUxHomeLayout.CLASSIC, cardInteractions = DesktopWindowsUxCardInteractions.OFF,
            detailsPanel = DesktopWindowsUxDetailsPanel.OPEN, detailsPanelWidthDp = 500,
            wheelAction = DesktopWindowsUxWheelAction.SEEK, clickAction = DesktopWindowsUxClickAction.NONE,
            doubleClickAction = DesktopWindowsUxDoubleClickAction.NONE, windowBounds = sane)
        val reset = desktopWindowsUxAfter(customised, DesktopWindowsUxCommand.ResetLayout)
        assertEquals(DesktopWindowsUxSettings(windowBounds = sane), reset)
        val edits = desktopWindowsUxEdits(DesktopWindowsUxCommand.ResetLayout)
        assertEquals(UX_LAYOUT_KEYS.toSet(), edits.keys)
        assertTrue(edits.values.all { it == null })
    }

    @Test fun aCommandWritesOnlyItsOwnKeyAndDefaultsStayImplicit() {
        val edits = desktopWindowsUxEdits(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED))
        assertEquals(mapOf<String, JsonElement?>(UX_KEY_RAIL_MODE to JsonPrimitive("COLLAPSED")), edits)
        // An explicit choice equal to the default is still written: it must survive a later flip of the default.
        val explicit = desktopWindowsUxEdits(DesktopWindowsUxCommand.SetPlayerLayout(DesktopWindowsUxPlayerLayout.LEGACY))
        assertEquals(mapOf<String, JsonElement?>(UX_KEY_PLAYER_LAYOUT to JsonPrimitive("LEGACY")),
            desktopWindowsUxEffectiveEdits(emptyMap(), explicit))
        // A command that changes nothing writes nothing.
        val stored = mapOf<String, JsonElement>(UX_KEY_RAIL_MODE to JsonPrimitive("COLLAPSED"))
        assertTrue(desktopWindowsUxEffectiveEdits(stored, edits).isEmpty())
        // Removing a key that is not stored writes nothing.
        assertTrue(desktopWindowsUxEffectiveEdits(emptyMap(), desktopWindowsUxEdits(DesktopWindowsUxCommand.ResetLayout)).isEmpty())
        assertEquals(setOf(UX_KEY_RAIL_MODE),
            desktopWindowsUxEffectiveEdits(stored, desktopWindowsUxEdits(DesktopWindowsUxCommand.ResetLayout)).keys)
    }

    @Test fun anOptimisticUpdateKeepsTheReadProblemsOfTheKeysItDoesNotEdit() {
        val invalid = decode(
            UX_KEY_RAIL_MODE to JsonPrimitive("bogus"),
            UX_KEY_DETAILS_PANEL_WIDTH to JsonPrimitive(100),
            UX_KEY_WHEEL_ACTION to JsonPrimitive("SEEK"),
        )
        assertEquals(setOf(UX_KEY_RAIL_MODE, UX_KEY_DETAILS_PANEL_WIDTH), invalid.invalidKeys.toSet())
        // A command on other keys keeps both problems and the message: the file still holds the bad values.
        val other = desktopWindowsUxAfter(invalid, DesktopWindowsUxCommand.SetClickAction(DesktopWindowsUxClickAction.NONE))
        assertEquals(invalid.invalidKeys, other.invalidKeys)
        assertEquals(DesktopWindowsUxStrings.INVALID_VALUES, other.error)
        assertEquals(DesktopWindowsUxClickAction.NONE, other.clickAction)
        assertEquals(DesktopWindowsUxWheelAction.SEEK, other.wheelAction)
        // A command that rewrites one bad key repairs that key only.
        val repaired = desktopWindowsUxAfter(invalid, DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.EXPANDED))
        assertEquals(listOf(UX_KEY_DETAILS_PANEL_WIDTH), repaired.invalidKeys)
        assertEquals(DesktopWindowsUxStrings.INVALID_VALUES, repaired.error)
        assertEquals(DesktopWindowsUxRailMode.EXPANDED, repaired.railMode)
        // Repairing the last bad key clears the message.
        val clean = desktopWindowsUxAfter(repaired, DesktopWindowsUxCommand.SetDetailsPanelWidth(400))
        assertTrue(clean.invalidKeys.isEmpty())
        assertNull(clean.error)
        assertEquals(400, clean.detailsPanelWidthDp)
        // Removing a bad key repairs it as well.
        val removed = desktopWindowsUxAfter(repaired, DesktopWindowsUxCommand.SetDetailsPanelWidth(null))
        assertTrue(removed.invalidKeys.isEmpty())
        assertNull(removed.error)
        // The reset removes every bad layout key.
        val reset = desktopWindowsUxAfter(invalid, DesktopWindowsUxCommand.ResetLayout)
        assertTrue(reset.invalidKeys.isEmpty())
        assertNull(reset.error)
    }

    @Test fun anOptimisticUpdateKeepsTheUnreadableMessageAndTheWindowGroupProblems() {
        // A namespace that could not be read stays reported: no command can repair it.
        val unreadable = DesktopWindowsUxSettings(error = DesktopWindowsUxStrings.UNREADABLE)
        val changed = desktopWindowsUxAfter(unreadable, DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED))
        assertEquals(DesktopWindowsUxRailMode.COLLAPSED, changed.railMode)
        assertEquals(DesktopWindowsUxStrings.UNREADABLE, changed.error)
        assertEquals(DesktopWindowsUxStrings.UNREADABLE, desktopWindowsUxAfter(changed, DesktopWindowsUxCommand.ResetLayout).error)
        // A partial window group stays reported until a command rewrites or forgets the group.
        val partial = decode(UX_KEY_WINDOW_X to JsonPrimitive(10), UX_KEY_WINDOW_WIDTH to JsonPrimitive(1300))
        val group = setOf(UX_KEY_WINDOW_X, UX_KEY_WINDOW_WIDTH)
        assertEquals(group, partial.invalidKeys.toSet())
        assertEquals(group, desktopWindowsUxAfter(partial, DesktopWindowsUxCommand.ResetLayout).invalidKeys.toSet())
        val insane = DesktopWindowsUxCommand.SetWindowBounds(DesktopWindowsUxWindowBounds(0, 0, 3, 3))
        assertEquals(group, desktopWindowsUxAfter(partial, insane).invalidKeys.toSet())
        for (command in listOf(DesktopWindowsUxCommand.SetWindowBounds(sane), DesktopWindowsUxCommand.SetWindowBounds(null))) {
            val repaired = desktopWindowsUxAfter(partial, command)
            assertTrue(repaired.invalidKeys.isEmpty(), command.toString())
            assertNull(repaired.error, command.toString())
        }
    }

    @Test fun inMemoryDefaultAppliesCommandsWithoutAFile() {
        val memory = DesktopWindowsUxMemory()
        assertEquals(DesktopWindowsUxSettings(), memory.settings.value)
        assertNull(memory.writeError.value)
        assertTrue(memory.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
        assertEquals(DesktopWindowsUxRailMode.COLLAPSED, memory.settings.value.railMode)
        assertTrue(memory.submit(DesktopWindowsUxCommand.ResetLayout))
        assertEquals(DesktopWindowsUxSettings(), memory.settings.value)
        // Two memory instances never share state; only a shared instance would (the local's default is the read-only one).
        assertEquals(DesktopWindowsUxSettings(), DesktopWindowsUxMemory().settings.value)
    }

    @Test fun theDefaultOfTheLocalIsAnImmutableSharedInstance() {
        // Compose keeps the default of a CompositionLocal in one holder per JVM, so every fixture that provides
        // nothing reads this one object. It must never change: a fixture that toggles flags provides its own memory.
        val first: DesktopWindowsUx = DesktopWindowsUxDefaults
        val second: DesktopWindowsUx = DesktopWindowsUxDefaults
        assertSame(first, second)
        assertFalse(first.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
        assertFalse(second.submit(DesktopWindowsUxCommand.ResetLayout))
        assertEquals(DesktopWindowsUxSettings(), first.settings.value)
        assertEquals(DesktopWindowsUxSettings(), second.settings.value)
        assertNull(second.writeError.value)
    }

    @Test fun userTextLivesInTheStringsObject() {
        assertTrue(DesktopWindowsUxStrings.INVALID_VALUES.isNotBlank())
        assertTrue(DesktopWindowsUxStrings.UNREADABLE.isNotBlank())
        assertTrue(DesktopWindowsUxStrings.WRITE_FAILED.isNotBlank())
    }
}
