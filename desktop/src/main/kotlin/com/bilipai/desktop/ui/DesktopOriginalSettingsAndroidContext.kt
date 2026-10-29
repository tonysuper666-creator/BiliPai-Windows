package com.bilipai.desktop.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.DesktopPreferencesDataStoreOwner
import androidx.datastore.preferences.core.Preferences
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.settings.DesktopSettingsDocumentDataStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The Android Context upstream app code receives on the desktop (LocalContext): one page's settings owner. Upstream's
 * `settings_prefs` DataStore is the "settings" namespace of Root's settings document, and its SharedPreferences are the
 * same original mirror namespaces the desktop already keeps; every write goes through this owner's admission, so a
 * retired page cannot write. DataStores the desktop does not hold in that document fail loudly.
 */
internal class DesktopOriginalSettingsAndroidContext(private val settings: DesktopOriginalPlayerSettingsContext) :
    android.content.Context(settings.pluginContext.filesDir), DesktopPreferencesDataStoreOwner {
    // Built on the page's own settings DataStore, so upstream writes join an open settings operation (staged, published
    // at its end, rolled back on failure or retirement) and otherwise go through the page's admission and CAS.
    private val settingsStore = object : DataStore<Preferences> {
        // Read on access: an open operation is a thread-local, and its staged values are what this caller must see.
        override val data: kotlinx.coroutines.flow.Flow<Preferences>
            get() = settings.settingsDataStore.data.map { DesktopSettingsDocumentDataStore.toPreferences(it.json) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val before = data.first()
            val after = transform(before)
            val changes = DesktopSettingsDocumentDataStore.changes(before.asMap(), after.asMap())
            if (changes.isNotEmpty()) settings.settingsDataStore.edit { values -> values.changes.putAll(changes) }
            return after
        }
    }

    override val applicationContext: android.content.Context get() = this
    internal fun isLargeScreenOrFoldableConfiguration(): Boolean = settings.isLargeScreenOrFoldableConfiguration()
    override val filesDir: java.io.File get() = settings.pluginContext.filesDir

    override fun desktopPreferencesDataStore(name: String): DataStore<Preferences> = when (name) {
        "settings_prefs" -> settingsStore
        else -> throw UnsupportedDesktopCapability("DataStore \"$name\"", "the desktop keeps only settings_prefs in Root's settings document")
    }

    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        MirrorSharedPreferences(settings.getSharedPreferences(name, mode))

    private class MirrorSharedPreferences(private val mirror: DesktopOriginalPlayerMirrorPreferences) : android.content.SharedPreferences {
        override fun getString(key: String, defValue: String?) = mirror.getString(key, defValue)
        override fun getStringSet(key: String, defValues: Set<String>?) = mirror.getStringSet(key, defValues)
        override fun getLong(key: String, defValue: Long) = mirror.getLong(key, defValue)
        override fun getInt(key: String, defValue: Int) = mirror.getInt(key, defValue)
        override fun getBoolean(key: String, defValue: Boolean) = mirror.getBoolean(key, defValue)
        override fun getFloat(key: String, defValue: Float) = mirror.getFloat(key, defValue)
        override fun contains(key: String) = mirror.contains(key)
        override val all: Map<String, *> get() = mirror.all().mapValues { (_, value) ->
            when (value) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                is JsonPrimitive -> when {
                    value.isString -> value.content
                    value.booleanOrNull != null -> value.booleanOrNull
                    else -> value.longOrNull ?: value.doubleOrNull
                }
                else -> null
            }
        }
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val editor = mirror.edit()
            override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
            override fun putStringSet(key: String, values: Set<String>?) = apply { editor.putStringSet(key, values) }
            override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
            override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
            override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
            override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
            override fun remove(key: String) = apply { editor.remove(key) }
            override fun clear(): android.content.SharedPreferences.Editor =
                throw UnsupportedDesktopCapability("SharedPreferences.clear()", "a mirror namespace of Root's settings document is never cleared wholesale")
            override fun commit(): Boolean = editor.commit()
            override fun apply() { editor.apply() }
        }
    }
}

/** The Android Context upstream UI code reads through LocalContext: Root's plugin context, whose settings_prefs is the
 * settings document. Provided once at the window root; a composition outside it fails instead of reading a detached store. */
internal val LocalDesktopUpstreamAndroidContext = androidx.compose.runtime.staticCompositionLocalOf<android.content.Context?> { null }
