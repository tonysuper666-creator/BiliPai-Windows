package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopHotDanmakuNativeClockTest {
    @Test fun `position uses the actual clock port while queue intent creates no discontinuity`() {
        var actualPosition = 1_500L
        val clock = DesktopHotDanmakuNativeClock({ actualPosition }, { true }, PlayerState(positionSeconds = 1.0))
        val events = events(clock)
        assertEquals(1_500L, clock.currentPosition)
        actualPosition = 2_000L
        clock.accept(PlayerState(positionSeconds = 20.0, loading = true))
        clock.accept(PlayerState(positionSeconds = 2.0, loading = false))
        assertEquals(2_000L, clock.currentPosition)
        assertTrue(events.isEmpty(), "Position or loading intent is not a native seek completion")
    }

    @Test fun `actual seek ID and readback refresh once using readback rather than displayed position`() {
        val clock = DesktopHotDanmakuNativeClock({ 5_000L }, { true }, PlayerState(positionSeconds = 1.0))
        val events = events(clock)
        clock.accept(PlayerState(positionSeconds = 2.0))
        val completed = PlayerState(positionSeconds = 9.0, seekCompletedId = 7, seekCompletedPositionSeconds = 4.25)
        clock.accept(completed)
        clock.accept(completed.copy(positionSeconds = 10.0))
        clock.accept(completed.copy(positionSeconds = 10.0, seekCompletedId = 6, seekCompletedPositionSeconds = 0.5))
        clock.accept(completed.copy(positionSeconds = 11.0, seekCompletedId = 8, seekCompletedPositionSeconds = 10.5))
        assertEquals(listOf(Event(2_000, 4_250, 1), Event(10_000, 10_500, 1)), events)
    }

    @Test fun `initial completion is not replayed when a new source clock subscribes`() {
        val initial = PlayerState(positionSeconds = 8.0, seekCompletedId = 31, seekCompletedPositionSeconds = 7.5)
        val clock = DesktopHotDanmakuNativeClock({ 8_000L }, { true }, initial)
        val events = events(clock)
        clock.accept(initial)
        clock.accept(initial.copy(positionSeconds = 8.1))
        assertTrue(events.isEmpty())
        clock.accept(initial.copy(seekCompletedId = 32, seekCompletedPositionSeconds = 2.0))
        assertEquals(listOf(Event(8_100, 2_000, 1)), events)
    }

    @Test fun `incomplete or invalid readback cannot acknowledge a seek ID`() {
        val clock = DesktopHotDanmakuNativeClock({ 1_000L }, { true }, PlayerState(positionSeconds = 1.0))
        val events = events(clock)
        listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { invalid ->
            clock.accept(PlayerState(positionSeconds = 1.0, seekCompletedId = 4, seekCompletedPositionSeconds = invalid))
        }
        assertTrue(events.isEmpty())
        clock.accept(PlayerState(positionSeconds = 1.0, seekCompletedId = 4, seekCompletedPositionSeconds = 0.0))
        assertEquals(listOf(Event(1_000, 0, 1)), events)
        clock.accept(PlayerState(positionSeconds = 2.0, seekCompletedId = 4, seekCompletedPositionSeconds = 1.0))
        assertEquals(1, events.size)
    }

    @Test fun `duplicate listener registration and removal do not multiply callbacks`() {
        val clock = DesktopHotDanmakuNativeClock({ 0L }, { true }, PlayerState())
        val seen = mutableListOf<Event>()
        val listener = recording(seen)
        clock.addListener(listener)
        clock.addListener(listener)
        clock.accept(PlayerState(seekCompletedId = 1, seekCompletedPositionSeconds = 2.0))
        assertEquals(listOf(Event(0, 2_000, 1)), seen)
        clock.removeListener(listener)
        clock.accept(PlayerState(seekCompletedId = 2, seekCompletedPositionSeconds = 3.0))
        assertEquals(1, seen.size)
    }

    @Test fun `retired source suppresses position port subscriptions and completion callbacks`() {
        var owned = true
        var positionReads = 0
        val clock = DesktopHotDanmakuNativeClock({ positionReads++; 5_000L }, { owned }, PlayerState())
        val seen = events(clock)
        assertEquals(5_000L, clock.currentPosition)
        owned = false
        clock.addListener(recording(seen))
        clock.accept(PlayerState(seekCompletedId = 1, seekCompletedPositionSeconds = 8.0))
        assertEquals(0L, clock.currentPosition)
        assertEquals(1, positionReads, "A retired source must not consult its native clock port")
        assertTrue(seen.isEmpty())
    }

    @Test fun `retirement during first listener callback prevents remaining callbacks`() {
        var owned = true
        val clock = DesktopHotDanmakuNativeClock({ 0L }, { owned }, PlayerState())
        val seen = mutableListOf<Event>()
        clock.addListener(object : DesktopHotDanmakuPlayer.Listener {
            override fun onPositionDiscontinuity(oldPosition: DesktopHotDanmakuPlayer.PositionInfo,
                newPosition: DesktopHotDanmakuPlayer.PositionInfo, reason: Int) { owned = false }
        })
        clock.addListener(recording(seen))
        clock.accept(PlayerState(seekCompletedId = 1, seekCompletedPositionSeconds = 3.0))
        assertTrue(seen.isEmpty())
    }

    @Test fun `listener removal during callback is safe for the current delivery snapshot`() {
        val clock = DesktopHotDanmakuNativeClock({ 0L }, { true }, PlayerState())
        val seen = mutableListOf<Event>()
        lateinit var listener: DesktopHotDanmakuPlayer.Listener
        listener = object : DesktopHotDanmakuPlayer.Listener {
            override fun onPositionDiscontinuity(oldPosition: DesktopHotDanmakuPlayer.PositionInfo,
                newPosition: DesktopHotDanmakuPlayer.PositionInfo, reason: Int) {
                seen += Event(oldPosition.positionMs, newPosition.positionMs, reason)
                clock.removeListener(listener)
            }
        }
        clock.addListener(listener)
        clock.accept(PlayerState(seekCompletedId = 1, seekCompletedPositionSeconds = 1.0))
        clock.accept(PlayerState(seekCompletedId = 2, seekCompletedPositionSeconds = 2.0))
        assertEquals(listOf(Event(0, 1_000, 1)), seen)
    }

    private data class Event(val oldMs: Long, val nextMs: Long, val reason: Int)
    private fun recording(seen: MutableList<Event>) = object : DesktopHotDanmakuPlayer.Listener {
        override fun onPositionDiscontinuity(oldPosition: DesktopHotDanmakuPlayer.PositionInfo,
            newPosition: DesktopHotDanmakuPlayer.PositionInfo, reason: Int) {
            seen += Event(oldPosition.positionMs, newPosition.positionMs, reason)
        }
    }
    private fun events(clock: DesktopHotDanmakuNativeClock): MutableList<Event> =
        mutableListOf<Event>().also { clock.addListener(recording(it)) }
}
