package com.android.purebilibili.feature.video.danmaku
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.bilipai.desktop.danmaku.DanmakuSettings

internal fun desktopOriginalLiveDanmakuAllows(item:LiveDanmakuItem,settings:DanmakuSettings):Boolean {
            val typeFilter = DanmakuTypeFilterSettings(
                allowScroll = settings.allowScroll,
                allowTop = settings.allowTop,
                allowBottom = settings.allowBottom,
                allowColorful = settings.allowColorful,
                allowSpecial = settings.allowSpecial
            )
            if (item.isSuperChat && !settings.allowSpecial) {
                return false
            }
            if (!item.isSuperChat && !shouldDisplayStandardDanmaku(item.mode, item.color, typeFilter)) {
                return false
            }
            val userHash = item.uid.takeIf { it > 0L }?.toString().orEmpty()
            if (shouldBlockDanmakuByRules(item.text, settings.blockedRules + settings.blockedKeywords, userHash)) {
                return false
            }
            return true
}
