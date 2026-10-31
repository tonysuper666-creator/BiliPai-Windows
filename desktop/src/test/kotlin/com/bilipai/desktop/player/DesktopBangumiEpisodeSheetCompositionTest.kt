package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class, InternalComposeApi::class, coil3.annotation.DelicateCoilApi::class)
class DesktopBangumiEpisodeSheetCompositionTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
        proxy, method, args -> when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Unused${T::class.java.simpleName}"
            else -> error("Unexpected ${T::class.java.simpleName}.${method.name}")
        }
    } as T

    @Test fun actualSheetPagesReordersAndDispatchesSelectedEpisodeAndSeason() {
        val scheduler = TestCoroutineScheduler()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) + CoroutineExceptionHandler { _, e -> errors += e })
        val api = unused<BangumiApi>()
        val repository = DesktopOriginalBangumiHubRepository(api, unused<BilibiliApi>(), unused<SearchApi>(), { null }, { null })
        val commit: (() -> Unit) -> Boolean = { it(); true }
        val hub = BangumiHubViewModel(DesktopBangumiHubEnvironment(repository, scope, { false }, { true }, commit))
        val environment = DesktopOriginalBangumiPagesEnvironment(hub,
            DesktopOriginalBangumiPagesRequests(api, repository, { null }, { null }, { true }, { true }),
            DesktopOriginalBangumiReviewRequests(api, { null }, { true }, { true }),
            unused<DesktopOriginalVideoProgressPort>(), scope, commit, { true }, { error("Unexpected feedback") }, false,
            { BangumiFollowSession(null, null) })
        val folder = java.nio.file.Files.createTempDirectory("bangumi-sheet")
        val previousLoader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        coil3.SingletonImageLoader.reset()
        val images = DesktopApplicationImageLoader(com.bilipai.desktop.data.DesktopRepository(com.bilipai.desktop.data.DesktopSessionStore.temporary()), folder.resolve("images"), { true })
        val events = mutableListOf<String>()
        val detail = BangumiDetail(seasonId = 10, episodes = (1..51).map { BangumiEpisode(id = it.toLong(), title = "$it") },
            seasons = listOf(SeasonInfo(seasonId = 10, title = "当前季度"), SeasonInfo(seasonId = 20, title = "另一季度")))
        val content = Class.forName("com.android.purebilibili.feature.bangumi.BangumiDetailScreenKt")
            .declaredMethods.single { it.name == "EpisodeSelectionSheet" }.apply { isAccessible = true }
        val scene = ImageComposeScene(800, 1000, Density(1f), coroutineContext = scope.coroutineContext) {
            MaterialTheme { CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false),
                LocalDesktopOriginalBangumiPagesEnvironment provides environment, LocalDesktopApplicationImageLoader provides images) {
                content.invoke(null, detail, ({ events += "dismiss" } as () -> Unit),
                    ({ ep: BangumiEpisode -> events += "episode:${ep.id}" } as (BangumiEpisode) -> Unit),
                    ({ id: Long -> events += "season:$id" } as (Long) -> Unit), currentComposer, 0)
            } }
        }
        fun nodes(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
        fun label(text: String) = all().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
        fun frame() {
            repeat(90) { Snapshot.sendApplyNotifications(); scheduler.runCurrent(); scene.render(scheduler.currentTime * 1_000_000).close(); scheduler.advanceTimeBy(20) }
            scheduler.runCurrent(); assertTrue(errors.isEmpty(), errors.toString())
        }
        fun click(text: String) {
            var n: SemanticsNode? = assertNotNull(label(text), text)
            while (n != null && !n.config.contains(SemanticsActions.OnClick)) n = n.parent
            assertTrue(assertNotNull(n).config[SemanticsActions.OnClick].action!!.invoke()); frame()
        }
        try {
            frame(); assertTrue(events.isEmpty()); assertNotNull(label("第1话"))
            click("51-51"); assertNotNull(label("第51话")); assertNull(label("第1话")); assertTrue(events.isEmpty())
            click("第51话"); assertEquals(listOf("episode:51"), events)
            click("正序"); assertNotNull(label("51-2")); assertNotNull(label("第51话"))
            click("1-1"); assertNotNull(label("第1话")); assertNull(label("第51话"))
            click("第1话"); assertEquals(listOf("episode:51", "episode:1"), events)
            click("当前季度"); assertEquals(2, events.size)
            click("另一季度"); assertEquals(listOf("episode:51", "episode:1", "season:20", "dismiss"), events)
            frame(); assertEquals(4, events.size)
        } finally { scene.close(); scope.cancel(); scheduler.runCurrent(); images.close(); coil3.SingletonImageLoader.setUnsafe(previousLoader); folder.toFile().deleteRecursively() }
    }
}
