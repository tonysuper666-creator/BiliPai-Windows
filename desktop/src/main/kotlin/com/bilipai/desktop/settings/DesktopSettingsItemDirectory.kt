package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchFocusIds
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.ui.DesktopWindowsSettingsStrings

/*
 * The item-level directory of the Windows settings (roadmap slice set-settings-search-items; upstream v0.3.4-beta.1
 * item search, v0.3.8 description hits, v0.3.9 focus claim).
 *
 * Upstream keeps one Android map (PlaybackSettingDirectory.kt and SettingsAdditionalDirectory.kt, about 200 rows, most of them
 * phone-only). The Windows directory is its own list of exactly the rows that the Windows pages mount. Every row below is a
 * row of a page that DesktopSettingsTree shows: its title, and its description where the row has a static one, are copied
 * from that row's source (test_v046_settings_search_items_source.py reads each source and fails on a title that no row
 * carries, on a row that the directory forgot, and on a group anchor that is not the row's own group).
 *
 * Ids: a row that exists upstream under the same meaning keeps the upstream id (the settings share catalog and a future
 * backup import can then relate the two); a Windows-only row gets an id of the same shape. Aliases are search words only.
 *
 * The directory never contains a phone control (permissions, gestures, portrait modes, bottom bar, brightness, onboarding).
 * Pages whose rows come from generated original screens (home, storage and backup, plugins, update and diagnostics, blocked
 * list, tips, licenses) are not itemised here; their sections stay reachable through DesktopWindowsSettingsCatalog.
 *
 * focus is the id of a group anchor that the page already mounts (desktopSettingsSearchFocusAnchor); null opens the page.
 */
internal data class DesktopSettingsItem(
    val settingId: String,
    val title: String,
    /** The static description line of the row, or "" when the row has none. */
    val description: String,
    val target: SettingsSearchTarget,
    /** The rail category whose page holds the row; it names the second part of the breadcrumb. */
    val category: SettingsRootCategory,
    /** The group heading on the page, or null when the page has no heading for the row. */
    val group: String?,
    val focus: String?,
    val aliases: List<String> = emptyList(),
)

private const val ITEM_ROOT = "设置"

private const val ITEM_FOCUS_QUALITY = "windows_playback_quality"
private const val ITEM_FOCUS_SPEED_SUBTITLE = "windows_playback_speed_subtitle"
private const val ITEM_FOCUS_BEHAVIOR = "windows_playback_behavior"
private const val ITEM_FOCUS_COMMENTS = "windows_playback_comments"
private const val ITEM_FOCUS_AUDIO = "windows_audio_output"
private const val ITEM_FOCUS_ENHANCEMENT = "windows_video_enhancement"
private const val ITEM_FOCUS_DISPLAY_SCALE = "windows_display_scale"

private const val ITEM_GROUP_QUALITY = "解码与画质"
private const val ITEM_GROUP_SPEED_SUBTITLE = "倍速与字幕"
private const val ITEM_GROUP_BEHAVIOR = "播放行为"
private const val ITEM_GROUP_COMMENTS = "评论"
private const val ITEM_GROUP_AUDIO = "音频"
private const val ITEM_GROUP_ENHANCEMENT = "视频增强"
private const val ITEM_GROUP_SEARCH_HISTORY = "搜索与历史"
private const val ITEM_GROUP_NOTIFICATION = "消息通知"

/** A row of the playback pane (DesktopWindowsPlaybackSettings and the two panels it hosts). */
private fun playbackItem(id: String, title: String, description: String, group: String, focus: String, vararg aliases: String) =
    DesktopSettingsItem(id, title, description, SettingsSearchTarget.PLAYBACK, SettingsRootCategory.PLAYBACK_QUALITY,
        group, focus, aliases.toList())

/** A row of the appearance page; its cards carry no heading, so the breadcrumb stops at the page. */
private fun appearanceItem(id: String, title: String, description: String, focus: String?, vararg aliases: String) =
    DesktopSettingsItem(id, title, description, SettingsSearchTarget.APPEARANCE, SettingsRootCategory.APPEARANCE_THEME,
        null, focus, aliases.toList())

/** A row of the message notification detail, opened from the privacy category. */
private fun notificationItem(id: String, title: String, description: String, vararg aliases: String) =
    DesktopSettingsItem(id, title, description, SettingsSearchTarget.MESSAGE_NOTIFICATION, SettingsRootCategory.PRIVACY_PERMISSION,
        ITEM_GROUP_NOTIFICATION, null, aliases.toList())

