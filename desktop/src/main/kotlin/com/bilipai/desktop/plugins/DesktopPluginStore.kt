package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.plugin.BiliPaiFeedFilterConfig
import com.android.purebilibili.feature.plugin.DesktopFeedFilterEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
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
            DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
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

    /** Accepted global preference commit. Its consumption never reacquires Root gates. */
    internal class OriginalPreferenceWritePermit internal constructor(private val store: DesktopPluginStore) {
        private val consumed = java.util.concurrent.atomic.AtomicBoolean()
        internal fun consume(target: DesktopPluginStore) {
            require(store === target) { "Original preference permit belongs to a different Store" }
            check(consumed.compareAndSet(false, true)) { "Original preference write permit already consumed" }
        }
    }

    /** Original read/edit callbacks are pure and may be retried after a document CAS conflict.
     * The same global backing owns persistence. Snapshot reads, callback evaluation, staging,
     * fsync and replacement all run outside Root admission. Only permit minting enters Root.
     */
    internal fun <T> updateOriginalFromSnapshot(
        name: String, checkRequest: () -> Unit,
        acquirePermit: () -> OriginalPreferenceWritePermit,
        edit: (DesktopPreferenceSnapshot) -> Pair<T, Map<String, JsonElement?>>,
    ): T = updateOriginalNamespacesFromSnapshot(name, checkRequest, acquirePermit) { snapshot ->
        val (result, changes) = edit(snapshot)
        result to mapOf(name to changes)
    }

    /** Same sole backing/CAS/permit. Canonical storage + original sync mirror replace as one file. */
    internal fun <T> updateOriginalNamespacesFromSnapshot(
        name: String,
        checkRequest: () -> Unit,
        acquirePermit: () -> OriginalPreferenceWritePermit,
        edit: (DesktopPreferenceSnapshot) -> Pair<T, Map<String, Map<String, JsonElement?>>>,
    ): T {
        var replacementFailures = 0
        while (true) {
            checkRequest()
            val snapshot = synchronized(backing) {
                check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                require(backing.document[name] == null || backing.document[name] is JsonObject) { "设置命名空间格式无效" }
                backing.document
            }
            val current = snapshot[name] as? JsonObject ?: JsonObject(emptyMap())
            val (result, edits) = edit(DesktopPreferenceSnapshot(current))
            checkRequest()
            val updates = edits.mapValues { (namespace, changes) ->
                require(snapshot[namespace] == null || snapshot[namespace] is JsonObject) { "设置命名空间格式无效" }
                val values = (snapshot[namespace] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                if (namespace == "plugin_prefs") values["plugin_config_bilipai_feed_filter"]?.let { value ->
                    val encoded = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("推荐流过滤配置格式无效")
                    validateFeedFilter(json.decodeFromString<BiliPaiFeedFilterConfig>(encoded))
                }
                JsonObject(values)
            }
            val next = JsonObject(snapshot.toMutableMap().apply { putAll(updates) })
            Files.createDirectories(backing.root)
            val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(next))
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                val permit = acquirePermit()
                var replacementFailure: java.nio.file.AccessDeniedException? = null
                val committed = synchronized(backing) {
                    check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                    if (backing.document !== snapshot) false
                    else {
                        permit.consume(this)
                        try {
                            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                Files.move(temporary, backing.file, StandardCopyOption.REPLACE_EXISTING)
                            }
                        } catch (failure: java.nio.file.AccessDeniedException) {
                            replacementFailure = failure
                            return@synchronized false // no document or Flow publication
                        }
                        backing.document = next
                        updates.forEach { (namespace, values) -> backing.snapshots[namespace]?.value = DesktopPreferenceSnapshot(values) }
                        updates["plugin_prefs"]?.let { values ->
                            backing.feedFilterConfig.value = decodeFeedFilter(next)
                            backing.feedFilterEnabled.value = values["plugin_enabled_bilipai_feed_filter"]?.jsonPrimitive?.booleanOrNull ?: false
                        }
                        true
                    }
                }
                if (committed) return result
                replacementFailure?.let { failure ->
                    DesktopPreferenceReplacementRetry.waitOutsideAdmissionOrThrow(failure, ++replacementFailures, checkRequest)
                }
                // Conflict/retry retires this permit/temp. Fresh callback/CAS and fresh Root permit follow.
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    /** Captured playback writes use the SAME backing. All staging and rename IO is outside Root gates. */
    internal fun updateCapturedPlayback(name: String, values: Map<String, JsonElement?>,
        operation: DesktopPlayerPluginWriteAdmission.Operation, callerJob: kotlinx.coroutines.Job) {
        require(operation.context.store === this) { "Captured playback plugin has a different Store" }
        var replacementFailures = 0
        while (true) {
            callerJob.ensureActive(); operation.check()
            val snapshot = synchronized(backing) {
                check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                backing.document
            }
            val updated = (snapshot[name] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
            values.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
            if (name == "plugin_prefs") {
                updated["plugin_config_bilipai_feed_filter"]?.let { value ->
                    val encoded = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("推荐流过滤配置格式无效")
                    validateFeedFilter(json.decodeFromString<BiliPaiFeedFilterConfig>(encoded))
                }
            }
            val next = JsonObject(snapshot.toMutableMap().apply { put(name, JsonObject(updated)) })
            Files.createDirectories(backing.root)
            val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(next))
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                // Linearization only mints a permit. No backing monitor, rename or IO is held inside admission.
                val permit = operation.permit(this, callerJob)
                var replacementFailure: java.nio.file.AccessDeniedException? = null
                val committed = synchronized(backing) {
                    check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
                    if (backing.document !== snapshot) false
                    else {
                        permit.consume()
                        try {
                            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                                Files.move(temporary, backing.file, StandardCopyOption.REPLACE_EXISTING)
                            }
                        } catch (failure: java.nio.file.AccessDeniedException) {
                            replacementFailure = failure
                            return@synchronized false // no document or Flow publication
                        }
                        backing.document = next
                        backing.snapshots[name]?.value = DesktopPreferenceSnapshot(JsonObject(updated))
                        if (name == "plugin_prefs") {
                            backing.feedFilterConfig.value = decodeFeedFilter(next)
                            backing.feedFilterEnabled.value = updated["plugin_enabled_bilipai_feed_filter"]?.jsonPrimitive?.booleanOrNull ?: false
                        }
                        true
                    }
                }
                if (committed) return
                replacementFailure?.let { failure ->
                    DesktopPreferenceReplacementRetry.waitOutsideAdmissionOrThrow(failure, ++replacementFailures) {
                        callerJob.ensureActive(); operation.check()
                    }
                }
                // Snapshot conflict/retry discards this permit/temp and remints only after fresh ownership checks.
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    /** Same backing, one atomic document publication for original canonical theme + startup cache. */
    internal fun updateThemeFromSnapshot(checkPublication: () -> Unit, edit: (JsonObject, JsonObject) -> Map<String, Map<String, JsonElement?>>) = synchronized(backing) {
        check(!backing.writesFrozen) { "插件已停止，不能写入旧设置实例" }
        requireObjectNamespace("settings"); requireObjectNamespace("theme_cache")
        val updates = edit(preferences("settings"), preferences("theme_cache"))
        require(updates.keys.all { it in setOf("settings", "theme_cache") })
        val document = backing.document.toMutableMap()
        for ((name, edits) in updates) {
            val values = preferences(name).toMutableMap()
            edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            document[name] = JsonObject(values)
        }
        val next = JsonObject(document)
        Files.createDirectories(backing.root)
        val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(next))
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
            checkPublication()
            try { Files.move(temporary, backing.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, backing.file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
        backing.document = next
        for (name in updates.keys) backing.snapshots[name]?.value = DesktopPreferenceSnapshot(preferences(name))
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
        val operation = DesktopPlayerPluginWriteAdmission.currentOrNull()
        if (operation == null) store.update("plugin_prefs", editor.values)
        else store.updateCapturedPlayback("plugin_prefs", editor.values, operation,
            currentCoroutineContext()[Job] ?: error("Playback plugin write requires a caller Job"))
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
