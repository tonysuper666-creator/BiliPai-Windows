# 设置接入上游原文：两栏映射、手机专属项与剩余文件（TON-1115，第二批）

本文是第三批（收尾）的输入。数据来自 `desktop/upstream-core/m4d/settings-target-placement.tsv` 和 `settings-item-placement.tsv`；`desktop/tools/ux/test_ux_settings_item_placement.py` 守着这两张表和合并后的上游一致。

## 1. 两栏壳接入上游设置页：上游分区/条目 → 桌面树形分类

### 机制：上游新增的条目怎么自动落进对应分区

1. **新增 `SettingsSearchTarget`**：上游 `resolveSettingsRootCategoryForSearchTarget` 是没有 `else` 的穷尽 `when`，上游自己必须给每个 target 一个 `SettingsRootCategory`。桌面 `desktopSettingsPageCategory` 直接调用它，再经 `canonicalSettingsRootCategory` 归并，所以新 target 落进哪个分区由上游决定；该分区在桌面栏目里就自动出现在对应栏目下，不在栏目里的（手机导航）就不显示。
2. **新增设置条目**（`PlaybackSettingItem`）：条目带 `page`（上游分区），同样经 `canonicalSettingsRootCategory` 得到桌面栏目。除下面「玻璃高级调节 → 外观」这一个人工改道，不需要桌面代码跟着改。
3. **守卫**：上游合并后如果出现表里没有的 target 或条目（或表里有上游已删除的），`test_ux_settings_item_placement.py` 失败，逼人给新项做一次「可用 / 隐藏 / 不可用 / 被代替」的决定。它同时核对：可用项的桌面栏目与上游规则一致且在栏目列表里；树里有详情渲染的 target 恰是 `DesktopSettingsTree` 详情分支；隐藏项必须写原因。
4. **页面内部的行**：第三批接入的是上游页面（`SettingsSections` 等）本身，页面里新增的行随上游文件自动出现；桌面树只负责「栏目 → 页面」和「target → 详情」。
5. 已知缺口：树里没有渲染的 target 现在会显示「此入口不属于当前 Windows 设置」。第三批要么给它接上游页面，要么在 `settings-target-placement.tsv` 里改成隐藏并写原因。

### 栏目 ← 上游 target（`SettingsSearchTarget`）

桌面栏目共 7 个：播放、首页与推荐、外观、下载与备份、隐私与权限、插件、帮助与关于（见 `DESKTOP_SETTINGS_RAIL_CATEGORIES`）。上游的「导航布局」分区（底栏、首页分类栏、平板侧栏）不在栏目里。

| 桌面栏目 | 上游 target | 落点 |
|---|---|---|
| 播放（`PLAYBACK_QUALITY`） | `PLAYBACK_QUALITY`、`FULLSCREEN_GESTURE`、`INTERACTION_COMMENT`、`PLAYBACK`（详情） | 带「详情」的在树里有渲染，其余落到栏目页 |
| 首页与推荐（`HOME_RECOMMENDATION`） | `HOME_FEED`（详情） | 带「详情」的在树里有渲染，其余落到栏目页 |
| 外观（`APPEARANCE_THEME`） | `INTERFACE_THEME`、`APPEARANCE`（详情） | 带「详情」的在树里有渲染，其余落到栏目页 |
| 下载与备份（`STORAGE_BACKUP`） | `DATA_BACKUP`、`SETTINGS_SHARE`（详情）、`WEBDAV_BACKUP`（详情）、`DOWNLOAD_PATH`（详情）、`IMAGE_SAVE_PATH`（详情）、`CLEAR_CACHE`（详情） | 带「详情」的在树里有渲染，其余落到栏目页 |
| 隐私与权限（`PRIVACY_PERMISSION`） | `PRIVACY_PERMISSION`、`SPACE_PRIVACY`、`LOGIN_ACTIVITY`、`MESSAGE_NOTIFICATION`（详情）、`BLOCKED_LIST`（详情） | 带「详情」的在树里有渲染，其余落到栏目页 |
| 插件（`PLUGINS_EXTENSIONS`） | `PLUGINS`（详情） | 带「详情」的在树里有渲染，其余落到栏目页 |
| 帮助与关于（`SYSTEM_ABOUT`） | `DIAGNOSTICS`、`ABOUT_SUPPORT`、`EXPORT_LOGS`、`OPEN_SOURCE_LICENSES`（详情）、`OPEN_SOURCE_HOME`、`VIEW_RELEASE_NOTES`、`TIPS`（详情）、`DONATE`、`TELEGRAM`、`TWITTER`、`DISCLAIMER` | 带「详情」的在树里有渲染，其余落到栏目页 |

