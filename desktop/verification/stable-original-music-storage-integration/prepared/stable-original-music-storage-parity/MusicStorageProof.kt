package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private var musicStorageAssertions = 0
private fun musicStorageVerify(value: Boolean, message: String) { check(value) { message }; musicStorageAssertions++ }

fun main() = runBlocking {
    withTimeout(30_000) {
        val root = Files.createTempDirectory("bilipai-original-music-storage-")
        val store = DesktopPluginStore(root)
        store.update("unrelated", mapOf("retain" to JsonPrimitive("keep")))
        val current = AtomicBoolean(true)
        val admissions = AtomicInteger()
        val sameSnapshot = CountDownLatch(2)
        var concurrent = false
        val gate = Any()
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), current::get, { action ->
            synchronized(gate) { check(current.get()); action() }
            if (concurrent && admissions.incrementAndGet() <= 2) {
                sameSnapshot.countDown(); check(sameSnapshot.await(5, TimeUnit.SECONDS))
            }
            true
        })
        val storage = DesktopOriginalMusicStorageBinding(context)
        musicStorageVerify(storage.history.recent(50).first().isEmpty(), "same original history starts empty")
        musicStorageVerify(storage.history.lastSession().first() == null, "same original last session starts empty")
        val original = PlayHistoryEntry("BV1original", 11, "title", "cover", "owner", 150, 1, 1000)
        storage.history.record(original)
        storage.history.record(original.copy(cid = 22, title = "", cover = "", owner = "", durationSec = 151, lastPlayedAtMs = 2000))
        val record = storage.history.recent(50).first().single()
        musicStorageVerify(record.cid == 22L && record.playCount == 2, "original history deduplicates BVID and counts the new CID play")
        musicStorageVerify(record.title == "title" && record.cover == "cover" && record.owner == "owner", "original blank-field merge survives real Store")
        musicStorageVerify(record.durationSec == 151L && record.lastPlayedAtMs == 2000L, "original fields retain seconds and wall-clock schema")
        concurrent = true
        coroutineScope {
            val left = async(Dispatchers.IO) { storage.history.record(original.copy(cid = 33, lastPlayedAtMs = 3000)) }
            val right = async(Dispatchers.IO) { storage.history.record(original.copy(cid = 44, lastPlayedAtMs = 4000)) }
            left.await(); right.await()
        }
        concurrent = false
        musicStorageVerify(storage.history.recent(50).first().single().playCount == 4, "original read-dependent count survives a real concurrent CAS conflict")
        musicStorageVerify(admissions.get() == 3, "conflicting original record recomputes exactly once")
        val session = PlayLastSession("BV1original", 44, "title", "cover", "owner", 123456, 567890)
        storage.history.saveLastSession(session)
        musicStorageVerify(storage.history.lastSession().first() == session, "original last-session milliseconds round-trip")
        val playlist = LocalPlaylist("same-id", "First", "cover", "external", 123,
            listOf(LocalPlaylistItem("BV1original", "title", "cover", "owner", 150)))
        storage.savePlaylist(playlist)
        storage.savePlaylist(playlist.copy(name = "Replacement"))
        musicStorageVerify(DesktopOriginalLocalPlaylistStore.playlists(context).first().single().name == "Replacement", "original playlist id replacement has one canonical row")
        storage.savePlaylist(playlist.copy(id = "other-id", name = "New"))
        musicStorageVerify(DesktopOriginalLocalPlaylistStore.playlists(context).first().map { it.id } == listOf("other-id", "same-id"), "original playlist prepend order is retained")
        DesktopOriginalLocalPlaylistStore.addToPlaylist(context, "same-id", listOf(LocalPlaylistItem("BV1original"), LocalPlaylistItem("BV2new")))
        musicStorageVerify(DesktopOriginalLocalPlaylistStore.playlists(context).first().single { it.id == "same-id" }.items.map { it.bvid } == listOf("BV1original", "BV2new"), "original add algorithm filters existing BVIDs")
        DesktopOriginalLocalPlaylistStore.deletePlaylist(context, "other-id")
        musicStorageVerify(DesktopOriginalLocalPlaylistStore.playlists(context).first().map { it.id } == listOf("same-id"), "original delete preserves other playlist")
        val before = Files.readString(root.resolve("plugin-settings.json"))
        current.set(false)
        musicStorageVerify(runCatching { storage.history.record(original) }.exceptionOrNull() is CancellationException, "retired Root history port rejects writes")
        musicStorageVerify(runCatching { storage.savePlaylist(playlist) }.exceptionOrNull() is CancellationException, "retired Root playlist port rejects writes")
        musicStorageVerify(Files.readString(root.resolve("plugin-settings.json")) == before, "retired operations leave disk intact")
        current.set(true)
        store.freezeWrites()
        val restoredStore = DesktopPluginStore(root)
        val restoredContext = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(restoredStore), { true }, { action -> action(); true })
        val restored = DesktopOriginalMusicStorageBinding(restoredContext)
        musicStorageVerify(restored.history.lastSession().first() == session, "cold generation reads original session from actual disk")
        musicStorageVerify(restored.history.recent(50).first().single().playCount == 4, "cold generation reads original recent schema")
        musicStorageVerify(DesktopOriginalLocalPlaylistStore.playlists(restoredContext).first().single().items.size == 2, "cold generation reads original playlist schema")
        val raw = restoredStore.preferences("settings")
        musicStorageVerify(raw.keys.containsAll(listOf("audio_play_history_v1", "audio_last_session_v1", "local_playlists_v1")), "all original keys are in the existing settings namespace")
        musicStorageVerify(restoredStore.preferences("unrelated")["retain"]?.jsonPrimitive?.content == "keep", "all original writes preserve unrelated namespaces")
        restoredStore.update("settings", mapOf("audio_play_history_v1" to JsonPrimitive("broken"), "audio_last_session_v1" to JsonPrimitive("broken")))
        musicStorageVerify(restored.history.recent(50).first().isEmpty() && restored.history.lastSession().first() == null, "original corrupt-history fallback is unchanged")
        val many = (1..303).map { PlayHistoryEntry("BV-many-$it", lastPlayedAtMs = it.toLong()) }
        restoredStore.update("settings", mapOf("audio_play_history_v1" to JsonPrimitive(Json.encodeToString(many))))
        restored.history.record(original)
        val capped = restored.history.recent(500).first()
        musicStorageVerify(capped.size == 300 && capped.first().bvid == original.bvid, "original MAX_ENTRIES cap applies to migrated disk history")
        musicStorageVerify(restored.history.recent(2).first().size == 2, "original recent limit is retained")
        musicStorageVerify(Files.list(root).use { it.noneMatch { p -> p.fileName.toString().endsWith(".tmp") } }, "no history or playlist staging files leak")
        println("MusicStorageProof PASS $musicStorageAssertions assertions; complete original algorithms, sole models and real existing Store")
        println("Fixture temporary root: $root")
    }
}
