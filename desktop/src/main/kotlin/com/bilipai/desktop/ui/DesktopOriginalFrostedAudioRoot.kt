package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.store.DesktopOriginalLinkedDockSettings
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.transition.NowPlayingBarHandoffState
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.audio.screen.*
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.navigation.submitDesktopOriginalBottomBarSearchKeyword
import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.DesktopOriginalFrostedSettings
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.combine
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** Actual Root navigation state, measured page and same channels; no invented defaults. */
internal data class DesktopOriginalFrostedNavigationState(
    val currentItem: BottomNavItem,
    val visibleItems: List<BottomNavItem>,
    val itemColorIndices: Map<String, Int>,
    val itemLabels: Map<String, String>,
    val dynamicUnreadCount: Int,
    val isFloating: Boolean,
    val isMainHost: Boolean,
    val isTopLevelDestination: Boolean,
    val transitionRunning: Boolean,
    val forceLowBlurBudget: Boolean,
    val collapseLinkedDock: Boolean,
    val motionTier: MotionTier,
    val indicatorPosition: (() -> Float)?,
    val pagerScrolling: () -> Boolean,
    val animateNowPlayingPresence: Boolean,
    val searchLaunchKey: Int,
)

internal class DesktopOriginalFrostedNavigationActions(
    val navigate: (BottomNavItem) -> Unit,
    val homeDoubleTap: () -> Unit,
    val dynamicDoubleTap: () -> Unit,
    val searchClick: () -> Unit,
    val openSearch: (String) -> Unit,
    val openNativeTarget: (BilibiliNavigationTarget) -> Unit,
    val searchTransitionFinished: (Int) -> Unit,
    val toggleSidebar: (() -> Unit)?,
    val historySearchChannel: SendChannel<String>?,
    val favoriteSearchChannel: SendChannel<String>?,
    val watchLaterSearchChannel: SendChannel<String>?,
)

/** Root's session-active means the listening bar remains available while paused.
 * It is the existing window's ephemeral visibility ownership, never ListenAudioState.active. */
internal data class DesktopOriginalNowPlayingVisibility(
    val sessionActive: Boolean,
    val onAudioModeScreen: Boolean,
    val inPipMode: Boolean,
    val barEnabled: Boolean,
    val inMiniMode: Boolean,
    val videoDetailDestination: Boolean,
    val landscape: Boolean,
    val playerDestination: Boolean,
)

/** Navigation stays in Root, with its actual source route and same return-morph ownership. */
internal class DesktopOriginalNowPlayingNavigation(
    val sourceRoute: String,
    val opensAudioMode: Boolean,
    val handoff: NowPlayingBarHandoffState,
    val driveBottomBarByProgress: Boolean,
    val openAudioMode: (PlaylistItem) -> Unit,
    val openVideo: (PlaylistItem, String) -> Unit,
    val dismissBar: () -> Unit,
)

/** One thin now-playing read/command view. The preserved Listen constructor
 * supports serial integration; the full Root supplies the real priority owner port. */
internal class DesktopOriginalAudioNowPlayingBinding(private val owner: DesktopOriginalNowPlayingOwnerPort,
    private val publishVisible: (Boolean) -> Unit) {
    constructor(listen: ListenAudioSession, repository: DesktopRepository, rootScope: CoroutineScope,
        ownerIsCurrent: () -> Boolean) : this(DesktopOriginalListenNowPlayingPort(listen,repository,rootScope,ownerIsCurrent),
            { visible -> if(rootScope.isActive && ownerIsCurrent()) com.android.purebilibili.feature.audio.player.AudioNowPlayingSession.publishBarOverlayVisible(visible) })
    @Composable fun observeSnapshot() = owner.observe()
    fun currentSnapshot() = owner.current()
    fun isOwned() = owner.current()?.let(owner::owns) == true
    fun ownsSnapshot(expected: DesktopOriginalNowPlayingSnapshot) = owner.owns(expected)
    fun ownsCurrent(item: PlaylistItem) = owner.current()?.let { it.item == item && owner.owns(it) } == true
    fun publishBarOverlayVisible(visible: Boolean) = publishVisible(visible)
    fun expand(expected: DesktopOriginalNowPlayingSnapshot, navigation: DesktopOriginalNowPlayingNavigation) {
        if (!owner.owns(expected)) return
        if (navigation.opensAudioMode) navigation.openAudioMode(expected.item)
        else navigation.openVideo(expected.item,navigation.sourceRoute)
    }
    fun playPause(expected: DesktopOriginalNowPlayingSnapshot, navigation: DesktopOriginalNowPlayingNavigation) {
        if (!owner.owns(expected)) return
        if (!owner.toggle(expected) && owner.owns(expected)) navigation.openAudioMode(expected.item)
    }
    fun next(expected: DesktopOriginalNowPlayingSnapshot) { if (owner.owns(expected)) owner.next(expected) }
    fun previous(expected: DesktopOriginalNowPlayingSnapshot) { if (owner.owns(expected)) owner.previous(expected) }
    fun dismiss(expected: DesktopOriginalNowPlayingSnapshot, navigation: DesktopOriginalNowPlayingNavigation) {
        if (owner.owns(expected) && owner.dismiss(expected)) navigation.dismissBar()
    }
}

