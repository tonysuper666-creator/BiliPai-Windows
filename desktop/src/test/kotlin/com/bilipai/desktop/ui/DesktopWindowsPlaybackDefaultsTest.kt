package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings
import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.bilipai.desktop.player.PlayerPreferences
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
    @Test fun `omitting the legacy snapshot never seeds playback preferences`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-windows-defaults-no-seed-")
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        ensureDesktopWindowsPlaybackDefaults(context)
        assertEquals(setOf("auto_highest_quality"), store.preferences("settings").keys)
        assertEquals(setOf("highest_quality_v1"), store.preferences("windows_desktop_defaults").keys)
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
    }
    @Test fun `legacy snapshot seeds with the highest default in one publication and only once`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-windows-defaults-seed-")
        val store = DesktopPluginStore(root)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        val legacy = PlayerPreferences(videoCodecPreference = "av01", subtitleAutoPreference = SubtitleAutoPreference.ON,
            defaultSpeed = 1.5, rememberLastSpeed = false)
        ensureDesktopWindowsPlaybackDefaults(context, legacy)
        val settings = store.preferences("settings")
        assertEquals("av01", settings["video_codec_preference"]!!.jsonPrimitive.content)
        assertEquals(SubtitleAutoPreference.ON.ordinal, settings["subtitle_auto_preference"]!!.jsonPrimitive.int)
        assertTrue(settings["auto_highest_quality"]!!.jsonPrimitive.boolean)
        val marks = store.preferences("windows_desktop_defaults")
        assertTrue(marks["highest_quality_v1"]!!.jsonPrimitive.boolean)
        assertTrue(marks[DesktopOriginalPlaybackPreferenceProjection.LEGACY_SEED_MARKER]!!.jsonPrimitive.boolean)
        val published = Files.readAllBytes(root.resolve("plugin-settings.json"))
        ensureDesktopWindowsPlaybackDefaults(context, PlayerPreferences(videoCodecPreference = "avc1"))
        assertContentEquals(published, Files.readAllBytes(root.resolve("plugin-settings.json")))
    }
    @Test fun `retired final Root permit cannot partially seed the legacy snapshot`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-private-windows-defaults-seed-retired-")
        val store = DesktopPluginStore(root)
        var current = true
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { current }, { current = false; false })
        assertFailsWith<CancellationException> {
            ensureDesktopWindowsPlaybackDefaults(context, PlayerPreferences(videoCodecPreference = "av01"))
        }
        listOf("settings", "quality_settings", "playback_speed_cache", "windows_desktop_defaults").forEach {
            assertTrue(store.preferences(it).isEmpty(), it)
        }
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }
}
