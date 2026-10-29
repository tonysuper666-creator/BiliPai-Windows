package com.bilipai.desktop.plugins

import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A plugin Context created with an admission applies it to both stores upstream code writes: SharedPreferences and
 * the settings_prefs DataStore. */
class DesktopAndroidContextAdmissionTest {
    private val store = DesktopPluginStore(Files.createTempDirectory("bp-android-context-admission-"))
    private val plugin = DesktopPluginContext(store)
    private val key = intPreferencesKey("fixture_counter")
    private fun settingsOf(context: android.content.Context) =
        (context as DesktopPreferencesDataStoreOwner).desktopPreferencesDataStore("settings_prefs")

    @Test fun `a retired admission refuses settings_prefs DataStore writes`() = runBlocking {
        val retired = plugin.asAndroidContext({ throw CancellationException("retired") }, { throw CancellationException("retired") })
        assertFailsWith<CancellationException> { settingsOf(retired).edit { it[key] = 1 } }
        assertNull(store.preferences("settings")[key.name])
    }

    @Test fun `a permit that cannot be minted refuses the write after the request check passed`() = runBlocking {
        val noPermit = plugin.asAndroidContext({}, { throw CancellationException("retired") })
        assertFailsWith<CancellationException> { settingsOf(noPermit).edit { it[key] = 1 } }
        assertNull(store.preferences("settings")[key.name])
    }

    @Test fun `an admitted context writes through its permit`() = runBlocking {
        var permits = 0
        val admitted = plugin.asAndroidContext({}, { permits++; DesktopPluginStore.OriginalPreferenceWritePermit(store) })
        settingsOf(admitted).edit { it[key] = 1 }
        assertEquals(1, permits)
        assertEquals(JsonPrimitive(1), store.preferences("settings")[key.name])
    }
}
