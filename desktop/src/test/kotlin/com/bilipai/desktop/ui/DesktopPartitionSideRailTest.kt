@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.DesktopOriginalProfileSplashProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.partition.PartitionFeedViewModel
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*

/** Same test source compiles on the unchanged base: exercise mounted production consumers,
 * never the new bridge by name, and never a replacement settings control or partition view. */
class DesktopPartitionSideRailTest {
    @Test fun narrowHostSwitchPreservesSelectionAndBothScrollPositions(): Unit = runBlocking { switchFromHost(360) }
    @Test fun wideHostSwitchPreservesSelectionAndBothScrollPositions(): Unit = runBlocking { switchFromHost(1200) }

    private suspend fun CoroutineScope.switchFromHost(width: Int) {
        Harness(coroutineContext).use { h ->
            val partition = h.partition(width)
            val settings = h.settings()
            h.await("original list and default left rail") { partition.text("全站") != null && partition.text("video-1") != null }
            h.assertSide(partition, false, width)
            h.await("real HOME_FEED exposes original-key setting") { settings.text("分区侧栏位置") != null }
            partition.clickText("音乐")
            h.await("selected region data") { h.vm.uiState.value.selectedPartition.id == 3 && !h.vm.uiState.value.isLoading }
            h.scroll(partition, rail = true, 180f)
            h.scroll(partition, rail = false, 460f)
            h.settle()
            val before = partition.scrollPositions()
            assertEquals(2, before.size)
            assertTrue(before.all { it > 0f }, "Both original lazy lists actually scrolled: $before")
            settings.clickText("右侧")
            h.await("right control writes shared canonical settings and moves live rail") {
                h.context.store.preferences("settings")["partition_side_rail_on_right"].toString() == "true" &&
                    partition.rail().boundsInRoot.left > width / 2f
            }
            h.assertSide(partition, true, width)
            assertEquals(3, h.vm.uiState.value.selectedPartition.id)
            assertEquals(before, partition.scrollPositions(), "Moving the physical children must preserve both lazy states")
            settings.clickText("左侧")
            h.await("left control moves retained rail back") { partition.rail().boundsInRoot.left < width / 2f }
            h.assertSide(partition, false, width)
            assertEquals(before, partition.scrollPositions())
            assertEquals(3, h.vm.uiState.value.selectedPartition.id)
            assertTrue(h.failures.isEmpty(), h.failures.toString())
            println("actualHost width=$width left->right->left selected=3 scroll=$before retained=true")
        }
    }

    @Test fun freshBackingRestoresRightInNarrowAndWideLayouts(): Unit = runBlocking {
        val cold = Files.createTempDirectory("partition-cold-ui-")
        try {
            Harness(coroutineContext).use { original ->
                val settings = original.settings()
                original.await("real HOME_FEED right control") { settings.text("右侧") != null }
                settings.clickText("右侧")
                original.await("durable right setting") {
                    original.context.store.preferences("settings")["partition_side_rail_on_right"].toString() == "true"
                }
                Files.copy(original.context.store.root.resolve("plugin-settings.json"), cold.resolve("plugin-settings.json"))
            }
            Harness(coroutineContext, DesktopPluginContext(DesktopPluginStore(cold))).use { restored ->
                for (width in listOf(360, 1200)) {
                    val partition = restored.partition(width)
                    restored.await("cold original content width=$width") { partition.text("video-1") != null }
                    restored.assertSide(partition, true, width)
                }
                val settings = restored.settings()
                restored.await("cold HOME_FEED selected choice") { settings.selectedText("右侧") }
                assertTrue(restored.failures.isEmpty())
                println("cold separate backing restored right at widths=360,1200; actualHost selected=true")
            }
        } finally { cold.toFile().deleteRecursively() }
    }

