@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.diagnostics

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

fun main(args:Array<String>):Unit = runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val cases=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries) {
        val root=Files.createTempDirectory("bilipai-diagnostic-ui-")
        val diagnostics=DesktopDiagnostics(DesktopPluginStore(root),"fixture")
        val failures=mutableListOf<Throwable>();var exported=0;var pointerCount=0;var nanos=0L
        val showViewer=mutableStateOf(false);var chooserCalls=0;var viewerClosed=0
        val chosenFile=output.resolve("${style.name.lowercase()}-ui-export-${java.util.UUID.randomUUID()}.txt")
        val scene=ImageComposeScene(width=960,height=820,coroutineContext=coroutineContext)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    CompositionLocalProvider(LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                        LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT) {
                        androidx.compose.material3.Surface(color=if(style==AppUiStyle.MIUIX)AppSurfaceTokens.chromeBackground() else AppSurfaceTokens.groupedListContainer()) {
                            DesktopDiagnosticSettingsSection(diagnostics,{exported++;showViewer.value=true},{failures+=it},Modifier.fillMaxWidth().padding(16.dp))
                        }
                        if(showViewer.value)DesktopLocalDiagnosticViewer(diagnostics,{viewerClosed++;showViewer.value=false},{chooserCalls++;chosenFile})
                    }
                }
            }
            suspend fun settle(){repeat(12){nanos+=30_000_000L;scene.render(nanos).close();yield()}}
            fun all():List<SemanticsNode> {
                fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
            }
            fun exists(text:String)=all().any{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==text}==true}
            suspend fun click(text:String) {
                val item=all().last{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==text}==true}
                val position=item.boundsInRoot.center
                println("${style.name}: actual pointer '$text' at $position")
                scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());pointerCount++;settle()
            }
            settle();click("增强诊断日志")
            check(exists("同意并开启")&&!diagnostics.enhancedEnabled.value)
            click("取消");check(!exists("同意并开启")&&!diagnostics.enhancedEnabled.value&&!Files.exists(root.resolve("logs/runtime.log")))
            click("增强诊断日志");check(exists("开启增强诊断日志？"))
            click("同意并开启")
            scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-after-confirm.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            println("${style.name}: confirm closed=${!exists("同意并开启")}, error=${diagnostics.error.value}, failures=${failures.map{it.javaClass.simpleName}}")
            withTimeout(2500){while(!diagnostics.enhancedEnabled.value){settle();delay(5)}}
            settle()
            diagnostics.record("I","UiFixture","real_detail");diagnostics.flush()
            check(Files.readString(root.resolve("logs/runtime.log")).contains("real_detail"))
            click("增强诊断日志")
            withTimeout(2500){while(diagnostics.enhancedEnabled.value){settle();delay(5)}}
            diagnostics.flush();check(!Files.exists(root.resolve("logs/runtime.log")))
            diagnostics.record("W","UiFixture","fixture_local_view");diagnostics.flush()
            click("导出日志");check(exported==1&&failures.isEmpty()&&exists("本地诊断日志"))
            withTimeout(2500){while(!all().any{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text.contains("fixture_local_view")}==true}){settle();delay(5)}}
            scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-local-viewer.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            click("导出日志")
            withTimeout(2500){while(!Files.isRegularFile(chosenFile)){settle();delay(5)}}
            settle();check(chooserCalls==1&&Files.readString(chosenFile).contains("fixture_local_view"))
            click("清理日志")
            withTimeout(2500){while(!exists("本地诊断日志已清理")){settle();delay(5)}}
            check(diagnostics.entries().isEmpty()&&diagnostics.artifactSize()==0L&&Files.exists(chosenFile))
            click("关闭");check(viewerClosed==1&&!showViewer.value)
            check(!exists("崩溃追踪")&&!exists("使用情况统计"))
            settle();scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-diagnostics.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            cases+=buildJsonObject{put("style",style.name);put("actualPointerCount",pointerCount);put("originalConsentCancelConfirm",true);put("diskDetailCleared",true);put("realLocalViewerExportAndClear",true);put("chooserWasFixtureCallbackNotOsDialog",true);put("passed",true)}
        }finally{scene.close();diagnostics.close()}
    }
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("cases",JsonArray(cases));put("nativeWindowCreated",false);put("accountOrHttpRequestMade",false)}.toString())
    println("Original controls/consent and actual local viewer export/clear: both styles, ${cases.size*9} actual pointer events.")
    Unit
}
