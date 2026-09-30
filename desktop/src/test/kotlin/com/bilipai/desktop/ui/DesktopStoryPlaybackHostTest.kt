package com.bilipai.desktop.ui

import com.bilipai.desktop.DesktopPlaybackState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlayerState
import kotlin.test.*

class DesktopStoryPlaybackHostTest {
    private fun card(id: String, cid: Long = 7) = VideoCard(id, id, "", "", 0, 120, preferredCid = cid)
    private fun request(owner: DesktopStoryOwner, revision: Long = 1, select: Boolean = true,
        queue: List<VideoCard> = listOf(card("av51")), index: Int = 0) =
        DesktopStoryPlaybackRequest(owner, revision, queue, index, select)

    @Test fun `inactive stale invalid and previously released routes cannot acquire playback`() {
        val f = Fixture(); val owner = DesktopStoryOwner("first", 7)
        assertFalse(f.host.request(request(owner), active = false))
        assertFalse(f.host.request(request(owner.copy(sessionEpoch = 6)), true))
        assertFalse(f.host.request(request(owner, revision = -1), true))
        assertFalse(f.host.request(request(owner, queue = listOf(card(""))), true))
        assertFalse(f.host.request(request(owner, index = 1), true))
        assertFalse(f.host.request(request(owner, queue = List(10_001) { card("av51") }), true))
        assertFalse(f.host.release(owner))
        assertFalse(f.host.request(request(owner), true))
        assertEquals(0, f.acquisitions); assertEquals(0, f.player.opens); assertNull(f.host.owner.value)
    }

    @Test fun `equal route values retain one reference token through page selection and append`() {
        val f = Fixture(); val first = DesktopStoryOwner("first", 7); val equal = first.copy()
        assertFalse(first === equal)
        assertTrue(f.host.request(request(first), true))
        assertTrue(f.host.request(request(equal, 2, queue = listOf(card("av51"), card("BV-two")), index = 1), true))
        assertSame(first, f.player.token); assertSame(first, f.host.owner.value)
        assertTrue(f.host.request(request(equal, 3, select = false,
            queue = listOf(card("av51"), card("BV-two"), card("BV-three")), index = 1), true))
        assertSame(first, f.player.lastUpdateToken)
        assertEquals(2, f.player.opens); assertEquals(2, f.acquisitions); assertEquals(0, f.player.stops)
    }

    @Test fun `append retains stream token pause and progress and rejects reordered stale messages`() {
        val f = Fixture(); val owner = DesktopStoryOwner("first", 7)
        assertTrue(f.host.request(request(owner), true))
        f.player.position = 31.25; f.player.paused = true
        val source = f.player.source
        assertTrue(f.host.request(request(owner, 3, select = false, queue = listOf(card("av51"), card("BV-two"))), true))
        assertFalse(f.host.request(request(owner, 2), true))
        assertFalse(f.host.request(request(owner, 3), true))
        assertSame(source, f.player.source); assertEquals(31.25, f.player.position); assertTrue(f.player.paused)
        assertEquals(1, f.player.opens); assertEquals(1, f.acquisitions); assertEquals(2, f.player.queue.size)
        assertFalse(f.host.request(request(owner, 4, select = false, queue = listOf(card("av51", 9))), true))
        // A rejected queue cannot advance the accepted revision.
        assertTrue(f.host.request(request(owner, 4, select = false, queue = listOf(card("av51"), card("BV-two"))), true))
    }

    @Test fun `foreign native takeover retires route without stopping or reacquiring foreign media`() {
        val f = Fixture(); val owner = DesktopStoryOwner("first", 7)
        assertTrue(f.host.request(request(owner), true))
        f.player.token = Any(); val foreignSource = Any(); f.player.source = foreignSource
        assertFalse(f.host.request(request(owner, 2), true))
        assertNull(f.host.owner.value); assertFalse(f.host.request(request(owner, 3), true))
        assertFalse(f.host.release(owner)); f.host.close()
        assertSame(foreignSource, f.player.source); assertEquals(0, f.player.stops); assertEquals(1, f.acquisitions)
    }

