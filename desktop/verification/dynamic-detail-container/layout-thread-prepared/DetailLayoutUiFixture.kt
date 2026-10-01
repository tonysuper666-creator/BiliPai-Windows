@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

internal class LayoutPlatform(override val context:DesktopPluginContext,private val owned:()->Boolean={true}):DesktopCommentPlatform {
    override val collapsedReplyPreviewLimit=3
    override val subReplyLoadedCountEnabled=MutableStateFlow(true)
    override val emotes=object:DesktopDynamicEmotes {
        override fun snapshot()=emptyMap<String,String>()
        override fun currentSessionKey():Any=1L
        override suspend fun ensureLoaded()=snapshot()
    }
    override fun isOwned()=owned()
    override fun showFeedback(message:String){}
    override fun copyText(text:String,label:String)=error("Clipboard is outside this proof")
    override fun shareText(text:String,title:String)=error("SHARE is outside this proof")
    override suspend fun videoTitle(bvid:String)=Result.success<String?>(null)
    override suspend fun translateReply(type:Long,oid:Long,rpid:Long)=Result.success<String?>(null)
    override suspend fun blockUser(mid:Long,name:String,face:String,relationSource:BlockedUpRelationSource):BlockedUpWriteResult=error("Mutation is outside this proof")
    override suspend fun saveCommentImage(spec:ReplyCommentImageSpec):Boolean=error("Save is outside this proof")
}

