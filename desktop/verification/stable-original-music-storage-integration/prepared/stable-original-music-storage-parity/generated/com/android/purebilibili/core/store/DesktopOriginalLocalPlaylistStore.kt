package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
object DesktopOriginalLocalPlaylistStore {
    private val KEY_LOCAL_PLAYLISTS = stringPreferencesKey("local_playlists_v1")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun playlists(context: Context): Flow<List<LocalPlaylist>> =
        context.settingsDataStore.data.map { prefs ->
            val raw = prefs[KEY_LOCAL_PLAYLISTS] ?: return@map emptyList()
            runCatching { json.decodeFromString<List<LocalPlaylist>>(raw) }.getOrDefault(emptyList())
        }

    suspend fun savePlaylist(context: Context, playlist: LocalPlaylist) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_LOCAL_PLAYLISTS]
                ?.let { raw -> runCatching { json.decodeFromString<List<LocalPlaylist>>(raw) }.getOrNull() }
                .orEmpty()
                .filterNot { it.id == playlist.id }
            prefs[KEY_LOCAL_PLAYLISTS] = json.encodeToString(listOf(playlist) + current)
        }
    }

    suspend fun deletePlaylist(context: Context, playlistId: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_LOCAL_PLAYLISTS]
                ?.let { raw -> runCatching { json.decodeFromString<List<LocalPlaylist>>(raw) }.getOrNull() }
                .orEmpty()
                .filterNot { it.id == playlistId }
            prefs[KEY_LOCAL_PLAYLISTS] = json.encodeToString(current)
        }
    }

    suspend fun addToPlaylist(context: Context, playlistId: String, items: List<LocalPlaylistItem>) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[KEY_LOCAL_PLAYLISTS]
                ?.let { raw -> runCatching { json.decodeFromString<List<LocalPlaylist>>(raw) }.getOrNull() }
                .orEmpty()
                .toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index >= 0) {
                val target = current[index]
                val mergedItems = target.items + items.filter { candidate ->
                    target.items.none { it.bvid == candidate.bvid }
                }
                current[index] = target.copy(items = mergedItems)
            }
            prefs[KEY_LOCAL_PLAYLISTS] = json.encodeToString(current)
        }
    }
}

