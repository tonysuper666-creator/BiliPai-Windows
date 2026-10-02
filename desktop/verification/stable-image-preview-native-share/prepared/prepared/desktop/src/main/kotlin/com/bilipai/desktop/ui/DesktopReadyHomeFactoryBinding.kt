package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.LifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import com.android.purebilibili.core.network.*
import com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import java.awt.Window
import java.nio.file.Path
import java.util.UUID

/** Concrete composition from existing ReadyApp actors. Window/global services live above
 * the captured Home entry, and no service below builds another HTTP client or persistence. */
internal class DesktopReadyHomeFactoryBinding(
    private val repository: DesktopRepository,
    private val discovery: DesktopDiscoveryRepository,
    private val community: DesktopCommunityRepository,
    private val runtime: DesktopPluginRuntime,
    private val cardSession: DesktopDynamicCardSession,
    private val scope: CoroutineScope,
    private val actualWindow: Window,
    private val context: DesktopPluginContext,
    private val settings: DesktopHomeSettingsPort,
    private val lifecycle: LifecycleOwner,
    private val resources: DesktopHomeActualWindowResources,
    private val locations: DesktopImageSaveLocations,
    private val windowLifetime: DesktopImageSaveLifetime,
    private val applicationImageLoader: ImageLoader,
    private val platformContext: PlatformContext,
    private val appearance: DesktopThemePrefs,
    private val profileConfiguration: StateFlow<DesktopProfileWindowConfiguration>,
    private val profileMetadata: DesktopProfileVideoWidth,
    private val applicationIcon: Any,
    private val clipboard: DesktopTextClipboard,
    private val nativeShare: DesktopNativeTextShare,
    private val textShare: DesktopTextShareBindings,
    private val diagnostics: DesktopDiagnostics?,
    private val musicOverlayVisible: StateFlow<Boolean>,
    private val incrementalRefresh: StateFlow<Boolean>,
    private val palette: WallpaperPaletteStore,
    private val chrome: DesktopWindowsProfileChrome,
    private val liveScroll: Channel<Unit>,
    private val haze: HazeState,
    private val returnPorts: DesktopHomeRootReturnPorts,
    private val feedback: (String) -> Unit,
    private val openLink: (String) -> Unit,
    private val invalidateAuthentication: (Long, Long) -> Unit,
    private val rootPublished: (DesktopHomeRetainedGate) -> Boolean,
) {
    private val systemWallpaper = DesktopWindowsSystemWallpaperPort()
    val window = DesktopHomeRootWindowBindings(actualWindow, context, settings, lifecycle,
        resources, locations, textShare, diagnostics, palette.currentPalette,
        loadWallpaperPalette = { capturedGate, uri, pageScope ->
            palette.loadWallpaperPalette(DesktopOwnedWallpaperPaletteContext(platformContext,
                applicationImageLoader, systemWallpaper, capturedGate::owns, capturedGate::commit), uri, pageScope)
        }, musicOverlayVisible = musicOverlayVisible,
        sourceForMediaUrl = { url -> PlaybackSource(url, title = "BiliPai 预览 / 壁纸",
            referer = if (url.startsWith("http://") || url.startsWith("https://")) "https://www.bilibili.com/" else "",
            cookieHeader = "") },
        isVideoWallpaper = { uri -> desktopHomeWallpaperIsVideo(uri, ::desktopHomeWallpaperFile) },
        imageWallpaper = { owner, uri, model, playing, modifier ->
            DesktopHomeWallpaperImages(owner, uri, model, playing, modifier, ::desktopHomeWallpaperFile, feedback)
        }, clipboard = { text -> if (!clipboard.copyText(text)) feedback("无法写入系统剪贴板") },
        externalLink = openLink, feedback = feedback,
        onMatchClick = { openLink("https://www.bilibili.com/match/") },
        liveScrollToTop = liveScroll, globalHaze = { haze }, shareFiles = ::shareFiles,
        mediaShare = { file, title, text, owned, retired -> nativeShare.shareMedia(file, title, text, owned, retired) },
        overlays = ::overlays)

    val factory = DesktopHomeRootFactory(repository, discovery.homePreferences,
        community.blockedUpRepository, runtime, cardSession, scope, window, incrementalRefresh,
        community.searchPreferences::isPrivacyModeEnabledSync, invalidateAuthentication, returnPorts)

    private fun diagnostic(failure: Throwable) {
        // No source URL, account, filesystem path or exception message is recorded.
        diagnostics?.record("W", "ProfilePlatform", failure.javaClass.simpleName)
    }

    fun profile(root: DesktopHomeRetainedRoot, accounts: DesktopProfileAccountPort): DesktopOriginalProfileBinding {
        val gate = root.entry.gate
        gate.assertOwned()
        fun <T> api(type: Class<T>, base: String = "https://api.bilibili.com/") =
            repository.ownedHomeService(type, base, gate.epoch, gate::owns)
        val video = api(BilibiliApi::class.java)
        val space = api(SpaceApi::class.java)
        val dynamic = api(DynamicApi::class.java)
        val search = api(SearchApi::class.java)
        val pgc = api(BangumiApi::class.java)
        val favorites = DesktopFavoriteEnvironment(gate.scope, video, space, dynamic, pgc,
            gate::owns, { repository.ownedHomeCookie("bili_jct", gate.epoch, gate::owns) },
            { if (gate.owns()) repository.account.value?.mid else throw CancellationException("Profile account retired") },
            feedback, null, null, null, null, null, null)
        val analytics = object : DesktopProfileAnalytics {
            private val original = DesktopHomeRootAnalytics(runtime.store, diagnostics, gate::owns)
            override fun logScreenView(name: String) = original.logScreenView(name)
            override fun syncUserContext(mid: Long?, isVip: Boolean, privacyModeEnabled: Boolean) =
                original.syncUserContext(mid, isVip, privacyModeEnabled)
            override fun logLogout() { if (gate.owns()) diagnostics?.record("I", "Profile", "logout") }
        }
        return createDesktopOriginalWindowsProfileBinding(context, runtime.store.root, gate.scope,
            gate::owns, gate::commit, video, space, dynamic, search,
            DesktopOriginalProfileSplashProtocol(api(SplashApi::class.java)), favorites.favorite, favorites.pgc,
            { repository.ownedHomeCookie("bili_jct", gate.epoch, gate::owns) }, accounts, appearance,
            profileConfiguration, desktopDetailRenderEffectsSupported(), applicationIcon, actualWindow,
            repository.ownedHomeCallFactory(gate.epoch, gate::owns), profileMetadata,
            (root.entry.embeddedPages as DesktopOriginalHomeEmbeddedAggregate).gallery.imageAssets,
            clipboard, chrome, DesktopOriginalProfileMedia(root.mediaLifetime, feedback), analytics,
            { text -> gate.commit { feedback(text) } }, ::diagnostic)
    }

    private fun shareFiles(gate: DesktopHomeRetainedGate): DesktopVideoShareFiles {
        fun owned() = gate.owns() && rootPublished(gate)
        return DesktopVideoShareFiles(runtime.store.root.resolve("cache"), { url ->
            if (!owned()) throw CancellationException("Share Root has not been published")
            readShareBytes(repository.ownedHomeCallFactory(gate.epoch, ::owned), url, gate)
        }, ::owned, { block -> gate.commit {
            if (!owned()) throw CancellationException("Share Root publication retired")
            block()
        } })
    }

    private fun overlays(gate: DesktopHomeRetainedGate, requests: DesktopHomeRootRequestBinding,
        files: DesktopVideoShareFiles): DesktopHomeOverlayPorts {
        val operations = DesktopDynamicCardOperations(repository, gate.epoch,
            stillOwned = gate::owns, sharedEmotes = cardSession.emotes)
        val messages = DesktopOriginalVideoShareMessages(repository.ownedHomeService(MessageApi::class.java,
            "https://api.vc.bilibili.com/", gate.epoch, gate::owns),
            { repository.ownedHomeCookie("bili_jct", gate.epoch, gate::owns) },
            { if (gate.owns()) repository.account.value?.mid else throw CancellationException("Share account retired") },
            // Original MessageRepository caches one random device ID per repository lifetime.
            UUID.randomUUID().toString().let { identity -> { identity } })
        val share = DesktopVideoShareBindings(operations, requests.ports.following,
            { id, text -> messages.sendTextMessage(id, text) }, files,
            { if (gate.owns()) repository.account.value?.mid else throw CancellationException("Share owner retired") },
            gate::owns, { text -> if (!clipboard.copyText(text)) feedback("无法写入系统剪贴板") },
            { text -> gate.commit { feedback(text) } }, textShare,
            { file, title, text, owned, retired -> nativeShare.shareMedia(file, title, text, owned, retired) },
            { name, mime -> selectDynamicSaveTarget(name, mime, actualWindow)?.path },
            nativeShare::probeMediaAvailable,
            { profileConfiguration.value.screenWidthDp }, { profileConfiguration.value.screenHeightDp })
        val consent = object : DesktopCrashConsentBindings {
            override val enhancedEnabled: Boolean get() = diagnostics?.enhancedEnabled?.value == true
            override suspend fun saveChoice(enabled: Boolean) {
                gate.assertOwned()
                val actual = diagnostics ?: throw IllegalStateException("本地诊断不可用，授权未更改")
                actual.setOriginalCrashConsent(enabled, gate::owns, gate::commit)
                currentCoroutineContext().ensureActive(); gate.assertOwned()
            }
        }
        return DesktopOriginalHomeOverlayBindings(share, consent, gate::owns)
    }
}

private suspend fun readShareBytes(factory: okhttp3.Call.Factory, url: String,
    gate: DesktopHomeRetainedGate): ByteArray = coroutineScope {
    gate.assertOwned(); currentCoroutineContext().ensureActive()
    val call = factory.newCall(Request.Builder().url(url).build())
    val caller = currentCoroutineContext()[Job]
    val watcher = launch(Dispatchers.Default) {
        try { while (gate.owns() && caller?.isActive == true) delay(40) }
        finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            call.execute().use { response ->
                require(response.isSuccessful) { "分享封面读取失败 (${response.code})" }
                response.body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
                    while (true) {
                        caller?.ensureActive(); gate.assertOwned()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size().toLong() + count <= 32L * 1024 * 1024) { "分享封面过大" }
                        output.write(buffer, 0, count)
                    }
                    caller?.ensureActive(); gate.assertOwned(); output.toByteArray()
                }
            }
        }
    } finally { watcher.cancel(); call.cancel() }
}
