@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
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

fun main(args:Array<String>):Unit = runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output)
    val root=Files.createTempDirectory("bilipai-original-proxy-ui-")
    val store=DesktopPluginStore(root)
    val context=DesktopPluginContext(store)
    NetworkProxyStore.init(context)
    val repository=DesktopRepository(DesktopSessionStore(root.resolve("session.json"),persistent=false))
    val cases=mutableListOf<JsonObject>();val failures=mutableListOf<Throwable>()
    try {
        for(style in AppUiStyle.entries) {
            NetworkProxyStore.save(context,AppHttpProxySettings(false,"127.0.0.1","7890"))
            var nanos=0L
            val scene=ImageComposeScene(width=720,height=380,coroutineContext=coroutineContext)
            try {
                scene.setContent {
                    DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                        CompositionLocalProvider(
                            LocalAppPreferenceIconTreatment provides AppPreferenceIconTreatment.FILLED,
                            LocalAppPreferenceGroupPresentation provides if(isMiuixNonGlassEnabled())AppPreferenceGroupPresentation.CARD else AppPreferenceGroupPresentation.FLAT,
                        ) {
                            androidx.compose.material3.Surface(color=if(style==AppUiStyle.MIUIX) AppSurfaceTokens.chromeBackground() else AppSurfaceTokens.groupedListContainer()) {
                                DesktopNetworkProxySettings(context,repository.httpClient,{failures+=it},Modifier.fillMaxWidth().padding(16.dp))
                            }
                        }
                    }
                }
                suspend fun settle(){repeat(6){nanos+=30_000_000L;scene.render(nanos).close();yield()}}
                fun all():List<SemanticsNode> {
                    fun tree(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::tree)
                    return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}
                }
                fun label(text:String)=all().single{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text==text}==true}
                suspend fun click(){val position=label("HTTP 代理").boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press,position,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                    scene.sendPointerEvent(PointerEventType.Release,position,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());settle()
                }
                settle()
                check(label("代理地址").boundsInRoot.width>0)
                check(label("127.0.0.1:7890").boundsInRoot.width>0)
                check(all().any{it.config.getOrNull(SemanticsProperties.Text)?.any{line->line.text.contains("视频播放仍直接连接")}==true})
                click()
                withTimeout(2000){while(!NetworkProxyStore.settings.value.enabled){settle();delay(10)}}
                check(NetworkProxyStore.getSync().enabled&&failures.isEmpty())
                click()
                withTimeout(2000){while(NetworkProxyStore.settings.value.enabled){settle();delay(10)}}
                val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["network_proxy_prefs"]!!.jsonObject
                check(disk["enabled"]!!.jsonPrimitive.boolean==false&&disk["host"]!!.jsonPrimitive.content=="127.0.0.1")
                check(failures.isEmpty());settle()
                scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-proxy.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
                cases+=buildJsonObject {put("style",style.name);put("realPointerToggleCount",2);put("sameOriginalStoreDiskAndLiveSettings",true);put("passed",true)}
            }finally{scene.close()}
        }
    } finally {repository.httpClient.dispatcher.cancelAll();repository.httpClient.connectionPool.evictAll();repository.httpClient.dispatcher.executorService.shutdownNow()}
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("passed",true);put("cases",JsonArray(cases));put("nativeWindowCreated",false)
        put("proxyAddressDialogClicked",false);put("enhancedDiagnosticConsentClaimed",false);put("accountOrHttpRequestMade",false)
    }.toString())
    println("Original HTTP proxy switches: both styles, four actual pointers -> original live store and actual disk; no dialog/window/account/HTTP.")
    Unit
}
