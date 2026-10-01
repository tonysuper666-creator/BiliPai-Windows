package com.bilipai.desktop.ui
internal object DesktopOriginalHomeSettingConstants {
    const val DEFAULT_TOP_TAB_ORDER = "RECOMMEND,FOLLOW,POPULAR,LIVE,GAME"
    const val DEFAULT_TOP_TAB_VISIBLE = "RECOMMEND,FOLLOW,POPULAR,LIVE,GAME"
    const val MAX_TOP_TABS = 5
    const val DEFAULT_BOTTOM_BAR_ORDER = "HOME,DYNAMIC,HISTORY,LISTEN_VIDEO,PROFILE"
    const val DEFAULT_BOTTOM_BAR_VISIBLE_TABS = "HOME,DYNAMIC,HISTORY,LISTEN_VIDEO,PROFILE"
    object BottomBarLabelMode {
        const val SELECTED = 0 // 兼容 AppNavigation 的调用
        const val ICON_AND_TEXT = 0
        const val ICON_ONLY = 1
        const val TEXT_ONLY = 2
    }
    object TopTabLabelMode {
        const val ICON_AND_TEXT = 0
        const val ICON_ONLY = 1
        const val TEXT_ONLY = 2
    }
}
