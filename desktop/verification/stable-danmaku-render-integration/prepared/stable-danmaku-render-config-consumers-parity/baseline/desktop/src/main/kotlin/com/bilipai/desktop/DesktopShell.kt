package com.bilipai.desktop

import com.bilipai.desktop.appearance.DesktopAppearanceSettings
import com.bilipai.desktop.appearance.DesktopAppearanceTheme
import com.bilipai.desktop.appearance.DesktopThemePrefs
import com.bilipai.desktop.appearance.DesktopStrings
import com.bilipai.desktop.appearance.LocalDesktopStrings
import com.bilipai.desktop.appearance.WindowsTextClipboard

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
import com.bilipai.desktop.player.DesktopVideoEnhancementSession
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.bilipai.desktop.plugins.DesktopVideoShaderResources
import com.android.purebilibili.feature.plugin.Anime4KPlugin
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
import kotlinx.coroutines.flow.collect

private enum class DesktopSection(val label: String, val symbol: String) {
    HOME("推荐", "⌂"), POPULAR("热门", "◉"), REGION("分区", "▦"), RANKING("排行榜", "↗"), PRECIOUS("入站必刷", "★"), WEEKLY("每周必看", "▤"),
    DYNAMIC("动态", "▤"), LIVE("直播", "◉"), BANGUMI("番剧影视", "▷"), PLUGINS("插件", "◇"),
    CLOUD_HISTORY("云端历史", "◷"), CLOUD_FAVORITES("云端收藏", "♥"), WATCH_LATER("稍后再看", "▣"),
    FOLLOWINGS("我的关注", "♧"), LIKED("赞过的视频", "♥"), LISTEN("听视频", "♫"), DOWNLOADS("下载与离线", "↓"), MESSAGES("消息", "✉"),
    HISTORY("本地历史", "◷"), FAVORITES("本地收藏", "♡"), SEARCH("搜索", "⌕"), USER("UP 主空间", "♧"),
    ARTICLE("专栏", "▤"), NOTES("视频笔记", "✎"), COLLECTION("合集与系列", "▣"),
    JS_CONTENT("JS 插件内容", "◇"), EXTERNAL_MEDIA("外部媒体", "▷"), APPEARANCE("外观设置", "◐"), MUSIC("音乐详情", "♫"), BGM("背景音乐详情", "♫"),
    STORY("竖屏播放", "▯"), TOPIC("话题", "#"), SETTINGS("设置", "⚙")
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
    danmakuPresentation: DesktopDanmakuPresentationBinding) {
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
            danmakuPresentation = danmakuPresentation)
    }
    diagnosticLifecycle?.crashPrompt?.let { prompt ->
        DesktopAppearanceTheme(startupTheme) {
            DesktopCrashPromptHost(prompt) { chooseDesktopDiagnosticExportFile(hostWindow) }
        }
    }
}

