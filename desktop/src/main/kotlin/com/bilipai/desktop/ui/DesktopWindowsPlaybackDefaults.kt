package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The Windows edition starts with the user's requested highest available quality.
 * Apply once, before constructing the original video resources. Later manual choices
 * remain intact. Canonical preference, original synchronous mirror and migration mark
 * share the existing Store's single atomic publication and live Root permit.
 *
 * [legacyPlayback] is the player-settings.json snapshot Root read at startup. When given, every
 * canonical codec, audio, subtitle and speed key that is still absent is seeded from it (once,
 * see [DesktopOriginalPlaybackPreferenceProjection.legacySeedEdits]) in the same publication.
 * It defaults to null, so callers without that snapshot never read the user's legacy file.
 */
internal suspend fun ensureDesktopWindowsPlaybackDefaults(context: DesktopOriginalPlayerSettingsContext,
    legacyPlayback: PlayerPreferences? = null) =
    withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun current() { caller.ensureActive(); context.requireCurrent() }
        val store = context.pluginContext.store
        store.updateOriginalNamespacesFromSnapshot("settings", ::current,
            { context.preferenceWritePermit(::current) }) { fresh ->
            val initialized = (store.preferences("windows_desktop_defaults")["highest_quality_v1"]
                as? JsonPrimitive)?.booleanOrNull == true
            val restoredHighest = (store.preferences("windows_android_backup_migration")["pending"]
                as? JsonPrimitive)?.booleanOrNull == true && fresh[playerBooleanPreferencesKey("auto_highest_quality")] != null
            val highest: Map<String, Map<String, JsonElement?>> = if (initialized) emptyMap() else if (restoredHighest) mapOf(
                "windows_desktop_defaults" to mapOf("highest_quality_v1" to JsonPrimitive(true)),
            ) else mapOf(
                "settings" to mapOf("auto_highest_quality" to JsonPrimitive(true)),
                "quality_settings" to mapOf("auto_highest_quality" to JsonPrimitive(true)),
                "windows_desktop_defaults" to mapOf("highest_quality_v1" to JsonPrimitive(true)),
            )
            val seeded = legacyPlayback?.let {
                DesktopOriginalPlaybackPreferenceProjection.legacySeedEdits(store, fresh, it)
            }.orEmpty()
            Unit to mergeNamespaceEdits(highest, seeded)
        }
        com.bilipai.desktop.settings.DesktopSettingsShareService(context).reconcileRestoredAndroidBackup()
    }

private fun mergeNamespaceEdits(vararg parts: Map<String, Map<String, JsonElement?>>): Map<String, Map<String, JsonElement?>> {
    val merged = linkedMapOf<String, MutableMap<String, JsonElement?>>()
    parts.forEach { part -> part.forEach { (namespace, edits) -> merged.getOrPut(namespace) { linkedMapOf() }.putAll(edits) } }
    return merged
}
