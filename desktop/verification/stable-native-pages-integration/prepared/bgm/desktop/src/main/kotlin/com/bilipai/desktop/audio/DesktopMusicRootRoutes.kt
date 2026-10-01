package com.bilipai.desktop.audio

import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.player.PlayerState

/** Navigation adapter, not another API or player. Original AppNavigation onBgmClick is the source. */
internal sealed interface DesktopBgmMusicTarget {
    data class Web(val url: String) : DesktopBgmMusicTarget
    data class Detail(val musicId: String, val aid: Long = 0, val cid: Long = 0, val showVideos: Boolean = false) : DesktopBgmMusicTarget
}

internal fun resolveDesktopBgmMusicTarget(bgm: BgmInfo, bvid: String, cid: Long): DesktopBgmMusicTarget? {
    return resolveOriginalBgmMusicTarget(bgm, cid)
}

/** Existing queue identifiers remain AU SID or actual BV/CID; unknown CID is never invented. */
internal fun nativeMusicSourceForListenItem(item: PlaylistItem?): MusicPlaybackSource? {
    item ?: return null
    val sid = Regex("(?i)^au([1-9][0-9]*)$").matchEntire(item.bvid)?.groupValues?.get(1)?.toLongOrNull()
    if (sid != null) return MusicPlaybackSource.AudioSong(sid)
    if (item.bvid.startsWith("au", ignoreCase = true)) return null
    return item.takeIf { it.bvid.isNotBlank() && it.cid > 0 }?.let { MusicPlaybackSource.VideoAudio(it.bvid, it.cid, it.title) }
}

internal data class DesktopMusicVideoTarget(val bvid: String, val cid: Long, val sourceVersion: Long, val sessionEpoch: Long) {
    fun isCurrent(actualBvid: String?, actualCid: Long?, actualVersion: Long?, actualEpoch: Long): Boolean =
        cid > 0 && sourceVersion > 0 && sessionEpoch == actualEpoch && bvid == actualBvid && cid == actualCid && sourceVersion == actualVersion
}

/** A back-to-video handoff uses only this session's actual native progress and the route's exact CID. */
internal fun nativeMusicVideoReturnCard(source: MusicPlaybackSource.VideoAudio, state: ListenAudioState,
    native: PlayerState, ownsNative: Boolean): VideoCard {
    val matching = source.matches(state.current)
    val item = state.current?.takeIf { matching }
    val seconds = native.positionSeconds.takeIf { matching && ownsNative && it.isFinite() && it >= 0.0 }
        ?.coerceAtMost(Int.MAX_VALUE.toDouble())?.toInt()
    return VideoCard(source.bvid, item?.title ?: source.title, item?.cover.orEmpty(), item?.owner.orEmpty(), 0,
        (item?.duration ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), progressSeconds = seconds, preferredCid = source.cid)
}
