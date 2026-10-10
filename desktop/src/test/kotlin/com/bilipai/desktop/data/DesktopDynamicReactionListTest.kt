package com.bilipai.desktop.data

import com.android.purebilibili.core.network.grpc.DesktopDynamicReactionApi
import com.android.purebilibili.core.network.grpc.DynamicReactionPage
import com.android.purebilibili.core.network.grpc.DynamicReactionUser
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.data.model.response.DynamicItem
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import kotlin.test.*

/** Direct original ProtoWire/API, actual State and cancellable OkHttp calls.
 * Application interceptors return local fixture responses; no endpoint is reached. */
class DesktopDynamicReactionListTest {
    private val request = Request.Builder().url("https://reaction-fixture.invalid/list").build()

    private fun item(type: String = "DYNAMIC_TYPE_WORD"): DynamicItem =
        Json.decodeFromString("""{"id_str":"12345","type":"$type","basic":{"rid_str":"789"},"modules":{}}""")

    private fun reaction(mid: Long, action: String) = ProtoWire.bytes(2, ProtoWire.message(
        ProtoWire.bytes(1, ProtoWire.message(
            ProtoWire.int64(1, mid), ProtoWire.string(2, "User-$mid"),
            ProtoWire.string(3, "https://image.invalid/$mid"),
        )),
        ProtoWire.string(3, action),
    ))

    private fun page(offset: String, more: Boolean, vararg reactions: ByteArray): ByteArray =
        ProtoWire.message(
            ProtoWire.string(1, "Server title"), *reactions,
            ProtoWire.string(3, offset), ProtoWire.bool(4, more),
        )

    private class SourceFixture {
        val sessions = DesktopSessionStore(Files.createTempDirectory("reaction-source-").resolve("session.json"), false)
        val gate = Any()
        val alive = AtomicBoolean(true)
        init { sessions.saveAccount(mapOf("SESSDATA" to "synthetic-A"), AccountSummary(101, "Fixture", "")) }
        val owner = checkNotNull(sessions.dynamicCacheOwner())
        fun current() = alive.get() && sessions.dynamicCacheOwner() == owner
        fun admit(action: () -> Unit): Boolean {
            var applied = false
            return sessions.withCurrentDynamicCacheOwner(owner) {
                synchronized(gate) {
                    if (alive.get()) { action(); applied = true }
                }
            } && applied
        }
        fun state(fetch: suspend (DynamicItem, String) -> DynamicReactionPage) =
            DesktopDynamicReactionListState(
                Json.decodeFromString("""{"id_str":"12345","type":"DYNAMIC_TYPE_WORD","modules":{}}"""),
                fetch, ::current, ::admit,
            )
    }

    private fun response(request: Request, body: ResponseBody) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("local fixture").body(body).build()

    private fun close(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }

    @Test fun originalApiUsesSourceBitTypesAndRetainsServerActions(): Unit = runBlocking {
        val types = linkedMapOf(
            "DYNAMIC_TYPE_FORWARD" to 1L, "DYNAMIC_TYPE_DRAW" to 2L,
            "DYNAMIC_TYPE_WORD" to 4L, "DYNAMIC_TYPE_AV" to 8L,
            "DYNAMIC_TYPE_ARTICLE" to 64L, "DYNAMIC_TYPE_MUSIC" to 256L,
            "DYNAMIC_TYPE_PGC" to 512L, "DYNAMIC_TYPE_COMMON_SQUARE" to 2048L,
            "DYNAMIC_TYPE_COMMON_VERTICAL" to 2049L, "DYNAMIC_TYPE_LIVE" to 4200L,
            "DYNAMIC_TYPE_LIVE_RCMD" to 4308L, "4096" to 4096L,
        )
        for ((type, expected) in types) {
            val api = DesktopDynamicReactionApi { path, payload ->
                assertEquals("/bilibili.app.dynamic.v2.Dynamic/ReactionList", path)
                val fields = ProtoWire.parseFields(payload)
                assertEquals(12345L, fields.single { it.number == 1 }.varint)
                assertEquals(expected, fields.single { it.number == 2 }.varint)
                assertEquals(789L, fields.single { it.number == 3 }.varint)
                assertEquals("cursor-A", ProtoWire.stringValue(fields.single { it.number == 4 }))
                page("cursor-B", true, reaction(42, "Liked"), reaction(42, "Forwarded"),
                    reaction(0, "Invalid"), ProtoWire.bytes(2, ProtoWire.string(3, "Missing user")))
            }
            val result = api.load(item(type), "cursor-A")
            assertEquals(listOf(42L to "Liked", 42L to "Forwarded"), result.users.map { it.mid to it.action })
            assertEquals("Server title", result.title); assertEquals("cursor-B", result.offset)
            assertTrue(result.hasMore)
        }
    }

