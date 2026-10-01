package com.android.purebilibili.core.store
enum class CommonListHeaderCollapseMode(
    val value: Int,
    val label: String,
    val description: String
) {
    ALWAYS_VISIBLE(0, "始终显示", "历史记录和收藏夹等通用列表的顶部栏保持展开"),
    SHOW_ON_REVERSE_SCROLL(1, "上滑时显示", "向下浏览时折叠，反向上滑时恢复"),
    SHOW_AT_TOP_ONLY(2, "仅回顶显示", "向下浏览时折叠，仅回到列表顶部时恢复");

    companion object {
        fun fromValue(value: Int): CommonListHeaderCollapseMode =
            entries.find { it.value == value } ?: SHOW_ON_REVERSE_SCROLL
    }
}

enum class HomeHeaderBlurMode(val value: Int, val label: String) {
    FOLLOW_PRESET(0, "跟随预设"),
    ALWAYS_ON(1, "始终开启"),
    ALWAYS_OFF(2, "始终关闭");

    companion object {
        fun fromValue(value: Int): HomeHeaderBlurMode {
            return entries.find { it.value == value } ?: FOLLOW_PRESET
        }
    }
}

internal fun resolveHomeHeaderBlurEnabled(
    mode: HomeHeaderBlurMode,
): Boolean {
    return when (mode) {
        HomeHeaderBlurMode.FOLLOW_PRESET -> true
        HomeHeaderBlurMode.ALWAYS_ON -> true
        HomeHeaderBlurMode.ALWAYS_OFF -> false
    }
}

internal fun resolveHomeHeaderBlurModePreference(
    rawMode: Int?,
    legacyEnabled: Boolean?
): HomeHeaderBlurMode {
    return if (rawMode != null) {
        HomeHeaderBlurMode.fromValue(rawMode)
    } else if (legacyEnabled == false) {
        HomeHeaderBlurMode.ALWAYS_OFF
    } else {
        HomeHeaderBlurMode.FOLLOW_PRESET
    }
}
