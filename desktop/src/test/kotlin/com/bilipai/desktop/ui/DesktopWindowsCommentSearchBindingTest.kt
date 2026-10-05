package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Calls the sole generated full original VM through the actual Windows binding.
 * No media, account, alternate state implementation or external API is involved. */
class DesktopWindowsCommentSearchBindingTest {
    private data class Call(val oid: Long, val type: Int, val page: Int, val ps: Int, val mode: Int, val offset: String?)
    private class Harness : AutoCloseable {
        var account = true
        var permit = true
        var source: Any = Any()
        val calls = mutableListOf<Call>()
        var search: suspend (Call) -> Result<ReplyData> = { Result.success(data(1, end = true)) }
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure -> failures += failure })
        val requests = object : DesktopVideoCommentRequests {
            override fun isOwned() = account
            override fun currentMid() = 0L
            override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int, mode: Int,
                paginationOffset: String?, fallbackOnMissingLocation: Boolean): Result<ReplyData> {
                val call = Call(oid, type, page, ps, mode, paginationOffset)
                calls += call
                return if (mode == 2) search(call) else Result.success(data(90, end = true))
            }
            override suspend fun getSortedSubCommentsForSubject(oid: Long,type: Int,rootId: Long,mode: Int,paginationOffset: String?,targetReplyId: Long) = Result.success(data(99,end=true))
            override suspend fun getDialogCommentsForSubject(oid: Long,type: Int,rootId: Long,dialogId: Long,page: Int,paginationOffset: String?) = Result.success(data(98,end=true))
            override suspend fun uploadCommentPicture(source: String,index: Int): Result<ReplyPicture> = error("No mutation expected")
            override suspend fun addCommentForSubject(oid: Long,type: Int,message: String,root: Long,parent: Long,pictures: List<ReplyPicture>,syncToDynamic: Boolean): Result<ReplyItem?> = error("No mutation expected")
            override suspend fun likeCommentForSubject(oid: Long,type: Int,rpid: Long,like: Boolean): Result<Unit> = error("No mutation expected")
            override suspend fun hateCommentForSubject(oid: Long,type: Int,rpid: Long,hate: Boolean): Result<Unit> = error("No mutation expected")
            override suspend fun deleteCommentForSubject(oid: Long,type: Int,rpid: Long): Result<Unit> = error("No mutation expected")
            override suspend fun setCommentTopForSubject(oid: Long,type: Int,rpid: Long,isCurrentlyTop: Boolean): Result<Unit> = error("No mutation expected")
            override suspend fun reportCommentForSubject(oid: Long,type: Int,rpid: Long,reason: Int,content: String): Result<Unit> = error("No mutation expected")
            override suspend fun checkCommentStatus(aid: Long,rpid: Long,rootId: Long,hasPictures: Boolean,sentAtSeconds: Long,waitMs: Long): Result<CommentFraudStatus> = error("No mutation expected")
            override suspend fun saveFraudRecord(rpid: Long,oid: Long,type: Int,root: Long,message: String,status: CommentFraudStatus,initialStatus: CommentFraudStatus?) = error("No mutation expected")
        }
        val vm = VideoCommentViewModel(scope, requests).also { it.init(170001L,42L,CommentSortMode.HOT,0) }
        fun binding(captured: Any = source): DesktopWindowsCommentSearchBinding = DesktopWindowsCommentSearchBinding(vm,captured,
            { account && source === captured }, { action -> if (permit && account && source === captured) { action(); true } else false })
        val searchCalls get() = calls.filter { it.mode == 2 }
        override fun close() { scope.cancel() }
    }
    companion object {
        private fun data(id: Long, end: Boolean = false, offset: String = "next", tops: List<ReplyItem>? = null) = ReplyData(
            cursor = ReplyCursor(allCount=5,isEnd=end),replies=listOf(ReplyItem(rpid=id)),grpcNextOffset=offset,topReplies=tops)
    }

    @Test fun originalTimePagingOffsetsProgressAndTopDedupeAreConsumed(): Unit = runBlocking {
        Harness().use { h ->
            val second = CompletableDeferred<Result<ReplyData>>()
            h.search = { call -> if (call.page == 1) Result.success(data(1,tops=listOf(ReplyItem(rpid=1),ReplyItem(rpid=2)))) else second.await() }
            val binding=h.binding(); binding.load()
            assertTrue(h.vm.fullSearchState.value.isLoading)
            assertEquals(3,h.vm.fullSearchState.value.loadedCount)
            assertEquals(listOf(Call(170001,1,1,20,2,null),Call(170001,1,2,20,2,"next")),h.searchCalls)
            second.complete(Result.success(data(3,end=true)))
            assertTrue(h.vm.fullSearchState.value.isReady)
            assertEquals(listOf(1L,2L,3L),h.vm.fullSearchReplies.value.map { it.rpid })
            assertEquals(3,h.vm.fullSearchState.value.loadedCount)
            binding.close()
        }
    }

    @Test fun loadingAndCompletedSameSourceAreNotFetchedTwice(): Unit = runBlocking {
        Harness().use { h -> val response=CompletableDeferred<Result<ReplyData>>();h.search={response.await()}
            val binding=h.binding();binding.load();binding.load();assertEquals(1,h.searchCalls.size)
            response.complete(Result.success(data(1,end=true)));binding.load();assertEquals(1,h.searchCalls.size)
            binding.close();val reopened=h.binding();reopened.load();assertEquals(1,h.searchCalls.size);reopened.close()
        }
    }

    @Test fun originalFailureShowsRetryAndRetriesFromFirstPage(): Unit = runBlocking {
        Harness().use { h -> h.search={Result.failure(IllegalStateException("synthetic read failure"))}
            val binding=h.binding();binding.load();assertFalse(h.vm.fullSearchState.value.isReady)
            assertEquals("评论加载失败，请稍后重试",h.vm.fullSearchState.value.error)
            h.search={Result.success(data(8,end=true))};binding.load()
            assertEquals(listOf(1,1),h.searchCalls.map { it.page });assertTrue(h.vm.fullSearchState.value.isReady)
            assertNull(h.vm.fullSearchState.value.error);binding.close()
        }
    }

    @Test fun resultCancellationReleasesSearchBusyWithoutErrorOrSuccess(): Unit = runBlocking {
        Harness().use { h -> h.search={Result.failure(CancellationException("retired synthetic caller"))}
            val binding=h.binding();binding.load();assertFalse(h.vm.fullSearchState.value.isLoading)
            assertFalse(h.vm.fullSearchState.value.isReady);assertNull(h.vm.fullSearchState.value.error)
            assertTrue(h.vm.fullSearchReplies.value.isEmpty());binding.close()
        }
    }

    @Test fun closeCancelsOnlyOptionalSearchAndKeepsOrdinaryCommentOwner(): Unit = runBlocking {
        Harness().use { h -> val response=CompletableDeferred<Result<ReplyData>>();h.search={response.await()}
            val binding=h.binding();binding.load();binding.close()
            assertTrue(h.scope.isActive);assertFalse(h.vm.fullSearchState.value.isLoading)
            h.vm.refreshComments();assertTrue(h.calls.count { it.mode == 3 } >= 2)
            response.complete(Result.success(data(1,end=true)));assertFalse(h.vm.fullSearchState.value.isReady)
        }
    }

    @Test fun closedRequestLateFinallyCannotClearReopenedRequestBusy(): Unit = runBlocking {
        Harness().use { h -> val old=CompletableDeferred<Result<ReplyData>>();val newer=CompletableDeferred<Result<ReplyData>>()
            var request=0;h.search={ if (++request == 1) withContext(NonCancellable) { old.await() } else newer.await() }
            val a=h.binding();a.load();a.close();val b=h.binding();b.load()
            old.complete(Result.success(data(4,end=true)));assertTrue(h.vm.fullSearchState.value.isLoading)
            newer.complete(Result.success(data(5,end=true)));assertEquals(listOf(5L),h.vm.fullSearchReplies.value.map { it.rpid });b.close()
        }
    }

    @Test fun samePartNewAcceptedIdentityRetiresOldResultAndNextPage(): Unit = runBlocking {
        Harness().use { h -> val old=CompletableDeferred<Result<ReplyData>>();h.search={withContext(NonCancellable) {old.await()}}
            val a=h.binding();a.load();h.source=Any();h.search={Result.success(data(9,end=true))};val b=h.binding();b.load()
            old.complete(Result.success(data(4)));assertEquals(2,h.searchCalls.size)
            assertEquals(listOf(9L),h.vm.fullSearchReplies.value.map {it.rpid});a.close();assertTrue(h.vm.fullSearchState.value.isReady);b.close()
        }
    }

    @Test fun changedSubjectRejectsOldResultAndUsesNewTypedSubject(): Unit = runBlocking {
        Harness().use {h -> val old=CompletableDeferred<Result<ReplyData>>();h.search={withContext(NonCancellable) {old.await()}}
            val a=h.binding();a.load();h.vm.init(170002,84,CommentSortMode.HOT,0)
            h.search={Result.success(data(7,end=true))};val b=h.binding();b.load();old.complete(Result.success(data(4)))
            assertEquals(listOf(170001L,170002L),h.searchCalls.map {it.oid});assertEquals(listOf(7L),h.vm.fullSearchReplies.value.map {it.rpid});a.close();b.close()
        }
    }

    @Test fun accountRetirementRejectsLateResponseAndFuturePage(): Unit = runBlocking {
        Harness().use {h -> val response=CompletableDeferred<Result<ReplyData>>();h.search={response.await()}
            val binding=h.binding();binding.load();h.account=false;response.complete(Result.success(data(4)))
            assertEquals(1,h.searchCalls.size);assertFalse(h.vm.fullSearchState.value.isReady)
            assertTrue(h.vm.fullSearchReplies.value.isEmpty());binding.close()
        }
    }

    @Test fun originalSafetyCapIsRetainedWithoutClaimingUnboundedSearch(): Unit = runBlocking {
        Harness().use {h ->h.search={call->Result.success(data(call.page.toLong(),offset="page-${call.page}"))}
            val binding=h.binding();binding.load()
            assertEquals((1..299).toList(),h.searchCalls.map {it.page})
            assertEquals(299,h.vm.fullSearchReplies.value.size);binding.close()
        }
    }

    @Test fun blankOffsetOrEmptyBatchStopsWithoutSpeculativePage(): Unit = runBlocking {
        Harness().use {h ->h.search={Result.success(data(1,offset=""))};val binding=h.binding();binding.load()
            assertEquals(1,h.searchCalls.size);binding.close()
            h.source=Any();h.search={Result.success(ReplyData(replies=emptyList(),grpcNextOffset="unused"))}
            val second=h.binding();second.load();assertEquals(2,h.searchCalls.size);second.close()
        }
    }
    @Test fun rejectedInitialPresentationDoesNotStartIoOrPublishBusy(): Unit = runBlocking {
        Harness().use {h ->h.permit=false;val binding=h.binding();binding.load()
            assertTrue(h.searchCalls.isEmpty());assertFalse(h.vm.fullSearchState.value.isLoading);binding.close()
        }
    }
    @Test fun finalPresentationPermitRejectsReturnedPageAndNextRequest(): Unit = runBlocking {
        Harness().use {h ->val response=CompletableDeferred<Result<ReplyData>>();h.search={response.await()}
            val binding=h.binding();binding.load();h.permit=false;response.complete(Result.success(data(4)))
            assertEquals(1,h.searchCalls.size);assertFalse(h.vm.fullSearchState.value.isReady)
            assertTrue(h.vm.fullSearchReplies.value.isEmpty());binding.close()
        }
    }
}
