package com.android.purebilibili.feature.settings

// 搜索路径的分组名沿用桌面既有的分组词汇，与设置首页展示标题（上游场景名）解耦，
// 避免展示标题改名时把已发布的搜索路径一起改掉。
private fun settingsSearchPathGroupTitle(category: SettingsRootCategory): String = when (category) {
    SettingsRootCategory.APPEARANCE_THEME -> "外观与动画"
    SettingsRootCategory.PLAYBACK_QUALITY -> "播放设置"
    SettingsRootCategory.HOME_RECOMMENDATION -> "首页与动态"
    SettingsRootCategory.NAVIGATION_INTERACTION -> "导航布局"
    SettingsRootCategory.PRIVACY_PERMISSION -> "隐私与权限"
    SettingsRootCategory.STORAGE_BACKUP -> "下载与备份"
    SettingsRootCategory.PLUGINS_EXTENSIONS -> "插件中心"
    SettingsRootCategory.SYSTEM_ABOUT -> "帮助与关于"
    SettingsRootCategory.VIDEO_DECODER,
    SettingsRootCategory.COMMENTS_CONTENT,
    SettingsRootCategory.FULLSCREEN_GESTURE -> "播放设置"
    SettingsRootCategory.PLAYER_DIAGNOSTICS -> "帮助与关于"
    SettingsRootCategory.GLASS_ADVANCED -> "外观与动画"
    else -> category.title
}

internal fun settingItemPath(item: PlaybackSettingItem): String {
    val owner = settingsSearchPathGroupTitle(item.page)
    val page = when {
        item.page == SettingsRootCategory.GLASS_ADVANCED -> "玻璃高级调节"
        item.page == SettingsRootCategory.PLAYER_DIAGNOSTICS -> "问题排查"
        item.target == SettingsSearchTarget.ANIMATION -> "动画与触感"
        item.target == SettingsSearchTarget.PLAYBACK -> "播放设置"
        else -> null
    }?.takeUnless { it == owner }
    val section = when (item.sectionKey) {
        SettingsSearchFocusIds.PLAYBACK_DECODER -> "视频解码"
        SettingsSearchFocusIds.PLAYBACK_SPEED -> "播放速度"
        SettingsSearchFocusIds.PLAYBACK_MINI_PLAYER -> "小窗与后台"
        SettingsSearchFocusIds.PLAYBACK_NETWORK -> "网络与画质"
        SettingsSearchFocusIds.PLAYBACK_DATA_SAVER -> "省流量"
        SettingsSearchFocusIds.PLAYBACK_DEBUG -> "播放器"
        SettingsSearchFocusIds.PLAYBACK_INTERACTION -> "字幕与连播"
        SettingsSearchFocusIds.PLAYBACK_COMMENTS -> "互动与评论"
        SettingsSearchFocusIds.PLAYBACK_FULLSCREEN -> "全屏与手势"
        "appearance_display_mode" -> "界面与效果"
        "appearance_theme_color" -> "主题与色彩"
        "appearance_text_size" -> "文字与大小"
        "appearance_splash_icon" -> "开屏与图标"
        "appearance_language" -> "语言"
        "home_list" -> "首页与列表"
        "home_wallpaper" -> "首页壁纸"
        "home_recommendation" -> "内容与推荐流"
        "home_browsing" -> "浏览交互与手势"
        "home_options" -> "推荐与动态"
        "animation_page" -> "动画与振动"
        "animation_card" -> "卡片动画"
        "animation_glass" -> if (item.page == SettingsRootCategory.GLASS_ADVANCED) null else "液态玻璃与磨砂"
        "navigation_behavior" -> "导航行为"
        "navigation_top_tabs" -> "顶部标签"
        "navigation_tablet" -> "平板导航"
        "diagnostics_options" -> "应用日志"
        "help_options" -> if (item.settingId == "help.easter_egg_enabled") "使用与反馈" else "更新"
        else -> null
    }
    return listOfNotNull("设置", owner, page, section).joinToString(" / ")
}
