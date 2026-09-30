package com.bilipai.desktop

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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.list.HistoryNavigationKind
import com.android.purebilibili.feature.list.resolveHistoryNavigationKind
import com.android.purebilibili.feature.list.resolveHistoryResumePositionMs
import com.android.purebilibili.feature.bangumi.policy.parseCourseNavigation
import com.android.purebilibili.core.plugin.skin.LocalUiSkinState
import com.android.purebilibili.core.plugin.skin.UiSkinSurface
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.audio.ListenAudioSession
import com.bilipai.desktop.audio.ListenAudioState
import com.bilipai.desktop.audio.ListenAudioStore
import com.bilipai.desktop.data.*
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMetadata
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerPreferencesStore
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.player.PictureInPictureController
import com.bilipai.desktop.player.WindowsMediaSession
import com.bilipai.desktop.player.WindowsMediaCommand
import com.bilipai.desktop.player.WindowsMediaSnapshot
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopEyePaint
import com.bilipai.desktop.backup.DesktopBackupCoordinator
import com.bilipai.desktop.backup.DesktopBackupStore
import com.bilipai.desktop.cast.DesktopCastController
import com.bilipai.desktop.cast.DesktopCastMediaResolver
import com.bilipai.desktop.cast.DesktopCastDialog
import com.bilipai.desktop.cast.DesktopGoogleCastDialog
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
    ARTICLE("专栏", "▤"), NOTES("视频笔记", "✎"), COLLECTION("合集与系列", "▣")
}

