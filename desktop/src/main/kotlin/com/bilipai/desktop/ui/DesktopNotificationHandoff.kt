package com.bilipai.desktop.ui

import java.util.WeakHashMap

/**
 * The account handoff has to outlive the consumer that owns a presentation.
 *
 * Loading another account tears the whole notification consumer down (the retained root is briefly
 * absent), so anything remembered inside that consumer is lost exactly when it is needed. This
 * registry is keyed weakly by the window, which outlives the reload, and holds the session strongly:
 * the previous account stays reachable until the handoff happens, and the entry disappears with the
 * window. A session never references its window, so this direction creates no cycle.
 */
internal object DesktopNotificationHandoff {
    private val byWindow = WeakHashMap<Any, DesktopNotificationSession>()

    @Synchronized
    fun attach(window: Any, session: DesktopNotificationSession) {
        val previous = byWindow.put(window, session)
        if (previous == null || previous === session || previous.accountId() == session.accountId()) return
        // The old account is gone: retire it off the UI thread, which is where attach is called.
        Thread({
            previous.retire()
            DesktopNotificationCleanup.track(previous)
        }, "BiliPai-notification-switch").apply { isDaemon = true }.start()
    }

    @Synchronized
    internal fun current(window: Any): DesktopNotificationSession? = byWindow[window]

    @Synchronized
    internal fun clearForTest(window: Any) { byWindow.remove(window) }
}
