@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import coil3.*
import coil3.decode.DataSource
import coil3.request.SuccessResult
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

private class ReplyScene(val scene:ImageComposeScene) {
    var nanos=0L;var pointers=0
    fun nodes():List<SemanticsNode> {
        fun walk(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun text(value:String)=nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==value}==true}
    fun description(value:String)=nodes().last{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value)==true}
    fun tag(value:String)=nodes().last{it.config.getOrNull(SemanticsProperties.TestTag)==value}
    suspend fun frame(){nanos+=32_000_000;scene.render(nanos).close();delay(4)}
    suspend fun await(label:String,condition:()->Boolean){
        try{withTimeout(6000){while(!condition())frame()};repeat(8){frame()}}
        catch(f:TimeoutCancellationException){error("$label: ${labels()}")}
    }
    suspend fun press(node:SemanticsNode){
        val point=node.boundsInRoot.center
        check(point.x in 0f..760f && point.y in 0f..1200f){"Actual target outside finite viewport $point"}
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+40,buttons=PointerButtons())
        pointers++;repeat(12){frame()}
    }
    suspend fun setText(node:SemanticsNode,value:String){
        press(node);assertTrue(checkNotNull(node.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(value)))
        repeat(10){frame()}
    }
    fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}
private class ReplyPlatform(override val context:DesktopPluginContext,val export:Path):DesktopCommentPlatform {
    var active=true;val calls=mutableListOf<String>()
    var failTranslationOnce=true;var failSaveOnce=true
    override val collapsedReplyPreviewLimit=DesktopOriginalReplySettings.getCommentCollapsedReplyPreviewLimitSync(context)
    override val subReplyLoadedCountEnabled=DesktopOriginalReplySettings.getSubReplyLoadedCountEnabled(context)
    override val emotes=object:DesktopDynamicEmotes {
        override fun snapshot()=emptyMap<String,String>()
        override fun currentSessionKey():Any=1L
        override suspend fun ensureLoaded()=snapshot()
    }
    override fun isOwned()=active
    override fun showFeedback(message:String){calls+="feedback:$message"}
    override fun copyText(text:String,label:String){calls+="copy:$label:$text"}
    override fun shareText(text:String,title:String){calls+="share:$title:$text"}
    override suspend fun videoTitle(bvid:String)=Result.success<String?>(null)
    override suspend fun translateReply(type:Long,oid:Long,rpid:Long):Result<String?> {
        calls+="translate:$type:$oid:$rpid"
        if(failTranslationOnce){failTranslationOnce=false;error("Synthetic adapter failure")}
        return Result.success("Actual synthetic translation")
    }
    override suspend fun blockUser(mid:Long,name:String,face:String,relationSource:BlockedUpRelationSource):BlockedUpWriteResult = error("Block user is not part of this UI proof")
    override suspend fun saveCommentImage(spec:ReplyCommentImageSpec):Boolean {
        calls+="save:${spec.message}"
        if(failSaveOnce){failSaveOnce=false;error("Synthetic adapter failure")}
        return writeDesktopReplyCommentImage(spec,export){active}
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    val loader=ImageLoader.Builder(PlatformContext.INSTANCE).components{
        add(coil3.intercept.Interceptor{chain->SuccessResult(ColorImage(0xff20c7cc.toInt(),400,300),chain.request,DataSource.MEMORY)})
    }.build();SingletonImageLoader.setUnsafe(loader)
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    val initial=json.decodeFromString<ReplyItem>("""{"rpid":700,"oid":100,"type":17,"mid":88,"ctime":1700000000,"count":5,"rcount":5,"like":8,"action":0,"content":{"message":"Original raw comment"},"member":{"mid":"88","uname":"Raw member"},"reply_control":{"translation_switch":2},"replies":[{"rpid":711,"oid":100,"root":700,"parent":700,"content":{"message":"Preview one"},"member":{"mid":"91","uname":"First"}},{"rpid":712,"oid":100,"root":700,"parent":700,"content":{"message":"Preview two"},"member":{"mid":"92","uname":"Second"}},{"rpid":713,"oid":100,"root":700,"parent":700,"content":{"message":"Preview three"},"member":{"mid":"93","uname":"Third"}},{"rpid":714,"oid":100,"root":700,"parent":700,"content":{"message":"Preview four"},"member":{"mid":"94","uname":"Fourth"}}]}""")
    val subject=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Raw subject"}},"module_author":{"mid":88,"name":"Author"},"module_stat":{"comment":{"count":3}}}}""")
    val proofs=mutableListOf<JsonObject>()
    try{for(style in AppUiStyle.entries){
        val root=Files.createTempDirectory("bilipai-original-reply-ui-")
        val platform=ReplyPlatform(DesktopPluginContext(DesktopPluginStore(root.resolve("prefs"))),root.resolve("comment.png"))
        val requests=FixtureReplyRequests();val ownedScope=CoroutineScope(coroutineContext+SupervisorJob())
        // The real thread renderer auto-prefetches short pages. This fixture is
        // one complete page, rather than an endlessly repeating synthetic cursor.
        requests.sub={_,_,_->
            val rootReply=json.decodeFromString<ReplyItem>("""{"rpid":701,"oid":100,"mid":88,"count":1,"rcount":1,"like":8,"content":{"message":"Reply 701"},"member":{"mid":"88","uname":"Raw member"}}""")
            val child=json.decodeFromString<ReplyItem>("""{"rpid":710,"oid":100,"root":701,"parent":701,"like":8,"content":{"message":"Reply 710"},"member":{"mid":"88","uname":"Raw member"}}""")
            json.decodeFromString<ReplyData>("""{"cursor":{"is_end":true,"all_count":1}}""").copy(root=rootReply,replies=listOf(child),grpcNextOffset="")
        }
        val session=DesktopOriginalDynamicReplySession("100",ownedScope,requests,{subject})
        var surface by mutableStateOf("item");var item by mutableStateOf(initial)
        var selectedRoot:Long?=null;var reported:Int?=null;var users=0
        val scene=ImageComposeScene(width=760,height=1200,coroutineContext=coroutineContext);val ui=ReplyScene(scene)
        try{
            scene.setContent{
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)){
                    AppSurface(Modifier.fillMaxSize()){
                        CompositionLocalProvider(LocalDesktopCommentBindings provides platform){
                            when(surface){
                                "item"->ReplyItemView(item=item,upMid=88,onClick={},onSubClick={r,_->selectedRoot=r.rpid},
                                    onLikeClick={item=item.copy(action=if(item.action==1)0 else 1,like=if(item.action==1)8 else 9)},
                                    onHateClick={item=item.copy(action=if(item.action==2)0 else 2)},
                                    onReplyClick={selectedRoot=item.rpid},onReportClick={reported=it},onAvatarClick={users++})
                                "panel"->DesktopOriginalDynamicCommentPanel(subject,session,88L,{users++},Modifier.fillMaxSize())
                                "thread"->DesktopOriginalDynamicThreadContent(session,88L,{users++},modifier=Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            ui.await("original raw item mounted"){"Original raw comment" in ui.labels()}
            ui.press(ui.description("Like"));assertEquals(1,item.action)
            ui.press(ui.description("Like"));assertEquals(0,item.action)
            ui.press(ui.description("点踩评论"));assertEquals(2,item.action)
            ui.press(ui.description("取消点踩"));assertEquals(0,item.action)
            ui.press(ui.text("翻译"));ui.await("ordinary adapter failure recovers its row scope"){platform.calls.contains("feedback:翻译失败，请重试")&&"翻译" in ui.labels()}
            ui.press(ui.text("翻译"));ui.await("original translation publishes"){"Actual synthetic translation" in ui.labels()}
            assertTrue(platform.calls.contains("translate:17:100:700"))
            ui.press(ui.text("原文"));ui.await("original text restored"){"Original raw comment" in ui.labels()}
            ui.press(ui.tag(COMMENT_ACTION_BUTTON_TAG_PREFIX+"700"));ui.await("original action sheet"){"复制全部" in ui.labels()}
            ui.press(ui.text("复制全部"));assertTrue(platform.calls.any{it.startsWith("copy:")&&it.endsWith("Original raw comment")})
            ui.press(ui.tag(COMMENT_ACTION_BUTTON_TAG_PREFIX+"700"));ui.await("save menu"){"保存评论" in ui.labels()}
            ui.press(ui.text("保存评论"));ui.await("save callback failure is contained"){platform.calls.contains("feedback:"+resolveReplyCommentImageSaveToast(false))}
            ui.press(ui.tag(COMMENT_ACTION_BUTTON_TAG_PREFIX+"700"));ui.await("save retry menu"){"保存评论" in ui.labels()}
            ui.press(ui.text("保存评论"));ui.await("actual selected PNG target written"){Files.isRegularFile(platform.export)}
            ui.press(ui.tag(COMMENT_ACTION_BUTTON_TAG_PREFIX+"700"));ui.await("share menu"){"分享评论" in ui.labels()}
            ui.press(ui.text("分享评论"));assertTrue(platform.calls.any{it.startsWith("share:")&&it.contains("comment_root_id=700")})
            ui.press(ui.tag(COMMENT_ACTION_BUTTON_TAG_PREFIX+"700"));ui.await("report menu"){"举报" in ui.labels()}
            ui.press(ui.text("举报"));ui.await("actual original reply reason dialog"){"垃圾广告" in ui.labels()}
            ui.press(ui.text("垃圾广告"));assertEquals(1,reported)
            ui.screenshot(output.resolveSibling("${style.name.lowercase()}-original-reply.png"))
            session.openCommentSheet(subject);surface="panel"
            ui.await("original inline comments with session"){"Reply 701" in ui.labels()&&"Reply 702" in ui.labels()}
            ui.screenshot(output.resolveSibling("${style.name.lowercase()}-original-comment-panel.png"))
            session.openSubReplyFromRoute(701,710);surface="thread"
            ui.await("original thread rendered"){"Reply 710" in ui.labels()}
            // The upstream target route animates the lazy list into place. Wait
            // for its actual sort hit box to settle before a pointer gesture.
            var stableFrames=0;var previousBounds=ui.tag(SUB_REPLY_DETAIL_SORT_TAG).boundsInRoot
            withTimeout(4000){while(stableFrames<8){ui.frame();val bounds=ui.tag(SUB_REPLY_DETAIL_SORT_TAG).boundsInRoot
                stableFrames=if(bounds==previousBounds)stableFrames+1 else 0;previousBounds=bounds}}
            ui.screenshot(output.resolveSibling("${style.name.lowercase()}-thread-before-sort.png"))
            Files.writeString(output.resolveSibling("${style.name.lowercase()}-sort-diagnostic.txt"),
                "before=${session.subReplyState.value}\nbounds=$previousBounds\nsemantics=${ui.tag(SUB_REPLY_DETAIL_SORT_TAG).config}\ncalls=${requests.calls}")
            val sortBefore=session.subReplyState.value.sortMode
            ui.press(ui.tag(SUB_REPLY_DETAIL_SORT_TAG));ui.await("actual sort consumer changed"){session.subReplyState.value.sortMode!=sortBefore&&!session.subReplyState.value.isLoading}
            assertTrue(requests.calls.any{it.startsWith("sub:100:17:701:3:")})
            ui.screenshot(output.resolveSibling("${style.name.lowercase()}-original-thread.png"))
            ui.press(ui.description("Close"));assertFalse(session.subReplyState.value.visible)
            surface="panel";ui.await("composer remains on parent"){ui.nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null}}
            val edit=ui.nodes().last{it.config.getOrNull(SemanticsActions.SetText)!=null}
            ui.setText(edit,"  Synthetic composer message  ")
            val ime=ui.nodes().last{it.config.getOrNull(SemanticsActions.OnImeAction)!=null}
            assertTrue(checkNotNull(ime.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke())
            ui.await("actual owned original post arguments"){requests.calls.any{it=="post:100:17:0:0:Synthetic composer message"}}
            proofs+=buildJsonObject{put("style",style.name);put("actualPointerPairs",ui.pointers);put("originalItemAndActions",true);put("originalInlinePanel",true);put("originalThreadSort",true);put("originalComposerPostFields",true);put("taskPngWritten",true)}
        }finally{scene.close();session.close();ownedScope.cancel()}
    }}finally{loader.shutdown()}
    Files.writeString(output,buildJsonObject{put("passed",true);put("proofs",JsonArray(proofs));put("MainIntegration",false);put("syntheticRequestsOnly",true);put("actualClipboardOrSHARE",false);put("HTTP",false);put("HWND",false)}.toString())
}
