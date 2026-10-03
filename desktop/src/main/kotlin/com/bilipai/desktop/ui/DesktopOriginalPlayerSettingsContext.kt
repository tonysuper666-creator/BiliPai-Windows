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
        // Pure platform defaults remain valid for the original outgoing retained composition.
        // They neither admit a settings write nor access native playback/account services.
        return checkNotNull(largeScreenOrFoldableConfiguration) { "Root actual physical monitor device default is required" }.invoke()
    }
    internal fun defaultPlayerDiagnosticLoggingEnabled(): Boolean {
        // Original BuildConfig-derived default is a pure value, also read as collect initialValue.
        // Page retirement still guards every write permit, commit and native overlay below.
        return com.android.purebilibili.core.store.resolveDefaultPlayerDiagnosticLoggingEnabled(checkNotNull(isDebugBuild) { "Root actual build type is required" }.invoke())
    }
    internal fun isCurrentForOriginalWrite(): Boolean = isCurrent()
    internal fun requireCurrent() { if (!isCurrent()) throw CancellationException("Original player settings owner retired") }
    internal fun preferenceWritePermit(checkRequest: () -> Unit): com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit {
        lateinit var permit: com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit
        commit {
            checkRequest()
            permit = com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit(pluginContext.store)
        }
        return permit
    }
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
internal fun playerLongPreferencesKey(name: String) = DesktopPreferenceKey(name) { v -> (v as? JsonPrimitive)?.longOrNull }
internal fun playerStringPreferencesKey(name: String) = DesktopPreferenceKey(name) { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }

internal class DesktopOriginalPlayerPreferenceValues(private val snapshot: DesktopPreferenceSnapshot) {
    val changes = linkedMapOf<String, JsonElement?>()
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? =
        if (changes.containsKey(key.name)) changes[key.name]?.let(key.decode) else snapshot[key]
    operator fun <T> set(key: DesktopPreferenceKey<T>, value: T) {
        changes[key.name] = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> error("Unsupported original player preference value")
        }
    }
    fun remove(key: DesktopPreferenceKey<*>) { changes[key.name] = null }
}

internal class DesktopOriginalPlayerSettingsDataStore(private val context: DesktopOriginalPlayerSettingsContext) {
    val data get() = DesktopOriginalPlaybackPreferenceOperation.currentOrNull()?.let {
        kotlinx.coroutines.flow.flowOf(it.values(context))
    } ?: context.pluginContext.store.snapshot("settings").map(::DesktopOriginalPlayerPreferenceValues)
    suspend fun edit(block: (DesktopOriginalPlayerPreferenceValues) -> Unit): DesktopOriginalPlayerPreferenceValues = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        DesktopOriginalPlaybackPreferenceOperation.currentOrNull()?.let { return@withContext it.edit(context, block) }
        lateinit var result: DesktopOriginalPlayerPreferenceValues
        fun checkRequest() {
            caller.ensureActive()
            context.requireCurrent()
            com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
        }
        context.pluginContext.store.updateOriginalFromSnapshot("settings", ::checkRequest,
            { context.preferenceWritePermit(::checkRequest) }) { snapshot ->
            result = DesktopOriginalPlayerPreferenceValues(snapshot).apply(block)
            result to result.changes.toMap()
        }
    }
}

internal class DesktopOriginalPlayerMirrorPreferences(private val context: DesktopOriginalPlayerSettingsContext, private val name: String) {
    private fun values() = DesktopOriginalPlaybackPreferenceOperation.currentOrNull()?.mirrorValues(context, name)
        ?: context.pluginContext.store.preferences(name)
    fun contains(key: String) = values().containsKey(key)
    fun getBoolean(key: String, default: Boolean) = (values()[key] as? JsonPrimitive)?.booleanOrNull ?: default
    fun getFloat(key: String, default: Float) = (values()[key] as? JsonPrimitive)?.floatOrNull ?: default
    fun getInt(key: String, default: Int) = (values()[key] as? JsonPrimitive)?.intOrNull ?: default
    fun getString(key: String, default: String?) = (values()[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default
    fun getStringSet(key: String, default: Set<String>?): Set<String>? =
        (values()[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }?.toSet() ?: default
    fun edit() = Editor(context, name)
    class Editor(private val context: DesktopOriginalPlayerSettingsContext, private val name: String) {
        private val changes = linkedMapOf<String, JsonElement?>()
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = JsonPrimitive(value) }
        fun putFloat(key: String, value: Float) = apply { changes[key] = JsonPrimitive(value) }
        fun putInt(key: String, value: Int) = apply { changes[key] = JsonPrimitive(value) }
        fun putString(key: String, value: String?) = apply { changes[key] = value?.let(::JsonPrimitive) }
        fun putStringSet(key: String, value: Set<String>?) = apply {
            changes[key] = value?.let { JsonArray(it.map(::JsonPrimitive)) }
        }
        fun apply() {
            val edits = changes.toMap()
            DesktopOriginalPlaybackPreferenceOperation.currentOrNull()?.let { it.mirror(context, name, edits); return }
            fun checkRequest() {
                context.requireCurrent()
                com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
            }
            context.pluginContext.store.updateOriginalFromSnapshot(name, ::checkRequest,
                { context.preferenceWritePermit(::checkRequest) }) { Unit to edits }
        }
        fun commit(): Boolean { apply(); return true }
    }
}
