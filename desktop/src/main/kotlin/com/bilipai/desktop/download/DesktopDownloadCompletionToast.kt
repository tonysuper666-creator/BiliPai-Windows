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
 *
 * A queued failure report is re-checked against the owner on the EDT, not only against the flags
 * sampled on the IO thread: the queue delay is real, and the owner may have lost its scope or its
 * window by the time the callback runs. [failureOwner] is therefore the owner's full liveness
 * predicate — the same gate the direct call used — and defaults to [stillOwned] for callers that
 * only have that much.
 */
internal class DesktopDownloadCompletionToast(
    // The default is the production surface: the real Windows notification center, same fixed title.
    private val toast: DesktopToast = DesktopToast(title = "下载完成"),
    private val stillOwned: () -> Boolean = { true },
    /** Whether this instance may still report a failure; evaluated on the EDT when the report runs. */
    private val failureOwner: () -> Boolean = stillOwned,
    private val onFailure: (String, Exception) -> Unit = { _, _ -> },
) {
    /** Blocking IO boundary: callers invoke this off the EDT, as [DesktopToast] shells out to Windows. */
    fun notify(taskId: String, taskTitle: String, isClosed: () -> Boolean) {
        if (isClosed() || !stillOwned()) return
        try {
            toast.show(taskTitle, ROUTE, taskId, ROUTE)
        } catch (failure: Exception) {
            SwingUtilities.invokeLater {
                if (!isClosed() && failureOwner()) onFailure(FAILURE_MESSAGE, failure)
            }
        }
    }

    private companion object {
        const val ROUTE = "downloads"
        const val FAILURE_MESSAGE = "下载完成通知失败"
    }
}
