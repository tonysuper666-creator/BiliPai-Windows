package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlin.test.*

/** Exercises the actual Root binding and original PlaylistManager with a temporary
 * Store. No player, HTTP, GUI or native library is created. The hook retires the
 * real caller at Root admission, after the caller's earlier outer check. */
class DesktopOriginalListenCollectionPlaylistAdmissionTest {
    @Test fun cancelledCallerCannotInstallCollectionAfterEnteringRootMutation(): Unit = runBlocking {
        fixture { f ->
            f.queue.setPlaylist(listOf(item("BVold")), 0)
            val before = f.queue.playlist.value
            val caller = Job()
            assertTrue(caller.isActive)
            f.beforeMutation = { caller.cancel() }
            assertFailsWith<CancellationException> {
                f.queue.setExternalPlaylistIfCallerCurrent(listOf(item("BVnew")), 0,
                    ExternalPlaylistSource.FAVORITE) { caller.isActive }
            }
            assertSame(before, f.queue.playlist.value)
            assertFalse(f.queue.isExternalPlaylist.value)
        }
    }

    @Test fun cancelledCallerCannotAppendEvenWhenOriginalSessionStillMatches(): Unit = runBlocking {
        fixture { f ->
            val session = f.queue.setExternalPlaylist(listOf(item("BVa")), 0, ExternalPlaylistSource.FAVORITE)
            val before = f.queue.playlist.value
            val caller = Job()
            f.beforeMutation = { caller.cancel() }
            assertFailsWith<CancellationException> {
                f.queue.addAllToPlaylistIfCallerCurrent(listOf(item("BVb")), session) { caller.isActive }
            }
            assertSame(before, f.queue.playlist.value)
            assertTrue(f.queue.isSessionCurrent(session))
        }
    }

    @Test fun replacedOriginalSessionRejectsAnOtherwiseLiveCollectionCaller(): Unit = runBlocking {
        fixture { f ->
            val old = f.queue.setExternalPlaylist(listOf(item("BVa")), 0, ExternalPlaylistSource.FAVORITE)
            val current = f.queue.setExternalPlaylist(listOf(item("BVother")), 0, ExternalPlaylistSource.FAVORITE)
            val before = f.queue.playlist.value
            assertFalse(f.queue.addAllToPlaylistIfCallerCurrent(listOf(item("BVlate")), old) { true })
            assertSame(before, f.queue.playlist.value)
            assertTrue(f.queue.isSessionCurrent(current))
        }
    }

    @Test fun cumulativePagesPreserveOriginalSelectionAndDeduplicateEarlierTracks(): Unit = runBlocking {
        fixture { f ->
            val a = item("BVa"); val b = item("BVb"); val c = item("BVc")
            val session = f.queue.setExternalPlaylistIfCallerCurrent(listOf(a, b), 0,
                ExternalPlaylistSource.FAVORITE) { true }
            assertEquals(b, f.queue.playAt(1))
            assertTrue(f.queue.addAllToPlaylistIfCallerCurrent(listOf(a, b, c), session) { true })
            assertEquals(listOf(a, b, c), f.queue.playlist.value)
            assertEquals(1, f.queue.currentIndex.value)
            assertEquals(b, f.queue.getCurrentItem())
            assertTrue(f.queue.isSessionCurrent(session))
        }
    }

    @Test fun selectedItemReceiptTracksOriginalSessionSelectionAndGrowingPages(): Unit = runBlocking {
        fixture { f ->
            val a = item("BVa"); val b = item("BVb"); val c = item("BVc")
            val session = f.queue.setExternalPlaylist(listOf(a, b), 0, ExternalPlaylistSource.FAVORITE)
            val selectedA = assertNotNull(DesktopOriginalListenCollectionQueueSelection.capture(
                f.queue, session, a, { true }, { true }))
            val requestA = PlaybackRequest.create(a.bvid, cid = a.cid)
            assertTrue(selectedA.matches(requestA, 0L))
            for (different in listOf(requestA.copy(bvid = b.bvid), requestA.copy(cid = 999L), requestA.copy(aid = 7L)))
                assertFalse(selectedA.matches(different, 0L))
            assertFalse(selectedA.matches(requestA, 1L))
            f.queue.playAt(1)
            assertFalse(selectedA.isCurrent())
            val selectedB = assertNotNull(DesktopOriginalListenCollectionQueueSelection.capture(
                f.queue, session, b, { true }, { true }))
            assertTrue(f.queue.addAllToPlaylistIfCurrent(listOf(a, b, c), session))
            assertTrue(selectedB.isCurrent(), "An appended page must keep the current item receipt")
            f.queue.setExternalPlaylist(listOf(b), 0, ExternalPlaylistSource.FAVORITE)
            assertFalse(selectedB.isCurrent(), "Equal item content cannot authorize a replacement session")
        }
    }

