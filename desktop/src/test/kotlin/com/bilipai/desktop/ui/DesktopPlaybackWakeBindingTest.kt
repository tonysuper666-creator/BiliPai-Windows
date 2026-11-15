package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.ui.DesktopWindowsVideoWakeLease.Mode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlaybackWakeBindingTest {
    private class Power : DesktopWindowsVideoWakeLease.Request {
        var displayHeld = false
        var systemHeld = false
        var closed = false
        var rejectDisplay = false
        var rejectClear = false
        val systemChanges = mutableListOf<Boolean>()
        override fun setDisplay(required: Boolean) {
            check(!closed)
            check(!(required && rejectDisplay))
            check(!(!required && rejectClear))
            displayHeld = required
        }
        override fun setSystem(required: Boolean) {
            check(!closed)
            check(!(!required && rejectClear))
            systemHeld = required
            systemChanges += required
        }
        override fun close() { closed = true; displayHeld = false; systemHeld = false }
    }
    private class Host {
        val handles = mutableListOf<Power>()
        val failures = mutableListOf<Throwable>()
        val pool = DesktopWindowsVideoWakeLease.Pool { Power().also(handles::add) }
        var source = true
        fun binding(audio: Boolean = false) = DesktopPlaybackWakeBinding(
            { source }, audio, failures::add, { pool.acquire(it, failures::add) })
        val display get() = handles.any { it.displayHeld }
        val system get() = handles.any { it.systemHeld }
        fun assertHeld(video: Boolean) { assertEquals(video, display); assertTrue(system) }
        fun assertReleased() { assertFalse(display); assertFalse(system) }
    }
    private val playing = PlayerState(ready = true, videoCodec = "h264", audioCodec = "aac")

    @Test fun livePlaybackAndPauseFollowNativeStateWhileItsPageIsCovered() = runTest {
        val host = Host()
        val states = MutableStateFlow(PlayerState())
        val job = launch { host.binding().observe(states) }
        runCurrent(); host.assertReleased()
        states.value = playing.copy(durationSeconds = 0.0) // Live streams need no duration.
        runCurrent(); host.assertHeld(video = true)
        states.value = states.value.copy(paused = true)
        runCurrent(); host.assertReleased()
        states.value = playing.copy(pausedForCache = true) // Buffering preserves play intent.
        runCurrent(); host.assertHeld(video = true)
        job.cancelAndJoin(); host.assertReleased()
    }

    @Test fun offlinePlaybackReleasesOnPauseEndErrorAndStoppedSource() {
        val host = Host()
        host.binding().use { binding ->
            binding.update(playing.copy(durationSeconds = 120.0)); host.assertHeld(video = true)
            binding.update(playing.copy(paused = true)); host.assertReleased()
            binding.update(playing); host.assertHeld(video = true)
            binding.update(playing.copy(ended = true)); host.assertReleased()
            binding.update(playing); host.assertHeld(video = true)
            binding.update(playing.copy(error = "file unavailable")); host.assertReleased()
            binding.update(playing); host.assertHeld(video = true)
            host.source = false
            binding.update(playing); host.assertReleased()
        }
    }

    @Test fun pipAndMainPlayerHandoffSharesOneHandleWithoutPrematureRelease() {
        val host = Host()
        val main = host.binding()
        val pip = host.binding()
        main.update(playing); pip.update(playing)
        assertEquals(1, host.handles.size)
        main.close(); host.assertHeld(video = true)
        val returned = host.binding()
        returned.update(playing)
        pip.close(); host.assertHeld(video = true)
        assertEquals(listOf(true), host.handles.single().systemChanges)
        returned.update(playing.copy(paused = true)); host.assertReleased()
        returned.close(); pip.close(); main.close()
    }

    @Test fun audioOnlyPlaybackKeepsSystemButAllowsScreenOffAndReleasesOnPause() = runTest {
        val host = Host()
        val states = MutableStateFlow(playing.copy(audioOnly = true))
        val job = launch { host.binding().observe(states) }
        runCurrent(); host.assertHeld(video = false)
        states.value = states.value.copy(paused = true)
        runCurrent(); host.assertReleased()
        states.value = playing.copy(videoCodec = null)
        runCurrent(); host.assertHeld(video = false)
        job.cancelAndJoin(); host.assertReleased()
    }

    @Test fun backgroundListenSessionRequestsOnlySystemEvenWithAttachedVideoTrack() {
        val host = Host()
        host.binding(audio = true).use {
            it.update(playing); host.assertHeld(video = false)
            it.update(playing.copy(paused = true)); host.assertReleased()
        }
    }

    @Test fun audioAndVideoModeSwitchNeverDropsSystemRequest() {
        val host = Host()
        host.binding().use {
            it.update(playing); host.assertHeld(video = true)
            it.update(playing.copy(audioOnly = true)); host.assertHeld(video = false)
            it.update(playing); host.assertHeld(video = true)
            assertEquals(1, host.handles.size)
            assertEquals(listOf(true), host.handles.single().systemChanges)
        }
        host.assertReleased()
    }

    @Test fun sameSourceNativeSurfaceReattachmentDoesNotReleasePlaybackRequest() {
        val host = Host()
        host.binding().use {
            it.update(playing)
            it.update(playing.copy(ready = false)) // Existing detach keeps the accepted source.
            host.assertHeld(video = true)
            it.update(playing)
            assertEquals(listOf(true), host.handles.single().systemChanges)
        }
    }

    @Test fun independentAudioBorrowerSurvivesVideoClosing() {
        val host = Host()
        val video = host.binding(); val audio = host.binding(audio = true)
        video.update(playing); audio.update(playing)
        video.close(); host.assertHeld(video = false)
        audio.close(); host.assertReleased()
    }

    @Test fun idleInitializationAndPausedLoadDoNotHoldRequests() {
        val host = Host()
        host.binding().use {
            it.update(PlayerState(ready = true)); host.assertReleased()
            it.update(playing.copy(loading = true, paused = true)); host.assertReleased()
            it.update(playing.copy(loading = true)); host.assertHeld(video = true)
        }
    }

    @Test fun closedHostRejectsLateNativeCallbacks() {
        val host = Host()
        val binding = host.binding()
        binding.update(playing); binding.close(); binding.close()
        binding.update(playing.copy(positionSeconds = 3.0))
        host.assertReleased()
    }

    @Test fun failedDisplayAcquisitionRollsBackItsSystemRequestAndClosesHandle() {
        val request = Power().apply { rejectDisplay = true }
        val errors = mutableListOf<Throwable>()
        val pool = DesktopWindowsVideoWakeLease.Pool { request }
        assertThrows(IllegalStateException::class.java) { pool.acquire(Mode.VIDEO, errors::add) }
        assertTrue(request.closed); assertFalse(request.systemHeld); assertFalse(request.displayHeld)
    }

    @Test fun failedVideoAcquisitionDoesNotRemoveExistingAudioRequest() {
        val request = Power().apply { rejectDisplay = true }
        val errors = mutableListOf<Throwable>()
        val pool = DesktopWindowsVideoWakeLease.Pool { request }
        val audio = pool.acquire(Mode.AUDIO, errors::add)
        assertThrows(IllegalStateException::class.java) { pool.acquire(Mode.VIDEO, errors::add) }
        assertTrue(request.systemHeld); assertFalse(request.closed)
        audio.close(); assertTrue(request.closed)
    }

    @Test fun failedClearStillClosesLastHandleSoNoRequestsRemain() {
        val request = Power()
        val errors = mutableListOf<Throwable>()
        val pool = DesktopWindowsVideoWakeLease.Pool { request }
        val lease = pool.acquire(Mode.VIDEO, errors::add)
        request.rejectClear = true
        lease.close(); lease.close()
        assertTrue(request.closed); assertFalse(request.systemHeld); assertFalse(request.displayHeld)
        assertFalse(errors.isEmpty())
    }
}
