package com.bilipai.desktop

import com.bilipai.desktop.appearance.DesktopAppearanceSettings
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.appearance.DesktopStrings
import com.bilipai.desktop.appearance.LocalDesktopStrings
import com.bilipai.desktop.appearance.WindowsTextClipboard
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.list.HistoryNavigationKind
import com.android.purebilibili.feature.list.resolveHistoryNavigationKind
import com.android.purebilibili.feature.list.resolveHistoryResumePositionMs
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.space.SpaceWatchProgress
import com.android.purebilibili.feature.space.SpaceExternalPlaylist
import com.android.purebilibili.feature.bangumi.policy.parseCourseNavigation
import com.android.purebilibili.navigation.navigateOriginalDynamicCollection
import com.android.purebilibili.navigation.navigateOriginalDynamicCourse
import com.android.purebilibili.core.plugin.skin.LocalUiSkinState
import com.android.purebilibili.core.plugin.skin.UiSkinSurface
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.audio.ListenAudioState
import com.bilipai.desktop.audio.ListenAudioStore
import com.bilipai.desktop.audio.DesktopMusicVideoTarget
import com.bilipai.desktop.audio.DesktopBgmMusicTarget
import com.bilipai.desktop.audio.nativeMusicSourceForListenItem
import com.bilipai.desktop.audio.nativeMusicVideoReturnCard
import com.bilipai.desktop.data.*
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMetadata
import com.bilipai.desktop.download.DesktopDownloadNotifications
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.applyLegacyPreferenceChanges
import com.bilipai.desktop.player.DesktopVideoEnhancementSession
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerPreferencesStore
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.player.DesktopAutomaticSubtitles
import com.bilipai.desktop.player.DesktopAutomaticSubtitleState
import com.bilipai.desktop.player.RepositoryAutomaticSubtitleDataSource
import com.bilipai.desktop.player.MpvAutomaticSubtitlePlayer
import com.bilipai.desktop.player.DesktopPlayerPreferencesWriter
import com.bilipai.desktop.player.PictureInPictureController
import com.bilipai.desktop.player.WindowsMediaSession
import com.bilipai.desktop.player.WindowsMediaCommand
import com.bilipai.desktop.player.WindowsMediaSnapshot
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.diagnostics.*
import com.android.purebilibili.core.store.SearchHintSettingsStore
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.bilipai.desktop.plugins.DesktopEyePaint
import com.bilipai.desktop.backup.DesktopBackupCoordinator
import com.bilipai.desktop.backup.DesktopBackupStore
import com.bilipai.desktop.cast.DesktopCastController
import com.bilipai.desktop.cast.DesktopCastMediaResolver
import com.bilipai.desktop.cast.DesktopCastDialog
import com.bilipai.desktop.cast.DesktopGoogleCastDialog
import com.bilipai.desktop.cast.DesktopCastProxySessions
import com.android.purebilibili.feature.cast.LocalProxyServer
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect

private enum class DesktopSection(val label: String, val symbol: String) {
    HOME("推荐", "⌂"), POPULAR("热门", "◉"), REGION("分区", "▦"), RANKING("排行榜", "↗"), PRECIOUS("入站必刷", "★"), WEEKLY("每周必看", "▤"),
    DYNAMIC("动态", "▤"), LIVE("直播", "◉"), BANGUMI("番剧影视", "▷"), PLUGINS("插件", "◇"),
    CLOUD_HISTORY("云端历史", "◷"), CLOUD_FAVORITES("云端收藏", "♥"), WATCH_LATER("稍后再看", "▣"),
    FOLLOWINGS("我的关注", "♧"), LIKED("赞过的视频", "♥"), LISTEN("听视频", "♫"), DOWNLOADS("下载与离线", "↓"), MESSAGES("消息", "✉"),
    HISTORY("本地历史", "◷"), FAVORITES("本地收藏", "♡"), SEARCH("搜索", "⌕"), USER("UP 主空间", "♧"),
    ARTICLE("专栏", "▤"), NOTES("视频笔记", "✎"), COLLECTION("合集与系列", "▣"),
    JS_CONTENT("JS 插件内容", "◇"), EXTERNAL_MEDIA("外部媒体", "▷"), APPEARANCE("外观设置", "◐"), MUSIC("音乐详情", "♫"), BGM("背景音乐详情", "♫"),
    STORY("竖屏播放", "▯"), TOPIC("话题", "#"), SETTINGS("设置", "⚙"), UNSUPPORTED("待接入页面", "…")
}

private fun DesktopSection.localizedLabel(strings: DesktopStrings): String = when (this) {
    DesktopSection.HOME -> strings["bottom_nav_home"]
    DesktopSection.POPULAR -> strings["home_category_popular"]
    DesktopSection.RANKING -> strings["home_popular_subcategory_ranking"]
    DesktopSection.PRECIOUS -> strings["home_popular_subcategory_precious"]
    DesktopSection.WEEKLY -> strings["home_popular_subcategory_weekly"]
    DesktopSection.DYNAMIC -> strings["bottom_nav_dynamic"]
    DesktopSection.LIVE -> strings["bottom_nav_live"]
    DesktopSection.CLOUD_HISTORY -> strings["bottom_nav_history"]
    DesktopSection.CLOUD_FAVORITES -> strings["bottom_nav_favorite"]
    DesktopSection.WATCH_LATER -> strings["bottom_nav_watch_later"]
    DesktopSection.FOLLOWINGS -> strings["home_category_follow"]
    DesktopSection.LISTEN -> strings["bottom_nav_listen_video"]
    DesktopSection.SEARCH -> strings["common_search"]
    DesktopSection.APPEARANCE -> strings["appearance_settings_title"]
    else -> label
}

@Composable
internal fun DesktopApp(repository: DesktopRepository, player: MpvPlayer?, playerError: String?, initialVideo: String?,
    onExit: () -> Unit, onToggleFullscreen: () -> Unit, hostWindow: java.awt.Window? = null,
    registerShutdown: ((suspend () -> Unit) -> Unit)? = null, onRestart: (() -> Unit)? = null,
    applicationPluginStore: DesktopPluginStore? = null, isClosing: () -> Boolean = { false },
    diagnosticLifecycle: DesktopDiagnosticLifecycle? = null, diagnosticStartupError: String? = null,
    danmakuPresentation: DesktopDanmakuPresentationBinding,
    isFullscreen: () -> Boolean, setFullscreen: (Boolean) -> Unit,
    onRootContentFrame: ((() -> Boolean) -> Unit) = {}) {
    val applicationImages = LocalDesktopApplicationImageLoader.current
    val pluginStore = remember(applicationPluginStore) {
        (applicationPluginStore ?: DesktopPluginStore(DesktopLibrary.directoryForAccount(null))).also { store ->
            com.android.purebilibili.core.store.NetworkProxyStore.init(com.bilipai.desktop.plugins.DesktopPluginContext(store))
        }
    }
    val blockedUps = remember(pluginStore) {
        DesktopBlockedUpStore(com.bilipai.desktop.plugins.DesktopPluginContext(pluginStore))
    }
    val rootClosing = remember(repository, pluginStore) { java.util.concurrent.atomic.AtomicBoolean() }
    val latestIsClosing by rememberUpdatedState(isClosing)
    val epoch by repository.sessionEpochFlow.collectAsState()
    val startupGuard = remember(repository, pluginStore, blockedUps) {
        DesktopDiscoveryStorageGuard(
            factory = { openDesktopDiscoveryStorage(repository) { DesktopDiscoveryPreferences(pluginStore.root, blockedUps) } },
            sessionEpoch = { repository.sessionEpoch },
            stillOwned = { !rootClosing.get() && !latestIsClosing() },
        )
    }
    val closeDiscoveryStorage = remember(startupGuard, rootClosing) {
        { rootClosing.set(true); startupGuard.close() }
    }
    val startupAppearance = remember(pluginStore) { DesktopThemePrefs(pluginStore) }
    val startupTheme by startupAppearance.settings.collectAsState(startupAppearance.initialSettings())
    // The startup failure branch has no Runtime; retire its global migration writer before restart/exit.
    DisposableEffect(startupGuard, diagnosticLifecycle) {
        registerShutdown?.invoke {
            closeDiscoveryStorage()
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { applicationImages.close() }
                diagnosticLifecycle?.shutdownForRestore()
                withContext(Dispatchers.IO) { pluginStore.freezeWrites() }
            }
        }
        onDispose { startupGuard.close() }
    }
    DesktopDiscoveryStorageBoundary(startupGuard, epoch, onRestart, Modifier.fillMaxSize(),
        errorTheme = { content -> DesktopAppearanceTheme(startupTheme) {
            com.android.purebilibili.core.ui.components.AppSurface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    diagnosticStartupError?.let { com.android.purebilibili.core.ui.components.AppText(it, Modifier.padding(12.dp)) }
                    Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                }
            }
        } }) { discovery ->
        DesktopReadyApp(repository, player, playerError, initialVideo, onExit, onToggleFullscreen, hostWindow,
            registerShutdown, onRestart, pluginStore, discovery, closeDiscoveryStorage,
            isClosing = { rootClosing.get() || latestIsClosing() },
            diagnosticLifecycle = diagnosticLifecycle, diagnosticStartupError = diagnosticStartupError,
            danmakuPresentation = danmakuPresentation, isFullscreen = isFullscreen, setFullscreen = setFullscreen,
            onRootContentFrame = onRootContentFrame)
    }
    // Startup remains unobstructed. Existing diagnostic snapshots are available
    // through the user-opened diagnostic settings; no prompt marker is cleared here.
}

