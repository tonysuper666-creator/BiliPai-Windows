package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.bangumi.BangumiPlayerState
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
    val addDownloadTask: (DownloadTask) -> Boolean,
    val applyPreferredVolume: (DesktopOriginalMpvSectionControl) -> Unit,
    val launch: (suspend CoroutineScope.() -> Unit) -> Job,
    val commitIfCurrent: (() -> Unit) -> Boolean,
    val owns: () -> Boolean,
    val ownsPlaybackSource: () -> Boolean,
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
