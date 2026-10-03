package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalPlaybackSettingsPreferences
import com.android.purebilibili.core.store.resolveMigratedHwDecodeValue
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Read-only projection until an admitted original setting/migration is committed.
 * Original canonical key, current original mirror and legacy original mirror precede
 * the existing Windows fallback. The Android migration policy itself is unchanged.
 */
internal class DesktopOriginalHardwareDecodePreferences(private val store: DesktopPluginStore) {
    private val key = playerBooleanPreferencesKey("hw_decode")
    private fun mirror(name: String): Boolean? =
        (store.preferences(name)["hw_decode_enabled"] as? JsonPrimitive)?.booleanOrNull
    fun current(legacyWindowsFallback: Boolean): Boolean {
        store.snapshot("settings").value[key]?.let { return it }
        val current = mirror("player_settings_cache")
        val legacy = mirror("hw_decode_cache")
        if (current == null && legacy == null) return legacyWindowsFallback
        return resolveMigratedHwDecodeValue(current != null, current ?: true, legacy != null, legacy ?: true)
    }
    fun changes(legacyWindowsFallback: Boolean): Flow<Boolean> = combine(
        store.snapshot("settings"), store.snapshot("player_settings_cache"), store.snapshot("hw_decode_cache")
    ) { _, _, _ -> current(legacyWindowsFallback) }.distinctUntilChanged()

    /** Absence-only migration rechecks the canonical key on each fresh actual CAS.
     * Root passes its actual context/permit. No source/volume/speed state is written.
     */
    suspend fun ensureMigrated(context: DesktopOriginalPlayerSettingsContext, legacyWindowsFallback: Boolean) = withContext(Dispatchers.IO) {
        require(context.pluginContext.store === store)
        val caller = currentCoroutineContext()
        fun checkRequest() {
            caller.ensureActive(); context.requireCurrent()
            com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
        }
        store.updateOriginalNamespacesFromSnapshot("settings", ::checkRequest,
            { context.preferenceWritePermit(::checkRequest) }) { fresh ->
            val value = fresh[key] ?: run {
                val current = mirror("player_settings_cache")
                val legacy = mirror("hw_decode_cache")
                if (current == null && legacy == null) legacyWindowsFallback
                else resolveMigratedHwDecodeValue(current != null, current ?: true, legacy != null, legacy ?: true)
            }
            Unit to mapOf(
                "settings" to mapOf("hw_decode" to JsonPrimitive(value)),
                "player_settings_cache" to mapOf("hw_decode_enabled" to JsonPrimitive(value)))
        }
    }
    suspend fun set(context: DesktopOriginalPlayerSettingsContext, value: Boolean, owns: () -> Boolean) {
        require(context.pluginContext.store === store)
        DesktopOriginalPlaybackPreferenceOperation.run(context, owns) {
            DesktopOriginalPlaybackSettingsPreferences.setHwDecode(context, value)
        }
    }
}
