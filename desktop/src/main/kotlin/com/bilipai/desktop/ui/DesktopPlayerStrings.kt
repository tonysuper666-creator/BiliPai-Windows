package com.bilipai.desktop.ui

/**
 * The texts the player policies and the accessible-name set need (spec 4.5, 4.9, 4.13, 7): accessible names, tooltips, the
 * visible dock and top bar labels, on-screen feedback, states, and the quality, speed, subtitle and layout labels of the flyouts
 * and the context menu. The remaining texts of the flyout pages (spec 4.9: the subtitle segments and tracks, the danmaku, chapter,
 * picture and share rows, the context menu rows) are added by the slice that builds each page (V5). Only files named *Strings.kt
 * may spell Chinese (UX hygiene suite). The accessible names of spec 7 are split with DesktopShellStrings, which holds the shell ones.
 */
internal object DesktopPlayerStrings {
    // Accessible names (spec 7): fixtures and the Java Access Bridge probe look controls up by them.
    const val BACK = "返回"
    const val PLAY = "播放"
    const val PAUSE = "暂停"
    const val FULLSCREEN = "全屏"
    const val EXIT_FULLSCREEN = "退出全屏"
    const val DETAILS = "详情"
    const val CLOSE_DETAILS = "关闭详情"
    const val DETAILS_PANEL = "视频详情面板"
    const val MORE_ACTIONS = "更多播放操作"
    const val INTRODUCTION_AND_SETTINGS = "简介、分P与播放设置"
    const val NVIDIA_TRIGGER = "NVIDIA 增强详情"
    const val NVIDIA_TITLE = "NVIDIA 自动增强"
    const val MINI_PLAYER = "浮窗"

    // Visible dock and top bar labels (spec 4.5): the accessible names above stay the ones the fixtures look up.
    const val LABEL_PIN = "置顶"
    const val LABEL_DANMAKU = "弹"
    const val LABEL_SUBTITLE = "字幕"
    const val LABEL_SPEED = "倍速"
    const val LABEL_SIDE_PANEL = "侧栏"
    const val SPONSOR_SKIP = "跳过片头 ›"

    // Tooltips: the name, then the key in full-width brackets (spec 4.5).
    const val TOOLTIP_PLAY = "播放（空格）"
    const val TOOLTIP_MUTE = "静音（M）"
    const val TOOLTIP_FULLSCREEN = "全屏（F）"
    const val TOOLTIP_MINI = "小窗（P）"
    const val TOOLTIP_CINEMA = "影院模式（T）"
    const val TOOLTIP_DANMAKU = "弹幕（D）"
    const val TOOLTIP_SUBTITLE = "字幕（C）"
    const val TOOLTIP_DETAILS = "详情（Ctrl+B）"

    // On-screen feedback (spec 4.5).
    const val MUTED = "已静音"
    const val SCREENSHOT_SAVED = "截图已保存"
    const val WINDOW_PINNED = "已置顶窗口"

    fun danmakuToggle(on: Boolean): String = if (on) "弹幕：已开启" else "弹幕：已关闭"

    fun subtitleToggle(label: String?): String = "字幕：" + (label ?: "已关闭")

    fun volumePill(percent: Int): String = "音量 $percent%"

    fun seekChip(deltaSeconds: Int): String = (if (deltaSeconds < 0) "-" else "+") + kotlin.math.abs(deltaSeconds) + " 秒"

    // States (spec 7).
    const val BUFFERING = "缓冲中…"
    const val REPLAY = "重新播放"
    const val ERROR_TITLE = "播放出错"
    const val RETRY = "重试"
    const val PIP_ACTIVE = "正在小窗播放"
    const val PIP_RETURN = "返回播放器"
    const val AUDIO_ONLY = "仅音频播放"

    // Subtitle, speed and layout labels of the flyouts and the context menu (spec 4.9, 4.13).
    const val SUBTITLE_NONE_TOOLTIP = "此视频无字幕"
    const val SPEED_EDIT_LIST = "编辑倍速列表…"
    const val SWITCH_TO_CLASSIC_LAYOUT = "切换到经典播放器布局"

    // Quality flyout (spec 4.9).
    const val QUALITY_AUTO = "最高可用（自动）"
    const val QUALITY_NEEDS_LOGIN = "需登录"
    const val QUALITY_NEEDS_VIP = "大会员"
    const val QUALITY_UNAVAILABLE = "不可用"
    const val QUALITY_SWITCHING = "切换中"
    const val QUALITY_PILL_FALLBACK = "画质"

    /** The secondary text of a quality row, or null when it needs none. */
    fun qualityHint(state: DesktopPlayerQualityRowState): String? = when (state) {
        DesktopPlayerQualityRowState.NEEDS_LOGIN -> QUALITY_NEEDS_LOGIN
        DesktopPlayerQualityRowState.NEEDS_VIP -> QUALITY_NEEDS_VIP
        DesktopPlayerQualityRowState.UNAVAILABLE -> QUALITY_UNAVAILABLE
        DesktopPlayerQualityRowState.CURRENT, DesktopPlayerQualityRowState.SELECTABLE -> null
    }

    /** The pill text: the live quality label, or the switching text while a switch is under way. */
    fun qualityPill(resolvedLabel: String?, switching: Boolean): String =
        if (switching) QUALITY_SWITCHING else resolvedLabel ?: QUALITY_PILL_FALLBACK
}
