package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.plugin.BiliPaiFeedFilterConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopPluginStoreSharingTest {
    @TempDir lateinit var root: Path

    @Test fun `an older facade cannot overwrite privacy or unknown namespaces`() {
        Files.writeString(root.resolve("plugin-settings.json"), """{"future":{"nested":{"keep":7}}}""")
        val first = DesktopPluginContext(DesktopPluginStore(root))
        val second = DesktopPluginContext(DesktopPluginStore(root))
        first.getSharedPreferences("privacy_mode", 0).edit().putBoolean("enabled", true).apply()
        second.getSharedPreferences("settings", 0).edit().putBoolean("search_suggestions_enabled", false).apply()
        assertTrue(second.getSharedPreferences("privacy_mode", 0).getBoolean("enabled", false))
        assertFalse(first.getSharedPreferences("settings", 0).getBoolean("search_suggestions_enabled", true))
        val persisted = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(7, persisted["future"]!!.jsonObject["nested"]!!.jsonObject["keep"]!!.jsonPrimitive.int)
        assertTrue(persisted["privacy_mode"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `committed changes update every existing facade flow`(): Unit = runBlocking {
        val first = DesktopPluginStore(root)
        val second = DesktopPluginStore(root)
        val firstSnapshot = first.snapshot("plugin_prefs")
        val secondSnapshot = second.snapshot("plugin_prefs")
        val enabled = booleanPreferencesKey("plugin_enabled_bilipai_feed_filter")
        second.setFeedFilterEnabled(true)
        val config = BiliPaiFeedFilterConfig(minDurationForRcmd = 31, whitelistMids = mapOf(11L to "作者"))
        first.setFeedFilterConfig(config)
        assertTrue(firstSnapshot.value[enabled] == true && secondSnapshot.value[enabled] == true)
        assertTrue(first.feedFilterEnabled.value && second.feedFilterEnabled.value)
        assertEquals(config, first.feedFilterConfig.value)
        assertEquals(config, second.feedFilterConfig.value)
    }

    @Test fun `concurrent facade edits merge keys under the same file lock`() {
        val facades = listOf(DesktopPluginStore(root), DesktopPluginStore(root.resolve(".").normalize()))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pending = facades.mapIndexed { index, store -> pool.submit {
                start.await()
                repeat(30) { key -> store.update("settings", mapOf("$index-$key" to JsonPrimitive(key))) }
            } }
            start.countDown()
            pending.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
        facades.forEach { facade -> assertEquals(60, facade.preferences("settings").size) }
        val persisted = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        assertEquals(60, persisted["settings"]!!.jsonObject.size)
    }

    @Test fun `restoration freezes all old facades and a new generation cannot reactivate them`() {
        val first = DesktopPluginStore(root)
        val second = DesktopPluginStore(root)
        first.update("settings", mapOf("value" to JsonPrimitive("before")))
        first.freezeWrites()
        Files.writeString(root.resolve("plugin-settings.json"), """{"settings":{"value":"restored"}}""")
        val fresh = DesktopPluginStore(root)
        assertEquals("restored", fresh.preferences("settings")["value"]!!.jsonPrimitive.content)
        fresh.update("settings", mapOf("new" to JsonPrimitive(true)))
        listOf(first, second).forEach { stale ->
            assertFailsWith<IllegalStateException> { stale.update("settings", mapOf("value" to JsonPrimitive("stale"))) }
        }
        assertEquals("restored", fresh.preferences("settings")["value"]!!.jsonPrimitive.content)
        assertTrue(fresh.preferences("settings")["new"]!!.jsonPrimitive.boolean)
    }

    @Test fun `failed atomic replacement publishes nothing to any facade`(): Unit = runBlocking {
        val first = DesktopPluginStore(root)
        val second = DesktopPluginStore(root)
        first.setFeedFilterEnabled(true)
        val snapshots = listOf(first.snapshot("plugin_prefs"), second.snapshot("plugin_prefs"))
        val before = snapshots.map { it.value }
        val target = root.resolve("plugin-settings.json")
        Files.delete(target)
        Files.createDirectory(target)
        Files.writeString(target.resolve("blocks-replacement"), "fixture")
        assertFailsWith<Exception> { second.setFeedFilterEnabled(false) }
        assertEquals(before, snapshots.map { it.value })
        assertTrue(first.feedFilterEnabled.value && second.feedFilterEnabled.value)
        Files.list(root).use { files -> assertTrue(files.noneMatch { it.fileName.toString().startsWith("plugin-settings-") }) }
    }
}
