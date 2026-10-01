package com.bilipai.desktop.ui
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.navigation.ScreenRoutes
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.*
import com.bilipai.desktop.ui.ArticleNavigationTarget

/** Complete pinned AppNavigation HISTORY video callback. Only concrete Root calls/scope
 * are parameters; business dispatch, CID, resume and vertical rules remain original. */
internal fun desktopOriginalHistoryVideoClick(
    historyViewModel: HistoryViewModel,
    historyNavigationScope: CoroutineScope,
    stillOwned: () -> Boolean,
    platform: DesktopPersonalListNavigation,
): (String, Long, String, Boolean) -> Unit {
    val original: (String, Long, String, Boolean) -> Unit = { lookupKey, cid, cover, isVertical ->
                                        val historyItem = historyViewModel.getHistoryItem(lookupKey)
                                        val resolvedCid = resolveHistoryPlaybackCid(
                                            clickedCid = cid,
                                            historyItem = historyItem
                                        )
                                        val resumePositionMs = resolveHistoryResumePositionMs(historyItem)
                                        when (resolveHistoryNavigationKind(historyItem)) {
                                            HistoryNavigationKind.PGC -> {
                                                if (historyItem != null && historyItem.epid > 0 && historyItem.seasonId > 0) {
                                                    platform.pushRoute(ScreenRoutes.BangumiPlayer.createRoute(historyItem.seasonId, historyItem.epid))
                                                } else if (historyItem != null && (historyItem.seasonId > 0 || historyItem.epid > 0)) {
                                                    platform.pushRoute(ScreenRoutes.BangumiDetail.createRoute(historyItem.seasonId, historyItem.epid))
                                                } else {
                                                    platform.video(
                                                        lookupKey,
                                                        resolvedCid,
                                                        cover,
                                                        resumePositionMs = resumePositionMs,
                                                        initialVertical = isVertical,
                                                        sourceRoute = ScreenRoutes.History.route
                                                    )
                                                }
                                            }
                                            HistoryNavigationKind.CHEESE -> {
                                                if (historyItem != null && (historyItem.seasonId > 0 || historyItem.epid > 0)) {
                                                    platform.push(
                                                        BiliPaiNavKey.BangumiPlayer(
                                                            seasonId = historyItem.seasonId,
                                                            epId = historyItem.epid,
                                                            resumePositionMs = resumePositionMs,
                                                            isCourse = true
                                                        )
                                                    )
                                                } else {
                                                    platform.video(
                                                        lookupKey,
                                                        resolvedCid,
                                                        cover,
                                                        resumePositionMs = resumePositionMs,
                                                        initialVertical = isVertical,
                                                        sourceRoute = ScreenRoutes.History.route
                                                    )
                                                }
                                            }
                                            HistoryNavigationKind.LIVE -> {
                                                if (historyItem != null && historyItem.roomId > 0) {
                                                    platform.pushRoute(
                                                        ScreenRoutes.Live.createRoute(
                                                            historyItem.roomId,
                                                            historyItem.videoItem.title,
                                                            historyItem.videoItem.owner.name
                                                        )
                                                    )
                                                } else {
                                                    platform.video(
                                                        lookupKey,
                                                        resolvedCid,
                                                        cover,
                                                        resumePositionMs = resumePositionMs,
                                                        initialVertical = isVertical,
                                                        sourceRoute = ScreenRoutes.History.route
                                                    )
                                                }
                                            }
                                            HistoryNavigationKind.ARTICLE -> {
                                                val articleId = historyItem?.videoItem?.id ?: 0L
                                                val articleTitle = historyItem?.videoItem?.title.orEmpty()
                                                if (articleId > 0L) {
                                                    historyNavigationScope.launch {
    if (!stillOwned()) return@launch
                                                        when (val target = platform.articleTarget(articleId)) {
                                                            is ArticleNavigationTarget.NativeDynamic -> {
                                                                platform.pushRoute(ScreenRoutes.DynamicDetail.createRoute(target.dynamicId))
                                                            }
                                                            is ArticleNavigationTarget.NativeArticle -> {
                                                                platform.pushRoute(
                                                                    ScreenRoutes.ArticleDetail.createRoute(target.articleId, articleTitle)
                                                                )
                                                            }
                                                            null -> {
                                                                platform.pushRoute(
                                                                    ScreenRoutes.ArticleDetail.createRoute(articleId, articleTitle)
                                                                )
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    platform.video(
                                                        lookupKey,
                                                        resolvedCid,
                                                        cover,
                                                        resumePositionMs = resumePositionMs,
                                                        initialVertical = isVertical,
                                                        sourceRoute = ScreenRoutes.History.route
                                                    )
                                                }
                                            }
                                            HistoryNavigationKind.VIDEO -> {
                                                platform.video(
                                                    lookupKey,
                                                    resolvedCid,
                                                    cover,
                                                    resumePositionMs = resumePositionMs,
                                                    initialVertical = isVertical,
                                                    sourceRoute = ScreenRoutes.History.route
                                                )
                                            }
                                        }
                                    }
    return { key, cid, cover, vertical ->
        if (stillOwned()) original(key, cid, cover, vertical)
    }
}
