package com.bilipai.desktop.data

import com.android.purebilibili.core.database.entity.SearchHistory
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopSearchRestoreLifecycleTest {
    @Test fun `freeze denies every cached writer and new account while history remains readable`(): Unit = runBlocking {
        directory { root ->
            val preferences = DesktopSearchPreferences(root)
            preferences.record(null, "global"); preferences.record(7, "account")
            preferences.setSuggestionsEnabled(false)
            val before = files(root)
            preferences.freezeWritesForRestore(); preferences.freezeWritesForRestore()
            assertEquals(listOf("global"), preferences.history(null).value.map { it.keyword })
            assertEquals(listOf("account"), preferences.history(7).value.map { it.keyword })
            assertFailsWith<IllegalStateException> { preferences.record(7, "late") }
            assertFailsWith<IllegalStateException> { preferences.delete(7, SearchHistory("account")) }
            assertFailsWith<IllegalStateException> { preferences.clear(7) }
            assertFailsWith<IllegalStateException> { preferences.setPrivacyMode(true) }
            assertFailsWith<IllegalStateException> { preferences.setSuggestionsEnabled(true) }
            assertFailsWith<IllegalStateException> { preferences.record(99, "new account") }
            assertFailsWith<IllegalStateException> { preferences.history(99) }
            assertFalse(preferences.privacyMode.value)
            assertFalse(preferences.suggestionsEnabled.value)
            assertEquals(before, files(root))
            assertFalse(Files.exists(root.resolve("accounts/99")))
        }
    }

    @Test fun `held peer stores cannot overwrite restoration but a fresh facade loads its new generation`(): Unit = runBlocking {
        directory { root ->
            val preferences = DesktopSearchPreferences(root)
            preferences.record(null, "before"); preferences.record(7, "before account")
            val globalPeer = DesktopPluginContext(DesktopPluginStore(root.resolve("search")))
            val accountPeer = DesktopPluginContext(DesktopPluginStore(root.resolve("accounts/7/search")))
            preferences.freezeWritesForRestore()
            assertFailsWith<IllegalStateException> { globalPeer.getSharedPreferences("privacy_mode", 0).edit().putBoolean("enabled", true).apply() }
            assertFailsWith<IllegalStateException> { accountPeer.getSharedPreferences("search_history", 0).edit().putString("entries", "[]").apply() }
            // Simulate the archive's actual file replacement before constructing the new facade.
            Files.writeString(root.resolve("search/plugin-settings.json"),
                """{"privacy_mode":{"enabled":true},"search_history":{"entries":"[{\"keyword\":\"restored\",\"timestamp\":1}]"}}""")
            val restored = DesktopSearchPreferences(root)
            assertTrue(restored.privacyMode.value)
            assertEquals(listOf("restored"), restored.history(null).value.map { it.keyword })
            restored.setSuggestionsEnabled(false) // new backing is writable and retains restored namespaces
            val restoredBytes = files(root)
            assertFailsWith<IllegalStateException> { preferences.setPrivacyMode(false) }
            assertEquals(restoredBytes, files(root))
            assertTrue(preferences.isPrivacyModeEnabledSync()) // fresh authoritative disk reader
            assertEquals(listOf("before"), preferences.history(null).value.map { it.keyword })
            assertEquals(listOf("restored"), restored.history(null).value.map { it.keyword })
        }
    }

    @Test fun `a queued IO write is refused after the owning generation freezes`(): Unit = runBlocking {
        directory { root ->
            val preferences = DesktopSearchPreferences(root)
            preferences.record(null, "before")
            val before = files(root)
            val lock = DesktopSearchPreferences::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(preferences)
            supervisorScope {
                val started = CountDownLatch(1)
                lateinit var queued: Deferred<Unit>
                synchronized(lock) {
                    queued = async(Dispatchers.IO) { started.countDown(); preferences.record(null, "queued") }
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    preferences.freezeWritesForRestore()
                }
                assertFailsWith<IllegalStateException> { queued.await() }
            }
            assertEquals(before, files(root))
            assertEquals(listOf("before"), preferences.history(null).value.map { it.keyword })
        }
    }

    @Test fun `freeze waits for an accepted disk operation before rejecting subsequent writes`(): Unit = runBlocking {
        directory { root ->
            val preferences = DesktopSearchPreferences(root)
            preferences.record(null, "before")
            val global = DesktopSearchPreferences::class.java.getDeclaredField("global").apply { isAccessible = true }
                .get(preferences) as DesktopPluginContext
            val backing = DesktopPluginStore::class.java.getDeclaredField("backing").apply { isAccessible = true }.get(global.store)
            supervisorScope {
                val worker = AtomicReference<Thread>(); val started = CountDownLatch(1); val freezeStarted = CountDownLatch(1)
                lateinit var accepted: Deferred<Unit>; lateinit var frozen: Deferred<Unit>
                synchronized(backing) {
                    accepted = async(Dispatchers.IO) { worker.set(Thread.currentThread()); started.countDown(); preferences.record(null, "accepted") }
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (worker.get().state != Thread.State.BLOCKED || worker.get().stackTrace.none { it.className == DesktopPluginStore::class.java.name }) {
                        check(System.nanoTime() < deadline) { "Accepted writer did not reach its real store monitor" }
                        Thread.sleep(5)
                    }
                    // The writer holds preferences.lock while waiting on the real store monitor.
                    frozen = async(Dispatchers.IO) { freezeStarted.countDown(); preferences.freezeWritesForRestore() }
                    assertTrue(freezeStarted.await(3, TimeUnit.SECONDS))
                    assertFalse(frozen.isCompleted)
                }
                withTimeout(3_000) { accepted.await(); frozen.await() }
            }
            assertEquals(listOf("accepted", "before"), preferences.history(null).value.map { it.keyword })
            val beforeLate = files(root)
            assertFailsWith<IllegalStateException> { preferences.record(null, "late") }
            assertEquals(beforeLate, files(root))
        }
    }

    private fun files(root: Path): Map<String, List<Byte>> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
    }
    private suspend fun directory(block: suspend (Path) -> Unit) {
        val root = Files.createTempDirectory("bilipai-search-restore-lifecycle-").toAbsolutePath().normalize()
        try { block(root) } finally {
            check(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(root.fileName.toString().startsWith("bilipai-search-restore-lifecycle-"))
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
            } }
        }
    }
}
