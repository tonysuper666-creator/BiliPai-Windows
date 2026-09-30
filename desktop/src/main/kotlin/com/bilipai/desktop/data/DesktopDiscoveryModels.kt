package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.core.util.IdUtils
import com.android.purebilibili.data.repository.buildDesktopWebRecommendParams
import com.android.purebilibili.core.store.DEFAULT_HOME_REFRESH_COUNT
import com.android.purebilibili.core.store.normalizeHomeRefreshCount
import com.android.purebilibili.data.repository.BlockedUpWriteResult

enum class DiscoverySection(val label: String) {
    RECOMMEND("推荐"), POPULAR("综合热门"), REGION("分区"), RANKING("排行榜"), PRECIOUS("入站必刷"), WEEKLY("每周必看")
}

data class DiscoveryPage(val items: List<VideoItem>, val nextPage: Int?, val title: String = "", val description: String = "",
    val weeklyConfig: PopularSeriesConfig? = null, val requestPage: Int = 1,
    val requestedMode: DesktopRecommendationMode? = null, val actualSources: Set<DesktopRecommendationMode> = emptySet(), val sourceNotice: String = "") {
    val cards: List<VideoCard> get() = items.map(::discoveryVideoCard)
}

internal fun discoveryVideoCard(item: VideoItem) = VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name,
    item.stat.view.toLong(), item.duration, preferredCid = item.cid, publishedAt = item.pubdate, authorMid = item.owner.mid)

/** Preserve the original season/sections/pages; queue items carry exact episode cid navigation. */
data class DesktopUgcCollection(val season: UgcSeason, val sections: List<UgcSection>, val queue: List<VideoCard>)

fun desktopUgcCollection(details: VideoDetails, sort: CollectionSortMode = CollectionSortMode.ASCENDING,
    currentCid: Long = details.pages.firstOrNull()?.cid ?: 0): DesktopUgcCollection? {
    val season = details.raw?.ugc_season ?: return null
    val sections = season.sections.map { section -> section.copy(episodes = sortCollectionEpisodes(section.episodes, sort, details.bvid, currentCid)) }
    val queue = sections.flatMap { it.episodes }.mapNotNull { episode ->
        val bvid = discoveryEpisodeBvid(episode)
        if (bvid.isBlank()) return@mapNotNull null
        VideoCard(bvid, episode.title.ifBlank { episode.arc?.title.orEmpty() }, personalImageUrl(episode.arc?.pic ?: season.cover), details.author,
            episode.arc?.stat?.view?.toLong() ?: 0, episode.arc?.duration ?: 0, preferredCid = episode.cid.takeIf { it > 0 }
                ?: episode.pages.firstOrNull()?.cid ?: 0, publishedAt = episode.arc?.pubdate ?: 0, authorMid = season.mid.takeIf { it > 0 } ?: details.authorMid)
    }.distinctBy { Pair(it.bvid, it.preferredCid) }
    return DesktopUgcCollection(season, sections, queue)
}

internal fun discoveryEpisodeBvid(episode: UgcEpisode): String = episode.bvid.ifBlank {
    (episode.aid.takeIf { it > 0 } ?: episode.arc?.aid?.takeIf { it > 0 })?.let(IdUtils::av2bv).orEmpty()
}

internal fun discoveryRecommendParams(page: Int, pageSize: Int = DEFAULT_HOME_REFRESH_COUNT): Map<String, String> {
    require(page > 0)
    return buildDesktopWebRecommendParams(page - 1, normalizeHomeRefreshCount(pageSize))
}

data class DesktopRecommendationFeedbackResult(val message: String, val creator: BlockedUpWriteResult? = null,
    val serverFeedbackSynced: Boolean? = null, val serverFeedbackError: String? = null)

internal fun discoveryPopularPage(data: PopularData, page: Int): DiscoveryPage {
    val items = data.list.orEmpty().map { it.toVideoItem() }.filter { it.bvid.isNotBlank() }
    return DiscoveryPage(items, (page + 1).takeIf { !data.no_more && data.list.orEmpty().isNotEmpty() })
}
