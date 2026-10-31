package com.bilipai.desktop.player

import androidx.paging.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.comment.CommentPageRequest
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Reflection retains actual VM callbacks only; no replacement paging algorithm or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCommentPagingLifecycleTest {
    private fun field(obj: Any, name: String): Any = obj.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(obj)
    private class Harness : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob()+dispatcher+CoroutineExceptionHandler { _,t -> errors+=t })
        var owned = true
        var explode = false
        val calls = mutableListOf<String>()
        val ports = java.lang.reflect.Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) { _,method,args ->
            when (method.name.substringBefore('-')) {
                "isOwned" -> owned
                "currentMid" -> 111L
                "getMobileCommentsForSubject" -> {
                    val v=args!!.dropLast(1); assertEquals(7,v.size); assertEquals(1,v[2]); assertNull(v[5])
                    calls += "main:${v[0]}:${v[4]}"
                    val rows = if (explode) object: AbstractList<ReplyItem>() {
                        override val size: Int get() = throw IllegalStateException("synthetic callback failure")
                        override fun get(index:Int): ReplyItem = error("unexpected index")
                    } else listOf(ReplyItem(rpid=10))
                    ReplyData(cursor=ReplyCursor(isEnd=true),replies=rows)
                }
                "getMobileSortedSubCommentsForSubject" -> {
                    val v=args!!.dropLast(1); assertEquals(7,v.size); assertEquals(true,v[6]); assertNull(v[4])
                    calls += "sub:${v[2]}:${v[3]}"
                    ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=20,root=v[2] as Long)))
                }
                "getDialogCommentsForSubject" -> {
                    val v=args!!.dropLast(1); assertEquals(6,v.size); assertEquals(1,v[4]); assertNull(v[5])
                    calls += "dialog:${v[3]}"
                    ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=30)))
                }
                else -> error("Unexpected port ${method.name}")
            }
        } as DesktopVideoCommentRequests
        val vm: VideoCommentViewModel
        init { Dispatchers.setMain(dispatcher); sharedVideoCommentCache.clear(); vm=VideoCommentViewModel(scope,ports) }
        fun start(id:Long=170001) { vm.init(id,42,CommentSortMode.HOT,0); scheduler.runCurrent() }
        override fun close() { scope.cancel(); scheduler.runCurrent(); assertFalse(scope.isActive); Dispatchers.resetMain(); sharedVideoCommentCache.clear() }
    }
    private data class Callbacks(val job:Job,val page:()->Unit,val state:()->Unit)
    @Suppress("UNCHECKED_CAST")
    private fun callbacks(h:Harness, dialog:Boolean=false):Callbacks {
        val session=field(h.vm,if(dialog) "dialogPagingSession" else "mainPagingSession")
        val changed=field(field(session,"session"),"onChanged")
        val captured=changed.javaClass.declaredFields.map { it.isAccessible=true; it.get(changed) }
        val page=captured.filterIsInstance<Function2<*,*,*>>().single() as (CommentPageRequest,ReplyData)->Unit
        val state=captured.filterIsInstance<Function3<*,*,*,*>>().single() as (Boolean,Boolean,Throwable?)->Unit
        return Callbacks(field(h.vm,if(dialog) "subReplyLoadJob" else "commentLoadJob") as Job,
            { page(CommentPageRequest(),ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=999)))) },
            { state(false,true,IllegalStateException("late")) })
    }
    @Suppress("UNCHECKED_CAST")
    private fun subCallback(h:Harness):Pair<Job,()->Unit> {
        val changed=field(field(h.vm,"subReplyPagingSession"),"onChanged") as (List<Any>,CombinedLoadStates?)->Unit
        val failed=LoadState.Error(IllegalStateException("late sub"))
        val states=CombinedLoadStates(failed,LoadState.NotLoading(true),LoadState.NotLoading(true),
            LoadStates(failed,LoadState.NotLoading(true),LoadState.NotLoading(true)))
        return (field(h.vm,"subReplyLoadJob") as Job) to { changed(emptyList(),states) }
    }
    private fun unchanged(h:Harness, action:()->Unit) {
        val main=h.vm.commentState.value; val sub=h.vm.subReplyState.value; val calls=h.calls.toList()
        action(); h.scheduler.runCurrent()
        assertEquals(main,h.vm.commentState.value); assertEquals(sub,h.vm.subReplyState.value); assertEquals(calls,h.calls)
    }
    @Test fun refreshSubjectAndSortRejectRetainedCallbacksAndCancelOldJobs() {
        Harness().use { h ->
            h.start(); val first=callbacks(h); h.vm.refreshComments(); h.scheduler.runCurrent()
            assertTrue(first.job.isCancelled); unchanged(h) { first.page();first.state() }
            val second=callbacks(h); h.start(170002); assertTrue(second.job.isCancelled)
            unchanged(h) { second.page();second.state() }
            h.vm.openSubReply(ReplyItem(rpid=10)); h.scheduler.runCurrent()
            val oldSub=subCallback(h); h.vm.setSubReplySortMode(SubReplySortMode.TIME); h.scheduler.runCurrent()
            // The default can already be TIME: select the other real mode first if needed.
            if (!oldSub.first.isCancelled) { h.vm.setSubReplySortMode(SubReplySortMode.entries.first { it != SubReplySortMode.TIME }); h.scheduler.runCurrent() }
            assertTrue(oldSub.first.isCancelled); unchanged(h,oldSub.second)
            val refreshSub=subCallback(h);h.vm.refreshSubReplies();h.scheduler.runCurrent()
            assertTrue(refreshSub.first.isCancelled);unchanged(h,refreshSub.second)
            val rootSub=subCallback(h);h.vm.openSubReply(ReplyItem(rpid=15));h.scheduler.runCurrent()
            assertTrue(rootSub.first.isCancelled);unchanged(h,rootSub.second)
            h.vm.openSubReplyConversation(ReplyItem(rpid=20,root=10,dialog=20)); h.scheduler.runCurrent()
            val dialog=callbacks(h,true); h.vm.closeSubReplyConversation(); h.scheduler.runCurrent()
            assertTrue(dialog.job.isCancelled); unchanged(h) { dialog.page();dialog.state() }
            assertEquals(3,h.calls.count { it.startsWith("main:") }); assertTrue(h.errors.isEmpty(),h.errors.toString())
        }
    }
    @Test fun retiredOwnerRejectsActualMainSubAndDialogCallbacks() {
        Harness().use { h ->
            h.start(); val main=callbacks(h)
            h.vm.openSubReply(ReplyItem(rpid=10));h.scheduler.runCurrent();val sub=subCallback(h)
            h.owned=false; unchanged(h) {main.page();main.state();sub.second()}
            h.owned=true;h.vm.openSubReplyConversation(ReplyItem(rpid=20,root=10,dialog=20));h.scheduler.runCurrent()
            val dialog=callbacks(h,true);h.owned=false;unchanged(h) {dialog.page();dialog.state()}
            assertEquals(3,h.calls.size);assertTrue(h.errors.isEmpty(),h.errors.toString())
        }
    }
    @Test fun exceptionInsideActualPageCallbackDoesNotKillVmScope() {
        Harness().use { h ->
            h.explode=true;h.start()
            assertEquals(1,h.errors.size);assertEquals("synthetic callback failure",h.errors.single().message)
            assertTrue(h.scope.isActive)
            h.explode=false;h.start(170002)
            assertEquals(listOf(10L),h.vm.commentState.value.replies.map{it.rpid})
            assertEquals(listOf("main:170001:3","main:170002:3"),h.calls)
            assertEquals(1,h.errors.size);assertTrue(h.scope.isActive)
        }
    }
}
