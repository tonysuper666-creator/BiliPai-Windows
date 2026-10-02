package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.navigation3.BiliPaiNavBackStackController
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Fixture-only raw DTO protocols. These never construct HTTP clients, read
 * account cookies, open native players, or imply real-account acceptance. */
private inline fun <reified T : Any> rawApi(crossinline call: (String, Array<out Any?>) -> Any): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        when (method.name) {
            "toString" -> "Explicit raw protocol fixture"
            "hashCode" -> 1
            "equals" -> false
            else -> call(method.name, args ?: emptyArray())
        }
    } as T

private class RawBangumi {
    val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    var pgcMissing = false
    var deniedReview = false
    var csrf: String? = "fixture-only-placeholder"
    val owned = AtomicBoolean(true)
    val active = AtomicBoolean(true)
    val api: BangumiApi = rawApi { name, args ->
        calls += name to args.dropLast(1)
        when (name) {
            "getSeasonDetail" -> (if (pgcMissing) """{"code":-404,"message":"PGC unavailable"}""" else
                """{"code":0,"result":{"season_id":7,"media_id":8,"title":"Original detail","season_type":1,"user_status":{"follow":0,"follow_status":0},"episodes":[{"id":21,"aid":101,"cid":102,"title":"1"}]}}""")
                .toResponseBody("application/json".toMediaType())
            "getPugvSeasonDetail" -> """{"code":0,"data":{"season_id":19,"title":"Paid course","user_status":{"payed":0,"favored":0},"episodes":[{"id":41,"aid":103,"cid":104,"title":"Course","duration":60,"playable":false,"episode_can_view":false}]}}"""
                .toResponseBody("application/json".toMediaType())
            "getSeasonSections" -> BangumiSectionResponse(result = BangumiSectionResult(
                mainSection = BangumiSection(id = 9, episodes = listOf(BangumiEpisode(id = 21), BangumiEpisode(id = 22))),
                section = listOf(BangumiSection(id = 10, title = "Special", episodes = listOf(BangumiEpisode(id = 23))))))
            "getBangumiMediaInfo" -> BangumiMediaInfoResponse(result = BangumiMediaInfoResult(media = BangumiMediaInfo(seasonId = 7)))
            "getBangumiIndex" -> BangumiIndexResponse(data = BangumiIndexData(list = emptyList()))
            "getBangumiIndexResult" -> BangumiIndexResponse(data = BangumiIndexData(list = emptyList()))
            "getBangumiIndexCondition" -> BangumiIndexConditionResponse(data = BangumiIndexConditionData())
            "getTimeline" -> BangumiTimelineResponse(result = emptyList())
            "getMyFollowBangumi" -> MyFollowBangumiResponse(data = MyFollowBangumiData(total = 0, list = emptyList()))
            "getBangumiShortReviews", "getBangumiLongReviews" -> if (deniedReview)
                BangumiReviewListResponse(code = -403, message = "restricted review") else
                BangumiReviewListResponse(data = BangumiReviewListData(list = listOf(BangumiReviewItem(review_id = 51)), next = "cursor-next", total = 27))
            "postBangumiShortReview", "likeBangumiReview", "followBangumi", "unfollowBangumi", "updateBangumiFollowStatus", "addFavPugv", "delFavPugv" -> SimpleApiResponse(code = 0)
            else -> error("Unplanned raw protocol fixture: $name")
        }
    }
    val hub = DesktopOriginalBangumiHubRepository(api,
        rawApi<BilibiliApi> { name, _ -> error("Unexpected nav request $name") },
        rawApi<SearchApi> { name, _ -> error("Unexpected search request $name") }, { csrf }, { 123L })
    val requests = DesktopOriginalBangumiPagesRequests(api, hub, { csrf }, { null }, owned::get,
        { owned.get() && active.get() })
    val reviews = DesktopOriginalBangumiReviewRequests(api, { csrf }, owned::get,
        { owned.get() && active.get() })
}

private var checks = 0
private fun prove(value: Boolean, name: String) { check(value) { name }; checks++ }

