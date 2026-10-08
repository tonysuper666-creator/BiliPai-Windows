package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.subtitle.*
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.ownedSource
import com.bilipai.desktop.player.tryAdmit
import com.bilipai.desktop.player.PlaybackMode
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.applyLegacyPreferenceChanges
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal data class ListenAudioState(
    val queue: List<PlaylistItem> = emptyList(),
    val currentIndex: Int = -1,
    val recent: List<PlaylistItem> = emptyList(),
    val favorites: List<PlaylistItem> = emptyList(),
    val loading: Boolean = false,
    val active: Boolean = false,
    val error: String? = null,
    val lyrics: LyricDocument? = null,
    val lyricsLoading: Boolean = false,
    val lyricsError: String? = null,
    val candidates: List<LyricCandidate> = emptyList(),
    val subtitles: List<SubtitleTrackMeta> = emptyList(),
    val primarySubtitleKey: String? = null,
    val secondarySubtitleKey: String? = null,
    val sleepRemainingMs: Long? = null,
    val sleepAfterTrack: Boolean = false,
    val songInfo: com.android.purebilibili.data.model.response.SongInfoData? = null,
) { val current: PlaylistItem? get() = queue.getOrNull(currentIndex) }

/** Retained by the application window, not the browsing screen, so navigation does not end audio or its queue. */
internal class ListenAudioSession(
    private val repository: DesktopRepository,
    community: DesktopCommunityRepository,
    val player: MpvPlayer,
    initialPreferences: PlayerPreferences = PlayerPreferences(),
    private val onAcquirePlayback: () -> Unit = {},
    private val store: ListenAudioStore = ListenAudioStore(),
    playbackDataSource: ListenPlaybackDataSource? = null,
    publication: com.bilipai.desktop.player.DesktopPlaybackPublication? = null,
) : AutoCloseable {
    val audio = DesktopAudioRepository(repository, community)
    private val playback: ListenPlaybackDataSource = playbackDataSource ?: audio
    private val publication = publication ?: if (playbackDataSource == null)
        com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository, allowPrimaryAccountSource = true)
        else error("Injected listen transport requires explicit local-source publication admission")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val sessionEpoch = repository.sessionEpoch
    private val saved = store.read()
    private val mutableState = MutableStateFlow(ListenAudioState(saved.queue, saved.currentIndex, saved.recent, saved.favorites))
    val state: StateFlow<ListenAudioState> = mutableState.asStateFlow()
    private var preferences = initialPreferences.normalized()
    private var lastWindowPreferences = preferences
    private var playJob: Job? = null
    private var lyricsJob: Job? = null
    private var savingJob: Job? = null
    private var lyricsOffsetSavingJob: Job? = null
    private var playGeneration = 0L
    /** Navigation-entry lease only; queue/items/native state remain the original session's. */
    private data class QueueOwnership(val owner: Any, val generation: Long, val nativeBaseline: Long)
    private var queueOwnership: QueueOwnership? = null
    private var ownedSourceVersion: Long? = null
    /** Actual ownership, including stopped/closed native players, without exposing a source or its credentials. */
    internal val ownedPlaybackSourceVersion: Long? get() = if (!sessionIsCurrent()) null else ownedSourceVersion?.takeIf {
        player.currentSourceSnapshot()?.sourceVersion == it
    }
    private var lyricsGeneration = 0L
    private var sleepDeadlineNanos: Long? = null
    private var resumedPositionSeconds = saved.positionSeconds
    private var shuffle = ShuffleProgress()
    private var musicLyrics: LyricDocument? = null
    private var subtitleLyrics: LyricDocument? = null
    private var closed = false

    private fun sessionIsCurrent() = !closed && scope.isActive && repository.sessionEpoch == sessionEpoch

    init {
        if (sessionIsCurrent()) player.initializePreferencesBeforeFirstSource(preferences.copy(audioOnly = true))
        scope.launch {
            repository.sessionEpochFlow.first { it != sessionEpoch }
            close()
        }
        scope.launch {
            player.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                if (ended && ownsSource() && mutableState.value.active && !mutableState.value.loading) {
                    when {
                        mutableState.value.sleepAfterTrack -> { cancelSleepTimer(); pause() }
                        preferences.playbackMode == PlaybackMode.STOP_AFTER_CURRENT -> pause()
                        preferences.playbackMode == PlaybackMode.REPEAT_ONE -> replayOwnedSource()
                        else -> next()
                    }
                }
            }
        }
        scope.launch {
            var lastCheckpoint = 0L
            while (isActive) {
                delay(500)
                sleepDeadlineNanos?.let { deadline ->
                    val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
                    mutableState.update { it.copy(sleepRemainingMs = remaining) }
                    if (remaining == 0L) { cancelSleepTimer(); pause() }
                }
                val now = System.nanoTime()
                if (mutableState.value.active && !mutableState.value.loading && now - lastCheckpoint > 5_000_000_000L) {
                    persist(); lastCheckpoint = now
                }
            }
        }
    }

    /** Window preference deltas preserve newer source-local speed/audio/loop choices. */
    fun updatePreferences(next: PlayerPreferences) {
        if (!sessionIsCurrent()) return
        val normalized = next.normalized()
        val changes = com.bilipai.desktop.player.DesktopLegacyPlaybackPreferenceChanges.between(lastWindowPreferences, normalized)
        lastWindowPreferences = normalized
        preferences = changes.mergeInto(preferences, normalized)
        player.applyLegacyPreferenceChanges(changes, preferences,
            listenOnly = true, sleepAfterTrack = mutableState.value.sleepAfterTrack)
    }

    fun onOriginalHardwareDecodeChanged(enabled: Boolean) {
        if (!sessionIsCurrent()) return
        lastWindowPreferences = lastWindowPreferences.copy(hardwareDecodeEnabled = enabled)
        preferences = preferences.copy(hardwareDecodeEnabled = enabled)
        player.setHardwareDecodingEnabled(enabled)
    }

    fun play(items: List<PlaylistItem>, index: Int = 0) = playStartingAt(items, index, 0.0)

    internal fun playStartingAt(items: List<PlaylistItem>, index: Int = 0, positionSeconds: Double = 0.0) =
        startQueue(items, index, positionSeconds, null)

    /** Same native actor as ordinary Listen playback; no fabricated parallel session. */
    internal fun playQueueForOwner(owner: Any, items: List<PlaylistItem>, index: Int = 0,
        positionSeconds: Double? = null): Boolean {
        startQueue(items, index, positionSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, owner)
        return ownsQueue(owner)
    }

    private fun startQueue(items: List<PlaylistItem>, index: Int, positionSeconds: Double, owner: Any?) {
        if (!sessionIsCurrent()) return
        val clicked = items.getOrNull(index)?.bvid ?: return
        val queue = normalizeListenQueue(items)
        if (queue.isEmpty()) return
        val selected = queue.indexOfFirst { it.bvid == clicked }.coerceAtLeast(0)
        queueOwnership = owner?.let { QueueOwnership(it, playGeneration, player.currentSourceVersion) }
        shuffle = ShuffleProgress(history = listOf(selected), historyIndex = 0, cyclePlayed = setOf(selected))
        mutableState.update { it.copy(queue = queue, currentIndex = selected) }
        playAt(selected, positionSeconds)
    }

    fun playAt(index: Int, positionSeconds: Double = 0.0) {
        if (!sessionIsCurrent()) return
        val item = mutableState.value.queue.getOrNull(index) ?: return
        val startPosition = positionSeconds.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        resumedPositionSeconds = startPosition
        onAcquirePlayback()
        if (!sessionIsCurrent()) return
        playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
        val generation = playGeneration
        player.stop()
        val pendingSourceVersion = player.currentSourceVersion
        queueOwnership = queueOwnership?.copy(generation = generation, nativeBaseline = pendingSourceVersion)
        ownedSourceVersion = null
        musicLyrics = null; subtitleLyrics = null
        mutableState.update { it.copy(currentIndex = index, loading = true, active = true, error = null, lyrics = null, songInfo = null,
            lyricsLoading = false, lyricsError = null, candidates = emptyList(), subtitles = emptyList(), primarySubtitleKey = null, secondarySubtitleKey = null) }
        persist()
        playJob = scope.launch {
            try {
                val prepared = playback.prepare(item)
                currentCoroutineContext().ensureActive()
                if (generation != playGeneration || !sessionIsCurrent()) return@launch
                if (player.currentSourceVersion != pendingSourceVersion || player.currentSourceSnapshot() != null) {
                    mutableState.update { it.copy(loading = false, active = false, error = "当前播放已切换，请重新加载音频。") }
                    return@launch
                }
                val resolvedIndex = mutableState.value.queue.indexOfFirst { it.bvid == item.bvid }
                if (resolvedIndex < 0) return@launch
                // A new source explicitly initializes this separate audio actor.
                preferences = preferences.copy(speed = preferences.preferredSpeed)
                player.applyPreferences(preferences.copy(audioOnly = true))
                player.setLoop(preferences.playbackMode == PlaybackMode.REPEAT_ONE && !mutableState.value.sleepAfterTrack)
                if (!sessionIsCurrent()) return@launch
                val callerJob = currentCoroutineContext()[Job]
                val retained = publication.ownedSource(prepared.source.copy(startPositionSeconds = startPosition),
                    { callerJob?.isCancelled != true && generation == playGeneration && sessionIsCurrent() })
                ownedSourceVersion = publication.admit(retained, { callerJob?.isActive == true && generation == playGeneration &&
                    sessionIsCurrent() && player.currentSourceVersion == pendingSourceVersion && player.currentSourceSnapshot() == null }) {
                    player.loadVersioned(retained)
                }
                val current = mutableState.value
                val queue = current.queue.toMutableList().also { it[resolvedIndex] = prepared.item }
                mutableState.update { it.copy(queue = queue, currentIndex = resolvedIndex, loading = false, songInfo = prepared.songInfo,
                    recent = (listOf(prepared.item) + it.recent.filter { recent -> recent.bvid != prepared.item.bvid }).take(300)) }
                persist()
                loadLyrics(prepared)
            } catch (failure: Exception) {
                if (failure is CancellationException) {
                    if (generation == playGeneration && sessionIsCurrent()) mutableState.update { it.copy(loading = false, active = false) }
                    throw failure
                }
                if (generation == playGeneration && sessionIsCurrent()) mutableState.update { it.copy(loading = false, active = false, error = failure.message ?: "音频加载失败。") }
            }
        }
    }

    /** SMTC controls one already accepted source. Do not cancel the successful
     * request generation on pause: its retained publication owns native commands.
     * Acquisition/checkpoint work remains outside Store and native monitors.
     */
    internal fun setSystemMediaPaused(expected: com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot,
        paused: Boolean, stillOwned: () -> Boolean): Boolean {
        if (!stillOwned() || !sessionIsCurrent() || ownedPlaybackSourceVersion != expected.sourceVersion ||
            !player.ownsSourceSnapshot(expected)) return false
        if (!paused) onAcquirePlayback()
        val generation = playGeneration
        var changed = false
        val admitted = publication.tryAdmit(expected.source,
            { stillOwned() && sessionIsCurrent() && generation == playGeneration && ownedPlaybackSourceVersion == expected.sourceVersion }) {
            player.admitSourceSnapshot(expected) {
                if (!stillOwned() || !sessionIsCurrent() || generation != playGeneration || ownedPlaybackSourceVersion != expected.sourceVersion)
                    return@admitSourceSnapshot
                if (!paused && player.state.value.ended) {
                    if (!replayOwnedSource()) return@admitSourceSnapshot
                } else player.setPaused(paused)
                mutableState.update { it.copy(active = !paused, loading = false) }
                changed = true
            } && changed
        }
        if (admitted && changed) persist()
        return admitted && changed
    }

    fun togglePause() {
        if (!sessionIsCurrent()) return
        val current = mutableState.value
        if (current.current == null) return
        if (!current.loading && (!ownsSource() || player.state.value.durationSeconds <= 0)) playAt(current.currentIndex, resumedPositionSeconds)
        else if (current.active && !player.state.value.paused) pause()
        else {
            val expectedSource = ownedPlaybackSourceVersion ?: return
            onAcquirePlayback()
            if (!sessionIsCurrent() || ownedPlaybackSourceVersion != expectedSource) return
            mutableState.update { it.copy(active = true) }
            if (player.state.value.ended) {
                val source = player.currentSourceSnapshot()?.source ?: return
                if (!publication.isCurrent(source)) return
                replayOwnedSource()
            } else player.setPaused(false)
        }
    }

    private fun replayOwnedSource(): Boolean {
        val expectedSource = ownedPlaybackSourceVersion ?: return false
        val source = player.currentSourceSnapshot()?.takeIf { it.sourceVersion == expectedSource }?.source ?: return false
        if (!publication.isCurrent(source)) return false
        val generation = playGeneration
        val owned = { sessionIsCurrent() && generation == playGeneration && ownedPlaybackSourceVersion == expectedSource }
        val retained = publication.ownedSource(source, owned)
        return publication.tryAdmit(retained, owned) { player.replayAuthorized(expectedSource, retained) }
    }

    fun pause() {
        if (!sessionIsCurrent()) return
        val loadedOwner = queueOwnership?.takeIf { ownedPlaybackSourceVersion != null }
        playJob?.cancel(); playGeneration++
        queueOwnership = loadedOwner?.copy(generation = playGeneration)
        mutableState.update { it.copy(active = false, loading = false) }
        if (ownsSource()) player.setPaused(true)
        persist()
    }

    fun next() {
        if (!sessionIsCurrent()) return
        val current = mutableState.value
        val index = if (preferences.playbackMode == PlaybackMode.SHUFFLE) {
            val next = advanceShuffleProgress(current.queue.size, current.currentIndex, shuffle) { it.random() }
            shuffle = next.progress; next.nextIndex
        } else resolveLinearPlayNextIndex(current.queue.size, current.currentIndex, preferences.playbackMode == PlaybackMode.REPEAT_ALL)
        if (index != null) playAt(index) else pause()
    }

    fun previous() {
        if (!sessionIsCurrent()) return
        val current = mutableState.value
        val index = if (preferences.playbackMode == PlaybackMode.SHUFFLE && shuffle.historyIndex > 0) {
            shuffle = shuffle.copy(historyIndex = shuffle.historyIndex - 1)
            shuffle.history.getOrNull(shuffle.historyIndex)
        } else resolveLinearPlayPreviousIndex(current.queue.size, current.currentIndex, preferences.playbackMode in setOf(PlaybackMode.REPEAT_ALL, PlaybackMode.SHUFFLE))
        if (index != null) playAt(index)
    }

    internal fun ownsQueue(owner: Any): Boolean {
        val lease = queueOwnership ?: return false
        if (lease.owner !== owner || lease.generation != playGeneration || !sessionIsCurrent()) return false
        if (ownedSourceVersion != null) return ownedPlaybackSourceVersion != null
        val current = mutableState.value
        return (current.loading || current.error != null) && player.currentSourceVersion == lease.nativeBaseline &&
            player.currentSourceSnapshot() == null
    }

    /** Original PlaylistManager addAllToCurrentPlaylist BVID append policy. */
    internal fun appendQueueForOwner(owner: Any, items: List<PlaylistItem>): Boolean {
        if (!ownsQueue(owner)) return false
        val existing = mutableState.value.queue.mapTo(mutableSetOf()) { it.bvid }
        val appended = items.filter { it.bvid !in existing }
        if (appended.isNotEmpty()) changeQueue(normalizeListenQueue(mutableState.value.queue + appended))
        return ownsQueue(owner)
    }

    internal fun stopQueueForOwner(owner: Any): Boolean {
        if (!ownsQueue(owner)) return false
        playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
        stopOwnedSource()
        mutableState.update { it.copy(active = false, loading = false, lyricsLoading = false) }
        persist()
        return true
    }

    fun enqueue(items: List<PlaylistItem>) { if (sessionIsCurrent()) changeQueue(normalizeListenQueue(mutableState.value.queue + items)) }

    fun moveQueueItem(index: Int, target: Int) {
        if (!sessionIsCurrent()) return
        val queue = mutableState.value.queue.toMutableList()
        if (index !in queue.indices || target !in queue.indices) return
        queue.add(target, queue.removeAt(index)); changeQueue(queue)
    }

    fun removeQueueItem(index: Int) {
        if (!sessionIsCurrent()) return
        val current = mutableState.value
        if (index !in current.queue.indices) return
        val removingCurrent = index == current.currentIndex
        changeQueue(current.queue.filterIndexed { itemIndex, _ -> itemIndex != index })
        if (removingCurrent) {
            if (current.active && mutableState.value.queue.isNotEmpty()) playAt(index.coerceAtMost(mutableState.value.queue.lastIndex))
            else {
                playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
                stopOwnedSource(); resumedPositionSeconds = 0.0
                mutableState.update { it.copy(active = false, loading = false, lyricsLoading = false, lyrics = null) }; persist()
            }
        }
    }

    fun clearQueue() {
        if (!sessionIsCurrent()) return
        playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
        stopOwnedSource(); resumedPositionSeconds = 0.0; shuffle = ShuffleProgress()
        mutableState.update { it.copy(queue = emptyList(), currentIndex = -1, active = false, loading = false,
            lyrics = null, lyricsLoading = false, lyricsError = null, candidates = emptyList(), subtitles = emptyList(), error = null) }; persist()
    }

    private fun changeQueue(queue: List<PlaylistItem>) {
        val before = mutableState.value
        val index = queue.indexOfFirst { it.bvid == before.current?.bvid }.let { if (it >= 0) it else if (queue.isEmpty()) -1 else before.currentIndex.coerceIn(queue.indices) }
        shuffle = reconcileShuffleProgressForPlaylistUpdate(before.queue, queue, index, shuffle)
        mutableState.update { it.copy(queue = queue, currentIndex = index) }; persist()
    }

    fun toggleFavorite(item: PlaylistItem) {
        if (!sessionIsCurrent()) return
        mutableState.update { it.copy(favorites = if (it.favorites.any { favorite -> favorite.bvid == item.bvid })
            it.favorites.filter { favorite -> favorite.bvid != item.bvid } else (listOf(item) + it.favorites).take(5_000)) }; persist()
    }

    fun clearRecent() { if (!sessionIsCurrent()) return; mutableState.update { it.copy(recent = emptyList()) }; persist() }

    fun setSleepMinutes(minutes: Int) {
        if (!sessionIsCurrent()) return
        require(minutes in 1..1_440)
        sleepDeadlineNanos = System.nanoTime() + minutes * 60_000_000_000L
        mutableState.update { it.copy(sleepRemainingMs = minutes * 60_000L, sleepAfterTrack = false) }
        player.setLoop(preferences.playbackMode == PlaybackMode.REPEAT_ONE)
    }

    fun setSleepAfterCurrentTrack() {
        if (!sessionIsCurrent()) return
        sleepDeadlineNanos = null
        mutableState.update { it.copy(sleepRemainingMs = null, sleepAfterTrack = true) }
        player.setLoop(false)
    }

    fun cancelSleepTimer() {
        if (!sessionIsCurrent()) return
        sleepDeadlineNanos = null
        mutableState.update { it.copy(sleepRemainingMs = null, sleepAfterTrack = false) }
        player.setLoop(preferences.playbackMode == PlaybackMode.REPEAT_ONE)
    }

    private fun loadLyrics(prepared: PreparedListenAudio, forceRefresh: Boolean = false) {
        if (!sessionIsCurrent()) return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val external = async { playback.lyrics.load(cacheKey(prepared.item), LyricQuery(prepared.item.title, prepared.item.owner, prepared.item.duration * 1_000L), prepared.songLyrics, forceRefresh) }
                try {
                    val tracks = withTimeout(20_000) { playback.subtitleTracks(prepared.item) }
                    val language = resolveDefaultSubtitleLanguages(tracks)
                    val primary = tracks.firstOrNull { it.lan == language.primaryLanguage }
                    if (generation != lyricsGeneration || !sessionIsCurrent()) return@launch
                    mutableState.update { it.copy(subtitles = tracks, primarySubtitleKey = primary?.trackKey, secondarySubtitleKey = null) }
                    val cues = primary?.let { withTimeout(20_000) { playback.subtitleCues(it) } }.orEmpty()
                    subtitleLyrics = BiliSubtitleLyricsPolicy.convertSubtitlesToLyricDocument(cues,
                        isAiGenerated = primary?.let(::isLikelyAiSubtitleTrack) == true, languageLabel = primary?.lanDoc)
                } catch (failure: Exception) { if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure }
                val result = external.await()
                if (generation != lyricsGeneration || !sessionIsCurrent()) return@launch
                musicLyrics = (result as? LyricsLoadResult.Found)?.document
                publishLyrics()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration && sessionIsCurrent()) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message ?: "歌词加载失败。") }
            }
        }
    }

    fun selectSubtitles(primaryKey: String?, secondaryKey: String?) {
        if (!sessionIsCurrent()) return
        val current = mutableState.value
        val currentItem = current.current ?: return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(primarySubtitleKey = primaryKey, secondarySubtitleKey = secondaryKey, lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val primary = current.subtitles.firstOrNull { it.trackKey == primaryKey }
                val secondary = current.subtitles.firstOrNull { it.trackKey == secondaryKey && it.trackKey != primaryKey }
                val primaryCues = primary?.let { playback.subtitleCues(it) }.orEmpty()
                val secondaryCues = secondary?.let { playback.subtitleCues(it) }.orEmpty()
                if (generation != lyricsGeneration || !sessionIsCurrent()) return@launch
                subtitleLyrics = BiliSubtitleLyricsPolicy.convertSubtitlesToLyricDocument(primaryCues, secondaryCues,
                    primary?.let(::isLikelyAiSubtitleTrack) == true, primary?.lanDoc)
                musicLyrics = null
                publishLyrics()
                mutableState.value.lyrics?.let { playback.lyrics.save(cacheKey(currentItem), it) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration && sessionIsCurrent()) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message ?: "字幕歌词加载失败。") }
            }
        }
    }

    fun searchLyrics() {
        if (!sessionIsCurrent()) return
        val current = mutableState.value.current ?: return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val candidates = playback.lyrics.search(LyricQuery(current.title, current.owner, current.duration * 1_000L))
                if (generation == lyricsGeneration && sessionIsCurrent()) mutableState.update { it.copy(candidates = candidates, lyricsLoading = false,
                    lyricsError = if (candidates.isEmpty()) "未找到匹配歌词。" else null) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration && sessionIsCurrent()) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message) }
            }
        }
    }

    fun selectLyrics(candidate: LyricCandidate) {
        if (!sessionIsCurrent()) return
        val current = mutableState.value.current ?: return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val result = playback.lyrics.select(cacheKey(current), candidate)
                if (generation != lyricsGeneration || !sessionIsCurrent()) return@launch
                if (result is LyricsLoadResult.Found) { musicLyrics = result.document; publishLyrics() }
                else mutableState.update { it.copy(lyricsLoading = false, lyricsError = "所选歌词未能加载。") }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration && sessionIsCurrent()) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message) }
            }
        }
    }

    fun importLyrics(text: String) {
        if (!sessionIsCurrent()) return
        require(text.length <= 2 * 1024 * 1024) { "歌词文件过大。" }
        val current = mutableState.value.current ?: return
        val document = parseSplLyrics(text, source = LyricSource.MANUAL).copy(manuallySelected = true)
        require(document.lines.isNotEmpty()) { "文件没有有效的带时间轴歌词。" }
        lyricsJob?.cancel(); lyricsGeneration++
        musicLyrics = document; subtitleLyrics = null; publishLyrics()
        lyricsOffsetSavingJob?.cancel()
        lyricsOffsetSavingJob = scope.launch { delay(200); playback.lyrics.save(cacheKey(current), document) }
    }

    fun setLyricsOffset(offsetMs: Long) {
        if (!sessionIsCurrent()) return
        val current = mutableState.value.current ?: return
        val document = mutableState.value.lyrics?.withOffset(offsetMs) ?: return
        mutableState.update { it.copy(lyrics = document) }
        lyricsOffsetSavingJob?.cancel()
        lyricsOffsetSavingJob = scope.launch { delay(200); playback.lyrics.save(cacheKey(current), document) }
    }

    private fun publishLyrics() {
        val document = BiliSubtitleLyricsPolicy.resolveEffectiveLyricsWithAlignment(musicLyrics, subtitleLyrics)
        mutableState.update { it.copy(lyrics = document, lyricsLoading = false, lyricsError = if (document == null) "此内容没有可用字幕或匹配歌词。" else null) }
    }

    private fun persist() {
        savingJob?.cancel()
        val current = mutableState.value
        val position = checkpointPosition()
        val snapshot = ListenAudioSaved(current.queue, current.currentIndex, current.recent, current.favorites, position)
        savingJob = scope.launch {
            delay(200)
            try { withContext(Dispatchers.IO) { store.save(snapshot) } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(error = "听视频状态保存失败：${failure.message}") }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val current = mutableState.value
        try {
            store.closeAndSave(ListenAudioSaved(current.queue, current.currentIndex, current.recent, current.favorites,
                checkpointPosition()))
        } catch (failure: Exception) { mutableState.update { it.copy(error = "听视频状态保存失败：${failure.message}") } }
        finally {
            scope.cancel()
            stopOwnedSource()
        }
    }

    /** Root calls this from outside the audio scope before replacing managed files. */
    internal suspend fun shutdownForRestore(): Unit = withContext(NonCancellable) {
        close()
        scope.coroutineContext[Job]?.join()
    }

    private fun stopOwnedSource() {
        queueOwnership = null
        ownedSourceVersion?.let(player::stopIfSourceVersion)
        ownedSourceVersion = null
    }

    private fun ownsSource(): Boolean = ownedSourceVersion != null && ownedSourceVersion == player.currentSourceVersion
    private fun checkpointPosition(): Double {
        if (!ownsSource()) return resumedPositionSeconds
        val native = player.state.value
        return (if (native.ended) 0.0 else if (native.durationSeconds > 0) native.positionSeconds else resumedPositionSeconds)
            .also { resumedPositionSeconds = it }
    }
}

