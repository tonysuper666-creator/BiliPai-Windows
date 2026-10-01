package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.WbiUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.video.progress.PbpProgressData
import com.android.purebilibili.feature.video.subtitle.SubtitleCue
import com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.data.reportDesktopPlaybackHeartbeat
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One immutable invocation's protocol composition. Nothing here creates an HTTP
 * client, Store, cache, subtitle downloader, Job, or native player. Root constructs
 * it INSIDE the actual invocation Job, over that invocation's existing Binding.
 * The heartbeat function must use the same captured primary API/CSRF/receipt.
 * It must not call the older epoch-only Community report overload.
 */
internal class DesktopOriginalVideoOwnerRequestRepository(
    val binding: DesktopOriginalVideoRepositoryBinding,
    private val metadata: DesktopOriginalVideoOwnerMetadataProtocol,
    private val subtitleAssets: DesktopSubtitleAssets,
    private val heartbeat: suspend (String, Long, Long, Long, Long, Long) -> Boolean,
    primaryCsrf: () -> String?,
    private val writePrimaryVip: (Boolean) -> Unit,
) : DesktopOriginalVideoOwnerRepository,
    DesktopOriginalVideoLoadRepository by binding.rawRepository {
    override val primaryApi get() = binding.primaryApi
    override val playbackCalls get() = binding.playbackCalls

    private val creator = DesktopOriginalVideoCreatorCard(binding.primaryApi, binding::assertCurrent)
    private val bgm = DesktopOriginalBgmRepository(binding.primaryApi) { parameters ->
        binding.assertCurrent()
        val keys = binding.protocol.getWbiKeys()
        binding.assertCurrent()
        WbiUtils.sign(parameters, keys.first, keys.second).also { binding.assertCurrent() }
    }
    val noteProtocol = DesktopOriginalVideoNoteProtocol(DesktopOriginalVideoNoteEnvironment(
        api = binding.primaryApi,
        hasSession = binding::hasPrimarySession,
        csrf = primaryCsrf,
        assertOwned = binding::assertCurrent,
    ))
    fun updatePrimaryVip(isVip: Boolean) {
        binding.assertCurrent()
        writePrimaryVip(isVip)
        binding.assertCurrent()
    }

    override suspend fun getPlayUrlDataForPlaybackTransition(bvid: String, cid: Long, qn: Int,
        audioLang: String?): PlayUrlData? = binding.protocol.getPlayUrlDataForPlaybackTransition(bvid, cid, qn, audioLang)
    override suspend fun getExactPremiumPlayUrl(bvid: String, cid: Long, targetQn: Int,
        audioLang: String?): PlayUrlData? = binding.protocol.getExactPremiumPlayUrl(bvid, cid, targetQn, audioLang)
    override suspend fun refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn: Boolean, cachedIsVip: Boolean,
        storedQuality: Int, autoHighestEnabled: Boolean): Boolean =
        metadata.refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn, cachedIsVip, storedQuality, autoHighestEnabled)
    override suspend fun getCreatorCardStats(mid: Long): Result<CreatorCardStats> = creator.getCreatorCardStats(mid)
    override suspend fun getBgmList(aid: Long, bvid: String, cid: Long): Result<List<BgmInfo>> = bgm.getBgmList(aid, bvid, cid)
    override suspend fun getVideoshot(bvid: String, cid: Long): VideoshotData? = metadata.getVideoshot(bvid, cid)
    override suspend fun getPlayerInfo(bvid: String, cid: Long): Result<PlayerInfoData> = metadata.getPlayerInfo(bvid, cid)
    override suspend fun getPbpProgressData(bvid: String, cid: Long, aid: Long): Result<PbpProgressData> =
        metadata.getPbpProgressData(bvid, cid, aid)
    override suspend fun getInteractEdgeInfo(bvid: String, graphVersion: Long, edgeId: Long?): Result<InteractEdgeInfoData> =
        metadata.getInteractEdgeInfo(bvid, graphVersion, edgeId)
    override suspend fun getAiSummary(bvid: String, cid: Long, upMid: Long): Result<AiSummaryResponse> =
        metadata.getAiSummary(bvid, cid, upMid)
    override fun getAppApiCooldownRemainingMs(nowMs: Long): Long {
        binding.assertCurrent()
        return (binding.environment.state.appApiCooldownUntilMs - nowMs).coerceAtLeast(0L)
    }
    override suspend fun getSubtitleCues(subtitleUrl: String, bvid: String, cid: Long, subtitleId: Long,
        subtitleIdStr: String, subtitleLan: String): Result<List<SubtitleCue>> {
        currentCoroutineContext().ensureActive(); binding.assertCurrent()
        return try {
            val file = subtitleAssets.import(SubtitleTrackMeta(
                id = subtitleId, idStr = subtitleIdStr, lan = subtitleLan, lanDoc = subtitleLan,
                subtitleUrl = subtitleUrl,
            ), playbackCalls)
            currentCoroutineContext().ensureActive(); binding.assertCurrent()
            Result.success(subtitleAssets.cues(file))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive(); binding.assertCurrent()
            Result.failure(failure)
        }
    }
    override suspend fun reportPlayHeartbeat(bvid: String, cid: Long, playedTime: Long, realPlayedTime: Long,
        startTsSec: Long, aid: Long): Boolean {
        currentCoroutineContext().ensureActive(); binding.assertCurrent()
        val reported = heartbeat(bvid, cid, playedTime, realPlayedTime, startTsSec, aid)
        currentCoroutineContext().ensureActive(); binding.assertCurrent()
        return reported
    }
}

