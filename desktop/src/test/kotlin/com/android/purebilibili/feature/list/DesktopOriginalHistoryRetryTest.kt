package com.android.purebilibili.feature.list

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Whole generated HistoryViewModel -> existing owned environment -> original repository.
 * The interceptor answers every request locally; no real transport/account/store is used. */
class DesktopOriginalHistoryRetryTest {
    @Test fun initialFailureRemainsAnErrorAndOriginalRetryFetchesRows(): Unit = runBlocking {
        withHistory { h ->
            h.plan(failure("initial denied"))
            h.vm.loadData()
            h.state { !it.isLoading && it.error == "initial denied" }
            assertTrue(h.vm.uiState.value.items.isEmpty())
            val retry = h.plan(rows("retained", max = 50))
            h.vm.retryHistory()
            h.state { !it.isLoading && it.items.singleOrNull()?.bvid == "retained" }
            assertNull(h.vm.uiState.value.error)
            assertNull(h.vm.uiState.value.loadMoreError)
            assertNull(retry.entered.await().queryParameter("max"))
            assertEquals(2, h.cursorRequests().size)
        }
    }

    @Test fun failedSilentRefreshRetainsFullNavigationCacheAndNextCursor(): Unit = runBlocking {
        withHistory { h ->
            h.plan(rows("old", max = 42, cid = 7007, progress = 37))
            h.vm.loadData(); h.state { !it.isLoading && it.items.size == 1 }
            val old = assertNotNull(h.vm.getHistoryItem("old"))
            val refresh = h.plan(failure("refresh denied"), delayed = true)
            h.vm.loadData(showLoading = false); refresh.entered.await()
            assertSame(old, h.vm.getHistoryItem("old"))
            assertFalse(h.vm.uiState.value.isLoading)
            refresh.release.countDown()
            h.state { it.error == "refresh denied" }
            assertSame(old, h.vm.getHistoryItem("old"))
            assertEquals(7007L, old.cid); assertEquals(37, old.progress)
            assertEquals(HistoryBusiness.ARCHIVE, old.business)
            assertTrue(h.vm.hasMoreState.value)
            val next = h.plan(rows("next", max = 41))
            h.vm.loadMore(retry = true)
            h.state { it.items.map { item -> item.bvid } == listOf("old", "next") }
            assertEquals("42", next.entered.await().queryParameter("max"))
            assertEquals("1042", next.entered.await().queryParameter("view_at"))
            assertEquals("archive", next.entered.await().queryParameter("business"))
            assertSame(old, h.vm.getHistoryItem("old"))
        }
    }

    @Test fun failedPageDoesNotAutoRetryOrLoseCursorAndExplicitRetryDeduplicates(): Unit = runBlocking {
        withHistory { h ->
            h.plan(rows("old", max = 60)); h.vm.loadData(); h.state { !it.isLoading && it.items.size == 1 }
            val failed = h.plan(failure("page denied"))
            h.vm.loadMore(); h.state { it.loadMoreError == "page denied" }
            h.awaitIdle()
            assertTrue(h.vm.hasMoreState.value); assertNull(h.vm.uiState.value.error)
            val count = h.cursorRequests().size
            h.vm.loadMore()
            assertEquals(count, h.cursorRequests().size)
            assertFalse(h.vm.isLoadingMoreState.value)
            val retried = h.plan(response(listOf(item("old"), item("new")), max = 59))
            h.vm.loadMore(retry = true)
            h.state { it.items.map { row -> row.bvid } == listOf("old", "new") }
            h.awaitIdle()
            assertEquals(failed.entered.await(), retried.entered.await())
            assertNull(h.vm.uiState.value.loadMoreError)
            assertEquals(3, h.cursorRequests().size)
        }
    }

    @Test fun failedSearchPageRetriesSameQueryAndCommittedPage(): Unit = runBlocking {
        withHistory { h ->
            h.plan(response((1..20).map { item("q$it") }, max = 20))
            h.vm.searchHistory(" original query ")
            h.state { !it.isLoading && it.items.size == 20 }
            assertTrue(h.vm.hasMoreState.value)
            val failed = h.plan(failure("search page denied"))
            h.vm.loadMore(); h.state { it.loadMoreError == "search page denied" }; h.awaitIdle()
            h.vm.loadMore(); assertEquals(2, h.searchRequests().size)
            val retried = h.plan(response((21..40).map { item("q$it") }, max = 1))
            h.vm.loadMore(retry = true)
            h.state { it.items.size == 40 }; h.awaitIdle()
            val first = failed.entered.await(); val second = retried.entered.await()
            assertEquals(first, second)
            assertEquals("2", second.queryParameter("pn"))
            assertEquals("original query", second.queryParameter("keyword"))
            assertEquals("/x/web-interface/history/search", second.encodedPath)
            assertNull(h.vm.uiState.value.loadMoreError)
            assertTrue(h.vm.hasMoreState.value)
        }
    }

