package com.bilipai.desktop.ui
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.model.response.CreatorCardStats
import com.android.purebilibili.feature.video.progress.PbpProgressData
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import okhttp3.Call

/** View of the same raw protocol in the actual caller's coroutine context.
 * A capture factory must supply an extended original repository in that one
 * invocation. Wrong/absent context fails rather than reading latest credentials.
 * The synchronous cooldown port is a read of the existing admitted protocol state
 * for UI decisions; it is not a retained, completed request repository.
 */
internal class DesktopOriginalVideoOwnerRepositoryView(
    private val invocations:DesktopOriginalVideoPlaybackInvocationPorts,
    private val readOwnedCooldown:(Long)->Long,
) : DesktopOriginalVideoOwnerRepository,
    DesktopOriginalVideoLoadRepository by invocations.repository {
    private fun request():DesktopOriginalVideoOwnerRepository =
        invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRepository
            ?: error("The same captured original metadata repository is required")
    override val primaryApi:BilibiliApi get()=request().primaryApi
    override val playbackCalls:Call.Factory get()=request().playbackCalls
    override fun getAppApiCooldownRemainingMs(nowMs:Long):Long {
        invocations.assertCurrent()
        return readOwnedCooldown(nowMs)
    }
    override suspend fun getPlayUrlDataForPlaybackTransition(bvid:String,cid:Long,qn:Int,audioLang:String?):PlayUrlData? = request().getPlayUrlDataForPlaybackTransition(bvid,cid,qn,audioLang)
    override suspend fun getExactPremiumPlayUrl(bvid:String,cid:Long,targetQn:Int,audioLang:String?):PlayUrlData? = request().getExactPremiumPlayUrl(bvid,cid,targetQn,audioLang)
    override suspend fun refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn:Boolean,cachedIsVip:Boolean,storedQuality:Int,autoHighestEnabled:Boolean):Boolean = request().refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn,cachedIsVip,storedQuality,autoHighestEnabled)
    override suspend fun getCreatorCardStats(mid:Long):Result<CreatorCardStats> = request().getCreatorCardStats(mid)
    override suspend fun getBgmList(aid:Long,bvid:String,cid:Long):Result<List<BgmInfo>> = request().getBgmList(aid,bvid,cid)
    override suspend fun getBgmDetail(musicId:String,aid:Long,cid:Long):Result<BgmDetailData?> = request().getBgmDetail(musicId,aid,cid)
    override suspend fun getBgmRecommendVideos(musicId:String,aid:Long,cid:Long,page:Int,pageSize:Int):Result<List<BgmRecommendVideo>> = request().getBgmRecommendVideos(musicId,aid,cid,page,pageSize)
    override suspend fun getVideoshot(bvid:String,cid:Long):VideoshotData? = request().getVideoshot(bvid,cid)
    override suspend fun getPlayerInfo(bvid:String,cid:Long):Result<PlayerInfoData> = request().getPlayerInfo(bvid,cid)
    override suspend fun getPbpProgressData(bvid:String,cid:Long,aid:Long):Result<PbpProgressData> = request().getPbpProgressData(bvid,cid,aid)
    override suspend fun getInteractEdgeInfo(bvid:String,graphVersion:Long,edgeId:Long?):Result<InteractEdgeInfoData> = request().getInteractEdgeInfo(bvid,graphVersion,edgeId)
    override suspend fun getAiSummary(bvid:String,cid:Long,upMid:Long):Result<AiSummaryResponse> = request().getAiSummary(bvid,cid,upMid)
    override suspend fun getSubtitleCues(subtitleUrl:String,bvid:String,cid:Long,subtitleId:Long,subtitleIdStr:String,subtitleLan:String):Result<List<SubtitleCue>> = request().getSubtitleCues(subtitleUrl,bvid,cid,subtitleId,subtitleIdStr,subtitleLan)
    override suspend fun reportPlayHeartbeat(bvid:String,cid:Long,playedTime:Long,realPlayedTime:Long,startTsSec:Long,aid:Long):Boolean = request().reportPlayHeartbeat(bvid,cid,playedTime,realPlayedTime,startTsSec,aid)
}
