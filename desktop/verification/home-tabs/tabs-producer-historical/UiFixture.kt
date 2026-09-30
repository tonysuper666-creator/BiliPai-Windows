@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.*
import kotlin.test.*

private class TabsScene(val scene:ImageComposeScene){
 var nanos=0L
 fun nodes():List<SemanticsNode>{fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
  return scene.semanticsOwners.flatMap{tree(it.unmergedRootSemanticsNode)}}
 fun labels()=nodes().flatMap{it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map{it.text}
 fun text(label:String)=nodes().filter{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true}
 suspend fun frame(){nanos+=30_000_000;scene.render(nanos).close();delay(5)}
 suspend fun await(label:String,ready:()->Boolean){try{withTimeout(5000){while(!ready())frame()};repeat(12){frame()}}catch(error:TimeoutCancellationException){error("$label; actual labels=${labels()}")}}
 suspend fun press(node:SemanticsNode){val p=node.boundsInRoot.center
  scene.sendPointerEvent(PointerEventType.Press,p,timeMillis=nanos/1_000_000,buttons=PointerButtons(isPrimaryPressed=true))
  scene.sendPointerEvent(PointerEventType.Release,p,timeMillis=nanos/1_000_000+55,buttons=PointerButtons());repeat(16){frame()}}
 suspend fun clickSettings(label:String)=press(text(label).firstOrNull{it.boundsInRoot.center.y<850}?:error("No settings $label"))
 suspend fun clickHost(label:String)=press(text(label).lastOrNull{it.boundsInRoot.center.y>850}?:error("No host $label: ${labels()}"))
 suspend fun description(label:String)=press(nodes().first{it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true})
 fun screenshot(path:Path){scene.render(nanos+1).use{Files.write(path,it.encodeToData(EncodedImageFormat.PNG)!!.bytes)}}
}

fun main(args:Array<String>):Unit=runBlocking {
 val output=Path.of(args[0]);Files.createDirectories(output);val checks=mutableListOf<JsonObject>()
 assertEquals("main-kotlin.jar",Path.of(DesktopPluginStore::class.java.protectionDomain.codeSource.location.toURI()).fileName.toString())
 for(type in listOf(DesktopDynamicTimelineState::class.java,com.android.purebilibili.data.repository.DesktopOriginalDynamicTimelineRepository::class.java,
     com.android.purebilibili.core.store.DesktopDynamicSettings::class.java))
  assertEquals("main-kotlin.jar",Path.of(type.protectionDomain.codeSource.location.toURI()).fileName.toString(),"No prior timeline source override")
 for(style in AppUiStyle.entries){
  val store=DesktopPluginStore(Files.createTempDirectory("tabs-ui-store-"));val context=DesktopPluginContext(store)
  val theme=DesktopThemePrefs(store);theme.setUiStyle(style)
  val preferences=DesktopDynamicTabsPreferences(context);val timelinePreferences=DesktopDynamicTimelinePreferences(context)
  val timelineCalls=mutableListOf<String>();val uidCalls=mutableListOf<Map<String,String>>()
  val users=DesktopDynamicUsersState(this,preferences,42,{FollowingsData(listOf(FollowingUser(77,"User-77",""),FollowingUser(78,"User-78","")),2)},
   {emptyList()},{Json.decodeFromString("""{"items":[{"has_update":1,"user_profile":{"info":{"uid":77}}}]}""")},
   {params->uidCalls+=params;tabsResponse(listOf(tabsDynamic("selected-77",77)))},{true})
  val timelines=mutableMapOf<String,DesktopDynamicTimelineState>()
  fun timeline(type:String)=timelines.getOrPut(type){DesktopDynamicTimelineState(type,{kind,_,_->timelineCalls+=kind;tabsResponse(listOf(tabsDynamic("$kind-card")))})}
  val scene=ImageComposeScene(width=1200,height=1600,coroutineContext=coroutineContext);val ui=TabsScene(scene)
  try{
   scene.setContent{
    val appearance by theme.settings.collectAsState(theme.initialSettings())
    DesktopAppearanceTheme(appearance){AppSurface(Modifier.fillMaxSize()){
     Column(Modifier.fillMaxSize()){
      Box(Modifier.height(850.dp).fillMaxWidth()){DesktopDynamicTabsSettings(preferences,{throw it})}
      Box(Modifier.weight(1f)){DesktopDynamicTabsHost(users,timelinePreferences,{}, {},{it},::timeline,row={AppText("Dynamic-${it.id_str}")})}
     }
    }}
   }
   ui.await("actual original controls and ALL source"){"动态栏位显示" in ui.labels()&&"Dynamic-all-card" in ui.labels()&&users.users.size==2}
   assertEquals(listOf("all"),timelineCalls);assertFalse(users.users.isEmpty())
   ui.screenshot(output.resolve("${style.name.lowercase()}-default-tabs.png"))
   ui.clickSettings("“全部”页显示关注用户栏")
   ui.await("real key makes original horizontal rail visible"){runBlocking{preferences.allTabUsers.first()}&&"User-77" in ui.labels()}
   ui.screenshot(output.resolve("${style.name.lowercase()}-actual-following-rail.png"))
   ui.clickHost("专栏");ui.await("original article logical ID produces article API type"){users.selectedLogicalTab==3&&"Dynamic-article-card" in ui.labels()}
   assertEquals(listOf("all","article"),timelineCalls)
   val beforeX=ui.text("专栏").last().boundsInRoot.center.x
   ui.description("上移专栏")
   ui.await("reorder persists and moves actual tab geometry"){
    runBlocking{preferences.tabOrder.first()}.indexOf("article")==2&&ui.text("专栏").last().boundsInRoot.center.x!=beforeX}
   assertEquals(3,users.selectedLogicalTab);assertEquals(2,timelineCalls.size)
   ui.screenshot(output.resolve("${style.name.lowercase()}-article-reordered.png"))
   ui.clickSettings("专栏");ui.await("hide selected tab restores ALL logical identity"){users.selectedLogicalTab==0&&"Dynamic-all-card" in ui.labels()}
   assertEquals(0,preferences.selectedTab);assertEquals(2,timelineCalls.size)
   ui.clickHost("UP");ui.await("original UP rail stays visible even ALL toggle later off"){users.selectedLogicalTab==4&&"User-77" in ui.labels()}
   ui.clickHost("User-77");ui.await("actual avatar click requests MID space feed"){uidCalls.size==1&&!users.userLoading&&"Dynamic-selected-77" in ui.labels()}
   assertEquals("77",uidCalls.single()["host_mid"]);assertEquals(77L,users.selectedUid)
   ui.screenshot(output.resolve("${style.name.lowercase()}-selected-up.png"))
   ui.clickSettings("“全部”页显示关注用户栏");ui.await("UP remains visible with ALL-only key false"){!runBlocking{preferences.allTabUsers.first()}&&"User-77" in ui.labels()}
   ui.clickSettings("UP");ui.await("hide UP falls back and removes scoped remote rows"){users.selectedLogicalTab==0&&users.selectedUid==null&&"Dynamic-selected-77" !in ui.labels()}
   ui.screenshot(output.resolve("${style.name.lowercase()}-up-hidden-restored-all.png"))
   val originalCalls=uidCalls.size;var opened=0L;users.selectUser(78){opened=it}
   assertEquals(78L,opened);assertEquals(originalCalls,uidCalls.size)
   checks+=buildJsonObject{put("style",style.name);put("passed",true);put("actualPointer",true);put("actualSettings",true)
    put("actualOriginalNativeTabsAndUserRail",true);put("reorderPreservesSource",true);put("hideRestoresLogicalTab",true)
    put("requests",JsonArray(timelineCalls.map(::JsonPrimitive)));put("selectedUserRequest",JsonObject(uidCalls.single().mapValues{JsonPrimitive(it.value)}))}
  }finally{users.close();scene.close()}
 }
 Files.writeString(output.resolve("ui-result.json"),JsonArray(checks).toString())
 println("PASS: both real themes, original settings switches/order arrows, native tabs and horizontal UP rail; real pointer, selected MID and request-type consumer")
}
