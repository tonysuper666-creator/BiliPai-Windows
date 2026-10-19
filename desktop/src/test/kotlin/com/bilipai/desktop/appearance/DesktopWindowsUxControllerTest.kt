package com.bilipai.desktop.appearance

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The waits are for real file replacements (temporary file, fsync, atomic move) on a possibly loaded disk.
private const val WINDOWS_UX_WAIT_MS = 30_000L

/** The persisted windows_ux flags owner over a real DesktopPluginStore in a temporary folder. No window, no GPU, no network. */
class DesktopWindowsUxControllerTest {
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun awaitStored(store: DesktopPluginStore, condition: (JsonObject) -> Boolean) {
        withTimeout(WINDOWS_UX_WAIT_MS) { while (!condition(store.preferences(WINDOWS_UX_NAMESPACE))) delay(10) }
    }

    @Test fun aFreshStoreGivesDefaultsAndWritesNothing(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-ux-fresh-")
        val store = DesktopPluginStore(root)
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            assertEquals(DesktopWindowsUxSettings(), ux.settings.value)
            assertNull(ux.writeError.value)
            delay(100)
            assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun aChangeShowsAtOnceWritesOnlyItsOwnKeyAndSurvivesANewController(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-ux-persist-")
        val store = DesktopPluginStore(root)
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            assertTrue(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
            assertEquals(DesktopWindowsUxRailMode.COLLAPSED, ux.settings.value.railMode) // before the write finishes
            awaitStored(store) { it[UX_KEY_RAIL_MODE] != null }
            assertEquals(setOf(UX_KEY_RAIL_MODE), store.preferences(WINDOWS_UX_NAMESPACE).keys)
            assertEquals("COLLAPSED", store.preferences(WINDOWS_UX_NAMESPACE)[UX_KEY_RAIL_MODE]?.jsonPrimitive?.content)
            assertNull(ux.writeError.value)
            val reopened = DesktopWindowsUxController(DesktopPluginStore(root), scope)
            try {
                assertEquals(DesktopWindowsUxSettings(railMode = DesktopWindowsUxRailMode.COLLAPSED), reopened.settings.value)
            } finally { reopened.close() }
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun anExplicitChoiceEqualToTheDefaultIsStoredExplicitly(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-explicit-"))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            assertTrue(ux.submit(DesktopWindowsUxCommand.SetPlayerLayout(DesktopWindowsUxPlayerLayout.LEGACY)))
            awaitStored(store) { it[UX_KEY_PLAYER_LAYOUT] != null }
            assertEquals("LEGACY", store.preferences(WINDOWS_UX_NAMESPACE)[UX_KEY_PLAYER_LAYOUT]?.jsonPrimitive?.content)
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun theStoreNeverWritesTheWindowZoomNamespaceOrAnyOtherNamespace(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-ux-isolation-")
        val store = DesktopPluginStore(root)
        store.update(WINDOWS_DISPLAY_NAMESPACE, mapOf("scale_percent" to JsonPrimitive(150)))
        store.update("settings", mapOf("app_ui_scale" to JsonPrimitive(3)))
        val zoomBefore = store.preferences(WINDOWS_DISPLAY_NAMESPACE)
        val settingsBefore = store.preferences("settings")
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            ux.submit(DesktopWindowsUxCommand.SetHomeLayout(DesktopWindowsUxHomeLayout.CLASSIC))
            ux.submit(DesktopWindowsUxCommand.SetWindowBounds(DesktopWindowsUxWindowBounds(10, 20, 1300, 800, true)))
            ux.submit(DesktopWindowsUxCommand.ResetLayout)
            awaitStored(store) { it[UX_KEY_WINDOW_X] != null && it[UX_KEY_HOME_LAYOUT] == null }
            assertEquals(zoomBefore, store.preferences(WINDOWS_DISPLAY_NAMESPACE))
            assertEquals(settingsBefore, store.preferences("settings"))
            assertEquals(150, store.preferences(WINDOWS_DISPLAY_NAMESPACE)["scale_percent"]?.jsonPrimitive?.intOrNull)
            val fileText = Files.readString(root.resolve("plugin-settings.json"))
            assertTrue(fileText.contains("\"$WINDOWS_UX_NAMESPACE\""))
            assertTrue(fileText.contains("\"$WINDOWS_DISPLAY_NAMESPACE\""))
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun resetRemovesTheLayoutKeysFromTheFileAndKeepsTheBounds(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-reset-"))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.EXPANDED))
            ux.submit(DesktopWindowsUxCommand.SetDetailsPanelWidth(480))
            ux.submit(DesktopWindowsUxCommand.SetWindowBounds(DesktopWindowsUxWindowBounds(5, 6, 1200, 850, false)))
            awaitStored(store) { it.keys.containsAll(listOf(UX_KEY_RAIL_MODE, UX_KEY_DETAILS_PANEL_WIDTH, UX_KEY_WINDOW_X)) }
            ux.submit(DesktopWindowsUxCommand.ResetLayout)
            awaitStored(store) { it[UX_KEY_RAIL_MODE] == null && it[UX_KEY_DETAILS_PANEL_WIDTH] == null }
            assertEquals(UX_WINDOW_KEYS.toSet(), store.preferences(WINDOWS_UX_NAMESPACE).keys)
            assertEquals(DesktopWindowsUxSettings(windowBounds = DesktopWindowsUxWindowBounds(5, 6, 1200, 850, false)),
                ux.settings.value)
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun manyRapidSubmitsEndWithTheLastValueInTheFileAndInMemory(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-burst-"))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            for (width in 320..339) assertTrue(ux.submit(DesktopWindowsUxCommand.SetDetailsPanelWidth(width)))
            assertEquals(339, ux.settings.value.detailsPanelWidthDp)
            awaitStored(store) { it[UX_KEY_DETAILS_PANEL_WIDTH]?.jsonPrimitive?.intOrNull == 339 }
            delay(100)
            assertEquals(339, ux.settings.value.detailsPanelWidthDp)
            assertNull(ux.writeError.value)
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun aFailedWriteKeepsTheSessionValueAndReportsTheError(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-ux-failure-")
        val store = DesktopPluginStore(root)
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            store.freezeWrites() // every later write of this file generation is refused
            assertTrue(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.EXPANDED)))
            assertEquals(DesktopWindowsUxRailMode.EXPANDED, ux.settings.value.railMode)
            withTimeout(WINDOWS_UX_WAIT_MS) { ux.writeError.first { it != null } }
            assertEquals(DesktopWindowsUxStrings.WRITE_FAILED, ux.writeError.value)
            delay(100)
            assertEquals(DesktopWindowsUxRailMode.EXPANDED, ux.settings.value.railMode) // the in-memory mode is kept
            assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun storedValuesThatAreNotUnderstoodAreReportedAndNotRewritten(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-invalid-"))
        store.update(WINDOWS_UX_NAMESPACE, mapOf(UX_KEY_RAIL_MODE to JsonPrimitive("bogus"),
            UX_KEY_DETAILS_PANEL_WIDTH to JsonPrimitive(100), UX_KEY_WHEEL_ACTION to JsonPrimitive("SEEK")))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            val settings = ux.settings.value
            assertEquals(DesktopWindowsUxRailMode.AUTO, settings.railMode)
            assertNull(settings.detailsPanelWidthDp)
            assertEquals(DesktopWindowsUxWheelAction.SEEK, settings.wheelAction)
            assertEquals(setOf(UX_KEY_RAIL_MODE, UX_KEY_DETAILS_PANEL_WIDTH), settings.invalidKeys.toSet())
            assertEquals(DesktopWindowsUxStrings.INVALID_VALUES, settings.error)
            assertEquals("bogus", store.preferences(WINDOWS_UX_NAMESPACE)[UX_KEY_RAIL_MODE]?.jsonPrimitive?.content)
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun aNamespaceThatIsNotAnObjectIsReportedAndNeverOverwritten(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-ux-corrupt-")
        val original = "{\"$WINDOWS_UX_NAMESPACE\": 5}"
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val store = DesktopPluginStore(root)
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            assertEquals(DesktopWindowsUxSettings().copy(error = DesktopWindowsUxStrings.UNREADABLE), ux.settings.value)
            assertTrue(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
            withTimeout(WINDOWS_UX_WAIT_MS) { ux.writeError.first { it != null } }
            assertEquals(DesktopWindowsUxStrings.WRITE_FAILED, ux.writeError.value)
            assertEquals(original, Files.readString(root.resolve("plugin-settings.json")))
            assertEquals(DesktopWindowsUxRailMode.COLLAPSED, ux.settings.value.railMode)
            delay(100)
            assertEquals(DesktopWindowsUxRailMode.COLLAPSED, ux.settings.value.railMode)
            assertEquals(DesktopWindowsUxStrings.UNREADABLE, ux.settings.value.error) // no command repairs the namespace
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun twoOwnersOfTheSameFileAdoptEachOthersChanges(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-windows-ux-shared-")
        val store = DesktopPluginStore(root)
        val scope = newScope()
        val first = DesktopWindowsUxController(store, scope)
        val second = DesktopWindowsUxController(store, scope)
        try {
            assertTrue(first.submit(DesktopWindowsUxCommand.SetHomeLayout(DesktopWindowsUxHomeLayout.CLASSIC)))
            val seen = withTimeout(WINDOWS_UX_WAIT_MS) { second.settings.first { it.homeLayout == DesktopWindowsUxHomeLayout.CLASSIC } }
            assertNotNull(seen)
            assertEquals(DesktopWindowsUxHomeLayout.CLASSIC, first.settings.value.homeLayout)
        } finally { first.close(); second.close(); scope.cancel() }
    }

    @Test fun aChangeKeepsTheReportedProblemsOfTheKeysItDoesNotEdit(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-problems-"))
        store.update(WINDOWS_UX_NAMESPACE, mapOf(UX_KEY_RAIL_MODE to JsonPrimitive("bogus")))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            assertEquals(listOf(UX_KEY_RAIL_MODE), ux.settings.value.invalidKeys)
            assertTrue(ux.submit(DesktopWindowsUxCommand.SetClickAction(DesktopWindowsUxClickAction.NONE)))
            // At once, and again after the write settled: the file still holds the bad value, so the message stays.
            assertEquals(listOf(UX_KEY_RAIL_MODE), ux.settings.value.invalidKeys)
            assertEquals(DesktopWindowsUxStrings.INVALID_VALUES, ux.settings.value.error)
            awaitStored(store) { it[UX_KEY_CLICK_ACTION] != null }
            delay(100)
            assertEquals(listOf(UX_KEY_RAIL_MODE), ux.settings.value.invalidKeys)
            assertEquals(DesktopWindowsUxStrings.INVALID_VALUES, ux.settings.value.error)
            assertEquals(DesktopWindowsUxClickAction.NONE, ux.settings.value.clickAction)
            assertEquals("bogus", store.preferences(WINDOWS_UX_NAMESPACE)[UX_KEY_RAIL_MODE]?.jsonPrimitive?.content)
            // Rewriting the bad key repairs it, in memory at once and in the file after the write.
            assertTrue(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.EXPANDED)))
            assertTrue(ux.settings.value.invalidKeys.isEmpty())
            assertNull(ux.settings.value.error)
            awaitStored(store) { it[UX_KEY_RAIL_MODE]?.jsonPrimitive?.content == "EXPANDED" }
            delay(100)
            assertTrue(ux.settings.value.invalidKeys.isEmpty())
            assertNull(ux.settings.value.error)
            assertEquals(DesktopWindowsUxRailMode.EXPANDED, ux.settings.value.railMode)
        } finally { ux.close(); scope.cancel() }
    }

    @Test fun closeLetsTheCommandsThatAreAlreadyQueuedFinishWriting(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-close-drain-"))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            for (width in 320..339) assertTrue(ux.submit(DesktopWindowsUxCommand.SetDetailsPanelWidth(width)))
            ux.close()
            assertFalse(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
            awaitStored(store) { it[UX_KEY_DETAILS_PANEL_WIDTH]?.jsonPrimitive?.intOrNull == 339 }
            assertNull(store.preferences(WINDOWS_UX_NAMESPACE)[UX_KEY_RAIL_MODE]) // the refused command wrote nothing
        } finally { scope.cancel() }
    }

    @Test fun closeAndJoinReturnsOnlyAfterTheLastQueuedWrite(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-close-join-"))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            for (width in 320..339) assertTrue(ux.submit(DesktopWindowsUxCommand.SetDetailsPanelWidth(width)))
            withTimeout(WINDOWS_UX_WAIT_MS) { ux.closeAndJoin() }
            // No waiting loop: the last write is already in the store when the call returns.
            assertEquals(339, store.preferences(WINDOWS_UX_NAMESPACE)[UX_KEY_DETAILS_PANEL_WIDTH]?.jsonPrimitive?.intOrNull)
            assertNull(ux.writeError.value)
            assertFalse(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
            withTimeout(WINDOWS_UX_WAIT_MS) { ux.closeAndJoin() } // a second call returns at once
        } finally { scope.cancel() }
    }

    @Test fun closeIsIdempotentAndLaterSubmitsAreRefused(): Unit = runBlocking {
        val store = DesktopPluginStore(Files.createTempDirectory("bilipai-windows-ux-close-"))
        val scope = newScope()
        val ux = DesktopWindowsUxController(store, scope)
        try {
            ux.close()
            ux.close()
            assertFalse(ux.submit(DesktopWindowsUxCommand.SetRailMode(DesktopWindowsUxRailMode.COLLAPSED)))
            assertEquals(DesktopWindowsUxSettings(), ux.settings.value)
        } finally { scope.cancel() }
    }
}
