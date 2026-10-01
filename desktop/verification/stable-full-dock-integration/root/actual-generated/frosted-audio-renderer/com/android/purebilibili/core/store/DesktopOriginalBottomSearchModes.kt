package com.android.purebilibili.core.store

enum class BottomBarSearchAutoExpandMode(val value: Int, val label: String) {
    EXPAND_WHEN_SCROLLING_DOWN(0, "下滑展开"),
    EXPAND_AT_HOME_TOP(1, "顶部展开"),
    DISABLED(2, "不自动展开");

    companion object {
        fun fromValue(value: Int): BottomBarSearchAutoExpandMode =
            entries.find { it.value == value } ?: EXPAND_AT_HOME_TOP
    }
}

enum class BottomBarSearchLayoutMode(val value: Int, val label: String) {
    FULL_DOCK(0, "完整底栏"),
    HOME_AND_SEARCH(1, "首页与搜索");

    companion object {
        fun fromValue(value: Int): BottomBarSearchLayoutMode =
            entries.find { it.value == value } ?: FULL_DOCK
    }
}
