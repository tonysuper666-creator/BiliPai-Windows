package com.bilipai.desktop.ui

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps group cleanups alive after the window that started them is gone.
 *
 * A refused withdrawal is not a lost one: the session stays here and is retried while the process
 * lives, and every notification round retries it first. The set is the recoverable state — if the
 * process exits before it empties, the notification center still holds the toasts and the next launch
 * withdraws them through its own account session.
 */
internal object DesktopNotificationCleanup {
    private val outstanding = CopyOnWriteArraySet<DesktopNotificationSession>()
    private val driving = AtomicBoolean()

    fun track(session: DesktopNotificationSession) {
        if (!session.isCleanupPending()) return
        outstanding += session
        drive()
    }

    /** One pass; returns how many sessions still need work. */
    fun retryOutstanding(): Int {
        for (session in outstanding) {
            if (session.retryCleanup() || !session.isCleanupPending()) outstanding -= session
        }
        return outstanding.size
    }

    internal fun outstandingCount(): Int = outstanding.size

    private fun drive() {
        if (!driving.compareAndSet(false, true)) return
        Thread({
            try {
                while (outstanding.isNotEmpty()) {
                    Thread.sleep(2_000)
                    retryOutstanding()
                }
            } finally { driving.set(false) }
        }, "BiliPai-notification-cleanup").apply { isDaemon = true }.start()
    }
}