### 不落进栏目的 target

| target | 处理 | 原因 |
|---|---|---|
| `NAVIGATION` | hidden | 手机底栏与平板侧栏；桌面固定使用侧栏（S1） |
| `ANIMATION` | replaced | 动画与玻璃项并入桌面「外观」页（target APPEARANCE）；接入上游搜索目录时，指向 ANIMATION 的结果要改指 APPEARANCE |
| `BOTTOM_BAR` | hidden | 手机底栏设置 |
| `PERMISSION` | hidden | Android 运行时权限页（相机、通知、存储等）；桌面没有对应权限模型 |
| `NEARBY_HANDOFF` | hidden | 设备接力：上游自己也由 NearbyHandoffFeatureGate 隐藏；依赖 feature/nearby（TON-1074） |
| `CHECK_UPDATE` | replaced | 桌面更新走自有发布渠道（TON-1013），不检查上游 GitHub |
| `REPLAY_ONBOARDING` | hidden | 手机首次启动引导 |
| `OPEN_LINKS` | hidden | Android 应用链接的默认打开方式 |

### 条目数（上游搜索目录，含 11 个振动场景）

| 桌面栏目 | 可用条目 |
|---|---|
| 播放 | 70 |
| 首页与推荐 | 31 |
| 外观 | 33 |
| 下载与备份 | 0 |
| 隐私与权限 | 12 |
| 插件 | 0 |
| 帮助与关于 | 7 |

合计 226 条：可用 153，其余见下一节。

玻璃高级调节（`glass.*`，上游分区 `GLASS_ADVANCED`，归并后落在不在栏目里的「导航布局」）人工改道到「外观」栏目，因为桌面外观页本来就承载液态玻璃设置。

## 2. 手机专属选项清单与桌面处理

处理取值：**隐藏**（桌面没有对应硬件或系统概念，不显示）；**不可用**（显示但置灰并说明原因）；**被代替**（隐藏上游项，由桌面等价项承担）。

### 不可用：需要 Windows Hello 或系统验证接口，桌面端尚未接入（1 项）

进入隐私内容时验证`privacy.privacy_content_authentication_enabled`

### 被代替：桌面不区分无线/移动网络，统一用「默认画质」（desktop 项 playback.default_quality）（2 项）

无线网络默认画质`playback.wifi_quality`、移动网络默认画质`playback.mobile_quality`

### 被代替：桌面更新走自有发布渠道（TON-1013），不检查上游 GitHub（1 项）

自动检查更新`help.auto_check_update_enabled`

### 被代替：桌面用窗口缩放（desktop 项 appearance.window_scale）（1 项）

界面缩放`appearance.app_ui_scale_preset`

### 隐藏：触摸滑动手势；桌面用滚轮、单击、双击动作（windows_ux）和键盘快捷键代替（18 项）

中部滑动切换全屏`playback.center_swipe_to_fullscreen_enabled`、滑动调亮度和音量`playback.slide_volume_brightness_enabled`、横屏精细调进度`playback.fullscreen_swipe_seek_enabled`、按视频时长比例调进度`playback.swipe_seek_use_relative_duration`、滑动调进度时长比例`playback.swipe_seek_relative_percent`、全屏手势反向`playback.fullscreen_gesture_reverse`、全屏显示锁定按钮`playback.show_fullscreen_lock_button`、应用内截图`playback.app_gesture_screenshot_enabled`、双指缩放调整列数`appearance.pinch_to_change_grid_columns_enabled`、返回时倾斜`animation.app_navigation_settings.video_shared_return_gesture_follow_enabled`、返回时跟手`animation.app_navigation_settings.video_shared_return_gesture_translation_enabled`、页面内右滑返回`animation.full_screen_swipe_back_enabled`、快进秒数（双击右侧）`playback.seek_forward_seconds`、后退秒数（双击左侧）`playback.seek_backward_seconds`、非全屏滑动调进度范围`playback.inline_swipe_seek_seconds`、横屏滑动调进度范围`playback.fullscreen_swipe_seek_seconds`、截图触发方式`playback.app_screenshot_gesture_mode`、截图范围`playback.app_screenshot_capture_mode`

