package com.bilipai.desktop.ui

import com.bilipai.desktop.data.VideoCard
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*

/*
 * Slice K3i, the Home 小窗播放 intent against a recording fake of the Root player. The fake keeps the facts the real
 * Root adapter reads (session, current item, NONE source, accepted source with its native version, mini state) and
 * records every call, so each case pins one rule of the intent:
 *   * the order of an ordinary click: baseline session and version, media exclusion, then the ordinary open;
 *   * a refused navigation admission runs nothing (no exclusion, no open, no mini);
 *   * an open or opening mini window rejects before the admission;
 *   * correlation needs a new session (or the reused one that plays this card), this card as current item and NONE;
 *   * the wait only takes a source newer than the baseline, so an earlier A of A -> B -> A is never entered;
 *   * a failed load rejects, a replaced session withdraws silently; nothing ever pauses or stops the player.
 */
class DesktopHomeCardMiniIntentTest {
    private val bvid = "BV1xx411c7mD"
    private val other = "BV1yy411c7mE"

    private fun target(cid: Long = 71L, id: String = bvid) =
        DesktopHomeCardTarget(id, 11L, cid, VideoCard(id, "K3i", "", "up", 1, 60, preferredCid = cid))

    private class Session(val n: Int)

    /** The Root player as the intent sees it; open() plays the card the way the ordinary click does. */
    private inner class FakePlayback : DesktopHomeCardMiniPlayback<Session> {
        val calls = mutableListOf<String>()
        var pip = false
        var ready = true
        var session = Session(0)
        var item: Pair<String, Long>? = null
        var none = true
        var acceptedSource: DesktopHomeCardMiniAccepted? = null
        var version: Long? = 5L
        var failed = false
        var miniReady = true
        var reuse = false
        var openThrows = false
        /** What open() leaves: by default a new session on the card with the NONE source. */
        var onOpen: (VideoCard) -> Unit = { card ->
            session = Session(session.n + 1); item = card.bvid to card.preferredCid; none = true
        }

        override fun pipActiveOrPending(): Boolean { calls += "pip"; return pip }
        override fun captureSession(): Session? { calls += "capture"; return if (ready) session else null }
        override fun isSessionCurrent(session: Session): Boolean = session === this.session
        override fun currentItem(): Pair<String, Long>? = item
        override fun sourceIsNone(): Boolean = none
        override fun accepted(): DesktopHomeCardMiniAccepted? = acceptedSource
        override fun sourceVersion(): Long? { calls += "version"; return version }
        override fun excludeOtherMedia(target: DesktopHomeCardTarget) { calls += "exclude:${target.bvid}" }
        override fun open(card: VideoCard): Boolean {
            calls += "open:${card.bvid}"
            if (openThrows) throw IllegalStateException("retired")
            if (!reuse) onOpen(card)
            return reuse
        }
        override fun loadFailed(bvid: String): Boolean = failed
        override fun enterMini(accepted: DesktopHomeCardMiniAccepted): Boolean {
            calls += "mini:${accepted.bvid}/${accepted.cid}/${accepted.sourceVersion}"; return miniReady
        }
    }

    private val admit: ((() -> Unit) -> Boolean) = { block -> block(); true }

    private fun waiting(result: DesktopHomeCardMiniResult): DesktopHomeCardMiniResult.Waiting<Session> {
        assertIs<DesktopHomeCardMiniResult.Waiting<*>>(result, "The request waits for its source")
        @Suppress("UNCHECKED_CAST") return result as DesktopHomeCardMiniResult.Waiting<Session>
    }

    private fun rejected(result: DesktopHomeCardMiniResult?): String =
        assertIs<DesktopHomeCardMiniResult.Rejected>(result).message

