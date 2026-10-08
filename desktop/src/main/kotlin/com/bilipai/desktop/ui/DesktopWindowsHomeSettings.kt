package com.bilipai.desktop.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.store.DesktopOriginalHomeSettingsManager as Preferences
import com.android.purebilibili.feature.settings.DesktopOriginalHomeSettingsViewModel
import com.android.purebilibili.feature.profile.DesktopOriginalHomeWallpaperViewModel
import com.android.purebilibili.feature.profile.WallpaperSaveState
import java.net.URI
import java.nio.file.Path

/** Mouse/keyboard Home settings. The real Root still owns state, file chooser and all writes. */
@Composable
internal fun DesktopWindowsHomeSettings(state: HomeSettings, viewModel: DesktopOriginalHomeSettingsViewModel,
    context: DesktopOriginalPlayerSettingsContext, actions: DesktopOriginalHomeSettingsActions,
    environment: DesktopProfileEnvironment, onBack: () -> Unit) {
    val wallpaper = desktopWindowsSettingsValue(remember(context) { Preferences.getHomeWallpaperUri(context) })
    val wallpaperViewModel = remember(context, environment) { DesktopOriginalHomeWallpaperViewModel(environment, context) }
    val saveState by wallpaperViewModel.splashSaveState.collectAsState()
    val saving = saveState is WallpaperSaveState.Loading
    val wallpaperLabel = remember(wallpaper) {
        wallpaper?.takeIf { it.isNotBlank() }?.let {
            runCatching { Path.of(URI(it)).fileName.toString() }.getOrDefault("已设置壁纸")
        } ?: "未设置"
    }
    DesktopWindowsSettingsPane("首页与推荐", onBack) {
        DesktopWindowsSettingsGroup("列表") {
            DesktopWindowsSettingsChoice("展示样式", state.displayMode, listOf(0 to "网格", 1 to "单列"), onChange = viewModel::setDisplayMode)
            DesktopWindowsSettingsChoice("网格列数", state.gridColumnCount,
                (0..6).map { it to if (it == 0) "自动" else "$it 列" }, enabled = state.displayMode == 0,
                onChange = viewModel::setGridColumnCount)
            DesktopWindowsSettingsChoice("卡片宽度", state.homeFeedCardWidthPreset,
                HomeFeedCardWidthPreset.entries.map { it to it.label }, enabled = state.gridColumnCount == 0,
                onChange = viewModel::setHomeFeedCardWidthPreset)
            DesktopWindowsSettingsSwitch("完整显示视频标题", state.showFullVideoCardContent) { value ->
                actions.launch { Preferences.setFullVideoCardContentVisible(context, value) }
            }
            DesktopWindowsSettingsSwitch("显示发布时间", state.showHomePublishTime) { value ->
                actions.launch { Preferences.setHomePublishTimeVisible(context, value) }
            }
            DesktopWindowsSettingsSwitch("显示 UP 主头像", state.showHomeUpAvatars) { value ->
                actions.launch { Preferences.setHomeUpAvatarsVisible(context, value) }
            }
            DesktopWindowsSettingsSwitch("显示 UP 主标识", state.showHomeUpBadges) { value ->
                actions.launch { Preferences.setHomeUpBadgesVisible(context, value) }
            }
        }
        DesktopWindowsSettingsGroup("刷新") {
            DesktopWindowsSettingsSwitch("撤销刷新按钮", state.homeRefreshUndoVisible) { value ->
                actions.launch { Preferences.setHomeRefreshUndoVisible(context, value) }
            }
        }
        DesktopWindowsSettingsGroup("顶部轮播") {
            DesktopWindowsSettingsSwitch("启用首页轮播", state.homeHeroCarouselEnabled) { value ->
                actions.launch { Preferences.setHomeHeroCarouselEnabled(context, value) }
            }
            DesktopWindowsSettingsSwitch("轮播默认播放", state.homeHeroCarouselAutoplayEnabled,
                enabled = state.homeHeroCarouselEnabled) { value ->
                actions.launch { Preferences.setHomeHeroCarouselAutoplayEnabled(context, value) }
            }
            DesktopWindowsSettingsSwitch("显示番剧影视时间表", state.showPgcTimeline) { value ->
                actions.launch { Preferences.setShowPgcTimeline(context, value) }
            }
        }
        DesktopWindowsSettingsGroup("背景") {
            Text(wallpaperLabel, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !saving, onClick = {
                environment.platform.pickMedia { selected ->
                    if (selected != null) wallpaperViewModel.setCustomHomeWallpaper(selected)
                }
            }) { Text(if (saving) "正在导入…" else "选择本地图片 / 视频") }
            TextButton(enabled = !saving && !wallpaper.isNullOrBlank(), onClick = {
                actions.launch { Preferences.setHomeWallpaperUri(context, "") }
            }) { Text("移除壁纸") }
            (saveState as? WallpaperSaveState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            DesktopWindowsSettingsChoice("壁纸效果", state.homeWallpaperEffectMode,
                HomeWallpaperEffectMode.entries.map { it to it.label }) { value ->
                actions.launch { Preferences.setHomeWallpaperEffectMode(context, value) }
            }
            DesktopWindowsSettingsChoice("作用范围", state.homeWallpaperEffectScope,
                HomeWallpaperEffectScope.entries.map { it to it.label }) { value ->
                actions.launch { Preferences.setHomeWallpaperEffectScope(context, value) }
            }
            DesktopWindowsSettingsSwitch("卡片动态取色", state.homeCardDynamicTintEnabled) { value ->
                actions.launch { Preferences.setHomeCardDynamicTintEnabled(context, value) }
            }
        }
        DesktopWindowsSettingsGroup("导航") {
            DesktopWindowsSettingsSwitch("滚动时收起首页顶栏", state.isHeaderCollapseEnabled,
                onChange = viewModel::toggleHeaderCollapse)
        }
    }
}