### 隐藏：桌面没有振动马达（13 项）

振动场景`animation.haptic_scenes`、触感反馈`animation.haptic_feedback_enabled`、振动场景：按钮与开关`animation.haptic_scene.controls`、振动场景：底栏与标签切换`animation.haptic_scene.navigation`、振动场景：菜单与列表选择`animation.haptic_scene.menus`、振动场景：滑块与数值调节`animation.haptic_scene.adjustment`、振动场景：内容长按`animation.haptic_scene.long_press`、振动场景：文字选择与复制`animation.haptic_scene.text`、振动场景：播放器手势`animation.haptic_scene.player_gestures`、振动场景：播放进度与章节`animation.haptic_scene.player_seek`、振动场景：视频互动操作`animation.haptic_scene.video_actions`、振动场景：刷新与列表操作`animation.haptic_scene.refresh`、振动场景：图片保存结果`animation.haptic_scene.results`

### 隐藏：屏幕方向是手机/平板概念；桌面窗口没有横竖屏切换（10 项）

缩小后自动暂停`playback.pause_on_player_collapse_enabled`、竖屏上滑进入全屏`playback.portrait_swipe_to_fullscreen_enabled`、点竖屏视频直接全屏`playback.direct_portrait_story_entry`、刷视频时只推荐竖屏（测试）`playback.portrait_only_vertical_recommendations`、打开应用直接刷视频`playback.launch_to_portrait_feed_on_startup`、自动横竖屏切换`playback.auto_rotate_enabled`、横屏适配`playback.horizontal_adaptation_enabled`、浏览时缩小视频`playback.portrait_player_collapse_mode`、默认全屏方向`playback.fullscreen_mode`、固定全屏比例`playback.fullscreen_aspect_ratio`

### 隐藏：手机底栏；桌面固定使用侧栏（S1），导航由 windows_ux.rail_mode 控制（9 项）

底栏磨砂`animation.bottom_bar_blur_enabled`、悬浮底栏`navigation.is_bottom_bar_floating`、切换时放大图标`navigation.navigation_icon_cross_scale_enabled`、底栏搜索按钮`navigation.bottom_bar_search_enabled`、保留首页顶部搜索条`navigation.keep_home_top_search`、滚动时收成当前入口`navigation.bottom_bar_scroll_compact_enabled`、滚动时合并搜索按钮`navigation.linked_dock_merge_on_scroll_enabled`、用底栏搜索当前列表`navigation.list_scoped_search_enabled`、显示模式`navigation.visibility_mode`

### 隐藏：Android 开屏页；桌面没有系统开屏（4 项）

随机展示开屏壁纸`appearance.splash_random_enabled`、启动动画`appearance.splash_icon_animation_enabled`、使用开屏壁纸`appearance.is_splash_enabled`、启动动画样式`appearance.startup_animation_style`

### 隐藏：平板专属布局；桌面详情页面板由 windows_ux.details_panel 控制（4 项）

侧边导航栏`navigation.tablet_use_sidebar`、侧边栏账号切换`navigation.sidebar_account_switcher_enabled`、平板评论区宽度`playback.tablet_comment_panel_width_preset`、大屏右侧默认显示`playback.tablet_secondary_default_tab`

### 隐藏：Android 画中画（PiP）（2 项）

小窗中隐藏弹幕`playback.pip_no_danmaku_enabled`、听视频离开时自动进入画中画`playback.audio_mode_auto_pip_enabled`

### 隐藏：桌面端不接入 Firebase 与使用统计，也不上传任何账号数据（2 项）

记录崩溃信息`diagnostics.crash_tracking_enabled`、使用情况统计`diagnostics.analytics_enabled`

### 隐藏：Android 音频焦点；Windows 没有对应概念（1 项）

播放时暂停其他应用的声音`playback.audio_focus_enabled`

### 隐藏：运营商定向流量只存在于手机网络（1 项）

B站定向流量支持`playback.directed_traffic_enabled`

### 隐藏：调节手机系统亮度；桌面由 Windows 管理（1 项）

调节系统亮度`playback.set_system_brightness_enabled`

### 隐藏：窗口没有系统状态栏和刘海（1 项）

状态栏背景模糊`playback.immersive_video_page_status_bar`

### 隐藏：桌面机通常没有电池信息接口（1 项）

全屏显示电量`playback.show_fullscreen_battery_level`

### 隐藏：首页分类栏由 windows_ux.home_layout 控制（1 项）

