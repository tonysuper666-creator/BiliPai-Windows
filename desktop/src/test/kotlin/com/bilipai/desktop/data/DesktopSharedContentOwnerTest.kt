package com.bilipai.desktop.data

import com.android.purebilibili.data.repository.SharedContentRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercise the actual shared repository, its independent cache worker and the owned HTTP call factory. */
class DesktopSharedContentOwnerTest {
    private class Fixture(blockResponse: Boolean = false) : AutoCloseable {
        val store = DesktopSessionStore.temporary().apply {
            for (mid in listOf(11L, 22L)) saveAccount(
                mapOf("SESSDATA" to "fixture-$mid", "bili_jct" to "csrf-$mid"),
                AccountSummary(mid, "Fixture $mid", "", isVip = true))
        }
        val repository = DesktopRepository(store)
        var windowOpen = true
        val window = DesktopUpstreamSession(repository) { windowOpen }
        val requests = AtomicInteger()
        val entered = Channel<Call>(Channel.UNLIMITED)
        val release = CountDownLatch(if (blockResponse) 1 else 0)
        private val field = DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
        private val client = (field.get(repository) as OkHttpClient).newBuilder().apply {
            interceptors().add(0, Interceptor { chain ->
                val tagClass = Class.forName("com.bilipai.desktop.data.DesktopSessionEpoch")
                requireNotNull(chain.request().tag(tagClass)) { "detail request has no owner" }
                val number = requests.incrementAndGet()
                check(entered.trySend(chain.call()).isSuccess)
                check(release.await(10, TimeUnit.SECONDS)) { "test response was never released" }
                val json = """{"code":0,"data":{"bvid":"BV1xx411c7mD","aid":42,"cid":1,"pubdate":$number}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody("application/json".toMediaType())).build()
            })
        }.build().also { field.set(repository, it) }

        suspend fun detail(owner: DesktopUpstreamOwner = window.owner()) =
            DesktopUpstreamCalls.withOwnerScope(owner) { SharedContentRepository.detail("BV1xx411c7mD", 42) }

        fun cached() = window.read(null) { SharedContentRepository.cachedDetailInfo("BV1xx411c7mD", 42) }

        override fun close() {
            release.countDown()
            repository.logout()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            entered.close()
        }
    }

    @Test fun cancellingOneWaiterKeepsTheSharedOwnedRequestAlive(): Unit = runBlocking {
        withTimeout(10_000) {
            Fixture(blockResponse = true).use { f ->
                // IO + UNDISPATCHED reaches getOrLoad before returning, so the second waiter has joined.
                val first = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { f.detail() }
                val call = f.entered.receive()
                val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { f.detail() }
                try {
                    first.cancelAndJoin()
                    assertFalse(call.isCanceled(), "one cancelled waiter must not cancel the shared call")
                    assertFalse(second.isCompleted)
                    f.release.countDown()
                    assertEquals(1L, second.await().getOrThrow().pubdate)
                    assertEquals(1, f.requests.get())
                    assertEquals(1L, f.cached()?.pubdate)
                } finally {
                    f.release.countDown()
                    first.cancelAndJoin()
                    second.cancelAndJoin()
                }
            }
        }
    }

    @Test fun cancellingTheLastWaiterCancelsHttpAndAllowsAReload(): Unit = runBlocking {
        withTimeout(10_000) {
            Fixture(blockResponse = true).use { f ->
                val first = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { f.detail() }
                val call = f.entered.receive()
                first.cancelAndJoin()
                withTimeout(2_000) { while (!call.isCanceled()) delay(10) }
                assertNull(f.cached())
                f.release.countDown()
                assertEquals(2L, f.detail().getOrThrow().pubdate)
                assertEquals(2, f.requests.get())
            }
        }
    }

    @Test fun detailCachePartitionsStoresAndEveryLoginEpoch(): Unit = runBlocking {
        Fixture().use { first ->
            Fixture().use { second ->
                assertEquals(1L, first.detail().getOrThrow().pubdate)
                assertNull(second.cached(), "identical credentials in another Store must not share detail data")
                assertEquals(1L, second.detail().getOrThrow().pubdate)
                assertEquals(1, second.requests.get())
                assertTrue(first.store.activateAccount(11))
                assertNull(first.cached())
                assertEquals(2L, first.detail().getOrThrow().pubdate)
                assertTrue(first.store.activateAccount(22))
                assertNull(first.cached(), "returning to the same credentials must not revive the old epoch")
                assertEquals(3L, first.detail().getOrThrow().pubdate)
                assertEquals(3, first.requests.get())
            }
        }
    }

    @Test fun retiredWindowCannotPublishOrCacheAnInFlightDetail(): Unit = runBlocking {
        withTimeout(10_000) {
            Fixture(blockResponse = true).use { f ->
                val pending = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { f.detail().getOrThrow() }
                }
                f.entered.receive()
                f.windowOpen = false
                f.release.countDown()
                assertTrue(pending.await().isFailure)
                f.windowOpen = true
                assertNull(f.cached(), "a retired owner must not populate the completed cache")
                assertEquals(2L, f.detail().getOrThrow().pubdate)
            }
        }
    }
}
