package com.bilipai.desktop.backup

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*

/** Real coordinator operations and their Jobs; private files, no UI or WebDAV server. */
class DesktopBackupUpdateHoldTest {
    @Test fun exactDialogDisposalCannotReleaseAReopenedEditor() {
        val allowed = AtomicBoolean(true)
        val hold = DesktopBackupUpdateHold(allowed::get)
        val old = Any()
        val current = Any()
        assertFalse(hold.blocksUpdateInstallation())
        assertTrue(hold.mountEditor(old))
        hold.unmountEditor(old)
        assertTrue(hold.mountEditor(current))
        hold.unmountEditor(old)
        assertTrue(hold.blocksUpdateInstallation())
        hold.unmountEditor(current)
        allowed.set(false)
        assertFalse(hold.mountEditor(Any()))
        assertFalse(hold.blocksUpdateInstallation())
    }

    @Test fun realExportIsHeldBeforeBusyAndReleasesAtOperationCompletionNotOuterJob(): Unit = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Job>()
            val release = CompletableDeferred<Unit>()
            val operationFinished = CompletableDeferred<Unit>()
            val outerRelease = CompletableDeferred<Unit>()
            val coordinator = f.coordinator { job ->
                val admitted = f.hold.beginOperation(job)
                entered.complete(job)
                release.await()
                admitted
            }
            val old = Any()
            assertTrue(f.hold.mountEditor(old))
            val outer = launch {
                coordinator.exportLocal(f.output).getOrThrow()
                operationFinished.complete(Unit)
                outerRelease.await()
            }
            try {
                val actualOperation = withTimeout(4_000) { entered.await() }
                assertNotSame(outer, actualOperation)
                assertEquals(setOf(actualOperation), f.hold.activity.value.operations)
                assertFalse(coordinator.state.value.busy)
                assertFalse(Files.exists(f.output))
                f.hold.unmountEditor(old)
                assertTrue(f.hold.blocksUpdateInstallation())
                release.complete(Unit)
                withTimeout(4_000) { operationFinished.await() }
                assertTrue(actualOperation.isCompleted)
                assertTrue(outer.isActive)
                assertFalse(f.hold.blocksUpdateInstallation())
                assertTrue(Files.size(f.output) > 0)
            } finally {
                release.complete(Unit)
                outerRelease.complete(Unit)
                outer.cancelAndJoin()
            }
        }
    }

    @Test fun cancellingAfterDialogClosesHoldsUntilTheActualOperationDrains(): Unit = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Job>()
            val release = CompletableDeferred<Unit>()
            val coordinator = f.coordinator { job ->
                val admitted = f.hold.beginOperation(job)
                entered.complete(job)
                withContext(NonCancellable) { release.await() }
                admitted
            }
            val editor = Any()
            assertTrue(f.hold.mountEditor(editor))
            val operation = launch { coordinator.exportLocal(f.output) }
            try {
                val actual = withTimeout(4_000) { entered.await() }
                f.hold.unmountEditor(editor)
                operation.cancel()
                assertTrue(actual.isCancelled)
                assertFalse(actual.isCompleted)
                assertTrue(f.hold.blocksUpdateInstallation())
                val reopened = Any()
                assertTrue(f.hold.mountEditor(reopened))
                release.complete(Unit)
                withTimeout(4_000) { operation.join() }
                assertTrue(actual.isCompleted)
                assertTrue(f.hold.activity.value.operations.isEmpty())
                assertTrue(f.hold.blocksUpdateInstallation())
                f.hold.unmountEditor(reopened)
                assertFalse(f.hold.blocksUpdateInstallation())
                assertFalse(Files.exists(f.output))
                assertFalse(coordinator.state.value.busy)
            } finally {
                release.complete(Unit)
                operation.cancelAndJoin()
            }
        }
    }

    @Test fun installationAdmissionRejectsRealUiAndAutomaticOperationsBeforeIo(): Unit = runBlocking {
        Fixture().use { f ->
            val coordinator = f.coordinator()
            f.allowed.set(false)
            assertTrue(coordinator.exportLocal(f.output).isFailure)
            assertTrue(coordinator.automaticBackupIfDue().isFailure)
            assertFalse(Files.exists(f.output))
            assertFalse(Files.exists(f.store.directory.resolve(".webdav-backup.lock")))
            assertFalse(coordinator.state.value.busy)
            assertFalse(f.hold.blocksUpdateInstallation())
            f.allowed.set(true)
            coordinator.exportLocal(f.output).getOrThrow()
            assertTrue(Files.exists(f.output))
            assertFalse(f.hold.blocksUpdateInstallation())
        }
    }

    @Test fun actualFileFailureAndAutomaticNoopBothReleaseTheirOperationJobs(): Unit = runBlocking {
        Fixture().use { f ->
            val operations = mutableListOf<Job>()
            val coordinator = f.coordinator { job ->
                operations += job
                f.hold.beginOperation(job)
            }
            assertTrue(coordinator.exportLocal(f.store.directory).isFailure)
            assertTrue(coordinator.state.value.error)
            assertFalse(f.hold.blocksUpdateInstallation())
            coordinator.automaticBackupIfDue().getOrThrow()
            assertEquals(2, operations.size)
            assertTrue(operations.all(Job::isCompleted))
            assertNotSame(operations[0], operations[1])
            assertFalse(f.hold.blocksUpdateInstallation())
        }
    }

    @Test fun aCancelledBeforeStartCallerCannotReserveOrRunBackup(): Unit = runBlocking {
        Fixture().use { f ->
            var admissions = 0
            val coordinator = f.coordinator { job -> admissions++; f.hold.beginOperation(job) }
            val job = launch(start = CoroutineStart.LAZY) { coordinator.exportLocal(f.output) }
            job.cancelAndJoin()
            assertFalse(job.start())
            assertEquals(0, admissions)
            assertFalse(Files.exists(f.output))
            assertFalse(f.hold.blocksUpdateInstallation())
        }
    }

    private class Fixture : AutoCloseable {
        private val temporary = Files.createTempDirectory("backup-update-hold-").toRealPath()
        val store = DesktopBackupStore(Files.createDirectory(temporary.resolve("settings")))
        val output = temporary.resolve("export.zip")
        val allowed = AtomicBoolean(true)
        val hold = DesktopBackupUpdateHold(allowed::get)
        init { Files.writeString(store.directory.resolve("library.json"), "{\"test\":true}") }

        fun coordinator(admit: suspend (Job) -> Boolean = { hold.beginOperation(it) }) =
            DesktopBackupCoordinator(store, object : DesktopBackupScheduler {
                override fun install() = error("No scheduled task creation")
                override fun uninstall() = error("No scheduled task removal")
            }, beforeOperation = admit)

        override fun close() {
            assertTrue(store.directory.toRealPath().startsWith(temporary))
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                assertTrue(path.toAbsolutePath().normalize().startsWith(temporary))
                Files.delete(path)
            } }
        }
    }
}
