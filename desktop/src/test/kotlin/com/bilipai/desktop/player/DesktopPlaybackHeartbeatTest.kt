package com.bilipai.desktop.player

import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class DesktopPlaybackHeartbeatTest {
    private val identity = DesktopHeartbeatIdentity("BVfixture", 11L, 1L, 4L)
    private fun playing(position: Double) = PlayerState(ready = true, videoCodec = "h264", positionSeconds = position)

    @Test fun `initialized or failed media never creates a viewing history event`() {
        val tracker = DesktopPlaybackHeartbeatTracker({ 1_000L }, { 1_700_000_000L })
        tracker.begin(identity, 42.0)
        tracker.observe(PlayerState(ready = true, loading = true, positionSeconds = 42.0), false)
        assertNull(tracker.initialReport())
        assertNull(tracker.periodicReport())
        assertNull(tracker.finalReport())
        tracker.observe(PlayerState(ready = true, positionSeconds = 42.0, error = "File not found"), false)
        assertNull(tracker.finalReport())
    }

    @Test fun `resume and seek change played position while real time excludes pause and background`() {
        var now = 1_000L
        val tracker = DesktopPlaybackHeartbeatTracker({ now }, { 1_700_000_000L })
        tracker.begin(identity, 500.0)
        tracker.observe(playing(500.0), false)
        val initial = assertNotNull(tracker.initialReport())
        assertEquals(0L, initial.snapshot.realPlayedTimeSec)
        assertEquals(0L, initial.snapshot.playedTimeSec)
        assertNull(tracker.initialReport())
        now += 5_000L
        tracker.observe(playing(7_200.0), false)
        val afterSeek = assertNotNull(tracker.periodicReport())
        assertEquals(7_200L, afterSeek.snapshot.playedTimeSec)
        assertEquals(5L, afterSeek.snapshot.realPlayedTimeSec)
        assertEquals(initial.startTsSec, afterSeek.startTsSec)
        tracker.observe(playing(7_200.0).copy(paused = true), false)
        now += 3_600_000L
        assertNull(tracker.periodicReport())
        tracker.observe(playing(7_200.0), false)
        now += 1_000L
        tracker.observe(playing(7_201.0), true)
        now += 3_600_000L
        assertNull(tracker.periodicReport())
        val final = assertNotNull(tracker.finalReport())
        assertEquals(6L, final.snapshot.realPlayedTimeSec)
        assertEquals(7_201L, final.snapshot.playedTimeSec)
    }

    @Test fun `final flush follows original meaningful delta and never acknowledges a later same CID session`() {
        var now = 1_000L
        val tracker = DesktopPlaybackHeartbeatTracker({ now }, { 1_700_000_000L })
        tracker.begin(identity, 0.0)
        tracker.observe(playing(0.0), false)
        assertTrue(tracker.confirm(assertNotNull(tracker.initialReport())))
        now += 30_000L
        tracker.observe(playing(30.0), false)
        val reported = assertNotNull(tracker.periodicReport())
        assertTrue(tracker.confirm(reported))
        tracker.observe(playing(30.0).copy(paused = true), false)
        assertNull(tracker.finalReport())
        tracker.begin(identity, 100.0)
        assertFalse(tracker.confirm(reported))
        tracker.observe(playing(100.0).copy(paused = true), false)
        assertNotNull(tracker.finalReport())
    }

    @Test fun `creator signal counts watched wall time once and preserves original 45 second cap`() {
        var now = 1_000L
        val tracker = DesktopPlaybackHeartbeatTracker({ now }, { 1_700_000_000L })
        tracker.begin(identity, 1_000.0)
        tracker.observe(playing(1_000.0), false)
        assertEquals(0L, tracker.takeCreatorWatchDelta())
        now += 60_000L
        tracker.observe(playing(30_000.0), false)
        assertEquals(45L, tracker.takeCreatorWatchDelta())
        assertEquals(0L, tracker.takeCreatorWatchDelta())
        now += 10_000L
        assertEquals(10L, tracker.takeCreatorWatchDelta())
        tracker.observe(playing(30_000.0).copy(loading = true), false)
        now += 30_000L
        assertEquals(0L, tracker.takeCreatorWatchDelta())
    }

    @Test fun `account changes drop queued and returned reports before they update the new account`() = runBlocking {
        val epoch = AtomicLong(4L)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val acceptedNew = CompletableDeferred<Unit>()
        val sent = Collections.synchronizedList(mutableListOf<Long>())
        val accepted = Collections.synchronizedList(mutableListOf<Long>())
        val reporter = DesktopHeartbeatReporter(epoch::get, send = { report ->
            sent += report.identity.sessionEpoch
            if (report.playbackSessionId == 1L) { started.complete(Unit); release.await() }
            true
        }, onAccepted = { report -> accepted += report.identity.sessionEpoch; acceptedNew.complete(Unit) })
        try {
            reporter.submit(report(1L, 4L))
            withTimeout(3_000L) { started.await() }
            reporter.submit(report(2L, 4L))
            epoch.set(5L)
            reporter.submit(report(3L, 5L))
            release.complete(Unit)
            withTimeout(3_000L) { acceptedNew.await() }
            assertEquals(listOf(4L, 5L), sent.toList())
            assertEquals(listOf(5L), accepted.toList())
        } finally { reporter.close() }
    }

    @Test fun `a stuck report cannot block the bounded final flush or send discarded backlog`() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finalAccepted = CompletableDeferred<Unit>()
        val sent = Collections.synchronizedList(mutableListOf<Long>())
        val reporter = DesktopHeartbeatReporter({ 4L }, send = { report ->
            sent += report.playbackSessionId
            if (report.playbackSessionId == 1L) { started.complete(Unit); awaitCancellation() }
            true
        }, onAccepted = { report -> if (report.playbackSessionId == 3L) finalAccepted.complete(Unit) },
            requestTimeoutMs = 100L, closeTimeoutMs = 1_000L)
        reporter.submit(report(1L, 4L))
        withTimeout(3_000L) { started.await() }
        reporter.submit(report(2L, 4L))
        reporter.close(report(3L, 4L))
        withTimeout(3_000L) { finalAccepted.await() }
        assertEquals(listOf(1L, 3L), sent.toList())
    }

    private fun report(session: Long, epoch: Long) = DesktopHeartbeatReport(identity.copy(sessionEpoch = epoch), session,
        com.android.purebilibili.feature.video.playback.policy.PlaybackHeartbeatSnapshot(30L, 20L), 1_700_000_000L)
}
