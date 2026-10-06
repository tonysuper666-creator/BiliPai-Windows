package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Actual sole generated VM -> real UI dispatch -> required request port.
 * All transport is synthetic; no second production VM or account is introduced. */
class DesktopWindowsVideoCommentActionsTest {
    private data class Call(val oid: Long, val type: Int, val page: Int, val ps: Int, val mode: Int, val offset: String?)
    private data class SubCall(val oid: Long, val type: Int, val root: Long, val target: Long)
    private class Harness(initialAid: Long = 170001L) : AutoCloseable {
        var account = true
        var permit = true
        var source: Any = Any()
        val calls = mutableListOf<Call>()
        val actions = mutableListOf<String>()
        val subCalls = mutableListOf<SubCall>()
        var subResult: suspend (SubCall) -> Result<ReplyData> = { call ->
            Result.success(data(99, end=true).copy(root=ReplyItem(rpid=call.root, oid=call.oid)))
        }
        var likeResult: suspend () -> Result<Unit> = { Result.success(Unit) }
        var search: suspend (Call) -> Result<ReplyData> = { Result.success(data(1, end = true)) }
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure -> failures += failure })
        val requests = object : DesktopVideoCommentRequests {
            override fun isOwned() = account
            override fun currentMid() = 111L
            override suspend fun getCommentsForSubject(oid: Long, type: Int, page: Int, ps: Int, mode: Int,
                paginationOffset: String?, fallbackOnMissingLocation: Boolean): Result<ReplyData> {
                val call = Call(oid, type, page, ps, mode, paginationOffset)
                calls += call
                return if (mode == 2) search(call) else Result.success(data(90, end = true))
            }
            override suspend fun getSortedSubCommentsForSubject(oid: Long,type: Int,rootId: Long,mode: Int,paginationOffset: String?,targetReplyId: Long): Result<ReplyData> {
                val call=SubCall(oid,type,rootId,targetReplyId);subCalls+=call
                return subResult(call)
            }
            override suspend fun getDialogCommentsForSubject(oid: Long,type: Int,rootId: Long,dialogId: Long,page: Int,paginationOffset: String?) = Result.success(data(98,end=true))
            override suspend fun uploadCommentPicture(source: String,index: Int): Result<ReplyPicture> = error("No mutation expected")
            override suspend fun addCommentForSubject(oid: Long,type: Int,message: String,root: Long,parent: Long,pictures: List<ReplyPicture>,syncToDynamic: Boolean): Result<ReplyItem?> = error("No mutation expected")
            override suspend fun likeCommentForSubject(oid: Long,type: Int,rpid: Long,like: Boolean): Result<Unit> { actions += "like:$oid:$type:$rpid:$like"; return likeResult() }
            override suspend fun hateCommentForSubject(oid: Long,type: Int,rpid: Long,hate: Boolean): Result<Unit> { actions += "hate:$oid:$type:$rpid:$hate"; return Result.success(Unit) }
            override suspend fun deleteCommentForSubject(oid: Long,type: Int,rpid: Long): Result<Unit> = error("No mutation expected")
            override suspend fun setCommentTopForSubject(oid: Long,type: Int,rpid: Long,isCurrentlyTop: Boolean): Result<Unit> = error("No mutation expected")
            override suspend fun reportCommentForSubject(oid: Long,type: Int,rpid: Long,reason: Int,content: String): Result<Unit> { actions += "report:$oid:$type:$rpid:$reason"; return Result.success(Unit) }
            override suspend fun checkCommentStatus(aid: Long,rpid: Long,rootId: Long,hasPictures: Boolean,sentAtSeconds: Long,waitMs: Long): Result<CommentFraudStatus> = error("No mutation expected")
            override suspend fun saveFraudRecord(rpid: Long,oid: Long,type: Int,root: Long,message: String,status: CommentFraudStatus,initialStatus: CommentFraudStatus?) = error("No mutation expected")
        }
        val vm = VideoCommentViewModel(scope, requests).also { if(initialAid>0L) it.init(initialAid,42L,CommentSortMode.HOT,0) }
        fun presentation(captured: Any = source) = DesktopWindowsCommentPresentation(captured, null,
            { account && source === captured }, { action -> if (permit && account && source === captured) { action(); true } else false })
        fun ui(presentation: DesktopWindowsCommentPresentation) = DesktopWindowsVideoCommentActions(vm, presentation)
        val searchCalls get() = calls.filter { it.mode == 2 }
        override fun close() { scope.cancel() }
    }
    companion object {
        private fun data(id: Long, end: Boolean = false, offset: String = "next", tops: List<ReplyItem>? = null) = ReplyData(
            cursor = ReplyCursor(allCount=5,isEnd=end),replies=listOf(ReplyItem(rpid=id)),grpcNextOffset=offset,topReplies=tops)
    }


    @Test fun rootAndNestedActionsUseSameGeneratedVmAndTypedSubject(): Unit = runBlocking {
        Harness().use { h -> val p=h.presentation(); val ui=h.ui(p)
            assertSame(h.vm,ui.viewModel)
            ui.like(90); ui.hate(90); ui.report(90,2)
            assertEquals(listOf("like:170001:1:90:true","hate:170001:1:90:true","report:170001:1:90:2"),h.actions)
            ui.thread(ReplyItem(rpid=90,oid=170001),99)
            assertTrue(h.vm.subReplyState.value.visible)
            assertEquals(90L,h.vm.subReplyState.value.rootReply?.rpid)
            assertEquals(99L,h.vm.subReplyState.value.targetReplyId)
            ui.reply(ReplyItem(rpid=99,oid=170001))
            assertEquals(99L,h.vm.commentState.value.replyTarget?.rpid)
            ui.closeThread(); assertFalse(h.vm.subReplyState.value.visible)
            assertTrue(h.failures.isEmpty(),h.failures.toString()); p.close()
        }
    }
    @Test fun originalSortRefreshAndThreadRequestsStayOnBorrowedOwner(): Unit = runBlocking {
        Harness().use {h -> val p=h.presentation();val ui=h.ui(p)
            ui.sort(CommentSortMode.NEWEST);ui.refresh();assertEquals(CommentSortMode.NEWEST,h.vm.commentState.value.sortMode)
            assertTrue(h.calls.any { it.oid==170001L && it.type==1 && it.mode==2 })
            ui.thread(ReplyItem(rpid=90,oid=170001),0);ui.refreshThread();ui.closeThread()
            p.close();assertTrue(h.scope.isActive);h.vm.refreshComments()
            assertTrue(h.calls.size>=3);assertTrue(h.failures.isEmpty(),h.failures.toString())
        }
    }
    @Test fun samePartNewSourceRejectsOldUiWithoutCancelingMainComments(): Unit = runBlocking {
        Harness().use {h -> val p=h.presentation();val old=h.ui(p);h.source=Any()
            assertFalse(old.like(90));assertFalse(old.thread(ReplyItem(rpid=90),0));assertTrue(h.actions.isEmpty())
            val next=h.presentation();assertTrue(h.ui(next).like(90));p.close()
            assertTrue(next.isCurrent());assertTrue(h.scope.isActive);next.close()
        }
    }
    @Test fun accountOrFinalAdmissionRetirementRejectsNewMutation(): Unit = runBlocking {
        Harness().use {h -> val p=h.presentation();val ui=h.ui(p)
            h.permit=false;assertFalse(ui.report(90,2));assertTrue(h.actions.isEmpty())
            h.permit=true;h.account=false;assertFalse(ui.like(90));assertTrue(h.actions.isEmpty());p.close()
        }
    }
    @Test fun alreadyDispatchedSameAidLikeKeepsOriginalSubjectCompletion(): Unit = runBlocking {
        Harness().use {h -> val result=CompletableDeferred<Result<Unit>>();h.likeResult={result.await()}
            val p=h.presentation();val ui=h.ui(p);assertTrue(ui.like(90));h.source=Any();p.close()
            result.complete(Result.success(Unit))
            // Root explicitly preserves this original already-authorized same-AID
            // account/subject behavior, rather than inventing new source cancellation.
            assertTrue(90L in h.vm.commentState.value.likedComments)
            assertEquals(listOf("like:170001:1:90:true"),h.actions);assertTrue(h.scope.isActive)
        }
    }
    @Test fun awaitingUiEffectRetiresWithoutPublishingAndIoStaysOutsideGate(): Unit = runBlocking {
        var active=true;var inGate=false;var published=false
        val result=CompletableDeferred<String>()
        val p=DesktopWindowsCommentPresentation(Any(),null,{active},{action->
            inGate=true;try{action();true}finally{inGate=false}})
        val job=async(start=CoroutineStart.UNDISPATCHED) {
            p.awaitEffect { assertFalse(inGate);result.await() }.also {published=true}
        }
        active=false;result.complete("late")
        assertFailsWith<CancellationException>{job.await()};assertFalse(published);p.close()
    }
    @Test fun closeIsUiOnlyAndCannotRetireSuccessor(): Unit = runBlocking {
        val source=Any();val old=DesktopWindowsCommentPresentation(source,null,{true},{it();true})
        val next=DesktopWindowsCommentPresentation(source,null,{true},{it();true})
        old.close();assertFalse(old.isCurrent());assertTrue(next.isCurrent())
        assertFalse(old.dispatch{error("Retired callback")});assertTrue(next.dispatch{})
        assertNull(old.nativeOwner);next.close()
    }
    @Test fun explicitRouteOpensLoadedRootAndClosingCannotReopenIt(): Unit = runBlocking {
        Harness().use {h -> val p=h.presentation();val ui=h.ui(p)
            val route=DesktopWindowsVideoRoutedCommentRequest(90,99)
            assertTrue(ui.threadFromRoute(route));assertTrue(route.handled);assertTrue(ui.threadVisible)
            assertEquals(90L,h.vm.subReplyState.value.rootReply?.rpid)
            assertEquals(99L,h.vm.subReplyState.value.targetReplyId)
            assertEquals(listOf(SubCall(170001,1,90,99)),h.subCalls)
            assertTrue(ui.closeThread());val calls=h.subCalls.size;p.close()
            // Same entry intent is retained while the comments Tab is rebuilt.
            val next=h.presentation();val recreated=h.ui(next)
            assertFalse(recreated.threadFromRoute(route));assertFalse(recreated.threadVisible)
            assertFalse(h.vm.subReplyState.value.visible);assertEquals(calls,h.subCalls.size)
            next.close();assertTrue(h.failures.isEmpty(),h.failures.toString())
        }
    }
    @Test fun remoteRouteKeepsExactRootTargetAndDoesNotReviveSuccessorUi(): Unit = runBlocking {
        Harness().use {h -> val response=CompletableDeferred<Result<ReplyData>>()
            h.subResult={response.await()};val p=h.presentation();val ui=h.ui(p)
            val route=DesktopWindowsVideoRoutedCommentRequest(700,701)
            assertTrue(ui.threadFromRoute(route));assertTrue(route.handled);assertTrue(ui.threadVisible)
            assertFalse(h.vm.subReplyState.value.visible);assertTrue(h.vm.subReplyState.value.isLoading)
            assertEquals(listOf(SubCall(170001,1,700,701)),h.subCalls)
            h.source=Any();p.close();val next=h.presentation();val recreated=h.ui(next)
            response.complete(Result.success(data(701,end=true).copy(root=ReplyItem(rpid=700,oid=170001))))
            // The original already-started same-AID read may publish; new UI remains closed.
            assertTrue(h.vm.subReplyState.value.visible)
            assertEquals(700L,h.vm.subReplyState.value.rootReply?.rpid)
            assertEquals(701L,h.vm.subReplyState.value.targetReplyId)
            assertFalse(recreated.threadFromRoute(route));assertFalse(recreated.threadVisible)
            assertEquals(1,h.subCalls.size);next.close();assertTrue(h.failures.isEmpty(),h.failures.toString())
        }
    }
    @Test fun rejectedRouteDoesNotConsumeIntentAndActualInitCanThenOpen(): Unit = runBlocking {
        Harness(initialAid=0).use {h -> val p=h.presentation();val ui=h.ui(p)
            val route=DesktopWindowsVideoRoutedCommentRequest(90,99)
            assertFalse(ui.threadFromRoute(route));assertFalse(route.handled);assertFalse(ui.threadVisible)
            assertTrue(h.subCalls.isEmpty());h.vm.init(170001,42,CommentSortMode.HOT,0)
            h.permit=false;assertFalse(ui.threadFromRoute(route));assertFalse(route.handled)
            h.permit=true;assertTrue(ui.threadFromRoute(route));assertTrue(route.handled)
            assertEquals(listOf(SubCall(170001,1,90,99)),h.subCalls);p.close()
            assertTrue(h.failures.isEmpty(),h.failures.toString())
        }
    }
    @Test fun retiredPresentationCannotConsumeTheNewExplicitRoute(): Unit = runBlocking {
        Harness().use {h -> val p=h.presentation();val old=h.ui(p)
            val route=DesktopWindowsVideoRoutedCommentRequest(90,99)
            h.source=Any();assertFalse(old.threadFromRoute(route));assertFalse(route.handled)
            assertTrue(h.subCalls.isEmpty());val next=h.presentation()
            assertTrue(h.ui(next).threadFromRoute(route));assertTrue(route.handled)
            p.close();next.close();assertTrue(h.failures.isEmpty(),h.failures.toString())
        }
    }
    @Test fun handledRouteSaverRetainsTheExplicitEntryDecision(): Unit = runBlocking {
        Harness().use {h -> val p=h.presentation();val ui=h.ui(p)
            val route=DesktopWindowsVideoRoutedCommentRequest(90,99)
            assertTrue(ui.threadFromRoute(route));assertTrue(ui.closeThread())
            val saved=with(DesktopWindowsVideoRoutedCommentRequest.Saver) {
                androidx.compose.runtime.saveable.SaverScope { true }.save(route)
            }
            val restored=requireNotNull(DesktopWindowsVideoRoutedCommentRequest.Saver.restore(requireNotNull(saved)))
            assertEquals(90L,restored.rootReplyId);assertEquals(99L,restored.targetReplyId);assertTrue(restored.handled)
            val calls=h.subCalls.size;assertFalse(ui.threadFromRoute(restored));assertEquals(calls,h.subCalls.size)
            p.close();assertTrue(h.failures.isEmpty(),h.failures.toString())
        }
    }
    @Test fun retainedSameAidThreadDoesNotAutomaticallyReopenInNewSourceUi(): Unit = runBlocking {
        Harness().use {h ->val a=h.presentation();val old=h.ui(a)
            old.thread(ReplyItem(rpid=90),0);assertTrue(old.threadVisible)
            h.source=Any();a.close();val b=h.presentation();val next=h.ui(b)
            assertTrue(h.vm.subReplyState.value.visible);assertFalse(next.threadVisible)
            assertFalse(old.closeThread());assertTrue(h.vm.subReplyState.value.visible)
            next.thread(ReplyItem(rpid=91),0);assertTrue(next.threadVisible)
            assertEquals(91L,h.vm.subReplyState.value.rootReply?.rpid);b.close()
        }
    }
}
