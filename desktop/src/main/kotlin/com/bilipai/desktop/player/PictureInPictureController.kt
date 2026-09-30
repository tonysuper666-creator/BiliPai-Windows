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
) : AutoCloseable {
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private var frame: JFrame? = null
    private var timer: Timer? = null
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
        val existing = frame
        if (existing != null) { existing.title = title; existing.toFront(); return@onUi }
        mutableActive.value = true
        // Compose observes the ownership change before the next AWT event mounts the surface.
        SwingUtilities.invokeLater {
            if (!mutableActive.value || frame != null) return@invokeLater
            try { mount(owner) } catch (failure: Throwable) {
                unmount()
                mutableError.value = failure.message ?: "浮窗播放器未能初始化"
                onRestore()
            }
        }
    }

    fun updateTitle(title: String) = onUi { this.title = title; frame?.title = title }

    /** The shell supplies availability for the currently owned queue, including non-video sources. */
    fun updateQueueControls(hasPrevious: Boolean, hasNext: Boolean) = onUi {
        previousEnabled = hasPrevious; nextEnabled = hasNext
        previousButton?.isEnabled = hasPrevious; nextButton?.isEnabled = hasNext
    }

    fun restore() = onUi {
        unmount()
        onRestore()
        owner?.let { it.toFront(); it.requestFocus() }
    }

    override fun close() = onUi { unmount() }

    private fun unmount() {
        timer?.stop(); timer = null
        frame?.let { window ->
            window.contentPane.remove(player.surface)
            window.dispose()
        }
        frame = null
        previousButton = null; nextButton = null
        mutableActive.value = false
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
        controls.add(button("−5", "后退五秒") { player.seekBy(-5.0) })
        val play = button("暂停", "播放/暂停") { player.togglePause() }
        controls.add(play)
        controls.add(button("+5", "前进五秒") { player.seekBy(5.0) })
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
                if (durationMs > 0) player.seekTo(resolveMiniPlayerSeekTargetPosition(0, event.x.toFloat(), progress.width.toFloat(), durationMs) / 1_000.0)
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
        bind(window.rootPane, KeyEvent.VK_LEFT) { player.seekBy(-5.0) }
        bind(window.rootPane, KeyEvent.VK_RIGHT) { player.seekBy(5.0) }
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