    @Test fun paginationKeepsRowsAndCursorAcrossFailureAndRetryThenStopsRepeatedCursor(): Unit = runBlocking {
        val fixture = SourceFixture()
        val cursors = mutableListOf<String>()
        var invocation = 0
        val api = DesktopDynamicReactionApi { _, payload ->
            val fields = ProtoWire.parseFields(payload)
            cursors += fields.firstOrNull { it.number == 4 }?.let(ProtoWire::stringValue).orEmpty()
            when (invocation++) {
                0 -> page("next", true, reaction(7, "Liked"))
                1 -> throw IOException("HTTP 200, gRPC -352, blocked")
                else -> page("next", true, reaction(7, "Liked"), reaction(7, "Forwarded"))
            }
        }
        val state = fixture.state(api::load)
        try {
            state.load()
            assertTrue(state.shouldLoadMore(0, 1))
            state.load()
            assertEquals(listOf(7L to "Liked"), state.state.value.users.map { it.mid to it.action })
            assertEquals("next", state.state.value.offset)
            assertContains(checkNotNull(state.state.value.error), "请求被风控拦截")
            assertFalse(state.shouldLoadMore(0, 1))
            state.retry()
            assertEquals(listOf("", "next", "next"), cursors)
            assertEquals(listOf(7L to "Liked", 7L to "Forwarded"), state.state.value.users.map { it.mid to it.action })
            assertNull(state.state.value.error); assertFalse(state.state.value.hasMore)
            assertFalse(state.state.value.loading)
        } finally { state.close() }
    }

    @Test fun closingSheetDuringCallConstructionPreventsEnqueueWhileParentRemainsCurrent(): Unit = runBlocking {
        val fixture = SourceFixture()
        val starts = AtomicInteger()
        val escaped = AtomicInteger()
        lateinit var state: DesktopDynamicReactionListState
        val client = OkHttpClient.Builder().eventListenerFactory {
            state.close()
            object : EventListener() { override fun callStart(call: Call) { starts.incrementAndGet() } }
        }.addInterceptor { escaped.incrementAndGet(); error("Retired sheet reached HTTP") }.build()
        val api = DesktopDynamicReactionApi { _, _ ->
            withContext(Dispatchers.IO) {
                DesktopDynamicGrpcCalls.execute(client, request, { true }) { DesktopDynamicGrpcCalls.readBody(checkNotNull(it.body)) }
            }
        }
        state = fixture.state(api::load)
        try {
            val result = async { state.load() }
            assertFailsWith<CancellationException> { result.await() }
            assertTrue(fixture.current()); assertFalse(state.isOwned())
            assertEquals(0, starts.get()); assertEquals(0, escaped.get())
            assertTrue(state.state.value.users.isEmpty())
        } finally { state.close(); close(client) }
    }

    @Test fun accountChangeDuringCallConstructionRejectsTheCapturedSourceBeforeEnqueue(): Unit = runBlocking {
        val fixture = SourceFixture()
        val starts = AtomicInteger()
        val client = OkHttpClient.Builder().eventListenerFactory {
            fixture.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-B"), AccountSummary(202, "Fixture", ""))
            object : EventListener() { override fun callStart(call: Call) { starts.incrementAndGet() } }
        }.addInterceptor { error("Wrong account reached HTTP") }.build()
        val state = fixture.state { _, _ ->
            DesktopDynamicGrpcCalls.execute(client, request, { true }) {
                DesktopDynamicGrpcCalls.readBody(checkNotNull(it.body))
            }
            error("Rejected response returned")
        }
        try {
            val result = async { state.load() }
            assertFailsWith<CancellationException> { result.await() }
            assertEquals(0, starts.get()); assertFalse(state.isOwned())
            assertTrue(state.state.value.users.isEmpty())
        } finally { state.close(); close(client) }
    }

