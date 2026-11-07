package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

/** Real Store -> coroutine cancellation -> Retrofit monitor, and the inverse
 * Retrofit -> Binding.current -> Repository -> Store path from TON-1322's dump.
 * The child JVM bounds even the old, non-interruptible monitor deadlock. */
class DesktopPlaybackAuthorizationLockOrderTest {
    @Test fun actualRetrofitEnqueueAndOwnerCancellationDoNotDeadlock() {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.getURLs().asSequence() }
            .map { File(it.toURI()).path }.toList() + System.getProperty("java.class.path").split(File.pathSeparator)
        val output = Files.createTempFile("playback-lock-order-", ".log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-Djava.awt.headless=true",
            "-Dbilipai.credentialProtection=${System.getProperty("bilipai.credentialProtection", "")}",
            "-Dbilipai.upstreamHttpCache=${System.getProperty("bilipai.upstreamHttpCache")}",
            "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}", "-cp",
            classpath.distinct().joinToString(File.pathSeparator), DesktopPlaybackAuthorizationLockOrderProbe::class.java.name)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Lock-order child exceeded its recovery deadline")
            val evidence = Files.readString(output)
            println(evidence)
            assertEquals(0, process.exitValue(), evidence)
            assertTrue(evidence.contains("enqueue/cancel completed; both threads reclaimed"), evidence)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Lock-order child was not reclaimed")
            Files.deleteIfExists(output)
        }
    }

    @Test fun primaryAccountAbaRejectsAnAlreadyAcceptedPreflight() = runBlocking<Unit> {
        Fixture().use { f ->
            val binding = f.capture()
            assertTrue(f.repository.isPlaybackReceiptCurrent(binding.receipt))
            f.sessions.activateAccount(42)
            f.sessions.activateAccount(41)
            var published = 0
            assertFalse(binding.admitCurrentMutation { published++ })
            assertFalse(f.repository.isPlaybackReceiptCurrent(binding.receipt))
            assertEquals(0, published)
            assertEquals(41L, f.sessions.activeAccountMid())
        }
    }

    @Test fun playbackAccountAbaAndClearRetireReceiptsWithoutChangingPrimaryEpoch() = runBlocking<Unit> {
        Fixture().use { f ->
            val epoch = f.sessions.generation
            f.sessions.setPlaybackAccountMid(41, epoch) { true }
            val first = f.capture()
            assertTrue(f.repository.isPlaybackReceiptCurrent(first.receipt))
            f.sessions.setPlaybackAccountMid(42, epoch) { true }
            f.sessions.setPlaybackAccountMid(41, epoch) { true }
            var published = 0
            assertFalse(first.admitCurrentMutation { published++ })
            val beforeClear = f.capture()
            assertTrue(f.repository.isPlaybackReceiptCurrent(beforeClear.receipt))
            f.sessions.setPlaybackAccountMid(null, epoch) { true }
            assertFalse(beforeClear.admitCurrentMutation { published++ })
            assertEquals(epoch, f.sessions.generation)
            assertEquals(0, published)
            f.capture().assertCurrent()
        }
    }

    @Test fun logoutRejectsReceiptEvenWhenSelectedPlaybackAccountSurvives() = runBlocking<Unit> {
        Fixture().use { f ->
            f.sessions.setPlaybackAccountMid(42, f.sessions.generation) { true }
            val binding = f.capture()
            val revision = binding.receipt.revision
            f.sessions.logout()
            assertEquals(revision, f.sessions.playbackAuthorizationRevision.value)
            assertFalse(f.repository.isPlaybackReceiptCurrent(binding.receipt))
            assertFalse(binding.admitCurrentMutation { fail("Logout published old playback result") })
        }
    }

    @Test fun lateActualRetrofitResponseCannotPublishAfterAccountAba() = runBlocking<Unit> {
        assertLateResponseRejected { f, _ ->
            f.sessions.activateAccount(42)
            f.sessions.activateAccount(41)
        }
    }

    @Test fun lateActualRetrofitResponseCannotPublishAfterEntryClear() = runBlocking<Unit> {
        assertLateResponseRejected { f, binding ->
            f.entry.cancel()
            // Clearing the video entry must reject publication even if the
            // account snapshot itself has not changed.
            assertTrue(f.repository.isPlaybackReceiptCurrent(binding.receipt))
        }
    }

    @Test fun lateCookiesCannotReadOrMutateAfterPlaybackAccountAba() = runBlocking<Unit> {
        Fixture().use { f ->
            val epoch = f.sessions.generation
            assertTrue(f.sessions.setPlaybackAccountMid(42, epoch) { true })
            val old = f.sessions.capturePlaybackAuthorization(epoch) { true }
            val url = "https://api.bilibili.com/x/web-interface/view".toHttpUrl()
            val oldJar = assertNotNull(old.cookieJar)
            val before = oldJar.loadForRequest(url)
            assertTrue(f.sessions.setPlaybackAccountMid(41, epoch) { true })
            assertTrue(f.sessions.setPlaybackAccountMid(42, epoch) { true })
            assertEquals(epoch, f.sessions.generation)
            f.sessions.requestGeneration.set(epoch)
            f.sessions.requestAdmission.set { true }
            f.sessions.requestPlaybackAuthorization.set(old)
            try {
                f.sessions.saveFromResponse(url, listOf(Cookie.Builder().domain("bilibili.com")
                    .name("SESSDATA").value("late-synthetic-credential").secure().build()))
                assertFailsWith<java.io.IOException> { f.sessions.loadForRequest(url) }
            } finally {
                f.sessions.requestPlaybackAuthorization.remove()
                f.sessions.requestAdmission.remove()
                f.sessions.requestGeneration.remove()
            }
            assertEquals(before, oldJar.loadForRequest(url))
            assertFailsWith<CancellationException> { f.sessions.playbackRequestCookies(old, url) { true } }
            val current = f.sessions.capturePlaybackAuthorization(epoch) { true }
            assertEquals("synthetic-b", f.sessions.playbackRequestCookies(current, url) { true }["SESSDATA"])
            assertEquals("synthetic-a", f.sessions.currentCookies()["SESSDATA"])
        }
    }

    private suspend fun assertLateResponseRejected(retire: (Fixture, DesktopOriginalVideoRepositoryBinding) -> Unit) {
        withTimeout(8_000) { Fixture().use { f ->
            val responseReady = CompletableDeferred<() -> Unit>()
            val captured = CompletableDeferred<DesktopOriginalVideoRepositoryBinding>()
            var published = 0
            val request = async {
                val binding = f.capture { delegate -> Call.Factory { request ->
                    val raw = delegate.newCall(request)
                    object : Call by raw {
                        override fun enqueue(callback: Callback) {
                            responseReady.complete {
                                callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                                    .code(200).message("in-memory late tags")
                                    .body("""{"code":0,"data":[]}""".toResponseBody("application/json".toMediaType())).build())
                            }
                        }
                    }
                } }
                captured.complete(binding)
                binding.primaryApi.getVideoTags("BV1GJ411x7h7")
                binding.admitCurrentMutation { published++ }
            }
            val releaseResponse = responseReady.await()
            assertTrue(request.isActive)
            retire(f, captured.await())
            releaseResponse()
            assertFalse(request.await())
            assertEquals(0, published)
        } }
    }

    /** A mutation wins after the lock-free preflight and before final admission. */
    @Test fun revisionMutationBetweenPreflightAndPublicationIsRejected() = runBlocking<Unit> {
        Fixture().use { f ->
            val binding = f.capture()
            val checked = CountDownLatch(1)
            val changed = CountDownLatch(1)
            val result = AtomicReference<Throwable?>()
            val reader = thread(name = "preflight-before-revision", isDaemon = true) {
                try {
                    check(f.repository.isPlaybackReceiptCurrent(binding.receipt))
                    checked.countDown()
                    check(changed.await(5, TimeUnit.SECONDS))
                    check(!binding.admitCurrentMutation { error("Stale publication") })
                } catch (failure: Throwable) { result.set(failure) }
            }
            try {
                assertTrue(checked.await(5, TimeUnit.SECONDS))
                f.sessions.saveProfileVipStatus(true)
            } finally { changed.countDown(); reader.join(6_000) }
            assertFalse(reader.isAlive)
            result.get()?.let { throw it }
        }
    }

    private class Fixture : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val entry = SupervisorJob()
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-b"), AccountSummary(42, "B", ""))
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-a"), AccountSummary(41, "A", ""))
        }
        suspend fun capture(decorate: ((Call.Factory) -> Call.Factory)? = null) =
            captureBinding(repository, entry, decorate)
        override fun close() {
            entry.cancel()
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
        }
    }
}

