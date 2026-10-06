package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real temporary SessionStore, captured Binding and original playurl protocol.
 * Every HTTP response is exact-origin in-memory; no profile, native player or GUI. */
class DesktopOriginalVideoAuthorizationBootstrapTest {
    private class Fixture(buvid: String? = null) : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val entry = SupervisorJob()
        var owned = true
        val requests = CopyOnWriteArrayList<Request>()
        val notices = CopyOnWriteArrayList<DesktopPlaybackAuthorizationReceipt>()
        var spi: () -> String = { """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}""" }
        val transport: OkHttpClient
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
                AccountSummary(41, "Fixture", ""))
            // Login credentials deliberately exclude visitor cookies. These two
            // fixtures model an ALREADY established SPI visitor via the real Store;
            // first-login and whole-VM fixtures still start without any buvid3.
            buvid?.let {
                sessions.saveSpiCookies(mapOf("buvid3" to it), sessions.generation)
                assertEquals(it, repository.ownedHomeCookie("buvid3", sessions.generation) { true })
            }
            transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    check(request.method == "GET" && request.url.isHttps && request.url.port == 443)
                    val body = when (request.url.host to request.url.encodedPath) {
                        "www.bilibili.com" to "/" -> "fixture bootstrap"
                        "api.bilibili.com" to "/x/frontend/finger/spi" -> spi()
                        "api.bilibili.com" to "/x/web-interface/view" -> """{"code":0,"data":{"bvid":"BV1GJ411x7h7","aid":7,"cid":22,"pages":[{"cid":22,"page":1,"part":"Fixture"}]}}"""
                        else -> throw java.io.IOException("No external fixture transport")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        suspend fun capture(notify: ((DesktopPlaybackAuthorizationReceipt) -> Unit)? = { notices += it }): DesktopOriginalVideoRepositoryBinding = DesktopOriginalVideoRepositoryBinding.capture(
            repository, repository.sessionEpoch, entry, { owned }, { action -> if (!owned) false else { action(); true } },
            PlayerPreferences(), null, emptySet(), false, { true }, { false }, { false }, { false },
            { _, _ -> false }, notify)
        override fun close() { owned = false; entry.cancel(); transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
    }
    private fun edt(action: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    @Test fun firstLoggedInBootstrapCancelsOldProtocolAndFreshCaptureUsesRealVisitor() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { f ->
            var old: DesktopPlaybackAuthorizationReceipt? = null
            val result = async {
                val binding = f.capture(); old = binding.receipt
                runCatching { binding.protocol.getInitialPlayUrlData("BV1GJ411x7h7", 22, 64, null) }
            }.await()
            assertIs<CancellationException>(result.exceptionOrNull())
            assertEquals(listOf("/", "/x/frontend/finger/spi"), f.requests.map { it.url.encodedPath })
            assertEquals(listOf(old), f.notices.toList())
            assertTrue(f.repository.ownedHomeVisitorInitialized(f.repository.sessionEpoch) { true })
            assertFalse(f.repository.isPlaybackReceiptCurrent(assertNotNull(old)))
            async {
                val fresh = f.capture()
                assertTrue(fresh.receipt.revision > old!!.revision)
                fresh.environment.ensureBuvid(); fresh.assertCurrent()
                assertTrue(fresh.hasPrimaryBuvid())
                assertEquals("synthetic-visitor", f.sessions.currentCookies()["buvid3"])
            }.await()
            assertEquals(2, f.requests.size); assertEquals(1, f.notices.size)
        } }
    }

    @Test fun sameVisitorDoesNotRetireOrRepeatBootstrap() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture("synthetic-visitor").use { f ->
            repeat(2) { async {
                val binding = f.capture(); val receipt = binding.receipt
                binding.environment.ensureBuvid(); binding.environment.ensureBuvid()
                binding.assertCurrent(); assertTrue(f.repository.isPlaybackReceiptCurrent(receipt))
            }.await() }
            assertTrue(f.notices.isEmpty()); assertEquals(2, f.requests.size)
        } }
    }

    @Test fun queueMetadataLookupOnlyReadsViewWithoutInitializingOrRetiringPlayback() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture().use { f ->
            async {
                val binding = f.capture(notify = null)
                val info = binding.rawRepository.getVideoInfoOnly("BV1GJ411x7h7", 0, 0).getOrThrow()
                assertEquals(22L, info.cid); binding.assertCurrent()
            }.await()
            assertEquals(listOf("/x/web-interface/view"), f.requests.map { it.url.encodedPath })
            assertTrue(f.notices.isEmpty())
            assertFalse(f.repository.ownedHomeVisitorInitialized(f.repository.sessionEpoch) { true })
        } }
    }

    @Test fun actualVipWriteSharesSingleBindingNotificationAndNewCaptureDoesNotLoop() = runBlocking<Unit> {
        withTimeout(5_000) { Fixture("synthetic-visitor").use { f ->
            async {
                val binding = f.capture()
                binding.environment.ensureBuvid()
                binding.updatePrimaryVip(true); binding.reportPlaybackAuthorizationRetired()
                binding.reportPlaybackAuthorizationRetired()
                assertFailsWith<CancellationException> { binding.assertCurrent() }
            }.await()
            assertEquals(1, f.notices.size)
            async {
                val next = f.capture(); next.updatePrimaryVip(true); next.reportPlaybackAuthorizationRetired()
                next.environment.ensureBuvid(); next.assertCurrent()
            }.await()
            assertEquals(1, f.notices.size); assertEquals(true, f.repository.account.value?.isVip)
        } }
    }

    @Test fun failedBootstrapAndAccountReplacementDoNotRequestRecapture() = runBlocking<Unit> {
        withTimeout(5_000) {
            Fixture().use { f ->
                f.spi = { """{"code":-403,"message":"synthetic denied"}""" }
                assertTrue(async { runCatching { f.capture().environment.ensureBuvid() } }.await().isFailure)
                assertTrue(f.notices.isEmpty())
                assertFalse(f.repository.ownedHomeVisitorInitialized(f.repository.sessionEpoch) { true })
            }
            Fixture().use { f ->
                f.spi = {
                    f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-successor"), AccountSummary(42, "Next", ""))
                    """{"code":0,"data":{"b_3":"old-visitor"}}"""
                }
                assertTrue(async { runCatching { f.capture().environment.ensureBuvid() } }.await().isFailure)
                assertTrue(f.notices.isEmpty()); assertEquals(42L, f.repository.account.value?.mid)
                assertFalse(f.sessions.currentCookies().values.contains("old-visitor"))
            }
        }
    }

    @Test fun queuedNotificationSurvivesOwnLoadCompletionButRejectsSameValueRequestAba() = runBlocking<Unit> {
        val session = PlaybackSessionStore()
        val request = PlaybackRequest.create("BV1GJ411x7h7", 7, 22)
        session.beginLoadRequest(request)
        var owned = true; var retries = 0
        val notice = DesktopOriginalVideoAuthorizationRetirement(Any(), DesktopPlaybackAuthorizationReceipt(1, 1),
            session.state.value, { owned }, { session.state.value }, { retries++ })
        val completedLoad = async { Unit }; completedLoad.await(); assertTrue(completedLoad.isCompleted)
        edt { assertTrue(notice.recaptureIfCurrent()); assertFalse(notice.recaptureIfCurrent()) }
        assertEquals(1, retries)
        val stale = DesktopOriginalVideoAuthorizationRetirement(Any(), notice.receipt,
            session.state.value, { owned }, { session.state.value }, { retries++ })
        // Same numeric token and equal payload still cannot replace request identity.
        session.setCurrentRequest(request.copy(force = true)); session.setCurrentRequest(request.copy())
        edt { assertFalse(stale.recaptureIfCurrent()) }
        assertEquals(1, retries)
        val retired = DesktopOriginalVideoAuthorizationRetirement(Any(), notice.receipt,
            session.state.value, { owned }, { session.state.value }, { retries++ })
        owned = false
        edt { assertFalse(retired.recaptureIfCurrent()) }; assertEquals(1, retries)
    }
}
