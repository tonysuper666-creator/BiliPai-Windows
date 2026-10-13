package com.bilipai.desktop.data

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.bilipai.desktop.ui.DesktopDynamicReplyRequests
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Exercises the mounted original consumer and real SessionStore/cache binding.
 * Only remote responses and the coroutine dispatcher are fixtures. */
class DesktopDynamicCommentCacheConsumptionTest {
    private data class Read(val oid: Long, val type: Int, val page: Int, val size: Int, val mode: Int)
    private data class Submission(val root: Long, val parent: Long)

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("bp-dynamic-comment-consumer-")
        val sessions = DesktopSessionStore(root.resolve("fixture-session.json"), persistent = false)
        val cache = DesktopDynamicCommentCacheStore(sessions)
        init { login("synthetic-first-generation") }
        fun login(credential: String) = sessions.saveAccount(
            mapOf("SESSDATA" to credential), AccountSummary(111, "Fixture", ""))
        override fun close() { root.toFile().deleteRecursively() }
    }

    private class Harness(
        val fixture: Fixture,
        val item: DynamicItem = dynamic(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) : AutoCloseable {
        val alive = AtomicBoolean(true)
        val epoch = fixture.sessions.generation
        val failures = CopyOnWriteArrayList<Throwable>()
        val reads = CopyOnWriteArrayList<Read>()
        val counts = CopyOnWriteArrayList<Pair<Long, Int>>()
        val submissions = CopyOnWriteArrayList<Submission>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, failure -> failures += failure })
        fun owned() = alive.get() && scope.isActive && fixture.sessions.generation == epoch
        private val pageLock = Any()
        val binding = checkNotNull(fixture.cache.bind(::owned) { action -> synchronized(pageLock) {
            if (owned()) { action(); true } else false
        } })
        var read: suspend (Read) -> Result<ReplyData> = { Result.success(data(1, it.page)) }
        var count: suspend (Long, Int) -> Result<Int> = { _, _ -> Result.success(3) }
        val requests = object : DesktopDynamicReplyRequests {
            override fun isOwned() = owned()
            override fun hasCsrf() = owned()
            override suspend fun getDynamicDetail(id: String) = error("No detail fallback expected")
            override suspend fun getCommentCountForSubject(oid: Long, type: Int): Result<Int> {
                counts += oid to type
                return count(oid, type)
            }
            override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int,
                mode: Int, paginationOffset: String?, fallbackOnMissingLocation: Boolean): Result<ReplyData> {
                val request = Read(oid, type, page, ps, mode)
                reads += request
                return read(request)
            }
            override suspend fun getSortedSubCommentsForSubject(oid: Long, type: Int, rootId: Long,
                mode: Int, paginationOffset: String?, targetReplyId: Long) = error("No thread read expected")
            override suspend fun uploadCommentPicture(source: String, index: Int) = error("No image upload expected")
            override suspend fun addCommentForSubject(oid: Long, type: Int, message: String,
                root: Long, parent: Long, pictures: List<ReplyPicture>): Result<ReplyItem?> {
                submissions += Submission(root, parent)
                return Result.success(ReplyItem(rpid = 99))
            }
            override suspend fun likeCommentForSubject(oid: Long, type: Int, rpid: Long, like: Boolean) = error("No like expected")
            override suspend fun hateCommentForSubject(oid: Long, type: Int, rpid: Long, hate: Boolean) = error("No hate expected")
            override suspend fun deleteCommentForSubject(oid: Long, type: Int, rpid: Long) = error("No deletion expected")
            override suspend fun setCommentTopForSubject(oid: Long, type: Int, rpid: Long, isCurrentlyTop: Boolean) = error("No pin expected")
            override suspend fun reportCommentForSubject(oid: Long, type: Int, rpid: Long, reason: Int) = error("No report expected")
        }
        val vm = DesktopOriginalDynamicReplySession(item.id_str, scope, requests, binding,
            seedItem = { item }, stillOwned = ::owned)
        fun ids() = vm.commentUiState.value.comments.map { it.rpid }
        override fun close() {
            synchronized(pageLock) { alive.set(false) }
            vm.close(); scope.cancel()
            assertTrue(failures.isEmpty(), failures.joinToString { it::class.simpleName.orEmpty() })
        }
    }

    @Test fun reenterUsesTheRepositoryCacheBeforeRefreshAndThenPublishesOneCompleteState(): Unit = runBlocking {
        Fixture().use { fixture ->
            Harness(fixture).use { first ->
                first.vm.openCommentSheet(first.item)
                assertEquals(listOf(1L), first.ids())
                assertEquals(1L, first.vm.commentConfirmationRevision.value)
            }
            Harness(fixture).use { next ->
                val pending = CompletableDeferred<Result<ReplyData>>()
                next.read = { pending.await() }
                next.vm.openCommentSheet(next.item)
                assertEquals(listOf(1L), next.ids())
                assertFalse(next.vm.commentUiState.value.isLoading)
                pending.complete(Result.success(data(2)))
                assertEquals(listOf(2L), next.ids())
                assertEquals(3, next.vm.commentUiState.value.totalCount)
                assertFalse(next.vm.commentUiState.value.isLoading)
                assertEquals(next.item.id_str, next.vm.commentUiState.value.dynamicId)
                assertTrue(next.reads.all { it.size == 30 })
            }
        }
    }

    @Test fun manualRefreshFailureKeepsTheVisibleRepliesAndNextPage(): Unit = runBlocking {
        Fixture().use { fixture -> Harness(fixture).use { h ->
            h.vm.openCommentSheet(h.item)
            h.read = { Result.failure(IOException("fixture refresh failure")) }
            h.count = { _, _ -> Result.failure(IOException("fixture count failure")) }
            h.vm.refreshComments()
            assertEquals(listOf(1L), h.ids())
            assertEquals("fixture refresh failure", h.vm.commentUiState.value.refreshError)
            assertFalse(h.vm.commentUiState.value.isRefreshing)
            h.read = { Result.success(data(2, it.page)) }
            h.vm.loadMoreComments()
            assertEquals(2, h.reads.last().page)
            assertEquals(listOf(1L, 2L), h.ids())
            assertFalse(h.vm.commentUiState.value.isLoadingMore)
        } }
    }

    @Test fun candidateListsAndCountsStartTogetherAndTheUsablePrimaryCancelsTheFallback(): Unit = runBlocking {
        Fixture().use { fixture -> Harness(fixture, dynamic(opus = true)).use { h ->
            val primary = CompletableDeferred<Result<ReplyData>>()
            val fallback = CompletableDeferred<Result<ReplyData>>()
            val primaryCount = CompletableDeferred<Result<Int>>()
            val fallbackCount = CompletableDeferred<Result<Int>>()
            val fallbackCancelled = AtomicBoolean(false)
            h.count = { _, type -> if (type == 11) primaryCount.await() else fallbackCount.await() }
            h.read = { request -> if (request.type == 11) primary.await() else try {
                fallback.await()
            } finally { fallbackCancelled.set(true) } }
            h.vm.openCommentSheet(h.item)
            assertEquals(setOf(11, 17), h.reads.map { it.type }.toSet())
            assertEquals(setOf(11, 17), h.counts.map { it.second }.toSet())
            assertTrue(h.vm.commentUiState.value.isLoading)
            primaryCount.complete(Result.success(3))
            primary.complete(Result.success(data(7)))
            assertEquals(listOf(7L), h.ids())
            assertTrue(fallbackCancelled.get())
            assertFalse(h.vm.commentUiState.value.isLoading)
        } }
    }

    @Test fun hotAndNewestRestoreTheirOwnCachedReplies(): Unit = runBlocking {
        Fixture().use { fixture -> Harness(fixture).use { h ->
            h.read = { Result.success(data(if (it.mode == CommentSortMode.HOT.apiMode) 1L else 2L)) }
            h.vm.openCommentSheet(h.item)
            h.vm.setDynamicCommentSortMode(CommentSortMode.NEWEST)
            assertEquals(listOf(2L), h.ids())
            val pending = CompletableDeferred<Result<ReplyData>>()
            h.read = { pending.await() }
            h.vm.setDynamicCommentSortMode(CommentSortMode.HOT)
            assertEquals(listOf(1L), h.ids())
            assertEquals(CommentSortMode.HOT.apiMode, h.reads.last().mode)
            pending.complete(Result.success(data(3)))
            assertEquals(listOf(3L), h.ids())
        } }
    }

    @Test fun renewedCredentialsForTheSameAccountRejectOldBindingsAndClearTheOriginalCache(): Unit = runBlocking {
        Fixture().use { fixture -> Harness(fixture).use { old ->
            old.vm.openCommentSheet(old.item)
            fixture.login("synthetic-replacement-generation")
            assertFailsWith<CancellationException> { old.binding.get(old.item.id_str, CommentSortMode.HOT.apiMode) }
            Harness(fixture).use { current ->
                val pending = CompletableDeferred<Result<ReplyData>>()
                current.read = { pending.await() }
                current.vm.openCommentSheet(current.item)
                assertTrue(current.ids().isEmpty())
                pending.complete(Result.success(data(8)))
                assertEquals(listOf(8L), current.ids())
            }
        } }
    }

    @Test fun retiredPageCannotPublishALateResponseOrPoisonTheNextPagesCache(): Unit = runBlocking {
        Fixture().use { fixture ->
            Harness(fixture).use { old ->
                val pending = CompletableDeferred<Result<ReplyData>>()
                old.read = { pending.await() }
                old.vm.openCommentSheet(old.item)
                old.alive.set(false)
                assertNull(fixture.cache.bind(old::owned) { action -> action(); true })
                pending.complete(Result.success(data(88)))
                assertTrue(old.ids().isEmpty())
            }
            Harness(fixture).use { next ->
                val pending = CompletableDeferred<Result<ReplyData>>()
                next.read = { pending.await() }
                next.vm.openCommentSheet(next.item)
                assertTrue(next.ids().isEmpty())
                pending.complete(Result.success(data(9)))
                assertEquals(listOf(9L), next.ids())
            }
        }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun drain() {
            var runs = 0
            while (true) {
                val next = queue.poll() ?: break
                check(++runs < 1000); next.run()
            }
        }
        suspend fun drainUntil(completed: () -> Boolean) = withTimeout(5_000) {
            while (!completed()) { drain(); delay(1) }
            drain()
        }
    }

    @Test fun queuedSubmissionKeepsTheAdmittedReplyTargetAfterTheComposerClearsIt(): Unit = runBlocking {
        Fixture().use { fixture ->
            val dispatcher = QueueDispatcher()
            Harness(fixture, dispatcher = dispatcher).use { h ->
                h.vm.openCommentSheet(h.item); dispatcher.drain()
                h.vm.startCommentReply(ReplyItem(rpid = 30, mid = 333))
                val admitted = checkNotNull(h.vm.commentReplyTarget.value)
                var delivered: Pair<Boolean, String>? = null
                h.vm.postComment(h.item.id_str, "fixture reply", emptyList()) { success, message -> delivered = success to message }
                h.vm.clearCommentReplyTarget()
                dispatcher.drainUntil { delivered != null }
                assertEquals(listOf(Submission(admitted.rootRpid, admitted.parentRpid)), h.submissions)
                assertEquals(true, delivered?.first, delivered?.second)
                assertNull(h.vm.commentReplyTarget.value)
            }
            dispatcher.drain()
        }
    }

    companion object {
        private fun dynamic(opus: Boolean = false): DynamicItem = Json.decodeFromString(
            if (opus) """{"id_str":"1001","type":"DYNAMIC_TYPE_DRAW","basic":{"comment_id_str":"2001","comment_type":11},"modules":{"module_dynamic":{"major":{"type":"MAJOR_TYPE_OPUS"}}}}"""
            else """{"id_str":"1001","type":"DYNAMIC_TYPE_WORD","modules":{}}""")
        private fun data(id: Long, page: Int = 1) = ReplyData(
            replies = listOf(ReplyItem(rpid = id, content = ReplyContent(message = "fixture $id"))),
            page = ReplyPage(num = page, size = 30, count = 3, acount = 3),
            cursor = ReplyCursor(allCount = 3, isEnd = false, next = page + 1),
        )
    }
}
