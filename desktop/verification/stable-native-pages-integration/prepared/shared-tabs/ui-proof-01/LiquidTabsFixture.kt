@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui.liquidTabsProof

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.home.components.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.shader.RuntimeShader

private class Owner:LifecycleOwner {
    override val lifecycle=LifecycleRegistry.createUnsafe(this).apply{currentState=Lifecycle.State.RESUMED}
}
private class Ui(val scene:ImageComposeScene) {
    var nanos=0L;var pointers=0
    fun nodes():List<SemanticsNode>{fun visit(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::visit);return scene.semanticsOwners.flatMap{visit(it.unmergedRootSemanticsNode)}}
    fun labels(n:SemanticsNode):Boolean=n.config.getOrNull(SemanticsProperties.Text).orEmpty().any{it.text=="Second"}||n.children.any(::labels)
    fun second()=nodes().last{labels(it)&&it.config.getOrNull(SemanticsActions.OnClick)!=null}
    suspend fun frame(){nanos+=32_000_000;scene.render(nanos).close();delay(3)}
    suspend fun settle(){repeat(25){frame()}}
    suspend fun await(p:()->Boolean){withTimeout(5000){while(!p())frame()};repeat(6){frame()}}
    suspend fun press(){val p=second().boundsInRoot.center;scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true));repeat(5){frame()};scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+30,buttons=PointerButtons());pointers++;settle()}
}

