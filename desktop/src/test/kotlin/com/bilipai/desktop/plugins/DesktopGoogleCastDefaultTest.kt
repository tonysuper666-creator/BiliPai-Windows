package com.bilipai.desktop.plugins

import java.nio.file.Files
import kotlin.test.*

class DesktopGoogleCastDefaultTest {
    @Test fun `windows cast starts disabled and retains the actual saved user choice`() {
        val directory = Files.createTempDirectory("cast-default")
        val key = booleanPreferencesKey("plugin_enabled_google_cast")
        val store = DesktopPluginStore(directory)
        initializeDesktopGoogleCastDefault(store)
        assertEquals(false, store.snapshot("plugin_prefs").value[key])
        store.update("plugin_prefs", mapOf(key.name to kotlinx.serialization.json.JsonPrimitive(true)))
        val reopened = DesktopPluginStore(directory)
        initializeDesktopGoogleCastDefault(reopened)
        assertEquals(true, reopened.snapshot("plugin_prefs").value[key])
    }
}
