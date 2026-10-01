package com.android.purebilibili.feature.video.screen

import com.bilipai.desktop.ui.DesktopOriginalPlaybackStates as Player
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioModePlaybackPolicyTest {

    fun `share title prefers real video title over display title`() {
        assertEquals(
            "何同学 一镜到底",
            resolveAudioModeShareTitle(
                videoTitle = "何同学 一镜到底",
                displayTitle = "iPhone 18发布2（b站换源）",
            )
        )
    }

    fun `share title falls back to display title when video title is blank`() {
        assertEquals(
            "分P标题",
            resolveAudioModeShareTitle(
                videoTitle = "  ",
                displayTitle = " 分P标题 ",
            )
        )
    }

    fun `play button pauses when player is already playing`() {
        assertEquals(
            AudioModePlayPauseAction.PAUSE,
            resolveAudioModePlayPauseAction(
                isPlaying = true,
                playbackState = Player.STATE_READY,
                playWhenReady = true
            )
        )
    }

    fun `play button resumes paused ready playback`() {
        assertEquals(
            AudioModePlayPauseAction.RESUME,
            resolveAudioModePlayPauseAction(
                isPlaying = false,
                playbackState = Player.STATE_READY,
                playWhenReady = false
            )
        )
    }

    fun `play button restarts playback after media ended`() {
        assertEquals(
            AudioModePlayPauseAction.RESTART_FROM_BEGINNING,
            resolveAudioModePlayPauseAction(
                isPlaying = false,
                playbackState = Player.STATE_ENDED,
                playWhenReady = false
            )
        )
    }

    fun `play button prepares idle player before resuming`() {
        assertEquals(
            AudioModePlayPauseAction.PREPARE_AND_RESUME,
            resolveAudioModePlayPauseAction(
                isPlaying = false,
                playbackState = Player.STATE_IDLE,
                playWhenReady = false
            )
        )
    }

    fun `audio mode creates standalone player when sourced route has no player`() {
        assertTrue(
            shouldCreateAudioModeStandalonePlayer(
                hasPlayer = false,
                initialBvid = "BV1audio"
            )
        )
    }

    fun `audio mode reuses existing player when available`() {
        assertFalse(
            shouldCreateAudioModeStandalonePlayer(
                hasPlayer = true,
                initialBvid = "BV1audio"
            )
        )
    }

    fun `audio mode waits when no source video is available`() {
        assertFalse(
            shouldCreateAudioModeStandalonePlayer(
                hasPlayer = false,
                initialBvid = ""
            )
        )
    }

    fun `audio mode page switch forces playback to resume`() {
        assertTrue(resolveAudioModePageSwitchAutoPlay())
    }

    fun `audio mode collection switch forces playback to resume`() {
        assertTrue(resolveAudioModeCollectionSwitchAutoPlay())
    }
}
