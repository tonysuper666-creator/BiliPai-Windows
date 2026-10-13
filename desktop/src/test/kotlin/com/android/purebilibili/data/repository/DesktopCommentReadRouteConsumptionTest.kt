package com.android.purebilibili.data.repository

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.grpc.ProtoWire
import com.android.purebilibili.data.model.response.ReplyData
import com.android.purebilibili.data.model.response.ReplyResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Exercises the real generated read protocol, original gRPC parser and memo.
 * Only the two network transports are fixtures; no user account or window opens. */
class DesktopCommentReadRouteConsumptionTest {
    private class Fixture {
        val owned = AtomicBoolean(true)
        val grpcReads = AtomicInteger()
        val restReads = AtomicInteger()
        val restData = Json.decodeFromString(ReplyData.serializer(),
            """{"replies":[{"rpid":77,"content":{"message":"REST fixture"}}]}""")
        val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, _ ->
            assertTrue(method.name in setOf("getReplyListMain", "getReplyListLegacy", "getReplyList"),
                "Unexpected comment transport: ${method.name}")
            restReads.incrementAndGet()
            ReplyResponse(code = 0, data = restData)
        } as BilibiliApi

        fun assertOwned() {
            if (!owned.get()) throw CancellationException("retired comment owner")
        }

        fun protocol(memo: CommentReadRouteMemo? = null,
            transport: suspend (String, ByteArray) -> ByteArray): DesktopDynamicCommentProtocol {
            val grpc = DesktopDynamicCommentGrpc({ path, request ->
                assertTrue(path.endsWith("/MainList"))
                assertTrue(request.isNotEmpty())
                grpcReads.incrementAndGet()
                transport(path, request)
            }, ::assertOwned)
            return if (memo == null) {
                DesktopDynamicCommentProtocol(api, api, grpc, { true }, { it }, ::assertOwned)
            } else {
                DesktopDynamicCommentProtocol(api, api, grpc, { true }, { it }, ::assertOwned, memo)
            }
        }

        fun grpcReply(): ByteArray = ProtoWire.message(ProtoWire.bytes(2,
            ProtoWire.message(ProtoWire.int64(2, 99),
                ProtoWire.bytes(12, ProtoWire.message(ProtoWire.string(1, "gRPC fixture"))))))
    }

    @Test fun repeatedVideosUseRestWithoutRepeatingTheRecentlyFailedGrpc(): Unit = runBlocking {
        val fixture = Fixture()
        val protocol = fixture.protocol { _, _ -> throw IOException("fixture gRPC unavailable") }
        assertEquals(77L, protocol.getCommentsForSubject(170001, 1, 1).getOrThrow().replies!!.single().rpid)
        assertEquals(77L, protocol.getCommentsForSubject(170002, 1, 1).getOrThrow().replies!!.single().rpid)
        assertEquals(1, fixture.grpcReads.get())
        assertEquals(2, fixture.restReads.get())
    }

    @Test fun expiredMemoRetriesGrpcAndSuccessfulReadsStayOnGrpc(): Unit = runBlocking {
        val fixture = Fixture()
        val now = AtomicLong()
        val memo = CommentReadRouteMemo(failureTtlMs = 1_000, nowMs = now::get)
        val protocol = fixture.protocol(memo) { _, _ ->
            if (fixture.grpcReads.get() == 1) throw IOException("first attempt unavailable")
            fixture.grpcReply()
        }
        assertEquals(77L, protocol.getCommentsForSubject(170001, 1, 1).getOrThrow().replies!!.single().rpid)
        now.set(999)
        protocol.getCommentsForSubject(170002, 1, 1).getOrThrow()
        assertEquals(1, fixture.grpcReads.get())
        now.set(1_000)
        assertEquals(99L, protocol.getCommentsForSubject(170003, 1, 1).getOrThrow().replies!!.single().rpid)
        assertFalse(memo.shouldSkipGrpcAttempt())
        protocol.getCommentsForSubject(170004, 1, 1).getOrThrow()
        assertEquals(3, fixture.grpcReads.get())
        assertEquals(2, fixture.restReads.get())
    }

    @Test fun newOwnedProtocolDoesNotInheritAnotherOwnersFailureMemory(): Unit = runBlocking {
        val fixture = Fixture()
        val old = fixture.protocol { _, _ -> throw IOException("old route unavailable") }
        old.getCommentsForSubject(170001, 1, 1).getOrThrow()
        val next = fixture.protocol { _, _ -> fixture.grpcReply() }
        assertEquals(99L, next.getCommentsForSubject(170002, 1, 1).getOrThrow().replies!!.single().rpid)
        assertEquals(2, fixture.grpcReads.get())
        assertEquals(1, fixture.restReads.get())
    }

    @Test fun retirementDuringGrpcCannotRecordFailureOrStartRest(): Unit = runBlocking {
        val fixture = Fixture()
        val deferred = CompletableDeferred<ByteArray>()
        val entered = CompletableDeferred<Unit>()
        val memo = CommentReadRouteMemo()
        val protocol = fixture.protocol(memo) { _, _ ->
            entered.complete(Unit)
            deferred.await()
        }
        val operation = async { protocol.getCommentsForSubject(170001, 1, 1) }
        entered.await()
        fixture.owned.set(false)
        deferred.complete(fixture.grpcReply())
        assertFailsWith<CancellationException> { operation.await() }
        assertFalse(memo.shouldSkipGrpcAttempt())
        assertEquals(1, fixture.grpcReads.get())
        assertEquals(0, fixture.restReads.get())
    }
}
