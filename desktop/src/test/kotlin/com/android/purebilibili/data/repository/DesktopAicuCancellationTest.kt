package com.android.purebilibili.data.repository

import com.android.purebilibili.data.model.response.AicuCategory
import com.android.purebilibili.data.model.response.AicuQuery
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Execute the actual unmodified repository through a local interceptor, without live queries. */
class DesktopAicuCancellationTest {
    @Test fun `cancelling an active queue closes its response and posts its own ticket cancellation`() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val closeCount = AtomicInteger()
        val paths = CopyOnWriteArrayList<String>()
        val ticket = "isolated-queue-ticket"
        val body = object : ResponseBody() {
            private val source: BufferedSource = object : Source {
                private val didClose = AtomicBoolean()
                override fun timeout() = Timeout.NONE
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.countDown()
                    if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("Test stream was not closed")
                    throw IOException("Test response closed after cancellation")
                }
                override fun close() {
                    if (didClose.compareAndSet(false, true)) { closeCount.incrementAndGet(); closed.countDown() }
                }
            }.buffer()
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val client = buildAicuClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            paths += path
            val responseBody = when (path) {
                "/api/v4/queue/enqueue" -> {
                    assertEquals("POST", request.method)
                    """{"code":0,"data":{"ticket":"$ticket","status":"waiting","position":3}}"""
                        .toResponseBody("application/json".toMediaType())
                }
                "/api/v4/queue/stream" -> {
                    assertEquals(ticket, request.url.queryParameter("ticket"))
                    assertEquals("text/event-stream", request.header("Accept"))
                    body
                }
                "/api/v4/queue/cancel" -> {
                    assertEquals("POST", request.method)
                    assertEquals(ticket, request.url.queryParameter("ticket"))
                    cancelled.countDown()
                    "{}".toResponseBody("application/json".toMediaType())
                }
                else -> error("Cancelled request must not consume a query: $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", responseBody.contentType().toString()).body(responseBody).build()
        }.build()
        val positions = CopyOnWriteArrayList<Int?>()
        val query = launch(Dispatchers.Default) {
            AicuRepository(client).query(AicuQuery(2L, AicuCategory.COMMENT)) { positions += it }
            fail("Cancelled repository must not return a successful result")
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { query.cancelAndJoin() }
            assertTrue(query.isCancelled)
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(5, TimeUnit.SECONDS) })
            assertEquals(1, closeCount.get())
            assertEquals(listOf(2), positions.toList())
            assertEquals(listOf("/api/v4/queue/enqueue", "/api/v4/queue/stream", "/api/v4/queue/cancel"), paths.toList())
        } finally {
            closed.countDown()
            query.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
