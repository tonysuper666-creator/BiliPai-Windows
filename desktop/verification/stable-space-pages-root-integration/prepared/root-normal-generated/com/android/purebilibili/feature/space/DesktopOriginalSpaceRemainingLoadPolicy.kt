package com.android.purebilibili.feature.space

import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.core.util.BilibiliNavigationTargetParser
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.core.util.resolveWindowWidthSizeClass
import com.android.purebilibili.feature.home.resolveHomeFeedGridColumns
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.SeasonArchiveItem
import com.android.purebilibili.data.model.response.SeasonItem
import com.android.purebilibili.data.model.response.SeriesArchiveItem
import com.android.purebilibili.data.model.response.SeriesItem
import com.android.purebilibili.data.model.response.SpaceAggregateArchiveItem
import com.android.purebilibili.data.model.response.SpaceAggregateData
import com.android.purebilibili.data.model.response.SpaceAggregateFavoriteItem
import com.android.purebilibili.data.model.response.SpaceAggregateImages
import com.android.purebilibili.data.model.response.SpaceAggregateRelation
import com.android.purebilibili.data.model.response.SpaceAudioItem
import com.android.purebilibili.data.model.response.SpaceTagItem
import com.android.purebilibili.data.model.response.SpaceUserInfo
import com.android.purebilibili.data.model.response.SpaceVideoItem
import com.android.purebilibili.data.model.response.Stat
import com.android.purebilibili.data.model.response.RelationStatData
import com.android.purebilibili.data.model.response.UpStatData
import com.android.purebilibili.data.model.response.ArchiveStatInfo
import com.android.purebilibili.data.model.response.SpaceArticleItem
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.model.response.VideoSortOrder

enum class SpaceSearchScope {
    NONE,
    DYNAMIC,
    VIDEO
}

internal fun resolveSpaceSearchScope(
    selectedMainTab: SpaceMainTab,
    selectedSubTab: SpaceSubTab
): SpaceSearchScope {
    return when {
        selectedMainTab == SpaceMainTab.DYNAMIC -> SpaceSearchScope.DYNAMIC
        selectedMainTab == SpaceMainTab.CONTRIBUTION &&
            selectedSubTab == SpaceSubTab.VIDEO -> {
            SpaceSearchScope.VIDEO
        }
        else -> SpaceSearchScope.NONE
    }
}

internal fun resolveSpaceSearchPlaceholder(scope: SpaceSearchScope): String {
    return when (scope) {
        SpaceSearchScope.DYNAMIC -> "搜索 TA 的动态"
        SpaceSearchScope.VIDEO -> "搜索 TA 的视频"
        SpaceSearchScope.NONE -> ""
    }
}

internal fun resolveSpaceSearchBarGridItemIndex(
    scope: SpaceSearchScope,
    @Suppress("UNUSED_PARAMETER") hasContributionToolbar: Boolean
): Int? {
    return when (scope) {
        // Header(0) + optional SearchEntry. Main/secondary tabs are pinned outside the grid.
        SpaceSearchScope.DYNAMIC,
        SpaceSearchScope.VIDEO -> 1
        SpaceSearchScope.NONE -> null
    }
}

/**
 * Always-visible search entry under main tabs (not only top-right icon).
 * Returns a short CTA label for the current searchable scope.
 */
internal fun resolveSpaceSearchEntryLabel(scope: SpaceSearchScope): String {
    return when (scope) {
        SpaceSearchScope.DYNAMIC -> "搜索 TA 的动态"
        SpaceSearchScope.VIDEO -> "搜索 TA 的视频"
        SpaceSearchScope.NONE -> ""
    }
}

internal fun shouldShowSpaceSearchEntry(
    scope: SpaceSearchScope,
    isSearchMode: Boolean
): Boolean {
    return scope != SpaceSearchScope.NONE && !isSearchMode
}

internal fun resolveSpaceSearchBarRevealScrollOffsetPx(
    topBarHeightPx: Int,
    extraVisibleMarginPx: Int
): Int {
    return -(topBarHeightPx.coerceAtLeast(0) + extraVisibleMarginPx.coerceAtLeast(0))
}

internal fun shouldEnableSpaceLazyGridSharedTransition(
    transitionEnabled: Boolean,
    hasSharedTransitionScope: Boolean,
    hasAnimatedVisibilityScope: Boolean
): Boolean {
    return transitionEnabled && hasSharedTransitionScope && hasAnimatedVisibilityScope
}















internal fun resolveSpaceArchiveSharedTransitionKey(bvid: String): String? {
    return bvid.trim().takeIf { it.isNotEmpty() }
}



/**
 * Resolves the in-app video target used by aggregate cards (coin/like previews).
 *
 * The aggregate endpoint is inconsistent: some responses populate [bvid], while
 * others only provide an `av`/numeric [param] or a `bilibili://video/...` [uri].
 * Keep that fallback inside the navigation policy so a missing bvid cannot send
 * a video deep link through the generic browser callback.
 */








internal const val SPACE_CONTENT_MAX_WIDTH_DP = 980
internal const val SPACE_EXPANDED_CONTENT_MAX_WIDTH_DP = 1280
internal const val SPACE_LIST_CONTENT_MAX_WIDTH_DP = 720
private const val SPACE_DYNAMIC_MIN_COLUMN_WIDTH_DP = 360
private const val SPACE_DYNAMIC_MAX_COLUMNS = 3

