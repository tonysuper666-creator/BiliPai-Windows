package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.android.purebilibili.feature.audio.bgm.BgmComposerCallbacks
import com.android.purebilibili.feature.video.viewmodel.VideoCommentViewModel
import com.android.purebilibili.data.model.response.ReplyCursor
import com.android.purebilibili.data.model.response.ReplyData
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VIDEO_COMMENT_PAGE_SIZE
import com.android.purebilibili.feature.video.viewmodel.commentComposerDraftKey
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
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

    @Test fun composerDraftsStayIsolatedPerReplyTarget() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            vm.beginCommentDraft()
            val session = vm.commentState.value.composerDrafts.sessionId
            vm.updateCommentDraft(session, null, "root text", listOf("root.png"), true)
            vm.updateCommentDraft(session, 41L, "to 41", emptyList(), false)
            vm.updateCommentDraft(session, 42L, "", listOf("a.png", "b.png"), false)
            val drafts = vm.commentState.value.composerDrafts.drafts
            assertEquals(3, drafts.size)
            assertEquals(Triple("root text", listOf("root.png"), true),
                drafts[commentComposerDraftKey(null)]!!.let { Triple(it.text, it.imageUris, it.syncToDynamic) })
            assertEquals(Triple("to 41", emptyList<String>(), false),
                drafts[commentComposerDraftKey(41L)]!!.let { Triple(it.text, it.imageUris, it.syncToDynamic) })
            assertEquals(Triple("", listOf("a.png", "b.png"), false),
                drafts[commentComposerDraftKey(42L)]!!.let { Triple(it.text, it.imageUris, it.syncToDynamic) })
            vm.updateCommentDraft(session, 41L, "", emptyList(), false)
            val after = vm.commentState.value.composerDrafts.drafts
            assertNull(after[commentComposerDraftKey(41L)], "An emptied draft is removed")
            assertEquals("root text", after[commentComposerDraftKey(null)]?.text)
            assertEquals(listOf("a.png", "b.png"), after[commentComposerDraftKey(42L)]?.imageUris)
        }
    }

    @Test fun staleSessionUpdatesCannotPolluteTheNewDraft() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            vm.beginCommentDraft()
            val old = vm.commentState.value.composerDrafts.sessionId
            vm.updateCommentDraft(old, null, "old", emptyList(), false)
            vm.beginCommentDraft()
            val current = vm.commentState.value.composerDrafts.sessionId
            assertEquals(old + 1, current)
            val before = vm.commentState.value.composerDrafts
            vm.updateCommentDraft(old, null, "stale", listOf("stale.png"), true)
            vm.updateCommentDraft(old, 41L, "stale reply", emptyList(), false)
            assertEquals(before, vm.commentState.value.composerDrafts, "A superseded session is ignored entirely")
            vm.updateCommentDraft(current, null, "fresh", emptyList(), false)
            val drafts = vm.commentState.value.composerDrafts.drafts
            assertEquals("fresh", drafts[commentComposerDraftKey(null)]?.text)
            assertNull(drafts[commentComposerDraftKey(41L)])
        }
    }

    @Test fun delayedSendCompletionKeepsTheNewSessionDraftAndReplyTarget() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            vm.beginCommentDraft()
            val first = vm.commentState.value.composerDrafts.sessionId
            vm.replyTo(ReplyItem(rpid = 41))
            vm.updateCommentDraft(first, 41L, "sent text", emptyList(), false)
            f.first.holdAdd = true
            vm.sendComment("sent text")
            f.first.awaitAdd()
            assertTrue(vm.commentState.value.isSending)
            assertEquals(1, f.first.addCalls.size)
            vm.beginCommentDraft()
            val second = vm.commentState.value.composerDrafts.sessionId
            vm.replyTo(ReplyItem(rpid = 42))
            vm.updateCommentDraft(second, 41L, "newer 41", listOf("n.png"), true)
            vm.updateCommentDraft(second, 42L, "to 42", emptyList(), false)
            f.first.releaseAdd()
            f.frames()
            val state = vm.commentState.value
            assertFalse(state.isSending)
            assertEquals(second, state.composerDrafts.sessionId)
            assertEquals(first, state.composerDrafts.completedSessionId)
            assertNotEquals(second, state.composerDrafts.completedSessionId, "The new session is not marked complete")
            assertEquals(42L, state.replyTarget?.rpid, "The new reply target is not replaced")
            assertEquals("newer 41", state.composerDrafts.drafts[commentComposerDraftKey(41L)]?.text)
            assertEquals("to 42", state.composerDrafts.drafts[commentComposerDraftKey(42L)]?.text)
        }
    }

    @Test fun completedSendOfTheCurrentSessionClearsItsOwnDraftAndTarget() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            vm.beginCommentDraft()
            val session = vm.commentState.value.composerDrafts.sessionId
            vm.replyTo(ReplyItem(rpid = 41))
            vm.updateCommentDraft(session, 41L, "sent text", emptyList(), false)
            vm.updateCommentDraft(session, null, "other root draft", emptyList(), false)
            f.first.holdAdd = true
            vm.sendComment("sent text")
            f.first.awaitAdd()
            f.first.releaseAdd()
            f.frames()
            val state = vm.commentState.value
            assertEquals(session, state.composerDrafts.completedSessionId)
            assertNull(state.replyTarget)
            assertNull(state.composerDrafts.drafts[commentComposerDraftKey(41L)])
            assertEquals("other root draft", state.composerDrafts.drafts[commentComposerDraftKey(null)]?.text)
        }
    }

    private class ComposerHarness(val vm: VideoCommentViewModel) {
        var open = true
        val submitted = mutableListOf<Long>()
        fun callbacks(session: Long, reply: Long?) =
            BgmComposerCallbacks(vm, session, reply, { open }, { open = false }, { submitted += it })
        fun session() = vm.commentState.value.composerDrafts.sessionId
    }

    @Test fun oldSessionDismissCannotChangeTheNewSessionVisibilityOrReplyTarget() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            val h = ComposerHarness(vm)
            vm.beginCommentDraft()
            val old = h.callbacks(h.session(), null)
            vm.beginCommentDraft()
            val current = h.callbacks(h.session(), 42L)
            vm.replyTo(ReplyItem(rpid = 42))
            old.onDismiss()
            assertTrue(h.open, "An older session cannot close the new composer")
            assertEquals(42L, vm.commentState.value.replyTarget?.rpid, "or drop its reply target")
            current.onDismiss()
            assertFalse(h.open)
            assertNull(vm.commentState.value.replyTarget)
        }
    }

    @Test fun oldSessionSendMakesNoRequestAndTheCurrentSessionSends() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            val h = ComposerHarness(vm)
            vm.beginCommentDraft()
            val old = h.callbacks(h.session(), null)
            vm.beginCommentDraft()
            val second = h.session()
            val current = h.callbacks(second, null)
            old.onSend("stale", emptyList(), false)
            assertFalse(vm.commentState.value.isSending)
            assertTrue(h.submitted.isEmpty())
            assertEquals(0, f.first.addCalls.size)
            f.first.holdAdd = true
            current.onSend("fresh", emptyList(), true)
            f.first.awaitAdd()
            assertEquals(listOf(second), h.submitted)
            assertEquals(1, f.first.addCalls.size)
            assertTrue(vm.commentState.value.isSending)
            val draft = vm.commentState.value.composerDrafts.drafts.values.single()
            assertEquals(Triple("fresh", emptyList<String>(), true), Triple(draft.text, draft.imageUris, draft.syncToDynamic),
                "The send first publishes the current session draft")
            f.first.releaseAdd()
            f.frames()
        }
    }

    @Test fun sendIsRejectedWhileBusyAndAfterTheSessionCompleted() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            val h = ComposerHarness(vm)
            vm.beginCommentDraft()
            val cb = h.callbacks(h.session(), null)
            f.first.holdAdd = true
            cb.onSend("one", emptyList(), false)
            f.first.awaitAdd()
            cb.onSend("two", emptyList(), false)
            assertEquals(1, f.first.addCalls.size, "A second send while busy makes no request")
            assertEquals(1, h.submitted.size)
            f.first.releaseAdd()
            f.frames()
            assertEquals(h.session(), vm.commentState.value.composerDrafts.completedSessionId)
            h.open = true
            cb.onSend("three", emptyList(), false)
            assertFalse(vm.commentState.value.isSending)
            assertEquals(1, f.first.addCalls.size, "A completed session cannot send again")
            assertEquals(1, h.submitted.size)
        }
    }

    @Test fun sendIsRejectedWhenTheReplyTargetChangedOrTheComposerClosed() = runTest {
        Fixture(testScheduler).use { f ->
            val vm = f.page.comments
            vm.init(aid = 771, commentType = 17)
            f.frames()
            val h = ComposerHarness(vm)
            vm.beginCommentDraft()
            vm.replyTo(ReplyItem(rpid = 41))
            val cb = h.callbacks(h.session(), 41L)
            vm.replyTo(ReplyItem(rpid = 42))
            cb.onSend("wrong target", emptyList(), false)
            vm.cancelReply()
            cb.onSend("no target", emptyList(), false)
            vm.replyTo(ReplyItem(rpid = 41))
            h.open = false
            cb.onSend("closed", emptyList(), false)
            assertFalse(vm.commentState.value.isSending)
            assertTrue(h.submitted.isEmpty())
            assertEquals(0, f.first.addCalls.size)
            h.open = true
            f.first.holdAdd = true
            cb.onSend("right", emptyList(), false)
            f.first.awaitAdd()
            assertEquals(1, f.first.addCalls.size, "Same session, target and open composer sends")
            f.first.releaseAdd()
            f.frames()
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
            val addCalls = java.util.concurrent.CopyOnWriteArrayList<List<Any?>>()
            var holdAdd = false
            @Volatile private var held: Continuation<Any?>? = null
            /** The VM uploads pictures on Dispatchers.IO first, so the request arrives on a real thread. */
            fun awaitAdd() {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (held == null && System.nanoTime() < deadline) Thread.sleep(5)
                checkNotNull(held) { "The send request never reached the requests owner" }
            }
            fun releaseAdd() { held!!.also { held = null }.resume(Result.success<ReplyItem?>(ReplyItem())) }
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
                    "addCommentForSubject" -> {
                        check(owned)
                        addCalls += args!!.dropLast(1)
                        if (holdAdd) {
                            @Suppress("UNCHECKED_CAST")
                            held = args.last() as Continuation<Any?>
                            COROUTINE_SUSPENDED
                        } else ReplyItem()
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
