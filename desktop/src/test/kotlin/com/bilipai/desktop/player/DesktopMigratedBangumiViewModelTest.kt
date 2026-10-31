package com.bilipai.desktop.player

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.bilipai.desktop.ui.*
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopMigratedBangumiViewModelTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    @Test fun detailResultsRespectLatestRequestAccountAndRetiredOwner() = runBlocking {
        val owned = AtomicBoolean(true)
        val session = AtomicReference(BangumiFollowSession(null, null))
        val pending = ConcurrentHashMap<Long, Continuation<ResponseBody>>()
        val counts = ConcurrentHashMap<String, Int>()
        val failures = mutableListOf<Throwable>()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e ->
            synchronized(failures) { failures += e }
        })
        val api = Proxy.newProxyInstance(BangumiApi::class.java.classLoader, arrayOf(BangumiApi::class.java)) { _, method, args ->
            counts.merge(method.name, 1, Int::plus)
            when (method.name) {
                "getBangumiIndex" -> BangumiIndexResponse(data = BangumiIndexData(list = emptyList()))
                "getTimeline" -> BangumiTimelineResponse(result = emptyList())
                "getSeasonDetail" -> {
                    val id = args!![0] as Long
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as Continuation<ResponseBody>
                    check(pending.putIfAbsent(id, continuation) == null)
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected BangumiApi.${method.name}")
            }
        } as BangumiApi
        val repository = DesktopOriginalBangumiHubRepository(api, unused<BilibiliApi>(), unused<SearchApi>(), { null }, { null })
        val commit: (() -> Unit) -> Boolean = { action -> if (owned.get()) { action(); true } else false }
        val hub = BangumiHubViewModel(DesktopBangumiHubEnvironment(repository, scope, { false }, owned::get, commit, kotlinx.coroutines.flow.MutableStateFlow(
            com.android.purebilibili.core.store.AccountSessionIdentity(mid = null, generation = 1L))))
        val environment = DesktopOriginalBangumiPagesEnvironment(
            hub, DesktopOriginalBangumiPagesRequests(api, repository, { null }, { null }, owned::get, owned::get),
            DesktopOriginalBangumiReviewRequests(api, { null }, owned::get, owned::get),
            unused<DesktopOriginalVideoProgressPort>(), scope, commit, owned::get,
            { error("Unexpected feedback: $it") }, false, session::get,
        )
        suspend fun awaitRequest(id: Long) = withTimeout(5_000) {
            while (!pending.containsKey(id)) delay(1)
        }
        fun complete(id: Long) {
            val body = """{"code":0,"result":{"season_id":$id,"title":"season-$id","episodes":[{"id":1}],"section":[]}}""".toResponseBody()
            requireNotNull(pending.remove(id)).resume(body)
        }
        try {
            val vm = BangumiViewModel(environment)
            // Derived StateFlows have long-lived collection jobs; wait for each actual request job instead.
            withTimeout(5_000) { vm.uiState.first { it.listState is BangumiListState.Success && it.timelineState is TimelineState.Success } }
            fun request(id: Long): Job {
                val previousJobs = job.children.toSet()
                vm.loadSeasonDetail(id)
                return (job.children.toSet() - previousJobs).single()
            }
            val first = request(101); awaitRequest(101)
            val second = request(202); awaitRequest(202)
            complete(202)
            withTimeout(5_000) { vm.uiState.first { (it.detailState as? BangumiDetailState.Success)?.detail?.seasonId == 202L } }
            complete(101)
            withTimeout(5_000) { first.join(); second.join() }
            assertEquals(202L, (vm.uiState.value.detailState as BangumiDetailState.Success).detail.seasonId)
            val accountRequest = request(303); awaitRequest(303)
            session.set(BangumiFollowSession(null, "next-account"))
            complete(303)
            withTimeout(5_000) { accountRequest.join() }
            assertSame(BangumiDetailState.Loading, vm.uiState.value.detailState)
            val ownerRequest = request(404); awaitRequest(404)
            owned.set(false)
            complete(404)
            withTimeout(5_000) { ownerRequest.join() }
            assertSame(BangumiDetailState.Loading, vm.uiState.value.detailState)
            assertEquals(mapOf("getBangumiIndex" to 1, "getTimeline" to 1, "getSeasonDetail" to 4), counts.toMap())
            assertTrue(pending.isEmpty())
            assertTrue(failures.isEmpty(), failures.toString())
        } finally { job.cancelAndJoin() }
    }
}
