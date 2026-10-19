package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.bangumi.BangumiFollowSession
import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import com.android.purebilibili.feature.bangumi.currentBangumiFollowSession
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.plugin.PlaybackCdnPlugin
import com.android.purebilibili.feature.video.playback.audio.AudioQualityOption
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** Required operation views of existing Root services. This contract does not
 * construct repositories, clients, settings, credentials, a Store or a player.
 * The full original PGC VM cannot be constructed until Root supplies every port.
 */
internal interface DesktopOriginalBangumiPlayerRequests {
    suspend fun getSeasonDetail(seasonId: Long = 0L, epId: Long = 0L): Result<BangumiDetail>
    suspend fun getPugvSeasonDetail(seasonId: Long = 0L, epId: Long = 0L): Result<BangumiDetail>
    suspend fun getBangumiPlayUrl(epId: Long, qn: Int = 80, cid: Long = 0L,
        bvid: String? = null, seasonId: Long? = null, aid: Long = 0L, isCourse: Boolean = false): Result<BangumiVideoInfo>
    suspend fun getMyFollowBangumi(type: Int, page: Int, pageSize: Int): Result<MyFollowBangumiData>
    suspend fun followBangumi(seasonId: Long, isCourse: Boolean = false): Result<Boolean>
    suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean = false): Result<Boolean>
    suspend fun updateBangumiFollowStatus(seasonId: Long, status: Int): Result<Boolean>
}

internal interface DesktopOriginalBangumiPlayerAccount {
    fun isPlaybackVip(): Boolean
    fun isPlaybackLoggedIn(): Boolean
    suspend fun refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn: Boolean, cachedIsVip: Boolean,
        storedQuality: Int, autoHighestEnabled: Boolean): Boolean
    fun hasPlaybackSessionCookie(): Boolean
    fun playbackAccessToken(): String?
    fun hasPrimarySessionCookie(): Boolean
    suspend fun reportPlayHeartbeat(bvid: String, cid: Long, playedTime: Long,
        aid: Long, epid: Long, sid: Long, videoType: Int, subType: Int?): Boolean
}

/** One selected source plan published by the original same native owner.
 * Implementations must carry the exact captured Binding, caller Job, epoch,
 * original SessionStore request token, Referer, all DURL segments and cache plan.
 * These functions never call Section.load or create a second source actor.
 */
internal interface DesktopOriginalBangumiNativePublication {
    suspend fun beginEpisode(detail: BangumiDetail, episode: BangumiEpisode)
    /** Legacy test/ports keep their existing begin behavior. The real Root
     * captures only this request's metadata retirement and a fresh LAZY launch. */
    suspend fun beginEpisodeWithAuthorizationRecapture(detail: BangumiDetail, episode: BangumiEpisode,
        isIntentCurrent: () -> Boolean,
        onRetired: (Job, () -> Boolean, (() -> Unit) -> Boolean) -> Unit) = beginEpisode(detail, episode)
    fun launchAuthorizationRecapture(block: suspend CoroutineScope.() -> Unit): Job {
        throw CancellationException("PGC authorization recapture unavailable")
    }
    /** Actual Root implements a same-episode replacement capture. Legacy port
     * implementations fail closed rather than silently stopping a usable source. */
    suspend fun beginQualityReplacement(state: BangumiPlayerState.Success) {
        throw CancellationException("PGC quality replacement capture unavailable")
    }
    fun publishDash(videoUrl: String, audioUrl: String?, seekToMs: Long,
        resetPlayer: Boolean, referer: String, dashManifest: String?)
    fun publishSegments(segmentUrls: List<String>, seekToMs: Long,
        resetPlayer: Boolean, referer: String)
    fun stopCurrentEpisode()
    fun canUseCachedPlayback(state: BangumiPlayerState.Success): Boolean
}

/** Sponsor and raw-danmaku methods are exact request views of the same global
 * original domains. No second sponsor/danmaku transport or writer is permitted.
 */
