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
    routes: DesktopOriginalRootRouteAssembly, target: BilibiliNavigationTarget,
    stillOwned: (() -> Boolean)? = null, sourceAdmission: (((() -> Unit) -> Boolean))? = null): Boolean {
    if (!routes.owns() || stillOwned?.invoke() == false) return false
    fun push(key: BiliPaiNavKey): Boolean = if (stillOwned == null && sourceAdmission == null) {
        routes.push(key); true // Preserve the existing three-argument dispatch outcome.
    } else routes.pushFromSource(key, stillOwned, sourceAdmission)
    when (target) {
        is BilibiliNavigationTarget.Video -> {
            val key = BiliPaiNavKey.VideoDetail(target.videoId)
            if (stillOwned == null && sourceAdmission == null) routes.video(key)
            else routes.videoFromSource(key, stillOwned, sourceAdmission)
        }
        is BilibiliNavigationTarget.Dynamic -> return push(BiliPaiNavKey.DynamicDetail(target.dynamicId))
        is BilibiliNavigationTarget.Search -> return push(BiliPaiNavKey.Search(target.keyword))
        is BilibiliNavigationTarget.Space -> {
            if (target.mid <= 0L) return false
            return push(BiliPaiNavKey.Space(target.mid))
        }
        is BilibiliNavigationTarget.Live -> return push(BiliPaiNavKey.Live(roomId = target.roomId.toString()))
        is BilibiliNavigationTarget.BangumiSeason -> return push(BiliPaiNavKey.BangumiDetail(
            seasonId = target.seasonId, mediaId = target.mediaId))
        is BilibiliNavigationTarget.BangumiEpisode -> return push(BiliPaiNavKey.BangumiDetail(seasonId = 0L, epId = target.epId))
        is BilibiliNavigationTarget.Music -> return push(legacyRouteToBiliPaiNavKey(
            ScreenRoutes.createMusicRoute(target.musicId) ?: return false))
        is BilibiliNavigationTarget.Article -> return push(BiliPaiNavKey.ArticleDetail(target.articleId))
        is BilibiliNavigationTarget.PopularFeed -> {
            if (target.subCategoryKey == "weekly") {
                return push(BiliPaiNavKey.WeeklySeries(target.weeklyNumber))
            }
            fun preparePopular() {
                root.entry.viewModel.switchPopularSubCategory(when (target.subCategoryKey) {
                    "weekly" -> PopularSubCategory.WEEKLY
                    "rank" -> PopularSubCategory.RANKING
                    "all", "precious" -> PopularSubCategory.PRECIOUS
                    else -> PopularSubCategory.COMPREHENSIVE
                })
                root.entry.viewModel.switchCategory(HomeCategory.POPULAR)
            }
            if (stillOwned == null && sourceAdmission == null) {
                preparePopular(); routes.home()
            } else return routes.pushFromSource(BiliPaiNavKey.Home, stillOwned, sourceAdmission, ::preparePopular)
        }
    }
    return true
}
