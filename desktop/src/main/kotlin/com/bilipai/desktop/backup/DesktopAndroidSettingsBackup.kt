package com.bilipai.desktop.backup

import com.google.protobuf.CodedInputStream
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Original Android backup paths and the AndroidX Preferences wire schema are retained under
 * desktop/third-party. This is a format decoder, never an Android store or a live settings writer.
 * The existing Windows restore barrier, file admission, rollback and exit own every replacement.
 */
internal class DesktopAndroidSettingsBackup private constructor(
    private val namespaces: Map<String, JsonObject>,
    val files: Map<String, ByteArray>,
) {
    /** Called after the original restore barrier, against the actual final old file generation.
     * Android settings merge into the same canonical namespaces. Windows-only keys, caches,
     * update clocks and unrelated namespaces are retained, rather than recreated or reset.
     */
    fun mergePluginSettings(previous: ByteArray?): ByteArray {
        val existing = previous?.let { Json.parseToJsonElement(utf8(it)) as? JsonObject
            ?: error("当前 Windows 设置格式无效") } ?: JsonObject(emptyMap())
        val merged = existing.toMutableMap()
        namespaces.forEach { (name, values) ->
            val old = existing[name]
            require(old == null || old is JsonObject) { "当前 Windows 设置命名空间格式无效" }
            merged[name] = JsonObject((old as? JsonObject).orEmpty() + values)
        }
        namespaces["settings"]?.let { imported ->
            // The real Root reuses its original settings-share setters before constructing
            // video resources. Never fabricate synchronous cache values in this decoder.
            merged["windows_android_backup_migration"] = buildJsonObject {
                put("pending", true)
                put("keys", JsonArray(imported.keys.sorted().map(::JsonPrimitive)))
            }
        }
        return JsonObject(merged).toString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val SETTINGS = "datastore/settings_prefs.preferences_pb"
        private const val PLUGINS = "datastore/plugin_prefs.preferences_pb"
        private const val REGISTRY = "shared_prefs/json_plugins.xml"
        private val scope = setOf(SETTINGS, PLUGINS, REGISTRY, "json_plugins/*.json")
        private val pluginName = Regex("json_plugins/[A-Za-z0-9_.-]{1,150}\\.[jJ][sS][oO][nN]")
        private const val MAX_VALUES = 10_000
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024

        fun allows(name: String): Boolean = name == SETTINGS || name == PLUGINS || name == REGISTRY ||
            pluginName.matches(name)

        fun decode(manifest: JsonObject, entries: Map<String, ByteArray>): DesktopAndroidSettingsBackup {
            require(manifest["format"] == null && manifest["schemaVersion"] == null &&
                manifest["createdAtEpochMs"]?.jsonPrimitive?.longOrNull?.let { it >= 0 } == true &&
                manifest["appVersion"]?.jsonPrimitive?.takeIf { it.isString }?.content?.isNotBlank() == true) {
                "此备份既不是 Windows 备份，也不是原版 BiliPai Android 备份"
            }
            Instant.parse(manifest["createdAtIso"]?.jsonPrimitive?.content ?: error("缺少 Android 备份时间"))
            val declared = (manifest["scope"] as? JsonArray)?.map { it.jsonPrimitive.content }
            require(declared?.size == scope.size && declared.toSet() == scope) { "Android 备份范围无法识别" }
            require(entries.isNotEmpty() && entries.keys.all(::allows)) { "Android 备份包含未知或混合格式文件" }
            val namespaces = linkedMapOf<String, JsonObject>()
            entries[SETTINGS]?.let { namespaces["settings"] = preferences(it) }
            entries[PLUGINS]?.let { namespaces["plugin_prefs"] = preferences(it) }
            entries[REGISTRY]?.let { namespaces["json_plugins"] = sharedPreferences(it) }
            val files = linkedMapOf<String, ByteArray>()
            if (namespaces.isNotEmpty()) files["plugin-settings.json"] = JsonObject(namespaces).toString().toByteArray(StandardCharsets.UTF_8)
            entries.filterKeys { it.startsWith("json_plugins/") }.forEach { (name, bytes) ->
                Json.parseToJsonElement(utf8(bytes))
                val destination = name.dropLast(5) + ".json"
                require(files.put(destination, bytes) == null) { "Android 备份包含重复插件文件" }
            }
            require(files.isNotEmpty()) { "Android 备份没有可迁移的设置" }
            return DesktopAndroidSettingsBackup(namespaces, files)
        }

        /** PreferenceMap -> map entry -> Value, with no generated classes or new dependency.
         * Existing protobuf-javalite performs length, varint, UTF-8 and EOF validation.
         */
        internal fun preferences(bytes: ByteArray): JsonObject {
            val result = linkedMapOf<String, JsonElement>()
            fields(bytes) { tag, input ->
                require(tag == 10) { "Android 设置表格式无效" }
                var key: String? = null
                var value: JsonElement? = null
                fields(input.readByteArray()) { field, entry -> when (field) {
                    10 -> { require(key == null); key = entry.readStringRequireUtf8() }
                    18 -> { require(value == null); value = preferenceValue(entry.readByteArray()) }
                    else -> error("Android 设置项格式无效")
                } }
                val actualKey = requireNotNull(key) { "Android 设置项缺少名称" }
                require(result.size < MAX_VALUES && actualKey.length <= 4096 && actualKey !in result) { "Android 设置项重复或过多" }
                result[actualKey] = requireNotNull(value) { "Android 设置项缺少值" }
            }
            return JsonObject(result)
        }

        private fun preferenceValue(bytes: ByteArray): JsonElement {
            var value: JsonElement? = null
            fields(bytes) { tag, input ->
                require(value == null) { "Android 设置项包含多个值" }
                value = when (tag) {
                    8 -> JsonPrimitive(input.readBool())
                    21 -> JsonPrimitive(input.readFloat().also { require(it.isFinite()) })
                    24 -> JsonPrimitive(input.readInt32())
                    32 -> JsonPrimitive(input.readInt64())
                    42 -> JsonPrimitive(input.readStringRequireUtf8())
                    50 -> {
                        val values = linkedSetOf<String>()
                        fields(input.readByteArray()) { field, set ->
                            require(field == 10 && values.size < MAX_VALUES) { "Android 字符串集合格式无效" }
                            values.add(set.readStringRequireUtf8())
                        }
                        JsonArray(values.map(::JsonPrimitive))
                    }
                    57 -> JsonPrimitive(input.readDouble().also { require(it.isFinite()) })
                    // No current BiliPai preference consumes ByteArray. Preserve its type without
                    // allocating one boxed JsonPrimitive per byte or confusing it with StringSet.
                    66 -> buildJsonObject { put("androidByteArrayBase64", java.util.Base64.getEncoder().encodeToString(input.readByteArray())) }
                    else -> error("Android 设置包含无法识别的类型")
                }
            }
            return requireNotNull(value) { "Android 设置项缺少值" }
        }

        private fun fields(bytes: ByteArray, consume: (Int, CodedInputStream) -> Unit) {
            require(bytes.size <= MAX_FILE_BYTES) { "Android 设置文件过大" }
            val input = CodedInputStream.newInstance(bytes)
            input.setSizeLimit(MAX_FILE_BYTES)
            var count = 0
            while (!input.isAtEnd) {
                require(++count <= MAX_VALUES) { "Android 设置字段过多" }
                val tag = input.readTag()
                require(tag != 0) { "Android 设置字段无效" }
                consume(tag, input)
            }
        }

        internal fun sharedPreferences(bytes: ByteArray): JsonObject {
            require(bytes.size <= MAX_FILE_BYTES)
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "16")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setErrorHandler(object : DefaultHandler() {
                    override fun warning(e: SAXParseException) { throw e }
                    override fun error(e: SAXParseException) { throw e }
                    override fun fatalError(e: SAXParseException) { throw e }
                })
            }
            val root = bytes.inputStream().use { builder.parse(it) }.documentElement
            require(root.tagName == "map") { "Android 插件登记表格式无效" }
            val result = linkedMapOf<String, JsonElement>()
            for (index in 0 until root.childNodes.length) {
                val item = root.childNodes.item(index)
                if (item.nodeType == org.w3c.dom.Node.TEXT_NODE) { require(item.textContent.isBlank()); continue }
                if (item.nodeType == org.w3c.dom.Node.COMMENT_NODE) continue
                require(item is Element && item.hasAttribute("name")) { "Android 插件登记项格式无效" }
                val name = item.getAttribute("name")
                require(result.size < MAX_VALUES && name.length <= 4096 && name !in result) { "Android 插件登记项重复或过多" }
                result[name] = when (item.tagName) {
                    "string" -> { require((0 until item.childNodes.length).none { item.childNodes.item(it) is Element }); JsonPrimitive(item.textContent) }
                    "boolean" -> JsonPrimitive(when (item.getAttribute("value")) { "true" -> true; "false" -> false; else -> error("Android 插件布尔值无效") })
                    "int" -> JsonPrimitive(item.getAttribute("value").toInt())
                    "long" -> JsonPrimitive(item.getAttribute("value").toLong())
                    "float" -> JsonPrimitive(item.getAttribute("value").toFloat().also { require(it.isFinite()) })
                    "set" -> {
                        val values = linkedSetOf<String>()
                        for (childIndex in 0 until item.childNodes.length) {
                            val child = item.childNodes.item(childIndex)
                            if (child.nodeType == org.w3c.dom.Node.TEXT_NODE) { require(child.textContent.isBlank()); continue }
                            require(child is Element && child.tagName == "string" && values.size < MAX_VALUES)
                            require((0 until child.childNodes.length).none { child.childNodes.item(it) is Element })
                            values.add(child.textContent)
                        }
                        JsonArray(values.map(::JsonPrimitive))
                    }
                    else -> error("Android 插件登记包含无法识别的类型")
                }
            }
            return JsonObject(result)
        }

        private fun utf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
