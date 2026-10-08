package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.MpvPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Playback jobs belong to the window/account, so a browser page can be disposed independently. */
sealed class DesktopMediaPageMemory(parent: CoroutineScope, internal val player: MpvPlayer?) : AutoCloseable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + lifetime)
    var loaded by mutableStateOf(false)
    var sourceVersion by mutableStateOf<Long?>(null)
    var playJob by mutableStateOf<Job?>(null)
    var error by mutableStateOf<String?>(null)
    var opening by mutableStateOf(false)
    var danmakuEnabled by mutableStateOf(true)
    internal var checkpoint: () -> Unit = {}
    internal var onBeforeStop: () -> Unit = {}
    internal var release: () -> Unit = {}
    internal var onRetireSource: () -> Unit = {}
    var previous by mutableStateOf<(() -> Unit)?>(null)
    var next by mutableStateOf<(() -> Unit)?>(null)
    val ownsNativeSource get() = sourceVersion != null && sourceVersion == player?.currentSourceVersion

    fun launchRequest(block: suspend CoroutineScope.() -> Unit) {
        if (!scope.isActive) return
        playJob?.cancel()
        val pending = scope.launch(start = CoroutineStart.LAZY) { block() }
        playJob = pending
        pending.start()
    }

    suspend fun isCurrentRequest(): Boolean = playJob === currentCoroutineContext()[Job]

    /** A foreign source can release this memory, but can never be stopped by it. */
    fun stopPlayback() {
        onRetireSource()
        playJob?.cancel(); playJob = null
        if (ownsNativeSource) { checkpoint(); onBeforeStop(); player?.stopIfSourceVersion(sourceVersion!!) }
        sourceVersion = null; loaded = false; opening = false; previous = null; next = null
        release()
    }

    override fun close() { stopPlayback(); lifetime.cancel() }
}

class DesktopLivePageMemory(parent: CoroutineScope, player: MpvPlayer?) : DesktopMediaPageMemory(parent, player) {
    internal var liveSourceSnapshot: com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot? = null
    internal var recoveryPorts: DesktopLiveRecoveryPorts? = null
    internal var remainingLiveReloadAttempts = com.android.purebilibili.feature.live.MAX_PLAYBACK_RELOAD_ATTEMPTS
    // One same-line native prepare before consuming the original next-source policy.
    // Only actual output or an explicit initial/manual selection replenishes it.
    internal var remainingLiveNativeReprepareAttempts = com.android.purebilibili.feature.live.MAX_PLAYBACK_RELOAD_ATTEMPTS
    internal var recoveryObserver: Job? = null
    internal var handledLiveFailure: com.bilipai.desktop.player.PlayerFailure? = null
    internal var handledLiveEof: com.bilipai.desktop.player.PlayerNativeEof? = null
    init {
        onRetireSource = {
            liveSourceSnapshot = null; recoveryPorts = null; handledLiveFailure = null; handledLiveEof = null
            remainingLiveNativeReprepareAttempts = 0
            recoveryObserver?.cancel(); recoveryObserver = null
        }
    }
    var section by mutableStateOf("热门")
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var areas by mutableStateOf(emptyList<LiveArea>())
    var parent by mutableStateOf<LiveArea?>(null)
    var area by mutableStateOf<LiveArea?>(null)
    var page by mutableIntStateOf(1)
    var generation by mutableIntStateOf(0)
    var cards by mutableStateOf(emptyList<LiveCard>())
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var room by mutableStateOf<LiveRoomDetails?>(null)
    var stream by mutableStateOf<LivePlaybackInfo?>(null)
    // Requested quality survives server fallback; actual quality lives in stream.source.
    var quality by mutableIntStateOf(10000)
    var onlyAudio by mutableStateOf(false)
    var initialRoomRequest by mutableLongStateOf(0)
    var chat by mutableStateOf<DesktopLiveSession?>(null)
    var liveToken: Long? = null
    private var chatActions: Job? = null

    internal fun connectChat(session: DesktopLiveSession, overlay: DanmakuOverlay?, action: suspend (com.android.purebilibili.feature.live.LiveRealtimeAction) -> Unit) {
        releaseChat(overlay)
        chat = session; liveToken = overlay?.enterLive()
        // Start the consumer before the socket, preserving original realtime ordering.
        chatActions = scope.launch(start = CoroutineStart.UNDISPATCHED) { session.actions.collect(action) }
        session.start()
        release = { releaseChat(overlay) }
    }

