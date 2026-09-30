package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.store.TodayWatchDislikedVideoSnapshot
import com.bilipai.desktop.backup.DesktopBackupArchive
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopDiscoveryRestoreLifecycleTest {
    @TempDir lateinit var directory: Path
    private fun video(name: String) = TodayWatchDislikedVideoSnapshot(name, name, "fixture", 7, 100)
    private fun lock(preferences: DesktopDiscoveryPreferences): Any = DesktopDiscoveryPreferences::class.java
        .getDeclaredField("lock").apply { isAccessible = true }.get(preferences)
    private fun files(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate {
            root.relativize(it).toString() to Files.readAllBytes(it).toList()
        }
    }

    @Test fun `actual archive restore rejects a blocked old account feedback writer`() {
        val root = directory.resolve("old")
        val old = DesktopDiscoveryPreferences(root)
        old.record(11, video("before"), emptySet(), false)
        old.feedback(22) // The current account differs from the already held writer's account.
        val incomingRoot = directory.resolve("incoming")
        DesktopDiscoveryPreferences(incomingRoot).record(11, video("restored"), emptySet(), false)
        val archive = DesktopBackupArchive(incomingRoot).create(123)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val queued = Thread({
            started.countDown()
            try { old.record(11, video("obsolete"), emptySet(), false) }
            catch (caught: Throwable) { failure.set(caught) }
        }, "owned-old-feedback-writer")
        lateinit var restored: Map<String, List<Byte>>
        synchronized(lock(old)) {
            queued.start()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (queued.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, queued.state)
            DesktopBackupArchive(root, old::freezeWritesForRestore).restore(archive)
            restored = files(root)
        }
        queued.join(3_000)
        assertFalse(queued.isAlive)
        assertIs<IllegalStateException>(failure.get())
        assertEquals(restored, files(root))
        assertEquals(setOf("restored"), DesktopDiscoveryPreferences(root).feedback(11).value.dislikedBvids)
    }

    @Test fun `restoration drains a writer accepted on the real backing monitor`(): Unit = runBlocking {
        val root = directory.resolve("accepted")
        val preferences = DesktopDiscoveryPreferences(root)
        preferences.record(11, video("before"), emptySet(), false)
        preferences.feedback(11).value
        val context = preferences.recommendationContext(11)
        val backing = DesktopPluginStore::class.java.getDeclaredField("backing")
            .apply { isAccessible = true }.get(context.store)
        supervisorScope {
            val worker = AtomicReference<Thread>()
            val retirementWorker = AtomicReference<Thread>()
            val started = CountDownLatch(1)
            val freezeStarted = CountDownLatch(1)
            lateinit var accepted: Deferred<Unit>
            lateinit var retired: Deferred<Unit>
            synchronized(backing) {
                accepted = async(Dispatchers.IO) {
                    worker.set(Thread.currentThread()); started.countDown()
                    preferences.record(11, video("accepted"), emptySet(), false)
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (worker.get().state != Thread.State.BLOCKED || worker.get().stackTrace.none {
                        it.className == DesktopPluginStore::class.java.name &&
                            (it.methodName == "update" || it.methodName.startsWith("update\u0024"))
                    }) {
                    check(System.nanoTime() < deadline) {
                        "Writer did not reach its actual atomic store monitor: " + worker.get().stackTrace.joinToString("\n")
                    }
                    Thread.sleep(1)
                }
                retired = async(Dispatchers.IO) {
                    retirementWorker.set(Thread.currentThread()); freezeStarted.countDown()
                    preferences.freezeWritesForRestore()
                }
                assertTrue(freezeStarted.await(3, TimeUnit.SECONDS))
                val freezeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (retirementWorker.get().state != Thread.State.BLOCKED ||
                    retirementWorker.get().stackTrace.none {
                        it.className == DesktopDiscoveryPreferences::class.java.name &&
                            it.methodName == "freezeWritesForRestore"
                    }) {
                    check(System.nanoTime() < freezeDeadline) { "Restore did not wait for the accepted Preferences write" }
                    Thread.sleep(1)
                }
                assertTrue(retirementWorker.get().stackTrace.none {
                    it.className == DesktopPluginStore::class.java.name && it.methodName.startsWith("freezeWrites")
                })
                assertFalse(retired.isCompleted)
            }
            withTimeout(3_000) { accepted.await(); retired.await() }
        }
        val beforeLate = files(root)
        assertFailsWith<IllegalStateException> { preferences.record(11, video("late"), emptySet(), false) }
        assertEquals(beforeLate, files(root))
        assertEquals(setOf("before", "accepted"), DesktopDiscoveryPreferences(root).feedback(11).value.dislikedBvids)
    }

    @Test fun `existing same root peers are retired while a new generation stays writable`(): Unit = runBlocking {
        val root = directory.resolve("peers")
        val first = DesktopDiscoveryPreferences(root)
        val peer = DesktopDiscoveryPreferences(root)
        first.record(11, video("first"), emptySet(), false)
        peer.record(44, video("peer"), emptySet(), false)
        first.freezeWritesForRestore()
        val beforeLate = files(root)
        assertFailsWith<IllegalStateException> { peer.record(44, video("late"), emptySet(), false) }
        assertFailsWith<IllegalStateException> { peer.record(99, video("new-old-scope"), emptySet(), false) }
        assertFailsWith<IllegalStateException> { peer.feedback(99) }
        assertEquals(beforeLate, files(root))
        val fresh = DesktopDiscoveryPreferences(root)
        assertEquals(setOf("peer"), fresh.feedback(44).value.dislikedBvids)
        fresh.setRefreshCount(30)
        first.freezeWritesForRestore() // An idempotent old callback must not freeze the fresh generation.
        peer.freezeWritesForRestore()
        fresh.record(99, video("fresh"), emptySet(), false)
        assertEquals(30, fresh.refreshCount.value)
        assertEquals(setOf("fresh"), fresh.feedback(99).value.dislikedBvids)
    }

    @Test fun `all local preference operations and global blacklist reject retired generation writes`(): Unit = runBlocking {
        val root = directory.resolve("operations")
        val preferences = DesktopDiscoveryPreferences(root)
        preferences.record(null, video("guest"), emptySet(), true)
        preferences.freezeWritesForRestore()
        val beforeLate = files(root)
        assertFailsWith<IllegalStateException> { preferences.setFeedMode(DesktopRecommendationMode.MOBILE) }
        assertFailsWith<IllegalStateException> { preferences.setRefreshCount(30) }
        assertFailsWith<IllegalStateException> { preferences.clearFeedback(null) }
        assertFailsWith<IllegalStateException> { preferences.changeBlocked(null, 7, false) }
        assertFailsWith<IllegalStateException> { preferences.blockedUps.upsert(BlockedUp(9, "late", "")) }
        assertFailsWith<IllegalStateException> { preferences.recommendationContext(55) }
        assertEquals(beforeLate, files(root))
        assertEquals(setOf(7L), preferences.blockedCreators(null).value)
    }

    @Test fun `actual repository delegates restoration to all held preference scopes`() {
        val root = directory.resolve("repository")
        val preferences = DesktopDiscoveryPreferences(root)
        preferences.record(11, video("held"), emptySet(), false)
        val repository = DesktopRepository(DesktopSessionStore(root.resolve("session.json"), persistent = false))
        val discovery = DesktopDiscoveryRepository(repository, preferences)
        discovery.freezeWritesForRestore()
        assertFailsWith<IllegalStateException> { preferences.record(11, video("late"), emptySet(), false) }
        assertFailsWith<IllegalStateException> { preferences.record(22, video("late-other"), emptySet(), false) }
    }
}
