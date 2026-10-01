package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.stringPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Original canonical getters on Root's same settings namespace. No second SettingsManager/Store. */
object DesktopOriginalDownloadListSettings {
    private val KEY_DOWNLOAD_PATH = stringPreferencesKey("download_path")

    private val KEY_DOWNLOAD_EXPORT_TREE_URI = stringPreferencesKey("download_export_tree_uri")

    fun getDownloadPath(context: Context): Flow<String?> = context.settingsDataStore.data
        .map { preferences -> 
            preferences[KEY_DOWNLOAD_PATH]
        }

    /**
     *  设置自定义下载路径
     * 传入 null 重置为默认路径
     */

    fun getDownloadExportTreeUri(context: Context): Flow<String?> = context.settingsDataStore.data
        .map { preferences ->
            preferences[KEY_DOWNLOAD_EXPORT_TREE_URI]
        }

    fun getDefaultDownloadPath(context: Context): String {
        return com.bilipai.desktop.download.DesktopDownloadManager.defaultDownloadRoot().toAbsolutePath().normalize().toString()
    }

    // ========== 📉 省流量模式 ==========

}