internal class LayoutScene(val scene:ImageComposeScene,val width:Int,val height:Int) {
    var nanos=0L;var pointers=0
    fun nodes():List<SemanticsNode> {
        fun walk(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun description(value:String)=nodes().last{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value)==true}
    suspend fun frame(){nanos+=32_000_000;scene.render(nanos).close();delay(3)}
    suspend fun await(label:String,predicate:()->Boolean){
        try{withTimeout(5000){while(!predicate())frame()};repeat(6){frame()}}
        catch(error:TimeoutCancellationException){error("$label: ${labels()}")}
    }
    suspend fun press(node:SemanticsNode){
        val point=node.boundsInRoot.center
        check(point.x in 0f..width.toFloat()&&point.y in 0f..height.toFloat())
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+30,buttons=PointerButtons())
        pointers++;repeat(10){frame()}
    }
    suspend fun scroll(point:Offset,delta:Float){
        scene.sendPointerEvent(PointerEventType.Scroll,point,scrollDelta=Offset(0f,delta),timeMillis=nanos/1_000_000)
        repeat(12){frame()}
    }
    fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    val subject=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_DRAW","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_author":{"mid":88,"name":"Author"},"module_dynamic":{"major":{"type":"MAJOR_TYPE_DRAW","draw":{"items":[{"src":"https://example.invalid/picture.png","width":400,"height":300}]}}}}}""")
    val proofs=mutableListOf<JsonObject>()
    for((width,style) in listOf(500 to AppUiStyle.MATERIAL3,1100 to AppUiStyle.MATERIAL3,1100 to AppUiStyle.MIUIX)){
        val root=Files.createTempDirectory("bilipai-detail-layout-")
        val platform=LayoutPlatform(DesktopPluginContext(DesktopPluginStore(root)))
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val requests=FixtureReplyRequests()
        requests.main={page,_,_->
            val ids=if(page==1)(701L..720L) else (721L..722L)
            val replies=ids.map{id->json.decodeFromString<ReplyItem>("""{"rpid":$id,"oid":100,"mid":88,"like":8,"content":{"message":"Reply $id"},"member":{"mid":"88","uname":"Raw member"}}""")}
            json.decodeFromString<ReplyData>("""{"page":{"count":22},"cursor":{"all_count":22,"is_end":${page!=1}}}""").copy(replies=replies,grpcNextOffset=if(page==1)"original-cursor" else "")
        }
        val requestBinding=object:DesktopDynamicReplyRequests by requests {
            override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.success(22)
        }
        val session=DesktopOriginalDynamicReplySession("100",scope,requestBinding,{subject})
        var cardBounds=Rect.Zero;var selected=DynamicDetailImageLayout.EXPANDED;var actualWidth=0f
        var backs=0
        val scene=ImageComposeScene(width=width,height=800,coroutineContext=coroutineContext)
        val ui=LayoutScene(scene,width,800)
        try{
            scene.setContent{
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)){
                    CompositionLocalProvider(LocalDesktopCommentBindings provides platform,LocalDesktopDetailForeground provides true){
                        DesktopDetailWindow {
                            actualWidth=LocalWindowSizeClass.current.widthDp.value
                            DesktopOriginalDynamicDetailLayout("100",DesktopOriginalDynamicDetailUiState.Success(subject),
                                session,DynamicDetailImageLayout.EXPANDED,false,88L,onBack={backs++},onRetry={},onUserClick={},
                                card={layout,_->
                                    selected=layout
                                    AppSurface(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(120f)).onGloballyPositioned{cardBounds=it.boundsInRoot()}){
                                        AppText("Synthetic existing CardHost seam: "+layout.name)
                                    }
                                })
                        }
                    }
                }
            }
            ui.await("actual original detail comments"){"Reply 701" in ui.labels()&&cardBounds.width>0f}
            check(actualWidth==width.toFloat())
            val replyNode=ui.nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="Reply 701"}==true}
            val replyBounds=replyNode.boundsInRoot
            if(width<600){check(replyBounds.top>cardBounds.bottom);check(cardBounds.width<=480.5f)}
            else{check(replyBounds.left>width/2f);check(cardBounds.right<=width/2f+1f)}
            ui.press(ui.description("切换图片展示（当前：展开大图）"))
            ui.await("original per-subject image override"){selected==DynamicDetailImageLayout.THUMBNAIL}
            ui.press(ui.description("切换图片展示（当前：缩略图）"))
            ui.await("original image override toggles back"){selected==DynamicDetailImageLayout.EXPANDED}
            check(requests.calls.none{it.startsWith("main:100:17:2:")})
            val cardBefore=cardBounds
            if(width>=600){
                repeat(6){ui.scroll(Offset(250f,400f),15f)}
                check(requests.calls.none{it.startsWith("main:100:17:2:")}){"Scrolling the separate primary card requested comment page 2"}
            }
            repeat(12){ui.scroll(Offset(if(width<600)250f else 850f,400f),15f)}
            ui.await("same actual active lazy list auto loads original cursor"){requests.calls.any{it=="main:100:17:2:20:3:original-cursor:true"}&&session.comments.value.size==22}
            check(requests.calls.count{it.startsWith("main:100:17:2:")}==1)
            if(width>=600)check(cardBounds==cardBefore){"Secondary list scroll moved the separate primary card"}
            ui.press(ui.description("返回"));check(backs==1)
            ui.screenshot(output.resolveSibling("${style.name.lowercase()}-$width-detail-layout.png"))
            proofs+=buildJsonObject{put("style",style.name);put("width",width);put("pointerPairs",ui.pointers)
                put("measuredWindowWidth",actualWidth);put("originalTwoPane",width>=600)
                put("originalImageLayoutOverride",true);put("originalRawComments",true)
                put("actualActiveListAutoPagination",true);put("originalCursorPreserved",true)
                put("initialTwentyRowsDoNotRequestPageTwo",true);put("otherSplitLaneCannotRequestPageTwo",width>=600)
                put("existingCardHostIsSyntheticSeam",true);put("liquidMaterialVerified",false)}
        }finally{scene.close();session.close();scope.cancel()}
    }
    Files.writeString(output,buildJsonObject{put("passed",true);put("proofs",JsonArray(proofs));put("MainAcceptance",false);put("HWND",false);put("HTTP",false)}.toString())
}
