package com.bilipai.desktop.plugins

import kotlinx.serialization.json.JsonPrimitive

// Upstream code compiled from the merged tree takes android.content.Context.
// The plugin store stays the single persistence owner for those preferences.
fun DesktopPluginContext.asAndroidContext(): android.content.Context = DesktopAndroidContext(this, null)

/** The same Context whose SharedPreferences and settings_prefs DataStore commits go through the caller's admission (the original write path:
 * [checkRequest] before and after, a write permit minted by [acquirePermit], CAS on the settings document). */
internal fun DesktopPluginContext.asAndroidContext(checkRequest: () -> Unit,
    acquirePermit: () -> DesktopPluginStore.OriginalPreferenceWritePermit): android.content.Context =
    DesktopAndroidContext(this, DesktopAndroidContext.Admission(checkRequest, acquirePermit))

private class DesktopAndroidContext(private val desktop: DesktopPluginContext, private val admission: Admission?) : android.content.Context(desktop.filesDir),
    androidx.datastore.preferences.DesktopPreferencesDataStoreOwner {
    override val applicationContext: android.content.Context get() = this
    override val filesDir: java.io.File get() = desktop.filesDir
    // Upstream settings_prefs is the "settings" namespace of the same document. With an admission it writes through that
    // admission, as the SharedPreferences below do; without one, through the store's own request admission (the plugin
    // preferences write the same way), with no page owner.
    private val settingsStore by lazy {
        if (admission != null) com.bilipai.desktop.settings.DesktopSettingsDocumentDataStore(desktop.store, "settings",
            checkRequest = admission.checkRequest, acquirePermit = { _ -> admission.acquirePermit() })
        else com.bilipai.desktop.settings.DesktopSettingsDocumentDataStore(desktop.store, "settings",
            checkRequest = { DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal() },
            acquirePermit = { check -> check(); DesktopPluginStore.OriginalPreferenceWritePermit(desktop.store) })
    }
    override fun desktopPreferencesDataStore(name: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> = when (name) {
        "settings_prefs" -> settingsStore
        else -> throw com.bilipai.desktop.compat.UnsupportedDesktopCapability("DataStore \"$name\"", "the desktop keeps only settings_prefs in Root's settings document")
    }
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        if (admission == null) PluginSharedPreferences(desktop.getSharedPreferences(name, mode))
        else AdmittedSharedPreferences(PluginSharedPreferences(desktop.getSharedPreferences(name, mode)), desktop.store, name, admission)

    class Admission(val checkRequest: () -> Unit, val acquirePermit: () -> DesktopPluginStore.OriginalPreferenceWritePermit)
}

/** Reads as the plain plugin preferences; commits the collected edits once, through the admission. */
private class AdmittedSharedPreferences(private val reads: android.content.SharedPreferences, private val store: DesktopPluginStore,
    private val name: String, private val admission: DesktopAndroidContext.Admission) : android.content.SharedPreferences by reads {
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val changes = linkedMapOf<String, kotlinx.serialization.json.JsonElement?>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value?.let(::JsonPrimitive) }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.let { kotlinx.serialization.json.JsonArray(it.map(::JsonPrimitive)) } }
        override fun putLong(key: String, value: Long) = apply { changes[key] = JsonPrimitive(value.toString()) }
        override fun putInt(key: String, value: Int) = apply { changes[key] = JsonPrimitive(value) }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = JsonPrimitive(value) }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = JsonPrimitive(value.toString()) }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear(): android.content.SharedPreferences.Editor = apply { reads.all.keys.forEach { changes[it] = null } }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            val edits = changes.toMap()
            store.updateOriginalFromSnapshot(name, admission.checkRequest, admission.acquirePermit) { Unit to edits }
        }
    }
}

private class PluginSharedPreferences(private val prefs: DesktopPluginPreferences) : android.content.SharedPreferences {
    override fun getString(key: String, defValue: String?) = prefs.getString(key, defValue)
    override fun getStringSet(key: String, defValues: Set<String>?) = prefs.getStringSet(key, defValues)
    override fun getLong(key: String, defValue: Long) = prefs.getString(key, null)?.toLongOrNull() ?: defValue
    override fun getInt(key: String, defValue: Int) = prefs.getInt(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = prefs.getBoolean(key, defValue)
    override fun getFloat(key: String, defValue: Float) = prefs.getString(key, null)?.toFloatOrNull() ?: defValue
    override fun contains(key: String) = key in prefs.all
    override val all: Map<String, *> get() = prefs.all
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val editor = prefs.edit()
        override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
        override fun putStringSet(key: String, values: Set<String>?) = apply { editor.putStringSet(key, values) }
        override fun putLong(key: String, value: Long) = apply { editor.putString(key, value.toString()) }
        override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
        override fun putFloat(key: String, value: Float) = apply { editor.putString(key, value.toString()) }
        override fun remove(key: String) = apply { editor.remove(key) }
        override fun clear() = apply { editor.clear() }
        override fun commit(): Boolean { editor.apply(); return true }
        override fun apply() { editor.apply() }
    }
}
