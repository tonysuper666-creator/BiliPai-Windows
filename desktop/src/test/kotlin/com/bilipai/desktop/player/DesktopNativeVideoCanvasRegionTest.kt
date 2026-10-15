package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** These execute the production region controller with an explicit native API
 * seam. They do not claim Win32 region/pixel or actual MPV lifecycle validation. */
class DesktopNativeVideoCanvasRegionTest {
    private class FakeNative : DesktopNativeVideoCanvasRegionApi {
        var currentMetrics: DesktopNativeVideoCanvasMetrics? = DesktopNativeVideoCanvasMetrics(400, 200, 0, 0, 400, 200)
        val writes = mutableListOf<Pair<DesktopNativeVideoCanvasPeer, DesktopNativeVideoCanvasPixels?>>()
        var succeeds = true
        var afterMetrics: (() -> Unit)? = null
        var duringSet: (() -> Unit)? = null
        override fun metrics(peer: DesktopNativeVideoCanvasPeer, hostWindowHandle: Long): DesktopNativeVideoCanvasMetrics? {
            afterMetrics?.invoke()
            return currentMetrics
        }
        override fun set(peer: DesktopNativeVideoCanvasPeer, rectangle: DesktopNativeVideoCanvasPixels?): Boolean {
            writes += peer to rectangle
            val result = succeeds
            duringSet?.invoke()
            return result
        }
        override fun read(peer: DesktopNativeVideoCanvasPeer) = 0 to null
    }
    private fun peer(handle: Long = 0x123456789L, generation: Long = 1L) = DesktopNativeVideoCanvasPeer(handle, generation)
    private val crop = DesktopNativeVideoCanvasClip(.25, .25, .75, .75)
    private fun controller(api: FakeNative) = DesktopNativeVideoCanvasRegion({ api }, { true })

    @Test fun `partial clip uses actual client pixels and the native nonclient offset`() {
        val metrics = DesktopNativeVideoCanvasMetrics(2020, 1032, 8, 24, 2000, 1000)
        assertEquals(DesktopNativeVideoCanvasPixels(508, 274, 1508, 774), crop.inWindow(metrics))
    }

    @Test fun `fractional physical boundaries round inward and never expose an outside pixel`() {
        val metrics = DesktopNativeVideoCanvasMetrics(11, 11, 0, 0, 11, 11)
        assertEquals(DesktopNativeVideoCanvasPixels(2, 2, 9, 9),
            DesktopNativeVideoCanvasClip(.1, .1, .9, .9).inWindow(metrics))
    }