private data class FrostedPreferenceState(val labels: Int, val listScoped: Boolean)

/** Full original Frosted navigation consumes full original audio slot and required actual Root ports.
 * Root mounts DesktopOriginalLinkedDockOwnerProvider on the real nested-scroll ancestor once. */
@Composable
internal fun DesktopOriginalFrostedAudioNavigation(
    preferenceContext: DesktopPluginContext,
    owner: DesktopOriginalLinkedDockOwner,
    navigation: DesktopOriginalFrostedNavigationState,
    actions: DesktopOriginalFrostedNavigationActions,
    audio: DesktopOriginalAudioNowPlayingBinding?,
    nowPlayingVisibility: DesktopOriginalNowPlayingVisibility,
    nowPlayingNavigation: DesktopOriginalNowPlayingNavigation,
    hazeState: HazeState?,
    backdrop: LayerBackdrop?,
    skinDecoration: BottomBarUiSkinDecoration?,
    modifier: Modifier,
) {
    if (!owner.isOwned()) return
    checkNotNull(LocalNavigationEventDispatcherOwner.current) { "Provide Root's sole navigation dispatcher" }
    val home by remember(preferenceContext) { DesktopOriginalFrostedSettings(preferenceContext).preferences }
        .collectAsState(initial = null)
    val extra by remember(preferenceContext) {
        combine(DesktopOriginalLinkedDockSettings.getBottomBarLabelMode(preferenceContext),
            DesktopOriginalLinkedDockSettings.getListScopedSearchEnabled(preferenceContext)) {
            labels, scoped -> FrostedPreferenceState(labels, scoped)
        }
    }.collectAsState(initial = null)
    val actualHome = home ?: return
    val actualExtra = extra ?: return
    val audioState = audio?.observeSnapshot()
    val item = audioState?.item
    val hasActiveAudioPlayback = audioState != null && audio?.ownsSnapshot(audioState) == true && audioState.active && nowPlayingVisibility.sessionActive && nowPlayingVisibility.barEnabled
    LaunchedEffect(hasActiveAudioPlayback, owner) {
        if (!owner.isOwned()) return@LaunchedEffect
        val reconciled = resolveLinkedDockPhaseOnAudioChange(owner.phase.value, hasActiveAudioPlayback)
        if (reconciled != owner.phase.value) owner.onPhaseChange(reconciled)
    }
    val showAudio = audio != null && audio.isOwned() && resolveAudioNowPlayingVisible(
        sessionActive = nowPlayingVisibility.sessionActive && audioState?.active == true,
        isOnAudioModeScreen = nowPlayingVisibility.onAudioModeScreen,
        isInPipMode = nowPlayingVisibility.inPipMode,
        hasCurrentItem = item != null,
        barEnabled = nowPlayingVisibility.barEnabled,
        isInMiniMode = nowPlayingVisibility.inMiniMode,
        isVideoDetailDestination = nowPlayingVisibility.videoDetailDestination,
        isLandscape = nowPlayingVisibility.landscape,
        isPlayerDestination = nowPlayingVisibility.playerDestination,
    )
    val tuning = LocalLiquidGlassRenderConfig.current.tuning
    val slot: LinkedDockNowPlayingSlot? = if (showAudio && item != null && audio != null && audioState != null) {
        { audioModifier, merge, iconOnly, surface, compactClick, stable ->
            if (audio.isOwned()) {
                AudioNowPlayingBar(
                    state = AudioNowPlayingBarState(item.bvid, item.title, item.owner, item.ownerFace, item.cover,
                        audioState.isPlaying, audioState.playbackSpeed),
                    sourceIsOwned = { owner.isOwned() && audio.ownsSnapshot(audioState) },
                    onExpand = { audio.expand(audioState, nowPlayingNavigation) },
                    onCompactClick = compactClick?.let { callback -> { if (owner.isOwned() && audio.ownsSnapshot(audioState)) callback() } },
                    isLayoutStable = stable && !nowPlayingNavigation.driveBottomBarByProgress,
                    onPlayPause = { audio.playPause(audioState, nowPlayingNavigation) },
                    onSkipNext = { audio.next(audioState) },
                    onSkipPrevious = { audio.previous(audioState) },
                    onDismiss = { audio.dismiss(audioState, nowPlayingNavigation) },
                    expandDestinationLabel = if (nowPlayingNavigation.opensAudioMode) "听视频" else "视频详情页",
                    sourceRoute = nowPlayingNavigation.sourceRoute,
                    handoff = nowPlayingNavigation.handoff,
                    glassEnabled = actualHome.androidNativeLiquidGlassEnabled,
                    blurEnabled = hazeState != null,
                    hazeState = hazeState,
                    miuixBackdrop = backdrop,
                    liquidGlassTuning = tuning,
                    liftAboveBottomBar = false,
                    consumeNavigationBarsPadding = false,
                    dockHosted = navigation.isFloating,
                    dockMergeProgress = merge,
                    iconOnlyProgress = iconOnly,
                    surfaceMergeProgress = surface,
                    modifier = audioModifier.excludeFromLiquidBackground(),
                )
            }
        }
    } else null
    FrostedBottomBar(
        currentItem = navigation.currentItem,
        onItemClick = { if (owner.isOwned()) actions.navigate(it) },
        modifier = modifier.excludeFromLiquidBackground(),
        nowPlayingContent = slot,
        hazeState = hazeState,
        isFloating = navigation.isFloating,
        labelMode = actualExtra.labels,
        homeSettings = actualHome,
        onHomeDoubleTap = { if (owner.isOwned()) actions.homeDoubleTap() },
        onDynamicDoubleTap = { if (owner.isOwned()) actions.dynamicDoubleTap() },
        onSearchClick = { if (owner.isOwned()) actions.searchClick() },
        onSearchKeywordSubmit = { text ->
            submitDesktopOriginalBottomBarSearchKeyword(text, actualHome.isBottomBarSearchEnabled,
                actualExtra.listScoped, navigation.isMainHost, navigation.currentItem,
                actions.historySearchChannel, actions.favoriteSearchChannel, actions.watchLaterSearchChannel,
                actions.openSearch, actions.openNativeTarget, owner.isOwned)
        },
        searchLaunchKey = navigation.searchLaunchKey,
        onSearchLaunchTransitionFinished = { if (owner.isOwned()) actions.searchTransitionFinished(it) },
        visibleItems = navigation.visibleItems,
        itemColorIndices = navigation.itemColorIndices,
        itemLabels = navigation.itemLabels,
        dynamicUnreadCount = navigation.dynamicUnreadCount,
        onToggleSidebar = actions.toggleSidebar?.let { callback -> { if (owner.isOwned()) callback() } },
        miuixBackdrop = backdrop,
        motionTier = navigation.motionTier,
        isTransitionRunning = navigation.transitionRunning,
        forceLowBlurBudget = navigation.forceLowBlurBudget,
        isFeedScrollInProgress = owner.feedScrollInProgress.value,
        collapseLinkedDock = navigation.collapseLinkedDock,
        indicatorPositionProvider = navigation.indicatorPosition,
        isPagerScrollInProgressProvider = navigation.pagerScrolling,
        uiSkinDecoration = skinDecoration,
        linkedDockPhase = owner.phase.value,
        onLinkedDockPhaseChange = { if (owner.isOwned()) owner.onPhaseChange(it) },
        isTopLevelDestination = navigation.isTopLevelDestination,
        animateNowPlayingPresence = navigation.animateNowPlayingPresence,
    )
}
