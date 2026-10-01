// File: core/store/PlayHistoryStore.kt
package com.android.purebilibili.core.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 听视频/音乐播放记录：最近播放列表（含播放次数）+ 冷启动续播会话。 */
@Serializable
data class PlayHistoryEntry(
    val bvid: String,
    val cid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    val durationSec: Long = 0L,
    val playCount: Int = 1,
    val lastPlayedAtMs: Long = 0L
)

@Serializable
data class PlayLastSession(
    val bvid: String,
    val cid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",
    val positionMs: Long = 0L,
    val savedAtMs: Long = 0L
)

object PlayHistoryStore {
    private val KEY_RECENT = stringPreferencesKey("audio_play_history_v1")
    private val KEY_LAST_SESSION = stringPreferencesKey("audio_last_session_v1")

    private const val MAX_ENTRIES = 300

    private val json = Json { ignoreUnknownKeys = true }

    fun recent(context: Context, limit: Int = 100): Flow<List<PlayHistoryEntry>> =
        context.settingsDataStore.data.map { prefs ->
            decode(prefs[KEY_RECENT]).take(limit)
        }

    fun lastSession(context: Context): Flow<PlayLastSession?> =
        context.settingsDataStore.data.map { prefs -> decodeSession(prefs[KEY_LAST_SESSION]) }

    suspend fun record(context: Context, entry: PlayHistoryEntry) {
        context.settingsDataStore.edit { prefs ->
            val existing = decode(prefs[KEY_RECENT]).toMutableList()
            val index = existing.indexOfFirst { it.bvid == entry.bvid }
            if (index >= 0) {
                val previous = existing.removeAt(index)
                existing.add(
                    0,
                    previous.copy(
                        cid = entry.cid,
                        title = entry.title.ifBlank { previous.title },
                        cover = entry.cover.ifBlank { previous.cover },
                        owner = entry.owner.ifBlank { previous.owner },
                        durationSec = entry.durationSec,
                        playCount = previous.playCount + 1,
                        lastPlayedAtMs = entry.lastPlayedAtMs
                    )
                )
            } else {
                existing.add(0, entry)
            }
            prefs[KEY_RECENT] = json.encodeToString(existing.take(MAX_ENTRIES))
        }
    }

    suspend fun saveLastSession(context: Context, session: PlayLastSession) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_LAST_SESSION] = json.encodeToString(session)
        }
    }

    private fun decode(raw: String?): List<PlayHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PlayHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeSession(raw: String?): PlayLastSession? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<PlayLastSession>(raw) }.getOrNull()
    }
}
