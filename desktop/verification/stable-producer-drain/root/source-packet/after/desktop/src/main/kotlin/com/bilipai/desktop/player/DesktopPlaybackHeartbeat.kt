package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.playback.policy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopHeartbeatIdentity(val bvid: String, val cid: Long, val aid: Long, val sessionEpoch: Long)
internal data class DesktopHeartbeatReport(val identity: DesktopHeartbeatIdentity, val playbackSessionId: Long,
    val snapshot: PlaybackHeartbeatSnapshot, val startTsSec: Long, val initial: Boolean = false)

/** Native lifecycle/clock adapter. Field construction, foreground gates and flush decisions remain original policies. */
internal class DesktopPlaybackHeartbeatTracker(
    private val elapsedMs: () -> Long = desktopElapsedClock(),
    private val epochSec: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private var nextSessionId = 0L
    private data class Session(val id: Long, val identity: DesktopHeartbeatIdentity, val startTsSec: Long,
        var positionMs: Long, var everReady: Boolean = false, var playing: Boolean = false,
        var background: Boolean = false, var accumulatedPlayMs: Long = 0L, var activePlayStartMs: Long? = null,
        var initialAttempted: Boolean = false, var lastReported: PlaybackHeartbeatSnapshot? = null,
        var lastCreatorRecordedRealSeconds: Long = 0L)
    private var current: Session? = null

    fun begin(identity: DesktopHeartbeatIdentity, positionSeconds: Double) {
        current = Session(++nextSessionId, identity, resolvePlaybackHeartbeatSessionStartTsSec(0L, epochSec()),
            secondsToMs(positionSeconds))
    }

    fun observe(native: PlayerState, inBackground: Boolean, suspended: Boolean = false) {
        val session = current ?: return
        session.positionMs = secondsToMs(native.positionSeconds)
        val ready = isMediaReadyForRecovery(native)
        session.everReady = session.everReady || ready
        session.playing = ready && !native.paused && !suspended
        session.background = inBackground
        syncActive(session, session.playing && !inBackground, elapsedMs())
    }

    fun initialReport(): DesktopHeartbeatReport? {
        val session = current ?: return null
        if (session.initialAttempted || !shouldSendInitialPlaybackHeartbeat(session.playing, session.background,
                session.identity.bvid, session.identity.cid)) return null
        session.initialAttempted = true
        return DesktopHeartbeatReport(session.identity, session.id, PlaybackHeartbeatSnapshot(0L, 0L), session.startTsSec, initial = true)
    }

    fun periodicReport(): DesktopHeartbeatReport? {
        val session = current ?: return null
        if (!shouldSendPlaybackHeartbeat(session.playing, session.background, session.identity.bvid, session.identity.cid)) return null
        return report(session)
    }

    fun finalReport(): DesktopHeartbeatReport? {
        val session = current ?: return null
        syncActive(session, false, elapsedMs())
        session.playing = false
        if (!session.everReady) return null
        val report = report(session)
        return report.takeIf { shouldFlushPlaybackHeartbeatSnapshot(session.identity.bvid, session.identity.cid,
            report.snapshot, session.lastReported) }
    }

    /** An old report can finish after a new source starts, including the same BV/CID; it never acknowledges that new session. */
    fun confirm(report: DesktopHeartbeatReport): Boolean {
        val session = current ?: return false
        if (session.id != report.playbackSessionId || session.identity != report.identity) return false
        session.lastReported = report.snapshot
        return true
    }

    fun takeCreatorWatchDelta(): Long {
        val session = current ?: return 0L
        val watchedSeconds = report(session).snapshot.realPlayedTimeSec
        val delta = (watchedSeconds - session.lastCreatorRecordedRealSeconds).coerceIn(0L, 45L)
        session.lastCreatorRecordedRealSeconds = watchedSeconds
        return delta
    }

    fun reset() { current = null }

    private fun report(session: Session) = DesktopHeartbeatReport(session.identity, session.id,
        resolvePlaybackHeartbeatSnapshot(session.positionMs, session.accumulatedPlayMs, session.activePlayStartMs, elapsedMs()),
        session.startTsSec)

    // Original PlayerVM syncHeartbeatPlaybackTracking with System.nanoTime replacing Android elapsedRealtime.
    private fun syncActive(session: Session, active: Boolean, nowElapsedMs: Long) {
        if (active) {
            if (session.activePlayStartMs == null) session.activePlayStartMs = nowElapsedMs
            return
        }
        val started = session.activePlayStartMs ?: return
        session.accumulatedPlayMs += (nowElapsedMs - started).coerceAtLeast(0L)
        session.activePlayStartMs = null
    }

    private fun secondsToMs(seconds: Double): Long = if (seconds.isFinite()) (seconds.coerceAtLeast(0.0) * 1_000L).toLong() else 0L
}

private fun desktopElapsedClock(): () -> Long {
    val startNanos = System.nanoTime()
    return { ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L) + 1L }
}

/** Bounded serial HTTP work, separate from UI/navigation cancellation, with a bounded final flush and account guard. */
internal class DesktopHeartbeatReporter(
    private val accountEpoch: () -> Long,
    private val send: suspend (DesktopHeartbeatReport) -> Boolean,
    private val onAccepted: suspend (DesktopHeartbeatReport) -> Unit,
    private val requestTimeoutMs: Long = 8_000L,
    private val closeTimeoutMs: Long = 20_000L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accepting = AtomicBoolean(true)
    private val reports = Channel<DesktopHeartbeatReport>(16, BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            try {
                for (report in reports) {
                    if (accountEpoch() != report.identity.sessionEpoch) continue
                    val accepted = try { withTimeout(requestTimeoutMs) { send(report) } }
                    catch (_: TimeoutCancellationException) { false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    if (accepted && accountEpoch() == report.identity.sessionEpoch) {
                        try { onAccepted(report) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { }
                    }
                }
            } finally { scope.cancel() }
        }
    }

    fun submit(report: DesktopHeartbeatReport?) {
        if (report != null && accepting.get()) reports.trySend(report)
    }

    fun close(finalReport: DesktopHeartbeatReport? = null) {
        if (!accepting.compareAndSet(true, false)) return
        while (reports.tryReceive().isSuccess) { }
        if (finalReport != null) reports.trySend(finalReport)
        reports.close()
        scope.launch { delay(closeTimeoutMs); scope.cancel() }
    }

    /** Ownership transfer waits outside Store/entry locks. False means this existing
     * producer has not drained; the caller must not start a replacement reporter. */
    internal suspend fun closeAndJoin(finalReport: DesktopHeartbeatReport? = null): Boolean {
        close(finalReport)
        return withTimeoutOrNull(closeTimeoutMs + 1_000L) {
            requireNotNull(scope.coroutineContext[Job]).join()
            true
        } ?: false
    }
}