internal fun normalizeListenQueue(items: List<PlaylistItem>): List<PlaylistItem> = items.filter { it.bvid.isNotBlank() }
    .distinctBy { it.bvid }.take(5_000)

private fun cacheKey(item: PlaylistItem) = if (item.bvid.startsWith("au", true)) "au:${item.bvid.removePrefix("au")}" else "video:${item.bvid}:${item.cid}"

@Serializable
internal data class ListenAudioSaved(val queue: List<PlaylistItem> = emptyList(), val currentIndex: Int = -1,
    val recent: List<PlaylistItem> = emptyList(), val favorites: List<PlaylistItem> = emptyList(), val positionSeconds: Double = 0.0)

internal class ListenAudioStore(private val file: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "BiliPaiWindows", "listen-state.json")) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var writesClosed = false
    @Synchronized fun read(): ListenAudioSaved = runCatching {
        require(Files.isRegularFile(file) && Files.size(file) <= 8 * 1024 * 1024)
        json.decodeFromString<ListenAudioSaved>(Files.readString(file)).normalized()
    }.getOrDefault(ListenAudioSaved())
    @Synchronized fun save(snapshot: ListenAudioSaved) {
        check(!writesClosed) { "听视频会话已关闭，不能写入旧状态实例" }
        writeAtomic(snapshot)
    }

    /** Drain an accepted write, retire this facade, then persist its final snapshot under one monitor.
     * A cancelled IO task already waiting for the monitor must recheck retirement after acquiring it.
     * Ordinary close intentionally leaves independently constructed stores for the same file usable.
     */
    @Synchronized fun closeAndSave(snapshot: ListenAudioSaved) {
        if (writesClosed) return
        writesClosed = true
        writeAtomic(snapshot)
    }

    private fun writeAtomic(snapshot: ListenAudioSaved) {
        val target = file.toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "listen-state-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(snapshot.normalized()))
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun ListenAudioSaved.normalized(): ListenAudioSaved {
        val normalizedQueue = normalizeListenQueue(queue)
        return copy(queue = normalizedQueue, currentIndex = if (normalizedQueue.isEmpty()) -1 else currentIndex.coerceIn(normalizedQueue.indices),
            recent = normalizeListenQueue(recent).take(300), favorites = normalizeListenQueue(favorites),
            positionSeconds = if (positionSeconds.isFinite()) positionSeconds.coerceIn(0.0, 604_800.0) else 0.0)
    }
}
