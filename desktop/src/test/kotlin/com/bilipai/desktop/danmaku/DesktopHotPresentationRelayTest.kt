package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.engine.DanmakuItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The counted hot bar reports once and drops identical reports, so a report the overlay rejected (document or
 * reservation not current at that moment) must be offered again by the Windows side. No window, GPU or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopHotPresentationRelayTest {
    private fun item(id: Long) = DanmakuItem().apply { danmakuId = id; text = "banner $id" }

    private class Overlay {
        var accepting = false
        val received = mutableListOf<Pair<List<DanmakuItem>, Float>>()
        fun update(items: List<DanmakuItem>, heightPx: Float): Boolean {
            if (!accepting) return false
            received += items to heightPx
            return true
        }
    }

    @Test
    fun `a rejected report followed by the identical report is delivered once the overlay accepts`() {
        val overlay = Overlay()
        val relay = DesktopHotPresentationRelay(overlay::update)
        val shown = listOf(item(1L), item(2L))
        assertFalse(relay.report(shown, 48f))
        assertTrue(relay.isWaiting.value)
        assertTrue(overlay.received.isEmpty())
        // The overlay becomes ready; the bar never repeats the identical report, so the relay does.
        overlay.accepting = true
        assertTrue(relay.retry())
        assertFalse(relay.isWaiting.value)
        assertEquals(1, overlay.received.size)
        assertSame(shown, overlay.received.single().first)
        assertEquals(48f, overlay.received.single().second)
        // Nothing is waiting any more, so a further retry offers nothing.
        assertTrue(relay.retry())
        assertEquals(1, overlay.received.size)
    }

    @Test
    fun `a newer report replaces a waiting one and an empty report clears the shown entries`() {
        val overlay = Overlay()
        val relay = DesktopHotPresentationRelay(overlay::update)
        relay.report(listOf(item(1L)), 40f)
        relay.report(listOf(item(2L), item(3L)), 52f)
        overlay.accepting = true
        assertTrue(relay.retry())
        assertEquals(listOf(2L, 3L), overlay.received.single().first.map { it.danmakuId })
        assertEquals(52f, overlay.received.single().second)
        assertTrue(relay.report(emptyList(), 0f))
        assertEquals(2, overlay.received.size)
        assertTrue(overlay.received.last().first.isEmpty())
        assertEquals(0f, overlay.received.last().second)
    }

    @Test
    fun `resend offers the last report again after a new reservation and is silent before any report`() {
        val overlay = Overlay().apply { accepting = true }
        val relay = DesktopHotPresentationRelay(overlay::update)
        assertTrue(relay.resend())
        assertTrue(overlay.received.isEmpty())
        val shown = listOf(item(7L))
        assertTrue(relay.report(shown, 44f))
        assertEquals(1, overlay.received.size)
        // A new reservation of the overlay starts without a selection; the relay hands the same entries over again.
        assertTrue(relay.resend())
        assertEquals(2, overlay.received.size)
        assertSame(shown, overlay.received.last().first)
        assertEquals(44f, overlay.received.last().second)
        // A resend that is rejected leaves the report waiting for the retry loop.
        overlay.accepting = false
        assertFalse(relay.resend())
        assertTrue(relay.isWaiting.value)
    }

    @Test
    fun `a report that makes the overlay throw is not kept and the earlier waiting report survives`() {
        var accepting = false
        val taken = mutableListOf<Float>()
        val relay = DesktopHotPresentationRelay { _, heightPx ->
            require(heightPx.isFinite() && heightPx >= 0f)
            if (accepting) taken += heightPx
            accepting
        }
        assertFalse(relay.report(listOf(item(1L)), 40f))
        assertFailsWith<IllegalArgumentException> { relay.report(listOf(item(2L)), Float.NaN) }
        assertTrue(relay.isWaiting.value)
        accepting = true
        assertTrue(relay.retry())
        assertEquals(listOf(40f), taken)
    }

    @Test
    fun `the redelivery loop keeps offering a waiting report until it is taken and then goes quiet`() = runTest {
        var calls = 0
        var accepting = false
        val relay = DesktopHotPresentationRelay { _, _ -> calls += 1; accepting }
        backgroundScope.launch { relay.redeliverWhileWaiting(intervalMs = 100L) }
        runCurrent()
        assertEquals(0, calls)
        assertFalse(relay.report(listOf(item(1L)), 40f))
        runCurrent()
        advanceTimeBy(350L)
        runCurrent()
        assertTrue(calls >= 4, "the waiting report is offered again on every interval, offered $calls times")
        assertTrue(relay.isWaiting.value)
        accepting = true
        advanceTimeBy(150L)
        runCurrent()
        assertFalse(relay.isWaiting.value)
        val settled = calls
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(settled, calls)
    }
}
