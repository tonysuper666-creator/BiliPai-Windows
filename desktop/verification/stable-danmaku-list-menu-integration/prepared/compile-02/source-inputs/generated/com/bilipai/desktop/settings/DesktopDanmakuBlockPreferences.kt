package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.DanmakuSettingsScope
import com.android.purebilibili.feature.video.danmaku.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive

/** Reads/writes the one Root global settings backing. Scope is portrait/landscape, never MID. */
internal class DesktopDanmakuBlockPreferences(
    private val store:DesktopPluginStore,
    private val withOwnedAdmission:((()->Unit)->Boolean),
) {
    init {store.requireObjectNamespace("settings")}
    private val KEY_DANMAKU_BLOCK_RULES=stringPreferencesKey("danmaku_block_rules")
    private suspend fun writeOriginalScopedValue(key:DesktopPreferenceKey<String>,value:String) {
        val caller=currentCoroutineContext()
        withContext(Dispatchers.IO) {
            if(!withOwnedAdmission {
                store.updateFromSnapshot("settings") { caller.ensureActive(); mapOf(key.name to JsonPrimitive(value)) }
            }) throw CancellationException("Danmaku settings owner retired")
        }
    }
    private fun buildScopedDanmakuKeyName(
        scope: DanmakuSettingsScope,
        suffix: String
    ): String {
        // Keep the existing fullscreen values authoritative across playback modes.
        val shared = suffix == "enabled" || suffix == "font_scale" || suffix == "area"
        val prefix = if (shared) DanmakuSettingsScope.LANDSCAPE.keyPrefix else scope.keyPrefix
        return "danmaku_${prefix}_$suffix"
    }
    private fun keyDanmakuBlockRules(scope: DanmakuSettingsScope) =
        stringPreferencesKey(buildScopedDanmakuKeyName(scope, "block_rules"))
    private fun <T> readScopedDanmakuPreference(
        preferences: DesktopPreferenceSnapshot,
        scopeKey: DesktopPreferenceKey<T>,
        legacyKey: DesktopPreferenceKey<T>,
        defaultValue: T
    ): T {
        return preferences[scopeKey] ?: preferences[legacyKey] ?: defaultValue
    }
fun getDanmakuBlockRulesRaw(
                scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ): Flow<String> = store.snapshot("settings")
        .map { preferences ->
            readScopedDanmakuPreference(
                preferences = preferences,
                scopeKey = keyDanmakuBlockRules(scope),
                legacyKey = KEY_DANMAKU_BLOCK_RULES,
                defaultValue = ""
            )
        }
    suspend fun setDanmakuBlockRulesRaw(
                value: String,
        scope: DanmakuSettingsScope = DanmakuSettingsScope.PORTRAIT
    ) {
        val trailingNewlines = value.takeLastWhile { it == '\n' || it == '\r' }
        val parsed = parseDanmakuBlockRules(value)
        val normalized = if (parsed.isEmpty()) {
            if (value.isBlank()) "" else value
        } else {
            parsed.joinToString(separator = "\n") + trailingNewlines
        }
        writeOriginalScopedValue(keyDanmakuBlockRules(scope), normalized)
    }
}
