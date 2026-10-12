package com.bilipai.desktop.download

import com.bilipai.desktop.update.DesktopUpdater
import kotlinx.coroutines.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Private Temp files and actual OS leases; never starts Main or an application image. */
class DesktopDownloadQueueAuthorityTest {
    private fun root(): Path = Files.createTempDirectory("bp-queue-authority-").toRealPath()
    private fun marker(state: Path): Path = state.resolveSibling(state.fileName.toString() + ".bilipai-transfer.json")
    private fun token(): String = UUID.randomUUID().toString()
    private fun acquire(state: Path, startup: String? = null, recovery: String? = null): DesktopDownloadQueueAuthority.Lease =
        assertNotNull(DesktopDownloadQueueAuthority.tryAcquire(state, startup, recovery).lease)
    private fun cleanup(root: Path) {
        check(root.isAbsolute && root.fileName.toString().startsWith("bp-queue-authority-"))
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    private fun target(root: Path): DesktopDownloadQueueAuthority.CommitTarget {
        val executable = root.resolve("sealed-target.exe")
        Files.writeString(executable, "not an executable; no test may launch this file")
        Files.createDirectories(root.resolve("updates"))
        return DesktopDownloadQueueAuthority.CommitTarget("owner/BiliPai-Windows", "windows-v0.2.427.52",
            executable.toString(), root.resolve("updates/active-install.json").toString())
    }
    private fun commit(target: DesktopDownloadQueueAuthority.CommitTarget, token: String,
        version: String = target.version, executable: String = target.executablePath) {
        val value = kotlinx.serialization.json.buildJsonObject {
            put("repository", kotlinx.serialization.json.JsonPrimitive(target.repository))
            put("version", kotlinx.serialization.json.JsonPrimitive(version))
            put("executablePath", kotlinx.serialization.json.JsonPrimitive(executable))
            put("downloadHandoffToken", kotlinx.serialization.json.JsonPrimitive(token))
        }
        Files.writeString(Path.of(target.registryFile), value.toString())
    }

    @Test fun actualLeaseExcludesAnotherOwnerAndRetainsPermanentLockInode(): Unit {
        val root = root(); val state = root.resolve("state.json")
        try {
            val first = acquire(state)
            try {
                assertTrue(first.holds(state))
                val other = DesktopDownloadQueueAuthority.tryAcquire(state)
                assertNull(other.lease); assertTrue(other.busy)
                assertFalse(Files.exists(state), "Authority must not create task JSON")
            } finally { first.close() }
            val lock = root.resolve("state.json.bilipai-lock")
            assertTrue(Files.isRegularFile(lock)); assertEquals(0L, Files.size(lock))
            acquire(state).use { assertTrue(it.holds(state)) }
            assertTrue(Files.isRegularFile(lock))
        } finally { cleanup(root) }
    }

    @Test fun durableUncommittedTransferSurvivesLeaseReleaseAndRejectsNoOrWrongToken(): Unit {
        val root = root(); val state = root.resolve("state.json"); val transfer = token()
        try {
            acquire(state).use { it.prepareTransfer(transfer, null) }
            val durable = Files.readAllBytes(marker(state))
            for (startup in listOf(null, token())) {
                val attempt = DesktopDownloadQueueAuthority.tryAcquire(state, startup)
                assertNull(attempt.lease); assertFalse(attempt.busy)
                assertContentEquals(durable, Files.readAllBytes(marker(state)))
            }
            val child = acquire(state, startup = transfer)
            val claimed = Files.readAllBytes(marker(state))
            assertFalse(durable.contentEquals(claimed))
            child.close() // Lock release is deliberately not retirement confirmation.
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(state).lease)
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(state, startupToken = transfer).lease,
                "The same health UUID must not claim an already claimed unresolved transfer again")
            assertContentEquals(claimed, Files.readAllBytes(marker(state)))
            acquire(state, recovery = transfer).use { it.finishRollback(transfer) }
            assertFalse(Files.exists(marker(state)))
            acquire(state).use { assertTrue(it.holds(state)) }
        } finally { cleanup(root) }
    }

    @Test fun exactRegistryTokenIsRequiredAndOldCommittedTokenCannotClearANewGeneration(): Unit {
        val root = root(); val state = root.resolve("state.json"); val first = token(); val second = token()
        try {
            val target = target(root)
            acquire(state).use { it.prepareTransfer(first, target) }
            val child = acquire(state, startup = first)
            try {
                commit(target, token())
                child.finishCleanExit()
                assertTrue(Files.exists(marker(state)))
                assertFalse(DesktopUpdater.downloadHandoffCommitMatches(target, first))
                commit(target, first)
                assertTrue(DesktopUpdater.downloadHandoffCommitMatches(target, first))
                child.prepareTransfer(second, target)
            } finally { child.close() }
            val secondMarker = Files.readAllBytes(marker(state))
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(state).lease,
                "Matching version/executable with an old token is not a new generation commit")
            assertContentEquals(secondMarker, Files.readAllBytes(marker(state)))
            commit(target, second)
            acquire(state).use { assertTrue(it.holds(state)) }
            assertFalse(Files.exists(marker(state)))
        } finally { cleanup(root) }
    }

    @Test fun mismatchedOrUnreadableCommitCannotClearPendingOwnership(): Unit {
        val root = root(); val state = root.resolve("state.json"); val transfer = token()
        try {
            val target = target(root)
            acquire(state).use { it.prepareTransfer(transfer, target) }
            val durable = Files.readAllBytes(marker(state))
            commit(target, transfer, version = "windows-v0.2.427.51")
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(state).lease)
            commit(target, transfer, executable = root.resolve("other.exe").toString())
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(state).lease)
            Files.writeString(Path.of(target.registryFile), "{broken-registry")
            assertNull(DesktopDownloadQueueAuthority.tryAcquire(state).lease)
            assertContentEquals(durable, Files.readAllBytes(marker(state)))
            acquire(state, recovery = transfer).use { it.finishRollback(transfer) }
        } finally { cleanup(root) }
    }

    @Test fun malformedTransferNeverCreatesANewQueueOwnerOrChangesTheMarker(): Unit {
        val root = root(); val state = root.resolve("state.json")
        try {
            Files.writeString(marker(state), "{broken-transfer")
            val durable = Files.readAllBytes(marker(state))
            val attempt = DesktopDownloadQueueAuthority.tryAcquire(state)
            assertNull(attempt.lease); assertFalse(attempt.busy)
            assertContentEquals(durable, Files.readAllBytes(marker(state)))
            assertFalse(Files.exists(state))
        } finally { cleanup(root) }
    }

    @Test fun privateJvmFileChannelActuallyExcludesAnotherProcessAndReleaseAllowsAcquire(): Unit = runBlocking {
        val root = root(); val state = root.resolve("state.json")
        val ready = root.resolve("child-ready"); val release = root.resolve("child-release")
        val javaBin = Path.of(System.getProperty("java.home"), "bin")
        val java = if (Files.isRegularFile(javaBin.resolve("javaw.exe"))) javaBin.resolve("javaw.exe")
            else javaBin.resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val classpath = listOf(DesktopDownloadQueueAuthorityLeaseChild::class.java,
            Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(File.pathSeparator)
        var child: Process? = null
        try {
            val builder = ProcessBuilder(java.toString(), "-cp", classpath,
                DesktopDownloadQueueAuthorityLeaseChild::class.java.name, root.toString())
                .redirectOutput(root.resolve("child-output.txt").toFile())
                .redirectError(root.resolve("child-error.txt").toFile())
            listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach { builder.environment().remove(it) }
            val process = builder.start()
            child = process
            withTimeout(5_000) {
                while (runCatching { Files.readString(ready) == "held" }.getOrDefault(false).not()) {
                    check(process.isAlive); delay(10)
                }
            }
            assertEquals("held", Files.readString(ready))
            val blocked = DesktopDownloadQueueAuthority.tryAcquire(state)
            assertNull(blocked.lease); assertTrue(blocked.busy)
            Files.writeString(release, "release", CREATE_NEW)
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            acquire(state).use { assertTrue(it.holds(state)) }
        } finally {
            child?.let { if (it.isAlive) { it.destroyForcibly(); check(it.waitFor(5, TimeUnit.SECONDS)) } }
            cleanup(root)
        }
    }
}

/** A sealed private JVM fixture: only a FileChannel on its own fresh Temp lock file. */
object DesktopDownloadQueueAuthorityLeaseChild {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val root = Path.of(args.single()).toRealPath()
        require(root.isAbsolute && root.fileName.toString().startsWith("bp-queue-authority-"))
        FileChannel.open(root.resolve("state.json.bilipai-lock"), CREATE, WRITE).use { channel ->
            checkNotNull(channel.tryLock()).use {
                Files.writeString(root.resolve("child-ready"), "held", CREATE_NEW)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!Files.exists(root.resolve("child-release"))) {
                    check(System.nanoTime() < deadline) { "Private lock fixture release timed out" }
                    Thread.sleep(10)
                }
            }
        }
    }
}
