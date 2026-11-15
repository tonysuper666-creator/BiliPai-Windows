@file:OptIn(coil3.annotation.DelicateCoilApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.android.purebilibili.app.newImageLoader
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.feature.home.components.LinkedDockPhase
import com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.DesktopNativeTextShare
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.cache.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.download.DownloadTask
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import dev.chrisbanes.haze.HazeState
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import sun.misc.Unsafe

/** Actual retained Root and page assembly. Substitutes are limited to window, network and image IO.
 * No card action local, Home model, request receipt, accepted source or Root is fabricated. */
internal class DesktopHomeCardRootFixture : AutoCloseable {
    val folder = Files.createTempDirectory("home-card-root-")
    val sessions = DesktopSessionStore(folder.resolve("session.json"), persistent = false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var alive = true
    val repository: DesktopRepository
    val runtime: DesktopPluginRuntime
    val cards: DesktopDynamicCardSession
    val calls = CopyOnWriteArrayList<String>()
    val feedback = CopyOnWriteArrayList<String>()
    /** Blocks a synthetic HTTP response until [DesktopHomeTransportOrder.release]. The call is recorded first. */
    val transport = DesktopHomeTransportOrder()
    val imageLifetime = DesktopImageSaveLifetime { false }
    val resources: DesktopHomeActualWindowResources
    val navigation: DesktopRootWindowNavigationOwner
    val shell: DesktopOriginalVideoShellOwner
    var video: RootVideo? = null
    val binding: DesktopReadyHomeFactoryBinding
    val retainer: DesktopHomeRootRetainer
    val root: DesktopHomeRetainedRoot
    val pages: DesktopOriginalRootPageBindings
    val routes: DesktopOriginalRootRouteAssembly
    val chrome: DesktopOriginalRootChromeBindings
    val clock = VideoCardTransitionClock()
    private val previousLoader = SingletonImageLoader.get(PlatformContext.INSTANCE)
    private val image = folder.resolve("cover.png").toFile().apply {
        ImageIO.write(BufferedImage(160, 100, BufferedImage.TYPE_INT_ARGB), "png", this)
    }
    private val loader = newImageLoader(PlatformContext.INSTANCE,
        Call.Factory { error("Image must not access a socket") }, folder.resolve("cache").toFile())
        .newBuilder().components { add(object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                chain.withRequest(chain.request.newBuilder().data(image).build()).proceed()
        }) }.build()
    private val share: DesktopNativeTextShare

    init {
        check(EventQueue.isDispatchThread())
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
            AccountSummary(47L, "Synthetic", ""))
        repository = DesktopRepository(sessions)
        val clientField = DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
        val offline = repository.httpClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            calls += "${request.method} $path ${request.url.encodedQuery.orEmpty()} ${
                (request.body as? FormBody)?.let { body -> (0 until body.size).joinToString("&") { "${body.name(it)}=${body.value(it)}" } }.orEmpty()}"
            transport.awaitRelease(path)
            val data = when {
                path == "/" -> "{}" // Actual visitor bootstrap; no socket is opened.
                path.endsWith("/nav") -> """{"code":0,"data":{"isLogin":true,"mid":47,"uname":"Synthetic","wbi_img":{"img_url":"https://example.invalid/abcdefghijklmnopqrstuvwxyz012345.png","sub_url":"https://example.invalid/012345abcdefghijklmnopqrstuvwxyz.png"}}}"""
                path.contains("rcmd") || path.endsWith("/popular") -> """{"code":0,"data":{"item":[$VIDEO],"list":[$VIDEO],"no_more":true}}"""
                path.endsWith("/folder/created/list-all") -> """{"code":0,"data":{"count":1,"list":[{"id":9,"title":"Root synthetic folder","media_count":0,"fav_state":0}]}}"""
                path.endsWith("/resource/deal") -> """{"code":0}"""
                path == "/x/web-interface/view" -> {
                    val bvid = request.url.queryParameter("bvid")
                    val info = when (bvid) {
                        "BV1xx411c7mD" -> VIDEO
                        "BV1xx411c7mE" -> VIDEO.replace("BV1xx411c7mD", bvid)
                        else -> error("Unexpected synthetic video request: $bvid")
                    }
                    """{"code":0,"data":${info.dropLast(1)},"pages":[{"cid":71,"page":1,"part":"Root","duration":60}]}}"""
                }
                path.endsWith("/playurl") -> """{"code":0,"data":{"quality":64,"timelength":60000,"accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"https://media.invalid/video.m4s","bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7}],"audio":[{"id":30280,"baseUrl":"https://media.invalid/audio.m4s","bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2"}]}}}"""
                path.endsWith("/archive/related") || path.endsWith("/archive/tags") -> """{"code":0,"data":[]}"""
                path.endsWith("/player/wbi/v2") || path.endsWith("/player/v2") -> """{"code":0,"data":{"subtitle":{"subtitles":[]}}}"""
                path.endsWith("/finger/spi") -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                else -> null
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (data == null) 503 else 200)
                .message("Synthetic transport").body((data ?: "{}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        clientField.set(repository, offline)
        val store = DesktopPluginStore(folder.resolve("global"))
        val blocked = DesktopBlockedUpStore(DesktopPluginContext(store))
        val discovery = DesktopDiscoveryRepository(repository, DesktopDiscoveryPreferences(folder.resolve("discovery"), blocked))
        val community = DesktopCommunityRepository(repository, blocked)
        runtime = DesktopPluginRuntime(store, repository, community, discovery)
        shell = DesktopOriginalVideoShellOwner(repository, store, scope, { alive }, { it(); true }, {}, { feedback += it })
        val settings = runBlocking { DesktopOriginalHomePreferences.create(store, scope, false, { false }) }
        cards = DesktopDynamicCardSession(repository)
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        val window = unsafe.allocateInstance(HeadlessWindow::class.java) as Window
        resources = DesktopHomeActualWindowResources(window, { alive }, { Color.BLACK }, Logger.getLogger("fixture"))
        navigation = DesktopRootWindowNavigationOwner(window, { alive }, {})
        val locations = DesktopImageSaveLocations(DesktopImageSaveLocationPreferences(store, imageLifetime::withCommit),
            imageLifetime::isActive, imageLifetime::withCommit, { Result.success(folder) }, { Result.success(folder) })
        share = DesktopNativeTextShare({ error("No native sharing in this fixture") }, "", { window })
        val clipboard = object : DesktopTextClipboard { override fun copyText(value: String) = true }
        val textShare = DesktopTextShareBindings { _, _, _ -> false }
        SingletonImageLoader.setUnsafe(loader)
        binding = DesktopReadyHomeFactoryBinding(repository, discovery, community, runtime, cards, scope, window,
            runtime.context, settings, navigation, resources, locations, imageLifetime, loader, PlatformContext.INSTANCE,
            DesktopThemePrefs(store), MutableStateFlow(DesktopProfileWindowConfiguration(1200, 900)),
            DesktopProfileVideoWidth { _, _ -> error("No video metadata IO") }, image, clipboard, share, textShare, null,
            MutableStateFlow(false), MutableStateFlow(false), WallpaperPaletteStore(),
            DesktopWindowsProfileChrome(window, resources.clientPolicy, { true }, {}), Channel(Channel.CONFLATED), HazeState(),
            DesktopHomeRootReturnPorts({ it(); true }, { Offset.Zero }, { System.nanoTime() / 1_000_000 }, clock,
                { false }, { false }, { true }), { feedback += it }, {}, {},
            { gate -> retainerCurrentEntry(gate) != null })
        retainer = DesktopHomeRootRetainer(runtime.recommendations, { repository.sessionEpoch }, { alive })
        root = awaitEdt { retainer.install(repository.sessionEpoch, 47L, binding.factory) }
        val gate = root.entry.gate
        val accounts = DesktopProfileAccountsBinding(repository, gate.epoch, gate.mid,
            requireNotNull(gate.scope.coroutineContext[Job]), gate::owns, gate::commit)
        val profile = binding.profile(root, accounts)
        val scroll = mutableFloatStateOf(0f)
        val scrolling = mutableStateOf(false)
        pages = desktopReadyOriginalRootPages(repository, community.favoriteApi, { it(); true }, { _, _ -> },
            { error("Fixture is already logged in") }, { feedback += it }, { false }, binding.window, scroll, scrolling,
            Channel(Channel.CONFLATED), { false }, { 0.dp }, {}, clock, { false }, { 0 }, { 0.dp }, profile,
            { 0 }, null, null, null, {}, {})
        routes = DesktopOriginalRootRouteAssembly(root, mutableStateListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost),
            DesktopOriginalRootRoutePlatform({ it(); true }, { _, _ -> }, { _, _ -> null }, {}, {}),
            { setOf("home", "profile", "dynamic", "partition", "live") }, navigation)
        val owner = DesktopOriginalLinkedDockOwner(scroll, scrolling, mutableStateOf(LinkedDockPhase.Expanded), {}, routes::owns)
        chrome = DesktopOriginalRootChromeBindings(owner,
            DesktopOriginalRootNavigationPreferences(store, scope, { alive }, { it(); true }), scope,
            DesktopOriginalFrostedNavigationActions({}, {}, {}, {}, {}, {}, {}, {}, null, null, null, null), null,
            { DesktopOriginalNowPlayingVisibility(false, false, false, false, false, false, false, false) },
            { DesktopOriginalNowPlayingNavigation("home", false, NowPlayingBarHandoffState.Idle, false, {}, { _, _ -> }, {}) },
            { 0.dp }, { 0 }, { 0 }, { false }, null, {})
    }

    private fun retainerCurrentEntry(gate: DesktopHomeRetainedGate) = retainer.current()?.takeIf { it.entry.gate === gate }

    override fun close() {
        routes.close()
        awaitEdt { shell.closeAndJoin() }
        video?.close()
        awaitEdt { retainer.closeAndJoin() }
        alive = false
        navigation.close()
        cards.close(); resources.close(); imageLifetime.close(); runtime.close(); share.close(); scope.cancel()
        SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown()
        folder.toFile().deleteRecursively()
    }

    /** Install the real Shell slot/Factory/request/media/Runtime owners. Native readiness and
     * window presentation are IO substitutes; no decoder, native frames or physical mini is claimed. */
    fun installVideo(): RootVideo = RootVideo().also { video = it }

    inner class RootVideo : AutoCloseable {
        val player = MpvPlayer(useNullAudioOutput = true)
        private val subtitles = DesktopSubtitleAssets(repository.httpClient)
        private val cache = DesktopMediaByteCache(folder.resolve("bytes"), scope, repository)
        private val privacy = DesktopSearchPreferences(folder.resolve("privacy"))
        private val progress = DesktopOriginalGlobalPlaybackProgress(runtime.context, scope) { feedback += it }
        private val fraud = DesktopCommentFraudRoot(repository, cards, folder.resolve("fraud"), scope)
        val entries = mutableListOf<Pair<DesktopOriginalVideoOwnerAssembly, DesktopOriginalVideoAcceptedPublication>>()
        var retained: VideoPlaybackUiState.Success? = null
        val assembly: DesktopOriginalVideoOwnerAssembly
        val factory: DesktopOriginalVideoRootFactory
        val holderMini: DesktopOriginalVideoHolderMiniBinding
        private var miniMode = false
        private var navigating = false

        fun closeMiniWindow() { miniMode = false }

        init {
            runtime.store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(false),
                "video_codec" to JsonPrimitive("avc1"), "video_second_codec" to JsonPrimitive("avc1"), "wifi_quality" to JsonPrimitive(64)))
            runtime.store.update("video_note_settings", mapOf("video_note_enabled" to JsonPrimitive(false)))
            awaitEdt { privacy.setPrivacyMode(true) }
            factory = DesktopOriginalVideoRootFactory(runtime.context, repository, repository.originalVideoLogin,
                runtime, player, subtitles, cache, privacy, { PlayerPreferences() }, { gate ->
                    val settings = DesktopOriginalPlayerSettingsContext(runtime.context, gate::owns, gate::commitEntry,
                        largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
                    val operations = DesktopDynamicCardOperations(repository, gate.capturedEpoch, gate::owns)
                    val requests = DesktopVideoCommentOperationsBinding(operations, repository,
                        { error("No comment image IO") }, fraud.records, operations::checkCommentStatus, fraud::recordPublished)
                    val comments = DesktopOriginalCommentRootOwner(operations, requests, gate.scope, operations.emotes,
                        gate::owns, { feedback += it })
                    val diagnostics = DesktopOriginalVideoOwnerDiagnosticsBinding(runtime.store, null, gate::owns, gate::commitEntry)
                    // Download IO is unavailable here. Every command fails rather than pretending success.
                    val downloads = ioPort<DesktopOriginalVideoOwnerDownload> { name ->
                        if (name == "getTasks") MutableStateFlow(emptyMap<String, DownloadTask>()) else error("Unexpected download IO: $name")
                    }
                    val network = object : DesktopOriginalVideoOwnerNetwork {
                        override fun isMobileData() = false
                        override fun isWifi() = true
                        override fun cdnNetwork() = DesktopCdnNetworkObservation(true, "synthetic-transport")
                        override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                            DesktopHomeNetworkObservation(true, 3, 71L, false, "synthetic-transport"), null)
                    }
                    val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
                        override fun isHevcSupported() = false
                        override fun isAv1Supported() = false
                        override fun isHdrSupported() = false
                        override fun isDolbyVisionSupported() = false
                        override fun isDolbyAtmosAudioSupported() = false
                        override fun isDolbySoftwareAudioDecoderRequired() = false
                    }
                    val effects = DesktopOriginalVideoOwnerEffects(shell.mini, shell.playlist, downloads, network,
                        repository.originalVideoOwnerCacheView(gate.capturedEpoch, gate::owns, gate::commitEntry),
                        diagnostics, diagnostics, ioPort<DesktopOriginalVideoOwnerDanmaku> { error("Unexpected danmaku IO: $it") },
                        shell.background)
                    DesktopOriginalVideoRootEntryPorts(settings, comments, effects, capabilities,
                        progress.forEntry({ bvid, cid -> shell.slot.currentAssembly()?.let { a ->
                            a.native.current()?.takeIf { it.request.bvid == bvid && it.request.cid == cid }?.let { a.section.duration }
                        } ?: 0L }, gate::owns),
                        DesktopOriginalVideoAnalyticsBinding(runtime.store, null, gate::owns), {}, { feedback += it }, {},
                        { false }, { false }, { _, _, _, _ -> }, { error("No native replay IO") }, { false }, { false },
                        { shell.awaitNativeInitialization(gate) },
                        DesktopTodayWatchFeedbackWriteBinding(runtime.context, root.entry.gate::owns, root.entry.gate::commit))
                }, { error("Synthetic authorization retired") }, {})
            awaitEdt { shell.install(repository.sessionEpoch, factory, AtomicReference(null), {}, {}) }
            assembly = shell.slot.requireAssembly()
            holderMini = DesktopOriginalVideoHolderMiniBinding(assembly, shell.slot::currentAssembly, shell.mini,
                { miniMode }, { navigating }, { a, value -> check(a === assembly); navigating = value },
                { _, _, _ -> error("No navigation exit") }, { a, _ -> check(a === assembly) },
                { a, value -> check(a === assembly); retained = value },
                { a, forced ->
                    check(a === assembly && !forced)
                    entries += a to checkNotNull(a.native.current())
                    miniMode = true
                })
            val holder = ioPort<DesktopOriginalVideoHolderPlatform> { name ->
                if (name == "getMini") holderMini else error("Unexercised Holder IO: $name")
            }
            val windows = ioPort<DesktopOriginalVideoRootWindowPlatforms> { name -> when (name) {
                "getHolder" -> holder
                "awaitNativeInitialization" -> Unit // No native decoder is started by this headless IO boundary.
                else -> error("Unexercised Window IO: $name")
            } }
            shell.publishWindows(assembly, windows)
        }
        override fun close() {
            awaitEdt { progress.closeAndJoin() }
            fraud.close(); cache.close(); subtitles.close(); player.close()
        }
    }

    private inline fun <reified T> ioPort(crossinline invoke: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            invoke(method.name)
        } as T

    /** No constructor is invoked. Only the physical Window IO surface is replaced. */
    private class HeadlessWindow : Window(null as Frame?) {
        override fun isDisplayable() = true
        override fun isVisible() = true
        override fun isFocused() = true
        override fun getOwner(): Window? = null
        override fun addWindowListener(listener: WindowListener?) {}
        override fun removeWindowListener(listener: WindowListener?) {}
        override fun addWindowStateListener(listener: WindowStateListener?) {}
        override fun removeWindowStateListener(listener: WindowStateListener?) {}
        override fun addComponentListener(listener: ComponentListener?) {}
        override fun removeComponentListener(listener: ComponentListener?) {}
        override fun setBackground(color: Color?) {}
        override fun getBackground(): Color = Color.BLACK
    }

    /** One in-flight synthetic response. Arrival is recorded before the worker blocks, so the EDT can release it. */
    internal class DesktopHomeTransportOrder {
        private class Hold {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val responded = AtomicBoolean(false)
            val blockedOnEdt = AtomicBoolean(false)
        }
        private val holds = java.util.concurrent.ConcurrentHashMap<String, Hold>()
        fun hold(suffix: String) { holds[suffix] = Hold() }
        fun release(suffix: String) { holds[suffix]?.release?.countDown() }
        fun arrived(suffix: String) = holds[suffix]?.arrived?.count == 0L
        fun responded(suffix: String) = holds[suffix]?.responded?.get() == true
        fun blockedOnEdt(suffix: String) = holds[suffix]?.blockedOnEdt?.get() == true
        fun awaitRelease(path: String) {
            val hold = holds.entries.firstOrNull { path.endsWith(it.key) }?.value ?: return
            hold.arrived.countDown()
            if (EventQueue.isDispatchThread()) {
                hold.blockedOnEdt.set(true)
                return
            }
            check(hold.release.await(20, TimeUnit.SECONDS)) { "Transport hold timed out for $path" }
            hold.responded.set(true)
        }
    }

    companion object {
        /** Pump the real Swing dispatcher while awaiting actual suspend installation/drain.
         * A nested secondary loop can return before Dispatchers.Main finishes the block, and
         * CompletableFuture.get on that same EDT never runs the timeout. Each pump posts its
         * own exit, so the wait cannot park the event thread. */
        fun <T> awaitEdt(block: suspend () -> T): T {
            check(EventQueue.isDispatchThread())
            val result = java.util.concurrent.CompletableFuture<T>()
            val waiting = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            waiting.launch {
                try { result.complete(withTimeout(15_000) { block() }) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!result.isDone) {
                if (System.nanoTime() >= deadline) {
                    waiting.cancel()
                    pumpEdt()
                    throw AssertionError("awaitEdt timed out after 20s")
                }
                pumpEdt()
                if (!result.isDone) Thread.sleep(15)
            }
            waiting.cancel()
            try {
                return result.get()
            } catch (wrapped: java.util.concurrent.ExecutionException) {
                throw wrapped.cause ?: wrapped
            }
        }

        fun pumpEdt() {
            val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            EventQueue.invokeLater { loop.exit() }
            check(loop.enter())
        }

        const val TITLE = "Root generated card"
        private const val VIDEO = """{"id":11,"aid":11,"bvid":"BV1xx411c7mD","cid":71,"title":"Root generated card","pic":"https://example.invalid/cover.png","duration":60,"owner":{"mid":9,"name":"Synthetic up"},"stat":{"view":1200,"danmaku":3}}"""
    }
}
