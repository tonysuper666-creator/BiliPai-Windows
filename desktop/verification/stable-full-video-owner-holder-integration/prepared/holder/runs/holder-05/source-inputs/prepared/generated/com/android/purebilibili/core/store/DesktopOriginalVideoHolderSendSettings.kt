package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalVideoHolderSendSettings {
    private val KEY_DANMAKU_SEND_COLOR = intPreferencesKey("danmaku_send_color")

    private val KEY_DANMAKU_SEND_MODE = intPreferencesKey("danmaku_send_mode")

    private val KEY_DANMAKU_SEND_FONT_SIZE = intPreferencesKey("danmaku_send_font_size")

    fun getDanmakuSendColor(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_DANMAKU_SEND_COLOR] ?: 16777215 }


    suspend fun setDanmakuSendColor(context: Context, value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_DANMAKU_SEND_COLOR] = value
        }
    }


    fun getDanmakuSendMode(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_DANMAKU_SEND_MODE] ?: 1 }


    suspend fun setDanmakuSendMode(context: Context, value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_DANMAKU_SEND_MODE] = value
        }
    }


    fun getDanmakuSendFontSize(context: Context): Flow<Int> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_DANMAKU_SEND_FONT_SIZE] ?: 25 }


    suspend fun setDanmakuSendFontSize(context: Context, value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_DANMAKU_SEND_FONT_SIZE] = value
        }
    }
    
    // --- 弹幕合并重复 (默认开启) ---

}
