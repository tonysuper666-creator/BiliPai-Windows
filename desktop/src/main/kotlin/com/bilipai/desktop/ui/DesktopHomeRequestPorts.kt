package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Facade only. All selected request/parse/fallback/cache algorithms remain in original protocols;
 * account/CookieJar admission must additionally be provided by Root's owner-bound Call.Factory. */
internal class DesktopHomeRequestPorts(
    private val environment:DesktopHomeProtocolEnvironment,
    private val beginNavRequest: (Job) -> DesktopHomeNavRequestSource,
    private val observeNavResult: (DesktopHomeNavRequestSource, Boolean, Job) -> Unit,
) : AutoCloseable {
    private val originalVideo=DesktopOriginalHomeVideoProtocol(environment)
    private val originalHistory=DesktopOriginalHomeHistoryProtocol(environment.api)
    private val originalLive=DesktopOriginalHomeLiveProtocol(environment.api)
    private val originalMessages=DesktopOriginalHomeMessageProtocol(environment.messageApi)
    private val originalActions=DesktopOriginalHomeActionProtocol(environment)
    private fun assertOwned(){if(!environment.isCurrent())throw CancellationException("Home request owner retired")}
    private suspend fun <T> owned(block:suspend()->T):T {
        assertOwned();currentCoroutineContext().ensureActive()
        val value=block()
        currentCoroutineContext().ensureActive();assertOwned()
        return value
    }
    private suspend fun <T> result(block:suspend()->Result<T>):Result<T> = owned(block).also {
        (it.exceptionOrNull() as? CancellationException)?.let { cancelled->throw cancelled }
    }
    val video=object:DesktopHomeVideoRequests {
        override suspend fun getHomeVideos(idx:Int)=result {originalVideo.getHomeVideos(idx)}
        override suspend fun getPopularVideos(page:Int)=result {originalVideo.getPopularVideos(page)}
        override suspend fun getRankingVideos(rid:Int,type:String)=result {originalVideo.getRankingVideos(rid,type)}
        override suspend fun getWeeklyMustWatchVideos()=result {originalVideo.getWeeklyMustWatchVideos()}
        override suspend fun getPreciousVideos()=result {originalVideo.getPreciousVideos()}
        override suspend fun getRegionVideos(tid:Int,page:Int)=result {originalVideo.getRegionVideos(tid,page)}
        override suspend fun getNavInfo()=result {
            val caller = currentCoroutineContext()
            val callerJob = requireNotNull(caller[Job]) { "Home nav requires its actual caller Job" }
            caller.ensureActive()
            val source = beginNavRequest(callerJob)
            // Preserve the complete original parser: -101 is success(false), other failures stay Result.failure.
            val response = originalVideo.getNavInfo()
            caller.ensureActive()
            response.onSuccess { nav -> observeNavResult(source, nav.isLogin, callerJob) }
            response
        }
        override suspend fun getPreviewVideoUrl(bvid:String,cid:Long)=owned {originalVideo.getPreviewVideoUrl(bvid,cid)}
        override suspend fun isVerticalVideo(bvid:String,aid:Long)=owned {originalVideo.isVerticalVideo(bvid,aid)}
    }
    val history=object:DesktopHomeHistoryRequests {
        override suspend fun getHistoryList(ps:Int,max:Long,viewAt:Long,business:String)=result {
            originalHistory.getHistoryList(ps,max,viewAt,business)
        }
    }
    val live=object:DesktopHomeLiveRequests {
        override suspend fun getFollowedLive(page:Int)=result {originalLive.getFollowedLive(page)}
        override suspend fun getLiveRooms(page:Int)=result {originalLive.getLiveRooms(page)}
    }
    val messages=object:DesktopHomeMessageRequests {
        override suspend fun getUnreadCount()=result {originalMessages.getUnreadCount()}
        override suspend fun getFeedUnread()=result {originalMessages.getFeedUnread()}
    }
    val actions=object:DesktopHomeActionRequests {
        override suspend fun toggleWatchLater(aid:Long,add:Boolean)=result {originalActions.toggleWatchLater(aid,add)}
        override suspend fun submitRecommendationFeedback(metadata:RecommendationFeedbackMetadata,reason:RecommendationFeedbackReason)=result {
            originalActions.submitRecommendationFeedback(metadata,reason)
        }
    }
    val following=object:DesktopHomeFollowingRequests {
        override suspend fun getFollowings(mid:Long,page:Int,pageSize:Int)=owned {
            environment.api.getFollowings(vmid=mid,pn=page,ps=pageSize)
        }
    }
    /** Root may prime this very original repository before installing its Home VM. It remains
     * one one-shot original preload cache in the same retained epoch, never a second planner. */
    fun preloadHomeData(){assertOwned();originalVideo.preloadHomeData()}
    fun isHomeDataReady():Boolean=environment.isCurrent() && originalVideo.isHomeDataReady()
    override fun close(){originalVideo.close()}
}
