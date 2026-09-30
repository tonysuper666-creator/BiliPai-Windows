package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.lyrics.LyricsRepository
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta

/** Business boundary shared by actual transport and deterministic queue/lifecycle verification. */
internal interface ListenPlaybackDataSource {
    val lyrics: LyricsRepository
    suspend fun prepare(item: PlaylistItem): PreparedListenAudio
    suspend fun subtitleTracks(item: PlaylistItem): List<SubtitleTrackMeta>
    suspend fun subtitleCues(track: SubtitleTrackMeta): List<SubtitleCue>
}
