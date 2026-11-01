package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class DesktopWindowsVideoDanmakuLoadTest {
    private val starRail = DesktopWindowsDanmakuLoadIdentity(
        bvid = "BV1JYsd6sE4k",
        cid = 43140187661L,
    )

    @Test
    fun enabledCurrentMatchingPublicationProducesTheSectionLoad() {
        val request = desktopWindowsDanmakuLoadRequest(
            enabled = true,
            pageActive = true,
            accepted = starRail,
            detailBvid = starRail.bvid,
            detailCid = starRail.cid,
            detailAid = 115_000_000L,
            pageDurationSeconds = 120L,
            playerDurationMs = 1L,
        )

        assertEquals(
            DesktopWindowsDanmakuLoadRequest(
                cid = 43140187661L,
                aid = 115_000_000L,
                bvid = "BV1JYsd6sE4k",
                durationHintMs = 120_000L,
            ),
            request,
        )
    }

    @Test
    fun zeroPageDurationFallsBackToThePlayerAndStillLoads() {
        val request = desktopWindowsDanmakuLoadRequest(
            enabled = true,
            pageActive = true,
            accepted = starRail,
            detailBvid = starRail.bvid,
            detailCid = starRail.cid,
            detailAid = 0L,
            pageDurationSeconds = 0L,
            playerDurationMs = 95_000L,
        )

        assertEquals(95_000L, request?.durationHintMs)
        assertEquals(43140187661L, request?.cid)
    }

    @Test
    fun negativeDurationsLoadWithAZeroHint() {
        val request = desktopWindowsDanmakuLoadRequest(
            enabled = true,
            pageActive = true,
            accepted = starRail,
            detailBvid = starRail.bvid,
            detailCid = starRail.cid,
            detailAid = 1L,
            pageDurationSeconds = -5L,
            playerDurationMs = -1L,
        )

        assertEquals(0L, request?.durationHintMs)
    }

    @Test
    fun disabledPageOrUnmatchedPublicationDoesNotLoad() {
        val blocked = listOf(
            request(enabled = false),
            request(pageActive = false),
            request(accepted = null),
            request(detailBvid = ""),
            request(detailBvid = "BV1other"),
            request(detailCid = 0L),
            request(detailCid = starRail.cid + 1),
            request(detailAid = -1L),
            request(accepted = starRail.copy(cid = starRail.cid + 1)),
        )
        assertEquals(9, blocked.size)
        assertTrue(blocked.all { it == null })
        assertNull(blocked.first())
    }

    @Test
    fun runnerCallsTheSectionLoadOnceWhileCurrent() = runTest {
        val request = requireNotNull(request())
        var calls = 0
        runDesktopWindowsDanmakuLoad(request, stillCurrent = { true }, load = {
            calls += 1
            assertEquals(request, it)
        }, pause = { error("ownership miss was not expected") })
        assertEquals(1, calls)
    }

    @Test
    fun runnerSkipsWhenTheRequestIsMissingOrThePageLeft() = runTest {
        var calls = 0
        val record: suspend (DesktopWindowsDanmakuLoadRequest) -> Unit = { calls += 1 }
        runDesktopWindowsDanmakuLoad(null, stillCurrent = { true }, load = record)
        runDesktopWindowsDanmakuLoad(requireNotNull(request()), stillCurrent = { false }, load = record)
        assertEquals(0, calls)
    }

    @Test
    fun runnerRetriesAnOwnershipMissThenStops() = runTest {
        var calls = 0
        runDesktopWindowsDanmakuLoad(
            requireNotNull(request()),
            stillCurrent = { true },
            load = {
                calls += 1
                if (calls < 3) throw CancellationException("accepted source retired")
            },
            pause = {},
            maxOwnershipRetries = 8,
        )
        assertEquals(3, calls)
    }

    @Test
    fun runnerGivesUpAfterTheOwnershipRetryBudget() = runTest {
        var calls = 0
        runDesktopWindowsDanmakuLoad(
            requireNotNull(request()),
            stillCurrent = { true },
            load = {
                calls += 1
                throw CancellationException("accepted source retired")
            },
            pause = {},
            maxOwnershipRetries = 2,
        )
        assertEquals(3, calls)
    }

    @Test
    fun runnerRethrowsWhenTheEffectItselfIsCancelled() = runTest {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val child = launch {
            runDesktopWindowsDanmakuLoad(
                requireNotNull(request()),
                stillCurrent = { true },
                load = {
                    calls += 1
                    entered.complete(Unit)
                    awaitCancellation()
                },
                pause = { error("a disposed effect must not retry") },
            )
        }
        entered.await()
        child.cancel(CancellationException("effect disposed"))
        child.join()
        assertEquals(1, calls)
        assertTrue(child.isCancelled)
    }

    private fun request(
        enabled: Boolean = true,
        pageActive: Boolean = true,
        accepted: DesktopWindowsDanmakuLoadIdentity? = starRail,
        detailBvid: String = starRail.bvid,
        detailCid: Long = starRail.cid,
        detailAid: Long = 115_000_000L,
        pageDurationSeconds: Long = 120L,
        playerDurationMs: Long = 0L,
    ) = desktopWindowsDanmakuLoadRequest(
        enabled, pageActive, accepted, detailBvid, detailCid, detailAid, pageDurationSeconds, playerDurationMs,
    )
}
