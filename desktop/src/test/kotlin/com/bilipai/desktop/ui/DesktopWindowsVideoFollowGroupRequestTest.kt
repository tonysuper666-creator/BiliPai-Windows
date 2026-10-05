package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlin.test.*

/** Real cancellable jobs exercise the same request/ticket used by the complete
 * generated group's load/save bodies. No account or native actor is fabricated. */
class DesktopWindowsVideoFollowGroupRequestTest {
    private class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = DesktopOriginalVideoAcceptedPublication(PlaybackRequest.create("BVfixture", aid = 17, cid = 70),
            OwnedPlaybackSourceSnapshot(1, PlaybackSource("https://example.invalid/fixture")))
        var accepted = source
        var account = 1
        var current: DesktopWindowsVideoFollowGroupRequest? = null
        var admissionDepth = 0
        var beforeAdmission: () -> Unit = {}
        var busy = false
        var publication = ""
        fun open(mid: Long = 33): DesktopWindowsVideoFollowGroupRequest {
            val previous = current
            val captured = accepted
            val request = DesktopWindowsVideoFollowGroupRequest(captured, mid,
                { it === current && account == 1 && accepted === captured },
                { action -> beforeAdmission(); if (account != 1 || accepted !== captured) false else {
                    admissionDepth++
                    try { action(); true } finally { admissionDepth-- }
                } })
            current = request; busy = false; previous?.close()
            return request
        }
        fun launch(request: DesktopWindowsVideoFollowGroupRequest, operation: DesktopWindowsVideoFollowGroupRequest.Operation,
            block: suspend (DesktopWindowsVideoFollowGroupRequest.Ticket) -> Unit): Pair<DesktopWindowsVideoFollowGroupRequest.Ticket, Job> {
            val ticket = request.ticket(operation)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { request.assertCurrent(ticket); assertEquals(0, admissionDepth); block(ticket) }
                finally { request.finish(ticket) { busy = false } }
            }
            assertTrue(request.begin(ticket, job) { busy = true }); job.start()
            return ticket to job
        }
        override fun close() { current?.close(); scope.cancel() }
    }

    @Test fun completeLoadPublishesOnlyAfterBothResultsOnSameRequest() = runBlocking {
        Harness().use { h ->
            val request = h.open()
            val tags = CompletableDeferred<String>(); val ids = CompletableDeferred<String>()
            val (_, job) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.LOAD) { ticket ->
                val names = tags.await(); request.assertCurrent(ticket)
                val selected = ids.await(); request.assertCurrent(ticket)
                request.publish(ticket) { h.publication = "$names/$selected" }
            }
            tags.complete("one,two"); assertEquals("", h.publication); assertTrue(h.busy)
            ids.complete("2"); job.join()
            assertEquals("one,two/2", h.publication); assertFalse(h.busy)
        }
    }
    @Test fun closeCancelsExactCallerAndCannotPublishOrStartSecondApi() = runBlocking {
        Harness().use { h ->
            val request = h.open(); val first = CompletableDeferred<String>(); var calls = 1
            val (_, job) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.LOAD) { ticket ->
                first.await(); request.assertCurrent(ticket); calls++
                request.publish(ticket) { h.publication = "old" }
            }
            request.close(); first.complete("old"); job.join()
            assertTrue(job.isCancelled); assertEquals(1, calls); assertEquals("", h.publication)
        }
    }
    @Test fun sameMidReopenRejectsOldFinallyAndOldCallbackDispatch() = runBlocking {
        Harness().use { h ->
            val old = h.open(); val first = CompletableDeferred<Unit>()
            val (oldTicket, oldJob) = h.launch(old, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { first.await() }
            val next = h.open(); val second = CompletableDeferred<Unit>()
            val (_, nextJob) = h.launch(next, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { second.await() }
            first.complete(Unit); oldJob.join()
            assertTrue(h.busy); assertFalse(old.finish(oldTicket) { h.busy = false })
            assertFalse(old.dispatch { h.publication = "old callback" }); assertEquals("", h.publication)
            second.complete(Unit); nextJob.join(); assertFalse(h.busy)
        }
    }
    @Test fun oldTicketOfSameRequestCannotClearFollowingSave() = runBlocking {
        Harness().use { h ->
            val request = h.open()
            val (oldTicket, oldJob) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) {}
            oldJob.join()
            val result = CompletableDeferred<Unit>()
            val (_, job) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { result.await() }
            assertFalse(request.finish(oldTicket) { h.busy = false }); assertTrue(h.busy)
            result.complete(Unit); job.join(); assertFalse(h.busy)
        }
    }
    @Test fun accountAndSameValueAcceptedReplacementRejectFinalPublication() = runBlocking {
        for (retire in listOf<(Harness) -> Unit>({ it.account = 2 },
            { it.accepted = DesktopOriginalVideoAcceptedPublication(it.source.request, it.source.nativeSource) })) Harness().use { h ->
            val request = h.open(); val response = CompletableDeferred<Unit>()
            val (_, job) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { ticket ->
                response.await(); request.publish(ticket) { h.publication = "saved" }
            }
            retire(h); response.complete(Unit); job.join()
            assertTrue(job.isCancelled); assertEquals("", h.publication); assertFalse(request.isOwned())
        }
    }
    @Test fun retirementBetweenPrecheckAndFinalPermitRejects() = runBlocking {
        Harness().use { h ->
            val request = h.open(); val response = CompletableDeferred<Unit>()
            val (_, job) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { ticket ->
                response.await(); request.publish(ticket) { h.publication = "saved" }
            }
            h.beforeAdmission = { h.account = 2 }
            response.complete(Unit); job.join(); assertEquals("", h.publication)
        }
    }
    @Test fun currentCallerCancellationClearsOnlyItsBusyAndAllowsRetry() = runBlocking {
        Harness().use { h ->
            val request = h.open(); val response = CompletableDeferred<Unit>()
            val (_, job) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { response.await() }
            job.cancel(); job.join(); assertFalse(h.busy); assertTrue(request.isOwned())
            val (_, retry) = h.launch(request, DesktopWindowsVideoFollowGroupRequest.Operation.SAVE) { ticket ->
                request.publish(ticket) { h.publication = "retry" }
            }
            retry.join(); assertEquals("retry", h.publication); assertFalse(h.busy)
        }
    }
    @Test fun dispatchRunsOutsideAdmissionAndRestoresLexicalRequestAfterFailure() {
        Harness().use { h ->
            val request = h.open()
            assertFailsWith<IllegalStateException> {
                request.dispatch {
                    assertEquals(0, h.admissionDepth)
                    assertSame(request, DesktopWindowsVideoFollowGroupRequest.dispatchedRequest())
                    error("callback failed")
                }
            }
            assertNull(DesktopWindowsVideoFollowGroupRequest.dispatchedRequest())
        }
    }
}
