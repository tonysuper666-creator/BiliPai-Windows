package com.bilipai.desktop.player

import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*
import org.junit.jupiter.api.Test

class DesktopWindowsAudioOutputStoreTest {
    @Test fun `exclusive choice uses the sole existing document without changing other namespaces or legacy levels`() = runBlocking {
        val root = Files.createTempDirectory("bilipai-private-windows-audio-")
        val store = DesktopPluginStore(root)
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val player = MpvPlayer(); val controller = DesktopWindowsAudioOutputController(store, scope, player, null)
        var current = true
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { current }, { action -> if (current) { action(); true } else false })
        try {
            assertFalse(controller.preferences.value.exclusive)
            player.setVolume(31.0); player.setMuted(true)
            controller.selectDevice(context, "wasapi/{synthetic-private-device}")
            controller.updateExclusive(context, true)
            assertEquals(JsonPrimitive(true), store.preferences(DesktopWindowsAudioOutputController.NAMESPACE)["exclusive"])
            assertEquals(JsonPrimitive("wasapi/{synthetic-private-device}"), store.preferences(DesktopWindowsAudioOutputController.NAMESPACE)["device_id"])
            assertEquals(31.0, player.state.value.volume); assertTrue(player.state.value.muted)
            assertFalse(Files.exists(root.resolve("player-settings.json")))
            val persisted = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
            assertEquals(setOf(DesktopWindowsAudioOutputController.NAMESPACE), persisted.keys)
            current = false
            assertFailsWith<CancellationException> { controller.updateExclusive(context, false) }
            assertEquals(JsonPrimitive(true), store.preferences(DesktopWindowsAudioOutputController.NAMESPACE)["exclusive"])
        } finally {
            controller.close(); player.close(); job.cancel()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun `foreign Store page is rejected and an uninitialized actor does not invent a device directory`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-private-windows-audio-foreign-")
        val store = DesktopPluginStore(root.resolve("first")); val other = DesktopPluginStore(root.resolve("other"))
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val player = MpvPlayer(); val controller = DesktopWindowsAudioOutputController(store, scope, player, null)
        val foreign = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(other), { true }, { action -> action(); true })
        try {
            assertFailsWith<IllegalArgumentException> { controller.updateExclusive(foreign, true) }
            assertTrue(store.preferences(DesktopWindowsAudioOutputController.NAMESPACE).isEmpty())
            val actual = player.queryWindowsAudioDevices()
            assertFalse(actual.available); assertTrue(actual.devices.isEmpty()); assertNotNull(actual.error)
        } finally {
            controller.close(); player.close(); job.cancel()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun `explicit device selection repairs malformed fields in the same atomic namespace without enabling exclusive`() = runBlocking {
        val root = Files.createTempDirectory("bilipai-private-windows-audio-malformed-")
        val store = DesktopPluginStore(root)
        store.update(DesktopWindowsAudioOutputController.NAMESPACE, mapOf("exclusive" to JsonPrimitive("true"), "device_id" to JsonPrimitive("null")))
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val player = MpvPlayer(); val controller = DesktopWindowsAudioOutputController(store, scope, player, null)
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { action -> action(); true })
        try {
            assertFalse(controller.preferences.value.exclusive); assertNotNull(controller.configurationError.value)
            assertFalse(player.windowsAudioOutput.value.requested.exclusive)
            controller.selectDevice(context, "wasapi/{reselected-private-device}")
            val stored = store.preferences(DesktopWindowsAudioOutputController.NAMESPACE)
            assertEquals(JsonPrimitive(false), stored["exclusive"])
            assertEquals(JsonPrimitive("wasapi/{reselected-private-device}"), stored["device_id"])
            val decoded = DesktopWindowsAudioOutputController.decodeConfiguration(store.snapshot(DesktopWindowsAudioOutputController.NAMESPACE).value)
            assertFalse(decoded.preferences.exclusive); assertNull(decoded.error)
            assertEquals("wasapi/{reselected-private-device}", player.windowsAudioOutput.value.requested.deviceId)
        } finally {
            controller.close(); player.close(); job.cancel()
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
