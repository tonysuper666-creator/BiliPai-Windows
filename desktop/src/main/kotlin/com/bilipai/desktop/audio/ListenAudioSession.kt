package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.subtitle.*
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackMode
import com.bilipai.desktop.player.PlayerPreferences
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
) { val current: PlaylistItem? get() = queue.getOrNull(currentIndex) }

/** Retained by the application window, not the browsing screen, so navigation does not end audio or its queue. */
internal class ListenAudioSession(
    repository: DesktopRepository,
    community: DesktopCommunityRepository,
    val player: MpvPlayer,
    initialPreferences: PlayerPreferences = PlayerPreferences(),
    private val onAcquirePlayback: () -> Unit = {},
    private val store: ListenAudioStore = ListenAudioStore(),
) : AutoCloseable {
    val audio = DesktopAudioRepository(repository, community)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val saved = store.read()
    private val mutableState = MutableStateFlow(ListenAudioState(saved.queue, saved.currentIndex, saved.recent, saved.favorites))
    val state: StateFlow<ListenAudioState> = mutableState.asStateFlow()
    private var preferences = initialPreferences.normalized()
    private var playJob: Job? = null
    private var lyricsJob: Job? = null
    private var savingJob: Job? = null
    private var lyricsOffsetSavingJob: Job? = null
    private var playGeneration = 0L
    private var ownedSourceVersion: Long? = null
    private var lyricsGeneration = 0L
    private var sleepDeadlineNanos: Long? = null
    private var resumedPositionSeconds = saved.positionSeconds
    private var shuffle = ShuffleProgress()
    private var musicLyrics: LyricDocument? = null
    private var subtitleLyrics: LyricDocument? = null
    private var closed = false

    init {
        scope.launch {
            player.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                if (ended && ownsSource() && mutableState.value.active && !mutableState.value.loading) {
                    when {
                        mutableState.value.sleepAfterTrack -> { cancelSleepTimer(); pause() }
                        preferences.playbackMode == PlaybackMode.STOP_AFTER_CURRENT -> pause()
                        preferences.playbackMode == PlaybackMode.REPEAT_ONE -> player.replay()
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

    fun updatePreferences(next: PlayerPreferences) {
        if (closed) return
        preferences = next.normalized()
        player.applyPreferences(preferences.copy(audioOnly = true))
        player.setLoop(preferences.playbackMode == PlaybackMode.REPEAT_ONE && !mutableState.value.sleepAfterTrack)
    }

    fun play(items: List<PlaylistItem>, index: Int = 0) {
        val clicked = items.getOrNull(index)?.bvid ?: return
        val queue = normalizeListenQueue(items)
        if (queue.isEmpty()) return
        val selected = queue.indexOfFirst { it.bvid == clicked }.coerceAtLeast(0)
        shuffle = ShuffleProgress(history = listOf(selected), historyIndex = 0, cyclePlayed = setOf(selected))
        mutableState.update { it.copy(queue = queue, currentIndex = selected) }
        playAt(selected)
    }

    fun playAt(index: Int, positionSeconds: Double = 0.0) {
        if (closed) return
        val item = mutableState.value.queue.getOrNull(index) ?: return
        val startPosition = positionSeconds.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        resumedPositionSeconds = startPosition
        onAcquirePlayback()
        playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
        val generation = playGeneration
        player.stop()
        ownedSourceVersion = null
        musicLyrics = null; subtitleLyrics = null
        mutableState.update { it.copy(currentIndex = index, loading = true, active = true, error = null, lyrics = null,
            lyricsLoading = false, lyricsError = null, candidates = emptyList(), subtitles = emptyList(), primarySubtitleKey = null, secondarySubtitleKey = null) }
        playJob = scope.launch {
            try {
                val prepared = audio.prepare(item)
                if (generation != playGeneration || closed) return@launch
                val resolvedIndex = mutableState.value.queue.indexOfFirst { it.bvid == item.bvid }
                if (resolvedIndex < 0) return@launch
                preferences = preferences.copy(speed = preferences.preferredSpeed)
                updatePreferences(preferences)
                ownedSourceVersion = player.loadVersioned(prepared.source.copy(startPositionSeconds = startPosition))
                val current = mutableState.value
                val queue = current.queue.toMutableList().also { it[resolvedIndex] = prepared.item }
                mutableState.update { it.copy(queue = queue, currentIndex = resolvedIndex, loading = false,
                    recent = (listOf(prepared.item) + it.recent.filter { recent -> recent.bvid != prepared.item.bvid }).take(300)) }
                persist()
                loadLyrics(prepared)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == playGeneration) mutableState.update { it.copy(loading = false, active = false, error = failure.message ?: "音频加载失败。") }
            }
        }
    }

    fun togglePause() {
        if (closed) return
        val current = mutableState.value
        if (current.current == null) return
        if (!current.loading && (!ownsSource() || player.state.value.durationSeconds <= 0)) playAt(current.currentIndex, resumedPositionSeconds)
        else if (current.active && !player.state.value.paused) pause()
        else { onAcquirePlayback(); mutableState.update { it.copy(active = true) }; if (player.state.value.ended) player.replay() else player.setPaused(false) }
    }

    fun pause() {
        if (closed) return
        playJob?.cancel(); playGeneration++
        mutableState.update { it.copy(active = false, loading = false) }
        if (ownsSource()) player.setPaused(true)
        persist()
    }

    fun next() {
        val current = mutableState.value
        val index = if (preferences.playbackMode == PlaybackMode.SHUFFLE) {
            val next = advanceShuffleProgress(current.queue.size, current.currentIndex, shuffle) { it.random() }
            shuffle = next.progress; next.nextIndex
        } else resolveLinearPlayNextIndex(current.queue.size, current.currentIndex, preferences.playbackMode == PlaybackMode.REPEAT_ALL)
        if (index != null) playAt(index) else pause()
    }

    fun previous() {
        val current = mutableState.value
        val index = if (preferences.playbackMode == PlaybackMode.SHUFFLE && shuffle.historyIndex > 0) {
            shuffle = shuffle.copy(historyIndex = shuffle.historyIndex - 1)
            shuffle.history.getOrNull(shuffle.historyIndex)
        } else resolveLinearPlayPreviousIndex(current.queue.size, current.currentIndex, preferences.playbackMode in setOf(PlaybackMode.REPEAT_ALL, PlaybackMode.SHUFFLE))
        if (index != null) playAt(index)
    }

    fun enqueue(items: List<PlaylistItem>) = changeQueue(normalizeListenQueue(mutableState.value.queue + items))

    fun moveQueueItem(index: Int, target: Int) {
        val queue = mutableState.value.queue.toMutableList()
        if (index !in queue.indices || target !in queue.indices) return
        queue.add(target, queue.removeAt(index)); changeQueue(queue)
    }

    fun removeQueueItem(index: Int) {
        val current = mutableState.value
        if (index !in current.queue.indices) return
        val removingCurrent = index == current.currentIndex
        changeQueue(current.queue.filterIndexed { itemIndex, _ -> itemIndex != index })
        if (removingCurrent) {
            if (current.active && mutableState.value.queue.isNotEmpty()) playAt(index.coerceAtMost(mutableState.value.queue.lastIndex))
            else {
                playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
                player.stop(); mutableState.update { it.copy(active = false, loading = false, lyricsLoading = false, lyrics = null) }
            }
        }
    }

    fun clearQueue() {
        playJob?.cancel(); lyricsJob?.cancel(); playGeneration++; lyricsGeneration++
        player.stop(); shuffle = ShuffleProgress()
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
        mutableState.update { it.copy(favorites = if (it.favorites.any { favorite -> favorite.bvid == item.bvid })
            it.favorites.filter { favorite -> favorite.bvid != item.bvid } else (listOf(item) + it.favorites).take(5_000)) }; persist()
    }

    fun clearRecent() { mutableState.update { it.copy(recent = emptyList()) }; persist() }

    fun setSleepMinutes(minutes: Int) {
        require(minutes in 1..1_440)
        sleepDeadlineNanos = System.nanoTime() + minutes * 60_000_000_000L
        mutableState.update { it.copy(sleepRemainingMs = minutes * 60_000L, sleepAfterTrack = false) }
        player.setLoop(preferences.playbackMode == PlaybackMode.REPEAT_ONE)
    }

    fun setSleepAfterCurrentTrack() {
        sleepDeadlineNanos = null
        mutableState.update { it.copy(sleepRemainingMs = null, sleepAfterTrack = true) }
        player.setLoop(false)
    }

    fun cancelSleepTimer() {
        sleepDeadlineNanos = null
        mutableState.update { it.copy(sleepRemainingMs = null, sleepAfterTrack = false) }
        player.setLoop(preferences.playbackMode == PlaybackMode.REPEAT_ONE)
    }

    private fun loadLyrics(prepared: PreparedListenAudio, forceRefresh: Boolean = false) {
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val external = async { audio.lyrics.load(cacheKey(prepared.item), LyricQuery(prepared.item.title, prepared.item.owner, prepared.item.duration * 1_000L), prepared.songLyrics, forceRefresh) }
                try {
                    val tracks = withTimeout(20_000) { audio.subtitleTracks(prepared.item) }
                    val language = resolveDefaultSubtitleLanguages(tracks)
                    val primary = tracks.firstOrNull { it.lan == language.primaryLanguage }
                    if (generation != lyricsGeneration) return@launch
                    mutableState.update { it.copy(subtitles = tracks, primarySubtitleKey = primary?.trackKey, secondarySubtitleKey = null) }
                    val cues = primary?.let { withTimeout(20_000) { audio.subtitleCues(it) } }.orEmpty()
                    subtitleLyrics = BiliSubtitleLyricsPolicy.convertSubtitlesToLyricDocument(cues,
                        isAiGenerated = primary?.let(::isLikelyAiSubtitleTrack) == true, languageLabel = primary?.lanDoc)
                } catch (failure: Exception) { if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure }
                val result = external.await()
                if (generation != lyricsGeneration) return@launch
                musicLyrics = (result as? LyricsLoadResult.Found)?.document
                publishLyrics()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message ?: "歌词加载失败。") }
            }
        }
    }

    fun selectSubtitles(primaryKey: String?, secondaryKey: String?) {
        val current = mutableState.value
        val currentItem = current.current ?: return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(primarySubtitleKey = primaryKey, secondarySubtitleKey = secondaryKey, lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val primary = current.subtitles.firstOrNull { it.trackKey == primaryKey }
                val secondary = current.subtitles.firstOrNull { it.trackKey == secondaryKey && it.trackKey != primaryKey }
                val primaryCues = primary?.let { audio.subtitleCues(it) }.orEmpty()
                val secondaryCues = secondary?.let { audio.subtitleCues(it) }.orEmpty()
                if (generation != lyricsGeneration) return@launch
                subtitleLyrics = BiliSubtitleLyricsPolicy.convertSubtitlesToLyricDocument(primaryCues, secondaryCues,
                    primary?.let(::isLikelyAiSubtitleTrack) == true, primary?.lanDoc)
                musicLyrics = null
                publishLyrics()
                mutableState.value.lyrics?.let { audio.lyrics.save(cacheKey(currentItem), it) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message ?: "字幕歌词加载失败。") }
            }
        }
    }

    fun searchLyrics() {
        val current = mutableState.value.current ?: return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val candidates = audio.lyrics.search(LyricQuery(current.title, current.owner, current.duration * 1_000L))
                if (generation == lyricsGeneration) mutableState.update { it.copy(candidates = candidates, lyricsLoading = false,
                    lyricsError = if (candidates.isEmpty()) "未找到匹配歌词。" else null) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message) }
            }
        }
    }

    fun selectLyrics(candidate: LyricCandidate) {
        val current = mutableState.value.current ?: return
        lyricsJob?.cancel(); lyricsGeneration++
        val generation = lyricsGeneration
        mutableState.update { it.copy(lyricsLoading = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val result = audio.lyrics.select(cacheKey(current), candidate)
                if (generation != lyricsGeneration) return@launch
                if (result is LyricsLoadResult.Found) { musicLyrics = result.document; publishLyrics() }
                else mutableState.update { it.copy(lyricsLoading = false, lyricsError = "所选歌词未能加载。") }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (generation == lyricsGeneration) mutableState.update { it.copy(lyricsLoading = false, lyricsError = failure.message) }
            }
        }
    }

    fun importLyrics(text: String) {
        require(text.length <= 2 * 1024 * 1024) { "歌词文件过大。" }
        val current = mutableState.value.current ?: return
        val document = parseSplLyrics(text, source = LyricSource.MANUAL).copy(manuallySelected = true)
        require(document.lines.isNotEmpty()) { "文件没有有效的带时间轴歌词。" }
        lyricsJob?.cancel(); lyricsGeneration++
        musicLyrics = document; subtitleLyrics = null; publishLyrics()
        lyricsOffsetSavingJob?.cancel()
        lyricsOffsetSavingJob = scope.launch { delay(200); audio.lyrics.save(cacheKey(current), document) }
    }

    fun setLyricsOffset(offsetMs: Long) {
        val current = mutableState.value.current ?: return
        val document = mutableState.value.lyrics?.withOffset(offsetMs) ?: return
        mutableState.update { it.copy(lyrics = document) }
        lyricsOffsetSavingJob?.cancel()
        lyricsOffsetSavingJob = scope.launch { delay(200); audio.lyrics.save(cacheKey(current), document) }
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
            store.save(ListenAudioSaved(current.queue, current.currentIndex, current.recent, current.favorites,
                checkpointPosition()))
        } catch (failure: Exception) { mutableState.update { it.copy(error = "听视频状态保存失败：${failure.message}") } }
        finally {
            scope.cancel()
            ownedSourceVersion?.let(player::stopIfSourceVersion)
            ownedSourceVersion = null
        }
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
    @Synchronized fun read(): ListenAudioSaved = runCatching {
        require(Files.isRegularFile(file) && Files.size(file) <= 8 * 1024 * 1024)
        json.decodeFromString<ListenAudioSaved>(Files.readString(file)).normalized()
    }.getOrDefault(ListenAudioSaved())
    @Synchronized fun save(snapshot: ListenAudioSaved) {
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
