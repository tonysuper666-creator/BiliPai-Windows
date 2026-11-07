package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.data.*
import com.bilipai.desktop.download.DownloadDanmakuTransport
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.test.*

/** Scheduling wrappers only: authority is the real Store/NativeOwner, the related
 * decorator and DownloadDanmakuTransport, and cancellation is Retrofit's own. */
class DesktopPlaybackTransportLockOrderTest {
    @Test fun relatedCancellationDoesNotInvertNativeAdmission() = probe("related")
    @Test fun playurlPreloadCancellationDoesNotInvertNativeAdmission() = probe("preload")
    @Test fun downloadCancellationDoesNotInvertStoreAdmission() = probe("download")

    @Test fun deferredInspectionAndCancellationDoNotCreateOrAdmitTransport() {
        val client = OkHttpClient()
        val created = mutableListOf<Call>()
        val calls = deferredPlaybackCalls(Call.Factory { request ->
            val raw = client.newCall(request).also { created += it }
            object : Call by raw {
                override fun enqueue(responseCallback: Callback) = responseCallback.onFailure(this, IOException("No HTTP"))
            }
        })
        val request = Request.Builder().url("https://fixture.invalid").tag(String::class.java, "request tag").build()
        val call = calls.newCall(request)
        assertSame(request, call.request())
        assertEquals("request tag", call.tag(String::class))
        assertEquals(17, call.tag(Int::class.javaObjectType) { 17 })
        call.timeout().timeout(7, TimeUnit.SECONDS)
        assertFalse(call.isExecuted()); assertFalse(call.isCanceled())
        val clone = call.clone()
        call.cancel()
        assertTrue(call.isCanceled()); assertFalse(clone.isCanceled())
        assertTrue(created.isEmpty(), "Inspection/cancel must not enter an owner factory")
        call.enqueue(failureOnly)
        assertTrue(created.single().isCanceled())
        assertEquals(17, created.single().tag(Int::class.javaObjectType))
        assertTrue(call.isExecuted())
        assertFailsWith<IllegalStateException> { call.enqueue(failureOnly) }
        clone.enqueue(failureOnly)
        assertFalse(created.last().isCanceled())
        assertNull(clone.tag(Int::class.javaObjectType), "Computed Call tags are not clone request tags")
    }

    @Test fun cancellationDuringDeferredFactoryCreationIsNotLostOrBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val raw = OkHttpClient().newCall(Request.Builder().url("https://fixture.invalid").build())
        val error = AtomicReference<Throwable?>()
        val call = deferredPlaybackCalls(Call.Factory {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            object : Call by raw {
                override fun enqueue(responseCallback: Callback) = responseCallback.onFailure(this, IOException("No HTTP"))
            }
        }).newCall(raw.request())
        val sender = thread(isDaemon = true) {
            try { call.enqueue(failureOnly) } catch (failure: Throwable) { error.set(failure) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            call.cancel() // Must return while the real owner/factory is still blocked.
            assertTrue(call.isCanceled())
            assertFalse(raw.isCanceled())
        } finally { release.countDown(); sender.join(6_000) }
        assertFalse(sender.isAlive)
        error.get()?.let { throw it }
        assertTrue(raw.isCanceled(), "Cancellation must cross the eventual raw Call handoff")
    }

    @Test fun timeoutReferenceKeepsClientDefaultsAndForwardsChangesAcrossHandoff() {
        val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        val created = mutableListOf<Call>()
        val calls = deferredPlaybackCalls(Call.Factory { request ->
            val raw = client.newCall(request).also { created += it }
            object : Call by raw {
                override fun execute(): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("memory").body("".toResponseBody()).build()
            }
        })
        val request = Request.Builder().url("https://fixture.invalid").build()
        val defaults = calls.newCall(request)
        defaults.execute().close()
        assertEquals(TimeUnit.SECONDS.toNanos(3), created.last().timeout().timeoutNanos())
        val changed = calls.newCall(request)
        val timeout = changed.timeout()
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
        timeout.timeout(7, TimeUnit.SECONDS).deadlineNanoTime(deadline)
        changed.execute().close()
        assertSame(timeout, changed.timeout())
        assertEquals(TimeUnit.SECONDS.toNanos(7), created.last().timeout().timeoutNanos())
        assertEquals(deadline, created.last().timeout().deadlineNanoTime())
        timeout.clearDeadline().clearTimeout()
        assertFalse(created.last().timeout().hasDeadline())
        assertEquals(0, created.last().timeout().timeoutNanos())
    }

