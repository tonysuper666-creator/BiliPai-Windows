@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.core.network.PassportApi
import com.android.purebilibili.data.model.response.NavResponse
import com.bilipai.desktop.data.*
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

/** Real production Dialog, QR transport and return binding; no native window or account. */
class DesktopAdvancedLoginDialogLayoutTest {
    private class Transport {
        val generated = AtomicInteger()
        val pollCode = AtomicInteger(86039)
        val blockPoll = AtomicBoolean()
        val pollEntered = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val pollReturned = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when {
                path.endsWith("/auth_code") -> {
                    val n = generated.incrementAndGet()
                    """{"code":0,"data":{"auth_code":"isolated-$n","url":"https://example.invalid/qr/$n"}}"""
                }
                path.endsWith("/poll") -> {
                    if (blockPoll.get()) {
                        pollEntered.countDown()
                        check(releasePoll.await(5, TimeUnit.SECONDS)) { "Fixture poll was not released" }
                        pollReturned.countDown()
                    }
                    """{"code":${pollCode.get()},"message":"isolated fixture","data":{"mid":42,"cookie_info":{"cookies":[{"name":"SESSDATA","value":"isolated-test-only"}]}}}"""
                }
                else -> error("Unexpected login transport: $path; sockets are forbidden")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private class Scene(val width: Int, val height: Int, dark: Boolean) : AutoCloseable {
        val transport = Transport()
        val store = DesktopSessionStore.temporary()
        val repository = DesktopRepository(store)
        private val originalClient = repository.httpClient
        private val hold = DesktopLoginUpdateHold({ true }, { true })
        val returns = DesktopLoginReturnCoordinator { true }
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private var shown by mutableStateOf(false)
        private var tab by mutableIntStateOf(1)
        lateinit var list: LazyListState
        lateinit var binding: DesktopLoginReturnBinding
        var completed = 0
        val validationCalls = AtomicInteger()
        private val scene: ImageComposeScene

        init {
            check(GraphicsEnvironment.isHeadless())
            // Only transport is replaced. Production Retrofit, polling, intent and disposal still run.
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
                .set(repository, transport.client)
            val nav = Json { ignoreUnknownKeys = true }.decodeFromString<NavResponse>(
                """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"isolated fixture","face":""}}""")
            val validation = Proxy.newProxyInstance(PassportApi::class.java.classLoader,
                arrayOf(PassportApi::class.java)) { _, method, _ ->
                check(method.name == "validateCookieSession") { "Unexpected validation: ${method.name}" }
                validationCalls.incrementAndGet(); nav
            } as PassportApi
            DesktopRepository::class.java.getDeclaredField("validationPassportApi").apply { isAccessible = true }
                .set(repository, validation)
            scene = ImageComposeScene(width, height, Density(1f),
                coroutineContext = StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, error -> errors += error }) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.fillMaxSize()) {
                        Column {
                            Row {
                                listOf("推荐", "关注").forEachIndexed { index, label ->
                                    FilterChip(tab == index, { tab = index }, label = { Text(label) })
                                }
                                TextButton(onClick = { open() }) { Text("来源页登录") }
                            }
                            list = rememberLazyListState(8, 13)
                            LazyColumn(Modifier.fillMaxSize().testTag("source-list"), state = list) {
                                items(40) { Text("关注条目 $it", Modifier.fillMaxWidth().height(48.dp)) }
                            }
                        }
                        if (shown) AdvancedLoginDialog(repository, hold,
                            // This is DesktopShell's modal callback, with its real return binding.
                            onDismiss = { binding.cancel(); shown = false },
                            onComplete = { completed++; shown = false }, loginReturn = binding)
                    }
                }
            }
            pump()
        }

        private fun open() {
            EventQueue.invokeAndWait {
                binding = requireNotNull(returns.beginLoginReturn(store.generation, null,
                    BiliPaiNavKey.MainHost, DesktopLoginReturnOrigin.MODAL))
            }
            shown = true
        }
        fun pump(frames: Int = 2) { repeat(frames) {
            scheduler.runCurrent()
            time += 16_000_000
            scene.render(time).close()
            scheduler.runCurrent()
            errors.firstOrNull()?.let { throw AssertionError("Login composition failed", it) }
        } }
        fun until(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition() && System.nanoTime() < deadline) { pump(); Thread.sleep(5) }
            pump()
            assertTrue(condition(), "$message\n${dump()}")
        }
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun text(value: String) = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun dialog() = nodes().first { it.config.contains(SemanticsProperties.PaneTitle) }
        fun dump() = nodes().joinToString("\n") { "${it.boundsInWindow} ${it.config}" }
        private fun click(node: SemanticsNode) {
            val bounds = node.boundsInWindow
            assertVisible(bounds)
            val p = bounds.center
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons())
            pump(20)
        }
        fun click(value: String) = click(assertNotNull(text(value), "Missing $value\n${dump()}"))
        fun assertVisible(bounds: Rect) {
            assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= width && bounds.bottom <= height, "Not fully reachable in ${width}x$height: $bounds")
        }
        fun ready() {
            click("来源页登录")
            until("Production QR did not load") { text("请使用哔哩哔哩手机客户端扫码") != null }
        }
        fun poll() { scheduler.advanceTimeBy(2_001); pump() }
        fun scrollBody() {
            val p = Offset(width / 2f, height / 2f)
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Scroll, p, scrollDelta = Offset(0f, 10f))
            pump(20)
        }
        fun assertSource() {
            assertEquals(1, tab, "Cancel must retain the source follow tab")
            assertEquals(8, list.firstVisibleItemIndex)
            assertEquals(13, list.firstVisibleItemScrollOffset)
        }
        fun capture(label: String) {
            val dir = Path.of("build/reports/ton1062")
            Files.createDirectories(dir)
            scene.render(time).use { image ->
                Files.write(dir.resolve("$label.png"), requireNotNull(image.encodeToData()).bytes)
            }
        }
        override fun close() {
            transport.releasePoll.countDown()
            scene.close(); hold.close(); returns.close()
            for (client in listOf(originalClient, transport.client)) {
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
        }
    }

    @TestFactory fun realDialogFitsAvailableWidthAndItsQrAndActionsAreReachable() =
        listOf(false, true).flatMap { dark -> listOf(960, 480, 320).map { width ->
            DynamicTest.dynamicTest("${width}dp ${if (dark) "dark" else "light"}") {
                Scene(width, 720, dark).use { s ->
                    s.ready()
                    val bounds = s.dialog().boundsInWindow
                    println("available=${width}x720 density=1 dark=$dark dialog=$bounds")
                    s.capture("waiting-$width-$dark")
                    assertEquals(minOf(480, width - 32).toFloat(), bounds.width, 1f,
                        "The production surface must cap at 480dp and leave 16dp each side")
                    assertEquals((width - bounds.width) / 2f, bounds.left, 1f)
                    assertTrue(bounds.left >= 16 && width - bounds.right >= 16)
                    val qr = s.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("登录二维码") == true }
                    s.assertVisible(qr.boundsInWindow)
                    s.assertVisible(assertNotNull(s.text("刷新二维码")).boundsInWindow)
                    val before = s.transport.generated.get()
                    s.click("刷新二维码")
                    s.until("Refresh must use the same dialog") { s.transport.generated.get() > before && s.text("请使用哔哩哔哩手机客户端扫码") != null }
                    assertEquals(bounds, s.dialog().boundsInWindow)
                    s.click("关闭")
                    assertNull(s.text("账号与登录")); assertFalse(s.binding.owns()); s.assertSource()
                }
            }
        } }

    @TestFactory fun expiredRefreshAndCancelledLatePollRetainTheMountedSource() = listOf(false, true).map { dark ->
        DynamicTest.dynamicTest("expired, refresh, cancel, late poll / dark=$dark") {
            Scene(480, 720, dark).use { s ->
                s.ready(); val sourceList = s.list
                s.transport.pollCode.set(86038); s.poll()
                s.until("Expired QR must offer refresh in place") { s.text("二维码已过期，请刷新") != null }
                s.capture("expired-$dark")
                assertTrue(s.binding.owns()); s.assertSource()
                s.transport.pollCode.set(86039)
                s.click("刷新二维码")
                s.until("New QR must replace expired state") { s.transport.generated.get() == 2 && s.text("请使用哔哩哔哩手机客户端扫码") != null }
                assertNull(s.text("二维码已过期，请刷新"))
                s.transport.blockPoll.set(true); s.poll()
                s.until("Late poll barrier not entered") { s.transport.pollEntered.count == 0L }
                val epoch = s.store.generation
                s.click("关闭")
                assertFalse(s.binding.owns()); assertSame(sourceList, s.list); s.assertSource()
                s.transport.pollCode.set(0) // A valid synthetic success arriving after close must be inert.
                s.transport.releasePoll.countDown()
                s.until("Late HTTP result not returned") { s.transport.pollReturned.count == 0L }
                s.pump(30)
                assertNull(s.text("账号与登录")); assertNull(s.repository.account.value)
                assertNull(s.repository.loginInstallation.value); assertNull(s.returns.installedLoginReturn.value)
                assertEquals(epoch, s.store.generation); assertEquals(0, s.completed)
                assertEquals(0, s.validationCalls.get(), "Retired success must not even start account validation")
                assertSame(sourceList, s.list); s.assertSource(); s.capture("cancelled-$dark")
            }
        }
    }

    @TestFactory fun shortNarrowViewportKeepsCloseVisibleAndScrollsToRefresh() = listOf(false, true).map { dark ->
        DynamicTest.dynamicTest("320x440dp scrollable body / dark=$dark") {
            Scene(320, 440, dark).use { s ->
                s.ready()
                val bounds = s.dialog().boundsInWindow
                s.assertVisible(bounds)
                assertTrue(bounds.height <= 408, "Keep vertical clearance in a short viewport")
                s.assertVisible(assertNotNull(s.text("关闭")).boundsInWindow)
                s.scrollBody()
                s.assertVisible(assertNotNull(s.text("刷新二维码")).boundsInWindow)
                val before = s.transport.generated.get()
                s.click("刷新二维码")
                s.until("Scrolled refresh must work") { s.transport.generated.get() > before }
                s.capture("short-scrolled-$dark")
                s.click("关闭"); s.assertSource()
            }
        }
    }
}
