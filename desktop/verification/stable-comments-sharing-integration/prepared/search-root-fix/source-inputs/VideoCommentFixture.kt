package com.bilipai.desktop.videoCommentProof

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.screen.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

fun main(args:Array<String>):Unit = runBlocking {
    val root=Path.of(args[0]).toRealPath(); val candidate=Path.of(args[1]).toRealPath();val main=Path.of(args[2]).toRealPath()
    var assertions=0;fun prove(value:Boolean,label:String){check(value){label};assertions++}
    val session=DesktopSessionStore.temporary()
    session.saveAccount(mapOf("SESSDATA" to "declared-comment-session","bili_jct" to "declared-comment-csrf"),AccountSummary(42,"Declared comment proof",""))
    val repository=DesktopRepository(session)
    val uploads=AtomicInteger(); val forms=mutableListOf<Map<String,String>>()
    val client=repository.httpClient.newBuilder().addInterceptor { chain ->
        val request=chain.request();check(request.url.host=="api.bilibili.com")
        val json=when(request.url.encodedPath) {
            "/x/dynamic/feed/draw/upload_bfs" -> {
                val body=Buffer().also{request.body!!.writeTo(it)}.readUtf8()
                check(body.contains("declared-stream-") && body.contains("declared-comment-csrf"));uploads.incrementAndGet()
                """{"code":0,"data":{"image_url":"https://fixture.invalid/p.png","image_width":2,"image_height":3,"img_size":1}}"""
            }
            "/x/v2/reply/add" -> {
                val form=Buffer().also{request.body!!.writeTo(it)}.readUtf8().split('&').associate {
                    val pair=it.split('=',limit=2); URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
                };forms+=form
                check(form["type"]=="1" && form["csrf"]=="declared-comment-csrf")
                val rootId=form["root"]?.toLongOrNull() ?: 0L
                val parentId=form["parent"]?.toLongOrNull() ?: 0L
                """{"code":0,"data":{"reply":{"rpid":${555+forms.size},"oid":${form["oid"]},"mid":42,"ctime":3,"root":$rootId,"parent":$parentId,"content":{"message":"Declared sent"}}}}"""
            }
            else -> error("Undeclared endpoint denied: ${request.url.encodedPath}")
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("declared terminal transport")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }.build()
    fun field(name:String,value:Any)=repository.javaClass.getDeclaredField(name).apply{isAccessible=true}.set(repository,value)
    field("client",client)
    val api=Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
        .addConverterFactory(Json{ignoreUnknownKeys=true;coerceInputValues=true}.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    field("api",api);field("visitorInitialized",true);field("visitorGeneration",session.generation)
    val operations=DesktopDynamicCardOperations(repository)
    val pageScope=CoroutineScope(coroutineContext+SupervisorJob(coroutineContext[Job]))
    val records=DesktopCommentFraudRoot(repository,DesktopDynamicCardSession(repository),root.resolve("declared-records"),pageScope)
    val selected=DesktopDynamicEditorSelectedImages(operations::isOwned,operations::withOwnedEditorImageAdmission)
    try {
        for((clazz,expected) in listOf(DesktopSessionStore::class.java to main,DesktopRepository::class.java to main,
            DesktopDynamicCardOperations::class.java to main,VideoCommentViewModel::class.java to main,
            DesktopOriginalVideoCommentComposer::class.java to candidate))
            prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath()==expected,"Exact frozen code source ${clazz.name}")
        val sources=selected.accept((1..10).map{n->root.resolve("comment-$n.png").also{Files.writeString(it,"declared-stream-$n")}})
        val checkCount=AtomicInteger()
        val binding=DesktopVideoCommentOperationsBinding(operations,repository,selected::read,records.records,
            {aid,rpid,rootId,hasPictures,sentAt,wait->check(aid==991L && rpid>0 && rootId>=0);checkCount.incrementAndGet();Result.success(CommentFraudStatus.NORMAL)},
            records::recordPublished)
        val rawRoot=ReplyItem(rpid=100,oid=991,mid=42,rcount=2,content=ReplyContent(message="原富文本 [微笑] 01:20"))
        val loaded=object:DesktopVideoCommentRequests by binding {
            override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long):Result<ReplyData> {
                check(oid==991L && type==1 && rootId==100L && targetReplyId==0L)
                return Result.success(ReplyData(root=rawRoot,replies=emptyList(),cursor=ReplyCursor(isEnd=true)))
            }
            override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean):Result<ReplyData> {
                check(oid==991L && type==1);return Result.success(ReplyData(replies=listOf(rawRoot),page=ReplyPage(count=1,acount=1)))
            }
        }
        val vm=VideoCommentViewModel(pageScope,loaded);vm.init(991,expectedReplyCount=1)
        withTimeout(5000){while(vm.commentState.value.replies.isEmpty())delay(1)}
        prove(vm.commentState.value.replies.single()==rawRoot && vm.commentState.value.currentMid==42L,"Full existing generic VM keeps raw rich reply and actual account")
        val searchReturnedRoot=rawRoot
        vm.openSubReply(searchReturnedRoot)
        withTimeout(5000){while(vm.subReplyState.value.isLoading)delay(1)}
        prove(vm.subReplyState.value.visible && vm.subReplyState.value.rootReply?.rpid==100L && vm.subReplyState.value.targetReplyId==0L,
            "Exact original SearchSheet passes its root ReplyItem into full inline thread")
        vm.closeSubReply()
        var info=ViewInfo(bvid="BV-fixture",aid=991,cid=12)
        val feedback=mutableListOf<String>();var emoteLoads=0;val mentioned=mutableListOf<String>()
        val composer=DesktopOriginalVideoCommentComposer(pageScope,loaded,{info},
            {emoteLoads++;Result.success(emptyList())},{q->mentioned+=q;Result.success(emptyList())},feedback::add)
        val receipts=mutableListOf<ReplyItem?>()
        val collector=pageScope.launch { composer.commentSentEvent.collect { receipt -> receipts+=receipt;vm.onExternalCommentSent(991,receipt,true) } }
        yield()
        composer.openRootCommentComposer();yield();composer.updateCommentDraft("root draft",sources.take(1),true)
        composer.hideCommentInputDialog();composer.setReplyingTo(rawRoot);composer.showCommentInputDialog();composer.updateCommentDraft("reply draft",sources.take(2),false)
        composer.hideCommentInputDialog();composer.openRootCommentComposer();yield()
        prove(composer.composerDrafts.value.comments[commentComposerDraftKey(null)]?.text=="root draft","Exact root draft restored")
        prove(composer.composerDrafts.value.comments[commentComposerDraftKey(100)]?.text=="reply draft","Reply draft remains separate by original key")
        prove(emoteLoads==1 && composer.showCommentDialog.value,"Original successful lazy-emote load runs once")
        composer.setReplyingTo(rawRoot);composer.sendComment("   reply captured   ",sources,false)
        prove(composer.isSendingComment.value,"Synchronous admission closes Windows dispatcher duplicate-send gap")
        composer.hideCommentInputDialog();composer.setReplyingTo(ReplyItem(rpid=200,root=199))
        info=info.copy(aid=992);composer.sendComment("must be rejected while busy")
        withTimeout(5000){while(receipts.isEmpty() || checkCount.get()==0 || vm.commentState.value.isDetectingFraud)delay(1)}
        prove(forms.single()["oid"]=="991" && forms.single()["root"]=="100" && forms.single()["parent"]=="100","Original aid/root/parent captured before dismissal and part metadata changes")
        prove(forms.single()["message"]=="reply captured" && uploads.get()==9 && forms.single()["pictures"]?.contains("img_width")==true,"Original root reply text and take9 stream body using sole installed Ops")
        prove(receipts.size==1 && forms.size==1 && !composer.isSendingComment.value,"Duplicate admission emits only one success receipt")
        prove(vm.commentState.value.replyCount==2 && vm.commentState.value.replies.single().replies?.single()?.rpid==556L,"Original external receipt increments and updates preview once")
        prove(checkCount.get()==1,"Original receipt invokes existing generic fraud completion once")
        prove(composer.composerDrafts.value.comments[commentComposerDraftKey(100)]==null && composer.composerDrafts.value.comments[commentComposerDraftKey(null)]!=null,"Only sent draft is removed")
        info=info.copy(aid=991)
        val child=ReplyItem(rpid=201,root=100,member=ReplyMember(uname=" Target "))
        composer.setReplyingTo(child);composer.sendComment("  child ",emptyList(),true)
        withTimeout(5000){while(forms.size<2 || composer.isSendingComment.value)delay(1)}
        prove(forms.last()["root"]=="100" && forms.last()["parent"]=="201" && forms.last()["message"]==" 回复 @Target : child","Exact original nested-reply targets and reply prefix")
        prove(forms.last()["sync_to_dynamic"]=="1","Original sync-to-dynamic argument retained")
        composer.searchCommentMentionUsers("old");composer.searchCommentMentionUsers("new");delay(300)
        prove(mentioned==listOf("new") && composer.commentMentionSearchState.value.query=="new" && !composer.commentMentionSearchState.value.isLoading,"Original 250ms debounce cancels prior mention query")
        composer.searchCommentMentionUsers("cancelled");composer.clearCommentMentionSearch();delay(280)
        prove(mentioned==listOf("new") && composer.commentMentionSearchState.value.query.isEmpty(),"Original clear cancels delayed mention search")
        val blockedScope=CoroutineScope(coroutineContext+SupervisorJob(coroutineContext[Job]));val entered=CompletableDeferred<Unit>()
        val blocked=object:DesktopVideoCommentRequests by loaded {
            override suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture> {entered.complete(Unit);awaitCancellation()}
        }
        val cancelled=DesktopOriginalVideoCommentComposer(blockedScope,blocked,{info},{Result.success(emptyList())},{Result.success(emptyList())},feedback::add)
        cancelled.sendComment("blocked",sources.take(1));entered.await();blockedScope.cancel();yield();delay(5)
        prove(!cancelled.isSendingComment.value && forms.size==2,"Owned coroutine cancellation releases matching busy state without posting")
        val beforeFeedback=feedback.size; val beforeForms=forms.size
        session.saveAccount(mapOf("SESSDATA" to "declared-replaced-session","bili_jct" to "declared-comment-csrf"),AccountSummary(42,"Same MID replacement",""))
        prove(!operations.isOwned(),"Actual SessionStore retires account epoch on same-MID credential replacement")
        composer.sendComment("late",sources.take(1));delay(20)
        prove(forms.size==beforeForms && feedback.size==beforeFeedback && !composer.isSendingComment.value,"Retired owner rejects send before selected image transport and feedback")
        prove(resolveCommentReplyTargets(null,null)==(0L to 0L) && resolveCommentSendTargetAid(0,991)==991L,"Original pure target fallback rules")
        prove(shouldLoadMoreVideoComments(8,10,false,false) && !shouldLoadMoreVideoComments(8,10,true,false) && !shouldLoadMoreVideoComments(8,10,false,true),"Original prefetch/end/busy pagination rules")
        prove(shouldUseLightweightCommentRendering(1,true,false) && shouldUseLightweightCommentRendering(1,false,true) && !shouldUseLightweightCommentRendering(0,true,true),"Original playback/scroll visual budget policy")
        collector.cancel()
        Files.writeString(root.resolve("result.json"),"""{"passed":true,"cases":12,"assertions":$assertions,"terminalTransportRequests":${uploads.get()+forms.size},"externalNetwork":false,"HWND":false,"actualFrozenStoreRepositoryOpsGenericVM":true,"originalCommentComposerDraftsReceiptPolicies":true,"sameMIDReplacement":true,"commentsTake9SelectedStreaming":true,"OriginalFullTabSearchAndInputCompile":true}""")
        println("PASS full original video comment owner: $assertions assertions")
    } finally {selected.close();records.close();pageScope.cancel();client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
}
