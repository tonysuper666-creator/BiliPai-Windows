package liveproof

import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.DesktopFeedSettings
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.live.toggleLiveFavoriteTag
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

private data class Call(val name: String, val arguments: List<Any?>)
private var assertions = 0
private fun expect(value: Boolean) { check(value); assertions++ }
private fun <T> proxy(type: Class<T>, answer: (Call) -> Any?): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
    if (method.name == "toString") "Memory-only owned original API" else answer(Call(method.name, args.orEmpty().toList().dropLast(1)))
})
private fun origin(type: Class<*>) {
    val resource = "/" + type.name.replace('.', '/') + ".class"
    val bytes = type.getResourceAsStream(resource)!!.use { it.readBytes() }
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location} $hash")
}
fun main(args: Array<String>) = runBlocking {
    val dir = Path.of(args[0])
    val store = DesktopPluginStore(dir)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val gate = Any()
    var current = true
    var nav = NavResponse(data = NavData(wbi_img = WbiImg("https://example.invalid/" + "a".repeat(32) + ".png", "https://example.invalid/" + "b".repeat(32) + ".png")))
    val calls = CopyOnWriteArrayList<Call>()
    val api = proxy(BilibiliApi::class.java) { call ->
        expect(call.name == "getNavInfo")
        nav
    }
    var failCode = 0
    var cancelled = false
    val search = proxy(SearchApi::class.java) { call ->
        calls += call
        if (cancelled) throw CancellationException("Synthetic original API cancellation")
        when (call.name) {
            "searchLive" -> LiveRoomSearchResponse(code = failCode, data = LiveRoomSearchData(page = 2, numPages = 4, numResults = 99,
                result = listOf(LiveRoomSearchItem(roomid = 71, title = "<em class=\"keyword\">Room</em>", user_cover = "//example.invalid/cover.png"))))
            "searchUp" -> SearchUpResponse(data = SearchUpData(page = 3, numPages = 5, result = listOf(SearchUpItem(mid = 82, uname = "<em>Host</em>", upic = "//example.invalid/face.png"))))
            else -> error("Unexpected ${call.name}")
        }
    }
    val message = proxy(MessageApi::class.java) { error("Unused message API must fail") }
    val env = DesktopHomeProtocolEnvironment(api, api, message, scope, { synchronized(gate) { current } }, { action ->
        synchronized(gate) { if (current) { action(); true } else false }
    }, { DesktopFeedSettings.FeedApiType.WEB }, { 20 }, { error("Original search must use actual nav API") },
        { error("Unused token must fail") }, { error("Unused csrf must fail") }, { error("Unused buvid must fail") }, {}, {})
    val binding = DesktopLiveNavigationBinding(env, search, store)
    try {
        val live = binding.requests.searchLive("test", 2, SearchLiveOrder.ONLINE).getOrThrow()
        expect(live.first.single().title == "Room" && live.first.single().cover == "https://example.invalid/cover.png")
        expect(live.second.currentPage == 2 && live.second.totalPages == 4 && live.second.hasMore && live.second.totalResults == 99)
        @Suppress("UNCHECKED_CAST") val liveParams = calls.single().arguments.single() as Map<String, String>
        expect(liveParams["search_type"] == "live_room" && liveParams["keyword"] == "test" && liveParams["page"] == "2" && liveParams["page_size"] == "20")
        expect(liveParams["order"] == "online" && liveParams["platform"] == "pc" && liveParams["web_location"] == "1430654")
        expect(liveParams["w_rid"]?.length == 32 && !liveParams["wts"].isNullOrBlank())
        val ups = binding.requests.searchUp("test", 3).getOrThrow()
        expect(ups.first.single().uname == "Host" && ups.second.currentPage == 3 && ups.second.hasMore)
        @Suppress("UNCHECKED_CAST") val upParams = calls.last().arguments.single() as Map<String, String>
        expect(upParams["search_type"] == "bili_user" && upParams["page"] == "3" && upParams["order_sort"] == "0" && upParams["user_type"] == "0")
        nav = NavResponse(code = -101)
        binding.requests.searchLive("guest", 1, SearchLiveOrder.LIVE_TIME).getOrThrow()
        @Suppress("UNCHECKED_CAST") val unsigned = calls.last().arguments.single() as Map<String, String>
        expect("w_rid" !in unsigned && "wts" !in unsigned && unsigned["order"] == "live_time")
        failCode = -412
        expect(binding.requests.searchLive("failure", 1, SearchLiveOrder.ONLINE).exceptionOrNull()?.message == "搜索请求被拦截，请稍后重试")
        cancelled = true
        expect(runCatching { binding.requests.searchLive("cancel", 1, SearchLiveOrder.ONLINE) }.exceptionOrNull() is CancellationException)
        cancelled = false
        println("GROUP original search params/WBI/unsigned fallback/raw page/cleanup/cancellation")

        val oldStarted = CompletableDeferred<Unit>();val late = CompletableDeferred<Unit>();val newStarted = CompletableDeferred<Unit>();val newRelease = CompletableDeferred<Unit>()
        var visible = "initial";var busy = false;var oldFinish = false
        val old = scope.launch {
            val request = binding.begin("search-submit", "search")
            request.publish { busy = true };oldStarted.complete(Unit)
            try {
                withContext(NonCancellable) { late.await() }
                Result.success("old").onOwnedSuccess(request) { visible = it }
            } finally { request.finish { busy = false; oldFinish = true } }
        }
        oldStarted.await()
        val replacement = scope.launch {
            val request = binding.begin("search-submit", "search")
            try { request.publish { visible = "replacement"; busy = true };newStarted.complete(Unit);newRelease.await() }
            finally { request.finish { busy = false } }
        }
        newStarted.await();late.complete(Unit);old.join()
        expect(visible == "replacement" && busy && !oldFinish)
        newRelease.complete(Unit);replacement.join();expect(!busy)
        var afterRetire = false
        val retired = CompletableDeferred<Unit>();val finishRetired = CompletableDeferred<Unit>()
        val oldAccount = scope.launch {
            val request = binding.begin("following-more")
            retired.complete(Unit);finishRetired.await()
            try { Result.success(1).onOwnedSuccess(request) { afterRetire = true } }
            finally { request.finish { afterRetire = true } }
        }
        retired.await();synchronized(gate) { current = false };finishRetired.complete(Unit);oldAccount.join()
        expect(!afterRetire)
        synchronized(gate) { current = true }
        val cancelledRequestDone = CompletableDeferred<Unit>()
        val cancelledJob = scope.launch {
            val request = binding.begin("area-page", "area")
            try { request.publish { busy = true };cancelledRequestDone.complete(Unit);awaitCancellation() }
            finally { request.finish { busy = false } }
        }
        cancelledRequestDone.await();cancelledJob.cancelAndJoin();expect(!busy)
        println("GROUP old callback/finally cannot clear replacement; epoch rejects; cancellation releases only own busy")

        fun tag(id: Int) = LiveFavoriteTagEntry(parentAreaId = 1, areaId = id, title = "area$id", coverUrl = "", parentTitle = "parent")
        val tags = (1..15).map(::tag)
        binding.preferences.setLiveFavoriteTags(listOf(tag(1), tag(1), tag(2).copy(parentAreaId = 0)) + tags)
        val saved = binding.preferences.favoriteTags.first()
        expect(saved.size == 12 && saved.map { it.areaId } == (1..12).toList())
        val toggled = toggleLiveFavoriteTag((1..8).map(::tag), tag(9))
        expect(toggled.map { it.areaId } == (2..9).toList())
        expect(toggleLiveFavoriteTag(toggled, tag(9)).map { it.areaId } == (2..8).toList())
        binding.close()
        expect(runCatching { binding.preferences.setLiveFavoriteTags(listOf(tag(90))) }.exceptionOrNull() is CancellationException)
        expect(binding.preferences.favoriteTags.first() == saved)
        expect(runCatching { binding.begin("area-page") }.exceptionOrNull() is CancellationException)
        println("GROUP sole global original live_favorite_tags decode/setter limits and original UI toggle8; closed rejects")
        listOf(DesktopLiveNavigationBinding::class.java, DesktopOriginalLiveSearchProtocol::class.java, DesktopDynamicUpSearch::class.java,
            DesktopOriginalLiveListProtocol::class.java, DesktopOriginalHomeLiveProtocol::class.java, DesktopHomeProtocolEnvironment::class.java,
            DesktopPluginStore::class.java, LiveRoomSearchItem::class.java, SearchUpItem::class.java, LiveFavoriteTagEntry::class.java).forEach(::origin)
        println("RESULT assertions=$assertions groups=3")
    } finally { binding.close();scope.cancel() }
}