    @Test fun lateOldSearchCannotReplaceNewQueryResults(): Unit = runBlocking {
        withHistory { h ->
            val before = h.job.children.toSet()
            val old = h.plan(rows("old-query", max = 0), delayed = true)
            h.vm.searchHistory("A"); old.entered.await()
            val oldJob = h.job.children.first { it !in before }
            val latest = h.plan(rows("new-query", max = 0))
            h.vm.searchHistory("B")
            h.state { !it.isLoading && it.items.singleOrNull()?.bvid == "new-query" }
            old.release.countDown(); withTimeout(5_000) { oldJob.join() }
            assertEquals("B", latest.entered.await().queryParameter("keyword"))
            assertEquals(listOf("new-query"), h.vm.uiState.value.items.map { it.bvid })
            assertNull(h.vm.getHistoryItem("old-query"))
        }
    }

    @Test fun retiredAccountRejectsSuspendedResultAndFurtherTransport(): Unit = runBlocking {
        withHistory { h ->
            val before = h.job.children.toSet()
            val old = h.plan(rows("retired-account", max = 0), delayed = true)
            h.vm.loadData(); old.entered.await()
            val requestJob = h.job.children.first { it !in before }
            h.epoch.incrementAndGet(); old.release.countDown()
            withTimeout(5_000) { requestJob.join() }
            assertTrue(h.vm.uiState.value.items.isEmpty())
            assertNull(h.vm.uiState.value.error)
            assertNull(h.vm.getHistoryItem("retired-account"))
            val count = h.requests.size
            h.vm.retryHistory()
            withTimeout(5_000) { h.job.children.filter { it !in before }.forEach { it.join() } }
            assertEquals(count, h.requests.size)
            assertTrue(h.feedback.isEmpty())
        }
        withHistory { successor ->
            successor.plan(rows("successor-account", max = 0)); successor.vm.loadData()
            successor.state { !it.isLoading && it.items.singleOrNull()?.bvid == "successor-account" }
        }
    }

    @Test fun cancelledPageReleasesOnlyBusyAndKeepsRowsForRetry(): Unit = runBlocking {
        withHistory { h ->
            h.plan(rows("retained", max = 70)); h.vm.loadData(); h.state { !it.isLoading && it.items.size == 1 }
            val before = h.job.children.toSet()
            val pending = h.plan(rows("cancelled", max = 69), delayed = true)
            h.vm.loadMore(); pending.entered.await()
            val pageJob = h.job.children.first { it !in before }
            pageJob.cancel(); pending.release.countDown()
            withTimeout(5_000) { pageJob.join() }
            assertFalse(h.vm.isLoadingMoreState.value)
            assertNull(h.vm.uiState.value.error); assertNull(h.vm.uiState.value.loadMoreError)
            assertEquals(listOf("retained"), h.vm.uiState.value.items.map { it.bvid })
            assertNull(h.vm.getHistoryItem("cancelled"))
            val retry = h.plan(rows("after-cancel", max = 68))
            h.vm.loadMore(retry = true)
            h.state { it.items.map { row -> row.bvid } == listOf("retained", "after-cancel") }
            assertEquals("70", retry.entered.await().queryParameter("max"))
            assertTrue(h.feedback.isEmpty())
        }
    }

