package com.bilipai.desktop.ui

/**
 * Every user-facing text of the 界面布局 settings group (spec 3.7). The UX source hygiene suite exempts only files named
 * *Strings.kt from its no-Chinese rule, so the composable that shows the group spells nothing out.
 */
internal object DesktopWindowsUxSettingsStrings {
    const val TITLE = "界面布局"

    /**
     * The flags are read by the new rail, home feed, cards and player as those slices land. A row stays greyed out until its
     * consumer exists, so nothing is stored that no part of the program honours yet; the reset button works at all times.
     */
    const val NOTE = "布局设置立即应用；播放器默认保留旧版布局。"

    const val RAIL = "导航栏"
    const val RAIL_AUTO = "自动"
    const val RAIL_EXPANDED = "展开"
    const val RAIL_COLLAPSED = "折叠"
    const val RAIL_CLASSIC_TEXT = "经典文字"

    const val PLAYER = "播放器布局"
    const val PLAYER_DESKTOP = "桌面"
    const val PLAYER_LEGACY = "旧版底栏"

    const val DETAILS = "播放页详情面板"
    const val DETAILS_AUTO = "自动打开"
    const val DETAILS_OPEN = "打开面板"
    const val DETAILS_CLOSED = "收起面板"
    const val DETAILS_WIDTH = "详情面板宽度"
    const val DETAILS_WIDTH_AUTO = "自动宽度"
    const val DETAILS_WIDTH_RESET = "使用自动宽度"
    const val DETAILS_NOTE = "自动模式在进入宽屏播放页时打开；窗口空间不足或普通全屏时收起。宽度范围 320–560 dp。"
    fun width(dp: Int) = "$dp dp"

    const val HOME = "首页布局"
    const val HOME_DESKTOP = "桌面"
    const val HOME_CLASSIC = "经典"

    const val CARDS = "卡片右键菜单与悬停"
    const val CARDS_ON = "开启"
    const val CARDS_OFF = "关闭"

    const val RESET = "恢复默认界面布局"
}
