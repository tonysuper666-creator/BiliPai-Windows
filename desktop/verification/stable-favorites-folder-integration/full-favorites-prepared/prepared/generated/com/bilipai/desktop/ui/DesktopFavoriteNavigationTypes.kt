package com.bilipai.desktop.ui
object DesktopFavoriteNavigationTypes {
    enum class BottomBarVisibilityMode(val value: Int, val label: String, val description: String) {
        SCROLL_HIDE(0, "向下浏览时隐藏", "浏览更下方内容时隐藏，向上返回时显示"),
        ALWAYS_VISIBLE(1, "始终显示", "底栏始终可见"),
        ALWAYS_HIDDEN(2, "永久隐藏", "完全隐藏底栏");
        
        companion object {
            fun fromValue(value: Int): BottomBarVisibilityMode = entries.find { it.value == value } ?: ALWAYS_VISIBLE
        }
    }
}
