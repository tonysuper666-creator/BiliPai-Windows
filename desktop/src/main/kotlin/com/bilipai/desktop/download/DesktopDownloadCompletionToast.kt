package com.bilipai.desktop.download

import com.bilipai.desktop.platform.toast.DesktopToast
import javax.swing.SwingUtilities

/**
 * The "下载完成" notice a finished download owes the user, spoken through the platform toast
 * facade instead of a download-local notification implementation.
 *
 * The owner keeps the download bookkeeping: it decides which tasks just finished and calls
 * [notify] once per arrival from its own IO scope. This class owns only the presentation contract,
 * which must stay identical to the previous direct call: the fixed "下载完成" title, the task title
 * as the message, the `downloads` route and group, and the task id as the tag so two tasks finishing
 * close together do not replace each other's banner.
 *
 * [DesktopToast] reports every failure it can observe (not Windows, notifications disabled,
 * PowerShell timeout). Those exceptions stop at this boundary and reach [onFailure] on the EDT,
 * alongside the owner's other download-notification diagnostics, because a toast the user never saw
 * must not look delivered. A notice is dropped rather than shown once the owner has closed or
 * retired, so a late IO task cannot outlive the window it speaks for.
 */
internal class DesktopDownloadCompletionToast(
    // The default is the production surface: the real Windows notification center, same fixed title.
    private val toast: DesktopToast = DesktopToast(title = "下载完成"),
    private val stillOwned: () -> Boolean = { true },
    private val onFailure: (String, Exception) -> Unit = { _, _ -> },
) {
    /** Blocking IO boundary: callers invoke this off the EDT, as [DesktopToast] shells out to Windows. */
    fun notify(taskId: String, taskTitle: String, isClosed: () -> Boolean) {
        if (isClosed() || !stillOwned()) return
        try {
            toast.show(taskTitle, ROUTE, taskId, ROUTE)
        } catch (failure: Exception) {
            SwingUtilities.invokeLater { if (!isClosed() && stillOwned()) onFailure(FAILURE_MESSAGE, failure) }
        }
    }

    private companion object {
        const val ROUTE = "downloads"
        const val FAILURE_MESSAGE = "下载完成通知失败"
    }
}
