package com.bilipai.desktop.backup

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DesktopBackupArchiveTest {
    @Test fun `round trip restores global account and JSON plugin settings while excluding credentials and media`() {
        val from = Files.createTempDirectory("backup-source-")
        write(from, "library.json", "{\"dark\":true}")
        write(from, "accounts/123/listen-state.json", "{\"title\":\"中文 🎵\"}")
        write(from, "json_plugins/notes.json", "{\"rules\":[]}")
        write(from, "session.json", "{\"cookie\":\"private\"}")
        write(from, "webdav-settings.json", "{\"password\":\"private\"}")
        write(from, "downloads/media.json", "{}")
        val archive = DesktopBackupArchive(from).create(1_700_000_000_000)
        val names = entries(archive).keys
        assertEquals(setOf("manifest.json", "windows/library.json", "windows/accounts/123/listen-state.json", "windows/json_plugins/notes.json"), names)
        val to = Files.createTempDirectory("backup-target-")
        write(to, "library.json", "{}")
        write(to, "session.json", "{\"cookie\":\"unchanged\"}")
        assertEquals(3, DesktopBackupArchive(to).restore(archive))
        assertEquals(Files.readString(from.resolve("accounts/123/listen-state.json")), Files.readString(to.resolve("accounts/123/listen-state.json")))
        assertEquals("{\"cookie\":\"unchanged\"}", Files.readString(to.resolve("session.json")))
    }

    @Test fun `checksum failure validates every entry before replacing earlier valid files`() {
        val root = Files.createTempDirectory("backup-checksum-")
        write(root, "library.json", "{\"old\":true}")
        val invalid = buildArchive(linkedMapOf("library.json" to "{}", "player-settings.json" to "{}"), corruptPath = "player-settings.json")
        assertFailsWith<IllegalArgumentException> { DesktopBackupArchive(root).restore(invalid) }
        assertEquals("{\"old\":true}", Files.readString(root.resolve("library.json")))
        assertFalse(Files.exists(root.resolve("player-settings.json")))
    }

    @Test fun `archive traversal and protected credential entries are rejected`() {
        for (path in listOf("../escape.json", "/library.json", "accounts/0/library.json", "accounts/1/session.json", "webdav-settings.json", "json_plugins/../session.json", "accounts\\1\\library.json")) {
            val root = Files.createTempDirectory("backup-path-")
            assertFailsWith<IllegalArgumentException>(path) { DesktopBackupArchive(root).restore(buildArchive(mapOf(path to "{}"))) }
            assertEquals(0, Files.list(root).use { it.count() })
        }
    }

    @Test fun `a later filesystem failure rolls back prior replacements`() {
        val root = Files.createTempDirectory("backup-rollback-")
        write(root, "library.json", "{\"old\":true}")
        Files.createDirectory(root.resolve("player-settings.json"))
        assertFails { DesktopBackupArchive(root).restore(buildArchive(linkedMapOf("library.json" to "{}", "player-settings.json" to "{}"))) }
        assertEquals("{\"old\":true}", Files.readString(root.resolve("library.json")))
        assertTrue(Files.isDirectory(root.resolve("player-settings.json")))
        assertTrue(Files.list(root).use { it.noneMatch { p -> p.fileName.toString().startsWith("backup-restore-") } })
    }

    @Test fun `case aliases cannot overwrite one Windows path twice`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val root = Files.createTempDirectory("backup-alias-")
        assertFailsWith<IllegalArgumentException> { DesktopBackupArchive(root).restore(buildArchive(linkedMapOf("json_plugins/a.json" to "{}", "json_plugins/A.json" to "{}"))) }
        assertFalse(Files.exists(root.resolve("json_plugins/a.json")))
    }

    @Test fun `Android protobuf backup is identified as incompatible before writes`() {
        val root = Files.createTempDirectory("backup-android-")
        val android = zip(linkedMapOf("manifest.json" to "{\"createdAtEpochMs\":1}".toByteArray(),
            "datastore/settings_prefs.preferences_pb" to byteArrayOf(1, 2)))
        assertFailsWith<IllegalArgumentException> { DesktopBackupArchive(root).restore(android) }
        assertEquals(0, Files.list(root).use { it.count() })
    }

    @Test fun `same origin download excludes userinfo and alternate ports hosts or schemes`() {
        val archive = DesktopBackupArchive(Files.createTempDirectory("backup-origin-"))
        archive.requireSameOrigin("https://dav.example/base", "https://dav.example/files/backup.zip")
        for (url in listOf("https://other.example/b.zip", "http://dav.example/b.zip", "https://dav.example:444/b.zip", "https://user:secret@dav.example/b.zip"))
            assertFailsWith<IllegalArgumentException> { archive.requireSameOrigin("https://dav.example/base", url) }
    }

    @Test fun `an empty settings directory does not upload an empty backup`() {
        assertFailsWith<IllegalArgumentException> { DesktopBackupArchive(Files.createTempDirectory("backup-empty-")).create() }
    }

    @Test fun `restore quiesces old settings writers only after the entire backup is valid`() {
        val root = Files.createTempDirectory("backup-quiesce-")
        write(root, "library.json", "{\"old\":true}")
        var preparations = 0
        val archive = DesktopBackupArchive(root) { preparations++; assertEquals("{\"old\":true}", Files.readString(root.resolve("library.json"))) }
        assertFailsWith<IllegalArgumentException> { archive.restore(buildArchive(mapOf("library.json" to "{}"), "library.json")) }
        assertEquals(0, preparations)
        archive.restore(buildArchive(mapOf("library.json" to "{}")))
        assertEquals(1, preparations); assertEquals("{}", Files.readString(root.resolve("library.json")))
    }

    private fun write(root: Path, name: String, value: String) { val p = root.resolve(name); Files.createDirectories(p.parent); Files.writeString(p, value) }
    private fun buildArchive(files: Map<String, String>, corruptPath: String? = null): ByteArray {
        val values = files.mapValues { it.value.toByteArray() }
        val manifest = buildJsonObject {
            put("format", "bilipai-windows-settings"); put("schemaVersion", 1)
            putJsonArray("files") { values.forEach { (path, bytes) -> add(buildJsonObject {
                put("path", path); put("bytes", bytes.size)
                put("sha256", if (path == corruptPath) "incorrect" else MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }) } }
        }
        return zip(linkedMapOf("manifest.json" to manifest.toString().toByteArray()).apply { values.forEach { (path, bytes) -> put("windows/$path", bytes) } })
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun entries(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()); zip.closeEntry() } }
    }
}
