package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopDanmakuHotSettingsTest {
    private class Fixture {
        val directory = Files.createTempDirectory("bp-hot-danmaku-settings-")
        val store = DesktopPluginStore(directory)
        val current = AtomicBoolean(true)
        val admission: ((() -> Unit) -> Boolean) = { action ->
            if (current.get()) { action(); true } else false
        }
        val blocks = DesktopDanmakuBlockPreferences(store, admission)
        val preferences = DesktopOriginalDanmakuPreferences(store, blocks, admission)
    }

    @Test fun `missing hot preferences use original defaults without materializing disk`(): Unit = runBlocking {
        val f = Fixture()
        assertTrue(f.preferences.currentDanmakuHotBarEnabled())
        assertTrue(f.preferences.getDanmakuHotBarEnabled().first())
        assertFalse(Files.exists(f.directory.resolve("plugin-settings.json")))
    }

    @Test fun `the one hot switch shares actual persistence, preserves other settings and writes no expanded key`(): Unit = runBlocking {
        val f = Fixture()
        f.store.update("settings", mapOf("unrelated_video_setting" to JsonPrimitive("preserve")))
        f.preferences.setDanmakuHotBarEnabled(false)
        assertFalse(f.preferences.getDanmakuHotBarEnabled().first())
        val other = DesktopOriginalDanmakuPreferences(f.store, f.blocks, f.admission)
        assertFalse(other.currentDanmakuHotBarEnabled())
        val disk = Json.parseToJsonElement(Files.readString(f.directory.resolve("plugin-settings.json"))) as JsonObject
        val settings = disk["settings"] as JsonObject
        assertEquals(JsonPrimitive(false), settings["danmaku_hot_bar_enabled"])
        assertEquals(JsonPrimitive("preserve"), settings["unrelated_video_setting"])
        // v0.3.8 retired the expanded-mode key: the bar has one mode and nothing writes the old key any more.
        assertNull(settings["hot_danmaku_expanded_mode"])
        f.preferences.setDanmakuHotBarEnabled(true)
        assertTrue(f.preferences.currentDanmakuHotBarEnabled())
    }

    @Test fun `retired actual preference admission cannot alter the flow or disk`(): Unit = runBlocking {
        val f = Fixture()
        f.preferences.setDanmakuHotBarEnabled(false)
        val before = Files.readString(f.directory.resolve("plugin-settings.json"))
        f.current.set(false)
        assertFailsWith<CancellationException> { f.preferences.setDanmakuHotBarEnabled(true) }
        assertEquals(before, Files.readString(f.directory.resolve("plugin-settings.json")))
        assertFalse(f.preferences.getDanmakuHotBarEnabled().first())
    }

    @Test fun `cancelled original caller cannot reach final hot setting publication`(): Unit = runBlocking {
        val f = Fixture()
        val job = launch {
            cancel("Fixture caller retired before original setter")
            f.preferences.setDanmakuHotBarEnabled(false)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(f.preferences.currentDanmakuHotBarEnabled())
        assertFalse(Files.exists(f.directory.resolve("plugin-settings.json")))
    }
}
