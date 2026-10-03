package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException

/** Raw response view of Root's existing owned APIs and playback authorization.
 * Every implementation must use captured account epoch + same request admission
 * and reject retirement before returning cached or network data. A selected
 * PlaybackSource cannot be converted back into these original response models.
 */
internal interface DesktopOriginalVideoLoadRepository {
    suspend fun getVideoInfoOnly(bvid:String,aid:Long,requestedCid:Long):Result<ViewInfo>
    suspend fun getInitialPlayUrlData(bvid:String,cid:Long,targetQuality:Int,audioLang:String?):PlayUrlData?
    suspend fun getVideoDetails(bvid:String,aid:Long,requestedCid:Long,targetQuality:Int?,audioLang:String?):Result<Pair<ViewInfo,PlayUrlData>>
    suspend fun getRelatedVideos(bvid:String):List<RelatedVideo>
    suspend fun getPlayUrlData(bvid:String,cid:Long,qn:Int,audioLang:String? = null):PlayUrlData?
    suspend fun getPlaybackNavInfo():Result<NavData>
    fun isPlaybackLoggedIn():Boolean
    fun isPlaybackVip():Boolean
    fun isUsingDedicatedPlaybackAccount():Boolean
    fun isAppApiCoolingDown():Boolean
}

internal interface DesktopOriginalVideoInitialActions {
    suspend fun checkFollowStatus(mid:Long):Boolean
    suspend fun checkFavoriteStatus(aid:Long):Boolean
    suspend fun checkLikeStatus(aid:Long):Boolean
    suspend fun checkCoinStatus(aid:Long):Int
}

/** Existing Root Library progress, with original BVID/CID lookup and millisecond
 * contract. No cache or persistence is instantiated by the original UseCase.
 */
internal interface DesktopOriginalVideoProgressPort {
    fun getCachedPosition(bvid:String,cid:Long):Long
    fun savePosition(bvid:String,cid:Long,positionMs:Long)
}

/** These are actual decoder/output capabilities, not original Android platform
 * defaults. Unknown or unavailable hardware needs a deliberate Root binding.
 */
internal interface DesktopOriginalVideoPlaybackCapabilities {
    fun isHevcSupported():Boolean
    fun isAv1Supported():Boolean
    fun isHdrSupported():Boolean
    fun isDolbyVisionSupported():Boolean
    fun isDolbyAtmosAudioSupported():Boolean
    fun isDolbySoftwareAudioDecoderRequired():Boolean
}

/** Android MediaSource creation/acceptance boundary. prepare methods keep captured
 * authorized headers, DASH byte ranges, source metadata and the original source
 * fallback. accept must publish through Root's sole existing MPV publication,
 * update the same accepted sourceVersion, and never load via a second decoder.
 * A native adaptive manifest path remains pending until actual MPV proof.
 */
internal interface DesktopOriginalVideoMediaPort {
    /** Required synchronous lexical span. Native Load needs these exact original
     * arguments before enqueue; no Store/native gate or waiting is implied. */
    fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit)
    fun prepareLegacyDash(videoUrl:String,audioUrl:String?,cdnCacheKeysByUrl:Map<String,String>):PlaybackSource
    fun prepareAdaptiveDash(source:AdaptiveDashPlaybackSource,cdnCacheKeysByUrl:Map<String,String>):PlaybackSource?
    fun prepareProgressive(url:String):PlaybackSource
    fun accept(source:PlaybackSource)
}

internal class DesktopOriginalVideoPlaybackUseCaseEnvironment(
    val context:DesktopPluginContext,
    val repository:DesktopOriginalVideoLoadRepository,
    val actions:DesktopOriginalVideoInitialActions,
    val progress:DesktopOriginalVideoProgressPort,
    val capabilities:DesktopOriginalVideoPlaybackCapabilities,
    val media:DesktopOriginalVideoMediaPort,
    val applyPreferredVolume:(DesktopOriginalMpvSectionControl)->Unit,
    val emoteMap:suspend ()->Map<String,String>,
    val updatePrimaryVip:(Boolean)->Unit,
    val dashSegmentRequestsEnabled:()->Boolean,
    private val logSeekCallback:(Long,Long,Long,Long)->Unit,
    private val isCurrent:()->Boolean,
) {
    fun assertOwned() {
        if (!isCurrent()) throw CancellationException("Original playback UseCase owner retired")
    }
    fun logSeek(targetPositionMs:Long,currentPositionMs:Long,bufferedPositionMs:Long,durationMs:Long) =
        logSeekCallback(targetPositionMs,currentPositionMs,bufferedPositionMs,durationMs)
}