    private fun releaseChat(overlay: DanmakuOverlay?) {
        chatActions?.cancel(); chatActions = null
        chat?.close(); chat = null
        liveToken?.let { overlay?.clearLive(it) }; liveToken = null
    }
}

class DesktopBangumiPageMemory(parent: CoroutineScope, player: MpvPlayer?) : DesktopMediaPageMemory(parent, player) {
    var section by mutableStateOf("索引")
    var courseUrl by mutableStateOf("")
    var timetable by mutableStateOf(emptyList<com.android.purebilibili.data.model.response.TimelineDay>())
    var seasonType by mutableIntStateOf(1)
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var page by mutableIntStateOf(1)
    var generation by mutableIntStateOf(0)
    var cards by mutableStateOf(emptyList<BangumiCard>())
    var hasMore by mutableStateOf(false)
    var season by mutableStateOf<BangumiSeason?>(null)
    var episode by mutableStateOf<BangumiEpisode?>(null)
    var playback by mutableStateOf<BangumiPlaybackInfo?>(null)
    var loading by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var quality by mutableIntStateOf(80)
    var initialRequest: List<Any>? = null
}

class DesktopOfflinePageMemory(parent: CoroutineScope, player: MpvPlayer?) : DesktopMediaPageMemory(parent, player) {
    var current by mutableStateOf<String?>(null)
    var deleting by mutableStateOf<DownloadTask?>(null)
    var deleteFiles by mutableStateOf(false)
    internal var assetsJob: Job? = null
    internal var checkpointJob: Job? = null
    override fun close() { assetsJob?.cancel(); checkpointJob?.cancel(); super.close() }
}

class DesktopRetainedMedia(parent: CoroutineScope, val player: MpvPlayer?, private val onAcquire: () -> Unit) : AutoCloseable {
    val live = DesktopLivePageMemory(parent, player)
    val bangumi = DesktopBangumiPageMemory(parent, player)
    val offline = DesktopOfflinePageMemory(parent, player)
    val external = DesktopExternalPageMemory(parent, player)
    val pages: List<DesktopMediaPageMemory> = listOf(live, bangumi, offline, external)
    val current get() = pages.firstOrNull { it.ownsNativeSource }
    val title get() = when (current) {
        live -> live.room?.title
        bangumi -> bangumi.episode?.let { "${bangumi.season?.title.orEmpty()} ${it.title} ${it.subtitle}" }
        offline -> player?.state?.value?.sourceTitle
        external -> external.request?.title
        else -> null
    }.orEmpty()

    fun acquire(target: DesktopMediaPageMemory) {
        require(target in pages)
        pages.filter { it !== target }.forEach { it.stopPlayback() }
        onAcquire()
    }

    fun stop() { pages.forEach { it.stopPlayback() } }
    override fun close() { pages.forEach { it.close() } }
}

/** Always mounted by the shell; page disposal does not stop, detach ownership or close the live socket. */
@Composable
fun DesktopRetainedMediaEffects(memory: DesktopRetainedMedia, onActive: (Boolean) -> Unit) {
    val latestActive by rememberUpdatedState(onActive)
    val versions = memory.pages.map { it.sourceVersion to it.opening }
    LaunchedEffect(memory, versions) {
        val player = memory.player ?: return@LaunchedEffect
        var completedEpisode: Long? = null
        player.state.collect { state ->
            memory.pages.filter { it.sourceVersion != null && !it.ownsNativeSource }.forEach { it.stopPlayback() }
            val episodeVersion = memory.bangumi.sourceVersion
            if (memory.current === memory.bangumi && state.ended && episodeVersion != null && completedEpisode != episodeVersion) {
                completedEpisode = episodeVersion
                // Upstream BangumiPlayerViewModel always advances sequentially and stops at the last episode.
                memory.bangumi.next?.invoke() ?: run { memory.bangumi.notice = "已是最后一集" }
            }
            if (memory.current === memory.external && state.ended) memory.external.releaseCompletedLaunch()
            latestActive(memory.pages.any { it.opening } || memory.current != null && state.error == null && !state.ended &&
                (state.loading || state.videoCodec != null || state.audioCodec != null))
        }
    }
    DisposableEffect(memory) { onDispose { memory.close() } }
}
