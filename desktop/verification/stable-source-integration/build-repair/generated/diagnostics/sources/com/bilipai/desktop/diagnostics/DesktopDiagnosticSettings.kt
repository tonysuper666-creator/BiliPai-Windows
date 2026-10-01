// GENERATED from app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt; do not edit.
// LF-normalized SHA-256: 680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c
package com.bilipai.desktop.diagnostics
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class DesktopDiagnosticSettings(private val store:DesktopPluginStore) {
    private val KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED = "enhanced_diagnostic_logging_enabled"
fun getEnhancedDiagnosticLoggingEnabled(): Flow<Boolean> =
    store.snapshot("settings").map { preferences ->
        preferences[com.bilipai.desktop.plugins.booleanPreferencesKey(KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED)] ?: false
    }
fun setEnhancedDiagnosticLoggingEnabled(value: Boolean) {
    store.update("settings", mapOf(KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED to JsonPrimitive(value)))
}
    fun getEnhancedDiagnosticLoggingEnabledSync():Boolean = store.preferences("settings")[KEY_ENHANCED_DIAGNOSTIC_LOGGING_ENABLED]?.jsonPrimitive?.booleanOrNull ?: false
}
