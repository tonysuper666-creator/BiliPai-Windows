@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppText
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output);val checks=mutableListOf<String>()
    for(style in AppUiStyle.entries)for(accountPage in listOf(false,true)) {
        val root=Files.createTempDirectory("discovery-storage-ui-")
        val target=(if(accountPage)root.resolve("accounts/123")else root).resolve("discovery/plugin-settings.json")
        Files.createDirectories(target.parent);Files.writeString(target,"not JSON / DO_NOT_DISPLAY_fixture")
        val authority=DesktopBlockedUpStore(DesktopPluginContext(DesktopPluginStore(root)))
        val repo=DesktopRepository(DesktopSessionStore.temporary())
        val discovery=if(accountPage)openDesktopDiscoveryStorage(repo){DesktopDiscoveryPreferences(root,authority)}else null
        val guard=if(accountPage)DesktopDiscoveryStorageGuard<Any>({openDesktopDiscoveryFeedback(discovery!!,123)})
            else DesktopDiscoveryStorageGuard<Any>({openDesktopDiscoveryStorage(repo){DesktopDiscoveryPreferences(root,authority)}})
        var consumers=0;var restartClicks=0
        val scene=ImageComposeScene(width=720,height=480,coroutineContext=coroutineContext);var nanos=0L
        try {
            scene.setContent {DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                DesktopDiscoveryStorageBoundary(guard,0L,{restartClicks++},Modifier.fillMaxSize()) {actual->
                    LaunchedEffect(actual){consumers++}
                    Column(Modifier.padding(20.dp)){
                        if(accountPage) {
                            @Suppress("UNCHECKED_CAST")
                            val feedback=actual as kotlinx.coroutines.flow.StateFlow<com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot>
                            AppText("原反馈：${feedback.value.dislikedBvids.single()}")
                        } else AppText("原每次推荐条数：${(actual as DesktopDiscoveryRepository).refreshCount.value}")
                    }
                }
            }}
            fun all():List<SemanticsNode>{fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
                return scene.semanticsOwners.flatMap{nodes(it.unmergedRootSemanticsNode)}}
            fun labels()=all().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
            suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
            suspend fun settleUntil(label:String){withTimeout(3500){while(label !in labels())frame()};repeat(8){frame()}}
            suspend fun click(label:String){val node=all().lastOrNull{it.config.getOrNull(SemanticsProperties.Text)?.any{v->v.text==label}==true}
                ?:error("Missing actual $label: ${labels()}")
                val p=node.boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
                scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(8){frame()}}
            fun verify(label:String,value:Boolean){assertTrue(value,"$style/$accountPage: $label");checks+="$style/${if(accountPage)"feedback"else"startup"}: $label"}
            settleUntil("重试读取")
            verify("real corrupt original backing reaches original App error controls",labels().contains("本地存储无法读取"))
            verify("ready consumer has not started",consumers==0)
            verify("raw bad JSON and path are not visible",labels().none{it.contains("DO_NOT_DISPLAY_fixture")||it.contains(root.toString())})
            verify("original corrupt disk bytes retained",Files.readString(target)=="not JSON / DO_NOT_DISPLAY_fixture")
            scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-${if(accountPage)"feedback"else"startup"}-error.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            click("重新启动应用");verify("actual original App restart button invokes supplied root callback",restartClicks==1&&consumers==0)
            Files.writeString(target,if(accountPage)
                """{"today_watch_feedback":{"feedback_payload_v1":"{\"dislikedBvids\":[\"BVfixtureOriginal\"]}"},"future_namespace":{"keep":"原资料"}}"""
                else """{"feed_api":{"home_refresh_count":14},"future_namespace":{"keep":"原资料"}}""")
            val repaired=Files.readAllBytes(target)
            click("重试读取");val readyLabel=if(accountPage)"原反馈：BVfixtureOriginal"else"原每次推荐条数：14"
            settleUntil(readyLabel)
            verify("real pointer retries original factory and composes real source value",readyLabel in labels()&&consumers==1)
            verify("repaired input and unrelated namespace unchanged",repaired.contentEquals(Files.readAllBytes(target)))
            verify("no fake fallback state or stale error controls remain","重试读取" !in labels())
            scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-${if(accountPage)"feedback"else"startup"}-ready.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            guard.close();repeat(8){frame()}
            // close invalidates guard but does not invent an original preference publish; explicit composition tests the closed boundary.
            scene.setContent {DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                DesktopDiscoveryStorageBoundary(guard,0L,{restartClicks++}){error("Closed guard composed an old consumer")}
            }}
            settleUntil("此页面的存储读取已停止，请返回后重新打开。")
            verify("closed generation cannot compose or retry retained consumer",!guard.canRetry&&consumers==1)
        } finally {scene.close();guard.close()}
    }
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("styles",AppUiStyle.entries.size)
        put("cases",4);put("checks",JsonArray(checks.map(::JsonPrimitive)));put("actualPressRelease",true)
        put("originalAppControls",true);put("HWND",false);put("HTTP",false);put("accountFiles",false)
        put("restartCallbackOnlyNotActualProcessRestart",true);put("backgroundRuntimeHTTPBlockedClaimed",false)}.toString())
    println("PASS: two styles, real guest/account JSON failures and original App pointer retry/restart controls")
}
