package com.bilipai.desktop.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Lets one already-admitted write finish when its caller is cancelled while the request is in flight, and never lets
 * a retired owner see the outcome.
 *
 * Android 0.3.0 kept a like request alive across detail-page exit with a NonCancellable block (upstream 78de11a2);
 * the 0.3.6 interaction-state rewrite no longer has it. Windows keeps the intent but not the hole it leaves: the write
 * is admitted by [assertOwned], which throws CancellationException once the source, account or entry that issued the
 * write has retired, so the block is entered only while all three are current. After admission the request is
 * account-owned: it is not re-checked against the detail entry inside the block, and if the entry retires while it is
 * in flight it may still reach the server. [assertOwned] runs once more when the request returns, so the outcome is
 * handed back only while the caller's owner is still current. After a retirement nothing is published and no message
 * is shown; the like analytics event is dropped because DesktopOriginalVideoAnalyticsBinding checks isCurrent()
 * before it records, not because of anything this function does.
 */
internal suspend fun <T> desktopCompleteAdmittedWrite(assertOwned: () -> Unit, write: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    assertOwned()
    val outcome = withContext(NonCancellable) { write() }
    currentCoroutineContext().ensureActive()
    assertOwned()
    return outcome
}
