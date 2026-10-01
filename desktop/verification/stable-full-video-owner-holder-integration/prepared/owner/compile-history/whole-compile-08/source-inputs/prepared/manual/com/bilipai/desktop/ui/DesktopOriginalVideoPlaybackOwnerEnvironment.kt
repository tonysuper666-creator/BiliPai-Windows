package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.plugin.PlayerPlugin
import com.android.purebilibili.core.plugin.Plugin
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.VideoNoteSnapshot
import com.android.purebilibili.data.repository.VideoNoteSavePayload
import com.android.purebilibili.data.repository.CreatorCardStats
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.plugin.SponsorBlockPlugin
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.progress.PbpProgressData
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import kotlin.reflect.KClass

/** This is an invocation VIEW, never a second video repository/cache/client.
 * Root must supply the selected original protocol on the captured raw binding.
 * primaryApi/playbackCalls also require that current invocation, not latest credentials.
 */
internal interface DesktopOriginalVideoOwnerRepository : DesktopOriginalVideoLoadRepository {
    val primaryApi: BilibiliApi
    val playbackCalls: Call.Factory
    suspend fun getVideoDetails(bvid:String):Result<Pair<ViewInfo,PlayUrlData>> = getVideoDetails(bvid,0L,0L,null,null)
    suspend fun getPlayUrlDataForPlaybackTransition(bvid:String,cid:Long,qn:Int,audioLang:String?=null):PlayUrlData?
    suspend fun getExactPremiumPlayUrl(bvid:String,cid:Long,targetQn:Int,audioLang:String?=null):PlayUrlData?
    suspend fun refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn:Boolean,cachedIsVip:Boolean,storedQuality:Int,autoHighestEnabled:Boolean):Boolean
    suspend fun getCreatorCardStats(mid:Long):Result<CreatorCardStats>
    suspend fun getBgmList(aid:Long,bvid:String,cid:Long):Result<List<BgmInfo>>
    suspend fun getVideoshot(bvid:String,cid:Long):VideoshotData?
    suspend fun getPlayerInfo(bvid:String,cid:Long):Result<PlayerInfoData>
    suspend fun getPbpProgressData(bvid:String,cid:Long,aid:Long=0L):Result<PbpProgressData>
    suspend fun getInteractEdgeInfo(bvid:String,graphVersion:Long,edgeId:Long?=null):Result<InteractEdgeInfoData>
    suspend fun getAiSummary(bvid:String,cid:Long,upMid:Long):Result<AiSummaryResponse>
    suspend fun getSubtitleCues(subtitleUrl:String,bvid:String,cid:Long,subtitleId:Long=0L,subtitleIdStr:String="",subtitleLan:String=""):Result<List<SubtitleCue>>
    suspend fun reportPlayHeartbeat(bvid:String,cid:Long,playedTime:Long,realPlayedTime:Long,startTsSec:Long,aid:Long=0L):Boolean
    fun getAppApiCooldownRemainingMs(nowMs:Long=System.currentTimeMillis()):Long
}

/** Original note schemas; implementation is the complete selected original protocol
 * over this exact operation's API, session and cancellation admission. */
internal interface DesktopOriginalVideoOwnerNotes {
    suspend fun getVideoNoteSnapshot(aid:Long):Result<VideoNoteSnapshot>
    suspend fun savePrivateNote(payload:VideoNoteSavePayload):Result<String>
    suspend fun deletePrivateNote(aid:Long,noteId:String):Result<Unit>
}

internal interface DesktopOriginalVideoOwnerActions : DesktopOriginalVideoInitialActions {
    suspend fun checkWatchLaterStatus(aid:Long):Boolean
    suspend fun checkDislikeStatus(aid:Long):Boolean
    suspend fun createFavFolder(title:String,intro:String,isPrivate:Boolean):Result<Boolean>
    suspend fun getFollowGroupTags():Result<List<RelationTagItem>>
    suspend fun getUserFollowGroupIds(mid:Long):Result<Set<Long>>
    suspend fun overwriteFollowGroupIds(targetMids:Set<Long>,selectedTagIds:Set<Long>):Result<Boolean>
}

internal interface DesktopOriginalVideoOwnerAccount {
    val hasSession:Boolean
    val hasAccessToken:Boolean
    val mid:Long?
    fun updatePrimaryVip(isVip:Boolean)
}

