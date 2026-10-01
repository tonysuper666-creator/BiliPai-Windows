package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.*
import com.bilipai.desktop.backup.DesktopBackupArchive
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

private fun item(id: String) = PlaylistItem(id, 501, "Track $id", "", "Synthetic UP", duration = 90)
private fun saved(id: String, position: Double = 0.0) = ListenAudioSaved(listOf(item(id)), 0,
    recent = listOf(item(id)), favorites = listOf(item("favorite")), positionSeconds = position)
internal val listenBarrierFixtureRoots = mutableListOf<Path>()
private fun directory(): Path = Files.createTempDirectory("bp-l-").also {
    listenBarrierFixtureRoots.add(it)
    Files.writeString(it.resolve("fixture-owner.json"), "{\"owner\":\"isolated-listen-writer-barrier\"}")
}
private fun swing(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
private fun awaitBlocked(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
    assertEquals(Thread.State.BLOCKED, thread.state, "Writer must actually be queued on the store monitor")
}
private fun archive(snapshot: ListenAudioSaved): ByteArray {
    val root = directory()
    ListenAudioStore(root.resolve("listen-state.json")).save(snapshot)
    return DesktopBackupArchive(root).create(1_700_000_000_000L)
}
private fun rejectsWrite(store: ListenAudioStore) = assertThrows(IllegalStateException::class.java) { store.save(saved("stale")) }

private class BarrierSource(val prepareAction: suspend (PlaylistItem) -> PreparedListenAudio = {
    PreparedListenAudio(it, PlaybackSource("file:///C:/synthetic-listen-barrier.m4a", title = it.title))
}) : ListenPlaybackDataSource {
    override val lyrics = LyricsRepository(emptyList(), object : LyricsCache {
        override suspend fun read(key: String): LyricDocument? = null
        override suspend fun write(key: String, document: LyricDocument) = Unit
    })
    override suspend fun prepare(item: PlaylistItem) = prepareAction(item)
    override suspend fun subtitleTracks(item: PlaylistItem): List<SubtitleTrackMeta> = emptyList()
    override suspend fun subtitleCues(track: SubtitleTrackMeta): List<SubtitleCue> = emptyList()
}
private class SessionFixture(snapshot: ListenAudioSaved = saved("initial", 27.25),
    source: BarrierSource = BarrierSource()) : AutoCloseable {
    val root = directory()
    val file = root.resolve("listen-state.json")
    val store = ListenAudioStore(file)
    val repository = DesktopRepository(DesktopSessionStore.temporary())
    val community = DesktopCommunityRepository(repository)
    val player = MpvPlayer()
    lateinit var session: ListenAudioSession
    init {
        store.save(snapshot)
        swing { session = ListenAudioSession(repository, community, player, store = store, playbackDataSource = source) }
    }
    fun scope() = ListenAudioSession::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(session) as CoroutineScope
    @Suppress("UNCHECKED_CAST")
    fun native(change: (PlayerState) -> PlayerState) = swing {
        val state = MpvPlayer::class.java.getDeclaredField("mutableState").apply { isAccessible = true }.get(player) as MutableStateFlow<PlayerState>
        state.value = change(state.value)
    }
    override fun close() { swing { session.close() }; player.close() }
}

class ListenWriterBarrierTest {
    @Test fun cancelledQueuedIoCannotOverwriteActualRestoredArchive(): Unit = runBlocking {
        val root = directory(); val file = root.resolve("listen-state.json"); val store = ListenAudioStore(file)
        val final = saved("final", 27.25); val restored = saved("restored", 88.0); val bytes = archive(restored)
        val started = CountDownLatch(1); val thread = AtomicReference<Thread>(); val wrote = AtomicBoolean()
        val rejected = AtomicBoolean(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var writer: Job
        try {
            synchronized(store) {
                writer = scope.launch {
                    thread.set(Thread.currentThread()); started.countDown()
                    try { store.save(saved("obsolete", 1.0)); wrote.set(true) }
                    catch (_: IllegalStateException) { rejected.set(true) }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS)); awaitBlocked(thread.get())
                writer.cancel()
                store.closeAndSave(final)
                assertEquals(final, store.read())
                assertEquals(1, DesktopBackupArchive(root).restore(bytes))
            }
            withTimeout(3_000) { writer.join() }
            assertTrue(writer.isCancelled); assertFalse(wrote.get()); assertTrue(rejected.get())
            assertEquals(restored, ListenAudioStore(file).read())
            rejectsWrite(store)
        } finally { scope.cancel() }
    }

    @Test fun acceptedWriterDrainsBeforeFinalSnapshotAndCloseReturns(): Unit = runBlocking {
        val root = directory(); val store = ListenAudioStore(root.resolve("listen-state.json"))
        val accepted = CountDownLatch(1); val release = CountDownLatch(1); val closeStarted = CountDownLatch(1)
        val closeThread = AtomicReference<Thread>(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = scope.launch {
            synchronized(store) {
                accepted.countDown(); check(release.await(3, TimeUnit.SECONDS))
                store.save(saved("accepted", 10.0))
            }
        }
        assertTrue(accepted.await(3, TimeUnit.SECONDS))
        val closing = scope.async {
            closeThread.set(Thread.currentThread()); closeStarted.countDown()
            store.closeAndSave(saved("latest", 20.0))
        }
        try {
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS)); awaitBlocked(closeThread.get())
            assertFalse(closing.isCompleted)
            release.countDown()
            withTimeout(3_000) { writer.join(); closing.await() }
            assertEquals(saved("latest", 20.0), store.read())
            rejectsWrite(store)
        } finally { release.countDown(); scope.cancel() }
    }

    @Test fun failedFinalAtomicWriteStillRetiresOldFacade(): Unit {
        val root = directory(); val file = root.resolve("listen-state.json"); Files.createDirectory(file)
        Files.writeString(file.resolve("fixture-owner.txt"), "Intentionally block file replacement")
        val store = ListenAudioStore(file)
        assertThrows(Exception::class.java) { store.closeAndSave(saved("final")) }
        Files.delete(file.resolve("fixture-owner.txt"))
        Files.delete(file)
        rejectsWrite(store)
        val restored = saved("restored", 43.0)
        ListenAudioStore(file).save(restored)
        store.closeAndSave(saved("old-final"))
        assertEquals(restored, ListenAudioStore(file).read())
        assertEquals(listOf("fixture-owner.json", "listen-state.json"), Files.list(root).use { it.map { path -> path.fileName.toString() }.sorted().toList() })
    }

    @Test fun repeatedCloseKeepsFinalQueueCidResumeRecentAndFavorites(): Unit {
        val root = directory(); val store = ListenAudioStore(root.resolve("listen-state.json"))
        val snapshot = saved("final", 17.75).copy(queue = listOf(item("au501"), item("BVpart").copy(cid = 9988)), currentIndex = 1)
        store.closeAndSave(snapshot)
        store.closeAndSave(saved("second-close", 0.0))
        assertEquals(snapshot, store.read())
        rejectsWrite(store)
    }

    @Test fun ordinaryCloseDoesNotGloballyRetireNewRememberedStoreForSamePath(): Unit {
        val root = directory(); val file = root.resolve("listen-state.json")
        val old = ListenAudioStore(file)
        val alreadyConstructed = ListenAudioStore(file) // Compose remember runs before old DisposableEffect closes.
        old.closeAndSave(saved("old-final"))
        val next = saved("new-session", 6.0)
        alreadyConstructed.save(next)
        rejectsWrite(old)
        assertEquals(next, alreadyConstructed.read())
    }

    @Test fun accountStoreBarrierDoesNotCloseOtherAccountFile(): Unit {
        val root = directory(); val first = ListenAudioStore(root.resolve("account-first.json"))
        val second = ListenAudioStore(root.resolve("account-second.json"))
        first.closeAndSave(saved("one")); second.save(saved("two", 9.5))
        rejectsWrite(first)
        assertEquals(saved("two", 9.5), second.read())
    }

    @Test fun actualSessionCloseRejectsCancelledScopeWriterAfterRestore(): Unit = runBlocking {
        SessionFixture().use { f ->
            val restored = saved("restored-session", 72.0); val bytes = archive(restored)
            val started = CountDownLatch(1); val thread = AtomicReference<Thread>(); val rejected = AtomicBoolean()
            lateinit var writer: Job
            swing {
                synchronized(f.store) {
                    writer = f.scope().launch(Dispatchers.IO) {
                        thread.set(Thread.currentThread()); started.countDown()
                        try { f.store.save(saved("cancelled-session-io")) }
                        catch (_: IllegalStateException) { rejected.set(true) }
                    }
                    assertTrue(started.await(3, TimeUnit.SECONDS)); awaitBlocked(thread.get())
                    f.session.close()
                    DesktopBackupArchive(f.root).restore(bytes)
                }
            }
            withTimeout(3_000) { writer.join() }
            assertTrue(writer.isCancelled); assertTrue(rejected.get())
            assertEquals(restored, ListenAudioStore(f.file).read())
        }
    }

    @Test fun normalSessionCloseKeepsLastStateDespitePendingDebounce(): Unit = runBlocking {
        SessionFixture().use { f ->
            swing { f.session.enqueue(listOf(item("queued-new"))); f.session.toggleFavorite(item("manual-favorite")); f.session.close() }
            withTimeout(3_000) { f.scope().coroutineContext[Job]!!.join() }
            val disk = ListenAudioStore(f.file).read()
            assertEquals(listOf("initial", "queued-new"), disk.queue.map { it.bvid })
            assertEquals(listOf("manual-favorite", "favorite"), disk.favorites.map { it.bvid })
            assertEquals(listOf("initial"), disk.recent.map { it.bvid }); assertEquals(27.25, disk.positionSeconds)
        }
    }

    @Test fun restoreShutdownWaitsForActualScopeCompletionAndPreservesForeignSource(): Unit = runBlocking {
        val ready = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        SessionFixture(source = BarrierSource { selected -> withContext(NonCancellable) {
            ready.complete(Unit); finish.await(); PreparedListenAudio(selected, PlaybackSource("file:///C:/late-listen.m4a"))
        } }).use { f ->
            swing { f.session.play(listOf(item("au501"))) }
            withTimeout(3_000) { ready.await() }
            val foreign = f.player.loadVersioned(PlaybackSource("file:///C:/foreign-listen.m4a", startPaused = true))
            val shutdown = async(Dispatchers.Swing) { f.session.shutdownForRestore() }
            swing { } // Shutdown has run close and yielded in join on the same dispatcher.
            assertFalse(shutdown.isCompleted)
            rejectsWrite(f.store)
            finish.complete(Unit)
            withTimeout(3_000) { shutdown.await() }
            assertFalse(f.scope().coroutineContext[Job]!!.isActive)
            assertTrue(f.scope().coroutineContext[Job]!!.isCompleted)
            assertEquals(foreign, f.player.currentSourceVersion)
            withContext(Dispatchers.Swing) { f.session.shutdownForRestore() }
        }
    }

    @Test fun originalAudioRecentAndResumeRemainUnconditionalUnderPrivacyMode(): Unit = runBlocking {
        SessionFixture().use { f ->
            val preferences = DesktopSearchPreferences(f.root.resolve("temporary-settings"))
            preferences.setPrivacyMode(true)
            assertTrue(preferences.isPrivacyModeEnabledSync())
            // Bind the actual shared community facade to task-owned preferences; no user setting is touched.
            DesktopCommunityRepository::class.java.getDeclaredField("searchPreferences\$delegate").apply { isAccessible = true }
                .set(f.community, lazy { preferences })
            swing { f.session.play(listOf(item("au501"))) }
            withTimeout(3_000) { f.session.state.first { it.active && !it.loading } }
            f.native { it.copy(loading = false, ready = true, durationSeconds = 90.0, positionSeconds = 31.25) }
            withContext(Dispatchers.Swing) { f.session.shutdownForRestore() }
            val disk = ListenAudioStore(f.file).read()
            assertEquals("au501", disk.recent.first().bvid)
            assertEquals(31.25, disk.positionSeconds)
            assertEquals("au501", disk.queue[disk.currentIndex].bvid)
        }
    }
}
