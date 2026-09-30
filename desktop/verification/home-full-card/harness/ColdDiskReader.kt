package com.bilipai.desktop.settings.productfullcardproof
import com.android.purebilibili.core.store.HomeDurationStyle
import com.bilipai.desktop.settings.DesktopHomeCardVisualPreferences
import com.bilipai.desktop.plugins.*
import java.nio.file.Path
fun main(args:Array<String>) {
    val settings=DesktopHomeCardVisualPreferences(DesktopPluginContext(DesktopPluginStore(Path.of(args[0])))).initialSettings()
    check(settings.showFullVideoCardContent&&settings.compactVideoStatsOnCover&&settings.showHomeUpAvatars&&settings.showHomeUpBadges)
    check(!settings.showHomePublishTime&&settings.homeCardDynamicTintEnabled&&!settings.homeCardFrostedGlassEnabled&&!settings.showOnlineCount)
    check(settings.homeDurationStyle==HomeDurationStyle.OVERLAY_TEXT_ONLY)
    println("PASS actual cold JVM original persisted visual keys ${Path.of(args[0]).parent.fileName}")
}
