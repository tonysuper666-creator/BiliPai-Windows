package com.bilipai.desktop.plugins

// Upstream code compiled from the merged tree takes android.content.Context.
// The plugin store stays the single persistence owner for those preferences.
fun DesktopPluginContext.asAndroidContext(): android.content.Context = DesktopAndroidContext(this)

private class DesktopAndroidContext(private val desktop: DesktopPluginContext) : android.content.Context(desktop.filesDir) {
    override val applicationContext: android.content.Context get() = this
    override val filesDir: java.io.File get() = desktop.filesDir
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
        PluginSharedPreferences(desktop.getSharedPreferences(name, mode))
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
