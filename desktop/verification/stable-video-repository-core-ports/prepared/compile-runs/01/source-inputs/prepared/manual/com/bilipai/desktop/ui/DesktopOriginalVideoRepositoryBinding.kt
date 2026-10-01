package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BuvidApi
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
) {
    val receipt: DesktopPlaybackAuthorizationReceipt get() = authorization.receipt
    private fun entryCurrent(): Boolean = requestJob.isActive && entryJob.isActive && isEntryCurrent()
    private fun current(): Boolean = entryCurrent() && repository.isPlaybackReceiptCurrent(receipt)

    fun assertCurrent() {
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

    private val preferenceSnapshot = preferences.normalized()
    private val blockedSnapshot = blockedVideoCodecs.toSet()
    private val views = repository.originalVideoProtocolViews(authorization, ::entryCurrent, commitIfEntryCurrent) { bvid, cid, quality ->
        repository.originalVideoCacheKey(authorization, bvid, cid, quality, preferenceSnapshot, codecOverride,
            blockedSnapshot, av1Supported)
    }
    private val primaryApi = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current)
    private val playbackApi = repository.ownedPlaybackService(BilibiliApi::class.java, authorization, ::current)
    private val guestApi = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current, guest = true)
    private val buvidApi = repository.ownedHomeService(BuvidApi::class.java, "https://api.bilibili.com/",
        receipt.accountEpoch, ::current)

    // Original NetworkModule.playbackAccount returns the valid selected account even if
    // it is the main MID; only the transport CookieJar switches for a dedicated MID.
    private fun playbackAccount(): StoredAccountSession? = read { authorization.playbackAccount }

    val environment = DesktopOriginalVideoLoadProtocolEnvironment(
        api = primaryApi,
        playbackApi = playbackApi,
        guestApi = guestApi,
        cache = views.first,
        state = views.second,
        ensureBuvid = {
            currentCoroutineContext().ensureActive(); assertCurrent()
            repository.ensureOwnedHomeSession(receipt.accountEpoch, ::current, buvidApi)
            currentCoroutineContext().ensureActive(); assertCurrent()
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

    /** Preserve this receipt through DASH/CDN/plugin transformations and final native
     * publication. This method stamps metadata only; it never publishes or loads MPV. */
    fun authorized(source: PlaybackSource): PlaybackSource = read { source.copy(authorizationReceipt = receipt) }

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
        ): DesktopOriginalVideoRepositoryBinding {
            currentCoroutineContext().ensureActive()
            val requestJob = requireNotNull(currentCoroutineContext()[Job])
            val authorization = repository.capturePlaybackAuthorization(expectedEpoch) {
                requestJob.isActive && entryJob.isActive && isEntryCurrent()
            }
            return DesktopOriginalVideoRepositoryBinding(repository, authorization, requestJob, entryJob,
                isEntryCurrent, commitIfEntryCurrent, preferences, codecOverride, blockedVideoCodecs,
                av1Supported, auto1080pEnabled, directedTrafficEnabled, isMobileData,
                canRefreshPrimaryToken, refreshPrimaryToken).also { it.assertCurrent() }
        }
    }
}
