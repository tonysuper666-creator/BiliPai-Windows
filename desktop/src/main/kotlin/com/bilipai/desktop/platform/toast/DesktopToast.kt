package com.bilipai.desktop.platform.toast

import com.bilipai.desktop.integration.DesktopNotificationCenter
import com.bilipai.desktop.integration.DesktopToastNotifications

/**
 * System-notification surface for code that has no composition: one transient notice in the Windows
 * notification center, the OS-level counterpart of a screen's in-app toast. Compose screens keep
 * using the shell toast (`LocalDesktopToaster`); this facade exists for callers without a composition
 * that still owe the user a visible message.
 *
 * [center] defaults to the real Windows notification-center adapter, so a toast is delivered by the
 * same code path as message notifications instead of a second, memory-only implementation. That
 * center reports every failure it can observe (not Windows, notification disabled, PowerShell
 * timeout, nonzero exit); those exceptions reach the caller unchanged, because a toast the user
 * never saw must not look delivered.
 *
 * Repeated toasts share [DEFAULT_TAG] and [DEFAULT_GROUP], so a newer one replaces the previous
 * banner rather than stacking.
 */
internal class DesktopToast(
    private val center: DesktopNotificationCenter = DesktopToastNotifications,
    private val title: String = APP_NAME,
) {
    fun show(
        message: String,
        route: String = "",
        tag: String = DEFAULT_TAG,
        group: String = DEFAULT_GROUP,
    ) {
        center.show(title, message, route, tag, group)
    }

    companion object {
        const val APP_NAME = "BiliPai Windows"
        const val DEFAULT_TAG = "desktop-toast"
        const val DEFAULT_GROUP = "desktop-toast"
    }
}
