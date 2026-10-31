package com.android.purebilibili.feature.video.viewmodel

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Uses the mounted original VM and original shared cache; only requests are fixtures. */
class DesktopVideoCommentCacheConsumptionTest {
    @BeforeEach fun resetCache() = sharedVideoCommentCache.clear()
    @AfterEach fun clearCache() = sharedVideoCommentCache.clear()

    private data class Read(val oid: Long, val type: Int, val page: Int, val size: Int, val mode: Int)

    private class Harness(val mid: Long = 111L) : AutoCloseable {
        val owned = AtomicBoolean(true)
        val failures = CopyOnWriteArrayList<Throwable>()
        val reads = CopyOnWriteArrayList<Read>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined +
            CoroutineExceptionHandler { _, error -> failures += error })
        var read: suspend (Read) -> Result<ReplyData> = { Result.success(data(1)) }
        val requests = object : DesktopVideoCommentRequests {
            override fun isOwned() = owned.get() && scope.isActive
            override fun currentMid() = mid
            override suspend fun getMobileCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,continuation:com.android.purebilibili.data.model.response.CommentMainListContinuation?,fallbackOnMissingLocation:Boolean):Result<com.android.purebilibili.data.model.response.ReplyData> = error("Mobile paging is not expected in this existing fixture")
            override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int,
                mode: Int, paginationOffset: String?, fallbackOnMissingLocation: Boolean): Result<ReplyData> {
                val request = Read(oid, type, page, ps, mode)
                reads += request
                return read(request)
            }
            override suspend fun getSortedSubCommentsForSubject(oid: Long, type: Int, rootId: Long,
                mode: Int, paginationOffset: String?, targetReplyId: Long) = error("No thread read")
            override suspend fun getDialogCommentsForSubject(oid: Long, type: Int, rootId: Long,
                dialogId: Long, page: Int, paginationOffset: String?) = error("No dialog read")
            override suspend fun uploadCommentPicture(source: String, index: Int) = error("No upload")
            override suspend fun addCommentForSubject(oid: Long, type: Int, message: String, root: Long,
                parent: Long, pictures: List<ReplyPicture>, syncToDynamic: Boolean) = error("No write")
            override suspend fun likeCommentForSubject(oid: Long, type: Int, rpid: Long, like: Boolean) = error("No reaction")
            override suspend fun hateCommentForSubject(oid: Long, type: Int, rpid: Long, hate: Boolean) = error("No reaction")
            override suspend fun deleteCommentForSubject(oid: Long, type: Int, rpid: Long) = error("No deletion")
            override suspend fun setCommentTopForSubject(oid: Long, type: Int, rpid: Long, isCurrentlyTop: Boolean) = error("No pin")
            override suspend fun reportCommentForSubject(oid: Long, type: Int, rpid: Long, reason: Int, content: String) = error("No report")
            override suspend fun checkCommentStatus(aid: Long, rpid: Long, rootId: Long,
                hasPictures: Boolean, sentAtSeconds: Long, waitMs: Long) = error("No fraud request")
            override suspend fun saveFraudRecord(rpid: Long, oid: Long, type: Int, root: Long,
                message: String, status: CommentFraudStatus, initialStatus: CommentFraudStatus?) = error("No persistence")
        }
        val vm = VideoCommentViewModel(scope, requests)
        fun ids() = vm.commentState.value.replies.map { it.rpid }
        override fun close() { scope.cancel(); assertTrue(failures.isEmpty(), failures.toString()) }
    }

    @Test fun reentryShowsCachedFirstPageBeforeNetworkThenReplacesIt(): Unit = runBlocking {
        Harness().use { first ->
            first.vm.init(170001)
            assertEquals(listOf(1L), first.ids())
        }
        Harness().use { next ->
            val pending = CompletableDeferred<Result<ReplyData>>()
            next.read = { pending.await() }
            next.vm.init(170001)
            assertEquals(listOf(1L), next.ids())
            assertTrue(next.vm.commentState.value.isRepliesLoading)
            assertEquals(30, next.reads.single().size)
            pending.complete(Result.success(data(2)))
            assertEquals(listOf(2L), next.ids())
            assertFalse(next.vm.commentState.value.isRepliesLoading)
        }
    }

    @Test fun failedWarmRevalidationPreservesContentAndNextPage(): Unit = runBlocking {
        Harness().use { it.vm.init(170001) }
        Harness().use { next ->
            next.read = { Result.failure(IOException("fixture unavailable")) }
            next.vm.init(170001)
            assertEquals(listOf(1L), next.ids())
            assertNull(next.vm.commentState.value.repliesError)
            assertFalse(next.vm.commentState.value.isRepliesEnd)
            assertEquals(2, next.vm.commentState.value.nextPage)
            next.read = { Result.success(data(2, page = 2)) }
            next.vm.loadComments()
            assertEquals(2, next.reads.last().page)
            assertEquals(listOf(1L, 2L), next.ids())
        }
    }

    @Test fun sortBucketsRestoreTheirOwnCachedReplies(): Unit = runBlocking {
        Harness().use { hot -> hot.vm.init(170001, preferredSortMode = CommentSortMode.HOT) }
        Harness().use { time ->
            time.read = { Result.success(data(2)) }
            time.vm.init(170001, preferredSortMode = CommentSortMode.NEWEST)
            assertEquals(listOf(2L), time.ids())
        }
        Harness().use { next ->
            val pending = CompletableDeferred<Result<ReplyData>>()
            next.read = { pending.await() }
            next.vm.init(170001, preferredSortMode = CommentSortMode.HOT)
            assertEquals(listOf(1L), next.ids())
            next.vm.setSortMode(CommentSortMode.NEWEST)
            assertEquals(listOf(2L), next.ids())
            assertEquals(CommentSortMode.NEWEST.apiMode, next.reads.last().mode)
        }
    }

    @Test fun anotherViewingAccountCannotRestoreThePreviousAccountsReplies(): Unit = runBlocking {
        Harness(111).use { it.vm.init(170001) }
        Harness(222).use { next ->
            val pending = CompletableDeferred<Result<ReplyData>>()
            next.read = { pending.await() }
            next.vm.init(170001)
            assertTrue(next.ids().isEmpty())
            assertEquals(222L, next.vm.commentState.value.currentMid)
            pending.complete(Result.success(data(2)))
            assertEquals(listOf(2L), next.ids())
        }
        assertEquals(listOf(1L), sharedVideoCommentCache.get(videoCommentCacheKey(170001, 1, 3, 111))?.replies?.map { it.rpid })
        assertEquals(listOf(2L), sharedVideoCommentCache.get(videoCommentCacheKey(170001, 1, 3, 222))?.replies?.map { it.rpid })
    }

    @Test fun retiredOwnerCannotPublishLateNetworkDataIntoTheSharedCache(): Unit = runBlocking {
        Harness().use { retired ->
            val pending = CompletableDeferred<Result<ReplyData>>()
            retired.read = { pending.await() }
            retired.vm.init(170001)
            assertEquals(1, retired.reads.size)
            retired.owned.set(false)
            pending.complete(Result.success(data(99)))
            assertTrue(retired.ids().isEmpty())
            assertEquals(0, sharedVideoCommentCache.size())
        }
    }

    companion object {
        private fun data(id: Long, page: Int = 1) = ReplyData(
            replies = listOf(ReplyItem(rpid = id, content = ReplyContent(message = "fixture $id"))),
            page = ReplyPage(num = page, size = 30, count = 3, acount = 3),
            cursor = ReplyCursor(allCount = 3, isEnd = false, next = page + 1),
        )
    }
}
