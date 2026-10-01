from pathlib import Path
import re,json
HERE=Path(__file__).parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';P=HERE/'prepared/manual/com/bilipai/desktop/ui';P.mkdir(parents=True,exist_ok=True)
s=(REPO/'app/src/main/java/com/android/purebilibili/feature/home/HomeUiState.kt').read_text(encoding='utf-8')
body=s[s.index('data class HomeUiState('):]
props=re.findall(r'val (\w+): ([^=\n]+)=',body)
vm=(REPO/'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt').read_text(encoding='utf-8')
props=[(n,t.strip()) for n,t in props if n in re.findall(r'val (\w+) = homeStateFlow',vm)]
header='''package com.bilipai.desktop.ui
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
'''
methods=''' fun getCategoryState(category:HomeCategory):StateFlow<CategoryContent>
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
 fun completeVideoDissolve(bvid:String)
 fun markTodayWatchVideoOpened(video:VideoItem)
 fun switchTodayWatchMode(mode:TodayWatchMode)
 fun setTodayWatchCollapsed(collapsed:Boolean)
 fun refreshTodayWatchOnly()
 suspend fun getPreviewVideoUrl(bvid:String,cid:Long):String?
 fun undoRefresh()
 fun blockCreator(video:VideoItem)
 fun markNotInterested(video:VideoItem,reason:RecommendationFeedbackReason,cardAnimationEnabled:Boolean)
}
'''
(P/'DesktopOriginalHomeStateOwner.kt').write_text(header+''.join(' val '+n+':StateFlow<'+t+'>\n'for n,t in props)+methods,encoding='utf-8',newline='\n')
print('required original state fields',len(props))
