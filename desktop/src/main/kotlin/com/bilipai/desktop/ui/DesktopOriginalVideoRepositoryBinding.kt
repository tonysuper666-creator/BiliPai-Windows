package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.repository.DesktopOriginalBangumiPlayRequests
import com.android.purebilibili.core.network.BuvidApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.StoryApi
import com.android.purebilibili.core.store.StoredAccountSession
import com.android.purebilibili.data.repository.DesktopOriginalVideoLoadProtocol
import com.bilipai.desktop.data.DesktopPlaybackAuthorization
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One raw-load request view over the SAME Repository, Store, cache and transport.
 * Create inside each original load request, not once for the entire video page.
 * isEntryCurrent/commitIfEntryCurrent are the existing entry generation gate:
 * they MUST NOT acquire the Store; Repository takes Store -> entry in that order.
 * Request, entry and playback-authorization retirement invalidate this whole view.
 */
internal class DesktopOriginalVideoRepositoryBinding private constructor(
    private val repository: DesktopRepository,
    private val authorization: DesktopPlaybackAuthorization,
    private val requestJob: Job,
    private val entryJob: Job,
    private val isEntryCurrent: () -> Boolean,
    private val commitIfEntryCurrent: ((() -> Unit) -> Boolean),
    preferences: PlayerPreferences,
    codecOverride: String?,
    blockedVideoCodecs: Set<String>,
    av1Supported: Boolean,
    auto1080pEnabled: () -> Boolean,
    directedTrafficEnabled: () -> Boolean,
    isMobileData: () -> Boolean,
    canRefreshPrimaryToken: () -> Boolean,
    refreshPrimaryToken: suspend (DesktopPlaybackAuthorizationReceipt, () -> Boolean) -> Boolean,
    private val onPlaybackAuthorizationRetired: ((DesktopPlaybackAuthorizationReceipt) -> Unit)?,
) : DesktopOriginalExternalPlaylistRequest {
    private val authorizationRetirementReported = java.util.concurrent.atomic.AtomicBoolean(false)
    val receipt: DesktopPlaybackAuthorizationReceipt get() = authorization.receipt
    private fun entryCurrent(): Boolean = requestJob.isActive && entryJob.isActive && isEntryCurrent()
    private fun current(): Boolean = entryCurrent() && repository.isPlaybackReceiptCurrent(receipt)

    override fun assertCurrent() {
        repository.withPlaybackReceiptAdmission(receipt, ::entryCurrent) {
            if (!commitIfEntryCurrent {
                if (!entryCurrent()) throw CancellationException("Original raw load request retired")
            }) throw CancellationException("Original raw load entry retired")
        }
    }

    private fun <T> read(block: () -> T): T = repository.withPlaybackReceiptAdmission(receipt, ::entryCurrent) {
        var result: Result<T>? = null
        if (!commitIfEntryCurrent {
            if (!entryCurrent()) throw CancellationException("Original raw load request retired")
            result = runCatching(block)
        }) throw CancellationException("Original raw load entry retired")
        requireNotNull(result).getOrThrow()
    }

    // Identity scalar only, captured under the SAME receipt/Store -> entry gate.
    private val capturedPrimaryMid = read { repository.activeAccountMid() }
    private val metadataPlaybackCalls = repository.ownedPlaybackCallFactory(authorization, ::current)
    private val preferenceSnapshot = preferences.normalized()
    private val blockedSnapshot = blockedVideoCodecs.toSet()
    private val views = repository.originalVideoProtocolViews(authorization, ::entryCurrent, commitIfEntryCurrent) { bvid, cid, quality ->
        repository.originalVideoCacheKey(authorization, bvid, cid, quality, preferenceSnapshot, codecOverride,
            blockedSnapshot, av1Supported)
    }
    private val capturedPrimaryApi = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current)
    private val capturedPrimarySpaceApi = repository.ownedHomeService(SpaceApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current)
    private val capturedPrimarySearchApi = repository.ownedHomeService(SearchApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current)
    private val capturedPrimaryStoryApi = repository.ownedHomeService(StoryApi::class.java, "https://app.bilibili.com/",
        receipt.accountEpoch, ::current)
    private val playbackApi = repository.ownedPlaybackService(BilibiliApi::class.java, authorization, ::current)
    private val capturedPlaybackBangumiApi = repository.ownedPlaybackService(BangumiApi::class.java, authorization, ::current)
    private val guestApi = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current, guest = true)
    private val buvidApi = repository.ownedHomeService(BuvidApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current)

    // Original NetworkModule.playbackAccount returns the valid selected account even if
    // it is the main MID; only the transport CookieJar switches for a dedicated MID.
    private fun playbackAccount(): StoredAccountSession? = read { authorization.playbackAccount }

    val environment = DesktopOriginalVideoLoadProtocolEnvironment(
        api = capturedPrimaryApi,
        playbackApi = playbackApi,
        guestApi = guestApi,
        cache = views.first,
        state = views.second,
        ensureBuvid = {
            currentCoroutineContext().ensureActive(); assertCurrent()
            repository.ensureOwnedHomeSession(receipt.accountEpoch, ::current, buvidApi)
            currentCoroutineContext().ensureActive()
            // Real SPI bootstrap has completed. Its buvid3 may retire this immutable
            // authorization; enqueue a fresh same-request capture before preserving CE.
            if (entryCurrent() && repository.sessionEpoch == receipt.accountEpoch &&
                repository.ownedHomeVisitorInitialized(receipt.accountEpoch, ::entryCurrent))
                reportPlaybackAuthorizationRetired()
            assertCurrent()
        },
        playbackAccount = ::playbackAccount,
        hasPlaybackSessionCookie = { read {
            !authorization.playbackAccount?.sessData.isNullOrEmpty() ||
                !repository.ownedHomeCookie("SESSDATA", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
        } },
        playbackAccessToken = { read {
            authorization.playbackAccount?.accessToken?.takeIf(String::isNotBlank)
                ?: repository.accessTokenCredentials().first
        } },
        playbackAccessTokenPlatform = { read {
            authorization.playbackAccount?.accessTokenPlatform ?: repository.accessTokenCredentials().second
        } },
        androidAccessTokenPlatform = "android", // Original TokenManager.ACCESS_TOKEN_PLATFORM_ANDROID.
        isPlaybackVip = { read { authorization.playbackAccount?.isVip ?: (repository.account.value?.isVip == true) } },
        auto1080pEnabled = { read(auto1080pEnabled) },
        directedTrafficEnabled = { read(directedTrafficEnabled) },
        isMobileData = { read(isMobileData) },
        canRefreshPrimaryToken = { read(canRefreshPrimaryToken) },
        refreshPrimaryToken = {
            currentCoroutineContext().ensureActive(); assertCurrent()
            // REQUIRED existing Root primary-token actor. It must use this receipt and
            // lifetime before Call enqueue and credential commit, never unowned refresh.
            // Original TokenRefreshHelper handles TV only; non-TV returns false.
            val refreshed = refreshPrimaryToken(receipt, ::current)
            currentCoroutineContext().ensureActive(); assertCurrent()
            // A successful credential replacement retires this receipt. The assertion
            // cancels this load; Root starts a NEW load rather than silently retagging it.
            refreshed
        },
        stillOwned = ::current,
    )
    val protocol = DesktopOriginalVideoLoadProtocol(environment)
    val rawRepository: DesktopOriginalVideoLoadRepository get() = protocol

    /** Exact PGC view of THIS existing immutable request. No new client,
     * playback-account cache or WBI authority. Original fallback bodies use
     * Root's existing owned Home WBI cache/refresh with this captured API. */
    fun bangumiPlayRequests(): DesktopOriginalBangumiPlayRequests = read {
        DesktopOriginalBangumiPlayRequests(capturedPlaybackBangumiApi,
            { bangumiWbiKeys(forceRefresh = false) },
            { bangumiWbiKeys(forceRefresh = true) }, ::current)
    }
    private suspend fun bangumiWbiKeys(forceRefresh: Boolean): Result<Pair<String, String>> {
        currentCoroutineContext().ensureActive(); assertCurrent()
        val result = repository.homeWbiKeys(receipt.accountEpoch, ::current, capturedPrimaryApi, forceRefresh)
        currentCoroutineContext().ensureActive(); assertCurrent()
        return result
    }


    /** Metadata facet of THIS captured request, not a latest-credential service. */
    override val primaryApi: BilibiliApi get() = read { capturedPrimaryApi }
    /** Same fixed primary request/Call.Factory, including original Space app endpoints. */
    val primarySpaceApi: SpaceApi get() = read { capturedPrimarySpaceApi }
    override val primarySearchApi: SearchApi get() = read { capturedPrimarySearchApi }
    val primaryStoryApi: StoryApi get() = read { capturedPrimaryStoryApi }
    val playbackCalls: okhttp3.Call.Factory get() = read { metadataPlaybackCalls }
    /** Exact primary values of this request, admitted by its original receipt.
     * Used by original Notes/heartbeat; no page-wide credential or MID cache. */
    fun primaryMid(): Long? = read { capturedPrimaryMid }
    fun primaryCsrf(): String? = read {
        repository.ownedHomeCookie("bili_jct", receipt.accountEpoch, ::entryCurrent)
    }
    /** Fixed request's short Store -> entry mutation admission. No wait/IO may
     * occur in action. Success is never reported for an expired request. */
    /** Read-only caller identity; no new Job or request authority. */
    fun capturedDownloadCallerJob(): Job = read { requestJob }

    override fun admitCurrentMutation(action: () -> Unit): Boolean = try {
        read(action)
        true
    } catch (_: CancellationException) { false }
    // Read through THIS request's existing Store/receipt admission. No credential cache.
    fun primarySessData(): String? = read {
        repository.ownedHomeCookie("SESSDATA", receipt.accountEpoch, ::entryCurrent)
    }
    fun primaryAccessToken(): String? = read {
        repository.ownedHomeAccessToken(receipt.accountEpoch, ::entryCurrent)
    }
    fun primaryAccessTokenPlatform(): String = read { repository.accessTokenCredentials().second }
    fun hasPrimarySession(): Boolean = read {
        !repository.ownedHomeCookie("SESSDATA", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    fun hasPrimaryCsrf(): Boolean = read {
        !repository.ownedHomeCookie("bili_jct", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    fun hasPrimaryBuvid(): Boolean = read {
        !repository.ownedHomeCookie("buvid3", receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    fun hasPrimaryAccessToken(): Boolean = read {
        !repository.ownedHomeAccessToken(receipt.accountEpoch, ::entryCurrent).isNullOrEmpty()
    }
    /** Required query reads Repository's EXISTING visitor generation/flag. It is
     * supplied only by metadata assembly; the pre-existing capture ABI is intact. */
    fun isBuvidInitialized(
        query: (DesktopPlaybackAuthorizationReceipt, () -> Boolean) -> Boolean,
    ): Boolean = read { query(receipt, ::entryCurrent) }

    /** Called only after an owned SPI/VIP mutation returned, outside every Store/entry
     * monitor. This is not a generic cancellation retry or a mutable receipt update. */
    fun reportPlaybackAuthorizationRetired() {
        if (entryCurrent() && repository.sessionEpoch == receipt.accountEpoch &&
            !repository.isPlaybackReceiptCurrent(receipt) &&
            authorizationRetirementReported.compareAndSet(false, true)) {
            val notify = onPlaybackAuthorizationRetired
                ?: throw CancellationException("Metadata-only authorization retired; fresh capture required")
            notify(receipt)
        }
    }

    fun updatePrimaryVip(isVip: Boolean): Unit = read {
        if (capturedPrimaryMid == null) throw CancellationException("Primary VIP session absent")
        repository.withProfileAccountAdmission(receipt.accountEpoch, capturedPrimaryMid,
            ::entryCurrent, commitIfEntryCurrent) { saveProfileVipStatus(isVip) }
        // The existing Store may advance authorization revision for a changed VIP.
        // This view is NEVER retagged; subsequent use must reject that old receipt.
    }

    /** Preserve this receipt through DASH/CDN/plugin transformations and final native
     * publication. This method stamps metadata only; it never publishes or loads MPV. */
    fun authorized(source: PlaybackSource): PlaybackSource = read { source.copy(authorizationReceipt = receipt) }

    /** Exact captured media CookieJar, never latest account credentials. */
    fun captureMediaCookieHeader(url: String): String = read {
        repository.capturePlaybackMediaCookieHeader(authorization, url, ::entryCurrent)
    }

    /** Same raw request's exact account/Job/entry admission for native byte preparation. */
    fun captureMediaBytes(cache: com.bilipai.desktop.player.cache.DesktopMediaByteCache,
        network: () -> com.bilipai.desktop.player.cache.DesktopCdnNetworkObservation): DesktopOriginalVideoByteCacheRequest = read {
        val namespace = repository.capturePlaybackCachePartition(receipt, ::entryCurrent)
        DesktopOriginalVideoByteCacheRequest(cache,
            com.bilipai.desktop.player.cache.DesktopMediaByteRepositoryAdmission(repository, authorization,
                namespace, requestJob, ::entryCurrent, commitIfEntryCurrent, network), ::assertCurrent)
    }

    companion object {
        suspend fun capture(
            repository: DesktopRepository,
            expectedEpoch: Long,
            entryJob: Job,
            isEntryCurrent: () -> Boolean,
            commitIfEntryCurrent: ((() -> Unit) -> Boolean),
            preferences: PlayerPreferences,
            codecOverride: String?,
            blockedVideoCodecs: Set<String>,
            av1Supported: Boolean,
            auto1080pEnabled: () -> Boolean,
            directedTrafficEnabled: () -> Boolean,
            isMobileData: () -> Boolean,
            canRefreshPrimaryToken: () -> Boolean,
            refreshPrimaryToken: suspend (DesktopPlaybackAuthorizationReceipt, () -> Boolean) -> Boolean,
            onPlaybackAuthorizationRetired: ((DesktopPlaybackAuthorizationReceipt) -> Unit)? = null,
        ): DesktopOriginalVideoRepositoryBinding {
            currentCoroutineContext().ensureActive()
            val requestJob = requireNotNull(currentCoroutineContext()[Job])
            val authorization = repository.capturePlaybackAuthorization(expectedEpoch) {
                requestJob.isActive && entryJob.isActive && isEntryCurrent()
            }
            return DesktopOriginalVideoRepositoryBinding(repository, authorization, requestJob, entryJob,
                isEntryCurrent, commitIfEntryCurrent, preferences, codecOverride, blockedVideoCodecs,
                av1Supported, auto1080pEnabled, directedTrafficEnabled, isMobileData,
                canRefreshPrimaryToken, refreshPrimaryToken, onPlaybackAuthorizationRetired).also { it.assertCurrent() }
        }
    }
}
