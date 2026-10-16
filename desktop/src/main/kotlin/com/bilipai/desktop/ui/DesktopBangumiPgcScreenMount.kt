package com.bilipai.desktop.ui

import java.awt.EventQueue

/** One bit of Screen-effect history for the retained original PGC Entry.
 * Windows may unmount its foreground UI while that SAME VM keeps playing or
 * loading. Reattaching that UI must not replay the route's initial load and
 * overwrite the VM's current episode, pending Job, Error or live position.
 * User retry, Mini and episode commands still call the original VM directly.
 * This object owns no request, player, source, credentials or coroutine. */
internal class DesktopBangumiPgcScreenMount {
    private var completed = false
    private var entering = false

    /** Consume only a complete synchronous attach+launch. A failed synchronous
     * block propagates unchanged and may be tried on the next genuine mount.
     * Async playback completion never resets or controls this marker. */
    fun runInitialLoad(action: () -> Unit): Boolean {
        check(EventQueue.isDispatchThread())
        if (completed || entering) return false
        entering = true
        return try {
            action()
            completed = true
            true
        } finally {
            entering = false
        }
    }
}
