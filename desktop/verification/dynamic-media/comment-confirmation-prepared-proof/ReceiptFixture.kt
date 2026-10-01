package com.bilipai.desktop.ui
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*

fun main(args:Array<String>):Unit=runBlocking {
 val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
 val item=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_stat":{"comment":{"count":3}}}}""")
 val scope=CoroutineScope(coroutineContext+SupervisorJob());val requests=FixtureReplyRequests()
 val session=DesktopOriginalDynamicReplySession("100",scope,requests,{item})
 val cases=mutableListOf<String>()
 suspend fun waitReceipt(n:Long){withTimeout(3000){while(session.commentConfirmationRevision.value!=n||session.commentsLoading.value)yield()}}
 try {
  session.openCommentSheet(item);waitReceipt(1);check(session.commentTotalCount.value==3)
  session.setDynamicCommentSortMode(CommentSortMode.NEWEST);waitReceipt(2);check(session.commentTotalCount.value==3)
  check(requests.calls.count{it.startsWith("main:")}==2);cases+="successful same-count sort request advances receipt despite unchanged numeric3"
  var deleted=false;session.deleteDynamicComment(701){ok,_->deleted=ok}
  withTimeout(3000){while(!deleted)yield()};check(session.commentConfirmationRevision.value==3L&&session.commentTotalCount.value==2)
  session.closeCommentSheet();check(session.commentConfirmationRevision.value==3L&&session.commentTotalCount.value==0)
  cases+="owned successful delete advances receipt and original close reset never advances"
  val failed=object:DesktopDynamicReplyRequests by requests {
   override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.failure<Int>(Exception("Fixture count failure"))
   override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=Result.failure<ReplyData>(Exception("Fixture main failure"))
  }
  val fallback=DesktopOriginalDynamicReplySession("100",scope,failed,{item});fallback.openCommentSheet(item)
  repeat(20){yield()};check(!fallback.commentsLoading.value&&fallback.commentTotalCount.value==3&&fallback.commentConfirmationRevision.value==0L)
  fallback.close();cases+="fallback source count without successful selected response has no receipt"
  val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>();val returned=CompletableDeferred<Unit>()
  requests.main={_,_,_->withContext(NonCancellable){entered.complete(Unit);released.await();returned.complete(Unit);json.decodeFromString<ReplyData>("""{"cursor":{"all_count":99,"is_end":true},"replies":[]}""")}}
  session.openCommentSheet(item);entered.await();session.close();released.complete(Unit);returned.await();repeat(20){yield()}
  check(session.commentConfirmationRevision.value==3L);cases+="late NonCancellable retired request does not publish count receipt"
  Files.writeString(Path.of(args[0]),buildJsonObject{put("passed",true);putJsonArray("cases"){cases.forEach{add(it)}};put("candidateSessionOverride",1);put("actualMainAcceptance",false);put("HWND",false);put("HTTP",false);put("sessionCodeSource",session.javaClass.protectionDomain.codeSource.location.toString())}.toString())
 } finally {session.close();scope.cancel()}
}