    @Test fun relatedDeferredQueueRejectsPrimaryAccountAba() = rejectedRelatedQueue {
        it.sessions.activateAccount(42); it.sessions.activateAccount(41)
    }
    @Test fun relatedDeferredQueueRejectsPlaybackAccountAba() = rejectedRelatedQueue {
        it.sessions.setPlaybackAccountMid(42, it.repository.sessionEpoch) { true }
        it.sessions.setPlaybackAccountMid(41, it.repository.sessionEpoch) { true }
    }
    @Test fun relatedDeferredQueueRejectsPlaybackAccountClear() = rejectedRelatedQueue {
        it.sessions.setPlaybackAccountMid(null, it.repository.sessionEpoch) { true }
    }
    @Test fun relatedDeferredQueueRejectsLogout() = rejectedRelatedQueue { it.sessions.logout() }
    @Test fun relatedDeferredQueueRejectsEntryRetirement() = rejectedRelatedQueue { it.entryCurrent = false }

    @Test fun relatedCreationRechecksNativeOwnerBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ -> f.owner.close() }
    @Test fun relatedCreationRechecksPrimaryAccountAbaBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ ->
        f.sessions.activateAccount(42); f.sessions.activateAccount(41)
    }
    @Test fun relatedCreationRechecksPlaybackAccountAbaBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ ->
        f.sessions.setPlaybackAccountMid(42, f.repository.sessionEpoch) { true }
        f.sessions.setPlaybackAccountMid(41, f.repository.sessionEpoch) { true }
    }
    @Test fun relatedCreationRechecksPlaybackAccountClearBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ ->
        f.sessions.setPlaybackAccountMid(null, f.repository.sessionEpoch) { true }
    }
    @Test fun relatedCreationRechecksLogoutBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ -> f.sessions.logout() }
    @Test fun relatedCreationRechecksEntryBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ -> f.entryCurrent = false }
    @Test fun relatedCreationRechecksCallerCancellationBeforeQueue() = rejectedDuringRelatedCreation { f, _, _ -> f.caller.cancel() }
    @Test fun relatedCreationRechecksExplicitCancellationBeforeQueue() = rejectedDuringRelatedCreation { _, call, _ -> call.cancel() }
    @Test fun relatedCreationRechecksRawCancellationBeforeQueue() = rejectedDuringRelatedCreation { _, _, raw -> raw.cancel() }

    private fun rejectedDuringRelatedCreation(retire: (TransportAuthority, Call, Call) -> Unit) {
        // Both APIs share the production queue gate. Real newCall can invoke
        // reentrant application hooks even while Store/native admission is held.
        for (execute in listOf(false, true)) TransportAuthority().use { f ->
            val started = AtomicInteger()
            val created = AtomicInteger()
            val rawCall = AtomicReference<Call>()
            val client = f.repository.httpClient.newBuilder()
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { started.incrementAndGet() }
                })
                .addInterceptor { throw IOException("No external HTTP in creation regression") }.build()
            lateinit var call: Call
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(f.repository, client)
            val realFactory = f.repository.ownedPlaybackCallFactory(f.authorization) { true }
            val related = DesktopOriginalVideoRelatedRequestSource(f.accepted) { true }
            val calls = related.calls(Call.Factory { request ->
                realFactory.newCall(request).also { raw ->
                    rawCall.set(raw); created.incrementAndGet()
                    retire(f, call, raw) // Strictly after creation, before actual enqueue.
                }
            }, f.owner, f.caller)
            try {
                call = calls.newCall(Request.Builder().url("https://api.bilibili.com/x/web-interface/archive/related").build())
                assertEquals(0, created.get())
                val result = runCatching { if (execute) call.execute().close() else call.enqueue(failureOnly) }
                assertEquals(1, created.get())
                assertEquals(0, started.get(), "Retired/cancelled call was enqueued (execute=$execute)")
                assertTrue(result.exceptionOrNull() is CancellationException || result.exceptionOrNull() is IOException,
                    "Retirement must be observable to the caller: $result")
                assertTrue(call.isCanceled()); assertTrue(rawCall.get().isCanceled())
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    private fun rejectedRelatedQueue(retire: (TransportAuthority) -> Unit) {
        TransportAuthority().use { f ->
            val related = DesktopOriginalVideoRelatedRequestSource(f.accepted) { true }
            val creations = AtomicInteger()
            val original = f.repository.ownedPlaybackCallFactory(f.authorization) { true }
            val calls = related.calls(Call.Factory { request ->
                creations.incrementAndGet(); original.newCall(request)
            }, f.owner, f.caller)
            assertTrue(f.owner.isCurrent(f.accepted)) // Real preflight succeeds.
            val call = calls.newCall(Request.Builder().url("https://api.bilibili.com/x/web-interface/archive/related").build())
            assertEquals(0, creations.get())
            retire(f) // Wins strictly after creation/preflight and before queue admission.
            assertFailsWith<CancellationException> { call.enqueue(failureOnly) }
            assertTrue(call.isCanceled())
            assertEquals(0, creations.get(), "Stale related request reached the real Repository transport")
        }
    }

    @Test fun downloadDeferredPolicyRejectsAccountAbaBeforeTransportQueue(): Unit = runBlocking {
        rejectedDownloadQueue { it.sessions.activateAccount(42); it.sessions.activateAccount(41) }
    }
    @Test fun downloadDeferredPolicyRejectsEntryRetirementBeforeTransportQueue(): Unit = runBlocking {
        rejectedDownloadQueue { it.entryCurrent = false }
    }

    @Test fun downloadCreationRechecksAccountAbaBeforeQueue(): Unit = runBlocking {
        rejectedDuringDownloadCreation { it.sessions.activateAccount(42); it.sessions.activateAccount(41) }
    }
    @Test fun downloadCreationRechecksEntryBeforeQueue(): Unit = runBlocking {
        rejectedDuringDownloadCreation { it.entryCurrent = false }
    }

    private suspend fun rejectedDuringDownloadCreation(retire: (TransportAuthority) -> Unit) {
        TransportAuthority().use { f ->
            val created = AtomicInteger()
            val started = AtomicInteger()
            val transport = f.repository.httpClient.newBuilder().eventListenerFactory {
                created.incrementAndGet(); retire(f) // Real OkHttp factory callback, as in the Gate A failure.
                object : EventListener() {
                    override fun callStart(call: Call) { started.incrementAndGet() }
                }
            }.addInterceptor { throw IOException("No external HTTP in download regression") }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(f.repository, transport)
            try {
                DownloadDanmakuTransport.withTask(f.accepted.nativeSource.source, f.publication, f.caller,
                    { f.entryCurrent }, { action -> if (f.entryCurrent) { action(); true } else false }) {
                    DownloadDanmakuTransport.withApi(f.repository) {
                        val api = DownloadDanmakuTransport.currentBinding().api()
                        val result = runCatching { api.getDanmakuXml(17).close() }
                        assertEquals(1, created.get())
                        assertEquals(0, started.get(), "Download enqueued after its factory retired the owner")
                        assertTrue(result.exceptionOrNull() is CancellationException || result.exceptionOrNull() is IOException)
                    }
                }
            } finally { transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
        }
    }

    private suspend fun rejectedDownloadQueue(retire: (TransportAuthority) -> Unit) {
        TransportAuthority().use { f ->
            val dispatched = AtomicInteger()
            val transport = f.repository.httpClient.newBuilder().addInterceptor {
                dispatched.incrementAndGet(); throw IOException("No HTTP")
            }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(f.repository, transport)
            try {
                DownloadDanmakuTransport.withTask(f.accepted.nativeSource.source, f.publication, f.caller,
                    { f.entryCurrent }, { action -> if (f.entryCurrent) { action(); true } else false }) {
                    DownloadDanmakuTransport.withApi(f.repository) {
                        val api = DownloadDanmakuTransport.currentBinding().api()
                        val retrofit = productionRetrofit(api)
                        val captured = retrofit.callFactory()
                        val boundaryReached = AtomicInteger()
                        val gated = retrofit.newBuilder().callFactory { request ->
                            captured.newCall(request).also {
                                boundaryReached.incrementAndGet(); retire(f)
                            }
                        }.build().create(BilibiliApi::class.java)
                        assertFailsWith<IOException> { gated.getDanmakuXml(17).close() }
                        assertEquals(1, boundaryReached.get())
                        assertEquals(0, dispatched.get(), "Retired download reached the HTTP interceptor")
                    }
                }
            } finally { transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
        }
    }

    private val failureOnly = object : Callback {
        override fun onFailure(call: Call, e: IOException) = Unit
        override fun onResponse(call: Call, response: Response) { response.close(); error("Unexpected HTTP") }
    }

    private fun probe(mode: String) {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.getURLs().asSequence() }
            .map { File(it.toURI()).path }.toList() + System.getProperty("java.class.path").split(File.pathSeparator)
        val output = Files.createTempFile("playback-$mode-lock-order-", ".log")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-Djava.awt.headless=true",
            "-Dbilipai.credentialProtection=${System.getProperty("bilipai.credentialProtection", "")}",
            "-Dbilipai.upstreamHttpCache=${System.getProperty("bilipai.upstreamHttpCache")}",
            "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}", "-cp",
            classpath.distinct().joinToString(File.pathSeparator), DesktopPlaybackTransportLockOrderProbe::class.java.name, mode)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "$mode child exceeded recovery deadline")
            val evidence = Files.readString(output)
            println(evidence)
            assertEquals(0, process.exitValue(), evidence)
            assertTrue(evidence.contains("$mode: both production threads reclaimed"), evidence)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "$mode child not reclaimed")
            Files.deleteIfExists(output)
        }
    }
}