@Composable
private fun DesktopReadyApp(repository: DesktopRepository, player: MpvPlayer?, playerError: String?, initialVideo: String?,
    onExit: () -> Unit, onToggleFullscreen: () -> Unit, hostWindow: java.awt.Window?,
    registerShutdown: ((suspend () -> Unit) -> Unit)?, onRestart: (() -> Unit)?,
    pluginStore: DesktopPluginStore, discovery: DesktopDiscoveryRepository,
    closeDiscoveryStorage: () -> Unit, isClosing: () -> Boolean,
    diagnosticLifecycle: DesktopDiagnosticLifecycle?, diagnosticStartupError: String?,
    danmakuPresentation: DesktopDanmakuPresentationBinding,
    isFullscreen: () -> Boolean, setFullscreen: (Boolean) -> Unit,
    onRootContentFrame: ((() -> Boolean) -> Unit)) {
    val applicationImages = LocalDesktopApplicationImageLoader.current
    val diagnostics = diagnosticLifecycle?.diagnostics
    val account by repository.account.collectAsState()
    val sessionEpoch by repository.sessionEpochFlow.collectAsState()
    val settingsLibrary = remember { DesktopLibrary() }
    val library = remember(account?.mid) { if (account == null) settingsLibrary else DesktopLibrary(DesktopLibrary.directoryForAccount(account?.mid)) }
    val preferenceStore = remember { PlayerPreferencesStore() }
    val preferenceWriter = remember(preferenceStore) { DesktopPlayerPreferencesWriter(preferenceStore::save) }
    val preferenceWriteFailed by preferenceWriter.failed.collectAsState()
    val originalHardwareDecodePreferences = remember(pluginStore) { DesktopOriginalHardwareDecodePreferences(pluginStore) }
    val legacyHardwareDecodeFallback = remember(preferenceStore) { preferenceStore.read().hardwareDecodeEnabled }
    var preferences by remember { mutableStateOf(preferenceStore.read().let { it.copy(speed = it.preferredSpeed,
        hardwareDecodeEnabled = originalHardwareDecodePreferences.current(legacyHardwareDecodeFallback)) }) }
    val latestPreferences by rememberUpdatedState(preferences)
    val scope = rememberCoroutineScope()
    // App/window reference, independent of drawing, section and account MID.
    val homeRootRef = remember(repository, pluginStore) { java.util.concurrent.atomic.AtomicReference<DesktopReadyOriginalRootHandle?>() }
    val storageOwnerRef = remember(repository, pluginStore) { java.util.concurrent.atomic.AtomicReference<com.bilipai.desktop.settings.DesktopStorageSettingsOwner?>() }
    val ordinaryVideoRef = remember(repository, pluginStore) {
        java.util.concurrent.atomic.AtomicReference<DesktopOriginalVideoShellOwner?>()
    }
    val ordinaryVideoEvents = remember { kotlinx.coroutines.channels.Channel<DesktopOriginalVideoShellEvent>(64) }
    val ordinaryVideoResourcesRef = remember(repository, pluginStore) {
        java.util.concurrent.atomic.AtomicReference<DesktopOriginalVideoAppResources?>()
    }
    val ordinaryVideoResourceJob = remember { java.util.concurrent.atomic.AtomicReference<Job?>() }
    val ordinaryVideoResourcesRetired = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val originalWindowEnvironment = remember { java.util.concurrent.atomic.AtomicReference<DesktopOriginalVideoRootWindowEnvironment?>() }
    val originalNowPlaying = remember { java.util.concurrent.atomic.AtomicReference<Pair<DesktopHomeRetainedRoot,DesktopOriginalVideoRootNowPlayingBinding>?>() }
    val originalCaptureProtection = remember(hostWindow) { hostWindow?.let { actual ->
        DesktopWindowsVideoCaptureOwner(actual,{ !isClosing() }) { diagnostic ->
            diagnostics?.record("W","VideoCapture",diagnostic)
        }
    } }
    fun setOriginalFullscreen(desired: Boolean) {
        if (originalCaptureProtection?.orientationLocked == true && desired != isFullscreen()) {
            ordinaryVideoEvents.trySend(DesktopOriginalVideoShellEvent.Feedback("当前播放器已锁定展示方向，请先解除锁定"))
        } else setFullscreen(desired)
    }
    fun toggleOriginalFullscreen() = setOriginalFullscreen(!isFullscreen())
    suspend fun drainOriginalVideo() = withContext(NonCancellable) {
        ordinaryVideoResourcesRetired.set(true)
        ordinaryVideoRef.get()?.let { owner ->
            owner.closeAndJoin()
            ordinaryVideoRef.compareAndSet(owner, null)
        }
        ordinaryVideoResourceJob.getAndSet(null)?.cancelAndJoin()
        // Keep a failed drain reference available for a later bounded retry.
        ordinaryVideoResourcesRef.get()?.let { resources ->
            resources.closeAndJoin()
            ordinaryVideoResourcesRef.compareAndSet(resources, null)
        }
        withContext(Dispatchers.Main) { originalCaptureProtection?.close() }
    }
    val favoritesEntryRef = remember(repository, sessionEpoch) { java.util.concurrent.atomic.AtomicReference<DesktopFavoritesRootEntry?>() }
    val favoritesQueueRef = remember(repository, sessionEpoch) { java.util.concurrent.atomic.AtomicReference<DesktopFavoriteQueueBridge?>() }
    val social = remember(repository) { DesktopSocialRepository(repository) }
    val community = remember(repository, discovery.blockedUps) { DesktopCommunityRepository(repository, discovery.blockedUps) }
    val space = remember(repository) { DesktopSpaceRepository(repository) }
    val spaceContributions = remember(repository) { DesktopSpaceContributionsRepository(repository) }
    val storyTopic = remember(repository, discovery) { DesktopStoryTopicRepository(repository, discovery) }
    val browseMemory = remember(account?.mid, sessionEpoch) { DesktopBrowseMemory() }
    val homeCardProgress = remember(library, sessionEpoch) { desktopHomeCardProgressReader(library) }
    val appearance = remember(pluginStore) { DesktopThemePrefs(pluginStore, settingsLibrary.storedDark) }
    val themeSettings by appearance.settings.collectAsState(appearance.initialSettings())
    var appearanceReady by remember(appearance) { mutableStateOf(false) }
    var appearanceError by remember(appearance) { mutableStateOf<String?>(null) }
    var appearanceRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(appearance, appearanceRetry) {
        try { appearance.ensureMigrated(); appearanceReady = true; appearanceError = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { appearanceError = failure.message ?: failure.javaClass.simpleName }
    }
    val dynamicCache = remember(pluginStore, repository) { DesktopDynamicCache(repository.dynamicCacheSessionGuard, pluginStore) }
    DisposableEffect(dynamicCache) { onDispose { dynamicCache.stopAccepting() } }
    val latestDynamicIsClosing by rememberUpdatedState(isClosing)
    val imageSaveLifetime = remember(pluginStore) { DesktopImageSaveLifetime { latestDynamicIsClosing() } }
    val imageSavePreferences = remember(pluginStore, imageSaveLifetime) {
        DesktopImageSaveLocationPreferences(pluginStore, imageSaveLifetime::withCommit)
    }
    val imageSaveLocations = remember(imageSavePreferences, imageSaveLifetime) {
        DesktopImageSaveLocations(imageSavePreferences, imageSaveLifetime::isActive, imageSaveLifetime::withCommit)
    }
    DisposableEffect(imageSaveLifetime) { onDispose { imageSaveLifetime.close() } }
    val dynamicCardSession = remember(repository, sessionEpoch) {
        DesktopDynamicCardSession(repository, sessionEpoch, stillOwned = { !latestDynamicIsClosing() })
    }
    val dynamicEditor = rememberDesktopDynamicEditorRoot(repository, dynamicCardSession)
    val originalDanmakuBlocks = remember(pluginStore, dynamicEditor) {
        DesktopDanmakuBlockPreferences(pluginStore, dynamicEditor.operations::withOwnedEditorImageAdmission)
    }
    val originalDanmakuPreferences = remember(pluginStore, originalDanmakuBlocks, dynamicEditor) {
        DesktopOriginalDanmakuPreferences(pluginStore, originalDanmakuBlocks, dynamicEditor.operations::withOwnedEditorImageAdmission)
    }
    val originalDanmakuPresentation = danmakuPresentation.currentPresentation()
    val originalDanmakuScope = originalDanmakuPresentation.originalScope()
    val originalDanmakuSettings by remember(originalDanmakuPreferences, originalDanmakuScope) {
        originalDanmakuPreferences.getDanmakuSettings(originalDanmakuScope)
    }.collectAsState(originalDanmakuPreferences.currentSettings(originalDanmakuScope))
    val rendererDanmakuSettings = projectOriginalDanmakuRendererSettings(preferences.danmaku, originalDanmakuSettings)
    val latestRendererDanmakuSettings by rememberUpdatedState(rendererDanmakuSettings)

    val commentFraud = rememberDesktopCommentFraudRoot(repository, dynamicCardSession, DesktopLibrary.directoryForAccount(null))
    val dynamicCardRegistry = remember(repository, dynamicCache, sessionEpoch) {
        DesktopDynamicCardStateRegistry(repository.dynamicCacheSessionGuard, dynamicCache, sessionEpoch,
            stillOwned = dynamicCardSession::isOwned)
    }
    DisposableEffect(dynamicCardSession, dynamicCardRegistry) {
        onDispose { dynamicCardSession.close(); dynamicCardRegistry.close() }
    }
    LaunchedEffect(dynamicCardSession, dynamicCardRegistry) {
        dynamicCardSession.observeFollowStateChanges(dynamicCardRegistry)
    }
    val pluginRuntime = remember(pluginStore, diagnosticLifecycle, dynamicCache, imageSaveLifetime) {
        DesktopPluginRuntime(pluginStore, repository, community, discovery,
            beforeStoreFreeze = {
                storageOwnerRef.getAndSet(null)?.shutdownForRestore()
                drainOriginalVideo()
                homeRootRef.getAndSet(null)?.closeAndJoin()
                withContext(Dispatchers.IO) { applicationImages.close() }
                favoritesQueueRef.getAndSet(null)?.close()
                favoritesEntryRef.getAndSet(null)?.shutdownForRestore()
                imageSaveLifetime.close()
                dynamicCache.shutdownForRestore()
                diagnosticLifecycle?.shutdownForRestore()
            })
    }
    val globalPluginContext = pluginRuntime.context
    val detailedCommentTimeEnabled by remember(globalPluginContext) {
        com.android.purebilibili.core.store.DesktopOriginalReplySettings.getDetailedCommentTimeEnabled(globalPluginContext)
    }.collectAsState(initial = false)
    val dynamicTimelinePreferences = remember(pluginStore) {
        DesktopDynamicTimelinePreferences(globalPluginContext)
    }
    val homeCardPreferences = remember(pluginStore) {
        DesktopHomeCardPreferences(globalPluginContext)
    }
    val liquidTabSettings = remember(pluginStore) { DesktopLiquidTabSettings(pluginStore) }
    val liquidHomeSettings by liquidTabSettings.homeSettings.collectAsState(initial = null)
    val privacyBindings = remember(globalPluginContext, community.searchPreferences) {
        DesktopPrivacySectionBindings(globalPluginContext, community.searchPreferences)
    }
    val defaultSearchHintEnabled by remember(globalPluginContext) {
        SearchHintSettingsStore.isEnabled(globalPluginContext)
    }.collectAsState(initial = true)
    val settingsNavigator = remember { DesktopSettingsNavigator() }
    val settingsNavigation by settingsNavigator.state.collectAsState()
    val settingsSearchRepository = remember(globalPluginContext, community.searchPreferences) {
        DesktopSettingsSearchRepository(globalPluginContext, community.searchPreferences::isPrivacyModeEnabledSync)
    }
    // Controllers belong to navigation entries, so detail/back and nested search preserve queries.
    val settingsSearchControllers = remember(settingsSearchRepository) { mutableMapOf<Long, DesktopSettingsSearchController>() }
    val settingsSearchController = remember(settingsSearchRepository, settingsNavigation.searchEntryToken) {
        settingsSearchControllers.getOrPut(settingsNavigation.searchEntryToken ?: 0L) {
            DesktopSettingsSearchController(settingsSearchRepository)
        }
    }
    LaunchedEffect(settingsNavigation.stack) {
        val retainedTokens = settingsNavigation.stack.filterIsInstance<DesktopSettingsPage.Search>().map { it.entryToken }.toSet() + 0L
        settingsSearchControllers.keys.retainAll(retainedTokens)
    }
    val jsExecutionRevision by pluginRuntime.jsPlugins.host.executionRevision.collectAsState()
    val packages by pluginRuntime.packages.state.collectAsState()
    val cast = remember(pluginRuntime) { DesktopCastController(pluginRuntime.context, pluginRuntime.dlnaCast) }
    val castResolver = remember(repository, pluginRuntime) { DesktopCastMediaResolver(repository, pluginRuntime.context) }
    val casting by cast.playbackState.collectAsState()
    val castBusy by cast.isBusy.collectAsState()
    val googleCasting by pluginRuntime.googleCast.playbackState.collectAsState()
    val googleCastBusy by pluginRuntime.googleCast.isBusy.collectAsState()
    val castPreparations by DesktopCastProxySessions.pendingPreparations.collectAsState()
    val anyCasting = casting.isActive || googleCasting.isActive
    val anyCastBusy = castBusy || googleCastBusy || castPreparations > 0
    var castAccountEpoch by remember(repository) { mutableLongStateOf(repository.sessionEpoch) }
    val eyePlaybackActive = remember { MutableStateFlow(false) }
    var eyePaint by remember { mutableStateOf(DesktopEyePaint(0f, 0f)) }
    val storageSettingsContext=remember(globalPluginContext,imageSaveLifetime) {
        DesktopOriginalPlayerSettingsContext(globalPluginContext,imageSaveLifetime::isActive,imageSaveLifetime::withCommit)
    }
    val windowsDisplayScale = com.bilipai.desktop.appearance.LocalDesktopWindowsDisplayScale.current
    DisposableEffect(windowsDisplayScale, storageSettingsContext) {
        val registration = windowsDisplayScale.registerWindowContext(storageSettingsContext)
        onDispose { registration.close() }
    }
    val downloads = remember(repository,storageSettingsContext) { DesktopDownloadManager(repository) {
        val raw=(storageSettingsContext.pluginContext.store.preferences("settings")["download_path"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        storageSettingsContext.requireCurrent()
        resolveDesktopOriginalDownloadDestination(raw,DesktopDownloadManager.defaultDownloadRoot())
    } }
    val danmaku = remember(player, repository, hostWindow) { player?.let { DanmakuOverlay(it, renderPlatform = com.bilipai.desktop.danmaku.DesktopWindowsDanmakuRenderPlatform { requireNotNull(hostWindow) { "Danmaku requires the mounted Root window" } }, httpClient = repository.httpClient) } }
    val subtitleAssets = remember(repository) { DesktopSubtitleAssets(repository.httpClient) }
    val ordinaryVideo = remember(repository, pluginStore, scope) {
        DesktopOriginalVideoShellOwner(repository, pluginStore, scope,
            rootAlive = { !isClosing() }, rootAdmission = imageSaveLifetime::withCommit,
            metadata = { ordinaryVideoEvents.trySend(it) },
            feedback = { ordinaryVideoEvents.trySend(DesktopOriginalVideoShellEvent.Feedback(it)) })
    }
    SideEffect {
        if (!ordinaryVideoResourcesRetired.get() && !isClosing()) ordinaryVideoRef.set(ordinaryVideo)
    }
    val playback = ordinaryVideo.playback
    fun originalNowPlayingFor(root: DesktopHomeRetainedRoot, actualListen: ListenAudioSession?): DesktopOriginalVideoRootNowPlayingBinding {
        val old = originalNowPlaying.get()
        if (old?.first === root) return old.second
        val next = DesktopOriginalVideoRootNowPlayingBinding(root,repository,ordinaryVideo,
            checkNotNull(player),actualListen) { !isClosing() && root.isCurrentOwner() }
        originalNowPlaying.set(root to next)
        return next
    }
    var ordinaryVideoResources by remember(ordinaryVideo) { mutableStateOf<DesktopOriginalVideoAppResources?>(null) }
    var ordinaryVideoResourceError by remember(ordinaryVideo) { mutableStateOf<String?>(null) }
    LaunchedEffect(ordinaryVideo, globalPluginContext) {
        val creatorJob = checkNotNull(currentCoroutineContext()[Job])
        if (ordinaryVideoResourcesRetired.get() || isClosing()) return@LaunchedEffect
        check(ordinaryVideoResourceJob.compareAndSet(null, creatorJob))
        try {
            val resources = withContext(Dispatchers.IO) {
                com.bilipai.desktop.ui.ensureDesktopWindowsPlaybackDefaults(storageSettingsContext)
                val created = DesktopOriginalVideoAppResources.create(globalPluginContext, repository, scope,
                    DesktopLibrary.directoryForAccount(null).resolve("video-media-bytes")) {
                    ordinaryVideoEvents.trySend(DesktopOriginalVideoShellEvent.Feedback(it))
                }
                var published = false
                try {
                    creatorJob.ensureActive()
                    if (ordinaryVideoResourcesRetired.get() || isClosing())
                        throw CancellationException("Original app video resources retired during construction")
                    check(ordinaryVideoResourcesRef.compareAndSet(null, created))
                    published = true
                    created
                } finally { if (!published) created.closeAndJoin() }
            }
            ensureActive()
            ordinaryVideoResources = resources
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ordinaryVideoResourceError = failure.message ?: "视频缓存或进度存储初始化失败" }
        finally { ordinaryVideoResourceJob.compareAndSet(creatorJob, null) }
    }
    val beforeStoryAcquire = remember { java.util.concurrent.atomic.AtomicReference<() -> Unit>({}) }
    val storyHost = remember(playback, repository) { DesktopStoryPlaybackHost(ControllerStoryQueuePlayer(playback),
        { repository.sessionEpoch }, { beforeStoryAcquire.get().invoke() }) }
    val storyOwner by storyHost.owner.collectAsState()
    var systemTargetAudio by remember { mutableStateOf(false) }
    val audioPlayer = remember(player) { player?.let { MpvPlayer() } }
    val windowsAudioOutputController = remember(pluginStore, player, audioPlayer, scope) {
        com.bilipai.desktop.player.DesktopWindowsAudioOutputController(pluginStore, scope, player, audioPlayer)
    }
    DisposableEffect(windowsAudioOutputController) { onDispose { windowsAudioOutputController.close() } }
    val diagnosticObservers = remember(diagnosticLifecycle, player, audioPlayer) {
        listOfNotNull(
            player?.let { diagnosticLifecycle?.observePlayback(it.state, scope) },
            audioPlayer?.let { diagnosticLifecycle?.observePlayback(it.state, scope) },
        )
    }
    DisposableEffect(diagnosticObservers) { onDispose { diagnosticObservers.forEach { it.cancel() } } }
    val beforeListenAcquire = remember { java.util.concurrent.atomic.AtomicReference<() -> Unit>({}) }
    val listen = remember(repository, community, audioPlayer, playback, account?.mid, sessionEpoch) {
        audioPlayer?.let { ListenAudioSession(repository, community, it, preferences, onAcquirePlayback = { beforeListenAcquire.get().invoke(); systemTargetAudio = true; playback.pause() },
            store = ListenAudioStore(DesktopLibrary.directoryForAccount(account?.mid).resolve("listen-state.json"))) }
    }
    val emptyListenState = remember { MutableStateFlow(ListenAudioState()) }
    val listening by (listen?.state ?: emptyListenState).collectAsState()
    val playing by playback.state.collectAsState()
    val emptyNativeState = remember { MutableStateFlow(com.bilipai.desktop.player.PlayerState()) }
    val native by (player?.state ?: emptyNativeState).collectAsState()
    val commandVersion = player?.currentSourceVersion ?: 0L
    val commandDetails = playing.details
    val commandCid = commandDetails?.pages?.getOrNull(playing.currentPart)?.cid ?: 0L
    var originalDanmakuSettingsVisible by remember(sessionEpoch, commandCid, commandVersion) { mutableStateOf(false) }
    var originalDanmakuPoolVisible by remember(sessionEpoch, commandCid, commandVersion) { mutableStateOf(false) }
    var originalDanmakuEnabledChangeVersion by remember(sessionEpoch, commandCid, commandVersion) { mutableLongStateOf(0L) }
    val commandState = rememberDesktopVideoCommandVoteState(sessionEpoch, commandVersion,
        commandDetails?.bvid.orEmpty(), commandCid)
    var subtitleDialog by remember { mutableStateOf(false) }
    var subtitleDialogTarget by remember { mutableStateOf<DesktopSubtitleDialogTarget?>(null) }
    var section by remember { mutableStateOf(DesktopSection.HOME) }
    var physicalDestination by remember { mutableStateOf<BiliPaiNavKey>(BiliPaiNavKey.Home) }
    var rootNowPlayingDismissed by remember { mutableStateOf(false) }
    var initialVideoConsumed by remember { mutableStateOf(initialVideo==null) }
    val musicOverlay = remember { MutableStateFlow(false) }
    fun rootRoutes() = homeRootRef.get()?.route?.get()?.takeIf { it.owns() }
    var jsSettingsOrigin by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(false) }
    var favoritesEntry by remember(repository, sessionEpoch) { mutableStateOf<DesktopFavoritesRootEntry?>(null) }
    var favoritesAudioCover by remember(repository, sessionEpoch) { mutableStateOf(false) }
    val favoritesSaveable = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    DisposableEffect(favoritesEntryRef, favoritesQueueRef, favoritesSaveable) { onDispose {
        favoritesQueueRef.getAndSet(null)?.close()
        favoritesEntryRef.getAndSet(null)?.let { entry ->
            entry.savedStateKeys.forEach(favoritesSaveable::removeState)
            entry.close()
        }
    } }
    var lastMediaSection by remember { mutableStateOf<DesktopSection?>(null) }
    val pipPrevious = remember(playback) { java.util.concurrent.atomic.AtomicReference<() -> Unit>({ playback.previous() }) }
    val pipNext = remember(playback) { java.util.concurrent.atomic.AtomicReference<() -> Unit>({ playback.next() }) }
    val pipSeek = remember(player, playback) { java.util.concurrent.atomic.AtomicReference<(Double) -> Unit>({ playback.seekTo(it) }) }
    val pip = remember(player, playback) { player?.let { PictureInPictureController(it, onRestore = {
        showVideo = playback.state.value.details != null
        if (!showVideo) lastMediaSection?.let { section = it }
    }, onPrevious = { pipPrevious.get().invoke() }, onNext = { pipNext.get().invoke() },
        onSeekTo = { pipSeek.get().invoke(it) }) } }
    val emptyPipState = remember { MutableStateFlow(false) }
    val pipActive by (pip?.active ?: emptyPipState).collectAsState()
    val enhancementHostStarted = remember { MutableStateFlow(false) }
    val enhancement = remember(player, pluginRuntime) { player?.let { nativePlayer ->
        DesktopVideoEnhancementSession(nativePlayer, pluginRuntime.enhancementConfiguration.automaticEnabled,
            enhancementHostStarted, pip?.active ?: emptyPipState,
            pluginRuntime.enhancementConfiguration::setAutomaticEnabled,
            sessionEpoch = { repository.sessionEpoch })
    } }
    val emptyEnhancement = remember { MutableStateFlow(DesktopVideoEnhancementState()) }
    val enhancementState by (enhancement?.state ?: emptyEnhancement).collectAsState()
    var enhancementSettings by remember { mutableStateOf(false) }
    val emptyPipError = remember { MutableStateFlow<String?>(null) }
    val pipError by (pip?.error ?: emptyPipError).collectAsState()
    val retainedMedia = remember(player, repository, account?.mid) {
        DesktopRetainedMedia(scope, player) { storyHost.retire(); pip?.close(); playback.stop(); listen?.pause(); systemTargetAudio = false }
    }
    val enhancementSourceVersion = player?.currentSourceVersion ?: 0L
    val enhancementVideoIdentity = when {
        playback.currentCastSource(enhancementSourceVersion) != null -> playing.details?.bvid
        retainedMedia.bangumi.ownsNativeSource -> retainedMedia.bangumi.episode?.bvid?.takeIf { it.isNotBlank() }
        retainedMedia.offline.ownsNativeSource -> downloads.tasks.value.firstOrNull {
            it.id == retainedMedia.offline.current
        }?.item?.bvid?.takeIf { it.isNotBlank() }
        else -> null
    }
    SideEffect { enhancement?.bindVideoIdentity(enhancementVideoIdentity, enhancementSourceVersion) }
    SideEffect {
        beforeListenAcquire.set { storyHost.retire(); pip?.close(); retainedMedia.stop() }
        beforeStoryAcquire.set { pip?.close(); retainedMedia.stop(); listen?.pause(); systemTargetAudio = false }
        pipPrevious.set { val owner = retainedMedia.current; if (owner != null) owner.previous?.invoke() else playback.previous() }
        pipNext.set { val owner = retainedMedia.current; if (owner != null) owner.next?.invoke() else playback.next() }
        pipSeek.set { seconds ->
            val owner = retainedMedia.current
            if (owner != null) { if (owner.ownsNativeSource) player?.seekTo(seconds) } else playback.seekTo(seconds)
        }
    }
    val nativeTextShare = remember(hostWindow) {
        DesktopNativeTextShare(
            nativeDll = { java.nio.file.Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),
                "native", "windows-x64", "bilipai-diagnostic-share.dll") },
            expectedSha256 = DesktopNativeDiagnosticShareAssetHash.sha256,
            window = { hostWindow })
    }
    val storageOwner=remember(storageSettingsContext,repository,applicationImages,subtitleAssets,danmaku,player,nativeTextShare) {
        com.bilipai.desktop.settings.DesktopStorageSettingsOwner(storageSettingsContext,repository,applicationImages,
            {ordinaryVideoResourcesRef.get()?.mediaCache},subtitleAssets,danmaku,player,diagnostics,nativeTextShare,
            { (homeRootRef.get()?.retainer?.current()?.entry?.embeddedPages as? DesktopOriginalHomeEmbeddedAggregate)?.gallery?.shareFiles },
            { val captured=homeRootRef.get()?.retainer?.current()?.entry
              if(captured==null)null else ({homeRootRef.get()?.retainer?.current()?.entry===captured && captured.gate.owns()}) },
            imageSaveLifetime::isActive)
    }
    SideEffect { if(storageOwner.isActive())storageOwnerRef.compareAndSet(null,storageOwner) }
    var storageStartupReady by remember(storageOwner) { mutableStateOf(false) }
    LaunchedEffect(storageOwner,ordinaryVideoResources) {
        if(ordinaryVideoResources!=null) {
            try { storageOwner.clearAutomaticallyAtStartup() }
            catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive)throw cancelled
                ordinaryVideoEvents.trySend(DesktopOriginalVideoShellEvent.Feedback("启动缓存清理已因所有者变更中止，未清理文件已保留"))
            }
            catch(failure:Exception) { ordinaryVideoEvents.trySend(DesktopOriginalVideoShellEvent.Feedback(failure.message ?: "启动缓存清理失败，已保留未清理文件")) }
            finally { if(currentCoroutineContext().isActive && storageOwner.isActive())storageStartupReady=true }
        }
    }
    val rootTextShareBindings = remember(nativeTextShare) {
        DesktopTextShareBindings { title, text, owned ->
            nativeTextShare.share(title, text) { owned() && !isClosing() }
        }
    }
    val downloadNotification = remember(downloads, hostWindow) {
        java.util.concurrent.atomic.AtomicReference<DesktopDownloadNotifications?>()
    }
    val downloadNotificationRetired = remember(downloads, hostWindow) { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(downloadNotificationRetired) { onDispose {
        downloadNotificationRetired.set(true)
        downloadNotification.getAndSet(null)?.close()
    } }
    DisposableEffect(nativeTextShare) { onDispose { nativeTextShare.close() } }
    val backup = remember(playback, listen, pip, pluginRuntime, cast, retainedMedia, enhancement, diagnosticLifecycle, nativeTextShare) {
        DesktopBackupCoordinator(DesktopBackupStore(DesktopLibrary.directoryForAccount(null)), beforeRestore = {
            storageOwnerRef.getAndSet(null)?.shutdownForRestore()
            drainOriginalVideo()
            homeRootRef.getAndSet(null)?.closeAndJoin()
            withContext(Dispatchers.IO) { applicationImages.close() }
            closeDiscoveryStorage()
            favoritesQueueRef.getAndSet(null)?.close()
            favoritesEntryRef.getAndSet(null)?.shutdownForRestore()
            downloadNotificationRetired.set(true)
            downloadNotification.getAndSet(null)?.close()
            nativeTextShare.shutdown()
            withContext(Dispatchers.Main) { enhancement?.close(); pip?.close(); retainedMedia.close(); listen?.shutdownForRestore() }
            cast.quiesce()
            diagnosticLifecycle?.shutdownForRestore()
            community.searchPreferences.freezeWritesForRestore()
            pluginRuntime.shutdownForRestore()
            preferenceWriter.flushAndClose()
        }, afterRestore = { withContext(Dispatchers.Main) { onExit() } })
    }
    SideEffect {
        registerShutdown?.invoke {
            withContext(NonCancellable) {
                storageOwnerRef.getAndSet(null)?.shutdownForRestore()
                drainOriginalVideo()
                homeRootRef.getAndSet(null)?.closeAndJoin()
                withContext(Dispatchers.IO) { applicationImages.close() }
                closeDiscoveryStorage()
                favoritesQueueRef.getAndSet(null)?.close()
                favoritesEntryRef.getAndSet(null)?.shutdownForRestore()
                downloadNotificationRetired.set(true)
                downloadNotification.getAndSet(null)?.close()
                nativeTextShare.shutdown()
                withContext(Dispatchers.Main) { enhancement?.close(); pip?.close(); retainedMedia.close(); listen?.shutdownForRestore() }
                cast.quiesce()
                diagnosticLifecycle?.shutdownForRestore()
                community.searchPreferences.freezeWritesForRestore()
                pluginRuntime.shutdownForRestore()
                preferenceWriter.flushAndClose()
            }
        }
    }
    var mediaActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var playerFocused by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf(emptyList<VideoCard>()) }
    var feedLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rootFeedback = remember { SnackbarHostState() }
    LaunchedEffect(originalHardwareDecodePreferences, storageSettingsContext) {
        try { originalHardwareDecodePreferences.ensureMigrated(storageSettingsContext, legacyHardwareDecodeFallback) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (imageSaveLifetime.isActive()) error = failure.message ?: "硬件解码设置迁移失败" }
    }
    LaunchedEffect(originalDanmakuPreferences) {
        try { originalDanmakuPreferences.importLegacyWindowsDanmakuIfAbsent(preferenceStore.read().danmaku) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "旧弹幕设置迁移失败" }
    }
    val nativeTextShareError by nativeTextShare.error.collectAsState()
    LaunchedEffect(nativeTextShareError) { nativeTextShareError?.let { error = it } }
    var imageSaveMessage by remember { mutableStateOf<String?>(null) }
    var combinedBackupSettings by remember { mutableStateOf(false) }
    var showDiagnosticViewer by remember { mutableStateOf(false) }
    LaunchedEffect(preferenceWriteFailed) {
        if (preferenceWriteFailed) error = "播放设置保存失败，请检查本地存储空间和写入权限"
    }
    val clipboardFailure by WindowsTextClipboard.lastFailure.collectAsState()
    var loginDialog by remember { mutableStateOf(false) }
    var jsPluginId by remember { mutableStateOf("") }
    var jsSubscriptionReader by remember { mutableStateOf(false) }
    var castDialog by remember { mutableStateOf(false) }
    var dlnaDialog by remember { mutableStateOf(false) }
    var googleCastDialog by remember { mutableStateOf(false) }
    var userId by remember { mutableLongStateOf(0) }
    var dynamicRoute by remember { mutableStateOf<DesktopDynamicDetailRoute?>(null) }
    var topicId by remember { mutableLongStateOf(0) }
    var topicReturnSection by remember { mutableStateOf(DesktopSection.DYNAMIC) }
    var topicReturnDynamicRoute by remember { mutableStateOf<DesktopDynamicDetailRoute?>(null) }
    var topicStack by remember { mutableStateOf(emptyList<Long>()) }
    var storySeed by remember { mutableStateOf(DesktopStorySeed()) }
    var storyReturnSection by remember { mutableStateOf(DesktopSection.HOME) }
    var musicSource by remember(sessionEpoch) { mutableStateOf<MusicPlaybackSource?>(null) }
    var musicReturnSection by remember { mutableStateOf(DesktopSection.LISTEN) }
    var bgmRequest by remember(sessionEpoch) { mutableStateOf<DesktopBgmMusicTarget.Detail?>(null) }
    var bgmReturnSection by remember { mutableStateOf(DesktopSection.HOME) }
    var bgmReturnVideo by remember { mutableStateOf(false) }
    var musicReturnVideo by remember(sessionEpoch) { mutableStateOf(false) }
    var weeklyInitialNumber by remember(sessionEpoch) { mutableStateOf<Int?>(null) }
    var weeklyReturnSection by remember { mutableStateOf(DesktopSection.POPULAR) }
    var weeklyReturnVideo by remember(sessionEpoch) { mutableStateOf(false) }
    var musicStartPosition by remember(sessionEpoch) { mutableDoubleStateOf(0.0) }
    var articleId by remember { mutableLongStateOf(0) }
    var roomId by remember { mutableLongStateOf(0) }
    var seasonId by remember { mutableLongStateOf(0) }
    var episodeId by remember { mutableLongStateOf(0) }
    var seasonProgress by remember { mutableDoubleStateOf(0.0) }
    var isCourse by remember { mutableStateOf(false) }
    var seasonType by remember { mutableIntStateOf(1) }
    var collectionMid by remember { mutableLongStateOf(0) }
    var collectionId by remember { mutableLongStateOf(0) }
    var collectionType by remember { mutableStateOf("season") }
    var collectionTitle by remember { mutableStateOf("") }
    var noteVideo by remember { mutableStateOf<VideoDetails?>(null) }
    var favorite by remember(playing.details?.bvid) { mutableStateOf(playing.details?.let { library.isFavorite(it.bvid) } ?: false) }
    val updater = remember { DesktopUpdater() }
    val updateState by updater.state.collectAsState()
    var updatesDialog by remember { mutableStateOf(false) }
    var automaticUpdates by remember { mutableStateOf(settingsLibrary.automaticUpdates) }
    var updateJob by remember { mutableStateOf<Job?>(null) }
    var manuallyRequested by remember { mutableStateOf(false) }
    var activatingUpdate by remember { mutableStateOf(false) }
    // Store's nav invalidation callback only enqueues. Never start inline coroutine cleanup
    // or dispatcher cancellation while that original Store callback owns its monitor.
    val authenticationInvalidations = remember(repository) { Channel<Pair<Long,Long>>(Channel.UNLIMITED) }
    DisposableEffect(authenticationInvalidations) { onDispose { authenticationInvalidations.close() } }
    LaunchedEffect(repository, authenticationInvalidations) {
        for ((expectedEpoch, expectedMid) in authenticationInvalidations) {
            if (isClosing() || activatingUpdate || !scope.isActive) continue
            try {
                if(repository.logoutHomeAuthenticationInvalidated(expectedEpoch,expectedMid,
                    {!isClosing() && !activatingUpdate && scope.isActive}))
                    error="登录信息已失效，请重新登录"
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { error="登录失效状态无法保存，请重试" }
        }
    }
    var hostDisplayable by remember(hostWindow) { mutableStateOf(hostWindow?.isDisplayable == true) }
    var hostVisible by remember(hostWindow) { mutableStateOf(hostWindow?.isShowing == true) }
    DisposableEffect(hostWindow) {
        fun updateVisibility() {
            hostVisible = hostWindow?.isShowing == true && (hostWindow !is java.awt.Frame || hostWindow.extendedState and java.awt.Frame.ICONIFIED == 0)
        }
        val listener = java.awt.event.HierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L)
                hostDisplayable = hostWindow?.isDisplayable == true
            updateVisibility()
        }
        val windowStateListener = java.awt.event.WindowStateListener { updateVisibility() }
        hostWindow?.addHierarchyListener(listener)
        (hostWindow as? java.awt.Frame)?.addWindowStateListener(windowStateListener)
        hostDisplayable = hostWindow?.isDisplayable == true
        updateVisibility()
        onDispose { hostWindow?.removeHierarchyListener(listener); (hostWindow as? java.awt.Frame)?.removeWindowStateListener(windowStateListener) }
    }
    SideEffect { enhancementHostStarted.value = hostVisible }
    // The existing Root feedback port must reach a visible presentation surface.
    // Cancellation removes a replaced or hidden Snackbar; business ownership remains at each caller.
    LaunchedEffect(error, hostDisplayable, hostVisible, activatingUpdate) {
        val message = error?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (!hostDisplayable || !hostVisible || isClosing() || activatingUpdate) return@LaunchedEffect
        rootFeedback.showSnackbar(message)
        if (error == message) error = null
    }
    fun seekCurrentSystemMedia(seconds: Double, relative: Boolean = false) {
        if (!seconds.isFinite() || isClosing() || activatingUpdate) return
        val retained = if (systemTargetAudio) null else retainedMedia.current
        when {
            systemTargetAudio -> audioPlayer?.let { if (relative) it.seekBy(seconds) else it.seekTo(seconds) }
            retained != null -> player?.let {
                // A retained Offline/Live/PGC source has priority over stale ordinary detail metadata.
                if (retained.ownsNativeSource) { if (relative) it.seekBy(seconds) else it.seekTo(seconds) }
            }
            playback.state.value.details != null -> if (relative) playback.seekBy(seconds) else playback.seekTo(seconds)
            else -> Unit // No ordinary source exists; never command an unowned native source.
        }
    }
    val systemMedia = remember(hostWindow, hostDisplayable, player, playback, listen, retainedMedia) {
        hostWindow?.takeIf { hostDisplayable }?.let { owner -> WindowsMediaSession(owner, onCommand = { command ->
            val target = if (systemTargetAudio) audioPlayer else player
            when(command) {
                WindowsMediaCommand.PLAY -> if (systemTargetAudio) listen?.let { if (it.player.state.value.paused || !it.state.value.active) it.togglePause() }
                    else if (retainedMedia.current?.ownsNativeSource == true) target?.let { if (it.state.value.ended) it.replay() else it.setPaused(false) }
                    else ordinaryVideo.play()
                WindowsMediaCommand.PAUSE -> if (systemTargetAudio) listen?.pause()
                    else if (retainedMedia.current?.ownsNativeSource == true) target?.setPaused(true) else playback.pause()
                WindowsMediaCommand.STOP -> if (systemTargetAudio) listen?.pause() else {
                    pip?.close()
                    if (retainedMedia.current != null) { retainedMedia.stop(); target?.stop() } else playback.stop()
                }
                WindowsMediaCommand.NEXT -> if (systemTargetAudio) listen?.next() else { val owner = retainedMedia.current; if (owner != null) owner.next?.invoke() else playback.next() }
                WindowsMediaCommand.PREVIOUS -> if (systemTargetAudio) listen?.previous() else { val owner = retainedMedia.current; if (owner != null) owner.previous?.invoke() else playback.previous() }
                WindowsMediaCommand.FAST_FORWARD -> seekCurrentSystemMedia(10.0, relative = true)
                WindowsMediaCommand.REWIND -> seekCurrentSystemMedia(-10.0, relative = true)
            }
        }, onSeek = { seconds -> seekCurrentSystemMedia(seconds) }) }
    }

    LaunchedEffect(ordinaryVideoEvents, systemMedia) {
        for (event in ordinaryVideoEvents) when(event) {
            is DesktopOriginalVideoShellEvent.Feedback -> error = event.message
            is DesktopOriginalVideoShellEvent.Metadata -> {
                if (!systemTargetAudio && retainedMedia.current == null &&
                    ordinaryVideo.slot.currentAssembly() === event.owner && event.owner.owns() &&
                    event.owner.native.isCurrent(event.expected)) {
                    val readback = event.owner.section.nativePlayer.state.value
                    systemMedia?.update(WindowsMediaSnapshot(event.title, event.author,
                        event.expected.request.bvid, readback, readback.audioOnly,
                        playback.hasPrevious, playback.hasNext,
                        readback.loading || readback.durationSeconds > 0 || readback.ended))
                }
            }
        }
    }
    fun changePreferencesIntent(next: PlayerPreferences, forceSpeed: Boolean = false) {
        val previous = preferences
        val hardwareIntent = next.hardwareDecodeEnabled != previous.hardwareDecodeEnabled
        val speedIntent = forceSpeed || next.normalized().speed != previous.normalized().speed
        if (hardwareIntent || speedIntent) scope.launch(Dispatchers.IO) {
            try {
                DesktopOriginalPlaybackPreferenceOperation.run(storageSettingsContext, imageSaveLifetime::isActive) {
                    if (hardwareIntent) com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
                        .setHwDecode(storageSettingsContext, next.hardwareDecodeEnabled)
                    if (speedIntent) com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings
                        .setLastPlaybackSpeed(storageSettingsContext, next.normalized().speed.toFloat())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { withContext(Dispatchers.Main) {
                if (imageSaveLifetime.isActive()) error = failure.message ?: "播放设置保存失败"
            } }
        }
        // Unchanged legacy values carry no native intent and cannot overwrite newer
        // original player choices. Canonical hw decoding is projected independently.
        preferences = next.copy(danmaku = previous.danmaku,
            hardwareDecodeEnabled = originalHardwareDecodePreferences.current(legacyHardwareDecodeFallback)).normalized()
        val nativeChanges = com.bilipai.desktop.player.DesktopLegacyPlaybackPreferenceChanges.between(previous, preferences)
            .let { if (forceSpeed) it.copy(speed = true) else it }
        player?.applyLegacyPreferenceChanges(nativeChanges, preferences)
        listen?.updatePreferences(preferences)
        danmaku?.applySettings(latestRendererDanmakuSettings)
        playback.onPlaybackPreferencesChanged(previous, preferences, forceSpeed)
        val snapshot = preferences
        preferenceWriter.submit(snapshot)
    }
    fun changePreferences(next: PlayerPreferences) = changePreferencesIntent(next)
    fun toggleOriginalDanmaku() {
        val targetEpoch = repository.sessionEpoch
        val targetVersion = player?.currentSourceVersion
        val targetCid = commandCid
        val enabled = !originalDanmakuPreferences.currentSettings(danmakuPresentation.currentPresentation().originalScope()).enabled
        scope.launch {
            try {
                originalDanmakuPreferences.setDanmakuEnabled(enabled, danmakuPresentation.currentPresentation().originalScope())
                ensureActive()
                if (repository.sessionEpoch == targetEpoch && player?.currentSourceVersion == targetVersion && commandCid == targetCid)
                    originalDanmakuEnabledChangeVersion++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "弹幕显示设置保存失败" }
        }
    }
    fun checkpointForNavigation(): Boolean {
        if (playback.checkpoint()) return true
        error = playback.state.value.error ?: "播放记录保存失败，请检查本地隐私和存储设置"
        return false
    }
    fun retireFavoritesEntry() {
        favoritesQueueRef.getAndSet(null)?.close()
        (favoritesEntryRef.getAndSet(null) ?: favoritesEntry)?.let { entry ->
            entry.savedStateKeys.forEach(favoritesSaveable::removeState)
            entry.close()
        }
        favoritesEntry = null; favoritesAudioCover = false
    }
    fun ensureFavoritesEntry(): DesktopFavoritesRootEntry {
        favoritesEntryRef.get()?.takeIf { it.isOwned() }?.let { return it }
        retireFavoritesEntry()
        val capturedEpoch = repository.sessionEpoch
        val entry = DesktopFavoritesRootEntry(capturedEpoch, { repository.sessionEpoch }, scope,
            community.favoriteApi, community.favoriteSpaceApi, community.favoriteDynamicApi, community.favoriteBangumiApi,
            pluginStore, { repository.account.value?.mid }, { repository.requireCsrf() }, { error = it },
            rootAlive = { !isClosing() && !activatingUpdate })
        val queue = DesktopFavoriteQueueBridge(playback, listen, entry::isOwned,
            isSelectedTarget = { audio -> systemTargetAudio == audio },
            beforeOpen = { audio ->
                if (isClosing() || activatingUpdate || !entry.isOwned() || !checkpointForNavigation() || (audio && listen == null)) false
                else {
                    changePreferences(preferences.copy(playbackMode = com.bilipai.desktop.player.PlaybackMode.SEQUENTIAL))
                    storyHost.retire(); retainedMedia.stop()
                    if (audio) { playback.pause(); systemTargetAudio = true }
                    else { listen?.pause(); systemTargetAudio = false }
                    true
                }
            },
            revealVideo = {
                val item = playback.state.value.queue.getOrNull(playback.state.value.queueIndex)
                if (item != null) rootRoutes()?.video(BiliPaiNavKey.VideoDetail(item.bvid, item.preferredCid, item.cover,
                    sourceRoute = "favorite"))
            },
            revealAudio = {
                val item = listen?.state?.value?.current
                if (item != null) { favoritesAudioCover = true; rootRoutes()?.push(BiliPaiNavKey.AudioMode(item.bvid, item.cid,
                    ((listen.player.state.value.positionSeconds) * 1000L).toLong())) }
            })
        favoritesEntryRef.set(entry); favoritesQueueRef.set(queue); favoritesEntry = entry
        return entry
    }
    fun navigate(target: DesktopSection, commit: () -> Unit = {}): Boolean {
        val routes = rootRoutes() ?: return false
        if (activatingUpdate) return false
        commit()
        val key: BiliPaiNavKey = when (target) {
            DesktopSection.HOME -> BiliPaiNavKey.Home
            DesktopSection.POPULAR, DesktopSection.RANKING, DesktopSection.PRECIOUS -> {
                routes.root.entry.viewModel.switchPopularSubCategory(when(target) { DesktopSection.RANKING -> com.android.purebilibili.feature.home.PopularSubCategory.RANKING; DesktopSection.PRECIOUS -> com.android.purebilibili.feature.home.PopularSubCategory.PRECIOUS; else -> com.android.purebilibili.feature.home.PopularSubCategory.COMPREHENSIVE })
                routes.root.entry.viewModel.switchCategory(com.android.purebilibili.feature.home.HomeCategory.POPULAR); BiliPaiNavKey.Home
            }
            DesktopSection.REGION -> BiliPaiNavKey.Partition
            DesktopSection.WEEKLY -> BiliPaiNavKey.WeeklySeries(weeklyInitialNumber)
            DesktopSection.DYNAMIC -> dynamicRoute?.let { BiliPaiNavKey.DynamicDetail(it.dynamicId,it.rootReplyId,it.targetReplyId) } ?: BiliPaiNavKey.Dynamic
            DesktopSection.LIVE -> if(roomId>0) BiliPaiNavKey.Live(roomId=roomId.toString()) else BiliPaiNavKey.LiveList
            DesktopSection.BANGUMI -> if(seasonId>0||episodeId>0) BiliPaiNavKey.BangumiPlayer(seasonId,episodeId,(seasonProgress*1000).toLong(),isCourse) else BiliPaiNavKey.Bangumi(seasonType)
            DesktopSection.PLUGINS -> BiliPaiNavKey.PluginsSettings()
            DesktopSection.CLOUD_HISTORY, DesktopSection.HISTORY -> BiliPaiNavKey.History
            DesktopSection.CLOUD_FAVORITES, DesktopSection.FAVORITES -> BiliPaiNavKey.Favorite
            DesktopSection.WATCH_LATER -> BiliPaiNavKey.WatchLater
            DesktopSection.FOLLOWINGS -> BiliPaiNavKey.Following(account?.mid ?: 0)
            DesktopSection.LIKED -> BiliPaiNavKey.LikedVideos(account?.mid ?: 0)
            DesktopSection.LISTEN -> BiliPaiNavKey.ListenVideo
            DesktopSection.DOWNLOADS -> BiliPaiNavKey.DownloadList
            DesktopSection.MESSAGES -> BiliPaiNavKey.Inbox
            DesktopSection.SEARCH -> BiliPaiNavKey.Search(submitted)
            DesktopSection.USER -> BiliPaiNavKey.Space(userId)
            DesktopSection.ARTICLE -> BiliPaiNavKey.ArticleDetail(articleId)
            DesktopSection.UNSUPPORTED -> return false
            DesktopSection.NOTES -> { error="视频笔记的原版导航入口仍待接入"; return false }
            DesktopSection.COLLECTION -> BiliPaiNavKey.SeasonSeriesDetail(collectionType,collectionId,collectionMid,collectionTitle)
            DesktopSection.JS_CONTENT -> BiliPaiNavKey.JsPluginContent(jsPluginId)
            DesktopSection.EXTERNAL_MEDIA -> { error="外部媒体必须从原授权 launchId 进入"; return false }
            DesktopSection.APPEARANCE -> BiliPaiNavKey.AppearanceSettings
            DesktopSection.MUSIC -> when(val value=musicSource) {
                is MusicPlaybackSource.AudioSong -> BiliPaiNavKey.MusicDetail(value.sid)
                is MusicPlaybackSource.VideoAudio -> BiliPaiNavKey.NativeMusic(value.title,value.bvid,value.cid)
                null -> return false
            }
            DesktopSection.BGM -> bgmRequest?.let{BiliPaiNavKey.BgmDetail(it.musicId,it.aid,it.cid,it.showVideos)} ?: return false
            DesktopSection.STORY -> BiliPaiNavKey.Story(storySeed.bvid,storySeed.cid,storySeed.cover,storySeed.title)
            DesktopSection.TOPIC -> BiliPaiNavKey.TopicDetail(topicId)
            DesktopSection.SETTINGS -> BiliPaiNavKey.Settings
        }
        return routes.push(key)
    }
    fun openVideo(card: VideoCard) {
        rootRoutes()?.video(BiliPaiNavKey.VideoDetail(card.bvid,card.preferredCid,card.cover,
            resumePositionMs=(card.progressSeconds?.coerceAtLeast(0)?.toLong() ?: 0L)*1000L,
            sourceRoute=physicalDestination.toLegacyRoute()))
    }
    fun openQueue(videos: List<VideoCard>, selected: VideoCard) {
        if (activatingUpdate || videos.isEmpty() || !checkpointForNavigation()) return
        val index = videos.indexOfFirst { it.bvid == selected.bvid && it.preferredCid == selected.preferredCid }.takeIf { it >= 0 } ?: 0
        storyHost.retire(); retainedMedia.stop(); listen?.pause(); systemTargetAudio = false
        playback.openQueue(videos, index)
        rootRoutes()?.video(BiliPaiNavKey.VideoDetail(selected.bvid,selected.preferredCid,selected.cover,
            sourceRoute=physicalDestination.toLegacyRoute()))
    }
    fun openVideoHonorLink(url: String) {
        val target = com.android.purebilibili.core.util.BilibiliNavigationTargetParser.parse(url)
        if (target is com.android.purebilibili.core.util.BilibiliNavigationTarget.PopularFeed) {
            if (target.subCategoryKey == "weekly") navigate(DesktopSection.WEEKLY) {
                weeklyReturnSection = section; weeklyReturnVideo = showVideo; weeklyInitialNumber = target.weeklyNumber
            } else navigate(when (target.subCategoryKey) {
                "rank" -> DesktopSection.RANKING
                "all", "precious" -> DesktopSection.PRECIOUS
                else -> DesktopSection.POPULAR
            })
        } else runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.let { uri ->
            runCatching { java.awt.Desktop.getDesktop().browse(uri) }.onFailure { error = "无法打开荣誉链接" }
        }
    }
    fun closeWeeklySeries() { rootRoutes()?.back() }
    fun openUser(id: Long) { rootRoutes()?.push(BiliPaiNavKey.Space(id)) }
    fun openDynamicRoute(route: DesktopDynamicDetailRoute) { rootRoutes()?.push(BiliPaiNavKey.DynamicDetail(route.dynamicId,route.rootReplyId,route.targetReplyId)) }
    fun openDynamic(id: String) { openDynamicRoute(DesktopDynamicDetailRoute(id)) }
    fun openTopic(id: Long) { if(id>0) rootRoutes()?.push(BiliPaiNavKey.TopicDetail(id)) }
    fun openTopicKeyword(keyword: String) { if(keyword.isNotBlank()) rootRoutes()?.push(BiliPaiNavKey.Search(keyword)) }
    fun openStory(card: VideoCard? = null) { rootRoutes()?.push(BiliPaiNavKey.Story(card?.bvid.orEmpty(),card?.preferredCid ?: 0,card?.cover.orEmpty(),card?.title.orEmpty(),sourceRoute=physicalDestination.toLegacyRoute())) }
    fun openBgm(request: DesktopBgmMusicTarget.Detail) { rootRoutes()?.push(BiliPaiNavKey.BgmDetail(request.musicId,request.aid,request.cid,request.showVideos)) }
    fun closeBgm() { rootRoutes()?.back() }
    LaunchedEffect(sessionEpoch) {
        if (section == DesktopSection.BGM && bgmRequest == null) navigate(bgmReturnSection)
    }
    fun openMusicSource(source: MusicPlaybackSource, startPosition: Double = 0.0) {
        if (activatingUpdate || listen == null) return
        when (source) {
            is MusicPlaybackSource.AudioSong -> if (source.sid <= 0) return
            is MusicPlaybackSource.VideoAudio -> if (source.bvid.isBlank() || source.cid <= 0) return
        }
        navigate(DesktopSection.MUSIC) {
            if (section != DesktopSection.MUSIC) {
                musicReturnSection = section
                musicReturnVideo = showVideo
            }
            musicSource = source
            musicStartPosition = startPosition.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        }
    }
    fun openListenSpaceQueue(playlist: SpaceExternalPlaylist, mid: Long, history: List<VideoCard>) {
        if (activatingUpdate || listen == null) return
        val (items, index, progress) = desktopSpaceListenPlaybackQueue(playlist, mid, history) ?: return
        navigate(DesktopSection.LISTEN) {
            changePreferences(preferences.copy(playbackMode = com.bilipai.desktop.player.PlaybackMode.SEQUENTIAL))
            listen.playStartingAt(items, index, progress)
        }
    }
    fun openMusic(sid: Long) { if (sid > 0) openMusicSource(MusicPlaybackSource.AudioSong(sid)) }
    fun closeMusic() { rootRoutes()?.back() }
    LaunchedEffect(sessionEpoch) {
        if (section == DesktopSection.MUSIC && musicSource == null) navigate(DesktopSection.LISTEN)
    }
    fun openArticle(id: Long) { rootRoutes()?.push(BiliPaiNavKey.ArticleDetail(id)) }
    fun openNotes(info: VideoDetails) { navigate(DesktopSection.NOTES) { noteVideo = info } }
    fun openJsPlugin(id: String) { rootRoutes()?.push(BiliPaiNavKey.JsPluginContent(id)) }
    fun openJsMedia(launchId: String, revision: Long) {
        val host = pluginRuntime.jsPlugins.host
        if (activatingUpdate) { host.releaseExternalLaunch(launchId); return }
        if (!checkpointForNavigation()) { host.releaseExternalLaunch(launchId); return }
        storyHost.retire()
        val epoch = repository.sessionEpoch
        try {
            check(host.executionRevision.value == revision) { "插件播放授权已经变化，请重新打开内容" }
            retainedMedia.acquire(retainedMedia.external)
            retainedMedia.external.open(launchId, authorizationCurrent = {
                host.executionRevision.value == revision && repository.sessionEpoch == epoch
            }, releaseRequest = host::releaseExternalLaunch, host = host, overlay = danmaku,
                plugin = pluginRuntime.jsPlugins.state.value.plugins.firstOrNull {
                    it.installed.manifest.id == com.android.purebilibili.core.plugin.js.ExternalMediaLaunchStore.get(launchId)?.danmakuPluginId &&
                        it.authorizationMatches && it.installed.enabled
                }?.installed, expectedRevision = revision)
            rootRoutes()?.push(BiliPaiNavKey.ExternalMedia(launchId))
        } catch (failure: Exception) {
            host.releaseExternalLaunch(launchId)
            error = failure.message ?: "外部媒体无法播放"
        }
    }
    fun openLive(id: Long) { if(id>0) rootRoutes()?.push(BiliPaiNavKey.Live(roomId=id.toString())) }
    fun showSeason(id: Long, epId: Long = 0, course: Boolean = false, progress: Double = 0.0) {
        rootRoutes()?.push(BiliPaiNavKey.BangumiPlayer(id,epId,(progress.coerceAtLeast(0.0)*1000L).toLong(),course))
    }
    fun openBangumi(id: Long) { showSeason(id) }
    fun openCollection(mid: Long, id: Long, type: String) { rootRoutes()?.push(BiliPaiNavKey.SeasonSeriesDetail(type,id,mid)) }
    fun openDynamicWeb(url: String, title: String) {
        runCatching { java.net.URI(imageUrl(url)) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.let { uri ->
            runCatching { java.awt.Desktop.getDesktop().browse(uri) }.onFailure { error = it.message ?: "无法打开$title" }
        }
    }
    fun openResource(resource: PersonalResource) {
        when (resource) {
            is PersonalResource.History -> {
                val item = resource.item
                when (resolveHistoryNavigationKind(item)) {
                    HistoryNavigationKind.PGC -> showSeason(item.seasonId, item.epid, progress = resolveHistoryResumePositionMs(item) / 1000.0)
                    HistoryNavigationKind.CHEESE -> showSeason(item.seasonId, item.epid, true, resolveHistoryResumePositionMs(item) / 1000.0)
                    HistoryNavigationKind.LIVE -> openLive(item.roomId)
                    HistoryNavigationKind.ARTICLE -> openArticle(item.videoItem.id)
                    HistoryNavigationKind.VIDEO -> {
                        val video = item.videoItem
                        val lookup = video.bvid.ifBlank { if (video.id > 0) "av${video.id}" else "" }
                        if (lookup.isBlank()) error = "服务器没有提供此历史条目的可播放编号"
                        else openVideo(VideoCard(lookup, video.title, video.pic, video.owner.name, video.stat.view.toLong(), video.duration,
                            item.progress, item.cid, (item.page - 1).coerceAtLeast(0), authorMid = video.owner.mid))
                    }
                }
            }
            is PersonalResource.Favorite -> {
                val video = resource.item.toVideoItem()
                if (video.isCollectionResource) openCollection(video.collectionMid, video.collectionId, "favorite_season")
                else if (video.bvid.isNotBlank() || video.id > 0) openVideo(VideoCard(video.bvid.ifBlank { "av${video.id}" }, video.title,
                    video.pic, video.owner.name, video.stat.view.toLong(), video.duration, preferredCid = video.cid, authorMid = video.owner.mid))
                else error = "服务器没有提供此收藏资源的可用编号"
            }
            is PersonalResource.WatchLater -> {
                val item = resource.item
                val target = parseCourseNavigation(item.redirectUrl.orEmpty())
                if (target != null && (item.isPgc == true || item.isPugv == true)) showSeason(target.seasonId, target.epId, item.isPugv == true, (item.progress ?: 0).coerceAtLeast(0).toDouble())
                else if (!item.bvid.isNullOrBlank() || (item.aid ?: 0) > 0) openVideo(VideoCard(item.bvid.orEmpty().ifBlank { "av${item.aid}" },
                    item.title.orEmpty(), item.pic.orEmpty(), item.owner?.name.orEmpty(), item.stat?.view?.toLong() ?: 0, item.duration ?: 0,
                    item.progress, item.cid ?: 0))
                else error = "服务器没有提供此稍后再看条目的可用编号"
            }
        }
    }
    fun submitSearch() {
        val text = query.trim()
        if (text.isEmpty()) return
        Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE).find(text)?.value?.let { openVideo(VideoCard(it, "", "", "", 0, 0)); return }
        navigate(DesktopSection.SEARCH) { submitted = text }
    }
    suspend fun currentCastMedia(): com.bilipai.desktop.cast.DesktopCastMediaPublication {
        check(!systemTargetAudio) { "请先打开需要投屏的视频" }
        val owner = retainedMedia.current
        val nativeSource = player?.currentSourceSnapshot()
        val current = playback.state.value
        val epoch = repository.sessionEpoch
        val positionMs = ((player?.state?.value?.positionSeconds ?: 0.0) * 1000).toLong()
        val callerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val owned = {
            val latest = player?.currentSourceSnapshot()
            callerJob?.isCancelled != true && scope.isActive && !isClosing() && !activatingUpdate && epoch == repository.sessionEpoch &&
                nativeSource?.sourceVersion == latest?.sourceVersion && nativeSource?.source == latest?.source &&
                retainedMedia.current === owner && !systemTargetAudio &&
                (owner != null || playback.state.value.details?.bvid == current.details?.bvid && playback.state.value.currentPart == current.currentPart) &&
                (owner !== retainedMedia.external || retainedMedia.external.authorizationCurrent)
        }
        val resolvedVideo = if (owner == null) {
            val info = current.details ?: error("请先打开需要投屏的视频")
            checkNotNull(nativeSource) { "当前视频尚未发布可投屏的播放源" }
            playback.currentCastSource(nativeSource.sourceVersion) ?: error("当前视频播放源已经变化，请重新开始投屏")
        } else null
        val admittedSource = when (owner) {
            retainedMedia.bangumi -> retainedMedia.bangumi.playback?.source?.toNativePlayback() ?: error("剧集播放源已经变化")
            null -> checkNotNull(nativeSource).source
            else -> nativeSource?.source?.copy(primaryAccountEpoch = epoch) ?: error("当前播放源已经变化")
        }
        val frame = com.bilipai.desktop.cast.DesktopCastPublicationFrame(
            com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository, allowPrimaryAccountSource = true), admittedSource, owned, callerJob)
        return com.bilipai.desktop.cast.DesktopCastMediaPublication(frame) {
        val media = when (owner) {
            retainedMedia.external -> {
                check(retainedMedia.external.authorizationCurrent) { "插件播放授权已经变化，请重新打开内容" }
                val source = nativeSource?.source ?: error("请先打开需要投屏的媒体")
                val type = retainedMedia.external.request?.streams?.getOrNull(retainedMedia.external.selectedIndex)?.contentType ?: "video/mp4"
                castResolver.nativeSource(source, contentType = type, positionMs = positionMs)
            }
            retainedMedia.live -> {
                val source = nativeSource?.source ?: error("请先打开需要投屏的直播")
                val type = if (java.net.URI(source.videoUrl).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) "application/vnd.apple.mpegurl" else "video/x-flv"
                castResolver.nativeSource(source, creator = retainedMedia.live.room?.author.orEmpty(), contentType = type, positionMs = 0)
            }
            retainedMedia.bangumi -> {
                val source = retainedMedia.bangumi.playback?.source ?: error("请先打开需要投屏的剧集")
                castResolver.existingSource(source, nativeSource?.source ?: error("剧集播放源已经变化"),
                    durationMs = (retainedMedia.bangumi.episode?.durationSeconds ?: 0) * 1000, positionMs = positionMs)
            }
            retainedMedia.offline -> error("本地离线文件暂不支持局域网投屏，请打开在线视频")
            else -> {
                val info = current.details ?: error("请先打开需要投屏的视频")
                val source = resolvedVideo!!
                castResolver.video(info, current.currentPart, source, positionMs, nativeSource = nativeSource?.source)
            }
        }
        val latestSource = player?.currentSourceSnapshot()
        check(epoch == repository.sessionEpoch && nativeSource?.sourceVersion == latestSource?.sourceVersion &&
            nativeSource?.source == latestSource?.source && retainedMedia.current === owner && !systemTargetAudio &&
            (owner != null || playback.state.value.details?.bvid == current.details?.bvid && playback.state.value.currentPart == current.currentPart) &&
            (owner !== retainedMedia.external || retainedMedia.external.authorizationCurrent)) {
            "当前播放视频已切换，请重新开始投屏"
        }
        media
        }
    }
    val castMediaFactory: suspend () -> com.bilipai.desktop.cast.DesktopCastMediaPublication? = { currentCastMedia() }
    fun prepareUpdate(update: WindowsUpdate, manual: Boolean) {
        if (activatingUpdate || updateJob?.isActive == true) return
        if (manual) manuallyRequested = true
        updateJob = scope.launch(start = CoroutineStart.LAZY) {
            try { if (updater.prepareUpdate(update) == null) manuallyRequested = false }
            finally { updateJob = null }
        }.also { it.start() }
    }

    DisposableEffect(ordinaryVideo) { onDispose { ordinaryVideo.retire() } }
    DisposableEffect(preferenceWriter) { onDispose { preferenceWriter.close() } }
    DisposableEffect(storyHost) { onDispose { storyHost.close() } }
    DisposableEffect(pluginRuntime) { onDispose { pluginRuntime.close() } }
    DisposableEffect(enhancement) { onDispose { enhancement?.close() } }
    DisposableEffect(downloads) { onDispose {
        downloadNotificationRetired.set(true)
        downloadNotification.getAndSet(null)?.close()
        downloads.close()
    } }
    DisposableEffect(danmaku) { onDispose { danmaku?.close() } }
    DisposableEffect(listen) { onDispose { listen?.close() } }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer?.close() } }
    DisposableEffect(subtitleAssets, player) { onDispose { player?.close(); subtitleAssets.close() } }
    DisposableEffect(pip) { onDispose { pip?.close() } }
    DisposableEffect(systemMedia) { onDispose { systemMedia?.close() } }
    DesktopRetainedMediaEffects(retainedMedia) {
        mediaActive = it
        val owner = retainedMedia.current
        when (owner) {
            retainedMedia.live -> lastMediaSection = DesktopSection.LIVE
            retainedMedia.bangumi -> lastMediaSection = DesktopSection.BANGUMI
            retainedMedia.offline -> lastMediaSection = DesktopSection.DOWNLOADS
            retainedMedia.external -> lastMediaSection = DesktopSection.EXTERNAL_MEDIA
            else -> Unit
        }
    }
    LaunchedEffect(listening.current?.bvid,listening.current?.cid,sessionEpoch) { rootNowPlayingDismissed=false }
    val capturedFavoritesUiEpoch = sessionEpoch
    LaunchedEffect(capturedFavoritesUiEpoch, section) {
        if (section == DesktopSection.CLOUD_FAVORITES && repository.sessionEpoch == capturedFavoritesUiEpoch &&
            !isClosing() && !activatingUpdate && favoritesEntryRef.get()?.isOwned() != true) ensureFavoritesEntry()
    }
    DesktopHistoryRefreshEffects(browseMemory, showVideo && playing.details != null)
    LaunchedEffect(retainedMedia, jsExecutionRevision, sessionEpoch) {
        if (!retainedMedia.external.authorizationCurrent) retainedMedia.external.stopPlayback()
    }
    LaunchedEffect(player) { player?.applyPreferences(preferences) }
    LaunchedEffect(originalHardwareDecodePreferences, player, listen) {
        originalHardwareDecodePreferences.changes(legacyHardwareDecodeFallback).collect { enabled ->
            if (!imageSaveLifetime.isActive() || isClosing()) return@collect
            val previous = preferences
            preferences = previous.copy(hardwareDecodeEnabled = enabled)
            player?.setHardwareDecodingEnabled(enabled)
            listen?.onOriginalHardwareDecodeChanged(enabled)
            playback.onPlaybackPreferencesChanged(previous, preferences)
        }
    }
    LaunchedEffect(danmaku, rendererDanmakuSettings) { danmaku?.applySettings(rendererDanmakuSettings) }
    LaunchedEffect(pluginRuntime, danmaku) {
        pluginRuntime.danmakuRevision.collect { danmaku?.setPluginDanmakuProcessor(pluginRuntime::processDanmaku) }
    }
    LaunchedEffect(native.paused, native.loading, native.durationSeconds, native.error) {
        eyePlaybackActive.value = !native.paused && !native.loading && native.durationSeconds > 0 && native.error == null
    }
    LaunchedEffect(pluginRuntime, danmaku) {
        pluginRuntime.eyePaint(eyePlaybackActive).collect { paint ->
            eyePaint = paint
            danmaku?.setEyeProtection(paint.dimAlpha, paint.warmAlpha, paint.warmArgb)
        }
    }
    LaunchedEffect(backup) { backup.automaticBackupIfDue() }
    LaunchedEffect(playback, hostVisible, pipActive) { playback.setInBackground(!hostVisible && !pipActive) }
    DesktopOriginalBackgroundPlaybackEffects(storageSettingsContext, player,
        hidden = !hostVisible, isPip = pipActive, isInAudioMode = physicalDestination is BiliPaiNavKey.AudioMode,
        live = { imageSaveLifetime.isActive() && hostDisplayable && !isClosing() && !activatingUpdate && scope.isActive })
    LaunchedEffect(anyCasting) { if (anyCasting) {
        if (retainedMedia.current?.ownsNativeSource == true) player?.setPaused(true) else playback.pause()
        listen?.pause()
    } }
    LaunchedEffect(anyCasting, anyCastBusy) { if (!anyCasting && !anyCastBusy) LocalProxyServer.stopAndClear() }
    LaunchedEffect(sessionEpoch) {
        if (castAccountEpoch != sessionEpoch) {
            withContext(NonCancellable) {
                cast.quiesce()
                pluginRuntime.googleCast.onDisable()
                if (pluginRuntime.plugins.value.any { it.plugin === pluginRuntime.googleCast && it.enabled }) pluginRuntime.googleCast.onEnable()
                LocalProxyServer.stopAndClear()
                castAccountEpoch = sessionEpoch
            }
            if (dlnaDialog) cast.refreshDiscovery()
            if (googleCastDialog) pluginRuntime.googleCast.startRouteDiscovery(pluginRuntime.context)
        }
    }
    LaunchedEffect(Unit) { runCatching { repository.refreshAccount() } }
    LaunchedEffect(native.paused, native.loading) {
        if (!native.paused && (native.loading || native.durationSeconds > 0) && (playing.details != null || mediaActive)) {
            listen?.pause(); systemTargetAudio = false
        }
    }
    LaunchedEffect(native.sourceTitle) { pip?.updateTitle(native.sourceTitle) }
    LaunchedEffect(playing.details, playing.currentPart, mediaActive, retainedMedia.current,
        retainedMedia.current?.sourceVersion, retainedMedia.current?.previous, retainedMedia.current?.next) {
        val ordinary = playing.details.takeIf { !mediaActive }
        pip?.updateQueueControls(retainedMedia.current?.previous != null || ordinary != null && playback.hasPrevious,
            retainedMedia.current?.next != null || ordinary != null && playback.hasNext)
    }
    LaunchedEffect(pipError) { pipError?.let { error = it } }
    LaunchedEffect(systemMedia, playback, listen) {
        while (isActive) {
            val current = playback.state.value
            val audio = listen?.state?.value
            val audioTarget = systemTargetAudio && audio?.current != null
            val retainedOwner = retainedMedia.current
            val offlinePayload = if (!audioTarget && retainedOwner === retainedMedia.offline && retainedOwner.ownsNativeSource)
                downloads.tasks.value.firstOrNull { it.id == retainedMedia.offline.current }?.let {
                    com.android.purebilibili.feature.download.resolveOfflineMiniPlayerPayload(it.item)
                } else null
            val state = (if (audioTarget) audioPlayer else player)?.state?.value
            if (state != null) systemMedia?.update(WindowsMediaSnapshot(
                title = if (audioTarget) audio!!.current!!.title else offlinePayload?.title ?: if (retainedOwner != null) retainedMedia.title else current.details?.title ?: state.sourceTitle,
                artist = if (audioTarget) audio!!.current!!.owner else offlinePayload?.owner ?: if (retainedOwner != null) "" else current.details?.author.orEmpty(),
                mediaId = if (audioTarget) audio!!.current!!.bvid else offlinePayload?.bvid ?: if (retainedOwner != null) state.sourceTitle else current.details?.bvid ?: state.sourceTitle,
                state = state, isAudio = audioTarget || state.audioOnly,
                hasPrevious = if (audioTarget) audio!!.queue.size > 1 && (audio.currentIndex > 0 || preferences.playbackMode in setOf(com.bilipai.desktop.player.PlaybackMode.REPEAT_ALL, com.bilipai.desktop.player.PlaybackMode.SHUFFLE)) else retainedMedia.current?.let { it.previous != null } ?: playback.hasPrevious,
                hasNext = if (audioTarget) audio!!.queue.size > 1 && (audio.currentIndex < audio.queue.size - 1 || preferences.playbackMode in setOf(com.bilipai.desktop.player.PlaybackMode.REPEAT_ALL, com.bilipai.desktop.player.PlaybackMode.SHUFFLE)) else retainedMedia.current?.let { it.next != null } ?: playback.hasNext,
                enabled = state.loading || state.durationSeconds > 0 || state.ended))
            delay(500)
        }
    }
    LaunchedEffect(section, page, refresh, account?.mid) {
        if (section !in listOf(DesktopSection.HISTORY, DesktopSection.FAVORITES)) return@LaunchedEffect
        feedLoading = true; error = null
        try {
            cards = when (section) {
                DesktopSection.HISTORY -> library.history()
                else -> library.favorites()
            }.distinctBy { it.bvid }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { error = failure.message ?: "加载失败" }
        finally { feedLoading = false }
    }
    LaunchedEffect(Unit) { updater.autoCheck(); while (true) { delay(6 * 60 * 60 * 1000L); updater.autoCheck() } }
    LaunchedEffect(updateState, automaticUpdates, manuallyRequested, playing.details, playing.opening, mediaActive, listening.active, anyCasting, anyCastBusy, pipActive, activatingUpdate, updateJob) {
        if (updateJob?.isActive == true || activatingUpdate) return@LaunchedEffect
        when (val status = updateState) {
            is UpdateState.Available -> if (automaticUpdates) prepareUpdate(status.update, false)
            is UpdateState.Prepared -> if ((automaticUpdates || manuallyRequested) && playing.details == null && !playing.opening && !mediaActive && !listening.active && !anyCasting && !anyCastBusy && !pipActive) {
                activatingUpdate = true
                updateJob = scope.launch(start = CoroutineStart.LAZY) {
                    try { if (updater.activatePreparedUpdate(status.prepared)) onExit() }
                    finally { activatingUpdate = false; manuallyRequested = false; updateJob = null }
                }.also { it.start() }
            }
            else -> Unit
        }
    }

    fun performPlayerKey(action: PlayerKeyAction?): Boolean {
        val initialized = player ?: return false
        val snapshot = initialized.state.value
        return when (action) {
            PlayerKeyAction.PlayPause -> ordinaryVideo.togglePause()
            is PlayerKeyAction.SeekRelative -> { playback.seekBy(action.deltaMs / 1000.0); true }
            is PlayerKeyAction.SeekPercent -> { playback.seekTo(snapshot.durationSeconds * action.fraction); true }
            PlayerKeyAction.VolumeUp -> { changePreferences(preferences.copy(volume = snapshot.volume + 5)); true }
            PlayerKeyAction.VolumeDown -> { changePreferences(preferences.copy(volume = snapshot.volume - 5)); true }
            PlayerKeyAction.ToggleMute -> { changePreferences(preferences.copy(muted = !snapshot.muted)); true }
            PlayerKeyAction.ToggleFullscreen -> { toggleOriginalFullscreen(); true }
            PlayerKeyAction.ToggleDanmaku -> { toggleOriginalDanmaku(); true }
            PlayerKeyAction.PreviousPart -> { playback.previous(); true }
            PlayerKeyAction.NextPart -> { playback.next(); true }
            is PlayerKeyAction.SetSpeed -> { changePreferencesIntent(preferences.copy(speed = action.speed.toDouble()), forceSpeed = true); true }
            else -> false
        }
    }

    val latestDownloadNotificationOpen by rememberUpdatedState<() -> Unit>({
        if (navigate(DesktopSection.DOWNLOADS)) {
            (hostWindow as? java.awt.Frame)?.let { it.extendedState = it.extendedState and java.awt.Frame.ICONIFIED.inv() }
            hostWindow?.toFront()
            hostWindow?.requestFocus()
        }
    })
    val latestDownloadNotificationFailure by rememberUpdatedState<(String) -> Unit>({ error = it })
    DisposableEffect(downloads, hostWindow, hostDisplayable) {
        val effectAlive = java.util.concurrent.atomic.AtomicBoolean(true)
        val window = hostWindow
        if (window != null && hostDisplayable) javax.swing.SwingUtilities.invokeLater {
            fun owned() = effectAlive.get() && !downloadNotificationRetired.get() && !latestDynamicIsClosing() && window.isDisplayable
            if (owned()) try {
                val notification = DesktopDownloadNotifications(downloads, window, scope, ::owned,
                    { latestDownloadNotificationOpen() }, { latestDownloadNotificationFailure(it) })
                if (owned()) downloadNotification.getAndSet(notification)?.close() else notification.close()
            } catch (failure: Exception) {
                if (owned()) latestDownloadNotificationFailure(failure.message ?: "Windows 下载进度无法启动")
            }
        }
        onDispose {
            effectAlive.set(false)
            downloadNotification.getAndSet(null)?.close()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val measuredRootConstraints = constraints
    DesktopAppearanceTheme(themeSettings, windowSmallestWidthDp = minOf(maxWidth.value, maxHeight.value).toInt()) {
    val scheme = MaterialTheme.colorScheme
    val strings = LocalDesktopStrings.current
    val liquidBackground = rememberGraphicsLayer()
    var liquidBackgroundBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val latestLiquidWindowSize by rememberUpdatedState(LocalWindowInfo.current.containerSize)
    val latestLiquidBackgroundBounds by rememberUpdatedState(liquidBackgroundBounds)
    val liquidOwnerAlive = remember(section, showVideo, playing.details?.bvid, sessionEpoch) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(liquidOwnerAlive) { onDispose { liquidOwnerAlive.set(false) } }
    val latestLiquidOwner by rememberUpdatedState<() -> Boolean>({
        liquidOwnerAlive.get() && !isClosing() && repository.sessionEpoch == sessionEpoch && hostVisible && hostDisplayable &&
            generateSequence(java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow) { it.owner }
                .any { it === hostWindow }
    })
    val liquidEnvironment = remember(liquidBackground, liquidOwnerAlive) {
        DesktopLiquidReadabilityEnvironment(liquidBackground, { latestLiquidBackgroundBounds },
            { latestLiquidWindowSize }, { liquidOwnerAlive.get() && latestLiquidOwner() })
    }
    val parentThemeConfig = com.android.purebilibili.core.ui.LocalAppThemeConfig.current
    val parentLiquidConfig = com.android.purebilibili.feature.home.components.LocalLiquidGlassRenderConfig.current
    val effectiveThemeConfig = liquidHomeSettings?.let { parentThemeConfig.copy(liquidGlassEnabled = it.androidNativeLiquidGlassEnabled) } ?: parentThemeConfig
    val effectiveLiquidConfig = liquidHomeSettings?.let {
        com.android.purebilibili.feature.home.components.LiquidGlassRenderConfig(
            tuning = com.android.purebilibili.feature.home.components.resolveLiquidGlassTuning(
                progress = it.liquidGlassProgress, advancedSettings = it.liquidGlassAdvancedSettings,
                readabilityMode = it.liquidGlassReadabilityMode), preset = it.bottomBarLiquidGlassPreset)
    } ?: parentLiquidConfig
    val captureLiquidBackground = effectiveThemeConfig.liquidGlassEnabled &&
        liquidHomeSettings?.liquidGlassReadabilityMode == com.android.purebilibili.core.store.LiquidGlassReadabilityMode.ADAPTIVE
    val rootKeyHandler: (KeyEvent) -> Boolean = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) { toggleOriginalFullscreen(); true }
            else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !searchFocused) {
                if (isFullscreen()) setOriginalFullscreen(false) else homeRootRef.get()?.navigation?.requestBack()
                true
            }
            else if ((showVideo || section == DesktopSection.STORY) && playing.details != null && playerFocused && !searchFocused && player != null) {
                // Shortcuts are scoped to the focused player; comment and search editors keep their keys.
                performPlayerKey(resolvePlayerKeyAction(event, isTextInputActive = searchFocused))
            } else false
    }
    val latestRootKeyHandler by rememberUpdatedState(rootKeyHandler)
    val windowKeyFallback = com.bilipai.desktop.ui.LocalDesktopWindowKeyFallback.current
    DisposableEffect(windowKeyFallback, hostWindow, homeRootRef, scope) {
        val registration = windowKeyFallback?.register { event ->
            // The real Window invokes this only after the scene did not consume the key.
            // Native focus can exist without a Compose focus target below the Surface.
            if (isClosing() || activatingUpdate || !scope.isActive ||
                !hostDisplayable || !hostVisible || homeRootRef.get()?.isActive() != true) false
            else latestRootKeyHandler(event)
        }
        onDispose { registration?.close() }
    }
    CompositionLocalProvider(LocalDesktopBrowseMemory provides browseMemory, LocalUiSkinState provides packages.skin,
        LocalDesktopWindowsPlayerWindow provides hostWindow,
        LocalDesktopWindowsVideoEnhancement provides DesktopWindowsVideoEnhancementUiBinding(
            pluginRuntime.enhancementConfiguration, enhancement?.state ?: emptyEnhancement),
        LocalDesktopLiquidTabSettings provides liquidTabSettings,
        LocalDesktopLiquidReadabilityEnvironment provides liquidEnvironment,
        com.android.purebilibili.core.ui.LocalAppThemeConfig provides effectiveThemeConfig,
        com.android.purebilibili.feature.home.components.LocalLiquidGlassRenderConfig provides effectiveLiquidConfig,
        LocalDesktopDynamicCache provides dynamicCache,
        LocalDesktopDynamicCardRepository provides repository,
        LocalDesktopDynamicCardSession provides dynamicCardSession,
        LocalDesktopDetailForeground provides (hostDisplayable && hostVisible),
        LocalDesktopDynamicSaveParent provides hostWindow,
        LocalDesktopTextShareBindings provides rootTextShareBindings,
        LocalDesktopImageSaveLocations provides imageSaveLocations,
        LocalDesktopDynamicEditorActions provides dynamicEditor.actions,
        LocalDesktopDynamicCardStateRegistry provides dynamicCardRegistry,
        LocalDesktopDynamicCardMutations provides dynamicCardRegistry.bindings,
        LocalDesktopDynamicCardNavigation provides com.android.purebilibili.feature.dynamic.components.DynamicCardNavigationActions(
            onVideoClick = { openVideo(VideoCard(it, "", "", "", 0, 0)) }, onUserClick = ::openUser,
            onBangumiClick = { sid, eid -> showSeason(sid, eid) }, onMusicClick = ::openMusic,
            onLiveClick = { room, _, _ -> openLive(room) },
            onCollectionClick = { id, mid, title, url ->
                navigateOriginalDynamicCollection(id, mid, title, url, onFavorite = { type, mediaId, ownerMid, folderTitle ->
                    navigate(DesktopSection.COLLECTION) {
                        collectionMid = ownerMid; collectionId = mediaId; collectionType = type; collectionTitle = folderTitle
                    }
                }, onWeb = ::openDynamicWeb)
            },
            onCourseClick = { url, title ->
                navigateOriginalDynamicCourse(url, title, onPlayer = { sid, eid, course -> showSeason(sid, eid, course) }, onWeb = ::openDynamicWeb)
            }),
        com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext provides storageSettingsContext,
        com.bilipai.desktop.ui.LocalDesktopWindowsAudioOutputController provides windowsAudioOutputController,
        com.bilipai.desktop.ui.LocalDesktopDetailedCommentTimeContext provides globalPluginContext,
        com.android.purebilibili.core.ui.LocalDetailedCommentTimeEnabled provides detailedCommentTimeEnabled,
        LocalDesktopDynamicTimelinePreferences provides dynamicTimelinePreferences,
        LocalDesktopHomeCardProgress provides homeCardProgress,
        LocalDesktopHomeCardPreferences provides homeCardPreferences) {
        com.android.purebilibili.feature.dynamic.components.ImagePreviewOverlayHost()
        if (!appearanceReady) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (appearanceError == null) CircularProgressIndicator()
                    else {
                        Text(requireNotNull(appearanceError), color = scheme.error)
                        TextButton(onClick = { appearanceRetry++ }) { Text(strings["common_retry"]) }
                    }
                }
            }
            return@CompositionLocalProvider
        }
        DesktopDynamicEditorRootHost(dynamicEditor, hostWindow)
        Box(Modifier.fillMaxSize()) {
        // libmpv's audio worker needs a retained native host even when its screen is not visible.
        if (audioPlayer != null) SwingPanel(factory = { audioPlayer.surface }, background = Color.Transparent, modifier = Modifier.size(1.dp))
        Surface(Modifier.fillMaxSize().onGloballyPositioned { liquidBackgroundBounds = it.boundsInWindow() }
            .drawWithContent {
                if (captureLiquidBackground) liquidEnvironment.recordBackground {
                    liquidBackground.record { this@drawWithContent.drawContent() }
                }
                drawContent()
            }.onKeyEvent(rootKeyHandler), color = scheme.background) {
                    val playerContent: @Composable (MpvPlayer) -> Unit = { initialized ->
                        Column(Modifier.fillMaxWidth()) {
                        PlayerPanel(initialized, preferences.copy(danmaku = rendererDanmakuSettings), ::changePreferences, onToggleFullscreen,
                            modifier = Modifier.onFocusChanged { playerFocused = it.hasFocus }.focusable(), onMessage = { error = it },
                            onPreviousPart = if ((showVideo || section == DesktopSection.STORY) && playback.hasPrevious) ({ playback.previous() }) else null,
                            onNextPart = if ((showVideo || section == DesktopSection.STORY) && playback.hasNext) ({ playback.next() }) else null,
                            // Ordinary subtitles/audio are owned by the complete original Holder.
                            // This thin panel is retained only by Story/other native media.
                            onOriginalDanmakuSettings = if (initialized === player && showVideo && playing.details?.raw != null && hostWindow != null) ({ originalDanmakuSettingsVisible = true }) else null,
                            onOriginalDanmakuToggle = ::toggleOriginalDanmaku,
                            surfaceOnly = section == DesktopSection.STORY,

                            commandOverlay = if (initialized === player && danmaku != null &&
                                (showVideo || section == DesktopSection.STORY) && commandDetails != null &&
                                commandCid > 0 && initialized.ownsSourceVersion(commandVersion) &&
                                playback.currentCastSource(commandVersion) != null && rendererDanmakuSettings.enabled) ({
                                val capturedEpoch = sessionEpoch
                                val capturedInfo = commandDetails
                                val capturedCid = commandCid
                                val capturedVersion = commandVersion
                                DesktopVideoCommandVoteContent(repository, initialized, capturedVersion,
                                    capturedInfo.bvid, capturedInfo.aid, capturedCid, danmaku, commandState,
                                    fontScale = rendererDanmakuSettings.fontScale,
                                    hideInteractiveCommands = rendererDanmakuSettings.hideInteractiveCommands,
                                    stillOwned = {
                                        repository.sessionEpoch == capturedEpoch && initialized.ownsSourceVersion(capturedVersion) &&
                                            playback.state.value.details?.bvid == capturedInfo.bvid &&
                                            playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == capturedCid &&
                                            playback.currentCastSource(capturedVersion) != null
                                    }, submitGrade = { operations, aid, cid, progress, gradeId, score ->
                                        operations.submitGradeDanmaku(aid, cid, progress, gradeId, score)
                                    }, onFeedback = { error = it })
                            }) else null,
                            onSeekTo = if ((showVideo || section == DesktopSection.STORY) && playing.details != null) playback::seekTo else null,
                            renderSurface = !pipActive, onPictureInPicture = if (pip != null && hostWindow != null) ({ pip.open(hostWindow, initialized.state.value.sourceTitle) }) else null)
                        if (section != DesktopSection.STORY) DesktopVideoEnhancementControls(enhancementState,
                            pluginRuntime.enhancementConfiguration,
                            onToggle = { enabled -> if (!isClosing() && !activatingUpdate) pluginRuntime.enhancementConfiguration.setAutomaticEnabled(enabled) },
                            onSettings = { enhancementSettings = true })
                        }
                    }

            DesktopDetailWindow {
            val actualWindow = hostWindow
            if(actualWindow==null) Text("原版导航需要实际应用窗口，尚未创建") else {
                val ffprobe = com.bilipai.desktop.download.WindowsFfmpegMuxer.locateFfmpeg()?.resolveSibling("ffprobe.exe")
                    ?: java.nio.file.Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),"native","windows-x64","ffprobe.exe")
                val originalBarVisible by com.android.purebilibili.feature.audio.player.AudioNowPlayingSession.barOverlayVisible.collectAsState()
                SideEffect { musicOverlay.value = originalBarVisible }
                val services = DesktopReadyOriginalRootServices(repository,
                    { environment -> originalWindowEnvironment.set(environment) },
                    { environment -> originalWindowEnvironment.compareAndSet(environment,null) },
                    { environment, content ->
                        val appResources = ordinaryVideoResources
                        // Borrow the already created retained Gallery binding before the Root comment wrapper.
                        // This is the same owner, file pool and native share actor; it creates no new resource.
                        CompositionLocalProvider(LocalDesktopImagePreviewShareBindings provides environment.gallery.imageShare) {
                        DesktopOriginalCommentRootBindings(environment.repository, community, commentFraud,
                            environment.root, environment::owns, Modifier.fillMaxSize(),
                            borrowedImageAssets=environment.gallery.imageAssets) { _ ->
                        if (appResources == null) content() else {
                            val shellResources = remember(environment,appResources,ordinaryVideo,player,danmaku,enhancement,pip) {
                                DesktopOriginalVideoRootShellResources(checkNotNull(player),checkNotNull(danmaku),
                                    checkNotNull(enhancement),checkNotNull(pip),subtitleAssets,downloads,cast,
                                    checkNotNull(listen).audio,discovery,community,commentFraud,appResources,
                                    checkNotNull(originalCaptureProtection),originalDanmakuPreferences,danmakuPresentation,
                                    WindowsTextClipboard,diagnostics,{preferences},isFullscreen,::setOriginalFullscreen,
                                    { volume -> changePreferences(preferences.copy(volume=volume.toDouble())) },
                                    { !isClosing() && !ordinaryVideoResourcesRetired.get() },{message->ordinaryVideoEvents.trySend(DesktopOriginalVideoShellEvent.Feedback(message))},
                                    { event -> ordinaryVideoEvents.trySend(event) },
                                    { receipt -> scope.launch {
                                        if (repository.sessionEpoch == receipt.accountEpoch && ordinaryVideo.slot.currentAssembly()?.owns()==true)
                                            playback.retry() // Fresh request capture after the original VIP receipt invalidates itself.
                                    } },
                                    DesktopLibrary.directoryForAccount(null).resolve("video-scratch"))
                            }
                            DesktopOriginalVideoReadyRootMount(environment,ordinaryVideo,shellResources,content)
                        }
                        }
                        }
                    }, discovery,community,pluginRuntime,dynamicCardSession,
                    appearance,actualWindow,imageSaveLocations,imageSaveLifetime,diagnostics,nativeTextShare,rootTextShareBindings,
                    musicOverlay,{downloads.tasks.value.map{it.item}},listen,playerError,
                    { !isClosing() && !activatingUpdate },{pip?.active?.value==true},
                    { !isClosing() && !activatingUpdate && appearanceReady },
                    { action -> if(!isClosing()&&!activatingUpdate&&checkpointForNavigation()){action();true}else false },
                    { old,new ->
                        if(old is BiliPaiNavKey.Story && new !is BiliPaiNavKey.Story) storyHost.retire()
                        if(new is BiliPaiNavKey.VideoDetail) {
                            storyHost.retire();retainedMedia.stop();listen?.pause();systemTargetAudio=false
                            if(new.fullscreen && !isFullscreen()) setOriginalFullscreen(true)
                        } else if(old is BiliPaiNavKey.VideoDetail && old.fullscreen && isFullscreen()) setOriginalFullscreen(false)
                    },
                    { destination -> if(physicalDestination!=destination) playerFocused=false
                        physicalDestination=destination;section=desktopReadySection(destination)
                        showVideo=destination is BiliPaiNavKey.VideoDetail
                        if(destination != BiliPaiNavKey.Onboarding && !initialVideoConsumed && initialVideo!=null) {
                            initialVideoConsumed=true;rootRoutes()?.video(BiliPaiNavKey.VideoDetail(initialVideo,sourceRoute="home"))
                        } },
                    {error=it}, {raw->openDynamicWeb(raw,"链接")},
                    { expectedEpoch,expectedMid -> authenticationInvalidations.trySend(expectedEpoch to expectedMid); Unit },
                    { gate -> DesktopProfileAccountsBinding(repository,gate.epoch,gate.mid,
                        requireNotNull(gate.scope.coroutineContext[Job]),gate::owns,gate::commit) },
                    { onExit() },{loginDialog=true},if(account!=null)({repository.logout()})else null,
                    dynamicCardRegistry::currentAllUpdateBaseline,null,favoritesEntry?.searchChannel,null,
                    { originalNowPlaying.get()?.second?.dismiss() },
                    { DesktopOriginalNowPlayingVisibility(originalNowPlaying.get()?.second?.owner?.current()?.active==true,
                        physicalDestination is BiliPaiNavKey.AudioMode,pipActive,true,false,
                        physicalDestination is BiliPaiNavKey.VideoDetail,actualWindow.width>actualWindow.height,
                        physicalDestination is BiliPaiNavKey.VideoDetail || retainedMedia.current!=null) },ffprobe,library,
                    { root -> originalNowPlayingFor(root,listen).binding },
                    { root, expected -> originalNowPlayingFor(root,listen).positionMs(expected) },
                    ordinaryVideo.playlist,
                    { bvid -> ordinaryVideoResources?.progress?.cachedPositionForSpace(bvid) { !isClosing() } ?: 0L })
                if(!storageStartupReady) Text("正在准备存储与缓存…") else DesktopReadyOriginalRootMount(services,homeRootRef,Modifier.fillMaxSize(),onRootContentFrame) { entryKey,commands,active,pagerHosted,personalLists,originalHomePreferences,messagePages,spacePages ->
                    val messageRoutes = commands as DesktopOriginalRootRouteAssembly
                    val messageLink: (String) -> Unit = { raw ->
                        if (active) messageRoutes.callbackFor(entryKey) {
                            desktopOriginalOpenMessageLink(raw, commands, entryKey.toLegacyRoute())
                        }
                    }
                    CompositionLocalProvider(LocalDesktopDetailForeground provides (active&&hostVisible&&hostDisplayable),
                        LocalDesktopOriginalMessageLinkNavigation provides messageLink) {

                val section = desktopReadySection(entryKey)
                val videoEntry = entryKey as? BiliPaiNavKey.VideoDetail
                LaunchedEffect(entryKey,active) {
                    if(active) when(entryKey) {
                        BiliPaiNavKey.Settings -> settingsNavigator.openRoot()
                        is BiliPaiNavKey.SettingsCategory -> settingsNavigator.openCategory(entryKey.category)
                        BiliPaiNavKey.SettingsSearch -> settingsNavigator.activateSearch()
                        BiliPaiNavKey.PlaybackSettings -> settingsNavigator.openDetail(SettingsSearchTarget.PLAYBACK,null)
                        BiliPaiNavKey.AnimationSettings -> settingsNavigator.openDetail(SettingsSearchTarget.ANIMATION,null)
                        BiliPaiNavKey.BottomBarSettings -> settingsNavigator.openDetail(SettingsSearchTarget.BOTTOM_BAR,null)
                        BiliPaiNavKey.SettingsShare -> settingsNavigator.openDetail(SettingsSearchTarget.SETTINGS_SHARE,null)
                        BiliPaiNavKey.MessageNotificationSettings -> settingsNavigator.openDetail(SettingsSearchTarget.MESSAGE_NOTIFICATION,null)
                        BiliPaiNavKey.HomeSettings -> settingsNavigator.openDetail(SettingsSearchTarget.HOME_FEED,null)
                        BiliPaiNavKey.PermissionSettings -> settingsNavigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
                        BiliPaiNavKey.WebDavBackup -> settingsNavigator.openDetail(SettingsSearchTarget.WEBDAV_BACKUP,null)
                        BiliPaiNavKey.TipsSettings -> settingsNavigator.openDetail(SettingsSearchTarget.TIPS,null)
                        BiliPaiNavKey.OpenSourceLicenses -> settingsNavigator.openDetail(SettingsSearchTarget.OPEN_SOURCE_LICENSES,null)
                        else -> Unit
                    }
                }
                val showVideo = entryKey is BiliPaiNavKey.VideoDetail
                val userId = (entryKey as? BiliPaiNavKey.Space)?.mid ?: (entryKey as? BiliPaiNavKey.Following)?.mid ?: 0L
                val submitted = (entryKey as? BiliPaiNavKey.Search)?.keyword.orEmpty()
                val articleId = (entryKey as? BiliPaiNavKey.ArticleDetail)?.articleId ?: 0L
                val dynamicRoute = (entryKey as? BiliPaiNavKey.DynamicDetail)?.let { DesktopDynamicDetailRoute(it.dynamicId,it.commentRootRpid,it.commentTargetRpid) }
                val topicId = (entryKey as? BiliPaiNavKey.TopicDetail)?.topicId ?: 0L
                val roomId = (entryKey as? BiliPaiNavKey.Live)?.roomId?.toLongOrNull() ?: 0L
                val bgmRequest = (entryKey as? BiliPaiNavKey.BgmDetail)?.let {DesktopBgmMusicTarget.Detail(it.musicId,it.aid,it.cid,it.showVideos)}
                val musicSource = when(entryKey) {is BiliPaiNavKey.MusicDetail->MusicPlaybackSource.AudioSong(entryKey.sid);is BiliPaiNavKey.NativeMusic->MusicPlaybackSource.VideoAudio(entryKey.bvid,entryKey.cid,entryKey.title);else->null}
                val musicStartPosition = if(entryKey is BiliPaiNavKey.AudioMode) entryKey.sourceResumePositionMs/1000.0 else 0.0
                val seasonId = when(entryKey){is BiliPaiNavKey.BangumiPlayer->entryKey.seasonId;is BiliPaiNavKey.BangumiDetail->entryKey.seasonId;else->0L}
                val episodeId = when(entryKey){is BiliPaiNavKey.BangumiPlayer->entryKey.epId;is BiliPaiNavKey.BangumiDetail->entryKey.epId;else->0L}
                val isCourse = (entryKey as? BiliPaiNavKey.BangumiPlayer)?.isCourse ?: false
                val seasonProgress = (entryKey as? BiliPaiNavKey.BangumiPlayer)?.resumePositionMs?.div(1000.0) ?: 0.0
                val seasonType = (entryKey as? BiliPaiNavKey.Bangumi)?.initialType ?: 1
                val jsPluginId = (entryKey as? BiliPaiNavKey.JsPluginContent)?.pluginId.orEmpty()
                val storySeed = (entryKey as? BiliPaiNavKey.Story)?.let{DesktopStorySeed(it.seedBvid,it.seedCid,it.seedCover,it.seedTitle)} ?: DesktopStorySeed()
                val collectionMid = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.mid ?: 0L
                val collectionId = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.id ?: 0L
                val collectionType = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.type.orEmpty()
                val collectionTitle = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.title.orEmpty()

                    Column(Modifier.fillMaxSize()) {
                        if(!pagerHosted && (entryKey is BiliPaiNavKey.Search || entryKey == BiliPaiNavKey.Settings))
                            com.android.purebilibili.core.ui.components.AppTextButton(onClick={commands.back()}) {
                                com.android.purebilibili.core.ui.components.AppText("返回")
                            }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            entryKey == BiliPaiNavKey.Onboarding ->
                                DesktopOriginalOnboardingRootHost(messageRoutes, active, onDisagree = onExit)
                            entryKey == BiliPaiNavKey.IconSettings ->
                                DesktopDetailWindow { DesktopOriginalIconSettingsRootHost(messageRoutes, homeRootRef,
                                    services.runtime.context, services.imageLifetime, active,
                                    onFailure = { error = it.message ?: "图标设置保存失败" },
                                    onNotice = services.feedback) }
                            entryKey is BiliPaiNavKey.AicuQuery ->
                                DesktopDetailWindow { DesktopOriginalAicuRootHost(entryKey, messageRoutes, services.repository) }
                            entryKey is BiliPaiNavKey.Space || entryKey is BiliPaiNavKey.UpowerRank || entryKey is BiliPaiNavKey.MemberGuard ->
                                DesktopDetailWindow { DesktopOriginalSpacePageRootHost(entryKey, spacePages,
                                    services.originalSpacePlaylist, services.originalSpaceCachedPosition,
                                    { title, text, owned -> requestDesktopTextShare(services.textShare,
                                        spacePages.entry(entryKey).environment.scope, title, text,
                                        { owned() && active && messageRoutes.currentKey == entryKey && !isClosing() }, services.feedback) },
                                    desktopDetailRenderEffectsSupported(), active, personalLists.preferences) }
                            entryKey is BiliPaiNavKey.BangumiPlayer ->
                                DesktopOriginalBangumiPlayerPhysicalLeaf(entryKey, ordinaryVideo, messageRoutes, active,
                                    ::openVideoHonorLink, pendingOwner = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            ordinaryVideoResourceError?.let { Text(it) } ?: CircularProgressIndicator()
                                        }
                                    })
                            entryKey is BiliPaiNavKey.Bangumi || entryKey is BiliPaiNavKey.BangumiDetail || entryKey is BiliPaiNavKey.BangumiReview ->
                                DesktopDetailWindow { DesktopOriginalBangumiPagesRootHost(entryKey, messageRoutes, repository, ordinaryVideoResources, active,
                                    replaceSeason = { current, season -> messageRoutes.replaceBangumiDetail(current, season) }) }
                            entryKey == BiliPaiNavKey.Inbox || entryKey == BiliPaiNavKey.ReplyMe ||
                                entryKey == BiliPaiNavKey.AtMe || entryKey == BiliPaiNavKey.LikeMe ||
                                entryKey == BiliPaiNavKey.SystemNotice || entryKey is BiliPaiNavKey.Chat ->
                                DesktopDetailWindow { DesktopOriginalMessagePageRootHost(entryKey, messagePages, messageRoutes, active) }
                            entryKey is BiliPaiNavKey.CommentDetail ->
                                DesktopDetailWindow { DesktopOriginalCommentDetailRootHost(entryKey, messageRoutes, active) }
                            entryKey is BiliPaiNavKey.VideoDetail -> {
                                val hotDanmakuLink=remember(entryKey){DesktopWindowsHotDanmakuLink()}
                                val observedDanmakuAssembly by ordinaryVideo.slot.assemblies.collectAsState()
                                val danmakuAssembly = observedDanmakuAssembly
                                val danmakuSuccess = danmakuAssembly?.playback?.uiState?.collectAsState()?.value as?
                                    com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.Success
                                val danmakuSource = danmakuAssembly?.takeIf { it.owns() }?.native?.current()?.takeIf {
                                    danmakuSuccess != null && danmakuSuccess.info.bvid == it.request.bvid && danmakuSuccess.info.cid == it.request.cid
                                }
                                fun ownsDanmakuSource(): Boolean = !isClosing() && !activatingUpdate && active &&
                                    hostVisible && hostDisplayable && messageRoutes.currentKey == entryKey &&
                                    danmakuAssembly != null && ordinaryVideo.slot.currentAssembly() === danmakuAssembly &&
                                    danmakuAssembly.owns() && danmakuSource != null &&
                                    danmakuAssembly.native.isCurrent(danmakuSource)
                                DesktopWindowsVideoPhysicalLeaf(entryKey, ordinaryVideo,
                                    active && hostVisible && hostDisplayable, isFullscreen(), pipActive,
                                    preferences.copy(danmaku = rendererDanmakuSettings), ::changePreferences,
                                    DesktopWindowsVideoActions(
                                        back = commands::back, fullscreen = ::toggleOriginalFullscreen,
                                        pictureInPicture = { if (pip != null && hostWindow != null) pip.open(hostWindow, player?.state?.value?.sourceTitle.orEmpty()) },
                                        user = { commands.push(BiliPaiNavKey.Space(it)) }, video = ::openVideo,
                                        download = { owner, success ->
                                            val expected = owner.native.current()
                                            if (expected != null && success.info.bvid == expected.request.bvid && success.info.cid == expected.request.cid) {
                                                val source = playback.currentCastSource(expected.sourceVersion)
                                                if (source != null) scope.launch(Dispatchers.IO) {
                                                    fun owned() = !isClosing() && !activatingUpdate && messageRoutes.currentKey == entryKey &&
                                                        ordinaryVideo.slot.currentAssembly() === owner && owner.owns() && owner.native.isCurrent(expected)
                                                    try {
                                                        ensureActive()
                                                        if (!owned()) throw CancellationException("Windows video download entry retired")
                                                        val page = success.info.pages.firstOrNull { it.cid == success.info.cid }
                                                        downloads.enqueue(source.toNativePlayback(), metadata = com.bilipai.desktop.download.DownloadMetadata(
                                                            bvid = success.info.bvid, cid = success.info.cid, aid = success.info.aid,
                                                            cover = success.info.pic, author = success.info.owner.name,
                                                            durationSeconds = page?.duration?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0, quality = success.currentQuality,
                                                            qualityLabel = success.qualityLabels.getOrNull(success.qualityIds.indexOf(success.currentQuality)).orEmpty(),
                                                            episodeLabel = page?.part, episodeCount = success.info.pages.size.coerceAtLeast(1)), stillOwned = ::owned)
                                                        withContext(Dispatchers.Main) { if (owned()) error = "已添加到下载队列" }
                                                    } catch (cancelled: CancellationException) { throw cancelled }
                                                    catch (failure: Exception) { withContext(Dispatchers.Main) { if (owned()) error = failure.message ?: "下载失败" } }
                                                } else error = "当前播放来源尚未完成授权，请稍后重试"
                                            }
                                        },
                                        favorite = { owner, success, current ->
                                            val engagement by owner.domains.engagement.uiState.collectAsState()
                                            val expected = owner.native.current()
                                            DesktopVideoFavoriteRoot(success.info.aid, repository, community, pluginStore,
                                                engagement.isFavorited, engagement.favoriteCount,
                                                stillOwned = { current() && expected != null && owner.native.isCurrent(expected) },
                                                onFavoriteLoaded = { value -> if (current()) owner.domains.engagement.applyFavoriteFolderResult(value) },
                                                onFavoriteSaved = { value, count -> if (current() && expected != null && owner.native.isCurrent(expected)) {
                                                    owner.domains.engagement.applyFavoriteFolderResult(value)
                                                    owner.domains.engagement.uiState.value.subject?.let { subject -> owner.domains.engagement.confirmDesktopFavoriteCount(subject, count) }
                                                } }, onLogin = { loginDialog = true }, feedback = { error = it })
                                        },
                                        overlay = {
                                            if(player!=null && danmaku!=null && danmakuAssembly!=null && danmakuSource!=null && rendererDanmakuSettings.enabled && !pipActive && ownsDanmakuSource())
                                                DesktopWindowsHotDanmakuHost(hotDanmakuLink,danmakuSource,danmaku,player,danmakuAssembly,::ownsDanmakuSource,
                                                    {action->ownsDanmakuSource() && ordinaryVideo.factoryFor(danmakuAssembly).withPresentationAdmission(danmakuAssembly,danmakuSource,action)})
                                            if (player != null && danmaku != null && commandDetails != null && commandCid > 0 &&
                                                player.ownsSourceVersion(commandVersion) && playback.currentCastSource(commandVersion) != null && rendererDanmakuSettings.enabled) {
                                                val capturedEpoch = sessionEpoch
                                                val capturedInfo = commandDetails
                                                val capturedCid = commandCid
                                                val capturedVersion = commandVersion
                                                DesktopVideoCommandVoteContent(repository, player, capturedVersion,
                                                    capturedInfo.bvid, capturedInfo.aid, capturedCid, danmaku, commandState,
                                                    fontScale = rendererDanmakuSettings.fontScale,
                                                    hideInteractiveCommands = rendererDanmakuSettings.hideInteractiveCommands,
                                                    stillOwned = { messageRoutes.currentKey == entryKey && repository.sessionEpoch == capturedEpoch &&
                                                        player.ownsSourceVersion(capturedVersion) && playback.currentCastSource(capturedVersion) != null },
                                                    submitGrade = { operations, aid, cid, progress, gradeId, score -> operations.submitGradeDanmaku(aid, cid, progress, gradeId, score) },
                                                    onFeedback = { error = it })
                                            }
                                        },
                                        collectionQueue = { presentation ->
                                            DesktopWindowsVideoCollectionQueueRoot(presentation,ordinaryVideo.playlist,
                                                ::openQueue,{error=it},{title,text,owned->
                                                    requestDesktopTextShare(rootTextShareBindings,scope,title,text,owned,{error=it})},
                                                {action->presentation.stillOwned() && ordinaryVideo.factoryFor(presentation.assembly)
                                                    .withPresentationAdmission(presentation.assembly,presentation.sourceOwner,action)})
                                        },
                                        bgm = { presentation ->
                                            fun ownedBgm() = !isClosing() && !activatingUpdate && active && hostVisible && hostDisplayable &&
                                                messageRoutes.currentKey == entryKey && ordinaryVideo.slot.currentAssembly() === presentation.assembly &&
                                                presentation.stillOwned() && ordinaryVideo.factoryFor(presentation.assembly)
                                                    .isPresentationCurrent(presentation.assembly,presentation.sourceOwner)
                                            DesktopWindowsVideoBgmSection(DesktopWindowsVideoBgmPresentation(
                                                presentation.assembly,presentation.sourceOwner,presentation.result,::ownedBgm),
                                                { action -> ownedBgm() && ordinaryVideo.factoryFor(presentation.assembly)
                                                    .withPresentationAdmission(presentation.assembly,presentation.sourceOwner,action) },
                                                onDetail = { target -> if(ownedBgm()) messageRoutes.callbackFor(entryKey) {
                                                    commands.push(BiliPaiNavKey.BgmDetail(target.musicId,target.aid,target.cid,target.showVideos))
                                                } },
                                                onRelatedVideo = { bvid,cid -> if(ownedBgm()) messageRoutes.callbackFor(entryKey) {
                                                    openVideo(VideoCard(bvid,"","","",0,0,preferredCid=cid))
                                                } },
                                                onExternalUrl = { raw -> if(ownedBgm()) {
                                                    // Browser dispatch is outside the BGM final publication monitor.
                                                    desktopOriginalOpenMessageLink(raw,commands,entryKey.toLegacyRoute())
                                                } })
                                        },
                                        enhancement = { DesktopVideoEnhancementControls(enhancementState, pluginRuntime.enhancementConfiguration,
                                            onToggle = { enabled -> if (!isClosing() && !activatingUpdate) pluginRuntime.enhancementConfiguration.setAutomaticEnabled(enabled) }, onSettings = { enhancementSettings = true }) },
                                        openLink = { raw -> desktopOriginalOpenMessageLink(raw, commands, entryKey.toLegacyRoute()) },
                                        honorLink = { assembly, source, url ->
                                            if (!isClosing() && !activatingUpdate && active && hostVisible && hostDisplayable &&
                                                messageRoutes.currentKey == entryKey && ordinaryVideo.slot.currentAssembly() === assembly && assembly.owns()) {
                                                val internalTarget = com.android.purebilibili.core.util.BilibiliNavigationTargetParser.parse(url) is
                                                    com.android.purebilibili.core.util.BilibiliNavigationTarget.PopularFeed
                                                var externalLinkAdmitted = false
                                                ordinaryVideo.factoryFor(assembly).withPresentationAdmission(assembly, source) {
                                                    messageRoutes.callbackFor(entryKey) {
                                                        if (internalTarget) openVideoHonorLink(url)
                                                        else externalLinkAdmitted = true
                                                    }
                                                }
                                                // System browser I/O follows the accepted click outside the session/entry monitor.
                                                if (externalLinkAdmitted) openVideoHonorLink(url)
                                            }
                                        },
                                        login = { loginDialog = true }, danmakuSettings = {
                                            if (danmaku != null && hostWindow != null && danmakuSource != null &&
                                                danmakuSource.request.cid > 0L && ownsDanmakuSource()) originalDanmakuSettingsVisible = true
                                            else error = "当前视频弹幕尚未准备，请稍后重试"
                                        },
                                        toggleDanmaku = ::toggleOriginalDanmaku, notice = { error = it },
                                        focusChanged = { focused -> if(messageRoutes.currentKey==entryKey) playerFocused=focused },
                                        nativeKey = { event -> if(!isClosing() && !activatingUpdate && active && hostVisible && hostDisplayable &&
                                            messageRoutes.currentKey==entryKey) latestRootKeyHandler(event) else false }))
                                if (danmaku != null && hostWindow != null && danmakuSource != null && danmakuSource.request.cid > 0L) {
                                    LaunchedEffect(danmakuAssembly,danmakuSource,danmaku) {
                                        danmakuAssembly.playback.danmakuSentEvent.collect { event ->
                                            // A delayed successful send may remain in the original channel across a source change.
                                            // Consume it once, and only append to its exact accepted native source/load.
                                            if(ownsDanmakuSource())ordinaryVideo.factoryFor(danmakuAssembly).withPresentationAdmission(danmakuAssembly,danmakuSource) {
                                                val session=danmakuAssembly.playback.captureDesktopLoadState()
                                                if(ownsDanmakuSource() && event.nativeSource.sourceVersion==danmakuSource.sourceVersion &&
                                                    event.nativeSource.source==danmakuSource.nativeSource.source &&
                                                    event.bvid==danmakuSource.request.bvid && event.cid==danmakuSource.request.cid &&
                                                    event.loadToken==session.currentLoadRequestToken && event.bvid==session.currentBvid && event.cid==session.currentCid)
                                                    danmaku.addOriginalPortraitDanmaku(danmakuSource.sourceVersion,event.text,event.color,event.mode,event.fontSize)
                                            }
                                        }
                                    }
                                    LaunchedEffect(danmakuAssembly,danmakuSource) {
                                        danmakuAssembly.playback.toastEvent.collect { if(ownsDanmakuSource())error=it.message }
                                    }
                                    DisposableEffect(danmakuAssembly, danmakuSource, entryKey) {
                                        onDispose { originalDanmakuSettingsVisible = false; originalDanmakuPoolVisible = false }
                                    }
                                    DesktopOriginalDanmakuRootHost(
                                        owner = LocalDesktopOriginalCommentRootOwner.current,
                                        repository = repository, globalStore = pluginStore, overlay = danmaku,
                                        cid = danmakuSource.request.cid, sourceVersion = danmakuSource.sourceVersion,
                                        sourceLease = danmakuSource,
                                        stillOwned = ::ownsDanmakuSource, window = hostWindow,
                                        presentation = danmakuPresentation.currentPresentation(),
                                        viewport = with(androidx.compose.ui.platform.LocalDensity.current) {
                                            DesktopDanmakuSettingsViewport(
                                                measuredRootConstraints.maxWidth.toDp().value.toInt().coerceAtLeast(1),
                                                measuredRootConstraints.maxHeight.toDp().value.toInt().coerceAtLeast(1))
                                        },
                                        currentPositionMs = { ((player?.state?.value?.positionSeconds ?: 0.0) * 1_000).toLong() },
                                        seekFromUser = { position -> if (ownsDanmakuSource()) playback.seekTo(position / 1_000.0) },
                                        showSettings = originalDanmakuSettingsVisible, showPool = originalDanmakuPoolVisible,
                                        onShowPool = { if (ownsDanmakuSource()) { originalDanmakuSettingsVisible = false; originalDanmakuPoolVisible = true } },
                                        onDismissSettings = { originalDanmakuSettingsVisible = false },
                                        onDismissPool = { originalDanmakuPoolVisible = false },
                                        enabledChangeVersion = originalDanmakuEnabledChangeVersion,
                                        hotLink = hotDanmakuLink,
                                    )
                                }
                            }
                            entryKey is BiliPaiNavKey.AudioMode ->
                                DesktopOriginalVideoPhysicalLeaf(entryKey, ordinaryVideo, commands, active,
                                    commands::back, ::openVideoHonorLink,
                                    { enabled -> enhancementHostStarted.value = enabled },
                                    pendingOwner = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            ordinaryVideoResourceError?.let { Text(it) } ?: CircularProgressIndicator()
                                        }
                                    })
                            section == DesktopSection.CLOUD_FAVORITES -> {
                                val entry = ensureFavoritesEntry()
                                val queue = favoritesQueueRef.get()
                                if (entry == null || queue == null || !entry.isOwned()) {
                                    TextButton(onClick = { if (!isClosing() && !activatingUpdate) ensureFavoritesEntry() }) { Text("重新打开收藏") }
                                } else {
                                    DesktopDetailWindow {
                                    val prefs = entry.preferences
                                    val bindings = remember(entry, queue, rootTextShareBindings) {
                                        DesktopFavoriteBindings(prefs.showOnlineCount, prefs.homeSettings, prefs.initialHomeSettings(),
                                            prefs.navigationSettings, prefs.initialNavigationSettings(), entry.categories,
                                            queue::openQueue, queue::appendQueue,
                                            share = { subject, text, _ -> requestDesktopTextShare(rootTextShareBindings,
                                                entry.scope, subject, text, entry::isOwned, { error = it }) },
                                            homeFeedCardStyle = prefs.homeFeedCardStyle,
                                            hazeEffectSupported = false,
                                            backToTopEnabled = prefs.backToTopEnabled, initialBackToTopEnabled = prefs.initialBackToTopEnabled(),
                                            backToTopOffset = prefs.backToTopOffset, initialBackToTopOffset = prefs.initialBackToTopOffset(),
                                            setBackToTopOffset = { x, y -> if (entry.isOwned()) prefs.setBackToTopOffset(x, y) },
                                            updateBackToTopOffset = { x, y -> if (entry.isOwned()) prefs.updateBackToTopOffset(x, y) })
                                    }
                                    val detail = (entryKey as? BiliPaiNavKey.SeasonSeriesDetail)?.let {
                                        com.android.purebilibili.feature.list.FavoriteCollectionRoute(it.type,it.id,it.mid,it.title,it.ownerName)
                                    }
                                    favoritesSaveable.SaveableStateProvider(entry.stateKey(detail)) {
                                        DesktopOriginalFavoritesHost(entry.environment, bindings,
                                            onBack = { if (entry.isOwned()) commands.back() },
                                            onVideoClick = { bvid, cid, cover, _ ->
                                                if (entry.isOwned() && !queue.revealIfOwned(bvid, cid, false))
                                                    openVideo(VideoCard(bvid, bvid, cover, "", 0, 0, preferredCid = cid))
                                            }, onUpClick = { if (entry.isOwned()) openUser(it) },
                                            onCollectionClick = { if (entry.isOwned()) commands.push(BiliPaiNavKey.SeasonSeriesDetail(it.type,it.id,it.mid,it.title,it.ownerName)) },
                                            onFolderClick = { mediaId, ownerMid, title, ownerName ->
                                                if (entry.isOwned()) commands.push(BiliPaiNavKey.SeasonSeriesDetail("favorite",mediaId,ownerMid,title,ownerName)) },
                                            onBangumi = { if (entry.isOwned()) openBangumi(it) },
                                            onArticle = { id, _ -> if (entry.isOwned()) openArticle(id) },
                                            onTopic = { if (entry.isOwned()) openTopic(it) },
                                            onCourse = { if (entry.isOwned()) showSeason(it, course = true) },
                                            onWeb = { url, title -> if (entry.isOwned()) openDynamicWeb(url, title) },
                                            onPlayAllAudio = { bvid, cid ->
                                                if (entry.isOwned() && !queue.revealIfOwned(bvid, cid, true)) {
                                                    val fallback = com.android.purebilibili.feature.video.player.PlaylistItem(bvid, cid, bvid, "", "")
                                                    if (queue.openQueue(listOf(fallback), 0, true) != null) queue.revealIfOwned(bvid, cid, true)
                                                    else error = "音频播放器当前不可用"
                                                }
                                            }, detail = detail,
                                            listScopedSearchChannel = entry.searchChannel, scrollToTopChannel = entry.scrollToTopChannel,
                                            isCurrentPage = active && !activatingUpdate,
                                            retainedViewModel = detail?.let(entry::detailViewModel) ?: entry.viewModel,
                                            loadFavoriteViewModelOnEnter = false,
                                            initialSearchQuery = (entryKey as? BiliPaiNavKey.FavoriteSearch)?.query.orEmpty(),
                                            initialSearchScope = (entryKey as? BiliPaiNavKey.FavoriteSearch)?.scope ?: com.android.purebilibili.data.model.response.FavoriteSearchScope.CURRENT_FOLDER,
                                            initialSubscribed = entryKey == BiliPaiNavKey.FavoriteSubscribed,
                                            isSearchDestination = entryKey is BiliPaiNavKey.FavoriteSearch,
                                            onOpenSearchDestination = { value -> commands.push(BiliPaiNavKey.FavoriteSearch(value,(entryKey as? BiliPaiNavKey.FavoriteSearch)?.scope ?: com.android.purebilibili.data.model.response.FavoriteSearchScope.CURRENT_FOLDER)) })
                                    }
                                    }
                                }
                            }
                            entryKey is BiliPaiNavKey.Following -> {
                                val entry=remember(personalLists,entryKey) {personalLists.following(entryKey)}
                                DesktopDetailWindow {
                                    DesktopOriginalFollowingHost(entry,onBack={commands.back()},
                                        onUserClick={mid->openUser(mid)},isCurrentPage=active && !activatingUpdate)
                                }
                            }
                            entryKey == BiliPaiNavKey.WatchLater || entryKey is BiliPaiNavKey.WatchLaterSearch -> {
                                val entry = personalLists.watchLater(entryKey)
                                val queue = entry.queueBridge {
                                    DesktopFavoriteQueueBridge(playback,listen,entry::owns,
                                        {audio -> systemTargetAudio == audio}, beforeOpen = {audio ->
                                            if(!entry.owns() || isClosing() || activatingUpdate ||
                                                !checkpointForNavigation() || (audio && listen == null)) false
                                            else {
                                                changePreferences(preferences.copy(playbackMode = com.bilipai.desktop.player.PlaybackMode.SEQUENTIAL))
                                                storyHost.retire();retainedMedia.stop()
                                                if(audio) {playback.pause();systemTargetAudio=true}
                                                else {listen?.pause();systemTargetAudio=false}
                                                true
                                            }
                                        }, revealVideo = {
                                            playback.state.value.queue.getOrNull(playback.state.value.queueIndex)?.let {card ->
                                                if(entry.owns()) commands.video(BiliPaiNavKey.VideoDetail(card.bvid,
                                                    card.preferredCid,card.cover,sourceRoute=entry.key.toLegacyRoute()))
                                            }
                                        }, revealAudio = {
                                            listen?.state?.value?.current?.let {item -> if(entry.owns()) commands.push(
                                                BiliPaiNavKey.AudioMode(item.bvid,item.cid,
                                                    (listen.player.state.value.positionSeconds*1000L).toLong()))}
                                        })
                                }
                                val bindings = remember(entry,queue,originalHomePreferences) {
                                    DesktopWatchLaterBindings(originalHomePreferences.homeSettings,
                                        originalHomePreferences.navigation,desktopDetailRenderEffectsSupported(),
                                        {items,index,audio,position -> queue.openQueue(items,index,audio,position)})
                                }
                                val navigation = remember(entry,commands) {
                                    val article=DesktopPersonalArticleResolver(repository.ownedHomeCallFactory(
                                        personalLists.gate.epoch,entry::owns),entry::owns)
                                    DesktopPersonalListNavigation({key -> if(entry.owns()) commands.push(key)},
                                        {route -> if(entry.owns()) commands.push(com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey(route))},
                                        article::resolve,{key -> if(entry.owns()) commands.video(key)})
                                }
                                DesktopDetailWindow {
                                    DesktopOriginalWatchLaterHost(entry,bindings,navigation,
                                        onBack={commands.back()},onOpenSearch={commands.push(BiliPaiNavKey.WatchLaterSearch(it))},
                                        revealQueue=queue::revealIfOwned,
                                        onPlayAllAudio={_,_,_ -> error="音频播放器当前不可用"},
                                        searchChannel=personalLists.watchLaterSearchChannel,
                                        scrollToTopChannel=personalLists.watchLaterScrollToTopChannel,
                                        globalHazeState=personalLists.globalHazeState,isCurrentPage=active && !activatingUpdate)
                                }
                            }
                            entryKey == BiliPaiNavKey.History || entryKey is BiliPaiNavKey.HistorySearch || entryKey is BiliPaiNavKey.LikedVideos -> {
                                val entry = if (entryKey is BiliPaiNavKey.LikedVideos) personalLists.liked(entryKey)
                                    else personalLists.history(entryKey)
                                val queue = entry.queueBridge {
                                    DesktopFavoriteQueueBridge(playback, listen, entry::owns,
                                        { audio -> systemTargetAudio == audio },
                                        beforeOpen = { audio ->
                                            if (!entry.owns() || isClosing() || activatingUpdate ||
                                                !checkpointForNavigation() || (audio && listen == null)) false
                                            else {
                                                changePreferences(preferences.copy(playbackMode = com.bilipai.desktop.player.PlaybackMode.SEQUENTIAL))
                                                storyHost.retire(); retainedMedia.stop()
                                                if (audio) { playback.pause(); systemTargetAudio = true }
                                                else { listen?.pause(); systemTargetAudio = false }
                                                true
                                            }
                                        },
                                        revealVideo = {
                                            playback.state.value.queue.getOrNull(playback.state.value.queueIndex)?.let { card ->
                                                if (entry.owns()) commands.video(BiliPaiNavKey.VideoDetail(card.bvid,
                                                    card.preferredCid, card.cover,
                                                    initialVertical = entry.viewModel.uiState.value.items.firstOrNull { it.bvid == card.bvid }?.isVertical == true,
                                                    sourceRoute = entry.key.toLegacyRoute()))
                                            }
                                        }, revealAudio = {
                                            listen?.state?.value?.current?.let { item -> if (entry.owns()) commands.push(
                                                BiliPaiNavKey.AudioMode(item.bvid, item.cid,
                                                    ((listen.player.state.value.positionSeconds) * 1000L).toLong())) }
                                        })
                                }
                                val prefs = personalLists.preferences
                                val bindings = remember(entry, queue, rootTextShareBindings) {
                                    DesktopFavoriteBindings(prefs.showOnlineCount, prefs.homeSettings, prefs.initialHomeSettings(),
                                        prefs.navigationSettings, prefs.initialNavigationSettings(), entry.categories,
                                        { items,index,audio ->
                                            desktopOriginalPersonalQueueStart(entry.viewModel,items,index)?.let { start ->
                                                queue.openQueue(start.first,index,audio,start.second)
                                            }
                                        }, queue::appendQueue,
                                        { subject, text, _ -> requestDesktopTextShare(rootTextShareBindings, entry.scope,
                                            subject, text, entry::owns, { error = it }) }, prefs.homeFeedCardStyle,
                                        desktopDetailRenderEffectsSupported(), prefs.backToTopEnabled, prefs.initialBackToTopEnabled(),
                                        prefs.backToTopOffset, prefs.initialBackToTopOffset(),
                                        { x,y -> if(entry.owns()) prefs.setBackToTopOffset(x,y) },
                                        { x,y -> if(entry.owns()) prefs.updateBackToTopOffset(x,y) })
                                }
                                val personalNavigation = remember(entry, commands) {
                                    val article = DesktopPersonalArticleResolver(repository.ownedHomeCallFactory(
                                        personalLists.gate.epoch, entry::owns), entry::owns)
                                    DesktopPersonalListNavigation(
                                        { key -> if(entry.owns()) commands.push(key) },
                                        { route -> if(entry.owns()) commands.push(com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey(route)) },
                                        article::resolve, { key -> if(entry.owns()) commands.video(key) })
                                }
                                DesktopDetailWindow {
                                    DesktopOriginalPersonalListHost(entry, bindings, personalNavigation,
                                        onBack = { commands.back() }, onUp = { commands.push(BiliPaiNavKey.Space(it)) },
                                        onOpenHistorySearch = { commands.push(BiliPaiNavKey.HistorySearch(it)) },
                                        revealQueue = queue::revealIfOwned,
                                        onPlayAllAudio = { bvid,cid -> if(!queue.revealIfOwned(bvid,cid,true)) error="音频播放器当前不可用" },
                                        historySearchChannel = personalLists.historySearchChannel,
                                        historyScrollToTopChannel = personalLists.historyScrollToTopChannel,
                                        globalHazeState = personalLists.globalHazeState,
                                        isCurrentPage = active && !activatingUpdate)
                                }
                            }
                            section == DesktopSection.COLLECTION -> CommunityCollectionScreen(collectionMid, collectionId, collectionType, community, ::openVideo, ::openUser, { loginDialog = true },
                                space = space, onResource = ::openResource, initialTitle = collectionTitle)
                            section == DesktopSection.PLUGINS -> PluginCenterScreen(pluginRuntime, ::openVideo, ::openQueue, ::openJsPlugin)
                            section == DesktopSection.SETTINGS -> {
                                val mountedSettingsPage = settingsNavigation.current
                                val mountedSettingsHandle = homeRootRef.get()
                                val settingsPageBackOwns = {
                                    active && mountedSettingsHandle != null && homeRootRef.get() === mountedSettingsHandle &&
                                        mountedSettingsHandle.isActive() && mountedSettingsHandle.route.get() === messageRoutes &&
                                        mountedSettingsHandle.retainer.root.value === messageRoutes.root &&
                                        messageRoutes.owns() && messageRoutes.currentKey == entryKey &&
                                        settingsNavigator.state.value.current === mountedSettingsPage && services.imageLifetime.isActive() &&
                                        hostVisible && hostDisplayable && !isClosing() && !activatingUpdate
                                }
                                val ownedSettingsPageBack: () -> Unit = {
                                    if (settingsPageBackOwns()) {
                                        if ((entryKey is BiliPaiNavKey.SettingsCategory && mountedSettingsPage is DesktopSettingsPage.Category) ||
                                            (entryKey == BiliPaiNavKey.SettingsSearch && mountedSettingsPage is DesktopSettingsPage.Search))
                                            messageRoutes.callbackFor(entryKey) { commands.back() }
                                        else messageRoutes.root.entry.gate.commit {
                                            if (settingsPageBackOwns()) settingsNavigator.pop()
                                        }
                                    }
                                }
                                // Home/Playback and About dialogs already register their deeper handlers.
                                // Only the intermediate local category needs this parent-page Back owner.
                                val settingsCategoryBackState = androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None)
                                androidx.navigationevent.compose.NavigationBackHandler(state = settingsCategoryBackState,
                                    isBackEnabled = mountedSettingsPage is DesktopSettingsPage.Category && settingsPageBackOwns(),
                                    onBackCompleted = ownedSettingsPageBack)
                                DesktopSettingsTree(settingsNavigator, settingsSearchController,
                                historyWritesScope = scope, discovery = discovery, privacy = privacyBindings,
                                onCategoryOpen = { category ->
                                    if (com.android.purebilibili.feature.settings.canonicalSettingsRootCategory(category) == SettingsRootCategory.SYSTEM_ABOUT)
                                        messageRoutes.callbackFor(entryKey) { commands.push(BiliPaiNavKey.SettingsCategory(SettingsRootCategory.SYSTEM_ABOUT)) }
                                    else settingsNavigator.openCategory(category)
                                },
                                onOpenSearch = { messageRoutes.callbackFor(entryKey) { commands.push(BiliPaiNavKey.SettingsSearch) } },
                                onSearchResult = { result ->
                                    val ownerCategory = com.android.purebilibili.feature.settings.resolveSettingsRootCategoryForSearchTarget(result.target)
                                    if (ownerCategory == SettingsRootCategory.SYSTEM_ABOUT) {
                                        com.android.purebilibili.feature.settings.resolveSettingsSearchNavigation(result)?.let { target ->
                                            messageRoutes.callbackFor(entryKey) { commands.push(target) }
                                        }
                                    } else settingsNavigator.openSearchResult(result)
                                },
                                onPageBack = ownedSettingsPageBack,

                                onFailure = { error = it.message ?: "设置保存失败" },
                                onDetailBack = {
                                    if (entryKey == BiliPaiNavKey.TipsSettings || entryKey == BiliPaiNavKey.OpenSourceLicenses || entryKey == BiliPaiNavKey.PlaybackSettings || entryKey == BiliPaiNavKey.HomeSettings)
                                        commands.back()
                                    else settingsNavigator.pop()
                                },
                                appearanceContent = { DesktopAppearanceSettings(appearance,
                                    onRestartRequested = { onRestart?.invoke() ?: run { error = "请关闭并重新打开客户端以完成语言切换。" } },
                                    onNavigateToIconSettings = { messageRoutes.callbackFor(entryKey) { commands.push(BiliPaiNavKey.IconSettings) } }) },
                                pluginsContent = { PluginCenterScreen(pluginRuntime, ::openVideo, ::openQueue, ::openJsPlugin) },
                                playbackContent = { page, back ->
                                    DesktopOriginalPlaybackSettingsRootHost(messageRoutes, homeRootRef, entryKey, page,
                                        settingsNavigator, globalPluginContext, repository, services.imageLifetime,
                                        originalHomePreferences.homeSettings, originalHardwareDecodePreferences,
                                        legacyHardwareDecodeFallback,
                                        active = active && hostVisible && hostDisplayable && !isClosing() && !activatingUpdate,
                                        pictureInPictureAvailable = { pip != null && player != null && !isClosing() && !activatingUpdate },
                                        onFailure = { error = it.message ?: "播放设置保存失败" }, onNotice = { error = it },
                                        logSettingChange = { name, value ->
                                            com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge.record("INFO", "PlaybackSettings", "$name=$value")
                                        }, onBack = back)
                                },
                                backupContent = { target, dismiss -> BackupSettingsDialog(backup, dismiss, onExit,
                                    initialSection = requireNotNull(resolveDesktopBackupEntrySection(target))) },
                                blockedListContent = { DesktopBlockedListScreen(community.blockedUpRepository, onLogin = { loginDialog = true }) },
                                donateContent = { donateEntry, dismiss ->
                                    val capturedHandle = homeRootRef.get()
                                    val currentDonateActive by rememberUpdatedState(active)
                                    if (capturedHandle != null && capturedHandle.isActive() && active && hostWindow != null) {
                                        val donateBindings = remember(donateEntry, capturedHandle, hostWindow, services.imageLifetime) {
                                            DesktopDonateDialogBindings(hostWindow,
                                                owns = { currentDonateActive && donateEntry.ownsCurrent() &&
                                                    homeRootRef.get() === capturedHandle && capturedHandle.isActive() &&
                                                    services.imageLifetime.isActive() &&
                                                    !isClosing() && !activatingUpdate },
                                                admit = services.imageLifetime::withCommit,
                                                onDismiss = dismiss)
                                        }
                                        com.android.purebilibili.feature.settings.DesktopOriginalDonateDialog(donateBindings)
                                    }
                                },
                                homeContent = { page, back ->
                                    val capturedHandle = homeRootRef.get()
                                    val currentHomeSettingsActive by rememberUpdatedState(active)
                                    val homeSettingsServices = LocalDesktopOriginalHomeSettingsRootServices.current
                                    if (capturedHandle != null && capturedHandle.isActive() && active) {
                                        val owns = { currentHomeSettingsActive && homeRootRef.get() === capturedHandle &&
                                            capturedHandle.isActive() && capturedHandle.route.get() === messageRoutes &&
                                            settingsNavigator.state.value.current === page && services.imageLifetime.isActive() &&
                                            !isClosing() && !activatingUpdate }
                                        val admit: ((() -> Unit) -> Boolean) = { action ->
                                            var applied = false
                                            val accepted = messageRoutes.root.entry.gate.commit {
                                                services.imageLifetime.withCommit {
                                                    if (owns()) { action(); applied = true }
                                                }
                                            }
                                            accepted && applied
                                        }
                                        DesktopOriginalHomeSettingsRootHost(page, homeSettingsServices, globalPluginContext,
                                            owns = owns, admit = admit, onBack = { if (owns()) back() },
                                            onNotice = { if (owns()) services.feedback(it) },
                                            onFailure = { if (owns()) error = it.message ?: "首页设置保存失败" })
                                    }
                                },
                                commentFraudHistoryContent = { page, back ->
                                    val capturedHandle = homeRootRef.get()
                                    val currentHistoryActive by rememberUpdatedState(active)
                                    if (capturedHandle != null && capturedHandle.isActive() && active && hostWindow != null) {
                                        key(page, capturedHandle, commentFraud) {
                                            val pageScope = rememberCoroutineScope()
                                            val notices = remember { SnackbarHostState() }
                                            val bindings = remember(page, commentFraud, capturedHandle, hostWindow, services.imageLifetime) {
                                                DesktopCommentFraudHistoryBindings(commentFraud.records, pageScope, hostWindow,
                                                    owns = { currentHistoryActive && homeRootRef.get() === capturedHandle &&
                                                        capturedHandle.isActive() && settingsNavigator.state.value.current === page &&
                                                        commentFraud.isOwned() && services.imageLifetime.isActive() &&
                                                        !isClosing() && !activatingUpdate },
                                                    admit = services.imageLifetime::withCommit,
                                                    onNotice = { message -> pageScope.launch { notices.showSnackbar(message) } },
                                                    onFailure = { failure -> pageScope.launch { notices.showSnackbar(failure.message ?: "发评反诈历史处理失败") } })
                                            }
                                            DisposableEffect(bindings) { onDispose { bindings.close() } }
                                            val ownedBack: () -> Unit = {
                                                bindings.uiAction {
                                                    services.imageLifetime.withCommit { if (bindings.isOwned()) back() }
                                                }
                                            }
                                            val backState = androidx.navigationevent.compose.rememberNavigationEventState(androidx.navigationevent.NavigationEventInfo.None)
                                            androidx.navigationevent.compose.NavigationBackHandler(state = backState,
                                                isBackEnabled = bindings.isOwned(), onBackCompleted = ownedBack)
                                            Box(Modifier.fillMaxSize()) {
                                                com.android.purebilibili.feature.settings.screen.DesktopOriginalCommentFraudHistoryScreen(bindings, onBack = ownedBack)
                                                SnackbarHost(notices, Modifier.align(Alignment.BottomCenter))
                                            }
                                        }
                                    }
                                },
                                storageContent = { target ->
                                    val imagePath by imageSavePreferences.getImageSaveTreeUri().collectAsState(imageSavePreferences.getImageSaveTreeUriSync())
                                    com.bilipai.desktop.settings.DesktopStorageSettings(storageOwner,imagePath,target,
                                        chooseDirectory = { selectDynamicSaveDirectory(hostWindow,"选择新任务的下载目录") },
                                        openDetail = { settingsNavigator.openDetail(it,null) },
                                        onFailure = { error=it.message ?: "存储设置处理失败" },
                                        onMessage = { imageSaveMessage=it })
                                    imageSaveMessage?.let { Text(it,Modifier.padding(12.dp)) }
                                },
                                imageSavePathContent = { openInitially ->
                                    DesktopImageSavePathSettings(imageSavePreferences, scope,
                                        chooseDirectory = { selectDynamicSaveDirectory(hostWindow, "选择图片目录") },
                                        stillOwned = imageSaveLifetime::isActive,
                                        onFailure = { error = it.message ?: "图片保存位置设置失败" },
                                        onMessage = { imageSaveMessage = it }, openInitially = openInitially)
                                    imageSaveMessage?.let { Text(it, Modifier.padding(12.dp)) }
                                },
                                systemContent = { aboutPage, requestDonate ->
                                    DesktopOriginalSystemAboutRootHost(messageRoutes, entryKey, aboutPage, settingsNavigator,
                                        homeRootRef, globalPluginContext, services.imageLifetime, active,
                                        onWindowsUpdate = { updatesDialog = true; scope.launch { updater.check() } },
                                        onDonate = requestDonate,
                                        onFailure = { error = it.message ?: "系统与关于操作失败" },
                                        onNotice = { error = it })
                                    diagnostics?.let { localDiagnostics ->
                                        Text("本地诊断", style = MaterialTheme.typography.titleMedium)
                                        DesktopDiagnosticSettingsSection(localDiagnostics,
                                            onLocalLogs = { showDiagnosticViewer = true },
                                            onFailure = { error = "诊断设置保存失败，请重试" },
                                            modifier = Modifier.fillMaxWidth())
                                    }
                                    diagnosticStartupError?.let { Text(it, Modifier.padding(12.dp), color = scheme.error) }
                                    com.bilipai.desktop.settings.DesktopNetworkProxySettings(globalPluginContext, repository.httpClient,
                                        onFailure = { error = it.message ?: "代理设置保存失败" })
                                })
                            }
                            section == DesktopSection.APPEARANCE -> DesktopAppearanceSettings(appearance,
                                onRestartRequested = { onRestart?.invoke() ?: run { error = "请关闭并重新打开客户端以完成语言切换。" } },
                                onNavigateToIconSettings = { messageRoutes.callbackFor(entryKey) { commands.push(BiliPaiNavKey.IconSettings) } })
                            section == DesktopSection.JS_CONTENT -> Column(Modifier.fillMaxSize()) {
                                TextButton(onClick = { commands.back() }) { Text("返回插件") }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    DesktopJsPluginContentScreen(pluginRuntime.jsPlugins, jsPluginId,
                                        onPlayMedia = ::openJsMedia, onFeedModule = { _, _ -> jsSubscriptionReader = true }, onBack = { commands.back() })
                                }
                            }
                            section == DesktopSection.EXTERNAL_MEDIA -> DesktopExternalMediaScreen(retainedMedia.external, player, playerContent,
                                onBack = { commands.back() })
                            section == DesktopSection.LIVE -> LiveBrowserScreen(repository, player, playerError, { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, roomId, danmaku, retainedMedia)
                            section == DesktopSection.BANGUMI -> BangumiBrowserScreen(repository, player, playerError, { mediaActive = it; if (it) listen?.pause() }, downloads, onToggleFullscreen, playerContent, seasonId, danmaku,
                                initialIsCourse = isCourse, initialEpisodeId = episodeId, initialProgressSeconds = seasonProgress, initialSeasonType = seasonType, retained = retainedMedia, community = community)
                            entryKey == BiliPaiNavKey.DownloadList -> {
                                val owner = requireNotNull(homeRootRef.get()?.retainer?.current()).entry.gate
                                val binding = remember(owner) { DesktopOriginalDownloadListBindings(globalPluginContext,downloads,owner.scope,
                                    owner::owns,owner::commit,::desktopDownloadNetworkAvailable,{error=it}) }
                                DesktopOriginalDownloadListHost(binding,{commands.back()},
                                    {bvid->commands.video(BiliPaiNavKey.VideoDetail(bvid,sourceRoute="download_list"))},
                                    {taskId->commands.push(BiliPaiNavKey.OfflineVideoPlayer(taskId))})
                            }
                            entryKey is BiliPaiNavKey.OfflineVideoPlayer -> {
                                val owner = requireNotNull(homeRootRef.get()?.retainer?.current()).entry.gate
                                val offlineEntryScope = rememberCoroutineScope()
                                val binding = remember(owner,entryKey.taskId,offlineEntryScope) { DesktopOfflineTaskPlayerBinding(downloads,retainedMedia,danmaku,
                                    offlineEntryScope,owner.epoch,{repository.sessionEpoch},{owner.owns() && offlineEntryScope.isActive},owner::commit,
                                    ::desktopDownloadNetworkAvailable,{playerError}) }
                                DesktopOriginalOfflineRootHost(entryKey.taskId,binding,offlineEntryScope,globalPluginContext,
                                    originalDanmakuPreferences,danmakuPresentation,systemMedia,pip,pipActive,hostWindow,
                                    isFullscreen,::setOriginalFullscreen,{homeRootRef.get()?.refreshCurrentRootChrome()},
                                    {commands.back()},{error=it})
                            }
                            entryKey is BiliPaiNavKey.WeeklySeries -> DesktopWeeklySeriesScreen(discovery.weeklySeriesRequests(),repository,entryKey.number,{commands.back()},
                                {video,list -> val cards=list.map{VideoCard(it.bvid,it.title,it.pic,it.owner.name,it.stat.view.toLong(),it.duration,preferredCid=it.cid)};
                                    openQueue(cards,cards.firstOrNull{it.bvid==video.bvid} ?: VideoCard(video.bvid,video.title,video.pic,video.owner.name,video.stat.view.toLong(),video.duration,preferredCid=video.cid))},isClosing=isClosing)
                            entryKey == BiliPaiNavKey.Login -> Column(Modifier.fillMaxSize()) {
                                TextButton(onClick={commands.back()}) {Text("返回")}
                                AdvancedLoginDialog(repository,onDismiss={commands.back()},onComplete={commands.back()})
                            }
                            entryKey is BiliPaiNavKey.Web -> Column {
                                Text(entryKey.title);TextButton(onClick={openDynamicWeb(entryKey.url,entryKey.title)}){Text("在浏览器打开")}
                            }
                            section == DesktopSection.LISTEN -> if (listen != null) ListenBrowserScreen(listen, preferences, ::changePreferences, ::openVideo, { loginDialog = true })
                                else Text(playerError ?: "音频播放器未能初始化")
                            section == DesktopSection.BGM -> bgmRequest?.let { request ->
                                DesktopBgmDetailRootHost(request, repository, community, commentFraud,
                                    nowPlayingBarOverlayVisible = listen?.state?.value?.current != null,
                                    onBack = ::closeBgm,
                                    onVideosClick = { commands.push(BiliPaiNavKey.BgmDetail(request.musicId,request.aid,request.cid,true)) },
                                    onVideoClick = { bvid, cid, cover -> openVideo(VideoCard(bvid, "", cover, "", 0, 0, preferredCid = cid)) },
                                    onUserClick = ::openUser, onLinkClick = { raw ->
                                        runCatching { java.net.URI(imageUrl(raw)) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.let {
                                            runCatching { java.awt.Desktop.getDesktop().browse(it) }
                                        }
                                    }, onLogin = { loginDialog = true },
                                    onMediaSearch = { _, title, _ -> commands.push(BiliPaiNavKey.Search(title)) })
                            }
                            section == DesktopSection.MUSIC -> {
                                val source = musicSource
                                if (listen != null && source != null) DesktopNativeMusicDetailScreen(source, listen, preferences, ::changePreferences,
                                    ::closeMusic, ::openUser, ::openVideo, startPositionSeconds = musicStartPosition)
                                else Text(playerError ?: "请从个人空间的音频栏目打开歌曲")
                            }
                            entryKey is BiliPaiNavKey.Story ->
                                DesktopOriginalStoryPhysicalLeaf(entryKey, ordinaryVideo, commands, active && !activatingUpdate,
                                    pendingOwner = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            ordinaryVideoResourceError?.let { Text(it) } ?: CircularProgressIndicator()
                                        }
                                    })
                            section == DesktopSection.TOPIC -> DesktopTopicDetailScreen(topicId, storyTopic, community,
                                CommunityNavigation(::openVideo, ::openUser, ::openArticle, { loginDialog = true }, ::openLive, ::openBangumi, ::openDynamic, ::openTopic, ::openTopicKeyword,
                                    onDynamicRoute=::openDynamicRoute,onMessageLink=messageLink),
                                onBack = { commands.back() }, onTopic = ::openTopic)
                            entryKey is BiliPaiNavKey.ArticleDetail -> {
                                val articleHomeSettings by originalHomePreferences.homeSettings.collectAsState()
                                DesktopDetailWindow {
                                    DesktopOriginalArticleRootHost(entryKey, personalLists, commands,
                                        transitionEnabled = articleHomeSettings.cardTransitionEnabled)
                                }
                            }
                            section in listOf(DesktopSection.DYNAMIC, DesktopSection.NOTES) ->
                                CommunityContentScreen(when(section) {
                                    DesktopSection.DYNAMIC -> CommunitySection.DYNAMIC
                                    DesktopSection.SEARCH -> CommunitySection.SEARCH
                                    DesktopSection.USER -> CommunitySection.USER
                                    DesktopSection.ARTICLE -> CommunitySection.ARTICLE
                                    else -> CommunitySection.NOTES
                                }, repository, social, community, submitted, userId, articleId, noteVideo,
                                    ::openVideo, ::openUser, ::openArticle, { loginDialog = true }, ::openLive, ::openBangumi, runtime = pluginRuntime,
                                    initialDynamicId = dynamicRoute?.dynamicId.takeIf { section == DesktopSection.DYNAMIC }, onTopic = ::openTopic, onTopicKeyword = ::openTopicKeyword,
                                    defaultSearchHintEnabled = defaultSearchHintEnabled,
                                    initialCommentRootRpid=dynamicRoute?.rootReplyId?:0L,initialCommentTargetRpid=dynamicRoute?.targetReplyId?:0L)
                            else -> Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                                Text("该原版页面的平台闭包仍在接入中：${entryKey.toLegacyRoute()}")
                                TextButton(onClick={commands.back()}) { Text("返回") }
                            }
                        }

                        }
                    }
                    }
                }
            }
            }
        }
        if (eyePaint.dimAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = eyePaint.dimAlpha)))
        if (eyePaint.warmAlpha > 0f) Box(Modifier.fillMaxSize().background(Color(eyePaint.warmArgb).copy(alpha = eyePaint.warmAlpha)))
        if (hostDisplayable && hostVisible && !isClosing() && !activatingUpdate) {
            SnackbarHost(rootFeedback, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        }
        if (loginDialog) AdvancedLoginDialog(repository, onDismiss = { loginDialog = false }, onComplete = { loginDialog = false })
        if (enhancementSettings) DesktopVideoEnhancementSettingsDialog(pluginRuntime.enhancementConfiguration) { enhancementSettings = false }
        if (combinedBackupSettings) BackupSettingsDialog(backup, { combinedBackupSettings = false }, onExit)
        if (showDiagnosticViewer && diagnostics != null) {
            DesktopLocalDiagnosticViewer(diagnostics,
                onDismiss = { showDiagnosticViewer = false },
                chooseExportPath = { chooseDesktopDiagnosticExportFile(hostWindow) })
        }
        if (jsSubscriptionReader) SubscriptionReaderDialog(pluginRuntime, refreshOnOpen = true) { jsSubscriptionReader = false }
        if (castDialog) AlertDialog(onDismissRequest = { castDialog = false }, title = { Text("选择投屏方式") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { scope.launch {
                    try { pluginRuntime.setEnabled(pluginRuntime.dlnaCast.id, true); castDialog = false; dlnaDialog = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "DLNA 启用失败，请在插件页面检查配置" }
                } }, modifier = Modifier.fillMaxWidth()) { Text("DLNA / 电视媒体播放") }
                OutlinedButton(onClick = { scope.launch {
                    try { pluginRuntime.setEnabled("google_cast", true); castDialog = false; googleCastDialog = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Google Cast 启用失败，请在插件页面检查配置" }
                } }, modifier = Modifier.fillMaxWidth()) { Text("Google Cast / Chromecast") }
            }
        }, confirmButton = { TextButton(onClick = { castDialog = false }) { Text("关闭") } })
        if (dlnaDialog) DesktopCastDialog(cast, media = castMediaFactory, onDismiss = { dlnaDialog = false })
        if (googleCastDialog) DesktopGoogleCastDialog(pluginRuntime.context, pluginRuntime.googleCast,
            media = castMediaFactory, onDismiss = { googleCastDialog = false })
        PluginCareReminder(pluginRuntime)
        if (updatesDialog) WindowsUpdateDialog(updateState, automaticUpdates, activatingUpdate,
            playing.details != null || playing.opening || mediaActive || listening.active || anyCasting || anyCastBusy || pipActive,
            onAutomatic = { automaticUpdates = it; settingsLibrary.setAutomaticUpdates(it) },
            onPrepare = { prepareUpdate(it, true) }, onActivate = { manuallyRequested = true }, onDismiss = { updatesDialog = false })
    }
    }
    }
}

