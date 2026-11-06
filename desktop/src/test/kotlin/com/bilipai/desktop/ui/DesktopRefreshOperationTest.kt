package com.bilipai.desktop.ui

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

class DesktopRefreshOperationTest {
    private fun reply(request: Request, text: String) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
        .body(text.toResponseBody("application/json".toMediaType())).build()
    private val request = Request.Builder().url("https://fixture.invalid/feed").build()

    @Test fun asyncRequestsCarryTheirInitiatingOperationAndIgnoreUnrelatedWork() {
        val client = OkHttpClient.Builder().addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { reply(it.request(), """{"code":-352}""") }.build()
        val operation = DesktopRefreshFailureWatch.Operation()
        val tagged = DesktopRefreshFailureWatch.within(operation) { DesktopRefreshFailureWatch.callFactory(client).newCall(request) }
        val latch = CountDownLatch(1)
        tagged.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { latch.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Same client and endpoint, but no refresh caller: not this operation's failure.
        client.newCall(request).execute().close()
        assertEquals(1, operation.end())
    }

    @Test fun aLatePreviousRequestCannotBeChargedToTheNextRefresh() {
        val client = OkHttpClient.Builder().addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { reply(it.request(), """{"code":-352}""") }.build()
        val old = DesktopRefreshFailureWatch.Operation()
        val pending = DesktopRefreshFailureWatch.within(old) { DesktopRefreshFailureWatch.callFactory(client).newCall(request) }
        assertEquals(0, old.end())
        val next = DesktopRefreshFailureWatch.Operation()
        DesktopRefreshFailureWatch.within(next) {
            pending.execute().close()
            pending.clone().execute().close()
        }
        assertEquals(0, next.end())
        assertEquals(0, old.end())
    }

    @Test fun refreshContextSurvivesDispatchAndChildCoroutinesInARetainedScope() = runBlocking {
        val retained = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val operation = DesktopRefreshFailureWatch.Operation()
        try {
            val observed = CompletableDeferred<List<DesktopRefreshFailureWatch.Operation?>>()
            val task = DesktopRefreshFailureWatch.within(operation) {
                retained.desktopRefreshLaunch {
                    withContext(Dispatchers.IO) {
                        val parent = DesktopRefreshFailureWatch.operation()
                        val child = coroutineScope { async { DesktopRefreshFailureWatch.operation() }.await() }
                        observed.complete(listOf(parent, child))
                    }
                }
            }
            task.join()
            withTimeout(2000) { observed.await() }.forEach { assertSame(operation, it) }
            assertNull(DesktopRefreshFailureWatch.operation())
            val unrelated = retained.async { DesktopRefreshFailureWatch.operation() }
            assertNull(unrelated.await())
        } finally { retained.cancel() }
    }

    @Serializable data class Feed(val code: Int, val data: List<Int>)
    interface Api { @GET("feed") suspend fun feed(): Feed }

    @Test fun aTypedDecodeFailureCountsOnceAndTheFollowingRefreshCanSucceed() = runBlocking {
        var body = """{"code":0,"data":"wrong type"}"""
        val client = OkHttpClient.Builder().addInterceptor(DesktopRefreshFailureWatch.interceptor)
            .addInterceptor { reply(it.request(), body) }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/")
            .callFactory(DesktopRefreshFailureWatch.callFactory(client))
            .addConverterFactory(DesktopRefreshResponseConverter(Json.asConverterFactory("application/json".toMediaType())))
            .build().create(Api::class.java)
        suspend fun fetch(operation: DesktopRefreshFailureWatch.Operation): Result<Feed> {
            val caller = currentCoroutineContext()
            val task = DesktopRefreshFailureWatch.within(operation) {
                CoroutineScope(caller).async(DesktopRefreshFailureWatch.context()) { runCatching { api.feed() } }
            }
            return task.await()
        }
        val failed = DesktopRefreshFailureWatch.Operation()
        val result = fetch(failed)
        assertIs<kotlinx.serialization.SerializationException>(result.exceptionOrNull())
        assertEquals(1, failed.end())
        body = """{"data":[1,2],"code":0}"""
        val recovered = DesktopRefreshFailureWatch.Operation()
        assertEquals(listOf(1, 2), fetch(recovered).getOrThrow().data)
        assertEquals(0, recovered.end())
    }
}