    @Test fun `switch retires previous lease while delayed release cannot stop new page`() {
        val f = Fixture(); val first = DesktopStoryOwner("first", 7); val second = DesktopStoryOwner("second", 7)
        assertTrue(f.host.request(request(first), true))
        assertTrue(f.host.request(request(second), true))
        val source = f.player.source
        assertEquals(1, f.player.stops); assertFalse(f.host.release(first))
        assertFalse(f.host.request(request(first, 3), true))
        assertSame(source, f.player.source); assertTrue(f.host.owns(second))
        assertTrue(f.host.release(second)); assertEquals(2, f.player.stops)
    }

    @Test fun `changed account and closing host reject stale route and never stop foreign epoch`() {
        val f = Fixture(); val owner = DesktopStoryOwner("first", 7)
        assertTrue(f.host.request(request(owner), true))
        val source = f.player.source; f.epoch = 8
        assertFalse(f.host.owns(owner))
        assertNull(f.host.snapshot(DesktopPlaybackState(), PlayerState()).owner)
        f.host.close(); assertSame(source, f.player.source); assertEquals(0, f.player.stops)
        assertFalse(f.host.request(request(DesktopStoryOwner("new", 8)), true))
    }

    @Test fun `snapshot uses canonical detail CID and native errors only for the held lease`() {
        val f = Fixture(); val owner = DesktopStoryOwner("first", 7)
        f.host.request(request(owner), true)
        val details = VideoDetails("BV-canonical", 51, "title", "", "", "", 0, 0,
            listOf(VideoPart(7, "one", 30), VideoPart(9, "two", 40)))
        val state = DesktopPlaybackState(details = details, currentPart = 1, queue = listOf(card("av51")), queueIndex = 0)
        val snapshot = f.host.snapshot(state, PlayerState(loading = true, error = "native error"))
        assertSame(owner, snapshot.owner); assertEquals("BV-canonical", snapshot.bvid); assertEquals(9L, snapshot.cid)
        assertEquals(0, snapshot.queueIndex); assertTrue(snapshot.loading); assertEquals("native error", snapshot.error)
        val pending = f.host.snapshot(state.copy(details = null, opening = true, error = "fetch error"), PlayerState())
        assertEquals("av51", pending.bvid); assertEquals(7L, pending.cid); assertEquals("fetch error", pending.error)
        f.player.token = Any()
        assertEquals(DesktopStoryPlaybackSnapshot(), f.host.snapshot(state, PlayerState(error = "foreign")))
    }

    @Test fun `close stops only held queue once and prevents late route requests`() {
        val f = Fixture(); val owner = DesktopStoryOwner("first", 7)
        f.host.request(request(owner), true)
        f.host.close(); f.host.close()
        assertEquals(1, f.player.stops); assertNull(f.host.owner.value)
        assertFalse(f.host.request(request(owner, 2), true))
        assertFalse(f.host.request(request(DesktopStoryOwner("new", 7)), true))
    }

    private class Fixture {
        var epoch = 7L; var acquisitions = 0
        val player = QueuePlayer()
        val host = DesktopStoryPlaybackHost(player, { epoch }, { acquisitions++ })
    }
    private class QueuePlayer : DesktopStoryQueuePlayer {
        var token: Any? = null; var source = Any(); var position = 0.0; var paused = false
        var queue = emptyList<VideoCard>(); var index = -1
        var opens = 0; var stops = 0; var lastUpdateToken: Any? = null
        override fun ownsQueue(owner: Any) = token === owner
        override fun openQueue(cards: List<VideoCard>, index: Int, owner: Any) {
            token = owner; source = Any(); position = 0.0; paused = false; queue = cards; this.index = index; opens++
        }
        override fun updateQueueForOwner(owner: Any, cards: List<VideoCard>, index: Int): Boolean {
            if (!ownsQueue(owner) || cards.getOrNull(index) != queue.getOrNull(this.index)) return false
            lastUpdateToken = owner; queue = cards; this.index = index; return true
        }
        override fun stopQueueForOwner(owner: Any): Boolean {
            if (!ownsQueue(owner)) return false
            stops++; token = null; return true
        }
    }
}
