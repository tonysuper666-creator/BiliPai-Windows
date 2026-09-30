@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppSurface
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.*

/** Actual product stopped UI; bare theme versus an explicitly marked original AppSurface fixture host. */
fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output);val results=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries)for(withSurface in listOf(false,true)) {
        val root=Files.createTempDirectory("stopped-alpha-fixture-");val prefs=DesktopThemePrefs(DesktopPluginStore(root));prefs.setUiStyle(style)
        val guard=DesktopDiscoveryStorageGuard<String>({error("Retired fixture factory must never execute")});guard.close()
        var callbacks=0
        val scene=ImageComposeScene(width=720,height=640,coroutineContext=coroutineContext);var nanos=0L
        try {
            scene.setContent {val settings by prefs.settings.collectAsState(prefs.initialSettings())
                DesktopDiscoveryStorageBoundary(guard,0L,{callbacks++},Modifier.fillMaxSize(),errorTheme={body->
                    DesktopAppearanceTheme(settings){if(withSurface)AppSurface(Modifier.fillMaxSize()){body()}else body()}
                }) {error("Retired guard rendered ready")}
            }
            fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
            fun label(text:String)=nodes().lastOrNull{it.config.getOrNull(SemanticsProperties.Text)?.any{v->v.text==text}==true}
            suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
            withTimeout(3000){while(label("重新启动应用")==null)frame()};repeat(80){frame()}
            val prefix=style.name.lowercase()+if(withSurface)"-original-surface"else"-bare-theme"
            val captures=mutableListOf<String>()
            repeat(3){index->
                repeat(6){frame()};val path=output.resolve("$prefix-before-$index.png")
                scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
                captures+=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){"%02x".format(it)}
            }
            assertEquals(1,captures.distinct().size,"$prefix captures have not converged")
            val path=output.resolve("$prefix-before-2.png");val image=ImageIO.read(path.toFile())
            var transparent=0;var opaque=0;var nonzeroRgb=0
            for(y in 0 until image.height)for(x in 0 until image.width){val pixel=image.getRGB(x,y);val alpha=pixel ushr 24
                if(alpha==0)transparent++;if(alpha==255)opaque++;if((pixel and 0xffffff)!=0)nonzeroRgb++}
            val message=assertNotNull(label("本地设置读取已停止，请重新启动应用。"));val button=assertNotNull(label("重新启动应用"))
            val background=image.getRGB(500,300)
            fun contrastedInk(node:SemanticsNode):Int {
                val rect=node.boundsInRoot
                var count=0
                for(y in max(0,rect.top.toInt()) until min(image.height,rect.bottom.toInt()+1))
                    for(x in max(0,rect.left.toInt()) until min(image.width,rect.right.toInt()+1)){
                        val pixel=image.getRGB(x,y)
                        val difference=abs(((pixel shr 16) and 255)-((background shr 16) and 255))+
                            abs(((pixel shr 8) and 255)-((background shr 8) and 255))+abs((pixel and 255)-(background and 255))
                        if(pixel ushr 24>150&&difference>180)count++
                    }
                return count
            }
            val messageInk=contrastedInk(message);val buttonInk=contrastedInk(button)
            if(withSurface){assertEquals(0,transparent);assertEquals(image.width*image.height,opaque)
                assertTrue(messageInk>200,"$prefix message lacks actual contrast ink");assertTrue(buttonInk>100,"$prefix button lacks actual contrast ink")}
            else {assertTrue(transparent>450000,"Bare theme unexpectedly paints an opaque host")
                if(style==AppUiStyle.MATERIAL3){assertEquals(0,nonzeroRgb);assertEquals(0,messageInk);assertEquals(0,buttonInk)}}
            val p=button.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
            scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
            repeat(80){frame()};assertEquals(1,callbacks);assertFalse(guard.isActive);assertFalse(guard.canRetry)
            scene.render(nanos+1).use{Files.write(output.resolve("$prefix-after-restart-pointer.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            results+=buildJsonObject{put("style",style.name);put("host",if(withSurface)"explicit original AppSurface fixture control"else"current wrapper bare errorTheme seam")
                put("beforeFrameSha256",JsonArray(captures.map(::JsonPrimitive)));put("threeFramesConverged",true)
                put("transparentPixels",transparent);put("opaquePixels",opaque);put("nonzeroRgbPixels",nonzeroRgb)
                put("messageContrastedInkPixels",messageInk);put("restartContrastedInkPixels",buttonInk);put("actualRestartPointerCallbacks",callbacks)}
            println("PASS: $prefix stable actual render; alpha=$transparent/$opaque, ink=$messageInk/$buttonInk; actual restart pointer=$callbacks")
        }finally{scene.close();guard.close()}
    }
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("cases",JsonArray(results))
        put("actualProductSnapshotUnchanged",true);put("original43Unchanged",true);put("AppSurfaceIsFixtureHostControlNotClaimedMainFix",true)
        put("HWND",false);put("actualProcessRestart",false);put("HTTP",false);put("realAccountData",false)}.toString())
}
