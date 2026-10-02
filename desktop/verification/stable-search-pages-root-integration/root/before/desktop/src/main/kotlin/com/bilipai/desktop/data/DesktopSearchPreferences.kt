package com.bilipai.desktop.data

import com.android.purebilibili.core.database.dao.SearchHistoryDao
import com.android.purebilibili.core.database.entity.SearchHistory
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files

/** Room's keyword primary key and timestamp query, persisted atomically per Windows account. */
internal class DesktopSearchHistoryDao(private val context: DesktopPluginContext) : SearchHistoryDao {
    private val preferences = context.getSharedPreferences("search_history", 0)
    private val lock = Any()
    private var entries = decodeSearchHistory(preferences.getString("entries", null))
    private val visible = MutableStateFlow(entries.sortedByDescending { it.timestamp }.take(20))
    override fun getAll(): StateFlow<List<SearchHistory>> = visible.asStateFlow()

    override suspend fun insert(history: SearchHistory) = withContext(Dispatchers.IO) {
        insertNow(history)
    }
    internal fun insertNow(history: SearchHistory) = synchronized(lock) { save(listOf(history) + entries.filter { it.keyword != history.keyword }) }
    internal fun deleteNow(history: SearchHistory) = synchronized(lock) { save(entries.filter { it.keyword != history.keyword }) }
    internal fun clearNow() = synchronized(lock) { save(emptyList()) }
    override suspend fun delete(history: SearchHistory) = withContext(Dispatchers.IO) {
        deleteNow(history)
    }
    override suspend fun clearAll() = withContext(Dispatchers.IO) { clearNow() }

    private fun save(next: List<SearchHistory>) {
        val encoded = buildJsonArray { next.forEach { row -> add(buildJsonObject {
            put("keyword", row.keyword); put("timestamp", row.timestamp)
        }) } }.toString()
        preferences.edit().putString("entries", encoded).apply()
        entries = next
        visible.value = next.sortedByDescending { it.timestamp }.take(20)
    }
}

internal fun decodeSearchHistory(encoded: String?): List<SearchHistory> {
    if (encoded == null) return emptyList()
    return Json.parseToJsonElement(encoded).jsonArray.map { element ->
        val row = element.jsonObject
        SearchHistory(requireNotNull(row["keyword"]?.jsonPrimitive?.content), requireNotNull(row["timestamp"]?.jsonPrimitive?.longOrNull))
    }.distinctBy { it.keyword }
}

class DesktopSearchPreferences(private val root: Path = DesktopLibrary.directoryForAccount(null)) {
    private val lock = Any()
    private var writesFrozen = false
    private val contexts = mutableMapOf<Long?, DesktopPluginContext>()
    private val histories = mutableMapOf<Long?, DesktopSearchHistoryDao>()
    private fun context(mid: Long?): DesktopPluginContext = synchronized(lock) {
        require(mid == null || mid > 0)
        contexts.getOrPut(mid) {
            checkWritesOpen()
            val accountRoot = if (mid == null) root else root.resolve("accounts").resolve(mid.toString())
            DesktopPluginContext(DesktopPluginStore(accountRoot.resolve("search")))
        }
    }
    private val global = context(null)
    private val privacy = global.getSharedPreferences("privacy_mode", 0)
    private val suggestions = global.getSharedPreferences("settings", 0)
    private val _privacyMode = MutableStateFlow(privacy.getBoolean("enabled", false))
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()
    /** Drain a currently accepted disk operation, then retire all held store generations. */
    fun freezeWritesForRestore(): Unit = synchronized(lock) {
        if (writesFrozen) return@synchronized
        writesFrozen = true
        contexts.values.forEach { it.store.freezeWrites() }
    }
    private fun checkWritesOpen() = check(!writesFrozen) { "搜索设置已关闭，不能写入或创建旧会话数据" }
    /** Same original synchronous privacy key, reading the atomic file across facade instances. */
    fun isPrivacyModeEnabledSync(): Boolean = synchronized(lock) { readPrivacyModeEnabledSync(root) }

    internal companion object {
        /** One original privacy file/key. No new facade, cookie jar or cached default. */
        internal fun readPrivacyModeEnabledSync(root: Path = DesktopLibrary.directoryForAccount(null)): Boolean {
            val file = root.resolve("search").resolve("plugin-settings.json")
            if (!Files.exists(file)) return false
            val document = Json.parseToJsonElement(Files.readString(file)).jsonObject
            return (document["privacy_mode"] as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: false
        }
    }
    private val _suggestionsEnabled = MutableStateFlow(suggestions.getBoolean("search_suggestions_enabled", true))
    val suggestionsEnabled: StateFlow<Boolean> = _suggestionsEnabled.asStateFlow()

    private fun dao(mid: Long?): DesktopSearchHistoryDao = synchronized(lock) {
        histories.getOrPut(mid) { DesktopSearchHistoryDao(context(mid)) }
    }
    fun history(mid: Long?): StateFlow<List<SearchHistory>> = dao(mid).getAll()
    suspend fun record(mid: Long?, keyword: String) {
        val value = keyword.trim()
        if (value.isEmpty()) return
        // Like SearchViewModel.saveHistory: incognito suppresses writes, not access to saved history.
        withContext(Dispatchers.IO) { synchronized(lock) {
            checkWritesOpen()
            if (!isPrivacyModeEnabledSync()) {
                val target = dao(mid)
                target.insertNow(SearchHistory(value))
            }
        } }
    }
    suspend fun delete(mid: Long?, history: SearchHistory) = withContext(Dispatchers.IO) { synchronized(lock) {
        checkWritesOpen(); dao(mid).deleteNow(history)
    } }
    suspend fun clear(mid: Long?) = withContext(Dispatchers.IO) { synchronized(lock) {
        checkWritesOpen(); dao(mid).clearNow()
    } }
    suspend fun setPrivacyMode(enabled: Boolean) = withContext(Dispatchers.IO) { synchronized(lock) {
        checkWritesOpen()
        privacy.edit().putBoolean("enabled", enabled).apply(); _privacyMode.value = enabled
    } }
    suspend fun setSuggestionsEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { synchronized(lock) {
        checkWritesOpen()
        suggestions.edit().putBoolean("search_suggestions_enabled", enabled).apply(); _suggestionsEnabled.value = enabled
    } }
}