@Composable
private fun DesktopReadyApp(repository: DesktopRepository, player: MpvPlayer?, playerError: String?, initialVideo: String?,
    onExit: () -> Unit, onToggleFullscreen: () -> Unit, hostWindow: java.awt.Window?,
    registerShutdown: ((suspend () -> Unit) -> Unit)?, onRestart: (() -> Unit)?,
    pluginStore: DesktopPluginStore, discovery: DesktopDiscoveryRepository,
    closeDiscoveryStorage: () -> Unit, isClosing: () -> Boolean,
    diagnosticLifecycle: DesktopDiagnosticLifecycle?, diagnosticStartupError: String?,
    danmakuPresentation: DesktopDanmakuPresentationBinding) {
    val diagnostics = diagnosticLifecycle?.diagnostics
    val account by repository.account.collectAsState()
    val sessionEpoch by repository.sessionEpochFlow.collectAsState()
    val settingsLibrary = remember { DesktopLibrary() }
    val library = remember(account?.mid) { if (account == null) settingsLibrary else DesktopLibrary(DesktopLibrary.directoryForAccount(account?.mid)) }
    val preferenceStore = remember { PlayerPreferencesStore() }
    val preferenceWriter = remember(preferenceStore) { DesktopPlayerPreferencesWriter(preferenceStore::save) }
    val preferenceWriteFailed by preferenceWriter.failed.collectAsState()
    var preferences by remember { mutableStateOf(preferenceStore.read().let { it.copy(speed = it.preferredSpeed) }) }
    val latestPreferences by rememberUpdatedState(preferences)
    val scope = rememberCoroutineScope()
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
                favoritesQueueRef.getAndSet(null)?.close()
                favoritesEntryRef.getAndSet(null)?.shutdownForRestore()
                imageSaveLifetime.close()
                dynamicCache.shutdownForRestore()
                diagnosticLifecycle?.shutdownForRestore()
            })
    }
    val globalPluginContext = pluginRuntime.context
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
    val downloads = remember(repository) { DesktopDownloadManager(repository) }
    val danmaku = remember(player, repository) { player?.let { DanmakuOverlay(it, httpClient = repository.httpClient) } }
    val subtitleAssets = remember(repository) { DesktopSubtitleAssets(repository.httpClient) }
    val automaticSubtitles = remember(player, community, subtitleAssets, library) { player?.let {
        DesktopAutomaticSubtitles(RepositoryAutomaticSubtitleDataSource(community, subtitleAssets), MpvAutomaticSubtitlePlayer(it),
            sessionEpoch = { repository.sessionEpoch })
    } }
    val emptyAutomaticSubtitles = remember { MutableStateFlow(DesktopAutomaticSubtitleState()) }
    val automaticSubtitleState by (automaticSubtitles?.state ?: emptyAutomaticSubtitles).collectAsState()
    LaunchedEffect(automaticSubtitles, sessionEpoch) { automaticSubtitles?.onSessionChanged() }
    val playback = remember(repository, player, danmaku, library) {
        DesktopPlaybackController(repository, player, playerError, danmaku, library, { latestPreferences }, scope,
            plugins = pluginRuntime, community = community, automaticSubtitles = automaticSubtitles,
            currentDanmakuSettings = { latestRendererDanmakuSettings },
            onRememberAudioQuality = { quality ->
                preferences = preferences.copy(lastSelectedAudioQuality = quality).normalized()
                val snapshot = preferences
                preferenceWriter.submit(snapshot)
            })
    }
    val beforeStoryAcquire = remember { java.util.concurrent.atomic.AtomicReference<() -> Unit>({}) }
    val storyHost = remember(playback, repository) { DesktopStoryPlaybackHost(ControllerStoryQueuePlayer(playback),
        { repository.sessionEpoch }, { beforeStoryAcquire.get().invoke() }) }
    val storyOwner by storyHost.owner.collectAsState()
    var systemTargetAudio by remember { mutableStateOf(false) }
    val audioPlayer = remember(player) { player?.let { MpvPlayer() } }
    val diagnosticObservers = remember(diagnosticLifecycle, player, audioPlayer) {
        listOfNotNull(
            player?.let { diagnosticLifecycle?.observePlayback(it.state, scope) },
            audioPlayer?.let { diagnosticLifecycle?.observePlayback(it.state, scope) },
        )
    }
    DisposableEffect(diagnosticObservers) { onDispose { diagnosticObservers.forEach { it.cancel() } } }
    val beforeListenAcquire = remember { java.util.concurrent.atomic.AtomicReference<() -> Unit>({}) }
    val listen = remember(repository, community, audioPlayer, playback, account?.mid, sessionEpoch) {
        audioPlayer?.let { ListenAudioSession(repository, community, it, preferences, onAcquirePlayback = { beforeListenAcquire.get().invoke(); systemTargetAudio = true; playback.pause(); player?.setPaused(true) },
            store = ListenAudioStore(DesktopLibrary.directoryForAccount(account?.mid).resolve("listen-state.json"))) }
    }
    val emptyListenState = remember { MutableStateFlow(ListenAudioState()) }
    val listening by (listen?.state ?: emptyListenState).collectAsState()
    val playing by playback.state.collectAsState()
    val chapterIdentity = playing.details?.let { info ->
        info.pages.getOrNull(playing.currentPart)?.cid?.takeIf { it > 0 }?.let { info.bvid to it }
    }
    var chapterViewPoints by remember(chapterIdentity, sessionEpoch) {
        mutableStateOf<List<com.android.purebilibili.data.model.response.ViewPoint>>(emptyList())
    }
    LaunchedEffect(community, chapterIdentity, sessionEpoch) {
        val identity = chapterIdentity ?: return@LaunchedEffect
        try {
            val points = community.playerMetadata(identity.first, identity.second).viewPoints
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repository.sessionEpoch == sessionEpoch) chapterViewPoints = points
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional player metadata must not interrupt playback. */ }
    }
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
    var jsSettingsOrigin by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(initialVideo != null) }
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
    var mediaPrevious by remember { mutableStateOf<(() -> Unit)?>(null) }
    var mediaNext by remember { mutableStateOf<(() -> Unit)?>(null) }
    val pipSeek = remember(player, playback) { java.util.concurrent.atomic.AtomicReference<(Double) -> Unit>({ playback.seekTo(it) }) }
    val pip = remember(player, playback) { player?.let { PictureInPictureController(it, onRestore = {
        showVideo = playback.state.value.details != null
        if (!showVideo) lastMediaSection?.let { section = it }
    }, onPrevious = { mediaPrevious?.invoke() ?: playback.previous() }, onNext = { mediaNext?.invoke() ?: playback.next() },
        onSeekTo = { pipSeek.get().invoke(it) }) } }
    val emptyPipState = remember { MutableStateFlow(false) }
    val pipActive by (pip?.active ?: emptyPipState).collectAsState()
    val enhancementEnabled = remember(pluginRuntime) { MutableStateFlow(false) }
    val enhancementHostStarted = remember { MutableStateFlow(false) }
    LaunchedEffect(pluginRuntime) {
        pluginRuntime.plugins.collect { plugins ->
            enhancementEnabled.value = plugins.any { it.plugin === pluginRuntime.videoEnhancement && it.enabled }
        }
    }
    val enhancement = remember(player, pluginRuntime) { player?.let { nativePlayer ->
        val cache = DesktopLibrary.directoryForAccount(null).resolve("video-shaders")
        DesktopVideoEnhancementSession(nativePlayer, pluginRuntime.videoEnhancement.configState, enhancementEnabled,
            DesktopVideoShaderResources(cache.resolve("anime4k")), cache.resolve("fsr"),
            pip?.active ?: emptyPipState, enhancementHostStarted,
            enablePlugin = { pluginRuntime.setEnabled(Anime4KPlugin.PLUGIN_ID, true) },
            rememberCurrentEnabled = { pluginRuntime.enhancementConfiguration.rememberCurrentVideoEnabled(it) },
            sessionEpoch = { repository.sessionEpoch },
            enablePluginGuarded = { stillOwned -> pluginRuntime.setEnabled(Anime4KPlugin.PLUGIN_ID, true, stillOwned) })
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
        pipSeek.set { seconds -> if (retainedMedia.current != null) player?.seekTo(seconds) else playback.seekTo(seconds) }
    }
    val nativeTextShare = remember(hostWindow) {
        DesktopNativeTextShare(
            nativeDll = { java.nio.file.Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),
                "native", "windows-x64", "bilipai-diagnostic-share.dll") },
            expectedSha256 = DesktopNativeDiagnosticShareAssetHash.sha256,
            window = { hostWindow })
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
            closeDiscoveryStorage()
            favoritesQueueRef.getAndSet(null)?.close()
            favoritesEntryRef.getAndSet(null)?.shutdownForRestore()
            downloadNotificationRetired.set(true)
            downloadNotification.getAndSet(null)?.close()
            nativeTextShare.shutdown()
            withContext(Dispatchers.Main) { enhancement?.close(); pip?.close(); retainedMedia.close(); playback.close(); listen?.shutdownForRestore() }
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
                closeDiscoveryStorage()
                favoritesQueueRef.getAndSet(null)?.close()
                favoritesEntryRef.getAndSet(null)?.shutdownForRestore()
                downloadNotificationRetired.set(true)
                downloadNotification.getAndSet(null)?.close()
                nativeTextShare.shutdown()
                withContext(Dispatchers.Main) { enhancement?.close(); pip?.close(); retainedMedia.close(); playback.close(); listen?.shutdownForRestore() }
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
    val systemMedia = remember(hostWindow, hostDisplayable, player, playback, listen, retainedMedia) {
        hostWindow?.takeIf { hostDisplayable }?.let { owner -> WindowsMediaSession(owner, onCommand = { command ->
            val target = if (systemTargetAudio) audioPlayer else player
            when(command) {
                WindowsMediaCommand.PLAY -> if (systemTargetAudio) listen?.let { if (it.player.state.value.paused || !it.state.value.active) it.togglePause() }
                    else target?.let { if (it.state.value.ended) it.replay() else it.setPaused(false) }
                WindowsMediaCommand.PAUSE -> if (systemTargetAudio) listen?.pause() else target?.setPaused(true)
                WindowsMediaCommand.STOP -> if (systemTargetAudio) listen?.pause() else { pip?.close(); retainedMedia.stop(); playback.stop(); target?.stop() }
                WindowsMediaCommand.NEXT -> if (systemTargetAudio) listen?.next() else retainedMedia.current?.next?.invoke() ?: playback.next()
                WindowsMediaCommand.PREVIOUS -> if (systemTargetAudio) listen?.previous() else retainedMedia.current?.previous?.invoke() ?: playback.previous()
                WindowsMediaCommand.FAST_FORWARD -> if (!systemTargetAudio && playback.state.value.details != null) playback.seekBy(10.0) else target?.seekBy(10.0)
                WindowsMediaCommand.REWIND -> if (!systemTargetAudio && playback.state.value.details != null) playback.seekBy(-10.0) else target?.seekBy(-10.0)
            }
        }, onSeek = { seconds -> if (!systemTargetAudio && playback.state.value.details != null) playback.seekTo(seconds)
            else (if (systemTargetAudio) audioPlayer else player)?.seekTo(seconds) }) }
    }

    fun changePreferences(next: PlayerPreferences) {
        val previous = preferences
        // Legacy Windows danmaku values are retained only for the one-time absent-key migration.
        preferences = next.copy(danmaku = previous.danmaku).normalized()
        player?.applyPreferences(preferences)
        listen?.updatePreferences(preferences)
        danmaku?.applySettings(latestRendererDanmakuSettings)
        playback.onPlaybackPreferencesChanged(previous, preferences)
        val snapshot = preferences
        preferenceWriter.submit(snapshot)
    }
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
            revealVideo = { showVideo = true; mediaActive = false; playerFocused = false },
            revealAudio = { showVideo = false; playerFocused = false; mediaActive = false
                favoritesAudioCover = true; section = DesktopSection.LISTEN })
        favoritesEntryRef.set(entry); favoritesQueueRef.set(queue); favoritesEntry = entry
        return entry
    }
    fun navigate(target: DesktopSection, commit: () -> Unit = {}): Boolean {
        if (activatingUpdate || !checkpointForNavigation()) return false
        if (target == DesktopSection.CLOUD_FAVORITES) {
            val entry = ensureFavoritesEntry()
            if (section == target && !showVideo) entry.scrollToTopChannel.trySend(Unit)
            favoritesAudioCover = false
        } else retireFavoritesEntry()
        if (target != DesktopSection.STORY) storyHost.retire()
        if (target == DesktopSection.DYNAMIC) dynamicRoute = null
        commit()
        if ((section == DesktopSection.SETTINGS || jsSettingsOrigin) &&
            target !in listOf(DesktopSection.SETTINGS, DesktopSection.JS_CONTENT, DesktopSection.EXTERNAL_MEDIA)) {
            settingsNavigator.leave()
            jsSettingsOrigin = false
        }
        showVideo = false; playerFocused = false; section = target; page = 1; error = null
        if (target == DesktopSection.HISTORY) cards = library.history()
        if (target == DesktopSection.FAVORITES) cards = library.favorites()
        return true
    }
    fun openVideo(card: VideoCard) {
        if (activatingUpdate || !checkpointForNavigation()) return
        storyHost.retire(); retainedMedia.stop(); listen?.pause(); systemTargetAudio = false
        showVideo = true; mediaActive = false; playback.open(card)
    }
    fun openQueue(videos: List<VideoCard>, selected: VideoCard) {
        if (activatingUpdate || videos.isEmpty() || !checkpointForNavigation()) return
        val index = videos.indexOfFirst { it.bvid == selected.bvid && it.preferredCid == selected.preferredCid }.takeIf { it >= 0 } ?: 0
        storyHost.retire(); retainedMedia.stop(); listen?.pause(); systemTargetAudio = false
        showVideo = true; mediaActive = false; playback.openQueue(videos, index)
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
    fun closeWeeklySeries() {
        val returnVideo = weeklyReturnVideo
        if (navigate(if (returnVideo) weeklyReturnSection else DesktopSection.POPULAR) && returnVideo && playing.details != null)
            showVideo = true
        weeklyReturnVideo = false
    }
    fun openUser(id: Long) { navigate(DesktopSection.USER) { userId = id } }
    fun openDynamicRoute(route: DesktopDynamicDetailRoute) { navigate(DesktopSection.DYNAMIC) { dynamicRoute = route } }
    fun openDynamic(id: String) { openDynamicRoute(DesktopDynamicDetailRoute(id)) }
    fun openTopic(id: Long) {
        if (activatingUpdate || id <= 0) return
        navigate(DesktopSection.TOPIC) {
            if (section != DesktopSection.TOPIC) {
                topicReturnSection = section; topicReturnDynamicRoute = dynamicRoute; topicStack = listOf(id)
            } else if (topicStack.lastOrNull() != id) topicStack = topicStack + id
            topicId = id
        }
    }
    fun openTopicKeyword(keyword: String) {
        if (activatingUpdate || keyword.isBlank()) return
        navigate(DesktopSection.SEARCH) { query = keyword; submitted = keyword }
    }
    fun openStory(card: VideoCard? = null) {
        if (activatingUpdate) return
        navigate(DesktopSection.STORY) {
            if (section != DesktopSection.STORY) storyReturnSection = section
            storySeed = card?.let { DesktopStorySeed(it.bvid, it.preferredCid, it.cover, it.title) } ?: DesktopStorySeed()
        }
    }
    fun openBgm(request: DesktopBgmMusicTarget.Detail) {
        if (activatingUpdate || request.musicId.isBlank()) return
        navigate(DesktopSection.BGM) {
            if (section != DesktopSection.BGM) { bgmReturnSection = section; bgmReturnVideo = showVideo }
            bgmRequest = request
        }
    }
    fun closeBgm() {
        val request = bgmRequest
        if (request?.showVideos == true) bgmRequest = request.copy(showVideos = false)
        else if (navigate(bgmReturnSection) && bgmReturnVideo && playing.details != null) showVideo = true
    }
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
    fun closeMusic() {
        val source = musicSource
        if (musicReturnVideo && source is MusicPlaybackSource.VideoAudio && listen != null) {
            openVideo(nativeMusicVideoReturnCard(source, listen.state.value, listen.player.state.value,
                listen.ownedPlaybackSourceVersion != null))
        } else {
            if (navigate(musicReturnSection) && musicReturnVideo && playing.details != null) showVideo = true
        }
    }
    LaunchedEffect(sessionEpoch) {
        if (section == DesktopSection.MUSIC && musicSource == null) navigate(DesktopSection.LISTEN)
    }
    fun openArticle(id: Long) { navigate(DesktopSection.ARTICLE) { articleId = id } }
    fun openNotes(info: VideoDetails) { navigate(DesktopSection.NOTES) { noteVideo = info } }
    fun openJsPlugin(id: String) { navigate(DesktopSection.JS_CONTENT) {
        if (section != DesktopSection.JS_CONTENT) jsSettingsOrigin = section == DesktopSection.SETTINGS
        jsPluginId = id
    } }
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
            }, releaseRequest = host::releaseExternalLaunch)
            showVideo = false; section = DesktopSection.EXTERNAL_MEDIA; lastMediaSection = section
        } catch (failure: Exception) {
            host.releaseExternalLaunch(launchId)
            error = failure.message ?: "外部媒体无法播放"
        }
    }
    fun openLive(id: Long) { navigate(DesktopSection.LIVE) { roomId = id } }
    fun showSeason(id: Long, epId: Long = 0, course: Boolean = false, progress: Double = 0.0) {
        navigate(DesktopSection.BANGUMI) {
            seasonId = id; episodeId = epId; isCourse = course; seasonProgress = progress
        }
    }
    fun openBangumi(id: Long) { showSeason(id) }
    fun openCollection(mid: Long, id: Long, type: String) {
        navigate(DesktopSection.COLLECTION) { collectionMid = mid; collectionId = id; collectionType = type; collectionTitle = "" }
    }
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
    suspend fun currentCastMedia(): com.android.purebilibili.core.plugin.CastPluginMediaRequest {
        check(!systemTargetAudio) { "请先打开需要投屏的视频" }
        val owner = retainedMedia.current
        val nativeSource = player?.currentSourceSnapshot()
        val current = playback.state.value
        val epoch = repository.sessionEpoch
        val positionMs = ((player?.state?.value?.positionSeconds ?: 0.0) * 1000).toLong()
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
                val source = if (nativeSource == null) repository.playback(info, current.currentPart, current.quality)
                    else playback.currentCastSource(nativeSource.sourceVersion) ?: error("当前视频播放源已经变化，请重新开始投屏")
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
        return media
    }
    fun prepareUpdate(update: WindowsUpdate, manual: Boolean) {
        if (activatingUpdate || updateJob?.isActive == true) return
        if (manual) manuallyRequested = true
        updateJob = scope.launch(start = CoroutineStart.LAZY) {
            try { if (updater.prepareUpdate(update) == null) manuallyRequested = false }
            finally { updateJob = null }
        }.also { it.start() }
    }

    DisposableEffect(playback) { onDispose { playback.close() } }
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
        mediaPrevious = owner?.previous; mediaNext = owner?.next
        when (owner) {
            retainedMedia.live -> lastMediaSection = DesktopSection.LIVE
            retainedMedia.bangumi -> lastMediaSection = DesktopSection.BANGUMI
            retainedMedia.offline -> lastMediaSection = DesktopSection.DOWNLOADS
            retainedMedia.external -> lastMediaSection = DesktopSection.EXTERNAL_MEDIA
            else -> Unit
        }
    }
    val capturedFavoritesUiEpoch = sessionEpoch
    LaunchedEffect(capturedFavoritesUiEpoch, section) {
        if (section == DesktopSection.CLOUD_FAVORITES && repository.sessionEpoch == capturedFavoritesUiEpoch &&
            !isClosing() && !activatingUpdate && favoritesEntryRef.get()?.isOwned() != true) ensureFavoritesEntry()
    }
    DesktopHistoryRefreshEffects(browseMemory, showVideo && playing.details != null)
    LaunchedEffect(retainedMedia, jsExecutionRevision, sessionEpoch) {
        if (!retainedMedia.external.authorizationCurrent) retainedMedia.external.stopPlayback()
    }
    LaunchedEffect(player, danmaku) { player?.applyPreferences(preferences) }
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
    LaunchedEffect(anyCasting) { if (anyCasting) { playback.pause(); player?.setPaused(true); listen?.pause() } }
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
    LaunchedEffect(Unit) { runCatching { repository.refreshAccount() }; initialVideo?.let { playback.open(it) } }
    LaunchedEffect(playing.details?.bvid, playing.currentPart) {
        while (playing.details != null) {
            delay(5000)
            if (!withContext(Dispatchers.IO) { playback.checkpoint() })
                error = playback.state.value.error ?: "播放记录保存失败，请检查本地隐私和存储设置"
        }
    }
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
            val state = (if (audioTarget) audioPlayer else player)?.state?.value
            if (state != null) systemMedia?.update(WindowsMediaSnapshot(
                title = if (audioTarget) audio!!.current!!.title else if (retainedMedia.current != null) retainedMedia.title else current.details?.title ?: state.sourceTitle,
                artist = if (audioTarget) audio!!.current!!.owner else if (retainedMedia.current != null) "" else current.details?.author.orEmpty(),
                mediaId = if (audioTarget) audio!!.current!!.bvid else if (retainedMedia.current != null) state.sourceTitle else current.details?.bvid ?: state.sourceTitle,
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
            PlayerKeyAction.PlayPause -> { initialized.togglePause(); true }
            is PlayerKeyAction.SeekRelative -> { playback.seekBy(action.deltaMs / 1000.0); true }
            is PlayerKeyAction.SeekPercent -> { playback.seekTo(snapshot.durationSeconds * action.fraction); true }
            PlayerKeyAction.VolumeUp -> { changePreferences(preferences.copy(volume = snapshot.volume + 5)); true }
            PlayerKeyAction.VolumeDown -> { changePreferences(preferences.copy(volume = snapshot.volume - 5)); true }
            PlayerKeyAction.ToggleMute -> { changePreferences(preferences.copy(muted = !snapshot.muted)); true }
            PlayerKeyAction.ToggleFullscreen -> { onToggleFullscreen(); true }
            PlayerKeyAction.ToggleDanmaku -> { toggleOriginalDanmaku(); true }
            PlayerKeyAction.PreviousPart -> { playback.previous(); true }
            PlayerKeyAction.NextPart -> { playback.next(); true }
            is PlayerKeyAction.SetSpeed -> { changePreferences(preferences.copy(speed = action.speed.toDouble())); true }
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
    CompositionLocalProvider(LocalDesktopBrowseMemory provides browseMemory, LocalUiSkinState provides packages.skin,
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
            }.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) { onToggleFullscreen(); true }
            else if ((showVideo || section == DesktopSection.STORY) && playing.details != null && playerFocused && !searchFocused && player != null) {
                // Shortcuts are scoped to the focused player; comment and search editors keep their keys.
                performPlayerKey(resolvePlayerKeyAction(event, isTextInputActive = searchFocused))
            } else false
        }, color = scheme.background) {
            Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(180.dp).fillMaxHeight()) {
                DesktopUiSkinDecoration(UiSkinSurface.HOME_DRAWER, { it.homeSideBackground }, Modifier.fillMaxSize(),
                    onError = { error = it })
                DesktopUiSkinDecoration(UiSkinSurface.HOME_DRAWER, { it.drawerBottomTrim },
                    Modifier.fillMaxWidth().height(84.dp).align(Alignment.BottomCenter), onError = { error = it })
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("BiliPai", Modifier.padding(12.dp), style = MaterialTheme.typography.headlineSmall, color = scheme.primary, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopSection.entries.filter { it !in listOf(DesktopSection.SEARCH, DesktopSection.USER, DesktopSection.ARTICLE, DesktopSection.NOTES, DesktopSection.COLLECTION,
                            DesktopSection.JS_CONTENT, DesktopSection.EXTERNAL_MEDIA, DesktopSection.APPEARANCE, DesktopSection.MUSIC, DesktopSection.BGM, DesktopSection.TOPIC) }.forEach { item ->
                            Surface(Modifier.fillMaxWidth().height(48.dp).clickable { if (item == DesktopSection.STORY) openStory() else navigate(item) {
                                if (item == DesktopSection.SETTINGS) settingsNavigator.openRoot()
                            } }, shape = RoundedCornerShape(24.dp),
                                color = if (section == item && !showVideo) scheme.primaryContainer else Color.Transparent) {
                                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(item.symbol, style = MaterialTheme.typography.titleLarge); Text(item.localizedLabel(strings))
                                }
                            }
                        }
                    }
                    TextButton(onClick = { navigate(DesktopSection.SETTINGS) { settingsNavigator.openDetail(SettingsSearchTarget.PLAYBACK, null) } }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(strings["playback_settings_title"]) }
                    TextButton(onClick = { combinedBackupSettings = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("WebDAV 与备份") }
                    TextButton(onClick = { navigate(DesktopSection.SETTINGS) { settingsNavigator.openCategory(SettingsRootCategory.APPEARANCE_THEME) } }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(strings["appearance_settings_title"]) }
                    Box(Modifier.fillMaxWidth().height(64.dp)) {
                        DesktopUiSkinDecoration(UiSkinSurface.PROFILE,
                            { it.homeProfileVideoBackground ?: it.homeProfileSquaredBackground ?: it.homeProfileBackground },
                            Modifier.fillMaxSize(), playing = hostVisible, onError = { error = it })
                        TextButton(onClick = { loginDialog = true }, modifier = Modifier.fillMaxSize()) { Text(account?.name ?: "扫码登录", maxLines = 1) }
                    }
                    TextButton(onClick = { updatesDialog = true; scope.launch { updater.check() } }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Windows 更新") }
                }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    diagnosticStartupError?.let { Text(it, color = scheme.error) }
                    Box(Modifier.fillMaxWidth()) {
                    DesktopUiSkinDecoration(UiSkinSurface.HOME_TOP_CHROME, { it.topAtmosphere }, Modifier.matchParentSize(), onError = { error = it })
                    DesktopUiSkinDecoration(UiSkinSurface.HOME_TOP_CHROME, { it.searchCapsuleBackground }, Modifier.matchParentSize(), onError = { error = it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showVideo) OutlinedButton(onClick = { if (checkpointForNavigation()) { showVideo = false; playerFocused = false } }, modifier = Modifier.height(52.dp)) { Text("‹ ${strings["common_back"]}") }
                        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索视频、UP 主、番剧、专栏，或粘贴 BV / 链接") },
                            shape = RoundedCornerShape(26.dp), modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.hasFocus },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                        Button(onClick = ::submitSearch, modifier = Modifier.height(52.dp)) { Text("搜索") }
                    }
                    }
                    (error ?: playing.error ?: clipboardFailure?.let { when (strings.languageTag) {
                        "en" -> "Could not write to the clipboard. Please try again."
                        "zh-TW" -> "無法寫入系統剪貼簿，請稍後重試"
                        else -> it
                    } })?.let { text ->
                        Surface(color = scheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text, Modifier.weight(1f)); TextButton(onClick = { error = null; playback.dismissError(); WindowsTextClipboard.clearFailure() }) { Text("收起") }
                            }
                        }
                    }
                    if (listen != null && section != DesktopSection.LISTEN) ListenNowPlayingBar(listen, {
                        val source = nativeMusicSourceForListenItem(listen.state.value.current)
                        if (source is MusicPlaybackSource.AudioSong) openMusicSource(source)
                        else navigate(DesktopSection.LISTEN)
                    })
                    if (casting.isActive) TextButton(onClick = { dlnaDialog = true }) { Text("投屏：${casting.deviceLabel.ifBlank { "播放设备" }} · 打开控制") }
                    if (googleCasting.isActive) TextButton(onClick = { googleCastDialog = true }) { Text("Google Cast：${googleCasting.deviceLabel.ifBlank { "播放设备" }} · 打开控制") }
                    if (playing.recovering) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        playing.recoveryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    playing.manualSkip?.let { action -> Button(onClick = playback::executeManualSkip) { Text(action.label) } }
                    if (!showVideo && section != DesktopSection.STORY && playing.details != null && player != null) {
                        Surface(color = scheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().height(118.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!pipActive) SwingPanel(factory = { player.surface }, background = Color.Black, modifier = Modifier.width(180.dp).fillMaxHeight())
                                else Text("画中画播放中", Modifier.width(180.dp))
                                Text(playing.details!!.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { player.togglePause() }) { Text(if (native.paused) "播放" else "暂停") }
                                TextButton(onClick = { showVideo = true }) { Text("回到视频") }
                                TextButton(onClick = { pip?.close(); playback.stop() }) { Text("关闭") }
                            }
                        }
                    }
                    val retainedOwner = retainedMedia.current
                    if (retainedOwner != null && retainedOwner !== retainedMedia.offline) TextButton(onClick = { castDialog = true }) { Text("投屏当前媒体") }
                    val retainedPage = when (retainedOwner) {
                        retainedMedia.live -> DesktopSection.LIVE
                        retainedMedia.bangumi -> DesktopSection.BANGUMI
                        retainedMedia.offline -> DesktopSection.DOWNLOADS
                        retainedMedia.external -> DesktopSection.EXTERNAL_MEDIA
                        else -> null
                    }
                    if (retainedOwner != null && retainedPage != null && section != retainedPage && player != null && !showVideo) {
                        Surface(color = scheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().height(118.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!pipActive) SwingPanel(factory = { player.surface }, background = Color.Black, modifier = Modifier.width(180.dp).fillMaxHeight())
                                else Text("画中画播放中", Modifier.width(180.dp))
                                Text(retainedMedia.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { player.togglePause() }) { Text(if (native.paused) "播放" else "暂停") }
                                TextButton(onClick = { section = retainedPage }) { Text("回到播放") }
                                TextButton(onClick = { pip?.close(); retainedOwner.stopPlayback() }) { Text("关闭") }
                            }
                        }
                    }
                    val playerContent: @Composable (MpvPlayer) -> Unit = { initialized ->
                        Column(Modifier.fillMaxWidth()) {
                        PlayerPanel(initialized, preferences.copy(danmaku = rendererDanmakuSettings), ::changePreferences, onToggleFullscreen,
                            modifier = Modifier.onFocusChanged { playerFocused = it.hasFocus }.focusable(), onMessage = { error = it },
                            onPreviousPart = if ((showVideo || section == DesktopSection.STORY) && playback.hasPrevious) ({ playback.previous() }) else null,
                            onNextPart = if ((showVideo || section == DesktopSection.STORY) && playback.hasNext) ({ playback.next() }) else null,
                            onOnlineSubtitles = if ((showVideo || section == DesktopSection.STORY) && playing.details != null) ({
                                val current = playback.state.value
                                val info = current.details
                                val cid = info?.pages?.getOrNull(current.currentPart)?.cid
                                val owner = initialized.currentSourceVersion
                                if (info != null && cid != null && playback.currentCastSource(owner) != null) {
                                    subtitleDialogTarget = DesktopSubtitleDialogTarget(info.bvid, cid, owner, repository.sessionEpoch)
                                    subtitleDialog = true
                                }
                            }) else null,
                            onManualSubtitleSelection = playback::notifyUserSubtitleTrackSelection,
                            audioSelection = playback.currentCastSource(initialized.currentSourceVersion)?.audioSelection,
                            onAudioQualityChange = if (showVideo || section == DesktopSection.STORY) ({ playback.selectAudioQuality(it) }) else null,
                            automaticSubtitleMode = automaticSubtitleState.mode,
                            automaticSubtitleTracks = automaticSubtitleState.tracks,
                            onAutomaticSubtitleMode = if (showVideo || section == DesktopSection.STORY) ({ playback.setAutomaticSubtitleMode(it) }) else null,
                            onOriginalDanmakuSettings = if (initialized === player && showVideo && playing.details?.raw != null && hostWindow != null) ({ originalDanmakuSettingsVisible = true }) else null,
                            onOriginalDanmakuToggle = ::toggleOriginalDanmaku,
                            surfaceOnly = section == DesktopSection.STORY,
                            viewPoints = if (playback.currentCastSource(initialized.currentSourceVersion) != null)
                                chapterViewPoints else emptyList(),
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
                            onToggle = { enabled -> enhancement?.setCurrentVideoEnabled(enabled) },
                            onSettings = { enhancementSettings = true })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            showVideo && playing.opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            showVideo && playing.details != null -> DesktopVideoPage(playing, player, playerContent, favorite,
                                blockedUps = community.blockedUpRepository, onLogin = { loginDialog = true },
                                onVideo = ::openVideo, onPart = playback::playPart, onQuality = playback::switchQuality,
                                onFavorite = { library.toggleFavorite(playing.details!!.asCard()); favorite = library.isFavorite(playing.details!!.bvid) },
                                onCast = { castDialog = true },
                                onStory = { openStory(playing.details!!.asCard().copy(preferredCid = playing.details!!.pages[playing.currentPart].cid)) },
                                engagement = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    DesktopVideoMetadataHost(playing.details!!, repository, social, ::openUser, ::openVideoHonorLink, { error = it })
                                    val favoriteVideoAid = playing.details!!.aid
                                    val favoriteVideoEpoch = sessionEpoch
                                    VideoEngagementPanel(playing.details!!, repository, social, community,
                                    ::openUser, { loginDialog = true }, ::openNotes, playback::seek,
                                    cid = playing.details!!.pages[playing.currentPart].cid,
                                    globalStore = pluginStore,
                                    stillOwned = {
                                        repository.sessionEpoch == favoriteVideoEpoch && !isClosing() && !activatingUpdate && showVideo &&
                                            playback.state.value.details?.aid == favoriteVideoAid
                                    }, onFavoriteCount = { count ->
                                        playback.updateFavoriteCountForOwner(favoriteVideoAid, favoriteVideoEpoch, count)
                                    },
                                    commentContent = {
                                        val rawCommentInfo = playing.details!!.raw
                                        val commentCid = playing.details!!.pages[playing.currentPart].cid
                                        val commentEpoch = sessionEpoch
                                        val commentVideoId = playing.details!!.bvid
                                        val commentSourceVersion = player?.currentSourceVersion ?: 0L
                                        if (rawCommentInfo != null) DesktopVideoCommentRootHost(
                                            info = rawCommentInfo.copy(cid = commentCid), videoTags = emptyList(),
                                            repository = repository, community = community, fraud = commentFraud,
                                            isVideoPlaying = !native.paused,
                                            stillOwned = {
                                                repository.sessionEpoch == commentEpoch && !isClosing() && !activatingUpdate && showVideo &&
                                                    playback.state.value.details?.bvid == commentVideoId
                                            }, currentVideoPositionMsProvider = {
                                                if (repository.sessionEpoch == commentEpoch && player?.currentSourceVersion == commentSourceVersion &&
                                                    playback.state.value.details?.bvid == commentVideoId &&
                                                    playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == commentCid)
                                                    ((player?.state?.value?.positionSeconds ?: 0.0) * 1000L).toLong() else 0L
                                            }, onTimestampClick = { timestampMs ->
                                                if (repository.sessionEpoch == commentEpoch && player?.currentSourceVersion == commentSourceVersion &&
                                                    playback.state.value.details?.bvid == commentVideoId &&
                                                    playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == commentCid &&
                                                    playback.currentCastSource(commentSourceVersion) != null)
                                                    playback.seek(commentCid, timestampMs.coerceAtLeast(0L) / 1000.0)
                                            }, onUserClick = ::openUser, onLinkClick = ::openVideoHonorLink,
                                            onLogin = { loginDialog = true }, modifier = Modifier.fillMaxWidth().height(440.dp),
                                            auxiliaryContent = if (player != null && danmaku != null && hostWindow != null && commentCid > 0L) ({ owner ->
                                                val viewport = com.android.purebilibili.core.util.LocalWindowSizeClass.current
                                                DesktopOriginalDanmakuRootHost(owner, repository, pluginStore, danmaku, commentCid,
                                                    commentSourceVersion, stillOwned = {
                                                        repository.sessionEpoch == commentEpoch && !isClosing() && !activatingUpdate && showVideo &&
                                                            player.ownsSourceVersion(commentSourceVersion) && playback.state.value.details?.bvid == commentVideoId &&
                                                            playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid == commentCid &&
                                                            playback.currentCastSource(commentSourceVersion) != null
                                                    }, window = hostWindow, presentation = originalDanmakuPresentation,
                                                    viewport = DesktopDanmakuSettingsViewport(viewport.widthDp.value.toInt(), viewport.heightDp.value.toInt()),
                                                    currentPositionMs = { (player.state.value.positionSeconds * 1000.0).toLong().coerceAtLeast(0L) },
                                                    seekFromUser = { playback.seekTo(it.coerceAtLeast(0L) / 1000.0) },
                                                    showSettings = originalDanmakuSettingsVisible, showPool = originalDanmakuPoolVisible,
                                                    onShowPool = { originalDanmakuSettingsVisible = false; originalDanmakuPoolVisible = true },
                                                    onDismissSettings = { originalDanmakuSettingsVisible = false },
                                                    onDismissPool = { originalDanmakuPoolVisible = false },
                                                    enabledChangeVersion = originalDanmakuEnabledChangeVersion)
                                            }) else null)
                                        else Text("暂时无法取得原始评论信息")
                                    })
                                    val musicTarget = DesktopMusicVideoTarget(playing.details!!.bvid,
                                        playing.details!!.pages[playing.currentPart].cid, player?.currentSourceVersion ?: 0L, repository.sessionEpoch)
                                    DesktopVideoMusicEntries(playing.details!!, musicTarget, repository, community,
                                        currentTarget = { captured -> captured.isCurrent(playback.state.value.details?.bvid,
                                            playback.state.value.details?.pages?.getOrNull(playback.state.value.currentPart)?.cid,
                                            player?.currentSourceVersion, repository.sessionEpoch) && showVideo && !activatingUpdate &&
                                            playback.currentCastSource(captured.sourceVersion) != null },
                                        positionSeconds = { player?.state?.value?.positionSeconds ?: 0.0 },
                                        onMusic = ::openMusicSource, onBgm = ::openBgm, operations = dynamicEditor.operations,
                                        onRelatedVideo = { bvid, cid -> openVideo(VideoCard(bvid, "", "", "", 0, 0, preferredCid = cid)) },
                                        onExternalUrl = { raw ->
                                            runCatching { java.net.URI(imageUrl(raw)) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.let { uri ->
                                                runCatching { java.awt.Desktop.getDesktop().browse(uri) }
                                            }
                                        })
                                    DesktopCollectionSheetHost(playing.details!!, repository,
                                        currentCid = playing.details!!.pages[playing.currentPart].cid,
                                        isPlaying = player?.state?.value?.let { it.ready && !it.paused && !it.ended } == true,
                                        onPlayQueue = ::openQueue, onFeedback = { error = it },
                                        onShare = { title, text, owned -> scope.launch {
                                            try {
                                                if (!nativeTextShare.share(title, text) { owned() && !isClosing() } && owned() && !isClosing())
                                                    error = "无法打开 Windows 分享面板"
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) { if (owned() && !isClosing()) error = failure.message ?: "分享合集失败" }
                                        } },
                                        stillOwned = { showVideo && playback.state.value.details?.bvid == playing.details!!.bvid && !isClosing() })
                                } },
                                onDownload = { requestedQuality, options ->
                                    val expectedEpoch = repository.sessionEpoch
                                    scope.launch {
                                    try {
                                        val info = playing.details!!
                                        val part = info.pages[playing.currentPart]
                                        val source = repository.playback(info, playing.currentPart, requestedQuality)
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        if (repository.sessionEpoch != expectedEpoch) throw CancellationException("下载账号已切换")
                                        downloads.enqueue(com.bilipai.desktop.player.PlaybackSource(videoUrl = source.videoUrl, audioUrl = source.audioUrl,
                                            referer = source.referer, cookieHeader = source.cookieHeader, title = source.title,
                                            progressiveSegments = source.progressiveSegments), metadata = DownloadMetadata(
                                            aid = info.aid, bvid = info.bvid, cid = part.cid, cover = info.cover, author = info.author,
                                            durationSeconds = part.duration.toInt(), quality = source.quality,
                                            qualityLabel = source.availableQualities.firstOrNull { it.id == source.quality }?.label.orEmpty(),
                                            includeDanmaku = options.includeDanmaku, episodeLabel = part.title))
                                        error = "已加入下载队列"
                                    } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "下载失败" }
                                } })
                            section == DesktopSection.CLOUD_FAVORITES -> {
                                val entry = favoritesEntry
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
                                    val detail = entry.currentDetail
                                    favoritesSaveable.SaveableStateProvider(entry.stateKey(detail)) {
                                        DesktopOriginalFavoritesHost(entry.environment, bindings,
                                            onBack = { if (entry.isOwned()) {
                                                if (entry.currentDetail != null) entry.currentDetail = null else navigate(DesktopSection.HOME)
                                            } },
                                            onVideoClick = { bvid, cid, cover, _ ->
                                                if (entry.isOwned() && !queue.revealIfOwned(bvid, cid, false))
                                                    openVideo(VideoCard(bvid, bvid, cover, "", 0, 0, preferredCid = cid))
                                            }, onUpClick = { if (entry.isOwned()) openUser(it) },
                                            onCollectionClick = { if (entry.isOwned()) entry.currentDetail = it },
                                            onFolderClick = { mediaId, ownerMid, title, ownerName ->
                                                if (entry.isOwned()) entry.currentDetail = com.android.purebilibili.feature.list.FavoriteCollectionRoute("favorite", mediaId, ownerMid, title, ownerName) },
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
                                            isCurrentPage = !showVideo && !activatingUpdate,
                                            retainedViewModel = detail?.let(entry::detailViewModel) ?: entry.viewModel,
                                            loadFavoriteViewModelOnEnter = false)
                                    }
                                    }
                                }
                            }
                            section in listOf(DesktopSection.CLOUD_HISTORY, DesktopSection.WATCH_LATER, DesktopSection.FOLLOWINGS, DesktopSection.LIKED) ->
                                PersonalContentScreen(when(section) {
                                    DesktopSection.CLOUD_HISTORY -> PersonalSection.HISTORY
                                    DesktopSection.WATCH_LATER -> PersonalSection.WATCH_LATER
                                    DesktopSection.LIKED -> PersonalSection.LIKED
                                    else -> PersonalSection.FOLLOWINGS
                                }, repository, social, community, ::openVideo, ::openUser, { loginDialog = true }, ::openResource, ::openCollection)
                            section == DesktopSection.COLLECTION -> CommunityCollectionScreen(collectionMid, collectionId, collectionType, community, ::openVideo, ::openUser, { loginDialog = true },
                                space = space, onResource = ::openResource, initialTitle = collectionTitle)
                            section == DesktopSection.PLUGINS -> PluginCenterScreen(pluginRuntime, ::openVideo, ::openQueue, ::openJsPlugin)
                            section == DesktopSection.SETTINGS -> DesktopSettingsTree(settingsNavigator, settingsSearchController,
                                historyWritesScope = scope, discovery = discovery, privacy = privacyBindings,
                                onFailure = { error = it.message ?: "设置保存失败" },
                                appearanceContent = { DesktopAppearanceSettings(appearance,
                                    onRestartRequested = { onRestart?.invoke() ?: run { error = "请关闭并重新打开客户端以完成语言切换。" } }) },
                                pluginsContent = { PluginCenterScreen(pluginRuntime, ::openVideo, ::openQueue, ::openJsPlugin) },
                                playbackContent = { dismiss -> PlaybackSettingsDialog(preferences, ::changePreferences, dismiss) },
                                backupContent = { target, dismiss -> BackupSettingsDialog(backup, dismiss, onExit,
                                    initialSection = requireNotNull(resolveDesktopBackupEntrySection(target))) },
                                blockedListContent = { DesktopBlockedListScreen(community.blockedUpRepository, onLogin = { loginDialog = true }) },
                                imageSavePathContent = { openInitially ->
                                    DesktopImageSavePathSettings(imageSavePreferences, scope,
                                        chooseDirectory = { selectDynamicSaveDirectory(hostWindow, "选择图片目录") },
                                        stillOwned = imageSaveLifetime::isActive,
                                        onFailure = { error = it.message ?: "图片保存位置设置失败" },
                                        onMessage = { imageSaveMessage = it }, openInitially = openInitially)
                                    imageSaveMessage?.let { Text(it, Modifier.padding(12.dp)) }
                                },
                                systemContent = {
                                    com.bilipai.desktop.settings.DesktopNetworkProxySettings(globalPluginContext, repository.httpClient,
                                        onFailure = { error = it.message ?: "代理设置保存失败" })
                                    TextButton(onClick = { updatesDialog = true; scope.launch { updater.check() } }) { Text("检查 Windows 更新") }
                                    diagnostics?.let { localDiagnostics ->
                                        DesktopDiagnosticSettingsSection(localDiagnostics,
                                            onLocalLogs = { showDiagnosticViewer = true },
                                            onFailure = { error = "诊断设置保存失败，请重试" },
                                            modifier = Modifier.fillMaxWidth())
                                    }
                                    diagnosticStartupError?.let { Text(it, Modifier.padding(12.dp), color = scheme.error) }
                                    Text("许可和支持页面仍在移植中。", Modifier.padding(12.dp))
                                })
                            section == DesktopSection.APPEARANCE -> DesktopAppearanceSettings(appearance,
                                onRestartRequested = { onRestart?.invoke() ?: run { error = "请关闭并重新打开客户端以完成语言切换。" } })
                            section == DesktopSection.JS_CONTENT -> Column(Modifier.fillMaxSize()) {
                                TextButton(onClick = { navigate(if (jsSettingsOrigin) DesktopSection.SETTINGS else DesktopSection.PLUGINS) }) { Text("返回插件") }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    DesktopJsPluginContentScreen(pluginRuntime.jsPlugins, jsPluginId,
                                        onPlayMedia = ::openJsMedia, onFeedModule = { _, _ -> jsSubscriptionReader = true })
                                }
                            }
                            section == DesktopSection.EXTERNAL_MEDIA -> DesktopExternalMediaScreen(retainedMedia.external, player, playerContent,
                                onBack = { navigate(DesktopSection.JS_CONTENT) })
                            section in listOf(DesktopSection.HOME, DesktopSection.POPULAR, DesktopSection.REGION, DesktopSection.RANKING, DesktopSection.PRECIOUS, DesktopSection.WEEKLY) ->
                                DiscoveryContentScreen(when (section) {
                                    DesktopSection.HOME -> DiscoverySection.RECOMMEND
                                    DesktopSection.POPULAR -> DiscoverySection.POPULAR
                                    DesktopSection.REGION -> DiscoverySection.REGION
                                    DesktopSection.RANKING -> DiscoverySection.RANKING
                                    DesktopSection.PRECIOUS -> DiscoverySection.PRECIOUS
                                    else -> DiscoverySection.WEEKLY
                                }, discovery, repository, pluginStore, ::openVideo, ::openUser, { loginDialog = true },
                                    onBangumiPartition = { type -> seasonType = type; showSeason(0) }, onPlayQueue = ::openQueue, runtime = pluginRuntime,
                                    onRestart = onRestart, isClosing = isClosing,
                                    onWeeklyBack = ::closeWeeklySeries,
                                    initialWeeklyNumber = if (section == DesktopSection.WEEKLY) weeklyInitialNumber else null)
                            section == DesktopSection.LIVE -> LiveBrowserScreen(repository, player, playerError, { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, roomId, danmaku, retainedMedia)
                            section == DesktopSection.BANGUMI -> BangumiBrowserScreen(repository, player, playerError, { mediaActive = it; if (it) listen?.pause() }, downloads, onToggleFullscreen, playerContent, seasonId, danmaku,
                                initialIsCourse = isCourse, initialEpisodeId = episodeId, initialProgressSeconds = seasonProgress, initialSeasonType = seasonType, retained = retainedMedia)
                            section == DesktopSection.DOWNLOADS -> DownloadBrowserScreen(downloads, player, playerError,
                                { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, danmaku, retainedMedia,
                                onOnlinePlay = { task ->
                                    if (task.episodeId > 0) showSeason(task.seasonId, task.episodeId, task.isCourse,
                                        task.item.lastPlaybackPositionMs.coerceAtLeast(0L) / 1000.0)
                                    else if (task.item.bvid.startsWith("BV")) openVideo(VideoCard(task.item.bvid, task.title,
                                        task.item.cover, task.item.ownerName, 0L, task.item.duration,
                                        progressSeconds = (task.item.lastPlaybackPositionMs.coerceAtLeast(0L) / 1000L).toInt(),
                                        preferredCid = task.item.cid))
                                    else error = "缓存文件已失效，此任务没有可用的在线入口"
                                })
                            section == DesktopSection.LISTEN -> if (listen != null) ListenBrowserScreen(listen, preferences, ::changePreferences, ::openVideo, { loginDialog = true })
                                else Text(playerError ?: "音频播放器未能初始化")
                            section == DesktopSection.BGM -> bgmRequest?.let { request ->
                                DesktopBgmDetailRootHost(request, repository, community, commentFraud,
                                    nowPlayingBarOverlayVisible = listen?.state?.value?.current != null,
                                    onBack = ::closeBgm,
                                    onVideosClick = { bgmRequest = request.copy(showVideos = true) },
                                    onVideoClick = { bvid, cid, cover -> openVideo(VideoCard(bvid, "", cover, "", 0, 0, preferredCid = cid)) },
                                    onUserClick = ::openUser, onLinkClick = { raw ->
                                        runCatching { java.net.URI(imageUrl(raw)) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.let {
                                            runCatching { java.awt.Desktop.getDesktop().browse(it) }
                                        }
                                    }, onLogin = { loginDialog = true },
                                    onMediaSearch = { _, _, _ -> false })
                            }
                            section == DesktopSection.MUSIC -> {
                                val source = musicSource
                                if (listen != null && source != null) DesktopNativeMusicDetailScreen(source, listen, preferences, ::changePreferences,
                                    ::closeMusic, ::openUser, ::openVideo, startPositionSeconds = musicStartPosition)
                                else Text(playerError ?: "请从个人空间的音频栏目打开歌曲")
                            }
                            section == DesktopSection.STORY -> DesktopStoryScreen(storyTopic, storySeed,
                                isActive = !showVideo && !activatingUpdate,
                                playback = storyHost.snapshot(playing, native, playerError),
                                onPlaybackRequest = { storyHost.request(it, section == DesktopSection.STORY && !showVideo && !activatingUpdate) },
                                onReleasePlayback = { storyHost.release(it) }, onBack = { navigate(storyReturnSection) },
                                onUser = ::openUser, onSearch = { navigate(DesktopSection.SEARCH) },
                                onRetryPlayback = { storyHost.retry(it, section == DesktopSection.STORY && !showVideo && !activatingUpdate) },
                                nativeInput = player?.let { nativePlayer -> DesktopStoryNativeInputBinding(nativePlayer.surface,
                                    sourceVersion = { nativePlayer.currentSourceVersion },
                                    owns = { owner -> section == DesktopSection.STORY && !showVideo && !activatingUpdate && storyHost.owns(owner) },
                                    onPlayerKey = { action, version ->
                                        nativePlayer.ownsSourceVersion(version) && playback.currentCastSource(version) != null && performPlayerKey(action)
                                    }) },
                                playerContent = { owner, _, modifier -> Box(modifier) {
                                    if (player != null && storyOwner == owner && storyHost.owns(owner)) playerContent(player)
                                    else if (player == null) Text(playerError ?: "播放器未能初始化")
                                } })
                            section == DesktopSection.TOPIC -> DesktopTopicDetailScreen(topicId, storyTopic, community,
                                CommunityNavigation(::openVideo, ::openUser, ::openArticle, { loginDialog = true }, ::openLive, ::openBangumi, ::openDynamic, ::openTopic, ::openTopicKeyword,
                                    onDynamicRoute=::openDynamicRoute),
                                onBack = {
                                    if (topicStack.size > 1) {
                                        if (checkpointForNavigation()) { topicStack = topicStack.dropLast(1); topicId = topicStack.last() }
                                    } else navigate(topicReturnSection) {
                                        topicStack = emptyList()
                                        if (topicReturnSection == DesktopSection.DYNAMIC) dynamicRoute = topicReturnDynamicRoute
                                    }
                                }, onTopic = ::openTopic)
                            section == DesktopSection.USER -> {
                                val spaceTargetMid = userId
                                val spaceTargetEpoch = repository.sessionEpoch
                                val history = library.history().filter { it.authorMid == userId }
                                val progress = history.associate { card -> card.bvid to SpaceWatchProgress(card.bvid, card.preferredCid, card.title,
                                    card.progressSeconds ?: 0, card.duration, card.viewedAt) }
                                DesktopCompleteSpaceScreen(userId, repository, social, community, space, spaceContributions,
                                    ::openVideo, ::openUser, ::openArticle, ::openDynamic, ::openLive, ::openBangumi, ::openMusic,
                                    { showSeason(it, course = true) }, ::openResource, ::openCollection,
                                    onPlaylist = { playlist -> if (repository.sessionEpoch == spaceTargetEpoch && userId == spaceTargetMid) openListenSpaceQueue(playlist, spaceTargetMid, history) },
                                    onLogin = { loginDialog = true }, onExternalUrl = { raw ->
                                        runCatching { java.net.URI(imageUrl(raw)) }.getOrNull()?.takeIf { it.scheme in setOf("http", "https") }?.let { uri ->
                                            runCatching { java.awt.Desktop.getDesktop().browse(uri) }
                                        }
                                    }, progressByBvid = progress, localPositionMs = { bvid -> ((history.firstOrNull { it.bvid == bvid }?.progressSeconds ?: 0) * 1000L) },
                                    locateBvid = playing.details?.takeIf { it.authorMid == userId }?.bvid ?: history.firstOrNull()?.bvid, onTopic = ::openTopic, onTopicKeyword = ::openTopicKeyword, onImagePreviewFeedback = { error = it })
                            }
                            section in listOf(DesktopSection.DYNAMIC, DesktopSection.SEARCH, DesktopSection.MESSAGES, DesktopSection.ARTICLE, DesktopSection.NOTES) ->
                                CommunityContentScreen(when(section) {
                                    DesktopSection.DYNAMIC -> CommunitySection.DYNAMIC
                                    DesktopSection.SEARCH -> CommunitySection.SEARCH
                                    DesktopSection.USER -> CommunitySection.USER
                                    DesktopSection.MESSAGES -> CommunitySection.MESSAGES
                                    DesktopSection.ARTICLE -> CommunitySection.ARTICLE
                                    else -> CommunitySection.NOTES
                                }, repository, social, community, submitted, userId, articleId, noteVideo,
                                    ::openVideo, ::openUser, ::openArticle, { loginDialog = true }, ::openLive, ::openBangumi, runtime = pluginRuntime,
                                    initialDynamicId = dynamicRoute?.dynamicId.takeIf { section == DesktopSection.DYNAMIC }, onTopic = ::openTopic, onTopicKeyword = ::openTopicKeyword,
                                    defaultSearchHintEnabled = defaultSearchHintEnabled,
                                    initialCommentRootRpid=dynamicRoute?.rootReplyId?:0L,initialCommentTargetRpid=dynamicRoute?.targetReplyId?:0L)
                            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(section.localizedLabel(strings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = { refresh++ }, enabled = !feedLoading) { Text(strings["common_refresh"]) }
                                    if (section in listOf(DesktopSection.HOME, DesktopSection.POPULAR)) {
                                        TextButton(onClick = { page = (page - 1).coerceAtLeast(1) }, enabled = page > 1 && !feedLoading) { Text("上一页") }
                                        Text("$page"); TextButton(onClick = { page++ }, enabled = !feedLoading) { Text("下一页") }
                                    }
                                }
                                if (feedLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (cards.isEmpty() && !feedLoading) Text("这里暂时还没有视频", color = scheme.onSurfaceVariant)
                                LazyVerticalGrid(GridCells.Adaptive(240.dp), modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(cards, key = { it.bvid }) { card -> FeedCard(card) { openVideo(card) } }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (eyePaint.dimAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = eyePaint.dimAlpha)))
        if (eyePaint.warmAlpha > 0f) Box(Modifier.fillMaxSize().background(Color(eyePaint.warmArgb).copy(alpha = eyePaint.warmAlpha)))
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
        if (dlnaDialog) DesktopCastDialog(cast, media = { currentCastMedia() }, onDismiss = { dlnaDialog = false })
        if (googleCastDialog) DesktopGoogleCastDialog(pluginRuntime.context, pluginRuntime.googleCast,
            media = { currentCastMedia() }, onDismiss = { googleCastDialog = false })
        PluginCareReminder(pluginRuntime)
        val subtitleTarget = subtitleDialogTarget
        fun subtitleTargetOwned(target: DesktopSubtitleDialogTarget): Boolean {
            val current = playback.state.value
            return target.matches(current.details?.bvid, current.details?.pages?.getOrNull(current.currentPart)?.cid,
                player?.currentSourceVersion, repository.sessionEpoch, playback.currentCastSource(target.sourceVersion) != null)
        }
        LaunchedEffect(subtitleDialog, subtitleTarget, playing.details?.bvid, playing.currentPart, sessionEpoch, player?.currentSourceVersion) {
            if (subtitleDialog && (subtitleTarget == null || !subtitleTargetOwned(subtitleTarget))) subtitleDialog = false
        }
        if (subtitleDialog && subtitleTarget != null && subtitleTargetOwned(subtitleTarget)) {
            key(subtitleTarget) { OnlineSubtitleDialog(subtitleTarget.bvid, subtitleTarget.cid, community, subtitleAssets,
                onImport = { path, track, select ->
                    if (subtitleTargetOwned(subtitleTarget)) {
                        playback.notifyUserSubtitleTrackSelection()
                        player?.addSubtitle(path, track.lanDoc.ifBlank { track.lan }, track.lan, select)
                    } else { subtitleDialog = false; error = "播放内容已变化，请重新打开字幕选择" }
                }, onDismiss = { subtitleDialog = false }) }
        }
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
