package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** The actual sole generated domain consumes the original complete methods.
 * Required transports are synthetic; no media, account profile or VM copy. */
class DesktopOriginalVideoComposerCommentsTest {
    private data class Add(val aid: Long, val type: Int, val message: String,
        val root: Long, val parent: Long, val pictures: List<ReplyPicture>, val sync: Boolean)
    private class Harness : AutoCloseable {
        @Volatile var account = true
        @Volatile var source: Any = Any()
        @Volatile var permit = true
        var info = ViewInfo(bvid="BVfixture",aid=170001,cid=7007)
        var generation = 1L
        val feedback = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val adds = CopyOnWriteArrayList<Add>()
        val uploads = CopyOnWriteArrayList<Pair<String,Int>>()
        val mentionCalls = CopyOnWriteArrayList<String>()
        var emoteLoads = 0
        var addResult: suspend () -> Result<ReplyItem?> = { Result.success(ReplyItem(rpid=900,oid=170001)) }
        var uploadResult: suspend (String,Int) -> Result<ReplyPicture> = { uri,_ -> Result.success(ReplyPicture(imgSrc=uri)) }
        var mentionResult: suspend (String) -> Result<List<MentionSearchUser>> = { Result.success(listOf(MentionSearchUser(uid=1,name=it))) }
        var emoteResult: suspend () -> Result<List<EmotePackage>> = { Result.success(listOf(EmotePackage(1,"原表情",emote=emptyList()))) }
        private val gate=Any()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+CoroutineExceptionHandler { _,e -> failures+=e })
        private fun owned()=account && scope.isActive
        private fun commit(action:()->Unit):Boolean=synchronized(gate) { if (owned()) {action();true} else false }
        val requests=object:DesktopVideoCommentRequests {
            override fun isOwned()=owned()
            override fun currentMid()=111L
            override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=Result.success(ReplyData(replies=emptyList(),cursor=ReplyCursor(isEnd=true)))
            override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=Result.success(ReplyData())
            override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?)=Result.success(ReplyData())
            override suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture> { uploads+=source to index;return uploadResult(source,index) }
            override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean):Result<ReplyItem?> {
                currentCoroutineContext().ensureActive();if(!owned())throw CancellationException("Account retired")
                adds+=Add(oid,type,message,root,parent,pictures.toList(),syncToDynamic);return addResult()
            }
            override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean)=error("No reaction in composer")
            override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean)=error("No reaction in composer")
            override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long)=error("No delete in composer")
            override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean)=error("No top in composer")
            override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String)=error("No report in composer")
            override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long):Result<CommentFraudStatus> =error("Fraud disabled in synthetic test")
            override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=error("No persistence")
        }
        val environment=DesktopOriginalVideoComposerEnvironment(scope,::owned,::commit,requests,{info},
            { emoteLoads++;emoteResult() }, { keyword -> mentionCalls+=keyword;mentionResult(keyword) }, { message -> feedback+=message })
        val vm=VideoComposerViewModel(environment)
        fun subject()=VideoSubjectSnapshot(info.bvid,info.cid,info.aid,42,"标题","",60000,generation)
        init { vm.bindSubject(subject()) }
        fun presentation(captured:Any=source)=DesktopWindowsCommentPresentation(captured,null,
            { owned() && source===captured }, { action -> synchronized(gate) { if(owned()&&permit&&source===captured) {action();true}else false } })
        fun open(p:DesktopWindowsCommentPresentation=presentation(),reply:ReplyItem?=null)=checkNotNull(vm.openCommentComposer(p,reply))
        suspend fun settled() { withTimeout(3000) { vm.isSendingComment.first { !it } } }
        override fun close() { scope.cancel();vm.close() }
    }

    @Test fun originalRootReplyDraftsKeepImagesSyncAndRestoreTheirOwnKey():Unit=runBlocking {
        Harness().use { h ->
            val p=h.presentation();val a=h.open(p);val images=mutableListOf("file:///one.png")
            assertTrue(h.vm.changeCommentDraft(a,"root",images,true));images+="file:///late.png"
            assertTrue(h.vm.dismissCommentComposer(a))
            val reply=ReplyItem(rpid=99,root=90,member=ReplyMember(uname="用户"));val b=h.open(p,reply)
            h.vm.changeCommentDraft(b,"child",listOf("file:///two.png"),false)
            h.vm.dismissCommentComposer(b);val c=h.open(p)
            assertEquals(CommentComposerDraft("root",listOf("file:///one.png"),true),h.vm.composerDrafts.value.comments[0L])
            assertEquals("child",h.vm.composerDrafts.value.comments[99L]?.text)
            assertNull(h.vm.replyingToComment.value);assertTrue(h.vm.showCommentDialog.value)
            assertFalse(h.vm.changeCommentDraft(a,"old",emptyList(),false));assertSame(c,h.vm.commentStamp.value)
            p.close();assertTrue(h.failures.isEmpty())
        }
    }
    @Test fun sourceReplacementAndFinalPermitDenyOldUiButKeepEntryAlive():Unit=runBlocking {
        Harness().use {h -> val p=h.presentation();val a=h.open(p)
            h.vm.changeCommentDraft(a,"kept",emptyList(),false);h.source=Any()
            assertFalse(h.vm.submitComment(a,"stale",emptyList(),false));assertFalse(h.vm.dismissCommentComposer(a))
            h.vm.retireCommentPresentation(p);p.close();assertNull(h.vm.commentStamp.value);assertTrue(h.scope.isActive)
            val next=h.presentation();h.permit=false;assertNull(h.vm.openCommentComposer(next))
            h.permit=true;val b=h.open(next);assertEquals("kept",h.vm.composerDrafts.value.comments[0L]?.text)
            assertTrue(h.adds.isEmpty());assertFalse(h.vm.changeCommentDraft(a,"stale",emptyList(),false));assertSame(b,h.vm.commentStamp.value)
            next.close()
        }
    }
    @Test fun closeAndReopenRetiresOnlyExactDialogAndOptionalSearch():Unit=runBlocking {
        Harness().use {h ->val p=h.presentation();val a=h.open(p)
            h.vm.searchCommentMentions(a,"old");h.vm.dismissCommentComposer(a)
            val b=h.open(p);h.vm.changeCommentDraft(b,"new",emptyList(),true)
            assertFalse(h.vm.dismissCommentComposer(a));assertFalse(h.vm.searchCommentMentions(a,"stale"))
            assertSame(b,h.vm.commentStamp.value);assertEquals("new",h.vm.composerDrafts.value.comments[0L]?.text)
            assertFalse(h.vm.commentMentionSearchState.value.isLoading);assertTrue(h.scope.isActive);p.close()
        }
    }
    @Test fun emotesUseOriginalLazyCacheAndMentionsUseRealDebounceLatestJob():Unit=runBlocking {
        Harness().use {h ->val p=h.presentation();val a=h.open(p)
            assertEquals("原表情",h.vm.emotePackages.value.single().text);assertEquals(1,h.emoteLoads)
            h.vm.searchCommentMentions(a,"first");h.vm.searchCommentMentions(a,"last")
            withTimeout(3000) { h.vm.commentMentionSearchState.first { it.query=="last"&&!it.isLoading } }
            assertEquals(listOf("last"),h.mentionCalls);assertEquals("last",h.vm.commentMentionSearchState.value.users.single().name)
            h.vm.dismissCommentComposer(a);h.open(p);assertEquals(1,h.emoteLoads);p.close()
        }
    }
    @Test fun cancelledLateMentionCannotPublishIntoSameQueryReopenedDialog():Unit=runBlocking {
        Harness().use {h ->val pending=CompletableDeferred<Result<List<MentionSearchUser>>>();val entered=CompletableDeferred<Unit>()
            h.mentionResult={ entered.complete(Unit);withContext(NonCancellable){pending.await()} }
            val p=h.presentation();val a=h.open(p);h.vm.searchCommentMentions(a,"");entered.await()
            h.vm.dismissCommentComposer(a);h.mentionResult={Result.success(listOf(MentionSearchUser(uid=2,name="B")))}
            val b=h.open(p);h.vm.searchCommentMentions(b,"")
            pending.complete(Result.success(listOf(MentionSearchUser(uid=1,name="A"))))
            yield();assertEquals("B",h.vm.commentMentionSearchState.value.users.single().name)
            assertSame(b,h.vm.commentStamp.value);assertTrue(h.failures.isEmpty());p.close()
        }
    }
    @Test fun mentionResultCancellationReleasesOnlyItsOwnQueryBusy():Unit=runBlocking {
        Harness().use {h ->h.mentionResult={Result.failure(CancellationException("cancel"))}
            val p=h.presentation();val a=h.open(p);h.vm.searchCommentMentions(a,"")
            assertFalse(h.vm.commentMentionSearchState.value.isLoading);assertNull(h.vm.commentMentionSearchState.value.errorMessage)
            h.mentionResult={Result.success(listOf(MentionSearchUser(uid=2,name="retry")))}
            h.vm.searchCommentMentions(a,"");assertEquals("retry",h.vm.commentMentionSearchState.value.users.single().name)
            assertTrue(h.failures.isEmpty());p.close()
        }
    }
    @Test fun originalSendCapturesReplyProtocolNinePicturesAndSyncFlag():Unit=runBlocking {
        Harness().use {h ->val p=h.presentation();val a=h.open(p,ReplyItem(rpid=99,root=90,member=ReplyMember(uname="甲")))
            val images=(0..11).map { "file:///$it.png" };h.vm.changeCommentDraft(a,"hello",images,true)
            assertTrue(h.vm.submitComment(a," hello ",images,true));h.settled()
            val sent=h.adds.single();assertEquals(170001L,sent.aid);assertEquals(1,sent.type)
            assertEquals(90L,sent.root);assertEquals(99L,sent.parent);assertEquals(" 回复 @甲 : hello",sent.message)
            assertTrue(sent.sync);assertEquals(images.take(9),sent.pictures.map { it.imgSrc });assertEquals((0..8).toList(),h.uploads.map { it.second })
            assertFalse(h.vm.showCommentDialog.value);assertNull(h.vm.composerDrafts.value.comments[99L]);assertEquals(170001L,h.vm.commentSentEvent.first().aid)
            p.close();assertTrue(h.failures.isEmpty())
        }
    }
    @Test fun pictureFailureRetainsDraftAndAllowsOriginalRetryWithoutSend():Unit=runBlocking {
        Harness().use {h ->h.uploadResult={_,_->Result.failure(IllegalStateException("图片失败"))}
            val p=h.presentation();val a=h.open(p);h.vm.changeCommentDraft(a,"draft",listOf("file:///x"),true)
            h.vm.submitComment(a,"draft",listOf("file:///x"),true);h.settled()
            assertTrue(h.adds.isEmpty());assertTrue(h.vm.showCommentDialog.value);assertEquals("draft",h.vm.composerDrafts.value.comments[0L]?.text)
            assertEquals(listOf("图片失败"),h.feedback)
            h.uploadResult={u,_->Result.success(ReplyPicture(imgSrc=u))};h.vm.submitComment(a,"draft",listOf("file:///x"),true);h.settled()
            assertEquals(1,h.adds.size);p.close()
        }
    }
    @Test fun resultCancellationReleasesOwnBusyWithoutSuccessOrDraftDeletion():Unit=runBlocking {
        Harness().use {h ->h.addResult={Result.failure(CancellationException("cancel"))}
            val p=h.presentation();val a=h.open(p);h.vm.changeCommentDraft(a,"saved",emptyList(),false)
            h.vm.submitComment(a,"saved",emptyList(),false);h.settled()
            assertEquals("saved",h.vm.composerDrafts.value.comments[0L]?.text);assertSame(a,h.vm.commentStamp.value)
            assertTrue(h.feedback.isEmpty());assertTrue(h.failures.isEmpty());assertTrue(h.scope.isActive);p.close()
        }
    }
    @Test fun oldSendCompletionCannotDismissReopenedDialogOrClearSuccessorDraft():Unit=runBlocking {
        Harness().use {h ->val pending=CompletableDeferred<Result<ReplyItem?>>();h.addResult={pending.await()}
            val p=h.presentation();val a=h.open(p);h.vm.changeCommentDraft(a,"A",emptyList(),false);h.vm.submitComment(a,"A",emptyList(),false)
            withTimeout(3000){while(h.adds.isEmpty())yield()};h.vm.dismissCommentComposer(a)
            val b=h.open(p);h.vm.changeCommentDraft(b,"B",listOf("file:///b"),true)
            pending.complete(Result.success(ReplyItem(rpid=900,oid=170001)));h.settled()
            assertSame(b,h.vm.commentStamp.value);assertTrue(h.vm.showCommentDialog.value)
            assertEquals(CommentComposerDraft("B",listOf("file:///b"),true),h.vm.composerDrafts.value.comments[0L]);assertEquals("A",h.adds.single().message)
            assertEquals(170001L,h.vm.commentSentEvent.first().aid);p.close()
        }
    }
    @Test fun editingSameDialogDuringSendPreservesNewDraftAfterSuccess():Unit=runBlocking {
        Harness().use {h ->val pending=CompletableDeferred<Result<ReplyItem?>>();h.addResult={pending.await()}
            val p=h.presentation();val a=h.open(p);h.vm.changeCommentDraft(a,"A",emptyList(),false);h.vm.submitComment(a,"A",emptyList(),false)
            withTimeout(3000){while(h.adds.isEmpty())yield()};h.vm.changeCommentDraft(a,"B",emptyList(),false)
            pending.complete(Result.success(null));h.settled();assertSame(a,h.vm.commentStamp.value)
            assertTrue(h.vm.showCommentDialog.value);assertEquals("B",h.vm.composerDrafts.value.comments[0L]?.text);p.close()
        }
    }
    @Test fun alreadyDispatchedReceiptKeepsOriginalAidAndExistingCommentVmRejectsNewAid():Unit=runBlocking {
        Harness().use {h ->val pending=CompletableDeferred<Result<ReplyItem?>>();h.addResult={pending.await()}
            val comments=VideoCommentViewModel(h.scope,h.requests);comments.init(170001,42,CommentSortMode.HOT,0)
            val p=h.presentation();val a=h.open(p);h.vm.changeCommentDraft(a,"old",emptyList(),false);h.vm.submitComment(a,"old",emptyList(),false)
            withTimeout(3000){while(h.adds.isEmpty())yield()};h.source=Any();h.vm.retireCommentPresentation(p);p.close()
            h.info=ViewInfo(bvid="BVnext",aid=170002,cid=7008);h.generation++;h.vm.bindSubject(h.subject());comments.init(170002,43,CommentSortMode.HOT,0)
            val next=h.presentation();val b=h.open(next);h.vm.changeCommentDraft(b,"next",emptyList(),false)
            pending.complete(Result.success(ReplyItem(rpid=900,oid=170001)));h.settled()
            val receipt=h.vm.commentSentEvent.first();assertEquals(170001L,receipt.aid)
            comments.onExternalCommentSent(receipt.aid,receipt.reply,false)
            assertTrue(comments.commentState.value.replies.none {it.rpid==900L});assertSame(b,h.vm.commentStamp.value)
            assertEquals("next",h.vm.composerDrafts.value.comments[0L]?.text);next.close()
        }
    }
    @Test fun singleArgumentDraftBridgeDoesNotCarryImagesOrSyncAcrossVideos():Unit=runBlocking {
        Harness().use { h ->
            val oldPresentation=h.presentation();val a=h.open(oldPresentation)
            h.vm.changeCommentDraft(a,"A",listOf("file:///A.png"),true)
            assertEquals(CommentComposerDraft("A",listOf("file:///A.png"),true),h.vm.composerDrafts.value.comments[0L])
            h.source=Any();h.info=ViewInfo(bvid="BVnext",aid=170002,cid=7008);h.generation++
            h.vm.bindSubject(h.subject());oldPresentation.close()
            // Consume the public bridge before opening B: it must reset the
            // original per-video state before reading root images/sync.
            h.vm.updateCommentDraft("B")
            assertEquals("BVnext",h.vm.composerDrafts.value.videoId)
            assertEquals(mapOf(0L to CommentComposerDraft("B",emptyList(),false)),h.vm.composerDrafts.value.comments)
            val next=h.presentation();h.open(next)
            assertEquals(CommentComposerDraft("B",emptyList(),false),h.vm.composerDrafts.value.comments[0L])
            assertTrue(h.adds.isEmpty());assertTrue(h.uploads.isEmpty());assertTrue(h.failures.isEmpty());next.close()
        }
    }
    @Test fun accountRetirementAndDomainCloseCancelOptionalWorkWithoutHandlerFailure():Unit=runBlocking {
        Harness().use {h ->val p=h.presentation();val a=h.open(p);h.vm.searchCommentMentions(a,"query")
            h.account=false;assertFalse(h.vm.submitComment(a,"stale",emptyList(),false))
            h.scope.cancel();h.vm.close();p.close();assertTrue(h.adds.isEmpty());assertTrue(h.failures.isEmpty())
        }
    }
}
