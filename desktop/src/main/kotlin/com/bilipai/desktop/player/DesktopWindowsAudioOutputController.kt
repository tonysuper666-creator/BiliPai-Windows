package com.bilipai.desktop.player

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

/** Same application Store and actual actors; no profile, account data, second native core or preference file. */
internal class DesktopWindowsAudioOutputController(private val store: DesktopPluginStore,
    scope: CoroutineScope, private val videoPlayer: MpvPlayer?, private val listeningPlayer: MpvPlayer?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val binding = Any()
    private val players = listOfNotNull(videoPlayer, listeningPlayer).distinct()
    private val childJob = SupervisorJob(scope.coroutineContext[Job])
    private val childScope = CoroutineScope(scope.coroutineContext + childJob)
    private val snapshots = store.snapshot(NAMESPACE)
    private val initialSnapshot = snapshots.value
    private val initial = decodeConfiguration(initialSnapshot)
    private val configuration = snapshots.map(::decodeConfiguration)
        .stateIn(childScope, SharingStarted.Eagerly, initial)
    val preferences: StateFlow<DesktopWindowsAudioOutputPreferences> = configuration.map { it.preferences }
        .stateIn(childScope, SharingStarted.Eagerly, initial.preferences)
    val configurationError: StateFlow<String?> = configuration.map { it.error }
        .stateIn(childScope, SharingStarted.Eagerly, initial.error)
    private val unavailable = MutableStateFlow(DesktopWindowsAudioOutputStatus(phase = DesktopWindowsAudioOutputPhase.UNAVAILABLE,
        error = "当前原生播放器不可用"))
    val video: StateFlow<DesktopWindowsAudioOutputStatus> = videoPlayer?.windowsAudioOutput ?: unavailable.asStateFlow()
    val listening: StateFlow<DesktopWindowsAudioOutputStatus> = listeningPlayer?.windowsAudioOutput ?: unavailable.asStateFlow()
    private val mutableDevices = MutableStateFlow(DesktopWindowsAudioDeviceListState())
    val devices: StateFlow<DesktopWindowsAudioDeviceListState> = mutableDevices.asStateFlow()
    private val refresh = kotlinx.coroutines.sync.Mutex()
    init {
        // Set only future-load intent before initial deep-link/source effects can start; this does not open an AO.
        players.forEach {
            it.bindWindowsAudioOutputOwner(binding)
            it.requestWindowsAudioOutputPreferences(binding, initial.preferences) { !closed.get() && snapshots.value === initialSnapshot }
        }
        childScope.launch { snapshots.collect { snapshot ->
            val value = decodeConfiguration(snapshot)
            if (!closed.get()) {
                // Atomic StateFlow identity read only, under the existing MPV lock; never acquire Store here.
                // An old emission cannot overwrite a newer canonical publication, including a same-value ABA edit.
                players.forEach { it.requestWindowsAudioOutputPreferences(binding, value.preferences) { !closed.get() && snapshots.value === snapshot } }
            }
        } }
    }
    suspend fun updateExclusive(context: DesktopOriginalPlayerSettingsContext, enabled: Boolean) = edit(context, "exclusive", JsonPrimitive(enabled))
    suspend fun selectDevice(context: DesktopOriginalPlayerSettingsContext, deviceId: String) {
        DesktopWindowsAudioOutputPreferences(deviceId = deviceId).requireValid()
        edit(context, "device_id", JsonPrimitive(deviceId))
    }
    private suspend fun edit(context: DesktopOriginalPlayerSettingsContext, key: String, value: JsonElement) = withContext(Dispatchers.IO) {
        require(context.pluginContext.store === store) { "音频输出设置必须使用当前 Root 的同一 Store" }
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); check(!closed.get()); context.requireCurrent() }
        store.updateOriginalFromSnapshot(NAMESPACE, ::checkRequest, { context.preferenceWritePermit(::checkRequest) }) { snapshot ->
            val current = decodeConfiguration(snapshot)
            val edits = if (current.error == null) mapOf(key to value) else mapOf(
                "exclusive" to JsonPrimitive(false), "device_id" to JsonPrimitive("auto"), key to value)
            Unit to edits
        }
        checkRequest()
        val committedSnapshot = snapshots.value
        val committed = decodeConfiguration(committedSnapshot)
        context.commit { players.forEach { it.requestWindowsAudioOutputPreferences(binding, committed.preferences) { !closed.get() && snapshots.value === committedSnapshot } } }
    }
    suspend fun refreshDevices(context: DesktopOriginalPlayerSettingsContext) {
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); check(!closed.get()); context.requireCurrent() }
        checkRequest()
        refresh.lock()
        try {
            checkRequest()
            context.commit { mutableDevices.value = mutableDevices.value.copy(querying = true, error = null) }
            val replies = listOfNotNull(videoPlayer, listeningPlayer).distinct().map { it.queryWindowsAudioDevices() }
            checkRequest()
            val available = replies.filter { it.available }
            val items = available.flatMap { it.devices }.distinctBy { it.name }.sortedBy { it.description }
            val result = DesktopWindowsAudioDeviceListState(java.util.Collections.unmodifiableList(items), available = available.isNotEmpty(),
                error = if (available.isNotEmpty()) null else replies.mapNotNull { it.error }.firstOrNull() ?: "请等待实际播放器初始化后刷新设备")
            context.commit { mutableDevices.value = result }
        } finally {
            // This is the controller's transient query marker, not settings/device business data.
            // Clear it even when the page retires, so the successor page can request a new query.
            mutableDevices.update { it.copy(querying = false) }
            refresh.unlock()
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            childJob.cancel()
            players.forEach { it.retireWindowsAudioOutputOwner(binding) }
        }
    }
    companion object {
        const val NAMESPACE = "windows_audio_output"
        private val EXCLUSIVE = com.bilipai.desktop.plugins.DesktopPreferenceKey<JsonElement>("exclusive") { it }
        private val DEVICE = com.bilipai.desktop.plugins.DesktopPreferenceKey<JsonElement>("device_id") { it }
        internal data class ConfigurationRead(val preferences: DesktopWindowsAudioOutputPreferences, val error: String?)
        internal fun decodeConfiguration(snapshot: DesktopPreferenceSnapshot): ConfigurationRead {
            val enabled = snapshot[EXCLUSIVE]; val device = snapshot[DEVICE]
            val validBoolean = enabled == null || (enabled is JsonPrimitive && !enabled.isString && enabled.booleanOrNull != null)
            val validString = device == null || (device is JsonPrimitive && device.isString)
            val preferences = DesktopWindowsAudioOutputPreferences((enabled as? JsonPrimitive)?.booleanOrNull ?: false,
                (device as? JsonPrimitive)?.content ?: "auto")
            val valid = validBoolean && validString && runCatching { preferences.requireValid() }.isSuccess
            return if (valid) ConfigurationRead(preferences, null) else ConfigurationRead(DesktopWindowsAudioOutputPreferences(),
                "保存的音频输出配置无效，已安全关闭独占；请重新选择设备或调整开关后重试")
        }
    }
}
