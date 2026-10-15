package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** One child of the existing retained Home scope; one active batch and one pending trigger.
 * The caller closes this child; the same Root lifetime drains it before freezing stores. */
internal class DesktopSubscriptionTitleRefreshWorker(
    scope: CoroutineScope,
    private val stillOwned: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val refresh: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private fun owns() = !closed.get() && stillOwned()
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        for (request in requests) {
            // A new Operation captures this retained child, never the completed import Job.
            try { DesktopSubscriptionWriteAdmission.withOwned(::owns, commitIfCurrent, refresh) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (owns()) onFailure(failure) }
        }
    }
    fun request() {
        if (owns() && requests.trySend(Unit).isSuccess) job.start()
    }
    override fun close() {
        closed.set(true)
        requests.close()
        job.cancel()
    }
}
