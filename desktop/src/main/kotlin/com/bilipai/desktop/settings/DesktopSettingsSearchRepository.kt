package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.SettingsSearchHistoryStore
import com.android.purebilibili.feature.settings.SettingsSearchHistoryOperations
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Same original string key/JSON history; one supplied shared settings backing. */
class DesktopSettingsSearchRepository(context: DesktopPluginContext, privacyModeEnabled: () -> Boolean) {
    private val operations = SettingsSearchHistoryOperations(context, privacyModeEnabled)
    val history: Flow<List<String>> = SettingsSearchHistoryStore.observe(context)
    suspend fun record(query: String) = operations.recordSearchQuery(query)
    suspend fun delete(query: String) = operations.deleteSearchHistory(query)
    suspend fun clear() = operations.clearSearchHistory()
}

/** DataStore's atomic read/modify/write binding, shared by same-file facades. */
internal class DesktopSettingsSearchDataStore(private val context: DesktopPluginContext) {
    val data: StateFlow<DesktopPreferenceSnapshot> get() = context.store.snapshot("settings")
    suspend fun edit(block: (DesktopSettingsSearchEditor) -> Unit) = withContext(Dispatchers.IO) {
        val root = context.store.root.toAbsolutePath().normalize()
        locks.computeIfAbsent(root) { Mutex() }.withLock {
            val editor = DesktopSettingsSearchEditor(context.store.preferences("settings"))
            block(editor)
            context.store.update("settings", editor.changes)
        }
    }
    private companion object { val locks = ConcurrentHashMap<Path, Mutex>() }
}

internal class DesktopSettingsSearchEditor(private val original: JsonObject) {
    internal val changes = linkedMapOf<String, JsonElement?>()
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? =
        (if (changes.containsKey(key.name)) changes[key.name] else original[key.name])?.let(key.decode)
    operator fun set(key: DesktopPreferenceKey<String>, value: String) { changes[key.name] = JsonPrimitive(value) }
    fun remove(key: DesktopPreferenceKey<*>) { changes[key.name] = null }
}

internal val DesktopPluginContext.settingsSearchDataStore get() = DesktopSettingsSearchDataStore(this)