private fun VideoDetails.asCard() = VideoCard(bvid, title, cover, author, playCount, pages.firstOrNull()?.duration?.toInt() ?: 0, authorMid = authorMid)

@Composable
private fun DesktopVideoPage(playing: DesktopPlaybackState, player: MpvPlayer?, playerContent: @Composable (MpvPlayer) -> Unit,
    favorite: Boolean, onVideo: (VideoCard) -> Unit, onPart: (Int) -> Unit, onQuality: (Int) -> Unit, onFavorite: () -> Unit,
    engagement: @Composable () -> Unit, onDownload: (Int, com.android.purebilibili.feature.download.DownloadOptions) -> Unit, onCast: () -> Unit, onStory: () -> Unit,
    blockedUps: DesktopBlockedUpRepository, onLogin: () -> Unit) {
    val info = playing.details ?: return
    var showDownloadQuality by remember(info.bvid, playing.currentPart) { mutableStateOf(false) }
    if (showDownloadQuality) com.android.purebilibili.feature.download.DownloadQualityDialog(
        title = info.title, qualityOptions = playing.availableQualities.map { it.id to it.label },
        currentQuality = playing.effectiveQuality.takeIf { it > 0 } ?: playing.quality,
        onQualitySelected = { quality, options -> showDownloadQuality = false; onDownload(quality, options) },
        onDismiss = { showDownloadQuality = false })
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (player != null) playerContent(player)
            Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                playing.availableQualities.forEach { option ->
                    FilterChip(playing.effectiveQuality == option.id, { onQuality(option.id) }, label = { Text(option.label) })
                }
                OutlinedButton(onClick = onFavorite) { Text(if (favorite) "已存本地收藏" else "本地收藏") }
                OutlinedButton(onClick = { showDownloadQuality = true }, enabled = playing.availableQualities.isNotEmpty()) { Text("下载本集") }
                OutlinedButton(onClick = onCast) { Text("投屏") }
                OutlinedButton(onClick = onStory) { Text("竖屏播放") }
            }
            if (playing.effectiveQuality > 0 && playing.effectiveQuality != playing.quality)
                Text("服务器返回画质：${playing.availableQualities.firstOrNull { it.id == playing.effectiveQuality }?.label ?: playing.effectiveQuality}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (info.pages.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                info.pages.forEachIndexed { index, part -> FilterChip(playing.currentPart == index, { onPart(index) }, label = { Text("P${index + 1} ${part.title}") }) }
            }
            Text(info.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            engagement()
        }
        LazyColumn(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("相关推荐", style = MaterialTheme.typography.titleMedium) }
            items(playing.related, key = { it.bvid }) { card ->
                Column {
                    FeedCard(card) { onVideo(card) }
                    DesktopBlockedUpAction(blockedUps, card.authorMid, card.author, face = "", onLogin = onLogin)
                }
            }
        }
    }
}

