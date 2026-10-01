// Original source app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt
// LF SHA256 680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c
package com.android.purebilibili.core.store
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes
import com.android.purebilibili.core.ui.transition.*

data class HomeSettings(
    val displayMode: Int = 0,              // 展示模式 (0=网格, 1=故事卡片)
    val isBottomBarFloating: Boolean = true,
    val navigationIconCrossScaleEnabled: Boolean = true,
    val bottomBarLabelMode: Int = 0,       // (0=图标+文字, 1=仅图标, 2=仅文字)
    val topTabLabelMode: Int = 2,          // (0=图标+文字, 1=仅图标, 2=仅文字)
    val hideTopTabs: Boolean = false,
    val homeTopRightAction: HomeTopRightAction = HomeTopRightAction.SETTINGS,
    val homeTopLayoutOrder: HomeTopLayoutOrder = HomeTopLayoutOrder.SEARCH_THEN_TABS,
    val isHeaderBlurEnabled: Boolean = true,
    val headerBlurMode: HomeHeaderBlurMode = HomeHeaderBlurMode.FOLLOW_PRESET,
    val isBottomBarBlurEnabled: Boolean = false,
    val isTopBarLiquidGlassEnabled: Boolean = false,
    val isHomeSearchLiquidGlassEnabled: Boolean = false,
    val isBottomBarLiquidGlassEnabled: Boolean = false,
    val bottomBarLiquidGlassPreset: BottomBarLiquidGlassPreset =
        BottomBarLiquidGlassPreset.BILIPAI_TUNED,
    val isBottomBarSearchEnabled: Boolean = false,
    val listScopedSearchEnabled: Boolean = false,
    val linkedDockMergeOnScrollEnabled: Boolean = true,
    val bottomBarSearchAutoExpandMode: BottomBarSearchAutoExpandMode =
        BottomBarSearchAutoExpandMode.EXPAND_AT_HOME_TOP,
    val bottomBarSearchLayoutMode: BottomBarSearchLayoutMode =
        BottomBarSearchLayoutMode.FULL_DOCK,
    val androidNativeLiquidGlassEnabled: Boolean = false,
    val liquidGlassStyle: LiquidGlassStyle = LiquidGlassStyle.CLASSIC, // [New]
    val liquidGlassMode: LiquidGlassMode = LiquidGlassMode.BALANCED,
    val liquidGlassStrength: Float = 0.52f,
    val liquidGlassProgress: Float = 0.5f,
    val liquidGlassReadabilityMode: LiquidGlassReadabilityMode =
        LiquidGlassReadabilityMode.STABLE,
    val liquidGlassAdvancedSettings: LiquidGlassAdvancedSettings = LiquidGlassAdvancedSettings(),
    val homeHeaderCollapseMode: HomeHeaderCollapseMode = HomeHeaderCollapseMode.BOTH,
    val homeBarHideType: HomeBarHideType = HomeBarHideType.SYNC,
    val commonListHeaderCollapseMode: CommonListHeaderCollapseMode =
        CommonListHeaderCollapseMode.SHOW_ON_REVERSE_SCROLL,
    val isHeaderCollapseEnabled: Boolean = true,
    val showPgcTimeline: Boolean = true,
    val gridColumnCount: Int = 0, // [New] 网格列数 (0=自动, 1-6=固定)——宽屏（折叠屏内屏/平板）
    val gridColumnCountCompact: Int = 0, // [New] 窄屏（折叠屏外屏/手机竖屏）独立列数记忆 (0=自动)
    val pinchToChangeGridColumnsEnabled: Boolean = true, // [新增] 双指缩放切换网格列数
    val homeFeedCardWidthPreset: HomeFeedCardWidthPreset = HomeFeedCardWidthPreset.AUTO,
    val homeFeedCardStyle: HomeFeedCardStyle = HomeFeedCardStyle.BILIPAI,
    val homeHeroCarouselEnabled: Boolean = true,
    val homeHeroCarouselAutoplayEnabled: Boolean = false,
    val homeRefreshTipVisible: Boolean = true, // 推荐流刷新后在旧内容分界处显示提示
    val cardAnimationEnabled: Boolean = false,    //  卡片进场动画（默认关闭）
    val cardTransitionEnabled: Boolean = true,    //  卡片过渡动画（默认开启）
    val videoSharedTransitionSpeed: VideoSharedTransitionSpeed = VideoSharedTransitionSpeed.STANDARD,
    val videoSharedTransitionCustomDurationMillis: Int =
        VIDEO_SHARED_TRANSITION_CUSTOM_DEFAULT_MILLIS,
    // [Retired] 旧的首页 feed「智能流畅优先」，固定关闭。
    // 运行时视觉守卫是另一套机制，见 [runtimeVisualGuardEnabled]。
    val smartVisualGuardEnabled: Boolean = false,
    // 运行时视觉守卫：连续掉帧时自动降级毛玻璃/液态玻璃/景深。
    // 影响面覆盖全 App 视觉，必须保留 kill switch——某机型 JankStats 读数异常时可关闭。
    val runtimeVisualGuardEnabled: Boolean = true,
    val compactVideoStatsOnCover: Boolean = false, // 播放/弹幕位于信息区，不叠加在封面上
    val lowQualityHomeCoverInDataSaver: Boolean = false, // 省流量时首页封面使用低清晰度
    // 卡片标签 / 信息区玻璃效果已下线，保留字段仅为兼容旧数据结构。
    val showHomeCoverGlassBadges: Boolean = false,
    val showHomeInfoGlassBadges: Boolean = false,
    val homeCardBadgeEffectMode: HomeCardBadgeEffectMode = HomeCardBadgeEffectMode.OFF,
    val homeCardInfoGlassMode: HomeCardInfoGlassMode = HomeCardInfoGlassMode.OFF,
    val homeWallpaperEffectMode: HomeWallpaperEffectMode = HomeWallpaperEffectMode.SOFT_BLUR,
    val homeWallpaperEffectScope: HomeWallpaperEffectScope = HomeWallpaperEffectScope.HOME_ONLY,
    val showHomeUpBadges: Boolean = false, // 首页和相关推荐 UP 主标识显示(默认关闭,设置后全局生效)
    val showHomeUpAvatars: Boolean = false, // 首页视频卡片 UP 主头像显示(默认关闭,设置后全局生效)
    val showHomePublishTime: Boolean = true, // 首页视频卡片发布时间（默认显示，可关闭）
    val showFullVideoCardContent: Boolean = false, // 视频卡片标题完整展示(默认关闭,设置后全局生效)
    val videoCardLongPressActionEnabled: Boolean = false, // 长按视频卡片快捷操作与预览（默认关闭）
    val homeCardDynamicTintEnabled: Boolean = false, // 卡片动态取色
    val homeCardFrostedGlassEnabled: Boolean = false, // 卡片毛玻璃
    val homeDurationStyle: HomeDurationStyle = HomeDurationStyle.OUTSIDE_COVER,
    val easterEggEnabled: Boolean = false, // 下拉刷新趣味提示开关
    //  [修复] 默认值改为 true，避免在 Flow 加载实际值之前错误触发弹窗
    // 当 Flow 加载完成后，如果实际值是 false，LaunchedEffect 会再次触发并显示弹窗
    val crashTrackingConsentShown: Boolean = true
) {
    val isLiquidGlassEnabled: Boolean
        get() = androidNativeLiquidGlassEnabled
}

