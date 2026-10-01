@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.Canvas
import java.nio.file.*

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    val subject=json.decodeFromString<DynamicItem>("""{"id_str":"100","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"100","comment_type":17},"modules":{"module_author":{"mid":88,"name":"Author"},"module_dynamic":{"desc":{"text":"Raw subject"}}}}""")
    fun reply(id:Long,root:Long=0)=json.decodeFromString<ReplyItem>("""{"rpid":$id,"oid":100,"mid":88,"root":$root,"parent":$root,"count":1,"rcount":1,"like":8,"content":{"message":"Reply $id"},"member":{"mid":"88","uname":"Raw member"}}""")
    val proofs=mutableListOf<JsonObject>()
    for((width,style) in listOf(500 to AppUiStyle.MATERIAL3,1100 to AppUiStyle.MATERIAL3,1100 to AppUiStyle.MIUIX)){
        val root=Files.createTempDirectory("bilipai-detail-thread-")
        val platform=LayoutPlatform(DesktopPluginContext(DesktopPluginStore(root)))
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        val requests=FixtureReplyRequests()
        requests.main={_,_,_->json.decodeFromString<ReplyData>("""{"page":{"count":20},"cursor":{"all_count":20,"is_end":true}}""").copy(replies=(701L..720L).map{reply(it)},grpcNextOffset="")}
        requests.sub={_,_,_->json.decodeFromString<ReplyData>("""{"page":{"count":1},"cursor":{"all_count":1,"is_end":true}}""").copy(root=reply(701),replies=listOf(reply(710,701)),grpcNextOffset="")}
        val binding=object:DesktopDynamicReplyRequests by requests{
            override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.success(20)
            override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long):Result<ReplyData>{
                requests.calls+="sub:$oid:$type:$rootId:$mode:$paginationOffset:$targetReplyId"
                return Result.success(json.decodeFromString<ReplyData>("""{"page":{"count":1},"cursor":{"all_count":1,"is_end":true}}""").copy(root=reply(rootId),replies=listOf(reply(rootId+9,rootId)),grpcNextOffset=""))
            }
        }
        val session=DesktopOriginalDynamicReplySession("100",scope,binding,{subject})
        var backs=0
        val scene=ImageComposeScene(width=width,height=800,coroutineContext=coroutineContext)
        val ui=LayoutScene(scene,width,800)
        try{
            scene.setContent{
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)){
                    CompositionLocalProvider(LocalDesktopCommentBindings provides platform,LocalDesktopDetailForeground provides true){
                        DesktopDetailWindow{
                            DesktopOriginalDynamicDetailLayout("100",DesktopOriginalDynamicDetailUiState.Success(subject),session,
                                DynamicDetailImageLayout.EXPANDED,false,88L,onBack={backs++},onRetry={},onUserClick={},
                                card={_,_->AppSurface(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(100f))){AppText("Existing card host seam")}})
                        }
                    }
                }
            }
            suspend fun open(){
                ui.await("original main row"){"Reply 701" in ui.labels()}
                val row=ui.nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="Reply 701"}==true}
                ui.press(row)
                ui.await("actual original modal thread"){session.subReplyState.value.visible&&"评论详情" in ui.labels()&&"Reply 710" in ui.labels()}
                check(requests.calls.contains("sub:100:17:701:2:null:0"))
            }
            open()
            check(scene.semanticsOwners.size>=2){"Thread was not rendered in a separate real Compose modal layer"}
            ui.screenshot(output.resolveSibling("${style.name.lowercase()}-$width-thread-modal.png"))
            ui.press(ui.description("Close"))
            ui.await("actual modal close button"){!session.subReplyState.value.visible&&"评论详情" !in ui.labels()}
            check(backs==0&&session.comments.value.size==20)
            open()
            // Use the pinned runtime's actual AWT->InternalKeyEvent conversion.
            // It is internal to Compose in Kotlin, so the fixture invokes that
            // exact public JVM method instead of pretending AWT is its native type.
            val awtKey=java.awt.event.KeyEvent(Canvas(),java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),0,java.awt.event.KeyEvent.VK_ESCAPE,java.awt.event.KeyEvent.CHAR_UNDEFINED)
            val converted=Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
                .getMethod("toComposeEvent",java.awt.event.KeyEvent::class.java).invoke(null,awtKey)
            val consumed=scene.sendKeyEvent(KeyEvent(converted))
            repeat(12){ui.frame()}
            ui.await("Escape through actual modal key input owner"){!session.subReplyState.value.visible&&"评论详情" !in ui.labels()}
            check(consumed&&backs==0&&session.comments.value.size==20)
            open()
            val header=ui.nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="评论详情"}==true}
            val originalHeaderBounds=header.boundsInRoot
            val start=originalHeaderBounds.center
            scene.sendPointerEvent(PointerEventType.Press,start,timeMillis=ui.nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
            var last=start
            repeat(7){step->
                last=Offset(start.x,(start.y+(step+1)*45f).coerceAtMost(785f))
                scene.sendPointerEvent(PointerEventType.Move,last,timeMillis=ui.nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                repeat(2){ui.frame()}
            }
            val draggedHeader=ui.nodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text=="评论详情"}==true}.boundsInRoot
            check(draggedHeader.top>originalHeaderBounds.top+150f){"Real header drag did not move the actual modal"}
            scene.sendPointerEvent(PointerEventType.Release,last,timeMillis=ui.nanos/1_000_000+30,buttons=PointerButtons())
            ui.pointers++
            // Replace the real root while the old dismiss spring is pending.
            // This direct session call is a deterministic lifecycle fixture,
            // separate from the actual row/Close/drag pointer acceptance above.
            session.openSubReply(session.comments.value.first{it.rpid==702L})
            ui.await("new root retires old header dismiss job"){session.subReplyState.value.rootReply?.rpid==702L&&"Reply 711" in ui.labels()}
            repeat(45){ui.frame()}
            check(session.subReplyState.value.visible&&session.subReplyState.value.rootReply?.rpid==702L)
            ui.press(ui.description("Close"))
            ui.await("replacement modal closes normally"){!session.subReplyState.value.visible}
            proofs+=buildJsonObject{put("style",style.name);put("width",width);put("pointerPairs",ui.pointers)
                put("originalRootClickOpensThread",true);put("actualSeparateComposeModal",true)
                put("closePointerKeepsUnderlyingDetail",true);put("actualEscapeInputCompletedOnly",true)
                put("actualHeaderDragMovesModal",true);put("replacementRootCancelsOldDismissSpring",true)
                put("rootReplacementIsSyntheticLifecycleAdmission",true);put("originalTimeRequestMode",2);put("OSPredictiveGesture",false)}
        }finally{scene.close();session.close();scope.cancel()}
    }
    Files.writeString(output,buildJsonObject{put("passed",true);put("proofs",JsonArray(proofs));put("MainAcceptance",false);put("HWND",false);put("HTTP",false)}.toString())
}
