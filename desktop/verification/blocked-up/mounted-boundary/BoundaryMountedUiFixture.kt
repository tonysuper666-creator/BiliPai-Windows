@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.AppText
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import java.util.concurrent.atomic.*
import kotlin.math.abs
import kotlin.test.*

/** Actual source factory and persisted theme values; one continuously mounted boundary. No HWND. */
fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);Files.createDirectories(output);val checks=mutableListOf<String>()
    for(style in AppUiStyle.entries) {
        val root=Files.createTempDirectory("discovery-mounted-")
        val corrupt=root.resolve("discovery/plugin-settings.json");Files.createDirectories(corrupt.parent);Files.writeString(corrupt,"NOT JSON / fixture private value")
        val store=DesktopPluginStore(root);val preferences=DesktopThemePrefs(store)
        preferences.setUiStyle(style);preferences.setDpiOverride(110)
        val authority=DesktopBlockedUpStore(DesktopPluginContext(store));val repository=DesktopRepository(DesktopSessionStore.temporary())
        val epoch=AtomicLong(1);val closing=AtomicBoolean(false)
        var factoryCalls=0
        val guard=DesktopDiscoveryStorageGuard({factoryCalls++;openDesktopDiscoveryStorage(repository){DesktopDiscoveryPreferences(root,authority)}},epoch::get,{!closing.get()})
        var displayedEpoch by mutableLongStateOf(1);var revision by mutableIntStateOf(0)
        var startupEffects=0;var readyDisposals=0;var errorThemeCompositions=0
        var actualReady:DesktopDiscoveryRepository?=null;var readyDensity=0f;var errorDensity=0f
        val scene=ImageComposeScene(width=720,height=480,coroutineContext=coroutineContext);var nanos=0L
        try {
            scene.setContent {
                val actualTheme by preferences.settings.collectAsState(preferences.initialSettings())
                val revisionSnapshot=revision
                DesktopDiscoveryStorageBoundary(guard,displayedEpoch,null,Modifier.fillMaxSize(),errorTheme={errorBody->
                    DesktopAppearanceTheme(actualTheme) {
                        val density=LocalDensity.current.density
                        SideEffect{errorThemeCompositions++;errorDensity=density}
                        errorBody()
                    }
                }) {discovery->
                    // ReadyShell already owns its one real theme; there is deliberately no outer theme here.
                    DesktopAppearanceTheme(actualTheme) {
                        val density=LocalDensity.current.density
                        val owned=remember(discovery){discovery}
                        SideEffect{actualReady=owned;readyDensity=density}
                        LaunchedEffect(owned){startupEffects++}
                        DisposableEffect(owned){onDispose{readyDisposals++}}
                        AppText("原每次推荐条数：${owned.refreshCount.value}；epoch=$displayedEpoch；revision=$revisionSnapshot")
                    }
                }
            }
            fun all():List<SemanticsNode>{fun nodes(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::nodes)
                return scene.semanticsOwners.flatMap{nodes(it.unmergedRootSemanticsNode)}}
            fun labels()=all().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
            suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
            suspend fun waitFor(test:()->Boolean){try {withTimeout(4000){while(!test())frame()};repeat(8){frame()}}
                catch(failure:TimeoutCancellationException){error("$style timed out; labels=${labels()}, readyDensity=$readyDensity, errorDensity=$errorDensity, epoch=$displayedEpoch, revision=$revision, factories=$factoryCalls, effects=$startupEffects, disposals=$readyDisposals, active=${guard.isActive}, completed=$checks")}}
            fun verify(label:String,value:Boolean){assertTrue(value,"$style: $label");checks+="$style: $label";println("PASS: $style: $label")}
            waitFor{"重试读取" in labels()}
            verify("actual persisted error style and once-applied density",abs(errorDensity-1.1f)<0.001f)
            verify("real factory failure has no ready initializer",factoryCalls==1&&startupEffects==0&&actualReady==null)
            verify("error theme applied while boundary remains active",errorThemeCompositions>0&&guard.isActive)
            Files.writeString(corrupt,"""{"feed_api":{"home_refresh_count":14},"future_namespace":{"keep":"原资料"}}""")
            val repaired=Files.readAllBytes(corrupt)
            val retry=all().last{it.config.getOrNull(SemanticsProperties.Text)?.any{v->v.text=="重试读取"}==true}.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press,retry,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
            scene.sendPointerEvent(PointerEventType.Release,retry,timeMillis=nanos/1_000_000+55,buttons=PointerButtons())
            waitFor{labels().any{it=="原每次推荐条数：14；epoch=1；revision=0"}}
            val actual=assertNotNull(actualReady);val errorCount=errorThemeCompositions
            verify("actual pointer retry retains active mounted guard",guard.isActive&&factoryCalls==2&&guard.result.value?.getOrNull()===actual)
            verify("same global blocked-UP authority",actual.blockedUps===authority)
            verify("ready initializer runs once and no ready disposal",startupEffects==1&&readyDisposals==0)
            verify("ready density applied only once",abs(readyDensity-1.1f)<0.001f)
            scene.render(nanos+1).use{Files.write(output.resolve("${style.name.lowercase()}-ready.png"),it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}
            epoch.set(2);displayedEpoch=2;revision++
            waitFor{labels().any{it=="原每次推荐条数：14；epoch=2；revision=1"}}
            verify("epoch recompose preserves live guard and exact original repository",guard.isActive&&factoryCalls==2&&actualReady===actual)
            verify("epoch change does not dispose ready initializer",startupEffects==1&&readyDisposals==0)
            preferences.setDpiOverride(105);revision++
            waitFor{abs(readyDensity-1.05f)<0.001f}
            verify("actual persisted theme recompose has one density transform",abs(readyDensity-1.05f)<0.001f)
            verify("ready branch never invokes errorTheme",errorThemeCompositions==errorCount)
            verify("theme recompose preserves live repository and guard",guard.isActive&&actualReady===actual&&factoryCalls==2&&startupEffects==1&&readyDisposals==0)
            verify("retry did not rewrite repaired or unrelated fields",repaired.contentEquals(Files.readAllBytes(corrupt)))
            closing.set(true);guard.close();revision++
            waitFor{"此页面的存储读取已停止，请返回后重新打开。" in labels()}
            verify("actual root closing predicate suppresses retained ready content",!guard.isActive&&readyDisposals==1)
            verify("stopped branch uses actual error theme once",errorThemeCompositions>errorCount&&abs(errorDensity-1.05f)<0.001f)
            verify("root closing cannot schedule another factory",!guard.load()&&factoryCalls==2&&!guard.canRetry)
            guard.close();assertFalse(guard.isActive)
        } finally {scene.close();guard.close()}
    }
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("styles",2);put("checks",JsonArray(checks.map(::JsonPrimitive)))
        put("actualPointerRetry",true);put("actualTemporaryStoreTheme",true);put("continuousBoundaryMounted",true)
        put("HWND",false);put("sharedGradle",false);put("HTTP",false);put("userAccountFiles",false)}.toString())
    println("PASS: two styles, actual retry-to-ready retains mounted live guard; epoch/theme changes and closing verified")
}
