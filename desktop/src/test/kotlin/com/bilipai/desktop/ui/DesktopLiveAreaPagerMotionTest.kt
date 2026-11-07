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
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.live.LiveAreaScreen
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
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

/** Full compiled LiveAreaScreen, its request binding and real pager; only HTTP data is synthetic. */
class DesktopLiveAreaPagerMotionTest {
    @Test
    fun liveAreaSelectionRailAndContentStayTogetherAcrossClicksAndTouchSwipe(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val requests = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                requests += exchange.requestURI.path
                val data = (1..3).joinToString(",") { id ->
                    """{"id":$id,"name":"分区$id","list":[{"id":"${id}01","parent_id":"$id","name":"内容$id","pic":""}]}"""
                }
                val bytes = """{"code":0,"data":[$data]}""".toByteArray()
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
                    check(request.method == "GET" && request.url.host == "api.live.bilibili.com" &&
                        request.url.encodedPath == "/room/v1/Area/getList") {
                        "Unexpected live fixture request: ${request.method} ${request.url.encodedPath}"
                    }
                    chain.proceed(request.newBuilder().url(request.url.newBuilder()
                        .scheme("http").host("127.0.0.1").port(server.address.port).build()).build())
                }.build()
            val retrofit = Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
            val api = retrofit.create(BilibiliApi::class.java)
            val environment = DesktopHomeProtocolEnvironment(
                api, api, retrofit.create(MessageApi::class.java), fixture.scope,
                isCurrent = { true }, commitIfCurrent = { it(); true },
                feedApiType = { SettingsManager.FeedApiType.WEB }, refreshCount = { 0 },
                wbiKeys = { error("unexpected WBI") }, accessToken = { null }, csrf = { null }, buvid3 = { null },
                awaitSessionRestored = {}, ensureBuvid3FromSpi = {},
            )
            val binding = DesktopLiveNavigationBinding(environment, retrofit.create(SearchApi::class.java), fixture.pluginContext.store)
            val opened = mutableListOf<Triple<Int, Int, String>>()
            // Offscreen software renderer: advertise no native visual-effect capabilities.
            val background = object : DesktopHomeWindowBackgroundPort {
                override val isInBackground = false
                override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
                override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            }
            val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
            val settings = DesktopOriginalPlayerSettingsContext(fixture.pluginContext, { true }, { it(); true })
            var nanos = 0L
            val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(settings),
                    LocalDesktopLiveNavigationBinding provides binding,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta)) {
                        LiveAreaScreen(onBack = { error("unexpected back") },
                            onAreaClick = { parent, area, title -> opened += Triple(parent, area, title) })
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun hasLabel(node: SemanticsNode, text: String) =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            fun label(text: String) = allNodes().single { hasLabel(it, text) }.boundsInRoot
            fun selected(text: String) = allNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true && nodes(it).any { child -> hasLabel(child, text) }
            }
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> scanY?.let { y ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val xs = (0 until 800).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "live area underline disappeared at $nanos")
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
                val point = label(text).center
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                val held = frames(7)
                pointer(PointerEventType.Release, point, false)
                return held
            }
            fun continuous(values: List<Float>, spacing: Float) = assertTrue(
                values.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                "live area rail jumped half a tab in one virtual 165 Hz frame: $values",
            )
            try {
                withTimeout(6_000) {
                    while (allNodes().none { hasLabel(it, "分区3") }) { frames(2); delay(5) }
                }
                frames(90)
                val centers = (1..3).map { label("分区$it").center.x }
                val spacing = centers[1] - centers[0]
                assertTrue(spacing > 0f)
                val tab = allNodes().single { it.config.getOrNull(SemanticsProperties.Selected) == true &&
                    nodes(it).any { child -> hasLabel(child, "分区1") } }.boundsInRoot
                nanos += 6_060_606L
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (label("分区1").bottom.toInt() until tab.bottom.toInt()).map { y ->
                        y to (0 until 800).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no rendered live area underline: $rows")
                    scanY = best.first
                } }
                val start = frames(2).last()
                assertTrue(abs(start - centers[0]) < 3f)
                val held = click("分区3")
                val early = frames(2)
                assertTrue(selected("分区3"), "the clicked live area must be selected before intermediate pages are crossed")
                val travel = listOf(start) + held + early + frames(90)
                assertTrue(travel.any { it > centers[0] + spacing * .2f && it < centers[2] - spacing * .2f })
                assertTrue(travel.zipWithNext().all { (a, b) -> b >= a - 1f }, "live area rail reversed: $travel")
                continuous(travel, spacing)
                assertTrue(abs(travel.last() - centers[2]) < 3f)
                click("内容3"); frames(2)
                assertEquals(Triple(3, 301, "内容3"), opened.last())

                val reverse = click("分区1") + frames(8)
                val heldAgain = click("分区2")
                val earlyAgain = frames(2)
                assertTrue(selected("分区2"), "the latest live area click owns selection during reversal")
                val retargeted = reverse + heldAgain + earlyAgain + frames(90)
                continuous(retargeted, spacing)
                assertTrue(abs(retargeted.last() - centers[1]) < 3f)
                click("内容2"); frames(2)
                assertEquals(Triple(2, 201, "内容2"), opened.last())

                pointer(PointerEventType.Press, Offset(180f, 480f), true, PointerType.Touch)
                frames(2)
                val swipe = mutableListOf<Float>()
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(180f + (step + 1) * 40f, 480f), true, PointerType.Touch)
                    swipe += frames(2)
                }
                assertTrue(swipe.any { it > centers[0] + spacing * .1f && it < centers[1] - spacing * .1f },
                    "touch input did not move the live rail through intermediate positions: $swipe")
                pointer(PointerEventType.Release, Offset(660f, 480f), false, PointerType.Touch)
                val settled = frames(90)
                continuous(swipe + settled, spacing)
                assertTrue(selected("分区1"))
                assertTrue(abs(settled.last() - centers[0]) < 3f)
                click("内容1"); frames(2)
                assertEquals(listOf(Triple(3, 301, "内容3"), Triple(2, 201, "内容2"), Triple(1, 101, "内容1")), opened)
                assertEquals(listOf("/room/v1/Area/getList"), requests.toList())
            } finally {
                scene.close()
                binding.close()
                server.stop(0)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
