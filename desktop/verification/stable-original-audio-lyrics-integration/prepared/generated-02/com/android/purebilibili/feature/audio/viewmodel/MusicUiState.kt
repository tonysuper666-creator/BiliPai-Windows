// Generated from app/src/main/java/com/android/purebilibili/feature/audio/viewmodel/MusicViewModel.kt; do not edit.
// LF-normalized SHA-256: d9705a700bbe14722b79d9bb484d70da0afb97ae1c9df393e7a271e4a0173d0c
package com.android.purebilibili.feature.audio.viewmodel

import com.android.purebilibili.data.model.response.SongInfoData
import com.android.purebilibili.feature.audio.lyrics.LyricDocument
import com.android.purebilibili.feature.audio.lyrics.LyricCandidate
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource

internal data class MusicUiState(
    val isLoading: Boolean = false,
    val songInfo: SongInfoData? = null,
    val lyrics: String? = null,
    val lyricsDocument: LyricDocument? = null,
    val lyricsError: String? = null,
    val lyricCandidates: List<LyricCandidate> = emptyList(),
    val isLyricsSearching: Boolean = false,
    val error: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val currentPositionMs: Long = 0,
    val musicTitle: String? = null,
    val musicCover: String? = null,
    val musicArtist: String? = null,
    val source: MusicPlaybackSource? = null
)
