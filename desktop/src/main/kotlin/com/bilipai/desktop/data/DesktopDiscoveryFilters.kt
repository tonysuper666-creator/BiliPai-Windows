package com.bilipai.desktop.data

import com.android.purebilibili.core.plugin.FeedKind
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.plugin.BiliPaiFeedFilterConfig
import com.android.purebilibili.feature.plugin.DesktopFeedFilterEditor
import com.android.purebilibili.feature.plugin.shouldShowFeedItem
import kotlinx.serialization.Serializable

@Serializable data class DesktopDiscoveryFilters(val enabled: Boolean = false, val config: BiliPaiFeedFilterConfig = BiliPaiFeedFilterConfig())

internal fun validateDiscoveryFilters(value: DesktopDiscoveryFilters) {
    val config = value.config
    require(config.minDurationForRcmd >= 0 && config.minPlayForRcmd >= 0 && config.minLikeRatioForRecommend in 0..100) { "时长和播放量须非负，点赞率须在 0–100%" }
    DesktopFeedFilterEditor.parseBanWordToRegex(config.banWordForRecommend)?.let { Regex(it, RegexOption.IGNORE_CASE) }
    DesktopFeedFilterEditor.parseBanWordToRegex(config.banWordForZone)?.let { Regex(it, RegexOption.IGNORE_CASE) }
}

internal fun filterDiscoveryItems(items: List<VideoItem>, filters: DesktopDiscoveryFilters, section: DiscoverySection): List<VideoItem> {
    if (!filters.enabled) return items
    val config = filters.config
    val title = DesktopFeedFilterEditor.parseBanWordToRegex(config.banWordForRecommend)?.let { Regex(it, RegexOption.IGNORE_CASE) }
    val region = DesktopFeedFilterEditor.parseBanWordToRegex(config.banWordForZone)?.let { Regex(it, RegexOption.IGNORE_CASE) }
    val kind = discoveryFeedKind(section)
    return items.filter { shouldShowFeedItem(config, it, kind, title, region) }
}

internal fun discoveryFeedKind(section: DiscoverySection): FeedKind = when (section) {
        DiscoverySection.RECOMMEND -> FeedKind.HOME_RECOMMEND
        DiscoverySection.RANKING -> FeedKind.HOME_RANK
        DiscoverySection.REGION -> FeedKind.HOME_REGION
        else -> FeedKind.HOME_POPULAR
}
