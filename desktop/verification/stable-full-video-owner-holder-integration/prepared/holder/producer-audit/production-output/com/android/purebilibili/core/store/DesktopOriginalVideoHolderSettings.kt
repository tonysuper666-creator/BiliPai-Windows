package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalVideoHolderSettings {
    private val KEY_LONG_PRESS_SPEED_HINT_SCALE =
        floatPreferencesKey("long_press_speed_hint_scale")

    private val KEY_LONG_PRESS_SPEED_HINT_ALPHA =
        floatPreferencesKey("long_press_speed_hint_alpha")

    fun getLongPressSpeedHintScale(context: Context): Flow<Float> = context.settingsDataStore.data
        .map { preferences ->
            normalizeLongPressSpeedHintScale(
                preferences[KEY_LONG_PRESS_SPEED_HINT_SCALE] ?: LONG_PRESS_SPEED_HINT_DEFAULT_SCALE
            )
        }


    fun getLongPressSpeedHintAlpha(context: Context): Flow<Float> = context.settingsDataStore.data
        .map { preferences ->
            normalizeLongPressSpeedHintAlpha(
                preferences[KEY_LONG_PRESS_SPEED_HINT_ALPHA] ?: LONG_PRESS_SPEED_HINT_DEFAULT_ALPHA
            )
        }


    private val KEY_PORTRAIT_ONLY_VERTICAL_RECOMMENDATIONS =
        booleanPreferencesKey("portrait_only_vertical_recommendations")

    fun getPortraitOnlyVerticalRecommendations(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_PORTRAIT_ONLY_VERTICAL_RECOMMENDATIONS] ?: false
        }


    private val KEY_QUALITY_SWITCH_FAILURE_DIALOG_ENABLED =
        booleanPreferencesKey("quality_switch_failure_dialog_enabled")

    private val KEY_QUALITY_SWITCH_FAILURE_DIALOG_ONCE_ENABLED =
        booleanPreferencesKey("quality_switch_failure_dialog_once_enabled")

    private val KEY_QUALITY_SWITCH_FAILURE_DIALOG_SHOWN =
        booleanPreferencesKey("quality_switch_failure_dialog_shown")

    fun getQualitySwitchFailureDialogEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_QUALITY_SWITCH_FAILURE_DIALOG_ENABLED]
                ?: DEFAULT_QUALITY_SWITCH_FAILURE_DIALOG_ENABLED
        }


    fun getQualitySwitchFailureDialogOnceEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_QUALITY_SWITCH_FAILURE_DIALOG_ONCE_ENABLED]
                ?: DEFAULT_QUALITY_SWITCH_FAILURE_DIALOG_ONCE_ENABLED
        }


    suspend fun setQualitySwitchFailureDialogOnceEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_QUALITY_SWITCH_FAILURE_DIALOG_ONCE_ENABLED] = enabled
            if (!enabled) {
                preferences.remove(KEY_QUALITY_SWITCH_FAILURE_DIALOG_SHOWN)
            }
        }
    }


    fun getQualitySwitchFailureDialogShown(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_QUALITY_SWITCH_FAILURE_DIALOG_SHOWN] ?: false
        }


    suspend fun markQualitySwitchFailureDialogShown(context: Context) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_QUALITY_SWITCH_FAILURE_DIALOG_SHOWN] = true
        }
    }


}
internal const val DEFAULT_QUALITY_SWITCH_FAILURE_DIALOG_ENABLED = true
internal const val DEFAULT_QUALITY_SWITCH_FAILURE_DIALOG_ONCE_ENABLED = false
