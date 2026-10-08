package com.bilipai.desktop.ui

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** These are the original request/result models. A Root binding must call its ONE shared
 * Discovery/API/account graph; no flattened DiscoveryPage or second cache is substituted. */
/** One successful nav response with its ORIGINAL request/caller admission. This is
 * a transient result carrier, not a nav cache, state owner or successful-login stamp. */
internal class DesktopHomeNavPublication internal constructor(
 val data:NavData,
 private val publishCurrent:((()->Unit))->Unit,
 private val checkCurrent:()->Unit,
) {
 fun publish(block:()->Unit) = publishCurrent(block)
 fun assertCurrent() = checkCurrent()
}
internal interface DesktopHomeVideoRequests {
 suspend fun getHomeVideos(idx:Int):Result<List<VideoItem>>
 suspend fun getPopularVideos(page:Int):Result<List<VideoItem>>
 suspend fun getRankingVideos(rid:Int,type:String):Result<List<VideoItem>>
 suspend fun getWeeklyMustWatchVideos():Result<List<VideoItem>>
 suspend fun getPreciousVideos():Result<List<VideoItem>>
 suspend fun getRegionVideos(tid:Int,page:Int):Result<List<VideoItem>>
 suspend fun getNavInfo():Result<DesktopHomeNavPublication>
 suspend fun getPreviewVideoUrl(bvid:String,cid:Long):String?
 suspend fun isVerticalVideo(bvid:String,aid:Long=0L):Boolean
}
internal interface DesktopHomeHistoryRequests {suspend fun getHistoryList(ps:Int,max:Long,viewAt:Long,business:String):Result<HistoryResult>}
internal interface DesktopHomeLiveRequests {suspend fun getFollowedLive(page:Int):Result<List<LiveRoom>>;suspend fun getLiveRooms(page:Int):Result<List<LiveRoom>>}
internal interface DesktopHomeMessageRequests {suspend fun getUnreadCount():Result<MessageUnreadData>;suspend fun getFeedUnread():Result<MessageFeedUnreadData>}
internal interface DesktopHomeActionRequests {
 suspend fun toggleWatchLater(aid:Long,add:Boolean):Result<Boolean>
 suspend fun submitRecommendationFeedback(metadata:RecommendationFeedbackMetadata,reason:RecommendationFeedbackReason):Result<Unit>
}
internal interface DesktopHomeFollowRequests {
 fun currentUpdateBaseline(scope:DynamicFeedScope,type:String):String
 suspend fun getDynamicFeed(refresh:Boolean,scope:DynamicFeedScope,type:String,incrementalRefresh:Boolean):Result<DynamicFeedFetchResult>
 fun hasMoreData(scope:DynamicFeedScope,type:String):Boolean
 fun syncPaginationAfterRefresh(scope:DynamicFeedScope,type:String,offset:String,hasMore:Boolean)
}
internal interface DesktopHomeBlockedRequests {
 fun getAllBlockedUps():Flow<List<BlockedUp>>
 suspend fun blockUp(mid:Long,name:String,face:String)
 suspend fun blockUpWithBilibiliSync(mid:Long,name:String,face:String):BlockedUpWriteResult
}
internal interface DesktopHomeFollowingRequests {suspend fun getFollowings(mid:Long,page:Int,pageSize:Int):FollowingsResponse}
internal interface DesktopHomeIdentityAnalytics {fun syncUserContext(mid:Long?,isVip:Boolean,privacyModeEnabled:Boolean)}

/** Captured per naventry+epoch; Root supplies atomic session admission and its actual retained
 * dispatcher. commitIfCurrent must execute the block inside the real SessionStore admission.
 * View-state writes use the SAME sole Home state and do not create account/cache authorities. */
internal class DesktopHomeDataEnvironment(
 val capturedEpoch:Long,
 val parentScope:CoroutineScope,
 val isCurrent:()->Boolean,
 val commitIfCurrent:((()->Unit))->Boolean,
 val isLoggedIn:()->Boolean,
 val isPrivacyModeEnabledSync:()->Boolean,
 val analytics:DesktopHomeIdentityAnalytics,
 val recommendationContext:DesktopPluginContext,
 val followingCache:DesktopHomeFollowingCache,
 val incrementalTimelineRefresh:StateFlow<Boolean>,
 val homeRefreshTipVisible:StateFlow<Boolean>,
 val video:DesktopHomeVideoRequests,
 val history:DesktopHomeHistoryRequests,
 val live:DesktopHomeLiveRequests,
 val messages:DesktopHomeMessageRequests,
 val actions:DesktopHomeActionRequests,
 val follow:DesktopHomeFollowRequests,
 val blockedUps:DesktopHomeBlockedRequests,
 val following:DesktopHomeFollowingRequests,
 val feedback:(String)->Unit,
) {
 val todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(recommendationContext, isCurrent, commitIfCurrent)
}
