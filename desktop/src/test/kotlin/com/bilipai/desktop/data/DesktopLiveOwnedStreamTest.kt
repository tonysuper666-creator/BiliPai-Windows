package com.bilipai.desktop.data

import com.bilipai.desktop.ui.desktopLiveAdmission
import com.bilipai.desktop.ui.desktopLiveRecoveryPorts
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Actual repository signer, existing primary Session and generated API; every HTTP reply is intercepted locally. */
class DesktopLiveOwnedStreamTest {
    private val room = LiveRoomDetails(123, "Fixture", "", "Synthetic", "", 1, "生活", 1)
    private class Fixture {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val media = DesktopMediaRepository(repository)
        val requests = CopyOnWriteArrayList<Request>()
        var reply: (Request) -> String = { request ->
            when (request.url.encodedPath) {
                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                "/xlive/web-room/v2/index/getRoomPlayInfo" -> """{"code":0,"data":{"current_quality":150,"quality_description":[{"qn":150,"desc":"高清"}],"durl":[{"url":"https://fixture.invalid/live.flv"}]}}"""
                else -> error("No external transport allowed: ${request.url.encodedPath}")
            }
        }
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-live-account", "bili_jct" to "synthetic-csrf"), AccountSummary(123, "Fixture", ""))
            val transport = repository.httpClient.newBuilder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    check(request.method == "GET")
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("offline fixture")
                        .body(reply(request).toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            DesktopRepository::class.java.getDeclaredField("visitorInitialized").apply { isAccessible = true }.set(repository, true)
            DesktopRepository::class.java.getDeclaredField("visitorGeneration").apply { isAccessible = true }.set(repository, repository.sessionEpoch)
        }
    }

    @Test fun actualOwnedSignerAndStreamUseSameCompleteParameters(): Unit = runBlocking {
        withTimeout(5_000) {
            val f = Fixture(); val epoch = f.repository.sessionEpoch
            val info = f.media.livePlaybackInfo(room, 10000, expectedEpoch = epoch, stillOwned = { true })
            assertEquals(listOf("/x/web-interface/nav", "/xlive/web-room/v2/index/getRoomPlayInfo"), f.requests.map { it.url.encodedPath })
            val url = f.requests.last().url
            assertEquals("10000", url.queryParameter("qn"))
            assertEquals(setOf("room_id", "protocol", "format", "codec", "qn", "platform", "ptype", "dolby", "panorama", "web_location", "wts", "w_rid"), url.queryParameterNames)
            assertFalse(url.queryParameter("w_rid").isNullOrBlank())
            assertEquals(150, info.source.quality)
            assertEquals(10000, info.resolvedPlayback?.requestedQuality)
        }
    }

    @Test fun retiredPrimaryEpochDuringNavCannotSendStreamOrLegacyRequest(): Unit = runBlocking {
        withTimeout(5_000) {
            val f = Fixture(); val epoch = f.repository.sessionEpoch; val original = f.reply
            f.reply = { request -> original(request).also { if (request.url.encodedPath.endsWith("/nav")) f.sessions.logout() } }
            val result = runCatching { f.media.livePlaybackInfo(room, expectedEpoch = epoch, stillOwned = { true }) }
            assertTrue(result.isFailure)
            assertEquals(listOf("/x/web-interface/nav"), f.requests.map { it.url.encodedPath })
        }
    }

    @Test fun inactiveCallerDuringSignWaitCannotReachStreamOrFallback(): Unit = runBlocking {
        withTimeout(5_000) {
            val f = Fixture(); val epoch = f.repository.sessionEpoch; val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val original = f.reply
            f.reply = { request ->
                original(request).also { if (request.url.encodedPath.endsWith("/nav")) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
            }
            val job = launch { f.media.livePlaybackInfo(room, expectedEpoch = epoch, stillOwned = { true }) }
            try {
                withContext(Dispatchers.IO) { check(entered.await(3, TimeUnit.SECONDS)) }
                job.cancel()
            } finally { release.countDown() }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf("/x/web-interface/nav"), f.requests.map { it.url.encodedPath })
        }
    }

    @Test fun retiredOwnerBeforeAudioOnlyRetryCannotFallBackOrBorrowSuccessor(): Unit = runBlocking {
        withTimeout(5_000) {
            val f = Fixture(); val epoch = f.repository.sessionEpoch; var current = true; val original = f.reply
            f.reply = { request ->
                if (request.url.encodedPath.endsWith("getRoomPlayInfo")) {
                    assertEquals("1", request.url.queryParameter("only_audio")); current = false
                    """{"code":-1,"message":"synthetic audio rejection"}"""
                } else original(request)
            }
            assertTrue(runCatching { f.media.livePlaybackInfo(room, onlyAudio = true, expectedEpoch = epoch, stillOwned = { current }) }.isFailure)
            assertEquals(2, f.requests.size)
            assertFalse(f.requests.any { it.url.encodedPath.endsWith("/playUrl") })
            assertTrue(runCatching { f.media.livePlaybackInfo(room, expectedEpoch = epoch, stillOwned = { false }) }.isFailure)
            assertEquals(2, f.requests.size)
        }
    }

    @Test fun actualPrimaryAdmissionRejectsEpochRetiredAfterSuccessfulPrecheck(): Unit {
        val f = Fixture(); val epoch = f.repository.sessionEpoch
        val ports = desktopLiveRecoveryPorts(f.repository, f.media, epoch)
        assertTrue(ports.isAccountCurrent())
        f.sessions.logout() // deterministic interleaving before the real final Session gate
        var published = false
        assertFalse(ports.admit { published = true })
        assertFalse(published); assertTrue(f.requests.isEmpty())
    }

    @Test fun actualPrimaryAdmissionRejectsCallerOrOwnerRetiredBeforeErrorPublication(): Unit {
        val f = Fixture(); val epoch = f.repository.sessionEpoch; val caller = Job()
        assertTrue(caller.isActive)
        caller.cancel()
        var published = false
        assertFalse(desktopLiveAdmission(f.repository, epoch, { caller.isActive }) { published = true })
        assertFalse(published); assertTrue(f.requests.isEmpty())
        var current = true
        assertTrue(current)
        current = false
        assertFalse(desktopLiveAdmission(f.repository, epoch, { current }) { published = true })
        assertFalse(published)
    }

    @Test fun actualActionAuthenticationAndOtherFailuresAreNeverSwallowedAsRetirement(): Unit {
        val f = Fixture(); val epoch = f.repository.sessionEpoch
        val authentication = BiliApiException(-101, "synthetic actual action failure")
        assertSame(authentication, assertFailsWith<BiliApiException> {
            desktopLiveAdmission(f.repository, epoch, { true }) { throw authentication }
        })
        val failure = IllegalStateException("synthetic publication failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            desktopLiveAdmission(f.repository, epoch, { true }) { throw failure }
        })
        val cancelled = CancellationException("synthetic caller cancellation")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            desktopLiveAdmission(f.repository, epoch, { true }) { throw cancelled }
        })
        assertTrue(f.requests.isEmpty())
    }
}
