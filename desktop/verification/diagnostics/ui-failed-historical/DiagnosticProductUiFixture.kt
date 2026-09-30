@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.diagnostics.proof

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.AppThemeMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.diagnostics.*
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Only the fixture is compiled. All production classes resolve to the immutable main JAR. */
private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private fun writeJson(path:Path,value:JsonElement){Files.writeString(path,value.toString())}
private interface ReadLockKernel32:StdCallLibrary {
    fun CreateFileW(name:WString,access:Int,share:Int,security:Pointer?,creation:Int,flags:Int,template:Pointer?):Pointer?
    fun CloseHandle(handle:Pointer):Boolean
}

private class UiHarness(width:Int,val height:Int,context:kotlin.coroutines.CoroutineContext) {
    val errors=CopyOnWriteArrayList<Throwable>()
    private val job=SupervisorJob()
    val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,failure->errors+=failure})
    val scene=ImageComposeScene(width=width,height=height,coroutineContext=scope.coroutineContext)
    private var nanos=0L
    val pointers=mutableListOf<JsonObject>()
    suspend fun settle(){repeat(12){nanos+=30_000_000L;scene.render(nanos).close();yield();delay(3)}}
    fun nodes():List<SemanticsNode> {
        fun visit(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::visit)
        return scene.semanticsOwners.flatMap{visit(it.unmergedRootSemanticsNode)}
    }
    fun texts():List<String> = nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text)?.map{t->t.text}.orEmpty()}
    private fun topLayerNodes():List<SemanticsNode> {
        fun visit(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::visit)
        return scene.semanticsOwners.lastOrNull()?.let{visit(it.unmergedRootSemanticsNode)}.orEmpty()
    }
    fun has(text:String)=text in texts()
    fun contains(text:String)=texts().any{text in it}
    fun dump():JsonArray=JsonArray(nodes().map{node ->buildJsonObject {
        put("text",JsonArray(node.config.getOrNull(SemanticsProperties.Text)?.map{JsonPrimitive(it.text)}.orEmpty()))
        put("left",node.boundsInRoot.left);put("top",node.boundsInRoot.top);put("right",node.boundsInRoot.right);put("bottom",node.boundsInRoot.bottom)
        put("onClick",node.config.getOrNull(SemanticsActions.OnClick)!=null)
    }})
    suspend fun await(condition:()->Boolean){withTimeout(4000){while(!condition()){settle();delay(5)}};settle();check(errors.isEmpty()){errors.toString()}}
    suspend fun click(text:String) {
        // A popup transition can retain unplaced semantics copies at the zero rectangle.
        // Hit only an actually laid-out text node, as a pointer user would.
        // A modal's clipped action must not accidentally fall back to a same-text underlay row.
        await{topLayerNodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text}==true&&it.boundsInRoot.width>0&&it.boundsInRoot.height>0}}
        val node=topLayerNodes().last{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==text}==true&&it.boundsInRoot.width>0&&it.boundsInRoot.height>0}
        val bounds=node.boundsInRoot;val position=bounds.center
        check(position.x in 0f..960f&&position.y in 0f..height.toFloat()){ "Pointer outside finite viewport: $text $bounds" }
        pointers+=buildJsonObject{put("text",text);put("x",position.x);put("y",position.y);put("nodeLeft",bounds.left);put("nodeTop",bounds.top);put("nodeRight",bounds.right);put("nodeBottom",bounds.bottom);put("pressMillis",nanos/1_000_000);put("releaseMillis",nanos/1_000_000+55)}
        scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
        println("actual pointer '$text' at $position, height=$height");settle();check(errors.isEmpty()){errors.toString()}
    }
    fun save(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    suspend fun close(){scene.close();job.cancelAndJoin()}
}

private fun checkGlobalPreference(root:Path,enabled:Boolean):JsonObject {
    val document=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
    check(document["settings"]!!.jsonObject["enhanced_diagnostic_logging_enabled"]!!.jsonPrimitive.boolean==enabled)
    check(document.keys.none{it=="accounts"||it=="guest"})
    check(DesktopDiagnosticSettings(DesktopPluginStore(root)).getEnhancedDiagnosticLoggingEnabledSync()==enabled)
    return document
}

