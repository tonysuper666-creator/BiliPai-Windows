package com.bilipai.desktop.backup

import com.android.purebilibili.feature.settings.webdav.*
import com.bilipai.desktop.data.DesktopCredentialCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class DesktopBackupSnapshot(val config: WebDavBackupConfig = WebDavBackupConfig(), val lastSuccessfulBackupMs: Long = 0)

/** The Windows user's protected WebDAV credential is deliberately outside exported settings. */
class DesktopBackupStore(val directory: Path) {
    private val file = directory.resolve("webdav-settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    fun read(): DesktopBackupSnapshot {
        if (!Files.exists(file)) return DesktopBackupSnapshot()
        require(Files.size(file) <= 128 * 1024) { "WebDAV 配置文件过大" }
        val saved = json.decodeFromString<Document>(Files.readString(file))
        require(saved.schemaVersion == 1) { "WebDAV 配置版本不受支持" }
        return DesktopBackupSnapshot(WebDavBackupConfig(normalizeWebDavBaseUrl(saved.baseUrl), saved.username,
            DesktopCredentialCipher.unprotect(saved.protectedPassword), normalizeWebDavRemoteDir(saved.remoteDir), saved.enabled),
            saved.lastSuccessfulBackupMs)
    }

    @Synchronized
    fun save(snapshot: DesktopBackupSnapshot) {
        val config = snapshot.config
        val saved = Document(baseUrl = normalizeWebDavBaseUrl(config.baseUrl), username = config.username.trim(),
            protectedPassword = DesktopCredentialCipher.protect(config.password), remoteDir = normalizeWebDavRemoteDir(config.remoteDir),
            enabled = config.enabled, lastSuccessfulBackupMs = snapshot.lastSuccessfulBackupMs)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "webdav-settings-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(Document.serializer(), saved))
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    @Serializable
    private data class Document(val schemaVersion: Int = 1, val baseUrl: String = "", val username: String = "",
        val protectedPassword: String = "", val remoteDir: String = DEFAULT_WEBDAV_REMOTE_DIR, val enabled: Boolean = false,
        val lastSuccessfulBackupMs: Long = 0)
}
