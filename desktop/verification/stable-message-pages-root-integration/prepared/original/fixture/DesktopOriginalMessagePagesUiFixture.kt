@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.feature.message.*
import com.android.purebilibili.feature.message.feed.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

private class MessageUi(context:kotlin.coroutines.CoroutineContext) {
    private val job=SupervisorJob();private val errors=mutableListOf<Throwable>()
    private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,e->errors+=e})
    val scene=ImageComposeScene(1100,900,coroutineContext=scope.coroutineContext)
    var nanos=0L
    val pointers=mutableListOf<JsonObject>()
    fun nodes():List<SemanticsNode> {
        fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap {tree(it.unmergedRootSemanticsNode)}
    }
    fun texts()=nodes().flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}
    suspend fun frame() {nanos+=30_000_000;scene.render(nanos).close();yield();delay(5);check(errors.isEmpty()){errors.toString()}}
    suspend fun await(label:String,condition:()->Boolean) {withTimeout(7000){while(!condition())frame()};repeat(8){frame()}}
    suspend fun click(label:String) {
        fun matches()=nodes().filter {n-> n.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true || n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true}
        await(label) {matches().any {it.boundsInRoot.width>0 && it.boundsInRoot.height>0 && it.boundsInRoot.bottom<=900}}
        val node=matches().first {it.boundsInRoot.width>0 && it.boundsInRoot.height>0 && it.boundsInRoot.bottom<=900};val point=node.boundsInRoot.center
        pointers+=buildJsonObject {put("label",label);put("x",point.x);put("y",point.y)}
        scene.sendPointerEvent(PointerEventType.Press,point,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,point,timeMillis=nanos/1_000_000+60,buttons=PointerButtons());repeat(12){frame()}
    }
    fun save(path:Path) {scene.render(nanos+1).use {Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    fun dump(path:Path) {Files.writeString(path,nodes().joinToString("\n"){"${it.boundsInRoot} ${it.config}"})}
    suspend fun close() {scene.close();job.cancelAndJoin()}
}
fun main(args:Array<String>)=runBlocking {
    val dir=Path.of(args[0]);Files.createDirectories(dir);val checks=mutableListOf<JsonObject>()
    saveMessageLoadedIdentity(dir.resolve("actual-loaded-12-class-identities.json"))
    for(style in listOf(AppUiStyle.MATERIAL3,AppUiStyle.MIUIX)) {
        Fixture().use { f ->
            // Actual original VMs + production admission/services. Only its allowlisted HTTP socket is task-owned.
            val vm=InboxViewModel(f.admission);val reply=ReplyMeViewModel(f.admission)
            val global=DesktopHomeCardPreferences(DesktopPluginContext(DesktopPluginStore(f.root.resolve("global"))))
            val timeline=DesktopDynamicTimelinePreferences(global.context)
            val ui=MessageUi(coroutineContext);val actions=mutableListOf<String>();var showReply by mutableStateOf(false)
            try {
                ui.scene.setContent {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=AppThemeMode.LIGHT,hapticFeedbackEnabled=false)) {
                        CompositionLocalProvider(LocalDesktopDetailForeground provides true,LocalDesktopHomeCardPreferences provides global,LocalDesktopDynamicTimelinePreferences provides timeline) {
                        if(showReply) ReplyMeScreen(onBack={showReply=false;actions+="back"},onOpenLink={actions+="link:$it"},onOpenSpace={actions+="space:$it"},viewModel=reply)
                        else InboxScreen(onBack={actions+="root-back"},onTopItemClick={actions+="destination:$it";if(it==MessageCenterDestination.ReplyMe)showReply=true},
                            onSessionClick={id,type,name->actions+="chat:$id:$type:$name"},viewModel=vm)
                        }
                    }
                }
                ui.await("real original session text") { "Known7" in ui.texts() && !vm.uiState.value.isLoading }
                ui.save(dir.resolve("${style.name}-01-inbox.png"));ui.dump(dir.resolve("${style.name}-01-semantics.txt"))
                ui.click("Known7");check(actions.contains("chat:7:1:Known7"))
                val categoryLabel=ui.texts().first {it=="关注" || it=="关注 2"}
                ui.click(categoryLabel);ui.await("actual category request") {f.hits.any {it.path.endsWith("get_sessions") && it.query["session_type"]=="9"}}
                check(vm.uiState.value.selectedCategory==MessageSessionCategory.Follow)
                ui.click("回复我的");ui.await("actual notification Screen") {showReply && "回复我的" in ui.texts()}
                check(actions.contains("destination:ReplyMe"));ui.save(dir.resolve("${style.name}-02-reply.png"));ui.dump(dir.resolve("${style.name}-02-semantics.txt"))
                ui.click("返回");check(!showReply && actions.last()=="back")
                checks+=buildJsonObject {put("style",style.name);put("actualOriginalScreens",2);put("actualPointerActions",JsonArray(ui.pointers));put("actualTypedNavigationArguments",JsonArray(actions.map(::JsonPrimitive)));put("actualCategoryEndpoint",true)}
            } finally {ui.close()}
        }
    }
    Files.writeString(dir.resolve("ui-report.json"),buildJsonObject {put("passed",true);put("cells",JsonArray(checks));put("preparedOverlays",true);put("wholeRootAccepted",false);put("nativeWindowOpened",false);put("actualAccountRequests",0);put("chooserAccepted",false)}.toString())
    println("PASS 2 styles real original Inbox/Reply Screen pointer workflows (prepared overlay, not Root acceptance)")
}
