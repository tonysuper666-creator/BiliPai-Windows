package com.bilipai.desktop.ui
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.*

/** Actual Root entry-scoped original AppNavigation message actions. */
internal val LocalDesktopOriginalMessageLinkNavigation = staticCompositionLocalOf<(String) -> Unit> {
    error("Original message links require the actual retained Root route")
}

internal fun desktopOriginalOpenMessageLink(rawLink: String, commands: DesktopOriginalRootRouteCommands, sourceRoute: String) {
    when (val action = resolveMessageLinkNavigationAction(rawLink)) {
        is MessageLinkNavigationAction.Video -> {
            commands.video(BiliPaiNavKey.VideoDetail(action.videoId, 0L, "", sourceRoute=sourceRoute))
        }
        is MessageLinkNavigationAction.CommentDetail -> {
            commands.push(
                BiliPaiNavKey.CommentDetail(
                    oid = action.oid,
                    rootId = action.rootReplyId,
                    targetId = action.targetReplyId,
                    type = action.businessId,
                    enterUri = action.enterUri
                )
            )
        }
        is MessageLinkNavigationAction.VideoComment -> {
            commands.videoRoute(
                route = VideoRoute.createRoute(
                    bvid = action.videoId,
                    cid = 0L,
                    coverUrl = "",
                    commentRootRpid = action.rootReplyId,
                    commentTargetRpid = action.targetReplyId
                ),
                sourceRoute = sourceRoute
            )
        }
        is MessageLinkNavigationAction.Dynamic -> {
            commands.push(BiliPaiNavKey.DynamicDetail(action.dynamicId))
        }
        is MessageLinkNavigationAction.DynamicComment -> {
            commands.push(
                BiliPaiNavKey.DynamicDetail(
                    dynamicId = action.dynamicId,
                    commentRootRpid = action.rootReplyId,
                    commentTargetRpid = action.targetReplyId
                )
            )
        }
        is MessageLinkNavigationAction.Space -> {
            commands.push(BiliPaiNavKey.Space(action.mid))
        }
        is MessageLinkNavigationAction.Live -> {
            commands.push(BiliPaiNavKey.Live(roomId = action.roomId.toString()))
        }
        is MessageLinkNavigationAction.BangumiSeason -> {
            commands.push(
                BiliPaiNavKey.BangumiDetail(
                    seasonId = action.seasonId,
                    mediaId = action.mediaId
                )
            )
        }
        is MessageLinkNavigationAction.BangumiEpisode -> {
            commands.push(BiliPaiNavKey.BangumiDetail(seasonId = 0L, epId = action.epId))
        }
        is MessageLinkNavigationAction.Music -> {
            ScreenRoutes.createMusicRoute(action.musicId)?.let { commands.push(legacyRouteToBiliPaiNavKey(it)) }
                ?: commands.push(BiliPaiNavKey.Web(rawLink))
        }
        is MessageLinkNavigationAction.Article -> {
            commands.push(BiliPaiNavKey.ArticleDetail(action.articleId))
        }
        is MessageLinkNavigationAction.Web -> {
            val url = action.url.trim()
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                commands.push(BiliPaiNavKey.Web(url))
            }
        }
    }
}
