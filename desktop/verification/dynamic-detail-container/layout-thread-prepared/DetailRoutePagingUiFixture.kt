@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    val json=Json{ignoreUnknownKeys=true;coerceInputValues=true}
    fun subject(id:String)=json.decodeFromString<DynamicItem>("""{"id_str":"$id","type":"DYNAMIC_TYPE_WORD","basic":{"comment_id_str":"$id","comment_type":17},"modules":{"module_dynamic":{"desc":{"text":"Subject $id"}}}}""")
    fun page(subjectId:Long,start:Long,end:Long,final:Boolean)=json.decodeFromString<ReplyData>("""{"page":{"count":22},"cursor":{"all_count":22,"is_end":$final}}""").copy(
        replies=(start..end).map{id->json.decodeFromString<ReplyItem>("""{"rpid":$id,"oid":$subjectId,"mid":88,"content":{"message":"Reply $id"},"member":{"mid":"88","uname":"Raw member"}}""")},
        grpcNextOffset=if(final)"" else "owned-new-cursor")
    for(width in listOf(500,1100)){
        val root=Files.createTempDirectory("bilipai-detail-route-")
        val platform=LayoutPlatform(DesktopPluginContext(DesktopPluginStore(root)))
        val parent=CoroutineScope(coroutineContext+SupervisorJob())
        val entered=CompletableDeferred<Unit>();val late=CompletableDeferred<ReplyData>()
        var oldOwned=true
        val oldRequests=FixtureReplyRequests().apply{main={_,_,_->entered.complete(Unit);withContext(NonCancellable){late.await()}}}
        val oldBinding=object:DesktopDynamicReplyRequests by oldRequests{
            override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.success(22)
        }
        val oldSession=DesktopOriginalDynamicReplySession("100",parent,oldBinding,{subject("100")},{oldOwned})
        val newRequests=FixtureReplyRequests().apply{main={number,_,_->if(number==1)page(200,901,920,false)else page(200,921,922,true)}}
        val newBinding=object:DesktopDynamicReplyRequests by newRequests{
            override suspend fun getCommentCountForSubject(oid:Long,type:Int)=Result.success(22)
        }
        val newSession=DesktopOriginalDynamicReplySession("200",parent,newBinding,{subject("200")})
        var activeItem by mutableStateOf(subject("100"));var activeSession by mutableStateOf(oldSession)
        var rootRpid by mutableLongStateOf(701);var targetRpid by mutableLongStateOf(710)
        val scene=ImageComposeScene(width=width,height=800,coroutineContext=coroutineContext);val ui=LayoutScene(scene,width,800)
        try{
            scene.setContent{
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled=false)){
                    CompositionLocalProvider(LocalDesktopCommentBindings provides platform,LocalDesktopDetailForeground provides true){
                        DesktopDetailWindow{
                            DesktopOriginalDynamicDetailLayout(activeItem.id_str,DesktopOriginalDynamicDetailUiState.Success(activeItem),activeSession,
                                DynamicDetailImageLayout.EXPANDED,false,88L,rootRpid,targetRpid,onBack={},onRetry={},onUserClick={},
                                card={_,_->AppSurface(Modifier.fillMaxWidth().height(androidx.compose.ui.unit.Dp(110f))){AppText("Subject "+activeItem.id_str)}})
                        }
                    }
                }
            }
            ui.await("old routed target enters actual initial request"){entered.isCompleted}
            check(oldRequests.calls.count{it.startsWith("main:100:17:1:")}==1)
            check(oldRequests.calls.none{it.startsWith("sub:")||it.startsWith("main:100:17:2:")})
            oldOwned=false;oldSession.close()
            activeItem=subject("200");activeSession=newSession;rootRpid=0;targetRpid=0
            ui.await("replacement actual route first measured page"){"Reply 901" in ui.labels()}
            check(newSession.comments.value.size==20)
            check(newRequests.calls.none{it.startsWith("main:200:17:2:")})
            late.complete(page(100,701,720,false));repeat(20){ui.frame()}
            check(oldSession.comments.value.isEmpty())
            check("Reply 701" !in ui.labels())
            check(oldRequests.calls.none{it.startsWith("sub:")||it.startsWith("main:100:17:2:")})
            check(newRequests.calls.count{it.startsWith("main:200:17:1:")}==1)
            check(newRequests.calls.none{it.startsWith("main:200:17:2:")})
            if(width>=600){repeat(6){ui.scroll(Offset(250f,400f),15f)};check(newRequests.calls.none{it.startsWith("main:200:17:2:")})}
            repeat(12){ui.scroll(Offset(if(width<600)250f else 850f,400f),15f)}
            ui.await("replacement actual scrolled list requests only its cursor"){newSession.comments.value.size==22}
            check(newRequests.calls.count{it=="main:200:17:2:20:3:owned-new-cursor:true"}==1)
            check(oldRequests.calls.none{it.startsWith("sub:")||it.startsWith("main:100:17:2:")})
        }finally{late.complete(page(100,701,720,false));scene.close();oldSession.close();newSession.close();parent.cancel()}
    }
    Files.writeString(output,buildJsonObject{put("passed",true);put("widths",JsonArray(listOf(JsonPrimitive(500),JsonPrimitive(1100))))
        put("lateNonCancellableOldTargetCannotRouteThreadOrPageTwo",true);put("replacementMeasuredTwentyStartsAtTop",true)
        put("sameActualSecondaryListWheelRequestsOnlyCurrentCursorOnce",true);put("otherSplitLaneNoRequest",true)
        put("originalRequestAndTargetModels",true);put("MainAcceptance",false);put("HTTP",false);put("HWND",false)}.toString())
}