internal data class SpaceAdaptiveLayoutSpec(
    val contentMaxWidthDp: Int,
    val useExpandedHeader: Boolean,
    val dynamicColumns: Int,
    val listContentMaxWidthDp: Int = SPACE_LIST_CONTENT_MAX_WIDTH_DP,
)

internal const val SPACE_BANNER_ASPECT_RATIO = 1125f / 396f
/** Matches PiliPlus `kHeaderHeight = 135.0`. */
internal const val SPACE_HEADER_HEIGHT_DP = 135f
internal const val SPACE_WIDE_BANNER_MAX_HEIGHT_DP = 135f
internal const val SPACE_WIDE_BANNER_MIN_HEIGHT_DP = 120f

internal data class SpaceBannerMetrics(
    val heightDp: Float,
    val cropToFill: Boolean,
    val heroHeightDp: Float = heightDp,
)



/**
 * Flat unfolded foldables and tablets deliberately share the same width-class policy.
 * Geometry is derived from the current window, not from a device/model distinction.
 */
internal fun resolveSpaceAdaptiveLayoutSpec(
    widthDp: Int,
    widthSizeClass: WindowWidthSizeClass = resolveWindowWidthSizeClass(widthDp.dp),
): SpaceAdaptiveLayoutSpec {
    val expanded = widthSizeClass >= WindowWidthSizeClass.Expanded
    val useExpandedHeader = widthSizeClass != WindowWidthSizeClass.Compact
    val contentMaxWidthDp = if (expanded) {
        SPACE_EXPANDED_CONTENT_MAX_WIDTH_DP
    } else {
        SPACE_CONTENT_MAX_WIDTH_DP
    }
    val boundedContentWidthDp = minOf(widthDp.coerceAtLeast(0), contentMaxWidthDp)
    val dynamicColumns = (boundedContentWidthDp / SPACE_DYNAMIC_MIN_COLUMN_WIDTH_DP)
        .coerceIn(1, SPACE_DYNAMIC_MAX_COLUMNS)
    return SpaceAdaptiveLayoutSpec(
        contentMaxWidthDp = contentMaxWidthDp,
        useExpandedHeader = useExpandedHeader,
        dynamicColumns = dynamicColumns,
    )
}

internal fun resolveSpaceBannerMetrics(
    renderedBannerWidthDp: Float,
    windowWidthDp: Float,
    windowHeightDp: Float,
    topInsetDp: Float = 0f,
): SpaceBannerMetrics {
    val landscape = windowHeightDp > 0f && windowWidthDp > windowHeightDp
    val useDesktopHeader = windowWidthDp >= 600f || landscape
    val naturalHeroHeight = renderedBannerWidthDp.coerceAtLeast(0f) / SPACE_BANNER_ASPECT_RATIO
    val heroHeight = if (useDesktopHeader) {
        if (windowHeightDp > 0f) {
            (windowHeightDp * 0.22f).coerceIn(
                SPACE_WIDE_BANNER_MIN_HEIGHT_DP,
                SPACE_WIDE_BANNER_MAX_HEIGHT_DP,
            )
        } else {
            SPACE_WIDE_BANNER_MAX_HEIGHT_DP
        }
    } else {
        naturalHeroHeight
    }
    val totalHeight = heroHeight + topInsetDp
    return SpaceBannerMetrics(
        heightDp = totalHeight,
        cropToFill = useDesktopHeader,
        heroHeightDp = heroHeight,
    )
}

/**
 * 投稿网格列数：与首页信息流共用同一套策略（用户固定列数优先，其次按卡宽预设自适应），
 * 内容宽度按当前空间页自适应上限截断，保证投稿卡片排版与首页 feed 对齐。
 */
internal fun resolveSpaceContentGridColumnCount(
    widthDp: Int,
    fixedColumnCount: Int = 0,
    cardWidthPreset: HomeFeedCardWidthPreset = HomeFeedCardWidthPreset.AUTO,
    contentMaxWidthDp: Int = SPACE_CONTENT_MAX_WIDTH_DP,
    widthSizeClass: WindowWidthSizeClass = resolveWindowWidthSizeClass(widthDp.dp)
): Int {
    val contentWidthDp = minOf(widthDp, contentMaxWidthDp)
    return resolveHomeFeedGridColumns(
        contentWidthDp = contentWidthDp,
        displayMode = 0,
        fixedColumnCount = fixedColumnCount,
        cardWidthPreset = cardWidthPreset,
        widthSizeClass = widthSizeClass
    )
}









@Suppress("UNUSED_PARAMETER")






















internal fun mergeSpaceVideoPages(
    existing: List<SpaceVideoItem>,
    incoming: List<SpaceVideoItem>
): List<SpaceVideoItem> {
    val seen = LinkedHashSet<String>()
    val merged = ArrayList<SpaceVideoItem>(existing.size + incoming.size)
    fun addAll(source: List<SpaceVideoItem>) {
        for (item in source) {
            val key = item.bvid.ifBlank { item.aid.toString() }
            if (seen.add(key)) {
                merged += item
            }
        }
    }
    addAll(existing)
    addAll(incoming)
    return merged
}










