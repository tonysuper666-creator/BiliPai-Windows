package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.plugin.BiliPaiFeedFilterConfig
import com.android.purebilibili.feature.plugin.DesktopFeedFilterEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.lang.ref.WeakReference

/** Real Windows persistence for the original plugin keys and preference namespaces. */
class DesktopPluginStore(val root: Path) {
    private val backing = backingFor(root)
    val feedFilterConfig: StateFlow<BiliPaiFeedFilterConfig> = backing.feedFilterConfig.asStateFlow()
    val feedFilterEnabled: StateFlow<Boolean> = backing.feedFilterEnabled.asStateFlow()

    suspend fun setFeedFilterConfig(config: BiliPaiFeedFilterConfig) {
        validateFeedFilter(config)
        withContext(Dispatchers.IO) { update("plugin_prefs", mapOf("plugin_config_bilipai_feed_filter" to JsonPrimitive(json.encodeToString(config)))) }
    }

    suspend fun setFeedFilterEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        update("plugin_prefs", mapOf("plugin_enabled_bilipai_feed_filter" to JsonPrimitive(enabled)))
    }

    internal fun snapshot(name: String): StateFlow<DesktopPreferenceSnapshot> = synchronized(backing) {
        backing.snapshots.getOrPut(name) { MutableStateFlow(DesktopPreferenceSnapshot(preferences(name))) }.asStateFlow()
    }

    /** Validate the cached generation; never reload disk or revive a frozen backing. */
    internal fun requireObjectNamespace(name: String) = synchronized(backing) {
        val namespace = backing.document[name]
        require(namespace == null || namespace is JsonObject) { "设置命名空间格式无效" }
    }

    internal fun preferences(name: String): JsonObject = synchronized(backing) {
        backing.document[name] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Compute read-dependent changes under the same reentrant JVM backing monitor. */
    internal fun updateFromSnapshot(name: String, block: (DesktopPreferenceSnapshot) -> Map<String, JsonElement?>) = synchronized(backing) {
        check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
        requireObjectNamespace(name)
        val values = block(DesktopPreferenceSnapshot(preferences(name)))
        update(name, values)
    }

    /** Publish a new snapshot only after the atomic replacement succeeds. */
    internal fun update(name: String, values: Map<String, JsonElement?>, clear: Boolean = false) = synchronized(backing) {
        check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
        val updated = (if (clear) emptyMap() else preferences(name)).toMutableMap()
        values.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
        if (name == "plugin_prefs") {
            updated["plugin_config_bilipai_feed_filter"]?.let { value ->
                val encoded = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("推荐流过滤配置格式无效")
                validateFeedFilter(json.decodeFromString<BiliPaiFeedFilterConfig>(encoded))
            }
        }
        val next = JsonObject(backing.document.toMutableMap().apply { put(name, JsonObject(updated)) })
        Files.createDirectories(backing.root)
        val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(next))
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, backing.file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
        backing.document = next
        backing.snapshots[name]?.value = DesktopPreferenceSnapshot(JsonObject(updated))
        if (name == "plugin_prefs") {
            backing.feedFilterConfig.value = decodeFeedFilter(next)
            backing.feedFilterEnabled.value = updated["plugin_enabled_bilipai_feed_filter"]?.jsonPrimitive?.booleanOrNull ?: false
        }
    }

    /** All facades for this old file generation become read-only before restoration. */
    internal fun freezeWrites() = synchronized(backing) { backing.writesFrozen = true }

    private class Backing(val root: Path) {
        val file: Path = root.resolve("plugin-settings.json")
        var writesFrozen = false
        val snapshots = mutableMapOf<String, MutableStateFlow<DesktopPreferenceSnapshot>>()
        var document: JsonObject = if (Files.exists(file)) {
            try { json.parseToJsonElement(Files.readString(file)).jsonObject }
            catch (error: Exception) { throw IllegalStateException("插件设置文件无法读取: $file", error) }
        } else JsonObject(emptyMap())
        val feedFilterConfig = MutableStateFlow(decodeFeedFilter(document))
        val feedFilterEnabled = MutableStateFlow((document["plugin_prefs"] as? JsonObject)
            ?.get("plugin_enabled_bilipai_feed_filter")?.jsonPrimitive?.booleanOrNull ?: false)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        val registry = mutableMapOf<Path, WeakReference<Backing>>()

        fun backingFor(root: Path): Backing = synchronized(registry) {
            val key = root.toAbsolutePath().normalize()
            registry.entries.removeAll { it.value.get() == null }
            val current = registry[key]?.get()
            if (current != null && synchronized(current) { !current.writesFrozen }) current
            else Backing(key).also { registry[key] = WeakReference(it) }
        }

        private fun decodeFeedFilter(document: JsonObject): BiliPaiFeedFilterConfig {
            val encoded = (document["plugin_prefs"] as? JsonObject)?.get("plugin_config_bilipai_feed_filter")?.jsonPrimitive?.contentOrNull
                ?: return BiliPaiFeedFilterConfig()
            return try { json.decodeFromString<BiliPaiFeedFilterConfig>(encoded).also(::validateFeedFilter) }
            catch (error: Exception) { throw IllegalStateException("推荐流过滤配置无效", error) }
        }

        private fun validateFeedFilter(config: BiliPaiFeedFilterConfig) {
            require(config.minDurationForRcmd >= 0 && config.minPlayForRcmd >= 0) { "过滤阈值不能为负数" }
            require(config.minLikeRatioForRecommend in 0..100) { "点赞率必须在 0 到 100 之间" }
            for (text in listOf(config.banWordForRecommend, config.banWordForZone)) {
                DesktopFeedFilterEditor.parseBanWordToRegex(text)?.let { Regex(it, RegexOption.IGNORE_CASE) }
            }
        }
    }
}

