package com.bilipai.desktop.backup

import com.android.purebilibili.feature.settings.webdav.WebDavBackupConfig
import com.android.purebilibili.feature.settings.webdav.WebDavBackupService
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Task-owned disk, real archive/coordinator, and a UI dispatcher with prompt cancellation on dispatch. */
private class RestoreFixture : AutoCloseable {
    val directory = Files.createTempDirectory("bp-restore-")
    val root = Files.createDirectory(directory.resolve("destination"))
    private val source = Files.createDirectory(directory.resolve("source"))
    val zip: Path = directory.resolve("settings.zip")
    val bytes: ByteArray
    val pageJob = SupervisorJob()
    val scope = CoroutineScope(pageJob + Dispatchers.Default)
    val ui = Executors.newSingleThreadExecutor { Thread(it, "task-owned-restore-ui") }.asCoroutineDispatcher()
    val before = AtomicInteger()
    val exits = AtomicInteger()
    val completedExits = AtomicInteger()
    val cancellations = AtomicInteger()
    val result = AtomicReference<Result<Unit>?>(null)
    val store = DesktopBackupStore(root)
    init {
        Files.writeString(source.resolve("library.json"), "{\"restored\":true}")
        Files.writeString(source.resolve("player-settings.json"), "{\"restoredPlayer\":true}")
        bytes = DesktopBackupArchive(source).create(1_700_000_000_000)
        Files.write(zip, bytes)
        Files.writeString(root.resolve("library.json"), "{\"original\":true}")
        Files.writeString(root.resolve("player-settings.json"), "{\"originalPlayer\":true}")
    }
    fun coordinator(cancelPage: Boolean = false, failBefore: Boolean = false): DesktopBackupCoordinator =
        DesktopBackupCoordinator(store, object : DesktopBackupScheduler {
            override fun install() = error("scheduler must not be touched")
            override fun uninstall() = error("scheduler must not be touched")
        }, beforeRestore = {
            before.incrementAndGet()
            if (cancelPage) pageJob.cancel(CancellationException("task-owned ReadyShell disposed"))
            if (failBefore) throw CancellationException("quiescence abandoned before replacement")
        }, afterRestore = {
            withContext(ui) {
                assertTrue(currentCoroutineContext().isActive)
                assertRestored()
                exits.incrementAndGet()
                delay(20) // The post-commit callback itself must remain cancellable only by its own failure.
                completedExits.incrementAndGet()
            }
        })
    suspend fun runPage(block: suspend () -> Result<Unit>) {
        withTimeout(10_000) {
            scope.launch {
                try { result.set(block()) }
                catch (_: CancellationException) { cancellations.incrementAndGet() }
            }.join()
        }
    }
    fun assertRestored() {
        assertEquals("{\"restored\":true}", Files.readString(root.resolve("library.json")))
        assertEquals("{\"restoredPlayer\":true}", Files.readString(root.resolve("player-settings.json")))
    }
    fun assertOriginal() {
        assertEquals("{\"original\":true}", Files.readString(root.resolve("library.json")))
        assertEquals("{\"originalPlayer\":true}", Files.readString(root.resolve("player-settings.json")))
    }
    override fun close() {
        pageJob.cancel()
        ui.close()
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}

/** Exact allowlist: no redirects, unrelated paths, account server, or credentials beyond these synthetic values. */
private class RestoreDav(private val bytes: ByteArray, private val delayGet: Boolean = false,
                         private val getStatus: Int = 200) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newSingleThreadExecutor()
    private val file = "/backups/bilipai-backup-20231115-100000.zip"
    val calls = CopyOnWriteArrayList<String>()
    val getEntered = CountDownLatch(1)
    val releaseGet = CountDownLatch(if (delayGet) 1 else 0)
    val config get() = WebDavBackupConfig("http://127.0.0.1:${server.address.port}", "fixture-user", "fixture-secret", "/backups")
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readAllBytes() }
                val path = exchange.requestURI.path.trimEnd('/')
                val key = "${exchange.requestMethod} $path ${exchange.requestHeaders.getFirst("Depth").orEmpty()}"
                calls.add(key)
                val expected = "Basic " + Base64.getEncoder().encodeToString("fixture-user:fixture-secret".toByteArray())
                var status = 400
                var response = byteArrayOf()
                if (exchange.requestHeaders.getFirst("Authorization") != expected) status = 401
                else when (key) {
                    "PROPFIND /backups 0" -> {
                        status = 207; response = "<d:multistatus xmlns:d=\"DAV:\"/>".toByteArray()
                    }
                    "PROPFIND /backups 1" -> {
                        status = 207
                        response = ("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$file</d:href>" +
                            "<d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength>" +
                            "<d:getlastmodified>Wed, 15 Nov 2023 10:00:00 GMT</d:getlastmodified>" +
                            "</d:prop></d:propstat></d:response></d:multistatus>").toByteArray()
                    }
                    "GET $file " -> {
                        getEntered.countDown()
                        check(releaseGet.await(10, TimeUnit.SECONDS))
                        status = getStatus; response = if (status == 200) bytes else byteArrayOf()
                    }
                }
                exchange.sendResponseHeaders(status, if (response.isEmpty()) -1 else response.size.toLong())
                exchange.responseBody.use { if (response.isNotEmpty()) it.write(response) }
            } finally { exchange.close() }
        }
        server.start()
    }
    fun assertExactReadOnlyRequests() = assertEquals(listOf("PROPFIND /backups 0", "PROPFIND /backups 1", "GET $file "), calls.toList())
    override fun close() {
        releaseGet.countDown()
        server.stop(0)
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}

