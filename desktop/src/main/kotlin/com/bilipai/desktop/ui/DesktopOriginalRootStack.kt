package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.feature.home.HomeScrollRequest
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.feature.live.*
import com.android.purebilibili.feature.partition.PartitionScreen
import com.android.purebilibili.feature.profile.shouldShowProfileHistoryService
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.*
import dev.chrisbanes.haze.HazeState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.channels.Channel

/** Required actual Root globals and Profile consumer. No page/epoch-local settings, skin,
 * scroll registry, captured image locations, clock, lifecycle or native player is created here. */
internal class DesktopOriginalRootPageBindings(
    val window: DesktopHomeRootWindowBindings,
    val scrollOffset: MutableFloatState,
    val feedScrollInProgress: MutableState<Boolean>,
    val homeScroll: Channel<HomeScrollRequest>,
    val bottomBarVisible: () -> Boolean,
    val bottomBarContentPadding: () -> Dp,
    val setBottomBarVisible: (Boolean) -> Unit,
    val transitionClock: VideoCardTransitionClock,
    val inPictureInPicture: () -> Boolean,
    val homeGraphicsLayerCaptureReady: () -> Boolean,
    val liveScrollRequestId: () -> Int,
    val liveTopPadding: () -> Dp,
    val profile: DesktopOriginalProfileBinding,
    val accountSessionRefreshGeneration: () -> Int,
    val profileScrollToTop: Channel<Unit>?,
    val logout: (() -> Unit)?,
    val accountSwitcher: (() -> Unit)?,
    val logoutSucceeded: () -> Unit,
    val accountSwitchSucceeded: () -> Unit,
)

/** Actual original physical NavDisplay + MainHost pager + complete Home/Profile/Category/Live
 * renderers. leafContent is Root's existing typed destination renderer (not a flat section switch
 * or default Home); it is required for each remaining original key, including VideoDetail.
 * The SAME real pager selection/scroll drives the already installed full Dock/SideBar. */