private suspend fun normalCase(output:Path,style:AppUiStyle,theme:AppThemeMode,height:Int,context:kotlin.coroutines.CoroutineContext):JsonObject {
    val id="${style.name.lowercase()}-${theme.name.lowercase()}-$height"
    val directory=output.resolve(id);Files.createDirectories(directory)
    val root=directory.resolve("task-owned-global-store");Files.createDirectories(root)
    val store=DesktopPluginStore(root)
    val diagnostics=openDesktopDiagnostics(store,"product-ui-proof").getOrThrow()
    val lifecycle=DesktopDiagnosticLifecycle(diagnostics)
    val harness=UiHarness(960,height,context)
    var localRequests=0;var chooserCalls=0;var dismissed=0
    var stage="default-settings"
    val shown=mutableStateOf(false);val callbacks=CopyOnWriteArrayList<Throwable>()
    val export=directory.resolve("actual-fixture-selected-export.txt")
    val state=MutableStateFlow(PlayerState())
    val observer=lifecycle.observePlayback(state,harness.scope)
    try {
        check(!diagnostics.enhancedEnabled.value&&!DesktopDiagnosticSettings(store).getEnhancedDiagnosticLoggingEnabledSync())
        harness.scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=theme,hapticFeedbackEnabled=false)) {
                CompositionLocalProvider(LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                    LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT) {
                    androidx.compose.material3.Surface(color=if(style==AppUiStyle.MIUIX)AppSurfaceTokens.chromeBackground() else AppSurfaceTokens.groupedListContainer()) {
                        DesktopDiagnosticSettingsSection(diagnostics,{localRequests++;shown.value=true},{callbacks+=it},Modifier.fillMaxWidth().padding(16.dp))
                    }
                    if(shown.value)DesktopLocalDiagnosticViewer(diagnostics,{dismissed++;shown.value=false},{chooserCalls++;export})
                }
            }
        }
        harness.settle();harness.save(directory.resolve("01-settings-default.png"))
        android.util.Log.i("UiProof","off_info_must_not_persist")
        diagnostics.flush();check(diagnostics.entries().none{"off_info_must_not_persist" in it.message})
        stage="consent-cancel";harness.click("增强诊断日志")
        check(harness.has("开启增强诊断日志？")&&harness.has("同意并开启")&&!diagnostics.enhancedEnabled.value)
        harness.save(directory.resolve("02-original-consent.png"))
        harness.click("取消")
        check(!harness.has("同意并开启")&&!diagnostics.enhancedEnabled.value&&!Files.exists(root.resolve("logs/runtime.log")))
        check(!Files.exists(root.resolve("plugin-settings.json")))
        stage="consent-confirm";harness.click("增强诊断日志");check(harness.has("同意并开启"))
        harness.click("同意并开启");harness.await{diagnostics.enhancedEnabled.value}
        writeJson(directory.resolve("settings-enabled.json"),checkGlobalPreference(root,true))
        android.util.Log.i("UiProof","actual_bridge_detail")
        com.android.purebilibili.core.util.Logger.d("UiProof","actual_logger_detail")
        state.value=PlayerState(ready=true,paused=true,videoCodec="avc1",audioCodec="aac",hardwareDecoder="d3d11va",sourceTitle="excluded-title",subtitleText="excluded-subtitle")
        harness.await{harness.errors.isEmpty()&&diagnostics.enhancedEnabled.value}
        diagnostics.flush()
        val detail=Files.readString(root.resolve("logs/runtime.log"))
        check("actual_bridge_detail" in detail&&"actual_logger_detail" in detail&&"video=avc1" in detail)
        check("excluded-title" !in detail&&"excluded-subtitle" !in detail)
        Files.writeString(directory.resolve("detail-before-disable.txt"),detail)
        stage="disable-detail";harness.click("增强诊断日志");harness.await{!diagnostics.enhancedEnabled.value}
        diagnostics.flush();check(!Files.exists(root.resolve("logs/runtime.log")))
        writeJson(directory.resolve("settings-disabled.json"),checkGlobalPreference(root,false))
        android.util.Log.w("UiProof","fixture_local_view")
        com.android.purebilibili.core.util.Logger.e("UiProof","fixture_error_bridge",IllegalStateException("fixture cause"))
        diagnostics.flush();check(Files.readString(root.resolve("logs/basic.log")).contains("fixture_local_view"))
        stage="open-real-viewer";harness.click("导出日志");check(localRequests==1&&harness.has("本地诊断日志"))
        harness.await{harness.contains("fixture_local_view")&&!harness.has("正在读取本地日志…")}
        writeJson(directory.resolve("viewer-read-semantics.json"),JsonArray(harness.texts().map(::JsonPrimitive)))
        harness.save(directory.resolve("03-actual-local-viewer.png"))
        stage="viewer-export";harness.click("导出日志");harness.await{harness.has("日志已导出到你选择的本地文件")}
        check(chooserCalls==1&&Files.isRegularFile(export)&&Files.readString(export).contains("fixture_local_view"))
        val exportedBytes=Files.readAllBytes(export)
        harness.save(directory.resolve("after-export-result.png"))
        writeJson(directory.resolve("after-export-semantics.json"),harness.dump())
        stage="viewer-clear-after-export";harness.click("清理日志");harness.await{harness.has("本地诊断日志已清理")}
        check(diagnostics.entries().isEmpty()&&diagnostics.artifactSize()==0L)
        check(Files.readAllBytes(export).contentEquals(exportedBytes))
        harness.save(directory.resolve("04-cleared-viewer.png"))
        stage="viewer-dismiss";harness.click("关闭");check(dismissed==1&&!shown.value&&callbacks.isEmpty())
        check(!harness.has("崩溃追踪")&&!harness.has("使用情况统计"))
        harness.save(directory.resolve("05-settings-disabled.png"))
        writeJson(directory.resolve("pointer-events.json"),JsonArray(harness.pointers))
        lifecycle.shutdownForRestore()
        check(observer.isCompleted&&!diagnostics.record("W","UiProof","retired_write"))
        check(android.util.Log.w("UiProof","retired_bridge")==0)
        check(harness.pointers.size==9)
        return buildJsonObject {
            put("id",id);put("style",style.name);put("theme",theme.name);put("width",960);put("height",height)
            put("actualPointerPairs",harness.pointers.size);put("originalConsentCancelConfirm",true)
            put("sameOriginalGlobalPreferenceKey",true);put("actualFactoryBridgeAndLifecycle",true)
            put("finitePlaybackObserverOnlyNotNativePlayer",true);put("detailRemovedOnDisable",true)
            put("actualLocalViewerReadExportClear",true);put("chosenFile",export.toString());put("exportSha256Bytes",sha(exportedBytes))
            put("chooserWasFixtureCallbackNotOsDialog",true);put("callbackFailures",callbacks.size);put("escapedCoroutineFailures",harness.errors.size);put("passed",true)
        }
    } catch(failure:Exception) {
        harness.save(directory.resolve("failed-flow-actual.png"))
        writeJson(directory.resolve("failed-flow-semantics.json"),harness.dump())
        writeJson(directory.resolve("pointer-events.json"),JsonArray(harness.pointers))
        return buildJsonObject {
            put("id",id);put("style",style.name);put("theme",theme.name);put("width",960);put("height",height)
            put("passed",false);put("failedStage",stage);put("failure",failure.javaClass.simpleName)
            put("actualPointerPairs",harness.pointers.size);put("sameOriginalGlobalPreferenceKey",!diagnostics.enhancedEnabled.value&&Files.exists(root.resolve("plugin-settings.json")))
            put("chosenFileCreated",Files.isRegularFile(export));put("chooserCalls",chooserCalls);put("actualDismissCalls",dismissed)
            put("callbackFailures",callbacks.size);put("escapedCoroutineFailures",harness.errors.size)
            put("consentCancelConfirmCompleted",stage.startsWith("viewer-")||stage=="open-real-viewer")
            put("chooserWasFixtureCallbackNotOsDialog",true)
        }
    } finally {harness.close();lifecycle.shutdownForRestore()}
}

