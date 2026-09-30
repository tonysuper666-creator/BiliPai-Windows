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
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.store.DesktopDynamicSettings.DynamicFeedLayoutMode
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

private class DynamicScene(val scene:ImageComposeScene) {
    var nanos=0L;var pointerPairs=0
    fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
    fun texts()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
    fun text(label:String)=nodes().filter{it.config.getOrNull(SemanticsProperties.Text)?.any{v->v.text==label}==true}
    suspend fun frame(){nanos+=30_000_000L;scene.render(nanos).close();delay(5)}
    suspend fun waitFor(label:String,ready:()->Boolean){try{withTimeout(4000){while(!ready())frame()};repeat(20){frame()}}
        catch(error:TimeoutCancellationException){error("$label; actual labels=${texts()}")}}
    suspend fun click(label:String){val target=text(label).lastOrNull{it.boundsInRoot.center.y in 1f..898f}?:error("No $label: ${texts()}")
        val p=target.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
        scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());pointerPairs++;repeat(12){frame()}}
    fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}

fun main(args:Array<String>):Unit=runBlocking {
    val output=Path.of(args[0]);assertActualDynamicProductOrigins(output);Files.createDirectories(output);val checks=mutableListOf<JsonObject>()
    val origin=Path.of(DesktopPluginStore::class.java.protectionDomain.codeSource.location.toURI()).fileName.toString()
    assertEquals("main-kotlin.jar",origin,"Root persistence must remain actual product rather than test override")
    for(style in AppUiStyle.entries) for(dark in listOf(false,true)) for(width in listOf(800,1200)) {
        val caseName="${style.name.lowercase()}-${if(dark)"dark"else"light"}-$width"
        val root=Files.createTempDirectory("dynamic-original-ui-");val store=DesktopPluginStore(root)
        val theme=DesktopThemePrefs(store);theme.setUiStyle(style);theme.setThemeMode(if(dark)com.android.purebilibili.core.theme.AppThemeMode.DARK else com.android.purebilibili.core.theme.AppThemeMode.LIGHT)
        val preferences=DesktopDynamicTimelinePreferences(DesktopPluginContext(store))
        val repository=DesktopRepository(DesktopSessionStore.temporary())
        val community=DesktopCommunityRepository(repository,DesktopBlockedUpStore(DesktopPluginContext(store)))
        val navigation=CommunityNavigation({},{},{},{},{},{},{})
        var request=0;val transportCalls=mutableListOf<Triple<String,String,String>>()
        val replies=listOf(DynamicFeedData(listOf(fixtureDynamic("3",30),fixtureDynamic("2",20),fixtureDynamic("1",10)),"old-tail",true,"3"),
            DynamicFeedData(listOf(fixtureDynamic("4",40),fixtureDynamic("3",30)),"fresh-tail",true,"4",1),
            DynamicFeedData(listOf(fixtureDynamic("9",90)),"",false,"9",0))
        val state=DesktopDynamicTimelineState("all",{type,offset,baseline->transportCalls+=Triple(type,offset,baseline);DynamicFeedResponse(data=replies[request++])})
        assertTrue(state.fetch(true,false))
        val scene=ImageComposeScene(width=width,height=900,coroutineContext=coroutineContext);val ui=DynamicScene(scene)
        try {
            scene.setContent {
                val appearance by theme.settings.collectAsState(theme.initialSettings())
                DesktopAppearanceTheme(appearance) {
                    AppSurface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            DesktopDynamicTimelineSettings(preferences,{throw it},Modifier.fillMaxWidth())
                            Box(Modifier.weight(1f)) {DesktopDynamicTimelineFeed(state,preferences,{},row={CommunityDynamicCard(it,community,navigation)})}
                        }
                    }
                }
            }
            ui.waitFor("actual original settings and dynamic cards") {"动态页面布局" in ui.texts()&&listOf("Dynamic-3","Dynamic-2","Dynamic-1").all{it in ui.texts()}}
            val waterfallXs=listOf("Dynamic-3","Dynamic-2","Dynamic-1").map{ui.text(it).single().boundsInRoot.center.x.toInt()}
            assertTrue(waterfallXs.distinct().size>=2,"Original adaptive minimum-column-width must produce real multi-column geometry")
            ui.screenshot(output.resolve("${caseName}-waterfall.png"))
            ui.click("动态页面布局");ui.waitFor("original popup contains list option"){"列表" in ui.texts()}
            ui.screenshot(output.resolve("${caseName}-layout-popup.png"));ui.click("列表")
            ui.waitFor("actual persisted LIST reaches actual consumer") {runBlocking{preferences.layoutMode.first()}==DynamicFeedLayoutMode.LIST&&
                listOf("Dynamic-3","Dynamic-2","Dynamic-1").all{it in ui.texts()}}
            val listXs=listOf("Dynamic-3","Dynamic-2","Dynamic-1").map{ui.text(it).single().boundsInRoot.center.x.toInt()}
            assertEquals(1,listXs.distinct().size,"LIST must actually be a single lane")
            assertEquals(1,transportCalls.size,"Changing layout must preserve actual source rows without HTTP reload")
            ui.screenshot(output.resolve("${caseName}-list.png"))
            ui.click("刷新时保留当前列表")
            ui.waitFor("original switch commits real incremental key"){runBlocking{preferences.incrementalRefresh.first()}}
            ui.click("刷新");ui.waitFor("real consumer applies original incremental merge"){state.page.items.size==4&&!state.busy}
            assertEquals(listOf("4","3","2","1"),state.page.items.map{it.id_str})
            assertEquals(Triple("all","","3"),transportCalls.last());assertEquals("3",state.page.incrementalRefreshBoundaryKey)
            // The original list anchoring is retained. Scroll the actual source semantics to the
            // top only for capturing the new-card/divider pixels, not as a replacement algorithm.
            val scroll=ui.nodes().firstOrNull{it.config.getOrNull(SemanticsActions.ScrollBy)?.action!=null}
            assertNotNull(scroll);assertTrue(scroll.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f,-900f))
            repeat(20){ui.frame()};ui.waitFor("actual original old-content divider rendered"){"上次刷新到这里" in ui.texts()}
            ui.screenshot(output.resolve("${caseName}-incremental-divider.png"))
            ui.click("刷新时保留当前列表");ui.waitFor("original switch commits false"){!runBlocking{preferences.incrementalRefresh.first()}}
            ui.click("刷新");ui.waitFor("actual disabled preference replaces old list") {state.page.items.map{it.id_str}==listOf("9")&&!state.busy}
            assertEquals(Triple("all","",""),transportCalls.last());assertNull(state.page.incrementalRefreshBoundaryKey)
            assertFalse("上次刷新到这里" in ui.texts());ui.screenshot(output.resolve("${caseName}-replace.png"))
            val document=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
            assertEquals(1,document["settings"]!!.jsonObject["dynamic_feed_layout_mode"]!!.jsonPrimitive.int)
            assertFalse(document["settings"]!!.jsonObject["incremental_timeline_refresh"]!!.jsonPrimitive.boolean)
            checks+=buildJsonObject {put("style",style.name);put("dark",dark);put("width",width);put("actualPointerPairs",ui.pointerPairs);put("passed",true);put("actualPointerPressRelease",true);put("originalPopup",true)
                put("waterfallCenters",JsonArray(waterfallXs.map(::JsonPrimitive)));put("listCenters",JsonArray(listXs.map(::JsonPrimitive)))
                put("realStoreDiskKeys",true);put("layoutChangesRequestCount",0);put("transportCallbackCalls",JsonArray(transportCalls.map{JsonArray(listOf(it.first,it.second,it.third).map(::JsonPrimitive))}))
                put("originalMergeAndDividerConsumer",true);put("realAccount",false);put("HTTP",false);put("HWND",false)}
            println("PASS: $style actual original switch/popup pointer → shared disk → real geometry/baseline/merge/divider/replacement consumer")
        }finally{scene.close()}
    }
    Files.writeString(output.resolve("ui-result.json"),JsonArray(checks).toString())
}
