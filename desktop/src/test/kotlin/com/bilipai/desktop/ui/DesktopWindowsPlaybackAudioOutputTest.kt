package com.bilipai.desktop.ui

import com.bilipai.desktop.player.*
import kotlin.test.*

/** Presentation consumes actual native generations. No preference value can
 * promote a pending or stale readback into a confirmed exclusive output. */
class DesktopWindowsPlaybackAudioOutputTest {
    private val source = PlaybackSource("file:///C:/synthetic-silent-fixture.mp4")
    private val identity = PlayerNativeTrackIdentity(7L, 11L, 2L, 5L, source)
    private val state = PlayerState(ready = true, nativeTrackIdentity = identity)
    private val pcm = DesktopWindowsAudioPcm("s32", 48_000, "stereo", 2)
    private val status = DesktopWindowsAudioOutputStatus(
        phase = DesktopWindowsAudioOutputPhase.ACTIVE_EXCLUSIVE,
        requested = DesktopWindowsAudioOutputPreferences(true, "wasapi/{synthetic}"),
        requestedRevision = 4L, appliedRevision = 4L, sourceVersion = 7L, playbackRevision = 11L,
        activeDriver = "wasapi", configuredDeviceId = "wasapi/{synthetic}", configuredExclusive = true,
        sourcePcm = pcm.copy(format = "floatp"), outputPcm = pcm)

    @Test fun matchingReadbackIsDisplayedWithoutRewritingItsNativePhaseOrPcm() {
        assertSame(status, desktopWindowsPlaybackAudioOutputStatus(state, status))
        val shared = status.copy(phase = DesktopWindowsAudioOutputPhase.ACTIVE_SHARED,
            requested = DesktopWindowsAudioOutputPreferences(), configuredExclusive = false)
        assertSame(shared, desktopWindowsPlaybackAudioOutputStatus(state, shared))
    }

    @Test fun priorSourceAndRecoveryReadbacksAreRejectedEvenWithConfirmedExclusiveAndPcm() {
        assertNull(desktopWindowsPlaybackAudioOutputStatus(state, status.copy(sourceVersion = 6L)))
        assertNull(desktopWindowsPlaybackAudioOutputStatus(state, status.copy(playbackRevision = 10L)))
        assertNull(desktopWindowsPlaybackAudioOutputStatus(state, status.copy(sourceVersion = null)))
        assertNull(desktopWindowsPlaybackAudioOutputStatus(state, status.copy(playbackRevision = null)))
    }

    @Test fun stoppedLoadingFailedAndUnacknowledgedPlayersCannotDisplayOldOutput() {
        val failure = PlayerFailure(PlayerFailureKind.AUDIO_OUTPUT, -14, "synthetic output failure", sourceVersion = 7L, attemptId = 2L)
        for (unavailable in listOf(state.copy(ready = false), state.copy(loading = true), state.copy(ended = true),
            state.copy(error = "synthetic"), state.copy(failure = failure), state.copy(nativeTrackIdentity = null))) {
            assertNull(desktopWindowsPlaybackAudioOutputStatus(unavailable, status))
        }
    }

    @Test fun pausingAndPositionUpdatesKeepTheSameCurrentOutputReadback() {
        assertSame(status, desktopWindowsPlaybackAudioOutputStatus(state.copy(paused = true, nativePaused = true), status))
        assertSame(status, desktopWindowsPlaybackAudioOutputStatus(state.copy(positionSeconds = 19.5), status))
    }

    @Test fun savedExclusiveIntentNeverPromotesPendingOpeningOrFailureToActive() {
        for (phase in listOf(DesktopWindowsAudioOutputPhase.PENDING_NEXT_PLAY, DesktopWindowsAudioOutputPhase.OPENING,
            DesktopWindowsAudioOutputPhase.ERROR, DesktopWindowsAudioOutputPhase.UNAVAILABLE)) {
            val readback = status.copy(phase = phase, requestedRevision = 5L)
            assertSame(readback, desktopWindowsPlaybackAudioOutputStatus(state, readback))
            assertEquals(phase, desktopWindowsPlaybackAudioOutputStatus(state, readback)?.phase)
        }
    }

    @Test fun deviceNamesComeOnlyFromTheMatchingEnumeratedConfigurationAndDefaultsRemainExplicit() {
        val device = DesktopWindowsAudioDevice("wasapi/{synthetic}", "Synthetic optical output")
        assertEquals("Synthetic optical output", desktopWindowsPlaybackAudioDeviceLabel(status.configuredDeviceId, listOf(device)))
        assertEquals("指定的 WASAPI 设备", desktopWindowsPlaybackAudioDeviceLabel(status.configuredDeviceId, emptyList()))
        assertEquals("指定的 WASAPI 设备", desktopWindowsPlaybackAudioDeviceLabel(status.configuredDeviceId, listOf(device, device.copy(description = "ambiguous"))))
        assertEquals("Windows 默认 WASAPI", desktopWindowsPlaybackAudioDeviceLabel("wasapi", listOf(device)))
        assertEquals("跟随 Windows 默认输出", desktopWindowsPlaybackAudioDeviceLabel("auto", listOf(device)))
        assertEquals("尚未读回", desktopWindowsPlaybackAudioDeviceLabel(null, listOf(device)))
    }
}
