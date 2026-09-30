package com.bilipai.desktop.diagnostics

import com.android.purebilibili.core.util.DesktopDiagnosticCollector
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import java.net.URLClassLoader
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Actual serial consumer and task-owned disk. No account repository, player, network or user settings. */
@ResourceLock("DesktopDiagnosticsBridge")
class DesktopDiagnosticsTest {
    @TempDir lateinit var temporary: Path

    private class Fixture(val root: Path) : AutoCloseable {
        val store = DesktopPluginStore(root)
        val clock = AtomicLong(10_000)
        val writer = Executors.newSingleThreadExecutor { Thread(it, "task-owned-diagnostics-writer").apply { isDaemon = true } }
        val logs = DesktopDiagnostics(store, "diagnostic-offline-test", { clock.getAndAdd(500) }, writer)
        private val releases = mutableListOf<CountDownLatch>()
        fun blockWriter(): CountDownLatch {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            releases.add(release)
            writer.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            return release
        }
        override fun close() {
            releases.forEach { it.countDown() }
            logs.close()
            assertTrue(writer.isTerminated)
        }
    }
    private fun fixture(): Fixture = Fixture(Files.createTempDirectory(temporary, "store-"))
    private fun assertSafeError(f: Fixture) {
        val error = assertNotNull(f.logs.error.value)
        assertTrue(error.startsWith("诊断文件操作失败"))
        assertFalse(error.contains(f.root.toString()))
        assertFalse(error.contains("task-owned-private"))
    }

    @Test fun falseConsentKeepsOnlyBasicWarningsErrorsAndFixedStartup(): Unit = runBlocking {
        fixture().use { f ->
            assertFalse(f.logs.enhancedEnabled.value)
            f.logs.record("D", "Fixture", "not_opted_in_detail")
            f.logs.record("W", "Fixture", "basic_warning")
            f.logs.record("E", "Fixture", "basic_error")
            f.logs.flush()
            assertFalse(Files.exists(f.root.resolve("logs/runtime.log")))
            val rows = f.logs.entries()
            assertTrue(rows.any { it.message == "basic_warning" })
            assertTrue(rows.any { it.message == "basic_error" })
            assertFalse(rows.any { it.message.contains("not_opted_in_detail") })
            assertContains(Files.readString(f.root.resolve("logs/basic.log")), "stage=diagnostics_initialized")
            assertFalse(Files.exists(f.root.resolve("accounts")))
        }
    }

