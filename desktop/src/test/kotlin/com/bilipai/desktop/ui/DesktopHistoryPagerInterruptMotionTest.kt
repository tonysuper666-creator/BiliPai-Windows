@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.LocalBottomBarVisible
import com.android.purebilibili.core.ui.LocalSetBottomBarVisible
import com.android.purebilibili.feature.list.*
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.Bitmap
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlin.math.abs
import kotlin.test.*

/** Whole compiled CommonListScreen with the original History VM/repository.
 * Temporary real settings and intercepted empty HTTP data; no account or window. */
class DesktopHistoryPagerInterruptMotionTest {
    @Test
    fun touchDragInterruptsInFlightHistoryTabAnimation(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val requests = CopyOnWriteArrayList<Request>()
            val videoClicks = CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "GET") { "unexpected history mutation" }
                requests += request
                val body = when (request.url.encodedPath) {
                    "/x/v2/history/shadow" -> """{"code":0,"data":false}"""
                    "/x/web-interface/history/cursor" ->
                        """{"code":0,"data":{"list":[{"title":"Interrupt fixture video","author_name":"Fixture","author_mid":17,"duration":120,"view_at":1700000000,"history":{"bvid":"BV1fixture11","cid":7007,"oid":170001,"business":"archive","page":1}}],"cursor":{"max":0,"view_at":0,"business":""}}}"""
                    else -> error("unexpected history endpoint ${request.url.encodedPath}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic history")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build().create(BilibiliApi::class.java)
            val environment = DesktopFavoriteEnvironment(fixture.scope, api, null, null, null, { true },
                { "synthetic" }, { 17L }, { error("unexpected feedback $it") }, MutableSharedFlow<Long>(),
                { _, _ -> 0L }, { false }, { error("unexpected watch later") }, { null }, { "android" })
            val viewModel = HistoryViewModel(environment)
            viewModel.enterBrowsingSession()
            viewModel.setPageActive(true)
            val prefs = DesktopFavoritePreferences(fixture.pluginContext.store)
            val binding = DesktopFavoriteBindings(prefs.showOnlineCount, prefs.homeSettings, prefs.initialHomeSettings(),
                prefs.navigationSettings, prefs.initialNavigationSettings(), FavoriteCategoryViewModel(environment),
                { _, _, _ -> error("unexpected queue") }, { _, _ -> error("unexpected append") },
                { _, _, _ -> error("unexpected share") }, prefs.homeFeedCardStyle, false,
                prefs.backToTopEnabled, prefs.initialBackToTopEnabled(), prefs.backToTopOffset, prefs.initialBackToTopOffset(),
                { _, _ -> error("unexpected offset write") }, { _, _ -> error("unexpected offset update") })
            val recap = DesktopPersonalRecapBinding(fixture.pluginContext, environment, prefs, { true }, { it(); true })
            val context = DesktopOriginalPlayerSettingsContext(fixture.pluginContext, { true }, { it(); true })
            val rootSettings = DesktopOriginalHomeSettingsRootServices(fixture.home.settings, prefs,
                { error("unexpected notice") }, { _, _, _, _ -> error("unexpected profile") })
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
            val bottomBarVisible = mutableStateOf(true)
            val scene = ImageComposeScene(1000, 700, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalBottomBarVisible provides bottomBarVisible.value,
                    LocalSetBottomBarVisible provides { bottomBarVisible.value = it },
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopFavoriteBindings provides binding,
                    LocalDesktopFavoriteViewport provides DesktopFavoriteViewport(1000, 700),
                    LocalDesktopPersonalRecapBindings provides recap,
                    LocalDesktopOriginalHomeSettingsRootServices provides rootSettings,
                    LocalDesktopOriginalPlayerSettingsContext provides context,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(context),
                    LocalDesktopLiquidTabSettings provides DesktopLiquidTabSettings(fixture.pluginContext.store),
                    LocalNavigationEventDispatcherOwner provides navigation,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Magenta, secondaryContainer = Color.Magenta)) {
                        DesktopDetailWindow {
                            CommonListScreen(viewModel, onBack = { error("unexpected back") },
                                onVideoClick = { bvid, _, _, _ -> videoClicks += bvid })
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
                            val xs = (0 until 1000).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "history rail disappeared at $nanos")
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
                assertTrue(bounds.width > 10f && bounds.height > 10f && bounds.left >= 0f && bounds.right <= 1000f,
                    "history tab must be laid out inside the scene before pointer input: $text $bounds")
                val point = bounds.center
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                val held = frames(7)
                pointer(PointerEventType.Release, point, false)
                return held
            }
            fun continuous(values: List<Float>, spacing: Float) = assertTrue(
                values.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                "history rail jumped half a tab in one virtual 165 Hz frame: $values",
            )
            suspend fun loaded() {
                withTimeout(6_000) {
                    while (viewModel.uiState.value.isLoading) { frames(2); delay(5) }
                }
                assertNull(viewModel.uiState.value.error)
            }
            try {
                withTimeout(6_000) {
                    while (allNodes().none { hasLabel(it, "专栏") }) { frames(2); delay(5) }
                }
                loaded(); frames(90)
                val first = tab("全部").boundsInRoot
                val last = tab("专栏").boundsInRoot
                val spacing = tab("视频").boundsInRoot.center.x - first.center.x
                assertTrue(spacing > 0f)
                nanos += 6_060_606L
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (first.top.toInt() until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until first.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no rendered history rail: $rows")
                    scanY = best.first
                } }
                val start = frames(2).last()
                val held = click("专栏")
                val inFlight = frames(10)
                assertTrue(selected("专栏"), "clicked history filter must own selection before intermediate pages")
                val beforeDrag = inFlight.last()
                assertTrue(beforeDrag > start + 2f && beforeDrag < last.center.x - spacing * .2f &&
                    inFlight.last() > inFlight.first() + 1f,
                    "animation must still be moving between its endpoints before touch: start=$start held=$held frames=$inFlight")
                val interruptNanos = nanos
                pointer(PointerEventType.Press, Offset(200f, 500f), true, PointerType.Touch)
                val dragged = mutableListOf<Float>()
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(200f + (step + 1) * 50f, 500f), true, PointerType.Touch)
                    dragged += frames(2)
                }
                assertTrue(dragged.any { it < beforeDrag - 2f },
                    "real touch drag did not reverse the in-flight indicator: at=$interruptNanos before=$beforeDrag drag=$dragged")
                pointer(PointerEventType.Release, Offset(800f, 500f), false, PointerType.Touch)
                val settled = frames(90)
                assertTrue(selected("全部"), "drag back to first page must own the final history selection")
                assertTrue(abs(settled.last() - first.center.x) < 3f,
                    "settled indicator must match first page: ${settled.last()} vs ${first.center.x}")
                loaded(); frames(10)
                assertTrue(requests.any { it.url.encodedPath == "/x/web-interface/history/cursor" &&
                    it.url.queryParameter("type") == "article" }, "far-tab selection did not reach the real history request")
                assertNull(requests.last { it.url.encodedPath == "/x/web-interface/history/cursor" }
                    .url.queryParameter("type"), "settled first page must restore the all-history request")
                val card = allNodes().firstOrNull { hasLabel(it, "Interrupt fixture video") }
                assertNotNull(card, "the settled history page must render its fixture content")
                val point = card.boundsInRoot.center
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                frames(2)
                pointer(PointerEventType.Release, point, false)
                frames(10)
                assertEquals(listOf("BV1fixture11"), videoClicks.toList(),
                    "settled content must route its real video callback")
                println("History interrupt: nanos=$interruptNanos start=$start before=$beforeDrag drag=$dragged settled=${settled.last()} callback=$videoClicks")
            } finally {
                scene.close()
                navigation.navigationEventDispatcher.dispose()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
