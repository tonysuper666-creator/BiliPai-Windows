@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.feature.dynamic.components.DesktopOriginalDynamicDetailComposer
import com.bilipai.desktop.appearance.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import top.yukonga.miuix.kmp.blur.*
import java.nio.file.*
import java.nio.file.Path as NioPath
import java.security.MessageDigest

internal class ComposerScene(val scene:ImageComposeScene) {
    var nanos=0L;var pointers=0;var imeActions=0;var semanticsTextEntries=0
    fun nodes():List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun field()=nodes().last{it.config.getOrNull(SemanticsActions.SetText)!=null}
    fun value()=field().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    suspend fun frame(){nanos+=32_000_000L;scene.render(nanos).close();delay(3)}
    suspend fun await(label:String,condition:()->Boolean){
        try{withTimeout(5000){while(!condition())frame()};repeat(8){frame()}}
        catch(e:TimeoutCancellationException){error("$label: ${labels()}")}
    }
    suspend fun press(n:SemanticsNode){
        val pos=n.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press,pos,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,pos,timeMillis=nanos/1_000_000+30,buttons=PointerButtons())
        pointers++;repeat(8){frame()}
    }
    suspend fun input(text:String){
        // Actual semantics editor action, NOT claimed physical character/OS IME input.
        check(checkNotNull(field().config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)))
        semanticsTextEntries++;await("original field receives editor action"){value()==text}
    }
    suspend fun send(){
        val node=nodes().last{it.config.getOrNull(SemanticsActions.OnImeAction)!=null}
        check(checkNotNull(node.config.getOrNull(SemanticsActions.OnImeAction)?.action).invoke())
        imeActions++;repeat(10){frame()}
    }
    fun screenshot(path:NioPath):String {
        val bytes=scene.render(nanos+1).use{checkNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes}
        Files.write(path,bytes)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=NioPath.of(args[0]);Files.createDirectories(output.parent)
    val proofs=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries) for(glass in listOf(false,true)) {
        var target:String? by mutableStateOf(null)
        var sourceId by mutableStateOf("one")
        var userEnabled by mutableStateOf(true)
        val events=mutableListOf<String>()
        val scene=ImageComposeScene(width=500,height=220,coroutineContext=coroutineContext)
        val ui=ComposerScene(scene)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=userEnabled),
                        LocalLiquidGlassRenderConfig provides LiquidGlassRenderConfig(resolveLiquidGlassTuning(progress=0.5f),BottomBarLiquidGlassPreset.BILIPAI_TUNED)) {
                        val page=rememberLayerBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().layerBackdrop(page).background(Brush.linearGradient(listOf(Color(0xff3577bc),Color(0xffd85455),Color(0xff228858)))))
                            key(sourceId) {
                                DesktopOriginalDynamicDetailComposer(onPostComment={events+="post:$sourceId:$it:target=$target"},replyTargetUname=target,
                                    onClearReplyTarget={events+="clear:$sourceId";target=null},liquidGlassEnabled=glass,backdrop=page,
                                    modifier=Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal=16.dp))
                            }
                        }
                    }
                }
            }
            ui.await("original composer is mounted"){ui.nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null}}
            check(ui.field().boundsInRoot.width>350f)
            ui.press(ui.field());ui.input("   ");ui.send()
            check(events.isEmpty()){"Blank IME was sent"}
            ui.input("  Original hello  ");ui.send()
            check(events==listOf("post:one:Original hello:target=null"))
            check(ui.value().isEmpty()){"Successful original IME submit did not clear local text"}
            check(ui.field().config.getOrNull(SemanticsProperties.Focused)!=true){"Original submit did not clear focus"}
            target="Raw reply member"
            ui.await("original delayed reply focus"){ui.field().config.getOrNull(SemanticsProperties.Focused)==true}
            check(ui.labels().any{it.contains("Raw reply member")})
            val withTarget=ui.field().boundsInRoot.width
            ui.input("keep this draft")
            ui.press(ui.nodes().last{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("取消回复")==true})
            ui.await("real clear target pointer updates original field"){target==null&&ui.labels().none{it.contains("Raw reply member")}}
            check(ui.value()=="keep this draft"){"Cancel reply discarded original local draft"}
            check(ui.field().boundsInRoot.width>withTarget)
            target="Reply again"
            ui.await("reply target requests focus again"){ui.field().config.getOrNull(SemanticsProperties.Focused)==true}
            ui.input("  Targeted hello  ");ui.send()
            check(events.takeLast(2)==listOf("post:one:Targeted hello:target=Reply again","clear:one")){events.toString()}
            check(ui.value().isEmpty()&&target==null)
            val activeHash=ui.screenshot(output.resolveSibling("${style.name.lowercase()}-${if(glass)"glass" else "normal"}-composer.png"))
            userEnabled=false
            ui.await("actual original user setting disables shell"){!userEnabled}
            val offHash=ui.screenshot(output.resolveSibling("${style.name.lowercase()}-${if(glass)"glass" else "normal"}-user-off.png"))
            if(glass)check(activeHash!=offHash){"Original glass shell did not affect actual raster"}
            ui.press(ui.field());ui.input("retired local draft")
            target="Retired reply"
            sourceId="two";target=null
            ui.await("new keyed original composer does not inherit local draft"){ui.value().isEmpty()}
            val before=events.size;ui.send();check(events.size==before)
            check(ui.nodes().none{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("取消回复")==true})
            proofs+=buildJsonObject {
                put("style",style.name);put("liquidCallerEnabled",glass);put("pointerPairs",ui.pointers);put("semanticsTextEntries",ui.semanticsTextEntries);put("actualImeActions",ui.imeActions)
                put("blankDoesNotSubmit",true);put("trimSubmitClearAndFocusOrder",true);put("replyDelayFocus",true);put("clearPointerPreservesDraft",true)
                put("sameOriginalUserGate",true);put("newKeyRetiresOldDraftAndReplyFocus",true);put("activeRasterSha256",activeHash);put("userOffRasterSha256",offHash)
            }
        } finally {scene.close()}
    }
    Files.writeString(output,buildJsonObject {put("passed",true);put("proofs",JsonArray(proofs));put("physicalCharacterInput",false);put("OSIMEWindow",false)
        put("preparedComposerAndRenderer",true);put("actualMain25Base",true);put("MainConsumerAcceptance",false);put("HWND",false);put("HTTP",false)}.toString())
}
