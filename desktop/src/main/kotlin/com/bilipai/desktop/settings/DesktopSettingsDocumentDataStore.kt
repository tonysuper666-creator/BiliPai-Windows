package com.bilipai.desktop.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Upstream DataStore<Preferences> over one namespace of Root's settings document (DesktopPluginStore), so upstream
 * stores compiled from the merged tree read and write the same values as the desktop settings UI instead of a
 * separate, empty Android DataStore file.
 *
 * Reads convert the JSON namespace to Preferences. JSON tells booleans, strings and string sets apart; numbers are
 * typed by the table generated from the upstream key declarations (DesktopUpstreamPreferenceKeyTypes), because
 * Preferences.Key has no runtime type and a Float key holding an Int would fail at the caller. Values the upstream
 * keys cannot hold (JSON objects, null) stay in the document untouched.
 *
 * Writes run the upstream transform on the current namespace and commit only the changed keys through the document's
 * original write path: [checkRequest] and [acquirePermit] are the caller's admission (the retired page cannot write),
 * and a concurrent replacement of the namespace reruns the transform on the new values, as DataStore serializes edits.
 */
internal class DesktopSettingsDocumentDataStore(
    private val store: DesktopPluginStore,
    private val namespace: String,
    private val checkRequest: () -> Unit,
    private val acquirePermit: (() -> Unit) -> DesktopPluginStore.OriginalPreferenceWritePermit,
) : DataStore<Preferences> {
    override val data: Flow<Preferences> get() = store.snapshot(namespace).map { toPreferences(it.json) }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = withContext(Dispatchers.IO) {
        var result: Preferences? = null
        while (result == null) {
            checkRequest()
            val base = store.preferences(namespace)
            val before = toPreferences(base)
            val after = transform(before)
            val changes = changes(before.asMap(), after.asMap())
            val committed = changes.isEmpty() ||
                store.updateOriginalFromSnapshot(namespace, checkRequest, { acquirePermit(checkRequest) }) { snapshot ->
                    // The transform saw `base`; if the namespace moved since, commit nothing and run it again.
                    if (snapshot.json != base) false to emptyMap() else true to changes
                }
            if (committed) result = after
        }
        result!!
    }

    companion object {
        fun toPreferences(json: JsonObject): Preferences {
            val preferences = mutablePreferencesOf()
            json.forEach { (name, value) ->
                when (value) {
                    is JsonPrimitive -> when {
                        value.isString -> preferences[stringPreferencesKey(name)] = value.content
                        value.booleanOrNull != null -> preferences[booleanPreferencesKey(name)] = value.booleanOrNull!!
                        else -> putNumber(preferences, name, value)
                    }
                    is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
                        .takeIf { items -> items.all { it != null } }
                        ?.let { preferences[stringSetPreferencesKey(name)] = it.filterNotNull().toSet() }
                    else -> Unit
                }
            }
            return preferences
        }

        /** The upstream type of a numeric key, or null for a key no upstream file declares (desktop-only settings). */
        fun numericType(name: String): String? = DesktopUpstreamPreferenceKeyTypes.names[name]
            ?: Regex("danmaku_.+?_(.+)").matchEntire(name)?.groupValues?.get(1)?.let { DesktopUpstreamPreferenceKeyTypes.scopedDanmakuSuffixes[it] }

        private fun putNumber(preferences: androidx.datastore.preferences.core.MutablePreferences, name: String, value: JsonPrimitive) {
            val whole = value.longOrNull
            val type = numericType(name) ?: when {
                whole == null -> "float"
                whole in Int.MIN_VALUE..Int.MAX_VALUE -> "int"
                else -> "long"
            }
            val number = value.doubleOrNull ?: return
            when (type) {
                "int" -> preferences[intPreferencesKey(name)] = whole?.toInt() ?: number.toInt()
                "long" -> preferences[longPreferencesKey(name)] = whole ?: number.toLong()
                "float" -> preferences[floatPreferencesKey(name)] = number.toFloat()
                "double" -> preferences[doublePreferencesKey(name)] = number
                else -> error("Unknown preference type $type for $name")
            }
        }

        fun toJson(value: Any): JsonElement = when (value) {
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Set<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
            else -> throw IllegalArgumentException("The desktop settings document cannot hold ${value::class.java.name}")
        }

        fun changes(before: Map<Preferences.Key<*>, Any>, after: Map<Preferences.Key<*>, Any>): Map<String, JsonElement?> {
            val result = linkedMapOf<String, JsonElement?>()
            before.keys.filter { it !in after }.forEach { result[it.name] = null }
            after.forEach { (key, value) -> if (before[key] != value) result[key.name] = toJson(value) }
            return result
        }
    }
}
