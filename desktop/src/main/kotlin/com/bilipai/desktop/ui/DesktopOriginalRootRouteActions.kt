package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.HomeVideoClickRequest
import com.android.purebilibili.navigation.HomeNavigationTarget
import com.android.purebilibili.navigation.HomeVideoNavigationIntent
import com.android.purebilibili.navigation.resolveHomeNavigationTarget
import com.android.purebilibili.navigation.resolveHomeVideoNavigationIntent
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** Root's sole physical stack and existing playback admission. No second route DTO or account
 * authority. The complete typed video key is the handoff, including CID/portrait/return context. */
internal interface DesktopOriginalRootRouteCommands {
    fun push(key: BiliPaiNavKey): Boolean
    fun back(): Boolean
    fun articleBack(article: BiliPaiNavKey.ArticleDetail, useSharedReturn: Boolean): Boolean
    fun containsEntry(key: BiliPaiNavKey): Boolean
    fun home(): Boolean
    fun video(key: BiliPaiNavKey.VideoDetail)
    fun videoRoute(route: String, sourceRoute: String)
    fun replaceVideoDetail(current: BiliPaiNavKey.VideoDetail, bvid: String, cid: Long,
        cover: String, resumePositionMs: Long): Boolean
    fun homeFromVideo(current: BiliPaiNavKey.VideoDetail): Boolean
    fun markVideoReturning(current: BiliPaiNavKey.VideoDetail): Boolean
    fun clearVideoReturning(): Boolean
}

/** Stable AppNavigation HOME caller (2330–2408), adapted only to required Root effects.
 * Null account switch is the original disabled setting, never an unavailable mounted action. */
internal fun desktopOriginalRootHomeNavigation(
    commands: DesktopOriginalRootRouteCommands,
    logout: (() -> Unit)?,
    accountSwitcher: (() -> Unit)?,
): DesktopOriginalHomeNavigation = DesktopOriginalHomeNavigation(
    onVideoClick = { request ->
        when (val target = resolveHomeNavigationTarget(request)) {
            is HomeNavigationTarget.Video -> {
                val intent = resolveHomeVideoNavigationIntent(request)
                if (intent != null) commands.video(intent.desktopRootVideoKey())
                else commands.videoRoute(target.route, request.sourceRoute ?: "home")
            }
            is HomeNavigationTarget.DynamicDetail -> commands.push(BiliPaiNavKey.DynamicDetail(target.dynamicId))
            null -> Unit // Original invalid/empty request; no mounted route fallback.
        }
    },
    onLogout = logout,
    onAccountSwitchClick = accountSwitcher,
    onAvatarClick = { commands.push(BiliPaiNavKey.Login) },
    onProfileClick = { commands.push(BiliPaiNavKey.Profile) },
    onSettingsClick = { commands.push(BiliPaiNavKey.Settings) },
    onSearchClick = { commands.push(BiliPaiNavKey.Search()) },
    onDynamicClick = { commands.push(BiliPaiNavKey.Dynamic) },
    onHistoryClick = { commands.push(BiliPaiNavKey.History) },
    onPartitionClick = { commands.push(BiliPaiNavKey.Partition) },
    onWeeklySeriesClick = { commands.push(BiliPaiNavKey.WeeklySeries()) },
    onFavoriteClick = { commands.push(BiliPaiNavKey.Favorite) },
    onLikedVideosClick = { commands.push(BiliPaiNavKey.LikedVideos()) },
    onLiveListClick = { commands.push(BiliPaiNavKey.LiveList) },
    onLiveSearchClick = { commands.push(BiliPaiNavKey.LiveSearch) },
    onLiveAreaClick = { commands.push(BiliPaiNavKey.LiveArea) },
    onLiveFollowingClick = { commands.push(BiliPaiNavKey.LiveFollowing) },
    onWatchLaterClick = { commands.push(BiliPaiNavKey.WatchLater) },
    onDownloadClick = { commands.push(BiliPaiNavKey.DownloadList) },
    onInboxClick = { commands.push(BiliPaiNavKey.Inbox) },
    onStoryClick = { commands.push(BiliPaiNavKey.Story()) },
    onPluginsClick = { commands.push(BiliPaiNavKey.PluginsSettings()) },
    partitionVideoSourceRoute = "partition",
    onPartitionVideoClick = { video -> commands.video(BiliPaiNavKey.VideoDetail(
        bvid = video.bvid, cid = video.cid, coverUrl = video.pic,
        initialVertical = video.isVertical, sourceRoute = "partition")) },
    onLiveClick = { roomId, title, uname -> commands.push(BiliPaiNavKey.Live(
        roomId = roomId.toString(), title = title, uname = uname)) },
    onBangumiClick = { type -> commands.push(BiliPaiNavKey.Bangumi(type)) },
    onCategoryClick = { tid, name -> commands.push(BiliPaiNavKey.Category(tid, name)) },
    onLiveAreaDetailClick = { parent, area, title -> commands.push(BiliPaiNavKey.LiveAreaDetail(parent, area, title)) },
    onBangumiSeasonClick = { season -> commands.push(BiliPaiNavKey.BangumiDetail(season)) },
    onBangumiEpisodeClick = { season, episode -> commands.push(BiliPaiNavKey.BangumiDetail(season, episode)) },
    onSpaceClick = { mid -> commands.push(BiliPaiNavKey.Space(mid)) },
)