    @Test fun selectedItemReceiptRetiresWithItsActualCallerOrOriginalVmPublication(): Unit = runBlocking {
        fixture { f ->
            val item = item("BVa")
            val session = f.queue.setExternalPlaylist(listOf(item), 0, ExternalPlaylistSource.FAVORITE)
            val caller = Job()
            var originalPublication = true
            val receipt = assertNotNull(DesktopOriginalListenCollectionQueueSelection.capture(
                f.queue, session, item, { caller.isActive }, { originalPublication }))
            val before = f.queue.playlist.value
            originalPublication = false
            assertFalse(receipt.isCurrent())
            assertNull(DesktopOriginalListenCollectionQueueSelection.capture(
                f.queue, session, item, { caller.isActive }, { originalPublication }))
            originalPublication = true
            assertTrue(receipt.isCurrent())
            caller.cancel()
            assertFalse(receipt.isCurrent())
            assertSame(before, f.queue.playlist.value, "Read-only receipt checks cannot change the real queue")
        }
    }

    @Test fun resolvingOriginalSelectedCidRequiresAFreshItemReceipt(): Unit = runBlocking {
        fixture { f ->
            val unresolved = item("BVa").copy(cid = 0L)
            val session = f.queue.setExternalPlaylist(listOf(unresolved), 0, ExternalPlaylistSource.FAVORITE)
            val receipt = assertNotNull(DesktopOriginalListenCollectionQueueSelection.capture(
                f.queue, session, unresolved, { true }, { true }))
            assertTrue(receipt.matches(PlaybackRequest.create(unresolved.bvid, cid = 0L), 0L))
            assertTrue(f.queue.resolveSelectedCidIfCurrent(session, unresolved.bvid, 0L, 104L))
            assertFalse(receipt.isCurrent())
            val resolved = assertNotNull(f.queue.getCurrentItem())
            val fresh = assertNotNull(DesktopOriginalListenCollectionQueueSelection.capture(
                f.queue, session, resolved, { true }, { true }))
            assertTrue(fresh.matches(PlaybackRequest.create(resolved.bvid, cid = 104L), 0L))
        }
    }

    private fun item(bvid: String) = PlaylistItem(bvid, 101L, bvid,
        "https://media.invalid/cover.jpg", "Fixture UP", duration = 60L)

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) }
        finally { fixture.close() }
    }

    private class Fixture {
        private val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val directory = Files.createTempDirectory(temporaryRoot, "bilipai-collection-admission-")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val admission = Any()
        private val testThread = Thread.currentThread()
        private val failures = CopyOnWriteArrayList<Throwable>()
        var beforeMutation: (() -> Unit)? = null
        val queue = DesktopOriginalVideoPlaylistBinding(DesktopPluginStore(directory), scope,
            rootOwned = { true }, rootAdmission = { action ->
                synchronized(admission) {
                    if (Thread.currentThread() === testThread) {
                        val hook = beforeMutation
                        beforeMutation = null
                        hook?.invoke()
                    }
                    action()
                    true
                }
            }, onPersistenceFailure = { failures += it })

        suspend fun close() {
            queue.closeAndJoin()
            scope.cancel()
            require(directory.toAbsolutePath().normalize().parent == temporaryRoot)
            require(directory.fileName.toString().startsWith("bilipai-collection-admission-"))
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
            assertTrue(failures.isEmpty(), "Temporary original playlist persistence failed: $failures")
        }
    }
}
