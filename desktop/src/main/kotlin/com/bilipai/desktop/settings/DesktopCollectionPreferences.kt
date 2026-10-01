package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Original collection keys/read mappers use the existing global settings generation. */
internal class DesktopCollectionPreferenceEditor(private val snapshot: DesktopPreferenceSnapshot) {
    val values = linkedMapOf<String, JsonElement?>()
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? =
        if (values.containsKey(key.name)) values[key.name]?.let(key.decode) else snapshot[key]
    operator fun <T> set(key: DesktopPreferenceKey<T>, value: T) {
        values[key.name] = when (value) {
            is String -> JsonPrimitive(value)
            else -> error("Unsupported original collection setting value")
        }
    }
}
internal class DesktopCollectionSettingsDataStore(private val context: DesktopPluginContext) {
    val data: StateFlow<DesktopPreferenceSnapshot> get() = context.store.snapshot("settings")
    suspend fun edit(block: (DesktopCollectionPreferenceEditor) -> Unit) = withContext(Dispatchers.IO) {
        context.store.updateFromSnapshot("settings") { snapshot ->
            DesktopCollectionPreferenceEditor(snapshot).apply(block).values
        }
    }
}
internal val DesktopPluginContext.collectionSettingsDataStore get() = DesktopCollectionSettingsDataStore(this)
