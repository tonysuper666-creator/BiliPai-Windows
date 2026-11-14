package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BangumiDetail
import com.android.purebilibili.data.model.response.BangumiEpisode

/** Ordered original DTOs. Main episodes win duplicate IDs; no DTO is copied. */
internal fun desktopBangumiAllEpisodes(detail: BangumiDetail): List<BangumiEpisode> =
    (detail.episodes.orEmpty() + detail.section.orEmpty().flatMap { it.episodes.orEmpty() })
        .filter { it.id > 0L }.distinctBy { it.id }

/** A playback queue belongs to one original group, never a flattened season. */
internal fun desktopBangumiPlaybackEpisodes(detail: BangumiDetail, episodeId: Long): List<BangumiEpisode> {
    if (episodeId <= 0L) return emptyList()
    val groups = listOf(detail.episodes.orEmpty()) + detail.section.orEmpty().map { it.episodes.orEmpty() }
    val index = groups.indexOfFirst { group -> group.any { it.id == episodeId } }
    if (index < 0) return emptyList()
    // An ID belongs to main, otherwise its first section. A duplicate in a later
    // group must not make next silently jump back to an earlier group's queue.
    val earlierIds = groups.take(index).flatten().mapTo(HashSet()) { it.id }
    return groups[index].filter { it.id > 0L && it.id !in earlierIds }.distinctBy { it.id }
}

/** Root integration candidate: a userStatus copy may share decoded containers.
 * Empty singleton lists and equal, separately decoded DTOs are never witnesses.
 * Both containers must match so a new section response cannot borrow main's receipt.
 */
internal fun desktopBangumiSameDecodedPlaybackSeason(displayed: BangumiDetail?, requested: BangumiDetail): Boolean {
    if (displayed == null) return false
    if (displayed === requested) return true
    return displayed.seasonId == requested.seasonId &&
        displayed.episodes === requested.episodes && displayed.section === requested.section &&
        desktopBangumiAllEpisodes(requested).isNotEmpty()
}
