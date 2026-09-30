package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.DynamicFeedData
import com.android.purebilibili.data.model.response.FavoriteResourceData
import com.android.purebilibili.data.model.response.HistoryBusiness
import com.android.purebilibili.data.model.response.HistoryListData
import com.android.purebilibili.data.model.response.WatchLaterData

/** Server cursors remain authoritative when this desktop page contains unsupported resources. */
internal fun personalFavoritePage(data: FavoriteResourceData): VideoPage {
    val raw = data.medias.orEmpty()
    val videos = raw.map { it.toVideoItem() }.filter { !it.isCollectionResource && it.bvid.isNotBlank() }.map {
        VideoCard(it.bvid, it.title, personalImageUrl(it.pic), it.owner.name, it.stat.view.toLong(), it.duration,
            progressSeconds = it.progress, preferredCid = it.cid, viewedAt = it.view_at, authorMid = it.owner.mid)
    }.distinctBy { it.bvid }
    return VideoPage(videos, data.has_more && raw.isNotEmpty(), data.info?.media_count?.coerceAtLeast(0))
}

internal fun personalHistoryPage(data: HistoryListData, previous: CloudHistoryCursor?): CloudHistoryPage {
    val raw = data.list.orEmpty()
    val videos = raw.filter { HistoryBusiness.fromValue(it.history?.business.orEmpty()) == HistoryBusiness.ARCHIVE }
        .mapNotNull { history ->
            val item = history.toVideoItem()
            if (item.bvid.isBlank()) return@mapNotNull null
            VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name, item.stat.view.toLong(), item.duration,
                progressSeconds = history.progress, preferredCid = history.history?.cid ?: 0,
                pageIndex = ((history.history?.page ?: 1) - 1).coerceAtLeast(0), viewedAt = history.view_at, authorMid = item.owner.mid)
        }.distinctBy { Triple(it.bvid, it.preferredCid, it.viewedAt) }
    val next = data.cursor?.takeIf { it.max > 0 && raw.isNotEmpty() }?.let {
        CloudHistoryCursor(it.max, it.view_at.coerceAtLeast(0), it.business.trim())
    }?.takeIf { it != previous }
    return CloudHistoryPage(videos, next)
}

internal fun personalWatchLaterParams(page: Int): Map<String, String> {
    require(page > 0)
    // Same read request as upstream WatchLaterRepository.buildWatchLaterPageParams.
    return mapOf("pn" to page.toString(), "ps" to "20", "viewed" to "0", "key" to "",
        "asc" to "false", "need_split" to "true", "web_location" to "333.881")
}

internal fun personalWatchLaterPage(data: WatchLaterData, page: Int): VideoPage {
    require(page > 0)
    val raw = data.list.orEmpty()
    val videos = raw.filter { !it.bvid.isNullOrBlank() && it.isPgc != true && it.isPugv != true }.map {
        VideoCard(it.bvid.orEmpty(), it.title.orEmpty(), personalImageUrl(it.pic.orEmpty()), it.owner?.name.orEmpty(),
            it.stat?.view?.toLong() ?: 0L, it.duration ?: 0, progressSeconds = it.progress, preferredCid = it.cid ?: 0,
            publishedAt = it.pubdate ?: 0, authorMid = it.owner?.mid ?: 0)
    }.distinctBy { it.bvid }
    val total = data.count.coerceAtLeast(raw.size)
    return VideoPage(videos, raw.isNotEmpty() && page.toLong() * 20 < total, total)
}

internal fun personalFollowingPage(data: DynamicFeedData, requestedOffset: String): FollowingVideoPage {
    val videos = data.items.mapNotNull { dynamic ->
        val major = dynamic.modules.module_dynamic?.major
        val archive = major?.archive ?: major?.ugc_season?.archive ?: return@mapNotNull null
        if (archive.bvid.isBlank()) return@mapNotNull null
        val author = dynamic.modules.module_author
        VideoCard(archive.bvid, archive.title, personalImageUrl(archive.cover), author?.name.orEmpty(),
            personalCountText(archive.stat.play), personalDurationText(archive.duration_text), publishedAt = author?.pub_ts ?: 0, authorMid = author?.mid ?: 0)
    }.distinctBy { it.bvid }
    val next = data.offset.trim().takeIf { data.has_more && it.isNotBlank() && it != requestedOffset }
    return FollowingVideoPage(videos, next, data.update_baseline)
}

internal fun personalCountText(text: String): Long {
    val normalized = text.trim().replace(",", "")
    val multiplier = when {
        normalized.endsWith("万") -> 10_000
        normalized.endsWith("亿") -> 100_000_000
        else -> 1
    }
    val count = normalized.removeSuffix("万").removeSuffix("亿").toDoubleOrNull() ?: return 0
    return (count * multiplier).takeIf { it.isFinite() && it > 0 }?.coerceAtMost(Long.MAX_VALUE.toDouble())?.toLong() ?: 0
}

internal fun personalDurationText(text: String): Int {
    val parts = text.trim().split(':')
    if (parts.size !in 2..3) return 0
    val values = parts.map { it.toLongOrNull()?.takeIf { number -> number >= 0 } ?: return 0 }
    if (values.drop(1).any { it >= 60 } || values.first() > Int.MAX_VALUE) return 0
    return values.fold(0L) { seconds, number -> seconds * 60 + number }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun personalImageUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
    else -> url
}
