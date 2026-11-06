package com.bilipai.desktop.ui

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class ArchTmpMalformedJsonTest {
    private val request = Request.Builder().url("https://fixture.invalid/feed").build()

    private fun run(watched: Boolean): Triple<String, List<Throwable>, Int> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true; setUncaughtExceptionHandler { _, e -> uncaught += e } }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(pool))
            .addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("".toResponseBody("application/json".toMediaType())).build() }.build()
        val operation = DesktopRefreshFailureWatch.Operation()
        val call = if (watched) DesktopRefreshFailureWatch.within(operation) { DesktopRefreshFailureWatch.callFactory(client).newCall(request) }
            else client.newCall(request)
        val latch = CountDownLatch(1)
        var outcome = ""
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { outcome = "failure:" + e.message; latch.countDown() }
            override fun onResponse(call: Call, response: Response) { outcome = "response"; response.close(); latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.SECONDS)
        return Triple(outcome, uncaught.toList(), operation.end())
    }

    @Test fun unwatchedEmptyJsonBodyReachesTheCallerAsAResponse() {
        val (outcome, uncaught, _) = run(watched = false)
        println("ARCH unwatched outcome=$outcome uncaught=$uncaught")
        assertEquals("response", outcome)
        assertTrue(uncaught.isEmpty())
    }

    @Test fun watchedEmptyJsonBodyDoesNotEscapeToTheDispatcherThread() {
        val (outcome, uncaught, failures) = run(watched = true)
        println("ARCH watched outcome=$outcome uncaught=$uncaught failures=$failures")
        assertEquals("response", outcome)
        assertEquals(1, failures)
        assertTrue(uncaught.isEmpty(), "uncaught on OkHttp thread: $uncaught")
    }
}
