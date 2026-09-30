package com.bilipai.desktop.audio

import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import kotlin.test.*

class DesktopNativeMusicRouteTest {
    @Test fun reopeningOwnedPausedSongOrPendingPreparationDoesNotRestartPlayback() {
        val source = MusicPlaybackSource.AudioSong(81)
        val state = ListenAudioState(queue = listOf(musicSourcePlaylistItem(source)), currentIndex = 0, active = true)
        assertFalse(shouldStartNativeMusic(source, state, true))
        assertFalse(shouldStartNativeMusic(source, state.copy(active = false, loading = true), false))
        assertTrue(shouldStartNativeMusic(source, state.copy(active = false), false))
        assertTrue(shouldStartNativeMusic(MusicPlaybackSource.AudioSong(82), state, true))
    }

    @Test fun foreignOwnerAndAnotherPartRequireAnewNativeMusicAcquisition() {
        val source = MusicPlaybackSource.VideoAudio("BVfixture", 220, "分 P")
        val state = ListenAudioState(queue = listOf(musicSourcePlaylistItem(source)), currentIndex = 0, active = true)
        assertTrue(shouldStartNativeMusic(source, state, false))
        assertFalse(shouldStartNativeMusic(source, state, true))
        assertTrue(shouldStartNativeMusic(MusicPlaybackSource.VideoAudio("BVfixture", 221, "下一 P"), state, true))
    }
}
