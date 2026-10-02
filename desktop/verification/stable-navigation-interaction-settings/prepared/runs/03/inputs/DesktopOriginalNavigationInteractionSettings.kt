package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.settings.homeCardVisualBooleanKey as booleanPreferencesKey
import com.bilipai.desktop.settings.homeCardVisualDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
/** Original individual SettingsManager getters/setters, same Root global settings backing. */
internal object DesktopOriginalNavigationInteractionSettings {
    private val KEY_BOTTOM_BAR_FLOATING = booleanPreferencesKey("bottom_bar_floating")
    private val KEY_BOTTOM_BAR_SEARCH_ENABLED = booleanPreferencesKey("bottom_bar_search_enabled")
    private val KEY_CARD_ANIMATION_ENABLED = booleanPreferencesKey("card_animation_enabled")
    private val KEY_CARD_TRANSITION_ENABLED = booleanPreferencesKey("card_transition_enabled")
    private val KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED =
            booleanPreferencesKey("linked_dock_merge_on_scroll_enabled")
    private val KEY_LIST_SCOPED_SEARCH_ENABLED = booleanPreferencesKey("list_scoped_search_enabled")
    private val KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED =
            booleanPreferencesKey("navigation_icon_cross_scale_enabled")

    fun getBottomBarFloating(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_FLOATING] ?: true }

    suspend fun setBottomBarFloating(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_BOTTOM_BAR_FLOATING] = value }
    }

    fun getNavigationIconCrossScaleEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] ?: false
        }

    suspend fun setNavigationIconCrossScaleEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_NAVIGATION_ICON_CROSS_SCALE_ENABLED] = value
        }
    }

    fun getBottomBarSearchEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] ?: false }

    suspend fun setBottomBarSearchEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_BOTTOM_BAR_SEARCH_ENABLED] = value
        }
    }

    fun getLinkedDockMergeOnScrollEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data
            .map { preferences ->
                preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] ?: true
            }

    suspend fun setLinkedDockMergeOnScrollEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED] = value
        }
    }

    fun getListScopedSearchEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] ?: false }

    suspend fun setListScopedSearchEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LIST_SCOPED_SEARCH_ENABLED] = value
        }
    }

    fun getCardAnimationEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CARD_ANIMATION_ENABLED] ?: false }

    suspend fun setCardAnimationEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_CARD_ANIMATION_ENABLED] = value }
    }

    fun getCardTransitionEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_CARD_TRANSITION_ENABLED] ?: true }

    suspend fun setCardTransitionEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_CARD_TRANSITION_ENABLED] = value }
    }
}
