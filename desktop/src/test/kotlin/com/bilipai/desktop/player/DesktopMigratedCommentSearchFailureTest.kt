package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Candidate diagnostic: actual full VM and collector, strict in-memory ports, no network. */
class DesktopMigratedCommentSearchFailureTest {
    @Test fun upstreamSearchPreservesFailureThenRetriesFromFirstTypedRequest(): Unit = runBlocking {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, t -> failures += t })
        val searchCalls = mutableListOf<List<Any?>>()
        val failure = IllegalStateException("synthetic read failure")
        val ports = java.lang.reflect.Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "isOwned" -> true
                "currentMid" -> 111L
                "getMobileCommentsForSubject" -> {
                    val values = args!!.dropLast(1)
                    assertEquals(listOf(170001L, 1, 1, VIDEO_COMMENT_PAGE_SIZE), values.take(4))
                    assertNull(values[5])
                    when (values[4]) {
                        3 -> ReplyData(cursor=ReplyCursor(isEnd=true),replies=emptyList())
                        2 -> {
                            searchCalls += values
                            if (searchCalls.size == 1) {
                                Class.forName("kotlin.ResultKt").getMethod("createFailure", Throwable::class.java).invoke(null, failure)
                            } else {
                                assertEquals(2, searchCalls.size)
                                ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=8)))
                            }
                        }
                        else -> error("Unexpected mode ${values[4]}")
                    }
                }
                else -> error("Unexpected port ${method.name}")
            }
        } as DesktopVideoCommentRequests
        try {
            val vm = VideoCommentViewModel(scope, ports)
            vm.init(170001L,42L,CommentSortMode.HOT,0)
            fun searchJob() = VideoCommentViewModel::class.java.getDeclaredField("fullSearchJob").apply { isAccessible=true }.get(vm) as Job
            vm.loadAllCommentsForSearch()
            withTimeout(5000) { searchJob().join() }
            assertTrue(vm.fullSearchState.value.isReady)
            assertFalse(vm.fullSearchState.value.isComplete)
            assertFalse(vm.fullSearchState.value.isLoading)
            assertEquals("synthetic read failure", vm.fullSearchState.value.error)
            vm.loadAllCommentsForSearch()
            withTimeout(5000) { searchJob().join() }
            assertEquals(2, searchCalls.size)
            assertEquals(searchCalls[0], searchCalls[1])
            assertTrue(vm.fullSearchState.value.isReady)
            assertTrue(vm.fullSearchState.value.isComplete)
            assertNull(vm.fullSearchState.value.error)
            assertEquals(listOf(8L), vm.fullSearchReplies.value.map { it.rpid })
            assertTrue(failures.isEmpty(), failures.toString())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
}
