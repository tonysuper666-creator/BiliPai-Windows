@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.blur.*
import com.bilipai.desktop.appearance.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output.parent)
    var attach by mutableStateOf(false);var foreground by mutableStateOf(true);var ready by mutableStateOf(false)
    var source:ChromeBackdropSource?=null;var topActive=false;var providerDraws=0
    val scene=ImageComposeScene(width=500,height=800,coroutineContext=coroutineContext);val ui=LayoutScene(scene,500,800)
    try{
        scene.setContent{
            DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled=false)){
                CompositionLocalProvider(LocalDesktopDetailForeground provides foreground,
                    LocalAppThemeConfig provides AppThemeConfig(headerBlurEnabled=false,progressiveTopBlurEnabled=true,progressiveTopFadeEnabled=false)){
                    val recorder=rememberChromeBackdropSource();source=recorder
                    ImmersiveAppScaffold(chromeBackdropSource=recorder,blurContentReady=ready,
                        topBar={topActive=LocalImmersiveTopChromeActive.current;AppSurface(Modifier.fillMaxWidth().height(64.dp),color=Color.Transparent){AppText("Original top chrome")}}){
                        Canvas(Modifier.fillMaxSize().then(if(attach)recorder.modifier else Modifier)){
                            providerDraws++
                            drawRect(Color(0xff2b7fbe));drawRect(Color(0xffd5634b),topLeft=Offset(size.width/2,0f),size=Size(size.width/2,size.height))
                        }
                    }
                }
            }
        }
        ui.await("actual original recorder composed without a recording owner"){source!=null&&providerDraws>0}
        repeat(20){ui.frame()}
        check(source?.isReady==false&&!topActive){"Elapsed time fabricated original source readiness"}
        attach=true
        ui.await("actual graphics-layer record publishes readiness"){source?.isReady==true}
        check(!topActive){"Skeleton/loading readiness was bypassed by warm source"}
        ready=true
        val capability=desktopDetailRenderEffectsSupported()
        ui.await("original mode selected only from actual effect and recorded source"){topActive==capability}
        ui.screenshot(output.resolveSibling("chrome-foreground-source.png"))
        foreground=false
        ui.await("external recorded source cannot keep background progressive renderer alive"){!topActive}
        check(source?.isReady==true){"Fixture changed the recorded source instead of testing foreground admission"}
        ui.screenshot(output.resolveSibling("chrome-background-solid.png"))
        foreground=true
        ui.await("foreground restores the same actual recorded source"){topActive==capability}
        Files.writeString(output,buildJsonObject{put("passed",true);put("actualSourceReadinessWaitsForLayerRecording",true)
            put("loadingGatePreserved",true);put("externalSourceStillReadyWhenBackgrounded",true);put("backgroundDisablesActualTopChrome",true)
            put("actualComposeRenderEffectSupported",capability);put("unchangedSourceRestoresAtForeground",true)
            put("MainAcceptance",false);put("HWND",false);put("GPUWindow",false)}.toString())
    }finally{scene.close()}
}
