package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.overlay.resolveOverlayPlaybackButtonPlayingState
import com.android.purebilibili.feature.video.usecase.applyPlaybackButtonUserAction
import com.android.purebilibili.feature.video.usecase.togglePlayerPlaybackFromUserAction
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Upstream cda4979a9: while the source is still buffering, the centre button and
 * the collapsed bar follow the user's play intent (playWhenReady), not frame output.
 * The fixture MPV session never reports a loaded file, so it stays buffering; no
 * native DLL, window or media IO is opened. */
class DesktopBufferingPlaybackButtonTest {
    private class PendingDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
    }

    private class Buffering(paused: Boolean) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + PendingDispatcher())
        val player = MpvPlayer()
        private val actor: PremiumRecoveryActor
        val control: DesktopOriginalMpvSectionControl

        init {
            player.load(PlaybackSource("https://fixture.invalid/no-media", startPaused = paused))
            actor = PremiumRecoveryActor(player)
            val version = assertNotNull(player.currentSourceSnapshot()).sourceVersion
            control = DesktopOriginalMpvSectionControl(player, { version }, { true }, { block -> block(); true },
                { false }, {}, {}, { _, _, _, _ -> }, scope, MutableStateFlow<Long?>(null), { block -> block(); true })
        }
        override fun close() { scope.cancel(); actor.close(); player.close() }
    }

    @Test fun bufferingWithPlayIntentShowsPauseAndPressPauses() = Buffering(paused = false).use { b ->
        val c = b.control
        assertEquals(DesktopOriginalMpvOverlayControl.STATE_BUFFERING, c.playbackState)
        assertFalse(c.isPlaying, "No frame is produced yet")
        assertTrue(resolveOverlayPlaybackButtonPlayingState(c.isPlaying, c.playWhenReady, c.playbackState),
            "The button reflects the intent to play, so it offers pause")

        // The rendered icon may still lag one event behind and show "play".
        applyPlaybackButtonUserAction(c, isShowingPauseIcon = false)
        assertFalse(c.playWhenReady, "Pressing during buffering pauses the pending playback")
        assertTrue(b.player.state.value.paused)
    }

    @Test fun bufferingWhilePausedPressResumes() = Buffering(paused = true).use { b ->
        val c = b.control
        assertEquals(DesktopOriginalMpvOverlayControl.STATE_BUFFERING, c.playbackState)
        assertFalse(resolveOverlayPlaybackButtonPlayingState(c.isPlaying, c.playWhenReady, c.playbackState))

        applyPlaybackButtonUserAction(c, isShowingPauseIcon = true)
        assertTrue(c.playWhenReady, "Pressing during buffering resumes a paused intent")
        assertFalse(b.player.state.value.paused)
    }

    @Test fun collapsedBarToggleFollowsPlayIntentWhileBuffering() {
        Buffering(paused = false).use { b ->
            togglePlayerPlaybackFromUserAction(b.control)
            assertFalse(b.control.playWhenReady)
        }
        Buffering(paused = true).use { b ->
            togglePlayerPlaybackFromUserAction(b.control)
            assertTrue(b.control.playWhenReady)
        }
    }
}
