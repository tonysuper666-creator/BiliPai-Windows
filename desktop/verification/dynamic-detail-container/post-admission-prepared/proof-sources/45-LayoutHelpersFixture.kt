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

