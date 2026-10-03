package com.android.bilipai.tv

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TvPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tv_preferences", Context.MODE_PRIVATE)
    var quality: Int
        get() = prefs.getInt("quality", 64)
        set(value) { prefs.edit().putInt("quality", value).apply() }
    var autoContinue: Boolean
        get() = prefs.getBoolean("auto_continue", false)
        set(value) { prefs.edit().putBoolean("auto_continue", value).apply() }
    var danmakuEnabled: Boolean
        get() = prefs.getBoolean("danmaku_enabled", true)
        set(value) { prefs.edit().putBoolean("danmaku_enabled", value).apply() }
    var privacyMode: Boolean
        get() = prefs.getBoolean("privacy_mode", false)
        set(value) { prefs.edit().putBoolean("privacy_mode", value).apply() }
    var searchHistory: List<String>
        get() = runCatching { Json.decodeFromString<List<String>>(prefs.getString("search_history", "[]") ?: "[]") }.getOrDefault(emptyList())
        private set(value) { prefs.edit().putString("search_history", Json.encodeToString(value)).apply() }
    private var lastParts: List<Pair<String, Long>>
        get() = runCatching { Json.decodeFromString<List<Pair<String, Long>>>(prefs.getString("last_parts", "[]") ?: "[]") }.getOrDefault(emptyList())
        set(value) { prefs.edit().putString("last_parts", Json.encodeToString(value)).apply() }

    fun lastPlayedCid(bvid: String, aid: Long): Long {
        val keys = listOfNotNull(bvid.takeIf { it.isNotBlank() }, aid.takeIf { it > 0 }?.let { "aid:$it" })
        val parts = lastParts
        return keys.firstNotNullOfOrNull { key -> parts.firstOrNull { it.first == key }?.second } ?: 0
    }

    fun rememberPart(bvid: String, aid: Long, cid: Long) {
        if (cid <= 0) return
        val keys = listOfNotNull(bvid.takeIf { it.isNotBlank() }, aid.takeIf { it > 0 }?.let { "aid:$it" })
        lastParts = (keys.map { it to cid } + lastParts.filterNot { it.first in keys }).take(100)
    }

    fun recordSearch(query: String) {
        val clean = query.trim()
        if (clean.isNotBlank()) searchHistory = (listOf(clean) + searchHistory.filter { it != clean }).take(20)
    }
    fun clearSearchHistory() { searchHistory = emptyList() }
}
