package com.bilipai.desktop.ui

import com.android.purebilibili.feature.list.BaseListViewModel
import com.android.purebilibili.feature.list.HistoryViewModel
import com.android.purebilibili.feature.list.resolveHistoryPlaybackCid
import com.android.purebilibili.feature.list.resolveHistoryResumePositionMs
import com.android.purebilibili.feature.video.player.PlaylistItem

/** Original CommonList external-playlist schema deliberately omits history CID/progress.
 * Resolve them from the SAME original History VM just before the actual queue admission;
 * no queue, item, account or resume cache is copied into this stateless adapter. */
internal fun desktopOriginalPersonalQueueStart(
    model: BaseListViewModel,
    playlist: List<PlaylistItem>,
    selectedIndex: Int,
): Pair<List<PlaylistItem>, Long?>? {
    if (selectedIndex !in playlist.indices) return null
    val history = model as? HistoryViewModel ?: return playlist to null
    val items = playlist.map { item ->
        item.copy(cid = resolveHistoryPlaybackCid(item.cid, history.getHistoryItem(item.bvid)))
    }
    return items to resolveHistoryResumePositionMs(history.getHistoryItem(items[selectedIndex].bvid))
}