@Composable
private fun WindowsUpdateDialog(state: UpdateState, automatic: Boolean, activating: Boolean, playbackActive: Boolean,
    onAutomatic: (Boolean) -> Unit, onPrepare: (WindowsUpdate) -> Unit, onActivate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Windows 更新") }, text = {
        Column(Modifier.width(380.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(when(val status = state) {
                is UpdateState.Disabled -> status.reason
                UpdateState.Idle -> "等待检查更新"
                UpdateState.Checking -> "正在检查…"
                is UpdateState.UpToDate -> "已是最新版本 ${status.version}"
                is UpdateState.Available -> "新版本 ${status.update.version}，${status.update.size / 1024 / 1024} MB"
                is UpdateState.Downloading -> "正在下载：${status.receivedBytes / 1024 / 1024} / ${status.totalBytes / 1024 / 1024} MB"
                UpdateState.Verifying -> "正在校验更新包"
                is UpdateState.Prepared -> if (playbackActive) "更新已下载，关闭播放后安装" else "更新已下载，等待安装"
                UpdateState.Launching -> "正在启动新版本"
                UpdateState.Launched -> "新版本已启动"
                is UpdateState.Failed -> status.message
            })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("自动更新，播放时延后", Modifier.weight(1f)); Switch(automatic, onAutomatic, enabled = !activating) }
        }
    }, confirmButton = {
        when(val status = state) {
            is UpdateState.Available -> TextButton(onClick = { onPrepare(status.update) }, enabled = !activating) { Text("下载并更新") }
            is UpdateState.Prepared -> TextButton(onClick = onActivate, enabled = !activating) { Text(if (playbackActive) "关闭播放后更新" else "安装更新") }
            else -> TextButton(onClick = onDismiss) { Text("完成") }
        }
    })
}

