// GENERATED from app/src/main/java/com/android/purebilibili/core/store/SkeletonSettingsStore.kt; do not edit.
// LF-normalized SHA-256: e13b562319d70a79dc5a41d7c6228feee49dd62fe06443dd8978f5c4c06b21c6
package com.android.purebilibili.core.store

import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.map

object SkeletonSettingsStore {
    private val breathingKey = booleanPreferencesKey("skeleton_breathing_enabled")

    fun breathingEnabled(context: Context) = context.settingsDataStore.data.map {
        it[breathingKey] ?: true
    }

    suspend fun setBreathingEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[breathingKey] = enabled }
    }
}
