package com.bilipai.desktop.ui
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.flow.*
import kotlinx.collections.immutable.*
/** Required view-state/action projection from ONE original Home VM owner. No private list/cache, fake Flow,
 * default settings, optional action or second account authority is created by the Windows page.
 * Root must retain this owner per nav entry/epoch and recheck admission/receipt around repository awaits. */
interface DesktopOriginalHomeStateOwner {
 val isRefreshing:StateFlow<Boolean>
 val feedbackEvents:Flow<String>
 val user:StateFlow<UserState>
 val currentCategory:StateFlow<HomeCategory>
 val liveSubCategory:StateFlow<LiveSubCategory>
 val popularSubCategory:StateFlow<PopularSubCategory>
 val refreshKey:StateFlow<Long>
 val followingMids:StateFlow<ImmutableSet<Long>>
 val messageUnreadCount:StateFlow<Int>
 val displayedTabIndex:StateFlow<Int>
 val refreshMessage:StateFlow<String?>
 val refreshNewItemsCount:StateFlow<Int?>
 val refreshNewItemsKey:StateFlow<Long>
 val refreshNewItemsHandledKey:StateFlow<Long>
 val recommendOldContentAnchorBvid:StateFlow<String?>
 val recommendOldContentStartIndex:StateFlow<Int?>
 val recommendOldContentRevealKey:StateFlow<Long>
 val dissolvingVideos:StateFlow<ImmutableSet<String>>
 val todayWatchMode:StateFlow<TodayWatchMode>
 val todayWatchPlan:StateFlow<TodayWatchPlan?>
 val todayWatchLoading:StateFlow<Boolean>
 val todayWatchError:StateFlow<String?>
 val todayWatchPluginEnabled:StateFlow<Boolean>
 val todayWatchCollapsed:StateFlow<Boolean>
 val todayWatchCardConfig:StateFlow<TodayWatchCardUiConfig>
 val undoAvailable:StateFlow<Boolean>
 fun getCategoryState(category:HomeCategory):StateFlow<CategoryContent>
 fun getPopularCategoryState(subCategory:PopularSubCategory):StateFlow<CategoryContent>
 fun getPreloadVideosSnapshot(category:HomeCategory,popularSubCategory:PopularSubCategory):List<VideoItem>
 fun refresh()
 fun refresh(category:HomeCategory)
 fun updateDisplayedTabIndex(index:Int)
 fun switchCategory(category:HomeCategory)
 fun switchPopularSubCategory(subCategory:PopularSubCategory)
 fun switchLiveSubCategory(subCategory:LiveSubCategory)
 fun loadMore()
 fun markRefreshNewItemsHandled(key:Long)
 fun markRecommendOldContentDividerRevealed(key:Long)
 fun addToWatchLater(bvid:String,aid:Long)
 fun startVideoDissolve(bvid:String)
 fun completeVideoDissolve(bvid:String)
 fun markTodayWatchVideoOpened(video:VideoItem)
 fun switchTodayWatchMode(mode:TodayWatchMode)
 fun setTodayWatchCollapsed(collapsed:Boolean)
 fun refreshTodayWatchOnly()
 suspend fun getPreviewVideoUrl(bvid:String,cid:Long):String?
 fun undoRefresh()
 fun blockCreator(video:VideoItem)
 fun markNotInterested(video:VideoItem,reason:RecommendationFeedbackReason,dissolveAnimationEnabled:Boolean)
}
