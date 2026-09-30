package com.bilipai.desktop.backup

import kotlinx.serialization.json.*
import okhttp3.ResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Windows file adapter for the original WebDAV service. Login secrets and media assets are outside this archive. */
class DesktopBackupArchive(private val directory: Path, private val beforeReplace: () -> Unit = {}) {
    private val root = directory.toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true }
    private val globalNames = setOf("library.json", "player-settings.json", "appearance-settings.json", "plugin-settings.json",
        "feed-filter.json", "search-history.json", "listen-state.json", "subscriptions.json")

    fun create(nowEpochMs: Long = System.currentTimeMillis()): ByteArray {
        Files.createDirectories(root)
        val files = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
            .map { root.relativize(it).joinToString("/") }.filter(::allowed).sorted().toList() }
        require(files.size <= MAX_ENTRIES) { "备份设置文件过多" }
        require(files.isNotEmpty()) { "暂无可备份的本地设置" }
        val contents = linkedMapOf<String, ByteArray>()
        var size = 0L
        for (name in files) {
            val target = checkedTarget(name)
            require(Files.size(target) <= MAX_FILE_BYTES) { "设置文件过大：$name" }
            val bytes = Files.readAllBytes(target)
            size += bytes.size
            require(size <= MAX_TOTAL_BYTES) { "备份内容过大" }
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            contents[name] = bytes
        }
        val manifest = buildJsonObject {
            put("format", FORMAT); put("schemaVersion", 1); put("createdAtEpochMs", nowEpochMs)
            put("createdAtIso", Instant.ofEpochMilli(nowEpochMs).toString())
            putJsonArray("files") { contents.forEach { (name, bytes) -> add(buildJsonObject {
                put("path", name); put("bytes", bytes.size); put("sha256", sha256(bytes))
            }) } }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "manifest.json", json.encodeToString(JsonObject.serializer(), manifest).toByteArray(Charsets.UTF_8))
            contents.forEach { (name, bytes) -> writeEntry(zip, "windows/$name", bytes) }
        }
        return output.toByteArray().also { require(it.size <= MAX_ZIP_BYTES) { "备份压缩包过大" } }
    }

    /** Validate the entire archive before replacing any file. A failed replacement restores every earlier target. */
    @Synchronized
    fun restore(bytes: ByteArray): Int {
        require(bytes.size <= MAX_ZIP_BYTES) { "备份压缩包过大" }
        Files.createDirectories(root)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entries.size < MAX_ENTRIES + 1) { "备份目录结构不符合当前格式" }
                val name = entry.name
                require(name == "manifest.json" || name.startsWith("windows/") && allowed(name.removePrefix("windows/"))) { "备份包含未知或不安全路径" }
                require(name !in entries) { "备份包含重复文件" }
                val value = readLimited(zip, MAX_FILE_BYTES)
                total += value.size
                require(total <= MAX_TOTAL_BYTES) { "备份内容过大" }
                entries[name] = value
                zip.closeEntry()
            }
        }
        val manifest = entries.remove("manifest.json")?.let { json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
            ?: error("缺少备份说明；请选用 Windows 客户端导出的设置备份")
        require(manifest["format"]?.jsonPrimitive?.content == FORMAT && manifest["schemaVersion"]?.jsonPrimitive?.int == 1) {
            "此备份格式无法在 Windows 恢复"
        }
        val declared = requireNotNull(manifest["files"]).jsonArray.map { it.jsonObject }
        require(declared.size == entries.size && declared.map { it["path"]?.jsonPrimitive?.content }.distinct().size == declared.size) { "备份文件清单不完整" }
        val targets = linkedMapOf<Path, ByteArray>()
        for (description in declared) {
            val name = requireNotNull(description["path"]).jsonPrimitive.content
            require(allowed(name)) { "备份包含未知或不安全路径" }
            val value = requireNotNull(entries["windows/$name"]) { "备份文件缺失：$name" }
            require(description["bytes"]?.jsonPrimitive?.int == value.size && description["sha256"]?.jsonPrimitive?.content == sha256(value)) { "备份文件校验失败：$name" }
            json.parseToJsonElement(value.toString(Charsets.UTF_8))
            val target = checkedTarget(name)
            require(target !in targets) { "备份包含重复的 Windows 文件路径" }
            targets[target] = value
        }
        require(targets.isNotEmpty()) { "备份没有可恢复的设置" }
        beforeReplace()
        val previous = targets.keys.associateWith { if (Files.isRegularFile(it)) Files.readAllBytes(it) else null }
        val replaced = mutableListOf<Path>()
        try {
            targets.forEach { (target, value) ->
                // Recheck after validation, before every filesystem mutation.
                checkedTarget(root.relativize(target).joinToString("/"))
                writeAtomic(target, value)
                replaced.add(target)
            }
        } catch (failure: Throwable) {
            replaced.asReversed().forEach { target ->
                runCatching {
                    previous[target]?.let { writeAtomic(target, it) } ?: Files.deleteIfExists(target)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
        return replaced.size
    }

    fun requireSameOrigin(base: String, download: String) {
        val expected = base.toHttpUrl(); val actual = download.toHttpUrl()
        require(actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port &&
            actual.username.isEmpty() && actual.password.isEmpty()) { "WebDAV 返回了其它服务器的下载地址" }
    }

    fun readDownload(body: ResponseBody): ByteArray {
        require(body.contentLength() <= MAX_ZIP_BYTES) { "远端备份文件过大" }
        return body.byteStream().use { readLimited(it, MAX_ZIP_BYTES) }
    }

    private fun allowed(name: String): Boolean {
        if (name.isBlank() || name.contains('\\') || name.split('/').any { it.isEmpty() || it == "." || it == ".." } || name.contains(':')) return false
        if (name in globalNames) return true
        if (name in setOf("discovery/plugin-settings.json", "search/plugin-settings.json", "plugin/subscription_feeds.json", "plugin/feed_validators.json", "plugin/subscription_reading.json")) return true
        if (Regex("accounts/[1-9][0-9]{0,18}/(?:library|listen-state|feed-filter)\\.json").matches(name)) return true
        if (Regex("accounts/[1-9][0-9]{0,18}/discovery/plugin-settings\\.json").matches(name)) return true
        if (Regex("accounts/[1-9][0-9]{0,18}/search/plugin-settings\\.json").matches(name)) return true
        return Regex("json_plugins/[A-Za-z0-9_.-]{1,150}\\.json").matches(name)
    }

    private fun checkedTarget(name: String): Path {
        require(allowed(name)) { "设置路径无效" }
        val target = root.resolve(name).normalize()
        require(target.startsWith(root) && target != root) { "设置路径超出数据目录" }
        val realRoot = root.toRealPath()
        var ancestor: Path? = target
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.parent
        require(ancestor != null && !Files.isSymbolicLink(ancestor) && ancestor.toRealPath().startsWith(realRoot)) { "设置路径指向数据目录以外" }
        require(ancestor == target || Files.isDirectory(ancestor)) { "设置路径的父级不是目录" }
        return target
    }

    private fun writeAtomic(target: Path, value: ByteArray) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "backup-restore-", ".tmp")
        try {
            Files.write(temporary, value)
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() <= limit - count) { "备份文件超出大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val FORMAT = "bilipai-windows-settings"
        private const val MAX_ENTRIES = 512
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024
        private const val MAX_ZIP_BYTES = 32 * 1024 * 1024
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
