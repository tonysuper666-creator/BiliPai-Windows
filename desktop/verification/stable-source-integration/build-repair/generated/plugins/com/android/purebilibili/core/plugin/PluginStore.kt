// GENERATED from app/src/main/java/com/android/purebilibili/core/plugin/PluginStore.kt; do not edit.
// LF-normalized SHA-256: 04a12ad24b0ff6393d727638e61e348d115e617b3dd9a9e604cac59deae9ece8
// 文件路径: core/plugin/PluginStore.kt
package com.android.purebilibili.core.plugin

import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


/**
 * 🗄️ 插件配置持久化存储
 * 
 * 使用 DataStore 存储每个插件的启用状态和配置
 */
object PluginStore {

    private val effectMatchHintsEnabledKey = booleanPreferencesKey("effect_match_hints_enabled")
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    /**
     * 获取插件启用状态 (Flow)
     */
    fun isEnabledFlow(context: Context, pluginId: String): Flow<Boolean> {
        val key = booleanPreferencesKey("plugin_enabled_$pluginId")
        return context.pluginDataStore.data.map { prefs ->
            prefs[key] ?: resolvePluginDefaultEnabled(pluginId)
        }
    }
    
    /**
     * 获取插件启用状态 (同步)
     */
    suspend fun isEnabled(context: Context, pluginId: String): Boolean {
        return isEnabledFlow(context, pluginId).first()
    }
    
    /**
     * 设置插件启用状态
     */
    suspend fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val key = booleanPreferencesKey("plugin_enabled_$pluginId")
        context.pluginDataStore.edit { prefs ->
            prefs[key] = enabled
        }
    }

    /** 是否显示去广告、弹幕过滤等高频命中提示。 */
    fun effectMatchHintsEnabledFlow(context: Context): Flow<Boolean> {
        return context.pluginDataStore.data.map { prefs ->
            prefs[effectMatchHintsEnabledKey] ?: false
        }
    }

    suspend fun setEffectMatchHintsEnabled(context: Context, enabled: Boolean) {
        context.pluginDataStore.edit { prefs ->
            prefs[effectMatchHintsEnabledKey] = enabled
        }
    }
    
    /**
     * 获取插件配置 (JSON 字符串)
     */
    suspend fun getConfigJson(context: Context, pluginId: String): String? {
        val key = stringPreferencesKey("plugin_config_$pluginId")
        return context.pluginDataStore.data.map { prefs ->
            prefs[key]
        }.first()
    }
    
    /**
     * 设置插件配置 (JSON 字符串)
     */
    suspend fun setConfigJson(context: Context, pluginId: String, configJson: String) {
        val key = stringPreferencesKey("plugin_config_$pluginId")
        context.pluginDataStore.edit { prefs ->
            prefs[key] = configJson
        }
    }

    /**
     * 获取插件私有数据 (JSON 字符串)
     */
    suspend fun getDataJson(context: Context, pluginId: String, name: String): String? {
        val key = stringPreferencesKey("plugin_data_${pluginId}_$name")
        return context.pluginDataStore.data.map { prefs ->
            prefs[key]
        }.first()
    }

    /**
     * 设置插件私有数据 (JSON 字符串)
     */
    suspend fun setDataJson(context: Context, pluginId: String, name: String, dataJson: String) {
        val key = stringPreferencesKey("plugin_data_${pluginId}_$name")
        context.pluginDataStore.edit { prefs ->
            prefs[key] = dataJson
        }
    }
}
