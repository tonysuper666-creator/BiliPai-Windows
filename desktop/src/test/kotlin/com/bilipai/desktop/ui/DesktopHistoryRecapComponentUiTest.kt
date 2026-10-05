package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.plugin.feed.FeedReadingStore
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.list.*
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Whole generated v029 HistoryRecapCard, original repository/aggregators and the
 * actual owned binding. HTTP is intercepted before I/O; Store and read timestamps
 * are temporary. ImageComposeScene paints CPU pixels without an AWT window.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopHistoryRecapComponentUiTest {
    @Test fun wholeOriginalHeaderShowsActualStatsAndChangesWindowThroughPointerInBothStyles(): Unit = runBlocking {
        for (style in AppUiStyle.entries) exercise(style, width = 840)
    }

    @Test fun narrowOriginalHeaderKeepsEssentialTextAndActionsInsideMeasuredClientInBothStyles(): Unit = runBlocking {
        for (style in AppUiStyle.entries) exercise(style, width = 360)
    }

    private suspend fun exercise(style: AppUiStyle, width: Int) {
        val harness = Harness()
        var scene: Scene? = null
        try {
            harness.seedRealReads()
            val mounted = Scene(harness, style, width)
            scene = mounted
            for ((window, count, duration) in listOf(
                Triple(PersonalRecapWindow.TODAY, 1, "约 2 分钟"),
                Triple(PersonalRecapWindow.LAST_SEVEN_DAYS, 2, "约 3 分钟"),
                Triple(PersonalRecapWindow.LAST_MONTH, 3, "约 4 分钟"),
            )) {
                if (window != PersonalRecapWindow.TODAY) mounted.clickText(window.label)
                mounted.await("$style $width ${window.label} actual repository publication") {
                    harness.viewModel.recapSnapshots[window]?.let { snapshot ->
                        snapshot.rssStats.readCount == count && snapshot.videoStats.videoCount == count
                    } == true && mounted.hasText("已读 $count 篇") && mounted.hasText("看了 $count 个视频")
                }
                val snapshot = harness.viewModel.recapSnapshots.getValue(window)
                assertEquals(1, snapshot.videoStats.finishedCount)
                assertEquals(count, snapshot.rssStats.readTimestampsMs.size)
                assertEquals(count, snapshot.videoStats.watchTimestampsMs.size,
                    "Original aggregator excludes the synthetic LIVE item")
                assertEquals(17L, snapshot.videoStats.topUps.first().mid)
                mounted.assertActualChart(window, count,
                    "${style.name.lowercase()}-$width-${window.name.lowercase()}")
                for (text in listOf("我的回顾", "今日", "近七天", "近一个月", "已读 $count 篇",
                    "看了 $count 个视频", duration, "阅读与观看趋势", "视频看完比例", "1 / $count", "最近爱看", "示例UP")) {
                    mounted.assertFullText(text)
                }
            }
            mounted.clickText("示例UP")
            mounted.await("actual original UP callback") { harness.upClicks.toList() == listOf(17L) }
            assertTrue(harness.unexpected.isEmpty(), harness.unexpected.joinToString())
            val historyCalls = harness.requests.filter { it.url.encodedPath == "/x/web-interface/history/cursor" }
            assertTrue(historyCalls.size >= 3, "Time-window changes really use the original repository")
            assertTrue(historyCalls.all { it.method == "GET" && it.url.queryParameter("ps") == "30" })
        } finally {
            scene?.close()
            harness.close()
        }
    }

    private class Harness {
        val directory = Files.createTempDirectory("history-recap-cpu-")
        val context = DesktopPluginContext(DesktopPluginStore(directory))
        private val alive = AtomicBoolean(true)
        private val scopeJob = SupervisorJob()
        private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        val requests = ConcurrentLinkedQueue<Request>()
        val unexpected = ConcurrentLinkedQueue<String>()
        val upClicks = ConcurrentLinkedQueue<Long>()
        private val nowMs = System.currentTimeMillis()
        private val todayMs = maxOf(resolvePersonalRecapWindowStart(nowMs, PersonalRecapWindow.TODAY), nowMs - 1_000L)
        private val sevenMs = nowMs - 3L * 86_400_000L
        private val monthMs = nowMs - 12L * 86_400_000L
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val history = listOf(
            item("today", todayMs, progress = -1),
            item("three-days", sevenMs, progress = 60),
            item("twelve-days", monthMs, progress = 90, business = "pgc", mid = 18L, author = "另一UP"),
            item("unsupported-live", todayMs, progress = 120, business = "live"),
        )
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            check(request.method == "GET") { unexpected.add("mutation ${request.method}"); "Synthetic fixture refuses mutations" }
            val text = when (request.url.encodedPath) {
                "/x/v2/history/shadow" -> "{\"code\":0,\"data\":false}"
                "/x/web-interface/history/cursor" -> json.encodeToString(HistoryResponse(data = HistoryListData(
                    list = history, cursor = HistoryCursor(max = 0L, view_at = 0L, business = "archive"))))
                else -> error("Unknown synthetic endpoint ${request.url.encodedPath}".also { unexpected.add(it) })
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("intercepted synthetic history")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        private val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
        private val environment = DesktopFavoriteEnvironment(scope, api, null, null, null, alive::get,
            { "synthetic" }, { 17L }, { unexpected.add("unexpected feedback: $it") }, MutableSharedFlow<Long>(),
            { _, _ -> 0L }, { false }, {}, { null }, { "android" })
        val binding = DesktopPersonalRecapBinding(context, environment, DesktopFavoritePreferences(context.store), alive::get,
            { action -> if (!alive.get()) false else { action(); true } })
        val viewModel = HistoryViewModel(environment)
        suspend fun seedRealReads() {
            FeedReadingStore.recordRead(context, "builtin:test\u001ftoday", atMs = todayMs)
            FeedReadingStore.recordRead(context, "builtin:test\u001fthree-days", atMs = sevenMs)
            FeedReadingStore.recordRead(context, "builtin:test\u001ftwelve-days", atMs = monthMs)
        }
        suspend fun close() {
            alive.set(false)
            scopeJob.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            directory.toFile().deleteRecursively()
        }
        private fun item(bvid: String, atMs: Long, progress: Int, business: String = "archive", mid: Long = 17L, author: String = "示例UP") =
            HistoryData(title = bvid, author_name = author, author_mid = mid, author_face = "", duration = 120,
                progress = progress, view_at = atMs / 1_000L,
                history = HistoryPage(bvid = bvid, cid = 7007L, oid = 170001L, business = business, page = 1))
    }

    private class Background : DesktopHomeWindowBackgroundPort {
        override val isInBackground = false
        private val listeners = linkedSetOf<DesktopHomeWindowBackgroundPort.Listener>()
        override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) { check(listeners.add(listener)) }
        override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) { check(listeners.remove(listener)) }
    }

    private class ResumedLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private inner class Scene(harness: Harness, style: AppUiStyle, private val width: Int) : AutoCloseable {
        private val height = 720
        private val lifecycle = ResumedLifecycle()
        private val platform = DesktopHomePlatform(false, false, false, false, false, false, Background(), 0f, false)
        private var lastNanos = 0L
        private val scene: ImageComposeScene
        init {
            check(GraphicsEnvironment.isHeadless()) { "CPU component tests require the existing headless test JVM" }
            scene = ImageComposeScene(width, height, Density(1f)) {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = style, hapticFeedbackEnabled = false), systemLanguageTags = listOf("en")) {
                    CompositionLocalProvider(LocalDesktopHomePlatform provides platform, LocalLifecycleOwner provides lifecycle,
                        LocalDesktopPersonalRecapBindings provides harness.binding) {
                        DesktopDetailWindow {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().padding(8.dp)) {
                                    HistoryRecapCard(refreshToken = Unit, active = true,
                                        onUpClick = { harness.upClicks.add(it) }, snapshotCache = harness.viewModel.recapSnapshots,
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
        private fun render(): org.jetbrains.skia.Image {
            lastNanos = maxOf(System.nanoTime(), lastNanos + 1)
            return scene.render(lastNanos)
        }
        suspend fun await(label: String, condition: () -> Boolean) {
            withTimeout(5_000) {
                while (true) { render().close(); if (condition()) break; delay(12) }
            }
            assertTrue(condition(), label)
        }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        private fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        private fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        fun hasText(value: String) = nodes().any { text(it, value) }
        fun assertFullText(value: String) {
            val node = nodes().filter { text(it, value) }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
            val bounds = node.boundsInRoot
            assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height,
                "Actual text '$value' stays in $width x $height CPU client: $bounds")
            val layouts = mutableListOf<TextLayoutResult>()
            val readLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            assertNotNull(readLayout, "Original text exposes its real text layout")
            assertTrue(readLayout(layouts))
            assertTrue(layouts.isNotEmpty())
            for (layout in layouts) {
                assertFalse(layout.didOverflowHeight, "Original '$value' is not height-clipped")
                // Desktop's paragraph keeps the available container width even
                // when Text measures to its content (e.g. 796 vs 56 px). Check
                // actual painted line extents instead of didOverflowWidth.
                for (line in 0 until layout.lineCount) {
                    assertTrue(layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width,
                        "Original '$value' line $line stays inside its measured width: " +
                            "${layout.getLineLeft(line)}..${layout.getLineRight(line)} / ${layout.size.width}")
                    assertFalse(layout.isLineEllipsized(line), "Essential '$value' is complete")
                }
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1),
                    "All characters of '$value' are laid out")
            }
        }
        fun clickText(value: String) {
            val node = nodes().filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any { text(it, value) } }
                .minBy { it.boundsInRoot.width * it.boundsInRoot.height }
            check(node.config.getOrNull(SemanticsProperties.Disabled) == null)
            val point = node.boundsInRoot.center
            check(point.x in 0f..width.toFloat() && point.y in 0f..height.toFloat())
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = lastNanos / 1_000_000,
                buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = lastNanos / 1_000_000 + 40, buttons = PointerButtons())
        }
        fun assertActualChart(window: PersonalRecapWindow, count: Int, artifact: String) {
            val expected = "${window.label}活动趋势，阅读 $count 篇，观看 $count 个视频，最高每时段"
            val chart = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(expected) } == true }
            val bounds = chart.boundsInRoot
            assertTrue(bounds.width > 80 && bounds.height >= 70 && bounds.left >= 0 && bounds.right <= width && bounds.bottom <= height)
            val bytes = render().use { image -> requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes } }
            val image = requireNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
            val colors = mutableSetOf<Int>()
            for (y in ceil(bounds.top).toInt() until floor(bounds.bottom).toInt())
                for (x in ceil(bounds.left).toInt() until floor(bounds.right).toInt()) colors.add(image.getRGB(x, y))
            assertTrue(colors.size > 4, "Actual original chart has painted bars/grid, not only a semantics node")
            artifactRoot?.let { Files.write(it.resolve("$artifact.png"), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
        }
        override fun close() = scene.close()
    }

    companion object {
        private val artifactRoot: Path? by lazy {
            System.getenv("BILIPAI_RECAP_COMPONENT_QA_OUTPUT")?.takeIf { it.isNotBlank() }?.let { supplied ->
                val directory = Path.of(supplied).toAbsolutePath().normalize()
                check(!Files.exists(directory) && Files.isDirectory(directory.parent)) { "CPU screenshot directory must be fresh" }
                Files.createDirectory(directory)
            }
        }
    }
}
