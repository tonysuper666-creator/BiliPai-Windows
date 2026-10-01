package com.android.purebilibili.core.store
enum class BottomProgressBehavior(
    val value: Int,
    val label: String,
    val description: String
) {
    ALWAYS_SHOW(0, "始终展示", "控件隐藏时始终显示底部细进度条"),
    ALWAYS_HIDE(1, "始终隐藏", "不显示底部细进度条"),
    ONLY_SHOW_FULLSCREEN(2, "仅全屏时展示", "仅横屏全屏且控件隐藏时显示"),
    ONLY_HIDE_FULLSCREEN(3, "仅全屏时隐藏", "非全屏且控件隐藏时显示");

    companion object {
        fun fromValue(value: Int): BottomProgressBehavior {
            return entries.find { it.value == value } ?: ALWAYS_HIDE
        }
    }
}

enum class PlaybackCompletionBehavior(val value: Int, val label: String) {
    CONTINUE_CURRENT_LOGIC(0, "自动连播"),
    STOP_AFTER_CURRENT(1, "播完暂停"),
    PLAY_IN_ORDER(2, "顺序播放"),
    REPEAT_ONE(3, "单个循环"),
    LOOP_PLAYLIST(4, "列表循环");

    companion object {
        fun fromValue(value: Int): PlaybackCompletionBehavior {
            return entries.find { it.value == value } ?: CONTINUE_CURRENT_LOGIC
        }
    }
}

