package com.bilipai.desktop.ui

/**
 * The texts of the shortcut lists of the Tips page (spec 3.7): the group titles, the notes, the key names and the label of
 * every player action. The labels of the shell actions are DesktopShellStrings.actionLabel. The key names are the chord names
 * of desktop-ux-contract.json ("shortcuts"); a name that has no entry here is shown as it is.
 */
internal object DesktopWindowsTipsStrings {
    const val SHELL_GROUP = "快捷键：窗口与导航"
    const val PLAYER_GROUP = "快捷键：播放器"
    const val MOUSE_GROUP = "右键菜单"

    /**
     * Shown once above the three shortcut groups. The lists are the planned key tables and menus, which the new desktop
     * interface enables slice by slice, so the note names the keys that work today and says the rest is not on yet.
     */
    const val NOTE = "以下是新桌面界面计划使用的快捷键和右键菜单，会随功能上线逐步启用，目前并非每一项都可用。播放器里现在可用的按键：空格（播放 / 暂停）、Esc（退出全屏或返回）、F11（全屏）。"
    const val RIGHT_CLICK_HINT = "新界面启用后，在首页视频卡片或播放画面上点击右键可打开快捷菜单；也可按菜单键或 Shift+F10。"

    /** The name of a key as the list shows it: arrows and punctuation as their symbols, two keys by their Chinese names. */
    fun keyName(key: String): String = when (key) {
        "Left" -> "←"
        "Right" -> "→"
        "Up" -> "↑"
        "Down" -> "↓"
        "Comma" -> ","
        "Period" -> "."
        "Slash" -> "/"
        "LeftBracket" -> "["
        "RightBracket" -> "]"
        "Space" -> "空格"
        "Menu" -> "菜单键"
        else -> key
    }

    /** The label of a player action. The kinds are matched without a fallback, so a new kind cannot be left without a label. */
    fun playerActionLabel(action: DesktopPlayerAction): String = when (action.kind) {
        DesktopPlayerActionKind.PLAY_PAUSE -> "播放 / 暂停"
        DesktopPlayerActionKind.SEEK -> if (action.arg < 0) "快退 ${-action.arg} 秒" else "快进 ${action.arg} 秒"
        DesktopPlayerActionKind.CHAPTER -> if (action.arg < 0) "上一章节" else "下一章节"
        DesktopPlayerActionKind.VOLUME -> "音量 " + (if (action.arg < 0) "-" else "+") + kotlin.math.abs(action.arg) + "%"
        DesktopPlayerActionKind.MUTE -> "静音"
        DesktopPlayerActionKind.FULLSCREEN_TOGGLE -> "全屏"
        DesktopPlayerActionKind.ESC_CHAIN -> "关闭弹层，退出全屏"
        DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA -> "详情面板 / 影院模式"
        DesktopPlayerActionKind.MINI_PLAYER -> "小窗"
        DesktopPlayerActionKind.VIDEO_INFO -> "视频信息"
        DesktopPlayerActionKind.SUBTITLE_TOGGLE -> "字幕开关"
        DesktopPlayerActionKind.SUBTITLE_FLYOUT -> "字幕设置"
        DesktopPlayerActionKind.DANMAKU_TOGGLE -> "弹幕开关"
        DesktopPlayerActionKind.SCREENSHOT -> "截图"
        DesktopPlayerActionKind.PART -> if (action.arg < 0) "上一个分P或剧集" else "下一个分P或剧集"
        DesktopPlayerActionKind.SEEK_PERCENT -> "跳到 0% 至 90%"
        DesktopPlayerActionKind.SEEK_START -> "跳到开头"
        DesktopPlayerActionKind.SEEK_END -> "跳到结尾"
        DesktopPlayerActionKind.FRAME_STEP -> if (action.arg < 0) "上一帧" else "下一帧"
        DesktopPlayerActionKind.SPEED_STEP -> if (action.arg < 0) "倍速降一档" else "倍速升一档"
        DesktopPlayerActionKind.SPEED_SET -> "倍速 ${action.arg}×"
        DesktopPlayerActionKind.CONTEXT_MENU -> "打开右键菜单"
        DesktopPlayerActionKind.SHORTCUT_HELP -> "快捷键列表"
    }
}
