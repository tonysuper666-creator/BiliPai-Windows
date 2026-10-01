package com.bilipai.desktop

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private var assertions=0
private fun verify(value:Boolean,reason:String){check(value){reason};assertions++}
private suspend fun waitFor(reason:String,test:()->Boolean)=withTimeout(5000){while(!test())delay(5);verify(test(),reason)}
private suspend fun swing(block:()->Unit)=withContext(Dispatchers.Swing){block()}
private suspend fun controller(root:Path){
    val store=DesktopSessionStore.temporary();val repository=DesktopRepository(store)
    store.saveAccount(mapOf("SESSDATA" to "synthetic-two","bili_jct" to "csrf-two"),AccountSummary(2,"Two",""),imported=true)
    store.saveAccount(mapOf("SESSDATA" to "synthetic-one","bili_jct" to "csrf-one"),AccountSummary(1,"One",""),imported=true)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing);val player=MpvPlayer()
    val info=VideoDetails("BVfixture",99,"Fixture","","","",0,0,listOf(VideoPart(101,"First",100),VideoPart(102,"Second",100)))
    var calls=0
    val backend=object:DesktopPlaybackDataSource{
        override val sessionEpoch get()=repository.sessionEpoch
        override suspend fun videoDetails(bvid:String)=info.copy(bvid=bvid)
        override suspend fun related(bvid:String)=emptyList<VideoCard>()
        override suspend fun playback(details:VideoDetails,index:Int,quality:Int,codecOverride:String?,forceRefresh:Boolean):com.bilipai.desktop.data.PlaybackSource{
            calls++
            return com.bilipai.desktop.data.PlaybackSource("https://fixture.invalid/${details.pages[index].cid}.mp4",null,"Fixture","https://www.bilibili.com/",quality=quality,
                authorizationReceipt=repository.capturePlaybackAuthorization(repository.sessionEpoch){true}.receipt)
        }
    }
    val controller=DesktopPlaybackController(repository,player,null,null,DesktopLibrary(root.resolve("private-library")){false},
        {PlayerPreferences()},scope,dataSource=backend,currentDanmakuSettings={com.bilipai.desktop.danmaku.DanmakuSettings()},publication=DesktopRepositoryPlaybackPublication(repository))
    val first=VideoCard(info.bvid,"First","","",0,0,preferredCid=101)
    val second=first.copy(title="Second",preferredCid=102)
    try{
        val queueOwner=Any();swing{controller.openQueue(listOf(first,second),0,queueOwner)}
        waitFor("original queue current source admitted"){!controller.state.value.opening&&player.currentSourceSnapshot()!=null}
        val baseline=player.currentSourceVersion;val oldQueue=controller.state.value.queue
        swing{player.setPaused(true)}
        var retained=false;swing{retained=controller.openVideoDetail(first,1234,true)}
        verify(retained&&player.currentSourceVersion==baseline&&calls==1,"exact matching BVID/CID keeps same native source")
        verify(controller.state.value.queue==oldQueue&&controller.ownsQueue(queueOwner),"existing Favorites queue owner retained")
        verify(player.state.value.paused&&kotlin.math.abs(player.state.value.positionSeconds-1.234)<0.000001,"retained route seek keeps millisecond precision and paused state")
        swing{controller.open(second,2345)}
        waitFor("different CID route loads actual requested part"){!controller.state.value.opening&&player.currentSourceVersion!=baseline}
        verify(controller.state.value.currentPart==1&&player.currentSourceSnapshot()!!.source.startPositionSeconds==2.345,"immutable CID and millisecond resume reach final native mapping")
        verify(controller.state.value.queue==listOf(second)&&!controller.ownsQueue(queueOwner),"explicit fresh route does not retain previous queue lease")
        swing{controller.open(second.copy(progressSeconds=7),0)}
        waitFor("zero route resume preserves old history fallback"){!controller.state.value.opening&&player.currentSourceSnapshot()!!.source.startPositionSeconds==7.0}
        val existing=player.currentSourceSnapshot()!!;val before=calls
        store.setPlaybackAccountMid(2,repository.sessionEpoch){true}
        verify(player.currentSourceVersion==existing.sourceVersion,"selection alone leaves already-running source untouched")
        swing{retained=controller.openVideoDetail(second,0,true)}
        waitFor("retired authorization cannot be adopted by new route"){!controller.state.value.opening&&calls>before}
        verify(!retained&&player.currentSourceVersion!=existing.sourceVersion,"new route refetch uses current selected authorization")
        val same=player.currentSourceVersion;swing{retained=controller.openVideoDetail(second.copy(preferredCid=0),0,true)}
        waitFor("unspecified CID takes ordinary route path"){!controller.state.value.opening&&player.currentSourceVersion!=same}
        verify(!retained&&controller.state.value.currentPart==0,"CID zero never fabricates an exact source match")
        swing{controller.close()};val after=calls;swing{retained=controller.openVideoDetail(first,0,true)}
        verify(!retained&&calls==after,"closed controller rejects route without new transport")
    }finally{swing{controller.close()};scope.cancel();player.close()}
}