object DesktopPlaybackTransportLockOrderProbe {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args.single()
        val sessions = DesktopSessionStore.temporary()
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-a"), AccountSummary(41, "A", ""))
        val repository = DesktopRepository(sessions)
        val authorization = sessions.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        val publication = DesktopRepositoryPlaybackPublication(repository)
        val player = MpvPlayer()
        val initialJob = Job()
        val owner = DesktopOriginalVideoNativeOwner(player, publication, { repository.sessionEpoch },
            { false }, { true }, { block -> block(); true }, {}, { _, _ -> error("No byte transport") })
        player.loadVersioned(PlaybackSource("file:///C:/transport-probe-bootstrap.avi"))
        val actor = PremiumRecoveryActor(player)
        val accepted = owner.publish(PlaybackRequest.create("BVfixture", 17, 70),
            PlaybackSource("https://fixture.invalid/video", "https://fixture.invalid/audio", startPaused = true,
                authorizationReceipt = authorization.receipt), player.currentSourceVersion, initialJob) { true }
        actor.drain(); actor.loaded(); check(owner.isCurrent(accepted))
        // Even an unexpected regression cannot send HTTP. Native actor is the
        // retained memory-only ABI fixture; no GUI, native thread or DLL starts.
        repository.httpClient.dispatcher.executorService.shutdownNow()
        val factoryEntered = CountDownLatch(1)
        val admissionHeld = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val job = AtomicReference<Job>()
        val error = AtomicReference<Throwable?>()
        fun schedule(delegate: Call.Factory) = Call.Factory { request ->
            // Retrofit holds its real OkHttpCall monitor at this boundary.
            factoryEntered.countDown()
            check(admissionHeld.await(5, TimeUnit.SECONDS))
            delegate.newCall(request)
        }
        val sender = thread(name = "$mode-retrofit-create", isDaemon = true) {
            try {
                runBlocking {
                    job.set(currentCoroutineContext().job)
                    if (mode == "download") {
                        DownloadDanmakuTransport.withTask(accepted.nativeSource.source, publication, job.get(),
                            { true }, { block -> block(); true }) {
                            DownloadDanmakuTransport.withApi(repository) {
                                val original = DownloadDanmakuTransport.currentBinding().api()
                                // Preserve the actual private download factory/policy.
                                // Rebuild only Retrofit's outer scheduling boundary.
                                val retrofit = productionRetrofit(original)
                                retrofit.newBuilder().callFactory(schedule(retrofit.callFactory())).build()
                                    .create(BilibiliApi::class.java).getDanmakuXml(17).close()
                            }
                        }
                    } else {
                        val related = DesktopOriginalVideoRelatedRequestSource(accepted) { true }
                        val api = repository.ownedPlaybackService(BilibiliApi::class.java, authorization, { true }) { raw ->
                            val noNetwork = Call.Factory { request ->
                                val call = raw.newCall(request)
                                object : Call by call {
                                    override fun enqueue(callback: Callback) =
                                        callback.onFailure(this, IOException("No external HTTP in probe"))
                                }
                            }
                            schedule(related.calls(noNetwork, owner, job.get()))
                        }
                        when (mode) {
                            "related" -> api.getRelatedVideos("BVfixture")
                            "preload" -> api.getPlayUrl(mapOf("bvid" to "BVfixture", "cid" to "17"))
                            else -> error("Unknown mode $mode")
                        }
                    }
                }
            } catch (_: CancellationException) {
                // The actual request is cancelled by the admitted native callback.
            } catch (_: IOException) {
                // The synthetic external result can win after enqueue is admitted.
            } catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }
        val canceller = thread(name = "$mode-native-admission-cancel", isDaemon = true) {
            try {
                check(factoryEntered.await(5, TimeUnit.SECONDS))
                check(owner.admitPlaybackDispatch(accepted) {
                    admissionHeld.countDown()
                    job.get().cancel()
                })
            } catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }
        if (!finished.await(8, TimeUnit.SECONDS)) {
            val bean = ManagementFactory.getThreadMXBean()
            println("$mode: deadlocked thread ids=${bean.findDeadlockedThreads()?.toList()}")
            bean.getThreadInfo(longArrayOf(sender.id, canceller.id), true, true).forEach { info ->
                println("${info.threadName}: ${info.threadState} waiting=${info.lockInfo} owner=${info.lockOwnerName}")
                info.stackTrace.forEach { println("  at $it") }
                println("  owned monitors=${info.lockedMonitors.toList()}")
            }
            // Exiting this disposable JVM reclaims non-interruptible monitors.
            exitProcess(2)
        }
        sender.join(1_000); canceller.join(1_000)
        check(!sender.isAlive && !canceller.isAlive)
        error.get()?.let { throw it }
        actor.close(); owner.close(); initialJob.cancel(); player.close()
        repository.httpClient.connectionPool.evictAll()
        println("$mode: both production threads reclaimed")
    }
}

