package com.bilipai.desktop.ui

import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.home.HomeCategory
import com.android.purebilibili.feature.home.PopularSubCategory
import com.android.purebilibili.navigation.ScreenRoutes
import com.android.purebilibili.navigation3.*

/** Original AppNavigation.openBilibiliNativeTargetInNavigation3 branch mapping.
 * The existing physical pager is selected by routes.home(); the original VM owns its category.
 * In particular a PopularFeed target is not reduced to a separate Windows flat popular page. */
internal fun dispatchDesktopReadyNativeTarget(root: DesktopHomeRetainedRoot,
    routes: DesktopOriginalRootRouteAssembly, target: BilibiliNavigationTarget): Boolean {
    if (!routes.owns()) return false
    when (target) {
        is BilibiliNavigationTarget.Video -> routes.video(BiliPaiNavKey.VideoDetail(target.videoId))
        is BilibiliNavigationTarget.Dynamic -> routes.push(BiliPaiNavKey.DynamicDetail(target.dynamicId))
        is BilibiliNavigationTarget.Search -> routes.push(BiliPaiNavKey.Search(target.keyword))
        is BilibiliNavigationTarget.Space -> {
            if (target.mid <= 0L) return false
            routes.push(BiliPaiNavKey.Space(target.mid))
        }
        is BilibiliNavigationTarget.Live -> routes.push(BiliPaiNavKey.Live(roomId = target.roomId.toString()))
        is BilibiliNavigationTarget.BangumiSeason -> routes.push(BiliPaiNavKey.BangumiDetail(
            seasonId = target.seasonId, mediaId = target.mediaId))
        is BilibiliNavigationTarget.BangumiEpisode -> routes.push(BiliPaiNavKey.BangumiDetail(seasonId = 0L, epId = target.epId))
        is BilibiliNavigationTarget.Music -> routes.push(legacyRouteToBiliPaiNavKey(
            ScreenRoutes.createMusicRoute(target.musicId) ?: return false))
        is BilibiliNavigationTarget.Article -> routes.push(BiliPaiNavKey.ArticleDetail(target.articleId))
        is BilibiliNavigationTarget.PopularFeed -> {
            if (target.subCategoryKey == "weekly") {
                routes.push(BiliPaiNavKey.WeeklySeries(target.weeklyNumber)); return true
            }
            root.entry.viewModel.switchPopularSubCategory(when (target.subCategoryKey) {
                "weekly" -> PopularSubCategory.WEEKLY
                "rank" -> PopularSubCategory.RANKING
                "all", "precious" -> PopularSubCategory.PRECIOUS
                else -> PopularSubCategory.COMPREHENSIVE
            })
            root.entry.viewModel.switchCategory(HomeCategory.POPULAR)
            routes.home()
        }
    }
    return true
}
