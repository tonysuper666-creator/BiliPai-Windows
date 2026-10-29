package com.bilipai.desktop.ui

import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.android.purebilibili.core.store.SettingsSearchHistoryStore
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The page's upstream settings_prefs DataStore serializes edits like DataStore: a transform always runs on the values
 * it commits to, outside a settings operation (CAS rerun) and inside one (replayed on the actual snapshot). */
class DesktopOriginalSettingsAndroidContextTest {
    private val directory = Files.createTempDirectory("bp-original-settings-android-context-")
    private val plugin = DesktopPluginContext(DesktopPluginStore(directory))
    private val current = AtomicBoolean(true)
    private val context = DesktopOriginalPlayerSettingsContext(plugin, current::get, commitIfCurrent = { action ->
        if (current.get()) { action(); true } else false
    })
    private val counter = intPreferencesKey("fixture_counter")
    private val settings = (context.androidContext as DesktopPreferencesDataStoreOwner).desktopPreferencesDataStore("settings_prefs")
    private fun stored() = DesktopPluginStore(directory).preferences("settings")["fixture_counter"]

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
        assertEquals(JsonPrimitive(11), stored())
    }

    @Test fun `an unchanged namespace commits the first transform`() = runBlocking {
        var runs = 0
        settings.edit { prefs -> runs++; prefs[counter] = 5 }
        assertEquals(1, runs)
        assertEquals(JsonPrimitive(5), stored())
    }

    @Test fun `two concurrent increments both land when one transform suspends`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            settings.edit { prefs ->
                val old = prefs[counter] ?: 0
                entered.complete(Unit)
                release.await()
                prefs[counter] = old + 1
            }
        }
        entered.await()
        val second = async(Dispatchers.Default) { settings.edit { prefs -> prefs[counter] = (prefs[counter] ?: 0) + 1 } }
        withTimeoutOrNull(300) { second.await() }
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(JsonPrimitive(2), stored())
    }

    @Test fun `an operation replays the transform on a value another owner wrote while it was staging`() = runBlocking {
        DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
            settings.edit { it[counter] = (it[counter] ?: 0) + 1 }
            plugin.store.update("settings", mapOf("fixture_counter" to JsonPrimitive(10)))
        }
        assertEquals(JsonPrimitive(11), stored())
    }

    @Test fun `upstream search history keeps the query another owner recorded during the operation`() = runBlocking {
        DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
            SettingsSearchHistoryStore.record(context.androidContext, "first")
            SettingsSearchHistoryStore.record(plugin.asAndroidContext(), "second")
        }
        assertEquals(setOf("first", "second"), SettingsSearchHistoryStore.observe(context.androidContext).first().toSet())
    }

    @Test fun `a transform that suspends inside an operation fails and commits nothing`() = runBlocking {
        assertFailsWith<UnsupportedDesktopCapability> {
            DesktopOriginalPlaybackPreferenceOperation.run(context, current::get) {
                settings.edit { prefs -> CompletableDeferred<Unit>().await(); prefs[counter] = 1 }
            }
        }
        assertNull(stored())
    }
}
