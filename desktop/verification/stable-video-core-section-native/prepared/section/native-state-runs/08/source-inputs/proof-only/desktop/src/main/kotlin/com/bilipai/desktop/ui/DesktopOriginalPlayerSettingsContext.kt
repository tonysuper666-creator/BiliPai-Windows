package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*

/** Adapter over Root's sole global settings document and original mirror namespaces.
 * Root supplies its existing entry admission; no independent preferences/cache authority.
 */
class DesktopOriginalPlayerSettingsContext internal constructor(
    val pluginContext: DesktopPluginContext,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val videoOverlayPlatform: DesktopOriginalVideoOverlayPlatform? = null,
    private val largeScreenOrFoldableConfiguration: (() -> Boolean)? = null,
    private val isDebugBuild: (() -> Boolean)? = null
) {
    companion object { const val MODE_PRIVATE = 0 }
    internal val settingsDataStore get() = DesktopOriginalPlayerSettingsDataStore(this)
    internal val videoOverlay: DesktopOriginalVideoOverlayPlatform get() {
        requireCurrent()
        return checkNotNull(videoOverlayPlatform) { "Root original video overlay platform is required" }
    }
    internal fun getSharedPreferences(name: String, mode: Int): DesktopOriginalPlayerMirrorPreferences {
        require(mode == MODE_PRIVATE)
        return DesktopOriginalPlayerMirrorPreferences(this, name)
    }
    internal fun isLargeScreenOrFoldableConfiguration(): Boolean {
        requireCurrent()
        return checkNotNull(largeScreenOrFoldableConfiguration) { "Root actual physical monitor device default is required" }.invoke()
    }
    internal fun defaultPlayerDiagnosticLoggingEnabled(): Boolean {
        requireCurrent()
        return com.android.purebilibili.core.store.resolveDefaultPlayerDiagnosticLoggingEnabled(checkNotNull(isDebugBuild) { "Root actual build type is required" }.invoke())
    }
    internal fun requireCurrent() { if (!isCurrent()) throw CancellationException("Original player settings owner retired") }
    internal fun commit(block: () -> Unit) {
        requireCurrent()
        if (!commitIfCurrent { requireCurrent(); block() }) throw CancellationException("Original player settings owner retired")
    }
}

internal val LocalDesktopOriginalPlayerSettingsContext = staticCompositionLocalOf<DesktopOriginalPlayerSettingsContext> {
    error("Root original player settings context is required")
}

internal fun playerBooleanPreferencesKey(name: String) = DesktopPreferenceKey(name) { v -> (v as? JsonPrimitive)?.booleanOrNull }
internal fun playerFloatPreferencesKey(name: String) = DesktopPreferenceKey(name) { v -> (v as? JsonPrimitive)?.floatOrNull }
internal fun playerIntPreferencesKey(name: String) = DesktopPreferenceKey(name) { v -> (v as? JsonPrimitive)?.intOrNull }
internal fun playerStringPreferencesKey(name: String) = DesktopPreferenceKey(name) { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }

internal class DesktopOriginalPlayerPreferenceValues(private val snapshot: DesktopPreferenceSnapshot) {
    val changes = linkedMapOf<String, JsonElement>()
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? = changes[key.name]?.let(key.decode) ?: snapshot[key]
    operator fun <T> set(key: DesktopPreferenceKey<T>, value: T) {
        changes[key.name] = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> error("Unsupported original player preference value")
        }
    }
}

internal class DesktopOriginalPlayerSettingsDataStore(private val context: DesktopOriginalPlayerSettingsContext) {
    val data get() = context.pluginContext.store.snapshot("settings").map(::DesktopOriginalPlayerPreferenceValues)
    suspend fun edit(block: (DesktopOriginalPlayerPreferenceValues) -> Unit): DesktopOriginalPlayerPreferenceValues = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        lateinit var result: DesktopOriginalPlayerPreferenceValues
        context.commit {
            caller.ensureActive()
            context.pluginContext.store.updateFromSnapshot("settings") { snapshot ->
                result = DesktopOriginalPlayerPreferenceValues(snapshot).apply(block)
                caller.ensureActive()
                result.changes
            }
        }
        result
    }
}

internal class DesktopOriginalPlayerMirrorPreferences(private val context: DesktopOriginalPlayerSettingsContext, private val name: String) {
    private fun values() = context.pluginContext.store.preferences(name)
    fun contains(key: String) = values().containsKey(key)
    fun getBoolean(key: String, default: Boolean) = (values()[key] as? JsonPrimitive)?.booleanOrNull ?: default
    fun getFloat(key: String, default: Float) = (values()[key] as? JsonPrimitive)?.floatOrNull ?: default
    fun getInt(key: String, default: Int) = (values()[key] as? JsonPrimitive)?.intOrNull ?: default
    fun getString(key: String, default: String?) = (values()[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default
    fun edit() = Editor(context, name)
    class Editor(private val context: DesktopOriginalPlayerSettingsContext, private val name: String) {
        private val changes = linkedMapOf<String, JsonElement?>()
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = JsonPrimitive(value) }
        fun putFloat(key: String, value: Float) = apply { changes[key] = JsonPrimitive(value) }
        fun putInt(key: String, value: Int) = apply { changes[key] = JsonPrimitive(value) }
        fun putString(key: String, value: String?) = apply { changes[key] = value?.let(::JsonPrimitive) }
        fun apply() { context.commit { context.pluginContext.store.update(name, changes) } }
        fun commit(): Boolean { apply(); return true }
    }
}