    @Test fun `empty clip creates an empty region while full clip restores the native window`() {
        val api = FakeNative(); val region = controller(api); val owner = Any(); val peer = peer()
        assertTrue(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Empty) { true })
        assertEquals(DesktopNativeVideoCanvasPixels(0, 0, 0, 0), api.writes.single().second)
        assertTrue(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Full) { true })
        assertNull(api.writes.last().second)
    }

    @Test fun `a retired entry cannot issue a native write even after metrics were read`() {
        val api = FakeNative(); val region = controller(api); var current = true
        api.afterMetrics = { current = false }
        assertFalse(region.apply(Any(), peer(), crop) { current })
        assertTrue(api.writes.isEmpty())
        assertNull(region.captureReceipt())
    }

    @Test fun `failed native geometry does not produce a successful installation`() {
        val api = FakeNative(); api.currentMetrics = null
        val region = controller(api)
        assertFalse(region.apply(Any(), peer(), crop) { true })
        assertTrue(api.writes.isEmpty())
        assertNull(region.captureReceipt())
    }

    @Test fun `failed SetWindowRgn preserves the old receipt and a later retry can succeed`() {
        val api = FakeNative(); val region = controller(api); val owner = Any(); val peer = peer()
        assertTrue(region.apply(owner, peer, crop) { true })
        val first = assertNotNull(region.captureReceipt())
        api.succeeds = false
        assertFalse(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Empty) { true })
        assertSame(first, region.captureReceipt())
        api.succeeds = true
        assertTrue(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Empty) { true })
        assertEquals(DesktopNativeVideoCanvasClip.Empty, region.captureReceipt()?.clip)
    }

    @Test fun `delayed old cleanup cannot erase a newer installation by the same owner`() {
        val api = FakeNative(); val region = controller(api); val owner = Any(); val peer = peer()
        assertTrue(region.apply(owner, peer, crop) { true })
        val old = assertNotNull(region.captureReceipt())
        assertTrue(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Empty) { true })
        val fresh = assertNotNull(region.captureReceipt()); val count = api.writes.size
        assertFalse(region.release(old) { it === peer })
        assertSame(fresh, region.captureReceipt())
        assertEquals(count, api.writes.size)
    }

    @Test fun `new render lease installs its own receipt even at identical geometry`() {
        val api = FakeNative(); val region = controller(api); val firstOwner = Any(); val successor = Any(); val peer = peer()
        assertTrue(region.apply(firstOwner, peer, crop) { true })
        val old = assertNotNull(region.captureReceipt())
        assertTrue(region.apply(successor, peer, crop) { true })
        assertSame(successor, region.captureReceipt()?.owner)
        assertEquals(2, api.writes.size)
        assertFalse(region.release(old) { true })
    }

    @Test fun `a recycled numerical HWND does not let the old peer clear the new peer`() {
        val api = FakeNative(); val region = controller(api); val owner = Any()
        val oldPeer = peer(); val freshPeer = peer(oldPeer.handle, 2L)
        assertTrue(region.apply(owner, oldPeer, crop) { true })
        val old = assertNotNull(region.captureReceipt())
        region.peerRetired(oldPeer)
        assertTrue(region.apply(owner, freshPeer, crop) { true })
        assertFalse(region.release(old) { it === freshPeer })
        region.peerRetired(oldPeer)
        assertSame(freshPeer, region.captureReceipt()?.peer)
        assertEquals(0x123456789L, api.writes.last().first.handle)
    }

    @Test fun `a peer retired during native apply never gains a live receipt`() {
        val api = FakeNative(); val region = controller(api); val peer = peer()
        api.duringSet = { region.peerRetired(peer) }
        assertFalse(region.apply(Any(), peer, crop) { !peer.retired })
        assertNull(region.captureReceipt())
        assertFalse(region.apply(Any(), peer, crop) { true })
        assertEquals(1, api.writes.size)
    }

    @Test fun `source retirement reentered during the OS write cleans the newly installed receipt`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); var sourceCurrent = true
        api.duringSet = { sourceCurrent = false }
        assertFalse(region.apply(Any(), peer, crop) { sourceCurrent })
        assertNull(region.captureReceipt())
        assertEquals(2, api.writes.size)
        assertNull(api.writes.last().second)
    }

    @Test fun `reentrant retirement seeing the old receipt cannot strand the new installation`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val owner = Any(); var current = true
        assertTrue(region.apply(owner, peer, crop) { true })
        val old = assertNotNull(region.captureReceipt())
        api.duringSet = { current = false; region.release(old) { true } }
        assertFalse(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Empty) { current })
        assertNull(region.captureReceipt())
        assertEquals(3, api.writes.size)
        assertNull(api.writes.last().second)
    }

    @Test fun `failed immediate retirement cleanup retains the new exact receipt for a safe retry`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); var sourceCurrent = true
        api.duringSet = { sourceCurrent = false; api.succeeds = false }
        assertFalse(region.apply(Any(), peer, crop) { sourceCurrent })
        val receipt = assertNotNull(region.captureReceipt())
        assertEquals(2, api.writes.size)
        api.duringSet = null
        api.succeeds = true
        // No retired source/account predicate is needed to clear our OWN receipt.
        assertTrue(region.release(receipt) { it === peer })
        assertNull(region.captureReceipt())
        assertNull(api.writes.last().second)
    }

    @Test fun `actual Canvas movement updates clipping even when Compose and client size stay unchanged`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val owner = Any()
        api.currentMetrics = DesktopNativeVideoCanvasMetrics(100, 100, 0, 0, 100, 100)
        assertTrue(region.apply(owner, peer, crop) { true })
        assertEquals(DesktopNativeVideoCanvasPixels(25, 25, 75, 75), api.writes.last().second)
        api.currentMetrics = DesktopNativeVideoCanvasMetrics(100, 100, 0, 0, 100, 100,
            clientScreenLeft = 10, clientScreenTop = 5)
        assertTrue(region.apply(owner, peer, crop) { true })
        assertEquals(DesktopNativeVideoCanvasPixels(15, 20, 65, 70), api.writes.last().second)
        assertEquals(2, api.writes.size)
    }

    @Test fun `negative monitor coordinates do not get applied as local region coordinates`() {
        val metrics = DesktopNativeVideoCanvasMetrics(100, 100, 0, 0, 100, 100,
            clientScreenLeft = -1910, clientScreenTop = -75, hostScreenLeft = -1920, hostScreenTop = -80)
        assertEquals(DesktopNativeVideoCanvasPixels(15, 20, 65, 70), crop.inWindow(metrics))
    }

    @Test fun `failed cleanup retains its receipt for a safe retry`() {
        val api = FakeNative(); val region = controller(api); val peer = peer()
        assertTrue(region.apply(Any(), peer, crop) { true })
        val receipt = assertNotNull(region.captureReceipt())
        api.succeeds = false
        assertFalse(region.release(receipt) { true })
        assertSame(receipt, region.captureReceipt())
        api.succeeds = true
        assertTrue(region.release(receipt) { true })
    }

    @Test fun `actual native resize reapplies unchanged fractions and Compose bounds`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val owner = Any()
        assertTrue(region.apply(owner, peer, crop) { true })
        assertEquals(DesktopNativeVideoCanvasPixels(100, 50, 300, 150), api.writes.last().second)
        api.currentMetrics = DesktopNativeVideoCanvasMetrics(600, 300, 0, 0, 600, 300)
        assertTrue(region.apply(owner, peer, crop) { true })
        assertEquals(DesktopNativeVideoCanvasPixels(150, 75, 450, 225), api.writes.last().second)
        assertEquals(2, api.writes.size)
    }

    @Test fun `EDT violations fail before any native API access`() {
        var nativeTouched = false
        val region = DesktopNativeVideoCanvasRegion({ nativeTouched = true; FakeNative() }, { false })
        assertFailsWith<IllegalStateException> { region.apply(Any(), peer(), crop) { true } }
        assertFalse(nativeTouched)
    }

    @Test fun `a reentrant replacement cannot overwrite an in-flight region call`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val owner = Any(); var reentrantAccepted = true
        api.duringSet = { reentrantAccepted = region.apply(Any(), peer, DesktopNativeVideoCanvasClip.Empty) { true } }
        assertTrue(region.apply(owner, peer, crop) { true })
        assertFalse(reentrantAccepted)
        assertSame(owner, region.captureReceipt()?.owner)
        assertEquals(1, api.writes.size)
    }

    @Test fun `invalid fractions and overflowing client geometry are rejected before native access`() {
        assertFailsWith<IllegalArgumentException> { DesktopNativeVideoCanvasClip(Double.NaN, 0.0, 1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { DesktopNativeVideoCanvasClip(.8, 0.0, .2, 1.0) }
        assertFailsWith<IllegalArgumentException> { DesktopNativeVideoCanvasMetrics(400, 200, Int.MAX_VALUE, 0, 400, 200) }
    }

    @Test fun `the actual deferred cleanup lane cannot erase a newer same-owner installation`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val owner = Any()
        val tasks = mutableListOf<() -> Unit>(); var lockEntries = 0
        val delivery = DesktopNativeVideoCanvasRegionClearDelivery(region,
            { action -> lockEntries++; action() }, { it === peer }, { tasks += it })
        assertTrue(region.apply(owner, peer, crop) { true })
        delivery.retireLocked()
        assertEquals(1, tasks.size)
        assertEquals(1, api.writes.size) // No native write synchronously under the retiring source lock.
        assertTrue(region.apply(owner, peer, DesktopNativeVideoCanvasClip.Empty) { true })
        val successor = assertNotNull(region.captureReceipt())
        tasks.removeAt(0).invoke()
        assertEquals(1, lockEntries)
        assertSame(successor, region.captureReceipt())
        assertEquals(2, api.writes.size)
    }

    @Test fun `rapid retirement queues one bounded task and clears the latest retired receipt`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val tasks = mutableListOf<() -> Unit>()
        val delivery = DesktopNativeVideoCanvasRegionClearDelivery(region, { it() }, { it === peer }, { tasks += it })
        assertTrue(region.apply(Any(), peer, crop) { true })
        repeat(30) { delivery.retireLocked() }
        assertTrue(region.apply(Any(), peer, DesktopNativeVideoCanvasClip.Empty) { true })
        delivery.retireLocked()
        assertEquals(1, tasks.size)
        tasks.removeAt(0).invoke()
        assertNull(region.captureReceipt())
        assertEquals(3, api.writes.size)
        assertNull(api.writes.last().second)
    }

    @Test fun `the real deferred retry clears a new receipt left by failed reentrant retirement cleanup`() {
        val api = FakeNative(); val region = controller(api); val peer = peer(); val tasks = mutableListOf<() -> Unit>()
        val delivery = DesktopNativeVideoCanvasRegionClearDelivery(region, { it() }, { it === peer }, { tasks += it })
        var current = true
        api.duringSet = { current = false; api.succeeds = false }
        assertFalse(region.apply(Any(), peer, crop) { current })
        assertNotNull(region.captureReceipt())
        delivery.retireLocked()
        api.duringSet = null; api.succeeds = true
        tasks.removeAt(0).invoke()
        assertNull(region.captureReceipt())
        assertEquals(3, api.writes.size)
        assertNull(api.writes.last().second)
    }

    @Test fun `deferred cleanup does not call a destroyed or recycled peer`() {
        val api = FakeNative(); val region = controller(api); val oldPeer = peer(); val tasks = mutableListOf<() -> Unit>()
        var currentPeer = oldPeer
        val delivery = DesktopNativeVideoCanvasRegionClearDelivery(region, { it() }, { it === currentPeer }, { tasks += it })
        assertTrue(region.apply(Any(), oldPeer, crop) { true })
        delivery.retireLocked()
        region.peerRetired(oldPeer)
        currentPeer = peer(oldPeer.handle, 2L)
        assertTrue(region.apply(Any(), currentPeer, crop) { true })
        val successor = assertNotNull(region.captureReceipt())
        tasks.removeAt(0).invoke()
        assertSame(successor, region.captureReceipt())
        assertEquals(2, api.writes.size)
    }
}
