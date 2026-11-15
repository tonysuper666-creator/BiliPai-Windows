package com.bilipai.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.EventQueue
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.*

/** Real retained entry, generated/transitioned Home VM, request ports, owned OkHttp transport,
 * protobuf decoder, Store receipts and Root physical navigation. Only the server is synthetic.
 * Unrelated initial Home work is held; no GUI, native media or real account/network is used. */
class DesktopHomeMessageUnreadIntegrationTest {
    private class Held : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unrelated ${method.name}") } as T
    private fun edt(block: () -> Unit) {
        var failure: Throwable? = null
        EventQueue.invokeAndWait { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }

    private inner class Fixture(mid: Long? = 47L) : AutoCloseable {
        val folder = Files.createTempDirectory("home-unread-synthetic-")
        val sessions = DesktopSessionStore(folder.resolve("session.json"), persistent = false)
        val repository: DesktopRepository
        val scope = CoroutineScope(SupervisorJob() + Held())
        val context = DesktopPluginContext(DesktopPluginStore(folder.resolve("global")))
        val blocked = DesktopBlockedUpStore(context)
        val discovery = DesktopDiscoveryPreferences(folder.resolve("discovery"), blocked)
        val requests = CopyOnWriteArrayList<String>()
        var gatewayAvailable = true
        var interfaceAvailable = false
        var imAvailable = true
        var feedAvailable = true
        var total = 20L
        var im = 14L
        var likes = 7
        var beforeReply: (String) -> Unit = {}
        val roots = mutableListOf<DesktopHomeRetainedRoot>()
        val routes = mutableListOf<DesktopOriginalRootRouteAssembly>()
        lateinit var root: DesktopHomeRetainedRoot
        lateinit var navigation: DesktopOriginalRootRouteAssembly
        init {
            if (mid != null) save(mid)
            repository = DesktopRepository(sessions)
            val sealed = repository.httpClient.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                requests += path
                val code: Int
                val body: ByteArray
                val grpc = path.endsWith("GetTotalUnread")
                if (grpc) {
                    val available = when {
                        path.contains("ImGatewayApi") -> gatewayAvailable
                        path.contains("ImInterface") -> interfaceAvailable
                        else -> imAvailable
                    }
                    code = if (available) 200 else 503
                    body = ProtoWire.frame(if (path.contains("/bilibili.app.im."))
                        ProtoWire.bytes(1, ProtoWire.int64(2, im))
                    else ProtoWire.message(ProtoWire.bytes(6, ProtoWire.int64(1, total)),
                        ProtoWire.bytes(2, ProtoWire.bytes(1, ProtoWire.message(
                            ProtoWire.string(1, "like"), ProtoWire.int32(2, likes))))))
                } else {
                    code = if (path.contains("msgfeed") && !feedAvailable) 503 else 200
                    body = when {
                        path.endsWith("/nav") -> """{"code":0,"data":{"isLogin":true,"mid":${sessions.activeAccountMid()},"uname":"synthetic"}}"""
                        path.contains("single_unread") -> """{"code":0,"data":{"follow_unread":14}}"""
                        path.contains("msgfeed") -> """{"code":0,"data":{"reply":10,"at":6,"like":7,"sys_msg":7}}"""
                        else -> error("Unrelated HTTP: $path")
                    }.toByteArray()
                }
                // Bytes were captured before the barrier: deliberately release an old response late.
                beforeReply(path)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                    .header("grpc-status", if (code == 200) "0" else "14")
                    .body(body.toResponseBody((if (grpc) "application/grpc" else "application/json").toMediaType())).build()
            }.build()
            repository.javaClass.getDeclaredField("client").apply { isAccessible = true; set(repository, sealed) }
            mount()
        }
        fun save(mid: Long) {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-$mid", "bili_jct" to "synthetic-only"),
                AccountSummary(mid, "Synthetic", ""))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor"))
        }
        fun mount() {
            val preferences = runBlocking { DesktopOriginalHomePreferences.create(context.store, scope, false, { false }) }
            val analytics = DesktopHomeRootAnalytics(context.store, null, { true })
            val pages = object : DesktopHomeEmbeddedRetainedOwner, DesktopHomeEmbeddedPages by unused<DesktopHomeEmbeddedPages>() {
                override fun close() = Unit
                override suspend fun closeAndJoin() = Unit
            }
            val entry = DesktopHomeRetainedEntry.create(repository, discovery, context, scope,
                repository.sessionEpoch, sessions.activeAccountMid(), { true }, preferences, MutableStateFlow(false),
                { false }, { analytics }, DesktopBlockedUpRepository(repository, blocked),
                { error("Unexpected AUTH invalidation") }, {}, { _, _ -> pages })
            val lifecycle = object : LifecycleOwner {
                override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            val environment = DesktopHomeEnvironment(preferences, context, lifecycle, MutableStateFlow(false), analytics,
                { DesktopOriginalHomeGlobalNamespace(context.store, it) }, MutableStateFlow(null),
                { _, _ -> error("No wallpaper") }, {}, { _, _ -> }, pages,
                { _, _ -> error("No save") }, {}, unused<DesktopHomeOverlayPorts>())
            val media = DesktopHomeMediaLifetime(entry.gate.scope, entry.gate::owns, entry.gate::commit) { error("No media") }
            val mediaPorts = DesktopHomeActualMediaPorts(media, { error("No MIME") }, { _, _, _, _ -> error("No render") },
                MutableStateFlow(false), {}).ports
            val returns = DesktopHomeReturnNavigationOwner(entry.gate::owns, entry.gate::commit,
                { action -> action(); true }, { Offset.Zero }, { 0L }, VideoCardTransitionClock(),
                { false }, { false }, { true })
            root = DesktopHomeRetainedRoot(entry, environment, mediaPorts, returns,
                DesktopLiveNavigationBinding(entry.requests.environment, unused<SearchApi>(), context.store), media,
                DesktopHomeLottieBinding(repository.httpClient, entry.gate::owns, entry.gate::commit, { error("No animation") }))
            roots += root
            navigation = DesktopOriginalRootRouteAssembly(root, mutableStateListOf(BiliPaiNavKey.MainHost),
                DesktopOriginalRootRoutePlatform({ action -> action(); true }, { _, _ -> }, { _, _ -> error("No video") }, {}, {}),
                { setOf("home") })
            routes += navigation
        }
        val count get() = root.entry.viewModel.uiState.value.messageUnreadCount
        suspend fun poll(target: DesktopHomeRetainedRoot = root) {
            // Enter through the actual nav/Store receipt, then run the actual generated unread reducer.
            val publication = if (sessions.activeAccountMid() == null) DesktopHomeBackgroundPublication(
                { block -> if (!target.entry.gate.commit(block)) throw CancellationException("retired") }, target.entry.gate::assertOwned)
            else target.entry.requests.ports.video.getNavInfo().getOrThrow().forBackgroundCaller(currentCoroutineContext().job)
            val vm = target.entry.viewModel
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = vm.javaClass.getDeclaredMethod("refreshMessageUnreadCount",
                    DesktopHomeBackgroundPublication::class.java, kotlin.coroutines.Continuation::class.java)
                method.isAccessible = true
                try { method.invoke(vm, publication, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        fun inbox() = edt { assertTrue(navigation.push(BiliPaiNavKey.Inbox)) }
        fun leaveInbox() { edt { assertTrue(navigation.back()) }; edt {} }
        override fun close() {
            routes.asReversed().forEach { it.close() }
            roots.asReversed().forEach { it.close() }
            scope.cancel()
            repository.httpClient.dispatcher.executorService.shutdown()
            repository.httpClient.connectionPool.evictAll()
            folder.toFile().deleteRecursively()
        }
    }

    @Test fun gatewayWinsWithoutCallingOrAddingOtherSources() = runBlocking {
        Fixture().use { h ->
            h.poll(); assertEquals(20, h.count)
            assertEquals(1, h.requests.count { it.endsWith("GetTotalUnread") })
            assertFalse(h.requests.any { it.contains("msgfeed") || it.contains("single_unread") || it.contains("/bilibili.app.im.") })
        }
    }
    @Test fun unavailableGatewayUsesImAndOptionalFeedExactlyOnce() = runBlocking {
        Fixture().use { h ->
            h.gatewayAvailable = false; h.poll(); assertEquals(44, h.count)
            h.feedAvailable = false; h.poll(); assertEquals(14, h.count)
            h.imAvailable = false; h.poll(); assertEquals(14, h.count, "Failed IM must preserve last publication")
            assertFalse(h.requests.any { it.contains("single_unread") })
        }
    }
    @Test fun interfaceFallbackStillUsesOneAuthoritativeTotal() = runBlocking {
        Fixture().use { h ->
            h.gatewayAvailable = false; h.interfaceAvailable = true; h.total = 23
            h.poll(); assertEquals(23, h.count)
            assertFalse(h.requests.any { it.contains("/bilibili.app.im.") || it.contains("msgfeed") })
        }
    }
    @Test fun zeroGatewayTotalIsAuthoritative() = runBlocking {
        Fixture().use { h ->
            h.total = 0; h.likes = 0; h.poll(); assertEquals(0, h.count)
            assertEquals(1, h.requests.count { it.contains("ImGatewayApi") })
            assertFalse(h.requests.any { it.contains("/bilibili.app.im.") || it.contains("msgfeed") })
        }
    }
    @Test fun inboxClearSuppressesEqualAndDecreasingTotalsThenShowsFullIncrease() = runBlocking {
        Fixture().use { h ->
            h.total = 44; h.poll(); assertEquals(44, h.count)
            h.inbox(); assertEquals(0, h.count); h.leaveInbox()
            h.poll(); assertEquals(0, h.count)
            h.total = 43; h.poll(); assertEquals(0, h.count)
            h.total = 45; h.poll(); assertEquals(45, h.count)
        }
    }
    @Test fun newAccountAndReturnedAccountGetFreshCleanState() = runBlocking {
        Fixture().use { h ->
            h.total = 44; h.poll(); h.inbox(); assertEquals(0, h.count)
            val a = h.root
            h.save(48); h.mount(); h.poll(); assertEquals(44, h.count)
            h.inbox(); assertEquals(0, h.count)
            assertTrue(h.sessions.activateAccount(47)); h.mount(); h.poll(); assertEquals(44, h.count)
            assertFailsWith<CancellationException> { h.poll(a) }
            assertEquals(44, h.count)
        }
    }
    @Test fun coldVmWithSameAccountDoesNotInheritStickyClean() = runBlocking {
        Fixture().use { h ->
            h.total = 44; h.poll(); h.inbox(); assertEquals(0, h.count)
            h.root.close(); h.mount(); h.poll(); assertEquals(44, h.count)
        }
    }
    @Test fun preClearLateResponseCannotEstablishPostClearBaseline() = runBlocking {
        Fixture().use { h ->
            h.total = 44; h.poll()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            h.beforeReply = { path -> if (path.contains("ImGatewayApi")) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            val old = async(Dispatchers.Default) { h.poll() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); h.inbox(); assertEquals(0, h.count)
            } finally { release.countDown() }
            withTimeout(5_000) { old.await() }; assertEquals(0, h.count)
            h.beforeReply = {}; h.total = 45; h.poll(); assertEquals(0, h.count, "First post-clear total establishes baseline")
            h.total = 46; h.poll(); assertEquals(46, h.count)
        }
    }
    @Test fun lateAResponseCannotPublishAcrossActualStoreAba() = runBlocking {
        Fixture().use { h ->
            h.poll(); val a = h.root; val aNavigation = h.navigation
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            h.beforeReply = { path -> if (path.contains("ImGatewayApi")) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            val old = async(Dispatchers.Default) { runCatching { h.poll(a) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.save(48); h.mount(); h.beforeReply = {}; h.total = 8; h.poll(); assertEquals(8, h.count)
                assertTrue(h.sessions.activateAccount(47)); h.mount(); h.total = 9; h.poll(); assertEquals(9, h.count)
                edt { assertFalse(aNavigation.push(BiliPaiNavKey.Inbox)) }
                assertEquals(9, h.count, "Late old-account clear must not clean the new A epoch")
            } finally { release.countDown() }
            val failure = withTimeout(5_000) { old.await() }.exceptionOrNull()
            assertIs<CancellationException>(failure); assertEquals(9, h.count)
        }
    }
    @Test fun likesPreferenceAndLargeTotalsAreBounded() = runBlocking {
        Fixture().use { h ->
            h.context.asAndroidContext().getSharedPreferences("message_center_preferences", 0).edit().putBoolean("disable_likes", true).apply()
            h.poll(); assertEquals(13, h.count)
            h.gatewayAvailable = false; h.poll(); assertEquals(37, h.count)
            h.im = Long.MAX_VALUE; h.poll(); assertEquals(Int.MAX_VALUE, h.count)
            h.im = -3; h.feedAvailable = false; h.poll(); assertEquals(0, h.count)
        }
    }
    @Test fun emptyAccountDoesNotIssueUnreadRequests() = runBlocking {
        Fixture(null).use { h -> h.poll(); assertEquals(0, h.count); assertTrue(h.requests.isEmpty()) }
    }
    @Test fun cancellationNeverBecomesFallbackOrZero() = runBlocking {
        val calls = mutableListOf<String>()
        val protocol = DesktopHomeMessageUnreadProtocol { path, _ -> calls += path; throw CancellationException("retired") }
        assertFailsWith<CancellationException> { protocol.gateway() }
        assertEquals(1, calls.size)
    }
}
