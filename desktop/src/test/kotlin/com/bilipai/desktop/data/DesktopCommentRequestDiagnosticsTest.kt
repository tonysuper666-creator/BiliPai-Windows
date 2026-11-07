package com.bilipai.desktop.data

import com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.*

class DesktopCommentRequestDiagnosticsTest {
    @Test fun actualDetailProtocolReportsFailureWithoutRequestOrServerSecrets(): Unit = runBlocking {
        val failure = IllegalStateException("请求失败：HTTP 412，gRPC -352，SESSDATA=private; oid=170001")
        val lines = mutableListOf<String>()
        val grpc = DesktopDynamicCommentGrpc({ path, body ->
            assertTrue(body.isNotEmpty())
            desktopCommentGrpcRequest(path, lines::add) { throw failure }
        }, {})
        assertSame(failure, grpc.getDetailList(170001, 1, 77, mode = 3, nextOffset = null).exceptionOrNull())
        assertEquals(listOf("api=Reply/DetailList code=http=412 grpc=-352 message=server rejected comment request"), lines)
    }

    @Test fun cancellationAndSuccessNeverLog(): Unit = runBlocking {
        val lines = mutableListOf<String>()
        val cancelled = CancellationException("private cancellation context")
        assertFailsWith<CancellationException> {
            desktopCommentGrpcRequest("/private?token=secret", lines::add) { throw cancelled }
        }
        assertEquals(7, desktopCommentGrpcRequest("/private?token=secret", lines::add) { 7 })
        assertTrue(lines.isEmpty())
    }

    @Test fun networkAndApiFailuresDoNotEchoExceptionMessagesOrUnknownPaths(): Unit = runBlocking {
        val lines = mutableListOf<String>()
        for (failure in listOf(IOException("https://private/?SESSDATA=secret"), BiliApiException(-404, "oid=170001"))) {
            assertFailsWith<Exception> {
                desktopCommentGrpcRequest("/private?token=secret", lines::add) { throw failure }
            }
        }
        assertEquals(listOf(
            "api=Reply/Other code=unavailable message=comment transport failed",
            "api=Reply/Other code=-404 message=comment API rejected request",
        ), lines)
    }

    @Test fun retiredOwnerCannotEmitAnErrorForANewerPage(): Unit = runBlocking {
        val lines = mutableListOf<String>()
        assertFailsWith<CancellationException> {
            desktopCommentGrpcRequest("/bilibili.main.community.reply.v1.Reply/DetailList", lines::add,
                assertOwner = { throw CancellationException("retired owner") }) {
                throw IOException("request from retired owner")
            }
        }
        assertTrue(lines.isEmpty())
    }
}
