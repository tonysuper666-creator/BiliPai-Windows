package com.bilipai.desktop.brand

import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import com.bilipai.desktop.ui.playerBooleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Original SettingsManager maid preference, adapted only to Root's actual settings context. */
internal object DesktopMaidMotionPreferences {
    private val KEY_MAID_LOTTIE_ANIMATION_ENABLED =
        playerBooleanPreferencesKey("maid_lottie_animation_enabled")

    fun getMaidLottieAnimationEnabled(context: DesktopOriginalPlayerSettingsContext): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_MAID_LOTTIE_ANIMATION_ENABLED] ?: true }

    suspend fun setMaidLottieAnimationEnabled(context: DesktopOriginalPlayerSettingsContext, value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_MAID_LOTTIE_ANIMATION_ENABLED] = value
        }
    }
}
