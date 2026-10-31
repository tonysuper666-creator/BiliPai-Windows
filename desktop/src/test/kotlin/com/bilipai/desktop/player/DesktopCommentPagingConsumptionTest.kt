package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCommentPagingConsumptionTest {
    private data class Expected(val method:String,val args:List<Any?>,val result:Result<ReplyData>)
    private class Harness:AutoCloseable {
        val scheduler=TestCoroutineScheduler()
        val dispatcher=UnconfinedTestDispatcher(scheduler)
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+dispatcher+CoroutineExceptionHandler {_,t->errors+=t})
        val expected=ArrayDeque<Expected>()
        val calls=mutableListOf<List<Any?>>()
        val deletions=ArrayDeque<Long>()
        val deleted=mutableListOf<Long>()
        val requests=java.lang.reflect.Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
            arrayOf(DesktopVideoCommentRequests::class.java)) {_,m,a->
            when(m.name.substringBefore('-')) {
                "isOwned"->true
                "currentMid"->111L
                "deleteCommentForSubject"->{
                    val id=deletions.removeFirst();assertEquals(listOf<Any?>(170001L,1,id),a!!.dropLast(1));deleted+=id;Unit
                }
                "getMobileCommentsForSubject","getMobileSortedSubCommentsForSubject"->{
                    val e=expected.removeFirst();assertEquals(e.method,m.name.substringBefore('-'))
                    val args=a!!.dropLast(1);assertEquals(e.args,args);calls+=args
                    e.result.fold({it},{Class.forName("kotlin.ResultKt").getMethod("createFailure",Throwable::class.java).invoke(null,it)})
                }
                else->error("Unexpected port ${m.name}")
            }
        } as DesktopVideoCommentRequests
        val vm:VideoCommentViewModel
        init { Dispatchers.setMain(dispatcher);sharedVideoCommentCache.clear();vm=VideoCommentViewModel(scope,requests) }
        fun main(page:Int,token:CommentMainListContinuation?,result:Result<ReplyData>,oid:Long=170001) {
            expected+=Expected("getMobileCommentsForSubject",listOf(oid,1,page,VIDEO_COMMENT_PAGE_SIZE,3,token,true),result)
        }
        fun sub(root:Long,mode:Int,offset:String?,result:Result<ReplyData>) {
            expected+=Expected("getMobileSortedSubCommentsForSubject",listOf(170001L,1,root,mode,offset,0L,true),result)
        }
        fun flush(){scheduler.runCurrent();assertTrue(expected.isEmpty(),expected.toString());assertTrue(errors.isEmpty(),errors.toString())}
        override fun close(){scope.cancel();scheduler.runCurrent();Dispatchers.resetMain();sharedVideoCommentCache.clear()}
    }
    @Test fun fullMainSessionPreservesTypedTokensAcrossEmptyPageFailureRetryAndLocalSend() {
        Harness().use {h->
            val token1=CommentMainListContinuation(route=CommentMainListRoute.REST_MAIN,next=9007199254740993L,offset="opaque-one")
            val token2=CommentMainListContinuation(route=CommentMainListRoute.REST_LEGACY,next=9007199254740994L,offset="opaque-two")
            h.main(1,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=false),replies=listOf(ReplyItem(rpid=1)),mainListContinuation=token1)))
            h.vm.init(170001,42,CommentSortMode.HOT,0);h.flush()
            h.main(2,token1,Result.success(ReplyData(cursor=ReplyCursor(isEnd=false),replies=emptyList(),mainListContinuation=token2)))
            h.vm.loadComments();h.flush();assertFalse(h.vm.commentState.value.isRepliesEnd)
            h.main(3,token2,Result.failure(IllegalStateException("retry page three")))
            h.vm.loadComments();h.flush();assertEquals("retry page three",h.vm.commentState.value.repliesError)
            h.vm.onExternalCommentSent(170001,ReplyItem(rpid=99),fraudDetectionEnabled=false)
            h.main(3,token2,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=3)))))
            h.vm.loadComments();h.flush()
            assertEquals(listOf(99L,1L,3L),h.vm.commentState.value.replies.map{it.rpid})
            assertEquals(listOf(1,2,3,3),h.calls.map{it[2]});assertEquals(h.calls[2],h.calls[3])
            assertTrue(h.vm.commentState.value.isRepliesEnd)
            h.main(1,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=4)))))
            h.vm.refreshComments();h.flush();assertEquals(listOf(99L,4L),h.vm.commentState.value.replies.map{it.rpid})
            h.deletions+=99L;h.vm.deleteComment(99);h.flush();assertEquals(listOf(99L),h.deleted)
            h.main(1,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=99),ReplyItem(rpid=4)))))
            h.vm.refreshComments();h.flush();assertEquals(listOf(4L),h.vm.commentState.value.replies.map{it.rpid})
            h.main(1,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=emptyList())),oid=170002)
            h.vm.init(170002,42,CommentSortMode.HOT,0);h.flush()
            assertTrue(h.vm.commentState.value.replies.isEmpty());assertEquals(0,h.vm.commentState.value.replyCount)
            assertTrue(h.vm.commentState.value.replyCountIsAuthoritative)
        }
    }
    @Test fun fullSubSessionRetriesSameOffsetResetsSortAndRetainsLocalReply() {
        Harness().use {h->
            h.main(1,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=10)))))
            h.vm.init(170001,42,CommentSortMode.HOT,0);h.flush()
            val root=ReplyItem(rpid=10,count=5,rcount=5)
            val initialMode=SubReplyUiState().sortMode.apiMode
            h.sub(10,initialMode,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=false),replies=listOf(ReplyItem(rpid=11,root=10)),grpcNextOffset="sub-one")))
            h.vm.openSubReply(root);h.flush()
            h.sub(10,initialMode,"sub-one",Result.failure(IllegalStateException("retry sub")))
            h.vm.loadMoreSubReplies();h.flush();assertEquals("retry sub",h.vm.subReplyState.value.error)
            h.vm.onExternalCommentSent(170001,ReplyItem(rpid=19,root=10),fraudDetectionEnabled=false)
            h.sub(10,initialMode,"sub-one",Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=12,root=10)))))
            h.vm.loadMoreSubReplies();h.flush();assertEquals(setOf(11L,12L,19L),h.vm.subReplyState.value.items.map{it.rpid}.toSet())
            assertEquals(h.calls[2],h.calls[3])
            val other=SubReplySortMode.entries.first{it.apiMode!=initialMode}
            h.sub(10,other.apiMode,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=13,root=10)))))
            h.vm.setSubReplySortMode(other);h.flush()
            assertEquals(setOf(13L,19L),h.vm.subReplyState.value.items.map{it.rpid}.toSet())
            assertEquals(5,h.calls.size)
            h.deletions+=19L;h.vm.deleteSubComment(19);h.flush();assertEquals(listOf(19L),h.deleted)
            h.sub(10,other.apiMode,null,Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=listOf(ReplyItem(rpid=19,root=10),ReplyItem(rpid=14,root=10)))))
            h.vm.refreshSubReplies();h.flush();assertEquals(listOf(14L),h.vm.subReplyState.value.items.map{it.rpid})
        }
    }
}