private fun desktopReadySection(key:BiliPaiNavKey):DesktopSection = when(key) {
    is BiliPaiNavKey.VideoDetail -> DesktopSection.HOME
    BiliPaiNavKey.Dynamic,is BiliPaiNavKey.DynamicDetail -> DesktopSection.DYNAMIC
    is BiliPaiNavKey.Search -> DesktopSection.SEARCH
    is BiliPaiNavKey.Space -> DesktopSection.USER
    is BiliPaiNavKey.ArticleDetail -> DesktopSection.ARTICLE
    is BiliPaiNavKey.TopicDetail -> DesktopSection.TOPIC
    is BiliPaiNavKey.Story -> DesktopSection.STORY
    is BiliPaiNavKey.BgmDetail -> DesktopSection.BGM
    is BiliPaiNavKey.MusicDetail,is BiliPaiNavKey.NativeMusic -> DesktopSection.MUSIC
    BiliPaiNavKey.ListenVideo,is BiliPaiNavKey.AudioMode -> DesktopSection.LISTEN
    is BiliPaiNavKey.Live -> DesktopSection.LIVE
    is BiliPaiNavKey.Bangumi,is BiliPaiNavKey.BangumiDetail,is BiliPaiNavKey.BangumiReview,is BiliPaiNavKey.BangumiPlayer -> DesktopSection.BANGUMI
    BiliPaiNavKey.DownloadList,is BiliPaiNavKey.OfflineVideoPlayer -> DesktopSection.DOWNLOADS
    BiliPaiNavKey.Favorite,BiliPaiNavKey.FavoriteSubscribed,is BiliPaiNavKey.FavoriteSearch,is BiliPaiNavKey.SeasonSeriesDetail -> DesktopSection.CLOUD_FAVORITES
    BiliPaiNavKey.History,is BiliPaiNavKey.HistorySearch -> DesktopSection.CLOUD_HISTORY
    BiliPaiNavKey.WatchLater,is BiliPaiNavKey.WatchLaterSearch -> DesktopSection.WATCH_LATER
    is BiliPaiNavKey.Following -> DesktopSection.FOLLOWINGS
    is BiliPaiNavKey.LikedVideos -> DesktopSection.LIKED
    BiliPaiNavKey.Inbox,BiliPaiNavKey.ReplyMe,BiliPaiNavKey.AtMe,BiliPaiNavKey.LikeMe,BiliPaiNavKey.SystemNotice,is BiliPaiNavKey.Chat,is BiliPaiNavKey.CommentDetail -> DesktopSection.MESSAGES
    is BiliPaiNavKey.PluginsSettings -> DesktopSection.PLUGINS
    is BiliPaiNavKey.JsPluginContent -> DesktopSection.JS_CONTENT
    is BiliPaiNavKey.ExternalMedia -> DesktopSection.EXTERNAL_MEDIA
    BiliPaiNavKey.AppearanceSettings -> DesktopSection.APPEARANCE
    BiliPaiNavKey.Settings,is BiliPaiNavKey.SettingsCategory,BiliPaiNavKey.SettingsSearch,
        BiliPaiNavKey.HomeSettings,BiliPaiNavKey.IconSettings,BiliPaiNavKey.AnimationSettings,
        BiliPaiNavKey.PlaybackSettings,BiliPaiNavKey.PermissionSettings,BiliPaiNavKey.MessageNotificationSettings,
        BiliPaiNavKey.BottomBarSettings,BiliPaiNavKey.SettingsShare,BiliPaiNavKey.WebDavBackup,
        BiliPaiNavKey.TipsSettings,BiliPaiNavKey.OpenSourceLicenses -> DesktopSection.SETTINGS
    else -> DesktopSection.UNSUPPORTED // Explicit unsupported leaf; never Home or Notes.
}
