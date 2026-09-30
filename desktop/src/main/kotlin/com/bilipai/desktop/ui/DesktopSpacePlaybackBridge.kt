package com.bilipai.desktop.ui

import com.android.purebilibili.feature.space.SpaceExternalPlaylist
import com.bilipai.desktop.data.VideoCard

/** Original Space queue order/start index, with account-local resumable part identity at the Root boundary. */
internal fun desktopSpacePlaybackQueue(playlist: SpaceExternalPlaylist, mid: Long,
    history: List<VideoCard>): Pair<List<VideoCard>, Int>? {
    if (playlist.playlistItems.isEmpty() || playlist.startIndex !in playlist.playlistItems.indices) return null
    val progress = history.filter { it.authorMid == mid }.associateBy { it.bvid }
    val rows = playlist.playlistItems.map { item ->
        val saved = progress[item.bvid]
        val cid = item.cid.takeIf { it > 0 } ?: saved?.preferredCid ?: 0
        val samePart = saved != null && (item.cid <= 0 || item.cid == saved.preferredCid)
        VideoCard(item.bvid, item.title, item.cover, item.owner, 0, item.duration.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            progressSeconds = saved?.progressSeconds.takeIf { samePart }, preferredCid = cid,
            pageIndex = saved?.pageIndex.takeIf { samePart } ?: 0, authorMid = mid)
    }
    return rows to playlist.startIndex
}

/** Original Space play-all audio uses the same raw playlist, selected CID and account-local progress. */
internal fun desktopSpaceListenPlaybackQueue(playlist: SpaceExternalPlaylist, mid: Long,
    history: List<VideoCard>): Triple<List<com.android.purebilibili.feature.video.player.PlaylistItem>, Int, Double>? {
    val (rows, index) = desktopSpacePlaybackQueue(playlist, mid, history) ?: return null
    val items = playlist.playlistItems.zip(rows).map { (original, card) -> original.copy(cid = card.preferredCid) }
    val position = rows[index].progressSeconds?.coerceAtLeast(0)?.toDouble() ?: 0.0
    return Triple(items, index, position)
}