private suspend fun initialReadFailure(output:Path,style:AppUiStyle,theme:AppThemeMode,height:Int,context:kotlin.coroutines.CoroutineContext):JsonObject {
    val id="${style.name.lowercase()}-${theme.name.lowercase()}-$height-initial-io-failure"
    val directory=output.resolve(id);Files.createDirectories(directory)
    val root=directory.resolve("task-owned-global-store");Files.createDirectories(root)
    val diagnostics=openDesktopDiagnostics(DesktopPluginStore(root),"product-ui-failure-proof").getOrThrow()
    val lifecycle=DesktopDiagnosticLifecycle(diagnostics)
    val harness=UiHarness(960,height,context);var closed=0;var chooserCalls=0;val shown=mutableStateOf(true)
    val kernel=Native.load("kernel32",ReadLockKernel32::class.java)
    var handle:Pointer?=null
    try {
        diagnostics.record("W","UiProof","first_read_task_file");diagnostics.flush()
        val file=root.resolve("logs/basic.log")
        handle=kernel.CreateFileW(WString(file.toAbsolutePath().toString()),0x80000000.toInt(),0,null,3,0x80,null)
        check(handle!=null&&Pointer.nativeValue(handle)!=-1L){"Unable to acquire actual Win32 read-sharing denial"}
        val denial=runCatching{Files.readAllBytes(file)}.exceptionOrNull()
        check(denial is java.io.IOException){"Expected actual IO read denial, got $denial"}
        harness.scene.setContent {
            DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,themeMode=theme,hapticFeedbackEnabled=false)) {
                if(shown.value)DesktopLocalDiagnosticViewer(diagnostics,{closed++;shown.value=false},{chooserCalls++;null})
            }
        }
        harness.await{harness.has("本地诊断日志无法读取，请重试")&&harness.has("操作失败，请重试")}
        check(harness.errors.isEmpty()&&!harness.has("正在读取本地日志…")&&chooserCalls==0)
        check(harness.texts().none{root.toString() in it||"first_read_task_file" in it})
        harness.save(directory.resolve("actual-safe-first-io-error.png"))
        writeJson(directory.resolve("error-semantics.json"),JsonArray(harness.texts().map(::JsonPrimitive)))
        harness.click("关闭");check(closed==1&&!shown.value&&harness.errors.isEmpty())
        writeJson(directory.resolve("pointer-events.json"),JsonArray(harness.pointers))
        check(kernel.CloseHandle(handle));handle=null
        check(Files.readString(file).contains("first_read_task_file"))
        return buildJsonObject {
            put("id",id);put("style",style.name);put("theme",theme.name);put("width",960);put("height",height)
            put("realWindowsSharingDenial",denial.javaClass.simpleName);put("actorRetiredInsteadOfIoFailure",false)
            put("safeInitialErrorAndNoPathLeak",true);put("spinnerRemoved",true);put("actualDismissPointerPairs",harness.pointers.size)
            put("chooserCalls",chooserCalls);put("escapedCoroutineFailures",harness.errors.size);put("fileUnchangedAndReadableAfterUnlock",true);put("passed",true)
        }
    } finally {handle?.let{kernel.CloseHandle(it)};harness.close();lifecycle.shutdownForRestore()}
}

