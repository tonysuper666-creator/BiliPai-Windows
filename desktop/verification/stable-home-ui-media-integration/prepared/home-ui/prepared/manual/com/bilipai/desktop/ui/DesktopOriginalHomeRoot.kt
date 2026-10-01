package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.transition.*
import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.channels.Channel
import dev.chrisbanes.haze.HazeState

/** Every original Home route/action is supplied by the actual Root. Original nullable account
 * actions remain nullable; no mounted route can silently inherit the raw UI's empty defaults. */
internal class DesktopOriginalHomeNavigation(
 val onVideoClick:(HomeVideoClickRequest)->Unit,
 val onLogout:(()->Unit)?,
 val onAccountSwitchClick:(()->Unit)?,
 val onAvatarClick:()->Unit,
 val onProfileClick:()->Unit,
 val onSettingsClick:()->Unit,
 val onSearchClick:()->Unit,
 val onDynamicClick:()->Unit,
 val onHistoryClick:()->Unit,
 val onPartitionClick:()->Unit,
 val onWeeklySeriesClick:()->Unit,
 val onFavoriteClick:()->Unit,
 val onLikedVideosClick:()->Unit,
 val onLiveListClick:()->Unit,
 val onLiveSearchClick:()->Unit,
 val onLiveAreaClick:()->Unit,
 val onLiveFollowingClick:()->Unit,
 val onWatchLaterClick:()->Unit,
 val onDownloadClick:()->Unit,
 val onInboxClick:()->Unit,
 val onStoryClick:()->Unit,
 val onPluginsClick:()->Unit,
 val partitionVideoSourceRoute:String,
 val onPartitionVideoClick:(VideoItem)->Unit,
 val onLiveClick:(Long,String,String)->Unit,
 val onBangumiClick:(Int)->Unit,
 val onCategoryClick:(Int,String)->Unit,
 val onLiveAreaDetailClick:(Int,Int,String)->Unit,
 val onBangumiSeasonClick:(Long)->Unit,
 val onBangumiEpisodeClick:(Long,Long)->Unit,
 val onSpaceClick:(Long)->Unit,
)

/** Page lifetime follows the real retained nav entry and account epoch. Root supplies the existing
 * sole scroll/dock locals, transition owner and global window resources; this wrapper owns none. */
@Composable internal fun DesktopOriginalHomeRoot(
 owner:DesktopOriginalHomeStateOwner,
 environment:DesktopHomeEnvironment,
 media:DesktopHomeMediaPorts,
 platform:DesktopHomePlatform,
 metrics:DesktopHomeMetricHolder,
 errorAnimation:@Composable (String,Dp,Int)->Unit,
 navigation:DesktopOriginalHomeNavigation,
 scrollOffset:MutableFloatState,
 feedScrollInProgress:MutableState<Boolean>,
 scrollChannel:Channel<HomeScrollRequest>,
 bottomBarVisible:Boolean,
 bottomBarContentPadding:Dp,
 setBottomBarVisible:(Boolean)->Unit,
 transitionBackground:VideoCardTransitionBackgroundState,
 transitionClock:VideoCardTransitionClock?,
 globalHazeState:HazeState?,
 isTopLevelActive:Boolean,
 isReturningFromVideoDetail:Boolean,
 isQuickReturningFromVideoDetail:Boolean,
 onVideoDetailReturnAnimationConsumed:()->Unit,
) {
 CompositionLocalProvider(
  LocalDesktopHomeEnvironment provides environment,
  LocalDesktopHomeMediaPorts provides media,
  LocalDesktopHomePlatform provides platform,
  LocalDesktopHomeMetricHolder provides metrics,
  LocalDesktopHomeErrorAnimation provides errorAnimation,
  LocalHomeScrollOffset provides scrollOffset,
  LocalHomeFeedScrollInProgress provides feedScrollInProgress,
  LocalHomeScrollChannel provides scrollChannel,
  LocalBottomBarVisible provides bottomBarVisible,
  LocalBottomBarContentPadding provides bottomBarContentPadding,
  LocalSetBottomBarVisible provides setBottomBarVisible,
  LocalVideoCardTransitionBackgroundState provides transitionBackground,
  LocalVideoCardTransitionClock provides transitionClock,
 ) {
  HomeScreen(viewModel=owner,
   onVideoClick=navigation.onVideoClick,
   onLogout=navigation.onLogout,
   onAccountSwitchClick=navigation.onAccountSwitchClick,
   onAvatarClick=navigation.onAvatarClick,
   onProfileClick=navigation.onProfileClick,
   onSettingsClick=navigation.onSettingsClick,
   onSearchClick=navigation.onSearchClick,
   onDynamicClick=navigation.onDynamicClick,
   onHistoryClick=navigation.onHistoryClick,
   onPartitionClick=navigation.onPartitionClick,
   onWeeklySeriesClick=navigation.onWeeklySeriesClick,
   onFavoriteClick=navigation.onFavoriteClick,
   onLikedVideosClick=navigation.onLikedVideosClick,
   onLiveListClick=navigation.onLiveListClick,
   onLiveSearchClick=navigation.onLiveSearchClick,
   onLiveAreaClick=navigation.onLiveAreaClick,
   onLiveFollowingClick=navigation.onLiveFollowingClick,
   onWatchLaterClick=navigation.onWatchLaterClick,
   onDownloadClick=navigation.onDownloadClick,
   onInboxClick=navigation.onInboxClick,
   onStoryClick=navigation.onStoryClick,
   onPluginsClick=navigation.onPluginsClick,
   partitionVideoSourceRoute=navigation.partitionVideoSourceRoute,
   onPartitionVideoClick=navigation.onPartitionVideoClick,
   onLiveClick=navigation.onLiveClick,
   onBangumiClick=navigation.onBangumiClick,
   onCategoryClick=navigation.onCategoryClick,
   onLiveAreaDetailClick=navigation.onLiveAreaDetailClick,
   onBangumiSeasonClick=navigation.onBangumiSeasonClick,
   onBangumiEpisodeClick=navigation.onBangumiEpisodeClick,
   onSpaceClick=navigation.onSpaceClick,
   globalHazeState=globalHazeState,
   isTopLevelActive=isTopLevelActive,
   isReturningFromVideoDetail=isReturningFromVideoDetail,
   isQuickReturningFromVideoDetail=isQuickReturningFromVideoDetail,
   onVideoDetailReturnAnimationConsumed=onVideoDetailReturnAnimationConsumed)
 }
}
