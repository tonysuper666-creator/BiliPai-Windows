package com.bilipai.desktop.ui
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import kotlin.coroutines.CoroutineContext

private class PostQueue:CoroutineDispatcher() {
    private val tasks=java.util.ArrayDeque<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable){tasks.add(block)}
    fun drain(){var n=0;while(tasks.isNotEmpty()){check(++n<1000);tasks.removeFirst().run()}}
}
fun main(args:Array<String>) {
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    val item=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17}}""")
    val target=json.decodeFromString<ReplyItem>("""{"rpid":701,"oid":100,"member":{"mid":"88","uname":"Original reply target"}}""")
    val proofs=mutableListOf<String>()
    for(case in listOf("capture-before-clear","close-queued","pre-cancel","account-retired","generation-retired","close-in-flight")) {
        val q=PostQueue();val job=SupervisorJob();val failures=mutableListOf<Throwable>()
        val scope=CoroutineScope(q+job+CoroutineExceptionHandler{_,e->failures+=e})
        val requests=FixtureReplyRequests();val callbacks=mutableListOf<String>()
        if(case=="pre-cancel")job.cancel()
        val session=DesktopOriginalDynamicReplySession("100",scope,requests,{item})
        val finish=CompletableDeferred<Unit>()
        try {
            if(case!="pre-cancel"){session.openCommentSheet(item);q.drain();check(session.selectedCommentTarget.value!=null)}
            session.startCommentReply(target)
            if(case=="close-in-flight")requests.mutation={withContext(NonCancellable){finish.await()}}
            session.postComment("100","Original queued message"){ok,message->callbacks+="$ok:$message"}
            session.clearCommentReplyTarget() // Exact synchronous cleanup ordering of the unmodified original composer.
            when(case) {
                "close-queued"->session.close()
                "account-retired"->requests.active=false
                "generation-retired"->session.openCommentSheet(item)
            }
            q.drain()
            if(case=="close-in-flight") {
                check(requests.calls.single{it.startsWith("post:")}.startsWith("post:100:17:701:701:"))
                session.close();finish.complete(Unit);q.drain();check(callbacks.isEmpty())
            } else if(case=="capture-before-clear") {
                check(requests.calls.single{it.startsWith("post:")}=="post:100:17:701:701:Original queued message")
                check(callbacks==listOf("true:评论成功"))
            } else {
                check(requests.calls.none{it.startsWith("post:")}){"Retired owner called request in $case"}
                check(callbacks.isEmpty())
            }
            check(failures.isEmpty()){failures.toString()};proofs+=case
        } finally {finish.complete(Unit);session.close();job.cancel();q.drain()}
    }
    Files.writeString(Path.of(args[0]),buildJsonObject{put("passed",true);put("proofs",JsonArray(proofs.map(::JsonPrimitive)))
        put("dispatchQueueActuallyDelayed",true);put("originalPostingGateAbsentNoNewGate",true);put("sessionClassCodeSource",DesktopOriginalDynamicReplySession::class.java.protectionDomain.codeSource.location.toString());put("onlyOneSessionProductOverride",!DesktopOriginalDynamicReplySession::class.java.protectionDomain.codeSource.location.toString().contains("main-kotlin.jar"))
        put("MainConsumerAcceptance",false);put("HTTP",false);put("HWND",false)}.toString())
}
