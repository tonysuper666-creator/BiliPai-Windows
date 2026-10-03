package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.android.purebilibili.core.util.HomeCoverReturnPrefetchRegistry
import com.android.purebilibili.core.util.resolveHomeCoverReturnPrefetchCandidates
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo
import com.android.purebilibili.core.ui.adaptive.toAdaptiveFoldPosture
import com.android.purebilibili.feature.home.HomeScrollRequest
import com.android.purebilibili.feature.dynamic.DynamicScrollRequest
import com.android.purebilibili.feature.home.components.LinkedDockPhase
import com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore
import com.android.purebilibili.navigation3.*
import com.android.purebilibili.navigation.bottomPagerNavKeyForItem
import com.android.purebilibili.feature.profile.AccountSwitchDialog
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.appearance.LocalDesktopTextClipboard
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/** The actual Shell services. No navigation state, copied cookies, list model, cache or player
 * is added. Root passes its current existing actors and primitive UI actions below. */
internal class DesktopReadyOriginalRootServices(
    val repository: DesktopRepository,
    val originalVideoWindowReady: (DesktopOriginalVideoRootWindowEnvironment) -> Unit,
    val originalVideoWindowRetired: (DesktopOriginalVideoRootWindowEnvironment) -> Unit,
    val originalVideoWindowContent: @Composable (DesktopOriginalVideoRootWindowEnvironment, @Composable () -> Unit) -> Unit,
    val discovery: DesktopDiscoveryRepository,
    val community: DesktopCommunityRepository,
    val runtime: DesktopPluginRuntime,
    val dynamicCardSession: DesktopDynamicCardSession,
    val appearance: DesktopThemePrefs,
    val actualWindow: Window,
    val imageLocations: DesktopImageSaveLocations,
    val imageLifetime: DesktopImageSaveLifetime,
    val diagnostics: DesktopDiagnostics?,
    val nativeTextShare: DesktopNativeTextShare,
    val textShare: DesktopTextShareBindings,
    val musicOverlayVisible: StateFlow<Boolean>,
    val downloadItems: () -> Collection<com.android.purebilibili.feature.download.DownloadTask>,
    val listen: com.bilipai.desktop.audio.ListenAudioSession?,
    val playerError: String?,
    val rootAlive: () -> Boolean,
    val isPipActiveOrPending: () -> Boolean,
    val canTrimImages: () -> Boolean,
    val navigationAdmission: ((() -> Unit) -> Boolean),
    val beforeNavigationCommit: (BiliPaiNavKey, BiliPaiNavKey) -> Unit,
    val activeDestinationChanged: (BiliPaiNavKey) -> Unit,
    val feedback: (String) -> Unit,
    val openExternalLink: (String) -> Unit,
    val authenticationInvalidated: (Long, Long) -> Unit,
    val profileAccounts: (DesktopHomeRetainedGate) -> DesktopProfileAccountPort,
    val backAtHomeRoot: () -> Unit,
    val login: () -> Unit,
    val logout: (() -> Unit)?,
    val dynamicUnreadBaseline: () -> String,
    val historySearch: kotlinx.coroutines.channels.SendChannel<String>?,
    val favoriteSearch: kotlinx.coroutines.channels.SendChannel<String>?,
    val watchLaterSearch: kotlinx.coroutines.channels.SendChannel<String>?,
    val dismissNowPlayingBar: () -> Unit,
    val nowPlayingVisibility: () -> DesktopOriginalNowPlayingVisibility,
    val ffprobe: Path,
    val library: com.bilipai.desktop.DesktopLibrary,
    val nowPlayingBinding: (DesktopHomeRetainedRoot) -> DesktopOriginalAudioNowPlayingBinding,
    val nowPlayingPositionMs: (DesktopHomeRetainedRoot, DesktopOriginalNowPlayingSnapshot) -> Long?,
    val originalSpacePlaylist: DesktopOriginalVideoPlaylistBinding,
    val originalSpaceCachedPosition: (String) -> Long,
)

/** Shutdown order is captured entry/route admission first, drains outside Store locks, then
 * global Window owners. Root's three existing freeze/restore/exit hooks invoke this handle. */
