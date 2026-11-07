package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey

/**
 * Remembers the page a floating-window session was opened from. When the user
 * closes or returns from the floating window and that exact page is no longer the
 * visible destination, the returned surface would play with nothing on screen, so
 * the source is paused instead (reversible: play resumes it on the original page).
 * [page] is the destination that can show the main player, or null when the host
 * window cannot show anything at all.
 */
internal class DesktopPictureInPictureReturn {
    private var opened = false
    private var origin: BiliPaiNavKey? = null
    private var paused = false

    fun observe(active: Boolean, page: BiliPaiNavKey?) {
        if (!active) { opened = false; origin = null; paused = false }
        else if (!opened) { opened = true; origin = page; paused = false }
    }

    /** A stale return after the session already ended or was disposed is a no-op. */
    fun userReturn(page: BiliPaiNavKey?, pause: () -> Unit) {
        if (!opened || paused) return
        if (page != null && page == origin) return
        paused = true
        pause()
    }
}
