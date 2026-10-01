package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.shouldContinueAllInclude
import com.android.purebilibili.feature.download.shouldPauseAllInclude
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Read-only presentation of the authoritative manager tasks. No download state is stored here. */
internal data class DesktopDownloadNotificationProjection(
    val taskCount: Int,
    val activeCount: Int,
    val queuedCount: Int,
    val pausedCount: Int,
    val failedCount: Int,
    val percent: Int,
    val state: Taskbar.State,
    val taskLines: List<String>,
) {
    val ongoing: Boolean get() = activeCount + queuedCount + pausedCount + failedCount > 0
}

internal fun projectDesktopDownloadNotification(tasks: List<DownloadTask>): DesktopDownloadNotificationProjection {
    // Exact stable DownloadWorker.getForegroundInfo percentage/title expressions.
    fun percent(progress: Float): Int = (progress.coerceIn(0f, 1f) * 100).toInt()
    val active = tasks.filter { it.item.isDownloading }
    val queued = tasks.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PENDING }
    val paused = tasks.filter { it.status == DownloadStatus.PAUSED }
    val failed = tasks.filter { it.status == DownloadStatus.FAILED }
    // Windows has one bar per host window. This equal-task average is a platform projection,
    // not an upstream byte-weighted total (some asset totals are unknown).
    val displayed = active.ifEmpty { queued.ifEmpty { failed.ifEmpty { paused } } }
    val total = if (displayed.isEmpty()) 0 else displayed.sumOf { percent(it.progress).toLong() }.div(displayed.size).toInt()
    val state = when {
        active.isNotEmpty() || queued.isNotEmpty() -> if (total <= 0) Taskbar.State.INDETERMINATE else Taskbar.State.NORMAL
        failed.isNotEmpty() -> Taskbar.State.ERROR
        paused.isNotEmpty() -> Taskbar.State.PAUSED
        else -> Taskbar.State.OFF
    }
    val lines = tasks.filter { it.status != DownloadStatus.COMPLETED }.map { task ->
        val title = task.title.takeIf { it.isNotBlank() } ?: "下载中..."
        val label = when (task.status) {
            DownloadStatus.QUEUED, DownloadStatus.PENDING -> "等待下载"
            DownloadStatus.DOWNLOADING -> "下载中"
            DownloadStatus.MERGING -> "合并中"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.FAILED -> "下载失败"
            DownloadStatus.COMPLETED -> "已完成"
        }
        "${title.replace('\u0000', ' ')} · $label ${percent(task.progress)}%"
    }
    return DesktopDownloadNotificationProjection(tasks.size, active.size, queued.size, paused.size, failed.size, total, state, lines)
}

/** Diagnostic state: API dispatch is not proof of visible Shell pixels or exposed native HRESULT. */
internal data class DesktopDownloadNotificationStatus(
    val projection: DesktopDownloadNotificationProjection? = null,
    val taskbarSupported: Boolean = false,
    val taskbarDispatches: Long = 0,
    val traySupported: Boolean = false,
    val trayPresent: Boolean = false,
    val closed: Boolean = false,
    val error: String? = null,
)

/** Windows boundary. JDK's existing WTaskbarPeer/TrayIcon peers own the COM/Shell ABI.
 * Construct on EDT with the same Root host Window; close before that window/manager retires.
 * The tray is an ongoing, silent progress surface (no periodic balloon notifications).
 */
