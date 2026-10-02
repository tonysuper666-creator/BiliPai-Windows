package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.core.util.BilibiliNavigationTargetParser

/** Stable AppNavigation's actual VIDEO_DETAIL callbacks. The physical stack,
 * Window back dispatcher, Home return owner and monotonic route key decorator
 * remain the installed Root instances. No parallel route or playback is created. */
internal fun desktopOriginalRootVideoActions(
    key: BiliPaiNavKey.VideoDetail,
    commands: DesktopOriginalRootRouteCommands,
    windowBack: () -> Unit,
    openBilibiliLink: (String) -> Unit,
    immersivePlaybackChanged: (Boolean) -> Unit,
): DesktopOriginalVideoHolderRouteActions = DesktopOriginalVideoHolderRouteActions(
    markReturning = { commands.markVideoReturning(key) },
    clearReturning = { commands.clearVideoReturning() },
    back = windowBack,
    home = { commands.homeFromVideo(key) },
    audio = { commands.push(BiliPaiNavKey.AudioMode(key.bvid, key.cid, key.resumePositionMs)) },
    search = { commands.push(BiliPaiNavKey.Search()) },
    searchKeyword = { keyword -> commands.push(BiliPaiNavKey.Search(keyword = keyword)) },
    openBilibiliLink = openBilibiliLink,
    video = { bvid, cid, cover -> commands.video(BiliPaiNavKey.VideoDetail(bvid = bvid,
        cid = cid, coverUrl = cover.orEmpty(), sourceRoute = "video/${key.bvid}")) },
    replaceVideo = { bvid, cid, cover, resume -> commands.replaceVideoDetail(key, bvid, cid, cover, resume) },
    up = { mid -> commands.push(BiliPaiNavKey.Space(mid)) },
    upWithVideo = { mid, bvid -> commands.push(BiliPaiNavKey.Space(mid, bvid)) },
    immersivePlaybackChanged = immersivePlaybackChanged,
    bgm = { bgm ->
        val musicId = bgm.musicId.ifBlank {
            (BilibiliNavigationTargetParser.parse(bgm.jumpUrl) as? BilibiliNavigationTarget.Music)?.musicId.orEmpty()
        }
        if (musicId.isNotBlank()) commands.push(BiliPaiNavKey.BgmDetail(musicId, cid = key.cid))
        else if (bgm.jumpUrl.isNotBlank()) commands.push(BiliPaiNavKey.Web(bgm.jumpUrl, "发现音乐"))
    },
)
