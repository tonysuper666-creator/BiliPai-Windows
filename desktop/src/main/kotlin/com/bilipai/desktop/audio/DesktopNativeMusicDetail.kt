package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.player.*
import com.android.purebilibili.feature.audio.viewmodel.MusicUiState
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlaybackMode

/** Routes preserve original AU SID and video BV/CID source identity separately. */
internal fun musicSourcePlaylistItem(source: MusicPlaybackSource): PlaylistItem = when(source) {
    is MusicPlaybackSource.AudioSong -> {
        require(source.sid > 0) { "歌曲 SID 无效。" }
        PlaylistItem("au${source.sid}", title = "au${source.sid}", cover = "", owner = "")
    }
    is MusicPlaybackSource.VideoAudio -> {
        require(source.bvid.isNotBlank() && source.cid > 0) { "视频音轨编号无效。" }
        PlaylistItem(source.bvid, source.cid, source.title, "", "")
    }
}

internal fun MusicPlaybackSource.matches(item: PlaylistItem?): Boolean = when(this) {
    is MusicPlaybackSource.AudioSong -> item?.bvid.equals("au$sid", ignoreCase = true)
    is MusicPlaybackSource.VideoAudio -> item?.bvid == bvid && item.cid == cid
}

/** The original MusicUiState is extracted verbatim; this is only a Windows projection of retained audio. */
internal fun projectNativeMusicState(source: MusicPlaybackSource, state: ListenAudioState,
    native: PlayerState, ownsNative: Boolean): MusicUiState {
    val matches = source.matches(state.current)
    val current = state.current?.takeIf { matches }
    val song = state.songInfo?.takeIf { matches && source is MusicPlaybackSource.AudioSong }
    return MusicUiState(
        source = source,
        isLoading = matches && state.loading,
        songInfo = song,
        musicTitle = song?.title ?: current?.title ?: (source as? MusicPlaybackSource.VideoAudio)?.title,
        musicCover = song?.cover ?: current?.cover,
        musicArtist = song?.author?.ifBlank { song.uname } ?: current?.owner,
        lyricsDocument = if(matches && ownsNative) state.lyrics else null,
        lyricsError = if(matches && ownsNative) state.lyricsError else null,
        lyricCandidates = if(matches && ownsNative) state.candidates else emptyList(),
        isLyricsSearching = matches && ownsNative && state.lyricsLoading,
        error = if(matches) state.error ?: if(ownsNative) native.error else if(state.loading) null else "当前播放已切换，可重新播放这首内容。"
            else "当前播放已切换，可重新播放这首内容。",
        isPlaying = matches && ownsNative && state.active && !native.paused && !native.loading && !native.ended,
        isBuffering = matches && ownsNative && native.loading,
        currentPositionMs = if(matches && ownsNative) (native.positionSeconds.coerceAtLeast(0.0) * 1_000).toLong() else 0,
        durationMs = if(matches && ownsNative && native.durationSeconds > 0) (native.durationSeconds * 1_000).toLong()
            else (song?.duration?.toLong() ?: current?.duration ?: 0L).coerceAtLeast(0L) * 1_000,
    )
}

internal fun projectNativeMusicPlayerState(state: MusicUiState, mode: PlaybackMode, speed: Double): MusicPlayerUiState {
    val title = state.songInfo?.title ?: state.musicTitle ?: "未知歌曲"
    val artist = state.songInfo?.author?.ifBlank { state.songInfo.uname } ?: state.musicArtist.orEmpty()
    return MusicPlayerUiState(title = title, artist = artist, coverUrl = state.songInfo?.cover ?: state.musicCover.orEmpty(),
        isLoading = state.isLoading, error = state.error, isPlaying = state.isPlaying, isBuffering = state.isBuffering,
        positionMs = state.currentPositionMs, durationMs = state.durationMs, lyrics = state.lyricsDocument,
        lyricsError = state.lyricsError, isLyricsSearching = state.isLyricsSearching,
        lyricCandidates = state.lyricCandidates.map { MusicLyricCandidateUi(it.title, it.artist, it.source.name) },
        queue = listOf(MusicQueueItemUi(state.source?.stableId ?: "loading", title, artist, state.songInfo?.cover ?: state.musicCover.orEmpty())),
        currentQueueIndex = 0, playbackSpeed = speed.toFloat(),
        playMode = when(mode) {
            PlaybackMode.REPEAT_ONE -> com.android.purebilibili.feature.video.player.PlayMode.REPEAT_ONE
            PlaybackMode.REPEAT_ALL -> com.android.purebilibili.feature.video.player.PlayMode.REPEAT_ALL
            PlaybackMode.SHUFFLE -> com.android.purebilibili.feature.video.player.PlayMode.SHUFFLE
            else -> com.android.purebilibili.feature.video.player.PlayMode.SEQUENTIAL
        }, shuffleEnabled = mode == PlaybackMode.SHUFFLE)
}

/** No player is created. Root's single retained ListenAudioSession remains the music owner. */
internal fun ListenAudioSession.openNativeMusic(source: MusicPlaybackSource) = play(listOf(musicSourcePlaylistItem(source)))

internal fun shouldStartNativeMusic(source: MusicPlaybackSource, state: ListenAudioState, ownsNative: Boolean): Boolean =
    !source.matches(state.current) || !(state.loading || state.active && ownsNative)
