package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlayerTrack
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOriginalCdnMediaReadinessTest {
    @Test fun codecMetadataCannotRetireVideoStartupAndBufferingMustRecover() {
        val metadata = PlayerState(ready = true, videoCodec = "h264", audioCodec = "aac",
            tracks = listOf(PlayerTrack(1, "audio", selected = true)))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(metadata))
        val rendered = metadata.copy(firstVideoFrameReady = true)
        assertTrue(desktopOriginalCdnRecoveryMediaReady(rendered))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(rendered.copy(pausedForCache = true)))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(rendered.copy(loading = true)))
        assertTrue(desktopOriginalCdnRecoveryMediaReady(rendered.copy(videoCodec = null, audioCodec = null, tracks = emptyList())))
    }

    @Test fun nativeAudioOnlyUsesSelectedAudioAndIgnoresResidualVideoMetadata() {
        val audio = PlayerState(ready = true, audioOnly = true, audioCodec = "flac",
            videoCodec = "h264", tracks = listOf(PlayerTrack(2, "audio", selected = true)))
        assertFalse(audio.firstVideoFrameReady)
        assertTrue(desktopOriginalCdnRecoveryMediaReady(audio))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(audio.copy(audioCodec = null, firstVideoFrameReady = true)))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(audio.copy(tracks = listOf(PlayerTrack(2, "audio")))))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(audio.copy(audioOnly = false)))
        assertTrue(desktopOriginalCdnRecoveryMediaReady(audio.copy(audioOnly = false, firstVideoFrameReady = true)))
    }

    @Test fun explicitSplitAudioIntentRequiresItsTrackWithoutRequiringAudioForPureVideo() {
        val pureVideo = PlayerState(ready = true, firstVideoFrameReady = true)
        assertTrue(desktopOriginalCdnRecoveryMediaReady(pureVideo))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(pureVideo, expectedAudioTrack = true))
        val selectedAudio = pureVideo.copy(tracks = listOf(PlayerTrack(3, "audio", selected = true)))
        assertTrue(desktopOriginalCdnRecoveryMediaReady(selectedAudio, expectedAudioTrack = true))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(selectedAudio.copy(firstVideoFrameReady = false), expectedAudioTrack = true))
    }

    @Test fun pureVideoAndPausedRenderedOutputRemainValidButEmptyAndTerminalFactsDoNot() {
        val video = PlayerState(ready = true, firstVideoFrameReady = true, paused = true, nativePaused = true)
        assertTrue(video.tracks.isEmpty())
        assertTrue(desktopOriginalCdnRecoveryMediaReady(video))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(PlayerState()))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(video.copy(ready = false)))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(video.copy(ended = true)))
        assertFalse(desktopOriginalCdnRecoveryMediaReady(video.copy(error = "fixture failure")))
    }
}
