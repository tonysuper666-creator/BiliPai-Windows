package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.LocalPlaylist
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** The ListenVideo page's history-only music UI platform over the real settings document. No window, native
 * player, audio output or network: only the play history key that the AudioMode writes. */
class DesktopOriginalListenHistoryPlatformTest {
    private class Fixture {
        val directory = Files.createTempDirectory("bp-listen-history-")
        val plugin = DesktopPluginContext(DesktopPluginStore(directory))
        val current = AtomicBoolean(true)
        val context = DesktopOriginalPlayerSettingsContext(plugin, current::get, commitIfCurrent = { block ->
            if (current.get()) { block(); true } else false
        })
        val history = DesktopOriginalMusicStorageBinding(context).history
        val platform = DesktopOriginalListenHistoryPlatform(context, history)
        fun close() = Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun theSheetReadsTheSameHistoryDocumentTheAudioModeWrites() = fixture { f ->
        f.plugin.store.update("settings", mapOf("audio_play_history_v1" to JsonPrimitive(
            "[{\"bvid\":\"BV1aaaaaaaaa\",\"cid\":11,\"title\":\"One\",\"lastPlayedAtMs\":200}," +
                "{\"bvid\":\"BV1bbbbbbbbb\",\"cid\":22,\"title\":\"Two\",\"lastPlayedAtMs\":100}]")))
        val entries = runBlocking { f.platform.history.recent(50).first() }
        assertEquals(listOf("BV1aaaaaaaaa", "BV1bbbbbbbbb"), entries.map { it.bvid })
        assertEquals(listOf(11L, 22L), entries.map { it.cid })
        assertEquals(1, runBlocking { f.platform.history.recent(1).first() }.size)
    }

    @Test fun anEmptyDocumentIsAnEmptyHistory() = fixture { f ->
        assertTrue(runBlocking { f.platform.history.recent(50).first() }.isEmpty())
    }

    @Test fun theProvidedContextAndPortAreTheOnesTheSheetReads() = fixture { f ->
        assertSame(f.context, f.platform.context)
        assertSame(f.history, f.platform.history)
    }

    @Test fun aRetiredPageCannotStartAHistoryRead() = fixture { f ->
        f.current.set(false)
        assertThrows(CancellationException::class.java) { f.platform.history.recent(50) }
    }

    @Test fun everyFullPlayerMemberIsRejectedInsteadOfInventingState() = fixture { f ->
        val rejected = listOf<() -> Any?>(
            { f.platform.homeSettings }, { f.platform.systemReduceMotion }, { f.platform.isInBackground },
            { f.platform.blurEffectsAvailable }, { f.platform.volume }, { f.platform.maximumVolumeStep },
            { f.platform.setVolumeFraction(0.5f) }, { f.platform.externalPlaylist },
            { runBlocking { f.platform.savePlaylist(LocalPlaylist(id = "x", name = "x", items = emptyList())) } },
            { runBlocking { f.platform.loadOwnedArtwork("https://example.invalid/cover.jpg") } },
            { runBlocking { f.platform.commitUi { } } },
        )
        assertEquals(11, rejected.size)
        for (member in rejected) assertThrows(UnsupportedOperationException::class.java) { member() }
    }
}
