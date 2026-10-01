package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import kotlin.test.*

private val replyJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private fun rawReply(id: Long, root: Long = 0, parent: Long = root, likes: Int = 8, action: Int = 0): ReplyItem =
    replyJson.decodeFromString("""{"rpid":$id,"oid":100,"mid":88,"root":$root,"parent":$parent,"count":3,"rcount":3,"like":$likes,"action":$action,"content":{"message":"Reply $id"},"member":{"mid":"88","uname":"Raw member"}}""")
private fun replyData(items: List<ReplyItem>, total: Int = 3, next: String = "more", end: Boolean = false, root: ReplyItem? = null): ReplyData =
    replyJson.decodeFromString<ReplyData>("""{"page":{"count":$total},"cursor":{"all_count":$total,"is_end":$end},"grpc_next_offset":"$next"}""").copy(replies=items,root=root,grpcNextOffset=next)
private val subject: DynamicItem = replyJson.decodeFromString("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Original raw subject"}},"module_stat":{"comment":{"count":3}}}}""")

internal class FixtureReplyRequests : DesktopDynamicReplyRequests {
    var active = true
    var loggedIn = true
    val calls = mutableListOf<String>()
    var main: suspend (Int, Int, String?) -> ReplyData = { page, _, _ ->
        if(page==1)replyData(listOf(rawReply(701),rawReply(702))) else replyData(listOf(rawReply(702),rawReply(703)),next="",end=true)
    }
    var sub: suspend (Int, String?, Long) -> ReplyData = { _, _, _ ->replyData(listOf(rawReply(710,701)),root=rawReply(701)) }
    var mutation: suspend (String) -> Unit = {}
    override fun isOwned()=active
    override fun hasCsrf()=loggedIn
    override suspend fun getDynamicDetail(id:String):Result<DynamicItem> { calls+="detail:$id";return Result.success(subject) }
    override suspend fun getCommentCountForSubject(oid:Long,type:Int):Result<Int> { calls+="count:$oid:$type";return Result.success(3) }
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean):Result<ReplyData> {
        calls+="main:$oid:$type:$page:$ps:$mode:$paginationOffset:$fallbackOnMissingLocation"
        return Result.success(main(page,mode,paginationOffset))
    }
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long):Result<ReplyData> {
        calls+="sub:$oid:$type:$rootId:$mode:$paginationOffset:$targetReplyId"
        return Result.success(sub(mode,paginationOffset,targetReplyId))
    }
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long):Result<ReplyItem?> {
        calls+="post:$oid:$type:$root:$parent:$message";mutation("post");return Result.success(rawReply(800,root,parent))
    }
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean):Result<Unit> {
        calls+="like:$rpid:$like";return runCatching{mutation("like")}
    }
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean):Result<Unit> {
        calls+="hate:$rpid:$hate";return runCatching{mutation("hate")}
    }
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long):Result<Unit> {
        calls+="delete:$rpid";mutation("delete");return Result.success(Unit)
    }
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean):Result<Unit> {
        calls+="top:$rpid:$isCurrentlyTop";return Result.success(Unit)
    }
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int):Result<Unit> {
        calls+="report:$rpid:$reason";return Result.success(Unit)
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val failures=mutableListOf<Throwable>()
    val handler=CoroutineExceptionHandler { _,failure ->failures+=failure }
    val scope=CoroutineScope(coroutineContext+SupervisorJob()+handler)
    val proofs=mutableListOf<String>()
    suspend fun ready(session:DesktopOriginalDynamicReplySession) { withTimeout(3000){while(session.commentsLoading.value||session.comments.value.isEmpty())yield()} }
    val requests=FixtureReplyRequests();val session=DesktopOriginalDynamicReplySession("100",scope,requests,{subject})
    try {
        session.openCommentSheet(subject);ready(session)
        assertEquals(listOf(701L,702L),session.comments.value.map{it.rpid})
        assertTrue(requests.calls.contains("main:100:17:1:20:3:null:true"))
        session.loadMoreComments();withTimeout(3000){while(session.commentsLoadingMore.value)yield()}
        assertEquals(listOf(701L,702L,703L),session.comments.value.map{it.rpid})
        assertEquals(3,session.commentTotalCount.value)
        assertTrue(requests.calls.any{it.startsWith("main:100:17:2:20:3:more:")})
        proofs+="original main candidate/count/pagingOffset/distinctBy/end"
        session.setDynamicCommentSortMode(CommentSortMode.NEWEST);ready(session)
        assertTrue(requests.calls.any{it.startsWith("main:100:17:1:20:2:")})
        proofs+="original sort resets subject paging and uses API mode 2"
        session.openSubReplyFromRoute(701,710)
        withTimeout(3000){while(session.subReplyState.value.isLoading)yield()}
        assertEquals(710L,session.subReplyState.value.targetReplyId)
        // The actual SubReplyUiState defaults to TIME, whose API mode is 2.
        assertTrue(requests.calls.any{it=="sub:100:17:701:2:null:710"})
        session.setSubReplySortMode(SubReplySortMode.HOT)
        withTimeout(3000){while(session.subReplyState.value.isLoading)yield()}
        assertTrue(requests.calls.any{it=="sub:100:17:701:3:null:0"})
        session.setSubReplySortMode(SubReplySortMode.TIME)
        withTimeout(3000){while(session.subReplyState.value.isLoading)yield()}
        assertTrue(requests.calls.any{it=="sub:100:17:701:2:null:0"})
        session.loadMoreSubReplies()
        withTimeout(3000){while(session.subReplyState.value.isLoading)yield()}
        assertTrue(requests.calls.any{it=="sub:100:17:701:2:more:0"})
        assertEquals(listOf(710L),session.subReplyState.value.items.map{it.rpid})
        requests.sub={_,_,_->replyData(listOf(rawReply(811,801)),root=rawReply(801))}
        assertTrue(session.openSubReplyFromRoute(801,811))
        withTimeout(3000){while(session.subReplyState.value.isLoading)yield()}
        assertEquals(801L,session.subReplyState.value.rootReply?.rpid)
        assertEquals(811L,session.subReplyState.value.targetReplyId)
        assertTrue(requests.calls.contains("sub:100:17:801:2:null:811"))
        proofs+="original loaded thread target, explicit TIME/HOT modes and cursor rather than REST page"
        session.startCommentReply(rawReply(710,701));var postMessage:String?=null
        session.postComment("100","Synthetic text"){ok,msg->assertTrue(ok);postMessage=msg}
        withTimeout(3000){while(postMessage==null)yield()}
        assertTrue(requests.calls.contains("post:100:17:701:710:Synthetic text"));assertNull(session.commentReplyTarget.value)
        proofs+="original root/parent composer and successful reload"
        ready(session)
        val likeGate=CompletableDeferred<Unit>();requests.mutation={if(it=="like"){likeGate.await();error("Synthetic rejection")}}
        session.likeComment(701);assertEquals(1,session.comments.value.first().action)
        assertEquals(9,session.comments.value.first().like)
        likeGate.complete(Unit);withTimeout(3000){while(session.comments.value.first().action==1)yield()}
        assertEquals(8,session.comments.value.first().like)
        requests.mutation={}
        var hated=false;session.hateComment(701){ok,_->hated=ok}
        withTimeout(3000){while(!hated)yield()};assertEquals(2,session.comments.value.first().action)
        var unhated=false;session.hateComment(701){ok,_->unhated=ok}
        withTimeout(3000){while(!unhated)yield()};assertEquals(0,session.comments.value.first().action)
        proofs+="original optimistic like rollback and two-way hate"
        var deleted=false;session.deleteDynamicComment(702){ok,_->deleted=ok}
        withTimeout(3000){while(!deleted)yield()};assertTrue(session.comments.value.none{it.rpid==702L});assertEquals(2,session.commentTotalCount.value)
        var reported=false;session.reportDynamicComment(701,4){ok,_->reported=ok};withTimeout(3000){while(!reported)yield()}
        assertTrue(requests.calls.contains("report:701:4"))
        proofs+="original delete reducer/count and actual original reason field"
        val late=CompletableDeferred<ReplyData>();requests.main={_,_,_->withContext(NonCancellable){late.await()}}
        session.setDynamicCommentSortMode(CommentSortMode.HOT);yield()
        requests.active=false;late.complete(replyData(listOf(rawReply(999))))
        repeat(40){yield()};assertTrue(session.comments.value.none{it.rpid==999L})
        proofs+="same-MID epoch/retired request cannot publish even non-cancellable late read"
        session.close();session.openCommentSheet(subject)
        val before=requests.calls.size;repeat(40){yield()};assertEquals(before,requests.calls.size)
        proofs+="closed queued task produces no request"
        assertTrue(failures.isEmpty(),failures.toString())
        Files.writeString(Path.of(args[0]),buildJsonObject{
            put("passed",true);put("groups",JsonArray(proofs.map(::JsonPrimitive)))
            put("actualOriginalSession",true);put("typedRawReplyModels",true)
            put("syntheticRequestsOnly",true);put("MainIntegration",false);put("HTTP",false)
        }.toString())
    } catch(failure:Throwable) { println("synthetic completed groups=$proofs; calls=${requests.calls}");throw failure
    } finally {session.close();scope.cancel()}
}