    @Test fun repeatedLoadMoreWhilePendingSendsOnceAndTrueEndStopsPagination(): Unit = runBlocking {
        withHistory { h ->
            h.plan(rows("retained", max = 90)); h.vm.loadData(); h.state { !it.isLoading && it.items.size == 1 }
            val end = h.plan(response(emptyList(), max = 0), delayed = true)
            h.vm.loadMore()
            assertTrue(h.vm.isLoadingMoreState.value)
            repeat(10) { h.vm.loadMore(retry = true) }
            end.entered.await(); assertEquals(2, h.cursorRequests().size)
            end.release.countDown(); h.awaitIdle()
            assertFalse(h.vm.hasMoreState.value)
            repeat(3) { h.vm.loadMore(retry = true) }
            assertEquals(2, h.cursorRequests().size)
            assertEquals(listOf("retained"), h.vm.uiState.value.items.map { it.bvid })
            assertNull(h.vm.uiState.value.loadMoreError)
        }
    }

    private suspend fun withHistory(block: suspend (Harness) -> Unit) {
        val h = Harness()
        try { block(h); assertTrue(h.unexpected.isEmpty(), h.unexpected.joinToString()); assertTrue(h.failures.isEmpty(), h.failures.joinToString()) }
        finally { h.close() }
    }

    private class Plan(val text: String, delayed: Boolean) {
        val entered = CompletableDeferred<HttpUrl>()
        val release = CountDownLatch(if (delayed) 1 else 0)
    }

    private class Harness {
        val job = SupervisorJob()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> failures.add(failure) })
        val epoch = AtomicLong(1)
        val feedback = ConcurrentLinkedQueue<String>()
        val requests = ConcurrentLinkedQueue<HttpUrl>()
        val unexpected = ConcurrentLinkedQueue<String>()
        private val plans = ConcurrentLinkedQueue<Plan>()
        private val allPlans = ConcurrentLinkedQueue<Plan>()
        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests.add(request.url)
            if (request.method != "GET") {
                unexpected.add("mutation ${request.method} ${request.url.encodedPath}")
                throw IOException("unexpected synthetic mutation")
            }
            val text = when (request.url.encodedPath) {
                "/x/v2/history/shadow" -> "{\"code\":0,\"data\":false}"
                "/x/web-interface/history/cursor", "/x/web-interface/history/search" -> {
                    val plan = plans.poll() ?: run { unexpected.add("unplanned ${request.url}"); throw IOException("unplanned synthetic request") }
                    plan.entered.complete(request.url)
                    check(plan.release.await(5, TimeUnit.SECONDS)) { "synthetic API gate timed out" }
                    plan.text
                }
                else -> { unexpected.add("unknown ${request.url.encodedPath}"); throw IOException("unknown synthetic endpoint") }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("isolated fixture")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        private val environment = DesktopFavoriteEnvironment(scope, api, null, null, null,
            stillOwned = { epoch.get() == 1L }, readCsrf = { "synthetic-only" }, readMid = { 17L },
            feedback = { feedback.add(it) }, historyChanges = MutableSharedFlow<Long>(), getCachedPosition = { _, _ -> 0L },
            privacyModeEnabled = { false }, watchLaterChanged = {}, readAccessToken = { null }, readAccessTokenPlatform = { "android" })
        val vm = HistoryViewModel(environment)
        fun plan(text: String, delayed: Boolean = false): Plan = Plan(text, delayed).also { allPlans.add(it); plans.add(it) }
        suspend fun state(predicate: (ListUiState) -> Boolean): ListUiState = withTimeout(5_000) { vm.uiState.first(predicate) }
        suspend fun awaitIdle() { withTimeout(5_000) { vm.isLoadingMoreState.first { !it } } }
        fun cursorRequests() = requests.filter { it.encodedPath == "/x/web-interface/history/cursor" }
        fun searchRequests() = requests.filter { it.encodedPath == "/x/web-interface/history/search" }
        suspend fun close() {
            allPlans.forEach { it.release.countDown() }
            job.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
        private fun item(bvid: String, cid: Long = 7007, progress: Int = 37) = HistoryData(
            title = "synthetic $bvid", author_name = "fixture", author_mid = 17, duration = 120, progress = progress,
            history = HistoryPage(bvid = bvid, cid = cid, oid = 170001, business = "archive", page = 2))
        private fun response(items: List<HistoryData>, max: Long) = json.encodeToString(HistoryResponse(data = HistoryListData(
            list = items, cursor = HistoryCursor(max = max, view_at = max + 1000, business = "archive"))))
        private fun rows(bvid: String, max: Long, cid: Long = 7007, progress: Int = 37) = response(listOf(item(bvid, cid, progress)), max)
        private fun failure(message: String) = json.encodeToString(HistoryResponse(code = -1, message = message))
    }
}
