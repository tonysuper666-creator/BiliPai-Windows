@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import coil3.PlatformContext
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.data.model.response.SearchType
import com.android.purebilibili.data.repository.DesktopOriginalSearchRepository
import com.android.purebilibili.feature.search.SearchScreen
import com.android.purebilibili.feature.search.SearchViewModel
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Full compiled SearchScreen and original VM/repository; only the remote search data is synthetic. */
class DesktopSearchPagerMotionTest {
    @Test
    fun resultTypeSelectionAndRailFollowClicksRetargetingAndTouch(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val requests = CopyOnWriteArrayList<Pair<String, String?>>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val bytes = if (exchange.requestURI.path == "/main/suggest") {
                    """{"code":0,"result":{"tag":[]}}""".toByteArray()
                } else {
                    """{"code":0,"data":{"page":1,"pagesize":20,"numPages":1,"numResults":0,"result":[]}}""".toByteArray()
                }
                try {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally { exchange.close() }
            }
            server.start()
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.method == "GET" && request.url.encodedPath in listOf(
                        "/x/web-interface/wbi/search/all/v2", "/x/web-interface/wbi/search/type", "/main/suggest")) {
                        "Unexpected search fixture request: ${request.method} ${request.url.encodedPath}"
                    }
                    requests += request.url.encodedPath to request.url.queryParameter("search_type")
                    chain.proceed(request.newBuilder().url(request.url.newBuilder()
                        .scheme("http").host("127.0.0.1").port(server.address.port).build()).build())
                }.build()
            val retrofit = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
            val preferences = DesktopSearchPreferences(fixture.root.resolve("search-preferences"))
            preferences.setSuggestionsEnabled(false)
            val environment = DesktopOriginalSearchEnvironment(
                fixture.scope, { true }, { it(); true }, fixture.pluginContext, fixture.home.settings,
                preferences, null, DesktopOriginalSearchBlocked(MutableStateFlow(emptyList())),
                { owned -> DesktopPersonalArticleResolver(client, owned) }, DesktopFavoritePreferences(fixture.pluginContext.store),
                { owned -> DesktopOriginalSearchRepository(retrofit.create(SearchApi::class.java),
                    retrofit.create(BilibiliApi::class.java),
                    { Result.success("0123456789abcdef0123456789abcdef" to "fedcba9876543210fedcba9876543210") },
                    owned, { null to "android" }) },
                MutableStateFlow(DesktopProfileWindowConfiguration(1200, 700)), PlatformContext.INSTANCE, false,
                { _, _ -> error("unexpected watch later") }, {}, {}, { error("unexpected feedback: $it") },
            )
            val viewModel = SearchViewModel(environment)
            val navigation = object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
            val background = object : DesktopHomeWindowBackgroundPort {
                override val isInBackground = false
                override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
                override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            }
            val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
            var nanos = 0L
            val scene = ImageComposeScene(1200, 700, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(environment.originalSettings),
                    LocalDesktopOriginalSearchEnvironment provides environment,
                    LocalNavigationEventDispatcherOwner provides navigation,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta,
                        primaryContainer = Color.Magenta, secondaryContainer = Color.Magenta)) {
                        DesktopDetailWindow {
                          DesktopOriginalSearchBackToTop {
                            SearchScreen(viewModel, initialKeyword = "pager fixture", onBack = { error("unexpected back") },
                                onOpenTrending = { error("unexpected trending") }, onNavigateSearchTarget = { error("unexpected navigation") },
                                onVideoClick = { _, _, _ -> error("unexpected video") }, onWebClick = { _, _ -> error("unexpected web") },
                                onUpClick = { error("unexpected up") }, onBangumiClick = { error("unexpected bangumi") },
                                onLiveClick = { _, _, _ -> error("unexpected live") }, onTopicClick = { error("unexpected topic") },
                                onArticleClick = { _, _ -> error("unexpected article") }, onAvatarClick = { error("unexpected avatar") })
                          }
                        }
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun hasLabel(node: SemanticsNode, text: String) =
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                    it.text == text || it.text.matches(Regex("${Regex.escape(text)} [0-9]+\\+?"))
                } == true
            fun tab(text: String) = allNodes().single { it.config.getOrNull(SemanticsProperties.Selected) != null &&
                nodes(it).any { child -> hasLabel(child, text) } }
            fun selected(text: String) = tab(text).config[SemanticsProperties.Selected]
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> scanY?.let { y ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val xs = (0 until 1200).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "search rail disappeared at $nanos")
                            add(xs.average().toFloat())
                        }
                    } }
                    yield()
                }
            }
            fun pointer(event: PointerEventType, point: Offset, pressed: Boolean, type: PointerType = PointerType.Mouse) {
                scene.sendPointerEvent(event, point, timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = pressed), type = type)
            }
            suspend fun click(text: String): List<Float> {
                val bounds = tab(text).boundsInRoot
                assertTrue(bounds.width > 10f && bounds.height > 10f && bounds.left >= 0f && bounds.right <= 1200f,
                    "search tab must be laid out inside the scene before pointer input: $text $bounds")
                val point = bounds.center
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                val held = frames(7)
                pointer(PointerEventType.Release, point, false)
                return held
            }
            fun continuous(values: List<Float>, spacing: Float) = assertTrue(
                values.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                "search rail jumped half a tab in one virtual 165 Hz frame: $values",
            )
            suspend fun loaded(type: SearchType) {
                withTimeout(6_000) {
                    while (viewModel.uiState.value.searchType != type || viewModel.uiState.value.isSearching) { frames(2); delay(5) }
                }
                assertEquals("pager fixture", viewModel.uiState.value.submittedQuery)
                assertTrue(viewModel.uiState.value.showResults)
            }
            try {
                withTimeout(6_000) {
                    while (allNodes().none { hasLabel(it, "专栏") }) { frames(2); delay(5) }
                }
                loaded(SearchType.ALL); frames(90)
                val first = tab("综合").boundsInRoot
                val last = tab("专栏").boundsInRoot
                val spacing = tab("番剧").boundsInRoot.center.x - first.center.x
                assertTrue(spacing > 0f)
                nanos += 6_060_606L
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (first.top.toInt() until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until first.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no rendered search rail: $rows")
                    scanY = best.first
                } }
                val start = frames(2).last()
                val held = click("专栏")
                val early = frames(2)
                assertTrue(selected("专栏"), "the clicked search type must be selected before intermediate pages are crossed")
                val travel = listOf(start) + held + early + frames(90)
                assertTrue(travel.any { it > first.center.x + spacing * .2f && it < last.center.x - spacing * .2f })
                assertTrue(travel.zipWithNext().all { (a, b) -> b >= a - 1f }, "search rail reversed: $travel")
                continuous(travel, spacing)
                assertTrue(abs(travel.last() - last.center.x) < 3f)
                loaded(SearchType.ARTICLE)

                val reverse = click("综合") + frames(8)
                val redirect = click("影视") + frames(2)
                assertTrue(selected("影视"), "latest search type click owns selection during reversal")
                val retargeted = reverse + redirect + frames(90)
                continuous(retargeted, spacing)
                assertTrue(abs(retargeted.last() - tab("影视").boundsInRoot.center.x) < 3f)
                loaded(SearchType.MEDIA_FT)

                pointer(PointerEventType.Press, Offset(250f, 500f), true, PointerType.Touch)
                frames(2)
                val swipe = mutableListOf<Float>()
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(250f + (step + 1) * 60f, 500f), true, PointerType.Touch)
                    swipe += frames(2)
                }
                val target = tab("番剧").boundsInRoot.center.x
                assertTrue(swipe.any { it > target + spacing * .1f && it < target + spacing * .9f },
                    "touch input did not move the search rail through intermediate positions: $swipe")
                pointer(PointerEventType.Release, Offset(970f, 500f), false, PointerType.Touch)
                val settled = frames(90)
                continuous(swipe + settled, spacing)
                assertTrue(selected("番剧"))
                assertTrue(abs(settled.last() - target) < 3f)
                loaded(SearchType.BANGUMI)
                assertTrue(requests.any { it.second == "all" })
                assertTrue(requests.any { it.second == "article" })
                assertTrue(requests.any { it.second == "media_ft" })
                assertTrue(requests.any { it.second == "media_bangumi" })
            } finally {
                scene.close()
                viewModel.close()
                navigation.navigationEventDispatcher.dispose()
                server.stop(0)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
