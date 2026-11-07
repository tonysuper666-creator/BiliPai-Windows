package com.bilipai.desktop.platform.share

import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Writes to the system clipboard by default. The supplier is evaluated on the AWT thread. */
class AwtDesktopShare(
    private val clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
) : DesktopShare {
    override fun copyLink(link: String) {
        fun copy() = clipboard().setContents(StringSelection(link), null)
        if (EventQueue.isDispatchThread()) {
            copy()
        } else {
            val task = FutureTask { copy() }
            EventQueue.invokeAndWait(task)
            try {
                task.get()
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
        }
    }
}
