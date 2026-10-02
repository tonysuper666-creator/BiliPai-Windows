package com.bilipai.desktop.ui

import com.android.purebilibili.feature.audio.lyrics.LyricsRepository
import com.android.purebilibili.feature.audio.viewmodel.DesktopOriginalMusicLyricsController
import com.android.purebilibili.feature.audio.viewmodel.MusicUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Fixed logical BV/CID subject of Root's same original Assembly. It never owns
 * a generation, player, request client, cache or coroutine actor. Quality/decoder
 * changes may retain this subject; a replaced original subject must retire it.
 * The supplied admission publishes memory only, with Store -> entry ordering.
 */
internal class DesktopOriginalMusicSourceLease(
    val bvid: String,
    val cid: Long,
    private val owns: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
) {
    fun requireOwned() {
        if (!owns()) throw CancellationException("Original music lyrics subject retired")
    }
    fun commit(action: () -> Unit) {
        requireOwned()
        var applied = false
        if (!commitIfCurrent { requireOwned(); action(); applied = true } || !applied)
            throw CancellationException("Original music lyrics publication retired")
    }
    fun updateState(state: MutableStateFlow<MusicUiState>, transform: (MusicUiState) -> MusicUiState) =
        commit { state.update(transform) }
}

/** Each original launch carries its actual Job and immutable Root subject.
 * Repository/provider/cache IO occurs outside this short publication admission.
 * The existing public transport/cache retains its original timeout semantics;
 * this does not claim cancellation of an underlying blocking OkHttp execute.
 */
internal class DesktopOriginalMusicLyricsRequest(
    private val lease: DesktopOriginalMusicSourceLease,
    private val caller: CoroutineContext,
    private val isOpen: () -> Boolean,
) {
    fun check() {
        caller.ensureActive()
        if (!isOpen()) throw CancellationException("Original lyrics owner closed")
        lease.requireOwned()
    }
    fun commit(action: () -> Unit) {
        check()
        lease.commit { check(); action() }
    }
    fun updateState(state: MutableStateFlow<MusicUiState>, transform: (MusicUiState) -> MusicUiState) =
        commit { state.update(transform) }
}

/** The BV audio UI's six consumed original MusicViewModel lyrics operations.
 * Root supplies its actual scope/subject and SAME existing LyricsRepository.
 * The child Job replaces the original ViewModel lifetime and tracks every
 * original launched job, including a cancelled job whose IO is still finishing.
 */
internal class DesktopOriginalAudioLyricsBinding(
    private val settings: DesktopOriginalPlayerSettingsContext,
    repository: LyricsRepository,
    entryScope: CoroutineScope,
    private val captureSource: (bvid: String, cid: Long) -> DesktopOriginalMusicSourceLease,
) : DesktopOriginalAudioLyricsPort {
    private val closed = AtomicBoolean()
    private val job = SupervisorJob(checkNotNull(entryScope.coroutineContext[Job]))
    private val scope = CoroutineScope(entryScope.coroutineContext + job)
    private val controller = DesktopOriginalMusicLyricsController(repository, scope) { !closed.get() }
    private val currentLease = AtomicReference<DesktopOriginalMusicSourceLease?>()
    override val uiState: StateFlow<MusicUiState> get() = controller.uiState
    private fun checkOpen() {
        if (closed.get() || !job.isActive) throw CancellationException("Original lyrics owner closed")
        settings.requireCurrent()
    }
    private fun current(): DesktopOriginalMusicSourceLease? {
        checkOpen()
        return currentLease.get()?.also { it.requireOwned() }
    }
    override fun initPlayer(context: DesktopOriginalPlayerSettingsContext) {
        checkOpen()
        require(context === settings) { "Original audio lyrics requires the same Root settings context" }
        // The original Android-only player/client/cache construction is supplied
        // by Root's existing Repository. This BV port never starts an AU player.
    }
    override fun loadLyricsForVideo(title: String, artist: String, bvid: String, cid: Long, durationMs: Long) {
        checkOpen()
        val lease = captureSource(bvid, cid)
        require(lease.bvid == bvid && lease.cid == cid) { "Original lyrics capture returned a different BV/CID" }
        lease.commit { checkOpen(); currentLease.set(lease) }
        controller.loadLyricsForVideo(lease, title, artist, bvid, cid, durationMs)
    }
    override fun adjustLyricsOffset(offsetMs: Long) { current()?.let { controller.adjustLyricsOffset(it, offsetMs) } }
    override fun retryLyrics() { current()?.let(controller::retryLyrics) }
    override fun searchLyrics(title: String) { current()?.let { controller.searchLyrics(it, title) } }
    override fun selectLyricsCandidate(index: Int) { current()?.let { controller.selectLyricsCandidate(it, index) } }
    /** Root calls outside admission before Store/window/app scope teardown. The
     * original bounded public client can finish blocking IO after cancellation.
     * False means the lifetime has not drained; Root must not claim it has. */
    suspend fun closeCancelJoin(timeoutMs: Long = 10_000L): Boolean {
        require(timeoutMs > 0)
        closed.set(true); currentLease.set(null); job.cancel()
        check(currentCoroutineContext()[Job] !== job)
        return withContext(NonCancellable) { withTimeoutOrNull(timeoutMs) { job.join(); true } ?: false }
    }
}