internal interface DesktopOriginalBangumiBaseRequests {
    suspend fun getSponsorSegments(bvid: String): List<SponsorSegment>
    fun findSegmentAtPosition(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment?
    suspend fun getDanmakuRawData(cid: Long): ByteArray?
}

internal interface DesktopOriginalBangumiPlayerActions {
    suspend fun checkLikeStatus(aid: Long): Boolean
    suspend fun checkCoinStatus(aid: Long): Int
    /** v0.4.6 like read that reports a failed request instead of inventing "not liked". A port that
     * only has the older read derives success from it; the real Root overrides this with the
     * ordinary owner's status protocol so a transient failure keeps the like button unknown. */
    suspend fun queryLikeStatus(aid: Long): Result<Boolean> = try {
        Result.success(checkLikeStatus(aid))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
    /** Owner-scoped cached like state; absent means the status is not known yet. */
    fun cachedLikeStatus(aid: Long): Boolean? = null
}

/** Per-presenter lifetime attached to the SAME OriginalVideoAssembly. No
 * fallback/no-op constructor exists. Its admission must reject both a retired
 * NavEntry and a newer ordinary/portrait/PGC source token, including after IO.
 * Root must suspend ordinary end/heartbeat/sponsor producers while this original
 * presenter controls that source; the underlying assembly and Store stay shared.
 */
internal class DesktopOriginalBangumiPlayerEnvironment(
    val scope: CoroutineScope,
    val player: DesktopOriginalMpvSectionControl,
    val settings: DesktopOriginalPlayerSettingsContext,
    val requests: DesktopOriginalBangumiPlayerRequests,
    val account: DesktopOriginalBangumiPlayerAccount,
    val actions: DesktopOriginalBangumiPlayerActions,
    val interactionUseCase: VideoInteractionUseCase,
    val primaryApi: BilibiliApi,
    val progress: DesktopOriginalVideoProgressPort,
    val baseRequests: DesktopOriginalBangumiBaseRequests,
    val native: DesktopOriginalBangumiNativePublication,
    val sponsorAutoSkip: Flow<Boolean>,
    val defaultAudioQuality: () -> Int,
    val rememberedAudioQuality: () -> Int,
    val setAudioQuality: suspend (Int) -> Unit,
    val hevcSupported: () -> Boolean,
    val hdrSupported: () -> Boolean,
    val dolbyAudioSupported: () -> Boolean,
    val dolbyAudioSoftwareDecoded: () -> Boolean,
    val enabledCdnPlugin: () -> PlaybackCdnPlugin?,
    private val rewriteCandidatesPort: suspend (PlaybackCdnPlugin, List<String>, List<String>) -> com.android.purebilibili.feature.plugin.PlaybackCdnRewriteResult,
    private val externalPlaylistPort: (List<PlaylistItem>, Int, ExternalPlaylistSource) -> Unit,
    /** Capture the original task and exact accepted source synchronously at click;
     * the returned operation borrows the same global queue and performs IO outside admission. */
    val captureDownloadTask: (DownloadTask) -> (suspend () -> Boolean),
    val applyPreferredVolume: (DesktopOriginalMpvSectionControl) -> Unit,
    val launch: (suspend CoroutineScope.() -> Unit) -> Job,
    val commitIfCurrent: (() -> Unit) -> Boolean,
    val owns: () -> Boolean,
    val ownsPlaybackSource: () -> Boolean,
    val network: DesktopOriginalVideoOwnerNetwork,
    val dolbyVisionSupported: () -> Boolean,
    val publishReadOnlyDetail: suspend (BangumiDetail, BangumiEpisode, Int, Long, Boolean,
        (Job) -> Boolean) -> Unit,
    val prepareMedia: suspend () -> Unit,
    val captureLoadCaller: suspend () -> Job,
    /** The v0.4.6 PGC danmaku send and server-disabled read, through the ordinary owner's
     * existing send protocol. The default fails closed: a port set without it cannot send. */
    val danmaku: DesktopOriginalBangumiPlayerDanmaku = DesktopOriginalBangumiUnavailableDanmaku,
    /** Opaque identity of the signed-in account for the follow caches. The default is the
     * single constant identity (no account tracking); Root supplies the account epoch. */
    val followSession: () -> BangumiFollowSession = ::currentBangumiFollowSession,
    /** The ordinary owner's cover save (file admission included). Fails closed by default. */
    val saveImageToGallery: suspend (DesktopOriginalPlayerSettingsContext, String, String) -> Boolean =
        { _, _, _ -> false },
) {
    /** Read the existing Root quality mirror once per initial PGC request. */
    fun autoHighestQualityEnabled(): Boolean {
        assertCurrent()
        settings.requireCurrent()
        val enabled = com.android.purebilibili.core.store.DesktopOriginalVideoOwnerSettings
            .getAutoHighestQualitySync(settings)
        settings.requireCurrent()
        assertCurrent()
        return enabled
    }
    /** Same network-dependent quality mirror as the ordinary original VM. */
    fun defaultQualityId(): Int {
        assertCurrent(); settings.requireCurrent()
        val quality = network.getDefaultQualityId(settings)
        settings.requireCurrent(); assertCurrent()
        return quality
    }
    suspend fun rewriteCandidates(plugin: PlaybackCdnPlugin, videoUrls: List<String>, audioUrls: List<String>) =
        rewriteCandidatesPort(plugin, videoUrls, audioUrls)
    fun publishExternalPlaylist(items: List<PlaylistItem>, startIndex: Int, source: ExternalPlaylistSource) =
        externalPlaylistPort(items, startIndex, source)
    fun assertCurrent() {
        if (!owns() || !scope.isActive) throw CancellationException("Original PGC presenter retired")
    }
    fun assertPlaybackSource() {
        assertCurrent()
        if (!ownsPlaybackSource()) throw CancellationException("Original PGC source replaced")
    }
}
