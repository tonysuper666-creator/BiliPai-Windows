package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.update.DesktopVeyraReleaseMonitor
import com.bilipai.desktop.update.UpdateState
import com.bilipai.desktop.update.followDesktopAutomaticUpdateChecks
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The About 自动检查更新 switch calls bindings.update { preferences.setAutoCheck(value) }. These tests pin that the key
 * that call writes is the key the Windows consumers read. The key-level behaviour of the update loop is covered by
 * DesktopUpdateMonitoringTest and the setter's own write rules by DesktopOriginalAboutPreferencesTest. */
class DesktopAboutAutoCheckConsumerTest {
    private class Fixture(owns: () -> Boolean = { true }) {
        val store = DesktopPluginStore(Files.createTempDirectory("bp-about-consumers-"))
        val context = DesktopPluginContext(store)
        val prefs = DesktopOriginalAboutPreferences(context, owns, { it(); true })
        val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(SupervisorJob())
        val bindings = DesktopOriginalAboutBindings(prefs, scope, owns) { failures += it }

        /** The call a switch makes; returns once the launched write has finished. */
        suspend fun write(action: suspend DesktopOriginalAboutPreferences.() -> Unit) {
            bindings.update { prefs.action() }
            scope.coroutineContext[Job]!!.children.toList().joinAll()
        }
        fun close() = scope.cancel()
    }

    // Real time and a short interval on purpose: Fixture.write waits for a real IO write, which must not run
    // inside runTest while the loop's six-hour virtual delay is pending (the scheduler would keep skipping it).
    @Test fun `About auto-check switch stops and resumes the Windows update loop`(): Unit = runBlocking {
        val f = Fixture()
        val checks = AtomicInteger()
        f.write { setAutoCheck(false) }
        val loop = launch(Dispatchers.Default) {
            followDesktopAutomaticUpdateChecks(f.store, { true }, { checks.incrementAndGet() },
                restorePrepared = {}, intervalMs = 50)
        }
        try {
            delay(300)
            assertEquals(0, checks.get(), "update loop checked while the About switch was off")
            f.write { setAutoCheck(true) }
            withTimeout(10_000) { while (checks.get() == 0) delay(10) }
            f.write { setAutoCheck(false) }
            delay(150)
            val settled = checks.get()
            delay(300)
            assertEquals(settled, checks.get(), "update loop kept checking after the About switch was turned off")
            assertTrue(f.failures.isEmpty())
        } finally { loop.cancelAndJoin(); f.close() }
    }

    @Test fun `About auto-check switch gates the Veyra release monitor`(): Unit = runBlocking {
        val f = Fixture()
        val firstFetch = CompletableDeferred<String>()
        val monitor = DesktopVeyraReleaseMonitor(fetch = { url -> firstFetch.complete(url); "[]" },
            store = f.store, owns = { true }, windowsState = { UpdateState.Idle }, compatibleCatalog = { null })
        f.write { setAutoCheck(false) }
        val follower = launch(Dispatchers.Default) { monitor.followSettings() }
        try {
            delay(300)
            assertFalse(firstFetch.isCompleted, "Veyra monitor fetched while the About switch was off")
            f.write { setAutoCheck(true) }
            assertTrue("/releases?" in withTimeout(10_000) { firstFetch.await() })
            assertTrue(f.failures.isEmpty())
        } finally { follower.cancelAndJoin(); f.close() }
    }

    @Test fun `the startup metadata consumer reads the key the About switch writes`(): Unit = runBlocking {
        val f = Fixture()
        try {
            // DesktopOriginalAboutAutomaticMetadata builds its own DesktopOriginalAboutPreferences on the same Store.
            val startup = DesktopOriginalAboutPreferences(f.context, { true }, { it(); true })
            assertTrue(startup.initialAutoCheck)
            f.write { setAutoCheck(false) }
            assertEquals(JsonPrimitive(false), f.store.preferences("settings")["auto_check_app_update"])
            assertFalse(startup.initialAutoCheck); assertFalse(startup.autoCheck.first())
            f.write { setAutoCheck(true) }
            assertEquals(JsonPrimitive(true), f.store.preferences("settings")["auto_check_app_update"])
            assertTrue(startup.initialAutoCheck); assertTrue(startup.autoCheck.first())
            assertTrue(f.failures.isEmpty())
        } finally { f.close() }
    }

    @Test fun `a retired About page never launches the switch write`(): Unit = runBlocking {
        val f = Fixture(owns = { false })
        try {
            f.write { setAutoCheck(false) }
            assertTrue(f.store.preferences("settings").isEmpty())
            assertFalse(Files.exists(f.store.root.resolve("plugin-settings.json")))
            assertTrue(f.failures.isEmpty())
        } finally { f.close() }
    }
}
