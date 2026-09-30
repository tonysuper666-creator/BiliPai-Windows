@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.diagnostics

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

/** Original AppAlertDialog rendered offscreen, without shadow renderers or native windows. */
fun main(args:Array<String>):Unit = runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val reviewed=args[1]=="reviewed";val cases=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries) {
        val root=output.resolve("${style.name.lowercase()}-store")
        val diagnostics=DesktopDiagnostics(DesktopPluginStore(root),"offline-viewer-error")
        diagnostics.flush();diagnostics.close() // A real retired consumer makes initial viewLocal fail before file access.
        val failures=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val supervisor=SupervisorJob()
        val handler=CoroutineExceptionHandler{_,failure->failures+=failure}
        val scene=ImageComposeScene(width=960,height=900,coroutineContext=coroutineContext+supervisor+handler)
        var nanos=0L;var closed=0;var chooserCalls=0;val shown=mutableStateOf(true)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    if(shown.value) DesktopLocalDiagnosticViewer(diagnostics,{closed++;shown.value=false},{chooserCalls++;null})
                }
            }
            suspend fun settle(){repeat(12){nanos+=100_000_000L;scene.render(nanos).close();yield();delay(5)}}
            fun all():List<SemanticsNode> {
                fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
            }
            fun text(value:String)=all().any{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==value}==true}
            settle()
            if(reviewed) {
                check(failures.isEmpty()&&text("本地诊断日志无法读取，请重试")&&text("操作失败，请重试"))
                check(!text("正在读取本地日志…")&&chooserCalls==0)
                scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-initial-failure.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
                val node=all().last{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text=="关闭"}==true}
                val position=node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
                settle();check(closed==1&&!shown.value&&failures.isEmpty())
            }else check(failures.any{it is IllegalStateException}&&!text("本地诊断日志无法读取，请重试"))
            cases+=buildJsonObject {
                put("style",style.name);put("actualOriginalDialog",true);put("initialFailureEscaped",failures.isNotEmpty())
                put("safeErrorDisplayed",text("本地诊断日志无法读取，请重试")||reviewed&&closed==1)
                put("actualDismissPointerPairs",closed);put("chooserInvoked",chooserCalls);put("passed",true)
            }
        }finally{scene.close();supervisor.cancelAndJoin()}
    }
    val result=buildJsonObject {put("passed",true);put("mode",args[1]);put("cases",JsonArray(cases));put("nativeWindowCreated",false);put("accountOrHttpUsed",false)}
    Files.writeString(output.resolve("result.json"),result.toString());println(result)
    Unit
}
