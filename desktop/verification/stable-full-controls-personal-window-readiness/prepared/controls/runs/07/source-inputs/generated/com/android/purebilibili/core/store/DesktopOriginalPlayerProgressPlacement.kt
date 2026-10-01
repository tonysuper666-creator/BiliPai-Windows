package com.android.purebilibili.core.store
enum class PlayerProgressPlacement(
    val value: Int,
    val label: String
) {
    ABOVE_CONTROLS(0, "控制栏上方"),
    BOTTOM_EDGE(1, "视频最底部");

    companion object {
        fun fromValue(value: Int): PlayerProgressPlacement {
            return entries.find { it.value == value } ?: ABOVE_CONTROLS
        }
    }
}

