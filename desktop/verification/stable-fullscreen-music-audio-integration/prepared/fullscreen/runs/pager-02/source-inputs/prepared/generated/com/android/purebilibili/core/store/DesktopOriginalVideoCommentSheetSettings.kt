package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
internal object DesktopOriginalVideoCommentSheetSettings {
    private val KEY_COMMENT_DEFAULT_SORT_MODE = intPreferencesKey("comment_default_sort_mode")

    suspend fun setCommentDefaultSortMode(context: Context, value: Int) {
        val normalized = if (value == 2 || value == 3) value else 3
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_COMMENT_DEFAULT_SORT_MODE] = normalized
        }
        context.getSharedPreferences("comment_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("default_sort_mode", normalized)
            .apply()
    }


}
