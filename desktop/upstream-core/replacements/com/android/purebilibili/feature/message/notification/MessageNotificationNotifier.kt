// Replaces upstream app/src/main/java/com/android/purebilibili/feature/message/notification/MessageNotificationNotifier.kt sha256=2413e72d8a709785075d0926415f171b8e8d0a03aa2dc811e77bfa262c362912
// Upstream posts through Android NotificationManager: channels, tagged notifications per account, group summaries and
// PendingIntents into MainActivity. On Windows the same Checker result goes to this window's Windows notification centre
// presentation (DesktopWindowsMessageNotifications): a balloon per item, and a tray menu that keeps the current items
// with their exact destinations. That presentation belongs to one Root, so it only ever holds this account's items and
// ends with the window (Android's per-account tags and cancelOtherAccounts have nothing left to do).
// A submitted Windows balloon cannot be withdrawn: a cancellation removes the item from the tray menu only.
package com.android.purebilibili.feature.message.notification

import android.content.Context
import com.bilipai.desktop.ui.desktopMessageNotificationContext
import com.bilipai.desktop.ui.desktopPendingSummaryCounts

internal object MessageNotificationNotifier {
    /** False without an attached, displayable window or where Windows offers no notification area. */
    fun canPost(context: Context): Boolean = desktopMessageNotificationContext(context).presentation?.canPost() == true

    suspend fun postAll(context: Context, mid: Long, result: MessageNotificationCheckResult, isCurrentSession: () -> Boolean) {
        val items = result.notifications
        if ((items.isEmpty() && result.cancelNotificationKeys.isEmpty()) || !canPost(context)) return
        val actual = desktopMessageNotificationContext(context)
        if (actual.mid != mid || !isCurrentSession()) return
        val presentation = actual.presentation ?: return
        // The committed baseline decides whether a group summary entry survives, as Android counts pending children.
        presentation.postAll(items, result.cancelNotificationKeys, desktopPendingSummaryCounts(result.state))
    }

    /** The keys this window currently shows in its tray menu; Windows balloons expose no active per-item IDs. */
    suspend fun activeMessageKeys(context: Context, mid: Long): Set<String> {
        val actual = desktopMessageNotificationContext(context)
        if (actual.mid != mid) return emptySet()
        return actual.presentation?.activeMessageKeys() ?: emptySet()
    }

    suspend fun cancelMessages(context: Context) {
        desktopMessageNotificationContext(context).presentation?.clear()
    }
}
