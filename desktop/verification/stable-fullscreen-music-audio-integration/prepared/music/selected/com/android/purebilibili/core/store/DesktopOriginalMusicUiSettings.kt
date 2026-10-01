package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
object DesktopOriginalMusicUiSettings {

    private val KEY_AUDIO_NOW_PLAYING_BAR_ENABLED = booleanPreferencesKey("audio_now_playing_bar_enabled")

    private val KEY_MUSIC_LYRICS_UI_STYLE = intPreferencesKey("music_lyrics_ui_style")

    fun getStartupAutoPlayEnabledSync(context: Context): Boolean {
        return context.getSharedPreferences("mini_player", Context.MODE_PRIVATE)
            .getBoolean("startup_auto_play_enabled", false)
    }


    fun getAudioNowPlayingBarEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_AUDIO_NOW_PLAYING_BAR_ENABLED] ?: true }
        .onEach { value ->
            context.getSharedPreferences("mini_player", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("audio_now_playing_bar_enabled", value)
                .apply()
        }


    enum class MusicLyricsUiStyle(val value: Int, val label: String) {
        CLASSIC(0, "经典"),
        IMMERSIVE(1, "沉浸");

        fun next(): MusicLyricsUiStyle = when (this) {
            CLASSIC -> IMMERSIVE
            IMMERSIVE -> CLASSIC
        }

        companion object {
            fun fromValue(value: Int): MusicLyricsUiStyle = when (value) {
                1 -> IMMERSIVE
                else -> CLASSIC
            }
        }
    }


    fun getMusicLyricsUiStyle(context: Context): Flow<MusicLyricsUiStyle> =
        context.settingsDataStore.data.map { preferences ->
            MusicLyricsUiStyle.fromValue(
                preferences[KEY_MUSIC_LYRICS_UI_STYLE] ?: MusicLyricsUiStyle.CLASSIC.value
            )
        }


    suspend fun setMusicLyricsUiStyle(context: Context, style: MusicLyricsUiStyle) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_MUSIC_LYRICS_UI_STYLE] = style.value
        }
    }


}
