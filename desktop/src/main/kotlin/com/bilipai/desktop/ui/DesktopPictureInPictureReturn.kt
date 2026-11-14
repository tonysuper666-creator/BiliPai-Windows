package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey

/**
 * Remembers the page a floating-window session was opened from. When the user
 * closes or returns from the floating window and that exact page is no longer the
 * visible destination, the returned surface would play with nothing on screen, so
 * the source is paused instead (reversible: play resumes it on the original page).
 * [page] reads the destination that can show the main player right now, or null
 * when the host window cannot show anything at all.
 */
internal class DesktopPictureInPictureReturn(
    private val page: () -> BiliPaiNavKey?,
    private val pause: () -> Unit,
) {
    private var opened = false
    private var origin: BiliPaiNavKey? = null
    private var paused = false

    /** The open request was accepted: pin the page it came from, before any navigation during the handoff. */
    fun openAccepted() { opened = true; origin = page(); paused = false }

    fun sessionEnded() { opened = false; origin = null; paused = false }

    /** A stale return after the session already ended or was disposed is a no-op. */
    fun userReturn() {
        if (!opened || paused) return
        val current = page()
        if (current != null && current == origin) return
        paused = true
        pause()
    }
}
