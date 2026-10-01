package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopLoginRepository
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Adapter to Root's ONE existing login actor, not another credentials/client owner.
 * This entry binding may outlive one load; each refresh captures its real caller Job
 * inside DesktopLoginRepository and uses the load's immutable receipt/predicate.
 */
internal class DesktopOriginalVideoTokenRefreshBinding(
    private val login: DesktopLoginRepository,
    private val entryJob: Job,
    private val isEntryCurrent: () -> Boolean,
    private val commitIfEntryCurrent: ((() -> Unit) -> Boolean),
) {
    private fun entryCurrent(): Boolean = entryJob.isActive && isEntryCurrent()
    /** Called by RawBinding inside same receipt admission; actual non-TV/no refresh
     * token is false exactly as original TokenRefreshHelper, not a fake platform flag. */
    fun available(): Boolean {
        if (!entryCurrent()) throw CancellationException("Original token entry retired")
        return login.originalPlaybackTokenRefreshAvailable()
    }
    suspend fun refresh(receipt: DesktopPlaybackAuthorizationReceipt, requestOwned: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!entryCurrent() || !requestOwned()) throw CancellationException("Original token request retired")
        return login.refreshTvTokenForOriginalPlayback(receipt, { entryCurrent() && requestOwned() }, commitIfEntryCurrent)
    }
}