fun main() = runBlocking {
    val raw = RawBangumi()
    prove(raw.requests.getSeasonDetail().isFailure, "zero season+episode retains original parameter error")
    prove(raw.calls.isEmpty(), "parameter failure makes no raw request")
    val detail = raw.requests.getSeasonDetail(seasonId = 99, epId = 42).getOrThrow()
    prove(raw.calls.first { it.first == "getSeasonDetail" }.second.take(2) == listOf(null, 42L), "episode has original priority over potentially wrong history season ID")
    prove(detail.episodes?.map { it.id } == listOf(21L, 22L), "original section merge deduplicates main episodes")
    prove(detail.section?.single()?.episodes?.single()?.id == 23L, "original extra sections retained")
    raw.calls.clear()
    prove(raw.requests.getSeasonDetailByMediaId(8).getOrThrow().seasonId == 7L, "media-ID route resolves actual season through original media protocol")
    prove(raw.calls.take(2).map { it.first } == listOf("getBangumiMediaInfo", "getSeasonDetail"), "media route retains original two-step request order")
    raw.pgcMissing = true
    val course = raw.requests.getSeasonDetail(epId = 41).getOrThrow()
    prove(course.seasonId == 19L && !course.hasPaid, "PGC failure retains original paid-course fallback and payment state")
    prove(course.episodes!!.single().duration == 60_000L && course.episodes!!.single().badge == "付费", "original course seconds and restricted episode badge retained")
    prove(raw.calls.any { it.first == "getPugvSeasonDetail" && it.second.take(2) == listOf(null, 41L) }, "course fallback keeps episode-ID priority")

    val short = raw.reviews.getReviews(8, BangumiReviewType.SHORT, "cursor-old", 1).getOrThrow()
    prove(short.count == 27 && short.hasMore && short.next == "cursor-next", "original review total fallback/cursor/hasMore")
    prove(raw.calls.last().first == "getBangumiShortReviews", "short review uses original endpoint")
    raw.reviews.getReviews(8, BangumiReviewType.LONG).getOrThrow()
    prove(raw.calls.last().first == "getBangumiLongReviews", "long review uses original endpoint")
    raw.deniedReview = true
    prove(raw.reviews.getReviews(8, BangumiReviewType.SHORT).exceptionOrNull()?.message == "restricted review", "business rejection is not converted into positive UI")
    raw.deniedReview = false
    raw.reviews.postShortReview(8, 99, "  original text  ").getOrThrow()
    prove(raw.calls.last().second.take(3) == listOf(8L, 10, "original text"), "original review score clamp/text trim preserved")
    raw.csrf = null
    val beforeLogin = raw.calls.size
    prove(raw.reviews.likeReview(8, 51).exceptionOrNull()?.message == "请先登录", "original missing-login error")
    prove(raw.calls.size == beforeLogin, "missing csrf does not call mutation endpoint")
    raw.csrf = "fixture-only-placeholder"
    raw.active.set(false)
    val beforeCovered = raw.calls.size
    prove(runCatching { raw.reviews.likeReview(8, 51) }.exceptionOrNull() is CancellationException, "covered entry cannot mutate reviews")
    prove(runCatching { raw.requests.followBangumi(7) }.exceptionOrNull() is CancellationException, "covered entry cannot follow season")
    prove(raw.calls.size == beforeCovered, "action admission happens before protocol invocation")
    prove(raw.reviews.getReviews(8, BangumiReviewType.SHORT).isSuccess, "covered but retained entry may finish data reads")
    raw.owned.set(false)
    prove(runCatching { raw.requests.getSeasonDetail(7) }.exceptionOrNull() is CancellationException, "retired route rejected before request")
    val lateOwned = AtomicBoolean(true)
    prove(runCatching { ownedBangumiRequest(Dispatchers.IO, lateOwned::get) {
        lateOwned.set(false); Result.success(7)
    } }.exceptionOrNull() is CancellationException, "retirement after original body blocks late Result success")
    val caller = launch {
        prove(runCatching { ownedBangumiRequest(Dispatchers.IO, { true }) {
            runCatching { currentCoroutineContext().cancel(); currentCoroutineContext().ensureActive() }
        } }.exceptionOrNull() is CancellationException, "original runCatching cannot swallow caller cancellation")
    }
    caller.join()

    // Execute the actual full original combined detail VM, not a duplicate
    // implementation. It borrows these explicit fixture-only raw ports.
    val vmRaw = RawBangumi()
    val ownerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val monitor = Any()
    fun commit(action: () -> Unit): Boolean = synchronized(monitor) {
        if (vmRaw.owned.get() && ownerScope.isActive) { action(); true } else false
    }
    val hubVm = BangumiHubViewModel(DesktopBangumiHubEnvironment(vmRaw.hub, ownerScope,
        { true }, vmRaw.owned::get, ::commit))
    val environment = DesktopOriginalBangumiPagesEnvironment(hubVm, vmRaw.requests, vmRaw.reviews,
        object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) = error("Detail never owns progress writes")
        }, ownerScope, ::commit, vmRaw.owned::get, { error("Unexpected feedback $it") }, false)
    val vm = BangumiViewModel(environment)
    vm.loadSeasonDetail(7)
    withTimeout(5_000) { vm.detailState.first { it is BangumiDetailState.Success } }
    prove((vm.detailState.value as BangumiDetailState.Success).detail.userStatus?.followStatus == 0, "original detail VM resolves untracked follow state")
    vm.updateFollowStatus(7, BANGUMI_FOLLOW_STATUS_WATCHED)
    withTimeout(5_000) { vm.detailState.first { (it as? BangumiDetailState.Success)?.detail?.userStatus?.followStatus == BANGUMI_FOLLOW_STATUS_WATCHED } }
    val actions = vmRaw.calls.filter { it.first in setOf("followBangumi", "updateBangumiFollowStatus") }
    prove(actions.takeLast(2).map { it.first } == listOf("followBangumi", "updateBangumiFollowStatus"), "original nondefault follow status first follows then updates")
    vm.loadSeasonDetail(7)
    withTimeout(5_000) { vm.detailState.first { (it as? BangumiDetailState.Success)?.detail?.userStatus?.followStatus == BANGUMI_FOLLOW_STATUS_WATCHED } }
    prove((vm.detailState.value as BangumiDetailState.Success).detail.userStatus?.follow == 1, "original follow cache overrides stale API follow=0 on reload")
    vm.updateFollowStatus(7, BANGUMI_FOLLOW_STATUS_UNFOLLOW)
    withTimeout(5_000) { vm.detailState.first { (it as? BangumiDetailState.Success)?.detail?.userStatus?.follow == 0 } }
    prove(vmRaw.calls.last { it.first in setOf("unfollowBangumi", "followBangumi", "updateBangumiFollowStatus") }.first == "unfollowBangumi", "original unfollow dispatch retained")
    vmRaw.owned.set(false); ownerScope.cancel()
    val stale = vm.detailState.value
    delay(20)
    prove(vm.detailState.value === stale, "retired full VM keeps last published state")

    val followIds = mutableSetOf<Long>()
    val pageCalls = mutableListOf<Int>()
    val preload = preloadFollowedSeasonsForType(1, followIds, pageSize = 2, maxPages = 3) { _, page, _ ->
        pageCalls += page
        if (page == 1) Result.success(MyFollowBangumiData(total = 3, ps = 2,
            list = listOf(FollowBangumiItem(seasonId = 70), FollowBangumiItem(seasonId = 71))))
        else Result.success(MyFollowBangumiData(total = 3, ps = 2, list = listOf(FollowBangumiItem(seasonId = 72))))
    }
    prove(preload.requestSucceeded && preload.total == 3 && followIds == setOf(70L, 71L, 72L), "original preload includes nonfirst-page follows")
    prove(pageCalls == listOf(1, 2), "original preload stops on real count/page-size boundary")
    val partial = preloadFollowedSeasonsForType(1, mutableSetOf(), pageSize = 1, maxPages = 2) { _, page, _ ->
        if (page == 1) Result.success(MyFollowBangumiData(total = 5, ps = 1, list = listOf(FollowBangumiItem(seasonId = 70))))
        else Result.failure(IllegalStateException("second page unavailable"))
    }
    prove(partial.total == 5 && partial.requestSucceeded, "original partial preload retains already successful total")
    val stack = listOf(BiliPaiNavKey.MainHost, BiliPaiNavKey.Bangumi(1), BiliPaiNavKey.BangumiDetail(7))
    val replacement = BiliPaiNavBackStackController(stack).replaceTop(BiliPaiNavKey.BangumiDetail(9)).backStack
    prove(replacement.size == stack.size && replacement.dropLast(1) == stack.dropLast(1), "original season replacement preserves catalog and root entries")
    prove(replacement.last() == BiliPaiNavKey.BangumiDetail(9), "original season switch replaces rather than pushes detail")
    println("PASS $checks focused original Bangumi assertions; raw protocol fixture only; no Root/native/account acceptance")
}
