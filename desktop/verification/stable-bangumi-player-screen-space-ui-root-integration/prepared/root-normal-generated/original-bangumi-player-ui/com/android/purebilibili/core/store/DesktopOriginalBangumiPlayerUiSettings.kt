package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
internal object DesktopOriginalBangumiPlayerUiSettings {
    private val KEY_SPONSOR_BLOCK_ENABLED = booleanPreferencesKey("sponsor_block_enabled")

    private val KEY_AUTO_SKIP_OP_ED = booleanPreferencesKey("exp_auto_skip_op_ed")

    private val KEY_SPONSOR_BLOCK_AUTO_SKIP = booleanPreferencesKey("sponsor_block_auto_skip")

    fun getSponsorBlockEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SPONSOR_BLOCK_ENABLED] ?: false }  // 默认关闭

    fun getAutoSkipOpEd(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_AUTO_SKIP_OP_ED] ?: false }

    fun getSponsorBlockAutoSkip(context: Context): Flow<Boolean> = context.settingsDataStore.data
        .map { preferences -> preferences[KEY_SPONSOR_BLOCK_AUTO_SKIP] ?: true }  // 默认自动跳过

}
