@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.audio.player.MusicPlayerUiState
import com.android.purebilibili.feature.audio.screen.MusicPlayerContent
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.model.response.CreatorCardStats
import com.android.purebilibili.data.model.response.UgcSeason
import com.android.purebilibili.data.model.response.UgcSection
import com.android.purebilibili.data.model.response.UgcEpisode
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.MpvPlayer
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import com.android.purebilibili.feature.video.danmaku.DanmakuCloudSyncUiState
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.ui.components.VideoViewportLayout
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.DesktopDynamicCardOperations

/** Every unlisted effect fails. These ports contain no pager or tab-selection state. */
private inline fun <reified T> tabletPort(crossinline call: (String, Array<out Any?>?) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "TabletLayoutFixture<${T::class.java.simpleName}>"
            else -> call(method.name, args)
        }
    } as T

/**
 * ImageComposeScene has no native window or accepted media source. Keep the original Section
 * foreground and lifecycle, replacing only the OS/media leaves. Preferences use the same real
 * fixture Store; no native playback, network, cloud changes or screenshot actions are allowed.
 */
private class TabletLayoutSection(
    override val settingsContext: DesktopOriginalPlayerSettingsContext,
    override val danmakuPreferences: DesktopOriginalDanmakuPreferences,
) : DesktopOriginalVideoSectionPlatform by tabletPort({ name, _ -> error("unexpected Section port $name") }) {
    private var danmakuEnabled = false
    private val emptyDanmaku = MutableStateFlow(emptyList<Nothing>())
    override val danmaku = tabletPort<DesktopOriginalSectionDanmakuPort> { name, args ->
        when (name) {
            "isEnabled" -> danmakuEnabled
            "setEnabled" -> { danmakuEnabled = args!![0] as Boolean; Unit }
            "updateSettings" -> { danmakuEnabled = (args!![0] as com.android.purebilibili.core.store.DanmakuSettings).enabled; Unit }
            "getAdvancedDanmakuFlow", "getBasDanmakuFlow", "getCommandDanmakuFlow" -> emptyDanmaku
            "getLoadedDanmakuList", "getHotDanmakuList" -> emptyList<Nothing>()
            "getUpOwnerUserHash" -> null
            "clear" -> Unit // Empty renderer, not a repository/session authority.
            "updateHotDanmakuPresentation" -> {
                check((args!![0] as List<*>).isEmpty()); Unit
            }
            else -> error("unexpected danmaku port $name")
        }
    }
    override val enhancementConfig = MutableStateFlow(Anime4KConfig())
    override val enhancementState = MutableStateFlow(DesktopVideoEnhancementState())
    override val cloudSync = DesktopDanmakuCloudSyncBinding(DanmakuCloudSyncUiState(),
        { error("unexpected cloud write") }, { error("unexpected cloud sync") }, { error("unexpected cloud toggle") })
    override val nextWatchHint = MutableStateFlow<DesktopOriginalVideoNextWatchHint?>(null)
    override val volume = tabletPort<DesktopOriginalSectionVolumePort> { name, _ ->
        when (name) {
            "currentStep" -> 0
            "maximumStep" -> 100
            else -> error("unexpected volume port $name")
        }
    }
    override val viewport = object : DesktopOriginalPlayerViewportPort by tabletPort({ name, _ ->
        error("unexpected viewport port $name")
    }) {
        override var resizeMode = 0
        override val width = 0
        override val height = 0
        override val isAttached = false
        override val playbackIdentity: Any? = null
        override var refreshController: DesktopOriginalViewportRefreshCancellation? = null
        override fun addAttachmentObserver(observer: DesktopOriginalViewportAttachmentObserver) {
            observer.onViewportDetached(this)
        }
        // Match the production no-accepted-source path: no native frame callbacks are scheduled.
        override fun requestLayout() = Unit
        override fun requestContentLayout() = Unit
        override fun invalidate() = Unit
        override fun post(block: () -> Unit) = Unit
        override fun postOnFrame(block: () -> Unit) = Unit
        override fun postOnFrame(callback: Runnable) = Unit
        override fun cancelFrameCallback(callback: Runnable) = Unit
    }
    override val viewportAttached = false
    override val viewportHeightPixels = 0
    override val statusBarInsetPixels = 0
    override val danmakuReferencePixels = 800f
    override val videoBackdropReady = false
    private var viewportLeaseCount = 0
    @Composable override fun RenderPlayerForeground(content: @Composable () -> Unit) = content()
    @Composable override fun ObserveVideoBackdrop(enabled: Boolean) = Unit
    override fun DrawScope.drawVideoBackdrop() = error("unexpected backdrop drawing")
    override fun hasPlaylistNext() = false
    override fun captureNextWatchHint(): DesktopOriginalVideoNextWatchHint? = null
    override fun commandVotePlatform(): DesktopWindowsCommandVotePlatform? = null
    override fun captureAmbientSourceLease(): DesktopOriginalAmbientSourceLease? = null
    override fun ambientViewportBoundsInWindow(): androidx.compose.ui.geometry.Rect? = null
    override fun setScreenshotAndOrientationLock(locked: Boolean): AutoCloseable {
        check(!locked)
        return AutoCloseable { }
    }
    override fun setViewportActive(active: Boolean) = Unit
    override fun acquireViewportLease(): AutoCloseable {
        viewportLeaseCount++
        return AutoCloseable { viewportLeaseCount-- }
    }
    override fun releaseViewportForThisEntry() { viewport.refreshController?.cancel() }
    override fun recoverViewport(identity: String, fullscreen: Boolean, pip: Boolean, predictiveBackGeneration: Int) {
        check(!fullscreen && !pip && predictiveBackGeneration == 0)
    }
    fun assertReleased() = assertEquals(0, viewportLeaseCount, "native viewport leases leaked")
    @Composable override fun NativeViewport(
        modifier: Modifier, layout: VideoViewportLayout, resizeMode: Int,
        revealAlpha: Float, revealScale: Float, freeScale: Float, panX: Float, panY: Float,
        flipHorizontal: Boolean, flipVertical: Boolean, visible: Boolean, keepAwake: Boolean,
    ) { Box(modifier) }
    @Composable override fun NativeDanmakuSurface(viewport: DanmakuViewport, renderingPaused: Boolean, modifier: Modifier) {
        Box(modifier)
    }
    private val hotPlayer = object : DesktopHotDanmakuPlayer {
        override val currentPosition = 0L
        private val listeners = mutableSetOf<DesktopHotDanmakuPlayer.Listener>()
        override fun addListener(listener: DesktopHotDanmakuPlayer.Listener) { listeners.add(listener) }
        override fun removeListener(listener: DesktopHotDanmakuPlayer.Listener) { check(listeners.remove(listener)) }
    }
    @Composable override fun HotDanmakuScope(content: @Composable (DesktopHotDanmakuPlayer) -> Unit) {
        content(hotPlayer)
    }
}