internal class DesktopDownloadNotifications(
    private val manager: DesktopDownloadManager,
    private val window: Window,
    parentScope: CoroutineScope,
    private val stillOwned: () -> Boolean,
    private val onOpenDownloads: () -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val renderGeneration = AtomicLong()
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableStatus = MutableStateFlow(DesktopDownloadNotificationStatus())
    val status: StateFlow<DesktopDownloadNotificationStatus> = mutableStatus.asStateFlow()
    private var tray: TrayIcon? = null
    private var taskbar: Taskbar? = null
    private val hierarchyListener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !window.isDisplayable) close()
    }

    init {
        check(SwingUtilities.isEventDispatchThread()) { "Download notification owner must be installed on EDT" }
        check(window.isDisplayable && stillOwned()) { "Download notification owner has retired" }
        // A replacement for this same native host retires the old presentation first.
        owners[window]?.get()?.closeOnEdt()
        owners[window] = WeakReference(this)
        window.addHierarchyListener(hierarchyListener)
        try {
            taskbar = if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().takeIf {
                it.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW) && it.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW)
            } else null
            mutableStatus.value = mutableStatus.value.copy(taskbarSupported = taskbar != null, traySupported = SystemTray.isSupported())
        } catch (failure: Exception) { reportFailure("Windows 下载进度不可用", failure) }
        publish()
        scope.launch {
            try { while (isActive && !closed.get()) {
                // Original DownloadWorker progress updater cadence; always read the same StateFlow.
                delay(2_000L)
                if (!stillOwned()) { close(); break }
                publish()
            } } finally { close() }
        }
    }

    private fun ownedOnEdt(): Boolean = !closed.get() && stillOwned() && window.isDisplayable && owners[window]?.get() === this

    private fun publish() {
        if (closed.get()) return
        val projection = projectDesktopDownloadNotification(manager.tasks.value)
        val generation = renderGeneration.incrementAndGet()
        SwingUtilities.invokeLater {
            if (!ownedOnEdt() || renderGeneration.get() != generation) return@invokeLater
            applyOnEdt(projection)
        }
    }

    private fun applyOnEdt(projection: DesktopDownloadNotificationProjection) {
        var dispatches = mutableStatus.value.taskbarDispatches
        try {
            taskbar?.let { bar ->
                // Value switches INDETERMINATE to NORMAL. Establish the target state first,
                // then set the value only for determinate states; OFF explicitly clears it.
                bar.setWindowProgressState(window, projection.state)
                if (projection.state != Taskbar.State.OFF && projection.state != Taskbar.State.INDETERMINATE)
                    bar.setWindowProgressValue(window, projection.percent)
                dispatches++
            }
        } catch (failure: Exception) { reportFailure("Windows 任务栏下载进度更新失败", failure) }
        try {
            if (projection.ongoing && SystemTray.isSupported()) {
                val icon = tray ?: TrayIcon(renderIcon(projection), "BiliPai 下载").also {
                    it.isImageAutoSize = true
                    it.addActionListener { if (ownedOnEdt()) onOpenDownloads() }
                    SystemTray.getSystemTray().add(it)
                    tray = it
                }
                icon.image = renderIcon(projection)
                icon.toolTip = ("BiliPai · 下载 ${projection.activeCount} · 等待 ${projection.queuedCount} · 暂停 ${projection.pausedCount} · 失败 ${projection.failedCount}" +
                    if (projection.taskLines.isNotEmpty()) "\n" + projection.taskLines.joinToString("\n") else "").take(127)
                icon.popupMenu = menuOnEdt()
            } else removeTrayOnEdt()
        } catch (failure: Exception) { reportFailure("Windows 托盘下载进度更新失败", failure) }
        mutableStatus.value = mutableStatus.value.copy(projection = projection, taskbarDispatches = dispatches, trayPresent = tray != null)
    }

    private fun menuOnEdt(): PopupMenu = PopupMenu().apply {
        add(MenuItem("打开离线缓存").apply { addActionListener { if (ownedOnEdt()) onOpenDownloads() } })
        addSeparator()
        add(MenuItem("全部暂停").apply {
            isEnabled = manager.tasks.value.any { shouldPauseAllInclude(it.item) }
            addActionListener { pauseAll() }
        })
        add(MenuItem("全部继续").apply {
            isEnabled = manager.tasks.value.any { shouldContinueAllInclude(it.item) }
            addActionListener { continueAll() }
        })
        for (task in manager.tasks.value.filter { it.status != DownloadStatus.COMPLETED }) {
            add(Menu((task.title.ifBlank { "下载中..." } + " ${((task.progress.coerceIn(0f, 1f)) * 100).toInt()}%").replace('\u0000', ' ').take(120)).apply {
                val canPause = shouldPauseAllInclude(task.item)
                add(MenuItem(if (canPause) "暂停" else if (task.status == DownloadStatus.FAILED) "重试" else "继续").apply {
                    isEnabled = canPause || shouldContinueAllInclude(task.item)
                    addActionListener {
                        if (ownedOnEdt()) runCommand {
                            val current = manager.tasks.value.firstOrNull { it.id == task.id } ?: return@runCommand
                            if (shouldPauseAllInclude(current.item)) manager.pause(current.id)
                            else if (shouldContinueAllInclude(current.item)) manager.resume(current.id)
                        }
                    }
                })
            })
        }
    }

    // These are the actual tray action endpoints, also callable by an owned Root action.
    fun pauseAll() {
        check(SwingUtilities.isEventDispatchThread())
        if (ownedOnEdt()) runCommand {
            manager.tasks.value.filter { shouldPauseAllInclude(it.item) }.forEach { if (ownedOnEdt()) manager.pause(it.id) }
        }
    }

    fun continueAll() {
        check(SwingUtilities.isEventDispatchThread())
        if (ownedOnEdt()) runCommand {
            manager.tasks.value.filter { shouldContinueAllInclude(it.item) }.forEach { if (ownedOnEdt()) manager.resume(it.id) }
        }
    }

    private fun runCommand(action: () -> Unit) {
        try { action(); publish() } catch (failure: Exception) { reportFailure("下载操作失败", failure) }
    }

    private fun reportFailure(message: String, failure: Exception) {
        val detail = "$message (${failure.javaClass.simpleName})"
        if (mutableStatus.value.error == detail) return
        mutableStatus.value = mutableStatus.value.copy(error = detail)
        if (!closed.get() && stillOwned()) runCatching { onFailure(message) }
    }

    private fun removeTrayOnEdt() { tray?.let { SystemTray.getSystemTray().remove(it) }; tray = null }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        renderGeneration.incrementAndGet()
        scope.cancel()
        if (SwingUtilities.isEventDispatchThread()) closeOnEdt() else SwingUtilities.invokeLater(::closeOnEdt)
    }

    private fun closeOnEdt() {
        closed.set(true)
        renderGeneration.incrementAndGet()
        scope.cancel()
        window.removeHierarchyListener(hierarchyListener)
        if (owners[window]?.get() === this) {
            runCatching { if (window.isDisplayable) taskbar?.setWindowProgressState(window, Taskbar.State.OFF) }
            owners.remove(window)
        }
        runCatching { removeTrayOnEdt() }
        mutableStatus.value = mutableStatus.value.copy(trayPresent = false, closed = true)
    }

    private fun renderIcon(value: DesktopDownloadNotificationProjection): BufferedImage = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).also { image ->
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = when (value.state) { Taskbar.State.ERROR -> Color(220, 60, 70); Taskbar.State.PAUSED -> Color(225, 165, 25); else -> Color(30, 140, 225) }
            g.fillRoundRect(2, 2, 28, 28, 8, 8)
            g.color = Color.WHITE
            g.fillRect(14, 6, 4, 11)
            g.fillPolygon(intArrayOf(9, 23, 16), intArrayOf(14, 14, 22), 3)
            g.fillRect(6, 25, (20 * value.percent / 100).coerceAtLeast(1), 3)
        } finally { g.dispose() }
    }

    private companion object {
        // Presentation ownership only, weak values and explicit retirement; no task/cache authority.
        val owners = WeakHashMap<Window, WeakReference<DesktopDownloadNotifications>>()
    }
}
