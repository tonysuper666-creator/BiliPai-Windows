package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.LifecycleOwner
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.feature.home.components.cards.WallpaperPalette
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.android.purebilibili.feature.plugin.SubscriptionFeedPlugin
import java.awt.Window

/** App/window effects remain outside account/route visibility. All are the actual Root actors.
 * nullable diagnostics/haze/channel represent supported unavailable capabilities, not fake data.
 * Settings was constructed using the original decoder/default observation before this factory.
 */
internal class DesktopHomeRootWindowBindings(
    val window: Window,
    val context: DesktopPluginContext,
    val settings: DesktopHomeSettingsPort,
    val lifecycle: LifecycleOwner,
    val resources: DesktopHomeActualWindowResources,
    val imageLocations: DesktopImageSaveLocations,
    val textShare: DesktopTextShareBindings,
    val diagnostics: DesktopDiagnostics?,
    val wallpaperPalette: StateFlow<WallpaperPalette?>,
    val loadWallpaperPalette: (DesktopHomeRetainedGate, String, CoroutineScope) -> Unit,
    val musicOverlayVisible: StateFlow<Boolean>,
    val sourceForMediaUrl: (String) -> PlaybackSource,
    val isVideoWallpaper: (String) -> Boolean,
    val imageWallpaper: @Composable (DesktopHomeMediaLifetime, String, Any, Boolean, Modifier) -> Unit,
    val clipboard: (String) -> Unit,
    val externalLink: (String) -> Unit,
    val feedback: (String) -> Unit,
    val onMatchClick: () -> Unit,
    val liveScrollToTop: Channel<Unit>?,
    val globalHaze: () -> HazeState?,
    val shareFiles: (DesktopHomeRetainedGate) -> DesktopVideoShareFiles,
    val mediaShare: DesktopImagePreviewMediaShare,
    val overlays: (DesktopHomeRetainedGate, DesktopHomeRootRequestBinding, DesktopVideoShareFiles) -> DesktopHomeOverlayPorts,
)

/** Real Root navigation/clock inputs. This is no parallel stack or CardPositionManager cache. */
internal class DesktopHomeRootReturnPorts(
    val admitNavigation: ((() -> Unit) -> Boolean),
    val hostOriginInRoot: () -> Offset,
    val monotonicMillis: () -> Long,
    val clock: VideoCardTransitionClock,
    val sharedCardTransitionEnabled: () -> Boolean,
    val relatedCardTransitionEnabled: () -> Boolean,
    val reduceMotion: () -> Boolean,
)

/** The single captured epoch's complete Home data/native/route assembly. The recommendation
 * bridge binds this owner, so its replacement closes all effects before creating the next VM.
 * Drawing a video/Favorite/Profile/audio destination must not close this object.
 */
internal class DesktopHomeRetainedRoot internal constructor(
    val entry: DesktopHomeRetainedEntry,
    val environment: DesktopHomeEnvironment,
    val media: DesktopHomeMediaPorts,
    val returns: DesktopHomeReturnNavigationOwner,
    val liveNavigation: DesktopLiveNavigationBinding,
    internal val mediaLifetime: DesktopHomeMediaLifetime,
    private val lottie: DesktopHomeLottieBinding,
) : DesktopOriginalTodayWatchOwner, AutoCloseable {
    override val capturedEpoch get() = entry.capturedEpoch
    override val todayWatchState get() = entry.todayWatchState
    override fun isCurrentOwner() = entry.isCurrentOwner()
    override suspend fun reloadTodayWatch(forceHistory: Boolean) = entry.reloadTodayWatch(forceHistory)
    override suspend fun consumeTodayWatchBvid(bvid: String) = entry.consumeTodayWatchBvid(bvid)
    @Composable fun ErrorAnimation(url: String, size: Dp, iterations: Int) =
        DesktopHomeActualErrorAnimation(lottie, url, size, iterations)

    override fun close() {
        // Admission retires first; no native worker or coroutine is joined under a Store monitor.
        entry.gate.close()
        try { liveNavigation.close() }
        finally { try { returns.close() } finally { try { lottie.close() }
            finally { try { mediaLifetime.close() } finally { entry.close() } } } }
    }
    override suspend fun closeAndJoin() {
        entry.gate.close() // Stop admission immediately, before any dispatcher/worker drain.
        withContext(NonCancellable + Dispatchers.IO) {
            try { close() } finally { entry.closeAndJoin() }
        }
    }
}

/** Concrete composition of the installed original VM/protocols/4 pages and existing platform
 * consumers. This constructs neither a client, credential store, planner, native main player
 * nor alternative list. No original route/effect has an optional empty fallback.
 */
