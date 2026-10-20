package com.bilipai.desktop.data

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The v0.4.6 search-only silent switch over the real Windows preferences facade. No window, GPU or network. */
class DesktopSearchHistoryRecordingTest {
    private fun root(): Path = Files.createTempDirectory("bp-search-history-recording-")

    private fun settings(root: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(root.resolve("search/plugin-settings.json"))).jsonObject["settings"]!!.jsonObject

    private fun keywords(preferences: DesktopSearchPreferences, mid: Long? = null) =
        preferences.history(mid).value.map { it.keyword }

    @Test fun `the switch defaults on and the cold facade reads the same default`() {
        val preferences = DesktopSearchPreferences(root())
        assertTrue(preferences.searchHistoryRecordingEnabled.value)
        assertTrue(preferences.isSearchHistoryRecordingEnabledSync())
        assertFalse(preferences.privacyMode.value)
    }

    @Test fun `the choice is stored under the original key beside the other search settings and survives a cold facade`(): Unit = runBlocking {
        val global = root()
        val preferences = DesktopSearchPreferences(global)
        preferences.setSuggestionsEnabled(false)
        preferences.setSearchHistoryRecordingEnabled(false)
        assertFalse(preferences.searchHistoryRecordingEnabled.value)
        assertFalse(preferences.isSearchHistoryRecordingEnabledSync())
        assertEquals("search_history_recording_enabled", DesktopSearchPreferences.SEARCH_HISTORY_RECORDING_KEY)
        assertEquals(false, settings(global)["search_history_recording_enabled"]!!.jsonPrimitive.booleanOrNull)
        // The neighbouring search setting is untouched by the new key.
        assertEquals(false, settings(global)["search_suggestions_enabled"]!!.jsonPrimitive.booleanOrNull)
        val cold = DesktopSearchPreferences(global)
        assertFalse(cold.searchHistoryRecordingEnabled.value)
        assertFalse(cold.isSearchHistoryRecordingEnabledSync())
        cold.setSearchHistoryRecordingEnabled(true)
        assertTrue(DesktopSearchPreferences(global).searchHistoryRecordingEnabled.value)
    }

    @Test fun `a backup restore that replaces the settings file is read by the next facade`() {
        val global = root()
        Files.createDirectories(global.resolve("search"))
        Files.writeString(global.resolve("search/plugin-settings.json"),
            """{"settings":{"search_history_recording_enabled":false}}""")
        val restored = DesktopSearchPreferences(global)
        assertFalse(restored.searchHistoryRecordingEnabled.value)
        assertFalse(restored.isSearchHistoryRecordingEnabledSync())
    }

    @Test fun `the silent switch and the global privacy mode each suppress the keyword write but never saved history`(): Unit = runBlocking {
        val preferences = DesktopSearchPreferences(root())
        preferences.record(null, "first")
        assertEquals(listOf("first"), keywords(preferences))
        preferences.setSearchHistoryRecordingEnabled(false)
        preferences.record(null, "silent")
        assertEquals(listOf("first"), keywords(preferences))
        preferences.setSearchHistoryRecordingEnabled(true)
        preferences.setPrivacyMode(true)
        preferences.record(null, "incognito")
        assertEquals(listOf("first"), keywords(preferences))
        preferences.setSearchHistoryRecordingEnabled(false)
        preferences.record(null, "both")
        assertEquals(listOf("first"), keywords(preferences))
        // Privacy mode never blocks reading saved history, and clearing the global switch alone is not enough.
        preferences.setPrivacyMode(false)
        preferences.record(null, "still silent")
        assertEquals(listOf("first"), keywords(preferences))
        preferences.setSearchHistoryRecordingEnabled(true)
        preferences.record(null, "second")
        assertEquals(listOf("second", "first"), keywords(preferences))
    }

    @Test fun `the silent switch and the global privacy mode are independent of each other`(): Unit = runBlocking {
        val preferences = DesktopSearchPreferences(root())
        preferences.setSearchHistoryRecordingEnabled(false)
        assertFalse(preferences.privacyMode.value)
        assertFalse(preferences.isPrivacyModeEnabledSync())
        preferences.setPrivacyMode(true)
        preferences.setSearchHistoryRecordingEnabled(true)
        assertTrue(preferences.privacyMode.value)
        assertTrue(preferences.isPrivacyModeEnabledSync())
        assertTrue(preferences.isSearchHistoryRecordingEnabledSync())
    }

    @Test fun `a facade frozen for a backup restore rejects the switch and keeps the stored value`(): Unit = runBlocking {
        val global = root()
        val preferences = DesktopSearchPreferences(global)
        preferences.setSearchHistoryRecordingEnabled(false)
        // The environment turns exactly this state into a retirement (cancellation) instead of an escaping failure.
        assertFalse(preferences.writesClosed())
        preferences.freezeWritesForRestore()
        assertTrue(preferences.writesClosed())
        assertFailsWith<IllegalStateException> { preferences.setSearchHistoryRecordingEnabled(true) }
        assertFalse(preferences.searchHistoryRecordingEnabled.value)
        assertEquals(false, settings(global)["search_history_recording_enabled"]!!.jsonPrimitive.booleanOrNull)
    }
}