private fun productionRetrofit(api: BilibiliApi): Retrofit {
    val handler = Proxy.getInvocationHandler(api)
    return handler.javaClass.declaredFields.single { it.type == Retrofit::class.java }
        .apply { isAccessible = true }.get(handler) as Retrofit
}

/** Real Store/native/entry admission, with the existing memory-only MPV ABI actor. */
private class TransportAuthority : AutoCloseable {
    val sessions = DesktopSessionStore.temporary()
    val repository = DesktopRepository(sessions)
    val publication = DesktopRepositoryPlaybackPublication(repository)
    val caller = Job()
    var entryCurrent = true
    val player = MpvPlayer()
    val owner = DesktopOriginalVideoNativeOwner(player, publication, { repository.sessionEpoch },
        { false }, { entryCurrent }, { block -> if (entryCurrent) { block(); true } else false },
        {}, { _, _ -> error("No byte transport") })
    val actor: PremiumRecoveryActor
    val authorization: DesktopPlaybackAuthorization
    val accepted: DesktopOriginalVideoAcceptedPublication
    init {
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-b"), AccountSummary(42, "B", ""))
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-a"), AccountSummary(41, "A", ""))
        sessions.setPlaybackAccountMid(41, repository.sessionEpoch) { true }
        authorization = sessions.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        player.loadVersioned(PlaybackSource("file:///C:/transport-authority.avi"))
        actor = PremiumRecoveryActor(player)
        accepted = owner.publish(PlaybackRequest.create("BVfixture", 17, 70),
            PlaybackSource("https://fixture.invalid/video", "https://fixture.invalid/audio", startPaused = true,
                authorizationReceipt = authorization.receipt), player.currentSourceVersion, caller) { true }
        actor.drain(); actor.loaded(); check(owner.isCurrent(accepted))
    }
    override fun close() {
        actor.close(); owner.close(); caller.cancel(); player.close()
        repository.httpClient.dispatcher.executorService.shutdownNow()
        repository.httpClient.connectionPool.evictAll()
    }
}
