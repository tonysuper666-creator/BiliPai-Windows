package com.bilipai.desktop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure coroutine rules of the like write guard. No window, network or Windows API is touched. */
class DesktopAdmittedWriteTest {
    private val retired = { throw CancellationException("owner retired") }

    @Test
    fun anInFlightWriteFinishesWhenItsCallerIsCancelledAndNothingIsDelivered() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var finished = false
        var delivered = false
        val caller = launch {
            desktopCompleteAdmittedWrite({}) {
                started.complete(Unit)
                release.await()
                finished = true
                "sent"
            }
            delivered = true
        }
        started.await()
        caller.cancel()
        assertFalse(finished, "the request is still in flight when the caller is cancelled")
        release.complete(Unit)
        caller.join()
        assertTrue(finished, "cancellation must not cut a request that was already admitted")
        assertFalse(delivered, "a cancelled caller must not observe the outcome")
        assertTrue(caller.isCancelled)
    }

    @Test
    fun aRetiredOwnerNeverStartsTheWrite() = runTest {
        var started = false
        assertFailsWith<CancellationException> {
            desktopCompleteAdmittedWrite(retired) { started = true }
        }
        assertFalse(started)
    }

    @Test
    fun anOwnerThatRetiresWhileTheRequestIsInFlightKeepsTheRequestButDropsTheOutcome() = runTest {
        var owned = true
        var finished = false
        assertFailsWith<CancellationException> {
            desktopCompleteAdmittedWrite({ if (!owned) throw CancellationException("owner retired") }) {
                owned = false
                finished = true
                Result.success(true)
            }
        }
        assertTrue(finished)
    }

    @Test
    fun aCallerThatWasCancelledBeforeTheWriteNeverStartsIt() = runTest {
        var started = false
        val caller = launch {
            currentCoroutineContext().job.cancel()
            desktopCompleteAdmittedWrite({}) { started = true }
        }
        caller.join()
        assertFalse(started)
        assertTrue(caller.isCancelled)
    }

    @Test
    fun anOwnedCompletionReturnsTheOutcomeAndAFailedRequestStaysAValue() = runTest {
        assertEquals(Result.success(true), desktopCompleteAdmittedWrite({}) { Result.success(true) })
        val failure = IllegalStateException("rejected")
        val outcome = desktopCompleteAdmittedWrite({}) { Result.failure<Boolean>(failure) }
        assertTrue(outcome.isFailure)
        assertEquals(failure, outcome.exceptionOrNull())
    }
}
