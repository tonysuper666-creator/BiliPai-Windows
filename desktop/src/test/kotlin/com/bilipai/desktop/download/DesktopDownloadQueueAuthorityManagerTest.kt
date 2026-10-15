package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.DownloadStatus
import com.bilipai.desktop.player.DesktopLocalPlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.update.DesktopUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real original Manager workers, checkpoint and OS lease on isolated private Temp state. */
class DesktopDownloadQueueAuthorityManagerTest {
    private class Worker(val task: DownloadTask, val directory: Path, val job: Job) {
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
    }
    private class Fixture {
        val root = Files.createTempDirectory("bp-queue-manager-").toRealPath()
        val state = root.resolve("BiliPai/downloads.json")
        val media = root.resolve("media")
        val marker get() = state.resolveSibling("downloads.json.bilipai-transfer.json")
        private val publicationOwner = SupervisorJob()
        private val publicationGate = Any()
        val workers = CopyOnWriteArrayList<Worker>()
        private val managers = mutableListOf<DesktopDownloadManager>()
        private val client = OkHttpClient.Builder().addInterceptor {
            throw IOException("Private ownership fixture disallows media transport")
        }.build()
        fun manager(startup: String? = null): DesktopDownloadManager {
            val publication = DesktopLocalPlaybackPublication({ publicationOwner.isActive }, { action ->
                synchronized(publicationGate) { if (!publicationOwner.isActive) false else { action(); true } }
            })
            return DesktopDownloadManager(client, state, DownloadMuxer { _, _, _ -> error("No fixture media may reach muxing") },
                danmakuDownloader = { task, directory, _ ->
                    val worker = Worker(task, directory, DownloadDanmakuTransport.taskOwner().callerJob)
                    val originalPart = directory.resolve("ownership-fixture.pb.part")
                    val previous = workers.firstOrNull { it.task.id == task.id }
                    if (previous == null) {
                        Files.writeString(originalPart, "original-part:" + task.id, java.nio.file.StandardOpenOption.CREATE_NEW)
                    } else {
                        check(directory == previous.directory) { "A resumed original task must retain its directory" }
                        check(Files.isRegularFile(originalPart)) { "Production lost the original partial file before resuming" }
                        check(Files.readString(originalPart) == "original-part:" + task.id) { "Production changed the original partial bytes" }
                    }
                    workers += worker
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) {
                        worker.cleanupEntered.complete(Unit)
                        worker.cleanupRelease.await()
                    } }
                }, publication = publication, startupHandoffToken = startup).also(managers::add)
        }
        fun enqueue(manager: DesktopDownloadManager, bvid: String = "BVoriginal", cid: Long = 10L): String =
            manager.enqueue(PlaybackSource("https://cdn.example/$bvid", title = bvid), media,
                DownloadMetadata(bvid = bvid, cid = cid, includeCover = false, includeDanmaku = true))
        suspend fun worker(id: String, count: Int): Worker = withTimeout(5_000) {
            while (workers.count { it.task.id == id } < count) delay(5)
            workers.filter { it.task.id == id }[count - 1]
        }
        fun releaseAll() { workers.forEach { it.cleanupRelease.complete(Unit) } }
        suspend fun drain(manager: DesktopDownloadManager): Unit = coroutineScope {
            val release = launch(Dispatchers.Default) {
                while (isActive) { releaseAll(); delay(5) }
            }
            try { withTimeout(5_000) { manager.shutdownForApplicationExit() } }
            finally { release.cancelAndJoin() }
        }
        fun target(): DesktopDownloadQueueAuthority.CommitTarget {
            val executable = root.resolve("sealed-target.exe")
            Files.writeString(executable, "not executable; no test may launch this file")
            Files.createDirectories(state.parent.resolve("updates"))
            return DesktopDownloadQueueAuthority.CommitTarget("owner/BiliPai-Windows", "windows-v0.2.427.52",
                executable.toString(), state.parent.resolve("updates/active-install.json").toString())
        }
        fun commit(target: DesktopDownloadQueueAuthority.CommitTarget, token: String) {
            val value = kotlinx.serialization.json.buildJsonObject {
                put("repository", kotlinx.serialization.json.JsonPrimitive(target.repository))
                put("version", kotlinx.serialization.json.JsonPrimitive(target.version))
                put("executablePath", kotlinx.serialization.json.JsonPrimitive(target.executablePath))
                put("downloadHandoffToken", kotlinx.serialization.json.JsonPrimitive(token))
            }
            Files.writeString(Path.of(target.registryFile), value.toString())
            check(DesktopUpdater.downloadHandoffCommitMatches(target, token))
        }
        suspend fun close(): Unit = withContext(NonCancellable) {
            try { managers.asReversed().forEach { drain(it) } }
            finally {
                publicationOwner.cancelAndJoin()
                client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
            }
            check(root.isAbsolute && root.fileName.toString().startsWith("bp-queue-manager-"))
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    private fun token(): String = UUID.randomUUID().toString()

    @Test fun handoffKeepsActualLeaseUntilWorkerCleanupAndDurableCheckpointThenNoTokenIsReadOnly(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val id = f.enqueue(original); val worker = f.worker(id, 1)
            val transfer = token(); val ticket = original.beginUpdateHandoff(transfer)
            val draining = async(start = CoroutineStart.UNDISPATCHED) { original.awaitUpdateHandoff(ticket) }
            withTimeout(5_000) { worker.cleanupEntered.await() }
            assertFalse(draining.isCompleted); assertFalse(worker.job.isCompleted)
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(f.state).lease)
            assertFalse(Files.exists(f.marker), "No transfer is published before actual drain/checkpoint")
            worker.cleanupRelease.complete(Unit)
            withTimeout(5_000) { draining.await() }
            assertTrue(worker.job.isCompleted); assertTrue(ticket.drained)
            assertFalse(original.acceptsMutations())
            assertTrue(Files.isRegularFile(f.marker))
            val journal = Files.readAllBytes(f.state); val durable = Files.readAllBytes(f.marker)
            val directories = Files.list(f.media).use { it.toList().toSet() }
            val noToken = f.manager()
            assertFalse(noToken.acceptsMutations()); assertNotNull(noToken.authorityReason.value)
            assertFalse(noToken.canPublishStartupHealth(transfer))
            assertFailsWith<IllegalStateException> { noToken.resume(id) }
            assertFailsWith<IllegalStateException> { f.enqueue(noToken, "BVrejected", 99L) }
            assertFailsWith<IllegalStateException> { noToken.remove(id, deleteFiles = true) }
            assertContentEquals(journal, Files.readAllBytes(f.state))
            assertContentEquals(durable, Files.readAllBytes(f.marker))
            assertEquals(directories, Files.list(f.media).use { it.toList().toSet() })
            assertEquals(1, f.workers.size)
            f.drain(noToken)
            assertContentEquals(journal, Files.readAllBytes(f.state))
            assertContentEquals(durable, Files.readAllBytes(f.marker))
        } finally { f.close() }
    }

    @Test fun sameHealthTokenClaimsOnceAndUncommittedChildExitLeavesNormalColdStartFrozen(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val id = f.enqueue(original); val first = f.worker(id, 1)
            val transfer = token(); val ticket = original.beginUpdateHandoff(transfer)
            first.cleanupRelease.complete(Unit); original.awaitUpdateHandoff(ticket)
            val child = f.manager(transfer); val second = f.worker(id, 2)
            assertTrue(child.acceptsMutations()); assertTrue(child.canPublishStartupHealth(transfer))
            assertFalse(child.canPublishStartupHealth(token()))
            child.savePlaybackPosition(id, 31_000, 120_000)
            val changed = Files.readAllBytes(f.state)
            original.shutdownForApplicationExit(); original.close()
            assertContentEquals(changed, Files.readAllBytes(f.state))
            assertFalse(second.job.isCompleted)
            f.drain(child)
            val persisted = Files.readAllBytes(f.state); val pending = Files.readAllBytes(f.marker)
            val cold = f.manager()
            assertFalse(cold.acceptsMutations()); assertFalse(cold.canPublishStartupHealth(transfer))
            val repeated = f.manager(transfer)
            assertFalse(repeated.acceptsMutations()); assertFalse(repeated.canPublishStartupHealth(transfer))
            assertEquals(2, f.workers.count { it.task.id == id })
            assertContentEquals(persisted, Files.readAllBytes(f.state))
            assertContentEquals(pending, Files.readAllBytes(f.marker))
        } finally { f.close() }
    }

    @Test fun rollbackCannotReacquireLiveSuccessorAndAfterRealDrainStrictReadsItsChangedDisk(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val id = f.enqueue(original); val first = f.worker(id, 1)
            val initialPart = Files.readAllBytes(first.directory.resolve("ownership-fixture.pb.part"))
            val transfer = token(); val ticket = original.beginUpdateHandoff(transfer)
            first.cleanupRelease.complete(Unit); original.awaitUpdateHandoff(ticket)
            val child = f.manager(transfer); f.worker(id, 2)
            child.savePlaybackPosition(id, 31_000, 120_000)
            val added = f.enqueue(child, "BVsuccessor", 20L)
            val addedWorker = f.worker(added, 1)
            child.pause(added)
            withTimeout(5_000) {
                addedWorker.cleanupEntered.await()
                val joined = async(start = CoroutineStart.UNDISPATCHED) { addedWorker.job.join() }
                assertFalse(joined.isCompleted, "The canonical root Job is still held in real cleanup")
                addedWorker.cleanupRelease.complete(Unit)
                joined.await()
            }
            assertTrue(addedWorker.job.isCompleted)
            val changed = Files.readAllBytes(f.state); val pending = Files.readAllBytes(f.marker)
            assertFailsWith<IllegalStateException> { original.rollbackUpdateHandoff(ticket) }
            assertFalse(original.acceptsMutations())
            assertContentEquals(changed, Files.readAllBytes(f.state))
            assertContentEquals(pending, Files.readAllBytes(f.marker))
            f.drain(child)
            assertTrue(Files.exists(f.marker), "Uncommitted exit does not grant rollback by itself")
            assertTrue(original.rollbackUpdateHandoff(ticket)) // This test has actually joined the known child owner.
            val resumed = f.worker(id, 3)
            assertTrue(original.acceptsMutations()); assertFalse(Files.exists(f.marker))
            assertEquals(31_000L, original.tasks.value.single { it.id == id }.item.lastPlaybackPositionMs)
            assertEquals(DownloadStatus.PAUSED, original.tasks.value.single { it.id == added }.status)
            assertTrue(first.job.isCompleted); assertTrue(first.job !== resumed.job)
            assertContentEquals(initialPart, Files.readAllBytes(resumed.directory.resolve("ownership-fixture.pb.part")))
        } finally { f.close() }
    }

    @Test fun rollbackWaitsForRealBusyLeaseThenRestoresSuccessorCheckpointAndOriginalPart(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val id = f.enqueue(original); val first = f.worker(id, 1)
            val part = Files.readAllBytes(first.directory.resolve("ownership-fixture.pb.part"))
            val transfer = token(); val ticket = original.beginUpdateHandoff(transfer)
            first.cleanupRelease.complete(Unit); original.awaitUpdateHandoff(ticket)
            val child = f.manager(transfer); f.worker(id, 2)
            child.savePlaybackPosition(id, 47_000, 120_000)
            val checkpoint = Files.readAllBytes(f.state); val pending = Files.readAllBytes(f.marker)
            val recovery = async { original.rollbackUpdateHandoff(ticket) }
            withTimeout(5_000) { original.authorityReason.first { it != null } }
            assertFalse(recovery.isCompleted); assertFalse(original.acceptsMutations())
            assertTrue(original.updateHandoffPending.value)
            assertFailsWith<IllegalStateException> { original.resume(id) }
            assertContentEquals(checkpoint, Files.readAllBytes(f.state))
            assertContentEquals(pending, Files.readAllBytes(f.marker))
            f.drain(child)
            assertTrue(withTimeout(5_000) { recovery.await() })
            val resumed = f.worker(id, 3)
            assertTrue(original.acceptsMutations()); assertFalse(original.updateHandoffPending.value)
            assertNull(original.authorityReason.value); assertFalse(Files.exists(f.marker))
            assertEquals(47_000L, original.tasks.value.single { it.id == id }.item.lastPlaybackPositionMs)
            assertContentEquals(part, Files.readAllBytes(resumed.directory.resolve("ownership-fixture.pb.part")))
        } finally { f.close() }
    }

    @Test fun rollbackBusyRetryCannotAdmitChangedTransferGeneration(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val transfer = token(); val ticket = original.beginUpdateHandoff(transfer)
            original.awaitUpdateHandoff(ticket)
            val checkpoint = Files.readAllBytes(f.state)
            FileChannel.open(f.state.resolveSibling("downloads.json.bilipai-lock"), WRITE).use { channel ->
                channel.lock().use { blockingLease ->
                    val recovery = async { runCatching { original.rollbackUpdateHandoff(ticket) } }
                    withTimeout(5_000) { original.authorityReason.first { it != null } }
                    assertFalse(recovery.isCompleted); assertFalse(original.acceptsMutations())
                    val before = Files.readString(f.marker); assertTrue(before.contains(transfer))
                    Files.writeString(f.marker, before.replace(transfer, token()))
                    val changed = Files.readAllBytes(f.marker)
                    blockingLease.release()
                    val failure = withTimeout(5_000) { recovery.await() }.exceptionOrNull()
                    assertIs<IllegalStateException>(failure)
                    assertEquals("下载交接代次已经改变", failure.message)
                    assertFalse(original.acceptsMutations()); assertTrue(original.updateHandoffPending.value)
                    assertContentEquals(checkpoint, Files.readAllBytes(f.state))
                    assertContentEquals(changed, Files.readAllBytes(f.marker))
                    assertTrue(f.workers.isEmpty())
                }
            }
        } finally { f.close() }
    }

    @Test fun callerCancellationDuringBusyRollbackStillFinishesQueueRecovery(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val ticket = original.beginUpdateHandoff(token())
            original.awaitUpdateHandoff(ticket)
            FileChannel.open(f.state.resolveSibling("downloads.json.bilipai-lock"), WRITE).use { channel ->
                channel.lock().use { blockingLease ->
                    val recovery = async { original.rollbackUpdateHandoff(ticket) }
                    withTimeout(5_000) { original.authorityReason.first { it != null } }
                    recovery.cancel()
                    assertFalse(recovery.isCompleted); assertFalse(original.acceptsMutations())
                    blockingLease.release()
                    withTimeout(5_000) { recovery.join() }
                    assertTrue(recovery.isCancelled)
                    assertTrue(original.acceptsMutations()); assertFalse(original.updateHandoffPending.value)
                    assertFalse(Files.exists(f.marker)); assertNull(original.authorityReason.value)
                }
            }
        } finally { f.close() }
    }

    @Test fun managerClosedDuringBusyRollbackReleasesRecoveredLeaseWithoutReopeningQueue(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val ticket = original.beginUpdateHandoff(token())
            original.awaitUpdateHandoff(ticket)
            val checkpoint = Files.readAllBytes(f.state)
            FileChannel.open(f.state.resolveSibling("downloads.json.bilipai-lock"), WRITE).use { channel ->
                channel.lock().use { blockingLease ->
                    val recovery = async { original.rollbackUpdateHandoff(ticket) }
                    withTimeout(5_000) { original.authorityReason.first { it != null } }
                    original.close()
                    assertFalse(original.acceptsMutations()); assertFalse(recovery.isCompleted)
                    blockingLease.release()
                    assertFalse(withTimeout(5_000) { recovery.await() })
                    original.shutdownForApplicationExit()
                    assertFalse(original.acceptsMutations()); assertFalse(original.updateHandoffPending.value)
                    assertFalse(Files.exists(f.marker)); assertTrue(f.workers.isEmpty())
                    assertContentEquals(checkpoint, Files.readAllBytes(f.state))
                    checkNotNull(DesktopDownloadQueueAuthority.tryAcquire(f.state).lease).use { assertTrue(it.holds(f.state)) }
                }
            }
        } finally { f.close() }
    }

    @Test fun corruptStrictRollbackReadCannotClearTransferOrResumeQueue(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val id = f.enqueue(original); val first = f.worker(id, 1)
            val transfer = token(); val ticket = original.beginUpdateHandoff(transfer)
            first.cleanupRelease.complete(Unit); original.awaitUpdateHandoff(ticket)
            val pending = Files.readAllBytes(f.marker)
            Files.writeString(f.state, "{invalid-task-journal")
            val invalid = Files.readAllBytes(f.state)
            assertFailsWith<SerializationException> { original.rollbackUpdateHandoff(ticket) }
            assertFalse(original.acceptsMutations()); assertTrue(original.updateHandoffPending.value)
            assertContentEquals(invalid, Files.readAllBytes(f.state))
            assertContentEquals(pending, Files.readAllBytes(f.marker))
            assertEquals(1, f.workers.count { it.task.id == id })
        } finally { f.close() }
    }

    @Test fun committedChildCleanExitPermitsNormalColdResumeAndFrozenOldCloseNeverOverwritesIt(): Unit = runBlocking {
        val f = Fixture()
        try {
            val original = f.manager(); val id = f.enqueue(original); val first = f.worker(id, 1)
            val target = f.target(); val transfer = token(); val ticket = original.beginUpdateHandoff(transfer, target)
            first.cleanupRelease.complete(Unit); original.awaitUpdateHandoff(ticket)
            val child = f.manager(transfer); val second = f.worker(id, 2)
            child.savePlaybackPosition(id, 41_000, 120_000)
            f.commit(target, transfer) // Actual parser/token match; Updater CAS is covered by its own actual activation test.
            original.commitUpdateHandoff(ticket)
            val childDisk = Files.readAllBytes(f.state)
            original.shutdownForApplicationExit(); original.close(); original.close()
            assertContentEquals(childDisk, Files.readAllBytes(f.state)); assertFalse(second.job.isCompleted)
            f.drain(child)
            assertFalse(Files.exists(f.marker))
            val cold = f.manager(); val resumed = f.worker(id, 3)
            assertTrue(cold.acceptsMutations()); assertTrue(resumed.job.isActive)
            assertEquals(41_000L, cold.tasks.value.single { it.id == id }.item.lastPlaybackPositionMs)
            assertTrue(first.job !== resumed.job)
        } finally { f.close() }
    }
}
