package com.bilipai.desktop.audio

import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.core.util.BilibiliNavigationTargetParser

/** Full original AppNavigation.onBgmClick decision, with Root route destinations. */
internal fun resolveOriginalBgmMusicTarget(bgm: BgmInfo, cid: Long): DesktopBgmMusicTarget? {
    val musicId = bgm.musicId.ifBlank {
        (BilibiliNavigationTargetParser.parse(bgm.jumpUrl) as? BilibiliNavigationTarget.Music)?.musicId.orEmpty()
    }
    if (musicId.isNotBlank()) {
        return DesktopBgmMusicTarget.Detail(musicId, cid = cid)
    } else if (bgm.jumpUrl.isNotBlank()) {
        return DesktopBgmMusicTarget.Web(bgm.jumpUrl)
    }
    return null
}
