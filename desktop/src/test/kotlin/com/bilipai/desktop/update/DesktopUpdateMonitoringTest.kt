package com.bilipai.desktop.update

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import okhttp3.ResponseBody.Companion.toResponseBody
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

    @Test fun `cancelled accepted catalog retries after throttle without refetching upstream`() = runBlocking {
        val store = store(); val update = target()
        var now = 1_000L; var upstreamCalls = 0; var catalogCalls = 0
        // Verify a real signed offer; neither the accepted cache nor its retry flag is modelled here.
        val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = buildJsonObject {
            put("schema", 1); put("repository", "tonysuper666-creator/BiliPai-Windows")
            put("version", update.version); put("releaseId", update.releaseId); put("assetId", update.assetId)
            put("assetName", update.assetName); put("size", update.size); put("downloadUrl", update.downloadUrl)
            put("sha256", "c".repeat(64)); put("sourceRepository", "Likely7/Veyra-NRVideo")
            put("sourceCommit", commit); put("adapterBuildId", "accepted-monitor-regression")
            put("engineProtocolMajor", 1); put("compatibilityStatus", "VALIDATED_FULL_APPLICATION_BUNDLE")
        }.toString().toByteArray(Charsets.UTF_8)
        val signature = java.security.Signature.getInstance("Ed25519").apply {
            initSign(keys.private); update(payload)
        }.sign()
        val envelope = buildJsonObject {
            put("schema", 1); put("keyId", "monitor-regression-key")
            put("payloadBase64", java.util.Base64.getEncoder().encodeToString(payload))
            put("signatureBase64", java.util.Base64.getEncoder().encodeToString(signature))
        }.toString()
        val offer = VerifiedVeyraCompatibleOffer.verify(envelope,
            mapOf("monitor-regression-key" to keys.public.encoded), "tonysuper666-creator/BiliPai-Windows", update)
        val monitor = DesktopVeyraReleaseMonitor(fetch = { upstreamCalls++; release(it) },
            store = store, owns = { true }, windowsState = { UpdateState.Available(update) },
            compatibleCatalog = { assertEquals(update, it); catalogCalls++; offer }, nowMs = { now })
        val accepted = CompletableDeferred<VerifiedVeyraCompatibleOffer>()
        val request = async(start = CoroutineStart.LAZY) { monitor.check() }
        // The real StateFlow publishes its cached offer before the final current() check.
        // Unconfined observation cancels that actual caller inline at this publication boundary.
        val observation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            monitor.state.first { state ->
                state.compatible?.let {
                    accepted.complete(it)
                    request.cancel()
                    true
                } ?: false
            }
        }
        try {
            request.start()
            assertSame(offer, withTimeout(5_000L) { accepted.await() })
            withTimeout(5_000L) { request.join() }
            assertTrue(request.isCancelled)
            assertFailsWith<CancellationException> { request.await() }
            assertNull(monitor.state.value.compatible)
            assertNull(monitor.state.value.catalogError); assertNull(monitor.state.value.error)
            assertFalse(monitor.state.value.checking)
            assertEquals(commit, monitor.state.value.latestPublished?.sourceCommit)
            assertEquals(3, upstreamCalls); assertEquals(1, catalogCalls)
            val evidence = store.preferences("veyra_release_tracking")
            assertTrue("lastSuccessMs" in evidence)

            now += DesktopVeyraReleaseMonitor.CATALOG_RETRY_MS - 1
            monitor.check() // Non-force, still inside both the catalog and upstream debounce.
            assertEquals(1, catalogCalls); assertEquals(3, upstreamCalls)
            assertNull(monitor.state.value.compatible)
            now++
            monitor.check() // The same target must retry at the existing fifteen-minute boundary.
            assertEquals(2, catalogCalls); assertEquals(3, upstreamCalls)
            assertSame(offer, monitor.state.value.compatible)
            assertNull(monitor.state.value.catalogError)
            assertEquals(evidence, store.preferences("veyra_release_tracking"))
        } finally {
            observation.cancelAndJoin()
            request.cancelAndJoin()
        }
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

    private fun clockUpdater(root: java.nio.file.Path, client: okhttp3.OkHttpClient): DesktopUpdater =
        DesktopUpdater.forIntegrationTest("0.3.3+1", "tonysuper666-creator/BiliPai-Windows",
            root.resolve("BiliPai/updates"), client, root, { error("A clock check must never launch an EXE") })

    @Test fun `restart five hours after a durable check sleeps only the remaining hour`() = runBlocking {
        val root = Files.createTempDirectory("bp-update-clock-")
        val updateRoot = UpdateStorage.verifiedRoot(root.resolve("BiliPai/updates"))
        val checkedAt = System.currentTimeMillis() - 5 * 60 * 60 * 1000L
        Files.writeString(updateRoot.resolve("last-check.txt"), checkedAt.toString())
        var networkCalls = 0
        val updater = clockUpdater(root, okhttp3.OkHttpClient.Builder().addInterceptor {
            networkCalls++; error("Debounced restart must not fetch a release")
        }.build())
        assertIs<UpdateState.Idle>(updater.autoCheck())
        assertEquals(0, networkCalls)
        assertEquals(60 * 60 * 1000L, updater.automaticCheckDelayMs(checkedAt + 5 * 60 * 60 * 1000L))
        assertEquals(60_000L, updater.automaticCheckDelayMs(checkedAt - 1))
        assertEquals(checkedAt.toString(), Files.readString(updateRoot.resolve("last-check.txt")))
    }

    @Test fun `background owner uses the durable remaining deadline then returns to six hours`() = runTest {
        val root = Files.createTempDirectory("bp-update-clock-owner-")
        val updateRoot = UpdateStorage.verifiedRoot(root.resolve("BiliPai/updates"))
        val checkedAt = 10_000_000L
        val checkpoint = updateRoot.resolve("last-check.txt")
        Files.writeString(checkpoint, checkedAt.toString())
        val updater = clockUpdater(root, okhttp3.OkHttpClient())
        var now = checkedAt + 5 * 60 * 60 * 1000L
        var calls = 0
        val job = backgroundScope.launch {
            followDesktopAutomaticUpdateChecks(store(), { true }, {
                calls++
                // Model a successful check checkpoint after the skipped restart.
                if (calls > 1) Files.writeString(checkpoint, now.toString())
            }, nextDelayMs = { updater.automaticCheckDelayMs(now) })
        }
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(60 * 60 * 1000L - 1); now += 60 * 60 * 1000L - 1
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); now++; runCurrent(); assertEquals(2, calls)
        advanceTimeBy(DesktopUpdater.AUTO_CHECK_INTERVAL_MS - 1); now += DesktopUpdater.AUTO_CHECK_INTERVAL_MS - 1
        runCurrent(); assertEquals(2, calls)
        advanceTimeBy(1); now++; runCurrent(); assertEquals(3, calls)
        job.cancelAndJoin()
    }

    @Test fun `failed due check retains success checkpoint and waits six hours without a hot retry`() = runBlocking {
        val root = Files.createTempDirectory("bp-update-clock-failure-")
        val updateRoot = UpdateStorage.verifiedRoot(root.resolve("BiliPai/updates"))
        val checkedAt = System.currentTimeMillis() - DesktopUpdater.AUTO_CHECK_INTERVAL_MS - 1_000L
        val checkpoint = updateRoot.resolve("last-check.txt")
        Files.writeString(checkpoint, checkedAt.toString())
        var networkCalls = 0
        val updater = clockUpdater(root, okhttp3.OkHttpClient.Builder().addInterceptor {
            networkCalls++; throw java.io.IOException("fixture release request failed")
        }.build())
        assertIs<UpdateState.Failed>(updater.autoCheck())
        assertEquals(1, networkCalls)
        assertEquals(checkedAt.toString(), Files.readString(checkpoint))
        assertEquals(DesktopUpdater.AUTO_CHECK_INTERVAL_MS, updater.automaticCheckDelayMs())
    }

    private val ownReleaseRepository = "tonysuper666-creator/BiliPai-Windows"
    private fun ownCoreRows(firstId: Long, count: Int = 100): List<JsonElement> = List(count) { index ->
        val id = firstId + index
        buildJsonObject {
            put("id", id); put("tag_name", "rtx-source-$id")
            put("html_url", "https://github.com/$ownReleaseRepository/releases/tag/rtx-source-$id")
            put("prerelease", true); put("draft", false); put("assets", buildJsonArray {})
        }
    }
    private fun ownWindowsRelease(id: Long, version: String): JsonObject = buildJsonObject {
        val tag = "Windows-v$version"
        val asset = "BiliPai-Windows-$version.zip"
        val prefix = "https://github.com/$ownReleaseRepository/releases/download/$tag"
        put("id", id); put("tag_name", tag)
        put("html_url", "https://github.com/$ownReleaseRepository/releases/tag/$tag")
        put("prerelease", true); put("draft", false)
        put("assets", buildJsonArray {
            add(buildJsonObject {
                put("id", id * 10); put("name", asset); put("size", 100)
                put("browser_download_url", "$prefix/$asset")
            })
            add(buildJsonObject {
                put("id", id * 10 + 1); put("name", "$asset.sha256"); put("size", 64)
                put("browser_download_url", "$prefix/$asset.sha256")
            })
        })
    }
    private fun ownPage(page: Int): String {
        val rows = ownCoreRows((page - 1) * 100L + 1).toMutableList()
        if (page == 1) rows[0] = ownWindowsRelease(1, "0.3.3+1")
        return JsonArray(rows).toString()
    }
    private fun ownResponse(chain: okhttp3.Interceptor.Chain, body: String, code: Int = 200): okhttp3.Response {
        val request = chain.request()
        assertEquals("GET", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("api.github.com", request.url.host)
        assertEquals("/repos/$ownReleaseRepository/releases", request.url.encodedPath)
        assertEquals("100", request.url.queryParameter("per_page"))
        return okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody()).build()
    }
    private data class OwnCheckFixture(val updater: DesktopUpdater, val updateRoot: java.nio.file.Path,
        val checkpointText: String, val activeText: String, val currentExe: java.nio.file.Path, val previousExe: java.nio.file.Path)
    private fun ownCheckFixture(client: okhttp3.OkHttpClient): OwnCheckFixture {
        val root = Files.createTempDirectory("bp-own-release-pages-")
        val updateRoot = UpdateStorage.verifiedRoot(root.resolve("BiliPai/updates"))
        val current = UpdateStorage.createStage(updateRoot, ownReleaseRepository, 700_001)
        val previous = UpdateStorage.createStage(updateRoot, ownReleaseRepository, 700_002)
        val currentExe = Files.writeString(Files.createDirectory(current.resolve("app")).resolve("BiliPai Windows.exe"), "current retained")
        val previousExe = Files.writeString(Files.createDirectory(previous.resolve("app")).resolve("BiliPai Windows.exe"), "previous retained")
        val active = buildJsonObject {
            put("repository", ownReleaseRepository); put("version", "0.3.3+1"); put("executablePath", currentExe.toString())
            put("previousVersion", "0.3.3+0"); put("previousExecutablePath", previousExe.toString())
        }.toString()
        Files.writeString(updateRoot.resolve("active-install.json"), active)
        val checkedAt = (System.currentTimeMillis() - DesktopUpdater.AUTO_CHECK_INTERVAL_MS - 1_000L).toString()
        Files.writeString(updateRoot.resolve("last-check.txt"), checkedAt)
        return OwnCheckFixture(clockUpdater(root, client), updateRoot, checkedAt, active, currentExe, previousExe)
    }
    private fun assertOwnInstallRetained(fixture: OwnCheckFixture) {
        assertEquals(fixture.activeText, Files.readString(fixture.updateRoot.resolve("active-install.json")))
        assertEquals("current retained", Files.readString(fixture.currentExe))
        assertEquals("previous retained", Files.readString(fixture.previousExe))
    }

    @Test fun `manual own update finds a higher Windows version after three full mixed pages`() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val fixture = ownCheckFixture(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val page = requireNotNull(chain.request().url.queryParameter("page")).toInt(); requests += page
            ownResponse(chain, if (page <= 3) ownPage(page) else {
                assertEquals(4, page); JsonArray(listOf(ownWindowsRelease(301, "0.3.3+2"))).toString()
            })
        }.build())
        val update = assertIs<UpdateState.Available>(fixture.updater.check()).update
        assertEquals("Windows-v0.3.3+2", update.version); assertEquals(301L, update.releaseId)
        assertEquals(listOf(1, 2, 3, 4), requests.toList())
        assertNotEquals(fixture.checkpointText, Files.readString(fixture.updateRoot.resolve("last-check.txt")))
        assertOwnInstallRetained(fixture)
    }

    @Test fun `a full own release page requires its empty terminal page before reporting current`() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val fixture = ownCheckFixture(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val page = requireNotNull(chain.request().url.queryParameter("page")).toInt(); requests += page
            ownResponse(chain, when (page) { 1 -> ownPage(1); 2 -> "[]"; else -> error("Unexpected page") })
        }.build())
        assertIs<UpdateState.UpToDate>(fixture.updater.check())
        assertEquals(listOf(1, 2), requests.toList()); assertOwnInstallRetained(fixture)
    }

    @Test fun `bad late own release pages retain checkpoint and installs without publishing a partial candidate`() = runBlocking {
        val cases = listOf(
            200 to "{\"message\":\"invalid page\"}",
            200 to JsonArray(ownCoreRows(1, 1)).toString(),
            200 to JsonArray(ownCoreRows(501, 101)).toString(),
            503 to "[]",
        )
        for ((code, body) in cases) {
            val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val fixture = ownCheckFixture(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val page = requireNotNull(chain.request().url.queryParameter("page")).toInt(); requests += page
                if (page <= 3) ownResponse(chain, ownPage(page))
                else { assertEquals(4, page); ownResponse(chain, body, code) }
            }.build())
            assertIs<UpdateState.Failed>(fixture.updater.autoCheck())
            assertEquals(listOf(1, 2, 3, 4), requests.toList())
            assertEquals(fixture.checkpointText, Files.readString(fixture.updateRoot.resolve("last-check.txt")))
            assertEquals(DesktopUpdater.AUTO_CHECK_INTERVAL_MS, fixture.updater.automaticCheckDelayMs())
            assertOwnInstallRetained(fixture)
        }
    }

    @Test fun `full own release pages exhausting budget never checkpoint a partial current version`() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val fixture = ownCheckFixture(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val page = requireNotNull(chain.request().url.queryParameter("page")).toInt(); requests += page
            ownResponse(chain, ownPage(page))
        }.build())
        assertIs<UpdateState.Failed>(fixture.updater.check())
        assertEquals((1..DesktopUpdater.MAX_RELEASE_PAGES).toList(), requests.toList())
        assertEquals(fixture.checkpointText, Files.readString(fixture.updateRoot.resolve("last-check.txt")))
        assertOwnInstallRetained(fixture)
    }

    @Test fun `cancelling a late own release response restores prior state checkpoint and both installs`() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val paged = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fixture = ownCheckFixture(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val page = requireNotNull(chain.request().url.queryParameter("page")).toInt(); requests += page
            val body = if (!paged.get()) JsonArray(listOf(ownWindowsRelease(1, "0.3.3+1"))).toString()
                else if (page <= 3) ownPage(page) else {
                    assertEquals(4, page); entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); "[]"
                }
            ownResponse(chain, body)
        }.build())
        val previousState = assertIs<UpdateState.UpToDate>(fixture.updater.check())
        val checkpoint = Files.readString(fixture.updateRoot.resolve("last-check.txt"))
        requests.clear(); paged.set(true)
        val request = async { fixture.updater.check() }
        try {
            withContext(Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            request.cancel(); release.countDown(); request.join()
            assertEquals(previousState, fixture.updater.state.value)
            assertEquals(checkpoint, Files.readString(fixture.updateRoot.resolve("last-check.txt")))
            assertEquals(listOf(1, 2, 3, 4), requests.toList()); assertOwnInstallRetained(fixture)
        } finally { release.countDown(); request.cancelAndJoin() }
    }

    @Test fun `a late ordinary response error with cancellation retains prior updater state and checkpoint`() = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val paged = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fixture = ownCheckFixture(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val page = requireNotNull(chain.request().url.queryParameter("page")).toInt(); requests += page
            if (!paged.get()) ownResponse(chain, JsonArray(listOf(ownWindowsRelease(1, "0.3.3+1"))).toString())
            else if (page <= 3) ownResponse(chain, ownPage(page)) else {
                assertEquals(4, page); entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                ownResponse(chain, "{invalid response}", 503)
            }
        }.build())
        val previousState = assertIs<UpdateState.UpToDate>(fixture.updater.check())
        val checkpoint = Files.readString(fixture.updateRoot.resolve("last-check.txt"))
        requests.clear(); paged.set(true)
        val request = async { fixture.updater.check() }
        try {
            withContext(Dispatchers.IO) { check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            request.cancel(); release.countDown(); request.join()
            assertTrue(request.isCancelled)
            assertEquals(previousState, fixture.updater.state.value)
            assertEquals(checkpoint, Files.readString(fixture.updateRoot.resolve("last-check.txt")))
            assertEquals(listOf(1, 2, 3, 4), requests.toList()); assertOwnInstallRetained(fixture)
        } finally { release.countDown(); request.cancelAndJoin() }
    }
}
