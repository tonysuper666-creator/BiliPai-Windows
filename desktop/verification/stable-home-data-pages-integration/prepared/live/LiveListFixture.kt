package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.live.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private data class Invocation(val method: String, val arguments: List<Any?>)
private class ScriptedApi {
    val calls = CopyOnWriteArrayList<Invocation>()
    @Volatile var answer: (Invocation) -> Any? = { error("Unexpected ${it.method}") }
    val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
        if (method.name == "toString") "scripted original Live API" else {
            val call = Invocation(method.name, args.orEmpty().toList().dropLast(1))
            calls += call
            answer(call)
        }
    } as BilibiliApi
}
private fun card(id: Long, ad: Boolean = false) = LiveFeedRoomCard(roomid = id, title = "room$id", cover = "", systemCover = "frame$id", uname = "host$id", face = "", online = 42, isAd = ad)
private fun feed(ids: List<Long>, more: Boolean, follow: List<Long> = listOf(80), areas: Boolean = true) = LiveFeedIndexResponse(data = LiveFeedIndexData(
    cardList = ids.map { LiveFeedCard("small_card_v1", LiveFeedCardData(smallCardV1 = card(it))) } + listOf(
        LiveFeedCard("small_card_v1", LiveFeedCardData(smallCardV1 = card(999, true))),
        LiveFeedCard("unknown_module", LiveFeedCardData(smallCardV1 = card(998))),
        LiveFeedCard("my_idol_v1", LiveFeedCardData(myIdolV1 = LiveFeedModuleBlock(follow.map(::card)))),
        LiveFeedCard("area_entrance_v3", LiveFeedCardData(areaEntranceV3 = LiveFeedModuleBlock(if (areas) listOf(LiveFeedRoomCard(title = "Games", areaV2ParentId = 7, areaV2Id = 9)) else emptyList())))
    ), hasMore = if (more) 1 else 0
))
private fun followed(ids: List<Long>, page: Int = 1, total: Int = 1) = FollowedLiveResponse(data = FollowedLiveData(
    list = ids.map { FollowedLiveRoom(roomid = it, title = "follow$it", cover = "", liveStatus = 1) } + FollowedLiveRoom(roomid = 777, title = "offline", liveStatus = 0),
    livingNum = ids.size, pageinfo = PageInfo(page = page, page_size = 50, total_page = total)
))
private val areaResponse = LiveAreaListResponse(data = listOf(LiveAreaParent(id = 7, name = "Games", list = listOf(LiveAreaChild(id = "9", parent_id = "7", name = "Child")))))
private val absentMessages = Proxy.newProxyInstance(MessageApi::class.java.classLoader, arrayOf(MessageApi::class.java)) { _, method, _ -> error("Unexpected messages ${method.name}") } as MessageApi