    @Test fun consentPersistsAtomicallyInTheSingleOriginalGlobalSettingsKey(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.setEnhancedEnabled(true)
            val document = Json.parseToJsonElement(Files.readString(f.root.resolve("plugin-settings.json"))).jsonObject
            assertTrue(document["settings"]!!.jsonObject["enhanced_diagnostic_logging_enabled"]!!.jsonPrimitive.boolean)
            assertFalse(document.containsKey("diagnostic_logging"))
            assertTrue(f.logs.enhancedEnabled.value)
            assertTrue(DesktopDiagnosticSettings(DesktopPluginStore(f.root)).getEnhancedDiagnosticLoggingEnabledSync())
            assertFalse(Files.exists(f.root.resolve("accounts")))
        }
    }

    @Test fun realPlaybackStateObserverRecordsFiniteStateWithoutMediaOrAccountText(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.setEnhancedEnabled(true)
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val state = MutableStateFlow(PlayerState(ready = true, sourceTitle = "fixture_title_secret",
                subtitleText = "fixture_subtitle_secret", error = "fixture_error_secret", videoCodec = "https://fixture.invalid/private"))
            val observer = f.logs.observePlayback(state, observerScope)
            try {
                withTimeout(3_000) { while (f.logs.entries().none { it.tag == "PlaybackDiagnostics" }) delay(5) }
                state.value = state.value.copy(hardwareDecodeEnabled = false, hardwareDecoder = "no", audioCodec = "aac",
                    failure = PlayerFailure(PlayerFailureKind.AUDIO_OUTPUT, -14, "fixture_failure_secret", sourceVersion = 8, attemptId = 2))
                withTimeout(3_000) { while (f.logs.entries().none { it.message.contains("kind=AUDIO_OUTPUT") }) delay(5) }
                val evidence = f.logs.entries().filter { it.tag == "PlaybackDiagnostics" }.joinToString("\n") { it.message }
                assertContains(evidence, "hwIntent=false"); assertContains(evidence, "nativeCode=-14")
                listOf("fixture_title_secret", "fixture_subtitle_secret", "fixture_error_secret", "fixture_failure_secret", "fixture.invalid").forEach {
                    assertFalse(evidence.contains(it), "Private media text reached finite state diagnostics")
                }
            } finally { observer.cancelAndJoin(); observerScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun bothActualUpstreamBridgesRedactBeforeMemoryDiskAndExport(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.setEnhancedEnabled(true)
            DesktopDiagnosticsBridge.install(f.logs)
            try {
                android.util.Log.e("Fixture", "SESSDATA=secret_cookie mid=123456 keyword=secret_search BV1AB411c7mD",
                    IllegalStateException("access_token=secret_token"))
                com.android.purebilibili.core.util.Logger.d("Fixture",
                    "https://fixture.invalid/private?auth_key=secret_signed C:\\Users\\fixture-user\\private\\media")
                f.logs.flush()
                val evidence = f.logs.entries().joinToString("\n") { it.format() } + f.logs.viewLocal() +
                    Files.readString(f.root.resolve("logs/basic.log")) + Files.readString(f.root.resolve("logs/runtime.log"))
                listOf("secret_cookie", "123456", "secret_search", "BV1AB411c7mD", "secret_token", "secret_signed", "fixture-user", "fixture.invalid/private").forEach {
                    assertFalse(evidence.contains(it), "Synthetic private value survived redaction")
                }
                assertContains(evidence, "SESSDATA=***"); assertContains(evidence, "[network-address]"); assertContains(evidence, "[user-home]")
            } finally { DesktopDiagnosticsBridge.retire(f.logs) }
        }
    }

    @Test fun disablingConsentRemovesDetailedFilesAndCannotBeUndoneByQueuedDetails(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.setEnhancedEnabled(true)
            val release = f.blockWriter()
            try {
                f.logs.record("D", "Fixture", "queued_old_detail")
                val off = async(start = CoroutineStart.UNDISPATCHED) { f.logs.setEnhancedEnabled(false) }
                f.logs.record("D", "Fixture", "queued_after_off_detail")
                f.logs.record("E", "Fixture", "basic_after_off")
                release.countDown(); off.await(); f.logs.flush()
                assertFalse(f.logs.enhancedEnabled.value); assertFalse(Files.exists(f.root.resolve("logs/runtime.log")))
                val entries = f.logs.entries()
                assertFalse(entries.any { it.message.contains("queued_") || it.level == "D" })
                assertTrue(entries.any { it.message == "basic_after_off" })
            } finally { release.countDown() }
        }
    }

    @Test fun realRingAndRollingFilesKeepOriginalBoundsWithoutSplittingUtf8(): Unit = runBlocking {
        fixture().use { f ->
            repeat(1_500) { f.logs.record("W", "Bounded", "故障线索-$it " + "汉".repeat(180)) }
            f.logs.record("W", "Bounded", "异常".repeat(20_000))
            f.logs.flush()
            val basic = Files.readString(f.root.resolve("logs/basic.log"))
            assertTrue(Files.size(f.root.resolve("logs/basic.log")) <= 64 * 1024)
            assertContains(basic, "[truncated]"); assertFalse(basic.contains('\uFFFD'))
            val ring = f.logs.entries()
            assertEquals(1_000, ring.size); assertContains(ring.first().message, "故障线索-501")
            f.logs.setEnhancedEnabled(true)
            repeat(500) { f.logs.record("I", "Detailed", "详细-$it " + "字".repeat(250)) }
            f.logs.flush()
            assertTrue(Files.size(f.root.resolve("logs/runtime.log")) <= 256 * 1024)
            assertFalse(Files.readString(f.root.resolve("logs/runtime.log")).contains('\uFFFD'))
        }
    }

    @Test fun originalCollectorSuppressesBurstDuplicatesButAcceptsLaterEvents() {
        val now = AtomicLong(500)
        val persisted = AtomicInteger()
        val collector = DesktopDiagnosticCollector(now::get) { _, _ -> persisted.incrementAndGet() }
        repeat(20) { collector.add("W", "Duplicate", "same") }
        assertEquals(1, collector.getCount()); assertEquals(1, persisted.get())
        now.set(751); collector.add("W", "Duplicate", "same")
        assertEquals(2, collector.getCount()); assertEquals(2, persisted.get())
    }

    @Test fun explicitExportIsBoundedAndCannotOverwriteAnExistingOrActiveFile(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.setEnhancedEnabled(true)
            f.logs.record("I", "Enabled", "user_requested_local_export")
            f.logs.flush()
            val chosen = temporary.resolve("explicit-export.txt")
            f.logs.exportTo(chosen)
            val exported = Files.readAllBytes(chosen)
            assertTrue(exported.size <= 512 * 1024); assertContains(String(exported, Charsets.UTF_8), "user_requested_local_export")
            assertFalse(String(exported, Charsets.UTF_8).contains('\uFFFD'))
            assertFails { f.logs.exportTo(chosen) }
            assertContentEquals(exported, Files.readAllBytes(chosen))
            val basic = Files.readAllBytes(f.root.resolve("logs/basic.log"))
            assertFails { f.logs.exportTo(f.root.resolve("logs/basic.log")) }
            assertContentEquals(basic, Files.readAllBytes(f.root.resolve("logs/basic.log")))
        }
    }

    @Test fun historicalOversizedFileIsBoundedAndSanitizedAgainOnActualRead(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.flush()
            Files.writeString(f.root.resolve("logs/runtime.log"), "旧".repeat(200_000) + "\naccess_token=fixture_export_secret\n")
            val historical = f.logs.viewLocal()
            assertTrue(historical.toByteArray().size <= 512 * 1024)
            assertFalse(historical.contains("fixture_export_secret")); assertFalse(historical.contains('\uFFFD'))
        }
    }

    @Test fun taskOwnedUncaughtHandlerKeepsLocalCrashEvidenceWithoutEnhancedConsent(): Unit = runBlocking {
        fixture().use { f ->
            assertFalse(f.logs.enhancedEnabled.value)
            val chained = AtomicInteger()
            val thread = Thread({ throw IllegalArgumentException("Authorization: Bearer fixture_crash_secret") }, "task-owned-diagnostic-crash")
            thread.uncaughtExceptionHandler = DesktopDiagnosticUncaughtHandler(f.logs) { _, _ -> chained.incrementAndGet() }
            thread.start(); thread.join(5_000)
            assertFalse(thread.isAlive); assertEquals(1, chained.get()); assertTrue(f.logs.hasPendingCrash())
            val snapshot = f.root.resolve("logs/last_crash_log.txt")
            assertFalse(Files.readString(snapshot).contains("fixture_crash_secret"))
            f.logs.clearCrashPrompt()
            assertFalse(f.logs.hasPendingCrash()); assertTrue(Files.exists(snapshot))
        }
    }

    @Test fun serializedClearDeletesOnlyKnownArtifactsAndTheActualRing(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.setEnhancedEnabled(true)
            f.logs.persistLocalCrash(IllegalStateException("synthetic local crash"))
            val unrelated = f.root.resolve("logs/keep-user-file.txt")
            Files.writeString(unrelated, "untouched")
            f.logs.clearAll()
            assertTrue(f.logs.entries().isEmpty()); assertEquals(0L, f.logs.artifactSize())
            assertEquals("untouched", Files.readString(unrelated))
            listOf("basic.log", "runtime.log", "last_crash_log.txt", "pending_crash.marker").forEach {
                assertFalse(Files.exists(f.root.resolve("logs/$it")))
            }
        }
    }

    @Test fun restoreShutdownDrainsAcceptedWritesAndRejectsTheRetiredFacade(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.record("W", "Shutdown", "accepted_before_shutdown")
            f.logs.setEnhancedEnabled(true)
            f.logs.shutdownForRestore()
            assertFalse(f.logs.record("E", "Shutdown", "rejected_after_shutdown"))
            assertContains(Files.readString(f.root.resolve("logs/basic.log")), "accepted_before_shutdown")
            assertFails { f.logs.clearAll() }; assertFails { f.logs.setEnhancedEnabled(false) }
            f.store.freezeWrites()
            assertTrue(DesktopDiagnosticSettings(f.store).getEnhancedDiagnosticLoggingEnabledSync())
        }
    }

    @Test fun realConsentPersistenceFailureKeepsFalseStateAndCanRetry(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.flush()
            val settingsFile = f.root.resolve("plugin-settings.json")
            Files.createDirectory(settingsFile)
            assertFails { f.logs.setEnhancedEnabled(true) }
            assertFalse(f.logs.enhancedEnabled.value); assertFalse(Files.exists(f.root.resolve("logs/runtime.log")))
            assertFalse(DesktopDiagnosticSettings(f.store).getEnhancedDiagnosticLoggingEnabledSync())
            assertSafeError(f)
            Files.delete(settingsFile)
            f.logs.setEnhancedEnabled(true)
            f.logs.record("I", "Retry", "write_after_failed_consent"); f.logs.flush()
            assertContains(Files.readString(f.root.resolve("logs/runtime.log")), "write_after_failed_consent")
        }
    }

    @Test fun closingWaitsForBlockedAcceptedDiskJobsAndRejectsConcurrentLateRecords(): Unit = runBlocking {
        fixture().use { f ->
            f.logs.flush()
            val release = f.blockWriter()
            try {
                f.logs.record("W", "Shutdown", "real_pending_write")
                val closing = async(Dispatchers.IO) { f.logs.shutdownForRestore() }
                withTimeout(3_000) { while (!f.writer.isShutdown) delay(1) }
                assertFalse(closing.isCompleted)
                assertFalse(f.logs.record("W", "Shutdown", "late_after_close"))
                release.countDown(); withTimeout(5_000) { closing.await() }
                val drained = Files.readString(f.root.resolve("logs/basic.log"))
                assertContains(drained, "real_pending_write"); assertFalse(drained.contains("late_after_close"))
                assertTrue(f.writer.isTerminated)
            } finally { release.countDown() }
        }
    }

    private fun verifyJunctionIsRejected(ancestor: Boolean): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", true), "Actual Windows junction fixture")
        val owner = Files.createTempDirectory(temporary, "junction-")
        val victim = Files.createDirectory(owner.resolve("task-owned-victim"))
        val link = owner.resolve(if (ancestor) "redirected-parent" else "store/logs")
        val root = if (ancestor) link.resolve("store") else link.parent
        val logsPath = if (ancestor) victim.resolve("store/logs") else victim
        Files.createDirectories(link.parent); Files.createDirectories(logsPath)
        assertTrue(link.startsWith(owner) && victim.startsWith(owner))
        val commandLog = owner.resolve("mklink-result.txt")
        val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), victim.toString())
            .redirectErrorStream(true).redirectOutput(commandLog.toFile()).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), "Cannot create task-owned junction")
            val attributes = Files.readAttributes(link, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            assertTrue(attributes.isOther); assertFalse(attributes.isSymbolicLink)
            val target = logsPath.resolve("basic.log")
            Files.writeString(target, "task-owned-private-victim-marker\n")
            val bytes = Files.readAllBytes(target)
            val store = DesktopPluginStore(root)
            val diagnostics = DesktopDiagnostics(store, "offline-boundary-test")
            try {
                diagnostics.flush()
                assertFails { diagnostics.viewLocal() }
                val export = owner.resolve("explicit-export.txt")
                assertFails { diagnostics.exportTo(export) }; assertFails { diagnostics.clearAll() }
                assertFalse(Files.exists(export)); assertContentEquals(bytes, Files.readAllBytes(target))
                val error = assertNotNull(diagnostics.error.value)
                assertContains(error, "诊断文件操作失败"); assertFalse(error.contains(owner.toString()))
                assertFalse(error.contains("task-owned-private"))
            } finally { diagnostics.close() }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            // Remove this exact task-owned junction before JUnit traverses the temporary directory.
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link)
        }
    }
    @Test fun actualLogsDirectoryJunctionCannotBeReadExportedOrCleared(): Unit = verifyJunctionIsRejected(false)
    @Test fun actualAncestorJunctionCannotBeReadExportedOrCleared(): Unit = verifyJunctionIsRejected(true)

    interface ReadLockKernel32 : StdCallLibrary {
        fun CreateFileW(name: WString, access: Int, share: Int, security: Pointer?, creation: Int, flags: Int, template: Pointer?): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
    }
    @Test fun actualWindowsSharingDenialIsReportedSafelyAndDoesNotProduceAnExport(): Unit = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", true), "Actual Windows exclusive file handle")
        fixture().use { f ->
            f.logs.record("W", "ReadFailure", "task-owned-private-log"); f.logs.flush()
            val basic = f.root.resolve("logs/basic.log")
            val original = Files.readAllBytes(basic)
            val kernel = Native.load("kernel32", ReadLockKernel32::class.java)
            val handle = kernel.CreateFileW(WString(basic.toString()), 0x80000000.toInt(), 0, null, 3, 0x80, null)
            assertNotNull(handle); assertNotEquals(-1L, Pointer.nativeValue(handle), "Cannot obtain exclusive task-owned handle")
            try {
                assertFailsWith<java.io.IOException> { Files.readAllBytes(basic) } // Real OS denial before product reader.
                assertFails { f.logs.viewLocal() }
                val target = temporary.resolve("sharing-denied-export.txt")
                assertFails { f.logs.exportTo(target) }
                assertFalse(Files.exists(target)); assertSafeError(f)
            } finally { assertTrue(kernel.CloseHandle(handle)) }
            assertContentEquals(original, Files.readAllBytes(basic))
            assertContains(f.logs.viewLocal(), "task-owned-private-log")
        }
    }

    @Test fun aColdJvmReadsPersistedConsentFromTheSameGlobalKeyBeforeFirstDetail(): Unit = runBlocking {
        val root = Files.createTempDirectory(temporary, "cold-store-")
        val store = DesktopPluginStore(root)
        val first = DesktopDiagnostics(store, "cold-writer-test")
        try { first.setEnhancedEnabled(true) } finally { first.close() }
        val environment = Files.createDirectory(temporary.resolve("cold-environment"))
        val argumentFile = temporary.resolve("cold-jvm.args")
        val classpath = linkedSetOf<String>()
        System.getProperty("java.class.path").split(java.io.File.pathSeparator).filter { it.isNotBlank() }.forEach(classpath::add)
        var loader: ClassLoader? = DesktopDiagnosticsTest::class.java.classLoader
        while (loader != null) {
            (loader as? URLClassLoader)?.getURLs()?.filter { it.protocol == "file" }?.forEach { classpath.add(Path.of(it.toURI()).toString()) }
            loader = loader.parent
        }
        listOf(DesktopDiagnosticsTest::class.java, DesktopDiagnostics::class.java, kotlin.Unit::class.java).forEach {
            classpath.add(Path.of(it.protectionDomain.codeSource.location.toURI()).toString())
        }
        val args = listOf("-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-Duser.home=$environment", "-Djava.io.tmpdir=$environment",
            "-cp", classpath.joinToString(java.io.File.pathSeparator), DesktopDiagnosticColdJvmProbe::class.java.name, root.toString())
        Files.writeString(argumentFile, args.joinToString("\n") { "\"" + it.replace("\\", "/").replace("\"", "\\\"") + "\"" })
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java")
        val builder = ProcessBuilder(java.toString(), "@$argumentFile").redirectErrorStream(true)
            .redirectOutput(temporary.resolve("cold-jvm-output.txt").toFile())
        listOf("APPDATA", "LOCALAPPDATA", "USERPROFILE", "TEMP", "TMP").forEach { builder.environment()[it] = environment.toString() }
        val process = builder.start()
        try { assertTrue(process.waitFor(25, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), "Actual cold JVM diagnostic probe failed") }
        finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        assertContains(Files.readString(root.resolve("logs/runtime.log")), "fresh_jvm_detail")
        assertFalse(Files.exists(root.resolve("accounts")))
    }
}

/** Child-process probe only; no additional JUnit count or process-wide exception handler is installed. */
object DesktopDiagnosticColdJvmProbe {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val store = DesktopPluginStore(Path.of(args.single()))
        val logs = DesktopDiagnostics(store, "cold-reader-test")
        try {
            check(logs.enhancedEnabled.value)
            logs.record("I", "ColdJvm", "fresh_jvm_detail"); logs.flush()
            check(Files.readString(store.root.resolve("logs/runtime.log")).contains("fresh_jvm_detail"))
            check(!Files.exists(store.root.resolve("accounts")))
        } finally { logs.close() }
    }
}