class DesktopBackupRestoreBarrierTest {
    @Test fun successfulLocalRestoreInvokesExitOnceAfterBothFilesCommit(): Unit = runBlocking {
        RestoreFixture().use { f ->
            val coordinator = f.coordinator()
            coordinator.importLocal(f.zip).getOrThrow()
            assertEquals(1, f.before.get()); assertEquals(1, f.exits.get()); assertEquals(1, f.completedExits.get())
            assertTrue(coordinator.state.value.restartRequired); assertFalse(coordinator.state.value.busy)
        }
    }

    @Test fun localCommitStillCompletesExitWhenBeforeRestoreDisposesInitiatingPage(): Unit = runBlocking {
        RestoreFixture().use { f ->
            val coordinator = f.coordinator(cancelPage = true)
            f.runPage { coordinator.importLocal(f.zip) }
            f.assertRestored()
            assertEquals(1, f.exits.get()); assertEquals(1, f.completedExits.get())
            assertEquals(1, f.cancellations.get()); assertNull(f.result.get())
            assertFalse(coordinator.state.value.busy)
        }
    }

    @Test fun realWebDavCommitStillCompletesExitWhenBeforeRestoreDisposesPage(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes).use { dav ->
            f.store.save(DesktopBackupSnapshot(dav.config))
            val coordinator = f.coordinator(cancelPage = true)
            f.runPage { coordinator.restoreLatest() }
            f.assertRestored(); dav.assertExactReadOnlyRequests()
            assertEquals(1, f.before.get()); assertEquals(1, f.exits.get()); assertEquals(1, f.completedExits.get())
            assertEquals(1, f.cancellations.get()); assertFalse(coordinator.state.value.busy)
        } }
    }

    @Test fun commitCallbackFiresBeforeOriginalWebDavWithContextLosesReturn(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes).use { dav ->
            val committed = AtomicInteger()
            val returned = AtomicInteger()
            val archive = DesktopBackupArchive(f.root) { f.pageJob.cancel() }
            val service = WebDavBackupService(archive) { committed.incrementAndGet() }
            val job = f.scope.launch(f.ui) {
                try { service.restoreLatest(dav.config).getOrThrow(); returned.incrementAndGet() }
                catch (_: CancellationException) { f.cancellations.incrementAndGet() }
            }
            withTimeout(10_000) { job.join() }
            f.assertRestored(); dav.assertExactReadOnlyRequests()
            assertEquals(1, committed.get()); assertEquals(0, returned.get()); assertEquals(1, f.cancellations.get())
        } }
    }

    @Test fun invalidArchiveNeverRetiresPageReplacesFilesOrExits(): Unit = runBlocking {
        RestoreFixture().use { f ->
            Files.write(f.zip, byteArrayOf(1, 2, 3))
            val coordinator = f.coordinator(cancelPage = true)
            assertTrue(coordinator.importLocal(f.zip).isFailure)
            f.assertOriginal(); assertEquals(0, f.before.get()); assertEquals(0, f.exits.get())
            assertTrue(f.pageJob.isActive); assertFalse(coordinator.state.value.restartRequired)
        }
    }

    @Test fun replacementFailureRollsBackEarlierFileAndNeverSignalsSuccessfulExit(): Unit = runBlocking {
        RestoreFixture().use { f ->
            Files.delete(f.root.resolve("player-settings.json"))
            Files.createDirectory(f.root.resolve("player-settings.json"))
            val coordinator = f.coordinator(cancelPage = true)
            f.runPage { coordinator.importLocal(f.zip) }
            assertEquals("{\"original\":true}", Files.readString(f.root.resolve("library.json")))
            assertTrue(Files.isDirectory(f.root.resolve("player-settings.json")))
            assertEquals(1, f.before.get()); assertEquals(0, f.exits.get())
            assertFalse(coordinator.state.value.busy)
        }
    }

    @Test fun failedQuiescenceIsNotACommitAndDoesNotExecuteExit(): Unit = runBlocking {
        RestoreFixture().use { f ->
            val coordinator = f.coordinator(failBefore = true)
            assertFailsWith<CancellationException> { coordinator.importLocal(f.zip) }
            f.assertOriginal(); assertEquals(1, f.before.get()); assertEquals(0, f.exits.get())
            assertFalse(coordinator.state.value.busy)
        }
    }

    @Test fun cancellationBeforeStartingRestoreDoesNotReadNetworkOrExit(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes).use { dav ->
            f.store.save(DesktopBackupSnapshot(dav.config))
            val coordinator = f.coordinator()
            f.pageJob.cancel()
            f.runPage { coordinator.restoreLatest() }
            f.assertOriginal(); assertTrue(dav.calls.isEmpty()); assertEquals(0, f.before.get()); assertEquals(0, f.exits.get())
        } }
    }

    @Test fun cancellationDuringRealDownloadPreventsRetirementAndReplacement(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes, delayGet = true).use { dav ->
            f.store.save(DesktopBackupSnapshot(dav.config))
            val coordinator = f.coordinator()
            val job = f.scope.launch {
                try { coordinator.restoreLatest(); fail("cancelled restore returned") }
                catch (_: CancellationException) { f.cancellations.incrementAndGet() }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { dav.getEntered.await(5, TimeUnit.SECONDS) })
                f.pageJob.cancel()
            } finally { dav.releaseGet.countDown() }
            withTimeout(10_000) { job.join() }
            f.assertOriginal(); dav.assertExactReadOnlyRequests()
            assertEquals(0, f.before.get()); assertEquals(0, f.exits.get()); assertEquals(1, f.cancellations.get())
            assertFalse(coordinator.state.value.restartRequired); assertFalse(coordinator.state.value.busy)
        } }
    }

    @Test fun realDownloadFailureDoesNotInvokeCommitOrExit(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes, getStatus = 503).use { dav ->
            f.store.save(DesktopBackupSnapshot(dav.config))
            val coordinator = f.coordinator()
            assertTrue(coordinator.restoreLatest().isFailure)
            f.assertOriginal(); dav.assertExactReadOnlyRequests()
            assertEquals(0, f.before.get()); assertEquals(0, f.exits.get())
        } }
    }

    @Test fun failureThenValidRestoreDoesNotReuseAStaleCommitFlag(): Unit = runBlocking {
        RestoreFixture().use { f ->
            val coordinator = f.coordinator()
            Files.write(f.zip, byteArrayOf(1))
            assertTrue(coordinator.importLocal(f.zip).isFailure); assertEquals(0, f.exits.get())
            Files.write(f.zip, f.bytes)
            coordinator.importLocal(f.zip).getOrThrow()
            f.assertRestored(); assertEquals(1, f.before.get()); assertEquals(1, f.exits.get())
        }
    }

    @Test fun secondConcurrentRestoreDoesNotExitBeforeTheAcceptedOperationCommits(): Unit = runBlocking {
        RestoreFixture().use { f -> RestoreDav(f.bytes, delayGet = true).use { dav ->
            f.store.save(DesktopBackupSnapshot(dav.config))
            val coordinator = f.coordinator()
            val first = async(Dispatchers.Default) { coordinator.restoreLatest() }
            try {
                assertTrue(withContext(Dispatchers.IO) { dav.getEntered.await(5, TimeUnit.SECONDS) })
                assertTrue(coordinator.importLocal(f.zip).isFailure)
                f.assertOriginal(); assertEquals(0, f.before.get()); assertEquals(0, f.exits.get())
            } finally { dav.releaseGet.countDown() }
            withTimeout(10_000) { first.await().getOrThrow() }
            f.assertRestored(); assertEquals(1, f.exits.get()); assertEquals(1, f.completedExits.get())
        } }
    }

    @Test fun archiveSuccessCallbackIsNotCalledAfterValidationOrRollbackFailure() {
        RestoreFixture().use { f ->
            val calls = AtomicInteger()
            assertFails { DesktopBackupArchive(f.root).restore(byteArrayOf(0)) { calls.incrementAndGet() } }
            Files.delete(f.root.resolve("player-settings.json")); Files.createDirectory(f.root.resolve("player-settings.json"))
            assertFails { DesktopBackupArchive(f.root).restore(f.bytes) { calls.incrementAndGet() } }
            assertEquals(0, calls.get()); assertEquals("{\"original\":true}", Files.readString(f.root.resolve("library.json")))
        }
    }
}