fun main(args:Array<String>):Unit=runBlocking {
    check(java.awt.GraphicsEnvironment.isHeadless())
    val output=Path.of(args[0]);Files.createDirectories(output)
    val identities=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    val actual=identities.map{row ->
        val pin=row.jsonObject;val name=pin["class"]!!.jsonPrimitive.content
        val type=Class.forName(name);val expectedJar=Path.of(pin["jar"]!!.jsonPrimitive.content).toRealPath()
        val origin=Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
        check(origin==expectedJar){"Class shadowing: $name from $origin"}
        val resource=type.getResource("/"+name.replace('.','/')+".class")!!
        val hash=resource.openStream().use{sha(it.readAllBytes())}
        check(hash==pin["sha256Bytes"]!!.jsonPrimitive.content)
        buildJsonObject{put("class",name);put("jar",origin.toString());put("classResource",resource.toString());put("sha256Bytes",hash);put("passed",true)}
    }
    writeJson(output.resolve("runtime-actual-class-identities.json"),JsonArray(actual))
    val normal=mutableListOf<JsonObject>();val failures=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries)for(theme in listOf(AppThemeMode.LIGHT,AppThemeMode.DARK))for(height in listOf(900,720)){
        normal+=normalCase(output,style,theme,height,coroutineContext)
        failures+=initialReadFailure(output,style,theme,height,coroutineContext)
        println("UI ${normal.last()["passed"]} ${style.name} ${theme.name} 960x$height; initial IO denial PASS")
    }
    writeJson(output.resolve("result.json"),buildJsonObject {
        put("proofCompleted",true);put("passed",normal.all{it["passed"]!!.jsonPrimitive.boolean});put("normalFlows",JsonArray(normal));put("initialIoFailureFlows",JsonArray(failures))
        put("actualPointerPairs",normal.sumOf{it["actualPointerPairs"]!!.jsonPrimitive.int}+failures.sumOf{it["actualDismissPointerPairs"]!!.jsonPrimitive.int})
        put("compiledProductionOverrides",0);put("storeOrRendererReplacements",0);put("nativeWindowCreated",false)
        put("accountOrHttpRequestMade",false);put("nativeOsChooserVerified",false);put("wholeMainOrPluginRuntimeInstantiated",false)
    })
    Unit
}
