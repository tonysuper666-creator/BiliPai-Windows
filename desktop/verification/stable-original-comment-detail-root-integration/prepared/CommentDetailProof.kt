package com.bilipai.desktop.ui

import com.android.purebilibili.feature.comment.CommentDetailViewModel
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.*

private var assertions=0
private fun verify(value:Boolean,label:String) { check(value){label};assertions++ }
private class Commands:DesktopOriginalRootRouteCommands {
    val pushed=mutableListOf<BiliPaiNavKey>()
    val videos=mutableListOf<BiliPaiNavKey.VideoDetail>()
    val videoRoutes=mutableListOf<Pair<String,String>>()
    override fun push(key:BiliPaiNavKey):Boolean { pushed+=key;return true }
    override fun video(key:BiliPaiNavKey.VideoDetail) { videos+=key }
    override fun videoRoute(route:String,sourceRoute:String) { videoRoutes+=route to sourceRoute }
    override fun back():Boolean=error("Unused fixture command")
    override fun articleBack(article:BiliPaiNavKey.ArticleDetail,useSharedReturn:Boolean):Boolean=error("Unused fixture command")
    override fun containsEntry(key:BiliPaiNavKey):Boolean=error("Unused fixture command")
    override fun home():Boolean=error("Unused fixture command")
    override fun replaceVideoDetail(current:BiliPaiNavKey.VideoDetail,bvid:String,cid:Long,cover:String,resumePositionMs:Long):Boolean=error("Unused fixture command")
    override fun homeFromVideo(current:BiliPaiNavKey.VideoDetail):Boolean=error("Unused fixture command")
    override fun markVideoReturning(current:BiliPaiNavKey.VideoDetail):Boolean=error("Unused fixture command")
    override fun clearVideoReturning():Boolean=error("Unused fixture command")
}

private fun routeProof() {
    val c=Commands()
    fun open(uri:String) = desktopOriginalOpenMessageLink(uri,c,"reply_me")
    open("bilibili://comment/detail/12/34646640/265141324256")
    verify(c.pushed.last()==BiliPaiNavKey.CommentDetail(34646640,265141324256,0,12,"bilibili://read/cv34646640"),"Article business retains complete independent comment key")
    open("bilibili://comment/detail/17/832703053858603029/238686570016/?anchor=238686628816")
    verify(c.pushed.last()==BiliPaiNavKey.CommentDetail(832703053858603029,238686570016,238686628816,17,"bilibili://following/detail/832703053858603029"),"Dynamic business uses independent CommentDetail")
    open("bilibili://comment/msg_fold/1/22222/33333/11111/?enterUri=bilibili%3A%2F%2Fvideo%2F22222")
    verify(c.pushed.last()==BiliPaiNavKey.CommentDetail(22222,33333,11111,1,"bilibili://video/22222"),"Fold retains oid/root/target/enterUri")
    verify(c.videos.isEmpty()&&c.videoRoutes.isEmpty(),"Independent comments never open playback")
    open("https://www.bilibili.com/video/BV1xx411c7mD?comment_root_id=1#reply2")
    val video=legacyRouteToBiliPaiNavKey(c.videoRoutes.last().first) as BiliPaiNavKey.VideoDetail
    verify(video.bvid=="BV1xx411c7mD"&&video.commentRootRpid==1L&&video.commentTargetRpid==2L,"VideoComment retains original Holder locator")
    verify(c.videoRoutes.last().second=="reply_me","VideoComment retains captured source route")
    open("bilibili://browser/?url=https%3A%2F%2Fwww.bilibili.com%2Fvideo%2FBV1xx411c7mD%3Fcomment_root_id%3D123%26comment_secondary_id%3D456")
    val nested=legacyRouteToBiliPaiNavKey(c.videoRoutes.last().first) as BiliPaiNavKey.VideoDetail
    verify(nested.commentRootRpid==123L&&nested.commentTargetRpid==456L,"Nested browser uses existing original parser")
    open("bilibili://article/40679479?jump_opus=1")
    verify(c.pushed.last()==BiliPaiNavKey.ArticleDetail(40679479),"Article remains original typed leaf")
    val navigation=CommunityNavigation({error("Video flattening")},{error("User flattening")},{error("Article flattening")},{error("Login flattening")},{error("Live flattening")},{error("Bangumi flattening")},{error("Dynamic flattening")},onMessageLink={open(it)})
    navigateCommunityUrl("bilibili://comment/detail/12/34646640/265141324256",navigation)
    verify(c.pushed.last() is BiliPaiNavKey.CommentDetail,"Community accepts non-HTTP native comment links")
    navigateCommunityUrl("bilibili://video/115391124741470?page=0&comment_root_id=279569905408",navigation)
    verify(c.pushed.last()==BiliPaiNavKey.CommentDetail(115391124741470,279569905408,0,1,"bilibili://video/115391124741470"),"Native aid comment preserves original action instead of regex BV fallback")
}