隐藏首页分类栏`navigation.hide_top_tabs`

## 3. 本批做完后 settings 还剩哪些文件、各被什么卡住

下表只列直接原因（对源码做的静态扫描，加上 3 轮编译实测）。「编译实测」栏是把该文件移出 `later-steps.tsv` 编译后的第一个真实错误。

| 文件 | 直接原因（静态扫描） | 编译实测/已查明 |
|---|---|---|
| `AppSegmentedControl.kt` | TON-1074 feature/nearby 与新分段控件 | 新参数 `liquidGlassAdaptIndicatorToLabel` / `centerNativeContent`（TON-1074） |
| `FullscreenActionCountPreference.kt` | — | `SettingsIconRole`（图标，TON-1072） |
| `HapticScenePreference.kt` | — | `SettingsItemAnchor.kt` 用 `LocalView.announceForAccessibility`（无障碍播报没有桌面接口，不做静默空实现） |
| `HomeFeedSettingsSection.kt` | TON-1073 平台接口 |  |
| `LiquidGlassLivePreview.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `LiquidGlassSavedPresets.kt` | TON-1073 平台接口 |  |
| `LoginActivityViewModel.kt` | data/repository 未迁移 | `data/repository/AccountLoginActivityRepository.kt` 还没迁（不在 M4d 设置范围，属 data/repository） |
| `PlaybackSettingDirectory.kt` | — | `SettingsUiState`（`SettingsViewModel`）与 `additionalSettingsDirectory`（`SettingsAdditionalDirectory`，nearby） |
| `SettingsAdditionalDirectory.kt` | TON-1074 feature/nearby 与新分段控件 | `feature/nearby`（TON-1074）、`SUBTITLE_APPEARANCE_TITLE` |
| `SettingsAppNavigationPolicy.kt` | — | `resolveSettingsRootCategoryForNavKey` 在 `SettingsNavHierarchyPolicy.kt` |
| `SettingsCardMotionPreview.kt` | — |  |
| `SettingsDescriptionIndex.kt` | — |  |
| `SettingsDestinationCopy.kt` | TON-1074 feature/nearby 与新分段控件 |  |
| `SettingsDynamicDescriptionIndex.kt` | — |  |
| `SettingsEntryVisualPolicy.kt` | TON-1072 图标资源 |  |
| `SettingsFeaturePreview.kt` | TON-1072 图标资源 |  |
| `SettingsFunctionIcon.kt` | — |  |
| `SettingsItemAnchor.kt` | TON-1073 平台接口 | `LocalView.announceForAccessibility`：需要桌面无障碍播报接口（TON-1073） |
| `SettingsItemPath.kt` | — |  |
| `SettingsNavHierarchyPolicy.kt` | — | 旧 `SettingsRootCategory` 没有 MESSAGE_NOTIFICATION / VIDEO_DECODER / PLAYER_DIAGNOSTICS / GLASS_ADVANCED；要和 `SettingsRootCategoryPolicy.kt` 一起迁 |
| `SettingsNavigationContainerOrigin.kt` | — |  |
| `SettingsRootCategoryPolicy.kt` | — | 与 `SettingsSearchPolicy.kt` 同属 target 枚举簇：新枚举多出 SPACE_PRIVACY / LOGIN_ACTIVITY / NEARBY_HANDOFF，旧副本里 `SettingsEntryVisualPolicy`、`SettingsSemanticIconPolicy`（图标）、`SettingsDestinationCopy`（nearby）的穷尽 `when` 会编译失败 |
| `SettingsSearchFocusPolicy.kt` | — |  |
| `SettingsSearchNavigationPolicy.kt` | — |  |
| `SettingsSearchPolicy.kt` | — | 同上（target 枚举簇） |
| `SettingsSectionFocus.kt` | — |  |
| `SettingsSemanticIconPolicy.kt` | TON-1072 图标资源；TON-1074 feature/nearby 与新分段控件 |  |
| `SettingsTextCopyPreference.kt` | — | 同上（`SettingsItemAnchor`） |
| `SettingsViewModel.kt` | TON-1073 平台接口 |  |
| `SpacePrivacyViewModel.kt` | data/repository 未迁移 | `data/repository/SpacePrivacyRepository.kt` 还没迁 |
| `ThemeCustomColorPickerDialog.kt` | — | `ThemeRoleColorTarget` 在 `screen/AppearanceSettingsScreen.kt` |
| `ThemeHomePreview.kt` | TON-1072 图标资源 |  |
| `WallpaperCompatibilitySettings.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `WallpaperPaletteEditor.kt` | — | 旧 `ThemeCustomColorPickerDialog` 缺 `exactRoleTitle`/`onReset`；新版又需要 `ThemeRoleColorTarget`（在 `AppearanceSettingsScreen.kt`） |
| `diagnostics/StartupRecoveryActivity.kt` | TON-1073 平台接口 |  |
| `highlights/UpdateHighlightsHost.kt` | TON-1073 平台接口 |  |
| `screen/AnimationSettingsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/AppearanceSettingsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/BlockedListFileService.kt` | — | 兼容层 `ContentResolver` 缺 `openOutputStream`；桌面还有一份 `buildBlockedListJsonFileName` 的生成副本要退役 |
| `screen/BlockedListScreen.kt` | TON-1073 平台接口；data/repository 未迁移 |  |
| `screen/BottomBarSettingsScreen.kt` | TON-1072 图标资源 |  |
| `screen/CommentFraudHistoryScreen.kt` | TON-1072 图标资源；TON-1073 平台接口；data/repository 未迁移 |  |
| `screen/IconSettingsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/JsonPluginEditorScreen.kt` | TON-1072 图标资源 |  |
| `screen/LoginActivitySettingsScreen.kt` | — |  |
| `screen/MessageNotificationSettingsScreen.kt` | TON-1073 平台接口 |  |
| `screen/OpenSourceLicensesScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/PermissionSettingsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/PlaybackSettingsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/PluginsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口；TON-1074 feature/nearby 与新分段控件 |  |
| `screen/SettingsCategoryScreen.kt` | — | `SettingsScreen` / `SettingsViewModel` |
| `screen/SettingsNavigationIconPreviewPolicy.kt` | TON-1072 图标资源 |  |
| `screen/SettingsScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `screen/SettingsSearchScreen.kt` | TON-1074 feature/nearby 与新分段控件 |  |
| `screen/SettingsSearchUi.kt` | TON-1072 图标资源 |  |
| `screen/SettingsTabletRouteShell.kt` | — | `SettingsTabletShell`（图标） |
| `screen/SettingsTabletShell.kt` | TON-1072 图标资源 |  |
| `screen/SkinCatalogScreen.kt` | TON-1072 图标资源 |  |
| `screen/SpacePrivacySettingsScreen.kt` | — |  |
| `screen/TipsSettingsScreen.kt` | TON-1072 图标资源 |  |
| `share/SettingsShareScreen.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `share/SettingsShareService.kt` | TON-1073 平台接口 |  |
| `share/SettingsShareViewModel.kt` | TON-1073 平台接口 |  |
| `ui/SettingsPageScaffold.kt` | — | `settingsIconColors` 在 `SettingsSemanticIconPolicy.kt`（TON-1072） |
| `ui/SettingsSections.kt` | TON-1072 图标资源；TON-1074 feature/nearby 与新分段控件 |  |
| `update/AppUpdateChecker.kt` | — |  |
| `update/AppUpdateDialogHost.kt` | TON-1073 平台接口 |  |
| `update/AppUpdateDownloadWorker.kt` | TON-1072 图标资源；TON-1073 平台接口 |  |
| `update/AppUpdateDownloader.kt` | — | `sanitizeAppUpdateFileName` 在 `AppUpdateDownloadWorker.kt`（WorkManager，TON-1073） |
| `update/AppUpdateInstaller.kt` | TON-1073 平台接口 |  |
| `webdav/WebDavAutoBackupWorker.kt` | TON-1073 平台接口 |  |
| `webdav/WebDavBackupScreen.kt` | TON-1072 图标资源 |  |
| `webdav/WebDavBackupViewModel.kt` | TON-1073 平台接口 |  |

- `update/AppUpdateChecker.kt` 按规矩保持不迁（桌面更新走 TON-1013）。
- 直接原因里的 TON-1072（图标）、TON-1073（平台接口）、TON-1074（nearby 与新分段控件）落地后，这些文件才能一起机械地移出 `later-steps.tsv`；其中「target 枚举簇」（`SettingsSearchPolicy`、`SettingsRootCategoryPolicy`、`SettingsNavHierarchyPolicy`、`SettingsNavigationContainerOrigin`、`SettingsSearchNavigationPolicy` …）要整簇一起迁，不能拆。
