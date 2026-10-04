package com.bilipai.desktop.player

import kotlin.test.*

class DesktopNativeMuteIntentTest {
    @Test fun readCapturedBeforeUserUnmuteCannotOverwriteIt() {
        val policy = DesktopNativeMuteIntentPolicy()
        val oldRead = policy.capture() // Actual native still reports mute=yes.
        val unmute = policy.request(false)
        assertFalse(policy.canPublish(oldRead))
        assertTrue(policy.applied(unmute))
        assertFalse(policy.canPublish(oldRead)) // ACK does not make a stale read current.
        assertTrue(policy.canPublish(policy.capture()))
    }

    @Test fun readCapturedAfterEnqueueBeforeNativeExecutionCannotOverwriteUserIntent() {
        val policy = DesktopNativeMuteIntentPolicy()
        val unmute = policy.request(false)
        val queuedRead = policy.capture() // New serial, but native command is still pending.
        assertFalse(policy.canPublish(queuedRead))
        assertTrue(policy.applied(unmute))
        assertFalse(policy.canPublish(queuedRead))
        assertTrue(policy.canPublish(policy.capture()))
    }

    @Test fun olderQueuedMuteAndItsAcknowledgementCannotClearNewerUserUnmute() {
        val policy = DesktopNativeMuteIntentPolicy()
        val oldMute = policy.request(true)
        val unmute = policy.request(false)
        assertFalse(policy.isCurrent(oldMute))
        assertFalse(policy.applied(oldMute))
        assertTrue(policy.hasPending)
        assertTrue(policy.applied(unmute))
        assertFalse(policy.hasPending)
    }

    @Test fun queuedSourceOwnedRestorationCannotOverrideSubsequentUserIntent() {
        val policy = DesktopNativeMuteIntentPolicy()
        val restoration = policy.request(true, sourceOwned = true)
        val user = policy.request(false)
        assertFalse(policy.isCurrent(restoration))
        assertFalse(policy.reject(restoration)) // A late rejected source cannot clear the user command.
        assertTrue(policy.isCurrent(user))
        assertTrue(policy.applied(user))
    }

    @Test fun acceptedSourceOwnedMuteWaitsForNativeExecutionAndThenAllowsFreshReadback() {
        val policy = DesktopNativeMuteIntentPolicy()
        val restoration = policy.request(true, sourceOwned = true)
        val oldRead = policy.capture()
        assertTrue(restoration.sourceOwned)
        assertTrue(policy.hasPending)
        assertTrue(policy.applied(restoration))
        assertFalse(policy.canPublish(oldRead))
        assertTrue(policy.canPublish(policy.capture()))
    }

    @Test fun failedNativeCommandOrRejectedSourceDoesNotLeavePendingForever() {
        for (owned in listOf(false, true)) {
            val policy = DesktopNativeMuteIntentPolicy()
            val command = policy.request(false, sourceOwned = owned)
            val read = policy.capture()
            assertTrue(policy.reject(command))
            assertFalse(policy.hasPending)
            assertFalse(policy.canPublish(read))
            assertTrue(policy.canPublish(policy.capture())) // Next actual read reconciles the failed intent.
        }
    }

    @Test fun lateFailureCannotRetireNewerSourceOrUserCommand() {
        val policy = DesktopNativeMuteIntentPolicy()
        val old = policy.request(true, sourceOwned = true)
        policy.retire()
        val fresh = policy.request(false)
        assertFalse(policy.reject(old))
        assertTrue(policy.hasPending)
        assertTrue(policy.isCurrent(fresh))
    }

    @Test fun sessionDetachLoadRecoveryAndStopRetireOldCommandsAndReadStamps() {
        val policy = DesktopNativeMuteIntentPolicy()
        val before = policy.capture()
        val old = policy.request(false)
        policy.retire()
        assertFalse(policy.hasPending)
        assertFalse(policy.isCurrent(old))
        assertFalse(policy.applied(old))
        assertFalse(policy.canPublish(before))
        assertTrue(policy.canPublish(policy.capture()))
    }

    @Test fun headlessUserMuteIsRetainedAcrossNewLoadAndSameVersionRecovery() {
        MpvPlayer().use { player ->
            player.setMuted(false)
            val version = player.loadVersioned(PlaybackSource("file:///C:/mute-intent-fixture.avi"))
            assertFalse(player.state.value.muted)
            assertTrue(player.recoverSource(version, positionSeconds = 12.0, paused = true))
            assertFalse(player.state.value.muted)
            assertEquals(version, player.currentSourceVersion)
            player.stop()
            assertFalse(player.state.value.muted)
        }
    }
}
