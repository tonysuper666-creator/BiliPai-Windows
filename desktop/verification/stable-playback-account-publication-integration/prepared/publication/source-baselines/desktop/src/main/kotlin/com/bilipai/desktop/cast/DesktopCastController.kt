package com.bilipai.desktop.cast

import com.android.purebilibili.core.plugin.CastPluginMediaRequest
import com.android.purebilibili.core.plugin.CastPluginRoute
import com.android.purebilibili.feature.cast.LocalProxyServer
import com.android.purebilibili.feature.cast.SsdpCastClient
import com.android.purebilibili.feature.plugin.dlna.DlnaCastPlugin
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The UI and plugin host share one original DLNA plugin and its transport session. */
class DesktopCastController(val context: DesktopPluginContext, val plugin: DlnaCastPlugin) {
    val routes = plugin.routes
    val playbackState = plugin.playbackState
    val isDiscovering = plugin.isDiscovering
    val discoveryError = plugin.discoveryError
    private val operation = Mutex()
    private val _busy = MutableStateFlow(false)
    val isBusy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun interfaces(): List<DesktopCastInterface> = DesktopCastNetwork.interfaces()
    fun selectInterface(network: DesktopCastInterface) {
        check(!playbackState.value.isActive && !_busy.value) { "请先停止当前投屏" }
        stopDiscovery()
        DesktopCastNetwork.select(context, network)
        plugin.onDiscoveryAccessRevoked()
        refreshDiscovery()
    }
    fun startDiscovery() { if (checkNetwork()) plugin.startRouteDiscovery(context) }
    fun refreshDiscovery() { if (checkNetwork()) plugin.refreshRouteDiscovery(context) }
    fun stopDiscovery() = plugin.stopRouteDiscovery()
    private fun checkNetwork(): Boolean {
        _error.value = null
        return try { DesktopCastNetwork.binding(context); true }
        catch (_: Exception) { _error.value = "没有可用于 DLNA 的本地 IPv4 网卡，请连接电视所在的局域网"; false }
    }

    suspend fun cast(route: CastPluginRoute, media: suspend () -> CastPluginMediaRequest?): Result<Unit> = execute {
        val request = media() ?: error("当前媒体没有可投屏的播放地址")
        plugin.cast(context, route, request).getOrThrow()
    }
    suspend fun cast(route: CastPluginRoute, media: CastPluginMediaRequest): Result<Unit> = cast(route) { media }
    suspend fun play(): Result<Unit> = execute { plugin.play().getOrThrow() }
    suspend fun pause(): Result<Unit> = execute { plugin.pause().getOrThrow() }
    suspend fun seek(positionMs: Long): Result<Unit> = execute { plugin.seek(positionMs.coerceAtLeast(0)).getOrThrow() }
    suspend fun stop(): Result<Unit> = execute {
        try { if (playbackState.value.isActive) SsdpCastClient.stop().getOrThrow() }
        finally { SsdpCastClient.clearPlaybackSession(); LocalProxyServer.stopAndClear() }
    }

    /** Explicit lifecycle shutdown cancels local discovery, polling, and proxy transport. */
    suspend fun quiesce() = operation.withLock {
        val preparation = DesktopCastProxySessions.acquirePreparation()
        try { plugin.onDisable(); _error.value = null }
        finally { preparation.close(); LocalProxyServer.stopAndClear() }
    }

    private suspend fun execute(block: suspend () -> Unit): Result<Unit> = operation.withLock {
        val preparation = DesktopCastProxySessions.acquirePreparation()
        _busy.value = true; _error.value = null
        try { block(); Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // Device/HTTP exceptions may contain signed playback URLs; expose only safe categories.
            _error.value = when (failure) {
                is IllegalStateException -> "设备未接受投屏操作，请检查设备状态与媒体格式"
                is IllegalArgumentException -> "投屏媒体或设备信息无效，请刷新设备"
                else -> "投屏操作失败（${failure.javaClass.simpleName}），请检查局域网连接"
            }
            Result.failure(failure)
        } finally { _busy.value = false; preparation.close(); LocalProxyServer.stopAndClear() }
    }
}