    private class Harness(private val dispatcher: CoroutineContext, supplied: DesktopPluginContext? = null) : AutoCloseable {
        private val fixture = DesktopPageTestFixture()
        val context = supplied ?: fixture.pluginContext
        private val job = SupervisorJob()
        private val scope = CoroutineScope(dispatcher + job)
        private val originalHome = fixture.home
        private val home = if (supplied == null) originalHome else DesktopHomeEnvironment(
            runBlocking { DesktopOriginalHomePreferences.create(context.store, scope, false, { false }) },
            context, fixture.lifecycleOwner, originalHome.subscriptionPluginEnabled, originalHome.analytics,
            { DesktopOriginalHomeGlobalNamespace(context.store, it) }, originalHome.wallpaperPalette,
            originalHome.loadWallpaperPalette, originalHome.ensureEdgeToEdge, originalHome.applyHomeSystemBars,
            originalHome.pages, originalHome.saveCover, originalHome.feedback, originalHome.overlays)
        private val videos = object : DesktopHomeVideoRequests by unused<DesktopHomeVideoRequests>() {
            override suspend fun getPopularVideos(page: Int) = Result.success(rows(page))
            override suspend fun getRegionVideos(tid: Int, page: Int) = Result.success(rows(page))
            private fun rows(page: Int) = (1..40).map { VideoItem(bvid = "BV-$page-$it", title = "video-$it") }
        }
        private val environment = DesktopPartitionEnvironment(videos, scope, { true }, { it(); true })
        val vm = PartitionFeedViewModel(environment)
        val failures = mutableListOf<Throwable>()
        private val scenes = mutableListOf<Scene>()
        private val background = object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) {}
        }
        private val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
        fun partition(width: Int) = scene(width, 480) {
            DesktopOriginalPartitionContent(environment, home, vm, PaddingValues(start = 12.dp, end = 20.dp),
                {}, {}, 0, null)
        }
        fun settings(): Scene {
            val services = DesktopOriginalHomeSettingsRootServices(home.settings, DesktopFavoritePreferences(context.store), {}) {
                    pageScope, owns, admit, _ ->
                // The real settings owner also constructs its PGC protocol. Mount its API
                // explicitly; any unrelated request still fails through unused().
                val favorite = DesktopFavoriteEnvironment(pageScope, unused(), null, null, unused(),
                    owns, { null }, { null }, {}, null, null, null, null, null, null)
                DesktopProfileEnvironment(pageScope, owns, admit, unused(), unused(), unused(), unused(),
                    DesktopOriginalProfileSplashProtocol(unused(), unused()), unused(), favorite.favorite, favorite.pgc,
                    { null }, unused(), unused(), unused(), unused(), unused())
            }
            val identity = Any()
            return scene(800, 3000) {
                DesktopOriginalHomeSettingsRootHost(identity, services, context, { true }, { it(); true },
                    {}, {}, { failures += it })
            }
        }
        private fun scene(width: Int, height: Int, content: @Composable () -> Unit): Scene {
            val navigation = object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
            return Scene(ImageComposeScene(width, height, Density(1f), coroutineContext = dispatcher) {
                DesktopAppearanceTheme(DesktopThemeSettings(), systemLanguageTags = listOf("zh-CN")) {
                    CompositionLocalProvider(LocalDesktopHomePlatform provides platform,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalLifecycleOwner provides fixture.lifecycleOwner,
                        LocalNavigationEventDispatcherOwner provides navigation) { content() }
                }
            }, navigation).also { scenes += it }
        }
        suspend fun await(description: String, condition: () -> Boolean) {
            try {
                withTimeout(8000) {
                    while (true) {
                        scenes.forEach { it.render() }
                        if (condition()) break
                        delay(16)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("$description; texts=${scenes.map { it.labels() }}; failures=$failures", e)
            }
        }
        suspend fun settle() { repeat(12) { scenes.forEach { it.render() }; delay(16) } }
        suspend fun scroll(scene: Scene, rail: Boolean, amount: Float) {
            val node = if (rail) scene.rail() else scene.list()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, amount))
            settle()
        }
        fun assertSide(scene: Scene, right: Boolean, width: Int) {
            val rail = scene.rail().boundsInRoot
            val list = scene.list().boundsInRoot
            assertEquals(92f, rail.width, 1f)
            val contentWidth = minOf(width, 1000)
            val contentLeft = (width - contentWidth) / 2f
            if (right) {
                assertEquals(contentLeft + contentWidth - 92f, rail.left, 1f)
                assertTrue(list.right <= rail.left + 1f, "$list $rail")
            } else {
                assertEquals(contentLeft, rail.left, 1f)
                assertTrue(list.left >= rail.right - 1f, "$list $rail")
            }
            println("layout width=$width right=$right rail=$rail list=$list")
        }
        override fun close() { scenes.asReversed().forEach { it.close() }; job.cancel(); fixture.close() }
    }

    private class Scene(private val scene: ImageComposeScene, private val navigation: NavigationEventDispatcherOwner) : AutoCloseable {
        fun render() { scene.render(System.nanoTime()).close() }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        private fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        fun text(value: String) = nodes().firstOrNull { nodeText(it, value) }
        private fun nodeText(node: SemanticsNode, value: String) =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        fun labels() = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        private fun clickNode(value: String) = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any { nodeText(it, value) }
        }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
        fun clickText(value: String) { assertTrue(requireNotNull(clickNode(value).config[SemanticsActions.OnClick].action).invoke()) }
        fun selectedText(value: String) = clickNode(value).config.getOrNull(SemanticsProperties.Selected) == true
        private fun scrollNodes() = nodes().filter {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                it.config.getOrNull(SemanticsActions.ScrollBy) != null
        }
        fun rail() = scrollNodes().minBy { it.boundsInRoot.width }
        fun list() = scrollNodes().maxBy { it.boundsInRoot.width }
        fun scrollPositions() = listOf(rail(), list()).map { it.config[SemanticsProperties.VerticalScrollAxisRange].value() }
        override fun close() { scene.close(); navigation.navigationEventDispatcher.dispose() }
    }

    private companion object {
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "Uncalled ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unrelated port invoked: ${T::class.java.simpleName}.${method.name}")
            }
        } as T
    }
}