enum class HomeTopRightAction(val value: Int, val label: String) {
    SETTINGS(0, "设置"),
    INBOX(1, "消息");

    companion object {
        fun fromValue(value: Int): HomeTopRightAction =
            entries.find { it.value == value } ?: SETTINGS
    }
}

enum class HomeTopLayoutOrder(val value: Int, val label: String) {
    SEARCH_THEN_TABS(0, "搜索在上"),
    TABS_THEN_SEARCH(1, "标签在上");

    companion object {
        fun fromValue(value: Int): HomeTopLayoutOrder =
            entries.find { it.value == value } ?: SEARCH_THEN_TABS
    }
}

enum class HomeHeaderCollapseMode(
    val value: Int,
    val label: String,
    val description: String,
    val collapseSearch: Boolean,
    val collapseTabs: Boolean
) {
    SEARCH_ONLY(0, "仅搜索", "列表下滑时只收起搜索行，标签页保持显示", true, false),
    TABS_ONLY(1, "仅标签", "列表下滑时只收起标签页，搜索行保持显示", false, true),
    BOTH(2, "都折叠", "搜索行和标签页都会随列表下滑收起", true, true),
    OFF(3, "都不折叠", "搜索行和标签页始终展开", false, false);

    val hasAnyCollapse: Boolean
        get() = collapseSearch || collapseTabs

    val hideTopBar: Boolean
        get() = collapseSearch

    companion object {
        fun fromValue(value: Int): HomeHeaderCollapseMode =
            entries.find { it.value == value } ?: BOTH

        fun fromLegacyBoolean(value: Boolean): HomeHeaderCollapseMode =
            if (value) BOTH else OFF
    }
}

enum class HomeBarHideType(
    val value: Int,
    val label: String,
    val description: String
) {
    SYNC(0, "同步", "顶栏高度跟随列表滑动，上滑展开、下滑收起"),
    INSTANT(1, "即时", "识别滑动方向后，顶栏整段收起或展开");

    companion object {
        fun fromValue(value: Int): HomeBarHideType =
            entries.find { it.value == value } ?: SYNC
    }
}

data class AppNavigationSettings(
    val bottomBarVisibilityMode: com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode = com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_VISIBLE,
    val orderedVisibleTabIds: List<String> = listOf("HOME", "DYNAMIC", "HISTORY", "LISTEN_VIDEO", "PROFILE"),
    val bottomBarItemColors: Map<String, Int> = emptyMap(),
    val bottomBarItemLabels: Map<String, String> = emptyMap(),
    val tabletUseSidebar: Boolean = false,
    val sidebarExpanded: Boolean = true,
    val sidebarAccountSwitcherEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val predictiveBackAnimationStyle: String = "miuix",
    val predictiveBackExitDirection: String = "always_right",
    val miuixTransitionBlurEnabled: Boolean = true,
    val miuixPredictiveBackMaxProgressPercent: Int = 100,
    val videoSharedReturnGestureFollowEnabled: Boolean = true,
)

data class HomeTopTabSettings(
    val orderIds: List<String> = listOf("RECOMMEND", "FOLLOW", "POPULAR", "LIVE", "GAME"),
    val visibleIds: Set<String> = setOf("RECOMMEND", "FOLLOW", "POPULAR", "LIVE", "GAME"),
    val hideTopTabs: Boolean = false
)

/**
 * 自动退出全屏策略。
 * - [OFF]：不自动退
 * - [CURRENT_PART]：当前分P/单视频结束就退（旧「开」语义中的激进行为）
 * - [ALL_PARTS]：还有下一段可连播时保持全屏，全部播完再退（默认）
 */
