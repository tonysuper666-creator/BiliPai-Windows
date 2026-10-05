package com.bilipai.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.purebilibili.feature.video.ui.overlay.FullscreenGestureMode
import com.android.purebilibili.feature.video.ui.overlay.shouldAutoHideFullscreenControls
import com.bilipai.desktop.player.PlayerState

/** Compose remember keys compare by value; the navigation entry is an identity. */
internal class DesktopWindowsFullscreenChromeEntryKey(private val entry: Any) {
    override fun equals(other: Any?): Boolean = other is DesktopWindowsFullscreenChromeEntryKey && other.entry === entry
    override fun hashCode(): Int = System.identityHashCode(entry)
}

/** Mount-local UI timing, never a playback/source/window authority. */
internal class DesktopWindowsFullscreenChromeState(private val clockNanos: () -> Long = System::nanoTime) {
    var visible by mutableStateOf(true)
        private set
    var activityRevision by mutableLongStateOf(0L)
        private set
    private var lastActivityNanos = clockNanos()

    fun reveal() {
        visible = true
        lastActivityNanos = clockNanos()
        activityRevision++
    }

    fun remainingIdleMillis(): Long {
        val elapsed = (clockNanos() - lastActivityNanos).coerceAtLeast(0L)
        val remaining = (IDLE_NANOS - elapsed).coerceAtLeast(0L)
        return (remaining + 999_999L) / 1_000_000L
    }

    /** Caller supplies the final existing Root/route/full-source admission. */
    fun hideIfIdle(expectedActivityRevision: Long, stillAllowed: Boolean): Boolean {
        if (!stillAllowed || !visible || activityRevision != expectedActivityRevision || remainingIdleMillis() > 0L) return false
        visible = false
        return true
    }

    companion object { private const val IDLE_NANOS = 4_000_000_000L }
}

/** Original fullscreen playback rule plus Windows focus/interaction holds. */
internal fun desktopWindowsFullscreenChromeCanAutoHide(
    fullscreen: Boolean, active: Boolean, windowFocused: Boolean, interactionHeld: Boolean,
    state: PlayerState, playbackProblem: Boolean,
): Boolean = fullscreen && active && windowFocused && !interactionHeld && !playbackProblem &&
    shouldAutoHideFullscreenControls(showControls = true, gestureMode = FullscreenGestureMode.None,
        isPlaying = state.ready && state.firstVideoFrameReady && state.nativePaused == false && !state.audioOnly &&
            !state.loading && !state.paused && !state.ended &&
            !state.pausedForCache && state.error == null)
