package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopOriginalTabletAudioSettings {
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


    private val KEY_TABLET_SECONDARY_DEFAULT_TAB = intPreferencesKey("tablet_secondary_default_tab")

    fun getTabletSecondaryDefaultTab(context: Context): Flow<TabletSecondaryDefaultTab> =
        context.settingsDataStore.data
            .map { preferences ->
                TabletSecondaryDefaultTab.fromValue(
                    preferences[KEY_TABLET_SECONDARY_DEFAULT_TAB] ?: TabletSecondaryDefaultTab.RELATED.value
                )
            }


    suspend fun setTabletSecondaryDefaultTab(
        context: Context,
        tab: TabletSecondaryDefaultTab,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_TABLET_SECONDARY_DEFAULT_TAB] = tab.value
        }
    }


}
