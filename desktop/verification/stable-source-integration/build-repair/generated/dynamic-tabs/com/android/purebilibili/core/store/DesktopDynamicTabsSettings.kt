// GENERATED from app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt; do not edit.
// LF-normalized SHA-256: 680005e1f25e8a365d30f0c78c988765e7d2140008c57d9bf31d859c5b835b1c
package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopDynamicTabsSettings {
    private val KEY_DYNAMIC_TAB_VISIBLE_TABS = stringPreferencesKey("dynamic_tab_visible_tabs")
    private val KEY_DYNAMIC_TAB_ORDER = stringPreferencesKey("dynamic_tab_order")
    private val KEY_DYNAMIC_ALL_TAB_HORIZONTAL_USER_LIST_VISIBLE =
    booleanPreferencesKey("dynamic_all_tab_horizontal_user_list_visible")
    private const val DEFAULT_DYNAMIC_TAB_VISIBLE = "all,video,pgc,article,up"
    private const val DEFAULT_DYNAMIC_TAB_ORDER = "all,video,pgc,article,up"

    fun getDynamicTabVisibleTabs(context: Context): Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        val tabsString = prefs[KEY_DYNAMIC_TAB_VISIBLE_TABS] ?: DEFAULT_DYNAMIC_TAB_VISIBLE
        tabsString.split(",").filter { it.isNotBlank() }.toSet()
    }

    suspend fun setDynamicTabVisibleTabs(context: Context, tabs: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_TAB_VISIBLE_TABS] = tabs.joinToString(",")
        }
    }

    fun getDynamicTabOrder(context: Context): Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        val orderString = prefs[KEY_DYNAMIC_TAB_ORDER] ?: DEFAULT_DYNAMIC_TAB_ORDER
        orderString.split(",").filter { it.isNotBlank() }
    }

    suspend fun setDynamicTabOrder(context: Context, order: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_TAB_ORDER] = order.joinToString(",")
        }
    }

    fun getDynamicAllTabHorizontalUserListVisible(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { prefs ->
            prefs[KEY_DYNAMIC_ALL_TAB_HORIZONTAL_USER_LIST_VISIBLE] ?: false
        }

    suspend fun setDynamicAllTabHorizontalUserListVisible(context: Context, visible: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_ALL_TAB_HORIZONTAL_USER_LIST_VISIBLE] = visible
        }
    }
}
