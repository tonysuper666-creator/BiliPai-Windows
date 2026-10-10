package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real menu selection policy and isolated canonical settings; no UI, account or native IO. */
class DesktopWindowsVideoSpeedOptionsTest {
    @Test fun customFloatSpeedDoesNotExposeItsDoublePrecisionTailInTheControlLabel() {
        val nativeSpeed = 2.4f.toDouble()
        assertEquals("2.4", playbackSpeedLabel(nativeSpeed))
        assertEquals("1.25", playbackSpeedLabel(1.25f.toDouble()))
    }

    @Test fun customSpeedIsOfferedWithTheConfiguredOptions() {
        assertEquals(listOf(2.4f, 2f, 1.5f, 1f),
            resolveDesktopWindowsVideoSpeedOptions(listOf(1f, 1.5f, 2f, 2.4f), 1f))
    }

    @Test fun deletedDefaultIsNotReintroducedByTheMenu() {
        val menu = resolveDesktopWindowsVideoSpeedOptions(listOf(1f, 2.4f), 1f)
        assertEquals(listOf(2.4f, 1f), menu)
        assertFalse(0.75f in menu)
        assertFalse(1.25f in menu)
        assertFalse(1.5f in menu)
        assertFalse(2f in menu)
    }

    @Test fun currentSpeedRemainsAvailableAfterItsOptionIsDeleted() {
        assertEquals(listOf(2.4f, 1.5f, 1f),
            resolveDesktopWindowsVideoSpeedOptions(listOf(1f, 2.4f), 1.5f))
        assertEquals(listOf(2.4f, 1f),
            resolveDesktopWindowsVideoSpeedOptions(listOf(1f, 2.4f), 1f))
    }

    @Test fun repeatedOptionsAndCurrentSpeedAppearOnlyOnceInDescendingOrder() {
        val configured = listOf(1.5f, 2.4f, 1f, 1.5f, 2.4f)
        assertEquals(listOf(2.4f, 1.5f, 1f),
            resolveDesktopWindowsVideoSpeedOptions(configured, 1.5f))
        assertEquals(listOf(1.5f, 2.4f, 1f, 1.5f, 2.4f), configured)
    }

    @Test fun beforeSettingsArriveOnlyTheActualCurrentSpeedIsShown() {
        assertEquals(listOf(2.4f), resolveDesktopWindowsVideoSpeedOptions(emptyList(), 2.4f))
    }

    @Test fun aChangedCanonicalListAndCurrentSpeedReplaceThePreviousMenu() {
        val oldMenu = resolveDesktopWindowsVideoSpeedOptions(listOf(1f, 1.5f, 2f), 1.5f)
        val newMenu = resolveDesktopWindowsVideoSpeedOptions(listOf(1f, 2.4f), 2.4f)
        assertEquals(listOf(2f, 1.5f, 1f), oldMenu)
        assertEquals(listOf(2.4f, 1f), newMenu)
    }

    @Test fun originalReaderAndSettersSupplyCustomAndDeletedOptionsToTheMenu(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-windows-video-speed-options-")
        val store = DesktopPluginStore(root)
        store.update("settings", mapOf("playback_speed_options" to JsonPrimitive("1,1.5,2")))
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), { true }, { it(); true })
        val options = DesktopOriginalVideoControlSettings.getPlaybackSpeedOptions(context)
        withTimeout(2_000L) {
            assertEquals(listOf(2f, 1.5f, 1f),
                resolveDesktopWindowsVideoSpeedOptions(options.first(), 1f))
            DesktopOriginalVideoPlayerSettings.addPlaybackSpeedOption(context, 2.4f)
            val added = options.first { 2.4f in it }
            assertEquals(listOf(2.4f, 2f, 1.5f, 1f),
                resolveDesktopWindowsVideoSpeedOptions(added, 1f))
            DesktopOriginalVideoPlayerSettings.removePlaybackSpeedOption(context, 1.5f)
            val removed = options.first { 1.5f !in it }
            assertEquals(listOf(2.4f, 2f, 1f),
                resolveDesktopWindowsVideoSpeedOptions(removed, 1f))
            assertEquals(listOf(2.4f, 2f, 1.5f, 1f),
                resolveDesktopWindowsVideoSpeedOptions(removed, 1.5f))
            val coldContext = DesktopOriginalPlayerSettingsContext(
                DesktopPluginContext(DesktopPluginStore(root)), { true }, { it(); true })
            val persisted = DesktopOriginalVideoControlSettings.getPlaybackSpeedOptions(coldContext).first()
            assertTrue(2.4f in persisted)
            assertFalse(1.5f in persisted)
        }
    }
}