internal class DesktopReadyOriginalRootHandle(
    val retainer: DesktopHomeRootRetainer,
    val navigation: DesktopRootWindowNavigationOwner,
    val preferencePlatform: DesktopHomeWindowsPreferencesPlatform,
    val palette: WallpaperPaletteStore,
) {
    private val closed = AtomicBoolean(false)
    private val chromeRefresh = AtomicReference<(() -> Unit)?>(null)
    fun installCurrentRootChrome(refresh: () -> Unit) { if (isActive()) chromeRefresh.set(refresh) }
    fun refreshCurrentRootChrome() { if (isActive()) chromeRefresh.get()?.invoke() }
    val route = AtomicReference<DesktopOriginalRootRouteAssembly?>()
    val personalLists = AtomicReference<DesktopPersonalListsRoot?>()
    val messagePages = AtomicReference<DesktopOriginalMessagePagesRoot?>()
    val spacePages = AtomicReference<DesktopOriginalSpacePagesRoot?>()
    var imageTrim: DesktopApplicationImageCacheTrim? = null
    fun isActive() = !closed.get() && retainer.isActive() && navigation.owns()
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        if (!closed.compareAndSet(false, true)) return@withContext
        chromeRefresh.set(null)
        route.getAndSet(null)?.close()
        messagePages.getAndSet(null)?.closeAndJoin()
        spacePages.getAndSet(null)?.closeAndJoin()
        personalLists.getAndSet(null)?.closeAndJoin()
        retainer.closeAndJoin()
        imageTrim?.close(); imageTrim = null
        palette.close(); preferencePlatform.close(); navigation.close()
    }
}

/** Call exactly once inside ReadyApp's actual root theme, above every route. While a video,
 * Favorite/audio/settings page covers Home this function remains mounted and the same VM
 * continues to own all four embedded pages and TodayWatch. */
