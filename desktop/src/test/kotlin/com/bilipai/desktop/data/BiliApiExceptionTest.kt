package com.bilipai.desktop.data

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BiliApiExceptionTest {
    @Test
    fun `interceptor rejection reaches async failure callback instead of killing dispatcher`() {
        val client = OkHttpClient.Builder().addInterceptor { throw BiliApiException(412, "request limited") }.build()
        val failure = CompletableFuture<IOException>()
        try {
            client.newCall(Request.Builder().url("https://example.test/").build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure.complete(e) }
                override fun onResponse(call: Call, response: Response) { response.close(); failure.completeExceptionally(AssertionError("unexpected response")) }
            })
            assertEquals(412, assertIs<BiliApiException>(failure.get(5, TimeUnit.SECONDS)).apiCode)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
