package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.core.plugin.DanmakuStyle
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
import kotlinx.coroutines.ensureActive
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
class DanmakuOverlay internal constructor(
    private val player: MpvPlayer,
    private val renderPlatform:DesktopOriginalDanmakuRenderPlatform,
    httpClient: OkHttpClient = ApiDesktopDanmakuSource.publicClient(),
    private val source: DesktopDanmakuSource = ApiDesktopDanmakuSource(httpClient),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val documentRevision = AtomicLong()
    private val requestLock = Any()
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var windowJob: Job? = null
    private var pluginJob: Job? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableCount = MutableStateFlow(0)
    val commentCount: StateFlow<Int> = mutableCount.asStateFlow()
    private val mutableCommands = MutableStateFlow(emptyList<CommandDanmakuItem>())
    val commandItems: StateFlow<List<CommandDanmakuItem>> = mutableCommands.asStateFlow()
    // This is transport ownership only; the command list remains the sole list.
    private var commandCid: Long? = null
    private var poolSourceVersion: Long? = null
    private val mutablePoolSourceRevision = MutableStateFlow(0L)
    val poolSourceRevision: StateFlow<Long> = mutablePoolSourceRevision.asStateFlow()
    /** Derived view of the sole raw document, with no second item/cache authority. */
    fun poolSourceFor(cid: Long, sourceVersion: Long): DanmakuPoolSourceSnapshot? = synchronized(requestLock) {
        if (closed.get() || liveMode || cid <= 0L || commandCid != cid || poolSourceVersion != sourceVersion ||
            !player.ownsSourceVersion(sourceVersion)) null
        else DanmakuPoolSourceSnapshot(cid, sourceVersion, documentRevision.get(), rawDocument.comments)
    }
    fun commandItemsFor(cid: Long): List<CommandDanmakuItem> = synchronized(requestLock) {
        if (!closed.get() && cid > 0 && commandCid == cid) mutableCommands.value else emptyList()
    }
    private val mutableFormat = MutableStateFlow<DanmakuFormat?>(null)
    val format: StateFlow<DanmakuFormat?> = mutableFormat.asStateFlow()
    @Volatile private var settings = DanmakuSettings()
    @Volatile private var eyeTint = DesktopEyeTint()
    @Volatile private var pluginProcessor: DanmakuPluginProcessor? = null
    @Volatile private var rawDocument = DanmakuDocument()
    private var styles = emptyMap<Int, DanmakuStyle>()
    var enabled: Boolean
        get() = settings.enabled
        set(value) { applySettings(settings.copy(enabled = value)) }
    val currentSettings: DanmakuSettings get() = settings
    internal fun maximumDisplayShortSidePx():Float=renderPlatform.maximumDisplayShortSidePx()
    private var overlay: JWindow? = null
    private var owner: Window? = null
    private var ownerWasActive = false
    private var scheduler = DanmakuScheduler(emptyList(), settings,liveAdmission=false)
    private var advancedRenderer = AdvancedDanmakuRenderer(emptyList())
    private var lastPosition = Double.NaN
    private var sampleTimeNanos = 0L
    private var anchoredPosition = 0.0
    private var displayTime = 0.0
    @Volatile private var liveMode = false
    private val liveRenderer = LiveDanmakuRenderer(requests)
    private data class PendingLive(val generation: Long, val item: LiveDanmakuItem)
    private val pendingLive = ArrayBlockingQueue<PendingLive>(600)
    private val measuredWidths = mutableMapOf<Pair<Int,Font>, Int>()
    private data class ConfigKey(val settings:DanmakuSettings,val viewport:DanmakuViewport,val font:Font,val live:Boolean)
    private var resolvedConfig:Pair<ConfigKey,DanmakuRenderConfig>?=null
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
                val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform,renderPlatform.maximumDisplayShortSidePx()) ?: return
                val viewport=geometry.viewport
                val configuration = settings
                if (configuration.enabled) {
                  context.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, configuration.opacity)
                  if (liveMode) {
                    // Uses the actual Root-monitor geometry resolved once above.
                    val key=ConfigKey(configuration,geometry.viewport,renderPlatform.resolveTypeface(configuration.fontWeight),true)
                    val bandHeight=(geometry.viewport.heightPx*configuration.displayAreaRatio).toInt().coerceAtLeast(1)
                    val config=resolvedConfig?.takeIf { it.first==key }?.second ?: com.android.purebilibili.feature.video.danmaku.resolveDesktopOriginalLiveDanmakuRenderConfig(configuration,geometry.viewport.widthPx,bandHeight,configuration.displayAreaRatio,renderPlatform).also { resolvedConfig=key to it }
                    val physical=context.create() as Graphics2D
                    try {geometry.configurePhysicalPixels(physical);physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f);liveRenderer.paint(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,bandHeight,config,configuration)}
                    finally {physical.dispose()}
                  } else {
                // Uses the actual Root-monitor geometry resolved once above.
                val key=ConfigKey(configuration,geometry.viewport,renderPlatform.resolveTypeface(configuration.fontWeight),false)
                val config=resolvedConfig?.takeIf { it.first==key }?.second ?: configuration.originalConfig(renderPlatform).resolveRenderConfig(geometry.viewport).also { resolvedConfig=key to it }
                val physical=context.create() as Graphics2D
                try {
                    geometry.configurePhysicalPixels(physical)
                    physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f)
                    if (measuredWidths.size > 5_000) measuredWidths.clear()
                    fun font(comment:DanmakuComment):Font {
                        val style=styles[comment.id]
                        return desktopDanmakuFont(config,comment,style?.scale ?: 1f,style?.bold==true)
                    }
                    val positioned = scheduler.frame(displayTime, geometry.viewport.widthPx, geometry.viewport.heightPx, config) { comment ->
                        val font=font(comment)
                        val metrics=physical.getFontMetrics(font)
                        val width=measuredWidths.getOrPut(comment.id to font) {metrics.stringWidth(comment.text)}
                        DesktopDanmakuTextMetrics(width,metrics.ascent.toDouble())
                    }
                    positioned.forEach { item ->
                        val style = styles[item.comment.id]
                        val font = font(item.comment)
                        pluginAwtColor(style?.backgroundColor)?.let { color ->
                            val metrics = physical.getFontMetrics(font)
                            physical.color = color
                            physical.fillRoundRect(item.x.toInt() - 4, item.baseline.toInt() - metrics.ascent - 2,
                                item.textWidth + 8, metrics.height + 4, 6, 6)
                        }
                        val shape = font.createGlyphVector(physical.fontRenderContext, item.comment.text)
                            .getOutline(item.x.toFloat(), item.baseline.toFloat())
                        if (config.strokeWidthPx > 0f) {
                            physical.color = pluginAwtColor(style?.borderColor) ?: Color(config.strokeColor,true)
                            physical.stroke = BasicStroke(config.strokeWidthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            physical.draw(shape)
                        }
                        if (item.comment.isVipGradualColor && style?.textColor == null) {
                            val x = item.x.toFloat()
                            physical.paint = LinearGradientPaint(x, 0f, x + item.textWidth.coerceAtLeast(1), 0f,
                                floatArrayOf(0f, 0.5f, 1f), arrayOf(Color(0xff7cba), Color(0xa798ff), Color(0x70d6ff)))
                        } else physical.color = pluginAwtColor(style?.textColor) ?: Color(item.comment.color)
                        physical.fill(shape)
                    }
                } finally {physical.dispose()}
                advancedRenderer.paint(context, (displayTime * 1000).toLong(), width, height, viewport.scale, configuration)
                  }
                }
                eyeTint.paint(context, width, height)
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

    fun setEyeProtection(dimAlpha: Float, warmAlpha: Float, warmArgb: Int = 0xffffc07a.toInt()) {
        eyeTint = DesktopEyeTint(dimAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            warmAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f, warmArgb)
        SwingUtilities.invokeLater { if (!closed.get()) panel.repaint() }
    }

    fun setPluginDanmakuProcessor(processor: DanmakuPluginProcessor?) {
        val vod = synchronized(requestLock) {
            pluginProcessor = processor
            if (!liveMode) rawDocument to generation.get() else null
        }
        vod?.let { (document, version) -> installDocument(document, version) }
        SwingUtilities.invokeLater { if (!closed.get()) { liveRenderer.setProcessor(processor); panel.repaint() } }
    }

    suspend fun load(cid: Long, aid: Long = 0L, durationSeconds: Double = 0.0, expectedSourceVersion: Long? = null) {
        require(cid > 0) { "Invalid danmaku content ID." }
        require(aid >= 0 && durationSeconds.isFinite() && durationSeconds >= 0)
        loadSource(source, cid, aid, durationSeconds, expectedSourceVersion)
    }

    /** Decodes the downloaded upstream protobuf assets through the same window policy, without HTTP. */
    suspend fun loadOffline(standardSegments: List<Path>, specialSegments: List<Path> = emptyList(), durationSeconds: Double = 0.0) {
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, null)
    }

    /** Switches from VOD segments to genuine realtime events received by the upstream live client. */
    fun enterLive(): Long {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = true; pendingLive.clear() }
                .also { commandCid = null; mutableCommands.value = emptyList() }
        }
        mutableError.value = null; mutableFormat.value = null
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

    private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear()
                // Clear the old document before publishing a new CID/version identity.
                rawDocument = DanmakuDocument()
                poolSourceVersion = expectedSourceVersion
                commandCid = cid; mutableCommands.value = emptyList()
                mutableError.value = null; mutableFormat.value = null
            }
        }
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
        synchronized(requestLock) {
            if (version != generation.get() || closed.get()) return
            mutableError.value = result.warning
            mutableCommands.value = result.commands
            mutableFormat.value = result.format
            installDocument(result.document, version)
        }
    }

    /** Also supports locally supplied documents and deterministic offline verification. */
    fun setComments(comments: List<DanmakuComment>) {
        setDocument(DanmakuDocument(comments))
    }

    fun setDocument(document: DanmakuDocument) {
        val version = synchronized(requestLock) {
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel(); liveMode = false; pendingLive.clear()
                commandCid = null; mutableCommands.value = emptyList()
            }
        }
        mutableError.value = null
        mutableFormat.value = null
        installDocument(document, version)
    }

    private fun installDocument(document: DanmakuDocument, version: Long) {
        val (revision, processor) = synchronized(requestLock) {
            if (version != generation.get() || closed.get()) return
            pluginJob?.cancel()
            rawDocument = document
            documentRevision.incrementAndGet().also { mutablePoolSourceRevision.value = it } to pluginProcessor
        }
        fun install(processed: DanmakuDocument, nextStyles: Map<Int, DanmakuStyle>) = SwingUtilities.invokeLater {
            if (!closed.get() && version == generation.get() && revision == documentRevision.get()) {
                mutableCount.value = processed.size
                styles = nextStyles
                scheduler = DanmakuScheduler(processed.comments, settings,liveAdmission=false)
                advancedRenderer = AdvancedDanmakuRenderer(processed.advanced)
                measuredWidths.clear()
                panel.repaint()
            }
        }
        if (processor == null) install(document, emptyMap())
        else {
          val processing = requests.launch(Dispatchers.Default) {
            val next = mutableListOf<DanmakuComment>()
            val nextStyles = mutableMapOf<Int, DanmakuStyle>()
            document.comments.forEachIndexed { index, comment ->
                if (index % 128 == 0) ensureActive()
                applyDesktopDanmakuPlugin(comment, processor)?.let { transformed ->
                    next += transformed.comment
                    transformed.style?.let { nextStyles[transformed.comment.id] = it }
                }
            }
            install(document.copy(comments = next), nextStyles)
          }
          synchronized(requestLock) {
              if (version == generation.get() && revision == documentRevision.get() && !closed.get()) pluginJob = processing
              else processing.cancel()
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
        val visible = ((enabled && mutableCount.value > 0) || eyeTint.visible) && surface.isShowing && surface.width > 0 && surface.height > 0 &&
            currentOwner != null && currentOwner.isVisible &&
            (currentOwner !is Frame || currentOwner.extendedState and Frame.ICONIFIED == 0) &&
            playerState.ready && playerState.firstVideoFrameReady && playerState.videoCodec != null && playerState.error == null && !playerState.ended && !playerState.audioOnly
        if (!visible) { overlay?.isVisible = false; ownerWasActive = false; return }
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
        val showing = !window.isVisible
        if (window.isAlwaysOnTop != currentOwner.isAlwaysOnTop) window.isAlwaysOnTop = currentOwner.isAlwaysOnTop
        if (showing) window.isVisible = true
        val hwnd = Native.getWindowPointer(window)
        val style = user32.GetWindowLongW(hwnd, -20)
        if (showing) user32.SetWindowLongW(hwnd, -20, style or 0x00080000 or 0x00000020 or 0x08000000)
        val nativeTopmost = style and 0x00000008 != 0
        if (showing || (currentOwner.isActive && !ownerWasActive) || nativeTopmost != currentOwner.isAlwaysOnTop) {
            // Restore only this owned popup after a host activation/reattachment. Do not keep
            // raising a background application or cover newer dialogs/menus on every repaint.
            val band = if (currentOwner.isAlwaysOnTop) Pointer.createConstant(-1L) else Pointer.createConstant(if (nativeTopmost) -2L else 0L)
            user32.SetWindowPos(hwnd, band, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0010 or 0x0200)
        }
        ownerWasActive = currentOwner.isActive
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

    /** Fixture diagnostics describe geometry and native state, never user comments or source credentials. */
    internal fun nativeRenderSnapshot(): String {
        check(SwingUtilities.isEventDispatchThread())
        val surface = player.surface
        val window = overlay
        val ancestor = SwingUtilities.getWindowAncestor(surface)
        val playerState = player.state.value
        val native = window?.takeIf { it.isDisplayable }?.let { nativeWindow ->
            runCatching { "nativeExtendedStyle=0x${user32.GetWindowLongW(Native.getWindowPointer(nativeWindow), -20).toString(16)}" }.getOrElse { "nativeExtendedStyle=unavailable" }
        } ?: "nativeWindow=absent"
        return "surface=${surface.bounds}, showing=${surface.isShowing}, ancestor=${ancestor?.javaClass?.simpleName}, ancestorVisible=${ancestor?.isVisible}, ancestorFrameState=${(ancestor as? Frame)?.extendedState}, " +
            "owner=${owner?.javaClass?.simpleName}, ownerActive=${owner?.isActive}, ownerTopmost=${owner?.isAlwaysOnTop}, timerRunning=${timer.isRunning}, enabled=$enabled, " +
            "overlay=${window?.bounds}, showing=${window?.isShowing}, overlayTopmost=${window?.isAlwaysOnTop}, panel=${panel.bounds}, comments=${mutableCount.value}, " +
            "mediaTime=$displayTime, ready=${playerState.ready}, firstFrame=${playerState.firstVideoFrameReady}, nativePaused=${playerState.nativePaused}, " +
            "videoCodecPresent=${playerState.videoCodec != null}, ended=${playerState.ended}, audioOnly=${playerState.audioOnly}, errorPresent=${playerState.error != null}, $native"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(requestLock) { generation.incrementAndGet(); loadJob?.cancel(); windowJob?.cancel(); pluginJob?.cancel() }
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

/** Immutable projection metadata; comments is the sole raw-document list reference. */
data class DanmakuPoolSourceSnapshot(val cid:Long,val sourceVersion:Long,val revision:Long,val comments:List<DanmakuComment>)