    @Test fun responseReadsAndClosesOutsideGateAndRetirementDuringReadCannotPublish(): Unit = runBlocking {
        val fixture = SourceFixture()
        val closed = AtomicBoolean()
        val body = object : ResponseBody() {
            private val buffered = object : ForwardingSource(Buffer().write(page("", false, reaction(9, "Liked")))) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    check(!Thread.holdsLock(fixture.gate))
                    val count = super.read(sink, byteCount)
                    fixture.alive.set(false)
                    return count
                }
                override fun close() {
                    check(!Thread.holdsLock(fixture.gate)); closed.set(true); super.close()
                }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = buffered
        }
        val client = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), body) }.build()
        val api = DesktopDynamicReactionApi { _, _ ->
            DesktopDynamicGrpcCalls.execute(client, request, { true }) {
                assertFalse(Thread.holdsLock(fixture.gate))
                DesktopDynamicGrpcCalls.readBody(checkNotNull(it.body))
            }
        }
        val state = fixture.state(api::load)
        try {
            val result = async { state.load() }
            assertFailsWith<CancellationException> { result.await() }
            assertTrue(closed.get()); assertTrue(state.state.value.users.isEmpty())
            assertFalse(state.state.value.loaded)
        } finally { state.close(); close(client) }
    }

    @Test fun inlineRejectedDispatcherDeliversFailureAfterSourceGateReturns(): Unit = runBlocking {
        val executor = Executors.newSingleThreadExecutor().also { it.shutdown() }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor))
            .addInterceptor { error("Rejected executor reached HTTP") }.build()
        val gate = Any()
        val source = DesktopDynamicGrpcRequestSource({ true }) { action ->
            // This is also called for final error acceptance; it cannot be nested
            // inside the enqueue admission when OkHttp rejects inline.
            assertFalse(Thread.holdsLock(gate))
            synchronized(gate) { action(); true }
        }
        try {
            val failure = assertFailsWith<IOException> {
                withContext(source) { DesktopDynamicGrpcCalls.execute(client, request, { true }) { it.code } }
            }
            assertContains(failure.message.orEmpty(), "executor")
        } finally { close(client) }
    }

    @Test fun closingInflightSheetCancelsActualCallOutsideGateAndLeavesParentJobAlive(): Unit = runBlocking {
        val fixture = SourceFixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                check(!Thread.holdsLock(fixture.gate)); cancelled.countDown()
            }
        }).addInterceptor { chain ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            response(chain.request(), page("", false, reaction(9, "Liked")).toResponseBody())
        }.build()
        val api = DesktopDynamicReactionApi { _, _ ->
            DesktopDynamicGrpcCalls.execute(client, request, { true }) { DesktopDynamicGrpcCalls.readBody(checkNotNull(it.body)) }
        }
        val state = fixture.state(api::load)
        try {
            val loading = async(Dispatchers.Default) { state.load() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            state.close()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(5, TimeUnit.SECONDS) })
            release.countDown()
            assertFailsWith<CancellationException> { loading.await() }
            assertTrue(currentCoroutineContext().isActive)
            assertTrue(fixture.current()); assertTrue(state.state.value.users.isEmpty())
        } finally { release.countDown(); state.close(); close(client) }
    }

    @Test fun existingGrpcWithoutRequestFacetStillUsesItsParentAndBoundedBody(): Unit = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), byteArrayOf(1, 2, 3).toResponseBody())
        }.build()
        try {
            assertContentEquals(byteArrayOf(1, 2, 3), DesktopDynamicGrpcCalls.execute(client, request, { true }) {
                DesktopDynamicGrpcCalls.readBody(checkNotNull(it.body))
            })
            assertFailsWith<CancellationException> {
                DesktopDynamicGrpcCalls.execute(client, request, { false }) { it.code }
            }
            val tooLarge = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = 8L * 1024 * 1024 + 1
                override fun source(): BufferedSource = Buffer()
            }
            assertFailsWith<IllegalArgumentException> { DesktopDynamicGrpcCalls.readBody(tooLarge) }
        } finally { close(client) }
    }
}
