package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalBangumiPagesRequests
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.data.reportDesktopPlaybackHeartbeat
import kotlinx.coroutines.*

/** Stateless views of the SAME immutable OriginalVideo invocation and the
 * already produced original page/Hub protocols. No fresh API/client/credentials.
 * Root supplies the exact in-progress native capture for playurl operations;
 * completed request objects are never retained as a presenter-wide authority.
 */
internal class DesktopOriginalBangumiPlayerRequestsView(
    private val pages: DesktopOriginalBangumiPagesRequests,
    private val capturedPlaybackBinding: () -> DesktopOriginalVideoRepositoryBinding,
    private val assertPresenterCurrent: () -> Unit,
    private val captureInitialDetailSource: suspend (Long, Long, Boolean) -> DesktopBangumiInitialDetailSource? = { _, _, _ -> null },
) : DesktopOriginalBangumiPlayerRequests {
    private suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); assertPresenterCurrent()
        val value = block()
        currentCoroutineContext().ensureActive(); assertPresenterCurrent()
        return value
    }
    override suspend fun getSeasonDetail(seasonId: Long, epId: Long) = owned {
        val source = captureInitialDetailSource(seasonId, epId, false)
        val responseFailure: ((Int, String) -> Throwable)? = source?.let { captured ->
            { code, message -> captured.failure(code, message) }
        }
        pages.getSeasonDetail(seasonId, epId, responseFailure)
    }
    override suspend fun getPugvSeasonDetail(seasonId: Long, epId: Long) = owned {
        val source = captureInitialDetailSource(seasonId, epId, true)
        val responseFailure: ((Int, String) -> Throwable)? = source?.let { captured ->
            { code, message -> captured.failure(code, message) }
        }
        pages.getPugvSeasonDetail(seasonId, epId, responseFailure)
    }
    override suspend fun getBangumiPlayUrl(epId: Long, qn: Int, cid: Long, bvid: String?, seasonId: Long?, aid: Long, isCourse: Boolean) = owned {
        val binding = capturedPlaybackBinding().also { it.assertCurrent() }
        binding.bangumiPlayRequests().getBangumiPlayUrl(epId, qn, cid, bvid, seasonId, aid, isCourse)
            .also { binding.assertCurrent() }
    }
    override suspend fun getMyFollowBangumi(type: Int, page: Int, pageSize: Int) = owned { pages.getMyFollowBangumi(type, page = page, pageSize = pageSize) }
    override suspend fun followBangumi(seasonId: Long, isCourse: Boolean) = owned { pages.followBangumi(seasonId, isCourse) }
    override suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean) = owned { pages.unfollowBangumi(seasonId, isCourse) }
    override suspend fun updateBangumiFollowStatus(seasonId: Long, status: Int) = owned { pages.updateBangumiFollowStatus(seasonId, status) }
}

/** Uses the actual Root's captured primary API/CSRF for epId/sid/type=4 heartbeats.
 * The older reporter that creates a client is deliberately not a dependency.
 * Exact PGC subject/token admission is required in addition to account epoch.
 */
internal class DesktopOriginalBangumiPlayerAccountView(
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val repository: DesktopRepository,
    private val privacy: DesktopSearchPreferences,
    private val assertPresenterCurrent: () -> Unit,
    private val assertPgcSubject: (String, Long, Long, Long) -> Unit,
) : DesktopOriginalBangumiPlayerAccount {
    private fun binding(): DesktopOriginalVideoRepositoryBinding {
        assertPresenterCurrent(); owner.environment.assertCurrent()
        return (owner.invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository)
            ?.binding?.also { it.assertCurrent() }
            ?: error("PGC requires the existing original invocation's captured Binding")
    }
    override fun isPlaybackVip() = owner.invocations.repository.isPlaybackVip().also { assertPresenterCurrent() }
    override fun isPlaybackLoggedIn() = owner.invocations.repository.isPlaybackLoggedIn().also { assertPresenterCurrent() }
    override fun hasPlaybackSessionCookie() = binding().environment.hasPlaybackSessionCookie()
    override fun playbackAccessToken() = binding().environment.playbackAccessToken()
    override fun hasPrimarySessionCookie() = owner.environment.account.hasSession.also { assertPresenterCurrent() }
    override suspend fun reportPlayHeartbeat(bvid: String, cid: Long, playedTime: Long, aid: Long,
        epid: Long, sid: Long, videoType: Int, subType: Int?): Boolean {
        currentCoroutineContext().ensureActive(); assertPgcSubject(bvid, cid, epid, sid)
        val captured = binding()
        val reported = reportDesktopPlaybackHeartbeat(
            privacyEnabled = privacy::isPrivacyModeEnabledSync,
            expectedEpoch = captured.receipt.accountEpoch, epoch = { repository.sessionEpoch },
            mid = captured::primaryMid, csrf = { captured.primaryCsrf().orEmpty() },
            bvid = bvid, cid = cid, playedTimeSec = playedTime,
            // Exact defaults of original VideoRepository.reportPlayHeartbeat:
            // PGC omits these arguments, so realPlayedTime=playedTime and now.
            realPlayedTimeSec = playedTime, startTsSec = System.currentTimeMillis() / 1000L,
            aid = aid, epid = epid, sid = sid,
            videoType = videoType, subType = subType,
            onReported = { captured.assertCurrent(); assertPgcSubject(bvid, cid, epid, sid); HistoryRefreshBus.notifyChanged() },
        ) { fields ->
            currentCoroutineContext().ensureActive(); captured.assertCurrent(); assertPgcSubject(bvid, cid, epid, sid)
            val response = captured.primaryApi.reportHeartbeat(fields)
            currentCoroutineContext().ensureActive(); captured.assertCurrent(); assertPgcSubject(bvid, cid, epid, sid)
            response.code
        }
        currentCoroutineContext().ensureActive(); captured.assertCurrent(); assertPgcSubject(bvid, cid, epid, sid)
        return reported
    }
}
