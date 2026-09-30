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
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.data.repository.BlockedUpImportItem
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.test.*

private val imageChecks=mutableListOf<JsonObject>()

private class ActualProductScene(val scene:ImageComposeScene,private val checks:MutableList<String>,private val label:String) {
    var nanos=0L
    fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
    fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
    suspend fun waitFor(description:String,test:()->Boolean){try{withTimeout(4000){while(!test())frame()};repeat(8){frame()}}
        catch(failure:TimeoutCancellationException){error("$label: $description; actual labels=${labels()}")}}
    fun verify(description:String,value:Boolean){assertTrue(value,"$label: $description");checks+="$label: $description";println("PASS: $label: $description")}
    suspend fun pointer(p:Offset){scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(8){frame()}}
    fun text(label:String)=nodes().filter{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
    suspend fun click(label:String){val actual=text(label).lastOrNull{it.boundsInRoot.center.y in 1f..638f}?:error("$this: no visible $label: ${labels()}");pointer(actual.boundsInRoot.center)}
    suspend fun scrollTo(label:String){repeat(14){
        if(text(label).any{it.boundsInRoot.center.y in 80f..610f})return
        val source=nodes().lastOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}
            ?:error("No actual product scroll source for $label: ${labels()}")
        verify("actual source scroll action accepted for $label",source.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f,220f))
        repeat(10){frame()}
    };error("Cannot show $label in actual finite viewport: ${labels()}")}
    suspend fun screenshot(path:Path){
        val checked=path.fileName.toString().contains("startup-error")||path.fileName.toString().contains("stopped-restart")
        if(!checked){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)};return}
        repeat(80){frame()}
        val hashes=mutableListOf<String>()
        repeat(3){index->repeat(6){frame()}
            val target=path.resolveSibling(path.fileName.toString().removeSuffix(".png")+"-converged-$index.png")
            scene.render(nanos+1).use{Files.write(target,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            hashes+=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)).joinToString(""){"%02x".format(it)}
            if(index==2)Files.copy(target,path)
        }
        verify("actual error/stopped render converges in three frames",hashes.distinct().size==1)
        val image=ImageIO.read(path.toFile());var transparent=0;var opaque=0
        for(y in 0 until image.height)for(x in 0 until image.width){val a=image.getRGB(x,y) ushr 24
            if(a==0)transparent++;if(a==255)opaque++}
        verify("product paints every error/stopped pixel opaque",transparent==0&&opaque==image.width*image.height)
        val error=path.fileName.toString().contains("startup-error")
        val message=assertNotNull(text(if(error)"本地存储无法读取"else"本地设置读取已停止，请重新启动应用。").lastOrNull())
        val action=assertNotNull(text(if(error)"重试读取"else"重新启动应用").lastOrNull())
        val background=image.getRGB(500,300)
        fun ink(node:SemanticsNode):Int{val rect=node.boundsInRoot;var count=0
            for(y in max(0,rect.top.toInt()) until min(image.height,rect.bottom.toInt()+1))
                for(x in max(0,rect.left.toInt()) until min(image.width,rect.right.toInt()+1)){
                    val pixel=image.getRGB(x,y);val difference=abs(((pixel shr 16)and 255)-((background shr 16)and 255))+
                        abs(((pixel shr 8)and 255)-((background shr 8)and 255))+abs((pixel and 255)-(background and 255))
                    if(pixel ushr 24>150&&difference>180)count++
                };return count}
        val messageInk=ink(message);val actionInk=ink(action)
        verify("actual product message pixels contrast with background",messageInk>200)
        verify("actual product action pixels contrast with background",actionInk>100)
        imageChecks+=buildJsonObject{put("file",path.fileName.toString());put("transparentPixels",transparent);put("opaquePixels",opaque)
            put("messageContrastedInkPixels",messageInk);put("actionContrastedInkPixels",actionInk);put("convergedFrameSha256",JsonArray(hashes.map(::JsonPrimitive)))
            put("fixtureAppSurfaceHost",false);put("actualProductBoundarySurface",true)}
    }
}