/** A row of the privacy category page (the search and history group); the category page has no focus anchor. */
private fun privacyItem(id: String, title: String, description: String, vararg aliases: String) =
    DesktopSettingsItem(id, title, description, SettingsSearchTarget.PRIVACY_PERMISSION, SettingsRootCategory.PRIVACY_PERMISSION,
        ITEM_GROUP_SEARCH_HISTORY, null, aliases.toList())

internal val desktopSettingsItemDirectory: List<DesktopSettingsItem> = listOf(
    // DesktopWindowsPlaybackSettings: 解码与画质
    playbackItem("playback.hw_decode", "硬件解码", "使用显卡解码；更改后由播放器按原有恢复策略应用。", ITEM_GROUP_QUALITY, ITEM_FOCUS_QUALITY, "启用硬件解码", "解码", "显卡"),
    playbackItem("playback.video_codec_preference", "首选视频编码", "", ITEM_GROUP_QUALITY, ITEM_FOCUS_QUALITY, "优先使用", "编码", "AV1", "HEVC", "H265", "H264", "AVC"),
    playbackItem("playback.video_second_codec_preference", "无法播放时的备用编码", "", ITEM_GROUP_QUALITY, ITEM_FOCUS_QUALITY, "无法播放时改用", "备用编码", "编码"),
    playbackItem("playback.auto_highest_quality_enabled", "优先最高可用画质", "", ITEM_GROUP_QUALITY, ITEM_FOCUS_QUALITY, "自动最高画质", "最高画质", "画质"),
    playbackItem("playback.default_quality", "默认画质", "", ITEM_GROUP_QUALITY, ITEM_FOCUS_QUALITY, "画质", "清晰度", "分辨率", "1080P", "720P", "4K"),
    // DesktopWindowsPlaybackSettings: 倍速与字幕
    playbackItem("playback.remember_last_playback_speed", "记住上次播放倍速", "", ITEM_GROUP_SPEED_SUBTITLE, ITEM_FOCUS_SPEED_SUBTITLE, "记住上次倍速", "倍速", "速度"),
    playbackItem("playback.default_playback_speed", "默认倍速", "", ITEM_GROUP_SPEED_SUBTITLE, ITEM_FOCUS_SPEED_SUBTITLE, "倍速", "速度", "播放速度"),
    playbackItem("playback.subtitle_auto_preference", "自动启用字幕", "", ITEM_GROUP_SPEED_SUBTITLE, ITEM_FOCUS_SPEED_SUBTITLE, "字幕", "AI字幕", "自动字幕"),
    // DesktopWindowsPlaybackSettings: 播放行为
    playbackItem("playback.click_to_play_enabled", "进入视频自动播放", "关闭后，进入视频详情页需手动开始播放。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "自动播放"),
    playbackItem("playback.auto_skip_op_ed", "自动跳过片头片尾", "番剧提供跳过区间时，自动跳过片头和片尾。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "片头", "片尾", "跳过", "番剧"),
    playbackItem("playback.background_playback_enabled", "后台播放", "最小化窗口后继续播放；听视频和小窗遵循各自的播放模式。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "后台", "最小化", "听视频"),
    playbackItem("playback.resume_playback_prompt_enabled", "历史续播提示", "发现历史播放进度时提示继续播放。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "继续播放提示", "续播", "断点", "历史进度"),
    playbackItem("playback.next_watch_hint_enabled", "接下来观看卡片", "最后 5 秒提示下一集或相关推荐，关闭不影响自动续播。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "接下来观看", "下一集提示", "相关推荐提示", "下一集", "相关推荐", "倒计时", "片尾提示"),
    playbackItem("playback.auto_play_enabled", "自动播放下一个", "分P/合集自动播放下一集，普通单视频播完暂停；“视频播完后”为“自动连播”时生效。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "自动播放", "自动连播", "连播", "分P", "合集"),
    playbackItem("playback.external_playlist_auto_continue_enabled", "列表/收藏夹连续播放", "控制收藏夹、稍后再看、合集等列表播放完后是否继续下一条；“视频播完后”为“自动连播”时生效。", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "列表连续播放", "自动播放", "连播", "收藏夹", "稍后再看", "合集"),
    playbackItem("playback.playback_completion_behavior", "视频播完后", "", ITEM_GROUP_BEHAVIOR, ITEM_FOCUS_BEHAVIOR, "播完", "循环", "暂停", "自动连播"),
    // DesktopWindowsPlaybackSettings: 评论
    playbackItem("playback.comment_default_sort_mode", "默认评论排序", "", ITEM_GROUP_COMMENTS, ITEM_FOCUS_COMMENTS, "评论排序", "排序", "热度", "按热度", "按时间"),
    playbackItem("playback.detailed_comment_time_enabled", "详细评论时间", "显示完整时间；关闭后沿用原有相对时间规则。", ITEM_GROUP_COMMENTS, ITEM_FOCUS_COMMENTS, "完整评论时间", "评论时间", "详细时间", "相对时间", "评论发布时间", "绝对时间"),
    playbackItem("playback.comment_avatar_push_enabled", "评论头像跟随", "滚动长评论时，头像跟随评论内容显示。", ITEM_GROUP_COMMENTS, ITEM_FOCUS_COMMENTS, "评论头像推挤", "头像跟随", "头像", "头像碰撞", "头像吸附", "评论动效"),
    playbackItem("playback.comment_member_decorations_enabled", "评论区个性装扮", "显示粉丝牌、铭牌和装扮卡片。", ITEM_GROUP_COMMENTS, ITEM_FOCUS_COMMENTS, "评论装扮", "个性装扮", "装扮", "粉丝牌", "铭牌"),
    // DesktopWindowsPlaybackSettings and DesktopWindowsAudioOutputSettings: 音频
    playbackItem("playback.default_audio_quality", "默认音质", "", ITEM_GROUP_AUDIO, ITEM_FOCUS_AUDIO, "音质", "音轨", "无损"),
    playbackItem("audio.wasapi_exclusive", "WASAPI 独占输出", "绕过系统混音。保存后可立即应用到当前播放，也会从下次播放生效；独占时其他应用可能无法使用同一设备。", ITEM_GROUP_AUDIO, ITEM_FOCUS_AUDIO, "独占", "WASAPI", "音频", "声音", "音频输出"),
    playbackItem("audio.output_device", "输出设备", "默认设备随 Windows 设置变化。", ITEM_GROUP_AUDIO, ITEM_FOCUS_AUDIO, "音频设备", "设备", "WASAPI", "音频", "声音", "音频输出"),
    // DesktopVideoEnhancementControls: 视频增强
    playbackItem("video.nvidia_auto_enhancement", "NVIDIA 自动增强", "按当前视频和显示尺寸增强清晰度，可选 SDR 转 HDR。播放时可直接调整。", ITEM_GROUP_ENHANCEMENT, ITEM_FOCUS_ENHANCEMENT, "NVIDIA", "RTX", "VSR", "HDR", "超分辨率", "画质增强", "自动增强"),
    playbackItem("video.nvidia_enhancement_content", "增强内容", "", ITEM_GROUP_ENHANCEMENT, ITEM_FOCUS_ENHANCEMENT, "NVIDIA", "RTX"),
    playbackItem("video.nvidia_enhancement_intensity", "效果强度", "", ITEM_GROUP_ENHANCEMENT, ITEM_FOCUS_ENHANCEMENT, "NVIDIA", "RTX", "增强强度"),
    playbackItem("video.nvidia_enhancement_quality", "质量上限", "", ITEM_GROUP_ENHANCEMENT, ITEM_FOCUS_ENHANCEMENT, "NVIDIA", "RTX", "处理质量"),
    // DesktopAppearanceSettings
    appearanceItem("appearance.ui_style", "界面预设", "", SettingsSearchFocusIds.APPEARANCE_THEME, "界面风格", "界面", "ui预设", "md3", "miuix"),
    appearanceItem("appearance.effective_liquid_glass_enabled", "原版液态玻璃", "复用原版折射与材质，跟随应用背景。", SettingsSearchFocusIds.APPEARANCE_THEME, "液态玻璃", "玻璃", "折射", "材质"),
    appearanceItem("appearance.theme_mode", "主题模式", "", SettingsSearchFocusIds.APPEARANCE_THEME, "浅色模式", "深色模式", "跟随系统", "夜间模式", "深色", "浅色"),
    appearanceItem("appearance.dark_theme_style", "深色风格", "", SettingsSearchFocusIds.APPEARANCE_THEME, "深色主题", "普通黑", "AMOLED", "纯黑"),
    appearanceItem("appearance.app_language", "应用语言", "", SettingsSearchFocusIds.APPEARANCE_THEME, "语言", "简体中文", "繁體中文", "English"),
    appearanceItem("appearance.md3_color_source", "主题颜色来源", "", null, "主题色", "颜色", "自定义颜色", "预设颜色"),
    appearanceItem("appearance.seed_palette_style", "配色预设", "", null, "配色", "调色板", "颜色"),
    appearanceItem("appearance.app_font_size_preset", "字体大小", "", SettingsSearchFocusIds.APPEARANCE_DISPLAY, "字号", "文字大小", "字体"),
    appearanceItem("appearance.app_font_weight_preset", "字体粗细", "", SettingsSearchFocusIds.APPEARANCE_DISPLAY, "文字粗细", "字体"),
    appearanceItem("appearance.window_scale", "窗口缩放", "在 Windows 系统 DPI 上调整整个窗口，字体设置仍单独保留。", ITEM_FOCUS_DISPLAY_SCALE, "缩放", "DPI", "放大", "缩小", "比例", "Ctrl", "4K", "5K"),
    appearanceItem("appearance.app_icon_style", "图标样式", "", null, "图标", "主题色容器"),
    appearanceItem("appearance.app_list_item_style", "列表样式", "", null, "列表条目样式", "原生组件", "自定义组件"),
    appearanceItem("animation.global_text_tap_copy_enabled", "点击文字复制", "", null, "点按文字复制", "复制文字", "复制"),
    appearanceItem("animation.ui_entrance_animation_enabled", "入场动画", "", null, "打开页面时淡入", "进场动画", "界面入场动画", "动画"),
    appearanceItem("animation.maid_lottie_animation_enabled", "女仆动画", "关闭后隐藏女仆动画和静态图", null, "蓝雪女仆动画", "女仆", "动画"),
    // DesktopMessageNotificationSettingsContent
    notificationItem("notification.enabled", "消息通知", "BiliPai 开着时检查新消息，最小化也会检查；首次检查只记录基线。", "通知", "后台消息通知", "新消息提醒"),
    notificationItem("notification.mode", "检查频率", "节能每 30 分钟，更及时每 15 分钟。", "节能", "更及时", "通知"),
    notificationItem("notification.resident_enabled", "窗口显示时更及时检查", "显示窗口时每 1–2 分钟；最小化后每 15 分钟。", "更及时", "通知"),
    notificationItem("notification.notify_private_messages", "私信", "", "私信通知", "通知"),
    notificationItem("notification.notify_replies", "回复我的", "", "回复通知", "通知"),
    notificationItem("notification.notify_at_me", "@我", "", "艾特我", "通知"),
    notificationItem("notification.notify_likes", "收到的赞", "", "点赞", "通知"),
    notificationItem("notification.notify_system_notices", "系统通知", "", "通知"),
    notificationItem("notification.notify_dynamic_updates", "关注动态", "需要 TV 授权登录；只有网页登录时无法预加载关注动态，不会发送此类通知。", "关注更新", "通知"),
    notificationItem("notification.notify_live_alerts", "开播提醒", "", "开播", "直播提醒", "通知"),
    // DesktopPrivacySection: 搜索与历史
    privacyItem("privacy.default_search_hint_enabled", "显示默认搜索词", "", "默认搜索词", "搜索框默认词", "搜索提示"),
    privacyItem("privacy.search_suggestions_enabled", "个性化搜索推荐", "使用官方搜索推荐；关闭时使用公开热搜。", "搜索推荐词", "推荐词", "热搜"),
    privacyItem("privacy.personal_recap_enabled", "我的回顾", "在历史页显示阅读与观看统计、趋势图和最近爱看的 UP 主。", "回顾", "阅读统计", "观看统计"),
    privacyItem("privacy.privacy_mode_enabled", "不新增播放和搜索历史", "已有记录保留。", "不记录历史", "不新增", "无痕", "隐私", "历史记录"),
)

/** The breadcrumb of a row: 设置 / rail title / group. */
internal fun desktopSettingsItemPath(item: DesktopSettingsItem): String =
    listOfNotNull(ITEM_ROOT, DesktopWindowsSettingsStrings.railTitle(item.category), item.group).joinToString(" / ")
