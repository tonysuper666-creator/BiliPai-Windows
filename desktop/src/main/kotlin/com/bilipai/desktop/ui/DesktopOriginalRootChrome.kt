@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.feature.home.components.FrostedSideBar
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop

internal class DesktopOriginalRootChromeBindings(
    val owner:DesktopOriginalLinkedDockOwner,
    val preferences:DesktopOriginalRootNavigationPreferences,
    val scope:CoroutineScope,
    val actions:DesktopOriginalFrostedNavigationActions,
    val audio:DesktopOriginalAudioNowPlayingBinding?,
    val nowPlayingVisibility:()->DesktopOriginalNowPlayingVisibility,
    val nowPlayingNavigation:()->DesktopOriginalNowPlayingNavigation,
    val navigationBarsBottom:()->Dp,
    val dynamicUnreadCount:()->Int,
    val searchLaunchKey:()->Int,
    val cardTransitionEnabled:()->Boolean,
    val accountSwitcher:(()->Unit)?,
    val onSourceReady:(Boolean)->Unit,
)

/** Large windows use the original side rail. Compact windows and Tabletop use the liquid bottom dock.
 * Video destinations keep the chrome hidden. The profile drawer stays inside HomeScreen. */
@Composable internal fun DesktopOriginalRootChrome(
    routes: DesktopOriginalRootRouteAssembly,
    pages: DesktopOriginalRootPageBindings,
    binding: DesktopOriginalRootChromeBindings,
    currentItem: BottomNavItem,
    visibleItems: List<BottomNavItem>,
    onItemClick: (BottomNavItem) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!routes.owns() || !binding.owner.isOwned()) return
    val key = routes.currentKey
    val video = key is BiliPaiNavKey.VideoDetail || key is BiliPaiNavKey.AudioMode || key is BiliPaiNavKey.NativeMusic ||
        key is BiliPaiNavKey.BangumiPlayer || key is BiliPaiNavKey.OfflineVideoPlayer
    val audio = binding.audio
    val snapshot = audio?.observeSnapshot()
    val visibility = binding.nowPlayingVisibility()
    val audioVisible = !video && audio != null && snapshot != null && audio.ownsSnapshot(snapshot) &&
        snapshot.active && visibility.sessionActive && !visibility.inPipMode
    val navigation = binding.nowPlayingNavigation()
    val homeSettings by routes.root.environment.settings.homeSettings.collectAsState()
    val navigationSettings by routes.root.environment.settings.navigation.collectAsState()
    val wallpaperUri by routes.root.environment.settings.homeWallpaperUri.collectAsState()
    val windowClass = LocalWindowSizeClass.current
    val posture = LocalAppWindowAdaptiveInfo.current.posture
    val placement = resolveDesktopShellNavigationPlacement(
        video, windowClass, navigationSettings.tabletUseSidebar, posture)
    val useSidebar = placement == DesktopShellNavigationPlacement.SIDEBAR
    val useBottomDock = placement == DesktopShellNavigationPlacement.BOTTOM_DOCK
    val bottomReserved = useBottomDock && pages.bottomBarVisible()
    val bottomPadding = rememberAppBottomBarContentPadding(
        navigationBarsBottom = binding.navigationBarsBottom(),
        reserveBottomBar = bottomReserved,
        isBottomBarFloating = homeSettings.isBottomBarFloating,
        hasUiSkinDecoration = false,
    )
    SideEffect {
        audio?.publishBarOverlayVisible(audioVisible)
    }
    DisposableEffect(audio) { onDispose { audio?.publishBarOverlayVisible(false) } }
    CompositionLocalProvider(
        LocalBottomBarVisible provides bottomReserved,
        LocalBottomBarContentPadding provides if (bottomReserved) bottomPadding else 0.dp,
        LocalSetBottomBarVisible provides pages.setBottomBarVisible,
        LocalGlobalWallpaperBackdropVisible provides false,
    ) {
        CompositionLocalProvider(LocalDesktopHomeMediaPorts provides routes.root.media) {
        DesktopWindowsGlassBackgroundHost(
            sourceOwner = routes.root, wallpaperUri = wallpaperUri, home = homeSettings,
            showHomeWallpaper = key == BiliPaiNavKey.MainHost && currentItem == BottomNavItem.HOME,
            isDataSaverActive = routes.root.environment.settings.isDataSaverActive(),
            owns = { routes.owns() && binding.owner.isOwned() },
        ) {
        val glassMaterial = LocalDesktopWindowsGlassMaterial.current
        SideEffect {
            binding.onSourceReady(glassMaterial != null && glassMaterial.renderer.supported &&
                glassMaterial.sourceReady && glassMaterial.owns())
        }
        DisposableEffect(binding.owner) { onDispose { binding.onSourceReady(false) } }
        // The Home renderer still independently requires its OWN ready backdrop.
        // This publishes the real Windows shader capability gate, not feed pixels.
        val haze = if (homeSettings.isBottomBarBlurEnabled) pages.window.globalHaze() else null
        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (useSidebar) Column(
                Modifier.fillMaxHeight().largeScreenFocusTraversal(DesktopLargeScreenFocusAxis.VERTICAL),
            ) {
                FrostedSideBar(
                    currentItem = currentItem,
                    onItemClick = { if (routes.owns()) onItemClick(it) },
                    modifier = Modifier.weight(1f),
                    hazeState = haze,
                    onHomeDoubleTap = { if (routes.owns()) binding.actions.homeDoubleTap() },
                    onDynamicDoubleTap = { if (routes.owns()) binding.actions.dynamicDoubleTap() },
                    visibleItems = visibleItems,
                    itemColorIndices = navigationSettings.bottomBarItemColors,
                    itemLabels = navigationSettings.bottomBarItemLabels,
                    sidebarExpanded = resolveDesktopShellSidebarExpanded(
                        navigationSettings.sidebarExpanded, windowClass, posture),
                    onSidebarExpandedChange = { expanded ->
                        binding.scope.launch { binding.preferences.setSidebarExpanded(expanded) }
                    },
                    onToggleSidebar = binding.actions.toggleSidebar,
                    onAccountSwitchClick = binding.accountSwitcher,
                )
                DesktopWindowsGlassSurface(shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)) {
                    AppIconButton(
                        onClick = { if (routes.owns()) binding.actions.searchClick() },
                        modifier = Modifier.defaultMinSize(
                            minWidth = desktopLargeScreenMinimumTarget(),
                            minHeight = desktopLargeScreenMinimumTarget(),
                        ),
                    ) {
                        AppIcon(rememberAppSearchIcon(), DesktopShellSearchDescription)
                    }
                    AppIconButton(
                        onClick = { if (routes.owns()) routes.push(BiliPaiNavKey.DownloadList) },
                        modifier = Modifier.defaultMinSize(
                            minWidth = desktopLargeScreenMinimumTarget(),
                            minHeight = desktopLargeScreenMinimumTarget(),
                        ),
                    ) {
                        AppIcon(rememberAppDownloadIcon(), DesktopShellDownloadsDescription)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (useSidebar && audioVisible && audio != null && snapshot != null) DesktopWindowsGlassSurface(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().largeScreenFocusTraversal(DesktopLargeScreenFocusAxis.HORIZONTAL)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(snapshot.item.title, Modifier.weight(1f), maxLines = 1)
                        LargeScreenLabeledButton("上一首") { audio.previous(snapshot) }
                        LargeScreenLabeledButton(if (snapshot.isPlaying) "暂停" else "播放", filled = true) {
                            audio.playPause(snapshot, navigation)
                        }
                        LargeScreenLabeledButton("下一首") { audio.next(snapshot) }
                        LargeScreenLabeledButton("打开播放器") { audio.expand(snapshot, navigation) }
                        LargeScreenLabeledButton("关闭") { audio.dismiss(snapshot, navigation) }
                    }
                }
            }
        }
        if (useBottomDock) {
            val readyGlass = glassMaterial?.takeIf {
                it.renderer.supported && it.sourceReady && it.owns() && it.window === LocalAwtWindow.current
            }
            val backdrop = readyGlass?.backdrop as? LayerBackdrop
            Box(
                Modifier.align(Alignment.BottomCenter).zIndex(1f).fillMaxWidth()
                    .largeScreenFocusTraversal(DesktopLargeScreenFocusAxis.HORIZONTAL),
            ) {
                DesktopOriginalFrostedAudioNavigation(
                    preferenceContext = routes.root.environment.pluginContext,
                    owner = binding.owner,
                    navigation = DesktopOriginalFrostedNavigationState(
                        currentItem = currentItem,
                        visibleItems = visibleItems,
                        itemColorIndices = navigationSettings.bottomBarItemColors,
                        itemLabels = navigationSettings.bottomBarItemLabels,
                        dynamicUnreadCount = binding.dynamicUnreadCount(),
                        isFloating = homeSettings.isBottomBarFloating,
                        isMainHost = key == BiliPaiNavKey.MainHost,
                        isTopLevelDestination = key == BiliPaiNavKey.MainHost ||
                            visibleItems.any { key.toLegacyRoute() == it.route },
                        transitionRunning = false,
                        forceLowBlurBudget = false,
                        collapseLinkedDock = !pages.bottomBarVisible() ||
                            (currentItem == BottomNavItem.DYNAMIC && pages.scrollOffset.floatValue > 50f),
                        motionTier = MotionTier.Normal,
                        indicatorPosition = null,
                        pagerScrolling = { false },
                        animateNowPlayingPresence = true,
                        searchLaunchKey = binding.searchLaunchKey(),
                    ),
                    actions = binding.actions,
                    audio = audio,
                    nowPlayingVisibility = visibility,
                    nowPlayingNavigation = navigation,
                    hazeState = haze,
                    backdrop = backdrop,
                    skinDecoration = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        }
        }
        }
    }
}
