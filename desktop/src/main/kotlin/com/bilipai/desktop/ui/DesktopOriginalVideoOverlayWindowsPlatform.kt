package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.feature.cast.LocalProxyServer
import com.android.purebilibili.feature.video.share.VideoSharePayload
import com.android.purebilibili.feature.video.ui.overlay.CastMediaResolution
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.cast.*
import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.diagnostics.sanitizeDesktopDiagnosticText
import com.bilipai.desktop.player.DesktopPlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.*
import java.awt.DisplayMode
import java.awt.EventQueue
import java.awt.Window
import java.net.NetworkInterface
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A captured reference to the SAME accepted native source and Repository
 * publication. Root captures raw Success aid/current resolved CID together with
 * that publication. owns reads only the existing entry/epoch/published reference;
 * it MUST NOT enter Store or MPV while the publication admission is held. */
internal class DesktopOriginalVideoOverlayCastSource(
    val aid: Long,
    val cid: Long,
    val source: PlaybackSource,
    val publication: DesktopPlaybackPublication,
    private val owns: () -> Boolean,
) {
    init { require(aid > 0 && cid > 0) }
    fun isCurrent() = owns() && publication.isCurrent(source)
    fun check() { if (!isCurrent()) throw CancellationException("Original overlay cast source retired") }
    override fun toString() = "DesktopOriginalVideoOverlayCastSource(aid=$aid,cid=$cid)"
}

/** These two views borrow the existing cast actors. The operation context is
 * lexical, not a latest-credential store or another discovery/native session. */
private class OriginalOverlayCastOperation(
    val owner: DesktopOriginalVideoOverlayWindowsPlatform,
    val captured: DesktopOriginalVideoOverlayCastSource,
    val caller: Job,
) : ThreadContextElement<OriginalOverlayCastOperation?>,
    AbstractCoroutineContextElement(Key) {
    val producedUrls = mutableSetOf<String>()
    companion object Key : CoroutineContext.Key<OriginalOverlayCastOperation> {
        val current = ThreadLocal<OriginalOverlayCastOperation?>()
    }
    override fun updateThreadContext(context: CoroutineContext): OriginalOverlayCastOperation? =
        current.get().also { current.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: OriginalOverlayCastOperation?) {
        if (oldState == null) current.remove() else current.set(oldState)
    }
    fun check() { caller.ensureActive(); owner.requireOwned(); captured.check() }
}

/** A capability hold in the EXISTING CastProxySessions lifecycle. Completion of
 * a resolver is distinct from cancellation; only the latter closes its hold.
 * This is bounded by the overlay's eight pending resolutions, not another actor. */
internal class DesktopOriginalVideoOverlayResolutionLease(
    val source: DesktopOriginalVideoOverlayCastSource,
    private val caller: Job,
    private val preparation: AutoCloseable,
) : AutoCloseable {
    @OptIn(InternalCoroutinesApi::class)
    private val cancellation = caller.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
        if (it != null) preparation.close()
    }
    fun check() {
        if (caller.isCancelled) throw CancellationException("Original cast resolver cancelled")
        source.check()
    }
    override fun close() { cancellation.dispose(); preparation.close() }
}

/** Concrete Windows effects for the complete ORIGINAL overlay. Root supplies
 * existing actors; this view creates no player/client/store/cache/discovery actor.
 * Windows uses the two existing native-protocol selectors, with their full bodies,
 * instead of Android's permission/package based DeviceListDialog renderer. */
