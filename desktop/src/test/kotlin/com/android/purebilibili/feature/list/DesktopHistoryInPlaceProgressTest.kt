package com.android.purebilibili.feature.list

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.android.purebilibili.core.refresh.HistoryRefreshSuppression
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.ui.DesktopHistoryBrowsingVisit
import com.bilipai.desktop.ui.bindDesktopPageActivity
import com.bilipai.desktop.ui.desktopHistoryBrowsingSessionKey
import com.bilipai.desktop.ui.enterDesktopBrowsingSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.parallel.ResourceLock
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** The generated History VM with the original v0.4.6 browsing session and in-place progress, behind
 * the Windows page-activity and session-key binding. Every request is answered by an in-process
 * interceptor: no network, account, store, window or GPU is involved. An unplanned request is a
 * failure, so "no reload" is proven by the absence of any request, not by timing alone. */
@ResourceLock("upstream-history-refresh-bus")
class DesktopHistoryInPlaceProgressTest {
    @Test fun watchingUpdatesProgressInPlaceAndTheLoadedOrderIsKept(): Unit = runBlocking {
        withHistory { h ->
            h.plan(page("a", "b", "c"))
            h.vm.bindDesktopPageActivity(entryOwned = true, active = true)
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = DesktopHistoryBrowsingVisit())
            h.state { !it.isLoading && it.items.size == 3 }
            assertEquals(listOf("a", "b", "c"), h.order())
            assertEquals(1, h.cursorRequests().size)
            h.awaitCollector()
            // The server would now list the watched video first. Nothing may ask for that order.
            h.plan(page("a", "c", "b"))
            h.positions["a"] = 60_000L
            h.signal()
            h.state { state -> state.items.first { it.bvid == "a" }.progress == 60 }
            assertEquals(listOf("a", "b", "c"), h.order())
            assertEquals(60, h.vm.getHistoryItem("a")?.progress)
            assertEquals(37, h.progress("b"))
            assertEquals(37, h.progress("c"))
            delay(200)
            assertEquals(1, h.cursorRequests().size)
            assertEquals(listOf("a", "b", "c"), h.order())
        }
    }

    @Test fun theSameBrowsingVisitReadsTheServerOnceAndANewVisitReadsItAgain(): Unit = runBlocking {
        withHistory { h ->
            val key = desktopHistoryBrowsingSessionKey(DesktopHistoryBrowsingVisit(), pagerHosted = true)
            h.plan(page("a", "b", "c"))
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = key)
            h.state { !it.isLoading && it.items.size == 3 }
            // Opening playback deactivates the page and returning activates it again with the same visit.
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = false, sessionKey = key)
            repeat(3) { h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = key) }
            delay(300)
            assertEquals(1, h.cursorRequests().size)
            assertEquals(listOf("a", "b", "c"), h.order())
            // Another tab was selected in between: the next visit reads the new server order once.
            val next = desktopHistoryBrowsingSessionKey(DesktopHistoryBrowsingVisit(), pagerHosted = true)
            assertNotSame(key, next)
            h.plan(page("c", "a", "b"))
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = next)
            h.state { state -> state.items.map { it.bvid } == listOf("c", "a", "b") }
            assertEquals(2, h.cursorRequests().size)
            repeat(3) { h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = next) }
            delay(300)
            assertEquals(2, h.cursorRequests().size)
        }
    }

    @Test fun aStandaloneHistoryPageHasNoVisitKeyAndKeepsOneSessionPerViewModel(): Unit = runBlocking {
        val visit = DesktopHistoryBrowsingVisit()
        assertNull(desktopHistoryBrowsingSessionKey(visit, pagerHosted = false))
        assertSame(visit, desktopHistoryBrowsingSessionKey(visit, pagerHosted = true))
        withHistory { h ->
            h.vm.enterDesktopBrowsingSession(entryOwned = false, active = true, sessionKey = null)
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = false, sessionKey = null)
            delay(200)
            assertEquals(0, h.cursorRequests().size)
            h.plan(page("a"))
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = null)
            h.state { !it.isLoading && it.items.size == 1 }
            repeat(3) { h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = null) }
            delay(300)
            assertEquals(1, h.cursorRequests().size)
        }
    }

    @Test fun signalsWhileInactiveWaitForActivationAndThenUpdateInPlace(): Unit = runBlocking {
        withHistory { h ->
            h.plan(page("a", "b"))
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = null)
            h.state { !it.isLoading && it.items.size == 2 }
            h.awaitCollector()
            h.positions["b"] = 90_000L
            // The page was never activated: both signals are ignored, the second proves the first was handled.
            h.signal(); h.signal()
            assertEquals(37, h.progress("b"))
            h.vm.bindDesktopPageActivity(entryOwned = true, active = true)
            assertEquals(90, h.progress("b"))
            assertEquals(listOf("a", "b"), h.order())
            // A retired entry or a deactivated page stops live updates again.
            h.vm.bindDesktopPageActivity(entryOwned = false, active = false)
            h.positions["a"] = 100_000L
            h.signal(); h.signal()
            assertEquals(37, h.progress("a"))
            h.vm.bindDesktopPageActivity(entryOwned = false, active = true)
            h.signal(); h.signal()
            assertEquals(37, h.progress("a"))
            assertEquals(1, h.cursorRequests().size)
        }
    }

    @Test fun aRetiredProgressSourceCannotCrashActivationAndLeavesTheListUntouched(): Unit = runBlocking {
        withHistory { h ->
            h.plan(page("a", "b"))
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = null)
            h.state { !it.isLoading && it.items.size == 2 }
            val loaded = h.vm.uiState.value.items
            h.retired.set(true)
            h.positions["a"] = 60_000L
            h.vm.bindDesktopPageActivity(entryOwned = true, active = true)
            assertEquals(loaded, h.vm.uiState.value.items)
            assertEquals(1, h.cursorRequests().size)
        }
    }

    @Test fun theOriginalBusAndSuppressionReplayDriveOneInPlaceUpdateAfterDetailsClose(): Unit = runBlocking {
        withHistory(useRealBus = true) { h ->
            h.plan(page("a", "b", "c"))
            h.vm.bindDesktopPageActivity(entryOwned = true, active = true)
            h.vm.enterDesktopBrowsingSession(entryOwned = true, active = true, sessionKey = null)
            h.state { !it.isLoading && it.items.size == 3 }
            try {
                // Subscription barrier: the collector starts asynchronously and the real bus keeps no replay.
                h.positions["a"] = 45_000L
                withTimeout(5_000) { while (h.progress("a") != 45) { HistoryRefreshBus.notifyChanged(); delay(20) } }
                delay(200) // let any signal still queued behind the barrier settle before the position changes
                HistoryRefreshSuppression.suppress()
                h.positions["a"] = 75_000L
                repeat(3) { HistoryRefreshBus.notifyChanged() }
                delay(200)
                assertEquals(45, h.progress("a"))
                HistoryRefreshSuppression.resume()
                withTimeout(5_000) { while (h.progress("a") != 75) delay(20) }
                assertEquals(listOf("a", "b", "c"), h.order())
                assertEquals(1, h.cursorRequests().size)
            } finally {
                while (HistoryRefreshSuppression.isSuppressed) HistoryRefreshSuppression.resume()
            }
        }
    }

    private suspend fun withHistory(useRealBus: Boolean = false, block: suspend (Harness) -> Unit) {
        val h = Harness(useRealBus)
        try {
            block(h)
            assertTrue(h.unexpected.isEmpty(), h.unexpected.joinToString())
            assertTrue(h.failures.isEmpty(), h.failures.joinToString())
        } finally { h.close() }
    }

    private class Harness(useRealBus: Boolean) {
        val job = SupervisorJob()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> failures.add(failure) })
        val requests = ConcurrentLinkedQueue<HttpUrl>()
        val unexpected = ConcurrentLinkedQueue<String>()
        val positions = ConcurrentHashMap<String, Long>()
        val retired = AtomicBoolean(false)
        val changes = MutableSharedFlow<Long>()
        private val plans = ConcurrentLinkedQueue<String>()
        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request.url)
            if (request.method != "GET") {
                unexpected.add("mutation ${request.method} ${request.url.encodedPath}")
                throw IOException("unexpected synthetic mutation")
            }
            val text = when (request.url.encodedPath) {
                "/x/v2/history/shadow" -> "{\"code\":0,\"data\":false}"
                "/x/web-interface/history/cursor", "/x/web-interface/history/search" ->
                    plans.poll() ?: run { unexpected.add("unplanned ${request.url}"); throw IOException("unplanned synthetic request") }
                else -> { unexpected.add("unknown ${request.url.encodedPath}"); throw IOException("unknown synthetic endpoint") }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("isolated fixture")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        private val environment = DesktopFavoriteEnvironment(scope, api, null, null, null,
            stillOwned = { true }, readCsrf = { "synthetic-only" }, readMid = { 17L },
            feedback = { }, historyChanges = if (useRealBus) HistoryRefreshBus.changes.map { 1L } else changes,
            getCachedPosition = { bvid, _ ->
                if (retired.get()) throw CancellationException("History progress entry retired")
                positions[bvid] ?: 0L
            },
            privacyModeEnabled = { false }, watchLaterChanged = {}, readAccessToken = { null }, readAccessTokenPlatform = { "android" })
        val vm = HistoryViewModel(environment)
        fun plan(text: String) { plans.add(text) }
        suspend fun state(predicate: (ListUiState) -> Boolean): ListUiState = withTimeout(5_000) { vm.uiState.first(predicate) }
        suspend fun awaitCollector() { withTimeout(5_000) { changes.subscriptionCount.first { it >= 1 } } }
        suspend fun signal() { withTimeout(5_000) { changes.emit(1L) } }
        fun order() = vm.uiState.value.items.map { it.bvid }
        fun progress(bvid: String) = vm.uiState.value.items.first { it.bvid == bvid }.progress
        fun cursorRequests() = requests.filter { it.encodedPath == "/x/web-interface/history/cursor" }
        suspend fun close() {
            job.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        private fun item(bvid: String) = HistoryData(
            title = "synthetic $bvid", author_name = "fixture", author_mid = 17, duration = 120, progress = 37,
            history = HistoryPage(bvid = bvid, cid = 7007, oid = 170001, business = "archive", page = 2))
        private fun page(vararg bvids: String) = json.encodeToString(HistoryResponse(data = HistoryListData(
            list = bvids.map { item(it) }, cursor = HistoryCursor(max = 50, view_at = 1050, business = "archive"))))
    }
}
