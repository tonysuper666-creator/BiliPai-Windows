package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.bilipai.desktop.player.MpvPlayer
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.LinearGradientPaint
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A transparent mouse-through native window above mpv's child HWND. */
class DanmakuOverlay(
    private val player: MpvPlayer,
    httpClient: OkHttpClient = ApiDesktopDanmakuSource.publicClient(),
    private val source: DesktopDanmakuSource = ApiDesktopDanmakuSource(httpClient),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val requestLock = Any()
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var windowJob: Job? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableCount = MutableStateFlow(0)
    val commentCount: StateFlow<Int> = mutableCount.asStateFlow()
    private val mutableCommands = MutableStateFlow(emptyList<CommandDanmakuItem>())
    val commandItems: StateFlow<List<CommandDanmakuItem>> = mutableCommands.asStateFlow()
    private val mutableFormat = MutableStateFlow<DanmakuFormat?>(null)
    val format: StateFlow<DanmakuFormat?> = mutableFormat.asStateFlow()
    @Volatile private var settings = DanmakuSettings()
    var enabled: Boolean
        get() = settings.enabled
        set(value) { applySettings(settings.copy(enabled = value)) }
    val currentSettings: DanmakuSettings get() = settings
    private var overlay: JWindow? = null
    private var owner: Window? = null
    private var scheduler = DanmakuScheduler(emptyList(), settings)
    private var advancedRenderer = AdvancedDanmakuRenderer(emptyList())
    private var lastPosition = Double.NaN
    private var sampleTimeNanos = 0L
    private var anchoredPosition = 0.0
    private var displayTime = 0.0
    @Volatile private var liveMode = false
    private val liveRenderer = LiveDanmakuRenderer(requests)
    private data class PendingLive(val generation: Long, val item: LiveDanmakuItem)
    private val pendingLive = ArrayBlockingQueue<PendingLive>(600)
    private val measuredWidths = mutableMapOf<Pair<Int, Int>, Int>()
    private val panel = object : JComponent() {
        override fun paintComponent(graphics: Graphics) {
            val context = graphics.create() as Graphics2D
            try {
                context.composite = AlphaComposite.Src
                context.color = Color(0, 0, 0, 0)
                context.fillRect(0, 0, width, height)
                context.composite = AlphaComposite.SrcOver
                context.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                context.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val density = graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                val viewport = resolveDanmakuViewport(width, height, density, 360f) ?: return
                val configuration = settings
                context.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, configuration.opacity)
                if (liveMode) {
                    liveRenderer.paint(context, width, height, viewport.scale, configuration)
                    return
                }
                if (measuredWidths.size > 5_000) measuredWidths.clear()
                fun fontSize(comment: DanmakuComment): Int =
                    (comment.size * viewport.scale * 0.9f * configuration.fontScale).toInt().coerceIn(10, 96)
                val fontStyle = if (configuration.fontWeight >= 5) Font.BOLD else Font.PLAIN
                val rowHeight = (48 * viewport.scale * 0.9f * configuration.fontScale * configuration.lineHeight).toInt().coerceAtLeast(18)
                val positioned = scheduler.frame(displayTime, width, height, rowHeight) { comment ->
                    val size = fontSize(comment)
                    measuredWidths.getOrPut(comment.id to size) {
                        getFontMetrics(Font("Microsoft YaHei UI", fontStyle, size)).stringWidth(comment.text)
                    }
                }
                positioned.forEach { item ->
                    val font = Font("Microsoft YaHei UI", fontStyle, fontSize(item.comment))
                    val shape = font.createGlyphVector(context.fontRenderContext, item.comment.text)
                        .getOutline(item.x.toFloat(), item.baseline.toFloat())
                    if (configuration.strokeEnabled && configuration.strokeWidth > 0f) {
                        context.color = Color.BLACK
                        context.stroke = BasicStroke(configuration.strokeWidth * viewport.scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        context.draw(shape)
                    }
                    if (item.comment.isVipGradualColor) {
                        val x = item.x.toFloat()
                        context.paint = LinearGradientPaint(x, 0f, x + item.textWidth.coerceAtLeast(1), 0f,
                            floatArrayOf(0f, 0.5f, 1f), arrayOf(Color(0xff7cba), Color(0xa798ff), Color(0x70d6ff)))
                    } else context.color = Color(item.comment.color)
                    context.fill(shape)
                }
                advancedRenderer.paint(context, (displayTime * 1000).toLong(), width, height, viewport.scale, configuration)
            } finally { context.dispose() }
        }
    }.apply { isOpaque = false }
    private val timer = Timer(16) { tick() }

    init { SwingUtilities.invokeLater { if (!closed.get()) timer.start() } }

    fun applySettings(settings: DanmakuSettings) {
        val normalized = settings.normalized()
        this.settings = normalized
        SwingUtilities.invokeLater {
            if (!closed.get()) {
                scheduler.applySettings(this.settings)
                measuredWidths.clear()
                panel.repaint()
            }
        }
    }

    suspend fun load(cid: Long, aid: Long = 0L, durationSeconds: Double = 0.0) {
        require(cid > 0) { "Invalid danmaku content ID." }
        require(aid >= 0 && durationSeconds.isFinite() && durationSeconds >= 0)
        loadSource(source, cid, aid, durationSeconds)
    }

    /** Decodes the downloaded upstream protobuf assets through the same window policy, without HTTP. */
    suspend fun loadOffline(standardSegments: List<Path>, specialSegments: List<Path> = emptyList(), durationSeconds: Double = 0.0) {
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds)
    }

    /** Switches from VOD segments to genuine realtime events received by the upstream live client. */
    fun enterLive(): Long {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = true; pendingLive.clear() }
        }
        mutableError.value = null; mutableCommands.value = emptyList(); mutableFormat.value = null
        SwingUtilities.invokeLater {
            if (!closed.get() && version == generation.get() && liveMode) {
                liveRenderer.reset(); mutableCount.value = 0; panel.repaint()
            }
        }
        return version
    }

    fun emitLive(item: LiveDanmakuItem, liveGeneration: Long = generation.get()) {
        if (!closed.get() && liveMode && liveGeneration == generation.get()) pendingLive.offer(PendingLive(liveGeneration, item))
    }

    fun removeLiveSuperChats(ids: List<Long>, liveGeneration: Long = generation.get()) {
        SwingUtilities.invokeLater {
            if (!closed.get() && liveMode && liveGeneration == generation.get()) {
                liveRenderer.removeSuperChats(ids); mutableCount.value = liveRenderer.size
            }
        }
    }

    fun clearLive(liveGeneration: Long? = null) {
        synchronized(requestLock) {
            if (liveMode && (liveGeneration == null || liveGeneration == generation.get())) setDocument(DanmakuDocument())
        }
    }

    private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear() }
        }
        mutableError.value = null
        mutableCommands.value = emptyList()
        mutableFormat.value = null
        installDocument(DanmakuDocument(), version)
        val loading = requests.async {
            val loader = DanmakuWindowLoader(playbackSource, cid, aid, (durationSeconds * 1000).toLong())
            val result = loader.initial((player.state.value.positionSeconds * 1000).toLong())
            publish(result, version)
            synchronized(requestLock) {
                if (version == generation.get() && !closed.get()) {
                    windowJob = requests.launch {
                        player.state.map { loader.windowForPosition((it.positionSeconds * 1000).toLong()) }
                            .distinctUntilChanged().collectLatest {
                                try {
                                    val next = loader.move((player.state.value.positionSeconds * 1000).toLong())
                                    if (next != null) publish(next, version)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    if (version == generation.get() && !closed.get())
                                        mutableError.value = "弹幕分段加载失败：${failure.message ?: "network error"}"
                                }
                            }
                    }
                }
            }
        }
        synchronized(requestLock) {
            if (version == generation.get() && !closed.get()) loadJob = loading else loading.cancel()
        }
        try {
            loading.await()
        } catch (failure: Exception) {
            if (failure is CancellationException) { loading.cancel(); throw failure }
            if (version == generation.get() && !closed.get()) mutableError.value = "弹幕加载失败：${failure.message ?: "network error"}"
        }
    }

    private fun publish(result: DanmakuWindowResult, version: Long) {
        if (version != generation.get() || closed.get()) return
        mutableError.value = result.warning
        mutableCommands.value = result.commands
        mutableFormat.value = result.format
        installDocument(result.document, version)
    }

    /** Also supports locally supplied documents and deterministic offline verification. */
    fun setComments(comments: List<DanmakuComment>) {
        setDocument(DanmakuDocument(comments))
    }

    fun setDocument(document: DanmakuDocument) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear() }
        }
        mutableError.value = null
        mutableCommands.value = emptyList()
        mutableFormat.value = null
        installDocument(document, version)
    }

    private fun installDocument(document: DanmakuDocument, version: Long) {
        SwingUtilities.invokeLater {
            if (!closed.get() && version == generation.get()) {
                mutableCount.value = document.size
                scheduler = DanmakuScheduler(document.comments, settings)
                advancedRenderer = AdvancedDanmakuRenderer(document.advanced)
                measuredWidths.clear()
                panel.repaint()
            }
        }
    }

    private fun tick() {
        if (closed.get()) return
        val surface = player.surface
        if (liveMode) {
            repeat(200) {
                val pending = pendingLive.poll()
                if (pending != null && pending.generation == generation.get()) liveRenderer.add(pending.item)
            }
            liveRenderer.expire(); mutableCount.value = liveRenderer.size
        }
        val currentOwner = SwingUtilities.getWindowAncestor(surface)
        val playerState = player.state.value
        val visible = enabled && surface.isShowing && surface.width > 0 && surface.height > 0 &&
            currentOwner != null && currentOwner.isVisible &&
            (currentOwner !is Frame || currentOwner.extendedState and Frame.ICONIFIED == 0) &&
            playerState.ready && playerState.error == null && !playerState.ended && !playerState.audioOnly && mutableCount.value > 0
        if (!visible) { overlay?.isVisible = false; return }
        if (currentOwner != owner) {
            overlay?.dispose()
            owner = currentOwner
            if (!GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                mutableError.value = "当前显示环境不支持透明弹幕窗口。"
                enabled = false
                return
            }
            overlay = JWindow(currentOwner).apply {
                focusableWindowState = false
                isAutoRequestFocus = false
                background = Color(0, 0, 0, 0)
                rootPane.isOpaque = false
                layeredPane.isOpaque = false
                (contentPane as JComponent).isOpaque = false
                contentPane.add(panel)
            }
        }
        val window = overlay ?: return
        val location = surface.locationOnScreen
        if (window.x != location.x || window.y != location.y || window.width != surface.width || window.height != surface.height)
            window.setBounds(location.x, location.y, surface.width, surface.height)
        if (!window.isVisible) {
            window.isVisible = true
            val hwnd = Native.getWindowPointer(window)
            val style = user32.GetWindowLongW(hwnd, -20)
            user32.SetWindowLongW(hwnd, -20, style or 0x00080000 or 0x00000020 or 0x08000000)
            user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0010)
        }
        val now = System.nanoTime()
        if (playerState.positionSeconds != lastPosition) {
            lastPosition = playerState.positionSeconds
            anchoredPosition = playerState.positionSeconds
            sampleTimeNanos = now
        }
        val offset = if (playerState.paused || playerState.loading) 0.0 else
            ((now - sampleTimeNanos) / 1_000_000_000.0).coerceIn(0.0, 0.3) * playerState.speed.coerceIn(0.1, 8.0)
        displayTime = anchoredPosition + offset
        panel.repaint()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(requestLock) { generation.incrementAndGet(); loadJob?.cancel(); windowJob?.cancel() }
            requests.cancel()
            SwingUtilities.invokeLater { timer.stop(); overlay?.dispose(); overlay = null }
        }
    }

    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(window: Pointer, insertAfter: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
    }

    companion object {
        private val user32 by lazy { Native.load("user32", User32::class.java) }
    }
}
