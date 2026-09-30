package com.bilipai.desktop.data

import com.android.purebilibili.core.util.IdUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*

/** A Windows presentation boundary over the original aggregate seed, not a server response schema. */
class DesktopSpaceOverview internal constructor(internal val seed: SpaceInitialSeed, val supplemental: DesktopSpaceHome) {
    val user: SpaceUserInfo get() = seed.userInfo
    val relation: RelationStatData? get() = seed.relationStat
    val statistics: UpStatData? get() = seed.upStat
    val mainTabs: List<SpaceMainTabItem> get() = seed.mainTabs
    val contributionTabs: List<SpaceContributionTab> get() = seed.contributionTabs
    val defaultMainTab: SpaceMainTab get() = seed.defaultMainTab
    val defaultContributionTabId: String get() = seed.defaultContributionTabId
    val hasCheeseTab: Boolean get() = seed.hasCheeseTab
}

fun DesktopSpaceMetadata.toOverview(supplemental: DesktopSpaceHome = DesktopSpaceHome(null, ""),
    cardLargePhoto: String = "", cardSmallPhoto: String = "", cardIpLocation: String? = null): DesktopSpaceOverview? =
    resolveSpaceInitialSeedFromAggregate(data, cardLargePhoto, cardSmallPhoto, cardIpLocation)?.let { DesktopSpaceOverview(it, supplemental) }

internal fun desktopSpaceVideoCard(item: SpaceVideoItem, mid: Long, progress: SpaceWatchProgress? = null,
    localPositionMs: Long = 0L): VideoCard {
    val playback = resolveSpacePlaybackTarget(progress, localPositionMs)
    return VideoCard(item.bvid.ifBlank { item.aid.takeIf { it > 0 }?.let(IdUtils::av2bv).orEmpty() }, item.title,
        item.pic, item.author, item.play.toLong(), personalDurationText(item.length), preferredCid = playback.cid,
        progressSeconds = (playback.resumePositionMs / 1_000).toInt().takeIf { it > 0 }, publishedAt = item.created, authorMid = mid)
}

internal fun desktopSpaceAggregateCard(item: SpaceAggregateArchiveItem, videoId: String, mid: Long): VideoCard {
    val bvid = videoId.takeIf { it.startsWith("BV") } ?: videoId.removePrefix("av").toLongOrNull()?.let(IdUtils::av2bv) ?: videoId
    return VideoCard(bvid, item.title, item.cover, item.author, item.play.toLong(), item.duration.takeIf { it > 0 }
        ?: personalDurationText(item.length), preferredCid = item.firstCid, publishedAt = item.ctime, authorMid = mid)
}

/** Dispatch exactly as SpaceScreen: explicit video first, then PGC, audio, then the untouched URI. */
fun dispatchDesktopSpaceAggregate(item: SpaceAggregateArchiveItem, mid: Long, onVideo: (VideoCard) -> Unit,
    onAudio: (Long) -> Unit, onBangumi: (Long) -> Unit, onExternalUrl: (String, String) -> Unit) =
    handleAggregateArchiveClick(item, { onVideo(desktopSpaceAggregateCard(item, it, mid)) }, onAudio, onBangumi, onExternalUrl)
