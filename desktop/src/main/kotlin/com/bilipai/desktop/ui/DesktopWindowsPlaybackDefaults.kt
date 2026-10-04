package com.bilipai.desktop.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The Windows edition starts with the user's requested highest available quality.
 * Apply once, before constructing the original video resources. Later manual choices
 * remain intact. Canonical preference, original synchronous mirror and migration mark
 * share the existing Store's single atomic publication and live Root permit.
 */
internal suspend fun ensureDesktopWindowsPlaybackDefaults(context: DesktopOriginalPlayerSettingsContext) =
    withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun current() { caller.ensureActive(); context.requireCurrent() }
        val store = context.pluginContext.store
        store.updateOriginalNamespacesFromSnapshot("settings", ::current,
            { context.preferenceWritePermit(::current) }) {
            val initialized = (store.preferences("windows_desktop_defaults")["highest_quality_v1"]
                as? JsonPrimitive)?.booleanOrNull == true
            Unit to if (initialized) emptyMap() else mapOf(
                "settings" to mapOf("auto_highest_quality" to JsonPrimitive(true)),
                "quality_settings" to mapOf("auto_highest_quality" to JsonPrimitive(true)),
                "windows_desktop_defaults" to mapOf("highest_quality_v1" to JsonPrimitive(true)),
            )
        }
    }
