@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.cancelCompletionProof

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.dynamic.components.DynamicInlineCommentComposer
import com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private val json=Json { ignoreUnknownKeys=true;coerceInputValues=true }
private val subject=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Declared cancellation fixture"}}}}""")
private val page=json.decodeFromString<ReplyData>("""{"page":{"count":3},"cursor":{"all_count":3,"is_end":true}}""")
private val picture=ReplyPicture("https://fixture.invalid/uploaded.png",13,17,1.25f)
private class QueueDispatcher:CoroutineDispatcher(){
    val queue=ArrayDeque<Runnable>()
    override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){synchronized(queue){queue.add(block)}}
    fun drain(){repeat(1000){val block=synchronized(queue){queue.poll()}?:return;block.run()};error("Queue did not settle")}
}
private class Harness(context:kotlin.coroutines.CoroutineContext):AutoCloseable {
    val store=DesktopSessionStore.temporary().also { it.saveAccount(mapOf("SESSDATA" to "declared-cancel-fixture","bili_jct" to "fixture-csrf"),AccountSummary(42,"Declared fixture","")) }
    val repository=DesktopRepository(store)
    val operations=DesktopDynamicCardOperations(repository)
    val scope=CoroutineScope(context+SupervisorJob(context[Job]))
    val uploads=mutableListOf<CompletableDeferred<ReplyPicture>>()
    var posts=0
    val binding=DesktopDynamicReplyOperationsBinding(operations,{true},{Result.success(subject)},{error("Image IO outside this cancellation-only fixture")})
    val requests=object:DesktopDynamicReplyRequests by binding {
        override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.success(3)
        override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=Result.success(com.bilipai.desktop.ui.cancelCompletionProof.page)
        override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=Result.success(com.bilipai.desktop.ui.cancelCompletionProof.page)
        override suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture> {
            val pending=CompletableDeferred<ReplyPicture>();uploads+=pending
            return Result.success(pending.await())
        }
        override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>):Result<ReplyItem?> {posts++;return Result.success(null)}
    }
    val session=DesktopOriginalDynamicReplySession("100",scope,requests,{subject})
    override fun close(){session.close();scope.cancel()}
}
private class Platform(override val context:DesktopPluginContext,val owned:()->Boolean,val image:String):DesktopCommentPlatform {
    override val collapsedReplyPreviewLimit=3
    override val subReplyLoadedCountEnabled=MutableStateFlow(true)
    override val emotes=object:DesktopDynamicEmotes {
        override fun snapshot()=emptyMap<String,String>()
        override fun currentSessionKey():Any=1L
        override suspend fun ensureLoaded()=snapshot()
    }
    override fun isOwned()=owned()
    override fun pickCommentImages(maxItems:Int,onSelected:(List<String>)->Unit){check(maxItems==9);onSelected(listOf(image))}
    override fun showFeedback(message:String)=error("Feedback outside cancellation-only fixture")
    override fun copyText(text:String,label:String)=error("Clipboard outside fixture")
    override fun shareText(text:String,title:String)=error("Share outside fixture")
    override suspend fun videoTitle(bvid:String)=error("Read outside fixture")
    override suspend fun translateReply(type:Long,oid:Long,rpid:Long)=error("Read outside fixture")
    override suspend fun blockUser(mid:Long,name:String,face:String,relationSource:BlockedUpRelationSource):BlockedUpWriteResult=error("Mutation outside fixture")
    override suspend fun saveCommentImage(spec:ReplyCommentImageSpec):Boolean=error("Save outside fixture")
}
private class Ui(val scene:ImageComposeScene) {
    var nanos=0L;var pointerPairs=0;var textActions=0
    fun nodes():List<SemanticsNode>{fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk);return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}}
    fun field()=nodes().last{it.config.getOrNull(SemanticsActions.SetText)!=null}
    fun value()=field().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    fun send()=nodes().last{it.config.getOrNull(SemanticsProperties.Text).orEmpty().any{it.text=="发送"}&&it.config.getOrNull(SemanticsActions.OnClick)!=null}
    fun sendEnabled()=send().config.getOrNull(SemanticsProperties.Disabled)==null
    suspend fun frame(){nanos+=32_000_000L;scene.render(nanos).close();delay(3)}
    suspend fun await(message:String,predicate:()->Boolean){withTimeout(5000){while(!predicate())frame()};repeat(6){frame()}}
    suspend fun press(node:SemanticsNode){val p=node.boundsInRoot.center;scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+30,buttons=PointerButtons());pointerPairs++;repeat(6){frame()}}
    suspend fun input(text:String){check(checkNotNull(field().config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)));textActions++;await("real editor state receives text"){value()==text}}
}

