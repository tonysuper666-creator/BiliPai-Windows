package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
object DesktopOriginalVideoContentSettings {
    private val KEY_VIDEO_AI_SUMMARY_ENTRY_ENABLED = booleanPreferencesKey("video_ai_summary_entry_enabled")

    private val KEY_VIDEO_NOTE_ENABLED = booleanPreferencesKey("video_note_enabled")

    private val KEY_VIDEO_DETAIL_CHROME_SCROLL_HIDE_ENABLED =
        booleanPreferencesKey("video_detail_chrome_scroll_hide_enabled")

    fun getVideoAiSummaryEntryEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_VIDEO_AI_SUMMARY_ENTRY_ENABLED] ?: true }


    fun getVideoNoteEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_VIDEO_NOTE_ENABLED] ?: true }


    fun getVideoDetailChromeScrollHideEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[KEY_VIDEO_DETAIL_CHROME_SCROLL_HIDE_ENABLED] ?: false
        }


    private val KEY_SHOW_VIDEO_DETAIL_COMMENT_COUNT =
        booleanPreferencesKey("show_video_detail_comment_count")

    fun getShowVideoDetailCommentCount(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SHOW_VIDEO_DETAIL_COMMENT_COUNT] ?: true }


}