internal fun runTabletLayoutMotion(cinema: Boolean): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            // Diagnostics may read environment metadata without admitting native services.
            val overlay = tabletPort<DesktopOriginalVideoOverlayPlatform> { name, _ ->
                when (name) {
                    "networkTypeLabel" -> "unavailable"
                    "readPanelRefreshRate" -> null
                    else -> error("unexpected overlay action $name")
                }
            }
            val noDanmakuWrites: (() -> Unit) -> Boolean = { error("unexpected danmaku preference write") }
            val danmakuPreferences = DesktopOriginalDanmakuPreferences(fixture.pluginContext.store,
                DesktopDanmakuBlockPreferences(fixture.pluginContext.store, noDanmakuWrites), noDanmakuWrites)
            val context = DesktopOriginalPlayerSettingsContext(fixture.pluginContext, { true }, { it(); true },
                videoOverlayPlatform = overlay, largeScreenOrFoldableConfiguration = { true },
                isDebugBuild = { false }, danmakuPreferences = danmakuPreferences)
            val rootSettings = DesktopOriginalHomeSettingsRootServices(fixture.home.settings,
                DesktopFavoritePreferences(fixture.pluginContext.store), { error("unexpected notice") },
                { _, _, _, _ -> error("unexpected profile") })
            val section = TabletLayoutSection(context, danmakuPreferences)
            val blocked = tabletPort<DesktopHomeBlockedRequests> { name, _ ->
                when (name) {
                    "getAllBlockedUps" -> flowOf(emptyList<Any>())
                    else -> error("unexpected blocked creator action $name")
                }
            }
            val content = DesktopOriginalVideoContentBindings(context, fixture.home.settings, fixture.pluginContext,
                blocked, { true }, { it(); true }, { _, _ -> error("unexpected watch later") },
                { error("unexpected feedback") }, { _, _ -> error("unexpected share") },
                DesktopTodayWatchFeedbackWriteBinding(fixture.pluginContext, { true }, { it(); true }),
                { _, _, _, _ -> error("unexpected source watch later") },
                { _, _ -> error("unexpected source block") })
            // Keep the original collection owner and operations. This guest fixture has no
            // valid collection ID; any accidental HTTP request or action must fail.
            val repository = DesktopRepository(DesktopSessionStore(fixture.root.resolve("session.json"), persistent = false))
            val httpClient = repository.httpClient.newBuilder().addInterceptor { chain ->
                error("unexpected tablet collection HTTP ${chain.request().url}")
            }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
                .set(repository, httpClient)
            val collection = DesktopCollectionBindings(fixture.pluginContext, DesktopDynamicCardOperations(repository),
                { true }, { error("unexpected collection feedback") }, { _, _, _ -> error("unexpected collection share") },
                { error("unexpected collection preference write") })
            val videoInfo = object : DesktopOriginalVideoInfoBindings {
                override val context = fixture.pluginContext
                override suspend fun getCreatorCardStats(mid: Long): Result<CreatorCardStats> =
                    error("unexpected creator request")
                override fun copyText(text: String, label: String) = error("unexpected copy")
                override fun showFeedback(message: String) = error("unexpected creator feedback")
            }
            val preferences = DesktopFavoritePreferences(fixture.pluginContext.store)
            val backToTop = DesktopBackToTopBindings(
                preferences.backToTopEnabled, preferences.initialBackToTopEnabled(),
                preferences.backToTopOffset, preferences.initialBackToTopOffset(),
                { _, _ -> error("unexpected control write") }, { _, _ -> error("unexpected control write") },
                DesktopFavoriteViewport(1400, 800),
            )
            val comments = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
                arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "InertTabletCommentPlatform"
                    "getContext" -> fixture.pluginContext
                    "isOwned" -> true
                    else -> error("unexpected comment port ${method.name}")
                }
            } as DesktopCommentPlatform
            val native = MpvPlayer(useNullAudioOutput = true)
            val version = MutableStateFlow<Long?>(null)
            val control = DesktopOriginalMpvSectionControl(native, { null }, { true }, { it(); true }, { false },
                { error("unexpected prepare") }, { error("unexpected resume") },
                { _, _, _, _ -> error("unexpected seek") }, fixture.scope, version, { it(); true })
            val player = DesktopOriginalMpvVideoPlayerState(control, fixture.scope, version, { true },
                { it(); true }, { _, _, _ -> error("unexpected metadata") })
            val playback = VideoDetailPlaybackActions(
                changeQuality = { error("unexpected changeQuality") },
                reloadVideo = { error("unexpected reloadVideo") },
                switchCdn = { error("unexpected switchCdn") },
                switchCdnTo = { error("unexpected switchCdnTo") },
                probeCdnCandidates = { error("unexpected probeCdnCandidates") },
                setAudioMode = { error("unexpected setAudioMode") },
                setSleepTimer = { error("unexpected setSleepTimer") },
                switchPage = { error("unexpected switchPage") },
                openDownloadDialog = { error("unexpected openDownloadDialog") },
                showDanmakuSendDialog = { error("unexpected showDanmakuSendDialog") },
                skipSponsorSegment = { error("unexpected skipSponsorSegment") },
                dismissSponsorSkipButton = { error("unexpected dismissSponsorSkipButton") },
                voteSponsorSegment = { error("unexpected voteSponsorSegment") },
                markSponsorContributionBoundary = { error("unexpected markSponsorContributionBoundary") },
                markWholeVideoAsSponsor = { error("unexpected markWholeVideoAsSponsor") },
                setSponsorContributionCategory = { error("unexpected setSponsorContributionCategory") },
                setSponsorContributionActionType = { error("unexpected setSponsorContributionActionType") },
                submitSponsorContribution = { error("unexpected submitSponsorContribution") },
                cancelSponsorContribution = { error("unexpected cancelSponsorContribution") },
                notifyExplicitSeek = { error("unexpected notifyExplicitSeek") },
                setVideoCodec = { error("unexpected setVideoCodec") },
                setVideoSecondCodec = { error("unexpected setVideoSecondCodec") },
                setAudioQuality = { error("unexpected setAudioQuality") },
                applyPlaybackSpeed = { error("unexpected applyPlaybackSpeed") },
                changeAudioLanguage = { error("unexpected changeAudioLanguage") },
                saveCover = { error("unexpected saveCover") },
                downloadAudio = { error("unexpected downloadAudio") },
                selectSubtitleTrack = { error("unexpected selectSubtitleTrack") },
                showFavoriteFolderDialog = { error("unexpected showFavoriteFolderDialog") },
                toggleFavoriteFolderSelection = { error("unexpected toggleFavoriteFolderSelection") },
                saveFavoriteFolderSelection = { error("unexpected saveFavoriteFolderSelection") },
                dismissFavoriteFolderDialog = { error("unexpected dismissFavoriteFolderDialog") },
                createFavoriteFolder = { _, _, _ -> error("unexpected createFavoriteFolder") },
                retryAiSummary = { error("unexpected retryAiSummary") },
                createVideoNoteDraftFromAiSummary = { error("unexpected createVideoNoteDraftFromAiSummary") },
                openVideoNoteEditor = { error("unexpected openVideoNoteEditor") },
                closeVideoNoteEditor = { error("unexpected closeVideoNoteEditor") },
                updateVideoNoteEditorDocument = { error("unexpected updateVideoNoteEditorDocument") },
                currentVideoNoteTimestamp = { error("unexpected currentVideoNoteTimestamp") },
                seekTo = { error("unexpected seekTo") },
                saveVideoNote = { error("unexpected saveVideoNote") },
                deleteVideoNote = { error("unexpected deleteVideoNote") },
                retryVideoNote = { error("unexpected retryVideoNote") },
                loadMorePublicVideoNotes = { error("unexpected loadMorePublicVideoNotes") },
                openRootCommentComposer = { error("unexpected openRootCommentComposer") },
                replyTo = { error("unexpected replyTo") },
                markVideoNotInterested = { error("unexpected markVideoNotInterested") },
            )
            val engagement = VideoDetailEngagementActions(
                toggleFollow = { error("unexpected toggleFollow") },
                toggleFavorite = { error("unexpected toggleFavorite") },
                onFavoriteAction = { error("unexpected onFavoriteAction") },
                toggleLike = { error("unexpected toggleLike") },
                toggleDislike = { error("unexpected toggleDislike") },
                openCoinDialog = { error("unexpected openCoinDialog") },
                doTripleAction = { error("unexpected doTripleAction") },
                toggleWatchLater = { error("unexpected toggleWatchLater") },
            )
            val commentActions = VideoDetailCommentActions(
                loadComments = { error("unexpected loadComments") },
                refreshComments = { error("unexpected refreshComments") },
                setSortMode = { error("unexpected setSortMode") },
                deleteComment = { error("unexpected deleteComment") },
                startDissolve = { error("unexpected startDissolve") },
                loadMoreSubReplies = { error("unexpected loadMoreSubReplies") },
                refreshSubReplies = { error("unexpected refreshSubReplies") },
                setSubReplySortMode = { error("unexpected setSubReplySortMode") },
                openSubReply = { _, _ -> error("unexpected openSubReply") },
                openSubReplyConversation = { error("unexpected openSubReplyConversation") },
                closeSubReplyConversation = { error("unexpected closeSubReplyConversation") },
                closeSubReply = { error("unexpected closeSubReply") },
                startSubDissolve = { error("unexpected startSubDissolve") },
                deleteSubComment = { error("unexpected deleteSubComment") },
                likeComment = { error("unexpected likeComment") },
                hateComment = { error("unexpected hateComment") },
                reportComment = { _, _ -> error("unexpected reportComment") },
                toggleTopComment = { error("unexpected toggleTopComment") },
                checkCommentFraud = { error("unexpected checkCommentFraud") },
            )
            val success = VideoPlaybackUiState.Success(
                info = ViewInfo(bvid = "BVmotionCurrent", cid = 1,
                    ugc_season = UgcSeason(title = "Motion collection", sections = listOf(UgcSection(
                        episodes = listOf(UgcEpisode(bvid = "BVmotionNext", cid = 77, title = "Motion episode")))))),
                playUrl = "",
            )
            com.android.purebilibili.core.store.DesktopOriginalTabletAudioSettings.setTabletSecondaryDefaultTab(
                context, com.android.purebilibili.core.store.TabletSecondaryDefaultTab.COMMENTS)
            var nanos = 0L
            val liquidTabSettings = DesktopLiquidTabSettings(fixture.pluginContext.store)
            // The Section tracks this scene's states even without a native window.
            val metricStates = mutableMapOf<String, String>()
            val metrics = DesktopHomeMetricHolder(object : DesktopHomeMetricSink {
                override fun putState(name: String, value: String) { metricStates[name] = value }
                override fun removeState(name: String) { metricStates.remove(name) }
            })
            // ImageComposeScene has no native window or RenderEffect-backed haze.
            val background = object : DesktopHomeWindowBackgroundPort {
                override val isInBackground = false
                override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
                override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            }
            val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
            // Full-width tablet fixture: avoid conflating the tab strip's own auto-scroll
            // with the pager indicator's motion in screen coordinates. Narrow rails are separate coverage.
            val windowWidth = 1400
            val playerControlsPlatform = object : DesktopOriginalPlayerControlsPlatform {
                override fun readBatteryPercent(): Int? = null // No battery in this headless scene.
                override fun readAmbientEnvironment() =
                    com.android.purebilibili.feature.video.ambient.AmbientEnvironment()
            }
            // The mounted entry is settled; navigation gestures/transitions are outside this test.
            val navigation = object : top.yukonga.miuix.kmp.nav.transition.NavTransitionScope {
                override val relativeDepth = 0f
                override val role = top.yukonga.miuix.kmp.nav.transition.NavRole.Top
                override val change = top.yukonga.miuix.kmp.nav.runtime.NavChange.None
                override val gesture: top.yukonga.miuix.kmp.nav.transition.NavGesture? = null
                override val layoutSize = androidx.compose.ui.unit.IntSize(windowWidth, 800)
                override val layoutDirection = androidx.compose.ui.unit.LayoutDirection.Ltr
                override val density = Density(1f)
            }
            val backgroundFailures = mutableListOf<Throwable>()
            val sceneContext = coroutineContext + kotlinx.coroutines.CoroutineExceptionHandler { _, failure ->
                backgroundFailures += failure
            }
            val scene = ImageComposeScene(windowWidth, 800, Density(1f), coroutineContext = sceneContext) {
                CompositionLocalProvider(
                    top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope provides navigation,
                    LocalDesktopOriginalPlayerControlsPlatform provides playerControlsPlatform,
                    LocalDesktopHomePlatform provides platform,
                    LocalDesktopHomeMetricHolder provides metrics,
                    LocalDesktopOriginalVideoSectionPlatform provides section,
                    LocalDesktopOriginalVideoContentBindings provides content,
                    LocalDesktopOriginalVideoInfoBindings provides videoInfo,
                    LocalDesktopCollectionBindings provides collection,
                    LocalDesktopBackToTopBindings provides backToTop,
                    LocalDesktopCommentBindings provides comments,
                    LocalDesktopOriginalHomeSettingsRootServices provides rootSettings,
                    LocalDesktopOriginalPlayerSettingsContext provides context,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(context),
                    LocalDesktopLiquidTabSettings provides liquidTabSettings,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Magenta, secondaryContainer = Color.Magenta)) {
                        DesktopDetailWindow {
                            if (cinema) {
                                TabletCinemaLayout(
                                    playerState = player, uiState = success, commentState = CommentUiState(isRepliesEnd = true),
                                    engagementState = VideoEngagementUiState(), subReplyState = SubReplyUiState(),
                                    downloadProgress = 0f,
                                    tabletCommentPanelWidthPreset = com.android.purebilibili.core.store.TabletCommentPanelWidthPreset.STANDARD,
                                    commentMemberDecorationsEnabled = false, videoAiSummaryEntryEnabled = false,
                                    videoNoteEnabled = false, videoNoteDefaultCollapsed = true,
                                    playbackActions = playback, engagementActions = engagement, commentActions = commentActions,
                                    configuration = DesktopHomeCardWindowBounds(windowWidth, 800), isVerticalVideo = false,
                                    sleepTimerMinutes = null, viewPoints = emptyList(), pbpProgressData = null,
                                    bvid = "BVmotionCurrent", onBack = {}, onUpClick = {}, onNavigateToAudioMode = {},
                                    onToggleFullscreen = {}, onPortraitFullscreen = {}, isInPipMode = false,
                                    onPipClick = {}, onHomeClick = {}, onRelatedVideoClick = { _, _, _ -> },
                                    showUpBadge = false, danmakuHostActive = false,
                                )
                            } else {
                                TabletVideoLayout(
                                    playerState = player, uiState = success, commentState = CommentUiState(isRepliesEnd = true),
                                    engagementState = VideoEngagementUiState(), subReplyState = SubReplyUiState(),
                                    downloadProgress = 0f, commentMemberDecorationsEnabled = false,
                                    playbackActions = playback, engagementActions = engagement, commentActions = commentActions,
                                    configuration = DesktopHomeCardWindowBounds(windowWidth, 800), isVerticalVideo = false,
                                    sleepTimerMinutes = null, viewPoints = emptyList(), bvid = "BVmotionCurrent",
                                    onBack = {}, onUpClick = {}, onNavigateToAudioMode = {}, onToggleFullscreen = {},
                                    onPortraitFullscreen = {}, isInPipMode = false, onPipClick = {}, onHomeClick = {},
                                    onRelatedVideoClick = { _, _, _ -> }, showUpBadge = false,
                                    videoAiSummaryEntryEnabled = false, videoNoteEnabled = false,
                                    playerContent = { Text("player fixture") },
                                )
                            }
                        }
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun label(node: SemanticsNode, text: String) =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            fun tab(text: String) = allNodes().single {
                it.config.getOrNull(SemanticsProperties.Selected) != null && nodes(it).any { child -> label(child, text) }
            }
            fun selected(text: String) = tab(text).config[SemanticsProperties.Selected]
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            val rail = mutableListOf<Float>()
            suspend fun frames(count: Int) {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> scanY?.let { y ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val xs = (0 until windowWidth).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "tablet indicator disappeared at $nanos")
                            rail += xs.average().toFloat()
                            // Diagnostics preserve the original scanner and every assertion below.
                            val destination = if (cinema) "相关推荐" else "合集"
                            println("tablet-rail cinema=$cinema sample=${rail.size} y=$y " +
                                "pixels=${xs.first()}..${xs.last()} count=${xs.size} centroid=${rail.last()} " +
                                "comments=${tab("评论").boundsInRoot} destination=${tab(destination).boundsInRoot}")
                            if (rail.size in setOf(2, 8, 15, 28, 97)) {
                                val output = java.io.File("build/reports/tablet-layout-motion").apply { mkdirs() }
                                image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data ->
                                    java.io.File(output, "${if (cinema) "cinema" else "video"}-${rail.size}.png")
                                        .writeBytes(data.bytes)
                                }
                            }
                        }
                    } }
                    yield()
                    // Composition effects must not silently die and leave stale tab semantics.
                    backgroundFailures.firstOrNull()?.let { throw AssertionError("fixture composition effect failed", it) }
                }
            }
            fun pointer(event: PointerEventType, point: Offset, pressed: Boolean) {
                scene.sendPointerEvent(event, point, timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = pressed), type = PointerType.Mouse)
            }
            suspend fun click(name: String) {
                val bounds = tab(name).boundsInRoot
                assertTrue(bounds.width > 10f && bounds.left >= 0f && bounds.right <= windowWidth.toFloat())
                pointer(PointerEventType.Move, bounds.center, false)
                pointer(PointerEventType.Press, bounds.center, true); frames(7)
                pointer(PointerEventType.Release, bounds.center, false)
            }
            try {
                val destination = if (cinema) "相关推荐" else "合集"
                withTimeout(6_000) {
                    while (allNodes().none { label(it, destination) }) { frames(2); delay(5) }
                }
                frames(90)
                assertTrue(selected("评论"), "comments should initially be selected")
                val first = tab("评论").boundsInRoot
                val last = tab(destination).boundsInRoot
                val textBottom = nodes(tab("评论")).single { label(it, "评论") }.boundsInRoot.bottom.toInt()
                nanos += 1_000_000_000L / 165
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (textBottom until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until last.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no visible layout indicator: $rows")
                    scanY = best.first
                } }
                frames(2)
                assertTrue(abs(rail.last() - first.center.x) < 4f)
                val start = rail.size
                click(destination); frames(2)
                assertTrue(selected(destination), "clicked tab must be selected during animation")
                frames(90)
                val motion = rail.drop(start)
                assertTrue(motion.any { it > first.center.x + 5f && it < last.center.x - 5f }, "no intermediate motion: $motion")
                assertTrue(motion.zipWithNext().all { (a, b) -> b >= a - 1f }, "indicator reversed: $motion")
                assertTrue(abs(motion.last() - last.center.x) < 4f)
                click("评论"); frames(90)
                assertTrue(selected("评论"))
                assertTrue(abs(rail.last() - first.center.x) < 4f)
            } finally { scene.close(); native.close(); section.assertReleased() }
        }
}

/** Directly mounts the generated desktop TabletVideoLayout and tests its rendered tab rail. */
class DesktopTabletVideoPagerMotionTest {
    @Test fun fullLayoutSelectionAndIndicator() = runTabletLayoutMotion(cinema = false)
}
