package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopWindowsPlaybackDefaultsTest {
    @Test fun `highest default reaches canonical and the actual original load getter together`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-windows-defaults-")
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        store.update("settings", mapOf("auto_highest_quality" to JsonPrimitive(false), "unrelated" to JsonPrimitive(37)))
        store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(false)))
        ensureDesktopWindowsPlaybackDefaults(context)
        assertTrue(DesktopOriginalPlaybackSettingsPreferences.getAutoHighestQuality(context).first())
        assertTrue(DesktopOriginalVideoOwnerSettings.getAutoHighestQualitySync(context))
        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertTrue(disk["settings"]!!.jsonObject["auto_highest_quality"]!!.jsonPrimitive.boolean)
        assertTrue(disk["quality_settings"]!!.jsonObject["auto_highest_quality"]!!.jsonPrimitive.boolean)
        assertTrue(disk["windows_desktop_defaults"]!!.jsonObject["highest_quality_v1"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(37), store.preferences("settings")["unrelated"])
        DesktopOriginalPlaybackPreferenceOperation.run(context, { true }) {
            DesktopOriginalPlaybackSettingsPreferences.setAutoHighestQuality(context, false)
        }
        val changed = Files.readAllBytes(root.resolve("plugin-settings.json"))
        ensureDesktopWindowsPlaybackDefaults(context)
        assertFalse(DesktopOriginalVideoOwnerSettings.getAutoHighestQualitySync(context))
        assertContentEquals(changed, Files.readAllBytes(root.resolve("plugin-settings.json")))
    }
    @Test fun `retired final Root permit cannot partially initialize highest default`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-windows-defaults-retired-")
        val store = DesktopPluginStore(root)
        var current = true
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { current }, { current = false; false })
        assertFailsWith<CancellationException> { ensureDesktopWindowsPlaybackDefaults(context) }
        assertTrue(store.preferences("settings").isEmpty())
        assertTrue(store.preferences("quality_settings").isEmpty())
        assertTrue(store.preferences("windows_desktop_defaults").isEmpty())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }
}