fun main(args:Array<String>):Unit=runBlocking {
    val out=Path.of(args[0]).toRealPath();val delta=Path.of(args[1]).toRealPath();val previous=Path.of(args[2]).toRealPath();val main=Path.of(args[3]).toRealPath()
    var assertions=0
    fun prove(ok:Boolean,label:String){check(ok){label};assertions++}
    val cases=mutableListOf<String>()
    val identities=mutableListOf<JsonObject>()
    for((clazz,expected) in listOf(DesktopOriginalDynamicReplySession::class.java to delta,DesktopDynamicReplyOperationsBinding::class.java to previous,DesktopDynamicCardOperations::class.java to previous,DesktopSessionStore::class.java to main,DesktopRepository::class.java to main,ReplyPicture::class.java to main)){
        val actual=Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath();prove(actual==expected,"code source ${clazz.name}")
        identities+=buildJsonObject{put("class",clazz.name);put("path",actual.toString())}
    }
    var pointerPairs=0;var textActions=0
    Harness(coroutineContext).use { h ->
        val png=out.resolve("selected.png");ImageIO.write(BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",png.toFile())
        val platform=Platform(DesktopPluginContext(DesktopPluginStore(out.resolve("fixture-settings"))),h.operations::isOwned,png.toUri().toString())
        val callbacks=mutableListOf<(Boolean)->Unit>();var cancellation=0;var business=0
        val scene=ImageComposeScene(width=540,height=220,coroutineContext=coroutineContext);val ui=Ui(scene)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled=false)) {
                    CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {
                        DynamicInlineCommentComposer(onPostComment={message,images,onResult->
                            callbacks+=onResult
                            h.session.postComment("100",message,images,onSubmissionCancelled={cancellation++;onResult(false)}){ok,_->business++;onResult(ok)}
                        },modifier=Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
            }
            ui.await("actual inline composer mounted"){ui.nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null}}
            ui.press(ui.nodes().last{it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any{it.startsWith("添加图片")}})
            ui.await("selected image enables image-only send"){ui.sendEnabled()}
            ui.input("keep draft after cancellation");ui.press(ui.send())
            ui.await("first real composer submit holds image request"){h.uploads.size==1&&!ui.sendEnabled()}
            h.session.loadComments("100");h.uploads[0].complete(picture)
            ui.await("same-owner cancelled submit releases actual busy"){cancellation==1&&ui.sendEnabled()}
            prove(business==0&&h.posts==0,"cancel completion produces no business result/post")
            prove(ui.value()=="keep draft after cancellation","cancel completion retains original text")
            prove(ui.nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("移除图片")},"cancel completion retains original selected image")
            cases+="actual-composer-image-send-refresh-cancel-releases-busy-preserves-draft"
            ui.press(ui.send());ui.await("replacement submit enters same composer busy"){h.uploads.size==2&&!ui.sendEnabled()}
            callbacks[0](false) // Declared stale duplicate callback: actual renderer guard, no mirrored reducer.
            repeat(10){ui.frame()}
            prove(!ui.sendEnabled()&&ui.value()=="keep draft after cancellation","old cancel callback cannot release replacement busy or text")
            cases+="actual-composer-old-cancel-does-not-release-replacement"
            h.uploads[1].complete(picture)
            ui.await("replacement success clears source image/text"){business==1&&h.posts==1&&ui.value().isEmpty()}
            prove(cancellation==1&&ui.nodes().none{it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("移除图片")},"matching success clears only matching original selected image")
            ui.input("next original draft");prove(ui.sendEnabled(),"successful completion releases real editor gate")
            cases+="actual-composer-matching-success-clears-and-next-input-works"
            pointerPairs=ui.pointerPairs;textActions=ui.textActions
        } finally {scene.close()}
    }
    Harness(coroutineContext).use { h ->
        var cancelled=0;var business=0
        h.session.postComment("100","body",listOf("file:///fixture.png"),onSubmissionCancelled={cancelled++}){_,_->business++}
        withTimeout(5000){while(h.uploads.size!=1)yield()}
        h.session.close();repeat(3){yield()}
        prove(h.operations.isOwned()&&cancelled==0&&business==0&&h.posts==0,"retired page with actual account active has zero old UI completion")
        cases+="page-close-never-calls-old-composer"
    }
    Harness(coroutineContext).use { h ->
        var cancelled=0;var business=0
        h.session.postComment("100","body",listOf("file:///fixture.png"),onSubmissionCancelled={cancelled++}){_,_->business++}
        withTimeout(5000){while(h.uploads.size!=1)yield()}
        h.store.saveAccount(mapOf("SESSDATA" to "declared-replacement","bili_jct" to "fixture-csrf"),AccountSummary(42,"Declared replacement",""));h.uploads[0].complete(picture)
        repeat(10){yield()}
        prove(!h.operations.isOwned()&&cancelled==0&&business==0&&h.posts==0,"same MID actual epoch replacement has zero old completion")
        cases+="same-mid-real-store-replacement-no-old-completion"
    }
    val queue=QueueDispatcher()
    Harness(queue).use { h ->
        var cancelled=0;var business=0
        h.session.postComment("100","body",listOf("file:///fixture.png"),onSubmissionCancelled={cancelled++}){_,_->business++}
        h.session.loadComments("100");queue.drain()
        prove(cancelled==1&&business==0&&h.uploads.isEmpty()&&h.posts==0,"queued generation-retired submission finishes busy exactly once before body starts")
        queue.drain();prove(cancelled==1,"completion idempotent")
        cases+="queued-prestart-generation-cancel-completion-once"
    }
    Harness(coroutineContext).use { h ->
        var oldCancelled=0;var oldBusiness=0;var newerCancelled=0;var newerBusiness=0
        h.session.postComment("100","old",listOf("file:///old.png"),onSubmissionCancelled={oldCancelled++}){_,_->oldBusiness++}
        withTimeout(5000){while(h.uploads.size!=1)yield()};h.session.loadComments("100")
        h.session.postComment("100","replacement",listOf("file:///new.png"),onSubmissionCancelled={newerCancelled++}){_,_->newerBusiness++}
        withTimeout(5000){while(h.uploads.size!=2)yield()};h.uploads[0].complete(picture);repeat(5){yield()}
        prove(oldCancelled==0&&oldBusiness==0&&newerCancelled==0&&newerBusiness==0,"old job completion cannot release newer admitted session submission")
        h.uploads[1].complete(picture);withTimeout(5000){while(newerBusiness!=1)yield()}
        prove(h.posts==1&&oldCancelled==0&&newerCancelled==0,"only replacement posts and receives business result")
        cases+="session-old-cancel-cannot-complete-newer-submission"
    }
    val result=buildJsonObject{
        put("status","PASS");put("assertions",assertions);put("caseCount",cases.size);put("cases",JsonArray(cases.map{JsonPrimitive(it)}))
        put("actualCodeSources",JsonArray(identities));put("actualComposePointerPairs",pointerPairs);put("actualEditorSemanticTextActions",textActions)
        put("declaredDeferredRequestTransport",true);put("declaredStaleUiCallbackInjection",true);put("ImageComposeSceneOffscreenOnly",true)
        put("noHWND",true);put("noHTTP",true);put("noSocket",true);put("noNativeChooser",true);put("noRootButtonE2E",true);put("preparedOnly",true)
    }
    Files.writeString(out.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result)+"\n");println(result)
}