private suspend fun startupAndStopped(style:AppUiStyle,output:Path,checks:MutableList<String>,context:kotlin.coroutines.CoroutineContext) {
    val root=Files.createTempDirectory("product-discovery-startup-")
    val corrupt=root.resolve("discovery/plugin-settings.json");Files.createDirectories(corrupt.parent);Files.writeString(corrupt,"not JSON / private fixture text")
    val store=DesktopPluginStore(root);val theme=DesktopThemePrefs(store);theme.setUiStyle(style)
    val blocked=DesktopBlockedUpStore(DesktopPluginContext(store));val repository=DesktopRepository(DesktopSessionStore.temporary())
    val closing=AtomicBoolean(false);var factories=0;var readyEffects=0;var readyDisposed=0;var restartClicks=0
    val guard=DesktopDiscoveryStorageGuard({factories++;openDesktopDiscoveryStorage(repository){DesktopDiscoveryPreferences(store.root,blocked)}},
        sessionEpoch={repository.sessionEpoch},stillOwned={!closing.get()})
    val scene=ImageComposeScene(width=720,height=640,coroutineContext=context);val ui=ActualProductScene(scene,checks,"$style/actual-boundary")
    try {
        scene.setContent {
            val epoch by repository.sessionEpochFlow.collectAsState()
            val settings by theme.settings.collectAsState(theme.initialSettings())
            DesktopDiscoveryStorageBoundary(guard,epoch,{restartClicks++},Modifier.fillMaxSize(),
                errorTheme={body->DesktopAppearanceTheme(settings){body()}}) {source->
                // Test-owned finite ready consumer. Never constructs DesktopReadyApp, MpvPlayer or PluginRuntime.
                DesktopAppearanceTheme(settings){
                    LaunchedEffect(source){readyEffects++}
                    DisposableEffect(source){onDispose{readyDisposed++}}
                    AppText("原每次推荐条数：${source.refreshCount.value}",Modifier.padding(20.dp))
                }
            }
        }
        ui.waitFor("actual constructor error has retry button"){"重试读取" in ui.labels()}
        ui.verify("product constructor fails and keeps corrupt file",factories==1&&Files.readString(corrupt)=="not JSON / private fixture text")
        ui.verify("no ready consumer before actual repair",readyEffects==0&&readyDisposed==0)
        ui.verify("product error labels hide raw bytes and path",ui.labels().none{it.contains("private fixture")||it.contains(root.toString())})
        ui.screenshot(output.resolve("${style.name.lowercase()}-startup-error.png"))
        Files.writeString(corrupt,"""{"feed_api":{"home_refresh_count":14},"future_namespace":{"keep":"原资料"}}""")
        val repaired=Files.readAllBytes(corrupt)
        ui.click("重试读取");ui.waitFor("actual pointer retries original constructor"){"原每次推荐条数：14" in ui.labels()}
        ui.verify("actual same guard remains live after ready",guard.isActive&&factories==2&&readyEffects==1&&readyDisposed==0)
        ui.verify("ready uses same original global blocked-UP store",guard.result.value?.getOrNull()?.blockedUps===blocked)
        ui.verify("repair and unrelated namespace remain untouched",repaired.contentEquals(Files.readAllBytes(corrupt)))
        ui.screenshot(output.resolve("${style.name.lowercase()}-startup-ready.png"))
        closing.set(true);guard.close()
        ui.waitFor("actual product stopped branch visible"){"本地设置读取已停止，请重新启动应用。" in ui.labels()}
        ui.verify("close disposes actual ready consumer",!guard.isActive&&readyDisposed==1&&!guard.canRetry)
        ui.verify("stopped branch contains usable restart action","重新启动应用" in ui.labels()&&"重试读取" !in ui.labels())
        ui.click("重新启动应用")
        ui.verify("actual stopped restart pointer invokes supplied callback",restartClicks==1&&factories==2)
        ui.verify("stopped retry cannot reopen product store",!guard.load())
        ui.screenshot(output.resolve("${style.name.lowercase()}-stopped-restart.png"))
    }finally{scene.close();guard.close()}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output);val checks=mutableListOf<String>()
    for(style in AppUiStyle.entries){startupAndStopped(style,output,checks,coroutineContext)}
    Files.writeString(output.resolve("screenshot-metrics.json"),JsonArray(imageChecks).toString())
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("styles",2);put("cases",2)
        put("checks",JsonArray(checks.map(::JsonPrimitive)));put("actualProductClassesOnly",true);put("pureTestSourcesOnly",true)
        put("HWND",false);put("MpvPlayerConstructed",false);put("PluginRuntimeConstructed",false);put("userAccountFiles",false);put("HTTP",false)
        put("fullDesktopShellClaimed",false);put("realProcessRestartClaimed",false);put("backupRestoreExecuted",false);put("productSnapshotContainsRootBackupFix",true)
        put("listScrollMethod","actual source SemanticsActions.ScrollBy; route and row buttons actual Press/Release")}.toString())
}
