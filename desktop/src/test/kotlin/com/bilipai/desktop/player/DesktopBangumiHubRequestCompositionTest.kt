package com.bilipai.desktop.player

import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.AccountSessionIdentity
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalBangumiHubRepository
import com.android.purebilibili.feature.bangumi.*
import com.bilipai.desktop.ui.DesktopBangumiHubEnvironment
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Complete VM and real repository; controlled API continuations, no HTTP or Root mounting. */
class DesktopBangumiHubRequestCompositionTest {
    private class Call(val name: String, val args: List<Any?>, val continuation: Continuation<Any?>) {
        var completed = false
        @Synchronized fun complete(value: Any?) { check(!completed); completed = true; continuation.resume(value) }
        @Synchronized fun cancel() { if (!completed) { completed = true; continuation.resumeWithException(CancellationException("fixture close")) } }
    }
    private class Fixture {
        val identity = MutableStateFlow(AccountSessionIdentity(mid = 71L, generation = 7L))
        var owned = true
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> failures += e })
        val requests = Channel<Call>(Channel.UNLIMITED)
        val all = Collections.synchronizedList(mutableListOf<Call>())
        private inline fun <reified T> api(allowed: Set<String>): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            check(method.name in allowed) { "Unexpected ${T::class.java.simpleName}.${method.name}" }
            @Suppress("UNCHECKED_CAST")
            val call = Call(method.name, args!!.dropLast(1), args.last() as Continuation<Any?>)
            all += call
            check(requests.trySend(call).isSuccess)
            COROUTINE_SUSPENDED
        } as T
        private val repository = DesktopOriginalBangumiHubRepository(
            api<BangumiApi>(setOf("getBangumiIndexResult", "getBangumiIndexCondition", "getTimeline", "getMyFollowBangumi")),
            api<BilibiliApi>(setOf("getNavInfo")), api<SearchApi>(setOf("searchBangumi")),
            { error("No mutation/CSRF allowed") }, { identity.value.mid })
        val vm = BangumiHubViewModel(DesktopBangumiHubEnvironment(repository, scope,
            { identity.value.mid != null }, { owned }, { action -> if (owned) { action(); true } else false }, identity))
        suspend fun next(name: String): Call = withTimeout(5000) { requests.receive() }.also { assertEquals(name, it.name) }
        suspend fun state(predicate: (BangumiHubUiState) -> Boolean) = withTimeout(5000) { vm.uiState.first(predicate) }
        fun home() = requireNotNull(vm.uiState.value.homeStates[BangumiChannel.BANGUMI])
        suspend fun close() {
            job.cancel()
            synchronized(all) { all.forEach { it.cancel() } }
            withTimeout(5000) { job.join() }
            assertTrue(failures.isEmpty(), failures.toString())
        }
    }
    private fun index(id: Long, more: Int = 0) = BangumiIndexResponse(data = BangumiIndexData(hasNext = more, list = listOf(BangumiItem(seasonId = id))))
    private fun follow(id: Long) = MyFollowBangumiResponse(data = MyFollowBangumiData(total = 1, list = listOf(FollowBangumiItem(seasonId = id))))

    @Test fun fullVmConsumesInitializationPagingIndexTimelineFollowSearchAndRefreshFailure() = runBlocking {
        val f = Fixture()
        try {
            f.vm.setShowPgcTimeline(false)
            f.vm.initialize(1)
            // IO scheduling may permute these two independent requests.
            val initial = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }.associateBy { it.name }
            assertEquals(setOf("getBangumiIndexResult", "getMyFollowBangumi"), initial.keys)
            val recommendation = initial.getValue("getBangumiIndexResult")
            val query = recommendation.args.single() as Map<*, *>
            assertEquals("1", query["page"]); assertEquals("20", query["pagesize"])
            assertEquals("1", query["season_type"]); assertEquals("1", query["type"])
            assertEquals(listOf<Any?>(71L, 1, null, 1, 30), initial.getValue("getMyFollowBangumi").args)
            recommendation.complete(index(101, 1)); initial.getValue("getMyFollowBangumi").complete(follow(201))
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.let { h -> h.recommendations.page == 1 && h.follows.page == 1 } == true }
            f.vm.initialize(4)
            assertTrue(f.requests.tryReceive().isFailure, "initialize is idempotent")
            f.vm.loadMoreHomeRecommendations()
            f.next("getBangumiIndexResult").also { assertEquals("2", (it.args.single() as Map<*, *>)["page"]); it.complete(index(102)) }
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.recommendations?.page == 2 }
            assertEquals(listOf(101L, 102L), f.home().recommendations.items.map { it.seasonId })
            f.vm.refreshHome()
            val refresh = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }.associateBy { it.name }
            refresh.getValue("getBangumiIndexResult").complete(BangumiIndexResponse(code = -1, message = "synthetic index failure"))
            refresh.getValue("getMyFollowBangumi").complete(follow(202))
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.let { h -> h.recommendations.error != null && h.follows.items.firstOrNull()?.seasonId == 202L } == true }
            assertEquals(listOf(101L, 102L), f.home().recommendations.items.map { it.seasonId })
            assertContains(f.home().recommendations.error!!, "synthetic index failure")
            f.vm.setShowPgcTimeline(true)
            val timeline = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }
            assertEquals(setOf(1, 4), timeline.map { assertEquals("getTimeline", it.name); it.args[0] }.toSet())
            timeline.forEach { assertEquals(listOf<Any?>(3, 7), it.args.drop(1)); it.complete(BangumiTimelineResponse(result = emptyList())) }
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.timeline?.isLoading == false }
            f.vm.openIndex()
            f.next("getBangumiIndexCondition").complete(BangumiIndexConditionResponse(data = BangumiIndexConditionData(order = listOf(BangumiIndexConditionOrder(field = "3", name = "追番", sort = "0")))))
            f.next("getBangumiIndexResult").also { assertEquals("0", (it.args.single() as Map<*, *>)["type"]); it.complete(index(301)) }
            f.state { it.indexStates[BangumiIndexCategory.BANGUMI]?.results?.items?.firstOrNull()?.seasonId == 301L }
            f.vm.openFollowManager()
            f.next("getMyFollowBangumi").also { assertEquals(71L, it.args[0]); assertEquals(1, it.args[3]); it.complete(follow(401)) }
            f.state { it.followStates.values.any { follow -> follow.content.items.firstOrNull()?.seasonId == 401L } }
            f.vm.openSearch(); f.vm.search("  synthetic search  ")
            f.next("getNavInfo").complete(NavResponse())
            f.next("searchBangumi").also { assertEquals(mapOf("keyword" to "synthetic search", "search_type" to "media_bangumi", "page" to "1", "pagesize" to "20"), it.args.single()); it.complete(BangumiSearchResponse(data = BangumiSearchData(result = listOf(BangumiSearchItem(seasonId = 501, seasonType = 1))))) }
            f.state { !it.search.results.isLoading }
            assertEquals(listOf(501L), f.vm.uiState.value.search.results.items.map { it.seasonId })
            assertEquals(mapOf("getBangumiIndexResult" to 4, "getMyFollowBangumi" to 3,
                "getTimeline" to 2, "getBangumiIndexCondition" to 1, "getNavInfo" to 1,
                "searchBangumi" to 1), synchronized(f.all) { f.all.groupingBy { it.name }.eachCount() })
            assertTrue(f.requests.tryReceive().isFailure)
        } finally { f.close() }
    }

    @Test fun controlledIdentityChangeJoinsOldAccountRequestBeforeResetAndKeepsPublicResult() = runBlocking {
        val f = Fixture()
        try {
            f.vm.setShowPgcTimeline(false); f.vm.initialize(1)
            val first = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }.associateBy { it.name }
            first.getValue("getBangumiIndexResult").complete(index(101))
            val oldFollow = first.getValue("getMyFollowBangumi")
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.recommendations?.page == 1 }
            f.identity.value = AccountSessionIdentity(mid = 72L, generation = 8L)
            // The genuine account observer cannot reset until this non-cooperative API continuation ends.
            assertEquals(7L, f.vm.uiState.value.accountGeneration)
            oldFollow.complete(follow(999))
            val replacement = f.next("getMyFollowBangumi")
            assertEquals(72L, replacement.args[0])
            assertEquals(8L, f.vm.uiState.value.accountGeneration)
            assertTrue(f.home().follows.items.isEmpty())
            assertEquals(listOf(101L), f.home().recommendations.items.map { it.seasonId })
            replacement.complete(follow(202))
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.follows?.items?.firstOrNull()?.seasonId == 202L }
            assertFalse(f.home().follows.items.any { it.seasonId == 999L })
            assertTrue(f.requests.tryReceive().isFailure)
        } finally { f.close() }
    }
}
