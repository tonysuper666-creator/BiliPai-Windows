// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeUiState.kt
// LF SHA256 3dd55fff4015adb388f9a403e6ae585a01b4a81f9aff3502a3af97f069187939
package com.android.purebilibili.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.model.response.LiveRoom
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class UserState(
    val isLogin: Boolean = false,
    val face: String = "",
    val name: String = "",
    val mid: Long = 0,
    val level: Int = 0,
    val currentLevelMinExp: Int = 0,
    val currentLevelExp: Int = 0,
    val nextLevelExp: Int = 0,
    val coin: Double = 0.0,
    val bcoin: Double = 0.0,
    val following: Int = 0,
    val follower: Int = 0,
    val dynamic: Int = 0,
    val isVip: Boolean = false,
    /** Server vip.label.text, e.g. 大会员 / 年度大会员 */
    val vipLabel: String = "",
    /** vip.type: 1 monthly, 2 annual — used when [vipLabel] is empty */
    val vipType: Int = 0,
    //  [New] 顶部背景图
    val topPhoto: String = ""
)

/**
 * 首页分类枚举（含 Bilibili 分区 ID）
 */

enum class LiveSubCategory(val label: String) {
    FOLLOWED("关注"),
    POPULAR("热门")
}

internal fun resolveHomeCategoryLabelRes(category: HomeCategory): String = when (category) {
    HomeCategory.RECOMMEND -> "home_category_recommend"
    HomeCategory.FOLLOW -> "home_category_follow"
    HomeCategory.POPULAR -> "home_category_popular"
    HomeCategory.LIVE -> "home_category_live"
    HomeCategory.ANIME -> "home_category_anime"
    HomeCategory.GAME -> "home_category_game"
    HomeCategory.KNOWLEDGE -> "home_category_knowledge"
    HomeCategory.TECH -> "home_category_tech"
}

internal fun resolveLiveSubCategoryLabelRes(subCategory: LiveSubCategory): String = when (subCategory) {
    LiveSubCategory.FOLLOWED -> "home_live_subcategory_followed"
    LiveSubCategory.POPULAR -> "home_live_subcategory_popular"
}

internal fun resolvePopularSubCategoryLabelRes(subCategory: PopularSubCategory): String = when (subCategory) {
    PopularSubCategory.COMPREHENSIVE -> "home_popular_subcategory_comprehensive"
    PopularSubCategory.RANKING -> "home_popular_subcategory_ranking"
    PopularSubCategory.WEEKLY -> "home_popular_subcategory_weekly"
    PopularSubCategory.PRECIOUS -> "home_popular_subcategory_precious"
}

internal fun resolveTodayWatchModeLabelRes(mode: TodayWatchMode): String = when (mode) {
    TodayWatchMode.RELAX -> "home_today_watch_mode_relax"
    TodayWatchMode.LEARN -> "home_today_watch_mode_learn"
}

@Immutable
data class TodayWatchCardUiConfig(
    val showUpRank: Boolean = true,
    val showReasonHint: Boolean = true,
    val queuePreviewLimit: Int = 6,
    val enableWaterfallAnimation: Boolean = true,
    val waterfallExponent: Float = 1.38f
)

/**
 * 单个分类的内容状态 (新增)
 */

@Stable
data class CategoryContent(
    val videos: ImmutableList<VideoItem> = persistentListOf(),
    val liveRooms: ImmutableList<LiveRoom> = persistentListOf(),
    val followedLiveRooms: ImmutableList<LiveRoom> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pageIndex: Int = 1, //  保存分页索引
    val hasMore: Boolean = true //  保存是否还有更多数据
)

/**
 * 首页 UI 状态
 *  性能优化：@Stable 告诉 Compose 此类字段变化可被追踪，优化重组
 */

@Stable
data class HomeUiState(
    // 兼容旧字段便于迁移（将被 categoryStates 替代）
    val videos: ImmutableList<VideoItem> = persistentListOf(),
    val liveRooms: ImmutableList<LiveRoom> = persistentListOf(),  // 热门直播
    val followedLiveRooms: ImmutableList<LiveRoom> = persistentListOf(),  // 🔴 [新增] 关注的主播直播
    val isLoading: Boolean = false,
    val error: String? = null,
    
    // 📺 [核心变更] 各分类独立状态缓存
    // 使用 Map 保存每个分类的数据，切换时直接读取
    val categoryStates: ImmutableMap<HomeCategory, CategoryContent> = persistentMapOf(),
    // 热门子页签需要独立缓存，切换时保留旧页内容并让 Pager 负责横向位移。
    val popularCategoryStates: ImmutableMap<PopularSubCategory, CategoryContent> = persistentMapOf(),
    
    val user: UserState = UserState(),
    val currentCategory: HomeCategory = HomeCategory.RECOMMEND,
    val liveSubCategory: LiveSubCategory = LiveSubCategory.FOLLOWED,
    val popularSubCategory: PopularSubCategory = PopularSubCategory.COMPREHENSIVE,
    val refreshKey: Long = 0L,
    val followingMids: ImmutableSet<Long> = persistentSetOf(),
    val messageUnreadCount: Int = 0,
    //  [新增] 标签页显示索引（独立于内容分类，用于特殊分类导航后保持标签位置）
    val displayedTabIndex: Int = 0,
    //  [彩蛋] 刷新成功后的趣味消息
    val refreshMessage: String? = null,
    //  [新增] 增量刷新新增条数（null 表示不展示）
    val refreshNewItemsCount: Int? = null,
    //  [新增] 新增条数提示触发键（用于一次性 UI 动效）
    val refreshNewItemsKey: Long = 0L,
    //  [新增] 新增条数提示已消费键（防止离开页面后重复弹出）
    val refreshNewItemsHandledKey: Long = 0L,
    //  [新增] 推荐流旧内容锚点（刷新前首条视频 bvid）
    val recommendOldContentAnchorBvid: String? = null,
    //  [新增] 推荐流中“旧内容起始”索引（用于插入分割线）
    val recommendOldContentStartIndex: Int? = null,
    //  [新增] 推荐流旧内容分割线已解锁的刷新键
    val recommendOldContentRevealKey: Long = 0L,
    //  [新增] 正在消散动画中的视频 BVIDs（动画完成后移除）
    val dissolvingVideos: ImmutableSet<String> = persistentSetOf(),
    //  [新增] 今日看什么推荐单
    val todayWatchMode: TodayWatchMode = TodayWatchMode.RELAX,
    val todayWatchPlan: TodayWatchPlan? = null,
    val todayWatchLoading: Boolean = false,
    val todayWatchError: String? = null,
    val todayWatchPluginEnabled: Boolean = false,
    val todayWatchCollapsed: Boolean = false,
    val todayWatchCardConfig: TodayWatchCardUiConfig = TodayWatchCardUiConfig(),
    //  [新增] 刷新撤销状态
    val undoAvailable: Boolean = false
)
