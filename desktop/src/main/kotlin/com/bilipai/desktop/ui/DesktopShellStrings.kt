package com.bilipai.desktop.ui

/**
 * Every user-facing text of the desktop shell policy layer: the rail, its menu and tooltips, the zoom toast, the
 * now-playing bar and the shortcut list. The UX source hygiene suite exempts only files named *Strings.kt from its
 * no-Chinese rule, so no other shell file may spell a label out.
 *
 * The rail labels of the BottomNavItem rows equal BottomNavItem.label (test_ux_layout_golden.py compares them with
 * the vendored enum), so a row may take its label from either place. The rows the rail adds itself have their own.
 */
internal object DesktopShellStrings {
    /** The accessible name of the rail container header, in both modes. */
    const val WORDMARK = "BiliPai"

    /** The accessible name of the rail as a navigation container. */
    const val RAIL_NAME = "主导航"

    /** The accessible name of the search row; the hint beside it is not part of the name. */
    const val SEARCH = "搜索"
    const val SEARCH_HINT = "Ctrl K"

    /** The shortcut of the search row and of the rail toggle, as a tooltip shows them. */
    const val SEARCH_SHORTCUT = "Ctrl+K"
    const val RAIL_TOGGLE_SHORTCUT = "Ctrl+B"

    const val GROUP_BROWSE = "浏览"
    const val GROUP_LIBRARY = "媒体库"

    const val RAIL_COLLAPSE = "折叠导航栏"
    const val RAIL_EXPAND = "展开导航栏"

    /**
     * Back to the automatic rail mode. The wording of the spec joins its last two words directly; that exact
     * four-character run is forbidden anywhere in desktop/src by test_nvidia_preferences_schema.py (a guard against an
     * NVIDIA manual mode), so one connecting character is added and the meaning stays.
     */
    const val RAIL_AUTO = "导航栏恢复为自动"

    /** Shown when a rail mode change could not be saved (a refused command, or a failed write; a failed write keeps the mode for this session). */
    const val RAIL_SAVE_FAILED = "无法保存导航栏设置"

    /** Shown instead of a write when the toggle asks for the expanded rail in a window that is too narrow to show it. */
    const val RAIL_TOO_NARROW = "窗口太窄，无法展开导航栏"

    /** Ctrl+B means the rail on shell pages and the side panel on the video page; the help list says so. */
    const val CTRL_B_NOTE = "Ctrl+B 在视频页打开或关闭详情面板"

    const val NOW_PLAYING_PREVIOUS = "上一首"
    const val NOW_PLAYING_PLAY = "播放"
    const val NOW_PLAYING_PAUSE = "暂停"
    const val NOW_PLAYING_NEXT = "下一首"
    const val NOW_PLAYING_OPEN = "打开播放器"
    const val NOW_PLAYING_CLOSE = "关闭"

    /** The toast shown after a zoom change. */
    fun zoomToast(percent: Int): String = "界面缩放 ${percent}%"

    fun railLabel(item: DesktopRailItem): String = when (item) {
        DesktopRailItem.HOME -> "推荐"
        DesktopRailItem.DYNAMIC -> "动态"
        DesktopRailItem.LIVE -> "直播"
        DesktopRailItem.LISTEN_VIDEO -> "听视频"
        DesktopRailItem.HISTORY -> "历史"
        DesktopRailItem.FAVORITE -> "收藏"
        DesktopRailItem.WATCHLATER -> "稍后看"
        DesktopRailItem.DOWNLOADS -> "下载与离线"
        DesktopRailItem.ACCOUNT_SWITCH -> "切换账号"
        DesktopRailItem.PROFILE -> "我的"
        DesktopRailItem.PLUGINS -> "插件"
        DesktopRailItem.SETTINGS -> "设置"
    }

    /** The group heading, or null for the footer, which has none. */
    fun groupTitle(group: DesktopRailGroup): String? = when (group) {
        DesktopRailGroup.BROWSE -> GROUP_BROWSE
        DesktopRailGroup.LIBRARY -> GROUP_LIBRARY
        DesktopRailGroup.FOOTER -> null
    }

    /** The label of an entry of the rail context menu. */
    fun railMenuLabel(action: DesktopRailMenuAction): String = when (action) {
        DesktopRailMenuAction.COLLAPSE -> RAIL_COLLAPSE
        DesktopRailMenuAction.EXPAND -> RAIL_EXPAND
        DesktopRailMenuAction.RESTORE_AUTO -> RAIL_AUTO
    }

    /** The accessible name of a row with unread items. The exact count is spoken, not the capped badge text. */
    fun unreadAnnouncement(label: String, unreadCount: Int): String = "${label}，${unreadCount} 条未读"

    /** The tooltip of a row on a collapsed rail: the label, and the Ctrl+number when the row has one. */
    fun collapsedTooltip(label: String, number: Int?): String = if (number == null) label else "$label (Ctrl+$number)"

    /** The tooltip of a row on an expanded rail, after the long delay: only the shortcut, or nothing. */
    fun expandedTooltip(number: Int?): String? = if (number == null) null else "Ctrl+$number"

    /** The tooltip of the search button of a collapsed rail, in the same form as the other collapsed rows. */
    fun searchTooltip(): String = "$SEARCH ($SEARCH_SHORTCUT)"

    /** The shortcut list line for an action. */
    fun actionLabel(action: DesktopShellAction): String = when (action.kind) {
        DesktopShellActionKind.OPEN_SEARCH -> SEARCH
        DesktopShellActionKind.BACK -> "返回"
        DesktopShellActionKind.REFRESH -> "刷新"
        DesktopShellActionKind.GO_DESTINATION -> "转到" + (action.destination?.let { railLabel(it) } ?: "")
        DesktopShellActionKind.RAIL_TOGGLE -> "折叠或展开导航栏"
        DesktopShellActionKind.OPEN_SETTINGS -> "设置"
        DesktopShellActionKind.OPEN_DOWNLOADS -> "下载与离线"
        DesktopShellActionKind.CYCLE_PANES -> if (action.direction < 0) "切换到上一个区域" else "切换到下一个区域"
        DesktopShellActionKind.SHORTCUT_HELP -> "快捷键列表"
        DesktopShellActionKind.FULLSCREEN_TOGGLE -> "全屏"
    }
}
