@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.settings
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.android.purebilibili.feature.settings.*
import com.android.purebilibili.core.theme.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopOriginalHomePreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*

private class FullUI(context:kotlin.coroutines.CoroutineContext,private val debug:Path) {
    private val job=SupervisorJob();private val errors=mutableListOf<Throwable>()
    private val scope=CoroutineScope(context+job+CoroutineExceptionHandler{_,e->errors+=e})
    val scene=ImageComposeScene(900,720,coroutineContext=scope.coroutineContext)
    var nanos=0L;val pointers=mutableListOf<JsonObject>()
    fun nodes():List<SemanticsNode> {
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap{walk(it.unmergedRootSemanticsNode)}
    }
    fun text(n:SemanticsNode,label:String)=n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true
    fun visible(n:SemanticsNode)=n.boundsInRoot.width>0&&n.boundsInRoot.top>=0&&n.boundsInRoot.bottom<=720
    suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();yield();delay(3);check(errors.isEmpty()){errors.toString()}}
    suspend fun await(label:String,condition:()->Boolean){try{withTimeout(6000){while(!condition())frame()};repeat(4){frame()}}catch(e:TimeoutCancellationException){error("$label: "+nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.joinToString{it.text})}}
    suspend fun focus(id:String){SettingsSearchFocusController.submit(SettingsSearchTarget.BOTTOM_BAR,id);await("focus $id"){SettingsSearchFocusController.request.value==null}}
    suspend fun point(label:String,p:Offset){
        pointers+=buildJsonObject{put("label",label);put("x",p.x);put("y",p.y)}
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+50,buttons=PointerButtons())
        repeat(10){frame()}
    }
    suspend fun click(label:String){await("click $label"){nodes().any{visible(it)&&text(it,label)}};point(label,nodes().first{visible(it)&&text(it,label)}.boundsInRoot.center)}
    suspend fun scrollTo(label:String){repeat(80){
        if(nodes().any{visible(it)&&text(it,label)})return
        val target=nodes().firstOrNull{text(it,label)}
        // Compose desktop wheel units scale to lines, not pixels; approach the target without overshooting its row.
        val delta=target?.let { ((it.positionInRoot.y-250f)/36f).coerceIn(-4f,4f) } ?: 4f
        scene.sendPointerEvent(PointerEventType.Scroll,Offset(790f,600f),scrollDelta=Offset(0f,delta),timeMillis=nanos/1_000_000)
        pointers+=buildJsonObject{put("label","wheel to $label");put("deltaY",delta);put("targetTop",target?.positionInRoot?.y?.toString())};repeat(7){frame()}
    };save(debug.resolve("failed-scroll.png"));Files.writeString(debug.resolve("failed-scroll.semantics.txt"),nodes().joinToString("\n"){"${it.boundsInRoot} ${it.config}"});Files.writeString(debug.resolve("failed-pointers.json"),JsonArray(pointers).toString());error("Cannot scroll to $label: "+nodes().filter{text(it,label)}.joinToString{it.boundsInRoot.toString()})}
    fun save(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
    suspend fun close(){scene.close();job.cancelAndJoin()}
}
fun main(args:Array<String>):Unit=runBlocking{
    val output=Path.of(args[0]).resolve("ui");Files.createDirectories(output)
    val results=mutableListOf<JsonObject>()
    for(style in AppUiStyle.entries){
        val dir=output.resolve(style.name.lowercase());Files.createDirectories(dir)
        val store=DesktopPluginStore(dir.resolve("global"));val context=DesktopPluginContext(store)
        val themePreferences=DesktopThemePrefs(store)
        themePreferences.setUiStyle(style);themePreferences.setThemeMode(AppThemeMode.LIGHT)
        var target by mutableStateOf(SettingsSearchTarget.BOTTOM_BAR)
        var observedConfig:com.android.purebilibili.core.ui.AppThemeConfig?=null
        val card=DesktopHomeCardPreferences(context);val job=SupervisorJob();val homeScope=CoroutineScope(coroutineContext+job)
        val home=DesktopOriginalHomePreferences.create(store,homeScope,false){false};val errors=mutableListOf<Throwable>();val ui=FullUI(coroutineContext,dir)
        try{
            ui.scene.setContent{
                val theme by themePreferences.settings.collectAsState(themePreferences.initialSettings())
                DesktopAppearanceTheme(theme){
                    val config=com.android.purebilibili.core.ui.LocalAppThemeConfig.current
                    SideEffect{observedConfig=config}
                    CompositionLocalProvider(LocalDesktopHomeCardPreferences provides card){DesktopNavigationInteractionSettings(target,onFailure={errors+=it})}
                }
            }
            ui.await("first original page"){ui.nodes().any{ui.text(it,"导航行为")}}
            ui.focus(SettingsSearchFocusIds.BOTTOM_BAR_DISPLAY)
            ui.click("标签样式");ui.click("仅图标")
            ui.await("real label mode") { home.homeSettings.value.bottomBarLabelMode==1 }
            ui.save(dir.resolve("bottom-display.png"))
            ui.focus(SettingsSearchFocusIds.BOTTOM_BAR_TOP_TABS)
            ui.click("首页右上角入口");ui.click("消息")
            ui.await("real right action") { home.homeSettings.value.homeTopRightAction==com.android.purebilibili.core.store.HomeTopRightAction.INBOX }
            ui.scrollTo("已显示（上下按钮可排序）")
            ui.await("top first move button") { ui.nodes().any{n->ui.visible(n)&&n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("下移")==true} }
            val down=ui.nodes().first{n->ui.visible(n)&&n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("下移")==true}
            ui.point("first top tab down",down.boundsInRoot.center)
            ui.await("real top order"){home.topTabs.value.orderIds.take(2)==listOf("FOLLOW","RECOMMEND")}
            ui.save(dir.resolve("top-sorted.png"))
            ui.focus(SettingsSearchFocusIds.BOTTOM_BAR_SEARCH_TABS)
            ui.scrollTo("分类顺序（上下按钮可排序）")
            val searchDown=ui.nodes().first{n->ui.visible(n)&&n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("下移")==true}
            ui.point("first search tab down",searchDown.boundsInRoot.center)
            ui.await("real search order") { store.snapshot("settings").value[fullNavigationStringKey("search_filter_tab_order")]?.startsWith("media_bangumi,video")==true }
            ui.focus(SettingsSearchFocusIds.BOTTOM_BAR_AVAILABLE)
            ui.scrollTo("动态");ui.click("动态")
            ui.await("original label dialog"){ui.nodes().any{ui.text(it,"自定义动态文字")}}
            val field=ui.nodes().first{it.config.getOrNull(SemanticsActions.SetText)!=null}
            check(field.config.getOrNull(SemanticsActions.SetText)!!.action!!.invoke(AnnotatedString("我的动态")))
            ui.click("保存")
            ui.await("Root label consumer") { home.navigation.value.bottomBarItemLabels["DYNAMIC"]=="我的动态" }
            ui.save(dir.resolve("label-saved.png"))
            ui.focus(SettingsSearchFocusIds.BOTTOM_BAR_TABLET)
            ui.click("侧边导航栏")
            ui.await("actual sidebar consumer") { home.navigation.value.tabletUseSidebar }
            ui.click("侧边栏账号切换")
            ui.await("actual sidebar account action consumer") { !home.navigation.value.sidebarAccountSwitcherEnabled }
            // Actual original detectDragGesturesAfterLongPress overlay, not a direct order setter.
            com.android.purebilibili.core.store.DesktopOriginalFullNavigationSettings.setBottomBarLabelMode(context,0)
            ui.focus(SettingsSearchFocusIds.BOTTOM_BAR_CURRENT)
            ui.await("bottom drag preview") { ui.nodes().any{ui.visible(it)&&ui.text(it,"推荐")} && ui.nodes().any{ui.visible(it)&&ui.text(it,"历史")} }
            val from=ui.nodes().first{ui.visible(it)&&ui.text(it,"推荐")}.boundsInRoot.center
            val to=ui.nodes().first{ui.visible(it)&&ui.text(it,"历史")}.boundsInRoot.center
            ui.pointers+=buildJsonObject{put("label","original bottom longpress drag");put("from",from.toString());put("to",to.toString())}
            ui.scene.sendPointerEvent(PointerEventType.Press,from,timeMillis=ui.nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
            delay(650);repeat(12){ui.frame()}
            ui.scene.sendPointerEvent(PointerEventType.Move,to,timeMillis=ui.nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
            repeat(8){ui.frame()}
            ui.scene.sendPointerEvent(PointerEventType.Release,to,timeMillis=ui.nanos/1_000_000+50,buttons=PointerButtons())
            ui.await("actual bottom original drag commits") { home.navigation.value.orderedVisibleTabIds.take(3)==listOf("DYNAMIC","HISTORY","HOME") }
            ui.save(dir.resolve("bottom-drag-sorted.png"))
            target=SettingsSearchTarget.ANIMATION
            ui.await("actual original animation page") { ui.nodes().any{ui.visible(it)&&ui.text(it,"界面入场动画")} }
            ui.click("界面入场动画")
            ui.await("real entrance config") { observedConfig?.uiEntranceAnimationEnabled==false }
            ui.scrollTo("进场动画");ui.click("进场动画")
            ui.await("actual card motion observer") { home.homeSettings.value.cardAnimationEnabled }
            ui.scrollTo("全局导航动画");ui.click("全局导航动画");ui.click("缩放")
            ui.await("actual Root host navigation style") { home.navigation.value.predictiveBackAnimationStyle=="scale" }
            ui.scrollTo("视频转场速度：标准");ui.click("视频转场速度：标准");ui.click("慢速")
            ui.await("actual Root transition speed") { home.homeSettings.value.videoSharedTransitionSpeed==com.android.purebilibili.core.ui.transition.VideoSharedTransitionSpeed.SLOW }
            ui.save(dir.resolve("animation-speed.png"))
            SettingsSearchFocusController.submit(SettingsSearchTarget.ANIMATION,SettingsSearchFocusIds.ANIMATION_VISUAL_EFFECTS)
            ui.await("visual focus consumed") { SettingsSearchFocusController.request.value==null }
            ui.click("骨架呼吸动画")
            ui.await("actual skeleton key") { store.snapshot("settings").value[fullNavigationBooleanKey("skeleton_breathing_enabled")]==false }
            ui.click("顶部渐进模糊")
            ui.await("actual projected progressive config") { observedConfig?.let{it.progressiveTopBlurEnabled && !it.headerBlurEnabled}==true }
            ui.click("顶部栏磨砂")
            ui.await("original exclusion and projected header config") { observedConfig?.let{it.headerBlurEnabled && !it.progressiveTopBlurEnabled}==true }
            ui.click("顶栏纯色渐变")
            ui.await("actual fade config") { observedConfig?.progressiveTopFadeEnabled==false }
            ui.scrollTo("模糊强度");ui.click("模糊强度");ui.click("重度")
            ui.await("actual projected intensity") { observedConfig?.blurIntensity==com.android.purebilibili.core.ui.blur.BlurIntensity.APPLE_DOCK }
            ui.save(dir.resolve("animation-visual-effects.png"))
            check(errors.isEmpty()){errors.toString()}
            Files.writeString(dir.resolve("pointers.json"),JsonArray(ui.pointers).toString())
            Files.writeString(dir.resolve("semantics.txt"),ui.nodes().joinToString("\n"){"${it.boundsInRoot} ${it.config}"})
            Files.writeString(dir.resolve("settings-disk.json"),store.preferences("settings").toString())
            results+=buildJsonObject{put("style",style.name);put("passed",true);put("pointerEvents",ui.pointers.size);put("originalEditor",true);put("originalGlobalReader",true);put("viewport","900x720")}
        }finally{SettingsSearchFocusController.clear();ui.close();job.cancelAndJoin()}
    }
    Files.writeString(output.resolve("result.json"),buildJsonObject{put("passed",true);put("cases",JsonArray(results));put("wholeRootEffectVerified",false);put("nativeWindowCreated",false)}.toString())
    println("2 styles original full BottomBar editor: finite scene real click/wheel, label dialog text, exact setters, disk and actual Home consumer passed")
    Unit
}
