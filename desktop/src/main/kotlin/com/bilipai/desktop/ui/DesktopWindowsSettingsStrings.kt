package com.bilipai.desktop.ui

import com.android.purebilibili.feature.settings.SettingsRootCategory

/**
 * The texts that the redesigned settings chrome adds or moves (spec 3.7, slice X3): the tree header, the rail titles, the
 * empty page and the select placeholder. The labels of the settings pages themselves stay with their pages.
 */
internal object DesktopWindowsSettingsStrings {
    /** The tree header title when the page names none (the root, the search page). Also the title the fixtures know. */
    const val TREE_TITLE = "Windows 设置"

    /** The accessible name of the one Back action of the tree header. The four settings fixtures click it by this name. */
    const val BACK = "返回"

    /** The search action on the right of the tree header. */
    const val SEARCH = "搜索设置"

    /** Shown on the root page after Back from the first page of a visit (the rail is the only content). */
    const val EMPTY_HINT = "从左侧选择一个设置分类。"

    /** The value of a select row whose stored value matches none of its choices. */
    const val UNSELECTED = "未选择"

    const val RAIL_PLAYBACK = "播放与音频"
    const val RAIL_HOME = "首页与推荐"
    const val RAIL_APPEARANCE = "外观与显示"
    const val RAIL_STORAGE = "缓存与备份"
    const val RAIL_PRIVACY = "隐私与屏蔽"
    const val RAIL_PLUGINS = "插件与扩展"
    const val RAIL_SYSTEM = "更新与诊断"

    /** The group on the home page that carries the recommendation fields of the category page it replaces. */
    const val HOME_RECOMMENDATION_GROUP = "推荐与刷新"

    /** The title of a rail category, or null for a category the Windows settings do not list. */
    fun railTitle(category: SettingsRootCategory): String? = when (category) {
        SettingsRootCategory.PLAYBACK_QUALITY -> RAIL_PLAYBACK
        SettingsRootCategory.HOME_RECOMMENDATION -> RAIL_HOME
        SettingsRootCategory.APPEARANCE_THEME -> RAIL_APPEARANCE
        SettingsRootCategory.STORAGE_BACKUP -> RAIL_STORAGE
        SettingsRootCategory.PRIVACY_PERMISSION -> RAIL_PRIVACY
        SettingsRootCategory.PLUGINS_EXTENSIONS -> RAIL_PLUGINS
        SettingsRootCategory.SYSTEM_ABOUT -> RAIL_SYSTEM
        else -> null
    }
}
