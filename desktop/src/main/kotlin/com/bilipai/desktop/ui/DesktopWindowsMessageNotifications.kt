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
 * Android group summary. The tray menu retains bounded current entries and their precise destinations. */
internal class DesktopWindowsMessageNotifications(
    private val context: DesktopMessageNotificationContext,
    private val window: Window,
    private val center: com.bilipai.desktop.integration.DesktopNotificationCenter =
        com.bilipai.desktop.integration.DesktopToastNotifications,
) : AutoCloseable {
    /** The account this presentation belongs to; cleanup must target it even after a switch. */
    private val session = DesktopNotificationSession(context.mid, center)
    private val closed = AtomicBoolean()
    private var tray: TrayIcon? = null // EDT only; created solely after master is enabled.
    private val entries = DesktopMessageNotificationEntries()
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

    /** These are this Root's menu entries; Windows balloons expose no active per-item IDs. */
    suspend fun activeMessageKeys(): Set<String> = withContext(Dispatchers.Main) {
        currentCoroutineContext().ensureActive()
        if (canPost()) entries.keys() else emptySet()
    }

    /** [cancelled] are items the server now reports as read. The tray entry is removed and the delivered
     * notification-center toast is withdrawn by its own tag inside this account's group. */
    suspend fun postAll(pending: List<PendingMessageNotification>, cancelled: Set<String> = emptySet(),
        pendingCounts: Map<String, Int>? = null) = withContext(Dispatchers.Main) {
        currentCoroutineContext().ensureActive()
        check(EventQueue.isDispatchThread()) { "Message notifications require the actual Windows EDT" }
        if (!canPost()) return@withContext
        if (pending.isEmpty() && cancelled.isEmpty()) return@withContext
        // A refused withdrawal stays pending: the toast is still on screen, so its key must stay
        // active for the upstream checker and the next round retries it.
        val stillOnScreen = withContext(Dispatchers.IO) { session.withdraw(cancelled) }
        entries.apply(pending, cancelled - stillOnScreen, pendingCounts)
        val visible = entries.snapshot()
        if (visible.isEmpty()) { removeTrayOnEdt(); return@withContext }
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
        for (item in visible) {
            menu.add(MenuItem(item.title + " · " + item.text.take(60)).also { row ->
                row.addActionListener { open(item.route) }
            })
        }
        icon.popupMenu = menu
        for (item in pending) {
            currentCoroutineContext().ensureActive()
            if (!canPost()) return@withContext
            val delivered = withContext(Dispatchers.IO) { session.publish(item) }
            if (!delivered) {
                // The notification center is the requested surface; the balloon keeps the message visible when it is not.
                System.err.println("Windows 通知中心提交失败，改用托盘气泡 tag=${item.key}")
                icon.displayMessage(item.title, item.text, TrayIcon.MessageType.INFO)
            }
        }
        // The OS may suppress/coalesce balloons. Submission is not evidence of visible delivery,
        // and a previously submitted balloon cannot be atomically withdrawn on account switch.
    }

    suspend fun clear() {
        withContext(Dispatchers.Main) { removeTrayOnEdt() }
        withContext(Dispatchers.IO) { session.clearAll() }
    }

    /** Keys the surface refused to withdraw; they stay active upstream until a retry succeeds. */
    internal fun pendingWithdrawals(): Set<String> = session.pendingWithdrawals()
    private fun removeTrayOnEdt() {
        check(EventQueue.isDispatchThread())
        tray?.let { SystemTray.getSystemTray().remove(it) }
        tray = null
        entries.clear()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cleanup = { window.removeHierarchyListener(hierarchy); removeTrayOnEdt() }
        if (EventQueue.isDispatchThread()) cleanup() else EventQueue.invokeLater { cleanup() }
        // Account switch or window teardown: the group belongs to the account captured at construction,
        // which is the one whose toasts may still be on screen.
        Thread({ session.retire() }, "BiliPai-notification-withdraw").apply { isDaemon = true }.start()
    }
}
