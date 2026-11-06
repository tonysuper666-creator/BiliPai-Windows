package com.bilipai.desktop.ui

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/** QA reproductions against PR b33160077; isolated transport, no account or external network. */
class QaRefreshRegressionTest {
    private val request = Request.Builder().url("https://fixture.invalid/feed").build()
    private fun response(body: ResponseBody) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body(body).build()

    @Test fun nestedCodeBeforeTheSuccessfulTopLevelCodeMustNotReportFailure() {
        response("""{"data":{"code":-1},"code":0}""".toResponseBody("application/json".toMediaType())).use {
            assertFalse(desktopRefreshResponseFailed(it), "JSON member order must not change success into failure")
        }
    }

    @Test fun nestedCodeBeforeTheFailingTopLevelCodeMustNotHideFailure() {
        response("""{"data":{"code":0},"code":-352}""".toResponseBody("application/json".toMediaType())).use {
            assertTrue(desktopRefreshResponseFailed(it), "top-level business failure must remain visible")
        }
    }

    @Test fun aConnectionLostWhileReadingTheBodyMustBeCounted() {
        val prefix = Buffer().writeUtf8("{\"code\":0,\"data\":\"" + "x".repeat(3000))
        val brokenBody = object : ResponseBody() {
            private val stream = object : Source {
                override fun timeout() = Timeout.NONE
                override fun close() { }
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (prefix.size > 0) return prefix.read(sink, minOf(byteCount, prefix.size))
                    throw IOException("connection reset during response body")
                }
            }.buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = stream
        }
        val client = OkHttpClient.Builder().addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { response(brokenBody) }.build()
        var readFailed = false
        DesktopRefreshFailureWatch.begin()
        try {
            client.newCall(request).execute().use { it.body!!.string() }
        } catch (_: IOException) { readFailed = true }
        val failures = DesktopRefreshFailureWatch.end()
        assertTrue(readFailed, "the real response read failed")
        assertEquals(1, failures, "the page must get visible failure feedback after a response-body disconnect")
    }
}