@Composable internal fun DesktopReadyOriginalRootMount(
    services: DesktopReadyOriginalRootServices,
    handleReference: AtomicReference<DesktopReadyOriginalRootHandle?>,
    modifier: Modifier,
    onRootContentFrame: ((() -> Boolean) -> Unit) = {},
    leaf: @Composable (BiliPaiNavKey, DesktopOriginalRootRouteCommands, Boolean, Boolean, DesktopPersonalListsRoot, DesktopHomeSettingsPort, DesktopOriginalMessagePagesRoot, DesktopOriginalSpacePagesRoot) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val applicationImages = LocalDesktopApplicationImageLoader.current
    val clipboard = LocalDesktopTextClipboard.current
    val platformContext = LocalPlatformContext.current
    val density = LocalDensity.current
    val latest by rememberUpdatedState(services)
    val rootThemeColor = MaterialTheme.colorScheme.background
    val themeColor by rememberUpdatedState(rootThemeColor)
    val logger = remember { Logger.getLogger("BiliPai.HomeWindow") }
    val resources = rememberDesktopHomeActualWindowResources(services.actualWindow,
        { latest.rootAlive() }, { java.awt.Color(themeColor.toArgb(), true) }, logger)
    var windowRetry by remember(services.actualWindow) { mutableIntStateOf(0) }
    val platformAttempt = remember(services.actualWindow, windowRetry) { runCatching {
        DesktopHomeWindowsPreferencesPlatform(services.actualWindow,
            { Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),
                "native", "windows-x64", "bilipai-diagnostic-share.dll") },
            DesktopNativeDiagnosticShareAssetHash.sha256, { latest.rootAlive() })
    } }
    val preferencesPlatform = platformAttempt.getOrNull()
    if (preferencesPlatform == null) {
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("Windows 窗口能力初始化失败，首页尚未创建")
            TextButton(onClick = { windowRetry++ }) { Text("重试") }
        }
        return
    }
    val navigation = remember(services.actualWindow, preferencesPlatform) { DesktopRootWindowNavigationOwner(services.actualWindow,
        { latest.rootAlive() }, { latest.backAtHomeRoot() }) }
    val palette = remember(applicationImages) { WallpaperPaletteStore() }
    val retainer = remember(services.runtime, navigation) { DesktopHomeRootRetainer(services.runtime.recommendations,
        { services.repository.sessionEpoch }, { latest.rootAlive() && navigation.owns() }) }
    val handle = remember(retainer, navigation) { DesktopReadyOriginalRootHandle(retainer, navigation, preferencesPlatform, palette) }
    SideEffect { check(handleReference.get() == null || handleReference.get() === handle); handleReference.set(handle) }
    DisposableEffect(handle) { onDispose {
        // cancel admission synchronously, then drain in a NON-child shutdown task.
        handleReference.compareAndSet(handle, null)
        handle.route.getAndSet(null)?.close(); handle.messagePages.get()?.close(); handle.spacePages.get()?.close(); handle.retainer.retire()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { handle.closeAndJoin() }
    } }
    val physicalStack = remember(navigation) { mutableStateListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost) }
    val homeScroll = remember(navigation) { Channel<HomeScrollRequest>(Channel.CONFLATED) }
    val profileScroll = remember(navigation) { Channel<Unit>(Channel.CONFLATED) }
    val liveScroll = remember(navigation) { Channel<Unit>(Channel.CONFLATED) }
    val scrollOffset = remember { mutableFloatStateOf(0f) }
    val feedScrolling = remember { mutableStateOf(false) }
    val linkedPhase = remember { mutableStateOf<LinkedDockPhase>(LinkedDockPhase.Expanded) }
    var bottomVisible by remember { mutableStateOf(true) }
    var bottomPadding by remember { mutableStateOf(0.dp) }
    var sourceReady by remember { mutableStateOf(false) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var searchLaunch by remember { mutableIntStateOf(0) }
    var liveScrollId by remember { mutableIntStateOf(0) }
    var accountRefresh by remember { mutableIntStateOf(0) }
    val configuration = remember(navigation) { MutableStateFlow(DesktopProfileWindowConfiguration(0, 0)) }
    val measuredConfiguration by configuration.collectAsState()
    val clock = remember { VideoCardTransitionClock() } // exact original rememberVideoCardTransitionClock body
    val saveable = rememberSaveableStateHolder()
    val haze = remember(navigation) { HazeState() }
    val reduceMotion = rememberDesktopDynamicReduceMotion()
    val reduceMotionNow by rememberUpdatedState(reduceMotion)
    val systemWallpaperChrome = remember(navigation, resources) { DesktopWindowsProfileChrome(services.actualWindow,
        resources.clientPolicy, { themeColor.luminance() > .5f }, { failure ->
            services.diagnostics?.record("W", "RootChrome", failure.javaClass.simpleName) }) }
    SideEffect { handle.installCurrentRootChrome(systemWallpaperChrome::refreshCurrentRootTheme) }
    val navPreferences = remember(services.runtime.store, handle) { DesktopOriginalRootNavigationPreferences(
        services.runtime.store, scope, handle::isActive, services.imageLifetime::withCommit) }
    var settings by remember(services.runtime.store, handle) { mutableStateOf<DesktopOriginalHomePreferences?>(null) }
    var installFailure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(handle, retry) {
        try { settings = DesktopOriginalHomePreferences.create(services.runtime.store, scope,
            preferencesPlatform.defaultTabletUseSidebar, preferencesPlatform::isMobileNetwork); installFailure = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { installFailure = "首页设置或 Windows 平台初始化失败，请重试" }
    }
    val epoch by services.repository.sessionEpochFlow.collectAsState()
    val account by services.repository.account.collectAsState()
    val incremental = remember(services.runtime.context, scope) {
        DesktopDynamicTimelinePreferences(services.runtime.context).incrementalRefresh
            .stateIn(scope, SharingStarted.Eagerly,
                services.runtime.store.snapshot("settings").value[booleanPreferencesKey("incremental_timeline_refresh")] ?: false)
    }
    val prefs = settings
    val factoryBinding = remember(prefs, services.dynamicCardSession, services.runtime, handle) {
        prefs?.let { actualSettings -> DesktopReadyHomeFactoryBinding(services.repository, services.discovery,
            services.community, services.runtime, services.dynamicCardSession, scope, services.actualWindow,
            services.runtime.context, actualSettings, navigation, resources, services.imageLocations,
            services.imageLifetime, applicationImages.imageLoader, platformContext, services.appearance,
            configuration, DesktopProfileFfprobeWidth(services.ffprobe),
            requireNotNull(DesktopReadyOriginalRootHandle::class.java.classLoader.getResource("app-icon.png")), clipboard,
            services.nativeTextShare, services.textShare, services.diagnostics, services.musicOverlayVisible,
            incremental, palette, systemWallpaperChrome, liveScroll, haze,
            DesktopHomeRootReturnPorts(services.navigationAdmission, { hostOrigin }, DesktopHomeClock::elapsedRealtime,
                clock, { actualSettings.homeSettings.value.cardTransitionEnabled && !reduceMotionNow },
                { actualSettings.homeSettings.value.cardTransitionEnabled && !reduceMotionNow }, { reduceMotionNow }),
            services.feedback, services.openExternalLink, services.authenticationInvalidated,
            rootPublished = { gate -> handle.retainer.current()?.entry?.gate === gate }) }
    }
    LaunchedEffect(handle, epoch, factoryBinding, retry) {
        val factory = factoryBinding ?: return@LaunchedEffect
        try {
            handle.route.getAndSet(null)?.close()
            handle.messagePages.getAndSet(null)?.closeAndJoin()
            handle.spacePages.getAndSet(null)?.closeAndJoin()
            physicalStack.clear(); physicalStack.add(BiliPaiNavKey.MainHost)
            handle.retainer.install(epoch, account?.mid, factory.factory)
            installFailure = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { installFailure = "首页资料初始化失败，请重试" }
    }
    val retained by retainer.root.collectAsState()
    val root = retained?.takeIf { it.capturedEpoch == epoch && it.isCurrentOwner() }
    BoxWithConstraints(modifier.onGloballyPositioned { coordinates ->
        hostOrigin = coordinates.positionInRoot()
        val width = with(density) { coordinates.size.width.toDp().value.toInt() }
        val height = with(density) { coordinates.size.height.toDp().value.toInt() }
        if (width > 0 && height > 0) configuration.value = DesktopProfileWindowConfiguration(width, height)
    }) {
        val windowBinding = factoryBinding
        if (root == null || prefs == null || windowBinding == null || measuredConfiguration.screenWidthDp <= 0 || measuredConfiguration.screenHeightDp <= 0) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                if (installFailure == null) CircularProgressIndicator()
                else { Text(requireNotNull(installFailure)); TextButton(onClick = { retry++ }) { Text("重试") } }
            }
            return@BoxWithConstraints
        }
        val currentHome by prefs.homeSettings.collectAsState()
        val speedSettings = remember(currentHome.videoSharedTransitionSpeed, currentHome.videoSharedTransitionCustomDurationMillis) {
            VideoSharedTransitionSpeedSettings(currentHome.videoSharedTransitionSpeed, currentHome.videoSharedTransitionCustomDurationMillis)
        }
        val windowClass = LocalWindowSizeClass.current
        val adaptiveWindow = LocalAppWindowAdaptiveInfo.current
        val adaptiveTransition = remember(windowClass, adaptiveWindow) {
            VideoTransitionAdaptiveInfo(windowClass.widthSizeClass, adaptiveWindow.posture.toAdaptiveFoldPosture())
        }
        val transitionDuration = if (reduceMotion) VideoCardTransitionVisualTimeline.REDUCED_MOTION_DURATION_MILLIS
            else resolveVideoSharedTransitionDurationMillis(speedSettings, adaptiveTransition)
        val portraitEntry by navPreferences.directPortraitStoryEntry.collectAsState()
        val portraitNow by rememberUpdatedState(portraitEntry)
        val resolver = remember(root, handle) { DesktopOriginalRootVideoResolver(root,
            { portraitNow }, { prefs.homeSettings.value.cardTransitionEnabled },
            { preferencesPlatform.currentNetwork().profilePresent }, services.downloadItems, services.feedback) }
        val routes = remember(root) { DesktopOriginalRootRouteAssembly(root, physicalStack,
            DesktopOriginalRootRoutePlatform(services.navigationAdmission, services.beforeNavigationCommit,
                resolver::resolve, saveable::removeState, services.backAtHomeRoot),
            { prefs.navigation.value.orderedVisibleTabIds.map { it.lowercase() }.toSet() }) }
        val ownsStartupRoot = remember(root, handle, routes) {
            { handle.isActive() && root.isCurrentOwner() && routes.owns() }
        }
        SideEffect { handle.route.set(routes) }
        DisposableEffect(routes) { onDispose { handle.route.compareAndSet(routes, null); routes.close() } }
        val messagePages = rememberDesktopOriginalMessagePagesRoot(services.repository, services.community,
            routes, desktopDetailRenderEffectsSupported())
        SideEffect { if (handle.isActive() && messagePages.isOwned()) handle.messagePages.set(messagePages) else messagePages.close() }
        val spacePages = remember(services.repository, services.community, routes) {
            DesktopOriginalSpacePagesRoot(services.repository, services.community, routes,
                services.community.originalSpaceTransport)
        }
        SideEffect { if (handle.isActive()) handle.spacePages.set(spacePages) else spacePages.close() }
        LaunchedEffect(spacePages, routes) { snapshotFlow { physicalStack.toList() }.collect { spacePages.prune() } }
        DisposableEffect(spacePages) { onDispose {
            // Leave the last owner in the handle until its existing account/
            // restore/shutdown drain has awaited it. Retirement cancels now.
            spacePages.close()
        } }
        val personalLists = remember(root, services.library) { DesktopPersonalListsRoot(root.entry.gate,
            services.repository, services.runtime.store, services.library,
            services.community.searchPreferences::isPrivacyModeEnabledSync, services.feedback, haze) }
        SideEffect { handle.personalLists.set(personalLists); personalLists.prune(physicalStack.toList()) }
        DisposableEffect(personalLists) { onDispose {
            handle.personalLists.compareAndSet(personalLists, null); personalLists.close()
        } }
        val profile = remember(root, windowBinding) { windowBinding.profile(root, services.profileAccounts(root.entry.gate)) }
        val originalSearch = remember(root, routes, windowBinding,personalLists) { windowBinding.searchRoot(root,routes,personalLists.preferences) }
        SideEffect { originalSearch.prune() }
        DisposableEffect(originalSearch) { onDispose { originalSearch.close() } }
        var activeDestination by remember(root) { mutableStateOf<BiliPaiNavKey>(BiliPaiNavKey.Home) }
        var dynamicUnreadCount by remember(root) { mutableIntStateOf(0) }
        val navigationPreferences by prefs.navigation.collectAsState()
        val dynamicUnreadPollingEnabled = navigationPreferences.orderedVisibleTabIds.any { it.equals("DYNAMIC",true) }
        LaunchedEffect(root, activeDestination, dynamicUnreadPollingEnabled) {
            // Original AppNavigation's lightweight 60-second polling branch, using the SAME
            // timeline pagination baseline and owner-bound DynamicApi (never advance it here).
            if (!dynamicUnreadPollingEnabled || activeDestination == BiliPaiNavKey.Dynamic) {
                dynamicUnreadCount = 0
                return@LaunchedEffect
            }
            val api = services.repository.ownedHomeService(com.android.purebilibili.core.network.DynamicApi::class.java, "https://api.bilibili.com/",
                root.capturedEpoch, root::isCurrentOwner)
            while (isActive && routes.owns()) {
                try {
                    val response = api.getDynamicUpdateCount(type="all",updateBaseline=services.dynamicUnreadBaseline())
                    ensureActive()
                    if (response.code == 0 && response.data != null)
                        root.entry.gate.commit { dynamicUnreadCount = requireNotNull(response.data).update_num.coerceAtLeast(0) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Original onSuccess-only polling preserves the prior count. */ }
                delay(60_000L)
            }
        }
        val dynamicScroll = remember(root) { Channel<DynamicScrollRequest>(Channel.CONFLATED) }
        DisposableEffect(dynamicScroll) { onDispose { dynamicScroll.close() } }
        var accountSwitcherShown by remember(root) { mutableStateOf(false) }
        val sidebarAccountSwitcher: (() -> Unit)? = if (prefs.navigation.value.sidebarAccountSwitcherEnabled)
            ({ if (routes.owns()) { profile.viewModel.refreshSavedAccounts(); accountSwitcherShown = true } }) else null
        val savedAccounts by profile.viewModel.accounts.collectAsState()
        val activeAccountMid by profile.viewModel.activeAccountMid.collectAsState()
        val playbackAccountMid by profile.viewModel.playbackAccountMid.collectAsState()
        if (accountSwitcherShown) AccountSwitchDialog(savedAccounts, activeAccountMid, playbackAccountMid,
            onDismiss = { accountSwitcherShown = false },
            onAddAccount = { accountSwitcherShown = false; routes.push(BiliPaiNavKey.Login) },
            onSwitch = { mid -> profile.viewModel.switchAccount(mid,
                onSuccess = { accountSwitcherShown = false; accountRefresh++ }, onFailure = services.feedback) },
            onSetPlayback = { mid -> profile.viewModel.setPlaybackAccount(mid,
                onSuccess = { accountRefresh++ }, onFailure = services.feedback) },
            onRemove = { mid -> profile.viewModel.removeStoredAccount(mid,
                onSuccess = { accountRefresh++ }, onFailure = services.feedback) })
        val owner = remember(root) { DesktopOriginalLinkedDockOwner(scrollOffset, feedScrolling, linkedPhase,
            { linkedPhase.value = it }, routes::owns) }
        val audio = remember(root, services.nowPlayingBinding) { services.nowPlayingBinding(root) }
        val navigationAudio = audio.observeSnapshot()
        val audioNavigation = {
            DesktopOriginalNowPlayingNavigation(routes.currentKey.toLegacyRoute(), false,
                com.android.purebilibili.core.ui.transition.NowPlayingBarHandoffState.Idle, false,
                { item ->
                    val position = captureDesktopReadyNowPlayingPositionMs(audio, navigationAudio, item,
                        routes::owns) { expected -> services.nowPlayingPositionMs(root, expected) }
                    if (position != null) {
                        val song = com.bilipai.desktop.audio.nativeMusicSourceForListenItem(item)
                            as? com.android.purebilibili.feature.audio.player.MusicPlaybackSource.AudioSong
                        if (song != null) routes.push(BiliPaiNavKey.MusicDetail(song.sid))
                        else routes.push(BiliPaiNavKey.AudioMode(item.bvid, item.cid, position))
                    }
                },
                { item, source ->
                    val position = captureDesktopReadyNowPlayingPositionMs(audio, navigationAudio, item,
                        routes::owns) { expected -> services.nowPlayingPositionMs(root, expected) }
                    if (position != null) {
                        val song = com.bilipai.desktop.audio.nativeMusicSourceForListenItem(item)
                            as? com.android.purebilibili.feature.audio.player.MusicPlaybackSource.AudioSong
                        if (song != null) routes.push(BiliPaiNavKey.MusicDetail(song.sid))
                        else routes.video(BiliPaiNavKey.VideoDetail(item.bvid, item.cid, item.cover,
                            resumePositionMs = position, sourceRoute = source))
                    }
                },
                services.dismissNowPlayingBar)
        }
        val actions = DesktopOriginalFrostedNavigationActions({ item -> routes.push(bottomPagerNavKeyForItem(item)) },
            { homeScroll.trySend(HomeScrollRequest.SCROLL_TO_TOP_AND_REFRESH) }, { if (routes.owns()) dynamicScroll.trySend(DynamicScrollRequest.SCROLL_TO_TOP_OR_REFRESH) },
            { searchLaunch++
                val key=BiliPaiNavKey.Search(openId=DesktopHomeClock.elapsedRealtime())
                if(routes.push(key))originalSearch.bottomBarEntry(key,searchLaunch)
            },
            { query -> routes.push(BiliPaiNavKey.Search(query)) },
            { target -> dispatchDesktopReadyNativeTarget(root, routes, target) },
            { searchLaunch = 0 }, { scope.launch { prefs.setTabletUseSidebar(!prefs.navigation.value.tabletUseSidebar) } },
            personalLists.historySearchChannel, services.favoriteSearch, personalLists.watchLaterSearchChannel)
        val chromeBindings = DesktopOriginalRootChromeBindings(owner, navPreferences, scope, actions, audio,
            services.nowPlayingVisibility, audioNavigation, { 0.dp }, { dynamicUnreadCount }, { searchLaunch },
            { prefs.homeSettings.value.cardTransitionEnabled }, sidebarAccountSwitcher, { sourceReady = it })
        val pages = DesktopOriginalRootPageBindings(windowBinding.window, scrollOffset, feedScrolling,
            homeScroll, { bottomVisible }, { bottomPadding }, { bottomVisible = it },
            clock, services.isPipActiveOrPending, { sourceReady }, { liveScrollId }, { 0.dp }, profile, { accountRefresh }, profileScroll,
            services.logout, sidebarAccountSwitcher, { accountRefresh++ }, { accountRefresh++ })
        val environment = remember(root, navigation) { DesktopNavigationHostEnvironment(services.runtime.context,
            navigation, navigation, navigation, navigation, { null /* physical monitor corner API unavailable */ },
            { handle.isActive() && routes.owns() }) }
        val programmaticBack = remember(navigation) { BiliPaiProgrammaticBackDispatcher() }
        if (handle.imageTrim == null) SideEffect { handle.imageTrim = DesktopApplicationImageCacheTrim(
            applicationImages.imageLoader, resources.background, scope, palette,
            services.isPipActiveOrPending, services.canTrimImages) }
        val originalVideoWindow = remember(root, resources, prefs, routes, configuration, systemWallpaperChrome) {
            DesktopOriginalVideoRootWindowEnvironment(root, services.actualWindow, scope, resources,
                preferencesPlatform, configuration, systemWallpaperChrome, haze, prefs, services.appearance,
                services.repository, services.runtime, navigation, routes, services.imageLocations, services.imageLifetime,
                services.isPipActiveOrPending, { routes.currentKey })
        }
        DisposableEffect(originalVideoWindow) {
            services.originalVideoWindowReady(originalVideoWindow)
            onDispose { services.originalVideoWindowRetired(originalVideoWindow) }
        }
        CompositionLocalProvider(LocalDesktopOriginalVideoRootWindowEnvironment provides originalVideoWindow,
            LocalDesktopOriginalSearchRoot provides originalSearch,
            LocalVideoSharedTransitionSpeedSettings provides speedSettings,
            LocalVideoTransitionAdaptiveInfo provides adaptiveTransition,
            LocalDesktopRootDynamicScroll provides dynamicScroll) {
        var returnPrefetched by remember(routes.currentKey) { mutableStateOf(false) }
        val prepareReturn = {
            if (routes.owns() && !returnPrefetched) {
                returnPrefetched = true
                val candidates = resolveHomeCoverReturnPrefetchCandidates(HomeCoverReturnPrefetchRegistry.snapshot(),
                    (routes.currentKey as? BiliPaiNavKey.VideoDetail)?.bvid)
                // Exact original prefetch request body, with the already configured Windows singleton.
                candidates.forEach { entry -> if (routes.owns()) applicationImages.imageLoader.enqueue(
                    ImageRequest.Builder(platformContext).data(entry.url).memoryCacheKey(entry.cacheKey)
                        .diskCacheKey(entry.cacheKey).build()) }
            }
            root.returns.prepareReturnBeforeBack(routes.currentKey, routes.previousKey)
        }
        services.originalVideoWindowContent(originalVideoWindow) {
        DesktopHomeWindowGlobals(resources, sourceReady, { url, size, count -> root.ErrorAnimation(url, size, count) }) {
            DesktopOriginalRootStack(routes, environment, pages, rootThemeColor.luminance() > .5f,
                reduceMotion, transitionDuration, programmaticBack,
                false, prepareReturn,
                { root.returns.onRelatedReturnExposure(currentHome.cardTransitionEnabled,
                    resolveVideoCardTransitionExposure(clock.phase,
                        clock.settleState == VideoCardTransitionSettleState.InteractiveSeek,
                        clock.settleState == VideoCardTransitionSettleState.CancelRestore || clock.gestureRestoreInProgress)) },
                Modifier.fillMaxSize(), chromeBindings, { active -> activeDestination = active; services.activeDestinationChanged(active) }, saveable,
                onRootContentFrame = { if (ownsStartupRoot()) onRootContentFrame(ownsStartupRoot) },
                onActivePageFrame = { key, hosted ->
                    if (ownsStartupRoot()) DesktopOriginalRootValidationTap.frame(key, hosted, handle, routes)
                },
                leafContent = { key, commands, active, hosted -> leaf(key, commands, active, hosted, personalLists, root.environment.settings, messagePages, spacePages) })
        }
        }
        }
    }
}