fun main(args: Array<String>) = runBlocking {
    val dir = Path.of(args[0]);Files.createDirectories(dir)
    val api = ScriptedApi();val alive = AtomicBoolean(true);val admission = Any()
    val failures = CopyOnWriteArrayList<Throwable>()
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, e -> failures += e })
    var token: String? = "fixture-ephemeral-token"
    val env = DesktopHomeProtocolEnvironment(api.api, api.api, absentMessages, scope, alive::get,
        { block -> synchronized(admission) { if (!alive.get()) false else { block();true } } },
        { DesktopFeedSettings.FeedApiType.WEB }, { 20 }, { error("Live does not request WBI") }, { token }, { null }, { null }, {}, {})
    val requests = DesktopOwnedOriginalLiveListRequests(env)
    val cases = mutableListOf<String>();var assertions = 0
    fun verify(value: Boolean, message: String) { check(value) { message };assertions++ }
    try {
        var loadedClasses = 0
        java.util.jar.JarFile(java.io.File(LiveListViewModel::class.java.protectionDomain.codeSource.location.toURI())).use { jar ->
            jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach {
                Class.forName(it.name.removeSuffix(".class").replace('/', '.'), false, LiveListViewModel::class.java.classLoader)
                loadedClasses++
            }
        }
        verify(loadedClasses == 103, "all prepared full UI/protocol classes load without ClassFormatError")
        cases += "all 103 complete UI/protocol classes load on actual97 plus declared raw694"
        api.answer = { call -> check(call.method == "getLiveFeedIndex");feed(listOf(1, 1), true) }
        val first = requests.getLiveFeedHome(1).getOrThrow()
        verify(first.rooms.map { it.roomid } == listOf(1L), "original parser deduplicates and drops ads/unknown modules")
        verify(first.followRooms.map { it.roomid } == listOf(80L) && first.areaEntries.single().parentAreaId == 7, "full original follow/area modules")
        @Suppress("UNCHECKED_CAST") val feedParams = api.calls.single().arguments.single() as Map<String, String>
        verify(feedParams["build"] == "8430300" && feedParams["version"] == "8.43.0" && feedParams["mobi_app"] == "android", "exact original App params")
        verify(feedParams["access_key"] == token && feedParams["relation_page"] == "1" && feedParams["page"] == "1", "sole environment token getter")
        verify(feedParams["sign"]?.length == 32 && !feedParams["ts"].isNullOrBlank(), "original AppSignUtils signature")
        cases += "App feed full parser/modules/dedup/signed original parameters"

        token = null;api.calls.clear();requests.getLiveFeedHome(2)
        @Suppress("UNCHECKED_CAST") val guestParams = api.calls.single().arguments.single() as Map<String, String>
        verify("access_key" !in guestParams && "relation_page" !in guestParams && guestParams["page"] == "2", "absent token omitted")
        cases += "absent-token original request parameters"

        api.calls.clear();api.answer = { call -> check(call.method == "getLiveAppSecondList");LiveAppSecondListResponse(data = LiveAppSecondListData(
            count = 50, list = listOf(card(2), card(2), card(999, true), LiveFeedRoomCard(roomid = 0, title = "invalid")),
            newTags = listOf(LiveSecondSortTag("Newest", "live_time"), LiveSecondSortTag()), hasMore = 0)) }
        val second = requests.getLiveSecondHome(7, 9, 2, "live_time").getOrThrow()
        verify(second.rooms.map { it.roomid } == listOf(2L) && second.hasMore && second.sortTags.size == 1, "second full filter/sort/count policy")
        @Suppress("UNCHECKED_CAST") val secondParams = api.calls.single().arguments.single() as Map<String, String>
        verify(secondParams["parent_area_id"] == "7" && secondParams["area_id"] == "9" && secondParams["page_size"] == "20" && secondParams["sort_type"] == "live_time", "exact original second parameters")
        cases += "App second list full sorting/filter/paging policy"

        api.calls.clear();api.answer = { call -> when (call.method) {
            "getLiveFeedIndex" -> LiveFeedIndexResponse(code = -412)
            "getLiveRecommendList" -> LiveRecommendResponse(data = LiveRecommendData(emptyList()))
            "getLiveList" -> LiveResponse(data = LiveData(list = listOf(LiveRoom(roomid = 3, title = "popular"))))
            "getFollowedLive" -> followed(listOf(4, 4))
            "getLiveAreaList" -> areaResponse
            else -> error(call.method)
        } }
        val fallback = requests.getLiveFeedHome(1).getOrThrow()
        verify(fallback.rooms.single().roomid == 3L && fallback.followRooms.single().roomid == 4L && fallback.areaEntries.single().parentAreaId == 7, "fallback exact reused popular/followed DTOs")
        verify(api.calls.map { it.method } == listOf("getLiveFeedIndex", "getLiveRecommendList", "getLiveList", "getFollowedLive", "getLiveAreaList"), "fallback source request sequence")
        api.calls.clear();verify(requests.getLiveFeedHome(2).isFailure && api.calls.size == 1, "later page does not fall back")
        cases += "first-page recommendation/popular/follow/area fallback; later failure"

        api.calls.clear();api.answer = { call -> when (call.method) {
            "getLiveAppSecondList" -> LiveAppSecondListResponse(code = -400)
            "getLiveList" -> LiveResponse(code = -412, message = "risk")
            "getLiveSecondAreaList" -> LiveSecondAreaResponse(data = LiveSecondAreaData(list = listOf(LiveRoom(roomid = 5, title = "web area")), hasMore = 1, count = 40))
            else -> error(call.method)
        } }
        val webArea = requests.getLiveSecondHome(7, 9, 2, null).getOrThrow()
        verify(webArea.rooms.single().roomid == 5L && webArea.hasMore && webArea.totalCount == 40, "exact original web risk fallback")
        verify(api.calls.last().arguments == listOf("web", 7, 9, 2, "online"), "web fallback source default online and platform")
        cases += "second-list web risk fallback including original sort default"

        api.calls.clear();api.answer = { throw CancellationException("fixture cancel") }
        verify(runCatching { requests.getLiveFeedHome(1) }.exceptionOrNull() is CancellationException && api.calls.size == 1, "cancellation cannot trigger fallback")
        alive.set(false);api.calls.clear()
        verify(runCatching { requests.getLiveFeedHome(1) }.exceptionOrNull() is CancellationException && api.calls.isEmpty(), "retired owner rejects before transport")
        alive.set(true);api.answer = { alive.set(false);feed(listOf(6), false) }
        verify(runCatching { requests.getLiveFeedHome(1) }.exceptionOrNull() is CancellationException, "retire during response rejects result")
        cases += "cancellation plus pre/post owner admission"

        alive.set(true);api.calls.clear()
        api.answer = { call -> when (call.method) {
            "getLiveFeedIndex" -> {
                @Suppress("UNCHECKED_CAST") val p = call.arguments.single() as Map<String, String>
                if (p["page"] == "1") feed(listOf(10, 10), true) else feed(listOf(10, 11), false, follow = emptyList())
            }
            "getLiveAreaList" -> areaResponse
            "getFollowedLive" -> followed(emptyList())
            "getLiveAppSecondList" -> LiveAppSecondListResponse(data = LiveAppSecondListData(list = listOf(card(12)), newTags = listOf(LiveSecondSortTag("Newest", "live_time")), hasMore = 0))
            else -> error(call.method)
        } }
        val owner = object : DesktopLiveListOwner {
            override fun isCurrent() = alive.get()
            override fun commit(action: () -> Unit) = synchronized(admission) { if (!alive.get()) false else { action();true } }
        }
        val vm = LiveListViewModel(scope, requests, owner)
        suspend fun waitFor(predicate: (LiveListUiState) -> Boolean): LiveListUiState = withTimeout(5000) { vm.uiState.first(predicate) }
        val initial = waitFor { !it.isLoading && it.areaList.isNotEmpty() }
        verify(initial.contentItems.map { it.roomId } == listOf(10L) && initial.followItems.single().roomId == 80L && initial.livingCount == 1, "full VM initial recommendation/follow/header")
        vm.loadMore();val more = waitFor { !it.isLoadingMore && it.page == 2 }
        verify(more.contentItems.map { it.roomId } == listOf(10L, 11L) && more.followItems.single().roomId == 80L && !more.hasMore, "full VM append/dedup retains follow and stops")
        val beforeNoMore = api.calls.size;vm.loadMore();delay(40)
        verify(api.calls.size == beforeNoMore, "hasMore gate prevents extra page")
        vm.selectHomeArea(1);val emptyFollow = waitFor { it.selectedAreaIndex == 1 && !it.isLoading }
        verify(emptyFollow.error == null && emptyFollow.contentItems.isEmpty() && emptyFollow.areaEntries.isNotEmpty(), "empty followed is content with category controls")
        vm.selectHomeArea(2);val area = waitFor { it.selectedAreaIndex == 2 && !it.isLoading }
        verify(area.selectedParentAreaId == 7 && area.selectedAreaId == 9 && area.sortTags.single().sortType == "live_time", "full VM original area selection")
        vm.selectSortTag("live_time");waitFor { it.selectedSortType == "live_time" && !it.isLoading }
        vm.toggleShowFirstFrame();verify(vm.uiState.value.showFirstFrame && vm.uiState.value.contentItems.single().resolvedCover(true) == "frame12", "first-frame UI transient source state")
        cases += "full original VM refresh/append/dedup/empty followed/area/sort/first frame"

        val beforeRetire = vm.uiState.value;val beforeCalls = api.calls.size
        alive.set(false);vm.toggleShowFirstFrame();vm.selectHomeArea(0);vm.refresh();vm.loadMore();vm.selectSortTag("online");delay(50)
        verify(vm.uiState.value == beforeRetire && api.calls.size == beforeCalls, "retired click callbacks do not mutate state or issue requests")
        verify(!owner.commit { error("retired publication executed") }, "atomic commit denied")
        verify(failures.isEmpty(), "no unhandled coroutine failures")
        cases += "same ephemeral VM owner gate rejects all retired actions"

        verify(formatLiveViewerCount(12000) == "1.2万" && formatLiveViewerCount(0) == "-", "original count formatting")
        verify(resolveLiveBiliPaiGridColumns(1200, true) == 5 && resolveLiveBiliPaiGridColumns(400, false) == 2, "original responsive grid")
        cases += "original pure format/grid policies"
        Files.writeString(dir.resolve("result.json"), buildJsonObject {
            put("actualRootRuntime", false);put("externalHttp", false);put("userAccount", false)
            put("groups", cases.size);put("assertions", assertions);put("cases", JsonArray(cases.map(::JsonPrimitive)))
            put("preparedClassLoadCount", loadedClasses)
            put("codeSources", buildJsonObject {
                for (type in listOf(LiveListViewModel::class.java, DesktopOwnedOriginalLiveListRequests::class.java, DesktopOriginalHomeLiveProtocol::class.java, BilibiliApi::class.java, LiveFeedIndexResponse::class.java)) put(type.name, type.protectionDomain.codeSource.location.toString())
            })
        }.toString())
        println("PASS ${cases.size} Live source groups / $assertions assertions")
    } finally { job.cancelAndJoin() }
}