class DesktopPreferenceKey<T>(val name: String, val decode: (JsonElement) -> T?)
fun booleanPreferencesKey(name: String) = DesktopPreferenceKey(name) { value: JsonElement -> (value as? JsonPrimitive)?.booleanOrNull }
fun stringPreferencesKey(name: String) = DesktopPreferenceKey(name) { value: JsonElement -> (value as? JsonPrimitive)?.contentOrNull }

class DesktopPreferenceSnapshot(private val values: JsonObject) {
    operator fun <T> get(key: DesktopPreferenceKey<T>): T? = values[key.name]?.let(key.decode)
}

class DesktopPreferenceEditor {
    internal val values = linkedMapOf<String, JsonElement?>()
    operator fun <T> set(key: DesktopPreferenceKey<T>, value: T) {
        values[key.name] = when (value) {
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            else -> error("Unsupported upstream plugin preference type")
        }
    }
}

class DesktopPluginDataStore(private val store: DesktopPluginStore) {
    val data: StateFlow<DesktopPreferenceSnapshot> get() = store.snapshot("plugin_prefs")
    suspend fun edit(block: (DesktopPreferenceEditor) -> Unit) = withContext(Dispatchers.IO) {
        val editor = DesktopPreferenceEditor().apply(block)
        store.update("plugin_prefs", editor.values)
    }
}

/** Only the original plugin platform surface is exposed; this is not an Android Context. */
class DesktopPluginContext(val store: DesktopPluginStore) {
    val applicationContext: DesktopPluginContext get() = this
    val filesDir get() = store.root.toFile()
    val pluginDataStore = DesktopPluginDataStore(store)
    fun getSharedPreferences(name: String, mode: Int): DesktopPluginPreferences {
        require(mode == MODE_PRIVATE)
        return DesktopPluginPreferences(store, name)
    }
    companion object { const val MODE_PRIVATE = 0 }
}

class DesktopPluginPreferences(private val store: DesktopPluginStore, private val name: String) {
    val all: Map<String, Any> get() = store.preferences(name).mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        val decoded: Any = if (primitive.isString) primitive.content else primitive.booleanOrNull ?: primitive.intOrNull ?: return@mapNotNull null
        key to decoded
    }.toMap()
    fun getBoolean(key: String, fallback: Boolean) = (store.preferences(name)[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
    fun getInt(key: String, fallback: Int) = (store.preferences(name)[key] as? JsonPrimitive)?.intOrNull ?: fallback
    fun getString(key: String, fallback: String?): String? = (store.preferences(name)[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fallback
    fun edit() = Editor(store, name)
    class Editor(private val store: DesktopPluginStore, private val name: String) {
        private val values = linkedMapOf<String, JsonElement?>()
        private var clear = false
        fun putBoolean(key: String, value: Boolean) = apply { values[key] = JsonPrimitive(value) }
        fun putInt(key: String, value: Int) = apply { values[key] = JsonPrimitive(value) }
        fun putString(key: String, value: String?) = apply { values[key] = value?.let { JsonPrimitive(it) } }
        fun remove(key: String) = apply { values[key] = null }
        fun clear() = apply { clear = true }
        fun apply() { store.update(name, values, clear) }
    }
}
