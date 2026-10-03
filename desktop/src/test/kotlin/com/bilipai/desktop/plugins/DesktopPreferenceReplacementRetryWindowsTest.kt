package com.bilipai.desktop.plugins

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real Windows no-share-delete handle, real Store file/CAS/Flow. No Root/UI claim. */
class DesktopPreferenceReplacementRetryWindowsTest {
    interface ReadLockKernel32 : StdCallLibrary {
        fun CreateFileW(name: WString, access: Int, share: Int, security: Pointer?, creation: Int, flags: Int, template: Pointer?): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
    }
    private class HeldFile(private val kernel: ReadLockKernel32, private val handle: Pointer) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) check(kernel.CloseHandle(handle)) }
    }
    private fun hold(path: Path): HeldFile {
        check(System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")) { "Opt-in Windows native file test" }
        val kernel = Native.load("kernel32", ReadLockKernel32::class.java)
        // Read and write sharing allowed. DELETE sharing deliberately denied on our private file.
        val handle = kernel.CreateFileW(WString(path.toString()), 0x80000000.toInt(), 3, null, 3, 0x80, null)
        check(handle != null && Pointer.nativeValue(handle) != -1L) { "Private Windows file handle did not open" }
        return HeldFile(kernel, handle)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < end) { if (condition()) return; Thread.sleep(2) }
        error("Windows replacement fixture timed out: $label")
    }
    private fun store(): DesktopPluginStore = DesktopPluginStore(Files.createTempDirectory("bp-original-windows-replace-")).also {
        it.update("settings", mapOf("base" to JsonPrimitive(1), "foreign" to JsonPrimitive("keep")))
        it.update("mini_player", mapOf("foreign" to JsonPrimitive("mirror")))
    }
    private fun path(store: DesktopPluginStore) = store.root.resolve("plugin-settings.json")
    private val baseKey = DesktopPreferenceKey("base") { (it as? JsonPrimitive)?.intOrNull }
    private fun write(store: DesktopPluginStore, permits: AtomicInteger, owns: AtomicBoolean = AtomicBoolean(true),
        atPermit: (Int) -> Unit = {}) {
        fun checkRequest() { if (!owns.get()) throw CancellationException("Private source owner retired") }
        store.updateOriginalNamespacesFromSnapshot("settings", ::checkRequest, {
            checkRequest(); val number = permits.incrementAndGet(); atPermit(number)
            checkRequest(); DesktopPluginStore.OriginalPreferenceWritePermit(store)
        }) { snapshot ->
            val projected = checkNotNull(snapshot[baseKey]) + 1
            Unit to mapOf("settings" to mapOf("projected" to JsonPrimitive(projected)),
                "mini_player" to mapOf("projected" to JsonPrimitive(projected)))
        }
    }
    private fun failure(future: Future<*>): Throwable = try {
        future.get(5, TimeUnit.SECONDS); error("Expected actual denied/cancelled replacement")
    } catch (exception: java.util.concurrent.ExecutionException) { requireNotNull(exception.cause) }
    private fun noTemps(store: DesktopPluginStore) = Files.list(store.root).use { paths ->
        assertFalse(paths.anyMatch { it.fileName.toString().startsWith("plugin-settings-") && it.fileName.toString().endsWith(".tmp") })
    }
    @Test fun actualDeniedHandleReleasedDuringBackoffPublishesBothNamespaces(): Unit {
        val store = store(); val before = Files.readAllBytes(path(store)); val flow = store.snapshot("settings")
        val priorFlow = flow.value; val permits = AtomicInteger(); val executor = Executors.newSingleThreadExecutor()
        hold(path(store)).use { handle ->
            try {
                val future = executor.submit { write(store, permits) }
                await("at least one actual denied rename and fresh retry permit") { permits.get() >= 2 }
                assertArrayEquals(before, Files.readAllBytes(path(store))); assertSame(priorFlow, flow.value)
                handle.close(); future.get(5, TimeUnit.SECONDS)
                assertTrue(permits.get() >= 2)
                assertEquals(2, store.preferences("settings")["projected"]!!.jsonPrimitive.int)
                assertEquals(2, store.preferences("mini_player")["projected"]!!.jsonPrimitive.int)
                val disk = Json.parseToJsonElement(Files.readString(path(store))).jsonObject
                assertEquals(2, disk["settings"]!!.jsonObject["projected"]!!.jsonPrimitive.int)
                assertEquals(2, disk["mini_player"]!!.jsonObject["projected"]!!.jsonPrimitive.int)
                assertNotSame(priorFlow, flow.value); noTemps(store)
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
    @Test fun permanentRealWindowsDenialIsBoundedAndKeepsDiskAndFlowByteExact(): Unit {
        val store = store(); val before = Files.readAllBytes(path(store)); val flow = store.snapshot("settings"); val priorFlow = flow.value
        val permits = AtomicInteger(); val executor = Executors.newSingleThreadExecutor()
        hold(path(store)).use {
            try {
                val error = failure(executor.submit { write(store, permits) })
                assertInstanceOf(AccessDeniedException::class.java, error)
                assertEquals(DesktopPreferenceReplacementRetry.maximumRetries + 1, permits.get())
                assertArrayEquals(before, Files.readAllBytes(path(store))); assertSame(priorFlow, flow.value)
                assertNull(store.preferences("settings")["projected"]); assertNull(store.preferences("mini_player")["projected"])
                noTemps(store)
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
    @Test fun retirementAfterActualFailedMoveCannotReuseConsumedPermit(): Unit {
        val store = store(); val before = Files.readAllBytes(path(store)); val flow = store.snapshot("settings"); val priorFlow = flow.value
        val permits = AtomicInteger(); val owns = AtomicBoolean(true); val executor = Executors.newSingleThreadExecutor()
        hold(path(store)).use {
            try {
                val future = executor.submit { write(store, permits, owns, atPermit = { number ->
                    if (number == 2) owns.set(false) // the first actual move failed and backoff completed
                }) }
                assertInstanceOf(CancellationException::class.java, failure(future))
                assertEquals(2, permits.get()); assertArrayEquals(before, Files.readAllBytes(path(store))); assertSame(priorFlow, flow.value)
                noTemps(store)
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
    @Test fun retryReevaluatesOriginalEditAndMirrorAfterConcurrentWholeDocumentConflict(): Unit {
        val store = store(); val permits = AtomicInteger(); val executor = Executors.newSingleThreadExecutor()
        hold(path(store)).use { handle ->
            try {
                val future = executor.submit { write(store, permits, atPermit = { number -> if (number == 2) {
                    handle.close()
                    // This admission hook runs outside the Store monitor. Its accepted
                    // publication forces the pending old snapshot to lose the real CAS.
                    store.update("settings", mapOf("base" to JsonPrimitive(4), "concurrent" to JsonPrimitive("new")))
                } }) }
                future.get(5, TimeUnit.SECONDS)
                assertTrue(permits.get() >= 3)
                assertEquals(5, store.preferences("settings")["projected"]!!.jsonPrimitive.int)
                assertEquals(5, store.preferences("mini_player")["projected"]!!.jsonPrimitive.int)
                assertEquals("new", store.preferences("settings")["concurrent"]!!.jsonPrimitive.content)
                assertEquals("keep", store.preferences("settings")["foreign"]!!.jsonPrimitive.content)
                assertEquals("mirror", store.preferences("mini_player")["foreign"]!!.jsonPrimitive.content)
                noTemps(store)
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
    @Test fun exhaustedRetryThrowsExactDeniedFailureBeforeNewOwnerCheck(): Unit {
        val denied = AccessDeniedException("private-temp", "private-target", null); var checks = 0
        val thrown = assertThrows(AccessDeniedException::class.java) {
            DesktopPreferenceReplacementRetry.waitOutsideAdmissionOrThrow(denied, DesktopPreferenceReplacementRetry.maximumRetries + 1) { checks++ }
        }
        assertSame(denied, thrown); assertEquals(0, checks)
    }
    @Test fun accessDeniedWithNonJdkReasonIsNotRetried(): Unit {
        val denied = AccessDeniedException("private-temp", "private-target", "arbitrary denial reason"); var checks = 0
        val thrown = assertThrows(AccessDeniedException::class.java) {
            DesktopPreferenceReplacementRetry.waitOutsideAdmissionOrThrow(denied, 1) { checks++ }
        }
        assertSame(denied, thrown); assertEquals(0, checks)
    }
    @Test fun capturedPlaybackRechecksCancelledActualCallerBeforeSecondPermit(): Unit {
        val store = store(); val before = Files.readAllBytes(path(store)); val flow = store.snapshot("settings"); val priorFlow = flow.value
        val permits = AtomicInteger(); val caller = Job(); val origin = Job(); val executor = Executors.newSingleThreadExecutor()
        val operation = DesktopPlayerPluginWriteAdmission.Operation(DesktopPluginContext(store), origin, null,
            checkCaptured = {}, admission = { action ->
                if (permits.incrementAndGet() == 2) caller.cancel()
                action(); true
            }, serializedDeferred = { _, block -> block() })
        hold(path(store)).use {
            try {
                val future = executor.submit { store.updateCapturedPlayback("settings", mapOf("captured" to JsonPrimitive(true)), operation, caller) }
                assertInstanceOf(CancellationException::class.java, failure(future))
                assertEquals(2, permits.get()); assertArrayEquals(before, Files.readAllBytes(path(store))); assertSame(priorFlow, flow.value)
                assertNull(store.preferences("settings")["captured"]); noTemps(store)
            } finally { caller.cancel(); origin.cancel(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        }
    }
}
