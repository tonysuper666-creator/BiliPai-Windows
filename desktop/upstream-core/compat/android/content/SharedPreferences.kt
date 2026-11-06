package android.content

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getLong(key: String, defValue: Long): Long
    fun getInt(key: String, defValue: Int): Int
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getFloat(key: String, defValue: Float): Float
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor
    /** Listeners need change notifications from the backing store; an implementation without them fails loudly. */
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener): Unit =
        throw com.bilipai.desktop.compat.UnsupportedDesktopCapability("SharedPreferences change listener", "this preferences store has no change notifications")
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener): Unit =
        throw com.bilipai.desktop.compat.UnsupportedDesktopCapability("SharedPreferences change listener", "this preferences store has no change notifications")
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putLong(key: String, value: Long): Editor
        fun putInt(key: String, value: Int): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putFloat(key: String, value: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}
