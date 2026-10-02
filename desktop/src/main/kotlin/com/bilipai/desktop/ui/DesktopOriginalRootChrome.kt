package com.bilipai.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.home.components.cards.LocalWallpaperPalette
import com.android.purebilibili.feature.audio.screen.*
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.android.purebilibili.core.ui.blur.*
import com.android.purebilibili.core.ui.motion.*
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.util.*
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.*
import com.android.purebilibili.core.plugin.skin.LocalUiSkinState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Actual Root window/scroll/audio effects. The renderer uses original settings and motion;
 * channels/actions are the same retained page consumers, not synthetic empty callbacks. */
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

/** Original shell layout: SideBar sibling, single captured page source and actual full dock.
 * Page content is recorded before the dock, never recursively capturing the dock itself. */
@Composable internal fun DesktopOriginalRootChrome(
    routes:DesktopOriginalRootRouteAssembly,
    pages:DesktopOriginalRootPageBindings,
    binding:DesktopOriginalRootChromeBindings,
    currentItem:BottomNavItem,
    mainPager:MainBottomPagerState,
    visibleItems:List<BottomNavItem>,
    onItemClick:(BottomNavItem)->Unit,
    content:@Composable ()->Unit,
) {
    if(!routes.owns()||!binding.owner.isOwned())return
    val root=routes.root
    val home by root.environment.settings.homeSettings.collectAsState()
    val navigation by root.environment.settings.navigation.collectAsState()
    val returns by root.returns.session.collectAsState()
    val audioPrefs by binding.preferences.audio.collectAsState()
    val realtimeBlur by binding.preferences.realtimeTransitionBlur.collectAsState()
    val configuredWallpaper by root.environment.settings.homeWallpaperUri.collectAsState()
    val splashWallpaper by root.environment.settings.splashWallpaperUri.collectAsState()
    val wallpaperPalette by root.environment.wallpaperPalette.collectAsState()
    val wallpaperUri=remember(configuredWallpaper,splashWallpaper){resolveHomeWallpaperUri(configuredWallpaper,splashWallpaper)}
    LaunchedEffect(wallpaperUri,root) { pages.window.loadWallpaperPalette(root.entry.gate,wallpaperUri,this) }
    val dataSaverActive=remember(root){root.environment.settings.isDataSaverActive()}
    val baseColor=MaterialTheme.colorScheme.background
    val lightBackground=baseColor.luminance()>.5f
    val audioSnapshot=binding.audio?.observeSnapshot()
    val windowClass=LocalWindowSizeClass.current
    val useSidebar=shouldUseSidebarNavigationForLayout(windowClass,navigation.tabletUseSidebar,
        LocalAppWindowAdaptiveInfo.current.posture)
    val currentKey=routes.currentKey
    val activeRoute=resolveActiveBottomTabRoute(currentKey,currentItem)
    val renderGlobalWallpaper=shouldRenderGlobalHomeWallpaperBackdrop(home.homeWallpaperEffectScope,currentKey.toLegacyRoute(),currentItem.route)
    val exposeWallpaper=shouldExposeGlobalHomeWallpaperChrome(home.homeWallpaperEffectScope,wallpaperUri.isNotBlank(),currentKey.toLegacyRoute(),currentItem.route)
    val wallpaperAppearance=remember(wallpaperUri,home.homeWallpaperEffectMode,renderGlobalWallpaper,lightBackground,dataSaverActive) {
        resolveHomeWallpaperBackdropAppearance(renderGlobalWallpaper&&wallpaperUri.isNotBlank(),home.homeWallpaperEffectMode,!lightBackground,dataSaverActive,globalWallpaper=false)
    }
    val video=currentKey is BiliPaiNavKey.VideoDetail
    val clock=pages.transitionClock
    val exposure=resolveVideoCardTransitionExposure(clock.phase,
        clock.settleState==VideoCardTransitionSettleState.InteractiveSeek,
        clock.settleState==VideoCardTransitionSettleState.CancelRestore||clock.gestureRestoreInProgress)
    val sourceIsBottom=isVideoCardTransitionBottomBarSource(clock.sourceRoute,visibleItems.map{it.route}.toSet())
    val sourceChrome=shouldShowVideoCardTransitionSourceChrome(video,exposure,sourceIsBottom)
    val driveByProgress=shouldDriveVideoCardTransitionChromeByProgress(binding.cardTransitionEnabled(),exposure,sourceIsBottom)
    val mountRoute=resolveVideoCardTransitionChromeBottomBarRoute(video,activeRoute,currentItem.route)
    val skin=rememberBottomBarUiSkinDecoration(LocalUiSkinState.current)
    val visibility=binding.nowPlayingVisibility().copy(barEnabled=audioPrefs.enabled)
    val audioActive=audioSnapshot!=null&&binding.audio?.ownsSnapshot(audioSnapshot)==true&&visibility.sessionActive&&audioSnapshot.active
    val state=desktopOriginalRootChromeState(currentKey.toLegacyRoute(),activeRoute,mountRoute,
        visibleItems.map{it.route}.toSet(),windowClass.isTablet,useSidebar,video,returns,
        binding.cardTransitionEnabled(),driveByProgress,sourceChrome,navigation.bottomBarVisibilityMode,
        pages.bottomBarVisible(),pages.setBottomBarVisible,home.isBottomBarFloating,audioPrefs.enabled,
        audioActive,audioSnapshot?.item,currentItem,pages.scrollOffset,skin,binding.navigationBarsBottom())
    val independentAudioDestination=isAudioNowPlayingPlayerDestination(currentKey.toLegacyRoute()) ||
        (returns.isReturningFromDetail&&returns.transitionSession?.bvid==audioSnapshot?.item?.bvid)
    val actualBarOverlayVisible=audioActive&&resolveAudioNowPlayingVisible(
        visibility.sessionActive&&audioSnapshot?.active==true,visibility.onAudioModeScreen,visibility.inPipMode,
        audioSnapshot!=null,visibility.barEnabled,visibility.inMiniMode,video,visibility.landscape,
        if(state.bottomBarCanMount)visibility.playerDestination else independentAudioDestination)
    SideEffect { binding.audio?.publishBarOverlayVisible(actualBarOverlayVisible) }
    val budget=resolveBottomPagerRenderBudget(mainPager.isNavigating)
    val motion=remember(windowClass.isTablet,binding.cardTransitionEnabled()){
        resolveAppNavigationMotionSpec(windowClass.isTablet,binding.cardTransitionEnabled())}
    val haze=if(home.isBottomBarBlurEnabled)pages.window.globalHaze()else null
    val backdrop=if(home.androidNativeLiquidGlassEnabled||home.isBottomBarBlurEnabled)rememberChromeBackdropSource()else null
    SideEffect{binding.onSourceReady(backdrop?.isReady==true)}
    val actions=DesktopOriginalFrostedNavigationActions(onItemClick,binding.actions.homeDoubleTap,
        binding.actions.dynamicDoubleTap,binding.actions.searchClick,binding.actions.openSearch,
        binding.actions.openNativeTarget,binding.actions.searchTransitionFinished,
        binding.actions.toggleSidebar,binding.actions.historySearchChannel,binding.actions.favoriteSearchChannel,
        binding.actions.watchLaterSearchChannel)
    val nowNavigation=binding.nowPlayingNavigation()
    val actualNowNavigation=DesktopOriginalNowPlayingNavigation(nowNavigation.sourceRoute,audioPrefs.opensAudioMode,
        if(returns.isReturningFromDetail&&driveByProgress)NowPlayingBarHandoffState.Returning(
            returns.transitionSession?.bvid,sourceChrome)else NowPlayingBarHandoffState.Idle,
        driveByProgress,nowNavigation.openAudioMode,nowNavigation.openVideo,nowNavigation.dismissBar)
    CompositionLocalProvider(LocalBottomBarVisible provides (state.finalBottomBarVisible&&!driveByProgress),
        LocalBottomBarContentPadding provides state.bottomBarContentPadding,
        LocalSetBottomBarVisible provides pages.setBottomBarVisible,
        LocalGlobalWallpaperBackdropVisible provides exposeWallpaper,
        LocalWallpaperPalette provides wallpaperPalette,
        LocalFloatingChromeBackdrop provides backdrop?.backdrop) {
        DesktopOriginalLinkedDockOwnerProvider(binding.owner){pageScrollModifier->
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    if(windowClass.shouldUseSideNavigation&&state.sideBarMountGate) {
                        AnimatedVisibility(useSidebar,
                            enter=slideInHorizontally(animationSpec=softLandingSpring(),initialOffsetX={-it})+
                                fadeIn(emphasizedEnterTween(motion.slowFadeDurationMillis)),
                            exit=slideOutHorizontally(animationSpec=emphasizedExitTween(motion.fastFadeDurationMillis),targetOffsetX={-it})+
                                fadeOut(emphasizedExitTween(motion.fastFadeDurationMillis))) {
                            FrostedSideBar(currentItem,onItemClick,firstItemModifier=Modifier,
                                hazeState=haze,onHomeDoubleTap=actions.homeDoubleTap,onDynamicDoubleTap=actions.dynamicDoubleTap,
                                visibleItems=visibleItems,itemColorIndices=navigation.bottomBarItemColors,itemLabels=navigation.bottomBarItemLabels,
                                uiSkinDecoration=skin,sidebarExpanded=navigation.sidebarExpanded,
                                onSidebarExpandedChange={expanded->binding.scope.launch{binding.preferences.setSidebarExpanded(expanded)}},
                                onToggleSidebar={binding.scope.launch{root.environment.settings.setTabletUseSidebar(false)}},
                                onAccountSwitchClick=if(navigation.sidebarAccountSwitcherEnabled)binding.accountSwitcher else null)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().then(pageScrollModifier)
                        .then(backdrop?.modifier?:Modifier)
                        .then(if(haze!=null)Modifier.hazeSourceCompat(haze)else Modifier)) {
                        DepthSyncedGlobalHomeWallpaperBackdrop(wallpaperUri,wallpaperAppearance,baseColor,
                            {clock.depthProgress()},{clock.phase},{clock.gestureRestoreInProgress},
                            {root.returns.sourceMetadataInCapturedHost().sourceBounds},dataSaverActive,lightBackground,realtimeBlur)
                        content()
                    }
                }
                if(state.bottomBarCanMount) {
                    val dockModifier=Modifier.align(Alignment.BottomCenter).zIndex(1f)
                        .then(if(driveByProgress)Modifier.videoCardTransitionChromeReveal(
                            {resolveVideoCardTransitionChromeReveal(clock.depthProgress())},true)else Modifier)
                    Box(dockModifier) {
                        BottomBarMatchedDockVisibility(state.bottomBarVisibilityState,BottomBarMatchedDockEdge.BOTTOM,
                            enterFadeDurationMillis=motion.slowFadeDurationMillis,exitFadeDurationMillis=motion.fastFadeDurationMillis) {
                            DesktopOriginalFrostedAudioNavigation(root.environment.pluginContext,binding.owner,
                                DesktopOriginalFrostedNavigationState(currentItem,visibleItems,navigation.bottomBarItemColors,
                                    navigation.bottomBarItemLabels,binding.dynamicUnreadCount(),home.isBottomBarFloating,
                                    currentKey==BiliPaiNavKey.MainHost,
                                    resolveBottomPagerPageForRoute(activeRoute,visibleItems)!=null,
                                    budget.isTransitionRunning,budget.forceLowBlurBudget,state.collapseLinkedPlaybackDock,
                                    MotionTier.Normal,{mainPager.indicatorPosition},{mainPager.isScrollInProgress},
                                    !driveByProgress,binding.searchLaunchKey()),
                                actions,binding.audio,visibility,actualNowNavigation,haze,backdrop?.backdrop,skin,Modifier)
                        }
                    }
                } else if (binding.audio != null && audioSnapshot != null) {
                    val audio=binding.audio
                    val item=audioSnapshot.item
                    val independentPlayer=isAudioNowPlayingPlayerDestination(currentKey.toLegacyRoute()) ||
                        (returns.isReturningFromDetail&&returns.transitionSession?.bvid==item.bvid)
                    val showIndependent=resolveAudioNowPlayingVisible(visibility.sessionActive&&audioSnapshot.active,visibility.onAudioModeScreen,
                        visibility.inPipMode,true,visibility.barEnabled,visibility.inMiniMode,video,
                        visibility.landscape,independentPlayer)
                    AudioNowPlayingBarPresenceHost(showIndependent,Modifier.align(Alignment.BottomCenter).zIndex(2f)) {
                        AudioNowPlayingBar(state=AudioNowPlayingBarState(item.bvid,item.title,item.owner,item.ownerFace,item.cover,
                            audioSnapshot.isPlaying,audioSnapshot.playbackSpeed),
                            sourceIsOwned={binding.owner.isOwned()&&audio.ownsSnapshot(audioSnapshot)},
                            isLayoutStable=!driveByProgress,sourceRoute=currentKey.toLegacyRoute(),handoff=actualNowNavigation.handoff,
                            onExpand={audio.expand(audioSnapshot,actualNowNavigation)},onPlayPause={audio.playPause(audioSnapshot,actualNowNavigation)},
                            onSkipNext={audio.next(audioSnapshot)},onSkipPrevious={audio.previous(audioSnapshot)},onDismiss={audio.dismiss(audioSnapshot,actualNowNavigation)},
                            expandDestinationLabel=if(audioPrefs.opensAudioMode)"听视频"else"视频详情页",
                            glassEnabled=home.androidNativeLiquidGlassEnabled,blurEnabled=home.isBottomBarBlurEnabled,
                            hazeState=haze,miuixBackdrop=backdrop?.backdrop,liquidGlassTuning=LocalLiquidGlassRenderConfig.current.tuning,
                            liftAboveBottomBar=false,consumeNavigationBarsPadding=true)
                    }
                }
            }
        }
    }
}
