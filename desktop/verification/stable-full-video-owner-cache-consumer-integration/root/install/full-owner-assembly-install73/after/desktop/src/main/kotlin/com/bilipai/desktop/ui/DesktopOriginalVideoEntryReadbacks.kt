package com.bilipai.desktop.ui

import com.android.purebilibili.data.repository.resolveVideoPlaybackAuthState
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/** Short synchronous reads from the SAME Store, not a completed request Binding.
 * Raw API operations continue to use Invocation's immutable receipt. These UI
 * projections deliberately preserve original selected-account fallback semantics,
 * including isUsingDedicatedPlaybackAccount == selected account is non-null.
 */
internal class DesktopOriginalVideoEntryReadbacks(
    private val repository: DesktopRepository,
    private val capturedEpoch: Long,
    private val entryScope: CoroutineScope,
    private val stillEntryOwned: () -> Boolean,
    private val commitIfEntryCurrent: ((() -> Unit) -> Boolean),
) : DesktopOriginalVideoPlaybackStatus {
    private fun owned() = entryScope.isActive && stillEntryOwned()
    private fun <T> read(action: () -> T): T = repository.withPrimaryPlaybackAdmission(capturedEpoch, ::owned) {
        var result: Result<T>? = null
        if (!commitIfEntryCurrent {
            if (!owned()) throw CancellationException("Original entry identity read retired")
            result = runCatching(action)
        }) throw CancellationException("Original entry identity admission retired")
        checkNotNull(result).getOrThrow()
    }
    fun hasPrimarySession(): Boolean = read {
        !repository.ownedHomeCookie("SESSDATA", capturedEpoch, ::owned).isNullOrEmpty()
    }
    fun hasPrimaryAccessToken(): Boolean = read {
        !repository.ownedHomeAccessToken(capturedEpoch, ::owned).isNullOrEmpty()
    }
    fun primaryMid(): Long? = read { repository.activeAccountMid() }
    override fun isPlaybackLoggedIn(): Boolean = read {
        val selected = repository.getPlaybackAccount()
        resolveVideoPlaybackAuthState(
            !selected?.sessData.isNullOrEmpty() || !repository.ownedHomeCookie("SESSDATA", capturedEpoch, ::owned).isNullOrEmpty(),
            !selected?.accessToken?.takeIf(String::isNotBlank).isNullOrEmpty() ||
                !repository.ownedHomeAccessToken(capturedEpoch, ::owned).isNullOrEmpty())
    }
    override fun isPlaybackVip(): Boolean = read { repository.getPlaybackAccount()?.isVip ?: (repository.account.value?.isVip == true) }
    override fun isUsingDedicatedPlaybackAccount(): Boolean = read { repository.getPlaybackAccount() != null }
    override fun isAppApiCoolingDown(): Boolean = cooldownRemainingMs(System.currentTimeMillis()) > 0L
    fun cooldownRemainingMs(nowMs: Long): Long = repository.originalVideoCooldownForEntry(
        capturedEpoch, ::owned, commitIfEntryCurrent, nowMs)
}
