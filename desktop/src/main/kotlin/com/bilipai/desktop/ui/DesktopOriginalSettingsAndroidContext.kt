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
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume

/**
 * The Android Context upstream app code receives on the desktop (LocalContext): one page's settings owner. Upstream's
 * `settings_prefs` DataStore is the "settings" namespace of Root's settings document, and its SharedPreferences are the
 * same original mirror namespaces the desktop already keeps; every write goes through this owner's admission, so a
 * retired page cannot write. DataStores the desktop does not hold in that document fail loudly.
 */
internal class DesktopOriginalSettingsAndroidContext(private val settings: DesktopOriginalPlayerSettingsContext) :
    android.content.Context(settings.pluginContext.filesDir), DesktopPreferencesDataStoreOwner {
    // Built on the page's own settings DataStore: inside an open settings operation upstream writes are staged, published
    // at its end and rolled back on failure or retirement; without one they go through the page's admission and CAS.
    private val settingsStore = object : DataStore<Preferences> {
        // Read on access: an open operation is a thread-local, and its staged values are what this caller must see.
        override val data: kotlinx.coroutines.flow.Flow<Preferences>
            get() = settings.settingsDataStore.data.map { DesktopSettingsDocumentDataStore.toPreferences(it.json) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            // In an open operation the edit is recorded and replayed on the actual snapshot at its end, like every
            // desktop edit there: the recorded edit is the transform itself, run again on the values it is replayed on.
            if (DesktopOriginalPlaybackPreferenceOperation.currentOrNull() != null) {
                var staged: Preferences? = null
                settings.settingsDataStore.edit { values ->
                    val before = DesktopSettingsDocumentDataStore.toPreferences(values.json)
                    val after = runReplayable(transform, before)
                    if (staged == null) staged = after
                    values.changes.putAll(DesktopSettingsDocumentDataStore.changes(before.asMap(), after.asMap()))
                }
                return staged!!
            }
            // Otherwise the namespace may move between the transform and the CAS: commit nothing and run the
            // transform again on the new values, as DesktopSettingsDocumentDataStore does.
            while (true) {
                val base = settings.settingsDataStore.data.first().json
                val before = DesktopSettingsDocumentDataStore.toPreferences(base)
                val after = transform(before)
                val changes = DesktopSettingsDocumentDataStore.changes(before.asMap(), after.asMap())
                if (changes.isEmpty()) return after
                var moved = false
                settings.settingsDataStore.edit { values ->
                    moved = values.json != base
                    if (!moved) values.changes.putAll(changes)
                }
                if (!moved) return after
            }
        }
    }

    /** Runs a DataStore transform to completion on this thread, so an operation can replay it on its actual snapshot.
     * A transform that suspends cannot be replayed there and fails instead of committing a stale result. */
    private fun runReplayable(transform: suspend (Preferences) -> Preferences, before: Preferences): Preferences {
        var outcome: Result<Preferences>? = null
        transform.createCoroutine(before, kotlin.coroutines.Continuation(kotlin.coroutines.EmptyCoroutineContext) { outcome = it }).resume(Unit)
        return (outcome ?: throw UnsupportedDesktopCapability("a suspending settings_prefs transform inside a settings operation",
            "the operation replays its edits synchronously on the actual snapshot")).getOrThrow()
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