private suspend fun captureBinding(repository: DesktopRepository, entry: Job,
    decorate: ((Call.Factory) -> Call.Factory)? = null,
    ownerCheck: () -> Boolean = { true }) = DesktopOriginalVideoRepositoryBinding.capture(
    repository, repository.sessionEpoch, entry, ownerCheck, { action -> action(); true },
    PlayerPreferences(), null, emptySet(), false, { false }, { false }, { false }, { false },
    { _, _ -> false }, decorateCalls = decorate)

/** No GUI or HTTP is started. The only substitute is the external HTTP result;
 * enqueue, suspend cancellation, owner checks and both monitors are production code. */
object DesktopPlaybackAuthorizationLockOrderProbe {
    @JvmStatic fun main(args: Array<String>) {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val entry = SupervisorJob()
        val factoryEntered = CountDownLatch(1)
        val admissionHeld = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val job = AtomicReference<Job>()
        val binding = AtomicReference<DesktopOriginalVideoRepositoryBinding>()
        val error = AtomicReference<Throwable?>()
        val inFactory = AtomicBoolean(false)
        val enqueue = thread(name = "retrofit-enqueue-owner", isDaemon = true) {
            try {
                runBlocking {
                    job.set(currentCoroutineContext().job)
                    val view = captureBinding(repository, entry, decorate = { delegate -> Call.Factory { request ->
                        inFactory.set(true)
                        // Retrofit holds OkHttpCall's monitor here. This invokes
                        // ownedHomeCallFactory -> Binding.current -> Store.
                        val raw = delegate.newCall(request)
                        object : Call by raw {
                            override fun enqueue(callback: Callback) {
                                callback.onFailure(this, java.io.IOException("No external HTTP in lock-order probe"))
                            }
                        }
                    } }, ownerCheck = {
                        if (inFactory.get()) {
                            // Inside Binding.current, AFTER its job-active read:
                            // cancellation cannot short-circuit the Store check.
                            factoryEntered.countDown()
                            check(admissionHeld.await(5, TimeUnit.SECONDS))
                        }
                        true
                    })
                    binding.set(view)
                    view.primaryApi.getVideoTags("BV1GJ411x7h7")
                }
            } catch (_: CancellationException) {
                // Real request-job retirement is the expected result.
            } catch (_: java.io.IOException) {
                // The in-memory transport may finish before cancel acquires the monitor.
            } catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }
        val cancel = thread(name = "store-admission-cancel", isDaemon = true) {
            try {
                check(factoryEntered.await(5, TimeUnit.SECONDS))
                repository.withPlaybackReceiptAdmission(binding.get().receipt, { true }) {
                    admissionHeld.countDown()
                    // This cancellation synchronously calls Retrofit OkHttpCall.cancel.
                    job.get().cancel()
                }
            } catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }
        check(finished.await(8, TimeUnit.SECONDS)) {
            val bean = ManagementFactory.getThreadMXBean()
            val deadlocked = bean.findDeadlockedThreads() ?: longArrayOf()
            "Actual monitor deadlock: ${deadlocked.toList()}\n" +
                bean.getThreadInfo(longArrayOf(enqueue.id, cancel.id), true, true).joinToString("\n")
        }
        enqueue.join(1_000); cancel.join(1_000)
        check(!enqueue.isAlive && !cancel.isAlive)
        error.get()?.let { throw it }
        entry.cancel()
        repository.httpClient.dispatcher.executorService.shutdownNow()
        repository.httpClient.connectionPool.evictAll()
        println("enqueue/cancel completed; both threads reclaimed")
    }
}