internal fun HomeVideoNavigationIntent.desktopRootVideoKey() = BiliPaiNavKey.VideoDetail(
    bvid = bvid, cid = cid, coverUrl = coverUrl, autoPortrait = true,
    initialVertical = isVerticalVideo, sourceRoute = sourceRoute ?: "home")

/** Stable AppNavigation PROFILE caller (2851–2904). The successful account callbacks are
 * required actual Root lifecycle events; after an epoch change they must refresh the successor
 * Home owner, rather than trying to mutate the retired one. */
internal fun desktopOriginalRootProfileNavigation(
    commands: DesktopOriginalRootRouteCommands,
    logoutSucceeded: () -> Unit,
    accountSwitchSucceeded: () -> Unit,
): DesktopProfileNavigation = DesktopProfileNavigation(
    onBack = { commands.home() },
    onGoToLogin = { commands.push(BiliPaiNavKey.Login) },
    onLogoutSuccess = logoutSucceeded,
    onAccountSwitchSuccess = accountSwitchSucceeded,
    onSettingsClick = { commands.push(BiliPaiNavKey.Settings) },
    onSearchClick = { commands.push(BiliPaiNavKey.Search()) },
    onHistoryClick = { commands.push(BiliPaiNavKey.History) },
    onFavoriteClick = { commands.push(BiliPaiNavKey.Favorite) },
    onSubscriptionClick = { commands.push(BiliPaiNavKey.FavoriteSubscribed) },
    onFavoriteFolderClick = { id, mid, title -> commands.push(BiliPaiNavKey.SeasonSeriesDetail(
        type = "favorite", id = id, mid = mid, title = title)) },
    onFollowingClick = { mid -> commands.push(BiliPaiNavKey.Following(mid)) },
    onDownloadClick = { commands.push(BiliPaiNavKey.DownloadList) },
    onWatchLaterClick = { commands.push(BiliPaiNavKey.WatchLater) },
    onInboxClick = { commands.push(BiliPaiNavKey.Inbox) },
    onVideoClick = { bvid -> commands.video(BiliPaiNavKey.VideoDetail(bvid)) },
    onBangumiClick = { season, episode ->
        if (season > 0L || episode > 0L) {
            if (episode > 0L) commands.push(BiliPaiNavKey.BangumiPlayer(season, episode))
            else commands.push(BiliPaiNavKey.BangumiDetail(season, episode))
        }
    },
    onBangumiMoreClick = { commands.push(BiliPaiNavKey.Bangumi(1)) },
)