@Composable internal fun DesktopOriginalRootStack(
    routes: DesktopOriginalRootRouteAssembly,
    environment: DesktopNavigationHostEnvironment,
    pages: DesktopOriginalRootPageBindings,
    isLightBackground: Boolean,
    reduceMotion: Boolean,
    videoSharedTransitionDurationMillis: Int,
    programmaticBackDispatcher: BiliPaiProgrammaticBackDispatcher,
    preferWholeCardReturn: Boolean,
    onPrepareVideoCardSharedReturn: () -> Boolean,
    onRelatedVideoDetailReturned: () -> Unit,
    modifier: Modifier,
    chrome: DesktopOriginalRootChromeBindings,
    onActiveDestination: (BiliPaiNavKey) -> Unit,
    saveableState: SaveableStateHolder,
    onRootContentFrame: () -> Unit = {},
    leafContent: @Composable (BiliPaiNavKey, DesktopOriginalRootRouteCommands, Boolean, Boolean) -> Unit,
) {
    if (!routes.owns() || !environment.isCurrent()) return
    val root = routes.root
    val storyFeeds = LocalDesktopOriginalVideoRootPlatforms.current?.storyFeeds
    val retainedStoryKeys = routes.stack.filterIsInstance<BiliPaiNavKey.Story>().toSet()
    SideEffect { storyFeeds?.reconcile(retainedStoryKeys) }
    check(pages.window.context === root.environment.pluginContext)
    if (!pages.profile.environment.owns()) return
    val homeSettings by root.environment.settings.homeSettings.collectAsState()
    val navigationSettings by root.environment.settings.navigation.collectAsState()
    val visibleItems = remember(navigationSettings.orderedVisibleTabIds) {
        resolveVisibleBottomBarItems(navigationSettings.orderedVisibleTabIds)
    }
    val pagerState = rememberPagerState(pageCount = { visibleItems.size.coerceAtLeast(1) })
    val mainPager = rememberMainBottomPagerState(pagerState)
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; contentReady = true }
    LaunchedEffect(pagerState.currentPage, mainPager) { mainPager.syncPage() }
    LaunchedEffect(visibleItems, mainPager.selectedPage) {
        val last = visibleItems.lastIndex
        if (last >= 0 && mainPager.selectedPage > last) mainPager.switchToPage(last)
    }
    val currentItem = resolveBottomPagerItemForPage(mainPager.selectedPage, visibleItems)
    val selectPage: (BiliPaiNavKey) -> Boolean = remember(routes, visibleItems, mainPager) {
        { key ->
            val page = resolveBottomPagerPageForRoute(key.toLegacyRoute(), visibleItems)
            if (page == null) false
            else {
                if (shouldResetNavigation3BackStackForBottomPager(routes.stack)) routes.returnToMainHostAdmitted()
                mainPager.switchToPage(page)
                true
            }
        }
    }
    DisposableEffect(routes, selectPage) {
        routes.bindMainHostNavigation(selectPage)
        onDispose { routes.unbindMainHostNavigation(selectPage) }
    }
    val rootBackAction: () -> AppSystemBackAction = remember(currentItem) {
        { resolveAppSystemBackAction(true, currentItem) }
    }
    DisposableEffect(routes, rootBackAction) {
        routes.bindMainHostBackAction(rootBackAction)
        onDispose { routes.unbindMainHostBackAction(rootBackAction) }
    }
    val homeNavigation = remember(routes, pages.logout, pages.accountSwitcher) {
        desktopOriginalRootHomeNavigation(routes, pages.logout, pages.accountSwitcher)
    }
    val profileNavigation = remember(routes, pages) {
        desktopOriginalRootProfileNavigation(routes, pages.logoutSucceeded, pages.accountSwitchSucceeded)
    }
    val returning by root.returns.session.collectAsState()
    val skin = rememberHomeUiSkinDecoration(com.android.purebilibili.core.plugin.skin.LocalUiSkinState.current)
    val aggregate = root.entry.embeddedPages as DesktopOriginalHomeEmbeddedAggregate
    val returnState = returning
    // This is the stable AppNavigation recovery-generation owner. Stable's
    // declared generation has no synthetic increment or fake gesture source.
    var predictiveBackCancelRecoveryGeneration by remember { mutableIntStateOf(0) }
    val renderPage: @Composable (BiliPaiNavKey, Boolean, Boolean) -> Unit = { key, active, pagerHosted ->
        if (active && routes.owns()) SideEffect { onActiveDestination(key) }
        if (routes.owns()) CompositionLocalProvider(
            LocalDesktopHomeEnvironment provides root.environment,
            LocalDesktopHomeMediaPorts provides root.media,
            LocalDesktopHomePlatform provides desktopHomeActualPlatform(pages.window.resources.background,
                pages.window.resources.metrics, pages.homeGraphicsLayerCaptureReady()),
            LocalDesktopHomeMetricHolder provides pages.window.resources.metrics.holder,
            LocalDesktopHomeErrorAnimation provides { url, size, count -> root.ErrorAnimation(url, size, count) },
            LocalSetBottomBarVisible provides pages.setBottomBarVisible,
            LocalVideoCardTransitionClock provides pages.transitionClock,
            LocalDesktopDynamicCardBindings provides aggregate.gallery,
            LocalDesktopImagePreviewShareBindings provides aggregate.gallery.imageShare,
        ) {
            // This exists only inside a real page slot, after every Root initialization gate.
            Box(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (contentReady && active && routes.owns()) onRootContentFrame()
            }) {
            when (key) {
                BiliPaiNavKey.Home -> DesktopRetainedHomePage(root, pages.window, homeNavigation,
                    pages.scrollOffset, pages.feedScrollInProgress, pages.homeScroll,
                    LocalBottomBarVisible.current, LocalBottomBarContentPadding.current, pages.setBottomBarVisible,
                    LocalVideoCardTransitionBackgroundState.current, pages.transitionClock, pages.window.globalHaze(),
                    isTopLevelActive = active && (routes.currentKey == BiliPaiNavKey.MainHost || routes.currentKey == key),
                    homeGraphicsLayerCaptureReady = pages.homeGraphicsLayerCaptureReady())
                BiliPaiNavKey.Profile -> DesktopOriginalProfileHost(pages.profile, profileNavigation,
                    isCurrentPage = active && (routes.currentKey == BiliPaiNavKey.MainHost || routes.currentKey == key),
                    accountSessionRefreshGeneration = pages.accountSessionRefreshGeneration(),
                    showHistoryService = shouldShowProfileHistoryService(visibleItems.map { it.name }),
                    skinBackgroundImagePath = skin?.profileBackgroundImagePath,
                    skinSquaredBackgroundImagePath = skin?.profileSquaredBackgroundImagePath,
                    skinVideoBackgroundPath = skin?.profileVideoBackgroundPath,
                    skinVideoPlayMode = skin?.profileVideoPlayMode,
                    deferImmersiveRenderBudget = resolveBottomPagerRenderBudget(mainPager.isNavigating).deferProfileImmersiveBackground,
                    scrollToTopChannel = pages.profileScrollToTop)
                is BiliPaiNavKey.Category -> DesktopCategoryRouteHost(routes.category(key),
                    { routes.callbackFor(key) { routes.back() } },
                    { bvid, cid, picture, vertical -> routes.callbackFor(key) { routes.video(BiliPaiNavKey.VideoDetail(
                        bvid, cid, picture, initialVertical = vertical, sourceRoute = key.toLegacyRoute())) } },
                    returnState.isReturningFromDetail, returnState.isQuickReturnFromDetail)
                BiliPaiNavKey.Partition -> CompositionLocalProvider(
                    LocalDesktopPartitionViewModel provides aggregate.partition) {
                    PartitionScreen({ routes.back() },
                        { bvid, cid, cover -> routes.video(BiliPaiNavKey.VideoDetail(
                            bvid, cid, cover, sourceRoute = "partition")) }, homeNavigation.onBangumiClick)
                }
                BiliPaiNavKey.LiveList -> aggregate.LiveListScreen({ routes.back() }, homeNavigation.onLiveClick,
                    homeNavigation.onLiveSearchClick, homeNavigation.onLiveAreaClick, homeNavigation.onLiveFollowingClick,
                    homeNavigation.onLiveAreaDetailClick, showNavigationBack = !pagerHosted, embeddedInHome = false,
                    contentTopPadding = pages.liveTopPadding(), scrollToTopRequestId = pages.liveScrollRequestId())
                BiliPaiNavKey.LiveSearch, BiliPaiNavKey.LiveArea, BiliPaiNavKey.LiveFollowing,
                is BiliPaiNavKey.LiveAreaDetail -> DesktopLiveNavigationHost(root.liveNavigation) {
                    val back = { routes.callbackFor(key) { routes.back() } }
                    val room: (Long, String, String) -> Unit = { id, title, uname -> routes.callbackFor(key) {
                        routes.push(BiliPaiNavKey.Live(roomId = id.toString(), title = title, uname = uname)) } }
                    val area: (Int, Int, String) -> Unit = { parent, id, title -> routes.callbackFor(key) {
                        routes.push(BiliPaiNavKey.LiveAreaDetail(parent, id, title)) } }
                    when (key) {
                        BiliPaiNavKey.LiveSearch -> LiveSearchScreen(back, room,
                            { mid -> routes.callbackFor(key) { routes.push(BiliPaiNavKey.Space(mid)) } })
                        BiliPaiNavKey.LiveArea -> LiveAreaScreen(back, area)
                        BiliPaiNavKey.LiveFollowing -> LiveFollowingScreen(back, room)
                        is BiliPaiNavKey.LiveAreaDetail -> androidx.compose.runtime.key(key) {
                            LiveAreaDetailScreen(key.parentAreaId, key.areaId, key.title, back, area, room) }
                        else -> error("Unreachable typed Live branch")
                    }
                }
                is BiliPaiNavKey.Search, BiliPaiNavKey.Search, BiliPaiNavKey.SearchTrending -> {
                    val actualSearchKey = if (key == BiliPaiNavKey.Search) BiliPaiNavKey.Search() else key
                    DesktopOriginalSearchPhysicalLeaf(actualSearchKey,active,pagerHosted,
                        onBack={ routes.back() },returningFromVideo=returnState.isReturningFromDetail,
                        quickReturningFromVideo=returnState.isQuickReturnFromDetail,
                        consumeVideoReturn={ root.returns.consumeReturning() })
                }
                is BiliPaiNavKey.VideoDetail -> {
                    // AppNavigation.kt 2933–3018, original stack/return policies.
                    val immediate = routes.previousKey == key
                    val bindPreview = immediate && shouldBindVideoDetailBackPreviewPlayer(routes.currentKey, key)
                    val sessionActive = shouldActivateVideoDetailPlaybackSession(routes.currentKey, key,
                        immediate, bindPreview && returnState.isReturningFromDetail)
                    val detailState = DesktopOriginalVideoHolderRouteState(immediate, bindPreview,
                        predictiveBackCancelRecoveryGeneration.takeIf { routes.currentKey == key } ?: 0,
                        returnState.isReturningFromDetail, returnState.isQuickReturnFromDetail,
                        com.android.purebilibili.navigation.shouldEnableVideoDetailSharedTransition(
                            cardTransitionEnabled = homeSettings.cardTransitionEnabled,
                            sourceRoute = key.sourceRoute), resolveAppNavigationMotionSpec(
                            com.android.purebilibili.core.util.LocalWindowSizeClass.current.isTablet,
                            homeSettings.cardTransitionEnabled).slowFadeDurationMillis,
                        pages.inPictureInPicture(), sessionActive && routes.currentKey !is BiliPaiNavKey.AudioMode,
                        sessionActive)
                    CompositionLocalProvider(LocalDesktopOriginalRootVideoRouteState provides detailState) {
                        leafContent(key, routes, active, pagerHosted)
                    }
                }
                else -> leafContent(key, routes, active, pagerHosted)
            }
            }
        }
    }
    // Root's original full Dock remains OUTSIDE the captured page source, never recursively
    // captured as part of its own backdrop. The same pager providers drive its moving indicator.
    // Dock is outside NavDisplay captured content but must use the very same physical Root dispatcher.
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides environment.rootNavigationEventOwner,
        LocalDesktopHomeMediaPorts provides root.media, LocalDesktopHomeEnvironment provides root.environment) {
    DesktopOriginalRootChrome(routes, pages, chrome, currentItem, mainPager, visibleItems,
        { item -> routes.push(bottomPagerNavKeyForItem(item)) }) {
        val actualBottomBarVisible = LocalBottomBarVisible.current
        DesktopOriginalNavigationHost(environment, routes.stack, homeSettings, navigationSettings,
            isLightBackground, reduceMotion, videoSharedTransitionDurationMillis, pages.transitionClock,
            root.returns.sourceMetadataInCapturedHost(), programmaticBackDispatcher, preferWholeCardReturn,
            { routes.back() }, onPrepareVideoCardSharedReturn, onRelatedVideoDetailReturned,
            returnState.previousTransitionSessions.isNotEmpty() || returnState.previousVideoSources.isNotEmpty(), modifier) {
            key ->
            if (key == BiliPaiNavKey.MainHost) DesktopOriginalMainHostPager(visibleItems, pagerState, mainPager,
                contentReady, saveableState, { actualBottomBarVisible }, renderPage)
            else renderPage(key, routes.currentKey == key, false)
        }
    }
    }
}
