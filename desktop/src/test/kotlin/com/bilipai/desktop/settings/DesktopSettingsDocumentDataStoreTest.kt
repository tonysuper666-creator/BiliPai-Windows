package com.bilipai.desktop.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Upstream stores read and write Root's settings document through the bridge, with the upstream key types. */
class DesktopSettingsDocumentDataStoreTest {
    private val root = Files.createTempDirectory("settings-bridge-")
    private val store = DesktopPluginStore(root)
    private var admitted = true
    private val bridge = DesktopSettingsDocumentDataStore(store, "settings",
        checkRequest = { if (!admitted) throw kotlinx.coroutines.CancellationException("page retired") },
        acquirePermit = { check -> check(); DesktopPluginStore.OriginalPreferenceWritePermit(store) })

    @Test fun `values written by the desktop settings UI read back with the upstream key types`() = runBlocking {
        // The desktop UI writes through the same document; JSON 1 / 1.0 / 1700000000000 carry no Kotlin type.
        store.update("settings", mapOf(
            "back_to_top_button_enabled" to JsonPrimitive(false),
            "back_to_top_button_offset_x_dp" to JsonPrimitive(12),
            "danmaku_portrait_opacity" to JsonPrimitive(1),
            "danmaku_landscape_font_weight" to JsonPrimitive(5),
            "desktop_only_window" to buildJsonObject { put("x", 1) },
        ))
        val prefs = bridge.data.first()
        assertEquals(false, prefs[booleanPreferencesKey("back_to_top_button_enabled")])
        assertEquals(12f, prefs[floatPreferencesKey("back_to_top_button_offset_x_dp")])
        assertEquals(1f, prefs[floatPreferencesKey("danmaku_portrait_opacity")])
        assertEquals(5, prefs[intPreferencesKey("danmaku_landscape_font_weight")])
        assertNull(prefs.asMap().keys.firstOrNull { it.name == "desktop_only_window" })
    }

    @Test fun `an upstream edit commits only the changed keys and keeps values the bridge cannot represent`() = runBlocking {
        val window = buildJsonObject { put("x", 1) }
        store.update("settings", mapOf("desktop_only_window" to window, "danmaku_enabled" to JsonPrimitive(true)))
        bridge.edit { prefs ->
            prefs[floatPreferencesKey("back_to_top_button_offset_y_dp")] = 3.5f
            prefs[stringSetPreferencesKey("fixture_tags")] = setOf("a", "b")
            prefs[longPreferencesKey("fixture_timestamp")] = 1_700_000_000_000L
            prefs[stringPreferencesKey("fixture_label")] = "x"
            prefs.remove(booleanPreferencesKey("danmaku_enabled"))
        }
        val json = store.preferences("settings")
        assertEquals(window, json["desktop_only_window"])
        assertEquals(JsonPrimitive(3.5f), json["back_to_top_button_offset_y_dp"])
        assertNull(json["danmaku_enabled"])
        val reread = DesktopSettingsDocumentDataStore(DesktopPluginStore(root), "settings", {}, { DesktopPluginStore.OriginalPreferenceWritePermit(store) }).data.first()
        assertEquals(setOf("a", "b"), reread[stringSetPreferencesKey("fixture_tags")])
        assertEquals(1_700_000_000_000L, reread[longPreferencesKey("fixture_timestamp")])
        assertEquals("x", reread[stringPreferencesKey("fixture_label")])
    }

    @Test fun `a retired page cannot write through the bridge`() = runBlocking {
        admitted = false
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            bridge.edit { it[booleanPreferencesKey("back_to_top_button_enabled")] = true }
        }
        assertEquals(JsonObject(emptyMap()), store.preferences("settings"))
    }

    @Test fun `every numeric upstream key has one type in the generated table`() {
        assertEquals("float", DesktopSettingsDocumentDataStore.numericType("back_to_top_button_offset_x_dp"))
        assertEquals("float", DesktopSettingsDocumentDataStore.numericType("danmaku_portrait_speed"))
        assertEquals("int", DesktopSettingsDocumentDataStore.numericType("danmaku_portrait_display_area_mode"))
    }
}
