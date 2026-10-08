package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import coil3.compose.LocalPlatformContext
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.audio.screen.MusicDetailScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.audio.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A stateless UI view of the existing AU session. Captured callbacks cannot
 * select whichever song happens to be current after their page/source retires. */
internal class DesktopOriginalAuMusicPage(
    val key: BiliPaiNavKey.MusicDetail,
    private val session: ListenAudioSession,
    val window: DesktopOriginalVideoRootWindowEnvironment,
    val caller: Job,
    private val active: () -> Boolean,
    private val source: OwnedPlaybackSourceSnapshot?,
) {
    private val epoch = window.root.capturedEpoch
    fun ownsRoot() = caller.isActive && window.owns() && window.repository.sessionEpoch == epoch
    fun isActive() = active()
    fun isCurrentUi() = ownsRoot() && active() && window.currentKey() == key
    fun ownsSource() = isCurrentUi() && source != null &&
        session.captureAuMusicSource(key.sid)?.let {
            it.sourceVersion == source.sourceVersion && it.source == source.source
        } == true && session.player.ownsSourceSnapshot(source)
    fun runUi(block: () -> Unit) {
        if (!isCurrentUi()) return
        try { block() } catch (_: CancellationException) { /* Actual caller/account retired. */ }
    }
    fun loadMusic(sid: Long) {
        require(sid == key.sid)
        if (isCurrentUi()) session.loadAuMusicForPage(sid, caller, ::isCurrentUi)
    }
    fun togglePlayPause() {
        if (!isCurrentUi() || session.state.value.loading) return
        val expected = source
        if (expected == null) { loadMusic(key.sid); return }
        if (ownsSource()) session.setAuMusicPaused(expected, caller, ::ownsSource, !session.player.state.value.paused)
    }
    fun seekTo(milliseconds: Long) { source?.let { session.seekAuMusic(it, caller, ::ownsSource, milliseconds) } }
    fun adjustLyricsOffset(delta: Long) { source?.let { session.adjustAuLyricsOffset(it, caller, ::ownsSource, delta) } }
    fun retryLyrics() { source?.let { session.retryAuLyrics(it, caller, ::ownsSource) } }
    fun searchLyrics(title: String) { source?.let { session.searchAuLyrics(it, caller, ::ownsSource, title) } }
    fun selectLyricsCandidate(index: Int) { source?.let { session.selectAuLyrics(it, caller, ::ownsSource, index) } }
    fun setVolume(fraction: Float): Boolean = source?.let {
        session.setAuMusicVolume(it, caller, ::ownsSource, fraction)
    } ?: false

    suspend fun externalPlaylistRequest(): DesktopOriginalExternalPlaylistRequest {
        val actualCaller = currentCoroutineContext()
        val job = checkNotNull(actualCaller[Job])
        fun current() = job.isActive && isCurrentUi()
        fun check() {
            actualCaller.ensureActive()
            if (!current()) throw CancellationException("AU playlist request retired")
        }
        check()
        val repository = window.repository
        val api = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, ::current)
        val search = repository.ownedHomeService(SearchApi::class.java, "https://api.bilibili.com/", epoch, ::current)
        return object : DesktopOriginalExternalPlaylistRequest {
            override val primaryApi: BilibiliApi get() { check(); return api }
            override val primarySearchApi: SearchApi get() { check(); return search }
            override fun assertCurrent() { check() }
            override fun admitCurrentMutation(action: () -> Unit): Boolean {
                if (!current()) return false
                return window.root.entry.gate.commit { check(); action() }
            }
        }
    }
}

/** AU SID keeps its actual Listen native actor. BV/CID NativeMusic continues to
 * use the complete AudioMode physical leaf, and BgmDetail remains metadata. */
@Composable internal fun DesktopOriginalAuMusicHost(
    key: BiliPaiNavKey.MusicDetail,
    session: ListenAudioSession,
    active: Boolean,
    background: StateFlow<Boolean>,
    onBack: () -> Unit,
    updateRootVolume: (Float) -> Unit,
) {
    val window = LocalDesktopOriginalVideoRootWindowEnvironment.current
    if (!window.owns()) return
    val pageScope = rememberCoroutineScope()
    val caller = checkNotNull(pageScope.coroutineContext[Job])
    val latestActive by rememberUpdatedState(active)
    val audioState by session.state.collectAsState()
    val native by session.player.state.collectAsState()
    val source = remember(key) { MusicPlaybackSource.AudioSong(key.sid) }
    val expected = session.captureAuMusicSource(key.sid)
    val page = remember(window, session, key, caller, expected?.sourceVersion, expected?.source) {
        DesktopOriginalAuMusicPage(key, session, window, caller, { latestActive }, expected)
    }
    val context = remember(window, key, caller) {
        DesktopOriginalPlayerSettingsContext(window.root.environment.pluginContext,
            { caller.isActive && window.owns() }, { action ->
                if (!page.isCurrentUi()) false else window.root.entry.gate.commit {
                    if (!page.isCurrentUi()) throw CancellationException("AU settings page retired")
                    action()
                }
            })
    }
    val storage = remember(context) { DesktopOriginalMusicStorageBinding(context) }
    val external = remember(page, context) { DesktopOriginalExternalPlaylistBinding(context, session.audio, page::externalPlaylistRequest) }
    val reducedMotion = rememberDesktopDynamicReduceMotion()
    val latestReducedMotion by rememberUpdatedState(reducedMotion)
    val reduceMotion = remember(window, key, caller) {
        snapshotFlow { latestReducedMotion }.stateIn(pageScope, SharingStarted.Eagerly, reducedMotion)
    }
    val imageContext = LocalPlatformContext.current
    val imageLoader = LocalDesktopApplicationImageLoader.current.imageLoader
    val latestRootVolume by rememberUpdatedState(updateRootVolume)
    val volume = remember(session, caller) {
        session.player.state.map { (it.volume / 100.0).toFloat().coerceIn(0f, 1f) }
            .stateIn(pageScope, SharingStarted.Eagerly, (session.player.state.value.volume / 100.0).toFloat().coerceIn(0f, 1f))
    }
    val music = remember(page, context, storage, external, reduceMotion, background, volume, imageContext, imageLoader) {
        DesktopOriginalAuMusicUiBinding(page, context, window.settings.homeSettings,
            reduceMotion, background, volume, storage, external, imageContext, imageLoader, { latestRootVolume(it) })
    }
    CompositionLocalProvider(LocalDesktopOriginalMusicUiPlatform provides music,
        LocalDesktopOriginalPlayerSettingsContext provides context) {
        MusicDetailScreen(sid = key.sid, onBack = { page.runUi(onBack) }, viewModel = page,
            state = projectNativeMusicState(source, audioState, native,
                expected != null && session.player.ownsSourceSnapshot(expected)))
    }
}
