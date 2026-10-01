package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalPortraitSettings {
    private val KEY_AUTO_PLAY = booleanPreferencesKey("auto_play")

    private val KEY_EXTERNAL_PLAYLIST_AUTO_CONTINUE =
        booleanPreferencesKey("external_playlist_auto_continue")

    fun getAutoPlay(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_AUTO_PLAY] ?: true }


    fun getExternalPlaylistAutoContinue(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_EXTERNAL_PLAYLIST_AUTO_CONTINUE] ?: true }


    private val KEY_PREFETCH_VIDEO = booleanPreferencesKey("exp_prefetch_video")

    fun getPrefetchVideo(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_PREFETCH_VIDEO] ?: false }


    private val KEY_AUDIO_QUALITY = intPreferencesKey("audio_quality_preference")

    suspend fun setAudioQuality(context: Context, value: Int) {
        android.util.Log.d("SettingsManager", "📻 setAudioQuality called with: $value")
        val result = editSettingsAndCommitPrefs(
            context, "quality_settings",
            editSettings = {
                this[KEY_AUDIO_QUALITY] = value
                android.util.Log.d("SettingsManager", "📻 setAudioQuality DataStore written: $value")
            },
            editPrefs = { putInt("audio_quality", value) },
        )
        android.util.Log.d("SettingsManager", "📻 setAudioQuality SharedPrefs committed: $value, success=$result")
    }

    // --- 评论默认排序 (2=最新,3=最热) ---

}
