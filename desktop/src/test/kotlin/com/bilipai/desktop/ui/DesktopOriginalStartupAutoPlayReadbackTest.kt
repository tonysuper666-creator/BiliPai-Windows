package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalMusicUiSettings
import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** Actual sole Store/complete original setter/sync reader. No Root/native startup claim. */
class DesktopOriginalStartupAutoPlayReadbackTest {
    private class Fixture {
        val directory = Files.createTempDirectory("bp-original-startup-")
        val plugin = DesktopPluginContext(DesktopPluginStore(directory))
        val current = AtomicBoolean(true)
        val context = DesktopOriginalPlayerSettingsContext(plugin, current::get, commitIfCurrent = { block ->
            if (current.get()) { block(); true } else false
        })
        fun read() = DesktopOriginalMusicUiSettings.getStartupAutoPlayEnabledSync(context)
    }
    @Test fun missingCanonicalRetainsOriginalDefaultAndLegacyMirror() {
        val f = Fixture()
        assertFalse(f.read())
        f.plugin.store.update("mini_player", mapOf("startup_auto_play_enabled" to JsonPrimitive(true)))
        assertTrue(f.read())
    }
    @Test fun canonicalFalseOverridesOlderTrueMirror() {
        val f = Fixture()
        f.plugin.store.update("mini_player", mapOf("startup_auto_play_enabled" to JsonPrimitive(true)))
        f.plugin.store.update("settings", mapOf("startup_auto_play_enabled" to JsonPrimitive(false)))
        assertFalse(f.read())
    }
    @Test fun completeOriginalSetterReachesSameSyncConsumerAndColdStore(): Unit = runBlocking {
        val f = Fixture()
        DesktopOriginalPlaybackPreferenceOperation.run(f.context, f.current::get) {
            DesktopOriginalPlaybackSettingsPreferences.setStartupAutoPlayEnabled(f.context, true)
            assertTrue(f.read()) // private journal read; published Store is still untouched
            assertFalse(f.plugin.store.preferences("settings").containsKey("startup_auto_play_enabled"))
        }
        assertTrue(f.read())
        val coldPlugin = DesktopPluginContext(DesktopPluginStore(f.directory))
        val cold = DesktopOriginalPlayerSettingsContext(coldPlugin, { true }, commitIfCurrent = { it(); true })
        assertTrue(DesktopOriginalMusicUiSettings.getStartupAutoPlayEnabledSync(cold))
    }
    @Test fun cancelledOriginalRequestDoesNotEnableColdReadback(): Unit = runBlocking {
        val f = Fixture()
        assertThrows(CancellationException::class.java) { runBlocking {
            DesktopOriginalPlaybackPreferenceOperation.run(f.context, f.current::get) {
                DesktopOriginalPlaybackSettingsPreferences.setStartupAutoPlayEnabled(f.context, true)
                f.current.set(false)
            }
        } }
        val coldPlugin = DesktopPluginContext(DesktopPluginStore(f.directory))
        val cold = DesktopOriginalPlayerSettingsContext(coldPlugin, { true }, commitIfCurrent = { it(); true })
        assertFalse(DesktopOriginalMusicUiSettings.getStartupAutoPlayEnabledSync(cold))
    }
}
