package com.bilipai.desktop.ui

import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual Root binding/factory -> original request repository -> scoped document cache. No media, window or real account. */
class DesktopOriginalSubtitleCacheOwnershipTest {
    @Test fun actualRepositoryUsesItsCapturedFactoryAndRestoresOriginalWordTimesFromDisk() = runBlocking<Unit> {
        Harness(this).use { h ->
            val owner = h.capture()
            val first = owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans").getOrThrow()
            assertEquals(listOf(100L, 500L), first.single().words.map { it.startMs })
            assertEquals(0, h.assets.activeDocumentOwners())
            assertEquals(0, h.assets.cachedCueDocumentCount())
            assertEquals(first, owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans").getOrThrow())
            assertEquals(1, h.requests.size)
            assertEquals(0, h.defaultClientCalls.get())
            assertEquals(0, h.assets.activeDocumentOwners())
        }
    }

    @Test fun cachedDocumentCannotBypassARetiredPlaybackEntry() = runBlocking<Unit> {
        Harness(this).use { h ->
            val owner = h.capture()
            owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans").getOrThrow()
            h.entry.set(false)
            assertFailsWith<CancellationException> {
                owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans")
            }
            assertEquals(1, h.requests.size)
            assertEquals(0, h.assets.activeDocumentOwners())
        }
    }

    @Test fun cachedDocumentCannotBypassReplacementCredentialsForTheSameAccount() = runBlocking<Unit> {
        Harness(this).use { h ->
            val owner = h.capture()
            owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans").getOrThrow()
            h.sessions.saveAccount(mapOf("SESSDATA" to "replacement-fixture-session", "bili_jct" to "replacement-fixture-csrf"),
                AccountSummary(41, "Fixture", ""))
            assertFailsWith<CancellationException> {
                owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans")
            }
            assertEquals(1, h.requests.size)
            assertEquals(0, h.assets.activeDocumentOwners())
        }
    }

    @Test fun cancellingTheActualCapturedRequestReleasesItsDocumentOwnerWithoutFiles() = runBlocking<Unit> {
        Harness(this, holdResponse = true).use { h ->
            val job = async {
                val owner = h.capture() // Capture in this actual invocation Job, as Root does.
                owner.getSubtitleCues(URL, "BV-local", 7L, 1L, "1", "zh-Hans")
            }
            withTimeout(3_000L) { h.responseEntered.await() }
            assertEquals(1, h.assets.activeDocumentOwners())
            withTimeout(3_000L) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(0, h.assets.activeDocumentOwners())
            h.releaseResponse.countDown()
            assertEquals(0L to 0L, h.assets.cacheSizes())
            val directory = DesktopSubtitleAssets::class.java.getDeclaredField("directory")
                .apply { isAccessible = true }.get(h.assets) as Path
            assertEquals(0L, Files.list(directory).use { it.count() })
        }
    }

    private class Harness(scope: CoroutineScope, holdResponse: Boolean = false) : AutoCloseable {
        val folder = Files.createTempDirectory("bilipai-root-subtitle-fixture-")
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val entry = AtomicBoolean(true)
        private val gate = Any()
        private val entryJob = SupervisorJob(scope.coroutineContext[Job])
        val defaultClientCalls = AtomicInteger()
        val requests = CopyOnWriteArrayList<Request>()
        val responseEntered = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(if (holdResponse) 1 else 0)
        private val transport: OkHttpClient
        private val unusedDefault = OkHttpClient.Builder().addInterceptor {
            defaultClientCalls.incrementAndGet(); throw IOException("The default subtitle client must not replace the captured request factory")
        }.build()
        val assets = DesktopSubtitleAssets(unusedDefault, 2, 1, 1_048_576)
        private val privacy = DesktopSearchPreferences(folder.resolve("search"))
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "fixture-session", "bili_jct" to "fixture-csrf"),
                AccountSummary(41, "Fixture", ""))
            transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.method == "GET" && request.url.toString() == URL)
                    requests.add(request); responseEntered.complete(Unit)
                    while (!releaseResponse.await(10L, TimeUnit.MILLISECONDS)) {
                        if (chain.call().isCanceled()) throw IOException("Fixture call canceled")
                    }
                    if (chain.call().isCanceled()) throw IOException("Fixture call canceled")
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(BODY.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        private fun current() = entry.get() && entryJob.isActive
        private fun commit(action: () -> Unit): Boolean = synchronized(gate) {
            if (!current()) false else { action(); true }
        }
        suspend fun capture(): DesktopOriginalVideoOwnerRequestRepository {
            val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, repository.sessionEpoch, entryJob,
                ::current, ::commit, PlayerPreferences(), null, emptySet(), false,
                { false }, { false }, { false }, { false }, { _, _ -> false })
            return createDesktopOriginalVideoOwnerRequestRepository(repository, binding, assets, privacy) { _, _ -> true }
        }
        override fun close() {
            entry.set(false); entryJob.cancel(); releaseResponse.countDown(); assets.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            unusedDefault.dispatcher.executorService.shutdownNow(); unusedDefault.connectionPool.evictAll()
        }
    }

    private companion object {
        const val URL = "https://fixture.hdslb.com/subtitle/actual-root.json"
        const val BODY = """{"body":[{"from":0,"to":2,"content":"hello","words":[{"word":"he","start":0.1,"end":0.4},{"word":"llo","start":0.5,"end":1.3}]}]}"""
    }
}