internal class DesktopHomeRootFactory(
    private val repository: DesktopRepository,
    private val discoveryPreferences: DesktopDiscoveryPreferences,
    private val blocked: DesktopBlockedUpRepository,
    private val runtime: DesktopPluginRuntime,
    private val cardSession: DesktopDynamicCardSession,
    private val rootScope: CoroutineScope,
    private val window: DesktopHomeRootWindowBindings,
    private val incrementalRefresh: StateFlow<Boolean>,
    private val privacyModeEnabledSync: () -> Boolean,
    private val onAuthenticationInvalidated: (DesktopHomeAuthenticationInvalidation) -> Unit,
    private val navigation: DesktopHomeRootReturnPorts,
) {
    init {
        require(runtime.context === window.context)
        require(blocked.store.context.store === runtime.store)
        require(window.imageLocations.isActive())
    }
    suspend fun create(epoch: Long, mid: Long?, stillOwned: () -> Boolean): DesktopHomeRetainedRoot {
        var sharedFiles: DesktopVideoShareFiles? = null
        var original: DesktopHomeRetainedEntry? = null
        var attemptedGate: DesktopHomeRetainedGate? = null
        var identityAnalytics: DesktopHomeRootAnalytics? = null
        var media: DesktopHomeMediaLifetime? = null
        var animation: DesktopHomeLottieBinding? = null
        var returns: DesktopHomeReturnNavigationOwner? = null
        var routes: DesktopLiveNavigationBinding? = null
        try {
            val entry = DesktopHomeRetainedEntry.create(repository, discoveryPreferences,
                window.context, rootScope, epoch, mid, stillOwned, window.settings,
                incrementalRefresh, privacyModeEnabledSync,
                { gate -> attemptedGate = gate; DesktopHomeRootAnalytics(runtime.store, window.diagnostics, gate::owns)
                    .also { identityAnalytics = it } },
                blocked, onAuthenticationInvalidated, window.feedback,
                { gate, requests ->
                    // Pure path/lease construction. Publication admission denies I/O until Retainer installs this gate.
                    val files = window.shareFiles(gate).also { sharedFiles = it }
                    DesktopOriginalHomeEmbeddedAggregate.create(repository, gate,
                        requests, runtime, window.settings, cardSession, window.imageLocations, window.window,
                        files, window.mediaShare, window.textShare, window.clipboard, window.externalLink, window.feedback,
                        DesktopHomeEmbeddedRootUi(window.onMatchClick, window.liveScrollToTop, window.globalHaze)) })
            original = entry
            val gate = entry.gate
            gate.assertOwned()
            val aggregate = entry.embeddedPages as DesktopOriginalHomeEmbeddedAggregate
            val analytics = requireNotNull(identityAnalytics)
            val mediaOwner = DesktopHomeMediaLifetime(gate.scope, gate::owns, gate::commit, window.sourceForMediaUrl)
            media = mediaOwner
            val mediaPorts = DesktopHomeActualMediaPorts(mediaOwner, window.isVideoWallpaper,
                { uri, model, playing, modifier -> window.imageWallpaper(mediaOwner, uri, model, playing, modifier) }, window.musicOverlayVisible,
                { message -> gate.commit { window.feedback(message) } }).ports
            val lottie = DesktopHomeLottieBinding(repository.httpClient, gate::owns, gate::commit,
                { message -> gate.commit { window.feedback(message) } })
            animation = lottie
            val returnOwner = DesktopHomeReturnNavigationOwner(gate::owns, gate::commit,
                navigation.admitNavigation, navigation.hostOriginInRoot, navigation.monotonicMillis,
                navigation.clock, navigation.sharedCardTransitionEnabled,
                navigation.relatedCardTransitionEnabled, navigation.reduceMotion)
            returns = returnOwner
            val search = repository.ownedHomeService(SearchApi::class.java,
                "https://api.bilibili.com/", epoch, entry.requests::isMountedSourceCurrent)
            val liveRoutes = DesktopLiveNavigationBinding(entry.requests.environment, search, runtime.store)
            routes = liveRoutes
            val subscriptionEnabled = runtime.plugins.map { plugins ->
                plugins.any { it.plugin.id == SubscriptionFeedPlugin.PLUGIN_ID && it.enabled }
            }.stateIn(gate.scope, SharingStarted.Eagerly, runtime.plugins.value.any {
                it.plugin.id == SubscriptionFeedPlugin.PLUGIN_ID && it.enabled })
            val environment = DesktopHomeEnvironment(window.settings, window.context, window.lifecycle,
                subscriptionEnabled, analytics,
                { name -> DesktopOriginalHomeGlobalNamespace(runtime.store, name) },
                window.wallpaperPalette, { uri, pageScope -> window.loadWallpaperPalette(gate, uri, pageScope) },
                window.resources.clientPolicy::ensureEdgeToEdge,
                window.resources.clientPolicy::applyHomeSystemBars, aggregate,
                { url, _ -> aggregate.gallery.saveImage(url) },
                { message -> gate.commit { window.feedback(message) } }, window.overlays(gate, entry.requests, requireNotNull(sharedFiles)))
            gate.assertOwned()
            entry.requests.assertMountedSourceCurrent()
            return DesktopHomeRetainedRoot(entry, environment, mediaPorts, returnOwner, liveRoutes, mediaOwner, lottie)
        } catch (failure: Throwable) {
            original?.gate?.close()
            for (cleanup in listOf<() -> Unit>({ routes?.close() }, { returns?.close() },
                { animation?.close() }, { media?.close() }, { original?.close() })) {
                try { cleanup() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            }
            withContext(NonCancellable) {
                try { if (original != null) original.closeAndJoin() else attemptedGate?.closeAndJoin() }
                catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            }
            throw failure
        }
    }
}
