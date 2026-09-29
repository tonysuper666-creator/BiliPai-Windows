package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlaybackSource
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
import java.awt.Window
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A transparent mouse-through native window above mpv's child HWND. */
class DanmakuOverlay(private val player: MpvPlayer) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val requestLock = Any()
    private val pendingCall = AtomicReference<Call?>()
    private val mutableError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableCount = MutableStateFlow(0)
    val commentCount: StateFlow<Int> = mutableCount.asStateFlow()
    @Volatile var enabled: Boolean = true
    private var overlay: JWindow? = null
    private var owner: Window? = null
    private var scheduler = DanmakuScheduler(emptyList())
    private var lastPosition = Double.NaN
    private var sampleTimeNanos = 0L
    private var anchoredPosition = 0.0
    private var displayTime = 0.0
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
                if (measuredWidths.size > 5_000) measuredWidths.clear()
                fun fontSize(comment: DanmakuComment): Int = (comment.size * viewport.scale * 0.9f).toInt().coerceIn(15, 36)
                val rowHeight = (36 * viewport.scale).toInt().coerceIn(26, 40) + 8
                val positioned = scheduler.frame(displayTime, width, height, rowHeight) { comment ->
                    val size = fontSize(comment)
                    measuredWidths.getOrPut(comment.id to size) {
                        getFontMetrics(Font("Microsoft YaHei UI", Font.BOLD, size)).stringWidth(comment.text)
                    }
                }
                positioned.forEach { item ->
                    val font = Font("Microsoft YaHei UI", Font.BOLD, fontSize(item.comment))
                    val shape = font.createGlyphVector(context.fontRenderContext, item.comment.text)
                        .getOutline(item.x.toFloat(), item.baseline.toFloat())
                    context.color = Color(0, 0, 0, 210)
                    context.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    context.draw(shape)
                    context.color = Color(item.comment.color)
                    context.fill(shape)
                }
            } finally { context.dispose() }
        }
    }.apply { isOpaque = false }
    private val timer = Timer(16) { tick() }

    init { SwingUtilities.invokeLater { if (!closed.get()) timer.start() } }

    suspend fun load(cid: Long) {
        require(cid > 0) { "Invalid danmaku content ID." }
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { pendingCall.getAndSet(null)?.cancel() }
        }
        mutableError.value = null
        installComments(emptyList(), version)
        try {
            val data = withContext(Dispatchers.IO) {
                val bytes = fetch(cid, version)
                DanmakuParser.parse(bytes.inputStream())
            }
            if (version == generation.get() && !closed.get()) installComments(data, version)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (version == generation.get() && !closed.get()) mutableError.value = "弹幕加载失败：${failure.message ?: "network error"}"
        }
    }

    /** Also supports locally supplied documents and deterministic offline verification. */
    fun setComments(comments: List<DanmakuComment>) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { pendingCall.getAndSet(null)?.cancel() }
        }
        installComments(comments, version)
    }

    private fun installComments(comments: List<DanmakuComment>, version: Long) {
        SwingUtilities.invokeLater {
            if (!closed.get() && version == generation.get()) {
                mutableCount.value = comments.size
                scheduler = DanmakuScheduler(comments)
                measuredWidths.clear()
                panel.repaint()
            }
        }
    }

    private suspend fun fetch(cid: Long, version: Long): ByteArray = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("https://comment.bilibili.com/$cid.xml")
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", PlaybackSource.DEFAULT_USER_AGENT).build()
        val call = client.newCall(request)
        synchronized(requestLock) {
            if (version != generation.get() || closed.get()) {
                continuation.cancel()
                return@suspendCancellableCoroutine
            }
            pendingCall.set(call)
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pendingCall.compareAndSet(call, null)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        val body = response.body
                        check(body.contentLength() <= DanmakuParser.MAX_DOCUMENT_BYTES) { "Danmaku document is too large." }
                        val bytes = body.byteStream().use { it.readNBytes(DanmakuParser.MAX_DOCUMENT_BYTES + 1) }
                        check(bytes.size <= DanmakuParser.MAX_DOCUMENT_BYTES) { "Danmaku document is too large." }
                        pendingCall.compareAndSet(call, null)
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (failure: Exception) {
                        pendingCall.compareAndSet(call, null)
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            }
        })
    }

    private fun tick() {
        if (closed.get()) return
        val surface = player.surface
        val currentOwner = SwingUtilities.getWindowAncestor(surface)
        val playerState = player.state.value
        val visible = enabled && surface.isShowing && surface.width > 0 && surface.height > 0 &&
            currentOwner != null && currentOwner.isVisible &&
            (currentOwner !is Frame || currentOwner.extendedState and Frame.ICONIFIED == 0) &&
            playerState.ready && playerState.error == null && !playerState.ended && mutableCount.value > 0
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
            ((now - sampleTimeNanos) / 1_000_000_000.0).coerceIn(0.0, 0.3) * playerState.speed.coerceIn(0.25, 4.0)
        displayTime = anchoredPosition + offset
        panel.repaint()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(requestLock) { generation.incrementAndGet(); pendingCall.getAndSet(null)?.cancel() }
            SwingUtilities.invokeLater { timer.stop(); overlay?.dispose(); overlay = null }
        }
    }

    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(window: Pointer, insertAfter: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
    }

    companion object {
        private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        private val user32 by lazy { Native.load("user32", User32::class.java) }
    }
}
