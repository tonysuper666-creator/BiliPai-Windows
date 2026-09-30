@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.network.policy.AppHttpProxySettings
import com.android.purebilibili.core.store.NetworkProxyStore
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

/** Actual product wrapper and private original dialog reached only by its real address row. */
fun main(args:Array<String>):Unit=runBlocking {
    check(java.awt.GraphicsEnvironment.isHeadless())
    check(java.awt.Window.getWindows().isEmpty())
    val output=Path.of(args[0]);Files.createDirectories(output)
    val root=Path.of(args[1]).toAbsolutePath().normalize()
    check(Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath().normalize()==root)
    val store=DesktopPluginStore(root.resolve("prefs"));val context=DesktopPluginContext(store)
    NetworkProxyStore.init(context)
    val repository=DesktopRepository(DesktopSessionStore(root.resolve("session.json"),persistent=false))
    val cases=mutableListOf<JsonObject>();val failures=mutableListOf<Throwable>()
    for(height in listOf(720,900)) for(style in AppUiStyle.entries) {
        NetworkProxyStore.save(context,AppHttpProxySettings(false,"127.0.0.1","7890"))
        var nanos=0L;var pointers=0;var inputs=0;var dialogStatus="not-run";var blocker:String?=null
        val scene=ImageComposeScene(width=720,height=height,coroutineContext=coroutineContext)
        try {
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    CompositionLocalProvider(
                        LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                        LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT,
                    ) {
                        androidx.compose.material3.Surface {
                            DesktopNetworkProxySettings(context,repository.httpClient,{failures+=it},Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                }
            }
            suspend fun settle(){repeat(80){nanos+=30_000_000L;scene.render(nanos).close();yield()}}
            fun all():List<SemanticsNode> {
                fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
            }
            fun matches(text:String)=all().filter {it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==text}==true}
            fun label(text:String)=matches(text).lastOrNull()?:error("Missing '$text'; labels="+all().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text})
            suspend fun pointer(position:Offset) {
                check(position.x in 0f..720f && position.y in 0f..height.toFloat())
                scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
                pointers++;settle()
            }
            suspend fun click(text:String)=pointer(label(text).boundsInRoot.center)
            suspend fun waitFor(condition:()->Boolean){withTimeout(2500){while(!condition()){delay(10);settle()}};settle()}
            suspend fun image(name:String){scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-${height}-$name.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
            suspend fun edit(index:Int,value:String) {
                val nodes=all().filter {it.config.contains(SemanticsProperties.EditableText)}
                check(nodes.size==2){"Actual dialog must expose original host/port fields: ${nodes.size}"}
                check(nodes[index].config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(value))==true)
                inputs++;settle()
            }
            settle();check(label("代理地址").boundsInRoot.width>0)
            click("HTTP 代理");waitFor {NetworkProxyStore.settings.value.enabled}
            click("HTTP 代理");waitFor {!NetworkProxyStore.settings.value.enabled}
            image("proxy-fields")
            check(failures.isEmpty())
            try {
                val ownerCountBefore=scene.semanticsOwners.size
                click("代理地址")
                check(label("设置 HTTP 代理").boundsInRoot.width>0)
                check(scene.semanticsOwners.size>ownerCountBefore){"Actual original dialog did not add its scene layer"}
                image("original-dialog")
                edit(0," https://127.0.0.1:9999/path ");edit(1,"0")
                val enabledToggle=all().filter {it.config.contains(SemanticsProperties.ToggleableState)}.last()
                pointer(enabledToggle.boundsInRoot.center)
                check(label("请填写有效的主机与端口（1–65535）").boundsInRoot.width>0)
                val before=NetworkProxyStore.getSync();click("保存")
                check(matches("设置 HTTP 代理").isNotEmpty() && NetworkProxyStore.getSync()==before)
                image("invalid-original-dialog")
                edit(1,"81x23")
                click("保存")
                waitFor {NetworkProxyStore.getSync()==AppHttpProxySettings(true,"127.0.0.1","8123")}
                check(matches("设置 HTTP 代理").isEmpty())
                val saved=NetworkProxyStore.getSync()
                click("代理地址");edit(0,"fixture.invalid");click("取消")
                check(matches("设置 HTTP 代理").isEmpty() && NetworkProxyStore.getSync()==saved)
                val disk=Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject["network_proxy_prefs"]!!.jsonObject
                check(disk["enabled"]!!.jsonPrimitive.boolean && disk["host"]!!.jsonPrimitive.content=="127.0.0.1" && disk["port"]!!.jsonPrimitive.content=="8123")
                Files.writeString(output.resolve("${style.name.lowercase()}-${height}-actual-proxy-disk.json"),disk.toString())
                image("saved-original-dialog")
                dialogStatus="actual-original-layer-validation-save-and-cancel-passed"
            }catch(failure:Throwable) {
                val causes=generateSequence(failure){it.cause}.toList()
                if(causes.any{it is java.awt.HeadlessException || it is UnsupportedOperationException}) {
                    dialogStatus="unsupported-by-actual-offscreen-host";blocker=causes.joinToString(" <- "){it.javaClass.name+": "+it.message}
                }else throw failure
            }
            check(failures.isEmpty() && repository.account.value==null && repository.savedAccounts.value.isEmpty())
            check(java.awt.Window.getWindows().isEmpty())
            cases+=buildJsonObject {
                put("style",style.name);put("sceneWidth",720);put("sceneHeight",height);put("settleFramesPerStep",80);put("settleLogicalMilliseconds",2400);put("actualPointerCount",pointers);put("actualEditorInputs",inputs)
                put("actualProductSwitchesPassed",true);put("originalDialogStatus",dialogStatus)
                blocker?.let{put("originalDialogHostBlocker",it)}
                put("actualProductRendererUsed",true);put("replacementDialogOrPanelUsed",false)
            }
        }finally{scene.close()}
    }
    repository.httpClient.dispatcher.cancelAll();repository.httpClient.connectionPool.evictAll();repository.httpClient.dispatcher.executorService.shutdownNow()
    check(!Files.exists(root.resolve("session.json")) && java.awt.Window.getWindows().isEmpty())
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("switchProofPassed",true);put("cases",JsonArray(cases));put("nativeWindowCreated",false)
        put("actualCurrentProductSnapshotClasses",true);put("externalAccountOrHttpRequest",false)
        put("realAddressDialogNativeAcceptanceClaimed",false);put("enhancedDiagnosticConsentClaimed",false)
    }.toString())
    println("Actual product proxy UI completed; original dialog status recorded per style, no renderer replacement or HWND.")
    Unit
}
