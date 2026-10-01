package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalVideoOwnerSettings {
private const val CACHE_KEY_EXTERNAL_PLAYLIST_AUTO_CONTINUE = "external_playlist_auto_continue"
private const val CACHE_KEY_RESUME_PROMPT_ENABLED = "resume_prompt_enabled"
private const val CACHE_KEY_RESUME_PROMPT_SHOWN = "resume_prompt_shown"
private const val CACHE_KEY_VIDEO_NOTE_ENABLED = "video_note_enabled"
private val KEY_AUDIO_QUALITY = intPreferencesKey("audio_quality_preference")
private val KEY_PLAYBACK_CDN_PREFERENCE = stringPreferencesKey("playback_cdn_preference")
private val KEY_TRIPLE_JUMP_ENABLED = booleanPreferencesKey("triple_jump_enabled")
private val KEY_VIDEO_CODEC = stringPreferencesKey("video_codec_preference")
private val KEY_VIDEO_SECOND_CODEC = stringPreferencesKey("video_second_codec_preference")
private const val RESUME_PROMPT_CACHE_PREFS = "resume_prompt_cache"
private const val VIDEO_NOTE_CACHE_PREFS = "video_note_settings"

    fun getPlaybackCdnPreference(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Flow<String> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_PLAYBACK_CDN_PREFERENCE] ?: "base_url"
        }

    fun getVideoCodec(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Flow<String> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_VIDEO_CODEC] ?: "hev1" }

    suspend fun setVideoCodec(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext, value: String) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_VIDEO_CODEC] = value }
        // Sync to SharedPreferences for synchronous access
        context.getSharedPreferences("quality_settings", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .edit().putString("video_codec", value).apply()
    }

    fun getVideoCodecSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): String {
        return context.getSharedPreferences("quality_settings", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getString("video_codec", "hev1") ?: "hev1"
    }

    fun getVideoSecondCodec(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Flow<String> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_VIDEO_SECOND_CODEC] ?: "avc1" }

    suspend fun setVideoSecondCodec(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext, value: String) {
        context.settingsDataStore.edit { preferences -> preferences[KEY_VIDEO_SECOND_CODEC] = value }
        context.getSharedPreferences("quality_settings", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .edit().putString("video_second_codec", value).apply()
    }

    fun getVideoSecondCodecSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): String {
        return context.getSharedPreferences("quality_settings", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getString("video_second_codec", "avc1") ?: "avc1"
    }

    fun getAudioQuality(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> 
            val value = preferences[KEY_AUDIO_QUALITY] ?: -1
            android.util.Log.d("SettingsManager", "📻 getAudioQuality Flow emitting: $value")
            value 
        }

    fun getDataSaverModeSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): DataSaverMode {
        val value = context.getSharedPreferences("data_saver", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getInt("mode", DataSaverMode.MOBILE_ONLY.value)
        return DataSaverMode.fromValue(value)
    }

    fun getVideoNoteEnabledSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences(VIDEO_NOTE_CACHE_PREFS, com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_VIDEO_NOTE_ENABLED, true)
    }

    fun getTripleJumpEnabled(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_TRIPLE_JUMP_ENABLED] ?: false }

    fun getResumePlaybackPromptEnabledSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences(RESUME_PROMPT_CACHE_PREFS, com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_RESUME_PROMPT_ENABLED, true)
    }

    fun hasResumePlaybackPromptShown(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext, promptKey: String): Boolean {
        if (promptKey.isBlank()) return false
        val shownSet = context.getSharedPreferences(RESUME_PROMPT_CACHE_PREFS, com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getStringSet(CACHE_KEY_RESUME_PROMPT_SHOWN, emptySet())
            .orEmpty()
        return shownSet.contains(promptKey)
    }

    fun markResumePlaybackPromptShown(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext, promptKey: String) {
        if (promptKey.isBlank()) return
        val prefs = context.getSharedPreferences(RESUME_PROMPT_CACHE_PREFS, com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
        val shownSet = prefs.getStringSet(CACHE_KEY_RESUME_PROMPT_SHOWN, emptySet())
            .orEmpty()
            .toMutableSet()
        if (shownSet.contains(promptKey)) return
        if (shownSet.size >= 500) {
            shownSet.clear()
        }
        shownSet.add(promptKey)
        prefs.edit()
            .putStringSet(CACHE_KEY_RESUME_PROMPT_SHOWN, shownSet)
            .apply()
    }

    fun isEasterEggEnabledSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences("easter_egg", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean("enabled", false)  // 默认关闭
    }

    fun getAutoPlaySync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences("auto_play_cache", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean("auto_play_enabled", true)  // 默认开启
    }

    fun getExternalPlaylistAutoContinueSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences("auto_play_cache", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean(CACHE_KEY_EXTERNAL_PLAYLIST_AUTO_CONTINUE, true)
    }

    fun getAutoHighestQualitySync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences("quality_settings", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean("auto_highest_quality", false)
    }

    fun getShowOnlineCountSync(context: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext): Boolean {
        return context.getSharedPreferences("video_overlay_cache", com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext.MODE_PRIVATE)
            .getBoolean("show_online_count", false)
    }

enum class DataSaverMode(val value: Int, val label: String, val description: String) {
        OFF(0, "关闭", "不限制流量使用"),
        MOBILE_ONLY(1, "仅移动数据", "使用移动数据时自动省流量"),
        ALWAYS(2, "始终开启", "始终使用省流量模式");
        
        companion object {
            fun fromValue(value: Int): DataSaverMode = entries.find { it.value == value } ?: MOBILE_ONLY
        }
    }
}