private data class SortedCall(val oid:Long,val type:Int,val root:Long,val mode:Int,val offset:String?,val target:Long,val completion:Continuation<Result<ReplyData>>)
/** Explicit fake requests only exercise the original VM reducer; this is not HTTP evidence. */
private class Requests(val owned:AtomicBoolean):DesktopVideoCommentRequests {
    val calls=mutableListOf<SortedCall>()
    override fun isOwned()=owned.get()
    override fun currentMid()=123L
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long):Result<ReplyData> = suspendCoroutine { calls+=SortedCall(oid,type,rootId,mode,paginationOffset,targetReplyId,it) }
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean):Result<ReplyData> = error("Unused fixture request")
    override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?):Result<ReplyData> = error("Unused fixture request")
    override suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture> = error("Unused fixture request")
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean):Result<ReplyItem?> = error("Unused fixture request")
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean):Result<Unit> = error("Unused fixture request")
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean):Result<Unit> = error("Unused fixture request")
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long):Result<Unit> = error("Unused fixture request")
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean):Result<Unit> = error("Unused fixture request")
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String):Result<Unit> = error("Unused fixture request")
    override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long):Result<CommentFraudStatus> = error("Unused fixture request")
    override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?) { error("Unused fixture request") }
}
private class Fixture {
    val owned=AtomicBoolean(true)
    val job=SupervisorJob()
    val errors=mutableListOf<Throwable>()
    val scope=CoroutineScope(job+Dispatchers.Unconfined+CoroutineExceptionHandler{_,error->errors+=error})
    val requests=Requests(owned)
    private val lock=Any()
    private fun commit(action:()->Unit)=synchronized(lock){if(owned.get()&&job.isActive){action();true}else false}
    val entryRequests=DesktopOriginalCommentEntryRequests(requests,{owned.get()&&job.isActive},::commit)
    val vm=CommentDetailViewModel(scope,entryRequests,::commit)
    fun retire(){ synchronized(lock){owned.set(false)} }
    suspend fun finish(){ job.cancelAndJoin();verify(errors.isEmpty(),"No unexpected coroutine error") }
}
private fun data(root:Long,ids:List<Long>,offset:String="",end:Boolean=false)=ReplyData(root=ReplyItem(rpid=root,rcount=5),replies=ids.map{ReplyItem(rpid=it,root=root)},cursor=ReplyCursor(allCount=5,isEnd=end),grpcNextOffset=offset)

private suspend fun vmProof() {
    val f=Fixture()
    f.vm.loadInitial(0,33333,12,11111)
    verify(f.requests.calls.isEmpty(),"Invalid subject does not enqueue request")
    f.vm.loadInitial(22222,33333,12,11111)
    val initial=f.requests.calls.single()
    verify(listOf(initial.oid,initial.root,initial.target)==listOf(22222L,33333L,11111L)&&initial.type==12&&initial.mode==2&&initial.offset==null,"Original first request retains independent business, TIME default and locator")
    initial.completion.resume(Result.success(data(33333,listOf(11111,11112),"page2")))
    verify(f.vm.subReplyState.value.rootReply?.rpid==33333L&&f.vm.subReplyState.value.targetReplyId==11111L,"Root and target locator published by original VM")
    f.vm.loadMore()
    val page=f.requests.calls.last()
    verify(page.target==0L&&page.offset=="page2"&&page.mode==2,"Original next-page clears target and retains grpc offset")
    page.completion.resume(Result.success(data(33333,listOf(11112,11113),end=true)))
    verify(f.vm.subReplyState.value.items.map{it.rpid}==listOf(11111L,11112L,11113L)&&f.vm.subReplyState.value.page==2&&f.vm.subReplyState.value.isEnd,"Original merge deduplicates and retains actual cursor end")
    f.vm.setSortMode(SubReplySortMode.HOT)
    val sorted=f.requests.calls.last()
    verify(sorted.mode==3&&sorted.target==0L&&sorted.offset==null,"Original sort reset uses HOT API mode")
    sorted.completion.resume(Result.success(data(33333,listOf(11113),end=true)))
    verify(f.vm.subReplyState.value.items.map{it.rpid}==listOf(11113L),"Sort replaces first page")
    f.retire()
    val before=f.vm.subReplyState.value
    verify(runCatching{f.vm.startDissolve(11113)}.exceptionOrNull() is CancellationException,"Retired synchronous final state write rejected")
    verify(f.vm.subReplyState.value==before,"Rejected write leaves original flow unchanged")
    verify(runCatching{f.entryRequests.getSortedSubCommentsForSubject(22222,12,33333,2,null,0)}.exceptionOrNull() is CancellationException&&f.requests.calls.size==3,"Removed entry rejects before reaching shared delegate")
    f.finish()

    val old=Fixture()
    old.vm.loadInitial(10,20,17,20)
    val pending=old.requests.calls.single()
    verify(pending.target==0L,"Root==target is normalized by original reducer")
    val loading=old.vm.subReplyState.value
    old.retire()
    pending.completion.resume(Result.success(data(20,listOf(21))))
    verify(old.vm.subReplyState.value==loading,"Noncooperative late response cannot publish after owner retirement")
    old.finish()

    val replace=Fixture()
    replace.vm.loadInitial(10,20,12,21)
    val a=replace.requests.calls.single()
    replace.vm.loadInitial(30,40,17,41)
    val b=replace.requests.calls.last()
    a.completion.resume(Result.success(data(20,listOf(21))))
    verify(replace.vm.subReplyState.value.rootReply==null&&replace.vm.subReplyState.value.targetReplyId==41L,"Canceled earlier request cannot overwrite replacement")
    b.completion.resume(Result.success(data(40,listOf(41))))
    verify(replace.vm.subReplyState.value.rootReply?.rpid==40L&&replace.vm.subReplyState.value.items.single().rpid==41L,"Replacement uses full original reply schema")
    replace.finish()

    val cancel=Fixture()
    cancel.vm.loadInitial(50,60,1,61)
    val late=cancel.requests.calls.single()
    val captured=cancel.vm.subReplyState.value
    cancel.job.cancel()
    late.completion.resume(Result.failure(java.io.IOException("Fixture late failure")))
    verify(cancel.vm.subReplyState.value==captured,"Actual canceled caller late failure does not become visible error")
    cancel.finish()
}
fun main()=runBlocking {
    routeProof();withTimeout(5000){vmProof()}
    println("PASS $assertions focused assertions; fixture-only requests, no HTTP/window/account/native")
}
