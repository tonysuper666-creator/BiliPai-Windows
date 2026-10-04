package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.ui.overlay.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * A real native top-level floating player. The caller gates its embedded SwingPanel
 * with [active], so the same decoding surface has exactly one owner at a time.
 * Android overlay policies are reused; AWT provides the Windows window/chrome adapter.
 */
class PictureInPictureController(
    private val player: MpvPlayer,
    private val onRestore: () -> Unit,
    private val onPrevious: (() -> Unit)? = null,
    private val onNext: (() -> Unit)? = null,
    private val onSeekTo: ((Double) -> Unit)? = null,
    /** Direct Swing hosts remove their slot here; Compose hosts observe active. */
    private val onDetachSurface: (() -> Unit)? = null,
) : AutoCloseable {
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private var frame: JFrame? = null
    private var timer: Timer? = null
    private var handoffTimer: Timer? = null
    private var handoff: DesktopNativePresentationTransfer? = null
    private var returning = false
    private var restoreRequested = false
    private var notifyRestore = true
    private var peerRemovalRequested = false
    private var nativeRelease: (() -> Boolean)? = null
    private var handoffStartedNanos = 0L
    private var surfaceHoverListener: MouseAdapter? = null
    private var title = "BiliPai"
    private var hover = false
    private var resizing = false
    private var adjustingBounds = false
    private var videoAspect = 16f / 9f
    private var owner: Window? = null
    private var previousEnabled = false
    private var nextEnabled = false
    private var previousButton: JButton? = null
    private var nextButton: JButton? = null

    fun open(owner: Window, title: String = "BiliPai") = onUi {
        this.owner = owner
        this.title = title
        mutableError.value = null
        frame?.let { it.title = title; it.toFront(); return@onUi }
        if (handoffTimer != null) return@onUi
        val source = player.currentSourceSnapshot()
        val transfer = source?.let(player::beginPresentationTransfer)
        if (transfer == null) {
            mutableError.value = "视频尚未就绪，暂时不能打开浮窗"
            return@onUi
        }
        beginHandoff(transfer, toMain = false, notify = true)
    }

    fun updateTitle(title: String) = onUi { this.title = title; frame?.title = title }

    /** The shell supplies availability for the currently owned queue, including non-video sources. */
    fun updateQueueControls(hasPrevious: Boolean, hasNext: Boolean) = onUi {
        previousEnabled = hasPrevious; nextEnabled = hasNext
        previousButton?.isEnabled = hasPrevious; nextButton?.isEnabled = hasNext
    }

    fun restore() = onUi { requestReturn(notify = true) }

    override fun close() = onUi { requestReturn(notify = false) }

    private fun requestReturn(notify: Boolean) {
        if (handoffTimer != null) {
            restoreRequested = true
            notifyRestore = notify
            return
        }
        if (frame == null) return
        val transfer = player.currentSourceSnapshot()?.let(player::beginPresentationTransfer)
        if (transfer != null) beginHandoff(transfer, toMain = true, notify = notify)
        else {
            // An ended, failed or retired source still needs a real worker-release
            // confirmation before the floating HWND can be disposed.
            nativeRelease = player.releasePresentationForDisposal()
            returning = true; notifyRestore = notify
            startHandoffTimer()
        }
    }

    private fun beginHandoff(transfer: DesktopNativePresentationTransfer, toMain: Boolean, notify: Boolean) {
        handoff = transfer
        returning = toMain
        restoreRequested = false
        notifyRestore = notify
        peerRemovalRequested = false
        nativeRelease = null
        startHandoffTimer()
    }

    private fun startHandoffTimer() {
        handoffStartedNanos = System.nanoTime()
        handoffTimer = Timer(25) { advanceHandoff() }.apply { start() }
    }

    private fun advanceHandoff() {
        if (System.nanoTime() - handoffStartedNanos > 15_000_000_000L) {
            val requested = handoff
            if (requested != null && player.cancelRequestedPresentationTransfer(requested)) {
                mutableError.value = "浮窗切换等待超时，请稍后重试"
                finishHandoff(restored = false) // The current window/source/pause never left.
                return
            }
            mutableError.value = "原生播放器仍在释放窗口，浮窗切换尚未完成"
        }
        nativeRelease?.let { released ->
            if (!released()) return
            nativeRelease = null
            unmountWindow()
            mutableActive.value = false
            player.resumeCurrentPresentationPeer()
            finishHandoff(restored = notifyRestore)
            return
        }
        val transfer = handoff ?: return
        if (!player.isPresentationTransferCurrent(transfer)) {
            if (transfer.cancelledFloatingPeerNeedsDisposal(frame != null, returning, restoreRequested)) {
                // addNotify may have created the new HWND before old-source
                // admission rejected attach. Drain any worker, then remove that
                // actual floating peer instead of waiting for it to disappear.
                nativeRelease = player.releasePresentationForDisposal()
                handoff = null
                return
            }
            // A fresh source which has already started on the new peer keeps
            // that peer. Only the old pre-attach worker must finish draining.
            if (!transfer.hasAttachedPeer && transfer.resume != null && !player.presentationWorkerReleased(transfer)) return
            if (!transfer.hasAttachedPeer && transfer.resume != null && peerRemovalRequested &&
                !player.wasPresentationPeerReleased(transfer)) return
            player.retirePresentationTransfer(transfer)
            if (!transfer.hasAttachedPeer && transfer.resume != null) {
                if (peerRemovalRequested) mutableActive.value = false
                else player.resumeCurrentPresentationPeer()
            }
            mutableError.value = "播放来源已改变，浮窗切换已取消"
            finishHandoff(restored = returning && frame == null && notifyRestore)
            return
        }
        when (transfer.phase) {
            DesktopNativePresentationTransfer.Phase.REQUESTED -> Unit
            DesktopNativePresentationTransfer.Phase.CAPTURED -> {
                if (!player.presentationWorkerReleased(transfer)) return
                if (returning) {
                    unmountWindow() // removeNotify confirms release of the old peer.
                    mutableActive.value = false
                    finishHandoff(restored = notifyRestore)
                } else if (restoreRequested && !peerRemovalRequested) {
                    if (player.resumePresentationInCurrentPeer(transfer)) {
                        // Retain the handoff until the real resumed frame/pause ACK.
                        returning = true
                    }
                } else if (!peerRemovalRequested) {
                    peerRemovalRequested = true
                    mutableActive.value = true // Root now removes its native peer.
                    try { onDetachSurface?.invoke() } catch (_: Throwable) {
                        nativeRelease = player.releasePresentationForDisposal()
                        notifyRestore = true
                        mutableError.value = "主播放器未能释放浮窗表面"
                    }
                }
            }
            DesktopNativePresentationTransfer.Phase.PEER_RELEASED -> {
                if (!player.presentationPeerReleased(transfer)) return
                if (returning || restoreRequested) {
                    mutableActive.value = false
                    finishHandoff(restored = notifyRestore)
                } else {
                    try {
                        val parent = owner?.takeIf { it.isDisplayable }
                            ?: error("主播放器窗口已关闭")
                        mount(parent)
                    } catch (_: Throwable) {
                        // Do not destroy a newly attached HWND while its worker
                        // might still be initializing. Use the same release fence.
                        nativeRelease = player.releasePresentationForDisposal()
                        notifyRestore = true
                        mutableError.value = "浮窗播放器未能初始化"
                    }
                }
            }
            DesktopNativePresentationTransfer.Phase.ATTACHED -> Unit
            DesktopNativePresentationTransfer.Phase.ACKNOWLEDGED -> {
                val returnAfterAttach = restoreRequested && frame != null
                val restored = returning && frame == null && notifyRestore
                finishHandoff(restored)
                if (returnAfterAttach) requestReturn(notifyRestore)
            }
            DesktopNativePresentationTransfer.Phase.RETIRED -> Unit
        }
    }

    private fun finishHandoff(restored: Boolean) {
        handoffTimer?.stop(); handoffTimer = null
        handoff = null; nativeRelease = null
        if (restored) {
            onRestore()
            owner?.let { it.toFront(); it.requestFocus() }
        }
    }

    private fun unmountWindow() {
        timer?.stop(); timer = null
        surfaceHoverListener?.let(player.surface::removeMouseListener); surfaceHoverListener = null
        frame?.let { window ->
            window.contentPane.remove(player.surface)
            window.dispose()
        }
        frame = null
        previousButton = null; nextButton = null
    }

    private fun mount(owner: Window) {
        val window = JFrame(title).apply {
            defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            isAlwaysOnTop = true
            isResizable = false
            background = Color.BLACK
        }
        frame = window
        val header = JPanel(BorderLayout()).apply { background = Color(28, 28, 32) }
        val label = JLabel(title).apply { foreground = Color.WHITE; border = javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8) }
        header.add(label, BorderLayout.CENTER)
        header.add(button("返回", "返回主播放器") { restore() }, BorderLayout.EAST)
        window.contentPane.add(header, BorderLayout.NORTH)
        window.contentPane.add(player.surface, BorderLayout.CENTER)
        val footer = JPanel(BorderLayout()).apply { background = Color(28, 28, 32) }
        val controls = JPanel(FlowLayout(FlowLayout.CENTER, 1, 1)).apply { background = Color(28, 28, 32) }
        if (onPrevious != null) controls.add(button("上集", "上一集", onPrevious).also { previousButton = it; it.isEnabled = previousEnabled })
        controls.add(button("−5", "后退五秒") { seekBy(-5.0) })
        val play = button("暂停", "播放/暂停") { player.togglePause() }
        controls.add(play)
        controls.add(button("+5", "前进五秒") { seekBy(5.0) })
        if (onNext != null) controls.add(button("下集", "下一集", onNext).also { nextButton = it; it.isEnabled = nextEnabled })
        val mute = button("静音", "静音/取消静音") { player.toggleMuted() }
        controls.add(mute)
        footer.add(controls, BorderLayout.CENTER)
        val progress = object : JProgressBar(0, 10_000) {
            override fun paintComponent(graphics: Graphics) {
                val chrome = resolveMiniPlayerOverlayChrome(hover, false, false, resizing)
                val context = graphics.create() as Graphics2D
                try { context.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, chrome.progressBarAlpha); super.paintComponent(context) }
                finally { context.dispose() }
            }
        }.apply {
            preferredSize = Dimension(300, 7)
            foreground = Color(244, 101, 152)
            background = Color(56, 56, 62)
            isBorderPainted = false
            toolTipText = "点击或拖动跳转"
        }
        val seek = object : MouseAdapter() {
            private fun seek(event: MouseEvent) {
                val durationMs = (player.state.value.durationSeconds * 1_000).toLong()
                if (durationMs > 0) seekTo(resolveMiniPlayerSeekTargetPosition(0, event.x.toFloat(), progress.width.toFloat(), durationMs) / 1_000.0)
            }
            override fun mousePressed(e: MouseEvent) = seek(e)
            override fun mouseDragged(e: MouseEvent) = seek(e)
        }
        progress.addMouseListener(seek); progress.addMouseMotionListener(seek)
        footer.add(progress, BorderLayout.NORTH)
        val resize = JLabel("↘").apply { foreground = Color.LIGHT_GRAY; cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR); toolTipText = "拖动调整浮窗大小" }
        footer.add(resize, BorderLayout.EAST)
        window.contentPane.add(footer, BorderLayout.SOUTH)
        val hoverListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; progress.repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; progress.repaint() }
        }
        surfaceHoverListener = hoverListener
        listOf(header, label, controls, progress, player.surface).forEach { it.addMouseListener(hoverListener) }
        val drag = object : MouseAdapter() {
            private var start: Point? = null
            private var origin: Point? = null
            override fun mousePressed(e: MouseEvent) { start = e.locationOnScreen; origin = window.location }
            override fun mouseDragged(e: MouseEvent) {
                val anchor = start ?: return; val original = origin ?: return
                val point = e.locationOnScreen
                window.location = Point(original.x + point.x - anchor.x, original.y + point.y - anchor.y)
                clampToScreen(window)
            }
        }
        label.addMouseListener(drag); label.addMouseMotionListener(drag)
        val sizeListener = object : MouseAdapter() {
            private var start: Point? = null
            private var width = 0f
            override fun mousePressed(e: MouseEvent) { resizing = true; start = e.locationOnScreen; width = player.surface.width.toFloat() }
            override fun mouseReleased(e: MouseEvent) { resizing = false; start = null; progress.repaint() }
            override fun mouseDragged(e: MouseEvent) {
                val anchor = start ?: return
                val screen = screenBounds(window)
                val bounds = resolveMiniPlayerResizeBounds(300, (300 / videoAspect).roundToInt(), screen.width, screen.height, 12, 0, 70)
                val point = e.locationOnScreen
                val nextWidth = resolveResizedMiniPlayerWidth(width, (point.x - anchor.x).toFloat(), (point.y - anchor.y).toFloat(), videoAspect,
                    bounds.minWidthDp, bounds.maxWidthDp)
                sizeVideo(window, nextWidth, header.preferredSize.height + footer.preferredSize.height)
                clampToScreen(window)
            }
        }
        resize.addMouseListener(sizeListener); resize.addMouseMotionListener(sizeListener)
        window.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { restore() } })
        window.addComponentListener(object : ComponentAdapter() { override fun componentMoved(e: ComponentEvent) { clampToScreen(window) } })
        bind(window.rootPane, KeyEvent.VK_SPACE) { player.togglePause() }
        bind(window.rootPane, KeyEvent.VK_LEFT) { seekBy(-5.0) }
        bind(window.rootPane, KeyEvent.VK_RIGHT) { seekBy(5.0) }
        bind(window.rootPane, KeyEvent.VK_M) { player.toggleMuted() }
        bind(window.rootPane, KeyEvent.VK_ESCAPE) { restore() }
        val state = player.state.value
        videoAspect = aspect(state)
        val layout = resolveMiniPlayerOverlayLayoutPolicy(owner.graphicsConfiguration.bounds.width)
        val dimensions = resolveAdaptiveMiniPlayerDimensions(videoAspect, layout.miniPlayerWidthDp.toFloat(), layout.miniPlayerHeightDp.toFloat())
        player.surface.preferredSize = Dimension(dimensions.widthDp.roundToInt(), dimensions.heightDp.roundToInt())
        window.pack()
        val screen = screenBounds(owner)
        val position = resolveMiniPlayerInitialOverlayOffset(null, false, screen.width.toFloat(), screen.height.toFloat(), window.width.toFloat(),
            window.height.toFloat(), layout.outerPaddingDp.toFloat(), 0f, 0f)
        window.location = Point(screen.x + position.x.roundToInt(), screen.y + position.y.roundToInt())
        window.isVisible = true
        timer = Timer(resolveMiniPlayerPollingIntervalMs(!state.paused).toInt()) {
            val current = player.state.value
            play.text = if (current.paused || current.ended) "播放" else "暂停"
            mute.text = if (current.muted) "取消静音" else "静音"
            val nextAspect = aspect(current)
            if (current.videoWidth > 0 && current.videoHeight > 0 && kotlin.math.abs(nextAspect - videoAspect) > 0.01f && !resizing) {
                videoAspect = nextAspect
                sizeVideo(window, player.surface.width.toFloat(), header.preferredSize.height + footer.preferredSize.height)
                clampToScreen(window)
            }
            label.text = title
            val fraction = if (current.durationSeconds > 0) current.positionSeconds / current.durationSeconds else 0.0
            progress.value = (fraction.coerceIn(0.0, 1.0) * 10_000).roundToInt()
            progress.toolTipText = "${clock(current.positionSeconds)} / ${clock(current.durationSeconds)}"
            timer?.delay = resolveMiniPlayerPollingIntervalMs(!current.paused).toInt()
        }.apply { start() }
    }

    /** A business host can retain original user-seek hooks while media-only hosts keep native controls. */
    private fun seekTo(seconds: Double) {
        if (seconds.isFinite()) (onSeekTo ?: player::seekTo)(seconds.coerceAtLeast(0.0))
    }
    private fun seekBy(seconds: Double) { seekTo(player.state.value.positionSeconds + seconds) }

    private fun sizeVideo(window: JFrame, width: Float, chromeHeight: Int) {
        val aspect = videoAspect.coerceIn(0.45f, 2.39f)
        val safeWidth = width.coerceAtLeast(168f)
        val insets = window.insets
        window.setSize(safeWidth.roundToInt() + insets.left + insets.right,
            (safeWidth / aspect).roundToInt() + chromeHeight + insets.top + insets.bottom)
        window.validate()
    }

    private fun clampToScreen(window: JFrame) {
        if (adjustingBounds) return
        adjustingBounds = true
        try {
            val screen = screenBounds(window)
            val offset = clampMiniPlayerOverlayOffset((window.x - screen.x).toFloat(), (window.y - screen.y).toFloat(), screen.width.toFloat(),
                screen.height.toFloat(), window.width.toFloat(), window.height.toFloat(), 4f, 0f, 0f)
            val target = Point(screen.x + offset.x.roundToInt(), screen.y + offset.y.roundToInt())
            if (window.location != target) window.location = target
        } finally { adjustingBounds = false }
    }

    private fun screenBounds(window: Window): Rectangle {
        val configuration = window.graphicsConfiguration
        val bounds = Rectangle(configuration.bounds)
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        return Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom)
    }

    private fun aspect(state: PlayerState): Float = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 16f / 9f
    private fun clock(seconds: Double): String { val value = seconds.takeIf(Double::isFinite)?.toLong()?.coerceAtLeast(0) ?: 0; return "%d:%02d".format(value / 60, value % 60) }
    private fun button(text: String, hint: String, action: () -> Unit) = JButton(text).apply {
        toolTipText = hint; isFocusable = false; margin = java.awt.Insets(3, 4, 3, 4); addActionListener { action() }
    }
    private fun bind(component: JComponent, key: Int, action: () -> Unit) {
        val name = "BiliPai-$key"
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name)
        component.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent) = action() })
    }
    private fun onUi(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action) }
}
