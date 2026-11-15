@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemeSettings
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.asAndroidContext
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Real RootHost -> generated MessageCenter/Inbox/Chat -> real owner/VM/repositories.
 * Only the HTTP transport is synthetic. Nothing can reach a socket: this interceptor
 * returns every allowed reply and rejects every unrecognised request, including sends.
 * The reflection seam matches DesktopOriginalMessagePagesTest; production stays untouched.
 */
internal class DesktopMessageConsumerFixture {
    private val homeFixture = DesktopPageTestFixture()
    val sessions = DesktopSessionStore(homeFixture.root.resolve("synthetic-message-account.json"), persistent = false)
    val repository = DesktopRepository(sessions)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing.immediate)
    val hits = ConcurrentLinkedQueue<String>()
    private val community: DesktopCommunityRepository
    private val transport: OkHttpClient
    lateinit var routes: DesktopOriginalRootRouteAssembly
        private set
    lateinit var pages: DesktopOriginalMessagePagesRoot
        private set
    var generation by mutableIntStateOf(0)
        private set
    @Volatile var late: LateReply? = null

    class LateReply(val talker: Long) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
    }

    init {
        saveAccount("initial")
        transport = repository.httpClient.newBuilder().retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                hits += "${request.method} $path ${request.url.encodedQuery.orEmpty()}"
                check(request.url.host in setOf("api.bilibili.com", "api.vc.bilibili.com", "app.bilibili.com"))
                check(path in allowedPaths) { "Unwired or forbidden synthetic route: $path" }
                val blocked = late?.takeIf { path.endsWith("fetch_session_msgs") && request.url.queryParameter("talker_id") == it.talker.toString() }
                if (blocked != null) {
                    blocked.entered.countDown()
                    check(blocked.release.await(10, TimeUnit.SECONDS)) { "Late reply was never released" }
                }
                val grpc = path.contains("/bilibili.app.im.v1.im/")
                val body = if (grpc) imReply() else jsonReply(path, request.url.queryParameter("talker_id") ?: "0")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic only")
                    .header("Content-Type", if (grpc) "application/grpc" else "application/json")
                    .header("grpc-status", "0")
                    .body(body.toResponseBody((if (grpc) "application/grpc" else "application/json").toMediaType()))
                    .build().also { blocked?.returned?.countDown() }
            }.build()
        repository.javaClass.getDeclaredField("client").apply { isAccessible = true; set(repository, transport) }
        community = DesktopCommunityRepository(repository, DesktopBlockedUpStore(homeFixture.pluginContext))
        community.javaClass.getDeclaredField("client").apply { isAccessible = true; set(community, transport) }
        createRoot()
    }

    private fun saveAccount(label: String) = sessions.saveAccount(
        mapOf("SESSDATA" to "synthetic-$label", "bili_jct" to "synthetic-csrf", "buvid3" to "synthetic-visitor"), AccountSummary(42, "Anonymous fixture", ""), imported = true)

    private fun createRoot() {
        val plugins = homeFixture.pluginContext
        val blocked = DesktopBlockedUpStore(plugins)
        val analytics = DesktopHomeRootAnalytics(plugins.store, null, { true })
        val embedded = object : DesktopHomeEmbeddedRetainedOwner, DesktopHomeEmbeddedPages by unused<DesktopHomeEmbeddedPages>() {
            override fun close() = Unit
            override suspend fun closeAndJoin() = Unit
        }
        val entry = DesktopHomeRetainedEntry.create(repository,
            DesktopDiscoveryPreferences(homeFixture.root.resolve("discovery"), blocked), plugins, scope,
            repository.sessionEpoch, 42, { true }, homeFixture.home.settings, MutableStateFlow(false), { false },
            { analytics }, DesktopBlockedUpRepository(repository, blocked),
            { error("Unexpected authentication observation") }, { error("Unexpected Home feedback") }, { _, _ -> embedded })
        val media = DesktopHomeMediaLifetime(entry.gate.scope, entry.gate::owns, entry.gate::commit) { error("No media") }
        val ports = DesktopHomeActualMediaPorts(media, { error("No wallpaper MIME") }, { _, _, _, _ -> error("No wallpaper") },
            MutableStateFlow(false), { error("No media feedback") }).ports
        val returns = DesktopHomeReturnNavigationOwner(entry.gate::owns, entry.gate::commit, { action -> action(); true },
            { Offset.Zero }, { 42L }, VideoCardTransitionClock(), { false }, { false }, { true })
        val root = DesktopHomeRetainedRoot(entry, homeFixture.home, ports, returns,
            DesktopLiveNavigationBinding(entry.requests.environment, unused<SearchApi>(), plugins.store), media,
            DesktopHomeLottieBinding(repository.httpClient, entry.gate::owns, entry.gate::commit, { error("No animation") }))
        routes = DesktopOriginalRootRouteAssembly(root, mutableStateListOf(BiliPaiNavKey.Inbox),
            DesktopOriginalRootRoutePlatform({ action -> action(); true }, { _, _ -> }, { _, _ -> error("No video") }, {},
                { error("Unexpected Root back") }), { setOf("home") })
        pages = DesktopOriginalMessagePagesRoot(repository, community, routes, homeFixture.home, { _, _ -> error("No external link") }, false)
        generation++
    }

    fun replaceSession() {
        saveAccount("replacement-$generation")
        pages.close()
        routes.close()
        createRoot()
    }

    fun owner() = pages.entry(BiliPaiNavKey.Inbox)

    inner class Host : AutoCloseable {
        private var frame = 0L
        private val navigation = object : androidx.navigationevent.NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = androidx.navigationevent.NavigationEventDispatcher()
        }
        private val platform = DesktopHomePlatform(false, false, false, false, false, false, object : DesktopHomeWindowBackgroundPort {
            override val isInBackground = false
            override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
        }, 0f, false)
        private val context = ConsumerMessagePreferencesContext(homeFixture.pluginContext.asAndroidContext())
        private val scene = ImageComposeScene(1200, 700, Density(1f)) {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle = AppUiStyle.MATERIAL3), systemLanguageTags = listOf("zh-CN")) {
                DesktopDetailWindow {
                    CompositionLocalProvider(
                        LocalDesktopUpstreamAndroidContext provides context,
                        LocalDesktopHomePlatform provides platform,
                        androidx.lifecycle.compose.LocalLifecycleOwner provides homeFixture.lifecycleOwner,
                        androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides navigation,
                        LocalDesktopOriginalMessageLinkNavigation provides { error("No external navigation") },
                        LocalDesktopChatImeInsets provides WindowInsets(0),
                    ) {
                        // Read the generation so real account replacement mounts the new Root.
                        generation
                        DesktopOriginalMessagePageRootHost(BiliPaiNavKey.Inbox, pages, routes, active = true)
                    }
                }
            }
        }
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        fun under(tag: String) = nodes().singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.let(::flatten).orEmpty()
        fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        fun contacts() = under(DESKTOP_MESSAGE_LIST_PANE_TAG).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }.filter { it.startsWith("Contact") }.distinct()
        fun select(name: String) {
            val row = under(DESKTOP_MESSAGE_LIST_PANE_TAG).first { it.config.contains(SemanticsActions.OnClick) && texts(it).filter { t -> t.startsWith("Contact") } == listOf(name) }
            check(row.config[SemanticsActions.OnClick].action!!.invoke())
        }
        private fun field() = under(DESKTOP_MESSAGE_CHAT_PANE_TAG).singleOrNull { it.config.contains(SemanticsActions.SetText) }
        fun draft() = field()?.config?.getOrNull(SemanticsProperties.EditableText)?.text
        fun type(value: String) { check(field()!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))) }
        fun scroll(tag: String, index: Int) {
            val list = under(tag).first { it.config.contains(SemanticsActions.ScrollToIndex) }
            check(list.config[SemanticsActions.ScrollToIndex].action!!.invoke(index))
        }
        fun readOlderMessages() {
            val list = under(DESKTOP_MESSAGE_CHAT_PANE_TAG).first { it.config.contains(SemanticsActions.ScrollBy) }
            check(list.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 1200f))
        }
        fun position(tag: String) = under(tag).first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        suspend fun settle(until: () -> Boolean = { true }) {
            try { withTimeout(8_000) {
                do { repeat(3) { frame += 16_000_000L; scene.render(frame).close() }; delay(15) } while (!until())
            } } catch (failure: TimeoutCancellationException) {
                throw AssertionError("Actual RootHost did not reach condition; texts=${nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }}; requests=$hits", failure)
            }
            repeat(3) { frame += 16_000_000L; scene.render(frame).close() }
        }
        override fun close() = scene.close()
    }

    suspend fun shutdown() {
        println("Synthetic consumer requests: ${hits.joinToString("; ")}")
        late?.release?.countDown()
        pages.closeAndJoin()
        routes.close()
        scope.cancel()
        transport.dispatcher.executorService.shutdownNow()
        transport.connectionPool.evictAll()
        homeFixture.shutdown()
    }

    private fun jsonReply(path: String, talker: String): ByteArray = when {
        path.endsWith("/feed/rcmd") -> """{"code":0,"data":{"item":[]}}"""
        path == "/x/relation/followings" -> """{"code":0,"data":{"list":[],"total":0}}"""
        path == "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"Anonymous fixture","wbi_img":{"img_url":"https://fixture.invalid/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png","sub_url":"https://fixture.invalid/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"}}}"""
        path == "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
        path.endsWith("fetch_session_msgs") -> """{"code":0,"data":{"min_seqno":1,"max_seqno":80,"has_more":0,"messages":[${(80 downTo 1).joinToString(",") { id -> """{"sender_uid":$talker,"receiver_id":42,"msg_key":$id,"msg_seqno":$id,"timestamp":$id,"msg_type":1,"content":"{\"content\":\"Anonymous $talker message $id\"}"}""" }}]}}"""
        path.endsWith("session_detail") -> """{"code":0,"data":{"is_dnd":0,"is_intercept":0}}"""
        path.endsWith("get_msg_dnd") -> """{"code":0,"data":{"uid_settings":[]}}"""
        path.endsWith("is_limit") -> """{"code":0,"data":{"is_limit":false}}"""
        path.endsWith("get_session_ss") -> """{"code":0,"data":{"push_setting":0,"follow_status":1}}"""
        path.endsWith("/card") -> """{"code":0,"data":{"card":{"mid":"$talker","name":"Contact$talker","face":""}}}"""
        else -> """{"code":0,"data":{}}"""
    }.toByteArray()

    private fun imReply(): ByteArray = ProtoWire.frame(ProtoWire.message(
        ProtoWire.bytes(1, ProtoWire.message(ProtoWire.int64(1, 1), ProtoWire.bool(2, false))),
        *(1L..40L).map { id -> ProtoWire.bytes(5, ProtoWire.message(
            ProtoWire.bytes(1, ProtoWire.bytes(1, ProtoWire.int64(1, id))),
            ProtoWire.bytes(2, ProtoWire.string(1, "Contact$id")), ProtoWire.int64(5, 1000 - id))) }.toTypedArray()
    ), gzipMinLength = Int.MAX_VALUE)

    private companion object {
        val allowedPaths = setOf("/bilibili.app.im.v1.im/SessionMain", "/bilibili.app.im.v1.im/ClearUnread",
            "/session_svr/v1/session_svr/single_unread", "/x/msgfeed/unread", "/svr_sync/v1/svr_sync/fetch_session_msgs",
            "/session_svr/v1/session_svr/session_detail", "/link_setting/v1/link_setting/get_msg_dnd", "/link_setting/v1/link_setting/is_limit",
            "/link_setting/v1/link_setting/get_session_ss", "/session_svr/v1/session_svr/update_ack", "/x/web-interface/card",
            "/x/emote/user/panel/web", "/x/emote/package", "/x/web-interface/nav", "/x/frontend/finger/spi", "/x/web-interface/wbi/index/top/feed/rcmd", "/x/relation/followings")
        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "Unused ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected unrelated port ${T::class.java.simpleName}.${method.name}")
            }
        } as T
    }
}

/** Same missing message preference-store adapter as DesktopMessageCenterWindowSizeTest.
 * It supplies empty synthetic preferences, never a replacement message model. */
private class ConsumerMessagePreferencesContext(private val root: android.content.Context) : android.content.Context(root.dataDir),
    androidx.datastore.preferences.DesktopPreferencesDataStoreOwner {
    private val values = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
    private val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        override val data get() = values
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = transform(values.value).also { values.value = it }
    }
    override val applicationContext: android.content.Context get() = this
    override val filesDir get() = root.filesDir
    override val cacheDir get() = root.cacheDir
    override val assets get() = root.assets
    override val resources get() = root.resources
    override fun getSharedPreferences(name: String, mode: Int) = root.getSharedPreferences(name, mode)
    override fun desktopPreferencesDataStore(name: String) = if (name == "message_center_preferences") store
        else (root.applicationContext as androidx.datastore.preferences.DesktopPreferencesDataStoreOwner).desktopPreferencesDataStore(name)
}