    @Test fun anOrdinaryRequestRunsInTheOrderOfAClickAndEntersTheMiniOnceForItsOwnSource() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        assertEquals(listOf("pip", "capture", "version", "exclude:$bvid", "open:$bvid", "capture"), p.calls,
            "Baseline, media exclusion, then the ordinary open, then correlation")
        assertFalse(w.reused)
        assertEquals(5L, w.baselineVersion)
        assertNull(intent.check(w), "No accepted source yet: keep waiting")
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertSame(DesktopHomeCardMiniResult.Entered, intent.check(w))
        assertEquals(1, p.calls.count { it.startsWith("mini:") }, "One mini entry")
        assertEquals("mini:$bvid/71/6", p.calls.last())
    }

    @Test fun aRefusedAdmissionRunsNothing() {
        val p = FakePlayback()
        val result = DesktopHomeCardMiniIntent(target(), p).start { false }
        assertEquals(DESKTOP_HOME_MINI_NOT_READY, rejected(result))
        assertEquals(listOf("pip"), p.calls, "Neither exclusion, nor open, nor mini without admission")
    }

    @Test fun anOpenMiniRejectsBeforeTheAdmission() {
        val p = FakePlayback().apply { pip = true }
        var admissions = 0
        val result = DesktopHomeCardMiniIntent(target(), p).start { block -> admissions++; block(); true }
        assertEquals(DESKTOP_HOME_MINI_PIP_BUSY, rejected(result))
        assertEquals(0, admissions, "The admission is never asked while a mini is open")
        assertEquals(listOf("pip"), p.calls)
    }

    @Test fun aRootWithoutAReadyPlayerIsNotReadyAndOpensNothing() {
        val p = FakePlayback().apply { ready = false }
        assertEquals(DESKTOP_HOME_MINI_NOT_READY, rejected(DesktopHomeCardMiniIntent(target(), p).start(admit)))
        assertFalse(p.calls.any { it.startsWith("exclude") || it.startsWith("open") }, "${p.calls}")
    }

    @Test fun aRetiredRootThatRefusesTheOpenIsNotReady() {
        val p = FakePlayback().apply { openThrows = true }
        assertEquals(DESKTOP_HOME_MINI_NOT_READY, rejected(DesktopHomeCardMiniIntent(target(), p).start(admit)))
        assertFalse(p.calls.any { it.startsWith("mini") })
    }

    @Test fun aQueueWithAnotherSourceThanNoneIsRejected() {
        val p = FakePlayback()
        p.onOpen = { card -> p.session = Session(9); p.item = card.bvid to card.preferredCid; p.none = false }
        assertEquals(DESKTOP_HOME_MINI_REJECTED, rejected(DesktopHomeCardMiniIntent(target(), p).start(admit)),
            "An UNKNOWN (or any non-NONE) source is not the ordinary click semantics")
    }

    @Test fun anOpenThatLeavesTheSameSessionIsRejected() {
        val p = FakePlayback()
        p.onOpen = { card -> p.item = card.bvid to card.preferredCid; p.none = true }
        assertEquals(DESKTOP_HOME_MINI_REJECTED, rejected(DesktopHomeCardMiniIntent(target(), p).start(admit)),
            "Without a new session the request is not correlated to this click")
    }

    @Test fun anOpenThatLeavesAnotherItemIsRejected() {
        val p = FakePlayback()
        p.onOpen = { _ -> p.session = Session(9); p.item = other to 71L; p.none = true }
        assertEquals(DESKTOP_HOME_MINI_REJECTED, rejected(DesktopHomeCardMiniIntent(target(), p).start(admit)))
        val q = FakePlayback()
        q.onOpen = { card -> q.session = Session(9); q.item = card.bvid to 72L; q.none = true }
        assertEquals(DESKTOP_HOME_MINI_REJECTED, rejected(DesktopHomeCardMiniIntent(target(), q).start(admit)),
            "Another cid of the same video is another item")
    }

    @Test fun theEarlierPublicationOfAnABAIsNotTakenForTheRequest() {
        val p = FakePlayback().apply { version = 8L }
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 7L)
        assertNull(intent.check(w), "A source of A older than the baseline is the earlier A")
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 8L)
        assertNull(intent.check(w), "The baseline source itself is not this request")
        p.acceptedSource = DesktopHomeCardMiniAccepted(other, 71L, 9L)
        assertNull(intent.check(w), "Another video is not this request")
        assertFalse(p.calls.any { it.startsWith("mini") }, "${p.calls}")
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 9L)
        assertSame(DesktopHomeCardMiniResult.Entered, intent.check(w))
    }

    @Test fun aFailedLoadRejectsAndEntersNoMini() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        p.failed = true
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertEquals(DESKTOP_HOME_MINI_FAILED, rejected(intent.check(w)))
        assertFalse(p.calls.any { it.startsWith("mini") })
    }

    @Test fun aReplacedSessionWithdrawsSilently() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        p.session = Session(42)
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertSame(DesktopHomeCardMiniResult.Withdrawn, intent.check(w))
        assertFalse(p.calls.any { it.startsWith("mini") })
    }

    @Test fun aMiniOpenedDuringTheWaitRejectsAsBusy() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        p.pip = true
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertEquals(DESKTOP_HOME_MINI_PIP_BUSY, rejected(intent.check(w)))
    }

    @Test fun aChangedSelectionWithinTheSameSessionWithdrawsTheOldRequest() {
        for (selection in listOf(null, other to 71L, bvid to 72L)) {
            val p = FakePlayback()
            val intent = DesktopHomeCardMiniIntent(target(), p)
            val w = waiting(intent.start(admit))
            p.item = selection
            p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
            assertSame(DesktopHomeCardMiniResult.Withdrawn, intent.check(w), "$selection")
            assertFalse(p.calls.any { it.startsWith("mini:") })
        }
    }

    @Test fun aChangedQueueSourceWithinTheSameSessionWithdrawsTheRequest() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        p.none = false
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertSame(DesktopHomeCardMiniResult.Withdrawn, intent.check(w))
        assertFalse(p.calls.any { it.startsWith("mini:") })
    }

    @Test fun anInitiallyUnresolvedCidMayEnterWithItsMatchingResolvedSelection() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(cid = 0L), p)
        val w = waiting(intent.start(admit))
        p.item = bvid to 71L
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertSame(DesktopHomeCardMiniResult.Entered, intent.check(w))
        assertEquals(1, p.calls.count { it.startsWith("mini:") })
    }

    @Test fun anInitiallyUnresolvedCidCannotEnterAnotherResolvedSelection() {
        val p = FakePlayback()
        val intent = DesktopHomeCardMiniIntent(target(cid = 0L), p)
        val w = waiting(intent.start(admit))
        p.item = bvid to 72L
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        assertSame(DesktopHomeCardMiniResult.Withdrawn, intent.check(w))
        assertFalse(p.calls.any { it.startsWith("mini:") })
    }

    @Test fun theReusedSessionThatPlaysTheCardEntersWithItsAcceptedSource() {
        val p = FakePlayback().apply {
            reuse = true; item = bvid to 71L; none = true; acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 5L)
        }
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        assertTrue(w.reused)
        assertSame(DesktopHomeCardMiniResult.Entered, intent.check(w), "The reused source is already this card")
    }

    @Test fun aReusedSessionThatPlaysAnotherCardIsRejected() {
        val p = FakePlayback().apply {
            reuse = true; item = bvid to 71L; none = true; acceptedSource = DesktopHomeCardMiniAccepted(other, 71L, 5L)
        }
        assertEquals(DESKTOP_HOME_MINI_REJECTED, rejected(DesktopHomeCardMiniIntent(target(), p).start(admit)))
    }

    @Test fun theMiniThatIsNotReadyYetKeepsWaitingAndAwaitEntersOnTheNextChange() = runTest {
        val p = FakePlayback().apply { miniReady = false }
        val intent = DesktopHomeCardMiniIntent(target(), p)
        val w = waiting(intent.start(admit))
        val changes = MutableSharedFlow<Unit>()
        var outcome: DesktopHomeCardMiniResult? = null
        val job = launch { outcome = intent.await(w, changes) }
        yield()
        p.acceptedSource = DesktopHomeCardMiniAccepted(bvid, 71L, 6L)
        changes.emit(Unit); yield()
        assertNull(outcome, "The presentation did not admit the mini yet")
        p.miniReady = true
        changes.emit(Unit); job.join()
        assertSame(DesktopHomeCardMiniResult.Entered, outcome)
    }
}
