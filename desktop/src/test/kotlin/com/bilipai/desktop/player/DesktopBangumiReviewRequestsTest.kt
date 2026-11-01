package com.bilipai.desktop.player

import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.data.repository.DesktopOriginalBangumiReviewRequests
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.jupiter.api.Test
import kotlin.test.*

class DesktopBangumiReviewRequestsTest {
    private data class Call(val name: String, val args: List<Any?>, val continuation: Continuation<Any?>)
    private class Fixture {
        val owned = AtomicBoolean(true)
        val active = AtomicBoolean(true)
        var csrf: String? = "synthetic-csrf"
        val calls = Channel<Call>(Channel.UNLIMITED)
        val api = Proxy.newProxyInstance(BangumiApi::class.java.classLoader, arrayOf(BangumiApi::class.java)) { _, method, args ->
            check(method.name in setOf("postBangumiShortReview", "dislikeBangumiReview", "modifyBangumiShortReview", "deleteBangumiShortReview")) { method.name }
            @Suppress("UNCHECKED_CAST")
            calls.trySend(Call(method.name, args!!.dropLast(1), args.last() as Continuation<Any?>)).getOrThrow()
            COROUTINE_SUSPENDED
        } as BangumiApi
        val requests = DesktopOriginalBangumiReviewRequests(api, { csrf }, owned::get, { owned.get() && active.get() })
        suspend fun next() = withTimeout(5000) { calls.receive() }
    }

    @Test fun allNewActionsKeepExactParametersAndOrdinaryFailures() = runBlocking {
        val f = Fixture()
        suspend fun checkCall(name: String, args: List<Any?>, op: suspend () -> Result<Unit>) {
            val result = async { op() }
            val c = f.next(); assertEquals(name,c.name); assertEquals(args,c.args)
            c.continuation.resume(SimpleApiResponse()); assertTrue(result.await().isSuccess)
        }
        checkCall("postBangumiShortReview", listOf(19L,10,"text",1,"synthetic-csrf")) { f.requests.postShortReview(19,12," text ",true) }
        checkCall("dislikeBangumiReview", listOf(19L,2,51L,"synthetic-csrf")) { f.requests.dislikeReview(19,51) }
        checkCall("modifyBangumiShortReview", listOf(19L,51L,2,"edit","synthetic-csrf")) { f.requests.modifyShortReview(19,51,0," edit ") }
        checkCall("deleteBangumiShortReview", listOf(19L,51L,"synthetic-csrf")) { f.requests.deleteShortReview(19,51) }
        // Both the old three-argument entry and the explicit false flag retain share_feed=0.
        checkCall("postBangumiShortReview", listOf(19L,8,"old",0,"synthetic-csrf")) { f.requests.postShortReview(19,8,"old") }
        checkCall("postBangumiShortReview", listOf(19L,8,"new",0,"synthetic-csrf")) { f.requests.postShortReview(19,8,"new",false) }
        val failed = async { f.requests.dislikeReview(19,51) }
        f.next().continuation.resume(SimpleApiResponse(code=-1,message="synthetic denial"))
        assertEquals("synthetic denial",failed.await().exceptionOrNull()?.message)
        assertTrue(f.calls.tryReceive().isFailure)
    }

    @Test fun missingCsrfAndRetiredOrCoveredActionsNeverCallApi() = runBlocking {
        val f = Fixture()
        val operations: List<suspend () -> Result<Unit>> = listOf(
            { f.requests.postShortReview(19,8,"text",true) },
            { f.requests.dislikeReview(19,51) },
            { f.requests.modifyShortReview(19,51,8,"text") },
            { f.requests.deleteShortReview(19,51) })
        f.csrf = null
        operations.forEach { assertEquals("请先登录",it().exceptionOrNull()?.message) }
        f.csrf = "synthetic-csrf"
        for (retired in listOf(false,true)) {
            f.owned.set(!retired);f.active.set(false)
            operations.forEach { op -> assertFailsWith<CancellationException> { op() } }
        }
        assertTrue(f.calls.tryReceive().isFailure)
    }

    @Test fun lateResultsAfterCancellationCoverageOrRetirementAreRejected() = runBlocking {
        for (mode in listOf("cancel","covered","retired")) {
            val f = Fixture()
            var published = false
            val job = launch { f.requests.postShortReview(19,8,"text",true).getOrThrow(); published=true }
            val call = f.next()
            when(mode) { "cancel" -> job.cancel(); "covered" -> f.active.set(false); else -> f.owned.set(false) }
            call.continuation.resume(SimpleApiResponse())
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled,mode);assertFalse(published,mode)
            assertTrue(f.calls.tryReceive().isFailure)
        }
    }
}