/** The original Notes protocol is selected from the exact coroutine invocation,
 * rather than retaining a completed Binding as the page-wide Notes authority. */
internal class DesktopOriginalVideoOwnerNotesView(
    private val invocations: DesktopOriginalVideoPlaybackInvocationPorts,
) : DesktopOriginalVideoOwnerNotes {
    private fun notes(): DesktopOriginalVideoNoteProtocol =
        (invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository)?.noteProtocol
            ?: error("The same captured video Notes protocol is required")
    override suspend fun getVideoNoteSnapshot(aid: Long): Result<VideoNoteSnapshot> = notes().getVideoNoteSnapshot(aid)
    override suspend fun savePrivateNote(payload: VideoNoteSavePayload): Result<String> = notes().savePrivateNote(payload)
    override suspend fun deletePrivateNote(aid: Long, noteId: String): Result<Unit> = notes().deletePrivateNote(aid, noteId)
}

/** Capture before any Runtime wait. This continuation's immutable request,
 * including its Job, is kept; a later load cannot replace it with latest state.
 * Invocation absence is a wiring error, not permission to mutate without a gate. */
internal fun DesktopOriginalVideoPlaybackInvocationPorts.captureCurrentRequestAdmission(): ((() -> Unit) -> Boolean) {
    val request = requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository
        ?: error("The same captured video request repository is required")
    request.binding.assertCurrent()
    return request.binding::admitCurrentMutation
}

/** The original deferred-signal VIP write has the SAME captured request path as
 * preferred-quality metadata. Root provides admitted synchronous primary reads;
 * this view owns neither account projection nor credential persistence. */
internal class DesktopOriginalVideoOwnerAccountView(
    private val invocations: DesktopOriginalVideoPlaybackInvocationPorts,
    private val readHasSession: () -> Boolean,
    private val readHasAccessToken: () -> Boolean,
    private val readPrimaryMid: () -> Long?,
) : DesktopOriginalVideoOwnerAccount {
    override val hasSession get() = readHasSession().also { invocations.assertCurrent() }
    override val hasAccessToken get() = readHasAccessToken().also { invocations.assertCurrent() }
    override val mid get() = readPrimaryMid().also { invocations.assertCurrent() }
    override fun updatePrimaryVip(isVip: Boolean) {
        val request = invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository
            ?: error("The same captured primary-account request is required")
        request.updatePrimaryVip(isVip)
    }
}

/** Concrete captured protocol assembly. onPrimaryVipReceiptRetired is a required
 * short Root event enqueue, performed AFTER Binding's Store/entry write returns.
 * Its consumer must start a NEW original load capture; it cannot retag this Binding.
 * No native IO, request cancellation, or join may run in that callback.
 */
internal fun createDesktopOriginalVideoOwnerRequestRepository(
    repository: DesktopRepository,
    binding: DesktopOriginalVideoRepositoryBinding,
    subtitleAssets: DesktopSubtitleAssets,
    privacy: DesktopSearchPreferences,
    visitorInitialized: (DesktopPlaybackAuthorizationReceipt, () -> Boolean) -> Boolean,
    onPrimaryVipReceiptRetired: (DesktopPlaybackAuthorizationReceipt) -> Unit,
): DesktopOriginalVideoOwnerRequestRepository {
    binding.assertCurrent()
    val updateVip: (Boolean) -> Unit = { vip ->
        val before = binding.receipt
        binding.updatePrimaryVip(vip)
        if (!repository.isPlaybackReceiptCurrent(before)) onPrimaryVipReceiptRetired(before)
    }
    val metadata = DesktopOriginalVideoOwnerMetadataProtocol(DesktopOriginalVideoMetadataEnvironment(
        load = binding.environment,
        protocol = binding.protocol,
        hasPrimarySession = binding::hasPrimarySession,
        hasPrimaryCsrf = binding::hasPrimaryCsrf,
        hasPrimaryBuvid = binding::hasPrimaryBuvid,
        hasPrimaryAccessToken = binding::hasPrimaryAccessToken,
        isBuvidInitialized = { binding.isBuvidInitialized(visitorInitialized) },
        updatePrimaryVip = updateVip,
    ))
    return DesktopOriginalVideoOwnerRequestRepository(binding, metadata, subtitleAssets,
        heartbeat = { bvid, cid, playedTime, realPlayedTime, startTsSec, aid ->
            binding.assertCurrent()
            reportDesktopPlaybackHeartbeat(
                privacyEnabled = privacy::isPrivacyModeEnabledSync,
                expectedEpoch = binding.receipt.accountEpoch,
                epoch = { repository.sessionEpoch },
                mid = binding::primaryMid,
                csrf = { binding.primaryCsrf().orEmpty() },
                bvid = bvid, cid = cid, playedTimeSec = playedTime, realPlayedTimeSec = realPlayedTime,
                startTsSec = startTsSec, aid = aid,
                onReported = { binding.assertCurrent(); HistoryRefreshBus.notifyChanged() },
            ) { fields ->
                currentCoroutineContext().ensureActive(); binding.assertCurrent()
                val response = binding.primaryApi.reportHeartbeat(fields)
                currentCoroutineContext().ensureActive(); binding.assertCurrent()
                response.code
            }
        }, primaryCsrf = binding::primaryCsrf, writePrimaryVip = updateVip)
}
