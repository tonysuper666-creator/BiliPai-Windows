package com.bilipai.desktop.cast

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.feature.plugin.googlecast.GoogleCastMediaLoader
import com.android.purebilibili.feature.plugin.googlecast.toCastPluginRoute
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginScopeRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import su.litvak.chromecast.api.v2.DesktopGoogleCastProvider
import su.litvak.chromecast.api.v2.MediaStatus

/** Original CastPluginApi, actual JVM transport; Android GMS is replaced at this boundary. */
class DesktopGoogleCastPlugin : CastPluginApi {
    override val id = "google_cast"
    override val name = "Google Cast"
    override val description = "将视频投屏到 Chromecast / Google Cast 设备"
    override val version = "0.1.1"
    override val author = "Leko (lekoOwO), BiliPai Windows contributors"
    override val discoveryRequirement = CastDiscoveryRequirement.RAW_LOCAL_NETWORK
    override val capabilityManifest = PluginCapabilityManifest(id, name, version, 1, this::class.java.name,
        setOf(PluginCapability.PLAYER_STATE, PluginCapability.PLAYER_CONTROL, PluginCapability.NETWORK, PluginCapability.PLUGIN_STORAGE))

    private var provider = DesktopGoogleCastProvider()
    private var scope = DesktopPluginScopeRegistry.create("google-cast", Dispatchers.IO)
    private val operation = Mutex()
    private var discoveryJob: Job? = null
    private var pollingJob: Job? = null
    private val devices = MutableStateFlow(emptyMap<String, DesktopGoogleCastProvider.Route>())
    private val _routes = MutableStateFlow(emptyList<CastPluginRoute>())
    override val routes = _routes.asStateFlow()
    private val _playback = MutableStateFlow(CastPluginPlaybackState())
    override val playbackState = _playback.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    override val isDiscovering = _discovering.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val isBusy = _busy.asStateFlow()

    override fun startRouteDiscovery(context: DesktopPluginContext) {
        if (discoveryJob?.isActive == true) return
        val previous = discoveryJob
        discoveryJob = scope.launch {
            previous?.join()
            currentCoroutineContext().ensureActive()
            _discovering.value = true; _error.value = null
            try {
                val address = DesktopCastNetwork.binding(context).address
                provider.startDiscovery(address)
                while (isActive) {
                    val discovered = provider.routes().associateBy { it.id() }
                    devices.value = discovered
                    _routes.value = discovered.values.mapNotNull { device ->
                        toCastPluginRoute(device.id(), device.name().orEmpty(), device.model(), 0, false, true)
                    }
                    delay(300)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { _error.value = safeError(failure) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { provider.stopDiscovery() }
                _discovering.value = false
            }
        }
    }
    override fun stopRouteDiscovery() { discoveryJob?.cancel() }
    override fun refreshRouteDiscovery(context: DesktopPluginContext) {
        scope.launch { discoveryJob?.cancelAndJoin(); discoveryJob = null; startRouteDiscovery(context) }
    }
    override suspend fun cast(context: DesktopPluginContext, route: CastPluginRoute, media: CastPluginMediaRequest): Result<Unit> {
        pollingJob?.cancelAndJoin(); pollingJob = null
        val result = execute {
            val device = devices.value[route.routeId] ?: error("Cast route no longer available")
            val metadata = GoogleCastMediaLoader.resolveGoogleCastMediaMetadata(media.title, media.creator)
            val status = provider.cast(device, DesktopGoogleCastProvider.MediaRequest(media.url, metadata.title,
                metadata.subtitle.orEmpty(), media.contentType, media.autoplay, media.startPositionMs.coerceAtLeast(0L)))
            _playback.value = status.toPluginState(route.name, metadata.title)
        }
        if (result.isSuccess) pollingJob = scope.launch {
            while (isActive && _playback.value.isActive) {
                delay(1000)
                if (execute { val old = _playback.value; _playback.value = provider.status().toPluginState(old.deviceLabel, old.title) }.isFailure) {
                    _playback.value = CastPluginPlaybackState(); break
                }
            }
        }
        return result
    }
    override suspend fun play(): Result<Unit> = execute { provider.play(); refreshPlayback() }
    override suspend fun pause(): Result<Unit> = execute { provider.pause(); refreshPlayback() }
    override suspend fun seek(positionMs: Long): Result<Unit> = execute { provider.seek(positionMs.coerceAtLeast(0)); refreshPlayback() }
    suspend fun stop(): Result<Unit> {
        pollingJob?.cancelAndJoin(); pollingJob = null
        return execute { try { if (_playback.value.isActive) provider.stop() } finally { _playback.value = CastPluginPlaybackState() } }
    }
    override suspend fun onEnable() {
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = DesktopPluginScopeRegistry.create("google-cast", Dispatchers.IO); provider = DesktopGoogleCastProvider()
        }
    }
    override suspend fun onDisable() {
        provider.cancelPendingOperations()
        withContext(NonCancellable) {
            discoveryJob?.cancelAndJoin(); discoveryJob = null
            pollingJob?.cancelAndJoin(); pollingJob = null
            scope.coroutineContext[Job]?.cancelAndJoin()
            operation.withLock { withContext(Dispatchers.IO) { provider.close() } }
            _playback.value = CastPluginPlaybackState(); _discovering.value = false
            devices.value = emptyMap(); _routes.value = emptyList()
        }
    }
    private fun refreshPlayback() { val old = _playback.value; _playback.value = provider.status().toPluginState(old.deviceLabel, old.title) }
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun execute(block: () -> Unit): Result<Unit> = operation.withLock {
        _busy.value = true; _error.value = null
        try { withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            val cancel = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                if (it is CancellationException) provider.cancelPendingOperations()
            }
            try { currentCoroutineContext().ensureActive(); block(); currentCoroutineContext().ensureActive(); Result.success(Unit) }
            finally { cancel?.dispose() }
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { _error.value = safeError(failure); Result.failure(failure) }
        finally { _busy.value = false }
    }
    private fun MediaStatus.toPluginState(device: String, title: String): CastPluginPlaybackState = CastPluginPlaybackState(
        isActive = mediaSessionId > 0 && playerState != MediaStatus.PlayerState.IDLE,
        deviceLabel = device, title = title, isPlaying = playerState == MediaStatus.PlayerState.PLAYING,
        isBuffering = playerState in setOf(MediaStatus.PlayerState.BUFFERING, MediaStatus.PlayerState.LOADING),
        currentPositionMs = (currentTime.coerceAtLeast(0.0)*1000).toLong(), durationMs = ((media?.duration ?: 0.0)*1000).toLong(), canSeek = true)
    private fun safeError(error: Exception) = when (error) {
        is java.security.GeneralSecurityException -> "设备身份验证失败，请确认选择了正确的 Google Cast 设备"
        else -> "Google Cast 操作失败（${error.javaClass.simpleName}），请检查局域网连接与媒体格式"
    }
}
