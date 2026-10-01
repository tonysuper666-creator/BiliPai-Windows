package com.android.purebilibili.feature.space
import com.android.purebilibili.data.model.response.*
internal fun mapSeasonArchiveToVideoItem(
    item: SeasonArchiveItem,
    mid: Long,
    ownerName: String = ""
): VideoItem {
    return VideoItem(
        bvid = item.bvid,
        title = item.title,
        pic = item.pic,
        owner = com.android.purebilibili.data.model.response.Owner(
            mid = mid,
            name = item.author.ifBlank { ownerName }
        ),
        stat = Stat(
            view = item.stat.view.toInt(),
            danmaku = item.stat.danmaku.toInt(),
            reply = item.stat.reply.toInt()
        ),
        duration = item.duration,
        pubdate = item.pubdate
    )
}

internal fun mapSeriesArchiveToVideoItem(
    item: SeriesArchiveItem,
    mid: Long,
    ownerName: String = ""
): VideoItem {
    return VideoItem(
        bvid = item.bvid,
        title = item.title,
        pic = item.pic,
        owner = com.android.purebilibili.data.model.response.Owner(
            mid = mid,
            name = item.author.ifBlank { ownerName }
        ),
        stat = Stat(
            view = item.stat.view.toInt(),
            danmaku = item.stat.danmaku.toInt(),
            reply = item.stat.reply.toInt()
        ),
        duration = item.duration,
        pubdate = item.pubdate
    )
}
