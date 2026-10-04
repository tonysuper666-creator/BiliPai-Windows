package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopNativePresentationTransferTest {
    private val original = PlaybackSource("file:///C:/private-fixture.mp4", startPositionSeconds = 2.0)
    private fun transfer(released: () -> Boolean = { true }) = DesktopNativePresentationTransfer(
        OwnedPlaybackSourceSnapshot(7, original), 11, 100, released)

    @Test fun `cross window attachment requires both worker exit and actual peer release`() {
        var exited = false
        val token = transfer { exited }
        assertFalse(token.attach())
        assertFalse(token.peerReleased())
        assertTrue(token.capture(23.5, false, 3))
        assertFalse(token.peerReleased())
        assertFalse(token.attach())
        exited = true
        assertFalse(token.attach())
        assertTrue(token.peerReleased())
        assertTrue(token.attach())
        assertFalse(token.attach())
    }

    @Test fun `rapid return can keep the existing peer only after the worker has exited`() {
        var exited = false
        val token = transfer { exited }
        assertTrue(token.capture(12.25, true, 4))
        assertFalse(token.attach(existingPeer = true))
        exited = true
        assertTrue(token.attach(existingPeer = true))
        assertFalse(token.hasReleasedPeer)
        assertFalse(token.acknowledge(0.0, true))
        assertFalse(token.acknowledge(12.25, false))
        assertTrue(token.acknowledge(12.25, true))
        assertFalse(token.acknowledge(12.25, true))
    }

    @Test fun `latest pause serial overrides the captured cursor without changing original source`() {
        val token = transfer()
        token.capture(23.5, false, 5)
        token.pause(true, 6)
        token.pause(false, 5)
        assertTrue(assertNotNull(token.resume).paused)
        assertEquals(6, token.resume?.pauseSerial?.toInt())
        assertSame(original, token.source.source)
        assertEquals(2.0, original.startPositionSeconds)
        assertFalse(original.startPaused)
        token.peerReleased(); token.attach()
        assertFalse(token.acknowledge(23.5, false))
        assertTrue(token.acknowledge(23.5, true))
    }

    @Test fun `user seeks during the gap keep the newest real seek id and are duration bounded`() {
        val token = transfer()
        token.capture(25.0, false, 1)
        assertTrue(token.seek(41, 5.0, relative = true, durationSeconds = 60.0))
        assertEquals(30.0, token.resume?.positionSeconds)
        assertTrue(token.seek(42, 8.0, relative = false, durationSeconds = 60.0))
        assertEquals(8.0, token.resume?.positionSeconds)
        assertEquals(42L, token.resume?.seekId)
        assertTrue(token.seek(43, 80.0, relative = false, durationSeconds = 60.0))
        assertEquals(60.0, token.resume?.positionSeconds)
        assertFalse(token.seek(44, Double.NaN, relative = false))
        assertEquals(43L, token.resume?.seekId)
    }

    @Test fun `a retired transfer can confirm resource release but can never attach or acknowledge`() {
        val token = transfer()
        token.capture(20.0, true, 3)
        token.retire()
        assertTrue(token.peerReleased())
        assertTrue(token.hasReleasedPeer)
        assertEquals(DesktopNativePresentationTransfer.Phase.RETIRED, token.phase)
        assertFalse(token.attach())
        assertFalse(token.attach(existingPeer = true))
        assertFalse(token.seek(1, 1.0, false))
        token.pause(false, 9)
        assertTrue(assertNotNull(token.resume).paused)
        assertFalse(token.acknowledge(20.0, true))
    }

    @Test fun `full source value revision and version all belong to the transfer`() {
        val token = transfer()
        assertTrue(token.matches(OwnedPlaybackSourceSnapshot(7, original.copy()), 11))
        assertFalse(token.matches(OwnedPlaybackSourceSnapshot(8, original), 11))
        assertFalse(token.matches(OwnedPlaybackSourceSnapshot(7, original), 12))
        assertFalse(token.matches(OwnedPlaybackSourceSnapshot(7, original.copy(videoUrl = "file:///C:/other.mp4")), 11))
        assertFalse(token.matches(OwnedPlaybackSourceSnapshot(7, original.copy(startPaused = true)), 11))
        token.retire()
        assertFalse(token.matches(OwnedPlaybackSourceSnapshot(7, original), 11))
    }

    @Test fun `temporary per file cursor keeps every transport option and never rewrites load mute`() {
        val source = original.copy(audioUrl = "file:///C:/private-audio.wav", cookieHeader = "fixture-only", streamHeaders = mapOf("X-Fixture" to "value"))
        val options = source.mpvFileOptions()
        val restored = DesktopNativePresentationResume(39.25, true, 6).fileOptions(options)
        assertEquals(options.filterKeys { it !in setOf("start", "pause") }, restored.filterKeys { it !in setOf("start", "pause") })
        assertEquals("39.25", restored["start"])
        assertEquals("yes", restored["pause"])
        assertEquals("2.0", options["start"])
        assertFalse(restored.containsKey("mute"))
        assertEquals(2.0, source.startPositionSeconds)
        assertFalse(source.startPaused)
    }

    @Test fun `unattached actual player refuses transfer without changing user controls or source`() {
        MpvPlayer().use { player ->
            player.loadVersioned(original)
            player.setVolume(0.0); player.setSpeed(1.25); player.setMuted(false); player.setPaused(true)
            val expected = assertNotNull(player.currentSourceSnapshot())
            assertNull(player.beginPresentationTransfer(expected))
            assertTrue(player.ownsSourceSnapshot(expected))
            assertEquals(0.0, player.state.value.volume)
            assertEquals(1.25, player.state.value.speed)
            assertFalse(player.state.value.muted)
            assertTrue(player.state.value.paused)
            assertEquals(2.0, assertNotNull(player.currentSourceSnapshot()).source.startPositionSeconds)
        }
    }

    @Test fun `stopped and replacement sources cannot borrow an old full transfer snapshot`() {
        MpvPlayer().use { player ->
            player.loadVersioned(original)
            val old = assertNotNull(player.currentSourceSnapshot())
            val token = DesktopNativePresentationTransfer(old, 1, 100, { true })
            player.loadVersioned(original.copy(title = "New source"))
            assertFalse(player.isPresentationTransferCurrent(token))
            assertNull(player.beginPresentationTransfer(old))
            player.stop()
            assertNull(player.currentSourceSnapshot())
            assertFalse(player.isPresentationTransferCurrent(token))
        }
    }

    @Test fun `dispose release fence leaves actual immutable source and latest muted preference intact`() {
        MpvPlayer().use { player ->
            player.loadVersionedWithMuted(original, true)
            player.setMuted(false); player.setVolume(0.0); player.setSpeed(1.5)
            val source = assertNotNull(player.currentSourceSnapshot())
            val released = player.releasePresentationForDisposal()
            assertTrue(released()) // no peer was attached: no native core is constructed
            assertTrue(player.ownsSourceSnapshot(source))
            assertFalse(player.resumeCurrentPresentationPeer())
            assertEquals(1.5, player.state.value.speed)
            assertEquals(0.0, player.state.value.volume)
            assertFalse(player.state.value.muted)
        }
    }

    @Test fun `source retired after floating peer creation drains it even before attachment`() {
        var workerExited = false
        val token = transfer { workerExited }
        token.capture(22.0, false, 3)
        workerExited = true
        token.peerReleased() // The old Main peer has already been removed.
        token.retire() // New Load or source admission retirement during addNotify.
        assertFalse(token.hasAttachedPeer)
        assertTrue(token.hasReleasedPeer)
        assertTrue(token.cancelledFloatingPeerNeedsDisposal(true, returning = false, restoreRequested = false))
        assertFalse(token.attach()) // Cannot start a worker under the rejected source.
        assertFalse(token.cancelledFloatingPeerNeedsDisposal(false, returning = false, restoreRequested = false))
    }

    @Test fun `cancelled attached source keeps floating peer unless a return was requested`() {
        val token = transfer()
        token.capture(22.0, false, 3); token.peerReleased(); token.attach()
        token.retire()
        assertFalse(token.cancelledFloatingPeerNeedsDisposal(true, returning = false, restoreRequested = false))
        assertTrue(token.cancelledFloatingPeerNeedsDisposal(true, returning = true, restoreRequested = false))
        assertTrue(token.cancelledFloatingPeerNeedsDisposal(true, returning = false, restoreRequested = true))
    }

    @Test fun `EOF or fatal exit before capture retires requested handoff without a cursor`() {
        val token = transfer()
        assertTrue(token.retireTerminatedWorker())
        assertTrue(token.isRetired)
        assertNull(token.resume)
        assertFalse(token.capture(60.0, true, 4))
        assertFalse(token.attach())
        assertFalse(token.retireTerminatedWorker())
    }

    @Test fun `intentional outgoing worker exit cannot retire an already captured cursor`() {
        val token = transfer()
        token.capture(22.0, true, 3)
        assertFalse(token.retireTerminatedWorker())
        assertEquals(DesktopNativePresentationTransfer.Phase.CAPTURED, token.phase)
        token.peerReleased(); token.attach()
        assertTrue(token.acknowledge(22.0, true))
        assertFalse(token.retireTerminatedWorker())
    }

    @Test fun `restoration worker EOF or failure before actual ACK retires attached handoff`() {
        val token = transfer()
        token.capture(22.0, false, 3); token.peerReleased(); token.attach()
        assertTrue(token.retireTerminatedWorker())
        assertTrue(token.isRetired)
        assertTrue(token.hasAttachedPeer)
        assertFalse(token.acknowledge(22.0, false))
        assertTrue(token.cancelledFloatingPeerNeedsDisposal(true, returning = true, restoreRequested = false))
    }
}
