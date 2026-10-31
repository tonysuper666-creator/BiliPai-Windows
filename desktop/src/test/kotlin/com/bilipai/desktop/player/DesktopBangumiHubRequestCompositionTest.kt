package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.AccountSessionIdentity
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalBangumiHubRepository
import com.android.purebilibili.feature.bangumi.*
import com.bilipai.desktop.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.serialization.json.JsonPrimitive
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.ui.LocalDesktopUpstreamAndroidContext
import java.nio.file.Files
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
        val repository = DesktopOriginalBangumiHubRepository(
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

    @Test fun channelSwitchRetainsPendingPreparationAndPublishesToItsOwnChannel() = runBlocking {
        val f = Fixture()
        try {
            f.vm.setShowPgcTimeline(false); f.vm.initialize(1)
            val first = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }.associateBy { it.name }
            val oldIndex = first.getValue("getBangumiIndexResult")
            first.getValue("getMyFollowBangumi").complete(follow(201))
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.follows?.page == 1 }
            val jobsField = f.vm.javaClass.getDeclaredField("homeJobs").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val jobs = jobsField.get(f.vm) as Map<String, Job>
            val oldJob = requireNotNull(jobs["recommendations_BANGUMI"])
            f.vm.prepareHomeChannel(BangumiChannel.CINEMA)
            val second = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }.associateBy { it.name }
            f.vm.selectChannel(BangumiChannel.CINEMA)
            f.vm.prepareHomeChannel(BangumiChannel.CINEMA)
            assertEquals(BangumiChannel.CINEMA, f.vm.uiState.value.channel)
            assertTrue(oldJob.isActive, "Upstream public request is retained across channel selection")
            oldIndex.complete(index(101))
            withTimeout(5000) { oldJob.join() }
            assertEquals(listOf(101L), f.home().recommendations.items.map { it.seasonId })
            assertTrue(f.vm.uiState.value.homeStates.getValue(BangumiChannel.CINEMA).recommendations.items.isEmpty())
            second.getValue("getBangumiIndexResult").complete(index(301))
            second.getValue("getMyFollowBangumi").complete(follow(401))
            f.state { it.homeStates[BangumiChannel.CINEMA]?.let { h -> h.recommendations.page == 1 && h.follows.page == 1 } == true }
            assertEquals(listOf(301L), f.vm.uiState.value.homeStates.getValue(BangumiChannel.CINEMA).recommendations.items.map { it.seasonId })
            assertEquals(4, f.all.size)
            assertTrue(f.requests.tryReceive().isFailure)
        } finally { f.close() }
    }

    @Test fun closingVmScopeCancelsAllPendingChannelsWithoutLateCachePublication() = runBlocking {
        val f = Fixture()
        try {
            f.vm.setShowPgcTimeline(false); f.vm.initialize(1)
            val first = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }
            f.vm.prepareHomeChannel(BangumiChannel.CINEMA)
            val second = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }
            val before = f.vm.uiState.value
            val children = f.job.children.toList()
            f.job.cancel()
            (first + second).forEach { it.complete(if (it.name == "getBangumiIndexResult") index(999) else follow(999)) }
            withTimeout(5000) { f.job.join() }
            assertTrue(children.all { it.isCancelled && it.isCompleted })
            assertSame(before, f.vm.uiState.value)
            assertTrue(f.failures.isEmpty())
            assertEquals(4, f.all.size)
        } finally { f.close() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun composedPagerPreparesRealVmChannelsWithoutDuplicateRequests() = runBlocking {
        val f = Fixture()
        f.vm.setShowPgcTimeline(false)
        f.vm.initialize(1)
        val prepared = mutableListOf<BangumiChannel>()
        var now = 0L
        var mounted by mutableStateOf(true)
        val directory = Files.createTempDirectory("bangumi-preload-compose")
        val context = DesktopPluginContext(DesktopPluginStore(directory))
        val scene = ImageComposeScene(800, 900, Density(1f)) {
            if (mounted) {
                val state by f.vm.uiState.collectAsState()
                val pager = rememberBangumiChannelPagerState(state.channel, true, f.vm::selectChannel)
                MaterialTheme {
                    CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false), LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) {
                    BangumiHubContent(
                        state = state,
                        channelPagerState = pager,
                        onPrepareChannel = { prepared += it; f.vm.prepareHomeChannel(it) },
                        onBangumiClick = { _ -> error("Unexpected onBangumiClick") },
                        onEpisodeClick = { _, _ -> error("Unexpected onEpisodeClick") },
                        onPlayFollow = { _ -> error("Unexpected onPlayFollow") },
                        onRefreshHome = { error("Unexpected onRefreshHome") },
                        onLoadMoreHomeRecommendations = { error("Unexpected onLoadMoreHomeRecommendations") },
                        onLoadMoreHomeFollows = { error("Unexpected onLoadMoreHomeFollows") },
                        onRetryTimeline = { error("Unexpected onRetryTimeline") },
                        onTimelineRangeSelected = { _ -> error("Unexpected onTimelineRangeSelected") },
                        onOpenIndex = { error("Unexpected onOpenIndex") },
                        onOpenFollow = { error("Unexpected onOpenFollow") },
                        onIndexCategorySelected = { _ -> error("Unexpected onIndexCategorySelected") },
                        onIndexFilterSelected = { _, _ -> error("Unexpected onIndexFilterSelected") },
                        onToggleFiltersExpanded = { error("Unexpected onToggleFiltersExpanded") },
                        onRetryIndexConditions = { error("Unexpected onRetryIndexConditions") },
                        onRetryIndexResults = { error("Unexpected onRetryIndexResults") },
                        onLoadMoreIndexResults = { error("Unexpected onLoadMoreIndexResults") },
                        onFollowStatusSelected = { _ -> error("Unexpected onFollowStatusSelected") },
                        onRefreshFollow = { error("Unexpected onRefreshFollow") },
                        onLoadMoreFollow = { error("Unexpected onLoadMoreFollow") },
                        onToggleFollowSelection = { _ -> error("Unexpected onToggleFollowSelection") },
                        onSelectAllFollow = { error("Unexpected onSelectAllFollow") },
                        onClearFollowSelection = { error("Unexpected onClearFollowSelection") },
                        onMoveSelectedFollow = { _ -> error("Unexpected onMoveSelectedFollow") },
                        onMoveSingleFollow = { _, _ -> error("Unexpected onMoveSingleFollow") },
                        onUnfollowSingle = { _ -> error("Unexpected onUnfollowSingle") },
                        onSearchCategorySelected = { _ -> error("Unexpected onSearchCategorySelected") },
                        onLoadMoreSearch = { error("Unexpected onLoadMoreSearch") },
                        onSaveCover = { _, _ -> error("Unexpected onSaveCover") },
                    )
                    }
                }
            }
        }
        fun frames(n: Int) { repeat(n) { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() } }
        try {
            frames(10)
            assertTrue(BangumiChannel.BANGUMI in prepared)
            f.vm.selectChannel(BangumiChannel.CINEMA); frames(60)
            assertTrue(BangumiChannel.CINEMA in prepared)
            val calls = withTimeout(5000) { List(4) { f.requests.receive() } }
            assertEquals(mapOf("getBangumiIndexResult" to 2, "getMyFollowBangumi" to 2), calls.groupingBy { it.name }.eachCount())
            assertEquals(setOf(null, "102"), calls.filter { it.name == "getBangumiIndexResult" }.map { (it.args.single() as Map<*, *>)["index_type"] }.toSet())
            assertTrue(calls.filter { it.name == "getBangumiIndexResult" }.all { (it.args.single() as Map<*, *>)["season_type"] == "1" })
            calls.forEach { it.complete(if (it.name == "getBangumiIndexResult") BangumiIndexResponse(data = BangumiIndexData(hasNext = 0, list = emptyList())) else MyFollowBangumiResponse(data = MyFollowBangumiData(total = 0, list = emptyList()))) }
            f.state { state -> BangumiChannel.entries.all { channel -> state.homeStates[channel]?.let { !it.recommendations.isLoading && !it.follows.isLoading && it.recommendations.page == 1 && it.follows.page == 1 } == true } }
            repeat(2) {
                f.vm.selectChannel(BangumiChannel.BANGUMI); frames(60)
                f.vm.selectChannel(BangumiChannel.CINEMA); frames(60)
            }
            assertEquals(4, f.all.size)
            assertTrue(f.requests.tryReceive().isFailure)
            mounted = false; frames(10)
            val count = prepared.size
            frames(30)
            assertEquals(count, prepared.size)
            assertEquals(4, f.all.size)
        } finally { scene.close(); f.close(); directory.toFile().deleteRecursively() }
    }

    private inline fun <reified T> unusedPagePort(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.singleOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Unused page port"
            else -> error("Unexpected ${T::class.java.simpleName}.${method.name}")
        }
    } as T

    @Test fun publicHomeTabDispatchesFollowPlaySeparatelyFromPosterDetail() = verifyPublicEntry(false)
    @Test fun publicScreenDispatchesFollowPlaySeparatelyFromPosterDetail() = verifyPublicEntry(true)

    @OptIn(ExperimentalComposeUiApi::class, coil3.annotation.DelicateCoilApi::class)
    private fun verifyPublicEntry(screen: Boolean) = runBlocking {
        for (independentScreen in listOf(screen)) {
            val f = Fixture()
            val directory = Files.createTempDirectory("bangumi-page-click")
            val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("preferences")))
            context.store.update("settings", mapOf("show_pgc_timeline" to JsonPrimitive(false)))
            val preferences = DesktopOriginalHomePreferences.create(context.store, f.scope, false, { false })
            val lifecycle = object : LifecycleOwner {
                override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            val home = DesktopHomeEnvironment(preferences, context, lifecycle, MutableStateFlow(false),
                unusedPagePort<DesktopHomeAnalyticsPort>(), { DesktopOriginalHomeGlobalNamespace(context.store, it) },
                MutableStateFlow(null), { _, _ -> error("Unexpected palette") }, { error("Unexpected edge to edge") },
                { _, _ -> error("Unexpected system bars") }, unusedPagePort<DesktopHomeEmbeddedPages>(),
                { _, _ -> error("Unexpected save cover") }, { error("Unexpected feedback") }, unusedPagePort<DesktopHomeOverlayPorts>())
            val api = unusedPagePort<BangumiApi>()
            val commit: (() -> Unit) -> Boolean = { it(); true }
            val pages = DesktopOriginalBangumiPagesEnvironment(f.vm,
                com.android.purebilibili.data.repository.DesktopOriginalBangumiPagesRequests(api, f.repository, { null }, { null }, { true }, { true }),
                com.android.purebilibili.data.repository.DesktopOriginalBangumiReviewRequests(api, { null }, { true }, { true }),
                unusedPagePort<DesktopOriginalVideoProgressPort>(), f.scope, commit, { true }, { error("Unexpected feedback") }, false,
                { BangumiFollowSession(null, null) })
            val previousLoader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
            coil3.SingletonImageLoader.reset()
            val images = DesktopApplicationImageLoader(com.bilipai.desktop.data.DesktopRepository(com.bilipai.desktop.data.DesktopSessionStore.temporary()), directory.resolve("images"), { true })
            val pushed = mutableListOf<BiliPaiNavKey>()
            val commands = Proxy.newProxyInstance(DesktopOriginalRootRouteCommands::class.java.classLoader, arrayOf(DesktopOriginalRootRouteCommands::class.java)) { _, method, args ->
                check(method.name == "push"); pushed += args!![0] as BiliPaiNavKey; true
            } as DesktopOriginalRootRouteCommands
            val navigation = desktopOriginalRootHomeNavigation(commands, null, null)
            f.vm.setShowPgcTimeline(false); f.vm.initialize(1)
            val initial = withTimeout(5000) { listOf(f.requests.receive(), f.requests.receive()) }.associateBy { it.name }
            initial.getValue("getBangumiIndexResult").complete(BangumiIndexResponse(data = BangumiIndexData(hasNext = 0, list = emptyList())))
            initial.getValue("getMyFollowBangumi").complete(MyFollowBangumiResponse(data = MyFollowBangumiData(total = 1,
                list = listOf(FollowBangumiItem(seasonId = 19, title = "Synthetic follow", cover = "")))))
            f.state { it.homeStates[BangumiChannel.BANGUMI]?.follows?.page == 1 }
            var now = 0L
            val scene = ImageComposeScene(1000, 1200, Density(1f)) {
                MaterialTheme {
                    val backToTop = rememberBangumiBackToTopBindings(context.store, { true }, commit)
                    CompositionLocalProvider(LocalDesktopBackToTopBindings provides backToTop, LocalDesktopHomeEnvironment provides home,
                        LocalDesktopOriginalBangumiPagesEnvironment provides pages,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalDesktopApplicationImageLoader provides images,
                        LocalLifecycleOwner provides lifecycle,
                        LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false)) {
                        if (independentScreen) BangumiScreen(onBack = { error("Unexpected back") },
                            onBangumiClick = navigation.onBangumiSeasonClick,
                            onBangumiEpisodeClick = navigation.onBangumiEpisodeClick,
                            onPlayFollow = navigation.onBangumiPlayFollow, viewModel = f.vm)
                        else HomeBangumiTabPage(PaddingValues(0.dp), navigation.onBangumiSeasonClick,
                            navigation.onBangumiEpisodeClick, navigation.onBangumiPlayFollow, viewModel = f.vm)
                    }
                }
            }
            fun frames() { repeat(60) { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() } }
            fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
            fun click(label: String) {
                var node: SemanticsNode? = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }.firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
                }
                assertNotNull(node, label)
                while (node != null && !node.config.contains(SemanticsActions.OnClick)) node = node.parent
                assertTrue(assertNotNull(node).config[SemanticsActions.OnClick].action!!.invoke())
                frames()
            }
            try {
                frames(); assertTrue(pushed.isEmpty())
                click("开始观看")
                assertEquals(listOf<BiliPaiNavKey>(BiliPaiNavKey.BangumiPlayer(19, 0)), pushed)
                click("Synthetic follow")
                assertEquals(listOf<BiliPaiNavKey>(BiliPaiNavKey.BangumiPlayer(19, 0), BiliPaiNavKey.BangumiDetail(19)), pushed)
                frames(); assertEquals(2, pushed.size)
            } finally { scene.close(); f.close(); images.close(); coil3.SingletonImageLoader.setUnsafe(previousLoader); directory.toFile().deleteRecursively() }
        }
    }
}
