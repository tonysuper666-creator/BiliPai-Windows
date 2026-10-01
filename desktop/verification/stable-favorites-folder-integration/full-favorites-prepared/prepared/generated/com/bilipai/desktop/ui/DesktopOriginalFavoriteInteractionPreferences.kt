package com.bilipai.desktop.ui
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
const val DEFAULT_FAVORITE_QUICK_SAVE_DEFAULT_FOLDER = false

class DesktopFavoriteInteractionPreferences(private val store:com.bilipai.desktop.plugins.DesktopPluginStore) {
    init { store.requireObjectNamespace("settings") }
    private val quickSaveDefaultFolderKey =
        favoriteBooleanKey("favorite_quick_save_default_folder")

    fun getQuickSaveDefaultFolder(): Flow<Boolean> =
        store.snapshot("settings").map { preferences ->
            preferences[quickSaveDefaultFolderKey] ?: DEFAULT_FAVORITE_QUICK_SAVE_DEFAULT_FOLDER
        }

    suspend fun setQuickSaveDefaultFolder(enabled: Boolean) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            store.update("settings", mapOf("favorite_quick_save_default_folder" to kotlinx.serialization.json.JsonPrimitive(enabled)))
        }
    }
}
