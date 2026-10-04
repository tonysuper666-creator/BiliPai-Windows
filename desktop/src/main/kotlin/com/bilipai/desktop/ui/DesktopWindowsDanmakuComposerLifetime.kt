package com.bilipai.desktop.ui

import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot

/** Presentation-only stamp over the existing player snapshot and automatic pause
 * token. Neither this stamp nor visibility grants playback/source ownership. */
internal data class DesktopWindowsDanmakuComposerSource(
    val player: MpvPlayer,
    val snapshot: OwnedPlaybackSourceSnapshot,
)

internal fun desktopWindowsDanmakuComposerSourceMatches(
    first: DesktopWindowsDanmakuComposerSource,
    second: DesktopWindowsDanmakuComposerSource,
): Boolean = first.player === second.player && first.snapshot.sourceVersion == second.snapshot.sourceVersion &&
    first.snapshot.source == second.snapshot.source

internal class DesktopWindowsDanmakuComposerLifetime<S : Any, T : Any>(
    private val captureSource: () -> S?,
    private val pause: (S) -> T?,
    private val resume: (T) -> Boolean,
    private val sameSource: (S, S) -> Boolean,
) {
    private class Stamp<S, T>(val source: S, val pauseToken: T?)
    private var current: Stamp<S, T>? = null

    fun open(wasPlaying: Boolean) {
        val source = captureSource()
        current = source?.let { Stamp(it, if (wasPlaying) pause(it) else null) }
    }

    fun stamp(): Any? = current

    /** The original request may finish after its window closed and reopened.
     * Network feedback/local injection survive; only the matching composer UI changes. */
    fun completeSubmission(expected: Any?, action: () -> Unit): Boolean {
        if (current !== expected) return false
        action()
        return true
    }

    /** MPV's exact source + pause-intent serial makes a later explicit Pause win. */
    fun dismiss(): Boolean {
        val previous = current ?: return false
        current = null
        return captureSource()?.let { sameSource(it, previous.source) } == true && previous.pauseToken?.let(resume) == true
    }

    /** A late old window disposal cannot clear a subsequently opened composer. */
    fun retire(expected: Any): Boolean {
        if (current !== expected) return false
        current = null
        return true
    }
}
