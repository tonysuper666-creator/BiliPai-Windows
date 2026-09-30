package com.bilipai.desktop.player

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopPlayerPreferencesWriterTest {
    @Test fun `blocked older write cannot overwrite newest remembered quality after shutdown flush`() = runBlocking {
        val directory = Files.createTempDirectory("player-pref-writer")
        val store = PlayerPreferencesStore(directory.resolve("settings.json"))
        val started = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<PlayerPreferences>()
        val writer = DesktopPlayerPreferencesWriter({ snapshot ->
            if (writes.isEmpty()) { started.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            writes += snapshot; store.save(snapshot)
        })
        try {
            val old = PlayerPreferences(volume = 11.0)
            val newest = old.copy(volume = 71.0, lastSelectedAudioQuality = 30251)
            assertTrue(writer.submit(old)); withTimeout(5_000) { started.await() }
            assertTrue(writer.submit(old.copy(volume = 23.0)))
            assertTrue(writer.submit(newest)); release.countDown(); writer.flushAndClose()
            assertEquals(listOf(old, newest), writes.toList())
            assertEquals(newest.normalized(), store.read())
            assertFalse(writer.submit(old))
        } finally { release.countDown(); writer.close(); Files.deleteIfExists(directory.resolve("settings.json")); Files.deleteIfExists(directory) }
    }

    @Test fun `a failed disk write is surfaced and next accepted snapshot still persists`() = runBlocking {
        val directory = Files.createTempDirectory("player-pref-writer")
        val store = PlayerPreferencesStore(directory.resolve("settings.json")); var attempts = 0
        val writer = DesktopPlayerPreferencesWriter({ snapshot ->
            if (++attempts == 1) throw java.io.IOException("synthetic failure")
            store.save(snapshot)
        })
        try {
            writer.submit(PlayerPreferences(volume = 11.0))
            withTimeout(5_000) { writer.failed.first { it } }
            val newest = PlayerPreferences(volume = 82.0, lastSelectedAudioQuality = 30250)
            writer.submit(newest); writer.flushAndClose()
            assertFalse(writer.failed.value); assertEquals(2, attempts); assertEquals(newest.normalized(), store.read())
        } finally { writer.close(); Files.deleteIfExists(directory.resolve("settings.json")); Files.deleteIfExists(directory) }
    }
}
