package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
/** Original read policies; Root provides its same global preference context. */
internal object DesktopOriginalLinkedDockSettings {
    private val KEY_BOTTOM_BAR_LABEL_MODE = intPreferencesKey("bottom_bar_label_mode")
    //  [新增] 顶部标签显示模式 (0=图标+文字, 1=仅图标, 2=仅文字)

    private val KEY_BOTTOM_BAR_SEARCH_ENABLED = booleanPreferencesKey("bottom_bar_search_enabled")

    private val KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED =
        booleanPreferencesKey("linked_dock_merge_on_scroll_enabled")

    private val KEY_LIST_SCOPED_SEARCH_ENABLED = booleanPreferencesKey("list_scoped_search_enabled")

    fun getBottomBarLabelMode(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_LABEL_MODE] ?: 0 }  // 默认图标+文字

    fun getBottomBarSearchEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] ?: false }

    fun getLinkedDockMergeOnScrollEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences ->
                preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] ?: true
            }

    fun getListScopedSearchEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] ?: false }

}
