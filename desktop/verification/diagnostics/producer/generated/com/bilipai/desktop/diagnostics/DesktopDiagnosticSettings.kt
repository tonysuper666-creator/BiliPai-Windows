// GENERATED from app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt; do not edit.
// LF-normalized SHA-256: b43c112cfda44780b29d3a5419f6ce452bf7c0e4859cb0145636eae3f3ec2010
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
