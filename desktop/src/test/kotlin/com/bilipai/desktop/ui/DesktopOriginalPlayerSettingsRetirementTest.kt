package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.resolveDefaultPlayerDiagnosticLoggingEnabled
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real fixed-original defaults and real settings Store; no AWT/MPV/account fixture claim. */
class DesktopOriginalPlayerSettingsRetirementTest {
    private fun store() = DesktopPluginStore(Files.createTempDirectory("bp-retained-player-default-"))

    @Test fun originalReleaseAndDebugDefaultsRemainReadableAfterPageRetirementWithoutPublication() {
        for (debug in listOf(false, true)) {
            val store = store()
            val owns = AtomicBoolean(true)
            val gates = AtomicInteger()
            val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), owns::get,
                { gates.incrementAndGet(); error("A pure build default must not enter a write gateway") },
                largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { debug })
            assertEquals(resolveDefaultPlayerDiagnosticLoggingEnabled(debug), context.defaultPlayerDiagnosticLoggingEnabled())
            owns.set(false)
            // Same call used as the complete original page's collect initialValue during exit recomposition.
            assertEquals(!debug, context.defaultPlayerDiagnosticLoggingEnabled())
            assertEquals(0, gates.get())
            assertTrue(store.preferences("settings").isEmpty())
            assertFalse(Files.exists(store.root.resolve("plugin-settings.json")))
        }
    }

    @Test fun capturedActualDeviceDefaultRemainsPureAfterRetirementWithoutAReplacementDefault() {
        for (actualLarge in listOf(false, true)) {
            val store = store()
            val owns = AtomicBoolean(true)
            val calls = AtomicInteger()
            val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), owns::get,
                { error("A pure physical-device default must not enter a write gateway") },
                largeScreenOrFoldableConfiguration = { calls.incrementAndGet(); actualLarge }, isDebugBuild = { false })
            assertEquals(actualLarge, context.isLargeScreenOrFoldableConfiguration())
            owns.set(false)
            assertEquals(actualLarge, context.isLargeScreenOrFoldableConfiguration())
            assertEquals(2, calls.get())
            assertFalse(Files.exists(store.root.resolve("plugin-settings.json")))
        }
    }

    @Test fun absentPlatformProvidersStillFailExplicitlyEvenAfterRetirement() {
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store()), { false },
            { error("Missing required platform providers must not commit") })
        assertThrows(IllegalStateException::class.java) { context.defaultPlayerDiagnosticLoggingEnabled() }
        assertThrows(IllegalStateException::class.java) { context.isLargeScreenOrFoldableConfiguration() }
    }

    @Test fun retiredPageStillRejectsRealCanonicalMirrorPermitsAndNativeOverlayWithoutPublishing() {
        val store = store()
        store.update("settings", mapOf("foreign" to JsonPrimitive("keep")))
        val before = Files.readAllBytes(store.root.resolve("plugin-settings.json"))
        val gates = AtomicInteger()
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { false },
            { gates.incrementAndGet(); it(); true }, largeScreenOrFoldableConfiguration = { true }, isDebugBuild = { false })
        assertTrue(context.defaultPlayerDiagnosticLoggingEnabled())
        assertThrows(CancellationException::class.java) { context.commit {} }
        assertThrows(CancellationException::class.java) { context.preferenceWritePermit {} }
        assertThrows(CancellationException::class.java) { context.videoOverlay }
        assertThrows(CancellationException::class.java) {
            context.getSharedPreferences("player_settings_cache", 0).edit().putBoolean("hw_decode_enabled", false).apply()
        }
        assertThrows(CancellationException::class.java) { runBlocking {
            DesktopOriginalPlaybackSettingsPreferences.setHwDecode(context, false)
        } }
        assertThrows(CancellationException::class.java) { runBlocking {
            DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, 1.5f)
        } }
        assertEquals(0, gates.get())
        assertArrayEquals(before, Files.readAllBytes(store.root.resolve("plugin-settings.json")))
        assertTrue(store.preferences("player_settings_cache").isEmpty())
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
    }

    @Test fun actualRetirementDuringFinalAdmissionStillRejectsTheWholeOriginalDoubleWrite() {
        val store = store()
        store.update("settings", mapOf("foreign" to JsonPrimitive(7)))
        val before = Files.readAllBytes(store.root.resolve("plugin-settings.json"))
        val owns = AtomicBoolean(true)
        val permits = AtomicInteger()
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), owns::get,
            { action -> permits.incrementAndGet(); owns.set(false); action(); true },
            largeScreenOrFoldableConfiguration = { true }, isDebugBuild = { false })
        assertTrue(context.defaultPlayerDiagnosticLoggingEnabled())
        assertThrows(CancellationException::class.java) { runBlocking {
            DesktopOriginalVideoPlayerSettings.setLastPlaybackSpeed(context, 2f)
        } }
        assertTrue(permits.get() >= 1)
        assertArrayEquals(before, Files.readAllBytes(store.root.resolve("plugin-settings.json")))
        assertTrue(store.preferences("playback_speed_cache").isEmpty())
        assertNull(DesktopOriginalPlaybackPreferenceOperation.currentOrNull())
    }
}