internal class DesktopOriginalVideoOverlayWindowsPlatform(
    private val runtime: DesktopPluginRuntime,
    private val window: Window,
    private val entryScope: CoroutineScope,
    private val owns: () -> Boolean,
    private val commitEntry: ((() -> Unit) -> Boolean),
    private val captureRequest: suspend () -> DesktopOriginalVideoRepositoryBinding,
    private val captureCastSource: () -> DesktopOriginalVideoOverlayCastSource,
    private val cast: DesktopCastController,
    private val homeOverlays: DesktopHomeOverlayPorts,
    private val clipboard: DesktopTextClipboard,
    private val diagnostics: DesktopDiagnostics?,
    private val actualFeedback: (String) -> Unit,
) : DesktopOriginalVideoOverlayPlatform, AutoCloseable {
    private val closed = AtomicBoolean()
    private val context: DesktopPluginContext get() = runtime.context
    private val resolved = LinkedHashMap<String, DesktopOriginalVideoOverlayResolutionLease>()
    private val resolutionsLock = Any()
    private val dlna = OriginalCastPlugin(cast.plugin)
    private val google = OriginalCastPlugin(runtime.googleCast)
    @OptIn(InternalCoroutinesApi::class)
    private val retirement = checkNotNull(entryScope.coroutineContext[Job])
        .invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) close() }
    init { require(cast.context === runtime.context && cast.plugin === runtime.dlnaCast) }

    internal fun requireOwned() {
        if (closed.get() || entryScope.coroutineContext[Job]?.isActive != true || !owns())
            throw CancellationException("Original overlay entry retired")
    }
    private fun commit(action: () -> Unit) {
        requireOwned()
        if (!commitEntry { requireOwned(); action() })
            throw CancellationException("Original overlay publication retired")
    }
    private fun operation(): OriginalOverlayCastOperation =
        checkNotNull(OriginalOverlayCastOperation.current.get()) { "Captured original cast-source scope is required" }
            .also { require(it.owner === this); it.check() }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun withCastSource(aid: Long, cid: Long,
        action: suspend () -> CastMediaResolution?): CastMediaResolution? {
        currentCoroutineContext().ensureActive(); requireOwned()
        val captured = captureCastSource()
        require(captured.aid == aid && captured.cid == cid) { "Original cast source does not match the resolved subject" }
        captured.check()
        val caller = checkNotNull(currentCoroutineContext()[Job])
        val op = OriginalOverlayCastOperation(this, captured, caller)
        // Normal resolver completion must not retire an accepted LAN read entry.
        // Cancellation still rejects IO through the existing frame's caller hook.
        val frame = DesktopCastPublicationFrame(captured.publication, captured.source,
            { !closed.get() && !caller.isCancelled && owns() && captured.isCurrent() }, caller)
        val preparation = DesktopCastProxySessions.acquirePreparation()
        var transferred = false
        try {
            return withContext(op + frame) {
                op.check()
                val original = action() ?: return@withContext null
                op.check()
                // TV progressive URLs also need the actual final source headers
                // and an opaque source capability, rather than an unguarded URL.
                val url = if (original.url in op.producedUrls) original.url
                    else castProxyUrl(original.url)
                op.check()
                val capability = DesktopOriginalVideoOverlayResolutionLease(captured, caller, preparation)
                val evicted = mutableListOf<DesktopOriginalVideoOverlayResolutionLease>()
                synchronized(resolutionsLock) {
                    if (closed.get()) { capability.close(); throw CancellationException("Original cast entry closed") }
                    while (resolved.size >= 8) resolved.remove(resolved.keys.first())?.let(evicted::add)
                    resolved.put(url, capability)?.let(evicted::add)
                    transferred = true
                }
                evicted.forEach { it.close() }
                original.copy(url = url)
            }
        } finally { if (!transferred) preparation.close() }
    }

    override suspend fun getTvCastPlayData(aid: Long, cid: Long, qn: Int): PlayUrlData? {
        val op = operation()
        require(op.captured.aid == aid && op.captured.cid == cid)
        val request = captureRequest()
        request.assertCurrent(); op.check()
        require(request.receipt == op.captured.source.authorizationReceipt) { "Original cast authorization differs from native source" }
        // Full original selected TV parameter/signing/request/error body is appended
        // to the existing sole protocol; its captured playback API remains unique.
        return request.protocol.getTvCastPlayData(aid, cid, qn).also { request.assertCurrent(); op.check() }
    }
    override fun castProxyUrl(url: String): String {
        val op = operation()
        LocalProxyServer.ensureStarted()
        return LocalProxyServer.getProxyUrl(context, requirePlayableCastUrl(url), desktopCastStreamHeaders(op.captured.source))
            .also { op.check(); op.producedUrls.add(it) }
    }
    override fun registerCastDashManifest(manifest: String): String {
        val op = operation()
        return LocalProxyServer.registerDashManifest(context, manifest).also { op.check(); op.producedUrls.add(it) }
    }
    override fun ensureCastProxyStarted() { operation(); LocalProxyServer.ensureStarted(); operation() }
    override val dashContentType: String get() = LocalProxyServer.DASH_CONTENT_TYPE

    private inner class OriginalCastPlugin(private val actor: CastPluginApi) : CastPluginApi by actor {
        private val last = AtomicReference<DesktopOriginalVideoOverlayCastSource?>()
        private suspend fun <T> onSource(source: DesktopOriginalVideoOverlayCastSource, action: suspend () -> T): T {
            currentCoroutineContext().ensureActive(); requireOwned(); source.check()
            val caller = checkNotNull(currentCoroutineContext()[Job])
            val frame = DesktopCastPublicationFrame(source.publication, source.source,
                { !closed.get() && !caller.isCancelled && owns() && source.isCurrent() }, caller)
            return withContext(frame) { source.check(); action().also { source.check(); requireOwned() } }
        }
        override suspend fun cast(context: DesktopPluginContext, route: CastPluginRoute,
            media: CastPluginMediaRequest): Result<Unit> {
            require(context === runtime.context)
            val capability = synchronized(resolutionsLock) { resolved.remove(media.url) }
                ?: throw CancellationException("Original cast resolution expired")
            val preparation = DesktopCastProxySessions.acquirePreparation()
            try {
                val source = capability.source
                capability.check()
                return onSource(source) {
                    val result = if (actor === runtime.dlnaCast) this@DesktopOriginalVideoOverlayWindowsPlatform.cast.cast(route, media)
                        else actor.cast(context, route, media)
                    if (result.isSuccess) last.set(source)
                    result
                }
            } finally { capability.close(); preparation.close(); LocalProxyServer.stopAndClear() }
        }
        private suspend fun control(action: suspend () -> Result<Unit>): Result<Unit> =
            onSource(last.get() ?: throw CancellationException("Original cast is not active"), action)
        override suspend fun play(): Result<Unit> = control {
            if (actor === runtime.dlnaCast) this@DesktopOriginalVideoOverlayWindowsPlatform.cast.play() else actor.play()
        }
        override suspend fun pause(): Result<Unit> = control {
            if (actor === runtime.dlnaCast) this@DesktopOriginalVideoOverlayWindowsPlatform.cast.pause() else actor.pause()
        }
        override suspend fun seek(positionMs: Long): Result<Unit> = control {
            if (actor === runtime.dlnaCast) this@DesktopOriginalVideoOverlayWindowsPlatform.cast.seek(positionMs) else actor.seek(positionMs)
        }
    }

    override fun networkTypeLabel(): String {
        requireOwned()
        return try {
            val active = Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            if (active.isEmpty()) "未连接"
            else if (active.any { (it.name + " " + it.displayName).lowercase().let { name ->
                listOf("wi-fi", "wireless", "wlan").any(name::contains) } }) "Wi-Fi"
            else "Windows 网络"
        } catch (_: Exception) { "Windows 网络状态不可用" }
    }
    override fun readPanelRefreshRate(): Float? {
        requireOwned()
        return window.graphicsConfiguration?.device?.displayMode?.refreshRate
            ?.takeIf { it != DisplayMode.REFRESH_RATE_UNKNOWN && it > 0 }?.toFloat()
    }
    override fun copyText(label: String, text: String): Boolean {
        requireOwned()
        // The original click calls this outside Store admission. The existing
        // clipboard actor handles its own Swing dispatch and reports real acceptance.
        val safe = if (label == "BiliPai Player Diagnostics") sanitizeDesktopDiagnosticText(text) else text
        val copied = clipboard.copyText(safe)
        requireOwned()
        if (!copied) feedback("无法写入系统剪贴板，请稍后重试")
        return copied
    }
    override fun feedback(message: String) = commit { actualFeedback(message) }
    override fun exportPlayerDiagnostic(content: String): String? {
        requireOwned()
        return diagnostics?.exportOriginalPlayerReport(content,
            { owns() && entryScope.coroutineContext[Job]?.isActive == true }, commitEntry)
    }

    @Composable override fun CastDevices(onDismissRequest: () -> Unit,
        onPluginCastDeviceSelected: (CastPluginApi, CastPluginRoute) -> Unit) {
        requireOwned()
        val info by runtime.plugins.collectAsState()
        var protocol by remember(this) { mutableStateOf<String?>(null) }
        var enabling by remember(this) { mutableStateOf(false) }
        val latestDismiss by rememberUpdatedState(onDismissRequest)
        val latestSelected by rememberUpdatedState(onPluginCastDeviceSelected)
        val selected: (CastPluginApi, CastPluginRoute) -> Unit = { plugin, route ->
            commit { latestSelected(if (plugin === runtime.dlnaCast) dlna else if (plugin === runtime.googleCast) google
                else error("Unsupported Windows cast actor"), route) }
        }
        when (protocol) {
            runtime.dlnaCast.id -> DesktopCastDialog(cast, selected) { commit { latestDismiss() } }
            runtime.googleCast.id -> DesktopGoogleCastDialog(context, runtime.googleCast, selected) { commit { latestDismiss() } }
            else -> AlertDialog(onDismissRequest = { commit { latestDismiss() } }, title = { Text("选择投屏协议") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(runtime.dlnaCast, runtime.googleCast).forEach { plugin ->
                        val enabled = info.any { it.plugin === plugin && it.enabled }
                        OutlinedButton(enabled = !enabling && !plugin.unavailable, modifier = Modifier.fillMaxWidth(), onClick = {
                            entryScope.launch {
                                requireOwned(); enabling = true
                                try {
                                    if (!enabled) runtime.setEnabled(plugin.id, true, owns)
                                    currentCoroutineContext().ensureActive(); requireOwned()
                                    check(runtime.plugins.value.any { it.plugin === plugin && it.enabled }) { "投屏插件未启用" }
                                    commit { protocol = plugin.id }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { if (owns()) feedback("投屏插件启用失败，请检查插件设置") }
                                finally { if (owns()) enabling = false }
                            }
                        }) { Text(if (enabled) plugin.name else "启用 ${plugin.name}") }
                    }
                } }, confirmButton = { TextButton(onClick = { commit { latestDismiss() } }) { Text("关闭") } })
        }
    }
    @Composable override fun Share(payload: VideoSharePayload, onDismiss: () -> Unit) {
        requireOwned()
        homeOverlays.VideoShareSheetHost(payload) { commit(onDismiss) }
    }
    /** Root retires this view before entry/global teardown. Shared cast actors
     * remain under Root's existing lifecycle; only unresolved capability holds
     * are released here, outside the map lock. No native/cast teardown is added. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val pending = synchronized(resolutionsLock) { resolved.values.toList().also { resolved.clear() } }
        pending.forEach { it.close() }
    }
}
