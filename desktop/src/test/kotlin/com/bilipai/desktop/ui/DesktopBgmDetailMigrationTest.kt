package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.android.purebilibili.data.model.response.ReplyCursor
import com.android.purebilibili.data.model.response.ReplyData
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VIDEO_COMMENT_PAGE_SIZE
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import kotlin.test.*

/** Current production Root state and generic comment VM; no window, HTTP, account or audio session. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBgmDetailMigrationTest {
    @Test fun sameOwnerRecompositionRetainsSubjectReplyAndNestedRoute() = runTest {
        Fixture(testScheduler).use { f ->
            val page = f.page
            page.comments.init(aid = 771, commentType = 17)
            f.frames()
            page.comments.replyTo(ReplyItem(rpid = 41))
            page.openComment(771, 41, 42, 17)
            val route = page.route
            f.tick.value++
            f.frames()
            assertSame(page, f.page)
            f.page.comments.init(aid = 771, commentType = 17)
            f.frames()
            assertEquals(listOf(771L to 17), f.first.reads)
            assertEquals(41L, f.page.comments.commentState.value.replyTarget?.rpid)
            assertEquals(route, f.page.route)
            f.page.closeComment()
            assertNull(f.page.route)
        }
    }

    @Test fun replacingRequestsWithTheSameOperationsRebindsModelsScopeAndRoute() = runTest {
        Fixture(testScheduler).use { f ->
            val previous = f.page
            previous.comments.init(aid = 771, commentType = 17)
            previous.openComment(771, 41, 42, 17)
            f.frames()
            val replacement = f.owner()
            assertSame(f.first.root.operations, replacement.root.operations)
            f.current.value = replacement.root
            f.frames()
            assertNotSame(previous.detail, f.page.detail)
            assertNotSame(previous.comments, f.page.comments)
            assertNotSame(previous.detailRequests, f.page.detailRequests)
            assertNull(f.page.route, "A new request owner must not inherit a nested comment route")
            f.first.retire()
            f.page.comments.init(aid = 881, commentType = 17)
            f.frames()
            f.page.comments.refreshComments()
            f.frames()
            assertEquals(listOf(771L to 17), f.first.reads)
            assertEquals(listOf(881L to 17, 881L to 17), replacement.reads,
                "The current VM must use the replacement requests and live scope")
        }
    }

    @Test fun retainedNestedNavigationCallbacksCannotWriteAfterOwnerRetirement() = runTest {
        Fixture(testScheduler).use { f ->
            val previous = f.page
            previous.openComment(771, 41, 42, 17)
            val route = previous.route
            val retainedBack = previous::closeComment
            val retainedOpen = { previous.openComment(771, 99, 100, 17) }
            f.first.retire()
            val replacement = f.owner()
            f.current.value = replacement.root
            f.frames()
            f.page.openComment(881, 51, 52, 17)
            retainedBack()
            retainedOpen()
            assertEquals(route, previous.route, "Retired callbacks cannot publish even to the old route")
            assertEquals(DesktopBgmCommentRoute(881, 51, 52, 17), f.page.route)
            f.page.closeComment()
            assertNull(f.page.route, "The current owner's back action still works")
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Fixture(private val scheduler: TestCoroutineScheduler) : AutoCloseable {
        private val errors = mutableListOf<Throwable>()
        private val compositionScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        private val repository = DesktopRepository(DesktopSessionStore.temporary())
        private val operations = DesktopDynamicCardOperations(repository)
        private val owners = mutableListOf<Owner>()
        val first = owner()
        val current = mutableStateOf(first.root)
        val tick = mutableStateOf(0)
        lateinit var page: DesktopBgmDetailState
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(compositionScope.coroutineContext + clock)
        private val runner = compositionScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        private val composition = Composition(EmptyApplier(), recomposer)

        init {
            composition.setContent {
                tick.value
                page = rememberDesktopBgmDetailState(current.value)
            }
            frames()
        }

        inner class Owner {
            var owned = true
            val reads = mutableListOf<Pair<Long, Int>>()
            private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
                CoroutineExceptionHandler { _, failure -> errors += failure })
            private val requests = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, args ->
                when (method.name.substringBefore('-')) {
                    "isOwned" -> owned
                    "currentMid" -> 111L
                    "getCommentsForSubject" -> {
                        check(owned)
                        val values = args!!.dropLast(1)
                        assertEquals(listOf(1, VIDEO_COMMENT_PAGE_SIZE), values.drop(2).take(2))
                        reads += (values[0] as Long) to (values[1] as Int)
                        ReplyData(replies = emptyList(), cursor = ReplyCursor(isEnd = true))
                    }
                    else -> error("Unexpected comment request ${method.name}")
                }
            } as DesktopVideoCommentRequests
            val root = DesktopOriginalCommentRootOwner(operations, requests, scope, operations.emotes,
                { owned }, { error("Unexpected feedback: $it") })

            fun retire() { owned = false; scope.cancel() }
        }

        fun owner() = Owner().also(owners::add)

        fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                scheduler.runCurrent()
                clock.sendFrame(scheduler.currentTime * 1_000_000)
                scheduler.runCurrent()
                scheduler.advanceTimeBy(20)
            }
            assertTrue(errors.isEmpty(), errors.toString())
        }

        override fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancel()
            compositionScope.cancel()
            owners.forEach(Owner::retire)
            repository.httpClient.dispatcher.cancelAll()
            repository.httpClient.connectionPool.evictAll()
            scheduler.runCurrent()
        }
    }
}
