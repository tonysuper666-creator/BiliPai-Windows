package com.bilipai.desktop.update

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopUpdateMonitoringTest {
    private fun store() = DesktopPluginStore(Files.createTempDirectory("bp-update-monitor-"))
    private fun target(asset: Long = 42) = WindowsUpdate("windows-0.3.3+1", 41, asset,
        "BiliPai-Windows.zip", 100, "https://github.com/tonysuper666-creator/BiliPai-Windows/releases/download/windows-0.3.3+1/BiliPai-Windows.zip",
        "https://github.com/tonysuper666-creator/BiliPai-Windows/releases/download/windows-0.3.3+1/BiliPai-Windows.zip.sha256",
        "https://github.com/tonysuper666-creator/BiliPai-Windows/releases/tag/windows-0.3.3+1")
    private val commit = "a".repeat(40)
    private val tagObject = "b".repeat(40)
    private fun release(url: String): String = when {
        "/releases?" in url -> """[{"id":7,"tag_name":"v1.1-alpha","published_at":"2026-10-09T00:00:00Z","prerelease":true,"draft":false,"body":"native changes"}]"""
        "/git/ref/tags/" in url -> """{"object":{"type":"tag","sha":"$tagObject"}}"""
        "/git/tags/" in url -> """{"object":{"type":"commit","sha":"$commit"}}"""
        else -> error("Unexpected upstream request")
    }

    @Test fun `bad own catalog preserves upstream annotated tag evidence and catalog-only retry`() = runBlocking {
        val store = store(); var now = 1_000L; var upstreamCalls = 0; var catalogCalls = 0
        val update = target()
        val monitor = DesktopVeyraReleaseMonitor(fetch = { upstreamCalls++; release(it) },
            store = store, owns = { true }, windowsState = { UpdateState.Available(update) },
            compatibleCatalog = { catalogCalls++; if (catalogCalls == 1) error("bad signature") else null }, nowMs = { now })
        monitor.check()
        assertEquals(commit, monitor.state.value.latestPublished?.sourceCommit)
        assertTrue(monitor.state.value.latestPublished?.prerelease == true)
        assertNull(monitor.state.value.error); assertEquals("bad signature", monitor.state.value.catalogError)
        assertNull(monitor.state.value.compatible)
        val accepted = store.preferences("veyra_release_tracking")
        assertEquals(commit, accepted.getValue("latestPublished").jsonObject.getValue("sourceCommit").jsonPrimitive.content)
        assertEquals(1, accepted.getValue("observedIdentities").jsonArray.size)
        assertEquals(3, upstreamCalls); assertEquals(1, catalogCalls)
        now += DesktopVeyraReleaseMonitor.CATALOG_RETRY_MS - 1
        monitor.check()
        assertEquals(3, upstreamCalls); assertEquals(1, catalogCalls)
        now++
        monitor.check()
        assertEquals(3, upstreamCalls); assertEquals(2, catalogCalls)
        assertNull(monitor.state.value.catalogError)
        assertEquals(accepted, store.preferences("veyra_release_tracking"))
        val restored = DesktopVeyraReleaseMonitor(fetch = { error("ten-hour clock must not fetch") },
            store = store, owns = { true }, windowsState = { UpdateState.Idle }, compatibleCatalog = { null }, nowMs = { now })
        restored.check()
        assertEquals(commit, restored.state.value.latestPublished?.sourceCommit)
    }

    @Test fun `explicit catalog retry bypasses retry throttle without fetching upstream`() = runBlocking {
        val update = target(); var upstreamCalls = 0; var catalogs = 0
        val monitor = DesktopVeyraReleaseMonitor(fetch = { upstreamCalls++; release(it) },
            store = store(), owns = { true }, windowsState = { UpdateState.Available(update) },
            compatibleCatalog = { catalogs++; error("bad catalog") }, nowMs = { 1_000L })
        monitor.check()
        assertFailsWith<IllegalStateException> { monitor.compatibleForUpdate(update) }
        assertEquals(2, catalogs); assertEquals(3, upstreamCalls)
        assertNull(monitor.state.value.compatible)
        assertEquals(commit, monitor.state.value.latestPublished?.sourceCommit)
    }

    @Test fun `target changed during lookup clears compatibility without invalidating source evidence`() = runBlocking {
        var windows: UpdateState = UpdateState.Available(target())
        val monitor = DesktopVeyraReleaseMonitor(fetch = { release(it) }, store = store(), owns = { true },
            windowsState = { windows }, compatibleCatalog = { windows = UpdateState.Available(target(99)); null }, nowMs = { 1_000L })
        monitor.check()
        assertEquals(commit, monitor.state.value.latestPublished?.sourceCommit)
        assertNull(monitor.state.value.compatible)
        assertNull(monitor.state.value.catalogError); assertNull(monitor.state.value.error)
    }

    @Test fun `catalog cancellation propagates and keeps only already accepted upstream evidence`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val store = store()
        val monitor = DesktopVeyraReleaseMonitor(fetch = { release(it) }, store = store, owns = { true },
            windowsState = { UpdateState.Available(target()) }, compatibleCatalog = { entered.complete(Unit); awaitCancellation() }, nowMs = { 1_000L })
        val request = async { monitor.check() }
        entered.await(); request.cancel()
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(commit, monitor.state.value.latestPublished?.sourceCommit)
        assertNull(monitor.state.value.compatible); assertNull(monitor.state.value.catalogError)
        assertFalse(monitor.state.value.checking)
        assertTrue("lastSuccessMs" in store.preferences("veyra_release_tracking"))
    }

    @Test fun `default enabled background check is cancelled on durable opt-out and resumes on opt-in`() = runTest {
        val store = store(); var calls = 0; var cancellations = 0
        store.update("veyra_release_tracking", mapOf("lastAttemptMs" to JsonPrimitive(123)))
        val originalTracking = store.preferences("veyra_release_tracking")
        val job = backgroundScope.launch {
            followDesktopAutomaticUpdateChecks(store, { true }, {
                calls++
                try { awaitCancellation() } finally { cancellations++ }
            })
        }
        runCurrent(); assertEquals(1, calls)
        store.update("settings", mapOf("auto_check_app_update" to JsonPrimitive(false)))
        runCurrent(); assertEquals(1, cancellations); assertEquals(1, calls)
        store.update("settings", mapOf("auto_check_app_update" to JsonPrimitive(true)))
        runCurrent(); assertEquals(2, calls)
        assertEquals(originalTracking, store.preferences("veyra_release_tracking"))
        job.cancelAndJoin()
    }

    @Test fun `Windows own-release clock stays six hours and disabled interval does not check`() = runTest {
        val store = store(); var calls = 0
        val job = backgroundScope.launch { followDesktopAutomaticUpdateChecks(store, { true }, { calls++ }) }
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(DesktopUpdater.AUTO_CHECK_INTERVAL_MS - 1); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        store.update("settings", mapOf("auto_check_app_update" to JsonPrimitive(false)))
        runCurrent(); advanceTimeBy(DesktopUpdater.AUTO_CHECK_INTERVAL_MS * 2); runCurrent(); assertEquals(2, calls)
        assertEquals(10 * 60 * 60 * 1000L, DesktopVeyraReleaseMonitor.INTERVAL_MS)
        job.cancelAndJoin()
    }
}
