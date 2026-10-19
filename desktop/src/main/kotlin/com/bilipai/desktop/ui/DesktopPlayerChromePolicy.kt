package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState

/*
 * Pure chrome policy of the desktop player (spec 4.7, 7). No Compose, AWT or Windows dependency. Idle is 2.5 s of real
 * pointer movement in every mode, the controls never hide while a hold is true, and the cursor hides with them. Mirrors
 * desktop-ux-contract.json ("chrome"); test_ux_player_policy.py and DesktopPlayerChromePolicyTest check the agreement.
 */

// UX-PLAYER-CHROME-BEGIN
internal const val DESKTOP_PLAYER_CHROME_IDLE_MS = 2500L
internal const val DESKTOP_PLAYER_CHROME_LEAVE_IDLE_MS = 600L
internal const val DESKTOP_PLAYER_CHROME_FADE_OUT_MS = 200
internal const val DESKTOP_PLAYER_CHROME_FADE_IN_MS = 120
internal const val DESKTOP_PLAYER_CHROME_MOVE_THRESHOLD_PX = 2
internal const val DESKTOP_PLAYER_BUFFERING_SPINNER_AFTER_MS = 400L
internal const val DESKTOP_PLAYER_BUFFERING_TEXT_AFTER_MS = 5000L
// UX-PLAYER-CHROME-END

/** What the leaf passes as DesktopWindowsFullscreenChromeState(idleNanos = ...). */
internal const val DESKTOP_PLAYER_CHROME_IDLE_NANOS = DESKTOP_PLAYER_CHROME_IDLE_MS * 1_000_000L

/** The reasons the controls stay visible, in the order of the contract list "hold_while". */
internal enum class DesktopPlayerChromeHold {
    PAUSED, BUFFERING, ENDED, ERRORED, AUDIO_ONLY, FLYOUT_OPEN, SCRUBBING, DRAGGING_VOLUME, DOCK_HOVERED, TOP_BAR_HOVERED,
}

/** The pointer and popup facts of the overlay itself. */
internal data class DesktopPlayerChromeInteraction(
    val flyoutOpen: Boolean = false,
    val scrubbing: Boolean = false,
    val draggingVolume: Boolean = false,
    val dockHovered: Boolean = false,
    val topBarHovered: Boolean = false,
)

/**
 * The playback holds. None holds exactly when the existing fullscreen rule counts the video as playing: ready, first frame
 * shown, an unpaused native readback, and no audio-only, loading, pause, end, cache stall or error. A native pause readback
 * still unknown counts as BUFFERING, as the existing rule treats it as not playing.
 */
internal fun desktopPlayerPlaybackHolds(state: PlayerState, playbackProblem: Boolean): Set<DesktopPlayerChromeHold> {
    val holds = linkedSetOf<DesktopPlayerChromeHold>()
    if (state.paused || state.nativePaused == true) holds.add(DesktopPlayerChromeHold.PAUSED)
    if (state.loading || state.pausedForCache || !state.ready || !state.firstVideoFrameReady || state.nativePaused == null) {
        holds.add(DesktopPlayerChromeHold.BUFFERING)
    }
    if (state.ended) holds.add(DesktopPlayerChromeHold.ENDED)
    if (state.error != null || playbackProblem) holds.add(DesktopPlayerChromeHold.ERRORED)
    if (state.audioOnly) holds.add(DesktopPlayerChromeHold.AUDIO_ONLY)
    return holds
}

/** All holds, playback and interaction, in the order of the enum. */
internal fun desktopPlayerChromeHolds(
    state: PlayerState,
    playbackProblem: Boolean,
    interaction: DesktopPlayerChromeInteraction,
): Set<DesktopPlayerChromeHold> {
    val playback = desktopPlayerPlaybackHolds(state, playbackProblem)
    val flags = mapOf(
        DesktopPlayerChromeHold.FLYOUT_OPEN to interaction.flyoutOpen,
        DesktopPlayerChromeHold.SCRUBBING to interaction.scrubbing,
        DesktopPlayerChromeHold.DRAGGING_VOLUME to interaction.draggingVolume,
        DesktopPlayerChromeHold.DOCK_HOVERED to interaction.dockHovered,
        DesktopPlayerChromeHold.TOP_BAR_HOVERED to interaction.topBarHovered,
    )
    return DesktopPlayerChromeHold.entries.filterTo(linkedSetOf<DesktopPlayerChromeHold>()) { it in playback || flags[it] == true }
}

/**
 * The sibling of desktopWindowsFullscreenChromeCanAutoHide without the fullscreen requirement: the controls may hide in
 * every mode while the page is active, the window focused, nothing holds the chrome and the video is playing.
 */
internal fun desktopPlayerChromeCanAutoHide(
    active: Boolean,
    windowFocused: Boolean,
    interactionHeld: Boolean,
    state: PlayerState,
    playbackProblem: Boolean,
): Boolean = active && windowFocused && !interactionHeld && desktopPlayerPlaybackHolds(state, playbackProblem).isEmpty()

/** The remaining idle time; once the pointer has left the carrier it is cut to the short leave time. */
internal fun desktopPlayerChromeRemainingIdleMs(remainingMs: Long, pointerLeftCarrier: Boolean): Long =
    if (pointerLeftCarrier) minOf(remainingMs, DESKTOP_PLAYER_CHROME_LEAVE_IDLE_MS) else remainingMs

/** A physical screen movement is activity from the move threshold on (Euclidean, in pixels). */
internal fun desktopPlayerPointerMoveCounts(dxPx: Int, dyPx: Int): Boolean {
    val threshold = DESKTOP_PLAYER_CHROME_MOVE_THRESHOLD_PX.toLong()
    return dxPx.toLong() * dxPx + dyPx.toLong() * dyPx >= threshold * threshold
}

/** The cursor hides together with the chrome, and only while the pointer is over the video. */
internal fun desktopPlayerCursorHidden(chromeVisible: Boolean, pointerOverVideo: Boolean): Boolean = !chromeVisible && pointerOverVideo

internal enum class DesktopPlayerBufferingPresentation { NONE, SPINNER, SPINNER_WITH_TEXT }

/** The spinner shows after 400 ms of buffering, the text as well after 5 s. */
internal fun desktopPlayerBufferingPresentation(bufferingMs: Long): DesktopPlayerBufferingPresentation = when {
    bufferingMs >= DESKTOP_PLAYER_BUFFERING_TEXT_AFTER_MS -> DesktopPlayerBufferingPresentation.SPINNER_WITH_TEXT
    bufferingMs >= DESKTOP_PLAYER_BUFFERING_SPINNER_AFTER_MS -> DesktopPlayerBufferingPresentation.SPINNER
    else -> DesktopPlayerBufferingPresentation.NONE
}
