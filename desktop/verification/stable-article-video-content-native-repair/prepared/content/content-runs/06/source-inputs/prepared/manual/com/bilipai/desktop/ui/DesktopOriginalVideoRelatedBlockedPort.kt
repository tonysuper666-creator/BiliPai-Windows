package com.bilipai.desktop.ui

import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.network.BilibiliApi
import com.bilipai.desktop.data.DesktopBlockedUpRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A detail-entry admission facade over the already existing global block repository.
 * Root provides its current detail-owned API/CSRF/bootstrap views over the same transport.
 * A retained Home request facade must not be supplied as this detail lifetime's owner.
 */
internal fun desktopOriginalVideoRelatedBlockedPort(
    actual: DesktopBlockedUpRepository,
    expectedEpoch: Long,
    isCurrent: () -> Boolean,
    commitIfCurrent: ((() -> Unit) -> Boolean),
    ownedApi: BilibiliApi,
    ownedCsrf: () -> String?,
    ensureOwnedSession: suspend () -> Unit,
): DesktopHomeBlockedRequests = object : DesktopHomeBlockedRequests {
    private fun assertOwned() {
        if (actual.sessionEpoch != expectedEpoch || !isCurrent())
            throw CancellationException("Original related-video block owner retired")
    }
    override fun getAllBlockedUps() = actual.store.records
    override suspend fun blockUp(mid: Long, name: String, face: String) {
        val caller = currentCoroutineContext()
        caller.ensureActive(); assertOwned()
        if (!commitIfCurrent {
            caller.ensureActive(); assertOwned()
            actual.store.upsert(BlockedUp(mid = mid, name = name, face = face))
        }) throw CancellationException("Original related-video local block owner retired")
    }
    override suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String) =
        actual.blockUpWithBilibiliSync(mid, name, face,
            expectedSessionEpoch = expectedEpoch, stillOwned = isCurrent,
            commitLocal = commitIfCurrent, ownedApi = ownedApi,
            ownedCsrf = ownedCsrf, ensureOwnedSession = ensureOwnedSession).also {
                currentCoroutineContext().ensureActive(); assertOwned()
            }
}
