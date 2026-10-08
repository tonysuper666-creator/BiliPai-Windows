package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.notification.PendingMessageNotification
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.legacyRouteToBiliPaiNavKey
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** Native Windows presentation over the same JDK Shell peer used by download progress.
 * No credentials, business dedup store or routing stack. Android channel IDs/groups/routes
 * remain on the original pending objects; Shell balloons have no per-item click ID or native
 * Android group summary. The tray menu retains this bounded batch's precise destinations. */
internal class DesktopWindowsMessageNotifications(
    private val context: DesktopMessageNotificationContext,
    private val window: Window,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private var tray: TrayIcon? = null // EDT only; created solely after master is enabled.
    private val hierarchy = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !window.isDisplayable) close()
    }
    init {
        check(EventQueue.isDispatchThread()) { "Message notification presentation requires the actual Windows EDT" }
        window.addHierarchyListener(hierarchy)
    }

    fun canPost(): Boolean = !closed.get() && window.isDisplayable && context.isCurrent() &&
        !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()

    private fun open(route: String) {
        check(EventQueue.isDispatchThread())
        if (closed.get() || !window.isDisplayable || !context.isCurrent()) return
        // The existing controller supplies checkpoint, Store/Root admission, decoration and
        // native beforeCommit. No external/UI callback is invoked inside the receipt gate.
        context.routes.push(legacyRouteToBiliPaiNavKey(route))
    }

    suspend fun postAll(pending: List<PendingMessageNotification>) = withContext(Dispatchers.Main) {
        currentCoroutineContext().ensureActive()
        check(EventQueue.isDispatchThread()) { "Message notifications require the actual Windows EDT" }
        if (pending.isEmpty() || !canPost()) return@withContext
        val icon = tray ?: TrayIcon(requireNotNull(ImageIO.read(
            requireNotNull(javaClass.classLoader.getResource("app-icon.png")))), "BiliPai 消息").also {
            it.isImageAutoSize = true
            // A Windows balloon click cannot reliably identify an individual pending object.
            // Its action opens the real message center; exact routes stay in the tray menu.
            it.addActionListener {
                if (!closed.get() && window.isDisplayable && context.isCurrent()) context.routes.push(BiliPaiNavKey.Inbox)
            }
            SystemTray.getSystemTray().add(it)
            tray = it
        }
        val menu = PopupMenu()
        for (item in pending) {
            menu.add(MenuItem(item.title + " · " + item.text.take(60)).also { row ->
                row.addActionListener { open(item.route) }
            })
        }
        icon.popupMenu = menu
        for (item in pending) {
            currentCoroutineContext().ensureActive()
            if (!canPost()) return@withContext
            icon.displayMessage(item.title, item.text, TrayIcon.MessageType.INFO)
        }
        // The OS may suppress/coalesce balloons. Submission is not evidence of visible delivery,
        // and a previously submitted balloon cannot be atomically withdrawn on account switch.
    }

    suspend fun clear() = withContext(Dispatchers.Main) { removeTrayOnEdt() }
    private fun removeTrayOnEdt() {
        check(EventQueue.isDispatchThread())
        tray?.let { SystemTray.getSystemTray().remove(it) }
        tray = null
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cleanup = { window.removeHierarchyListener(hierarchy); removeTrayOnEdt() }
        if (EventQueue.isDispatchThread()) cleanup() else EventQueue.invokeLater { cleanup() }
    }
}