fun main(args:Array<String>):Unit=runBlocking {
    val out=Path.of(args[0]).toRealPath();val candidate=Path.of(args[1]).toRealPath();val main=Path.of(args[2]).toRealPath()
    var assertions=0;fun prove(v:Boolean,m:String){check(v){m};assertions++}
    val identities=mutableListOf<JsonObject>()
    for((name,expected) in listOf(
        "com.android.purebilibili.core.ui.components.AppLiquidAwareTabRowKt" to candidate,
        "com.android.purebilibili.feature.home.components.FloatingBottomBarKt" to candidate,
        "com.bilipai.desktop.settings.DesktopLiquidTabSettings" to candidate,
        "com.bilipai.desktop.ui.DesktopLiquidReadabilityEnvironment" to candidate,
        "com.bilipai.desktop.plugins.DesktopPluginStore" to main,
        "com.android.purebilibili.core.ui.components.DesktopOriginalDynamicNativeTabsKt" to main)) {
        val c=Class.forName(name);val actual=Path.of(c.protectionDomain.codeSource.location.toURI()).toRealPath();prove(actual==expected,"code source $name")
        identities+=buildJsonObject{put("class",name);put("path",actual.toString())}
    }
    val store=DesktopPluginStore(out.resolve("actual-global-store-fixture"));val bridge=DesktopLiquidTabSettings(store)
    val defaults=bridge.homeSettings.first()
    prove(!defaults.androidNativeLiquidGlassEnabled&&defaults.navigationIconCrossScaleEnabled&&defaults.liquidGlassReadabilityMode==com.android.purebilibili.core.store.LiquidGlassReadabilityMode.STABLE,"original defaults and readability")
    store.update("settings",mapOf("liquid_glass_mode" to JsonPrimitive(2),"liquid_glass_strength" to JsonPrimitive(0.5f)))
    prove(kotlin.math.abs(bridge.homeSettings.first().liquidGlassProgress-0.84f)<0.0001f,"same store original legacy mode+strength migration")
    store.update("settings",mapOf("liquid_glass_material_progress_v2" to JsonPrimitive(0.5f)))
    prove(bridge.homeSettings.first().liquidGlassProgress==0.5f,"same store v2 precedence")
    var pointers=0;var samples=0;val cases=mutableListOf<String>()
    for(style in listOf(AppUiStyle.MD3,AppUiStyle.MIUIX)) {
        val scene=ImageComposeScene(width=480,height=180,coroutineContext=coroutineContext);val ui=Ui(scene);val lifecycle=Owner()
        var selected by mutableIntStateOf(0);var callbacks=0;var color by mutableStateOf(Color.White)
        var layerBounds by mutableStateOf<Rect?>(null);var environment:DesktopLiquidReadabilityEnvironment?=null
        var scenePrefs:DesktopLiquidTabHomePreferences?=null;var alive=true
        try {
            store.update("settings",mapOf("android_native_liquid_glass_enabled" to JsonPrimitive(false)))
            scene.setContent {
                DesktopAppearanceTheme(DesktopThemeSettings(uiStyle=style,hapticFeedbackEnabled=false)) {
                    val prefs by bridge.homeSettings.collectAsState(initial=null);scenePrefs=prefs
                    val layer=rememberGraphicsLayer();val backdrop=rememberLayerBackdrop(layer)
                    val source=remember(layer){DesktopLiquidReadabilityEnvironment(layer,{layerBounds},{IntSize(480,180)},{alive})}
                    SideEffect{environment=source}
                    CompositionLocalProvider(LocalLifecycleOwner provides lifecycle,LocalDesktopLiquidTabSettings provides bridge,
                        LocalDesktopLiquidReadabilityEnvironment provides source,
                        LocalAppThemeConfig provides LocalAppThemeConfig.current.copy(liquidGlassEnabled=prefs?.androidNativeLiquidGlassEnabled==true),
                        LocalDesktopDetailForeground provides true) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned{layerBounds=it.boundsInWindow()}.layerBackdrop(backdrop).background(color))
                            AppThemeAdaptiveTabRow(options=listOf(AppSegmentOption(0,"First"),AppSegmentOption(1,"Second"),AppSegmentOption(2,"Third")),
                                selectedValue=selected,onSelectionChange={selected=it;callbacks++},modifier=Modifier.fillMaxWidth().padding(12.dp),
                                compactMiuixWhenTwoOptions=false,miuixBackdrop=backdrop)
                        }
                    }
                }
            }
            ui.await{scenePrefs!=null&&ui.nodes().any{it.config.getOrNull(SemanticsActions.OnClick)!=null}}
            ui.press();prove(selected==1&&callbacks==1,"actual $style original disabled native row selection")
            selected=0
            store.update("settings",mapOf("android_native_liquid_glass_enabled" to JsonPrimitive(true)))
            ui.await{scenePrefs?.androidNativeLiquidGlassEnabled==true};ui.settle();ui.press()
            prove(selected==1&&callbacks==2,"actual $style original enabled liquid renderer selection")
            val sample=checkNotNull(environment).sampleBitmap(DesktopLiquidSampleRect(380,100,460,160),24,8)
            val pixels=IntArray(24*8);checkNotNull(sample).getPixels(pixels,0,24,0,0,24,8)
            prove(pixels.all{(it and 0xffffff)==0xffffff},"actual same white recorded background layer 24x8 sampled");samples++
            color=Color.Black;ui.settle()
            val dark=checkNotNull(environment).sampleBitmap(DesktopLiquidSampleRect(380,100,460,160),24,8)
            checkNotNull(dark).getPixels(pixels,0,24,0,0,24,8)
            prove(pixels.all{(it and 0xffffff)==0},"actual black recorded layer replaces white sample");samples++
            alive=false
            prove(checkNotNull(environment).sampleBitmap(DesktopLiquidSampleRect(380,100,460,160),24,8)==null,"retired page sampler rejects capture")
            store.update("settings",mapOf("android_native_liquid_glass_enabled" to JsonPrimitive(false)))
            ui.await{scenePrefs?.androidNativeLiquidGlassEnabled==false}
            prove(!bridge.homeSettings.first().androidNativeLiquidGlassEnabled,"same actual global store disables original renderer")
            pointers+=ui.pointers;cases+="$style-original-native-liquid-store-toggle-and-recorded-sample"
        } finally {alive=false;scene.close();lifecycle.lifecycle.currentState=Lifecycle.State.DESTROYED}
    }
    val shader=RuntimeShader("""uniform float2 size; layout(color) uniform half4 color; uniform float radius; uniform float2 position; half4 main(float2 coord) { float dist=distance(coord,position); float intensity=smoothstep(radius,radius*0.5,dist); return color*intensity; }""")
    shader.setFloatUniform("size",10f,10f);shader.setColorUniform("color",Color.White);shader.setFloatUniform("radius",4f);shader.setFloatUniform("position",5f,5f)
    prove(top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported(),"actual existing Skiko shader API supports original InteractiveHighlight uniforms")
    val output=buildJsonObject {put("status","PASS");put("assertions",assertions);put("caseCount",cases.size);put("cases",JsonArray(cases.map{JsonPrimitive(it)}));put("pointerPairs",pointers);put("actualRecordedLayerSamples",samples);put("actualCodeSources",JsonArray(identities));put("noMainEdits",true);put("noHTTP",true);put("noHWND",true);put("offscreenActualComposeScene",true);put("preparedOnly",true);put("notRootMountedNativeWindowOrAndroidPixelEquivalence",true)}
    Files.writeString(out.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),output)+"\n");println(output)
}
