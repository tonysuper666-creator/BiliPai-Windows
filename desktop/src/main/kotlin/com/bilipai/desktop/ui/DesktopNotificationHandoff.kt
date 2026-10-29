package com.bilipai.desktop.ui

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** What a presentation must be able to answer for the account handoff. */
internal interface DesktopNotificationHandover {
    fun accountId(): Long?
    fun retireForAccountSwitch()
}

/**
 * The account handoff has to outlive the consumer that owns a presentation.
 *
 * Loading another account tears the whole notification consumer down (the retained root is briefly
 * absent), so a "previous port" remembered inside that consumer is lost exactly when it is needed.
 * This registry is keyed by the window, which survives the reload: the old account's presentation is
 * still here when the new one arrives, and retiring the wrong (same) account never happens.
 */
internal object DesktopNotificationHandoff {
    private val byWindow = WeakHashMap<Any, WeakReference<DesktopNotificationHandover>>()

    @Synchronized
    fun attach(window: Any, holder: DesktopNotificationHandover) {
        val previous = byWindow.put(window, WeakReference(holder))?.get()
        if (previous != null && previous !== holder && previous.accountId() != holder.accountId()) {
            previous.retireForAccountSwitch()
        }
    }

    @Synchronized
    internal fun current(window: Any): DesktopNotificationHandover? = byWindow[window]?.get()

    @Synchronized
    internal fun clearForTest(window: Any) { byWindow.remove(window) }
}
