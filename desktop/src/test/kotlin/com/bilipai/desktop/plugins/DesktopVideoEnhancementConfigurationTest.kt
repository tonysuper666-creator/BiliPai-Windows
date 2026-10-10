package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.bilipai.desktop.player.DesktopNvidiaVideoHdrMode
import com.bilipai.desktop.player.DesktopNvidiaVideoPreferences
import com.bilipai.desktop.player.DesktopNvidiaVideoQuality
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopVideoEnhancementConfigurationTest {
    @Test fun `quality and HDR writes merge in queue order and drain on retirement`(): Unit = runBlocking {
        val accepting = AtomicBoolean(true)
        val config = configuration(accepting = accepting::get)
        val requests = listOf(config.setQuality(DesktopNvidiaVideoQuality.STANDARD),
            config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF), config.setAutomaticEnabled(false),
            config.setQuality(DesktopNvidiaVideoQuality.HIGH))
        accepting.set(false)
        assertFailsWith<IllegalStateException> { config.setQuality(DesktopNvidiaVideoQuality.HIGHEST) }
        withTimeout(5000) { config.flushAndClose(); requests.forEach { it.await() } }
        assertEquals(DesktopNvidiaVideoPreferences(false, DesktopNvidiaVideoQuality.HIGH, DesktopNvidiaVideoHdrMode.OFF),
            config.preferences.value)
        val namespace = document()["windows_video_enhancement"]!!.jsonObject
        assertEquals(3, namespace["quality_level"]!!.jsonPrimitive.int)
        assertEquals("off", namespace["hdr_mode"]!!.jsonPrimitive.content)
        val coldRoot = Files.createDirectory(root.resolve("quality-cold-store"))
        Files.copy(root.resolve("plugin-settings.json"), coldRoot.resolve("plugin-settings.json"))
        val cold = configuration(DesktopPluginStore(coldRoot))
        withTimeout(5000) { cold.flushAndClose() }
        assertEquals(config.preferences.value, cold.preferences.value)
    }

    @Test fun `existing migration keeps off and defaults missing quality and HDR without rewriting disk`(): Unit = runBlocking {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":false},"unrelated":{"keep":7}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        withTimeout(5000) { config.flushAndClose() }
        assertEquals(DesktopNvidiaVideoPreferences(false, DesktopNvidiaVideoQuality.HIGHEST, DesktopNvidiaVideoHdrMode.OFF),
            config.preferences.value)
        assertEquals(Json.parseToJsonElement(original), document())
    }

    @Test fun `invalid quality or HDR stays disabled and is never overwritten`(): Unit = runBlocking {
        for ((index, field) in listOf("\"quality_level\":\"4\"", "\"quality_level\":0", "\"quality_level\":5",
            "\"hdr_mode\":true", "\"hdr_mode\":\"unknown\"").withIndex()) {
            val path = Files.createDirectory(root.resolve("invalid-$index"))
            val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":true,$field}}"""
            Files.writeString(path.resolve("plugin-settings.json"), original)
            val config = configuration(DesktopPluginStore(path))
            assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setHdrMode(DesktopNvidiaVideoHdrMode.OFF).await() } }
            assertFalse(config.preferences.value.enabled)
            assertNotNull(config.error.value)
            assertEquals(Json.parseToJsonElement(original), document(path))
            withTimeout(5000) { config.flushAndClose() }
        }
    }

    @Test fun `frozen quality write does not optimistically change the atomic preferences`(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val config = configuration(store)
        withTimeout(5000) { config.setQuality(DesktopNvidiaVideoQuality.HIGHEST).await() }
        val before = config.preferences.value
        val saved = document()
        store.freezeWrites()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setQuality(DesktopNvidiaVideoQuality.STANDARD).await() } }
        assertEquals(before, config.preferences.value)
        assertEquals(saved, document())
        assertNotNull(config.error.value)
        withTimeout(5000) { config.flushAndClose() }
    }

    @TempDir lateinit var root: Path
    private fun configuration(store: DesktopPluginStore = DesktopPluginStore(root),
        accepting: () -> Boolean = { true }) = DesktopVideoEnhancementConfiguration(store,
            dispatcher = Dispatchers.Default, acceptChanges = accepting)
    private fun document(path: Path = root): JsonObject =
        Json.parseToJsonElement(Files.readString(path.resolve("plugin-settings.json"))).jsonObject

    @Test fun `first Windows migration enables NVIDIA without changing legacy JSON or other settings`(): Unit = runBlocking {
        val original = Json.parseToJsonElement("""{"plugin_prefs":{"plugin_enabled_anime4k":false,"plugin_config_anime4k":"{\"algorithm\":\"FSR_1_0\",\"rememberedEnabled\":false}"},"unrelated":{"keep":7}}""").jsonObject
        Files.writeString(root.resolve("plugin-settings.json"), original.toString())
        val config = configuration()
        withTimeout(5000) { config.flushAndClose() }
        assertTrue(config.automaticEnabled.value)
        val saved = document()
        assertEquals(original["plugin_prefs"], saved["plugin_prefs"])
        assertEquals(original["unrelated"], saved["unrelated"])
        assertEquals(1, saved["windows_video_enhancement"]!!.jsonObject["migration_version"]!!.jsonPrimitive.int)
        assertTrue(saved["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `manual off survives independent persisted Store reload and startup migration`(): Unit = runBlocking {
        val first = configuration()
        withTimeout(5000) { first.setAutomaticEnabled(false).await(); first.flushAndClose() }
        val coldRoot = Files.createDirectory(root.resolve("independent-cold-store"))
        Files.copy(root.resolve("plugin-settings.json"), coldRoot.resolve("plugin-settings.json"))
        val reloaded = configuration(DesktopPluginStore(coldRoot))
        withTimeout(5000) { reloaded.flushAndClose() }
        assertFalse(reloaded.automaticEnabled.value)
        assertFalse(document(coldRoot)["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `queued user intent is serialized and accepted writes drain after shutdown starts`(): Unit = runBlocking {
        val accepting = AtomicBoolean(true)
        val config = configuration(accepting = accepting::get)
        val requests = listOf(false, true, false).map(config::setAutomaticEnabled)
        accepting.set(false)
        assertFailsWith<IllegalStateException> { config.setAutomaticEnabled(true) }
        withTimeout(5000) { config.flushAndClose(); requests.forEach { it.await() } }
        assertFalse(config.automaticEnabled.value)
        assertFalse(document()["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertFailsWith<IllegalStateException> { config.setAutomaticEnabled(true) }
    }

    @Test fun `frozen Store write fails visibly without optimistic preference publication`(): Unit = runBlocking {
        val store = DesktopPluginStore(root)
        val config = configuration(store)
        withTimeout(5000) { config.setAutomaticEnabled(true).await() }
        store.freezeWrites()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setAutomaticEnabled(false).await() } }
        assertTrue(config.automaticEnabled.value)
        assertNotNull(config.error.value)
        assertTrue(document()["windows_video_enhancement"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        withTimeout(5000) { config.flushAndClose() }
    }

    @Test fun `legacy algorithm selection cannot mutate Windows intent or reinstall old configuration`(): Unit = runBlocking {
        val config = configuration()
        withTimeout(5000) { config.setAutomaticEnabled(false).await() }
        val before = document()
        assertFailsWith<IllegalStateException> { config.setAlgorithm(VideoEnhancementAlgorithm.FSR_1_0).await() }
        assertFalse(config.automaticEnabled.value)
        assertEquals(before, document())
        assertNotNull(config.error.value)
        withTimeout(5000) { config.flushAndClose() }
    }

    @Test fun `invalid migrated value stays disabled with visible error and is not silently overwritten`(): Unit = runBlocking {
        val original = """{"windows_video_enhancement":{"migration_version":1,"enabled":"true"}}"""
        Files.writeString(root.resolve("plugin-settings.json"), original)
        val config = configuration()
        assertFailsWith<IllegalStateException> { withTimeout(5000) { config.setAutomaticEnabled(false).await() } }
        assertFalse(config.automaticEnabled.value)
        assertNotNull(config.error.value)
        assertEquals(Json.parseToJsonElement(original), document())
        withTimeout(5000) { config.flushAndClose() }
    }
}
