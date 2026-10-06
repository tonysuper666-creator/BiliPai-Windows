package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsSearchResult
import com.android.purebilibili.feature.settings.SettingsSearchTarget

/** Search indexes mounted Windows settings only. Android's index remains source history. */
internal data class DesktopWindowsSettingsSearchEntry(val target: SettingsSearchTarget,
    val title: String, val subtitle: String, val section: String, val words: List<String>, val focus: String? = null)

internal val desktopWindowsSettingsSearchEntries = listOf(
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "播放与画质", "硬件解码、编码、默认画质", "播放", listOf("解码", "显卡", "AV1", "HEVC", "H264", "画质", "清晰度"), "windows_playback_quality"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "倍速与字幕", "记忆倍速、默认速度与自动字幕", "播放", listOf("倍速", "速度", "字幕", "AI"), "windows_playback_speed_subtitle"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "Windows 音频输出", "输出设备、WASAPI 独占与实际格式", "音频", listOf("音频", "声音", "输出", "设备", "独占", "WASAPI", "采样率", "光纤"), "windows_audio_output"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "播放行为", "后台播放、历史续播提示和播完行为", "播放", listOf("后台", "最小化", "续播", "断点", "连播", "循环", "播完", "暂停"), "windows_playback_behavior"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "评论", "默认排序和详细评论时间", "播放", listOf("评论", "热度", "排序", "相对时间", "详细时间"), "windows_playback_comments"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "默认音质", "后续播放的音轨质量偏好", "音频", listOf("音质", "音轨", "无损"), "windows_audio_output"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.HOME_FEED, "首页与推荐", "网格、卡片、轮播和本地背景", "首页", listOf("首页", "推荐", "网格", "标题", "轮播", "壁纸", "背景", "UP", "时间表")),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.APPEARANCE, "外观与显示", "主题、字体、语言和图标", "外观", listOf("外观", "主题", "字体", "语言", "图标", "颜色")),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.APPEARANCE, "Windows 窗口缩放", "系统 DPI 上的完整布局比例", "外观", listOf("缩放", "DPI", "放大", "缩小", "比例", "Ctrl", "4K", "5K"), "windows_display_scale"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PRIVACY_PERMISSION, "隐私与屏蔽", "搜索推荐、历史记录、屏蔽与发评反诈历史", "隐私", listOf("隐私", "历史", "搜索", "屏蔽", "黑名单", "反诈")),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.DATA_BACKUP, "缓存、下载与备份", "本机路径、缓存管理和备份", "存储", listOf("缓存", "下载", "目录", "路径", "备份", "WebDAV", "ZIP")),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLAYBACK, "NVIDIA 自动增强", "所有视频统一增强与当前原生输出状态", "播放", listOf("NVIDIA", "RTX", "超分辨率", "画质增强", "VSR", "HDR"), "windows_video_enhancement"),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.PLUGINS, "插件与扩展", "CDN、播放加速与规则扩展", "插件", listOf("插件", "CDN", "加速", "规则")),
    DesktopWindowsSettingsSearchEntry(SettingsSearchTarget.DIAGNOSTICS, "更新与诊断", "Windows 更新器与用户打开的本地日志", "系统", listOf("更新", "版本", "日志", "诊断", "代理", "关于", "帮助", "协议", "许可")),
)

internal fun resolveDesktopWindowsSettingsSearchResults(query: String): List<SettingsSearchResult> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val terms = trimmed.split(Regex("\\s+"))
    return desktopWindowsSettingsSearchEntries.filter { entry ->
        val searchable = listOf(entry.title, entry.subtitle, entry.section) + entry.words
        terms.all { term -> searchable.any { it.contains(term, ignoreCase = true) } }
    }.take(20).map { entry -> SettingsSearchResult(entry.target, entry.title, entry.subtitle, entry.section, entry.focus) }
}
