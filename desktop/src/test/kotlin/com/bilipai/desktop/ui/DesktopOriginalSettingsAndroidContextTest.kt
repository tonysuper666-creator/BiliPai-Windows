package com.bilipai.desktop.ui

import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

/** The page's upstream settings_prefs DataStore serializes edits like DataStore: a moved namespace reruns the transform. */
class DesktopOriginalSettingsAndroidContextTest {
    private val directory = Files.createTempDirectory("bp-original-settings-android-context-")
    private val plugin = DesktopPluginContext(DesktopPluginStore(directory))
    private val current = AtomicBoolean(true)
    private val context = DesktopOriginalPlayerSettingsContext(plugin, current::get, commitIfCurrent = { action ->
        if (current.get()) { action(); true } else false
    })
    private val counter = intPreferencesKey("fixture_counter")
    private val settings = (context.androidContext as DesktopPreferencesDataStoreOwner).desktopPreferencesDataStore("settings_prefs")

    @Test fun `a write that lands while the transform runs is read again instead of overwritten`() = runBlocking {
        plugin.store.update("settings", mapOf("fixture_counter" to JsonPrimitive(1)))
        var runs = 0
        settings.edit { prefs ->
            runs++
            // Another writer moves the namespace after this transform has read it.
            if (runs == 1) plugin.store.update("settings", mapOf("fixture_counter" to JsonPrimitive(10)))
            prefs[counter] = (prefs[counter] ?: 0) + 1
        }
        assertEquals(2, runs)
        assertEquals(JsonPrimitive(11), DesktopPluginStore(directory).preferences("settings")["fixture_counter"])
    }

    @Test fun `an unchanged namespace commits the first transform`() = runBlocking {
        var runs = 0
        settings.edit { prefs -> runs++; prefs[counter] = 5 }
        assertEquals(1, runs)
        assertEquals(JsonPrimitive(5), DesktopPluginStore(directory).preferences("settings")["fixture_counter"])
    }
}