/** Same original playlist authority exposed as its original state/commands. */
internal interface DesktopOriginalVideoOwnerPlaylist {
    val playlist:StateFlow<List<PlaylistItem>>
    val currentIndex:StateFlow<Int>
    val playMode:StateFlow<PlayMode>
    val isExternalPlaylist:StateFlow<Boolean>
    val externalPlaylistSource:StateFlow<ExternalPlaylistSource>
    fun playAt(index:Int):PlaylistItem?
    fun playNext():PlaylistItem?
    fun playPrevious():PlaylistItem?
    fun setPlaylist(items:List<PlaylistItem>,startIndex:Int)
}

internal interface DesktopOriginalVideoOwnerMini {
    var onNavigateNextCallback:(()->Boolean)?
    var onNavigatePreviousCallback:(()->Boolean)?
    var onHasNextNavigationCallback:(()->Boolean)?
    var onHasPreviousNavigationCallback:(()->Boolean)?
    val currentBvid:String?
    val currentCid:Long?
    val isActive:Boolean
    val player:DesktopOriginalMpvSectionControl?
    fun syncCurrentVideoInfo(state:VideoPlaybackUiState.Success)
    fun updateCachedVideoTags(bvid:String,tags:List<VideoTag>)
}

internal interface DesktopOriginalVideoOwnerDownload {
    val tasks:StateFlow<Map<String,DownloadTask>>
    fun addTask(task:DownloadTask):Boolean
    fun getVideoTask(bvid:String,cid:Long):DownloadTask?
    suspend fun saveImageToGallery(context:DesktopOriginalPlayerSettingsContext,url:String,title:String):Boolean
}

internal interface DesktopOriginalVideoOwnerNetwork {
    fun isMobileData():Boolean
    fun isWifi():Boolean
    fun getDefaultQualityId(context:DesktopOriginalPlayerSettingsContext):Int {
        val prefs=context.getSharedPreferences("quality_settings",DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
        return if(isWifi()) prefs.getInt("wifi_quality",80) else prefs.getInt("mobile_quality",64)
    }
}
internal interface DesktopOriginalVideoOwnerCache {
    fun get(bvid:String,cid:Long):PlayUrlData?
    fun invalidate(bvid:String,cid:Long)
}
/** REQUIRED cache consumer. A no-op writer is invalid: original cachedSegments
 * means bytes stored for actual playback consumption, not just a successful GET.
 * Root's MPV demux cache has no CacheWriter ingress yet; capability stays pending. */
internal interface DesktopOriginalCdnRangeCache {
    suspend fun prefetchRange(url:String,cacheKey:String,position:Long,length:Long,headers:Map<String,String>)
}
internal interface DesktopOriginalVideoOwnerAnalytics {
    fun logVideoPlay(videoId:String,title:String,author:String)
    fun logQualityChange(videoId:String,fromQuality:Int,toQuality:Int)
}
internal fun interface DesktopOriginalVideoOwnerCrash {
    fun reportVideoError(videoId:String,type:String,message:String)
}

/** Typed view of existing subject reply requests / same streaming image provider.
 * Root must mount this over its sole Operations/catalog, not another reply store. */
internal interface DesktopOriginalVideoOwnerComments {
    suspend fun getEmotePackages():Result<List<EmotePackage>>
    suspend fun searchMentionUsers(keyword:String):Result<List<MentionSearchUser>>
    suspend fun addComment(aid:Long,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean):Result<ReplyItem?>
    suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture>
}

/** Actual raw Danmaku owner + sole original mutation protocol; no CID cache here. */
internal interface DesktopOriginalVideoOwnerDanmaku {
    fun isDanmakuServerDisabled(cid:Long):Boolean
    suspend fun sendDanmaku(aid:Long,cid:Long,message:String,progress:Long,color:Int,fontSize:Int,mode:Int,colorful:Boolean,upIdentity:Boolean):Result<SendDanmakuData>
    suspend fun sendAttentionCommandDanmaku(aid:Long,cid:Long,progress:Long):Result<CommandDanmakuData>
    suspend fun getDanmakuThumbupState(cid:Long,dmid:Long):Result<com.android.purebilibili.data.repository.DanmakuThumbupState>
    suspend fun recallDanmaku(cid:Long,dmid:Long):Result<String>
    suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean):Result<Unit>
    suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String):Result<Unit>
}

/** SAME Runtime provider objects and generation; implementation must serialize every
 * playback callback against the retained Runtime generation, never construct plugins.
 * The native inherited mute ledger is observed by the existing position observer.
 */
