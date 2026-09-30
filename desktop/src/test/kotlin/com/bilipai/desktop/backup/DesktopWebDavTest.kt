package com.bilipai.desktop.backup

import com.android.purebilibili.feature.settings.webdav.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class DesktopWebDavTest {
    @Test fun `original WebDAV protocol creates directories uploads lists and restores a real ZIP`() = runBlocking {
        withDav { server ->
            val root = Files.createTempDirectory("webdav-roundtrip-")
            Files.writeString(root.resolve("library.json"), "{\"dark\":true}")
            val config = WebDavBackupConfig(server.url, "test-user", "test-password", "/BiliPai/windows-backups")
            val service = WebDavBackupService(DesktopBackupArchive(root))
            service.testConnection(config).getOrThrow()
            val saved = service.backupNow(config).getOrThrow()
            assertTrue(saved.fileName.startsWith("bilipai-backup-"))
            assertEquals(listOf(saved.fileName), service.listBackups(config).getOrThrow().map { it.fileName })
            Files.writeString(root.resolve("library.json"), "{}")
            service.restoreLatest(config).getOrThrow()
            assertEquals("{\"dark\":true}", Files.readString(root.resolve("library.json")))
            assertEquals(2, server.calls.count { it.startsWith("MKCOL ") })
            assertTrue(server.calls.any { it.startsWith("PUT ") }); assertTrue(server.calls.any { it.startsWith("GET ") })
        }
    }

    @Test fun `WebDAV unauthorized result never creates or uploads a backup`() = runBlocking {
        withDav { server ->
            server.authorized = false
            val root = Files.createTempDirectory("webdav-auth-")
            Files.writeString(root.resolve("library.json"), "{}")
            val result = WebDavBackupService(DesktopBackupArchive(root)).backupNow(WebDavBackupConfig(server.url, "user", "wrong", "/backups"))
            assertTrue(result.isFailure); assertTrue(server.uploads.isEmpty()); assertFalse(server.calls.any { it.startsWith("PUT ") })
        }
    }

    @Test fun `scheduled backup uses original daily cadence and persists success across process instances`() = runBlocking {
        withDav { server ->
            val root = Files.createTempDirectory("webdav-schedule-")
            Files.writeString(root.resolve("library.json"), "{}")
            val store = DesktopBackupStore(root)
            val scheduler = FakeScheduler()
            var now = 1_700_000_000_000L
            val config = WebDavBackupConfig(server.url, "test-user", "test-password", "/backups", true)
            val first = DesktopBackupCoordinator(store, scheduler) { now }
            first.configure(config).getOrThrow(); assertEquals(1, scheduler.installs)
            first.automaticBackupIfDue().getOrThrow(); assertEquals(1, server.calls.count { it.startsWith("PUT ") })
            DesktopBackupCoordinator(DesktopBackupStore(root), scheduler) { now }.automaticBackupIfDue().getOrThrow()
            assertEquals(1, server.calls.count { it.startsWith("PUT ") })
            now += 24 * 60 * 60 * 1000L
            first.automaticBackupIfDue().getOrThrow(); assertEquals(2, server.calls.count { it.startsWith("PUT ") })
            first.configure(config.copy(enabled = false)).getOrThrow(); assertEquals(1, scheduler.removals)
            now += 25 * 60 * 60 * 1000L
            first.automaticBackupIfDue().getOrThrow(); assertEquals(2, server.calls.count { it.startsWith("PUT ") })
            assertFalse(first.state.value.snapshot.config.enabled)
        }
    }

    @Test fun `failed scheduling does not silently enable automatic backup`() = runBlocking {
        val root = Files.createTempDirectory("webdav-schedule-fail-")
        val store = DesktopBackupStore(root)
        val scheduler = object : DesktopBackupScheduler { override fun install() = error("scheduler failed"); override fun uninstall() {} }
        val result = DesktopBackupCoordinator(store, scheduler).configure(WebDavBackupConfig("https://dav.example", "name", "secret", enabled = true))
        assertTrue(result.isFailure); assertFalse(store.read().config.enabled)
    }

    @Test fun `daily backup uses original flex window across a 23 hour daylight saving day`() = runBlocking {
        withDav { server ->
            val root = Files.createTempDirectory("webdav-dst-")
            Files.writeString(root.resolve("library.json"), "{}")
            val store = DesktopBackupStore(root)
            val previous = 1_700_000_000_000L
            store.save(DesktopBackupSnapshot(WebDavBackupConfig(server.url, "test-user", "test-password", "/backups", true), previous))
            var now = previous + 21 * 60 * 60 * 1000L
            val backup = DesktopBackupCoordinator(store, FakeScheduler()) { now }
            backup.automaticBackupIfDue().getOrThrow(); assertEquals(0, server.calls.count { it.startsWith("PUT ") })
            now = previous + 23 * 60 * 60 * 1000L
            backup.automaticBackupIfDue().getOrThrow(); assertEquals(1, server.calls.count { it.startsWith("PUT ") })
        }
    }

    @Test fun `Windows protects WebDAV password while retaining original normalized settings`() {
        val root = Files.createTempDirectory("webdav-credentials-")
        val store = DesktopBackupStore(root)
        store.save(DesktopBackupSnapshot(WebDavBackupConfig(" https://dav.example/ ", " name ", "中文 secret 🎵", "backups\\daily", true), 1234))
        val restored = DesktopBackupStore(root).read()
        assertEquals("https://dav.example", restored.config.baseUrl); assertEquals("name", restored.config.username)
        assertEquals("/backups/daily", restored.config.remoteDir); assertEquals("中文 secret 🎵", restored.config.password)
        assertEquals(1234, restored.lastSuccessfulBackupMs)
        if (System.getProperty("os.name").startsWith("Windows")) assertFalse(Files.readString(root.resolve("webdav-settings.json")).contains("secret"))
    }

    private class FakeScheduler : DesktopBackupScheduler { var installs = 0; var removals = 0; override fun install() { installs++ }; override fun uninstall() { removals++ } }
    private class Dav : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"
        val calls = mutableListOf<String>(); val uploads = linkedMapOf<String, ByteArray>(); val directories = mutableSetOf("/")
        var authorized = true
        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
                val method = exchange.requestMethod
                calls.add("$method $path depth=${exchange.requestHeaders.getFirst("Depth")}")
                val expected = "Basic " + Base64.getEncoder().encodeToString("test-user:test-password".toByteArray())
                var status = 200; var body = byteArrayOf()
                if (!authorized || exchange.requestHeaders.getFirst("Authorization") != expected) status = 401
                else when (method) {
                    "PROPFIND" -> if (path !in directories) status = 404 else {
                        status = 207
                        val files = if (exchange.requestHeaders.getFirst("Depth") == "1") uploads.filterKeys { it.substringBeforeLast('/') == path } else emptyMap()
                        body = ("<d:multistatus xmlns:d=\"DAV:\">" + files.entries.joinToString("") { (name, bytes) ->
                            "<d:response><d:href>$name</d:href><d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength><d:getlastmodified>Wed, 15 Nov 2023 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>"
                        } + "</d:multistatus>").toByteArray()
                    }
                    "MKCOL" -> { status = if (directories.add(path)) 201 else 405 }
                    "PUT" -> { uploads[path] = exchange.requestBody.readAllBytes(); status = 201 }
                    "GET" -> { body = uploads[path] ?: byteArrayOf(); if (path !in uploads) status = 404 }
                    else -> status = 405
                }
                exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                exchange.responseBody.use { if (body.isNotEmpty()) it.write(body) }; exchange.close()
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }
    private suspend fun withDav(block: suspend (Dav) -> Unit) { Dav().use { block(it) } }
}
