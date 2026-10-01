package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

private class ReplyQueue:CoroutineDispatcher(){
    val pending=ArrayDeque<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable){pending.addLast(block)}
    fun drain(){var steps=0;while(pending.isNotEmpty()){check(++steps<500){"Unexpected repeating fixture work"};pending.removeFirst().run()}}
}
private val lifecycleJson=Json{ignoreUnknownKeys=true;coerceInputValues=true}
private val lifecycleSubject=lifecycleJson.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Source-owned subject"}},"module_stat":{"comment":{"count":3}}}}""")
private fun holders(session:DesktopOriginalDynamicReplySession):Map<*,*> {
    val field=session.javaClass.getDeclaredField("replyMutationHolders");field.isAccessible=true;return field.get(session) as Map<*,*>
}
private fun ownedJob(session:DesktopOriginalDynamicReplySession):Job {
    val field=session.javaClass.getDeclaredField("ownedJob");field.isAccessible=true;return field.get(session) as Job
}
fun main(args:Array<String>):Unit=runBlocking {
    val groups=mutableListOf<String>();val errors=mutableListOf<Throwable>()
    fun scope(queue:ReplyQueue)=CoroutineScope(SupervisorJob()+queue+CoroutineExceptionHandler{_,f->errors+=f})
    run{
        val queue=ReplyQueue();val parent=scope(queue);parent.cancel()
        val requests=FixtureReplyRequests();val session=DesktopOriginalDynamicReplySession("100",parent,requests,{lifecycleSubject})
        session.openCommentSheet(lifecycleSubject);session.likeComment(701);session.hateComment(701);queue.drain()
        assertFalse(session.isOwned());assertTrue(requests.calls.isEmpty());assertTrue(holders(session).isEmpty());session.close()
        groups+="pre-cancelled owner admits no API, optimistic write or holder"
    }
    run{
        val queue=ReplyQueue();val parent=scope(queue);val requests=FixtureReplyRequests()
        val session=DesktopOriginalDynamicReplySession("100",parent,requests,{lifecycleSubject})
        try{
            session.openCommentSheet(lifecycleSubject);queue.drain();session.likeComment(701)
            assertEquals(1,holders(session).size);session.close();queue.drain()
            assertTrue(requests.calls.none{it.startsWith("like:")});assertTrue(holders(session).isEmpty())
            groups+="queued job disposal releases completion holder without running mutation"
        }finally{session.close();parent.cancel();queue.drain()}
    }
    run{
        val queue=ReplyQueue();val parent=scope(queue);val requests=FixtureReplyRequests()
        val session=DesktopOriginalDynamicReplySession("100",parent,requests,{lifecycleSubject})
        try{
            session.openCommentSheet(lifecycleSubject);queue.drain()
            val first=CompletableDeferred<Unit>();requests.mutation={first.await();error("Synthetic first failure")}
            session.likeComment(701);queue.drain();val firstJob=ownedJob(session).children.last{it.isActive}
            session.likeComment(701);session.hateComment(701);assertEquals(1,session.comments.value.first().action)
            assertEquals(1,requests.calls.count{it.startsWith("like:")});assertTrue(requests.calls.none{it.startsWith("hate:")})
            first.complete(Unit);queue.drain();assertEquals(0,session.comments.value.first().action);assertTrue(holders(session).isEmpty())
            val next=CompletableDeferred<Unit>();requests.mutation={next.await()}
            session.likeComment(701);queue.drain();assertEquals(1,holders(session).size)
            firstJob.cancel();session.hateComment(701);assertEquals(1,holders(session).size);assertEquals(1,session.comments.value.first().action)
            next.complete(Unit);queue.drain();assertTrue(holders(session).isEmpty())
            session.hateComment(701);queue.drain();assertEquals(2,session.comments.value.first().action)
            groups+="same rpid like/hate admission preserves original rollback and old completion cannot release new holder"
        }finally{session.close();parent.cancel();queue.drain()}
    }
    run{
        val queue=ReplyQueue();val parent=scope(queue);val requests=FixtureReplyRequests()
        val session=DesktopOriginalDynamicReplySession("100",parent,requests,{lifecycleSubject})
        try{
            session.openCommentSheet(lifecycleSubject);queue.drain()
            val late=CompletableDeferred<ReplyData>()
            requests.sub={_,_,_->withContext(NonCancellable){late.await()}}
            session.openSubReply(session.comments.value.first());queue.drain();assertTrue(session.subReplyState.value.isLoading)
            session.loadComments("100");queue.drain();assertFalse(session.subReplyState.value.isLoading);assertFalse(session.subReplyState.value.visible)
            late.complete(lifecycleJson.decodeFromString("""{"cursor":{"is_end":true}}"""));queue.drain()
            assertFalse(session.subReplyState.value.isLoading)
            requests.sub={_,_,_->lifecycleJson.decodeFromString("""{"cursor":{"is_end":true}}""")}
            session.openSubReply(session.comments.value.first());queue.drain();assertTrue(session.subReplyState.value.visible);assertFalse(session.subReplyState.value.isLoading)
            groups+="main refresh retires old thread generation without permanent loading; replacement remains usable"
            val wrong=lifecycleJson.decodeFromString<DynamicItem>("""{"id_str":"200","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"200","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Foreign subject"}}}}""")
            val before=requests.calls.size;session.openCommentSheet(wrong);queue.drain();assertEquals(before,requests.calls.size)
            session.setDynamicCommentSortMode(CommentSortMode.NEWEST);queue.drain()
            assertTrue(requests.calls.drop(before).none{it.startsWith("main:200:")});assertTrue(requests.calls.last{it.startsWith("main:")}.startsWith("main:100:"))
            groups+="foreign subject rejected before state write and subsequent sort stays on original identity"
        }finally{session.close();parent.cancel();queue.drain()}
    }
    assertTrue(errors.isEmpty(),errors.toString())
    Files.writeString(Path.of(args[0]),buildJsonObject{put("passed",true);put("groups",JsonArray(groups.map(::JsonPrimitive)));put("deterministicQueue",true);put("actualOwnedSession",true);put("HTTP",false);put("MainIntegration",false);put("HWND",false)}.toString())
}
