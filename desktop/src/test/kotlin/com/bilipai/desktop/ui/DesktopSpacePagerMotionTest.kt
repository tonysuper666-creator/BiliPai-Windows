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
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.LocalBottomBarVisible
import com.android.purebilibili.core.ui.LocalSetBottomBarVisible
import com.android.purebilibili.feature.space.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.bilipai.desktop.data.*
import java.lang.reflect.Proxy
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.*

/** Full compiled SpaceScreen and original retained VM. Only HTTP data and unused
 * action ports are synthetic; all persisted settings/session data are temporary. */
class DesktopSpacePagerMotionTest {
    @Test
    fun spaceTabsFollowClicksRetargetingAndTouch(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val requests = CopyOnWriteArrayList<Request>()
            val repository = DesktopRepository(DesktopSessionStore(fixture.root.resolve("session.json"), persistent = false))
            val client = repository.httpClient.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "GET") { "unexpected space mutation" }
                check(request.url.host in setOf("api.bilibili.com", "app.bilibili.com", "api.live.bilibili.com"))
                requests += request
                val body = when (request.url.encodedPath) {
                    "/x/v2/space" -> """{"code":0,"data":{"default_tab":"home","card":{"mid":"77","name":"Known UP","face":"","sign":"Fixture signature"},"archive":{"count":1,"item":[{"bvid":"BVfixture1","title":"Aggregate title","cover":""}]}}}"""
                    "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":false,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/abcdef0123456789abcdef0123456789.png"}}}"""
                    "/x/web-interface/card" -> """{"code":0,"data":{"card":{"mid":"77","name":"Known UP","face":""}}}"""
                    "/x/space/wbi/arc/search" -> """{"code":0,"data":{"list":{"vlist":[{"bvid":"BVfixture1","aid":100,"title":"Video 1","pic":"","mid":77,"author":"Known UP","typeid":4,"typename":"Known category"}],"tlist":{"4":{"tid":4,"name":"Known category","count":1}}},"page":{"pn":1,"ps":30,"count":1}}}"""
                    "/x/space/wbi/acc/info" -> """{"code":0,"data":{"mid":77,"name":"Known UP","face":"","is_followed":false}}"""
                    "/x/relation" -> """{"code":0,"data":{"attribute":0}}"""
                    "/x/relation/stat" -> """{"code":0,"data":{"mid":77,"following":4,"follower":11}}"""
                    "/x/space/upstat" -> """{"code":0,"data":{"archive":{"view":15},"likes":9}}"""
                    "/x/web-interface/history/cursor" -> """{"code":0,"data":{"list":[],"cursor":{"max":0,"view_at":0,"business":""}}}"""
                    "/x/polymer/web-space/seasons_series_list" -> """{"code":0,"data":{"items_lists":{"seasons_list":[],"series_list":[]}}}"""
                    "/x/v3/fav/folder/created/list-all", "/x/v3/fav/folder/collected/list" -> """{"code":0,"data":{"list":[],"count":0}}"""
                    "/x/polymer/web-dynamic/v1/feed/space" -> """{"code":0,"data":{"has_more":false,"offset":"","items":[]}}"""
                    "/x/space/wbi/article" -> """{"code":0,"data":{"articles":[],"count":0,"pn":1,"ps":30}}"""
                    "/pugv/app/web/season/page" -> """{"code":0,"data":{"items":[],"page":{"next":false}}}"""
                    "/audio/music-service/web/song/upper" -> """{"code":0,"data":{"data":[],"totalSize":0,"pageCount":1,"curPage":1}}"""
                    "/x/space/top/arc" -> """{"code":0,"data":{}}"""
                    "/x/space/notice" -> """{"code":0,"data":"Original notice"}"""
                    else -> error("unexpected space endpoint ${request.url.encodedPath}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic space")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, client)
            val parent = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
            val environment = DesktopOriginalSpaceEnvironment(repository, repository.sessionEpoch, null, 77, parent,
                { true }, { true }, { it(); true }, { error("unexpected feedback $it") }, client)
            val entry = DesktopOriginalSpacePageEntry(environment)
            val viewModel = entry.viewModel
            val session = DesktopDynamicCardSession(repository)
            val operations = DesktopDynamicCardOperations(repository)
            val commentRequests = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                when (method.name) { "isOwned" -> true; "currentMid" -> null; else -> error("unexpected comment ${method.name}") }
            } as DesktopVideoCommentRequests
            val comments = DesktopOriginalCommentRootOwner(operations, commentRequests, parent, operations.emotes,
                { true }, { error("unexpected comment feedback $it") })
            val playlist = DesktopOriginalVideoPlaylistBinding(fixture.pluginContext.store, parent, { true },
                { it(); true }, { throw it })
            val spacePlatform = DesktopOriginalSpacePlatform(entry, fixture.home, false,
                DesktopCommunityRepository(repository, DesktopBlockedUpStore(fixture.pluginContext)), session, comments,
                playlist, { 0L }, { _, _, _ -> error("unexpected share") }, { error("unexpected detail") })
            val commentPlatform = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
                arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "InertSpaceCommentPlatform"
                    "getContext" -> fixture.pluginContext
                    "isOwned" -> true
                    else -> error("unexpected comment port ${method.name}")
                }
            } as DesktopCommentPlatform
            val prefs = DesktopFavoritePreferences(fixture.pluginContext.store)
            val backToTop = DesktopBackToTopBindings(prefs.backToTopEnabled, prefs.initialBackToTopEnabled(),
                prefs.backToTopOffset, prefs.initialBackToTopOffset(), { _, _ -> error("unexpected offset write") },
                { _, _ -> error("unexpected offset update") }, DesktopFavoriteViewport(1000, 900))
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
            val scene = ImageComposeScene(1000, 900, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalBottomBarVisible provides bottomBarVisible.value,
                    LocalSetBottomBarVisible provides { bottomBarVisible.value = it },
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopOriginalSpacePlatform provides spacePlatform,
                    LocalDesktopCommentBindings provides commentPlatform,
                    LocalDesktopFeedGridColumnSettings provides fixture.home.settings,
                    LocalDesktopBackToTopBindings provides backToTop,
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
                            SpaceScreen(77, onBack = { error("unexpected back") },
                                onVideoClick = { _, _, _ -> error("unexpected video") })
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
            // Each pager page has a counter-translated stationary header. Its descendants
            // can have on-screen coordinates even when the owning grid is off-screen.
            // A tab can receive input only when its center is inside every clipped
            // ancestor viewport. An off-screen grid can retain a few visible pixels
            // while its counter-translated tab is elsewhere on screen. Do not use
            // the Selected value to choose between those duplicate rails.
            fun tab(text: String): SemanticsNode {
                val matches = allNodes().filter { node ->
                    node.config.getOrNull(SemanticsProperties.Selected) != null &&
                        nodes(node).any { child -> hasLabel(child, text) } &&
                        node.boundsInRoot.width > 10f &&
                        generateSequence(node) { it.parent }.all {
                            it.boundsInRoot.contains(node.boundsInRoot.center)
                        }
                }
                check(matches.size == 1) { "ambiguous visible space tab $text: " + matches.joinToString("\n") { n ->
                    "node=${n.id}, selected=${n.config.getOrNull(SemanticsProperties.Selected)}, labels=" +
                        nodes(n).mapNotNull { it.config.getOrNull(SemanticsProperties.Text) } +
                        ", ancestors=" + generateSequence(n) { it.parent }.joinToString(" -> ") {
                            "${it.id}:${it.boundsInRoot}:${it.config}"
                        }
                } }
                val result = matches.single()
                val grid = generateSequence(result) { it.parent }.first {
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                }
                println("SPACE_TAB $text bounds=${result.boundsInRoot} owner=${grid.boundsInRoot}")
                return result
            }
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
                            assertTrue(xs.isNotEmpty(), "space rail disappeared at $nanos")
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
                    "space tab must be laid out inside the scene before pointer input: $text $bounds")
                val point = bounds.center
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                val held = frames(7)
                pointer(PointerEventType.Release, point, false)
                return held
            }
            fun continuous(values: List<Float>, spacing: Float) = assertTrue(
                values.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                "space rail jumped half a tab in one virtual 165 Hz frame: $values",
            )
            fun state() = viewModel.uiState.value as? SpaceUiState.Success
            suspend fun loaded() {
                withTimeout(6_000) {
                    while (state()?.videos?.isNotEmpty() != true || state()?.isLoadingMore != false) {
                        frames(2); delay(5)
                    }
                }
            }
            try {
                loaded(); frames(90)
                val first = tab("主页").boundsInRoot
                val last = tab("投稿").boundsInRoot
                val spacing = tab("动态").boundsInRoot.center.x - first.center.x
                assertTrue(spacing > 0f)
                nanos += 6_060_606L
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (first.top.toInt() until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until first.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no rendered space rail: $rows")
                    scanY = best.first
                } }
                val start = frames(2).last()
                val held = click("投稿")
                val early = frames(2)
                assertEquals(SpaceMainTab.CONTRIBUTION, state()!!.tabShellState.selectedTab,
                    "the clicked space tab must own VM selection before intermediate pages are crossed")
                val travel = listOf(start) + held + early + frames(90)
                assertTrue(travel.any { it > first.center.x + spacing * .2f && it < last.center.x - spacing * .2f },
                    "space rail must cross intermediate positions: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> b >= a - 1f }, "space rail reversed: $travel")
                continuous(travel, spacing)
                assertTrue(abs(travel.last() - last.center.x) < 3f)
                assertTrue(selected("投稿"), "contribution semantics: " + allNodes().filter {
                    it.config.getOrNull(SemanticsProperties.Selected) != null && nodes(it).any { n -> hasLabel(n, "投稿") }
                }.map { n -> "id=${n.id} selected=${n.config.getOrNull(SemanticsProperties.Selected)} chain=" +
                    generateSequence(n) { it.parent }.map { "${it.id}:${it.boundsInRoot}" }.toList() })
                assertEquals("BVfixture1", state()!!.videos.single().bvid)

                val reverse = click("主页") + frames(8)
                val redirect = click("动态") + frames(2)
                assertEquals(SpaceMainTab.DYNAMIC, state()!!.tabShellState.selectedTab)
                val retargeted = reverse + redirect + frames(90)
                continuous(retargeted, spacing)
                assertTrue(abs(retargeted.last() - tab("动态").boundsInRoot.center.x) < 3f)
                assertTrue(selected("动态"))
                withTimeout(6_000) { while (state()?.hasLoadedDynamicsOnce != true) { frames(2); delay(5) } }
                assertTrue(requests.any { it.url.encodedPath.endsWith("feed/space") && it.url.queryParameter("host_mid") == "77" })

                val touchY = (tab("动态").boundsInRoot.bottom + 100f).coerceAtMost(850f)
                pointer(PointerEventType.Press, Offset(200f, touchY), true, PointerType.Touch)
                frames(2)
                val swipe = mutableListOf<Float>()
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(200f + (step + 1) * 50f, touchY), true, PointerType.Touch)
                    swipe += frames(2)
                }
                val target = tab("主页").boundsInRoot.center.x
                assertTrue(swipe.any { it > target + spacing * .1f && it < target + spacing * .9f },
                    "touch input did not move the space rail through intermediate positions: $swipe")
                pointer(PointerEventType.Release, Offset(800f, touchY), false, PointerType.Touch)
                val settled = frames(90)
                continuous(swipe + settled, spacing)
                assertTrue(selected("主页"))
                assertEquals(SpaceMainTab.HOME, state()!!.tabShellState.selectedTab)
                assertTrue(abs(settled.last() - target) < 3f)
                assertTrue(requests.filter { it.url.encodedPath.endsWith("arc/search") }.all {
                    it.url.queryParameter("mid") == "77" && it.url.queryParameter("w_rid")?.length == 32
                })
                assertTrue(requests.none { it.method != "GET" })
            } finally {
                scene.close()
                navigation.navigationEventDispatcher.dispose()
                check(environment.closeAndJoin())
                playlist.closeAndJoin()
                session.close()
                parent.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