private class Requests:DesktopVideoCommentRequests{
    var owned=true
    val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var pending=false
    val roots=mutableListOf<Pair<Long,Long>>()
    override fun isOwned()=owned
    override fun currentMid()=1L
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=Result.success(ReplyData(cursor=ReplyCursor(isEnd=true),replies=emptyList()))
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long):Result<ReplyData>{
        check(oid==99L&&type==1);roots.add(rootId to targetReplyId)
        if(pending){started.complete(Unit);withContext(NonCancellable){release.await()}}
        return Result.success(ReplyData(root=ReplyItem(rpid=rootId,oid=oid,rcount=1),replies=listOf(ReplyItem(rpid=targetReplyId,root=rootId,oid=oid)),cursor=ReplyCursor(isEnd=true)))
    }
    override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?):Result<ReplyData> = error("unused dialog")
    override suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture> = error("unused upload")
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean):Result<ReplyItem?> = error("unused send")
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean):Result<Unit> = error("unused like")
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean):Result<Unit> = error("unused hate")
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long):Result<Unit> = error("unused delete")
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean):Result<Unit> = error("unused top")
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String):Result<Unit> = error("unused report")
    override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long):Result<CommentFraudStatus> = error("unused fraud")
    override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=error("unused record")
}
private suspend fun commentRoutes(){
    val request=Requests();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Swing);val vm=VideoCommentViewModel(scope,request)
    swing{vm.init(99,8,CommentSortMode.HOT,1);verify(vm.openSubReplyFromRoute(701,702),"original initial route accepted")}
    waitFor("actual original VM resolves requested root/target"){vm.subReplyState.value.visible}
    verify(request.roots.single()==(701L to 702L)&&vm.subReplyState.value.rootReply?.rpid==701L&&vm.subReplyState.value.targetReplyId==702L,"same original request fields and full root data")
    swing{vm.closeSubReply()};verify(!vm.subReplyState.value.visible,"original user close retires thread")
    request.pending=true;swing{verify(vm.openSubReplyFromRoute(703,704),"replacement original route starts")};request.started.await()
    swing{vm.closeSubReply()};request.release.complete(Unit);delay(50)
    verify(!vm.subReplyState.value.visible&&!vm.subReplyState.value.isLoading,"late cancelled request cannot re-open old thread")
    request.owned=false;scope.cancel()
    val freshRequests=Requests();val freshScope=CoroutineScope(SupervisorJob()+Dispatchers.Swing);val fresh=VideoCommentViewModel(freshScope,freshRequests)
    try{
        swing{fresh.init(99,8,CommentSortMode.HOT,1);verify(fresh.openSubReplyFromRoute(801,801),"fresh owner accepts new route")}
        waitFor("replacement owner visible"){fresh.subReplyState.value.visible}
        verify(fresh.subReplyState.value.rootReply?.rpid==801L&&fresh.subReplyState.value.targetReplyId==0L,"original target-equals-root normalization retained")
        verify(!vm.subReplyState.value.visible,"new owner never adopts previous thread state")
    }finally{freshRequests.owned=false;freshScope.cancel()}
}
fun main(args:Array<String>)=runBlocking{
    val root=Path.of(args.single());Files.createDirectories(root);controller(root);commentRoutes()
    val classes=listOf(DesktopPlaybackController::class.java,Class.forName("com.bilipai.desktop.ui.DesktopVideoCommentRootKt"),
        VideoCommentViewModel::class.java,MpvPlayer::class.java,DesktopRepository::class.java)
    val origins=classes.joinToString(","){type->
        val bytes=type.getResourceAsStream("/"+type.name.replace('.','/')+".class")!!.use{it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location}\",\"classSha256\":\"$hash\"}"
    }
    Files.writeString(root.resolve("result.json"),"{\"passed\":true,\"groups\":2,\"assertions\":$assertions,\"origins\":[$origins],\"HTTP\":false,\"NativeDLL\":false,\"RootMounted\":false,\"HostUIRuntime\":false}")
    println("PASS VideoDetail route 2 groups $assertions assertions; prospective Controller/Host, original VM/Repo/Mpv actual45, no HTTP/native DLL/RootUI")
}
