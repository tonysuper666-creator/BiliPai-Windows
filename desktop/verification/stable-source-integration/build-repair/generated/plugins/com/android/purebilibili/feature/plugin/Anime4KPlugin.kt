// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/Anime4KPlugin.kt; do not edit.
// LF-normalized SHA-256: 0979f3131d293cddfdeec2b41e00eb89d90a49043c1782e1a105e1b99b11baab
package com.android.purebilibili.feature.plugin

import com.android.purebilibili.core.plugin.Plugin
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.core.plugin.PluginStore
import com.bilipai.desktop.plugins.DesktopPluginLog as Logger
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.feature.anime4k.FSR_SHARPNESS_SLIDER_STEPS
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.anime4k.VideoEnhancementConfigLoadGuard
import com.android.purebilibili.feature.anime4k.decodeVideoEnhancementConfig
import com.android.purebilibili.feature.anime4k.encodeVideoEnhancementConfig
import com.android.purebilibili.feature.anime4k.normalizeFsrSharpness
import com.android.purebilibili.feature.anime4k.resolveConfigAfterRememberAcrossVideosChange
import com.android.purebilibili.feature.anime4k.resolveConfigAfterVideoEnhancementToggle
import com.android.purebilibili.feature.anime4k.resolveAnime4KPresetLabel
import com.android.purebilibili.feature.anime4k.shouldConfirmRememberAcrossVideosChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "Anime4KPlugin"

/**
 * Anime4K 是内置插件：插件层负责配置和可见性，播放器输出仍由 VideoOutputRouter 管理。
 */
class Anime4KPlugin : Plugin {

    override val id: String = PLUGIN_ID
    override val name: String = "画质增强"
    override val description: String = "提供 Anime4K 与 AMD FSR 1.0 实时画质增强"
    override val version: String = "0.4.0"
    override val author: String = "BiliPai项目组"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = Anime4KPlugin::class.java.name,
        capabilities = emptySet()
    )

    private val ioScope = com.bilipai.desktop.plugins.DesktopPluginScopeRegistry.create("com/android/purebilibili/feature/plugin/Anime4KPlugin.kt:1", Dispatchers.IO)
    @Volatile private var desktopPersistenceError: Throwable? = null
    private var config = Anime4KConfig()
    private val configLoadGuard = VideoEnhancementConfigLoadGuard()
    private val _configState = MutableStateFlow(config)
    val configState: StateFlow<Anime4KConfig> = _configState.asStateFlow()

    override suspend fun onEnable() {
        loadConfig()
        Logger.d(TAG, "画质增强插件已启用")
    }

    fun setPreset(preset: Anime4KPreset) {
        updateConfig(config.copy(preset = preset))
    }

    fun setAlgorithm(algorithm: VideoEnhancementAlgorithm) {
        updateConfig(config.copy(algorithm = algorithm))
    }

    fun setFsrSharpness(strength: Float) {
        updateConfig(config.copy(fsrSharpness = normalizeFsrSharpness(strength)))
    }

    fun setRememberAcrossVideos(enabled: Boolean, currentVideoEnabled: Boolean) {
        updateConfig(
            resolveConfigAfterRememberAcrossVideosChange(
                config = config,
                rememberAcrossVideos = enabled,
                currentVideoEnabled = currentVideoEnabled
            )
        )
    }

    fun rememberCurrentVideoEnabled(enabled: Boolean) {
        updateConfig(resolveConfigAfterVideoEnhancementToggle(config, enabled))
    }

    private fun updateConfig(value: Anime4KConfig) {
        if (config == value) return
        desktopPersistenceError = null
        configLoadGuard.markLocalChange()
        config = value
        _configState.value = value
        ioScope.launch {
            runCatching {
                PluginStore.setConfigJson(
                    context = PluginManager.getContext(),
                    pluginId = id,
                    configJson = encodeVideoEnhancementConfig(value)
                )
            }.onFailure { error ->
                desktopPersistenceError = error
                Logger.e(TAG, "保存画质增强配置失败", error)
            }
        }
    }

    internal suspend fun loadConfig() {
        val loadedConfig = runCatching {
            val raw = PluginStore.getConfigJson(PluginManager.getContext(), id)
            if (raw.isNullOrBlank()) {
                Anime4KConfig()
            } else {
                decodeVideoEnhancementConfig(raw)
            }
        }.onFailure { error ->
            Logger.e(TAG, "读取画质增强配置失败", error)
        }.getOrDefault(Anime4KConfig())
        if (!configLoadGuard.shouldApplyLoadedConfig()) {
            Logger.d(TAG, "保留本次进程中刚修改的画质增强配置")
            return
        }
        config = loadedConfig
        _configState.value = config
    }



    internal suspend fun awaitDesktopConfigurationWrites() {
        ioScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
        desktopPersistenceError?.let { throw IllegalStateException("画质增强配置保存失败", it) }
    }

    companion object {
        const val PLUGIN_ID: String = "anime4k"

        fun getInstance(): Anime4KPlugin? {
            return PluginManager.plugins
                .firstOrNull { it.plugin.id == PLUGIN_ID }
                ?.plugin as? Anime4KPlugin
        }
    }
}
