package com.android.purebilibili.core.store.player
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopOriginalVideoPlayerSettings {
    private val keyPlayerInsightMode = stringPreferencesKey("player_insight_mode")

    private const val legacyAppPrefs = "app_prefs"

    private const val legacyShowStatsKey = "show_stats"

    private const val cachePlayerInsightModeKey = "player_insight_mode_cache"

    enum class PlayerInsightMode {
        OFF,
        SMART,
        ALWAYS
    }

    fun getPlayerInsightMode(context: Context): Flow<PlayerInsightMode> = context.settingsDataStore.data
        .map { preferences ->
            val legacyPreferences = context.getSharedPreferences(legacyAppPrefs, Context.MODE_PRIVATE)
            resolvePlayerInsightMode(
                storedMode = preferences[keyPlayerInsightMode],
                legacyPreferencePresent = legacyPreferences.all.containsKey(legacyShowStatsKey),
                legacyStatsEnabled = legacyPreferences.getBoolean(legacyShowStatsKey, false)
            )
        }

    suspend fun setPlayerInsightMode(context: Context, mode: PlayerInsightMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[keyPlayerInsightMode] = mode.name
        }
        context.getSharedPreferences(legacyAppPrefs, Context.MODE_PRIVATE)
            .edit()
            .putString(cachePlayerInsightModeKey, mode.name)
            .putBoolean(legacyShowStatsKey, mode != PlayerInsightMode.OFF)
            .apply()
    }

    fun getPlayerInsightModeSync(context: Context): PlayerInsightMode {
        val preferences = context.getSharedPreferences(legacyAppPrefs, Context.MODE_PRIVATE)
        return resolvePlayerInsightMode(
            storedMode = preferences.getString(cachePlayerInsightModeKey, null),
            legacyPreferencePresent = preferences.all.containsKey(legacyShowStatsKey),
            legacyStatsEnabled = preferences.getBoolean(legacyShowStatsKey, false)
        )
    }

    internal fun resolvePlayerInsightMode(
        storedMode: String?,
        legacyPreferencePresent: Boolean,
        legacyStatsEnabled: Boolean
    ): PlayerInsightMode {
        PlayerInsightMode.entries.firstOrNull { it.name == storedMode }?.let { return it }
        // 全新安装 / 无历史偏好：默认关闭，避免首播叠加洞察浮层
        if (!legacyPreferencePresent) return PlayerInsightMode.OFF
        return if (legacyStatsEnabled) {
            PlayerInsightMode.ALWAYS
        } else {
            PlayerInsightMode.OFF
        }
    }

}
