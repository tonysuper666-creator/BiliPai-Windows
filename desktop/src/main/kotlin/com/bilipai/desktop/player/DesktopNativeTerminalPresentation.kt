package com.bilipai.desktop.player

/** A terminal source may keep its immutable publication while its Canvas moves.
 * Recreating that presentation's core is not an explicit replay/recovery request. */
internal class DesktopNativeTerminalPresentation private constructor(
    private val source: OwnedPlaybackSourceSnapshot,
    private val revision: Long,
    private val ended: Boolean,
    private val error: String?,
    private val failure: PlayerFailure?,
) {
    fun matches(expected: OwnedPlaybackSourceSnapshot, playbackRevision: Long): Boolean =
        source.sourceVersion == expected.sourceVersion && source.source == expected.source && revision == playbackRevision

    fun coreReady(previous: PlayerState, nativeVersion: String?): PlayerState = previous.copy(
        ready = true, loading = false, ended = ended, error = error, failure = failure,
        paused = if (ended) true else previous.paused, firstVideoFrameReady = false,
        nativePaused = null, nativeVersion = nativeVersion,
    )

    companion object {
        fun capture(source: OwnedPlaybackSourceSnapshot, revision: Long, state: PlayerState): DesktopNativeTerminalPresentation? =
            if (state.ended || state.error != null || state.failure != null)
                DesktopNativeTerminalPresentation(source, revision, state.ended, state.error, state.failure)
            else null
    }
}
