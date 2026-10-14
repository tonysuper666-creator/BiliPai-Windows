package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
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
