package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import com.android.purebilibili.core.ui.components.AppTagChipSize
import kotlinx.coroutines.flow.*
data class PlayerControlVisibilitySettings(
    val showCastButton: Boolean = true,
    val showFollowButton: Boolean = true,
    /** 紧凑播放器控件：隐藏顶栏分享并收紧顶底栏间距。默认经典布局。 */
    val compactPlayerChrome: Boolean = false
)

internal object DesktopOriginalVideoInfoSettings {
    private val KEY_VIDEO_INFO_DEFAULT_EXPANDED = booleanPreferencesKey("video_info_default_expanded")

    private val KEY_VIDEO_TAG_SIZE_PRESET = intPreferencesKey("video_tag_size_preset")

    private val KEY_SHOW_PLAYER_CAST_BUTTON = booleanPreferencesKey("show_player_cast_button")

    private val KEY_SHOW_VIDEO_FOLLOW_BUTTON = booleanPreferencesKey("show_video_follow_button")

    private val KEY_COMPACT_PLAYER_CHROME = booleanPreferencesKey("compact_player_chrome")

    private val KEY_TRIPLE_JUMP_ENABLED = booleanPreferencesKey("triple_jump_enabled")

    private val KEY_EASTER_EGG_ENABLED = booleanPreferencesKey("easter_egg_enabled")
    
    // --- 彩蛋功能开关（控制下拉刷新趣味提示等）---

    fun getVideoInfoDefaultExpanded(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_VIDEO_INFO_DEFAULT_EXPANDED] ?: false }

    suspend fun setVideoInfoDefaultExpanded(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_VIDEO_INFO_DEFAULT_EXPANDED] = enabled
        }
    }

    /** UP 主视频声明(如"虚构演绎,请勿过度解读")在详情页是否显示,默认开。 */

    fun getVideoTagSizePreset(context: Context): Flow<AppTagChipSize> = context.settingsDataStore.data
        .map { preferences ->
            AppTagChipSize.fromValue(preferences[KEY_VIDEO_TAG_SIZE_PRESET] ?: AppTagChipSize.STANDARD.value)
        }

    suspend fun setVideoTagSizePreset(context: Context, size: AppTagChipSize) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_VIDEO_TAG_SIZE_PRESET] = size.value
        }
    }

    fun getPlayerControlVisibilitySettings(
        context: Context
    ): Flow<PlayerControlVisibilitySettings> = context.settingsDataStore.data
        .map { preferences ->
            PlayerControlVisibilitySettings(
                showCastButton = preferences[KEY_SHOW_PLAYER_CAST_BUTTON] ?: true,
                showFollowButton = preferences[KEY_SHOW_VIDEO_FOLLOW_BUTTON] ?: true,
                compactPlayerChrome = preferences[KEY_COMPACT_PLAYER_CHROME] ?: false
            )
        }
        .distinctUntilChanged()

    suspend fun setShowPlayerCastButton(context: Context, visible: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_PLAYER_CAST_BUTTON] = visible }
    }

    suspend fun setShowVideoFollowButton(context: Context, visible: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_VIDEO_FOLLOW_BUTTON] = visible }
    }

    suspend fun setCompactPlayerChrome(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_COMPACT_PLAYER_CHROME] = enabled }
    }

    fun getTripleJumpEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_TRIPLE_JUMP_ENABLED] ?: false }

    fun getEasterEggEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_EASTER_EGG_ENABLED] ?: false }  // 默认关闭

    suspend fun setEasterEggEnabled(context: Context, value: Boolean) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_EASTER_EGG_ENABLED] = value }
        //  同步到 SharedPreferences，供同步读取使用
        context.getSharedPreferences("easter_egg", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", value).apply()
    }
    
    //  同步读取彩蛋开关（用于 ViewModel）

    fun isEasterEggEnabledSync(context: Context): Boolean {
        return context.getSharedPreferences("easter_egg", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)  // 默认关闭
    }
    
    // ==========  播放器设置 ==========

}
