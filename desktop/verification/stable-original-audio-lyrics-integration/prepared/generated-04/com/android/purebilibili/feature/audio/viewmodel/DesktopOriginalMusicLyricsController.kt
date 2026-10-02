// Generated from app/src/main/java/com/android/purebilibili/feature/audio/viewmodel/MusicViewModel.kt; do not edit.
// LF-normalized SHA-256: d9705a700bbe14722b79d9bb484d70da0afb97ae1c9df393e7a271e4a0173d0c
package com.android.purebilibili.feature.audio.viewmodel

import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.bilipai.desktop.ui.DesktopOriginalMusicSourceLease
import com.bilipai.desktop.ui.DesktopOriginalMusicLyricsRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
internal class DesktopOriginalMusicLyricsController(
    suppliedRepository: LyricsRepository,
    private val viewModelScope: CoroutineScope,
    private val isOpen: () -> Boolean,
) {
    private var lastLyricsLease: DesktopOriginalMusicSourceLease? = null
    private val _uiState = MutableStateFlow(MusicUiState())

    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private var lyricsRepository: LyricsRepository? = suppliedRepository

    private var lyricsJob: Job? = null

    private var lyricsOffsetSaveJob: Job? = null

    private var lastLyricsCacheKey: String? = null

    private var lastLyricsQuery: LyricQuery? = null

    private var lastBilibiliLyrics: String? = null

    fun adjustLyricsOffset(lease: DesktopOriginalMusicSourceLease, offsetMs: Long) {
        if (lastLyricsLease !== lease) return
        val document = _uiState.value.lyricsDocument ?: return
        val adjusted = document.withOffset(document.offsetMs + offsetMs)
        lease.updateState(_uiState, isOpen) { it.copy(lyricsDocument = adjusted) }
        val cacheKey = lastLyricsCacheKey ?: return
        if (lastLyricsLease !== lease) return
        val repository = lyricsRepository ?: return
        lyricsOffsetSaveJob?.cancel()
        lyricsOffsetSaveJob = viewModelScope.launch {
            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)
            request.check()
            repository.save(cacheKey, adjusted)
        }
    }

    fun loadLyricsForVideo(
        lease: DesktopOriginalMusicSourceLease,
        title: String,
        artist: String,
        bvid: String,
        cid: Long,
        durationMs: Long
    ) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)
            request.check()
            request.updateState(_uiState) {
                it.copy(lyricsDocument = null, lyricsError = null, isLyricsSearching = true)
            }
            loadLyrics(
                request = request,
                cacheKey = MusicPlaybackSource.VideoAudio(bvid, cid, title).stableId,
                query = LyricQuery(title, artist, durationMs),
                bilibiliLyrics = null
            )
        }
    }

    fun retryLyrics(lease: DesktopOriginalMusicSourceLease) {
        val cacheKey = lastLyricsCacheKey ?: return
        if (lastLyricsLease !== lease) return
        val query = lastLyricsQuery ?: return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)
            request.check()
            request.updateState(_uiState) {
                it.copy(lyricsDocument = null, lyricCandidates = emptyList(), isLyricsSearching = true)
            }
            loadLyrics(request, cacheKey, query, lastBilibiliLyrics, forceRefresh = true)
            request.updateState(_uiState) { it.copy(isLyricsSearching = false) }
        }
    }

    fun searchLyrics(lease: DesktopOriginalMusicSourceLease, title: String) {
        val cacheKey = lastLyricsCacheKey ?: return
        if (lastLyricsLease !== lease) return
        val previousQuery = lastLyricsQuery ?: return
        val repository = lyricsRepository ?: return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)
            request.check()
            request.updateState(_uiState) { it.copy(isLyricsSearching = true, lyricCandidates = emptyList()) }
            val candidates = repository.search(previousQuery.copy(title = title.ifBlank { previousQuery.title }))
            request.commit { lastLyricsCacheKey = cacheKey }
            request.updateState(_uiState) { it.copy(isLyricsSearching = false, lyricCandidates = candidates) }
        }
    }

    fun selectLyricsCandidate(lease: DesktopOriginalMusicSourceLease, index: Int) {
        val cacheKey = lastLyricsCacheKey ?: return
        if (lastLyricsLease !== lease) return
        val candidate = _uiState.value.lyricCandidates.getOrNull(index) ?: return
        val repository = lyricsRepository ?: return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val request = DesktopOriginalMusicLyricsRequest(lease, kotlinx.coroutines.currentCoroutineContext(), isOpen)
            request.check()
            request.updateState(_uiState) { it.copy(isLyricsSearching = true) }
            when (val result = repository.select(cacheKey, candidate)) {
                is LyricsLoadResult.Found -> request.updateState(_uiState) {
                    it.copy(
                        lyricsDocument = result.document,
                        lyricsError = null,
                        lyricCandidates = emptyList(),
                        isLyricsSearching = false
                    )
                }
                LyricsLoadResult.NotFound -> request.updateState(_uiState) { it.copy(isLyricsSearching = false) }
                LyricsLoadResult.Failed -> request.updateState(_uiState) {
                    it.copy(isLyricsSearching = false, lyricsError = "歌词加载失败，请检查网络后重试")
                }
            }
        }
    }

    private suspend fun loadLyrics(
        request: DesktopOriginalMusicLyricsRequest,
        cacheKey: String,
        query: LyricQuery,
        bilibiliLyrics: String?,
        forceRefresh: Boolean = false
    ) {
        val repository = lyricsRepository ?: return
        request.updateState(_uiState) { it.copy(isLyricsSearching = true, lyricsError = null) }
        request.commit {
            lastLyricsCacheKey = cacheKey
            lastLyricsQuery = query
            lastBilibiliLyrics = bilibiliLyrics
            lastLyricsLease = request.sourceLease
        }
        when (val result = repository.load(cacheKey, query, bilibiliLyrics, forceRefresh)) {
            is LyricsLoadResult.Found -> {
                val document = result.document
                request.updateState(_uiState) {
                    it.copy(
                        lyricsDocument = document,
                        lyrics = document.lines.joinToString("\n") { line -> line.text },
                        lyricsError = null,
                        isLyricsSearching = false
                    )
                }
            }
            LyricsLoadResult.NotFound -> request.updateState(_uiState) {
                it.copy(isLyricsSearching = false, lyricsError = null)
            }
            LyricsLoadResult.Failed -> request.updateState(_uiState) {
                it.copy(isLyricsSearching = false, lyricsError = "歌词加载失败，请检查网络后重试")
            }
        }
    }

}