internal interface DesktopOriginalVideoOwnerPlugins {
    val sponsorBlock:SponsorBlockPlugin
    fun enabledPlayerPlugins():List<PlayerPlugin>
    fun enabledPlugins():List<Plugin>
    fun <T:Plugin> enabledPlugins(type:KClass<T>):List<T>
    fun observeInheritedPluginMute()
    /** Existing generation is retained for same BV/CID adoption. New native
     * publication establishes the one Runtime generation before this observer. */
    suspend fun ensureSponsorLoaded(bvid:String,cid:Long)
    fun onSponsorDisabled()
    suspend fun onPositionUpdate(plugin:PlayerPlugin,positionMs:Long):com.android.purebilibili.core.plugin.SkipAction?
    fun onUserSeek(plugin:PlayerPlugin,positionMs:Long)
    fun onVideoEnd(plugin:PlayerPlugin)
    fun markSponsorSkipped(plugin:SponsorBlockPlugin,segmentId:String):SponsorSegment?
    suspend fun voteSponsorSegment(plugin:SponsorBlockPlugin,segmentId:String,vote:Int):Result<Unit>
    suspend fun submitSponsorSegment(plugin:SponsorBlockPlugin,bvid:String,cid:Long,videoDurationSeconds:Float,startMs:Long,endMs:Long,category:String,actionType:String):Result<List<SponsorSegment>>
    /** Actual native seek success, same accepted lease/generation and original
     * consent are prerequisites for the original history + optional view ping. */
    suspend fun recordSponsorSkip(record:SponsorBlockSkipRecord)
    /** Rewrite is a captured NEW request; health/probe refer to ACCEPTED media. */
    fun rewritePlaybackCandidates(plugin:PlaybackCdnPlugin,videoUrls:List<String>,audioUrls:List<String>):PlaybackCdnRewriteResult
    fun buildPlaybackCdnDiagnostics(plugin:PlaybackCdnPlugin,videoUrls:List<String>,sources:List<PlaybackCdnCandidateSource>):List<CdnLineDiagnostic>
    suspend fun probePlaybackCdnCandidates(plugin:PlaybackCdnPlugin,videoUrls:List<String>,sources:List<PlaybackCdnCandidateSource>):List<CdnLineDiagnostic>
    fun recordPlaybackCdnEvent(plugin:PlaybackCdnPlugin,url:String,event:CdnHealthEvent)
    fun isAdaptivePrefetchEnabled(plugin:PlaybackCdnPlugin):Boolean
}

/** All external effects are REQUIRED actual same-owner ports. Constructor intentionally
 * supplies no default actor, account, networking, capabilities or empty callback. */
internal class DesktopOriginalVideoPlaybackOwnerEnvironment(
    val scope:CoroutineScope,
    val settings:DesktopOriginalPlayerSettingsContext,
    val invocations:DesktopOriginalVideoPlaybackInvocationPorts,
    val repository:DesktopOriginalVideoOwnerRepository,
    val notes:DesktopOriginalVideoOwnerNotes,
    val actions:DesktopOriginalVideoOwnerActions,
    val useCase:DesktopOriginalVideoPlaybackUseCaseEnvironment,
    val interactionUseCase:VideoInteractionUseCase,
    val account:DesktopOriginalVideoOwnerAccount,
    val mini:DesktopOriginalVideoOwnerMini,
    val playlist:DesktopOriginalVideoOwnerPlaylist,
    val plugins:DesktopOriginalVideoOwnerPlugins,
    val download:DesktopOriginalVideoOwnerDownload,
    val network:DesktopOriginalVideoOwnerNetwork,
    val cache:DesktopOriginalVideoOwnerCache,
    val cdnRangeCache:DesktopOriginalCdnRangeCache,
    val analytics:DesktopOriginalVideoOwnerAnalytics,
    val crash:DesktopOriginalVideoOwnerCrash,
    val comments:DesktopOriginalVideoOwnerComments,
    val danmaku:DesktopOriginalVideoOwnerDanmaku,
    val background:StateFlow<Boolean>,
    private val isCurrent:()->Boolean,
    private val admission:((()->Unit)->Boolean),
) {
    fun assertCurrent() {
        if(!isCurrent() || !scope.isActive) throw CancellationException("Original video owner retired")
    }
    fun commit(block:()->Unit):Boolean = isCurrent() && scope.isActive && admission(block)
}