@Composable
fun DesktopApp(repository: DesktopRepository, player: MpvPlayer?, playerError: String?, initialVideo: String?,
    onExit: () -> Unit, onToggleFullscreen: () -> Unit, hostWindow: java.awt.Window? = null) {
    val account by repository.account.collectAsState()
    val settingsLibrary = remember { DesktopLibrary() }
    val library = remember(account?.mid) { if (account == null) settingsLibrary else DesktopLibrary(DesktopLibrary.directoryForAccount(account?.mid)) }
    val preferenceStore = remember { PlayerPreferencesStore() }
    var preferences by remember { mutableStateOf(preferenceStore.read().let { it.copy(speed = it.preferredSpeed) }) }
    val latestPreferences by rememberUpdatedState(preferences)
    val scope = rememberCoroutineScope()
    val social = remember(repository) { DesktopSocialRepository(repository) }
    val community = remember(repository) { DesktopCommunityRepository(repository) }
    val discovery = remember(repository) { DesktopDiscoveryRepository(repository) }
    val browseMemory = remember(account?.mid) { DesktopBrowseMemory() }
    val pluginStore = remember { DesktopPluginStore(DesktopLibrary.directoryForAccount(null)) }
    val pluginRuntime = remember(pluginStore) { DesktopPluginRuntime(pluginStore, repository, community, discovery) }
    val packages by pluginRuntime.packages.state.collectAsState()
    val cast = remember(pluginRuntime) { DesktopCastController(pluginRuntime.context, pluginRuntime.dlnaCast) }
    val castResolver = remember(repository, pluginRuntime) { DesktopCastMediaResolver(repository, pluginRuntime.context) }
    val casting by cast.playbackState.collectAsState()
    val castBusy by cast.isBusy.collectAsState()
    val googleCasting by pluginRuntime.googleCast.playbackState.collectAsState()
    val googleCastBusy by pluginRuntime.googleCast.isBusy.collectAsState()
    val anyCasting = casting.isActive || googleCasting.isActive
    val anyCastBusy = castBusy || googleCastBusy
    val eyePlaybackActive = remember { MutableStateFlow(false) }
    var eyePaint by remember { mutableStateOf(DesktopEyePaint(0f, 0f)) }
    val downloads = remember(repository) { DesktopDownloadManager(repository) }
    val danmaku = remember(player, repository) { player?.let { DanmakuOverlay(it, httpClient = repository.httpClient) } }
    val playback = remember(repository, player, danmaku, library) {
        DesktopPlaybackController(repository, player, playerError, danmaku, library, { latestPreferences }, scope, plugins = pluginRuntime, community = community)
    }
    var systemTargetAudio by remember { mutableStateOf(false) }
    val audioPlayer = remember(player) { player?.let { MpvPlayer() } }
    val beforeListenAcquire = remember { java.util.concurrent.atomic.AtomicReference<() -> Unit>({}) }
    val listen = remember(repository, community, audioPlayer, playback, account?.mid) {
        audioPlayer?.let { ListenAudioSession(repository, community, it, preferences, onAcquirePlayback = { beforeListenAcquire.get().invoke(); systemTargetAudio = true; playback.pause(); player?.setPaused(true) },
            store = ListenAudioStore(DesktopLibrary.directoryForAccount(account?.mid).resolve("listen-state.json"))) }
    }
    val emptyListenState = remember { MutableStateFlow(ListenAudioState()) }
    val listening by (listen?.state ?: emptyListenState).collectAsState()
    val playing by playback.state.collectAsState()
    val emptyNativeState = remember { MutableStateFlow(com.bilipai.desktop.player.PlayerState()) }
    val native by (player?.state ?: emptyNativeState).collectAsState()
    val subtitleAssets = remember(repository) { DesktopSubtitleAssets(repository.httpClient) }
    var subtitleDialog by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(settingsLibrary.dark) }
    var section by remember { mutableStateOf(DesktopSection.HOME) }
    var showVideo by remember { mutableStateOf(initialVideo != null) }
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
    val emptyPipError = remember { MutableStateFlow<String?>(null) }
    val pipError by (pip?.error ?: emptyPipError).collectAsState()
    val retainedMedia = remember(player, repository, account?.mid) {
        DesktopRetainedMedia(scope, player) { pip?.close(); playback.stop(); listen?.pause(); systemTargetAudio = false }
    }
    SideEffect {
        beforeListenAcquire.set { pip?.close(); retainedMedia.stop() }
        pipSeek.set { seconds -> if (retainedMedia.current != null) player?.seekTo(seconds) else playback.seekTo(seconds) }
    }
    val backup = remember(playback, listen, pip, pluginRuntime, cast, retainedMedia) {
        DesktopBackupCoordinator(DesktopBackupStore(DesktopLibrary.directoryForAccount(null)), beforeRestore = {
            withContext(Dispatchers.Main) { pip?.close(); retainedMedia.close(); playback.close(); listen?.close() }
            cast.quiesce()
            pluginRuntime.shutdownForRestore()
        }, afterRestore = { withContext(Dispatchers.Main) { onExit() } })
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
    var loginDialog by remember { mutableStateOf(false) }
    var playerSettings by remember { mutableStateOf(false) }
    var backupSettings by remember { mutableStateOf(false) }
    var castDialog by remember { mutableStateOf(false) }
    var dlnaDialog by remember { mutableStateOf(false) }
    var googleCastDialog by remember { mutableStateOf(false) }
    var userId by remember { mutableLongStateOf(0) }
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
        preferences = next.normalized()
        player?.applyPreferences(preferences)
        listen?.updatePreferences(preferences)
        danmaku?.applySettings(preferences.danmaku)
        val snapshot = preferences
        scope.launch(Dispatchers.IO) { preferenceStore.save(snapshot) }
    }
    fun navigate(target: DesktopSection) {
        if (activatingUpdate) return
        playback.checkpoint()
        showVideo = false; section = target; page = 1; error = null
        if (target == DesktopSection.HISTORY) cards = library.history()
        if (target == DesktopSection.FAVORITES) cards = library.favorites()
    }
    fun openVideo(card: VideoCard) {
        if (activatingUpdate) return
        retainedMedia.stop(); listen?.pause(); systemTargetAudio = false
        showVideo = true; mediaActive = false; playback.open(card)
    }
    fun openQueue(videos: List<VideoCard>, selected: VideoCard) {
        if (activatingUpdate || videos.isEmpty()) return
        val index = videos.indexOfFirst { it.bvid == selected.bvid && it.preferredCid == selected.preferredCid }.takeIf { it >= 0 } ?: 0
        retainedMedia.stop(); listen?.pause(); systemTargetAudio = false
        showVideo = true; mediaActive = false; playback.openQueue(videos, index)
    }
    fun openUser(id: Long) { userId = id; navigate(DesktopSection.USER) }
    fun openArticle(id: Long) { articleId = id; navigate(DesktopSection.ARTICLE) }
    fun openNotes(info: VideoDetails) { noteVideo = info; navigate(DesktopSection.NOTES) }
    fun openLive(id: Long) { roomId = id; navigate(DesktopSection.LIVE) }
    fun showSeason(id: Long, epId: Long = 0, course: Boolean = false, progress: Double = 0.0) {
        seasonId = id; episodeId = epId; isCourse = course; seasonProgress = progress
        navigate(DesktopSection.BANGUMI)
    }
    fun openBangumi(id: Long) { showSeason(id) }
    fun openCollection(mid: Long, id: Long, type: String) {
        collectionMid = mid; collectionId = id; collectionType = type; navigate(DesktopSection.COLLECTION)
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
        submitted = text; navigate(DesktopSection.SEARCH)
    }
    suspend fun currentCastMedia(): com.android.purebilibili.core.plugin.CastPluginMediaRequest {
        check(retainedMedia.current == null && !systemTargetAudio) { "请先打开需要投屏的视频" }
        val current = playback.state.value
        val info = current.details ?: error("请先打开需要投屏的视频")
        val token = player?.currentSourceVersion
        val epoch = repository.sessionEpoch
        val source = repository.playback(info, current.currentPart, current.quality)
        val media = castResolver.video(info, current.currentPart, source, ((player?.state?.value?.positionSeconds ?: 0.0) * 1000).toLong())
        check(epoch == repository.sessionEpoch && token == player?.currentSourceVersion && playback.state.value.details?.bvid == info.bvid &&
            playback.state.value.currentPart == current.currentPart && retainedMedia.current == null && !systemTargetAudio) {
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
    DisposableEffect(pluginRuntime) { onDispose { pluginRuntime.close() } }
    DisposableEffect(downloads) { onDispose { downloads.close() } }
    DisposableEffect(danmaku) { onDispose { danmaku?.close() } }
    DisposableEffect(listen) { onDispose { listen?.close() } }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer?.close() } }
    DisposableEffect(subtitleAssets) { onDispose { subtitleAssets.close() } }
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
            else -> Unit
        }
    }
    DesktopHistoryRefreshEffects(browseMemory, showVideo && playing.details != null)
    LaunchedEffect(player, danmaku) { player?.applyPreferences(preferences); danmaku?.applySettings(preferences.danmaku) }
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
    LaunchedEffect(Unit) { runCatching { repository.refreshAccount() }; initialVideo?.let { playback.open(it) } }
    LaunchedEffect(playing.details?.bvid, playing.currentPart) {
        while (playing.details != null) { delay(5000); withContext(Dispatchers.IO) { playback.checkpoint() } }
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

    val scheme = if (dark) darkColorScheme(primary = Color(0xFF83CDD1), primaryContainer = Color(0xFF294A4F), background = Color(0xFF101719),
        surface = Color(0xFF182226), surfaceVariant = Color(0xFF233034))
    else lightColorScheme(primary = Color(0xFF256D77), primaryContainer = Color(0xFFD8E7E9), background = Color(0xFFF4F8F9),
        surface = Color.White, surfaceVariant = Color(0xFFEAF0F2))
    CompositionLocalProvider(LocalDesktopBrowseMemory provides browseMemory, LocalUiSkinState provides packages.skin) {
    MaterialTheme(colorScheme = scheme, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        Box(Modifier.fillMaxSize()) {
        // libmpv's audio worker needs a retained native host even when its screen is not visible.
        if (audioPlayer != null) SwingPanel(factory = { audioPlayer.surface }, background = Color.Transparent, modifier = Modifier.size(1.dp))
        Surface(Modifier.fillMaxSize().onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) { onToggleFullscreen(); true }
            else if (showVideo && playing.details != null && playerFocused && !searchFocused && player != null) {
                // Shortcuts are scoped to the focused player; comment and search editors keep their keys.
                when (val action = resolvePlayerKeyAction(event, isTextInputActive = searchFocused)) {
                    PlayerKeyAction.PlayPause -> { player.togglePause(); true }
                    is PlayerKeyAction.SeekRelative -> { playback.seekBy(action.deltaMs / 1000.0); true }
                    is PlayerKeyAction.SeekPercent -> { playback.seekTo(native.durationSeconds * action.fraction); true }
                    PlayerKeyAction.VolumeUp -> { changePreferences(preferences.copy(volume = native.volume + 5)); true }
                    PlayerKeyAction.VolumeDown -> { changePreferences(preferences.copy(volume = native.volume - 5)); true }
                    PlayerKeyAction.ToggleMute -> { changePreferences(preferences.copy(muted = !native.muted)); true }
                    PlayerKeyAction.ToggleFullscreen -> { onToggleFullscreen(); true }
                    PlayerKeyAction.ToggleDanmaku -> { changePreferences(preferences.copy(danmaku = preferences.danmaku.copy(enabled = !preferences.danmaku.enabled))); true }
                    PlayerKeyAction.PreviousPart -> { playback.previous(); true }
                    PlayerKeyAction.NextPart -> { playback.next(); true }
                    is PlayerKeyAction.SetSpeed -> { changePreferences(preferences.copy(speed = action.speed.toDouble())); true }
                    else -> false
                }
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
                        DesktopSection.entries.filter { it !in listOf(DesktopSection.SEARCH, DesktopSection.USER, DesktopSection.ARTICLE, DesktopSection.NOTES, DesktopSection.COLLECTION) }.forEach { item ->
                            Surface(Modifier.fillMaxWidth().height(48.dp).clickable { navigate(item) }, shape = RoundedCornerShape(24.dp),
                                color = if (section == item && !showVideo) scheme.primaryContainer else Color.Transparent) {
                                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(item.symbol, style = MaterialTheme.typography.titleLarge); Text(item.label)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { playerSettings = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("播放与弹幕设置") }
                    TextButton(onClick = { backupSettings = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("WebDAV 与备份") }
                    TextButton(onClick = { dark = !dark; settingsLibrary.setDark(dark) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (dark) "☀ 浅色外观" else "☾ 深色外观") }
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
                    Box(Modifier.fillMaxWidth()) {
                    DesktopUiSkinDecoration(UiSkinSurface.HOME_TOP_CHROME, { it.topAtmosphere }, Modifier.matchParentSize(), onError = { error = it })
                    DesktopUiSkinDecoration(UiSkinSurface.HOME_TOP_CHROME, { it.searchCapsuleBackground }, Modifier.matchParentSize(), onError = { error = it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showVideo) OutlinedButton(onClick = { playback.checkpoint(); showVideo = false }, modifier = Modifier.height(52.dp)) { Text("‹ 返回") }
                        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索视频、UP 主、番剧、专栏，或粘贴 BV / 链接") },
                            shape = RoundedCornerShape(26.dp), modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.hasFocus },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                        Button(onClick = ::submitSearch, modifier = Modifier.height(52.dp)) { Text("搜索") }
                    }
                    }
                    (error ?: playing.error)?.let { text ->
                        Surface(color = scheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text, Modifier.weight(1f)); TextButton(onClick = { error = null; playback.dismissError() }) { Text("收起") }
                            }
                        }
                    }
                    if (listen != null && section != DesktopSection.LISTEN) ListenNowPlayingBar(listen, { navigate(DesktopSection.LISTEN) })
                    if (casting.isActive) TextButton(onClick = { dlnaDialog = true }) { Text("投屏：${casting.deviceLabel.ifBlank { "播放设备" }} · 打开控制") }
                    if (googleCasting.isActive) TextButton(onClick = { googleCastDialog = true }) { Text("Google Cast：${googleCasting.deviceLabel.ifBlank { "播放设备" }} · 打开控制") }
                    if (playing.recovering) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        playing.recoveryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    playing.manualSkip?.let { action -> Button(onClick = playback::executeManualSkip) { Text(action.label) } }
                    if (!showVideo && playing.details != null && player != null) {
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
                    val retainedPage = when (retainedOwner) {
                        retainedMedia.live -> DesktopSection.LIVE
                        retainedMedia.bangumi -> DesktopSection.BANGUMI
                        retainedMedia.offline -> DesktopSection.DOWNLOADS
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
                        PlayerPanel(initialized, preferences, ::changePreferences, onToggleFullscreen,
                            modifier = Modifier.onFocusChanged { playerFocused = it.hasFocus }.focusable(), onMessage = { error = it },
                            onPreviousPart = if (showVideo && playback.hasPrevious) ({ playback.previous() }) else null,
                            onNextPart = if (showVideo && playback.hasNext) ({ playback.next() }) else null,
                            onOnlineSubtitles = if (showVideo && playing.details != null) ({ subtitleDialog = true }) else null,
                            onSeekTo = if (showVideo && playing.details != null) playback::seekTo else null,
                            renderSurface = !pipActive, onPictureInPicture = if (pip != null && hostWindow != null) ({ pip.open(hostWindow, initialized.state.value.sourceTitle) }) else null)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            showVideo && playing.opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            showVideo && playing.details != null -> DesktopVideoPage(playing, player, playerContent, favorite,
                                onVideo = ::openVideo, onPart = playback::playPart, onQuality = playback::switchQuality,
                                onFavorite = { library.toggleFavorite(playing.details!!.asCard()); favorite = library.isFavorite(playing.details!!.bvid) },
                                onCast = { scope.launch {
                                    try { pluginRuntime.setEnabled(pluginRuntime.dlnaCast.id, true); castDialog = true }
                                    catch (failure: Exception) { error = failure.message ?: "无法启用 DLNA" }
                                } },
                                engagement = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { VideoEngagementPanel(playing.details!!, repository, social, community,
                                    ::openUser, { loginDialog = true }, ::openNotes, playback::seek,
                                    cid = playing.details!!.pages[playing.currentPart].cid)
                                    DiscoveryUgcCollectionPanel(playing.details!!, ::openVideo, ::openQueue,
                                        currentCid = playing.details!!.pages[playing.currentPart].cid)
                                } },
                                onDownload = { scope.launch {
                                    try {
                                        val info = playing.details!!
                                        val part = info.pages[playing.currentPart]
                                        val source = repository.playback(info, playing.currentPart, playing.quality)
                                        downloads.enqueue(com.bilipai.desktop.player.PlaybackSource(videoUrl = source.videoUrl, audioUrl = source.audioUrl,
                                            referer = source.referer, cookieHeader = source.cookieHeader, title = source.title,
                                            progressiveSegments = source.progressiveSegments), metadata = DownloadMetadata(
                                            aid = info.aid, bvid = info.bvid, cid = part.cid, cover = info.cover, author = info.author,
                                            durationSeconds = part.duration.toInt(), quality = source.quality, episodeLabel = part.title))
                                        error = "已加入下载队列"
                                    } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure.message ?: "下载失败" }
                                } })
                            section in listOf(DesktopSection.CLOUD_FAVORITES, DesktopSection.CLOUD_HISTORY, DesktopSection.WATCH_LATER, DesktopSection.FOLLOWINGS, DesktopSection.LIKED) ->
                                PersonalContentScreen(when(section) {
                                    DesktopSection.CLOUD_FAVORITES -> PersonalSection.FAVORITES
                                    DesktopSection.CLOUD_HISTORY -> PersonalSection.HISTORY
                                    DesktopSection.WATCH_LATER -> PersonalSection.WATCH_LATER
                                    DesktopSection.LIKED -> PersonalSection.LIKED
                                    else -> PersonalSection.FOLLOWINGS
                                }, repository, social, community, ::openVideo, ::openUser, { loginDialog = true }, ::openResource, ::openCollection)
                            section == DesktopSection.COLLECTION -> CommunityCollectionScreen(collectionMid, collectionId, collectionType, community, ::openVideo, ::openUser, { loginDialog = true })
                            section == DesktopSection.PLUGINS -> PluginCenterScreen(pluginRuntime, ::openVideo, ::openQueue)
                            section in listOf(DesktopSection.HOME, DesktopSection.POPULAR, DesktopSection.REGION, DesktopSection.RANKING, DesktopSection.PRECIOUS, DesktopSection.WEEKLY) ->
                                DiscoveryContentScreen(when (section) {
                                    DesktopSection.HOME -> DiscoverySection.RECOMMEND
                                    DesktopSection.POPULAR -> DiscoverySection.POPULAR
                                    DesktopSection.REGION -> DiscoverySection.REGION
                                    DesktopSection.RANKING -> DiscoverySection.RANKING
                                    DesktopSection.PRECIOUS -> DiscoverySection.PRECIOUS
                                    else -> DiscoverySection.WEEKLY
                                }, discovery, repository, pluginStore, ::openVideo, ::openUser, { loginDialog = true },
                                    onBangumiPartition = { type -> seasonType = type; showSeason(0) }, onPlayQueue = ::openQueue, runtime = pluginRuntime)
                            section == DesktopSection.LIVE -> LiveBrowserScreen(repository, player, playerError, { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, roomId, danmaku, retainedMedia)
                            section == DesktopSection.BANGUMI -> BangumiBrowserScreen(repository, player, playerError, { mediaActive = it; if (it) listen?.pause() }, downloads, onToggleFullscreen, playerContent, seasonId, danmaku,
                                initialIsCourse = isCourse, initialEpisodeId = episodeId, initialProgressSeconds = seasonProgress, initialSeasonType = seasonType, retained = retainedMedia)
                            section == DesktopSection.DOWNLOADS -> DownloadBrowserScreen(downloads, player, playerError, { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, danmaku, retainedMedia)
                            section == DesktopSection.LISTEN -> if (listen != null) ListenBrowserScreen(listen, preferences, ::changePreferences, ::openVideo, { loginDialog = true })
                                else Text(playerError ?: "音频播放器未能初始化")
                            section in listOf(DesktopSection.DYNAMIC, DesktopSection.SEARCH, DesktopSection.USER, DesktopSection.MESSAGES, DesktopSection.ARTICLE, DesktopSection.NOTES) ->
                                CommunityContentScreen(when(section) {
                                    DesktopSection.DYNAMIC -> CommunitySection.DYNAMIC
                                    DesktopSection.SEARCH -> CommunitySection.SEARCH
                                    DesktopSection.USER -> CommunitySection.USER
                                    DesktopSection.MESSAGES -> CommunitySection.MESSAGES
                                    DesktopSection.ARTICLE -> CommunitySection.ARTICLE
                                    else -> CommunitySection.NOTES
                                }, repository, social, community, submitted, userId, articleId, noteVideo,
                                    ::openVideo, ::openUser, ::openArticle, { loginDialog = true }, ::openLive, ::openBangumi, runtime = pluginRuntime)
                            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(section.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = { refresh++ }, enabled = !feedLoading) { Text("刷新") }
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
        if (playerSettings) PlaybackSettingsDialog(preferences, ::changePreferences, { playerSettings = false })
        if (backupSettings) BackupSettingsDialog(backup, { backupSettings = false }, onExit)
        if (castDialog) AlertDialog(onDismissRequest = { castDialog = false }, title = { Text("选择投屏方式") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { castDialog = false; dlnaDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("DLNA / 电视媒体播放") }
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
        if (subtitleDialog && playing.details != null) {
            val info = playing.details!!
            val cid = info.pages.getOrNull(playing.currentPart)?.cid ?: 0
            key(info.bvid, cid) { OnlineSubtitleDialog(info.bvid, cid, community, subtitleAssets,
                onImport = { path, track, select ->
                    val current = playback.state.value
                    if (current.details?.bvid == info.bvid && current.details.pages.getOrNull(current.currentPart)?.cid == cid)
                        player?.addSubtitle(path, track.lanDoc.ifBlank { track.lan }, track.lan, select)
                }, onDismiss = { subtitleDialog = false }) }
        }
        if (updatesDialog) WindowsUpdateDialog(updateState, automaticUpdates, activatingUpdate,
            playing.details != null || playing.opening || mediaActive || listening.active || anyCasting || anyCastBusy || pipActive,
            onAutomatic = { automaticUpdates = it; settingsLibrary.setAutomaticUpdates(it) },
            onPrepare = { prepareUpdate(it, true) }, onActivate = { manuallyRequested = true }, onDismiss = { updatesDialog = false })
    }
    }
}

private fun VideoDetails.asCard() = VideoCard(bvid, title, cover, author, playCount, pages.firstOrNull()?.duration?.toInt() ?: 0, authorMid = authorMid)

@Composable
private fun DesktopVideoPage(playing: DesktopPlaybackState, player: MpvPlayer?, playerContent: @Composable (MpvPlayer) -> Unit,
    favorite: Boolean, onVideo: (VideoCard) -> Unit, onPart: (Int) -> Unit, onQuality: (Int) -> Unit, onFavorite: () -> Unit,
    engagement: @Composable () -> Unit, onDownload: () -> Unit, onCast: () -> Unit) {
    val info = playing.details ?: return
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (player != null) playerContent(player)
            Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                playing.availableQualities.forEach { option ->
                    FilterChip(playing.effectiveQuality == option.id, { onQuality(option.id) }, label = { Text(option.label) })
                }
                OutlinedButton(onClick = onFavorite) { Text(if (favorite) "已存本地收藏" else "本地收藏") }
                OutlinedButton(onClick = onDownload) { Text("下载本集") }
                OutlinedButton(onClick = onCast) { Text("投屏") }
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
            items(playing.related, key = { it.bvid }) { card -> FeedCard(card) { onVideo(card) } }
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
